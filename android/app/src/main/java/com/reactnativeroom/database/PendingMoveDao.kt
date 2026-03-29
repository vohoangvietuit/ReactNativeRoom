package com.reactnativeroom.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingMoveDao {

    /** Insert a pending move. Silently ignores duplicate (x, y, gameId). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(move: PendingMove)

    /** All unsynced moves for the current game, ordered by insertion time. */
    @Query("SELECT * FROM pending_moves WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<PendingMove>

    /** Mark a move as successfully delivered to the host. */
    @Query("UPDATE pending_moves SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Int)

    /** Mark moves as synced by (x, y, gameId) — used during fullSync reconciliation. */
    @Query("UPDATE pending_moves SET synced = 1 WHERE x = :x AND y = :y AND game_id = :gameId")
    suspend fun markSyncedByPosition(x: Int, y: Int, gameId: String)

    @Query("DELETE FROM pending_moves")
    suspend fun clearAll()
}
