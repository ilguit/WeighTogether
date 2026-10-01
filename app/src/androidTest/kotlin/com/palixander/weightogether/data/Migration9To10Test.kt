package com.palixander.weightogether.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration9To10Test {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationPreservesPetsAndMarksLegacySpeciesUnspecified() {
        helper.createDatabase(DATABASE_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('legacy-pet', 'Барсик', 'барсик', 100, 200)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            AppDatabase.MIGRATION_9_10,
        )
        migrated.query(
            "SELECT displayName, species, createdAtEpochMillis, updatedAtEpochMillis FROM pets",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Барсик", it.getString(0))
            assertEquals("UNSPECIFIED", it.getString(1))
            assertEquals(100L, it.getLong(2))
            assertEquals(200L, it.getLong(3))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "pet-species-migration-test"
    }
}
