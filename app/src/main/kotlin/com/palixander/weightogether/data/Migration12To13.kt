package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One-time reset of choices made against the retired, unverified breed catalog. */
object Migration12To13 : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE pets SET breedId = NULL")
    }
}
