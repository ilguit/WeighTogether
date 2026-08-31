package com.palixander.scalesync.backup

import com.palixander.scalesync.data.MeasurementType
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
        val json = codec.encode(document()).replace("\"schemaVersion\":3", "\"schemaVersion\":4").replaceFirst("{", "{\"future\":true,")

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
    fun v3RoundTripPreservesPetsAndOlderVersionsDecodeWithEmptyProfileFields() {
        val source = document().copy(
            pets = listOf(BackupPetV2("p", "Мурка", "мурка", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", 12, 70.0, 74.5, 4.5)),
        )

        assertEquals(source, codec.decode(codec.encode(source)))

        val v2 = codec.decode(codec.encode(source.copy(schemaVersion = BACKUP_SCHEMA_VERSION_V2)))
        assertEquals(null, v2.pets.single().sex)
        assertEquals(null, v2.pets.single().breedId)
        val v1Json = codec.encode(document().copy(schemaVersion = BACKUP_SCHEMA_VERSION_V1))
        val legacy = codec.decode(v1Json)
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.pets.isEmpty())
        assertTrue(legacy.petMeasurements.isEmpty())
    }

    @Test
    fun v3RoundTripPreservesCompletePetProfile() {
        val pet = BackupPetV2("p", "Бим", "бим", PetSpecies.DOG, 10, 11, PetSex.MALE,
            "scalesync:dog:mixed-breed", 2020, 2, 29, DogAdultWeightCategory.III)
        val source = document().copy(pets = listOf(pet))
        assertEquals(source, codec.decode(codec.encode(source)))
    }

    @Test
    fun v3RejectsInvalidPetProfileValues() {
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
                leanBodyMassKg = null, algorithmVersion = null, huaweiStatus = SyncStatus.LOCAL_ONLY,
                healthConnectStatus = SyncStatus.LOCAL_ONLY, huaweiError = null,
                healthConnectError = null, huaweiWeightSynced = false,
                healthConnectWeightSynced = false, createdAtEpochMillis = 4, accountId = "a",
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL, sourcePendingId = null,
                deduplicationHash = "d", huaweiSyncedCalculatedValues = null,
                healthConnectSyncedCalculatedValues = null,
            ),
        ),
        settings = BackupSettingsV1("AA:BB", "Весы", true, emptyList(), emptyList()),
    )
}
