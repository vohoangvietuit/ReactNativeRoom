package com.reactnativeroom.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "game_sessions")
data class GameSession(
    @PrimaryKey
    @ColumnInfo(name = "game_id")
    val gameId: String,

    @ColumnInfo(name = "host_device_id")
    val hostDeviceId: String,

    @ColumnInfo(name = "status")
    val status: String = "WAITING", // WAITING, PLAYING, FINISHED

    @ColumnInfo(name = "winner")
    val winner: String? = null,     // "X", "O", "DRAW", or null

    @ColumnInfo(name = "board_size")
    val boardSize: Int = 15,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
