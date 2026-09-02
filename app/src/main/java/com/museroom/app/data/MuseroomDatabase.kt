package com.museroom.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlayEventEntity::class, ListeningSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MuseroomDatabase : RoomDatabase() {

    abstract fun dao(): MuseroomDao

    companion object {
        @Volatile
        private var instance: MuseroomDatabase? = null

        fun get(context: Context): MuseroomDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuseroomDatabase::class.java,
                    "museroom.db",
                ).build().also { instance = it }
            }
    }
}
