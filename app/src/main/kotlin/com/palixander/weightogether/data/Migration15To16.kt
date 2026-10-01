package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Collapses the retired Canadian Sphynx VBO duplicate into the canonical Sphynx ID. */
object Migration15To16 : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE pets SET breedId = 'VBO:0100230' WHERE breedId = 'VBO:0100061'",
        )
    }
}
