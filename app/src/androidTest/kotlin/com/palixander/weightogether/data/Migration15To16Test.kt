package com.palixander.weightogether.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationCanonicalizesCanadianSphynxAndPreservesUnknownBreedIds() {
        helper.createDatabase(DATABASE_NAME, 15).apply {
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
                    sex, breedId, birthYear, birthMonth, birthDay, dogAdultWeightCategory
                ) VALUES
                    ('alias', 'Alias', 'alias', 'CAT', 1, 2, NULL, 'VBO:0100061', NULL, NULL, NULL, NULL),
                    ('canonical', 'Canonical', 'canonical', 'CAT', 1, 2, NULL, 'VBO:0100230', NULL, NULL, NULL, NULL),
                    ('future', 'Future', 'future', 'CAT', 1, 2, NULL, 'external:cat:future', NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            16,
            true,
            AppDatabase.MIGRATION_15_16,
        )
        migrated.query("SELECT id, breedId FROM pets ORDER BY id").use { cursor ->
            val values = buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
            assertEquals("VBO:0100230", values.getValue("alias"))
            assertEquals("VBO:0100230", values.getValue("canonical"))
            assertEquals("external:cat:future", values.getValue("future"))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "cat-breed-canonicalization-migration-test"
    }
}
