package com.example.huaweimisync.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationAddsPetTablesWithRelationshipAndPreservesExistingState() {
        helper.createDatabase(DATABASE_NAME, 7).apply {
            execSQL(
                "INSERT INTO backup_import_checkpoint VALUES " +
                    "(1, 'PREPARED', '{}', '{}', '{}')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            8,
            true,
            AppDatabase.MIGRATION_7_8,
        )
        migrated.query("SELECT phase FROM backup_import_checkpoint").use {
            assertTrue(it.moveToFirst())
            assertEquals("PREPARED", it.getString(0))
        }
        migrated.execSQL(
            "INSERT INTO pets VALUES ('pet', 'Луна', 'луна', 100, 100)",
        )
        migrated.execSQL(
            "INSERT INTO pet_measurements VALUES " +
                "('measurement', 'pet', 200, 70.0, 66.5, 3.5)",
        )
        migrated.query("SELECT petWeightKg FROM pet_measurements").use {
            assertTrue(it.moveToFirst())
            assertEquals(3.5, it.getDouble(0), 0.0)
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "pet-migration-test"
    }
}
