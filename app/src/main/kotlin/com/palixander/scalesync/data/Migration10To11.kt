package com.palixander.scalesync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Restores a height snapshot for legacy measurements from their owning account. */
object Migration10To11 : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE measurements ADD COLUMN ratingHeightCm REAL")
        db.execSQL(
            """
            UPDATE measurements
            SET ratingHeightCm = (
                SELECT accounts.heightCm
                FROM accounts
                WHERE accounts.id = measurements.accountId
            )
            """.trimIndent(),
        )
        db.execSQL(
            "ALTER TABLE measurements ADD COLUMN ratingHeightOrigin TEXT NOT NULL " +
                "DEFAULT 'RESTORED_CURRENT_ACCOUNT'",
        )
    }
}
