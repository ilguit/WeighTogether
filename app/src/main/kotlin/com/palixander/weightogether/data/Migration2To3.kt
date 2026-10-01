package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds lossless measurement timestamps while retaining millis for existing query contracts. */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE measurements ADD COLUMN measuredAtEpochSecond INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE measurements ADD COLUMN measuredAtNano INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            UPDATE measurements
            SET measuredAtEpochSecond = CASE
                    WHEN measuredAtEpochMillis >= 0 OR measuredAtEpochMillis % 1000 = 0
                        THEN measuredAtEpochMillis / 1000
                    ELSE measuredAtEpochMillis / 1000 - 1
                END,
                measuredAtNano = (
                    (measuredAtEpochMillis % 1000 + 1000) % 1000
                ) * 1000000
            """.trimIndent(),
        )
        // Persistence shape for the second task in this plan. Its behavior is wired separately.
        db.execSQL(
            "ALTER TABLE app_state ADD COLUMN ignoreUnknownMeasurements INTEGER NOT NULL DEFAULT 0",
        )
    }
}
