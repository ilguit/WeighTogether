package com.example.huaweimisync.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Makes whole seconds the sole persisted measurement timestamp while preserving every v3 row.
 * Existing tombstones cannot recover provenance which was never stored, so their new metadata is
 * nullable; all tombstones created after migration populate it.
 */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildMeasurements(db)
        rebuildPendingMeasurements(db)
        rebuildTombstones(db)
    }

    private fun rebuildMeasurements(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS measurements_new (
                id TEXT NOT NULL PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                measurementType TEXT NOT NULL,
                deviceAddress TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                rawPayloadHex TEXT NOT NULL,
                weightKg REAL NOT NULL,
                rawWeight INTEGER NOT NULL,
                impedanceOhm INTEGER,
                bmi REAL,
                bodyFatPercent REAL,
                bodyFatMassKg REAL,
                waterPercent REAL,
                waterMassKg REAL,
                muscleMassKg REAL,
                skeletalMuscleMassKg REAL,
                boneMassKg REAL,
                proteinPercent REAL,
                proteinMassKg REAL,
                visceralFatLevel REAL,
                basalMetabolicRateKcal REAL,
                metabolicAge INTEGER,
                leanBodyMassKg REAL,
                algorithmVersion TEXT,
                huaweiStatus TEXT NOT NULL,
                healthConnectStatus TEXT NOT NULL,
                huaweiError TEXT,
                healthConnectError TEXT,
                huaweiWeightSynced INTEGER NOT NULL,
                healthConnectWeightSynced INTEGER NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                accountId TEXT NOT NULL,
                externalSyncPolicy TEXT NOT NULL,
                sourcePendingId TEXT,
                deduplicationHash TEXT,
                FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO measurements_new (
                id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond,
                rawPayloadHex, weightKg, rawWeight, impedanceOhm, bmi, bodyFatPercent,
                bodyFatMassKg, waterPercent, waterMassKg, muscleMassKg,
                skeletalMuscleMassKg, boneMassKg, proteinPercent, proteinMassKg,
                visceralFatLevel, basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
                algorithmVersion, huaweiStatus, healthConnectStatus, huaweiError,
                healthConnectError, huaweiWeightSynced, healthConnectWeightSynced,
                createdAtEpochMillis, accountId, externalSyncPolicy, sourcePendingId,
                deduplicationHash
            )
            SELECT
                id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond,
                rawPayloadHex, weightKg, CAST(ROUND(weightKg / 0.005) AS INTEGER),
                impedanceOhm, bmi, bodyFatPercent, bodyFatMassKg, waterPercent, waterMassKg,
                muscleMassKg, skeletalMuscleMassKg, boneMassKg, proteinPercent, proteinMassKg,
                visceralFatLevel, basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
                algorithmVersion, huaweiStatus, healthConnectStatus, huaweiError,
                healthConnectError, huaweiWeightSynced, healthConnectWeightSynced,
                createdAtEpochMillis, accountId, externalSyncPolicy, sourcePendingId,
                deduplicationHash
            FROM measurements
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE measurements")
        db.execSQL("ALTER TABLE measurements_new RENAME TO measurements")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_measurements_fingerprint " +
                "ON measurements(fingerprint)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_measurements_accountId_measuredAtEpochSecond " +
                "ON measurements(accountId, measuredAtEpochSecond)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_measurements_deviceAddress_rawWeight_measuredAtEpochSecond " +
                "ON measurements(deviceAddress, rawWeight, measuredAtEpochSecond)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_measurements_sourcePendingId " +
                "ON measurements(sourcePendingId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_measurements_deduplicationHash " +
                "ON measurements(deduplicationHash)",
        )
    }

    private fun rebuildPendingMeasurements(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_measurements_new (
                id TEXT NOT NULL PRIMARY KEY,
                deviceAddress TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                weightKg REAL NOT NULL,
                impedanceOhm INTEGER NOT NULL,
                isStable INTEGER NOT NULL,
                hasImpedance INTEGER NOT NULL,
                rawPayload BLOB NOT NULL,
                deduplicationHash TEXT NOT NULL,
                enqueuedAtEpochMillis INTEGER NOT NULL,
                rawWeight INTEGER NOT NULL,
                finalizeAfterEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO pending_measurements_new (
                id, deviceAddress, measuredAtEpochSecond, weightKg, impedanceOhm, isStable,
                hasImpedance, rawPayload, deduplicationHash, enqueuedAtEpochMillis, rawWeight,
                finalizeAfterEpochMillis
            )
            SELECT
                id, deviceAddress, measuredAtEpochSecond, weightKg, impedanceOhm, isStable,
                hasImpedance, rawPayload, deduplicationHash, enqueuedAtEpochMillis, rawWeight,
                enqueuedAtEpochMillis + 10000
            FROM pending_measurements
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE pending_measurements")
        db.execSQL("ALTER TABLE pending_measurements_new RENAME TO pending_measurements")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_measurements_deduplicationHash " +
                "ON pending_measurements(deduplicationHash)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_measurements_enqueuedAtEpochMillis_id " +
                "ON pending_measurements(enqueuedAtEpochMillis, id)",
        )
    }

    private fun rebuildTombstones(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS measurement_tombstones_new (
                deduplicationHash TEXT NOT NULL PRIMARY KEY,
                expiresAtEpochMillis INTEGER NOT NULL,
                deviceAddress TEXT,
                measuredAtEpochSecond INTEGER,
                rawWeight INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO measurement_tombstones_new (deduplicationHash, expiresAtEpochMillis)
            SELECT deduplicationHash, expiresAtEpochMillis FROM measurement_tombstones
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE measurement_tombstones")
        db.execSQL("ALTER TABLE measurement_tombstones_new RENAME TO measurement_tombstones")
    }
}
