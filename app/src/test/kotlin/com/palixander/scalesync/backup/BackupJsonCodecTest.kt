package com.palixander.scalesync.backup

import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.data.RatingHeightOrigin
import com.palixander.scalesync.data.SyncStatus
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    private val codec = BackupJsonCodec()


    @Test
    fun v5PreservesManualOriginAndStandalonePetWeight() {
        val source = document().copy(
            measurements = listOf(document().measurements.single().copy(
                weightKg = 4.125, origin = com.palixander.scalesync.domain.MeasurementOrigin.MANUAL,
            )),
            pets = listOf(BackupPetV2("p", "Кот", "кот", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", -60, null, null, 4.125,
                com.palixander.scalesync.domain.MeasurementOrigin.MANUAL)),
        )
        assertEquals(source, codec.decode(codec.encode(source)))
        val encoded = codec.encode(source)
        assertThrows(BackupException.Invalid::class.java) { codec.decode(encoded.replace("MANUAL", "UNKNOWN")) }
        assertThrows(BackupException.Invalid::class.java) { codec.decode(encoded.replace("MANUAL", "SCALE")) }
        assertThrows(BackupException.Invalid::class.java) { codec.encode(source.copy(schemaVersion = 4)) }
    }

    @Test
    fun everyLegacyVersionKeepsUnknownOriginIncludingOldManualAddress() {
        for (version in 1..4) {
            val source = document().copy(schemaVersion = version,
                measurements = listOf(document().measurements.single().copy(deviceAddress = "manual")))
            val encoded = codec.encode(source)
            assertTrue(!encoded.contains("origin"))
            val decoded = codec.decode(encoded)
            assertEquals(com.palixander.scalesync.domain.MeasurementOrigin.LEGACY, decoded.measurements.single().origin)
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
    fun unsupportedVersionIsReportedBeforeUnknownFields() {
        val json = codec.encode(document()).replace("\"schemaVersion\":5", "\"schemaVersion\":6").replaceFirst("{", "{\"future\":true,")

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
    fun v4RoundTripPreservesMeasurementContextAndPets() {
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
    fun v1AndV2RestoreEachMeasurementHeightFromItsImportedOwnerOnly() {
        val current = document().copy(
            accounts = listOf(
                document().accounts.single().copy(id = "a", profile = BackupAccountProfileV1(161.0, null, null, false)),
                document().accounts.single().copy(id = "b", normalizedName = "account-b", profile = BackupAccountProfileV1(null, null, null, false)),
            ),
            measurements = listOf(
                document().measurements.single().copy(id = "ma", fingerprint = "fa", accountId = "a", deduplicationHash = "da"),
                document().measurements.single().copy(id = "mb", fingerprint = "fb", accountId = "b", deduplicationHash = "db"),
            ),
        )
        val encoded = codec.encode(current)
        val legacyMeasurementFields = ",\"ratingHeightCm\":null,\"ratingHeightOrigin\":\"CAPTURED\""

        listOf(BACKUP_SCHEMA_VERSION_V1, BACKUP_SCHEMA_VERSION_V2).forEach { version ->
            var legacyJson = encoded
                .replace("\"schemaVersion\":5", "\"schemaVersion\":$version")
                .replace(legacyMeasurementFields, "")
                .replace(",\"origin\":\"LEGACY\"", "")
            if (version == BACKUP_SCHEMA_VERSION_V1) {
                legacyJson = legacyJson.replace(",\"pets\":[],\"petMeasurements\":[]", "")
            }
            val decoded = codec.decode(legacyJson)
            assertEquals(listOf(161.0, null), decoded.measurements.map { it.ratingHeightCm })
            assertTrue(decoded.measurements.all { it.ratingHeightOrigin == RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT })
        }
    }

    @Test
    fun schemaShapesAndMeasurementContextValuesAreStrict() {
        val encoded = codec.encode(document())
        val v2WithV3Fields = encoded.replace("\"schemaVersion\":5", "\"schemaVersion\":2")
        assertThrows(BackupException.Invalid::class.java) { codec.decode(v2WithV3Fields) }
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

        val v1Json = encoded.replace("\"schemaVersion\":5", "\"schemaVersion\":1")
            .replace(",\"ratingHeightCm\":null,\"ratingHeightOrigin\":\"CAPTURED\"", "")
            .replace(",\"origin\":\"LEGACY\"", "")
            .replace(",\"pets\":[],\"petMeasurements\":[]", "")
        val legacy = codec.decode(v1Json)
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.pets.isEmpty())
        assertTrue(legacy.petMeasurements.isEmpty())
    }

    @Test
    fun v4RoundTripPreservesCompletePetProfile() {
        val pet = BackupPetV2("p", "Бим", "бим", PetSpecies.DOG, 10, 11, PetSex.MALE,
            "scalesync:dog:mixed-breed", 2020, 2, 29, DogAdultWeightCategory.III)
        val source = document().copy(pets = listOf(pet))
        assertEquals(source, codec.decode(codec.encode(source)))
    }

    @Test
    fun v2AndV3EncodeRejectEveryV4PetProfileField() {
        val base = BackupPetV2("p", "Бим", "бим", PetSpecies.DOG, 10, 11)

        fun assertRejected(field: String, pet: BackupPetV2) {
            listOf(BACKUP_SCHEMA_VERSION_V2, BACKUP_SCHEMA_VERSION_V3).forEach { version ->
                val error = assertThrows(BackupException.Invalid::class.java) {
                    codec.encode(
                        document().copy(
                            schemaVersion = version,
                            pets = listOf(pet),
                        ),
                    )
                }
                assertEquals("$.pets[0].$field", error.path)
            }
        }

        assertRejected("sex", base.copy(sex = PetSex.MALE))
        assertRejected("breedId", base.copy(breedId = "scalesync:dog:mixed-breed"))
        assertRejected("birthYear", base.copy(birthYear = 2020))
        assertRejected("birthMonth", base.copy(birthMonth = 2))
        assertRejected("birthDay", base.copy(birthDay = 29))
        assertRejected("dogAdultWeightCategory", base.copy(dogAdultWeightCategory = DogAdultWeightCategory.III))
    }

    @Test
    fun v4RejectsInvalidPetProfileValues() {
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
}
