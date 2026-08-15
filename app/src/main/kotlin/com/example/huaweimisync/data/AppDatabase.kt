package com.example.huaweimisync.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeasurementEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `measurements_new` (
                        `id` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `measurementType` TEXT NOT NULL,
                        `deviceAddress` TEXT NOT NULL,
                        `measuredAtEpochMillis` INTEGER NOT NULL,
                        `rawPayloadHex` TEXT NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `impedanceOhm` INTEGER,
                        `bmi` REAL,
                        `bodyFatPercent` REAL,
                        `bodyFatMassKg` REAL,
                        `waterPercent` REAL,
                        `waterMassKg` REAL,
                        `muscleMassKg` REAL,
                        `skeletalMuscleMassKg` REAL,
                        `boneMassKg` REAL,
                        `proteinPercent` REAL,
                        `proteinMassKg` REAL,
                        `visceralFatLevel` REAL,
                        `basalMetabolicRateKcal` REAL,
                        `metabolicAge` INTEGER,
                        `leanBodyMassKg` REAL,
                        `algorithmVersion` TEXT,
                        `huaweiStatus` TEXT NOT NULL,
                        `healthConnectStatus` TEXT NOT NULL,
                        `huaweiError` TEXT,
                        `healthConnectError` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `measurements_new` (
                        `id`, `fingerprint`, `measurementType`, `deviceAddress`,
                        `measuredAtEpochMillis`, `rawPayloadHex`, `weightKg`, `impedanceOhm`,
                        `bmi`, `bodyFatPercent`, `bodyFatMassKg`, `waterPercent`, `waterMassKg`,
                        `muscleMassKg`, `skeletalMuscleMassKg`, `boneMassKg`, `proteinPercent`,
                        `proteinMassKg`, `visceralFatLevel`, `basalMetabolicRateKcal`,
                        `metabolicAge`, `leanBodyMassKg`, `algorithmVersion`, `huaweiStatus`,
                        `healthConnectStatus`, `huaweiError`, `healthConnectError`,
                        `createdAtEpochMillis`
                    )
                    SELECT
                        old.`id`,
                        UPPER(old.`deviceAddress`) || '|' ||
                            CAST(old.`measuredAtEpochMillis` / 1000 AS INTEGER) || '|' ||
                            CAST(ROUND(old.`weightKg` / 0.005) AS INTEGER) ||
                            CASE WHEN old.`id` = (
                                SELECT MIN(candidate.`id`)
                                FROM `measurements` AS candidate
                                WHERE UPPER(candidate.`deviceAddress`) = UPPER(old.`deviceAddress`)
                                    AND CAST(candidate.`measuredAtEpochMillis` / 1000 AS INTEGER) =
                                        CAST(old.`measuredAtEpochMillis` / 1000 AS INTEGER)
                                    AND CAST(ROUND(candidate.`weightKg` / 0.005) AS INTEGER) =
                                        CAST(ROUND(old.`weightKg` / 0.005) AS INTEGER)
                            ) THEN '' ELSE '|legacy:' || old.`id` END,
                        'FULL',
                        old.`deviceAddress`, old.`measuredAtEpochMillis`, old.`rawPayloadHex`,
                        old.`weightKg`, old.`impedanceOhm`, old.`bmi`, old.`bodyFatPercent`,
                        old.`bodyFatMassKg`, old.`waterPercent`, old.`waterMassKg`,
                        old.`muscleMassKg`, old.`skeletalMuscleMassKg`, old.`boneMassKg`,
                        old.`proteinPercent`, old.`proteinMassKg`, old.`visceralFatLevel`,
                        old.`basalMetabolicRateKcal`, old.`metabolicAge`, old.`leanBodyMassKg`,
                        old.`algorithmVersion`, old.`huaweiStatus`, old.`healthConnectStatus`,
                        old.`huaweiError`, old.`healthConnectError`, old.`createdAtEpochMillis`
                    FROM `measurements` AS old
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `measurements`")
                db.execSQL("ALTER TABLE `measurements_new` RENAME TO `measurements`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_measurements_fingerprint` " +
                        "ON `measurements` (`fingerprint`)",
                )
            }
        }
    }
}
