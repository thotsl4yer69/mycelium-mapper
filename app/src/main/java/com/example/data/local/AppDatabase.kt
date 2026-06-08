package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting

@Database(
    entities = [Species::class, Observation::class, UserSighting::class],
    version = 3,
    // Schemas are exported to app/schemas and checked in so future versions can
    // ship proper Room migrations (validated against the committed schema)
    // instead of destructively wiping the user's sightings logbook.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fungiDao(): FungiDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mycelium_mapper_db"
                )
                // Pre-1.0 backstop only. Once a released schema exists, add a
                // Migration(n, n+1) (validated against app/schemas) and remove
                // this so upgrades preserve the user's sightings.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
