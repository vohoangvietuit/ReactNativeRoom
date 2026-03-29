package com.reactnativeroom.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CaroDao {

    // Live board updates — emits new list whenever caro_moves table changes
    @Query("SELECT * FROM caro_moves ORDER BY move_number ASC")
    fun observeMoves(): Flow<List<CaroMove>>

    // One-shot fetch — for BLE full sync
    @Query("SELECT * FROM caro_moves ORDER BY move_number ASC")
    suspend fun getAllMoves(): List<CaroMove>

    // Check if a cell is already occupied
    @Query("SELECT * FROM caro_moves WHERE x = :x AND y = :y LIMIT 1")
    suspend fun getMoveAt(x: Int, y: Int): CaroMove?

    // Get the last move placed (for turn validation)
    @Query("SELECT * FROM caro_moves ORDER BY move_number DESC LIMIT 1")
    suspend fun getLastMove(): CaroMove?

    // Total move count
    @Query("SELECT COUNT(*) FROM caro_moves")
    suspend fun getMoveCount(): Int

    // Insert a single move — ABORT on conflict (duplicate x,y)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMove(move: CaroMove)

    // Insert a single move — silently ignore if cell already occupied (optimistic offline writes)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMoveIgnore(move: CaroMove)

    // Bulk insert for full sync from host
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(moves: List<CaroMove>)

    // Clear board for new game
    @Query("DELETE FROM caro_moves")
    suspend fun clearMoves()

    // ── Game Session ──

    @Upsert
    suspend fun upsertSession(session: GameSession)

    @Query("SELECT * FROM game_sessions WHERE game_id = :gameId LIMIT 1")
    suspend fun getSession(gameId: String): GameSession?

    @Query("SELECT * FROM game_sessions ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestSession(): GameSession?

    @Query("UPDATE game_sessions SET status = :status, winner = :winner WHERE game_id = :gameId")
    suspend fun updateSessionStatus(gameId: String, status: String, winner: String? = null)

    @Query("DELETE FROM game_sessions")
    suspend fun clearSessions()
}
