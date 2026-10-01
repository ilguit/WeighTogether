package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds per-destination snapshots of calculated values without inferring them for old rows.
 *
 * A null snapshot means that this app version has no evidence of the exact calculated values sent
 * to that destination. In particular, existing sync statuses must remain untouched: backfilling a
 * snapshot from today's local values for an already-synced row could hide a later profile mismatch.
 */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE measurements ADD COLUMN healthConnectSyncedCalculatedValues TEXT")
    }
}
