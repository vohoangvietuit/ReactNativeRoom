package com.reactnativeroom.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline move queue — stores moves placed by the challenger while disconnected from the host.
 * Each row is flushed to the host via BLE once the connection is re-established.
 */
@Entity(
    tableName = "pending_moves",
    indices = [Index(value = ["x", "y", "game_id"], unique = true)]
)
data class PendingMove(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "x")
    val x: Int,

    @ColumnInfo(name = "y")
    val y: Int,

    @ColumnInfo(name = "player_symbol")
    val playerSymbol: String,    // "X" or "O"

    @ColumnInfo(name = "game_id")
    val gameId: String,

    /** 0 = pending (not yet confirmed by host), 1 = synced (host accepted). */
    @ColumnInfo(name = "synced", defaultValue = "0")
    val synced: Int = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
