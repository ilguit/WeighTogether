package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.ExternalSyncPolicy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration1To2Test {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun completeLegacyProfileAndEveryMeasurementFieldArePreserved() = runBlocking {
        val name = createVersion1Database(withMeasurement = true)
        val accountId = "5a8c76c4-8315-4e36-b2cc-ce09309361f0"
        val migrated = openMigrated(
            name = name,
            profile = LegacyProfileSnapshot(
                heightCm = 180.0,
                birthDate = LocalDate.of(1988, 5, 4),
                sex = Sex.FEMALE,
            ),
            accountId = accountId,
        )
        try {
            val account = migrated.accountDao().get(accountId)!!.toDomain()
            assertEquals("Основной", account.displayName)
            assertEquals("основной", account.normalizedName)
            assertTrue(account.profile is AccountProfile.Complete)
            assertEquals(accountId, migrated.appStateDao().get()!!.primaryAccountId)
            assertEquals(3.0, migrated.appStateDao().get()!!.weightDeltaKg, 0.0)

            val measurement = migrated.multiAccountMeasurementDao().get("legacy-id")!!
            assertEquals(accountId, measurement.accountId)
            assertEquals(ExternalSyncPolicy.USER_LOCAL.name, measurement.externalSyncPolicy)
            assertEquals("AA:BB", measurement.deviceAddress)
            assertEquals(1_234_567L, measurement.measuredAtEpochMillis)
            assertEquals("0102ff", measurement.rawPayloadHex)
            assertEquals(72.25, measurement.weightKg, 0.0)
            assertEquals(501, measurement.impedanceOhm)
            assertEquals(22.3, measurement.bmi!!, 0.0)
            assertEquals(24.1, measurement.bodyFatPercent!!, 0.0)
            assertEquals(17.4, measurement.bodyFatMassKg!!, 0.0)
            assertEquals(53.2, measurement.waterPercent!!, 0.0)
            assertEquals(38.4, measurement.waterMassKg!!, 0.0)
            assertEquals(52.1, measurement.muscleMassKg!!, 0.0)
            assertEquals(25.6, measurement.skeletalMuscleMassKg!!, 0.0)
            assertEquals(2.8, measurement.boneMassKg!!, 0.0)
            assertEquals(16.2, measurement.proteinPercent!!, 0.0)
            assertEquals(11.7, measurement.proteinMassKg!!, 0.0)
            assertEquals(7.5, measurement.visceralFatLevel!!, 0.0)
            assertEquals(1_450.0, measurement.basalMetabolicRateKcal!!, 0.0)
            assertEquals(42, measurement.metabolicAge)
            assertEquals(54.85, measurement.leanBodyMassKg!!, 0.0)
            assertEquals("legacy-algorithm", measurement.algorithmVersion)
            assertEquals(SyncStatus.FAILED.name, measurement.healthConnectStatus)
            assertEquals("legacy-health-error", measurement.healthConnectError)
            assertEquals(9_876_543L, measurement.createdAtEpochMillis)
            assertNull(measurement.sourcePendingId)
            assertNull(measurement.deduplicationHash)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun corruptedProfileWithHistoryCreatesIncompleteRecoveryAccount() = runBlocking {
        val name = createVersion1Database(withMeasurement = true)
        val accountId = "bbc31436-ee7d-45d8-a2ea-4a27a76382d5"
        val migrated = openMigrated(
            name = name,
            profile = LegacyProfileSnapshot(
                heightCm = 99.0,
                birthDate = null,
                sex = Sex.MALE,
            ),
            accountId = accountId,
        )
        try {
            val account = migrated.accountDao().get(accountId)!!.toDomain()
            val recovery = account.profile as AccountProfile.IncompleteRecovery
            assertEquals(99.0, recovery.heightCm!!, 0.0)
            assertNull(recovery.birthDate)
            assertEquals(Sex.MALE, recovery.sex)
            assertEquals(accountId, migrated.appStateDao().get()!!.primaryAccountId)
            assertNotNull(migrated.multiAccountMeasurementDao().get("legacy-id"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun emptyDatabaseCreatesAccountOnlyForAValidLegacyProfile() = runBlocking {
        val noProfileName = createVersion1Database(withMeasurement = false)
        val noProfile = openMigrated(
            noProfileName,
            LegacyProfileSnapshot(heightCm = 99.0, birthDate = null, sex = null),
            "4facd89e-43da-4363-98f5-cc65e05c92b6",
        )
        try {
            assertTrue(noProfile.accountDao().getAll().isEmpty())
            assertNull(noProfile.appStateDao().get()!!.primaryAccountId)
        } finally {
            noProfile.close()
        }

        val validProfileName = createVersion1Database(withMeasurement = false)
        val validProfile = openMigrated(
            validProfileName,
            LegacyProfileSnapshot(175.0, LocalDate.of(1990, 1, 1), Sex.MALE),
            "d0a9657c-cba7-4239-9a63-c4f8dcc1cae0",
        )
        try {
            assertEquals(1, validProfile.accountDao().count())
            assertNotNull(validProfile.appStateDao().get()!!.primaryAccountId)
        } finally {
            validProfile.close()
        }
    }

    @Test
    fun missingLegacyProfileWithHistoryCreatesEmptyRecoveryAccount() = runBlocking {
        val name = createVersion1Database(withMeasurement = true)
        val accountId = "c7daff00-8b38-4167-852c-9336176c6afe"
        val migrated = openMigrated(
            name,
            LegacyProfileSnapshot(heightCm = null, birthDate = null, sex = null),
            accountId,
        )
        try {
            val recovery = migrated.accountDao().get(accountId)!!.toDomain().profile
                as AccountProfile.IncompleteRecovery
            assertNull(recovery.heightCm)
            assertNull(recovery.birthDate)
            assertNull(recovery.sex)
            assertEquals(accountId, migrated.appStateDao().get()!!.primaryAccountId)
            assertNotNull(migrated.multiAccountMeasurementDao().get("legacy-id"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun legacyProfileReaderAcceptsNumericHeightWithoutSharedPreferencesTypeCrash() {
        val preferencesName = "migration-preferences-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        try {
            preferences.edit()
                .putFloat("height", 175.0f)
                .putString("birth_date", "1990-01-01")
                .putString("sex", Sex.FEMALE.name)
                .commit()

            val profile = LegacyProfileSnapshot.from(preferences)

            assertEquals(175.0, profile.heightCm!!, 0.0)
            assertEquals(LocalDate.of(1990, 1, 1), profile.birthDate)
            assertEquals(Sex.FEMALE, profile.sex)
            assertNotNull(profile.completeProfile)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun createVersion1Database(withMeasurement: Boolean): String {
        val name = "migration-${UUID.randomUUID()}.db"
        databaseNames += name
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(VERSION_1_CREATE_SQL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val writable = helper.writableDatabase
        if (withMeasurement) insertLegacyMeasurement(writable)
        helper.close()
        return name
    }

    private fun openMigrated(
        name: String,
        profile: LegacyProfileSnapshot,
        accountId: String,
    ): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                Migration1To2(
                    legacyProfile = profile,
                    nowEpochMillis = { Instant.parse("2026-08-15T12:00:00Z").toEpochMilli() },
                    newAccountId = { accountId },
                ),
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        return database
    }

    private fun insertLegacyMeasurement(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO measurements VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            arrayOf<Any?>(
                "legacy-id",
                "AA:BB",
                1_234_567L,
                "0102ff",
                72.25,
                501,
                22.3,
                24.1,
                17.4,
                53.2,
                38.4,
                52.1,
                25.6,
                2.8,
                16.2,
                11.7,
                7.5,
                1_450.0,
                42,
                54.85,
                "legacy-algorithm",
                SyncStatus.LOCAL_ONLY.name,
                SyncStatus.FAILED.name,
                "legacy-huawei-error",
                "legacy-health-error",
                9_876_543L,
            ),
        )
    }

    private companion object {
        val VERSION_1_CREATE_SQL =
            """
            CREATE TABLE IF NOT EXISTS measurements (
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
                PRIMARY KEY(id)
            )
            """.trimIndent()
    }
}
