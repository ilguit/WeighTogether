package com.palixander.weightogether.data

import android.app.Instrumentation
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Older fixtures describe only the retained data contract, not original Room schemas.
 * Room's authentic schema exports and validation remain the authority from version 15 onward.
 */
class RetainedMigrationTestHelper(
    private val instrumentation: Instrumentation,
    databaseClass: Class<out RoomDatabase>,
) : TestRule {
    private val room = MigrationTestHelper(instrumentation, databaseClass)
    private val opened = mutableListOf<SupportSQLiteOpenHelper>()

    override fun apply(base: Statement, description: Description): Statement = room.apply(
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    opened.forEach { it.close() }
                    opened.clear()
                }
            }
        },
        description,
    )

    fun createDatabase(name: String, version: Int): SupportSQLiteDatabase {
        if (version >= 15) return room.createDatabase(name, version)
        instrumentation.targetContext.deleteDatabase(name)
        return openFixture(name, version)
    }

    fun runMigrationsAndValidate(
        name: String,
        version: Int,
        validateDroppedTables: Boolean,
        vararg migrations: Migration,
    ): SupportSQLiteDatabase {
        if (version >= 15) {
            return room.runMigrationsAndValidate(name, version, validateDroppedTables, *migrations)
                .also { it.setForeignKeyConstraintsEnabled(true) }
        }
        val db = openFixture(name, version, migrations.toList())
        val expected = openFixture(null, version)
        fun tableNames(database: SupportSQLiteDatabase): Set<String> = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val expectedTables = tableNames(expected)
        if (validateDroppedTables) assertEquals(expectedTables, tableNames(db))
        expectedTables.forEach { table ->
            val fixture = TableInfo.read(expected, table)
            // Match Room's entity validation: an unspecified default accepts a migration-added default.
            val expectedContract = TableInfo(
                fixture.name,
                fixture.columns.mapValues { (_, column) ->
                    TableInfo.Column(
                        column.name,
                        column.type,
                        column.notNull,
                        column.primaryKeyPosition,
                        column.defaultValue,
                        TableInfo.CREATED_FROM_ENTITY,
                    )
                },
                fixture.foreignKeys,
                fixture.indices,
            )
            assertEquals("Retained table contract: $table", expectedContract, TableInfo.read(db, table))
        }
        expected.close()
        db.setForeignKeyConstraintsEnabled(true)
        return db
    }

    private fun openFixture(
        name: String?,
        version: Int,
        migrations: List<Migration> = emptyList(),
    ): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(instrumentation.targetContext)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        instrumentation.context.assets.open("retained-migration-fixtures/$version.sql")
                            .bufferedReader().use { it.readText() }
                            .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
                            .split(';').filter { it.isNotBlank() }.forEach(db::execSQL)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        var current = oldVersion
                        while (current < newVersion) {
                            val migration = migrations.single { it.startVersion == current }
                            check(migration.endVersion <= newVersion)
                            migration.migrate(db)
                            current = migration.endVersion
                        }
                    }
                })
                .build(),
        )
        opened += helper
        return helper.writableDatabase
    }
}
