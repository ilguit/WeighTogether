package com.palixander.scalesync.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationRestoresHeightFromEachMeasurementOwnerAndPreservesNull() {
        helper.createDatabase(DATABASE_NAME, 10).apply {
            insertAccount("alice", 171.5)
            insertAccount("bob", 188.0)
            insertAccount("unknown", null)
            insertMeasurement("alice-reading", "alice")
            insertMeasurement("bob-reading", "bob")
            insertMeasurement("unknown-reading", "unknown")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            11,
            true,
            AppDatabase.MIGRATION_10_11,
        )
        migrated.query(
            """
            SELECT id, ratingHeightCm, ratingHeightOrigin
            FROM measurements
            ORDER BY id
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("alice-reading", cursor.getString(0))
            assertEquals(171.5, cursor.getDouble(1), 0.0)
            assertEquals("RESTORED_CURRENT_ACCOUNT", cursor.getString(2))

            assertTrue(cursor.moveToNext())
            assertEquals("bob-reading", cursor.getString(0))
            assertEquals(188.0, cursor.getDouble(1), 0.0)
            assertEquals("RESTORED_CURRENT_ACCOUNT", cursor.getString(2))

            assertTrue(cursor.moveToNext())
            assertEquals("unknown-reading", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals("RESTORED_CURRENT_ACCOUNT", cursor.getString(2))
        }
        migrated.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertAccount(
        id: String,
        heightCm: Double?,
    ) {
        execSQL(
            """
            INSERT INTO accounts (
                id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (?, ?, ?, ?, 0, 'MALE', ?, 1, 1)
            """.trimIndent(),
            arrayOf<Any?>(id, id, id, heightCm, if (heightCm == null) 0 else 1),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertMeasurement(
        id: String,
        accountId: String,
    ) {
        execSQL(
            """
            INSERT INTO measurements (
                id, fingerprint, measurementType, deviceAddress, measuredAtEpochSecond,
                rawPayloadHex, weightKg, rawWeight, healthConnectStatus,
                healthConnectWeightSynced, createdAtEpochMillis,
                accountId, externalSyncPolicy
            ) VALUES (?, ?, 'WEIGHT_ONLY', 'device', 1, '', 70.0, 14000,
                'PENDING', 0, 1, ?, 'AUTO')
            """.trimIndent(),
            arrayOf<Any?>(id, id, accountId),
        )
    }

    private companion object {
        const val DATABASE_NAME = "measurement-context-migration-test"
    }
}
