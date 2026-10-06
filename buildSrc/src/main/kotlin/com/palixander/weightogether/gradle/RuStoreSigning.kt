package com.palixander.weightogether.gradle

import java.io.File
import org.gradle.api.GradleException

// Deliberately not a data class: generated toString() must never expose passwords.
class RuStoreSigning private constructor(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        val environmentNames = listOf(
            "RUSTORE_KEYSTORE_FILE",
            "RUSTORE_KEYSTORE_PASSWORD",
            "RUSTORE_KEY_ALIAS",
            "RUSTORE_KEY_PASSWORD",
        )

        fun read(enabled: String?, environment: Map<String, String>, checkout: File): RuStoreSigning? {
            if (enabled == null || enabled == "false") return null
            if (enabled != "true") {
                throw GradleException("rustoreSigning must be true or false.")
            }
            val missing = environmentNames.filter { environment[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException("RuStore signing requires environment variables: ${missing.joinToString()}")
            }
            val suppliedFile = File(environment.getValue("RUSTORE_KEYSTORE_FILE"))
            if (!suppliedFile.isAbsolute) {
                throw GradleException("RUSTORE_KEYSTORE_FILE must be an absolute path outside the checkout.")
            }
            val storeFile = suppliedFile.canonicalFile
            if (storeFile.toPath().startsWith(checkout.canonicalFile.toPath())) {
                throw GradleException("RUSTORE_KEYSTORE_FILE must be outside the checkout.")
            }
            if (!storeFile.isFile || !storeFile.canRead()) {
                throw GradleException("RUSTORE_KEYSTORE_FILE must identify a readable keystore file.")
            }
            return RuStoreSigning(
                storeFile = storeFile,
                storePassword = environment.getValue("RUSTORE_KEYSTORE_PASSWORD"),
                keyAlias = environment.getValue("RUSTORE_KEY_ALIAS"),
                keyPassword = environment.getValue("RUSTORE_KEY_PASSWORD"),
            )
        }
    }
}
