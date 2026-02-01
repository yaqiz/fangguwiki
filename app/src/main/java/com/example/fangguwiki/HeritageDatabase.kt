package com.example.fangguwiki

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HeritagePin::class], version = 3, exportSchema = false)
@TypeConverters(HeritageTypeConverters::class)
abstract class HeritageDatabase : RoomDatabase() {
    abstract fun heritageDao(): HeritageDao

    companion object {
        @Volatile
        private var INSTANCE: HeritageDatabase? = null

        fun getDatabase(context: Context): HeritageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HeritageDatabase::class.java,
                    "heritage_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE heritage_pins ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE heritage_pins ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
    }
}
