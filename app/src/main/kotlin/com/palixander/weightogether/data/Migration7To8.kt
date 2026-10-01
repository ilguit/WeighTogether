package com.palixander.weightogether.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds local pets and their completed two-reading weighing history. */
object Migration7To8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pets (
                id TEXT NOT NULL,
                displayName TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pets_normalizedName " +
                "ON pets(normalizedName)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pet_measurements (
                id TEXT NOT NULL,
                petId TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                firstWeightKg REAL NOT NULL,
                secondWeightKg REAL NOT NULL,
                petWeightKg REAL NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(petId) REFERENCES pets(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pet_measurements_petId_measuredAtEpochSecond " +
                "ON pet_measurements(petId, measuredAtEpochSecond)",
        )
    }
}
