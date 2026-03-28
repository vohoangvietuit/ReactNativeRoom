package com.reactnativeroom.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "caro_moves",
    indices = [
        Index(value = ["x", "y"], unique = true),
        Index(value = ["move_number"])
    ]
)
data class CaroMove(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "x")
    val x: Int,               // 0 to 14

    @ColumnInfo(name = "y")
    val y: Int,               // 0 to 14

    @ColumnInfo(name = "player_symbol")
    val playerSymbol: String, // "X" or "O"

    @ColumnInfo(name = "move_number")
    val moveNumber: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
