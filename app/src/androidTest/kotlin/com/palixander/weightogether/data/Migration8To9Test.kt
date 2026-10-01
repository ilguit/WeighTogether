package com.palixander.weightogether.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationAddsEmptyDurableBaselineAndPreservesExistingState() {
        helper.createDatabase(DATABASE_NAME, 8).apply {
            execSQL("INSERT INTO app_state (singletonId, primaryAccountId, weightDeltaKg) VALUES (1, NULL, 4.25)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        )
        migrated.query("SELECT weightDeltaKg FROM app_state").use {
            assertTrue(it.moveToFirst())
            assertEquals(4.25, it.getDouble(0), 0.0)
        }
        migrated.query("SELECT COUNT(*) FROM accepted_stable_measurements").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.execSQL(
            """
            INSERT INTO accepted_stable_measurements VALUES (
                'latest', 'AA:BB', 123, 456789, 70.005, 14001, 501, 1, 1, X'0180FF'
            )
            """.trimIndent(),
        )
        migrated.query("SELECT * FROM accepted_stable_measurements WHERE id = 'latest'").use {
            assertTrue(it.moveToFirst())
            assertEquals(456789, it.getInt(it.getColumnIndexOrThrow("measuredAtNano")))
            assertEquals(14001, it.getInt(it.getColumnIndexOrThrow("rawWeight")))
            assertArrayEquals(
                byteArrayOf(0x01, 0x80.toByte(), 0xff.toByte()),
                it.getBlob(it.getColumnIndexOrThrow("rawPayload")),
            )
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "accepted-stable-migration-test"
    }
}
