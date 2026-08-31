package com.palixander.scalesync.data

import androidx.room.testing.MigrationTestHelper
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
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationPreservesPetsAndAddsEmptyProfileColumns() {
        helper.createDatabase(DATABASE_NAME, 10).apply {
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('pet', 'Луна', 'луна', 'DOG', 100, 200)
                """.trimIndent(),
            )
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
            SELECT displayName, sex, breedId, birthYear, birthMonth, birthDay,
                dogAdultWeightCategory FROM pets
            """.trimIndent(),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Луна", it.getString(0))
            for (column in 1..6) assertTrue(it.isNull(column))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "pet-profile-migration-test"
    }
}
