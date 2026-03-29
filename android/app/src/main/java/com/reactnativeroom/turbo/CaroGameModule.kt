package com.reactnativeroom.turbo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.reactnativeroom.database.CaroDatabase
import com.reactnativeroom.database.CaroMove
import com.reactnativeroom.repository.CaroRepository
import com.reactnativeroom.service.CaroBleService
import com.reactnativeroom.service.BleConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class PlaceMoveResponse(
    val success: Boolean,
    val error: String? = null,
    val isWin: Boolean = false,
    val isDraw: Boolean = false,
    val winner: String? = null,
    val winningCells: String? = null
)

@Serializable
data class GameStateResponse(
    val gameId: String,
    val status: String,
    val myRole: String,
    val mySymbol: String,
    val currentTurn: String,
    val connectedPlayers: Int,
    val winner: String? = null,
    val hostDeviceId: String = "",
    val challengerDeviceId: String = "",
    val challengerDeviceName: String = "",
    val challengerReady: Boolean = false
)

@ReactModule(name = CaroGameModule.NAME)
class CaroGameModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "CaroGame"
    }

    override fun getName() = NAME

    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val db by lazy { CaroDatabase.getInstance(reactApplicationContext) }
    private val dao by lazy { db.caroDao() }
    private val pendingDao by lazy { db.pendingMoveDao() }
    private val repo by lazy { CaroRepository(dao, pendingDao) }

    private var bleService: CaroBleService? = null
    private var isBound = false
    private var serviceConnection: ServiceConnection? = null

    private var gameId = ""
    private var myRole = ""      // "host" | "challenger"
    private var mySymbol = ""    // "X" | "O" | ""
    private var connectedPlayers = 0
    private var boardObserverJob: Job? = null

    // Identity & lobby state
    private var passKeyHash = ""           // sha256(passKey).take(8) or "" for open games
    private var challengerDeviceId = ""
    private var challengerDeviceName = ""
    private var challengerReady = false
    private var hostDeviceId = ""          // set by challenger when it connects
    private var isReconnecting = false

    private val deviceId: String
        get() = Settings.Secure.getString(
            reactApplicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )

    // ── Game Actions ─────────────────────────────────────────────────────

    @ReactMethod
    fun placeMove(x: Double, y: Double, promise: Promise) {
        moduleScope.launch {
            try {
                val ix = x.toInt()
                val iy = y.toInt()

                if (myRole == "host") {
                    // Host places directly + broadcasts
                    val result = repo.placeMove(ix, iy, mySymbol, gameId)
                    if (result.success) {
                        val move = CaroMove(
                            x = ix, y = iy,
                            playerSymbol = mySymbol,
                            moveNumber = repo.getMoveCount()
                        )
                        bleService?.broadcastMove(move)

                        // Broadcast game-over control message so challenger shows modal
                        if (result.winResult?.isWin == true) {
                            bleService?.broadcastControl("GAME_OVER:${result.winResult.winner}")
                        } else if (result.isDraw) {
                            bleService?.broadcastControl("GAME_OVER:DRAW")
                        }
                    }
                    promise.resolve(serializeMoveResult(result))
                } else if (myRole == "challenger") {
                    // Challenger queues move locally (optimistic UI) and sends via BLE if connected.
                    // If disconnected, the move is saved in pending_moves and flushed on reconnect.
                    val moveCount = repo.getMoveCount()
                    val move = CaroMove(
                        x = ix, y = iy,
                        playerSymbol = mySymbol,
                        moveNumber = moveCount + 1
                    )
                    // Optimistic write to local DB (updates board immediately via Room observer)
                    val pendingId = repo.queueMove(move, gameId)

                    if (bleService?.isConnectedToHost() == true) {
                        // Connected — best-effort immediate send; mark synced on success
                        bleService?.sendMoveToHost(move)
                        // Mark synced optimistically; host will confirm via broadcastMove event
                        if (pendingId > 0) repo.markMoveSynced(pendingId)
                    }
                    // Board will update via onBoardUpdate event when host confirms.
                    // If host rejects (occupied cell, wrong turn), fullSync on reconnect will reconcile.
                    promise.resolve(Json.encodeToString(PlaceMoveResponse(
                        success = true,
                        isWin = false,
                        isDraw = false
                    )))
                } else {
                    promise.resolve(Json.encodeToString(PlaceMoveResponse(
                        success = false,
                        error = "Cannot place moves"
                    )))
                }
            } catch (e: Exception) {
                promise.reject("MOVE_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getBoard(promise: Promise) {
        moduleScope.launch {
            try {
                val moves = repo.getBoard()
                promise.resolve(Json.encodeToString(moves))
            } catch (e: Exception) {
                promise.reject("BOARD_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getGameState(promise: Promise) {
        moduleScope.launch {
            try {
                val session = repo.getSession(gameId)
                val moveCount = repo.getMoveCount()
                val currentTurn = if (moveCount % 2 == 0) "X" else "O"

                val state = GameStateResponse(
                    gameId = gameId,
                    status = session?.status ?: "WAITING",
                    myRole = myRole,
                    mySymbol = mySymbol,
                    currentTurn = currentTurn,
                    connectedPlayers = connectedPlayers,
                    winner = session?.winner,
                    hostDeviceId = if (myRole == "host") deviceId else hostDeviceId,
                    challengerDeviceId = challengerDeviceId,
                    challengerDeviceName = challengerDeviceName,
                    challengerReady = challengerReady
                )
                promise.resolve(Json.encodeToString(state))
            } catch (e: Exception) {
                promise.reject("STATE_ERROR", e.message, e)
            }
        }
    }

    // ── Hosting / Joining ────────────────────────────────────────────────

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val mgr = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            ).all {
                ContextCompat.checkSelfPermission(reactApplicationContext, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        return ContextCompat.checkSelfPermission(
            reactApplicationContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @ReactMethod
    fun startHosting(playerName: String, passKey: String, promise: Promise) {
        moduleScope.launch {
            try {
                if (!hasBlePermissions()) {
                    promise.reject("HOST_ERROR", "Bluetooth permissions not granted. Please allow Bluetooth access and try again.")
                    return@launch
                }
                val btAdapter = bluetoothAdapter()
                if (btAdapter == null) {
                    promise.reject("HOST_ERROR", "Bluetooth is not available on this device")
                    return@launch
                }
                if (!btAdapter.isEnabled) {
                    promise.reject("HOST_ERROR", "Bluetooth is turned off. Please enable Bluetooth and try again.")
                    return@launch
                }
                if (btAdapter.bluetoothLeAdvertiser == null) {
                    promise.reject("HOST_ERROR", "BLE advertising is not supported on this device. Use a real Android phone with Bluetooth enabled.")
                    return@launch
                }

                gameId = UUID.randomUUID().toString().take(8)
                myRole = "host"
                mySymbol = "X"
                passKeyHash = if (passKey.isNotBlank()) sha256(passKey).take(8) else ""

                // Create game session
                repo.clearAll()
                repo.createSession(gameId, deviceId)

                // Bind to BLE service
                bindBleService(
                    onBound = {
                        bleService?.repository = repo
                        bleService?.gameId = gameId
                        setupBleCallbacks()
                        bleService?.startHosting()
                        promise.resolve(gameId)
                    },
                    onError = { e ->
                        promise.reject("HOST_ERROR", e.message ?: "Bluetooth not available", e)
                    }
                )
            } catch (e: Exception) {
                promise.reject("HOST_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun joinGame(playerName: String, promise: Promise) {
        if (!hasBlePermissions()) {
            promise.reject("JOIN_ERROR", "Bluetooth permissions not granted. Please allow Bluetooth access and try again.")
            return
        }
        val btAdapter = bluetoothAdapter()
        if (btAdapter == null) {
            promise.reject("JOIN_ERROR", "Bluetooth is not available on this device")
            return
        }
        if (!btAdapter.isEnabled) {
            promise.reject("JOIN_ERROR", "Bluetooth is turned off. Please enable Bluetooth and try again.")
            return
        }
        if (btAdapter.bluetoothLeScanner == null) {
            promise.reject("JOIN_ERROR", "BLE scanning is not supported on this device. Use a real Android phone with Bluetooth enabled.")
            return
        }

        myRole = "challenger" // Will be downgraded to spectator if challenger slot taken
        mySymbol = "O"

        bindBleService(
            onBound = {
                bleService?.repository = repo
                setupBleCallbacks()
                bleService?.startScanning { device ->
                    // Found a host — connect
                    bleService?.stopScanning()
                    bleService?.connectToHost(device)
                    gameId = "joined" // Will get real ID from host
                    promise.resolve(null)
                }
            },
            onError = { e ->
                promise.reject("JOIN_ERROR", e.message ?: "Bluetooth not available", e)
            }
        )
    }

    @ReactMethod
    fun startMatch(promise: Promise) {
        if (myRole != "host") {
            promise.reject("NOT_HOST", "Only host can start the match")
            return
        }

        moduleScope.launch {
            try {
                repo.startGame(gameId)
                bleService?.isGamePlaying = true
                bleService?.startHeartbeat()
                bleService?.broadcastControl("GAME_START")
                sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                    "status" to "PLAYING",
                    "gameId" to gameId
                )))
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("START_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun stopGame() {
        bleService?.isGamePlaying = false
        bleService?.cleanup()
        moduleScope.launch {
            repo.clearAll()
        }
        myRole = ""
        mySymbol = ""
        gameId = ""
        connectedPlayers = 0
        passKeyHash = ""
        challengerDeviceId = ""
        challengerDeviceName = ""
        challengerReady = false
        hostDeviceId = ""
        isReconnecting = false
        unbindBleService()
    }

    /** Sends PLAYER_READY:{deviceId}:{deviceName} to the host. Challenger only. */
    @ReactMethod
    fun setReady(promise: Promise) {
        if (myRole != "challenger") {
            promise.reject("NOT_CHALLENGER", "Only challenger can call setReady")
            return
        }
        val name = bluetoothAdapter()?.name ?: deviceId
        bleService?.sendControlToHost("${BleConstants.MSG_PLAYER_READY}:${deviceId}:${name}")
        promise.resolve(null)
    }

    /** Sends AUTH:{hash8} to the host. Challenger only. */
    @ReactMethod
    fun submitPassKey(key: String, promise: Promise) {
        if (myRole != "challenger") {
            promise.reject("NOT_CHALLENGER", "Only challenger can submit passkey")
            return
        }
        val hash = sha256(key).take(8)
        bleService?.sendControlToHost("${BleConstants.MSG_AUTH}:${hash}")
        promise.resolve(null)
    }

    /** Cancels the ongoing game for both players. Works for host and challenger. */
    @ReactMethod
    fun cancelGame(promise: Promise) {
        moduleScope.launch {
            try {
                bleService?.isGamePlaying = false
                bleService?.stopHeartbeat()
                when (myRole) {
                    "host" -> bleService?.broadcastControl(BleConstants.MSG_GAME_CANCEL)
                    "challenger" -> bleService?.sendControlToHost(BleConstants.MSG_GAME_CANCEL)
                }
                repo.clearAll()
                myRole = ""
                mySymbol = ""
                gameId = ""
                connectedPlayers = 0
                passKeyHash = ""
                challengerDeviceId = ""
                challengerDeviceName = ""
                challengerReady = false
                hostDeviceId = ""
                isReconnecting = false
                unbindBleService()
                sendEvent("onGameCancel", "")
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("CANCEL_ERROR", e.message, e)
            }
        }
    }

    /** Wipes stale DB state on app cold-start. Safe to call repeatedly. */
    @ReactMethod
    fun initialize(promise: Promise) {
        moduleScope.launch {
            try {
                if (myRole.isEmpty()) {
                    // No active in-memory game session — clear any leftover DB state
                    repo.clearAll()
                }
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("INIT_ERROR", e.message, e)
            }
        }
    }

    /**
     * Manually reconnect to the last known BLE host.
     * Used when the user taps the "Reconnect" button in the game UI.
     * No-op if already connected or no host was previously seen.
     */
    @ReactMethod
    fun reconnect(promise: Promise) {
        bleService?.reconnect()
        promise.resolve(null)
    }

    // ── BLE Service Binding ──────────────────────────────────────────────

    private fun bindBleService(onBound: () -> Unit, onError: (Exception) -> Unit = {}) {
        val intent = Intent(reactApplicationContext, CaroBleService::class.java)
        try {
            reactApplicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            onError(e)
            return
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    bleService = (binder as CaroBleService.LocalBinder).getService()
                    isBound = true
                    onBound()
                } catch (e: Exception) {
                    onError(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bleService = null
                isBound = false
                serviceConnection = null
            }
        }
        serviceConnection = conn
        reactApplicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun unbindBleService() {
        val conn = serviceConnection
        if (isBound && conn != null) {
            try {
                reactApplicationContext.unbindService(conn)
            } catch (_: Exception) { }
            isBound = false
            serviceConnection = null
        }
        try {
            val intent = Intent(reactApplicationContext, CaroBleService::class.java)
            reactApplicationContext.stopService(intent)
        } catch (_: Exception) { }
    }

    private fun setupBleCallbacks() {
        // onMoveReceived only used by challenger — host gets board updates via Room observer
        bleService?.onMoveReceived = { _ -> }

        bleService?.onPlayerConnected = { role, devId, devName ->
            connectedPlayers++
            when (role) {
                "challenger" -> {
                    // Host side: record who joined
                    challengerDeviceId = devId
                    challengerDeviceName = devName
                    // AUTH_CHALLENGE is deferred until SUBS_COMPLETE arrives
                    // (challenger must finish subscribing before it can receive notifications)
                    sendEvent("onPlayerConnected", Json.encodeToString(mapOf(
                        "role" to role,
                        "deviceId" to devId,
                        "deviceName" to devName
                    )))
                    // Emit updated game state so Lobby sees the challenger info
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "challengerDeviceId" to devId,
                        "challengerDeviceName" to devName,
                        "challengerReady" to false,
                        "connectedPlayers" to connectedPlayers
                    )))
                }
                "joined" -> {
                    // Challenger side: record host identity
                    hostDeviceId = devId
                    sendEvent("onPlayerConnected", Json.encodeToString(mapOf(
                        "role" to role,
                        "deviceId" to devId,
                        "deviceName" to devName
                    )))
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "hostDeviceId" to devId,
                        "connectedPlayers" to connectedPlayers
                    )))
                }
                else -> {
                    sendEvent("onPlayerConnected", Json.encodeToString(mapOf(
                        "role" to role,
                        "deviceId" to devId,
                        "deviceName" to devName
                    )))
                }
            }
        }

        bleService?.onPlayerDisconnected = {
            connectedPlayers = maxOf(0, connectedPlayers - 1)
            sendEvent("onPlayerDisconnected", "")
        }

        bleService?.onGameControlMessage = { message ->
            when {
                message == BleConstants.MSG_GAME_START -> {
                    bleService?.isGamePlaying = true
                    bleService?.resetWatchdog()
                    // Create challenger's DB session BEFORE sending event so GameScreen mount-sync reads PLAYING
                    moduleScope.launch {
                        repo.createSession(gameId, hostDeviceId)
                        repo.startGame(gameId)
                        sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                            "status" to "PLAYING"
                        )))
                    }
                }
                message == BleConstants.MSG_GAME_RESET -> {
                    moduleScope.launch { repo.clearAll() }
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "status" to "WAITING"
                    )))
                }
                message == BleConstants.MSG_PLAYER_LEFT -> {
                    isReconnecting = false
                    bleService?.isGamePlaying = false
                    bleService?.stopWatchdog()
                    moduleScope.launch { repo.clearAll() }
                    sendEvent("onPlayerLeft", "")
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "status" to "FINISHED"
                    )))
                }
                message == BleConstants.MSG_GAME_CANCEL -> {
                    bleService?.isGamePlaying = false
                    bleService?.stopHeartbeat()
                    bleService?.stopWatchdog()
                    moduleScope.launch { repo.clearAll() }
                    myRole = ""
                    mySymbol = ""
                    gameId = ""
                    connectedPlayers = 0
                    isReconnecting = false
                    sendEvent("onGameCancel", "")
                }
                message == BleConstants.MSG_SUBS_COMPLETE -> {
                    // Challenger finished subscribing — check if we're reconnecting to an active game.
                    // If game is already PLAYING, send RECONNECTED instead of AUTH_CHALLENGE
                    // (prevents clearAll() wiping the in-progress board state).
                    moduleScope.launch {
                        val session = repo.getSession(gameId)
                        if (session?.status == "PLAYING") {
                            bleService?.broadcastControl(BleConstants.MSG_RECONNECTED)
                        } else {
                            val requiresPass = if (passKeyHash.isNotEmpty()) "1" else "0"
                            bleService?.broadcastControl(
                                "${BleConstants.MSG_AUTH_CHALLENGE}:${gameId}:${requiresPass}"
                            )
                        }
                    }
                }
                message == BleConstants.MSG_HEARTBEAT -> {
                    // Watchdog is already reset in CaroBleService.onCharacteristicChanged
                    // Nothing extra needed here
                }
                message == BleConstants.MSG_RECONNECTING -> {
                    isReconnecting = true
                    sendEvent("onReconnecting", "")
                }
                message == BleConstants.MSG_RECONNECTED -> {
                    isReconnecting = false
                    sendEvent("onReconnected", "")
                    // Flush any moves queued while offline
                    moduleScope.launch {
                        val pending = repo.getPendingMoves()
                        for (pm in pending) {
                            val move = CaroMove(
                                x = pm.x, y = pm.y,
                                playerSymbol = pm.playerSymbol,
                                moveNumber = repo.getMoveCount() + 1
                            )
                            bleService?.sendMoveToHost(move)
                            repo.markMoveSynced(pm.id)
                        }
                    }
                }
                message == BleConstants.MSG_AUTH_OK -> {
                    sendEvent("onAuthSuccess", "")
                }
                message == BleConstants.MSG_AUTH_FAIL -> {
                    sendEvent("onAuthFail", "")
                    bleService?.disconnectFromHost()
                }
                message.startsWith("${BleConstants.MSG_AUTH_CHALLENGE}:") -> {
                    // Challenger received AUTH_CHALLENGE:gameId:requiresPass
                    val parts = message.removePrefix("${BleConstants.MSG_AUTH_CHALLENGE}:").split(":")
                    val challengeGameId = parts.getOrNull(0) ?: ""
                    val requiresPass = parts.getOrNull(1) == "1"
                    gameId = challengeGameId
                    // Create DB session BEFORE sending event so UI reads correct state.
                    // Safety guard: if already PLAYING with the same ID (stale reconnect message), skip clearAll.
                    moduleScope.launch {
                        val existing = repo.getSession(challengeGameId)
                        if (existing?.status == "PLAYING" && challengeGameId == gameId) return@launch
                        repo.clearAll()
                        repo.createSession(challengeGameId, hostDeviceId)
                        if (requiresPass) {
                            sendEvent("onAuthRequired", challengeGameId)
                        } else {
                            sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                                "gameId" to challengeGameId
                            )))
                        }
                    }
                }
                message.startsWith("${BleConstants.MSG_AUTH}:") -> {
                    // Host received AUTH:hash from challenger
                    val submittedHash = message.removePrefix("${BleConstants.MSG_AUTH}:")
                    if (passKeyHash.isEmpty() || submittedHash == passKeyHash) {
                        bleService?.broadcastControl(BleConstants.MSG_AUTH_OK)
                    } else {
                        bleService?.broadcastControl(BleConstants.MSG_AUTH_FAIL)
                        // Disconnect the challenger after giving time for the message to arrive
                        moduleScope.launch {
                            delay(500)
                            bleService?.disconnectChallenger()
                        }
                    }
                }
                message.startsWith("${BleConstants.MSG_PLAYER_READY}:") -> {
                    // Host received PLAYER_READY:deviceId:deviceName from challenger
                    val parts = message.removePrefix("${BleConstants.MSG_PLAYER_READY}:").split(":")
                    challengerDeviceId = parts.getOrNull(0) ?: challengerDeviceId
                    challengerDeviceName = parts.getOrNull(1) ?: challengerDeviceName
                    challengerReady = true
                    sendEvent("onPlayerReady", Json.encodeToString(mapOf(
                        "deviceId" to challengerDeviceId,
                        "deviceName" to challengerDeviceName
                    )))
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "challengerDeviceId" to challengerDeviceId,
                        "challengerDeviceName" to challengerDeviceName,
                        "challengerReady" to true
                    )))
                }
                message.startsWith("GAME_OVER:") -> {
                    val winner = message.removePrefix("GAME_OVER:")
                    bleService?.isGamePlaying = false
                    bleService?.stopWatchdog()
                    sendEvent("onGameOver", winner)
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "status" to "FINISHED",
                        "winner" to winner
                    )))
                }
                else -> {
                    sendEvent("onGameOver", message)
                }
            }
        }
    }

    // ── Event Emitter ────────────────────────────────────────────────────

    @ReactMethod
    fun addListener(eventName: String) {
        if (eventName == "onBoardUpdate") {
            // Cancel any previous observer to prevent duplicate emissions
            boardObserverJob?.cancel()
            boardObserverJob = moduleScope.launch {
                repo.observeMoves.collectLatest { moves ->
                    sendEvent("onBoardUpdate", Json.encodeToString(moves))
                }
            }
        }
    }

    @ReactMethod
    fun removeListeners(count: Double) {
        // Managed by moduleScope cancellation
    }

    private fun sendEvent(eventName: String, data: Any?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, data)
    }

    // SHA-256 helper — returns lowercase hex string
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun serializeMoveResult(result: com.reactnativeroom.repository.MoveResult): String {
        val response = PlaceMoveResponse(
            success = result.success,
            error = result.error,
            isWin = result.winResult?.isWin ?: false,
            isDraw = result.isDraw,
            winner = result.winResult?.winner,
            winningCells = result.winResult?.winningCells?.let { cells ->
                Json.encodeToString(cells.map { listOf(it.first, it.second) })
            }
        )
        return Json.encodeToString(response)
    }

    override fun invalidate() {
        boardObserverJob?.cancel()
        moduleScope.cancel()
        bleService?.cleanup()
        unbindBleService()
        super.invalidate()
    }
}
