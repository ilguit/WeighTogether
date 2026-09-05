package com.palixander.scalesync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds a permanent marker for explicit edits of a pet measurement's final weight. */
object Migration14To15 : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE pet_measurements " +
                "ADD COLUMN isManuallyEdited INTEGER NOT NULL DEFAULT 0",
        )
    }
}
