package com.reactnativeroom.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CaroMove::class, GameSession::class, PendingMove::class],
    version = 2,
    exportSchema = true
)
abstract class CaroDatabase : RoomDatabase() {

    abstract fun caroDao(): CaroDao
    abstract fun pendingMoveDao(): PendingMoveDao

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
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
