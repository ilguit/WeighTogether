package com.palixander.weightogether.gradle

import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuStoreSigningTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun environment(file: File) = mapOf(
        "RUSTORE_KEYSTORE_FILE" to file.absolutePath,
        "RUSTORE_KEYSTORE_PASSWORD" to "store-secret",
        "RUSTORE_KEY_ALIAS" to "owner-key",
        "RUSTORE_KEY_PASSWORD" to "key-secret",
    )

    @Test
    fun signingRequiresExplicitOptIn() {
        assertNull(RuStoreSigning.read(null, emptyMap(), temporary.root))
        assertNull(RuStoreSigning.read("false", emptyMap(), temporary.root))
        assertThrows(GradleException::class.java) {
            RuStoreSigning.read("TRUE", emptyMap(), temporary.root)
        }
    }

    @Test
    fun eachMissingOrBlankVariableFailsWithoutExposingSecrets() {
        val values = environment(temporary.newFile("owner.jks"))
        for (name in RuStoreSigning.environmentNames) {
            for (replacement in listOf(null, "", " ")) {
                val incomplete = values.toMutableMap()
                if (replacement == null) incomplete.remove(name) else incomplete[name] = replacement
                val failure = assertThrows(GradleException::class.java) {
                    RuStoreSigning.read("true", incomplete, temporary.newFolder())
                }
                assertTrue(failure.message!!.contains(name))
                assertFalse(failure.message!!.contains("store-secret"))
                assertFalse(failure.message!!.contains("key-secret"))
            }
        }
    }

    @Test
    fun relativeMissingDirectoryAndCheckoutPathsFail() {
        val checkout = temporary.newFolder("checkout")
        val files = listOf(
            File("relative.jks"),
            File(temporary.root, "missing.jks"),
            temporary.newFolder("directory.jks"),
            File(checkout, "owner.jks").apply { writeText("fixture") },
        )
        for (file in files) {
            assertThrows(GradleException::class.java) {
                RuStoreSigning.read("true", environment(file) + ("RUSTORE_KEYSTORE_FILE" to file.path), checkout)
            }
        }
    }

    @Test
    fun externalFilePreservesPasswordsExactlyAndDoesNotExposeThemInToString() {
        val file = temporary.newFile("owner.jks")
        val values = environment(file) + ("RUSTORE_KEY_PASSWORD" to " key-secret ")
        val config = RuStoreSigning.read("true", values, temporary.newFolder())!!
        assertEquals(file.canonicalFile, config.storeFile)
        assertEquals(" key-secret ", config.keyPassword)
        assertEquals("store-secret", config.storePassword)
        assertEquals("owner-key", config.keyAlias)
        assertFalse(config.toString().contains("secret"))
    }
}
