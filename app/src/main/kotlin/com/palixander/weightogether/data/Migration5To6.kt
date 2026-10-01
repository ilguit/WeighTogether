package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the nullable, best-effort account classification for preliminary measurements. */
object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_measurements ADD COLUMN provisionalAccountId TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "index_pending_measurements_provisionalAccountId_measuredAtEpochSecond_id " +
                "ON pending_measurements (provisionalAccountId, measuredAtEpochSecond, id)",
        )
    }
}
