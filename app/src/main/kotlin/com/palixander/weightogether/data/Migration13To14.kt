package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Retain unknown legacy provenance; an old device address is not evidence of manual entry. */
object Migration13To14 : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE measurements ADD COLUMN origin TEXT NOT NULL DEFAULT 'LEGACY'")
        db.execSQL("""
            CREATE TABLE pet_measurements_new (
                id TEXT NOT NULL PRIMARY KEY, petId TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                firstWeightKg REAL, secondWeightKg REAL, petWeightKg REAL NOT NULL,
                origin TEXT NOT NULL DEFAULT 'LEGACY',
                FOREIGN KEY(petId) REFERENCES pets(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO pet_measurements_new
                (id, petId, measuredAtEpochSecond, firstWeightKg, secondWeightKg, petWeightKg)
            SELECT id, petId, measuredAtEpochSecond, firstWeightKg, secondWeightKg, petWeightKg
            FROM pet_measurements
        """.trimIndent())
        db.execSQL("DROP TABLE pet_measurements")
        db.execSQL("ALTER TABLE pet_measurements_new RENAME TO pet_measurements")
        db.execSQL("CREATE INDEX index_pet_measurements_petId_measuredAtEpochSecond ON pet_measurements(petId, measuredAtEpochSecond)")
    }
}
