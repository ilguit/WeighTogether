package com.palixander.weightogether.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {
    @get:Rule
    val helper = RetainedMigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationPreservesPetsAndAddsEmptyProfileColumns() {
        helper.createDatabase(DATABASE_NAME, 11).apply {
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('pet', 'Луна', 'луна', 'DOG', 100, 200)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO pet_measurements (
                    id, petId, measuredAtEpochSecond, firstWeightKg, secondWeightKg, petWeightKg
                ) VALUES ('measurement', 'pet', 300, 70.0, 74.5, 4.5)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            12,
            true,
            AppDatabase.MIGRATION_11_12,
        )
        migrated.query(
            """
            SELECT displayName, sex, breedId, birthYear, birthMonth, birthDay,
                dogAdultWeightCategory FROM pets
            """.trimIndent(),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Луна", it.getString(0))
            for (column in 1..6) assertTrue(it.isNull(column))
        }
        migrated.query(
            "SELECT petId, firstWeightKg, secondWeightKg, petWeightKg FROM pet_measurements",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("pet", it.getString(0))
            assertEquals(70.0, it.getDouble(1), 0.0)
            assertEquals(74.5, it.getDouble(2), 0.0)
            assertEquals(4.5, it.getDouble(3), 0.0)
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "pet-profile-migration-test"
    }
}
