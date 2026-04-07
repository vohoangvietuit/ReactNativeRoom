# Kotlin + Room + Bluetooth Sync + React Native TurboModule

> A complete step-by-step guide: build a shared SQLite database that syncs across multiple Android devices via Bluetooth, exposed to React Native through a Turbo Native Module. From Kotlin basics to production patterns.

---

## Table of Contents

1. [What We're Building](#1-what-were-building)
2. [Kotlin Fundamentals for Android](#2-kotlin-fundamentals-for-android)
3. [Kotlin Coroutines — Async the Kotlin Way](#3-kotlin-coroutines--async-the-kotlin-way)
4. [SQLite on Android — The Foundation](#4-sqlite-on-android--the-foundation)
5. [Room Database — SQLite with Superpowers](#5-room-database--sqlite-with-superpowers)
6. [Architecture: Repository + ViewModel + Coroutines](#6-architecture-repository--viewmodel--coroutines)
7. [Android Services — Background Work](#7-android-services--background-work)
8. [Bluetooth Fundamentals on Android](#8-bluetooth-fundamentals-on-android)
9. [Building the BLE Sync Service](#9-building-the-ble-sync-service)
10. [The Sync Protocol — Sharing Data Between Devices](#10-the-sync-protocol--sharing-data-between-devices)
11. [React Native Turbo Native Modules — How They Work](#11-react-native-turbo-native-modules--how-they-work)
12. [Step 1: Define the TypeScript Spec (Codegen)](#12-step-1-define-the-typescript-spec-codegen)
13. [Step 2: Implement the Kotlin TurboModule](#13-step-2-implement-the-kotlin-turbomodule)
14. [Step 3: Register the TurboModule](#14-step-3-register-the-turbomodule)
15. [Step 4: Use the TurboModule in React Native](#15-step-4-use-the-turbomodule-in-react-native)
16. [Wiring Everything Together — Full Flow](#16-wiring-everything-together--full-flow)
17. [Testing](#17-testing)
18. [Common Pitfalls & Debugging](#18-common-pitfalls--debugging)
19. [Summary & Mental Model](#19-summary--mental-model)

---

## 1. What We're Building

A **shared note-taking app** where two (or more) Android phones can sync notes with each other over Bluetooth — no internet required.

### Architecture overview

```
┌──────────────────────────────────────────────────────────┐
│                  React Native (TypeScript)                │
│    NoteListScreen   →   useSharedNotes hook              │
│         ↕ calls TurboModule JS API                       │
├──────────────────────────────────────────────────────────┤
│              TurboModule (Kotlin bridge)                  │
│    SharedStorageModule.kt  ←→  NativeSharedStorage.ts    │
│         ↕ calls Repository                               │
├──────────────────────────────────────────────────────────┤
│              Native Android Layer (Kotlin)                │
│  NoteRepository  →  NoteDao  →  Room  →  SQLite .db      │
│  BluetoothSyncService  →  BLE GATT  →  Other Device      │
└──────────────────────────────────────────────────────────┘
```

### What each component does

| Component | What it does | Android concept |
|---|---|---|
| `Room` | ORM over SQLite — type-safe queries, migrations | Jetpack library |
| `NoteDao` | Data Access Object — query methods as Kotlin functions | Room interface |
| `NoteRepository` | Single source of truth — coordinates DB + Bluetooth | Architecture pattern |
| `BluetoothSyncService` | Runs in background, advertises + scans + syncs | Android Service |
| `TurboModule` | JNI-free bridge — React Native ↔ Kotlin | New Architecture |
| BLE GATT | Bluetooth Low Energy protocol for data transfer | Android BluetoothGatt |

### The sync strategy

We use **last-write-wins** per record. Each note has a `updatedAt` timestamp. When two devices connect, they exchange all records. Each side keeps whichever version has the newer `updatedAt`.

---

## 2. Kotlin Fundamentals for Android

Before writing any database or Bluetooth code, you need to feel comfortable with Kotlin syntax. Here are the features you'll use constantly.

### Variables

```kotlin
val name: String = "Watermelon"   // val = immutable (like const)
var count: Int = 0                 // var = mutable (like let)
count = 1                          // OK — var can be reassigned
// name = "Other"                  // ERROR — val cannot be reassigned
```

> **Kotlin type inference:** The compiler can infer types, so `val name = "Watermelon"` is equivalent to `val name: String = "Watermelon"`. Write the type explicitly in function signatures and class properties for clarity.

### Null safety — the `?` operator

Kotlin has null safety built into the type system. A regular `String` can never be null. A `String?` can.

```kotlin
var note: String = "Hello"
// note = null  // ← compile error! String is non-nullable

var maybeNote: String? = null   // OK — String? is nullable
val length = maybeNote?.length  // Safe call — returns null if maybeNote is null
val safe = maybeNote ?: "default"  // Elvis operator — fallback if null
```

### Data classes

Data classes are Kotlin's equivalent of TypeScript interfaces + a value class in one. They automatically generate `equals()`, `hashCode()`, `toString()`, and `copy()`:

```kotlin
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val updatedAt: Long,     // unix timestamp in ms
    val deviceId: String,    // which device created/modified this
)

// copy() creates a modified copy without mutation
val updated = note.copy(content = "New content", updatedAt = System.currentTimeMillis())
```

### Functions

```kotlin
// Named function
fun greet(name: String): String {
    return "Hello, $name!"   // String templates — $ interpolates variables
}

// Single-expression function (concise)
fun add(a: Int, b: Int): Int = a + b

// Default parameters
fun createNote(title: String, content: String = "") = Note(
    id = java.util.UUID.randomUUID().toString(),
    title = title,
    content = content,
    updatedAt = System.currentTimeMillis(),
    deviceId = DeviceInfo.id,
)
```

### Classes and constructors

```kotlin
// Primary constructor is in the class header
class NoteRepository(
    private val dao: NoteDao,
    private val bluetoothService: BluetoothSyncService,
) {
    // Methods
    suspend fun getAll(): List<Note> = dao.getAll()
}
```

### Lambdas

```kotlin
val doubled = listOf(1, 2, 3).map { it * 2 }   // [2, 4, 6]
// 'it' is the implicit name for single-parameter lambdas

val filtered = notes.filter { note -> note.title.isNotEmpty() }
val titles = notes.map { it.title }
```

### Extension functions

Extension functions add methods to existing classes without inheriting from them:

```kotlin
// Add a method to String, defined outside the class
fun String.toBase64(): String =
    android.util.Base64.encodeToString(this.toByteArray(), android.util.Base64.NO_WRAP)

// Usage
val encoded = "Hello".toBase64()
```

---

## 3. Kotlin Coroutines — Async the Kotlin Way

Coroutines are Kotlin's way to write asynchronous code that *looks* synchronous. Think of them as lightweight threads.

### The problem they solve

Without coroutines, network or database calls block the main thread, freezing the UI. The old solution was callbacks or RxJava. Coroutines let you write:

```kotlin
// This reads like synchronous code but runs asynchronously
suspend fun loadNotes(): List<Note> {
    val notes = dao.getAll()          // suspends — doesn't block the thread
    val synced = syncWithServer(notes) // suspends — network call
    return synced
}
```

The `suspend` keyword marks a function that can be paused and resumed. You can only call a `suspend` function from another `suspend` function or from a coroutine scope.

### Dispatchers — which thread runs the code

```kotlin
import kotlinx.coroutines.*

// Dispatchers.IO      — for database / network / file I/O (thread pool)
// Dispatchers.Main    — Android's main (UI) thread
// Dispatchers.Default — CPU-intensive work (thread pool)

// withContext switches the dispatcher for a block
suspend fun loadFromDb(): List<Note> = withContext(Dispatchers.IO) {
    dao.getAll()  // runs on the IO thread pool — never blocks UI
}

// Launch a fire-and-forget coroutine
viewModelScope.launch(Dispatchers.IO) {
    val notes = dao.getAll()
    withContext(Dispatchers.Main) {
        _notes.value = notes  // update UI state on main thread
    }
}
```

### CoroutineScope — the lifecycle owner

Every coroutine needs a scope. When the scope is cancelled, all coroutines inside it are cancelled too. This prevents memory leaks.

```kotlin
// viewModelScope — tied to ViewModel lifecycle (cancelled on ViewModel.onCleared())
class NoteViewModel(private val repo: NoteRepository) : ViewModel() {
    fun save(note: Note) {
        viewModelScope.launch {
            repo.save(note)
        }
    }
}

// lifecycleScope — tied to Activity/Fragment lifecycle
lifecycleScope.launch {
    val notes = repo.getAll()
    adapter.submitList(notes)
}
```

### Flow — reactive streams with coroutines

`Flow` is the coroutines equivalent of an Observable. It emits values over time, and you collect them:

```kotlin
// In DAO — returns a Flow that emits whenever the table changes
@Query("SELECT * FROM notes ORDER BY updated_at DESC")
fun observeAll(): Flow<List<Note>>

// In ViewModel — collect the flow into StateFlow (UI observes this)
val notes: StateFlow<List<Note>> = repo.observeAll()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )
```

### async / await — parallel operations

```kotlin
suspend fun syncAll() = coroutineScope {
    // Run both database fetch AND bluetooth scan in parallel
    val dbNotesDeferred = async(Dispatchers.IO) { dao.getAll() }
    val btNotesDeferred = async { bluetoothService.fetchFromPeer() }

    val dbNotes = dbNotesDeferred.await()
    val btNotes = btNotesDeferred.await()

    merge(dbNotes, btNotes)
}
```

---

## 4. SQLite on Android — The Foundation

SQLite is the database engine underlying Room. Understanding it makes Room's abstractions clearer.

### What SQLite gives you

- A complete relational database stored in a **single file** on the device
- Full SQL: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `JOIN`, `INDEX`
- ACID transactions — no partial writes
- Built into Android (no external dependency)

### Raw SQLite — the manual way

This is what Room replaces, but you need to understand it:

```kotlin
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class NoteDbHelper(context: Context) :
    SQLiteOpenHelper(context, "notes.db", null, 1) {

    // Called once when the DB is first created
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE notes (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                device_id TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_updated ON notes(updated_at)")
    }

    // Called when DB version changes (migrations)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN is_deleted INTEGER DEFAULT 0")
        }
    }
}

// Writing — must use ContentValues, not raw SQL (prevents injection)
fun insert(note: Note) {
    val db = dbHelper.writableDatabase
    val values = ContentValues().apply {
        put("id", note.id)
        put("title", note.title)
        put("content", note.content)
        put("updated_at", note.updatedAt)
        put("device_id", note.deviceId)
    }
    db.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

// Reading — Cursor (old Java style)
fun getAll(): List<Note> {
    val db = dbHelper.readableDatabase
    val cursor = db.query("notes", null, null, null, null, null, "updated_at DESC")
    val notes = mutableListOf<Note>()
    cursor.use {
        while (it.moveToNext()) {
            notes.add(Note(
                id = it.getString(it.getColumnIndexOrThrow("id")),
                title = it.getString(it.getColumnIndexOrThrow("title")),
                content = it.getString(it.getColumnIndexOrThrow("content")),
                updatedAt = it.getLong(it.getColumnIndexOrThrow("updated_at")),
                deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
            ))
        }
    }
    return notes
}
```

This works, but it's verbose, error-prone (typos in column names aren't caught at compile time), and requires manual cursor management. **Room solves all of this.**

---

## 5. Room Database — SQLite with Superpowers

Room is Google's officially recommended SQLite ORM for Android. It adds:
- Compile-time query verification (SQL errors become build errors)
- Coroutines + Flow support out of the box
- Type converters for complex types
- Automatic migrations (with tooling)
- No manual cursor management

### The three parts of Room

```
@Entity         →  defines the table (equivalent to CREATE TABLE)
@Dao            →  defines queries (equivalent to SQL functions)
@Database       →  wires everything together (the SQLiteOpenHelper equivalent)
```

### Step 1 — Add Room to build.gradle

```kotlin
// android/app/build.gradle.kts
plugins {
    id("com.google.devtools.ksp")   // Kotlin Symbol Processing (faster than kapt)
}

dependencies {
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")        // Coroutines support
    ksp("androidx.room:room-compiler:$roomVersion")               // Code generator
}
```

### Step 2 — Define the Entity (table)

```kotlin
// android/app/src/main/java/com/yourapp/database/NoteEntity.kt
import androidx.room.*

@Entity(
    tableName = "notes",
    indices = [Index(value = ["updated_at"]), Index(value = ["device_id"])],
)
data class NoteEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,   // soft delete for sync
)
```

> **Soft delete vs hard delete:** Never hard-delete records you sync. If Device A deletes a note and Device B hasn't synced yet, the next sync will resurrect it. Instead, set `isDeleted = true`. Both devices will see the deletion and eventually clean it up.

### Step 3 — Define the DAO (queries)

```kotlin
// android/app/src/main/java/com/yourapp/database/NoteDao.kt
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    // Flow — emits a new list every time the 'notes' table changes
    @Query("SELECT * FROM notes WHERE is_deleted = 0 ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // Suspend — one-time fetch (use in sync logic, not UI)
    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    // Get records changed after a timestamp (for incremental sync)
    @Query("SELECT * FROM notes WHERE updated_at > :since")
    suspend fun getChangedSince(since: Long): List<NoteEntity>

    // Find single record by ID
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoteEntity?

    // Upsert — insert or replace if ID already exists
    @Upsert
    suspend fun upsert(note: NoteEntity)

    // Bulk upsert — for sync (one transaction = fast)
    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    // Soft delete
    @Query("UPDATE notes SET is_deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())

    // Count — for debugging
    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int
}
```

**Why this is better than raw SQLite:**
- `@Query` SQL is validated at **compile time** — wrong column names are build errors
- `Flow<List<NoteEntity>>` is fully reactive with zero extra code
- `@Upsert` generates the `INSERT OR REPLACE` SQL for you
- No `ContentValues`, no `Cursor`, no column index lookups

### Step 4 — Define the Database

```kotlin
// android/app/src/main/java/com/yourapp/database/NoteDatabase.kt
import androidx.room.*
import android.content.Context

@Database(
    entities = [NoteEntity::class],
    version = 1,
    exportSchema = true,   // exports schema to JSON for migration tooling
)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        fun getInstance(context: Context): NoteDatabase {
            // Double-checked locking — ensures only one instance is created
            // even when called from multiple threads simultaneously
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                "shared_notes.db",
            )
            .addMigrations(MIGRATION_1_2)  // add migrations here
            .build()

        // Example migration: add 'tags' column in version 2
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT DEFAULT ''")
            }
        }
    }
}
```

---

## 6. Architecture: Repository + ViewModel + Coroutines

The standard Android architecture separates concerns into layers:

```
UI (Compose/React Native)
    ↕
ViewModel  ← holds UI state, survives config changes
    ↕
Repository ← single source of truth, coordinates DB + network/Bluetooth
    ↕
Room DAO   ← database access
    ↕
SQLite     ← actual storage
```

### NoteRepository

```kotlin
// android/app/src/main/java/com/yourapp/repository/NoteRepository.kt
import kotlinx.coroutines.flow.Flow

class NoteRepository(
    private val dao: NoteDao,
    private val bluetoothService: BluetoothSyncService,
) {
    // Expose a Flow for the UI — DB changes propagate automatically
    val allNotes: Flow<List<NoteEntity>> = dao.observeAll()

    // Create or update a note
    suspend fun save(entity: NoteEntity) {
        dao.upsert(entity)
        // Optionally push to BLE peers immediately
        bluetoothService.broadcastChange(entity)
    }

    // Delete (soft)
    suspend fun delete(id: String) {
        dao.softDelete(id)
    }

    // Called by BluetoothSyncService when peer sends data
    suspend fun applyRemoteChanges(remoteNotes: List<NoteEntity>) {
        val localNotes = dao.getAll().associateBy { it.id }

        val toUpsert = remoteNotes.filter { remote ->
            val local = localNotes[remote.id]
            // Accept if remote has newer timestamp, or if we don't have it
            local == null || remote.updatedAt > local.updatedAt
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
        }
    }

    // Returns records changed since a timestamp — used by sync
    suspend fun getChangesSince(since: Long): List<NoteEntity> =
        dao.getChangedSince(since)
}
```

### NoteViewModel (for native Android UI if needed)

```kotlin
// android/app/src/main/java/com/yourapp/viewmodel/NoteViewModel.kt
import androidx.lifecycle.*
import kotlinx.coroutines.flow.*

class NoteViewModel(private val repo: NoteRepository) : ViewModel() {

    // StateFlow — the UI observes this for note list updates
    val notes: StateFlow<List<NoteEntity>> = repo.allNotes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun save(id: String, title: String, content: String) {
        viewModelScope.launch {
            repo.save(NoteEntity(
                id = id.ifEmpty { java.util.UUID.randomUUID().toString() },
                title = title,
                content = content,
                updatedAt = System.currentTimeMillis(),
                deviceId = DeviceInfo.id,
            ))
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }
}
```

> **Why ViewModelScope?** When the user rotates the screen, the Activity is destroyed and recreated, but the `ViewModel` survives. Any coroutine in `viewModelScope` keeps running through rotation. When the user navigates away (back stack cleared), `ViewModel.onCleared()` is called and all coroutines are cancelled — preventing memory leaks.

---

## 7. Android Services — Background Work

A **Service** is an Android component that runs in the background without a UI. Our Bluetooth sync runs in a Service so it keeps working even when the app is in the background.

### Service types

| Type | When used | Example |
|---|---|---|
| **Started Service** | Long-running background task | Music player |
| **Bound Service** | Other components connect to it and call methods | Our BluetoothSyncService |
| **Foreground Service** | Visible to user (notification), never killed | GPS tracking |

### Bound Service — the pattern we use

A Bound Service provides a `Binder` that other classes use to call its methods directly:

```kotlin
// android/app/src/main/java/com/yourapp/service/BluetoothSyncService.kt
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class BluetoothSyncService : Service() {

    // The Binder returned to clients (ViewModel, TurboModule)
    inner class LocalBinder : Binder() {
        fun getService(): BluetoothSyncService = this@BluetoothSyncService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Initialize BLE manager, start advertising
        initBluetooth()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetooth()
    }

    // Public API — called by TurboModule / Repository
    fun broadcastChange(note: NoteEntity) { /* ... send over BLE */ }
    fun startScanning() { /* ... */ }
    fun stopScanning() { /* ... */ }
}
```

### Foreground Service (for reliable background operation)

For the Bluetooth sync to keep running reliably (Android aggressively kills background processes), declare it as a Foreground Service with a persistent notification:

```kotlin
override fun onCreate() {
    super.onCreate()

    val notification = NotificationCompat.Builder(this, "sync_channel")
        .setContentTitle("Shared Notes")
        .setContentText("Syncing with nearby devices…")
        .setSmallIcon(R.drawable.ic_sync)
        .build()

    startForeground(1, notification)   // ID must be > 0
}
```

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<service
    android:name=".service.BluetoothSyncService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

---

## 8. Bluetooth Fundamentals on Android

Android supports two Bluetooth variants. Choose based on your use case:

| | Classic Bluetooth (BR/EDR) | Bluetooth Low Energy (BLE) |
|---|---|---|
| **Speed** | High (up to 3 Mbps) | Low (20–80 KB/s effective) |
| **Power** | High | Low |
| **Range** | ~10m | ~10–40m |
| **Connection** | Paired devices | No pairing needed |
| **Best for** | Large file transfer, audio | Small, frequent data packets |
| **Android API** | `BluetoothSocket` | `BluetoothGatt` |

**We use BLE** because our notes are small text records, we want low power consumption, and we don't want to force users through a pairing flow.

### How BLE works

```
Device A (Peripheral / Server)          Device B (Central / Client)
┌───────────────────────────┐           ┌───────────────────────────┐
│  GATT Server               │           │  GATT Client               │
│                            │           │                            │
│  Service: "NoteSync"       │           │  Scan for "NoteSync"       │
│    Characteristic: READ    │ ←─read──  │  Read all notes            │
│    Characteristic: WRITE   │ ←─write── │  Send local changes        │
│    Characteristic: NOTIFY  │ ──notify→ │  Subscribe to updates      │
└───────────────────────────┘           └───────────────────────────┘
```

**Key concepts:**
- **Peripheral (Server):** Advertises itself, hosts a GATT server with Services and Characteristics
- **Central (Client):** Scans for peripherals, connects, reads/writes Characteristics
- **Service:** A logical grouping of Characteristics (identified by a UUID)
- **Characteristic:** A data point — can be read, written, or notify the client on change

### UUIDs — custom service identifiers

You define your own UUIDs so your app's devices recognize each other:

```kotlin
object BleConstants {
    // Generate these once — use https://www.uuidgenerator.net/
    val NOTE_SYNC_SERVICE_UUID: UUID =
        UUID.fromString("12345678-1234-1234-1234-123456789abc")

    val NOTE_DATA_CHAR_UUID: UUID =        // used for write (client → server)
        UUID.fromString("12345678-1234-1234-1234-123456789abd")

    val SYNC_REQUEST_CHAR_UUID: UUID =      // client reads this to trigger sync
        UUID.fromString("12345678-1234-1234-1234-123456789abe")

    val NOTIFY_CHAR_UUID: UUID =            // server notifies client of new records
        UUID.fromString("12345678-1234-1234-1234-123456789abf")

    val MTU_SIZE = 512  // Max bytes per BLE packet (negotiated)
}
```

### Required permissions

Add to `AndroidManifest.xml`:

```xml
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## 9. Building the BLE Sync Service

The service has two roles that run simultaneously:
1. **Peripheral:** Advertises presence, serves a GATT server so other devices can connect and push/pull data
2. **Central:** Scans for other devices running the same service, connects, and syncs

### The full BluetoothSyncService

```kotlin
// android/app/src/main/java/com/yourapp/service/BluetoothSyncService.kt
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BluetoothSyncService(
    private val context: Context,
    private val repository: NoteRepository,
) : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter get() = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    // ── Peripheral side — advertise + GATT server ──────────────────────────

    fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.NOTE_SYNC_SERVICE_UUID))
            .setIncludeDeviceName(false)   // save bytes
            .build()

        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
        setupGattServer()
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            BleConstants.NOTE_SYNC_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // WRITE characteristic — remote device sends note data here
        val writeChar = BluetoothGattCharacteristic(
            BleConstants.NOTE_DATA_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // NOTIFY characteristic — we push updates to connected devices
        val notifyChar = BluetoothGattCharacteristic(
            BleConstants.NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // CCCD
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ,
            ))
        }

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
            } else {
                connectedDevices.remove(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleConstants.NOTE_DATA_CHAR_UUID) {
                scope.launch {
                    val json = String(value, Charsets.UTF_8)
                    val remoteNotes = Json.decodeFromString<List<NoteEntity>>(json)
                    repository.applyRemoteChanges(remoteNotes)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // Notify all connected devices of a change
    fun broadcastChange(note: NoteEntity) {
        val json = Json.encodeToString(note)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val notifyChar = gattServer
            ?.getService(BleConstants.NOTE_SYNC_SERVICE_UUID)
            ?.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID) ?: return

        notifyChar.value = bytes
        connectedDevices.forEach { device ->
            gattServer?.notifyCharacteristicChanged(device, notifyChar, false)
        }
    }

    // ── Central side — scan + connect to peers ─────────────────────────────

    fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.NOTE_SYNC_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch { syncWithDevice(result.device) }
        }
    }

    private suspend fun syncWithDevice(device: BluetoothDevice) {
        // Get our changes since last sync with this device
        val lastSync = getLastSyncTime(device.address)
        val localChanges = repository.getChangesSince(lastSync)

        val json = Json.encodeToString(localChanges)
        val bytes = json.toByteArray(Charsets.UTF_8)

        // Connect and write our changes
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestMtu(BleConstants.MTU_SIZE)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val char = gatt
                    .getService(BleConstants.NOTE_SYNC_SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.NOTE_DATA_CHAR_UUID) ?: return

                // BLE max write is MTU-3 bytes — chunk large payloads
                bytes.chunked(BleConstants.MTU_SIZE - 3).forEach { chunk ->
                    char.value = chunk.toByteArray()
                    gatt.writeCharacteristic(char)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    saveLastSyncTime(device.address, System.currentTimeMillis())
                    gatt.disconnect()
                }
            }
        })
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun getLastSyncTime(deviceAddress: String): Long =
        context.getSharedPreferences("ble_sync", Context.MODE_PRIVATE)
            .getLong("last_sync_$deviceAddress", 0L)

    private fun saveLastSyncTime(deviceAddress: String, time: Long) =
        context.getSharedPreferences("ble_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_$deviceAddress", time).apply()

    override fun onBind(intent: Intent) = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService() = this@BluetoothSyncService
    }
}
```

> **Chunking:** BLE packets are limited to `MTU - 3` bytes (the `MTU_SIZE` negotiated with `requestMtu()`). For notes with long content, split the JSON into chunks. A more robust approach uses the BLE "long write" (prepared write) feature or compresses JSON before sending.

---

## 10. The Sync Protocol — Sharing Data Between Devices

The sync logic is the heart of the app. Here's the complete protocol visualized:

```
Device A (initiator)                      Device B (responder)

1. Scan for peers ──────────────────→
                        ←────────────── 2. Advertise UUID

3. Connect ──────────────────────────→
4. requestMtu(512) ──────────────────→
                        ←────────────── 5. MTU acknowledged

6. discoverServices ─────────────────→
                        ←────────────── 7. Services list

8. Write localChanges (chunked) ─────→
                        ←────────────── 9. GATT_SUCCESS per chunk

10. B receives all chunks
11. B decodes JSON → List<NoteEntity>
12. B applies last-write-wins merge
13. B broadcasts NOTIFY to its clients with new state

14. A disconnects ───────────────────→
15. A saves lastSyncTime for B
```

### Last-write-wins merge

```kotlin
suspend fun applyRemoteChanges(remoteNotes: List<NoteEntity>) {
    val localMap = dao.getAll().associateBy { it.id }

    val winners = remoteNotes.mapNotNull { remote ->
        val local = localMap[remote.id]
        when {
            local == null -> remote                        // new record — accept
            remote.updatedAt > local.updatedAt -> remote  // remote newer — accept
            else -> null                                   // local newer — reject
        }
    }

    if (winners.isNotEmpty()) {
        dao.upsertAll(winners)
    }
}
```

### Handling large payloads

For notes with long content, the JSON might exceed `(MTU - 3)` bytes. Implement a simple length-prefix framing protocol:

```kotlin
// Sender: prepend 4-byte length header
fun framePayload(data: ByteArray): List<ByteArray> {
    val header = ByteBuffer.allocate(4).putInt(data.size).array()
    val full = header + data
    return full.toList().chunked(BleConstants.MTU_SIZE - 3) { it.toByteArray() }
}

// Receiver: collect chunks until length is satisfied
class ChunkAssembler {
    private val buffer = mutableListOf<Byte>()
    private var expectedLength = -1

    fun append(chunk: ByteArray): ByteArray? {
        buffer.addAll(chunk.toList())
        if (expectedLength < 0 && buffer.size >= 4) {
            expectedLength = ByteBuffer.wrap(buffer.take(4).toByteArray()).int
            repeat(4) { buffer.removeFirst() }
        }
        return if (expectedLength > 0 && buffer.size >= expectedLength) {
            buffer.take(expectedLength).toByteArray().also { buffer.clear(); expectedLength = -1 }
        } else null
    }
}
```

---

## 11. React Native Turbo Native Modules — How They Work

Before the New Architecture (Turbo Modules), React Native used an asynchronous **Bridge** — all JS↔Native calls were serialized to JSON and sent across an async message queue. This was slow for high-frequency calls.

**Turbo Modules** communicate directly using **JSI (JavaScript Interface)** — a C++ layer that lets JavaScript call native functions synchronously (when needed) without JSON serialization.

### The three files required for a TurboModule

```
1. NativeSharedStorage.ts      ← TypeScript spec (source of truth)
        ↓ (Codegen generates)
2. NativeSharedStorage.h/.cpp  ← C++ interface (generated, don't edit)
        ↑ (Kotlin implements)
3. SharedStorageModule.kt      ← Your Kotlin implementation
```

**Codegen** reads your TypeScript spec and generates the C++ bridge code. You write TypeScript, Codegen generates boilerplate, you implement in Kotlin. No JNI by hand.

---

## 12. Step 1: Define the TypeScript Spec (Codegen)

The spec file is the contract. Every method here will be callable from JavaScript.

```ts
// src/specs/NativeSharedStorage.ts
import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

// These types map to Kotlin types via JSI:
// string  → String
// number  → Double / Long (use ReadableMap for precision)
// boolean → Boolean
// Object  → ReadableMap
// Array   → ReadableArray
// Promise → Promise<T>

export type NoteData = {
  id: string;
  title: string;
  content: string;
  updatedAt: number;   // unix ms — JS numbers are doubles, fine up to 2^53
  deviceId: string;
  isDeleted: boolean;
};

export interface Spec extends TurboModule {
  // CRUD
  saveNote(note: NoteData): Promise<void>;
  deleteNote(id: string): Promise<void>;
  getAllNotes(): Promise<NoteData[]>;
  getNoteById(id: string): Promise<NoteData | null>;

  // Bluetooth sync
  startBluetoothSync(): Promise<void>;
  stopBluetoothSync(): Promise<void>;
  getSyncStatus(): Promise<string>;  // 'idle' | 'scanning' | 'syncing' | 'error'

  // Event emitter — JS subscribes to DB changes pushed from Bluetooth
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('SharedStorage');
```

### Register the spec in package.json

Add a `codegenConfig` section to `mobile/package.json`:

```json
{
  "codegenConfig": {
    "name": "SharedStorageSpec",
    "type": "modules",
    "jsSrcsDir": "src/specs",
    "android": {
      "javaPackageName": "com.yourapp.turbo"
    }
  }
}
```

Run Codegen:

```sh
cd android
./gradlew generateCodegenArtifactsFromSchema
```

This generates Java/Kotlin interfaces in `android/app/build/generated/`.

---

## 13. Step 2: Implement the Kotlin TurboModule

```kotlin
// android/app/src/main/java/com/yourapp/turbo/SharedStorageModule.kt
package com.yourapp.turbo

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.yourapp.database.NoteDatabase
import com.yourapp.database.NoteEntity
import com.yourapp.repository.NoteRepository
import com.yourapp.service.BluetoothSyncService
import kotlinx.coroutines.*

// @ReactModule registers metadata (name must match TurboModuleRegistry.getEnforcing('SharedStorage'))
@ReactModule(name = SharedStorageModule.NAME)
class SharedStorageModule(reactContext: ReactApplicationContext) :
    NativeSharedStorageSpec(reactContext) {  // NativeSharedStorageSpec is Codegen-generated

    companion object {
        const val NAME = "SharedStorage"
    }

    override fun getName() = NAME

    // Coroutine scope for all async work — tied to module lifecycle
    private val moduleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Lazy-init so we don't create DB on the main thread
    private val db by lazy { NoteDatabase.getInstance(reactApplicationContext) }
    private val dao by lazy { db.noteDao() }
    private val repo by lazy {
        NoteRepository(dao, bluetoothService)
    }

    private var bluetoothService: BluetoothSyncService? = null
    private var syncStatus = "idle"

    // ── Helper: convert NoteEntity → ReadableMap (JS object) ──────────────

    private fun NoteEntity.toReadableMap(): WritableMap =
        Arguments.createMap().apply {
            putString("id", id)
            putString("title", title)
            putString("content", content)
            putDouble("updatedAt", updatedAt.toDouble())
            putString("deviceId", deviceId)
            putBoolean("isDeleted", isDeleted)
        }

    // ── Helper: convert ReadableMap → NoteEntity ──────────────────────────

    private fun ReadableMap.toNoteEntity() = NoteEntity(
        id = getString("id") ?: throw IllegalArgumentException("id required"),
        title = getString("title") ?: "",
        content = getString("content") ?: "",
        updatedAt = getDouble("updatedAt").toLong(),
        deviceId = getString("deviceId") ?: "",
        isDeleted = getBoolean("isDeleted"),
    )

    // ── CRUD methods ───────────────────────────────────────────────────────

    override fun saveNote(note: ReadableMap, promise: Promise) {
        moduleScope.launch {
            runCatching {
                repo.save(note.toNoteEntity())
            }.fold(
                onSuccess = { promise.resolve(null) },
                onFailure = { promise.reject("SAVE_ERROR", it.message, it) },
            )
        }
    }

    override fun deleteNote(id: String, promise: Promise) {
        moduleScope.launch {
            runCatching { repo.delete(id) }
                .fold(
                    onSuccess = { promise.resolve(null) },
                    onFailure = { promise.reject("DELETE_ERROR", it.message, it) },
                )
        }
    }

    override fun getAllNotes(promise: Promise) {
        moduleScope.launch {
            runCatching { dao.getAll() }
                .fold(
                    onSuccess = { notes ->
                        val array = Arguments.createArray().apply {
                            notes.forEach { pushMap(it.toReadableMap()) }
                        }
                        promise.resolve(array)
                    },
                    onFailure = { promise.reject("FETCH_ERROR", it.message, it) },
                )
        }
    }

    override fun getNoteById(id: String, promise: Promise) {
        moduleScope.launch {
            runCatching { dao.findById(id) }
                .fold(
                    onSuccess = { note ->
                        promise.resolve(note?.toReadableMap())  // null is valid
                    },
                    onFailure = { promise.reject("FETCH_ERROR", it.message, it) },
                )
        }
    }

    // ── Bluetooth methods ──────────────────────────────────────────────────

    override fun startBluetoothSync(promise: Promise) {
        val intent = Intent(reactApplicationContext, BluetoothSyncService::class.java)
        reactApplicationContext.startForegroundService(intent)

        reactApplicationContext.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bluetoothService = (binder as BluetoothSyncService.LocalBinder).getService()
                bluetoothService?.startAdvertising()
                bluetoothService?.startScanning()
                syncStatus = "scanning"
                promise.resolve(null)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                bluetoothService = null
                syncStatus = "idle"
            }
        }, Context.BIND_AUTO_CREATE)
    }

    override fun stopBluetoothSync(promise: Promise) {
        bluetoothService?.stopScanning()
        syncStatus = "idle"
        promise.resolve(null)
    }

    override fun getSyncStatus(promise: Promise) {
        promise.resolve(syncStatus)
    }

    // ── Event emitter — push DB changes to JS ─────────────────────────────

    // Required by NativeEventEmitter in JS
    override fun addListener(eventName: String) {
        if (eventName == "onNotesChanged") {
            // Start a coroutine that watches the Flow and emits events to JS
            moduleScope.launch {
                dao.observeAll().collect { notes ->
                    val array = Arguments.createArray().apply {
                        notes.forEach { pushMap(it.toReadableMap()) }
                    }
                    sendEvent("onNotesChanged", array)
                }
            }
        }
    }

    override fun removeListeners(count: Double) {
        // Clean up if needed
    }

    private fun sendEvent(eventName: String, data: Any?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, data)
    }

    override fun invalidate() {
        super.invalidate()
        moduleScope.cancel()   // cancel all coroutines when module is destroyed
    }
}
```

---

## 14. Step 3: Register the TurboModule

### Create the Package

```kotlin
// android/app/src/main/java/com/yourapp/turbo/SharedStoragePackage.kt
package com.yourapp.turbo

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class SharedStoragePackage : TurboReactPackage() {

    override fun getModule(name: String, context: ReactApplicationContext) =
        if (name == SharedStorageModule.NAME) SharedStorageModule(context) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            SharedStorageModule.NAME to ReactModuleInfo(
                SharedStorageModule.NAME,
                SharedStorageModule.NAME,
                false,   // canOverrideExistingModule
                false,   // needsEagerInit
                true,    // isCxxModule
                true,    // isTurboModule ← CRITICAL — marks as Turbo
            )
        )
    }
}
```

### Register in MainApplication

```kotlin
// android/app/src/main/java/com/yourapp/MainApplication.kt
import com.yourapp.turbo.SharedStoragePackage

class MainApplication : Application(), ReactApplication {
    override val reactNativeHost = object : DefaultReactNativeHost(this) {
        override fun getPackages() = PackageList(this).packages.apply {
            add(SharedStoragePackage())   // ← add our package
        }
        override fun getUseDeveloperSupport() = BuildConfig.DEBUG
        override val isNewArchEnabled = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        override val isHermesEnabled = BuildConfig.IS_HERMES_ENABLED
    }
}
```

Enable New Architecture in `gradle.properties`:

```properties
newArchEnabled=true
```

---

## 15. Step 4: Use the TurboModule in React Native

### Create a typed wrapper

Never call the TurboModule spec directly from components — wrap it in a service layer:

```ts
// src/services/sharedStorage.ts
import NativeSharedStorage, {type NoteData} from '../specs/NativeSharedStorage';
import {NativeEventEmitter, Platform} from 'react-native';

// Re-export the type so callers don't import from specs/
export type {NoteData};

export const sharedStorage = {
  saveNote: (note: NoteData): Promise<void> =>
    NativeSharedStorage.saveNote(note),

  deleteNote: (id: string): Promise<void> =>
    NativeSharedStorage.deleteNote(id),

  getAllNotes: (): Promise<NoteData[]> =>
    NativeSharedStorage.getAllNotes(),

  getNoteById: (id: string): Promise<NoteData | null> =>
    NativeSharedStorage.getNoteById(id),

  startSync: () => NativeSharedStorage.startBluetoothSync(),
  stopSync: () => NativeSharedStorage.stopBluetoothSync(),
  getSyncStatus: () => NativeSharedStorage.getSyncStatus(),
};

// Event emitter — subscribe to DB changes pushed from BLE
const emitter = new NativeEventEmitter(NativeSharedStorage);

export function onNotesChanged(callback: (notes: NoteData[]) => void) {
  const sub = emitter.addListener('onNotesChanged', callback);
  return () => sub.remove();  // return cleanup function
}
```

### Custom hook for notes

```ts
// src/features/notes/hooks/useSharedNotes.ts
import {useState, useEffect, useCallback} from 'react';
import {sharedStorage, onNotesChanged, type NoteData} from '../../../services/sharedStorage';

const useSharedNotes = () => {
  const [notes, setNotes] = useState<NoteData[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncStatus, setSyncStatus] = useState<string>('idle');

  // Load initial data
  useEffect(() => {
    sharedStorage.getAllNotes()
      .then(setNotes)
      .finally(() => setLoading(false));
  }, []);

  // Subscribe to live DB changes (triggered by BLE sync or local writes)
  useEffect(() => {
    const unsubscribe = onNotesChanged(setNotes);
    return unsubscribe;
  }, []);

  // Poll sync status
  useEffect(() => {
    const interval = setInterval(async () => {
      const status = await sharedStorage.getSyncStatus();
      setSyncStatus(status);
    }, 2000);
    return () => clearInterval(interval);
  }, []);

  const saveNote = useCallback(async (id: string, title: string, content: string) => {
    const note: NoteData = {
      id: id || `note_${Date.now()}`,
      title,
      content,
      updatedAt: Date.now(),
      deviceId: 'this_device',   // replace with actual device ID
      isDeleted: false,
    };
    await sharedStorage.saveNote(note);
    // No need to update local state — onNotesChanged event handles it
  }, []);

  const deleteNote = useCallback(async (id: string) => {
    await sharedStorage.deleteNote(id);
  }, []);

  const startSync = useCallback(async () => {
    await sharedStorage.startSync();
    setSyncStatus('scanning');
  }, []);

  return {notes, loading, syncStatus, saveNote, deleteNote, startSync};
};

export default useSharedNotes;
```

### NoteListScreen

```tsx
// src/features/notes/screens/NoteListScreen.tsx
import React, {useState} from 'react';
import {
  FlatList, View, Text, TextInput, TouchableOpacity,
  StyleSheet, ActivityIndicator,
} from 'react-native';
import useSharedNotes from '../hooks/useSharedNotes';
import {colors} from '../../../theme';

const NoteListScreen = () => {
  const {notes, loading, syncStatus, saveNote, deleteNote, startSync} = useSharedNotes();
  const [newTitle, setNewTitle] = useState('');

  const handleAdd = async () => {
    if (!newTitle.trim()) { return; }
    await saveNote('', newTitle.trim(), '');
    setNewTitle('');
  };

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.primary} />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* Sync status banner */}
      <View style={[styles.banner, syncStatus !== 'idle' && styles.bannerActive]}>
        <Text style={styles.bannerText}>
          {syncStatus === 'idle'    ? 'Offline — tap Sync to find nearby devices'   : ''}
          {syncStatus === 'scanning' ? '🔵 Scanning for nearby devices…'             : ''}
          {syncStatus === 'syncing'  ? '🔄 Syncing…'                                 : ''}
          {syncStatus === 'error'    ? '⚠️ Sync error — tap to retry'                : ''}
        </Text>
      </View>

      {/* Add note input */}
      <View style={styles.inputRow}>
        <TextInput
          style={styles.input}
          value={newTitle}
          onChangeText={setNewTitle}
          placeholder="New note title…"
          placeholderTextColor={colors.textMuted}
          onSubmitEditing={handleAdd}
        />
        <TouchableOpacity style={styles.addBtn} onPress={handleAdd}>
          <Text style={styles.addBtnText}>Add</Text>
        </TouchableOpacity>
      </View>

      {/* Note list */}
      <FlatList
        data={notes}
        keyExtractor={n => n.id}
        renderItem={({item}) => (
          <View style={styles.card}>
            <Text style={styles.noteTitle}>{item.title}</Text>
            <Text style={styles.noteMeta}>
              {new Date(item.updatedAt).toLocaleString()} · {item.deviceId}
            </Text>
            <TouchableOpacity onPress={() => deleteNote(item.id)}>
              <Text style={styles.delete}>Delete</Text>
            </TouchableOpacity>
          </View>
        )}
        ListEmptyComponent={<Text style={styles.empty}>No notes yet. Add one above.</Text>}
      />

      {/* Sync button */}
      <TouchableOpacity style={styles.syncBtn} onPress={startSync}>
        <Text style={styles.syncBtnText}>
          {syncStatus === 'idle' ? '📡 Start Bluetooth Sync' : '🔵 Syncing…'}
        </Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container:   {flex: 1, backgroundColor: colors.background},
  center:      {flex: 1, alignItems: 'center', justifyContent: 'center'},
  banner:      {padding: 8, backgroundColor: colors.background},
  bannerActive:{backgroundColor: colors.primaryLight},
  bannerText:  {fontSize: 12, color: colors.textMuted, textAlign: 'center'},
  inputRow:    {flexDirection: 'row', padding: 16, gap: 8},
  input:       {flex: 1, height: 44, borderRadius: 8, borderWidth: 1, borderColor: colors.inputBorder, paddingHorizontal: 12, color: colors.textPrimary},
  addBtn:      {height: 44, paddingHorizontal: 16, borderRadius: 8, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center'},
  addBtnText:  {fontWeight: '700', color: colors.textHeading},
  card:        {padding: 16, borderBottomWidth: 1, borderBottomColor: colors.border},
  noteTitle:   {fontSize: 16, fontWeight: '600', color: colors.textHeading},
  noteMeta:    {fontSize: 11, color: colors.textMuted, marginTop: 4},
  delete:      {color: colors.error, marginTop: 8, fontSize: 13},
  empty:       {textAlign: 'center', color: colors.textMuted, marginTop: 48},
  syncBtn:     {margin: 16, height: 52, borderRadius: 12, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center'},
  syncBtnText: {fontWeight: '700', fontSize: 16, color: colors.textHeading},
});

export {NoteListScreen};
```

---

## 16. Wiring Everything Together — Full Flow

Here's the complete journey of data through the system:

### Flow 1: User creates a note

```
User types → onSubmitEditing
  → saveNote(title) in hook
  → sharedStorage.saveNote(note)
  → NativeSharedStorage.saveNote() [TurboModule JSI call]
  → SharedStorageModule.kt.saveNote()
  → coroutine on Dispatchers.IO
  → repo.save(entity)
  → dao.upsert(entity)        [Room → SQLite INSERT OR REPLACE]
  → Flow emits new list
  → SharedStorageModule collects Flow
  → sendEvent("onNotesChanged", notes)   [JS event]
  → NativeEventEmitter fires callback
  → setNotes(notes) in hook
  → FlatList re-renders
```

### Flow 2: Bluetooth sync

```
startSync() → BluetoothSyncService starts/binds
  → startAdvertising()     [this device is now discoverable]
  → startScanning()        [scanning for other devices]

[Other device found]
  → onScanResult fires
  → syncWithDevice(remoteDevice)
  → connectGatt()
  → onServicesDiscovered
  → writeCharacteristic(localChanges JSON)

[Remote device receives write]
  → onCharacteristicWriteRequest fires in remote gattServerCallback
  → repo.applyRemoteChanges(remoteNotes)
  → dao.upsertAll(winners)   [last-write-wins merge]
  → Flow emits updated list
  → onNotesChanged event fires on remote device's React Native
  → Remote UI re-renders with merged notes
```

### Project folder structure

```
android/app/src/main/java/com/yourapp/
├── database/
│   ├── NoteDatabase.kt       ← Room database singleton
│   ├── NoteDao.kt            ← Query methods
│   └── NoteEntity.kt         ← @Entity table definition
├── repository/
│   └── NoteRepository.kt     ← Business logic, merge strategy
├── service/
│   └── BluetoothSyncService.kt  ← BLE peripheral + central
├── turbo/
│   ├── SharedStorageModule.kt   ← TurboModule implementation
│   └── SharedStoragePackage.kt  ← Package registration
└── MainApplication.kt

mobile/src/
├── specs/
│   └── NativeSharedStorage.ts   ← Codegen spec
├── services/
│   └── sharedStorage.ts         ← Typed wrapper
└── features/
    └── notes/
        ├── hooks/
        │   └── useSharedNotes.ts
        └── screens/
            └── NoteListScreen.tsx
```

---

## 17. Testing

### Unit test: Repository merge logic

```kotlin
// NoteRepositoryTest.kt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class NoteRepositoryTest {

    private val dao: NoteDao = mock()
    private val btService: BluetoothSyncService = mock()
    private val repo = NoteRepository(dao, btService)

    @Test
    fun `applyRemoteChanges accepts newer remote record`() = runTest {
        val localNote = NoteEntity("1", "Local title", "", 1000L, "deviceA")
        val remoteNote = NoteEntity("1", "Remote title", "", 2000L, "deviceB")

        whenever(dao.getAll()).thenReturn(listOf(localNote))

        repo.applyRemoteChanges(listOf(remoteNote))

        // The remote note (newer) should be upserted
        verify(dao).upsertAll(listOf(remoteNote))
    }

    @Test
    fun `applyRemoteChanges rejects older remote record`() = runTest {
        val localNote = NoteEntity("1", "Local title", "", 3000L, "deviceA")
        val remoteNote = NoteEntity("1", "Old title", "", 1000L, "deviceB")

        whenever(dao.getAll()).thenReturn(listOf(localNote))

        repo.applyRemoteChanges(listOf(remoteNote))

        // Nothing should be written — local is newer
        verify(dao, never()).upsertAll(any())
    }
}
```

### Room DAO test with in-memory DB

```kotlin
// NoteDaoTest.kt
@RunWith(AndroidJUnit4::class)
class NoteDaoTest {

    private lateinit var db: NoteDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setup() {
        // In-memory DB — isolated, auto-destroyed after test
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NoteDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun insertAndQuery() = runBlocking {
        val note = NoteEntity("1", "Test", "Body", 1000L, "device1")
        dao.upsert(note)

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("Test", all[0].title)
    }

    @Test
    fun softDeleteHidesFromObserve() = runBlocking {
        val note = NoteEntity("1", "Test", "", 1000L, "device1")
        dao.upsert(note)
        dao.softDelete("1")

        // getAll returns all INCLUDING deleted (for sync)
        assertEquals(1, dao.getAll().size)
        // but the count of active notes is 0
        assertEquals(0, dao.observeAll().first().size)
    }
}
```

### TurboModule: test with mock data

```ts
// __tests__/useSharedNotes.test.ts
jest.mock('../../../specs/NativeSharedStorage', () => ({
  getAllNotes: jest.fn().mockResolvedValue([
    {id: '1', title: 'Test', content: '', updatedAt: 1000, deviceId: 'dev1', isDeleted: false},
  ]),
  saveNote: jest.fn().mockResolvedValue(undefined),
  deleteNote: jest.fn().mockResolvedValue(undefined),
  startBluetoothSync: jest.fn().mockResolvedValue(undefined),
  stopBluetoothSync: jest.fn().mockResolvedValue(undefined),
  getSyncStatus: jest.fn().mockResolvedValue('idle'),
  addListener: jest.fn(),
  removeListeners: jest.fn(),
}));

import {renderHook, act} from '@testing-library/react-native';
import useSharedNotes from '../hooks/useSharedNotes';

it('loads notes on mount', async () => {
  const {result} = renderHook(() => useSharedNotes());
  await act(async () => {});  // flush promises

  expect(result.current.notes).toHaveLength(1);
  expect(result.current.notes[0].title).toBe('Test');
  expect(result.current.loading).toBe(false);
});
```

---

## 18. Common Pitfalls & Debugging

### "Module not found: SharedStorage"

The TurboModule wasn't registered. Check:
1. `SharedStoragePackage` is added in `MainApplication.kt`
2. `isTurboModule = true` in `ReactModuleInfo`
3. `newArchEnabled=true` in `gradle.properties`
4. The module `NAME` constant matches the string in `TurboModuleRegistry.getEnforcing('SharedStorage')`

### Room: "Cannot access database on the main thread"

You called a `suspend` DAO method without `Dispatchers.IO`:

```kotlin
// ❌ Wrong — will throw on main thread
val notes = dao.getAll()

// ✅ Correct
val notes = withContext(Dispatchers.IO) { dao.getAll() }
// or use viewModelScope.launch(Dispatchers.IO) { ... }
```

### BLE: "Bluetooth not available" on emulator

Android emulators don't support real BLE. Test on **physical devices**. Use `BluetoothAdapter.getDefaultAdapter() == null` to guard emulator builds.

### BLE: Data truncated / corrupted

MTU negotiation is async. Always wait for `onMtuChanged` before sending data. And always chunk writes to `mtu - 3` bytes:

```kotlin
// MTU 3 bytes are reserved for BLE ATT protocol header
val maxChunk = negotiatedMtu - 3
```

### Room migration crash on schema change

If you add a column and forget to add a migration, Room throws `IllegalStateException: Room cannot verify the data integrity`. Fix:

1. Add a `Migration` object with the `ALTER TABLE` SQL
2. Pass it to `.addMigrations()` in the database builder
3. Increment `version` in `@Database`

During development, use `.fallbackToDestructiveMigration()` to auto-wipe the DB on version change. **Never use this in production.**

### TurboModule: Promise never resolves

If you call `promise.resolve()` or `promise.reject()` from the wrong thread, or forget to call it at all, the JS `await` hangs forever. Use `runCatching` with `fold` to always settle the promise:

```kotlin
moduleScope.launch {
    runCatching { /* your operation */ }
        .fold(
            onSuccess = { promise.resolve(it) },
            onFailure = { promise.reject("ERR", it.message, it) },
        )
}
```

### Memory leak: Flow collected without cancellation

Every `dao.observeAll().collect {}` in a coroutine keeps running until the scope is cancelled. Always cancel `moduleScope` in `invalidate()`:

```kotlin
override fun invalidate() {
    super.invalidate()
    moduleScope.cancel()
}
```

---

## 19. Summary & Mental Model

### The complete data flow

```
React Native JS          TurboModule (Kotlin)      Room + SQLite        Bluetooth
─────────────────        ──────────────────────    ─────────────────    ─────────────────

useSharedNotes hook
  │
  ├── getAllNotes() ──JSI──→ SharedStorageModule
  │                              │ launch(IO)
  │                              └──────────────→ dao.getAll()
  │                                                    │
  │                              ←── List<NoteEntity> ─┘
  │   ←── NoteData[] ───────────┘
  │
  ├── subscribe to           addListener() ──────→ dao.observeAll()
  │   "onNotesChanged"                               (Flow)
  │                                                   │ (emits on change)
  │   ←── event fired ←── sendEvent() ←─────────────┘
  │
  └── startSync() ──JSI──→ SharedStorageModule
                                │
                                └──────────────→ BluetoothSyncService
                                                    │ startAdvertising()
                                                    │ startScanning()
                                                    │
                                [peer found] ───────┘
                                                    │ syncWithDevice()
                                                    │ writeCharacteristic(changes)
                                                    │
                                [peer receives] →   repo.applyRemoteChanges()
                                                    dao.upsertAll(winners)
                                                    Flow emits → sendEvent()
```

### Key concepts summary

| Concept | What it is | Key rule |
|---|---|---|
| Room `@Entity` | Table definition | One class per table, use `@PrimaryKey` |
| Room `@Dao` | Query interface | Use `Flow<>` for reactive queries, `suspend` for one-time |
| Room `@Database` | Singleton wiring | One instance per app, use `synchronized` double-check |
| Coroutine `Dispatcher` | Which thread runs | DB/network on `IO`, UI updates on `Main` |
| `CoroutineScope` | Lifetime of coroutines | Always cancel scope on cleanup to prevent leaks |
| `Flow` | Reactive stream | Collect in `viewModelScope` or `moduleScope`, not `GlobalScope` |
| Bluetooth GATT Server | Peripheral role | Hosts Services + Characteristics, receives writes |
| Bluetooth GATT Client | Central role | Connects to peripherals, writes changes |
| MTU | Max packet size | Chunk all writes to `mtu - 3` bytes |
| Last-write-wins | Merge strategy | Keep the record with the higher `updatedAt` |
| TurboModule | Native bridge | JSI — no JSON serialization overhead |
| Codegen spec | TypeScript contract | `Spec extends TurboModule` — everything typed |
| `Promise` in TurboModule | Async return | Always call `resolve` OR `reject` — never both, never neither |

### Resources referenced in this guide

| Topic | Link |
|---|---|
| Android Kotlin basics | https://developer.android.com/courses/android-basics-compose/course |
| Room database | https://developer.android.com/training/data-storage/room/ |
| SQLite on Android | https://developer.android.com/training/data-storage/sqlite |
| Kotlin coroutines | https://kotlinlang.org/docs/coroutines-overview.html |
| Android TurboModule | https://reactnative.dev/docs/turbo-native-modules-android |
| Build TurboModule (LogRocket) | https://blog.logrocket.com/build-custom-react-native-turbo-module-android/ |
| BLE on Android | https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview |

---

*This guide targets React Native 0.73+ (New Architecture), Kotlin 1.9+, Room 2.7+, and Android API 24+.*
