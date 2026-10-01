package com.palixander.weightogether.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration12To13Test {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationClearsBreedIdsAndPreservesEveryOtherPetField() {
        helper.createDatabase(DATABASE_NAME, 12).apply {
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
                    sex, breedId, birthYear, birthMonth, birthDay, dogAdultWeightCategory
                ) VALUES ('pet', 'Луна', 'луна', 'DOG', 100, 200, 'FEMALE', 'VBO:0000661', 2024, 3, 4, 'III')
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            13,
            true,
            AppDatabase.MIGRATION_12_13,
        )
        migrated.query(
            """
            SELECT displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
                sex, breedId, birthYear, birthMonth, birthDay, dogAdultWeightCategory FROM pets
            """.trimIndent(),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Луна", it.getString(0))
            assertEquals("луна", it.getString(1))
            assertEquals("DOG", it.getString(2))
            assertEquals(100L, it.getLong(3))
            assertEquals(200L, it.getLong(4))
            assertEquals("FEMALE", it.getString(5))
            assertNull(it.getString(6))
            assertEquals(2024, it.getInt(7))
            assertEquals(3, it.getInt(8))
            assertEquals(4, it.getInt(9))
            assertEquals("III", it.getString(10))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "breed-reset-migration-test"
    }
}
