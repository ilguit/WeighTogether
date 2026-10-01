package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration16To17 : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN photoPath TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE pets ADD COLUMN photoPath TEXT DEFAULT NULL")
    }
}
