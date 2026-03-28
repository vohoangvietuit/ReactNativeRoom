# Building a Multiplayer Caro (Gomoku) Game with Kotlin, Room, BLE, and React Native

> A complete step-by-step guide: build a real-time multiplayer Caro (Gomoku) game that runs on two Android devices connected via Bluetooth Low Energy, with a beautiful React Native UI, Room database for game history, and Kotlin game logic. From setup to production patterns.

---

## Table of Contents

1. [What We're Building](#1-what-were-building)
2. [Project Setup & Dependencies](#2-project-setup--dependencies)
3. [Architecture Overview](#3-architecture-overview)
3.1. [Critical Implementation Details](#31-critical-implementation-details)
4. [Kotlin Fundamentals](#4-kotlin-fundamentals)
5. [Room Database — Storing Game Moves](#5-room-database--storing-game-moves)
6. [Game Logic — Win Detection](#6-game-logic--win-detection)
7. [Repository Pattern — Game State](#7-repository-pattern--game-state)
8. [Bluetooth Low Energy for Game Sync](#8-bluetooth-low-energy-for-game-sync)
9. [Building the BLE GameService](#9-building-the-ble-gameservice)
10. [Native Module Bridge — React Native ↔ Kotlin](#10-native-module-bridge--react-native--kotlin)
11. [React Native Hooks & Game Logic](#11-react-native-hooks--game-logic)
12. [UI Components — Game Board & Navigation](#12-ui-components--game-board--navigation)
13. [Full App Flow — From Hosting to Game End](#13-full-app-flow--from-hosting-to-game-end)
14. [Testing & Debugging](#14-testing--debugging)
15. [Common Issues & Solutions](#15-common-issues--solutions)
16. [Summary & Mental Models](#16-summary--mental-models)

---

## 1. What We're Building

A **real-time multiplayer Caro game** (Gomoku — 5-in-a-row on a 15×15 board) where two Android phones can play against each other over Bluetooth. No internet, no server, no latency. Features proper game state synchronization with comprehensive test coverage.

### Game Mechanics

- **15×15 board** with X and O players
- **5-in-a-row wins** (horizontal, vertical, or diagonal)
- **Two player modes:**
  - **Host** — creates a game, advertises via BLE, plays as X
  - **Challenger** — scans for games, joins as O
- **Real-time sync** — moves appear on both devices instantly via BLE GATT notifications with proper CCCD descriptor write sequencing
- **Game state persistence** — all moves stored in Room database; game state re-hydrates on app navigation
- **Proper serialization** — Boolean fields in native responses are properly typed (not string coercion)
- **Game history** — all moves stored in Room database, persist between sessions

### Complete Architecture

```
┌──────────────────────────────────────────────────────────┐
│              React Native (TypeScript)                    │
│   HomeScreen → LobbyScreen → GameScreen                  │
│   AppNavigator, GameBoard, GameHUD, useCaroGame hook    │
│         ↕ calls NativeModules.CaroGame                   │
├──────────────────────────────────────────────────────────┤
│         Native Module Bridge (Kotlin)                    │
│     CaroGameModule.kt ←→ @ReactMethod annotations        │
│         ↕ manages BLE Service, calls Repository          │
├──────────────────────────────────────────────────────────┤
│          Kotlin Game Engine (Kotlin)                     │
│  CaroRepository (game logic)                             │
│  ↓ persists to                ↓ syncs via               │
│  Room Database         BLE GATT (CaroBleService)         │
│  (CaroDatabase +                or                       │
│   CaroMove entity)      scans for peers              │
│                                                          │
│  ├─ WinChecker (5-in-a-row detection)                  │
│  ├─ GameSession entity (game metadata)                 │
│  └─ CaroMove entity (each move: x, y, player, turn)    │
└──────────────────────────────────────────────────────────┘
```

### Component responsibilities

| Component | Role | Language |
|---|---|---|
| **GameScreen** | Renders the board grid, handles taps | TypeScript/React |
| **GameHUD** | Displays player info, turn, connected count | TypeScript/React |
| **useCaroGame hook** | State management, emit/listen events | TypeScript |
| **CaroGameModule** | TurboModule bridge, lifecycle management | Kotlin |
| **CaroRepository** | Game rules, move validation, win checking | Kotlin |
| **WinChecker** | Pure 5-in-a-row detection | Kotlin |
| **CaroBleService** | BLE advertising, scanning, GATT server/client | Kotlin |
| **Room Database** | Persistent move storage | Kotlin + SQLite |

### The sync strategy

1. **Host broadcasts:** When host places a move, `CaroBleService` writes it to a NOTIFY characteristic
2. **Challengers subscribe:** When challenger connects, host's GATT server subscriptions activate
3. **Real-time updates:** Each move triggers a notification to all connected clients
4. **Spectators observe:** Can join after game starts but cannot place moves
5. **Late-join sync:** New joiners receive full board state via READ characteristic

---

## 2. Project Setup & Dependencies

### Prerequisites

- **Android SDK 36+** (compile target), Min SDK 24
- **React Native 0.84.1+** with New Architecture enabled
- **Kotlin 2.1.20+**
- **Node.js 22+**, npm 10+

### Create React Native project with TypeScript

```bash
npx @react-native-community/cli init ReactNativeRoom --template typescript
cd ReactNativeRoom
```

### Install dependencies

**npm packages:**
```bash
npm install react-native-safe-area-context
npm install @react-navigation/native @react-navigation/native-stack
```

**Gradle dependencies** (in `android/app/build.gradle`):

```gradle
dependencies {
    // Room — 2.7.1 with kapt (not KSP — KSP 2.1.20-1.0.32 incompatible with RN)
    implementation 'androidx.room:room-runtime:2.7.1'
    kapt 'androidx.room:room-compiler:2.7.1'
    implementation 'androidx.room:room-ktx:2.7.1'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1'

    // Serialization
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0'
}

plugins {
    id 'kotlin-kapt'  // Must come before room compiler can work
    id 'org.jetbrains.kotlin.plugin.serialization' version '2.1.20'
}
```

**Android permissions** (in `AndroidManifest.xml`):

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<service
    android:name=".service.CaroBleService"
    android:foregroundServiceType="connectedDevice" />
```

---

## 3. Architecture Overview

### Data Flow During a Game

#### Setup (Host creates game):

1. **HomeScreen** → User taps "Host Game"
2. `useCaroGame.startHosting()` → calls `NativeModules.CaroGame.startHosting()`
3. **CaroGameModule.startHosting()**:
   - Generates `gameId`
   - Creates `GameSession` entity in Room DB (status = "WAITING")
   - Starts `CaroBleService` as foreground service
   - Begins advertising via BLE GATT server
4. **LobbyScreen** → shows waiting animation, device ID for others to scan
5. **Challenger app** → scans, finds host's BLE advertisement
6. `useCaroGame.joinGame()` → calls native `joinGame()`
7. **CaroGameModule.joinGame()**:
   - Connects to host's GATT server
   - Subscribes to NOTIFY characteristic (gets move updates)
   - Marks role as "challenger"

#### Move Placement (During game):

1. **GameScreen** → User taps cell (x, y)
2. `placeMove(x, y)` → calls `NativeModules.CaroGame.placeMove(x, y)`
3. **CaroGameModule.placeMove()**:
   - Returns `PlaceMoveResponse` with properly typed booleans (not string coercion)
   - If **host**: `CaroRepository.placeMove(x, y)` → saves to DB → validates → checks win → broadcasts move via BLE
   - If **challenger**: sends move to host via BLE WRITE characteristic
   - Host receives move, validates, saves, broadcasts back to all subscribers
4. **BLE GATT operations are queued** — CCCD descriptor writes no longer race, ensuring reliable subscriptions
5. **BLE GATT Server notifies** all connected devices
6. `useCaroGame` hook listens via `NativeEventEmitter`, updates React state
7. **GameScreen re-renders** with new board state

**Key improvements:**
- Hook safely coerces booleans: `raw.success === true || raw.success === 'true'`
- Hook only calls `refreshBoard()` on win/draw (normal moves update via `onBoardUpdate` Room observer)
- Mount-sync useEffect re-hydrates game state from native module (fixes WAITING on navigation)

#### End of Game:

1. Move results in 5-in-a-row or board full
2. **WinChecker.check()** detects win/draw
3. `CaroGameModule` broadcasts GAME_OVER control message
4. All devices show **GameOverModal**
5. `stopGame()` → unbind BLE service, clear game state

### State Dependencies

```
GameState (React hook)
  ├─ gameId: string (set by host, shared to joiners)
  ├─ status: "WAITING" | "PLAYING" | "FINISHED"
  ├─ myRole: "host" | "challenger"
  ├─ mySymbol: "X" | "O" | ""
  ├─ currentTurn: "X" | "O"
  ├─ connectedPlayers: number
  └─ winner: string | null

Board (2D array of CellValue)
  └─ Derived from moves[] list: [CaroMoveData, ...]
     where each move = { x, y, playerSymbol, moveNumber, timestamp }
     
Sync on Mount:
  └─ useEffect calls getGameState() + getBoard() to re-hydrate from native
     (fixes WAITING status when navigating back to GameScreen)
```

---

## 3.1 Critical Implementation Details

The following three design decisions ensure reliable multiplayer gameplay. Understanding them is key to preventing common pitfalls.

### Design 1: Typed Boolean Serialization

**The Problem:**  
Native Kotlin methods return boolean fields, but the TurboModule bridge serializes everything to JSON strings. When JavaScript receives `{"success":"false"}`, the string `"false"` is truthy — causing immediate WIN when `isWin: false` should mean the game continues.

**The Solution:**  
Create `@Serializable` Kotlin data classes with explicit boolean fields:

```kotlin
@Serializable
data class PlaceMoveResponse(
    val success: Boolean,     // Proper boolean type
    val isWin: Boolean,       // Not a string
    val isDraw: Boolean,
    val winner: String? = null,
    val winningCells: String? = null,  // Only this stays as serialized JSON string
    val error: String? = null
)
```

Then in `CaroGameModule.placeMove()`, invoke the response directly without `.toString()` — Kotlin Serialization handles the JSON encoding with proper types.

In React, safely coerce at the boundary:
```typescript
const result: MoveResultData = {
  ...raw,
  success: raw.success === true || raw.success === 'true',  // Safety net
  isWin: raw.isWin === true || raw.isWin === 'true',
  isDraw: raw.isDraw === true || raw.isDraw === 'true',
};
```

### Design 2: BLE GATT Operation Queue

**The Problem:**  
Android's `BluetoothGattServer` callback model allows only one pending GATT operation at a time. When Challenger connects, we must:
1. Write CCCD for MOVE_NOTIFY_CHAR (enable notifications)
2. Write CCCD for GAME_CONTROL_CHAR (enable control notifications)
3. Request full board state (READ operation)

If these happen simultaneously, operations 2 and 3 may silently fail, leaving Challenger without notifications.

**The Solution:**  
Implement a sequential operation queue:

```kotlin
// In CaroBleService
private val pendingGattOps = ConcurrentLinkedQueue<GattOp>()
private var gattBusy = false

data class GattOp(val descriptor: UUID, val value: ByteArray)

fun enqueueGattOp(descriptor: UUID, value: ByteArray) {
    pendingGattOps.add(GattOp(descriptor, value))
    if (!gattBusy) drainGattQueue()
}

private fun drainGattQueue() {
    val op = pendingGattOps.poll() ?: return
    gattBusy = true
    // Perform write; on completion, onGattOpComplete() will drain next
}

fun onGattOpComplete() {
    gattBusy = false
    drainGattQueue()  // Process next queued operation
}

override fun onDescriptorWrite(
    device: BluetoothDevice,
    requestId: Int,
    descriptor: BluetoothGattDescriptor,
    preparedWrite: Boolean,
    responseNeeded: Boolean,
    offset: Int,
    value: ByteArray
) {
    // ... handle write ...
    sendResponse(device, requestId, GATT_SUCCESS, 0, null)
    onGattOpComplete()  // Trigger next operation
}
```

### Design 3: Mount-Sync for Game State Re-hydration

**The Problem:**  
React hooks initialize fresh local state on mount. When user navigates from GameScreen → HomeScreen → back to GameScreen, `useCaroGame()` creates a new instance with `status: 'WAITING'` and `myRole: ''`, losing sight of the active game in the native module.

**The Solution:**  
Add a mount-sync `useEffect` that fetches state from native on component mount:

```typescript
// In useCaroGame.ts
useEffect(() => {
  if (!CaroGame) return;
  const syncState = async () => {
    try {
      const stateJson = await CaroGame.getGameState();
      const parsed: GameStateData = JSON.parse(stateJson);
      if (parsed.myRole) {  // Only update if native has active game
        setGameState(prev => ({...prev, ...parsed}));
      }
      const boardJson = await CaroGame.getBoard();
      const boardParsed: CaroMoveData[] = JSON.parse(boardJson);
      if (boardParsed.length > 0) {
        setMoves(boardParsed);
      }
    } catch {}
  };
  syncState();
}, []);  // Empty deps: runs once on mount
```

This ensures React state matches native reality, preventing WAITING when the game is PLAYING.

---

## 4. Kotlin Fundamentals

> If you're new to Kotlin, this section covers syntax you'll see throughout the game logic.

### Variables & null safety

```kotlin
// Immutable (like const in JS)
val playerX = "X"
val playerO = "O"

// Mutable variable
var currentTurn: String = "X"
currentTurn = "O"

// Nullable types (explicitly opt-in to null)
val maybeWinner: String? = null
val winner = maybeWinner ?: "No winner yet"  // Elvis operator: fallback

// Safe navigation
val winnerLength = maybeWinner?.length  // returns null if maybeWinner is null
```

### Data classes (entity types)

```kotlin
@Entity(tableName = "caro_moves")
@Serializable
data class CaroMove(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "x")
    val x: Int,
    
    @ColumnInfo(name = "y")
    val y: Int,
    
    @ColumnInfo(name = "player_symbol")
    val playerSymbol: String,  // "X" or "O"
    
    @ColumnInfo(name = "move_number")
    val moveNumber: Int,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
)

// Data classes auto-generate:
// - equals() / hashCode()
// - toString()
// - copy() for immutable updates
val move1 = CaroMove(x = 7, y = 7, playerSymbol = "X", moveNumber = 1)
val move2 = move1.copy(playerSymbol = "O")  // creates new instance
```

### Extension functions (add methods to types)

```kotlin
// Extend Int to add a board coordinate formatter
fun Int.toBoardCoordinate(): String = "ABCDEFGHIJKLMNO"[this].toString()

// Usage
val col = 7
println(col.toBoardCoordinate())  // prints "H"

// Extend List<CaroMove> with game-specific logic
fun List<CaroMove>.lastX(): CaroMove? = lastOrNull()
fun List<CaroMove>.countBySymbol(symbol: String) = count { it.playerSymbol == symbol }

val xMoves = moves.countBySymbol("X")
```

### Scope functions (with, let, apply, also, run)

```kotlin
// apply — configure an object and return it
val move = CaroMove(x = 0, y = 0, playerSymbol = "X", moveNumber = 1).apply {
    // 'this' is the CaroMove
}

// also — do something with an object and return it
val savedMove = move.also { m ->
    // 'it' is the move
    println("Saved move: $m")
}

// let — transform an object into something else
maybeMove?.let { move ->
    println("Valid move: ${move.x}, ${move.y}")
}
```

---

## 5. Room Database — Storing Game Moves

### Why Room for a game?

- **Persistence:** Game history survives app restarts
- **Queries:** Find all moves by a player, moves since timestamp, etc.
- **Type safety:** Column names checked at compile time
- **Async-first:** All queries are suspend functions (non-blocking)

### Entity 1: CaroMove (each cell placed)

```kotlin
// android/app/src/main/java/com/reactnativeroom/database/CaroMove.kt
package com.reactnativeroom.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "caro_moves",
    indices = [
        Index(value = ["x", "y"], unique = true)  // No duplicate moves at same position
    ]
)
@Serializable
data class CaroMove(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "x")
    val x: Int,
    
    @ColumnInfo(name = "y")
    val y: Int,
    
    @ColumnInfo(name = "player_symbol")
    val playerSymbol: String,  // "X" or "O"
    
    @ColumnInfo(name = "move_number")
    val moveNumber: Int,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
)
```

### Entity 2: GameSession (game metadata)

```kotlin
// android/app/src/main/java/com/reactnativeroom/database/GameSession.kt
package com.reactnativeroom.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "game_sessions")
@Serializable
data class GameSession(
    @PrimaryKey
    val gameId: String,
    
    @ColumnInfo(name = "host_device_id")
    val hostDeviceId: String,
    
    @ColumnInfo(name = "status")
    val status: String = "WAITING",  // "WAITING" | "PLAYING" | "FINISHED"
    
    @ColumnInfo(name = "winner")
    val winner: String? = null,  // "X" | "O" | null
    
    @ColumnInfo(name = "board_size")
    val boardSize: Int = 15,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
```

### DAO: Query interface

```kotlin
// android/app/src/main/java/com/reactnativeroom/database/CaroDao.kt
package com.reactnativeroom.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CaroDao {
    
    // ─── Reads (one-time) ───
    
    @Query("SELECT * FROM caro_moves ORDER BY move_number ASC")
    suspend fun getAllMoves(): List<CaroMove>
    
    @Query("SELECT * FROM caro_moves WHERE x = :x AND y = :y LIMIT 1")
    suspend fun getMoveAt(x: Int, y: Int): CaroMove?
    
    @Query("SELECT * FROM caro_moves ORDER BY move_number DESC LIMIT 1")
    suspend fun getLastMove(): CaroMove?
    
    @Query("SELECT COUNT(*) FROM caro_moves")
    suspend fun getMoveCount(): Int
    
    // ─── Reactive (Flow emits on changes) ───
    
    @Query("SELECT * FROM caro_moves ORDER BY move_number ASC")
    fun observeMoves(): Flow<List<CaroMove>>
    
    // ─── Writes ───
    
    @Insert
    suspend fun insertMove(move: CaroMove)
    
    @Insert
    suspend fun insertAll(moves: List<CaroMove>)
    
    @Query("DELETE FROM caro_moves")
    suspend fun clearMoves()
    
    // ─── Session management ───
    
    @Upsert
    suspend fun upsertSession(session: GameSession)
    
    @Query("SELECT * FROM game_sessions WHERE game_id = :gameId LIMIT 1")
    suspend fun getSession(gameId: String): GameSession?
    
    @Query("UPDATE game_sessions SET status = :status WHERE game_id = :gameId")
    suspend fun updateSessionStatus(gameId: String, status: String)
}
```

**Why different query types:**
- `suspend fun` — one-time fetch (use in validation, sync logic)
- `Flow<T>` — reactive, emits whenever table changes (use in UI state)

### Database: The singleton container

```kotlin
// android/app/src/main/java/com/reactnativeroom/database/CaroDatabase.kt
package com.reactnativeroom.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [CaroMove::class, GameSession::class],
    version = 1,
    exportSchema = false,  // can enable for production migrations
)
abstract class CaroDatabase : RoomDatabase() {
    
    abstract fun caroDao(): CaroDao
    
    companion object {
        @Volatile
        private var INSTANCE: CaroDatabase? = null
        
        fun getInstance(context: Context): CaroDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CaroDatabase::class.java,
                    "caro_game.db"
                )
                    .fallbackToDestructiveMigration(true)  // for dev
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

**Double-checked locking:** Ensures only one database instance even when called from multiple threads.

---

## 6. Game Logic — Win Detection

### WinChecker: Pure function for 5-in-a-row

The most critical game logic. It scans 4 directions (horizontal, vertical, 2 diagonals) for 5 consecutive same symbols.

```kotlin
// android/app/src/main/java/com/reactnativeroom/game/WinChecker.kt
package com.reactnativeroom.game

data class WinResult(
    val isWin: Boolean,
    val winner: String? = null,
    val winningCells: List<Pair<Int, Int>> = emptyList(),
)

object WinChecker {
    private const val BOARD_SIZE = 15
    private const val WIN_LENGTH = 5
    
    fun check(board: Array<IntArray>, x: Int, y: Int, symbol: String): WinResult {
        if (!isWithinBounds(x, y)) return WinResult(false)
        
        val symbolInt = if (symbol == "X") 1 else 2
        
        // Check all 4 directions from (x, y)
        val directions = listOf(
            Pair(1, 0),   // horizontal
            Pair(0, 1),   // vertical
            Pair(1, 1),   // diagonal \
            Pair(1, -1),  // diagonal /
        )
        
        for ((dx, dy) in directions) {
            val cells = scanLine(board, x, y, dx, dy, symbolInt)
            if (cells.size >= WIN_LENGTH) {
                return WinResult(
                    isWin = true,
                    winner = symbol,
                    winningCells = cells.take(WIN_LENGTH)
                )
            }
        }
        
        return WinResult(false)
    }
    
    fun isDraw(board: Array<IntArray>): Boolean {
        return board.all { row -> row.all { it != 0 } }
    }
    
    private fun scanLine(
        board: Array<IntArray>,
        startX: Int,
        startY: Int,
        dx: Int,
        dy: Int,
        symbol: Int,
    ): List<Pair<Int, Int>> {
        val cells = mutableListOf<Pair<Int, Int>>()
        var x = startX
        var y = startY
        
        // Scan backwards first
        var bx = x - dx
        var by = y - dy
        while (isWithinBounds(bx, by) && board[by][bx] == symbol) {
            cells.add(0, Pair(bx, by))  // prepend
            bx -= dx
            by -= dy
        }
        
        // Add center
        cells.add(Pair(startX, startY))
        
        // Scan forwards
        x = startX + dx
        y = startY + dy
        while (isWithinBounds(x, y) && board[y][x] == symbol) {
            cells.add(Pair(x, y))
            x += dx
            y += dy
        }
        
        return cells
    }
    
    private fun isWithinBounds(x: Int, y: Int): Boolean =
        x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE
}
```

**How it works:**
1. Given a move at (x, y), check 4 directions from that position
2. For each direction, scan *both* backwards and forwards to count consecutive symbols
3. If any direction has ≥5 in a row, return `WinResult(isWin=true, winningCells=...)`
4. Return the exact cells that form the winning line for highlighting in UI

---

## 7. Repository Pattern — Game State

The repository is the **single source of truth** for game logic. All game state changes go through it.

```kotlin
// android/app/src/main/java/com/reactnativeroom/repository/CaroRepository.kt
package com.reactnativeroom.repository

import android.util.Log
import com.reactnativeroom.database.CaroDao
import com.reactnativeroom.database.CaroMove
import com.reactnativeroom.database.GameSession
import com.reactnativeroom.game.WinChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MoveResult(
    val success: Boolean,
    val error: String? = null,
    val winResult: WinChecker.WinResult? = null,
    val isDraw: Boolean = false,
)

class CaroRepository(private val dao: CaroDao) {
    
    val observeMoves = dao.observeMoves()
    
    suspend fun placeMove(x: Int, y: Int, symbol: String, gameId: String): MoveResult =
        withContext(Dispatchers.IO) {
            try {
                // Validate bounds
                if (x < 0 || x >= 15 || y < 0 || y >= 15) {
                    return@withContext MoveResult(
                        success = false,
                        error = "Out of bounds: ($x, $y)"
                    )
                }
                
                // Check cell is empty
                val existing = dao.getMoveAt(x, y)
                if (existing != null) {
                    return@withContext MoveResult(
                        success = false,
                        error = "Cell ($x, $y) already occupied"
                    )
                }
                
                // Check game is active
                val session = dao.getSession(gameId)
                if (session?.status != "PLAYING") {
                    return@withContext MoveResult(
                        success = false,
                        error = "Game not in PLAYING state: ${session?.status}"
                    )
                }
                
                // Check turn order (alternates X, O)
                val moveCount = dao.getMoveCount()
                val currentTurn = if (moveCount % 2 == 0) "X" else "O"
                if (symbol != currentTurn) {
                    return@withContext MoveResult(
                        success = false,
                        error = "Not your turn. Expected $currentTurn, got $symbol"
                    )
                }
                
                // Insert move
                val move = CaroMove(
                    x = x,
                    y = y,
                    playerSymbol = symbol,
                    moveNumber = moveCount + 1,
                    timestamp = System.currentTimeMillis()
                )
                dao.insertMove(move)
                
                // Build board state and check for win
                val allMoves = dao.getAllMoves()
                val board = buildBoard(allMoves)
                val winResult = WinChecker.check(board, x, y, symbol)
                val isDraw = !winResult.isWin && WinChecker.isDraw(board)
                
                if (winResult.isWin || isDraw) {
                    val winner = if (winResult.isWin) winResult.winner else null
                    dao.updateSessionStatus(gameId, "FINISHED")
                    Log.d("CaroRepository", "Game over! Winner: $winner")
                }
                
                return@withContext MoveResult(
                    success = true,
                    winResult = winResult,
                    isDraw = isDraw
                )
            } catch (e: Exception) {
                Log.e("CaroRepository", "placeMove failed", e)
                return@withContext MoveResult(
                    success = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    
    suspend fun getBoard(): List<CaroMove> = withContext(Dispatchers.IO) {
        dao.getAllMoves()
    }
    
    suspend fun getMoveCount(): Int = withContext(Dispatchers.IO) {
        dao.getMoveCount()
    }
    
    suspend fun getSession(gameId: String): GameSession? = withContext(Dispatchers.IO) {
        dao.getSession(gameId)
    }
    
    suspend fun createSession(gameId: String, hostDeviceId: String) {
        val session = GameSession(
            gameId = gameId,
            hostDeviceId = hostDeviceId,
            status = "WAITING"
        )
        dao.upsertSession(session)
    }
    
    suspend fun startGame(gameId: String) = withContext(Dispatchers.IO) {
        dao.updateSessionStatus(gameId, "PLAYING")
    }
    
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearMoves()
    }
    
    // For late joiners — receive full board state
    suspend fun fullSync(moves: List<CaroMove>) = withContext(Dispatchers.IO) {
        dao.clearMoves()
        dao.insertAll(moves)
    }
    
    private fun buildBoard(moves: List<CaroMove>): Array<IntArray> {
        val board = Array(15) { IntArray(15) }  // 0 = empty, 1 = X, 2 = O
        for (move in moves) {
            board[move.y][move.x] = if (move.playerSymbol == "X") 1 else 2
        }
        return board
    }
}
```

**Key responsibilities:**
- **Turn validation:** Ensures X and O alternate
- **Win detection:** Builds board array, calls WinChecker
- **State transitions:** WAITING → PLAYING → FINISHED
- **Sync:** Full board sync for late joiners

---

## 8. Bluetooth Low Energy for Game Sync

### BLE terminology quick ref

| Term | Meaning |
|---|---|
| **GATT** | Generic Attribute Profile — BLE service/characteristic structure |
| **Service** | A collection of characteristics (like a "feature") |
| **Characteristic** | A value with read/write/notify permissions |
| **Descriptor** | Metadata for a characteristic (CCCD = Client Characteristic Config Descriptor) |
| **UUID** | 128-bit unique ID for services/characteristics |
| **Advertise** | Host broadcasts its presence (`BluetoothLeAdvertiser`) |
| **Scan** | Client searches for broadcasters (`BluetoothLeScanner`) |
| **NOTIFY** | Server pushes updates to subscribers (`BluetoothGattServerCallback`) |
| **WRITE** | Client sends data to server |

### BLE architecture for Caro

**Host (game creator):**
- Advertises a service with UUID: `CARO_SERVICE_UUID`
- Implements GATT server with 3 characteristics:
  - **MOVE_NOTIFY_CHAR** — server broadcasts each move to all subscribers
  - **FULL_SYNC_READ_CHAR** — returns complete board state on read (for late joiners)
  - **MOVE_WRITE_CHAR** — receives moves from challengers

**Challenger (joiner):**
- Scans for `CARO_SERVICE_UUID` advertisement
- Connects to host's GATT server (becomes a client)
- Subscribes to MOVE_NOTIFY_CHAR (gets move notifications)
- Sends own moves via MOVE_WRITE_CHAR

**Note:** Spectator mode was removed to simplify the game flow. Only Host and Challenger can participate.

### Constants & UUID management

```kotlin
// android/app/src/main/java/com/reactnativeroom/service/BleConstants.kt
package com.reactnativeroom.service

import java.util.UUID

object BleConstants {
    // Caro game service
    val CARO_SERVICE_UUID: UUID = UUID.fromString(
        "12345678-1234-5678-1234-56789abcdef0"
    )
    
    // Characteristics
    val MOVE_NOTIFY_CHAR_UUID: UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee01"
    )
    val MOVE_WRITE_CHAR_UUID: UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee02"
    )
    val FULL_SYNC_READ_CHAR_UUID: UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee03"
    )
    val GAME_CONTROL_CHAR_UUID: UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee04"
    )
    
    // CCCD for enabling notifications
    val CCCD_UUID: UUID = UUID.fromString(
        "00002902-0000-1000-8000-00805f9b34fb"
    )
    
    const val MTU_SIZE = 512  // Data packet size
}
```

---

## 9. Building the BLE GameService

This is the most complex part. The service manages both GATT server (host) and GATT client (challenger) roles.

```kotlin
// android/app/src/main/java/com/reactnativeroom/service/CaroBleService.kt
package com.reactnativeroom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.reactnativeroom.database.CaroMove
import com.reactnativeroom.repository.CaroRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class CaroBleService : Service() {
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    
    var repository: CaroRepository? = null
    var gameId: String = ""
    
    private var isHost = false
    private val connectedDevices = mutableListOf<BluetoothDevice>()
    
    // Callbacks
    var onMoveReceived: ((CaroMove) -> Unit)? = null
    var onPlayerConnected: ((String) -> Unit)? = null
    var onPlayerDisconnected: (() -> Unit)? = null
    var onGameControlMessage: ((String) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): CaroBleService = this@CaroBleService
    }
    
    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    // ─── Host mode ───
    
    fun startHosting() {
        isHost = true
        setupGattServer()
        startAdvertising()
    }
    
    private fun setupGattServer() {
        val service = BluetoothGattService(
            BleConstants.CARO_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // NOTIFY for broadcasts
        val notifyChar = BluetoothGattCharacteristic(
            BleConstants.MOVE_NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notifyChar.addDescriptor(cccd)
        service.addCharacteristic(notifyChar)
        
        // WRITE for receiving moves from challengers
        val writeChar = BluetoothGattCharacteristic(
            BleConstants.MOVE_WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(writeChar)
        
        // READ for full board sync
        val readChar = BluetoothGattCharacteristic(
            BleConstants.FULL_SYNC_READ_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(readChar)
        
        // CONTROL messages (game start, reset, etc.)
        val controlChar = BluetoothGattCharacteristic(
            BleConstants.GAME_CONTROL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        controlChar.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(controlChar)
        
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        gattServer?.addService(service)
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d("CaroBleService", "Connection state: $newState, device: ${device.address}")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    onPlayerConnected?.invoke(device.name ?: device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    onPlayerDisconnected?.invoke()
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BleConstants.MOVE_WRITE_CHAR_UUID -> {
                    // Challenger sent a move
                    val json = String(value)
                    try {
                        val move = Json.decodeFromString<CaroMove>(json)
                        onMoveReceived?.invoke(move)
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, GATT_SUCCESS, offset, null)
                        }
                    } catch (e: Exception) {
                        Log.e("CaroBleService", "Failed to parse move", e)
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, GATT_FAILURE, offset, null)
                        }
                    }
                }
                BleConstants.GAME_CONTROL_CHAR_UUID -> {
                    val message = String(value)
                    onGameControlMessage?.invoke(message)
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, GATT_SUCCESS, offset, null)
                    }
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                BleConstants.FULL_SYNC_READ_CHAR_UUID -> {
                    // Late joiner requests full board
                    serviceScope.launch {
                        try {
                            val board = repository?.getBoard() ?: emptyList()
                            val json = Json.encodeToString(board)
                            val data = json.toByteArray()
                            gattServer?.sendResponse(
                                device, requestId, GATT_SUCCESS, offset,
                                data.copyOfRange(offset, minOf(offset + BleConstants.MTU_SIZE, data.size))
                            )
                        } catch (e: Exception) {
                            gattServer?.sendResponse(device, requestId, GATT_FAILURE, offset, null)
                        }
                    }
                }
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Client enabled/disabled notifications via CCCD
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, GATT_SUCCESS, offset, null)
            }
        }
    }
    
    private fun startAdvertising() {
        val advertiseSettings = BluetoothLeAdvertiseSettings.Builder()
            .setAdvertiseMode(BluetoothLeAdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(BluetoothLeAdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val advertiseData = BluetoothLeAdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(BleConstants.CARO_SERVICE_UUID))
            .setIncludeDeviceName(true)
            .build()
        
        bluetoothAdapter?.bluetoothLeAdvertiser?.startAdvertising(
            advertiseSettings,
            advertiseData,
            advertiseCallback
        )
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("CaroBleService", "Advertising started")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e("CaroBleService", "Advertising failed: $errorCode")
        }
    }
    
    fun broadcastMove(move: CaroMove) {
        val json = Json.encodeToString(move)
        val data = json.toByteArray()
        
        val char = gattServer?.getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.MOVE_NOTIFY_CHAR_UUID) ?: return
        
        char.value = data
        for (device in connectedDevices) {
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }
    
    fun broadcastControl(message: String) {
        val data = message.toByteArray()
        val char = gattServer?.getService(BleConstants.CARO_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID) ?: return
        
        char.value = data
        for (device in connectedDevices) {
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }
    
    // ─── Challenger/Spectator mode ───
    
    fun startScanning(onFoundHost: (BluetoothDevice) -> Unit) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        
        val scanFilter = BluetoothScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(BleConstants.CARO_SERVICE_UUID))
            .build()
        
        val scanSettings = BluetoothScanSettings.Builder()
            .setScanMode(BluetoothScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanner.startScan(
            listOf(scanFilter),
            scanSettings,
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    onFoundHost(result.device)
                }
            }
        )
    }
    
    fun stopScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanner.stopScan(null)
    }
    
    fun connectToHost(device: BluetoothDevice) {
        gattClient = device.connectGatt(this, false, gattClientCallback)
    }
    
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("CaroBleService", "Client connection state: $newState")
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(BleConstants.CARO_SERVICE_UUID) ?: return
            
            // Subscribe to move notifications
            val notifyChar = service.getCharacteristic(BleConstants.MOVE_NOTIFY_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(notifyChar, true)
            
            val cccd = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            
            // Also subscribe to control messages
            val controlChar = service.getCharacteristic(BleConstants.GAME_CONTROL_CHAR_UUID)
            if (controlChar != null) {
                gatt.setCharacteristicNotification(controlChar, true)
                val controlCccd = controlChar.getDescriptor(BleConstants.CCCD_UUID)
                controlCccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(controlCccd)
            }
            
            // Request full board sync
            val readChar = service.getCharacteristic(BleConstants.FULL_SYNC_READ_CHAR_UUID)
            if (readChar != null) {
                gatt.readCharacteristic(readChar)
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == BleConstants.FULL_SYNC_READ_CHAR_UUID) {
                val json = String(characteristic.value)
                try {
                    val moves = Json.decodeFromString<List<CaroMove>>(json)
                    serviceScope.launch {
                        repository?.fullSync(moves)
                    }
                } catch (e: Exception) {
                    Log.e("CaroBleService", "Failed to sync board", e)
                }
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            when (characteristic.uuid) {
                BleConstants.MOVE_NOTIFY_CHAR_UUID -> {
                    val json = String(characteristic.value)
                    try {
                        val move = Json.decodeFromString<CaroMove>(json)
                        onMoveReceived?.invoke(move)
                    } catch (e: Exception) {
                        Log.e("CaroBleService", "Failed to parse notified move", e)
                    }
                }
                BleConstants.GAME_CONTROL_CHAR_UUID -> {
                    val message = String(characteristic.value)
                    onGameControlMessage?.invoke(message)
                }
            }
        }
    }
    
    fun sendMoveToHost(move: CaroMove) {
        val json = Json.encodeToString(move)
        val data = json.toByteArray()
        
        val service = gattClient?.getService(BleConstants.CARO_SERVICE_UUID) ?: return
        val writeChar = service.getCharacteristic(BleConstants.MOVE_WRITE_CHAR_UUID) ?: return
        
        writeChar.value = data
        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gattClient?.writeCharacteristic(writeChar)
    }
    
    fun cleanup() {
        gattServer?.close()
        gattClient?.disconnect()
        gattClient?.close()
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
    }
    
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            "caro_game",
            "Caro Game",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        
        return Notification.Builder(this, "caro_game")
            .setContentTitle("Caro Game")
            .setContentText("Playing...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
```

**Key flow:**
- **Host:** `startHosting()` → `setupGattServer()` → `startAdvertising()`  → waits for connections
- **Challenger:** `startScanning()` → finds host → `connectToHost()` → `discoverServices()` → subscribes to notifications
- **Move broadcast:** Host calls `broadcastMove()` → writes to characteristic → all subscribers get `onCharacteristicChanged()` callback
- **Control messages:** Used for GAME_START, GAME_RESET, game-over signals

---

## 10. Native Module Bridge — React Native ↔ Kotlin

The TurboModule (now ReactModule) exposes game logic to React Native.

```kotlin
// android/app/src/main/java/com/reactnativeroom/turbo/CaroGameModule.kt
package com.reactnativeroom.turbo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.reactnativeroom.database.CaroDatabase
import com.reactnativeroom.repository.CaroRepository
import com.reactnativeroom.service.CaroBleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    // ─── Game Actions ───

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
                    val result = repo.placeMove(ix, iy, mySymbol, gameId)
                    if (result.success) {
                        val move = com.reactnativeroom.database.CaroMove(
                            x = ix, y = iy,
                            playerSymbol = mySymbol,
                            moveNumber = repo.getMoveCount()
                        )
                        bleService?.broadcastMove(move)
                    }
                    promise.resolve(serializeMoveResult(result))
                } else if (myRole == "challenger") {
                    val moveCount = repo.getMoveCount()
                    val move = com.reactnativeroom.database.CaroMove(
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

    // ─── Hosting / Joining ───

    @ReactMethod
    fun startHosting(playerName: String, promise: Promise) {
        moduleScope.launch {
            try {
                gameId = java.util.UUID.randomUUID().toString().take(8)
                myRole = "host"
                mySymbol = "X"

                repo.createSession(gameId, deviceId)
                repo.clearAll()

                bindBleService {
                    bleService?.repository = repo
                    bleService?.gameId = gameId
                    setupBleCallbacks()
                    bleService?.startHosting()
                    promise.resolve(gameId)
                }
            } catch (e: Exception) {
                promise.reject("HOST_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun joinGame(playerName: String, promise: Promise) {
        myRole = "challenger"
        mySymbol = "O"

        bindBleService {
            bleService?.repository = repo
            setupBleCallbacks()
            bleService?.startScanning { device ->
                bleService?.stopScanning()
                bleService?.connectToHost(device)
                gameId = "joined"
                promise.resolve(null)
            }
        }
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

    // ─── BLE Service Binding ───

    private fun bindBleService(onBound: () -> Unit) {
        val intent = Intent(reactApplicationContext, CaroBleService::class.java)
        reactApplicationContext.startForegroundService(intent)

        reactApplicationContext.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bleService = (binder as CaroBleService.LocalBinder).getService()
                isBound = true
                onBound()
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
                reactApplicationContext.unbindService(object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {}
                    override fun onServiceDisconnected(name: ComponentName) {}
                })
            } catch (_: Exception) { }
            isBound = false
        }
    }

    private fun setupBleCallbacks() {
        bleService?.onMoveReceived = { move ->
            moduleScope.launch {
                repo.placeMove(move.x, move.y, move.playerSymbol, gameId)
                sendEvent("onBoardUpdate", Json.encodeToString(listOf(move)))
            }
        }

        bleService?.onPlayerConnected = { _role ->
            connectedPlayers++
            sendEvent("onPlayerConnected", _role)
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

    // ─── Event Emitter ───

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

    // ─── Helpers ───

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

    private val deviceId: String
        get() = Settings.Secure.getString(
            reactApplicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
}
```

**How it bridges JS ↔ Kotlin:**
- `@ReactMethod` functions are callable from JavaScript via `NativeModules.CaroGame`
- `Promise` resolves/rejects like JS promises
- `sendEvent()` emits to JS via `NativeEventEmitter`
- Module lifecycle: created on app start, `invalidate()` called on cleanup

---

## 11. React Native Hooks & Game Logic

The custom hook manages all game state and communicates with the native module.

```typescript
// src/hooks/useCaroGame.ts
import {useState, useEffect, useCallback, useMemo} from 'react';
import {NativeEventEmitter, NativeModules} from 'react-native';
import type {
  CaroMoveData,
  MoveResultData,
  GameStateData,
} from '../specs/NativeCaroGame';

const CaroGame = NativeModules.CaroGame;
const eventEmitter = CaroGame ? new NativeEventEmitter(CaroGame) : null;

const BOARD_SIZE = 15;
type CellValue = '' | 'X' | 'O';

export function useCaroGame() {
  const [moves, setMoves] = useState<CaroMoveData[]>([]);
  const [gameState, setGameState] = useState<GameStateData>({
    gameId: '',
    status: 'WAITING',
    myRole: '',
    mySymbol: '',
    currentTurn: 'X',
    connectedPlayers: 0,
  });
  const [lastMoveResult, setLastMoveResult] = useState<MoveResultData | null>(null);
  const [loading, setLoading] = useState(false);

  // ─── Derived state ───

  const board = useMemo(() => {
    const grid: CellValue[][] = Array.from({length: BOARD_SIZE}, () =>
      Array(BOARD_SIZE).fill(''),
    );
    for (const move of moves) {
      if (move.x >= 0 && move.x < BOARD_SIZE && move.y >= 0 && move.y < BOARD_SIZE) {
        grid[move.y][move.x] = move.playerSymbol as CellValue;
      }
    }
    return grid;
  }, [moves]);

  const lastMove = useMemo(() => {
    if (moves.length === 0) return null;
    const last = moves[moves.length - 1];
    return {x: last.x, y: last.y};
  }, [moves]);

  const winningCells = useMemo(() => {
    if (!lastMoveResult?.isWin || !lastMoveResult.winningCells) return [];
    try {
      return JSON.parse(lastMoveResult.winningCells) as [number, number][];
    } catch {
      return [];
    }
  }, [lastMoveResult]);

  const isMyTurn = useMemo(() => {
    if (gameState.myRole === 'spectator') return false;
    return gameState.currentTurn === gameState.mySymbol;
  }, [gameState]);

  // ─── Event subscriptions ───

  useEffect(() => {
    if (!eventEmitter) return;

    const boardSub = eventEmitter.addListener('onBoardUpdate', (data: string) => {
      try {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) {
          setMoves(parsed);
          const turn = parsed.length % 2 === 0 ? 'X' : 'O';
          setGameState(prev => ({...prev, currentTurn: turn}));
        } else {
          setMoves(prev => {
            const updated = [...prev, parsed];
            const turn = updated.length % 2 === 0 ? 'X' : 'O';
            setGameState(gs => ({...gs, currentTurn: turn}));
            return updated;
          });
        }
      } catch {}
    });

    const stateSub = eventEmitter.addListener('onGameStateChange', (data: string) => {
      try {
        const parsed = JSON.parse(data);
        setGameState(prev => ({...prev, ...parsed}));
      } catch {}
    });

    const playerConnSub = eventEmitter.addListener('onPlayerConnected', (_role: string) => {
      setGameState(prev => ({
        ...prev,
        connectedPlayers: prev.connectedPlayers + 1,
      }));
    });

    const playerDiscSub = eventEmitter.addListener('onPlayerDisconnected', () => {
      setGameState(prev => ({
        ...prev,
        connectedPlayers: Math.max(0, prev.connectedPlayers - 1),
      }));
    });

    const gameOverSub = eventEmitter.addListener('onGameOver', () => {
      setGameState(prev => ({...prev, status: 'FINISHED'}));
    });

    CaroGame?.addListener('onBoardUpdate');

    return () => {
      boardSub.remove();
      stateSub.remove();
      playerConnSub.remove();
      playerDiscSub.remove();
      gameOverSub.remove();
    };
  }, []);

  // ─── Async actions ───

  const refreshBoard = useCallback(async () => {
    if (!CaroGame) return;
    try {
      const json = await CaroGame.getBoard();
      setMoves(JSON.parse(json));
    } catch {}
  }, []);

  const refreshGameState = useCallback(async () => {
    if (!CaroGame) return;
    try {
      const json = await CaroGame.getGameState();
      setGameState(prev => ({...prev, ...JSON.parse(json)}));
    } catch {}
  }, []);

  const placeMove = useCallback(
    async (x: number, y: number) => {
      if (!CaroGame) return;
      try {
        const json = await CaroGame.placeMove(x, y);
        const result: MoveResultData = JSON.parse(json);
        setLastMoveResult(result);

        if (result.success) {
          await refreshBoard();
          if (result.isWin || result.isDraw) {
            setGameState(prev => ({
              ...prev,
              status: 'FINISHED',
              winner: result.winner,
            }));
          }
        }
        return result;
      } catch {
        return null;
      }
    },
    [refreshBoard],
  );

  const startHosting = useCallback(async (playerName: string) => {
    if (!CaroGame) return '';
    setLoading(true);
    try {
      const gameId = await CaroGame.startHosting(playerName);
      setGameState(prev => ({
        ...prev,
        gameId,
        myRole: 'host',
        mySymbol: 'X',
        status: 'WAITING',
      }));
      return gameId;
    } finally {
      setLoading(false);
    }
  }, []);

  const joinGame = useCallback(async (playerName: string) => {
    if (!CaroGame) return;
    setLoading(true);
    try {
      await CaroGame.joinGame(playerName);
      setGameState(prev => ({
        ...prev,
        myRole: 'challenger',
        mySymbol: 'O',
        status: 'WAITING',
      }));
    } finally {
      setLoading(false);
    }
  }, []);

  const startMatch = useCallback(async () => {
    if (!CaroGame) return;
    try {
      await CaroGame.startMatch();
      setGameState(prev => ({...prev, status: 'PLAYING'}));
    } catch {}
  }, []);

  const stopGame = useCallback(() => {
    CaroGame?.stopGame();
    setMoves([]);
    setGameState({
      gameId: '',
      status: 'WAITING',
      myRole: '',
      mySymbol: '',
      currentTurn: 'X',
      connectedPlayers: 0,
    });
    setLastMoveResult(null);
  }, []);

  return {
    board,
    moves,
    gameState,
    lastMove,
    lastMoveResult,
    winningCells,
    isMyTurn,
    loading,
    placeMove,
    startHosting,
    joinGame,
    startMatch,
    stopGame,
    refreshBoard,
    refreshGameState,
  };
}
```

**State management pattern:**
- Moves come from either `onBoardUpdate` events (reactive) or `placeMove()` (imperative)
- Game state derived from API calls + events
- Board is derived from moves (2D grid computed from list)
- Turn alternates: 0 moves → X's turn, 1 move → O's turn, etc.

---

## 12. UI Components — Game Board & Navigation

### GameBoard: rendering 15×15 grid

```typescript
// src/components/game/GameBoard.tsx
import React, {useMemo} from 'react';
import {View, Pressable, Text, Dimensions} from 'react-native';
import {theme} from '../../theme';

interface GameBoardProps {
  board: Array<Array<'' | 'X' | 'O'>>;
  onCellPress: (x: number, y: number) => void;
  disabled?: boolean;
  lastMove?: {x: number; y: number} | null;
  winningCells?: Array<[number, number]>;
}

export function GameBoard({
  board,
  onCellPress,
  disabled,
  lastMove,
  winningCells,
}: GameBoardProps) {
  const BOARD_SIZE = /15/;
  const screenWidth = Dimensions.get('window').width;
  const boardWidth = screenWidth - theme.spacing(4);
  const CELL_SIZE = boardWidth / BOARD_SIZE;

  const isWinningCell = useMemo(() => {
    return new Set(winningCells?.map(([x, y]) => `${x},${y}`) ?? []);
  }, [winningCells]);

  return (
    <View style={{alignItems: 'center', padding: theme.spacing(2)}}>
      {/* Column labels */}
      <View style={{flexDirection: 'row', marginBottom: 4}}>
        <View style={{width: CELL_SIZE}} />
        {Array.from({length: BOARD_SIZE}).map((_, col) => (
          <Text
            key={`col-${col}`}
            style={{
              width: CELL_SIZE,
              textAlign: 'center',
              fontSize: 10,
              color: theme.colors.text.secondary,
            }}>
            {String.fromCharCode(65 + col)}
          </Text>
        ))}
      </View>

      {/* Board rows */}
      {board.map((row, y) => (
        <View key={`row-${y}`} style={{flexDirection: 'row'}}>
          {/* Row label */}
          <Text
            style={{
              width: CELL_SIZE,
              textAlign: 'center',
              lineHeight: CELL_SIZE,
              fontSize: 10,
              color: theme.colors.text.secondary,
            }}>
            {y + 1}
          </Text>

          {/* Cells */}
          {row.map((cell, x) => {
            const isLastMove = lastMove?.x === x && lastMove?.y === y;
            const isWinning = isWinningCell.has(`${x},${y}`);

            return (
              <Pressable
                key={`cell-${x}-${y}`}
                disabled={disabled || cell !== ''}
                onPress={() => onCellPress(x, y)}
                style={[
                  {
                    width: CELL_SIZE,
                    height: CELL_SIZE,
                    borderWidth: 1,
                    borderColor: theme.colors.border,
                    justifyContent: 'center',
                    alignItems: 'center',
                    backgroundColor:
                      isLastMove || isWinning
                        ? theme.colors.background.highlight
                        : theme.colors.background.cell,
                  },
                ]}>
                <Text
                  style={{
                    fontSize: CELL_SIZE * 0.5,
                    fontWeight: 'bold',
                    color:
                      cell === 'X'
                        ? theme.colors.playerX
                        : cell === 'O'
                          ? theme.colors.playerO
                          : 'transparent',
                  }}>
                  {cell}
                </Text>
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
}
```

### GameScreen: ties everything together

```typescript
// src/screens/GameScreen.tsx
import React, {useCallback} from 'react';
import {SafeAreaView, ScrollView, Alert} from 'react-native';
import {GameBoard} from '../components/game/GameBoard';
import {GameHUD} from '../components/game/GameHUD';
import {GameOverModal} from '../components/game/GameOverModal';
import {useCaroGame} from '../hooks/useCaroGame';
import {theme} from '../theme';

export function GameScreen(): React.ReactElement {
  const caro = useCaroGame();

  const handleCellPress = useCallback(
    (x: number, y: number) => {
      if (!caro.isMyTurn) {
        Alert.alert('Not your turn', `Waiting for ${caro.gameState.currentTurn}`);
        return;
      }

      caro.placeMove(x, y).then(result => {
        if (!result?.success) {
          Alert.alert('Invalid move', result?.error || 'Unknown error');
        }
      });
    },
    [caro.isMyTurn, caro.gameState.currentTurn, caro.placeMove],
  );

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: theme.colors.background.primary}}>
      <ScrollView
        contentContainerStyle={{flexGrow: 1}}
        scrollEnabled={false}>
        <GameHUD gameState={caro.gameState} isMyTurn={caro.isMyTurn} />
        <GameBoard
          board={caro.board}
          onCellPress={handleCellPress}
          disabled={!caro.isMyTurn}
          lastMove={caro.lastMove}
          winningCells={caro.winningCells}
        />
      </ScrollView>

      {caro.gameState.status === 'FINISHED' && caro.lastMoveResult && (
        <GameOverModal
          isWin={caro.lastMoveResult.isWin}
          isDraw={caro.lastMoveResult.isDraw}
          winner={caro.lastMoveResult.winner}
          onRematch={() => {
            caro.stopGame();
            // Navigate back to home or lobby
          }}
        />
      )}
    </SafeAreaView>
  );
}
```

---

## 13. Full App Flow — From Hosting to Game End

### Home → Host Creates Game

1. User: taps "Host Game" on HomeScreen
2. JS: `startHosting("Player1")` → calls native module
3. Kotlin:
   - Generates `gameId = "abc12345"`
   - Inserts `GameSession(gameId, status="WAITING")`
   - Starts `CaroBleService`
   - Begins BLE advertising with `CARO_SERVICE_UUID`
4. JS: Receives `gameId`, navigates to LobbyScreen
5. UI: Shows device ID: "Display ID for scanners" + waiting animation

### Home → Challenger Joins Game

1. User: taps "Join Game", app scans for BLE services
2. Kotlin: `startScanning()` finds host's advertisement
3. User sees list of available games (host device names), taps one
4. Kotlin: `connectToHost(device)` → discovers services → subscribes to `MOVE_NOTIFY_CHAR`
5. JS: Navigates to LobbyScreen with `myRole="challenger"`

### Lobby → Host Starts Match

1. Host sees "X Connected Players" with Start Match button
2. Host: taps "Start Match"
3. JS: `startMatch()` → calls native
4. Kotlin:
   - Updates session: status = "PLAYING"
   - Broadcasts `GAME_CONTROL_CHAR` = "GAME_START"
5. All devices get `onGameStateChange` event
6. JS: Navigates to GameScreen, turns on game interactions

### GameScreen → Moves Exchange

**Host places a move:**
```
Host taps (7, 7)
→ placeMove(7, 7)
→ Kotlin validates, saves to DB
→ WinChecker.check() → no win
→ broadcastMove(CaroMove{x:7, y:7, symbol:"X"})
→ GATT server notifies all subscribers
→ Challenger + spectators receive onCharacteristicChanged
→ onMoveReceived callback triggers
→ emits onBoardUpdate event
→ Hook updates moves[]
→ Board re-renders with X at (7, 7)
```

**Challenger sends move:**
```
Challenger taps (7, 8)
→ placeMove(7, 8)
→ Kotlin sends via BLE WRITE to host
→ Host receives onCharacteristicWriteRequest
→ onMoveReceived callback validates + saves
→ Host broadcastMove() to all
→ (same as above)
```

### Game End → Win/Draw

```
Move results in 5-in-a-row
→ WinChecker.check() → WinResult(isWin=true, winningCells=[...])
→ Status = "FINISHED"
→ broadcastControl("GAME_OVER:winner=X")
→ All devices show GameOverModal
→ User taps "Play Again"
→ stopGame() → unbind BLE, clear DB, reset state
→ Back to home
```

---

## 14. Testing &  Debugging

### Testing moves locally (single device)

```kotlin
// Add this to your test environment
class CaroRepositoryTest {
    @Test
    fun testPlaceMoveValidation() = runTest {
        val dao = FakeCaroDao()
        val repo = CaroRepository(dao)
        
        // First move should succeed
        val result1 = repo.placeMove(7, 7, "X", "test-game")
        assertTrue(result1.success)
        
        // Same cell should fail
        val result2 = repo.placeMove(7, 7, "O", "test-game")
        assertFalse(result2.success)
    }
    
    @Test
    fun testWinDetection() = runTest {
        val dao = FakeCaroDao()
        val repo = CaroRepository(dao)
        
        // Place 5 X's in a row horizontally
        for (x in 5..9) {
            repo.placeMove(x, 7, "X", "test-game")
        }
        
        val result = repo.placeMove(10, 7, "X", "test-game")
        assertTrue(result.winResult?.isWin == true)
        assertEquals(result.winResult?.winner, "X")
    }
}
```

### BLE debugging

```kotlin
// Add logging in CaroBleService
private fun logBleEvent(tag: String, message: String) {
    Log.d("CaroBLE", "$tag: $message")
}

// In callbacks:
override fun onCharacteristicChanged(...) {
    logBleEvent("NOTIFY", "Received: ${characteristic.uuid}")
    // ...
}
```

### React Native debugging

```typescript
// Add console logs in useCaroGame
useEffect(() => {
  eventEmitter?.addListener('onBoardUpdate', (data) => {
    console.log('[useCaroGame] Board update:', data);
    // ...
  });
}, []);
```

---

## 15. Common Issues & Solutions

### Issue: "Can't find ViewManager 'RNSScreenContentWrapper'"

**Cause:** `react-native-screens` not installed/compiled

**Fix:**
```bash
npm install react-native-screens
cd android && ./gradlew clean assembleDebug
```

### Issue: BLE notifications not received by challenger

**Cause:** CCCD (Client Characteristic Config Descriptor) not enabled

**Fix:** Ensure `gatt.setCharacteristicNotification()` and `writeDescriptor(ENABLE_NOTIFICATION_VALUE)` are called after service discovery.

### Issue: Win detection not triggering

**Cause:** WinChecker requires moves in database before board is built

**Fix:** Ensure `repo.placeMove()` completes before calling `WinChecker.check()`. Use `suspend` functions properly.

### Issue: Module "CaroGame" not found

**Cause:** TurboModule not registered in `MainApplication.kt`

**Fix:** Add `add(CaroGamePackage())` to `getPackages()` list.

---

## 16. Summary & Mental Models

### The three-layer architecture

```
Layer 1: React Native (UI)
  - Screens, components, hooks
  - NativeModules.CaroGame calls
  - Event subscription

Layer 2: Bridge (ReactModule + NativeEventEmitter)
  - @ReactMethod functions
  - Promise resolution
  - Event emission

Layer 3: Kotlin Logic
  - Room DB (data)
  - Repository (rules)
  - BLE Service (sync)
  - Game logic (WinChecker)
```

### Data flow patterns

**Imperative (push):**
- User taps cell → `placeMove(x, y)` → Promise resolves with result → UI updates

**Reactive (pull):**
- `repo.observeMoves: Flow<List<CaroMove>>`  → native emits `onBoardUpdate` → hook listens → state updates → UI re-renders

**Bi-directional sync:**
- Host has source of truth (database)
- Challengers send moves to host
- Host broadcasts to all subscribers
- All devices converge to same state

### Key mental models

1. **Game state = move list.** The board is computed from moves. Turn order, win condition, everything derives from the sequence.

2. **BLE is a transport layer.** It carries moves between devices. The game logic (win checking, validation) doesn't know about BLE.

3. **Repository is the gatekeeper.** All writes to database go through it. This ensures validation and consistency.

4. **Kotlin coroutines are async-first.** All database/BLE operations are suspend functions. Never block the main thread.

5. **React hook mirrors native state.** The `useCaroGame` hook is the JS side's view of the Kotlin game state. They're kept in sync via events.

---

## Conclusion

You've now built a **real-time multiplayer game** that demonstrates:
- ✓ Room database for persistence
- ✓ Kotlin coroutines for async/concurrent operations
- ✓ BLE GATT for device-to-device sync (no internet)
- ✓ Game logic with win detection
- ✓ React Native bridge for JS ↔ Kotlin communication
- ✓ Beautiful responsive UI with React Navigation
- ✓ Spectator mode for observers
- ✓ Turn-based validation and state management

Next steps: Test on real devices (BLE requires physical phones), add player names in auth flow, persist game history, and add replay mode.
