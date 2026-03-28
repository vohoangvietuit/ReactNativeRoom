package com.reactnativeroom.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private var challengerDevice: BluetoothDevice? = null
    private var isHosting = false

    // GATT Client (Join mode)
    private var clientGatt: BluetoothGatt? = null
    private var isJoined = false

    // Repository reference — set by the TurboModule
    var repository: CaroRepository? = null
    var gameId: String = ""

    // Callbacks — set by the TurboModule to push events to JS
    var onMoveReceived: ((CaroMove) -> Unit)? = null
    var onPlayerConnected: ((String) -> Unit)? = null // role: "challenger" | "spectator"
    var onPlayerDisconnected: (() -> Unit)? = null
    var onGameControlMessage: ((String) -> Unit)? = null

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
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        challengerDevice = null
        isHosting = false
        Log.d(TAG, "Host stopped")
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
                onPlayerConnected?.invoke(role)
                Log.d(TAG, "Device connected as $role: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                if (device == challengerDevice) {
                    challengerDevice = null
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
        val notifyChar = gattServer
            ?.getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.MOVE_NOTIFY_CHAR_UUID) ?: return

        notifyChar.value = bytes
        connectedDevices.forEach { device ->
            gattServer?.notifyCharacteristicChanged(device, notifyChar, false)
        }
        Log.d(TAG, "Broadcast move to ${connectedDevices.size} devices")
    }

    /**
     * Host broadcasts a game control message to all connected devices
     */
    fun broadcastControl(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val controlChar = gattServer
            ?.getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID) ?: return

        controlChar.value = bytes
        connectedDevices.forEach { device ->
            gattServer?.notifyCharacteristicChanged(device, controlChar, false)
        }
    }

    fun getConnectedCount(): Int = connectedDevices.size
    fun hasChallengerConnected(): Boolean = challengerDevice != null

    // ══════════════════════════════════════════════════════════════════════
    // JOIN MODE — GATT Client
    // ══════════════════════════════════════════════════════════════════════

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

        leScanner.startScan(
            listOf(filter), settings,
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    onDeviceFound(result.device)
                }
            }
        )
        Log.d(TAG, "Started scanning for Caro games")
    }

    fun stopScanning() {
        adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
    }

    fun connectToHost(device: BluetoothDevice) {
        clientGatt = device.connectGatt(this, false, clientGattCallback)
    }

    fun disconnectFromHost() {
        clientGatt?.disconnect()
        clientGatt?.close()
        clientGatt = null
        isJoined = false
    }

    /**
     * Client sends a move to the Host via WRITE characteristic
     */
    fun sendMoveToHost(move: CaroMove) {
        val json = Json.encodeToString(move)
        val bytes = json.toByteArray(Charsets.UTF_8)

        val writeChar = clientGatt
            ?.getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.MOVE_WRITE_CHAR_UUID) ?: return

        writeChar.value = bytes
        clientGatt?.writeCharacteristic(writeChar)
        Log.d(TAG, "Sent move to host: (${ move.x}, ${move.y})")
    }

    // Pending GATT operations queue — Android BLE allows only one at a time
    private val pendingGattOps = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private var gattBusy = false

    private fun enqueueGattOp(op: () -> Unit) {
        pendingGattOps.add(op)
        drainGattQueue()
    }

    private fun drainGattQueue() {
        if (gattBusy) return
        val next = pendingGattOps.poll() ?: return
        gattBusy = true
        next()
    }

    private fun onGattOpComplete() {
        gattBusy = false
        drainGattQueue()
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(BleConstants.MTU_SIZE)
                isJoined = true
                Log.d(TAG, "Connected to host")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isJoined = false
                pendingGattOps.clear()
                gattBusy = false
                onPlayerDisconnected?.invoke()
                Log.d(TAG, "Disconnected from host")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
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
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
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
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }

            // Step 3: Request full board sync (queued, runs after step 2 completes)
            enqueueGattOp {
                requestFullSync(gatt)
            }

            onPlayerConnected?.invoke("joined")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write success for ${descriptor.characteristic.uuid}")
            } else {
                Log.e(TAG, "Descriptor write failed for ${descriptor.characteristic.uuid}, status=$status")
            }
            onGattOpComplete()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                BleConstants.MOVE_NOTIFY_CHAR_UUID -> {
                    // Received a new move from host
                    try {
                        val json = String(characteristic.value, Charsets.UTF_8)
                        val move = Json.decodeFromString<CaroMove>(json)
                        scope.launch {
                            repository?.applyRemoteMove(move, gameId)
                        }
                        onMoveReceived?.invoke(move)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse incoming move", e)
                    }
                }
                BleConstants.GAME_CONTROL_CHAR_UUID -> {
                    val message = String(characteristic.value, Charsets.UTF_8)
                    onGameControlMessage?.invoke(message)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (characteristic.uuid == BleConstants.FULL_SYNC_READ_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val json = String(characteristic.value, Charsets.UTF_8)
                    val moves = Json.decodeFromString<List<CaroMove>>(json)
                    scope.launch {
                        repository?.fullSync(moves)
                    }
                    Log.d(TAG, "Full sync received: ${moves.size} moves")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse full sync data", e)
                }
            }
            onGattOpComplete()
        }
    }

    private fun requestFullSync(gatt: BluetoothGatt) {
        val readChar = gatt
            .getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.FULL_SYNC_READ_CHAR_UUID)
        if (readChar != null) {
            gatt.readCharacteristic(readChar)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    fun cleanup() {
        stopHosting()
        disconnectFromHost()
        scope.cancel()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
