package com.palixander.scalesync.data

import androidx.room.migration.bundle.SchemaBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSchemaSerializationTest {
    @Test
    fun exportedSchemasDeserializeWithFieldContentsIntact() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schemaDirectory = AppDatabase::class.java.name
        val schemaFiles = assets.list(schemaDirectory).orEmpty().filter { it.endsWith(".json") }
        assertTrue("Exported database schemas must be packaged in the test APK", schemaFiles.isNotEmpty())

        for (file in schemaFiles) {
            val json = assets.open("$schemaDirectory/$file").bufferedReader().use { it.readText() }
            // Use Room's deserializer in the real instrumentation classloader, as migration tests do.
            val database = json.byteInputStream().use { SchemaBundle.deserialize(it) }.database
            val expectedDatabase = JSONObject(json).getJSONObject("database")
            assertEquals(file, expectedDatabase.getInt("version"), database.version)
            val expectedEntities = expectedDatabase.getJSONArray("entities")
            assertEquals(file, expectedEntities.length(), database.entities.size)
            for (entityIndex in 0 until expectedEntities.length()) {
                val expectedEntity = expectedEntities.getJSONObject(entityIndex)
                val tableName = expectedEntity.getString("tableName")
                val entity = database.entities.single { it.tableName == tableName }
                val expectedFields = expectedEntity.getJSONArray("fields")
                assertTrue("$file/$tableName must exercise FieldBundle", expectedFields.length() > 0)
                assertEquals("$file/$tableName", expectedFields.length(), entity.fields.size)
                for (fieldIndex in 0 until expectedFields.length()) {
                    val expectedField = expectedFields.getJSONObject(fieldIndex)
                    val columnName = expectedField.getString("columnName")
                    val field = entity.fields.single { it.columnName == columnName }
                    val location = "$file/$tableName/$columnName"
                    assertEquals(location, expectedField.getString("fieldPath"), field.fieldPath)
                    assertEquals(location, expectedField.getString("affinity"), field.affinity)
                    // Legacy schemas omit false; FieldBundle defaults missing notNull to false.
                    assertEquals(location, expectedField.optBoolean("notNull", false), field.isNonNull)
                    val defaultValue = if (expectedField.isNull("defaultValue")) {
                        null
                    } else {
                        expectedField.getString("defaultValue")
                    }
                    assertEquals(location, defaultValue, field.defaultValue)
                }
            }
        }
    }
}
