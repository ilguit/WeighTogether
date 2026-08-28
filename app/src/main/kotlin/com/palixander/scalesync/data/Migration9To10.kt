package com.palixander.scalesync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds species while retaining pets created before species was collected. */
object Migration9To10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE pets ADD COLUMN species TEXT NOT NULL DEFAULT 'UNSPECIFIED'",
        )
    }
}
