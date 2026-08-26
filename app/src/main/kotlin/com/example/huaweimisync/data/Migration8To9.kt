package com.example.huaweimisync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the durable baseline used when deciding whether a stable reading is new. */
object Migration8To9 : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accepted_stable_measurements (
                id TEXT NOT NULL,
                deviceAddress TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                measuredAtNano INTEGER NOT NULL,
                weightKg REAL NOT NULL,
                rawWeight INTEGER NOT NULL,
                impedanceOhm INTEGER NOT NULL,
                isStable INTEGER NOT NULL,
                hasImpedance INTEGER NOT NULL,
                rawPayload BLOB NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
    }
}
