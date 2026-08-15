package com.example.huaweimisync.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.domain.DEFAULT_WEIGHT_DELTA_KG
import java.time.LocalDate
import java.util.UUID

data class LegacyProfileSnapshot(
    val heightCm: Double?,
    val birthDate: LocalDate?,
    val sex: Sex?,
) {
    val completeProfile: UserProfile?
        get() {
            val storedHeight = heightCm
            val storedBirthDate = birthDate
            val storedSex = sex
            return if (storedHeight != null && storedBirthDate != null && storedSex != null) {
                runCatching { UserProfile(storedHeight, storedBirthDate, storedSex) }.getOrNull()
            } else {
                null
            }
        }

    companion object {
        fun from(context: Context): LegacyProfileSnapshot = from(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        )

        fun from(preferences: SharedPreferences): LegacyProfileSnapshot {
            val height = preferences.getString(KEY_HEIGHT, null)
                ?.toDoubleOrNull()
                ?.takeIf(Double::isFinite)
            val birthDate = preferences.getString(KEY_BIRTH_DATE, null)?.let { value ->
                runCatching { LocalDate.parse(value) }.getOrNull()
            }
            val sex = preferences.getString(KEY_SEX, null)?.let { value ->
                runCatching { Sex.valueOf(value) }.getOrNull()
            }
            return LegacyProfileSnapshot(height, birthDate, sex)
        }

        private const val PREFERENCES_NAME = "mi_sync_settings"
        private const val KEY_HEIGHT = "height"
        private const val KEY_BIRTH_DATE = "birth_date"
        private const val KEY_SEX = "sex"
    }
}

class Migration1To2(
    private val legacyProfile: LegacyProfileSnapshot,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newAccountId: () -> String = { UUID.randomUUID().toString() },
) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createAccountTables(db)
        val hasHistory = db.query("SELECT EXISTS(SELECT 1 FROM measurements LIMIT 1)").use {
            it.moveToFirst() && it.getInt(0) == 1
        }
        val completeProfile = legacyProfile.completeProfile
        val accountId = if (completeProfile != null || hasHistory) {
            newAccountId().also { id ->
                UUID.fromString(id)
                val timestamp = nowEpochMillis()
                db.execSQL(
                    """
                    INSERT INTO accounts (
                        id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                        isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        id,
                        LEGACY_ACCOUNT_DISPLAY_NAME,
                        LEGACY_ACCOUNT_NORMALIZED_NAME,
                        legacyProfile.heightCm,
                        legacyProfile.birthDate?.toEpochDay(),
                        legacyProfile.sex?.name,
                        if (completeProfile != null) 1 else 0,
                        timestamp,
                        timestamp,
                    ),
                )
            }
        } else {
            null
        }
        db.execSQL(
            "INSERT INTO app_state (singletonId, primaryAccountId, weightDeltaKg) VALUES (1, ?, ?)",
            arrayOf<Any?>(accountId, DEFAULT_WEIGHT_DELTA_KG),
        )

        createPendingTables(db)
        check(!hasHistory || accountId != null) {
            "A legacy database containing measurements must have a recovery account"
        }
        migrateMeasurements(db, accountId)
    }

    private fun createAccountTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id TEXT NOT NULL,
                displayName TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                heightCm REAL,
                birthDateEpochDay INTEGER,
                sex TEXT,
                isProfileComplete INTEGER NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_normalizedName " +
                "ON accounts(normalizedName)",
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_state (
                singletonId INTEGER NOT NULL,
                primaryAccountId TEXT,
                weightDeltaKg REAL NOT NULL,
                PRIMARY KEY(singletonId),
                FOREIGN KEY(primaryAccountId) REFERENCES accounts(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_app_state_primaryAccountId " +
                "ON app_state(primaryAccountId)",
        )
    }

    private fun createPendingTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_measurements (
                id TEXT NOT NULL,
                deviceAddress TEXT NOT NULL,
                measuredAtEpochSecond INTEGER NOT NULL,
                measuredAtNano INTEGER NOT NULL,
                weightKg REAL NOT NULL,
                impedanceOhm INTEGER NOT NULL,
                isStable INTEGER NOT NULL,
                hasImpedance INTEGER NOT NULL,
                rawPayload BLOB NOT NULL,
                deduplicationHash TEXT NOT NULL,
                enqueuedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_measurements_deduplicationHash " +
                "ON pending_measurements(deduplicationHash)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_measurements_enqueuedAtEpochMillis_id " +
                "ON pending_measurements(enqueuedAtEpochMillis, id)",
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS measurement_tombstones (
                deduplicationHash TEXT NOT NULL,
                expiresAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(deduplicationHash)
            )
            """.trimIndent(),
        )
    }

    private fun migrateMeasurements(database: SupportSQLiteDatabase, accountId: String?) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS measurements_new (
                id TEXT NOT NULL,
                deviceAddress TEXT NOT NULL,
                measuredAtEpochMillis INTEGER NOT NULL,
                rawPayloadHex TEXT NOT NULL,
                weightKg REAL NOT NULL,
                impedanceOhm INTEGER NOT NULL,
                bmi REAL NOT NULL,
                bodyFatPercent REAL NOT NULL,
                bodyFatMassKg REAL NOT NULL,
                waterPercent REAL NOT NULL,
                waterMassKg REAL NOT NULL,
                muscleMassKg REAL NOT NULL,
                skeletalMuscleMassKg REAL NOT NULL,
                boneMassKg REAL NOT NULL,
                proteinPercent REAL NOT NULL,
                proteinMassKg REAL NOT NULL,
                visceralFatLevel REAL NOT NULL,
                basalMetabolicRateKcal REAL NOT NULL,
                metabolicAge INTEGER NOT NULL,
                leanBodyMassKg REAL NOT NULL,
                algorithmVersion TEXT NOT NULL,
                huaweiStatus TEXT NOT NULL,
                healthConnectStatus TEXT NOT NULL,
                huaweiError TEXT,
                healthConnectError TEXT,
                createdAtEpochMillis INTEGER NOT NULL,
                accountId TEXT NOT NULL,
                externalSyncPolicy TEXT NOT NULL,
                sourcePendingId TEXT,
                deduplicationHash TEXT,
                PRIMARY KEY(id),
                FOREIGN KEY(accountId) REFERENCES accounts(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        if (accountId != null) {
            database.execSQL(
                """
                INSERT INTO measurements_new (
                    id, deviceAddress, measuredAtEpochMillis, rawPayloadHex, weightKg,
                    impedanceOhm, bmi, bodyFatPercent, bodyFatMassKg, waterPercent,
                    waterMassKg, muscleMassKg, skeletalMuscleMassKg, boneMassKg,
                    proteinPercent, proteinMassKg, visceralFatLevel,
                    basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
                    algorithmVersion, huaweiStatus, healthConnectStatus, huaweiError,
                    healthConnectError, createdAtEpochMillis, accountId,
                    externalSyncPolicy, sourcePendingId, deduplicationHash
                )
                SELECT
                    id, deviceAddress, measuredAtEpochMillis, rawPayloadHex, weightKg,
                    impedanceOhm, bmi, bodyFatPercent, bodyFatMassKg, waterPercent,
                    waterMassKg, muscleMassKg, skeletalMuscleMassKg, boneMassKg,
                    proteinPercent, proteinMassKg, visceralFatLevel,
                    basalMetabolicRateKcal, metabolicAge, leanBodyMassKg,
                    algorithmVersion, huaweiStatus, healthConnectStatus, huaweiError,
                    healthConnectError, createdAtEpochMillis, ?,
                    CASE
                        WHEN huaweiStatus = 'LOCAL_ONLY' OR healthConnectStatus = 'LOCAL_ONLY'
                            THEN 'USER_LOCAL'
                        ELSE 'AUTO'
                    END,
                    NULL, NULL
                FROM measurements
                """.trimIndent(),
                arrayOf(accountId),
            )
        }
        database.execSQL("DROP TABLE measurements")
        database.execSQL("ALTER TABLE measurements_new RENAME TO measurements")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_measurements_accountId_measuredAtEpochMillis " +
                "ON measurements(accountId, measuredAtEpochMillis)",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_measurements_sourcePendingId " +
                "ON measurements(sourcePendingId)",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_measurements_deduplicationHash " +
                "ON measurements(deduplicationHash)",
        )
    }

    private companion object {
        const val LEGACY_ACCOUNT_DISPLAY_NAME = "Основной"
        const val LEGACY_ACCOUNT_NORMALIZED_NAME = "основной"
    }
}
