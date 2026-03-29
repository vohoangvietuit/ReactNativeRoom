package com.reactnativeroom.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.reactnativeroom.database.CaroMove
import com.reactnativeroom.repository.CaroRepository
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("MissingPermission")
class CaroBleService : Service() {

    companion object {
        private const val TAG = "CaroBleService"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter get() = bluetoothManager.adapter

    // GATT Server (Host mode)
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var challengerDevice: BluetoothDevice? = null
    private var isHosting = false

    // GATT Client (Join mode)
    private var clientGatt: BluetoothGatt? = null
    private var isJoined = false

    // Repository reference — set by the TurboModule
    var repository: CaroRepository? = null
    var gameId: String = ""

    // Set to true while a game is in progress (used for reconnect logic)
    var isGamePlaying = false

    // Callbacks — set by the TurboModule to push events to JS
    var onMoveReceived: ((CaroMove) -> Unit)? = null
    var onPlayerConnected: ((role: String, deviceId: String, deviceName: String) -> Unit)? = null
    var onPlayerDisconnected: (() -> Unit)? = null
    var onGameControlMessage: ((String) -> Unit)? = null

    // Heartbeat (host sends every 5 s while game is playing)
    private var heartbeatJob: Job? = null

    // Watchdog (joiner fires PLAYER_LEFT if no HEARTBEAT for 20 s)
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "Heartbeat watchdog expired — treating as host disconnect")
        onGameControlMessage?.invoke("PLAYER_LEFT")
        onPlayerDisconnected?.invoke()
    }

    // Last host device seen — used for auto-reconnect
    private var lastHostDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): CaroBleService = this@CaroBleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, BleConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Caro Game")
            .setContentText("Bluetooth game session active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startForeground(
                    BleConstants.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(BleConstants.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BleConstants.NOTIFICATION_CHANNEL_ID,
            "Caro BLE Game",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bluetooth game sync service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    // ══════════════════════════════════════════════════════════════════════
    // HOST MODE — GATT Server
    // ══════════════════════════════════════════════════════════════════════

    fun startHosting() {
        if (isHosting) return

        val btAdapter = adapter
            ?: throw UnsupportedOperationException("Bluetooth is not available on this device")
        val leAdvertiser = btAdapter.bluetoothLeAdvertiser
            ?: throw UnsupportedOperationException("BLE advertising is not supported on this device or emulator")

        isHosting = true

        // Start advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.CARO_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        leAdvertiser.startAdvertising(settings, data, advertiseCallback)

        // Setup GATT server
        setupGattServer()
        Log.d(TAG, "Host started advertising")
    }

    fun stopHosting() {
        if (!isHosting) return
        stopHeartbeat()
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        challengerDevice = null
        isHosting = false
        isGamePlaying = false
        Log.d(TAG, "Host stopped")
    }

    /** Start sending HEARTBEAT to all clients every 5 seconds. Call after GAME_START. */
    fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && isGamePlaying) {
                delay(5_000)
                if (isGamePlaying) broadcastControl(BleConstants.MSG_HEARTBEAT)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            isHosting = false
        }
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val service = BluetoothGattService(
            BleConstants.CARO_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // NOTIFY — push new moves to all connected clients
        val notifyChar = BluetoothGattCharacteristic(
            BleConstants.MOVE_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            ))
        }

        // WRITE — challenger sends moves here
        val writeChar = BluetoothGattCharacteristic(
            BleConstants.MOVE_WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // READ — full game history for late joiners
        val readChar = BluetoothGattCharacteristic(
            BleConstants.FULL_SYNC_READ_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // CONTROL — game state messages (start, reset, etc.)
        val controlChar = BluetoothGattCharacteristic(
            BleConstants.GAME_CONTROL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            ))
        }

        service.addCharacteristic(notifyChar)
        service.addCharacteristic(writeChar)
        service.addCharacteristic(readChar)
        service.addCharacteristic(controlChar)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                val role = if (challengerDevice == null) "challenger" else "spectator"
                if (role == "challenger") challengerDevice = device
                val name = device.name ?: device.address
                onPlayerConnected?.invoke(role, device.address, name)
                Log.d(TAG, "Device connected as $role: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                if (device == challengerDevice) {
                    challengerDevice = null
                    // Notify any remaining connected devices that the challenger left
                    broadcastControl("PLAYER_LEFT")
                }
                onPlayerDisconnected?.invoke()
                Log.d(TAG, "Device disconnected: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                BleConstants.FULL_SYNC_READ_CHAR_UUID -> {
                    // Late-joiner requesting full board
                    scope.launch {
                        val moves = repository?.getBoard() ?: emptyList()
                        val json = Json.encodeToString(moves)
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        val chunk = if (offset < bytes.size) {
                            bytes.copyOfRange(offset, minOf(offset + BleConstants.MTU_SIZE - 1, bytes.size))
                        } else {
                            ByteArray(0)
                        }
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
                    }
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            when (characteristic.uuid) {
                BleConstants.MOVE_WRITE_CHAR_UUID -> {
                    // Challenger sent a move
                    scope.launch {
                        try {
                            val json = String(value, Charsets.UTF_8)
                            val move = Json.decodeFromString<CaroMove>(json)
                            // Host validates and applies the move
                            val result = repository?.placeMove(move.x, move.y, move.playerSymbol, gameId)
                            if (result?.success == true) {
                                // Broadcast to all connected clients (including challenger confirmation)
                                broadcastMove(move)
                                onMoveReceived?.invoke(move)
                            } else {
                                Log.e(TAG, "Host rejected move ${move.x},${move.y} from ${device.address}: ${result?.error}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process remote move", e)
                        }
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                BleConstants.GAME_CONTROL_CHAR_UUID -> {
                    val message = String(value, Charsets.UTF_8)
                    onGameControlMessage?.invoke(message)
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            // Client subscribing to notifications
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    /**
     * Host broadcasts a move to all connected devices via NOTIFY
     */
    fun broadcastMove(move: CaroMove) {
        val json = Json.encodeToString(move)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val server = gattServer ?: return
        val notifyChar = server
            .getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.MOVE_NOTIFY_CHAR_UUID) ?: return

        connectedDevices.forEach { device ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, notifyChar, false, bytes)
                } else {
                    @Suppress("DEPRECATION")
                    notifyChar.value = bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, notifyChar, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send move to ${device.address}", e)
            }
        }
        Log.d(TAG, "Broadcast move to ${connectedDevices.size} devices")
    }

    /**
     * Host broadcasts a game control message to all connected devices
     */
    fun broadcastControl(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val server = gattServer ?: return
        val controlChar = server
            .getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID) ?: return

        connectedDevices.forEach { device ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, controlChar, false, bytes)
                } else {
                    @Suppress("DEPRECATION")
                    controlChar.value = bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, controlChar, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send control message to ${device.address}", e)
            }
        }
    }

    fun getConnectedCount(): Int = connectedDevices.size
    fun hasChallengerConnected(): Boolean = challengerDevice != null
    /** True when the GATT client has an active connection to a host. */
    fun isConnectedToHost(): Boolean = isJoined

    /** Disconnects (bans) the current challenger from the GATT server. */
    fun disconnectChallenger() {
        challengerDevice?.let { dev ->
            gattServer?.cancelConnection(dev)
        }
    }

    /**
     * Manually trigger a reconnect attempt to the last known host.
     * Called by the user tapping the "Reconnect" button in the UI.
     * If already joined, does nothing. Cancels any in-flight reconnect job and
     * starts a fresh one-shot attempt (no retry loop — user can tap again).
     */
    fun reconnect() {
        if (isJoined) return
        val host = lastHostDevice ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            Log.d(TAG, "Manual reconnect attempt to ${host.address}")
            val prev = clientGatt
            prev?.close()
            clientGatt = host.connectGatt(this@CaroBleService, false, clientGattCallback)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // JOIN MODE — GATT Client
    // ══════════════════════════════════════════════════════════════════════

    // Hold a reference so stopScanning can cancel the same scan
    private var activeScanCallback: ScanCallback? = null

    fun startScanning(onDeviceFound: (BluetoothDevice) -> Unit) {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.CARO_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val leScanner = (adapter
            ?: throw UnsupportedOperationException("Bluetooth is not available on this device"))
            .bluetoothLeScanner
            ?: throw UnsupportedOperationException("BLE scanning is not supported on this device or emulator")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }
        }
        activeScanCallback = cb
        leScanner.startScan(listOf(filter), settings, cb)
        Log.d(TAG, "Started scanning for Caro games")
    }

    fun stopScanning() {
        val cb = activeScanCallback ?: return
        adapter?.bluetoothLeScanner?.stopScan(cb)
        activeScanCallback = null
        Log.d(TAG, "Stopped scanning")
    }

    fun connectToHost(device: BluetoothDevice) {
        lastHostDevice = device
        clientGatt = device.connectGatt(this, false, clientGattCallback)
    }

    fun disconnectFromHost() {
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogHandler.removeCallbacks(watchdogRunnable)
        clientGatt?.disconnect()
        clientGatt?.close()
        clientGatt = null
        isJoined = false
        isGamePlaying = false
    }

    /**
     * Challenger sends a game control message to the host via WRITE on GAME_CONTROL char.
     */
    fun sendControlToHost(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        enqueueGattOp {
            val gatt = clientGatt
            val controlChar = gatt
                ?.getService(BleConstants.CARO_SERVICE_UUID)
                ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID)
            if (gatt != null && controlChar != null) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        controlChar, bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    controlChar.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(controlChar) == true
                }
                if (!ok) {
                    Log.e(TAG, "writeCharacteristic failed for control message: $message")
                    onGattOpComplete()
                }
                // else: onCharacteristicWrite callback will call onGattOpComplete()
            } else {
                onGattOpComplete()
            }
        }
    }

    /** Start the heartbeat watchdog (reset every time a HEARTBEAT is received). */
    fun resetWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        if (isGamePlaying) watchdogHandler.postDelayed(watchdogRunnable, 20_000)
    }

    fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    /**
     * Client sends a move to the Host via WRITE characteristic
     */
    fun sendMoveToHost(move: CaroMove) {
        val json = Json.encodeToString(move)
        val bytes = json.toByteArray(Charsets.UTF_8)
        enqueueGattOp {
            val gatt = clientGatt
            val writeChar = gatt
                ?.getService(BleConstants.CARO_SERVICE_UUID)
                ?.getCharacteristic(BleConstants.MOVE_WRITE_CHAR_UUID)
            if (gatt != null && writeChar != null) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        writeChar, bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    writeChar.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(writeChar) == true
                }
                if (ok) {
                    Log.d(TAG, "Sent move to host: (${move.x}, ${move.y})")
                } else {
                    Log.e(TAG, "writeCharacteristic failed for move: (${move.x}, ${move.y})")
                    onGattOpComplete()
                }
            } else {
                onGattOpComplete()
            }
        }
    }

    // Pending GATT operations queue — Android BLE allows only one at a time
    private val pendingGattOps = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var gattBusy = false
    private var gattTimeoutJob: Job? = null

    private fun enqueueGattOp(op: () -> Unit) {
        pendingGattOps.add(op)
        drainGattQueue()
    }

    private var currentGattOpId = 0L

    private fun drainGattQueue() {
        synchronized(pendingGattOps) {
            if (gattBusy) return
            val next = pendingGattOps.poll() ?: return
            gattBusy = true
            
            val opId = ++currentGattOpId
            gattTimeoutJob?.cancel()
            gattTimeoutJob = scope.launch {
                delay(2000)
                if (currentGattOpId == opId) {
                    Log.w(TAG, "GATT queue operation timed out! Recovering queue...")
                    onGattOpComplete()
                }
            }
            
            try {
                next()
            } catch (e: Exception) {
                Log.e(TAG, "GATT operation failed with exception", e)
                gattBusy = false
                Handler(Looper.getMainLooper()).post { drainGattQueue() }
            }
        }
    }

    private fun onGattOpComplete() {
        gattTimeoutJob?.cancel()
        synchronized(pendingGattOps) {
            gattBusy = false
        }
        // Use post to avoid nested locks
        Handler(Looper.getMainLooper()).post { drainGattQueue() }
    }

    private val clientGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectJob?.cancel()
                reconnectJob = null
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                isJoined = true
                Log.d(TAG, "Connected to host, requesting MTU...")
                
                // Some Android devices fail or ignore requestMtu, which halts the whole process.
                // We request MTU, but also set a timeout to force discoverServices if it hangs.
                val mtuRequested = gatt.requestMtu(BleConstants.MTU_SIZE)
                if (!mtuRequested) {
                    Log.w(TAG, "requestMtu returned false, proceeding to discoverServices directly.")
                    scope.launch {
                        delay(600)
                        gatt.discoverServices()
                    }
                } else {
                    scope.launch {
                        delay(2000)
                        // If onMtuChanged didn't complete, it's safe to call discoverServices anyway
                        Log.w(TAG, "MTU callback timeout, proceeding to discoverServices.")
                        try {
                            gatt.discoverServices()
                        } catch (e: Exception) { }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isJoined = false
                pendingGattOps.clear()
                gattBusy = false
                stopWatchdog()
                if (isGamePlaying) {
                    // Try to reconnect up to 3 times before giving up
                    val hostDevice = lastHostDevice
                    if (hostDevice != null) {
                        reconnectJob = scope.launch {
                            onGameControlMessage?.invoke(BleConstants.MSG_RECONNECTING)
                            var retrys = 0
                            while (retrys < 3 && isActive) {
                                retrys++
                                Log.d(TAG, "Reconnect attempt $retrys/3")
                                delay(5_000)
                                val prevGatt: BluetoothGatt? = this@CaroBleService.clientGatt
                                prevGatt?.close()
                                @Suppress("DEPRECATION")
                                val newGatt: BluetoothGatt = hostDevice.connectGatt(
                                    this@CaroBleService, false, clientGattCallback
                                )
                                this@CaroBleService.clientGatt = newGatt
                                // Wait for onConnectionStateChange to cancel this job
                                delay(6_000)
                                if (isJoined) return@launch
                            }
                            // All retries exhausted
                            if (!isJoined) {
                                onGameControlMessage?.invoke("PLAYER_LEFT")
                                onPlayerDisconnected?.invoke()
                            }
                        }
                    } else {
                        onGameControlMessage?.invoke("PLAYER_LEFT")
                        onPlayerDisconnected?.invoke()
                    }
                } else {
                    onGameControlMessage?.invoke("PLAYER_LEFT")
                    onPlayerDisconnected?.invoke()
                }
                Log.d(TAG, "Disconnected from host")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status=$status")
            // Always discover services even if status != GATT_SUCCESS
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Chain BLE operations: subscribe to move notify → subscribe to control → full sync
            // Android BLE only allows one pending GATT operation at a time.

            // Step 1: Subscribe to MOVE_NOTIFY
            val notifyChar = gatt
                .getService(BleConstants.CARO_SERVICE_UUID)
                ?.getCharacteristic(BleConstants.MOVE_NOTIFY_CHAR_UUID)

            if (notifyChar != null) {
                enqueueGattOp {
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val ok = gatt.writeDescriptor(descriptor)
                        if (!ok) onGattOpComplete()
                    } else {
                        onGattOpComplete()
                    }
                }
            }

            // Step 2: Subscribe to GAME_CONTROL (queued, runs after step 1 completes)
            val controlChar = gatt
                .getService(BleConstants.CARO_SERVICE_UUID)
                ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID)

            if (controlChar != null) {
                enqueueGattOp {
                    gatt.setCharacteristicNotification(controlChar, true)
                    val descriptor = controlChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val ok = gatt.writeDescriptor(descriptor)
                        if (!ok) onGattOpComplete()
                    } else {
                        onGattOpComplete()
                    }
                }
            }

            // Step 3: Request full board sync (queued, runs after step 2 completes)
            enqueueGattOp {
                val readChar = gatt
                    .getService(BleConstants.CARO_SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.FULL_SYNC_READ_CHAR_UUID)
                if (readChar != null) {
                    val ok = gatt.readCharacteristic(readChar)
                    if (!ok) onGattOpComplete()
                } else {
                    onGattOpComplete()
                }
            }

            // Step 4: After all subscriptions + sync complete, notify host we're ready
            // Inline the write to avoid nested queuing (sendControlToHost now self-enqueues)
            enqueueGattOp {
                val servDevice = gatt.device
                onPlayerConnected?.invoke("joined", servDevice.address, servDevice.name ?: servDevice.address)
                val innerGatt = clientGatt
                val controlChar = innerGatt
                    ?.getService(BleConstants.CARO_SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID)
                val subsBytes = BleConstants.MSG_SUBS_COMPLETE.toByteArray(Charsets.UTF_8)
                if (innerGatt != null && controlChar != null) {
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        innerGatt.writeCharacteristic(
                            controlChar, subsBytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        controlChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        controlChar.value = subsBytes
                        @Suppress("DEPRECATION")
                        innerGatt.writeCharacteristic(controlChar) == true
                    }
                    if (!ok) {
                        Log.e(TAG, "writeCharacteristic failed for SUBS_COMPLETE")
                        onGattOpComplete()
                    }
                    // else: onCharacteristicWrite callback will call onGattOpComplete()
                } else {
                    onGattOpComplete()
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write success for ${descriptor.characteristic.uuid}")
            } else {
                Log.e(TAG, "Descriptor write failed for ${descriptor.characteristic.uuid}, status=$status")
            }
            onGattOpComplete()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write success for ${characteristic.uuid}")
            } else {
                Log.e(TAG, "Characteristic write failed for ${characteristic.uuid}, status=$status")
            }
            onGattOpComplete()
        }

        // API 33+ callback — value is passed directly (avoids deprecated characteristic.value)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BleConstants.MOVE_NOTIFY_CHAR_UUID -> {
                    try {
                        val json = String(value, Charsets.UTF_8)
                        val move = Json.decodeFromString<CaroMove>(json)
                        scope.launch { repository?.applyRemoteMove(move, gameId) }
                        onMoveReceived?.invoke(move)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse incoming move", e)
                    }
                }
                BleConstants.GAME_CONTROL_CHAR_UUID -> {
                    val message = String(value, Charsets.UTF_8)
                    if (message == BleConstants.MSG_HEARTBEAT) resetWatchdog()
                    onGameControlMessage?.invoke(message)
                }
            }
        }

        // Keep legacy override so devices running API < 33 still fire the callback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == BleConstants.FULL_SYNC_READ_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val json = String(value, Charsets.UTF_8)
                    val moves = Json.decodeFromString<List<CaroMove>>(json)
                    scope.launch { repository?.fullSync(moves) }
                    Log.d(TAG, "Full sync received: ${moves.size} moves")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse full sync data", e)
                }
            }
            onGattOpComplete()
        }

        // Keep legacy override so devices running API < 33 still fire the callback
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    fun cleanup() {
        isGamePlaying = false
        stopHeartbeat()
        stopWatchdog()
        reconnectJob?.cancel()
        reconnectJob = null
        stopHosting()
        disconnectFromHost()
        scope.cancel()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
