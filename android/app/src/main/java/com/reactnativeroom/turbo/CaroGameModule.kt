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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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
    private val repo by lazy { CaroRepository(dao) }

    private var bleService: CaroBleService? = null
    private var isBound = false

    private var gameId = ""
    private var myRole = ""      // "host" | "challenger" | "spectator"
    private var mySymbol = ""    // "X" | "O" | ""
    private var connectedPlayers = 0

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

                if (myRole == "spectator") {
                    promise.resolve(Json.encodeToString(mapOf(
                        "success" to "false",
                        "error" to "Spectators cannot place moves"
                    )))
                    return@launch
                }

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
                    }
                    promise.resolve(serializeMoveResult(result))
                } else if (myRole == "challenger") {
                    // Challenger sends move to host via BLE
                    val moveCount = repo.getMoveCount()
                    val move = CaroMove(
                        x = ix, y = iy,
                        playerSymbol = mySymbol,
                        moveNumber = moveCount + 1
                    )
                    bleService?.sendMoveToHost(move)
                    promise.resolve(Json.encodeToString(mapOf(
                        "success" to "true",
                        "isWin" to "false",
                        "isDraw" to "false"
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

                val state = mapOf(
                    "gameId" to gameId,
                    "status" to (session?.status ?: "WAITING"),
                    "myRole" to myRole,
                    "mySymbol" to mySymbol,
                    "currentTurn" to currentTurn,
                    "connectedPlayers" to connectedPlayers.toString(),
                    "winner" to (session?.winner ?: "")
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
    fun startHosting(playerName: String, promise: Promise) {
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
        bleService?.cleanup()
        moduleScope.launch {
            repo.clearAll()
        }
        myRole = ""
        mySymbol = ""
        gameId = ""
        connectedPlayers = 0
        unbindBleService()
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

        reactApplicationContext.bindService(intent, object : ServiceConnection {
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
            }
        }, Context.BIND_AUTO_CREATE)
    }

    private fun unbindBleService() {
        if (isBound) {
            try {
                val intent = Intent(reactApplicationContext, CaroBleService::class.java)
                reactApplicationContext.stopService(intent)
            } catch (_: Exception) { }
            isBound = false
        }
    }

    private fun setupBleCallbacks() {
        bleService?.onMoveReceived = { move ->
            sendEvent("onBoardUpdate", Json.encodeToString(move))
        }

        bleService?.onPlayerConnected = { role ->
            connectedPlayers++
            sendEvent("onPlayerConnected", role)
        }

        bleService?.onPlayerDisconnected = {
            connectedPlayers--
            sendEvent("onPlayerDisconnected", "")
        }

        bleService?.onGameControlMessage = { message ->
            when (message) {
                "GAME_START" -> {
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "status" to "PLAYING"
                    )))
                }
                "GAME_RESET" -> {
                    moduleScope.launch { repo.clearAll() }
                    sendEvent("onGameStateChange", Json.encodeToString(mapOf(
                        "status" to "WAITING"
                    )))
                }
                else -> {
                    if (message.startsWith("GAME_OVER")) {
                        sendEvent("onGameOver", message)
                    }
                }
            }
        }
    }

    // ── Event Emitter ────────────────────────────────────────────────────

    @ReactMethod
    fun addListener(eventName: String) {
        if (eventName == "onBoardUpdate") {
            moduleScope.launch {
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

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun serializeMoveResult(result: com.reactnativeroom.repository.MoveResult): String {
        val map = mutableMapOf(
            "success" to result.success.toString(),
            "isWin" to (result.winResult?.isWin?.toString() ?: "false"),
            "isDraw" to result.isDraw.toString()
        )
        result.error?.let { map["error"] = it }
        result.winResult?.winner?.let { map["winner"] = it }
        result.winResult?.winningCells?.let { cells ->
            map["winningCells"] = Json.encodeToString(cells.map { listOf(it.first, it.second) })
        }
        return Json.encodeToString(map)
    }

    override fun invalidate() {
        moduleScope.cancel()
        bleService?.cleanup()
        unbindBleService()
        super.invalidate()
    }
}
