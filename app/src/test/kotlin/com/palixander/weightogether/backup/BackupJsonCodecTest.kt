package com.palixander.weightogether.backup

import com.palixander.weightogether.data.MeasurementType
import com.palixander.weightogether.data.RatingHeightOrigin
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.data.WeighingReminderOwnerType
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.domain.WeighingReminderImportance
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    @Test
    fun versionSevenReminderBackupRemainsReadableWithoutAlarmSound() {
        val legacy = codec.encode(
            document().copy(
                reminderSchedules = listOf(
                    BackupReminderScheduleV7(
                        "r",
                        WeighingReminderOwnerType.ACCOUNT,
                        "a",
                        540,
                        1,
                        WeighingReminderImportance.ALARM,
                        true,
                        1,
                        2,
                        "content://media/alarm/7",
                    ),
                ),
            ),
        )
            .replace("\"schemaVersion\":$BACKUP_SCHEMA_VERSION", "\"schemaVersion\":7")
            .replace(Regex(",\"alarmSoundUri\":(?:null|\"[^\"]*\")"), "")

        val decoded = codec.decode(legacy)

        assertEquals(7, decoded.schemaVersion)
        assertEquals(1, decoded.reminderSchedules.size)
        assertTrue(decoded.reminderSchedules.single().alarmSoundUri == null)
    }

    @Test
    fun petHeightRoundTripsAndLegacyBackupsHaveNoHeight() {
        val pet = BackupPetV2("p", "Cat", "cat", PetSpecies.CAT, 1, 2, heightCm = 25.5)
        val current = document().copy(pets = listOf(pet))
        assertEquals(current, codec.decode(codec.encode(current)))
        for (version in 2..8) {
            val legacy = current.copy(schemaVersion = version, pets = listOf(pet.copy(heightCm = null)))
            val json = codec.encode(legacy)
            assertTrue(!JsonParser.parseString(json).asJsonObject.getAsJsonArray("pets")[0].asJsonObject.has("heightCm"))
            assertTrue(codec.decode(json).pets.single().heightCm == null)
        }
        for (height in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(BackupException.Invalid::class.java) {
                codec.encode(current.copy(pets = listOf(pet.copy(heightCm = height))))
            }
        }
        val malformed = JsonParser.parseString(codec.encode(current)).asJsonObject
        malformed.getAsJsonArray("pets")[0].asJsonObject.addProperty("heightCm", "25.5")
        assertThrows(BackupException.Invalid::class.java) { codec.decode(malformed.toString()) }
    }

    private val codec = BackupJsonCodec()

    @Test
    fun reminderSchedulesRoundTripInV7AndAreAbsentFromV1ThroughV6() {
        val reminder = BackupReminderScheduleV7(
            "r", WeighingReminderOwnerType.ACCOUNT, "a", 1439, 127,
            WeighingReminderImportance.ALARM, false, 10, 11,
        )
        val current = document().copy(reminderSchedules = listOf(reminder))
        assertEquals(listOf(reminder), codec.decode(codec.encode(current)).reminderSchedules)
        for (version in 1..6) {
            val legacy = codec.decode(codec.encode(document().copy(schemaVersion = version)))
            assertEquals(emptyList<BackupReminderScheduleV7>(), legacy.reminderSchedules)
        }
    }

    @Test
    fun reminderScheduleShapeLimitsAndOwnerAreStrictlyValidated() {
        val valid = document().copy(reminderSchedules = listOf(
            BackupReminderScheduleV7("r", WeighingReminderOwnerType.ACCOUNT, "a", 540, 1,
                WeighingReminderImportance.REGULAR, true, 1, 2),
        ))
        val encoded = codec.encode(valid)
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"minuteOfDay\":540", "\"minuteOfDay\":1440"))
        }
        assertThrows(BackupException.MissingReminderOwner::class.java) {
            codec.decode(encoded.replace("\"ownerId\":\"a\"", "\"ownerId\":\"missing\""))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"enabled\":true", "\"enabled\":\"yes\""))
        }
    }


    @Test
    fun v6PreservesManualOriginStandalonePetWeightAndEditedFlag() {
        val source = document().copy(
            measurements = listOf(document().measurements.single().copy(
                weightKg = 4.125, origin = com.palixander.weightogether.domain.MeasurementOrigin.MANUAL,
            )),
            pets = listOf(BackupPetV2("p", "Кот", "кот", PetSpecies.CAT, 10, 11)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", -60, null, null, 4.125,
                com.palixander.weightogether.domain.MeasurementOrigin.MANUAL, true)),
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
    fun everyLegacyVersionImportsRetainedMeasurementFields() {
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
        }
    }

    @Test
    fun everyVersionRejectsUnknownMeasurementFields() {
        for (version in 1..BACKUP_SCHEMA_VERSION) {
            val root = JsonParser.parseString(if (version <= 5) legacyJson(version) else codec.encode(document())).asJsonObject
            root.getAsJsonArray("measurements").single().asJsonObject.addProperty("unknownSyncStatus", "FAILED")
            assertThrows(BackupException.Invalid::class.java) { codec.decode(root.toString()) }
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
    fun currentJsonRoundTripsActiveData() {
        val encoded = codec.encode(document())

        assertEquals(document(), codec.decode(encoded))
    }

    @Test
    fun unsupportedVersionIsReportedBeforeUnknownFields() {
        val json = codec.encode(document())
            .replace("\"schemaVersion\":$BACKUP_SCHEMA_VERSION", "\"schemaVersion\":${BACKUP_SCHEMA_VERSION + 1}")
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
        root.remove("reminderSchedules")
        root.getAsJsonArray("measurements").forEach { element ->
            element.asJsonObject.apply {
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
            root.getAsJsonArray("pets").forEach { it.asJsonObject.remove("heightCm") }
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
