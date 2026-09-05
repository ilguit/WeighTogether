package com.palixander.scalesync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Removes the retired Huawei integration state without touching measurement data. */
object Migration14To15 : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE measurements_new (
                id TEXT NOT NULL, fingerprint TEXT NOT NULL, measurementType TEXT NOT NULL,
                deviceAddress TEXT NOT NULL, measuredAtEpochSecond INTEGER NOT NULL,
                rawPayloadHex TEXT NOT NULL, weightKg REAL NOT NULL, rawWeight INTEGER NOT NULL,
                impedanceOhm INTEGER, bmi REAL, bodyFatPercent REAL, bodyFatMassKg REAL,
                waterPercent REAL, waterMassKg REAL, muscleMassKg REAL,
                skeletalMuscleMassKg REAL, boneMassKg REAL, proteinPercent REAL,
                proteinMassKg REAL, visceralFatLevel REAL, basalMetabolicRateKcal REAL,
                metabolicAge INTEGER, leanBodyMassKg REAL, algorithmVersion TEXT,
                healthConnectStatus TEXT NOT NULL, healthConnectError TEXT,
                healthConnectWeightSynced INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL,
                accountId TEXT NOT NULL, externalSyncPolicy TEXT NOT NULL, sourcePendingId TEXT,
                deduplicationHash TEXT, healthConnectSyncedCalculatedValues TEXT,
                ratingHeightCm REAL,
                ratingHeightOrigin TEXT NOT NULL DEFAULT 'RESTORED_CURRENT_ACCOUNT',
                origin TEXT NOT NULL DEFAULT 'LEGACY', PRIMARY KEY(id),
                FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        val retainedColumns = """
            id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond, rawPayloadHex,
            weightKg, rawWeight, impedanceOhm, bmi, bodyFatPercent, bodyFatMassKg, waterPercent,
            waterMassKg, muscleMassKg, skeletalMuscleMassKg, boneMassKg, proteinPercent,
            proteinMassKg, visceralFatLevel, basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
            algorithmVersion, healthConnectStatus, healthConnectError, healthConnectWeightSynced,
            createdAtEpochMillis, accountId, externalSyncPolicy, sourcePendingId, deduplicationHash,
            healthConnectSyncedCalculatedValues, ratingHeightCm, ratingHeightOrigin, origin
        """.trimIndent()
        db.execSQL("INSERT INTO measurements_new ($retainedColumns) SELECT $retainedColumns FROM measurements")
        db.execSQL("DROP TABLE measurements")
        db.execSQL("ALTER TABLE measurements_new RENAME TO measurements")
        db.execSQL("CREATE UNIQUE INDEX index_measurements_fingerprint ON measurements(fingerprint)")
        db.execSQL("CREATE INDEX index_measurements_accountId_measuredAtEpochSecond ON measurements(accountId, measuredAtEpochSecond)")
        db.execSQL("CREATE INDEX index_measurements_deviceAddress_rawWeight_measuredAtEpochSecond ON measurements(deviceAddress, rawWeight, measuredAtEpochSecond)")
        db.execSQL("CREATE UNIQUE INDEX index_measurements_sourcePendingId ON measurements(sourcePendingId)")
        db.execSQL("CREATE UNIQUE INDEX index_measurements_deduplicationHash ON measurements(deduplicationHash)")
    }
}
