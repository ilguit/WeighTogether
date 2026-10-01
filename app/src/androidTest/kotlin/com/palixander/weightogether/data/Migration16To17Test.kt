package com.palixander.weightogether.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration16To17Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationAddsNullablePhotoPathsWithoutChangingExistingProfiles() {
        helper.createDatabase(DATABASE_NAME, 16).apply {
            execSQL(
                """
                INSERT INTO accounts (
                    id, displayName, normalizedName, heightCm, birthDateEpochDay, sex,
                    isProfileComplete, createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('account', 'Alice', 'alice', 170.0, 0, 'FEMALE', 1, 1, 2)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO pets (
                    id, displayName, normalizedName, species, createdAtEpochMillis, updatedAtEpochMillis,
                    sex, breedId, birthYear, birthMonth, birthDay, dogAdultWeightCategory
                ) VALUES ('pet', 'Milo', 'milo', 'CAT', 1, 2, NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            17,
            true,
            AppDatabase.MIGRATION_16_17,
        )
        migrated.query("SELECT photoPath FROM accounts WHERE id = 'account'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.query("SELECT photoPath FROM pets WHERE id = 'pet'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "profile-photo-path-migration-test"
    }
}
