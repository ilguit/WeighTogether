package com.palixander.scalesync.backup

import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.data.RatingHeightOrigin
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    private val codec = BackupJsonCodec()
    private val legacyHuaweiKeys = setOf(
        "huaweiStatus",
        "huaweiError",
        "huaweiWeightSynced",
        "huaweiSyncedCalculatedValues",
    )


    @Test
    fun v6PreservesManualOriginStandalonePetWeightAndEditedFlag() {
        val source = document().copy(
            measurements = listOf(document().measurements.single().copy(
                weightKg = 4.125, origin = com.palixander.scalesync.domain.MeasurementOrigin.MANUAL,
            )),
            pets = listOf(BackupPetV2("p", "Кот", "кот", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", -60, null, null, 4.125,
                com.palixander.scalesync.domain.MeasurementOrigin.MANUAL, true)),
        )
        assertEquals(source, codec.decode(codec.encode(source)))
        val encoded = codec.encode(source)
        assertThrows(BackupException.Invalid::class.java) { codec.decode(encoded.replace("MANUAL", "UNKNOWN")) }
        assertThrows(BackupException.Invalid::class.java) { codec.decode(encoded.replace("MANUAL", "SCALE")) }
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(source.copy(schemaVersion = 5))
        }
    }

    @Test
    fun everyLegacyVersionImportsKnownHuaweiFieldsWithoutKeepingThem() {
        for (version in 1..5) {
            val decoded = codec.decode(legacyJson(version))

            assertEquals(version, decoded.schemaVersion)
            assertEquals(SyncStatus.SYNCED, decoded.measurements.single().healthConnectStatus)
            assertEquals("health error", decoded.measurements.single().healthConnectError)
            assertEquals(true, decoded.measurements.single().healthConnectWeightSynced)
            assertEquals("health-values", decoded.measurements.single().healthConnectSyncedCalculatedValues)
            assertEquals(listOf("weight", "bmi"), decoded.settings.selectedChartMetricKeys)
            assertEquals("Legacy account", decoded.accounts.single().displayName)
            if (version >= 2) assertEquals("Legacy pet", decoded.pets.single().displayName)
            assertTrue(!codec.encode(decoded.copy(schemaVersion = BACKUP_SCHEMA_VERSION)).contains("huawei", ignoreCase = true))
        }
    }

    @Test
    fun legacyHuaweiFieldValuesAreIgnoredButUnknownFieldsAreRejected() {
        val arbitraryValues = listOf("null", "false", "42", "\"arbitrary\"", "{}", "[]")
        for (version in 1..5) {
            for (value in arbitraryValues) {
                val root = JsonParser.parseString(legacyJson(version)).asJsonObject
                root.getAsJsonArray("measurements").single().asJsonObject.apply {
                    legacyHuaweiKeys.forEach { add(it, JsonParser.parseString(value)) }
                }

                val decoded = codec.decode(root.toString())

                assertEquals("Legacy account", decoded.accounts.single().displayName)
                assertEquals(SyncStatus.SYNCED, decoded.measurements.single().healthConnectStatus)
                assertEquals("health error", decoded.measurements.single().healthConnectError)
                assertEquals(true, decoded.measurements.single().healthConnectWeightSynced)
                assertEquals("health-values", decoded.measurements.single().healthConnectSyncedCalculatedValues)
            }
        }
        val legacy = legacyJson(5)
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(legacy.replace("\"huaweiStatus\":\"FAILED\"", "\"huaweiStatus\":\"FAILED\",\"huaweiFuture\":false"))
        }
    }

    @Test
    fun roundTripPreservesUnicodeAndEmptyOptionalCollections() {
        val source = document(accountName = "Иван ⚖")

        val decoded = codec.decode(codec.encode(source))

        assertEquals(source, decoded)
        assertTrue(codec.encode(source).toByteArray(Charsets.UTF_8).isNotEmpty())
    }

    @Test
    fun emptyBackupRoundTrips() {
        val source = document().copy(
            accounts = emptyList(),
            appState = BackupAppStateV1(null, 3.0, false),
            measurements = emptyList(),
        )

        assertEquals(source, codec.decode(codec.encode(source)))
    }

    @Test
    fun v6JsonContainsNoHuaweiFields() {
        val encoded = codec.encode(document())

        assertTrue(!encoded.contains("huawei", ignoreCase = true))
        assertEquals(document(), codec.decode(encoded))
    }

    @Test
    fun unsupportedVersionIsReportedBeforeUnknownFields() {
        val json = codec.encode(document())
            .replace("\"schemaVersion\":6", "\"schemaVersion\":7")
            .replaceFirst("{", "{\"future\":true,")

        assertThrows(BackupException.UnsupportedVersion::class.java) { codec.decode(json) }
    }

    @Test
    fun corruptAndUnknownOrMissingFieldsAreTyped() {
        assertThrows(BackupException.Corrupt::class.java) { codec.decode("{") }
        val encoded = codec.encode(document())
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replaceFirst("{", "{\"future\":true,"))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"exportedAt\":\"2026-08-25T00:00:00Z\",", ""))
        }
    }

    @Test
    fun enumAndNumericBoundariesAreValidated() {
        val encoded = codec.encode(document())
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"measurementType\":\"WEIGHT_ONLY\"", "\"measurementType\":\"FUTURE\""))
        }
        assertEquals(0.1, codec.decode(codec.encode(document().copy(appState = BackupAppStateV1("a", 0.1, false)))).appState.weightDeltaKg, 0.0)
        assertEquals(50.0, codec.decode(codec.encode(document().copy(appState = BackupAppStateV1("a", 50.0, false)))).appState.weightDeltaKg, 0.0)
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(document().copy(appState = BackupAppStateV1("a", 50.1, false)))
        }
    }

    @Test
    fun duplicateStableValuesAndMissingAccountsAreRejected() {
        val source = document()
        assertThrows(BackupException.Duplicate::class.java) {
            codec.encode(source.copy(measurements = listOf(source.measurements.single(), source.measurements.single().copy(accountId = "a"))))
        }
        assertThrows(BackupException.MissingAccount::class.java) {
            codec.encode(source.copy(measurements = listOf(source.measurements.single().copy(accountId = "missing"))))
        }
    }

    @Test
    fun duplicateNonNullMeasurementSourcePendingIdsAreRejected() {
        val measurement = document().measurements.single().copy(sourcePendingId = "pending-stable")
        val duplicate = measurement.copy(
            id = "other-id",
            fingerprint = "other-fingerprint",
            deduplicationHash = "other-hash",
        )

        val error = assertThrows(BackupException.Duplicate::class.java) {
            codec.encode(document().copy(measurements = listOf(measurement, duplicate)))
        }

        assertEquals("measurement source pending id", error.path)
        assertEquals("pending-stable", error.value)
    }

    @Test
    fun collectionLimitsAreRejected() {
        val account = document().accounts.single()
        assertThrows(BackupException.Limits::class.java) {
            codec.encode(document().copy(accounts = List(MAX_BACKUP_ACCOUNTS + 1) { account.copy(id = "a$it") }, measurements = emptyList()))
        }
        assertThrows(BackupException.Limits::class.java) {
            codec.encode(document().copy(settings = BackupSettingsV1(null, null, false, List(MAX_BACKUP_SERIES_KEYS + 1) { "k$it" }, null)))
        }
    }

    @Test
    fun v6RoundTripPreservesMeasurementContextAndPets() {
        val source = document().copy(
            measurements = listOf(
                document().measurements.single().copy(
                    ratingHeightCm = 181.5,
                    ratingHeightOrigin = RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
                ),
            ),
            pets = listOf(BackupPetV2("p", "Мурка", "мурка", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", 12, 70.0, 74.5, 4.5)),
        )

        assertEquals(source, codec.decode(codec.encode(source)))
        assertEquals(181.5, codec.decode(codec.encode(source)).measurements.single().ratingHeightCm)
    }

    @Test
    fun schemaShapesAndMeasurementContextValuesAreStrict() {
        val encoded = codec.encode(document())
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace(",\"ratingHeightCm\":null", ""))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace(",\"ratingHeightOrigin\":\"CAPTURED\"", ""))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"ratingHeightOrigin\":\"CAPTURED\"", "\"ratingHeightOrigin\":\"FUTURE\""))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(document().copy(measurements = listOf(document().measurements.single().copy(ratingHeightCm = Double.NaN))))
        }
    }

    @Test
    fun v6RoundTripPreservesCompletePetProfile() {
        val pet = BackupPetV2("p", "Бим", "бим", PetSpecies.DOG, 10, 11, PetSex.MALE,
            "scalesync:dog:mixed-breed", 2020, 2, 29, DogAdultWeightCategory.III)
        val source = document().copy(pets = listOf(pet))
        assertEquals(source, codec.decode(codec.encode(source)))
    }

    @Test
    fun v6RejectsInvalidPetProfileValues() {
        fun json(pet: BackupPetV2) = codec.encode(document().copy(pets = listOf(pet)))
        val base = BackupPetV2("p", "Cat", "cat", PetSpecies.CAT, 1, 2)
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(json(base).replace("\"birthYear\":null", "\"birthYear\":2021")
                .replace("\"birthMonth\":null", "\"birthMonth\":2").replace("\"birthDay\":null", "\"birthDay\":29"))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(json(base).replace("\"sex\":null", "\"sex\":\"FUTURE\""))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(document().copy(pets = listOf(base.copy(breedId = "scalesync:dog:mixed-breed"))))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(document().copy(pets = listOf(base.copy(dogAdultWeightCategory = DogAdultWeightCategory.I))))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(json(base).replace("\"birthMonth\":null", "\"birthMonth\":2"))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(json(base).replace("\"birthDay\":null", "\"birthDay\":1"))
        }
        val unknown = base.copy(breedId = "external:cat:future")
        assertEquals(unknown, codec.decode(json(unknown)).pets.single())
    }

    @Test
    fun v2RoundTripPreservesLegacyUnspecifiedPetSpecies() {
        val source = document().copy(
            pets = listOf(BackupPetV2("p", "Legacy pet", "legacy pet", PetSpecies.UNSPECIFIED, 10, 11)),
        )

        val decoded = codec.decode(codec.encode(source))

        assertEquals(PetSpecies.UNSPECIFIED, decoded.pets.single().species)
        assertEquals(source, decoded)
    }

    @Test
    fun v2RoundTripAcceptsPetMeasurementWithReverseReadingOrder() {
        val source = document().copy(
            pets = listOf(BackupPetV2("p", "Cat", "cat", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", 12, 74.5, 70.0, 4.5)),
        )

        val decoded = codec.decode(codec.encode(source))

        assertEquals(source, decoded)
        assertEquals(4.5, decoded.petMeasurements.single().petWeightKg, 0.0)
    }

    @Test
    fun invalidPetSpeciesAndMissingPetAreRejected() {
        val source = document().copy(
            pets = listOf(BackupPetV2("p", "Cat", "cat", PetSpecies.CAT, 1, 2)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", 3, 70.0, 74.0, 4.0)),
        )
        val encoded = codec.encode(source)
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"species\":\"CAT\"", "\"species\":\"DRAGON\""))
        }
        assertThrows(BackupException.MissingPet::class.java) {
            codec.decode(encoded.replace("\"petId\":\"p\"", "\"petId\":\"missing\""))
        }
    }

    private fun document(accountName: String = "Account") = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = listOf(
            BackupAccountV1(
                id = "a",
                displayName = accountName,
                normalizedName = accountName.lowercase(),
                profile = BackupAccountProfileV1(null, null, null, false),
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
            ),
        ),
        appState = BackupAppStateV1("a", 3.0, false),
        measurements = listOf(
            BackupMeasurementV1(
                id = "m", fingerprint = "f", measurementType = MeasurementType.WEIGHT_ONLY,
                deviceAddress = "AA:BB", measuredAtEpochSecond = 3, rawPayloadHex = "00ff",
                weightKg = 70.0, rawWeight = 14000, impedanceOhm = null, bmi = null,
                bodyFatPercent = null, bodyFatMassKg = null, waterPercent = null,
                waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
                boneMassKg = null, proteinPercent = null, proteinMassKg = null,
                visceralFatLevel = null, basalMetabolicRateKcal = null, metabolicAge = null,
                leanBodyMassKg = null, algorithmVersion = null,
                healthConnectStatus = SyncStatus.LOCAL_ONLY,
                healthConnectError = null,
                healthConnectWeightSynced = false, createdAtEpochMillis = 4, accountId = "a",
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL, sourcePendingId = null,
                deduplicationHash = "d",
                healthConnectSyncedCalculatedValues = null,
            ),
        ),
        settings = BackupSettingsV1("AA:BB", "Весы", true, emptyList(), emptyList()),
    )

    private fun legacyJson(version: Int): String {
        val root = JsonParser.parseString(codec.encode(document("Legacy account").copy(
            settings = BackupSettingsV1("AA:BB", "Legacy scale", true, listOf("weight", "bmi"), listOf("weight")),
            pets = listOf(BackupPetV2("pet", "Legacy pet", "legacy pet", PetSpecies.CAT, 5, 6)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "pet", 7, 75.0, 70.0, 5.0)),
            measurements = listOf(document().measurements.single().copy(
                healthConnectStatus = SyncStatus.SYNCED,
                healthConnectError = "health error",
                healthConnectWeightSynced = true,
                healthConnectSyncedCalculatedValues = "health-values",
            )),
        ))).asJsonObject
        root.addProperty("schemaVersion", version)
        root.getAsJsonArray("measurements").forEach { element ->
            element.asJsonObject.apply {
                addProperty("huaweiStatus", "FAILED")
                addProperty("huaweiError", "retired service error")
                addProperty("huaweiWeightSynced", true)
                addProperty("huaweiSyncedCalculatedValues", "retired-values")
                if (version < 5) remove("origin")
                if (version < 3) {
                    remove("ratingHeightCm")
                    remove("ratingHeightOrigin")
                }
            }
        }
        if (version == 1) {
            root.remove("pets")
            root.remove("petMeasurements")
        } else {
            root.getAsJsonArray("petMeasurements").forEach { element ->
                element.asJsonObject.remove("isManuallyEdited")
                if (version < 5) element.asJsonObject.remove("origin")
            }
            if (version < 4) {
                root.getAsJsonArray("pets").forEach { element ->
                    listOf("sex", "breedId", "birthYear", "birthMonth", "birthDay", "dogAdultWeightCategory")
                        .forEach(element.asJsonObject::remove)
                }
            }
        }
        return root.toString()
    }
}
