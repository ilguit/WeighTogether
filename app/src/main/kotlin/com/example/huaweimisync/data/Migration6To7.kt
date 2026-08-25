package com.example.huaweimisync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the durable cross-store import recovery journal. */
object Migration6To7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_import_checkpoint (
                singletonId INTEGER NOT NULL,
                phase TEXT NOT NULL,
                rollbackDatabaseJson TEXT NOT NULL,
                previousSettingsJson TEXT NOT NULL,
                targetSettingsJson TEXT NOT NULL,
                PRIMARY KEY(singletonId)
            )
            """.trimIndent(),
        )
    }
}
