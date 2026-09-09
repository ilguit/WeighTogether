package com.palixander.scalesync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds optional pet profile attributes without changing existing pet rows. */
object Migration11To12 : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pets ADD COLUMN sex TEXT")
        db.execSQL("ALTER TABLE pets ADD COLUMN breedId TEXT")
        db.execSQL("ALTER TABLE pets ADD COLUMN birthYear INTEGER")
        db.execSQL("ALTER TABLE pets ADD COLUMN birthMonth INTEGER")
        db.execSQL("ALTER TABLE pets ADD COLUMN birthDay INTEGER")
        db.execSQL("ALTER TABLE pets ADD COLUMN dogAdultWeightCategory TEXT")
    }
}
