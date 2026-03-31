// AppDatabase.kt
package com.gnimble.typewriter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class], version = 1, exportSchema = false)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // BUG FIX #15: Removed fallbackToDestructiveMigration() which silently deletes
        // all user data (every book they've written) when the database version changes.
        // Instead, define explicit migrations so user data is preserved across updates.
        //
        // When you need to add a new schema version, add a migration object here.
        // Example for a future version 2:
        //
        // private val MIGRATION_1_2 = object : Migration(1, 2) {
        //     override fun migrate(db: SupportSQLiteDatabase) {
        //         db.execSQL("ALTER TABLE books ADD COLUMN word_count INTEGER NOT NULL DEFAULT 0")
        //     }
        // }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "book_database"
                )
                    // Add migrations here as needed:
                    // .addMigrations(MIGRATION_1_2, MIGRATION_2_3, ...)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}