package com.palixander.scalesync.backup

import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
import com.palixander.scalesync.domain.PetSpecies
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.Instant

class BackupJsonCodec(
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create(),
    private val breedCatalog: BreedCatalog = BreedCatalog.bundled(),
) {
    fun encode(document: BackupDocumentV1): String {
        try {
            validate(document)
        } catch (error: BackupException) {
            throw error
        } catch (error: RuntimeException) {
            throw BackupException.Invalid("$", error.message ?: "invalid value")
        }
        val root = gson.toJsonTree(document).asJsonObject
        if (document.schemaVersion < BACKUP_SCHEMA_VERSION_V5) {
            root.getAsJsonArray("measurements").forEach { it.asJsonObject.remove("origin") }
            root.getAsJsonArray("petMeasurements").forEach { it.asJsonObject.remove("origin") }
        }
        if (document.schemaVersion < BACKUP_SCHEMA_VERSION) {
            root.getAsJsonArray("petMeasurements").forEach { it.asJsonObject.remove("isManuallyEdited") }
        }
        if (document.schemaVersion < BACKUP_SCHEMA_VERSION_V3) {
            root.getAsJsonArray("measurements").forEach { element ->
                (MEASUREMENT_KEYS_V3 - MEASUREMENT_KEYS_V1_V2).forEach(element.asJsonObject::remove)
            }
        }
        if (document.schemaVersion == BACKUP_SCHEMA_VERSION_V1) {
            root.remove("pets")
            root.remove("petMeasurements")
        } else if (document.schemaVersion in BACKUP_SCHEMA_VERSION_V2..BACKUP_SCHEMA_VERSION_V3) {
            root.getAsJsonArray("pets").forEach { element ->
                (PET_KEYS_V3 - PET_KEYS_V2).forEach(element.asJsonObject::remove)
            }
        }
        return gson.toJson(root)
    }

    fun decode(json: String): BackupDocumentV1 {
        val root = try {
            JsonParser.parseString(json).requiredObject("$")
        } catch (error: BackupException) {
            throw error
        } catch (error: JsonParseException) {
            throw BackupException.Corrupt(error)
        } catch (error: IllegalStateException) {
            throw BackupException.Corrupt(error)
        }

        val versionElement = root.get("schemaVersion")
        val version = versionElement?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isNumber
        }?.let { runCatching { it.asInt }.getOrNull() }
        if (version !in SUPPORTED_VERSIONS) {
            throw BackupException.UnsupportedVersion(version)
        }
        val supportedVersion = requireNotNull(version)

        checkShape(root, supportedVersion)
        if (supportedVersion == BACKUP_SCHEMA_VERSION_V1) {
            root.add("pets", com.google.gson.JsonArray())
            root.add("petMeasurements", com.google.gson.JsonArray())
        }
        if (supportedVersion < BACKUP_SCHEMA_VERSION_V5) {
            root.array("measurements").forEach { it.asJsonObject.addProperty("origin", "LEGACY") }
            root.array("petMeasurements").forEach { it.asJsonObject.addProperty("origin", "LEGACY") }
        }
        if (supportedVersion < BACKUP_SCHEMA_VERSION) {
            root.array("petMeasurements").forEach { it.asJsonObject.addProperty("isManuallyEdited", false) }
        }
        var document = try {
            gson.fromJson(root, BackupDocumentV1::class.java)
        } catch (error: JsonParseException) {
            throw BackupException.Invalid("$", error.message ?: "type mismatch")
        }
        if (supportedVersion < BACKUP_SCHEMA_VERSION_V3) {
            val importedAccountsById = document.accounts.associateBy { it.id }
            document = document.copy(
                measurements = document.measurements.map { measurement ->
                    measurement.copy(
                        ratingHeightCm = importedAccountsById[measurement.accountId]?.profile?.heightCm,
                        ratingHeightOrigin = com.palixander.scalesync.data.RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
                    )
                },
            )
        }
        validate(document)
        return document
    }

    private fun checkShape(root: JsonObject, version: Int) {
        root.requireKeys("$", if (version == BACKUP_SCHEMA_VERSION_V1) ROOT_KEYS_V1 else ROOT_KEYS_V2)
        root.requireStrings("$", setOf("format", "exportedAt"))
        root.requireNumbers("$", setOf("schemaVersion"))
        root.array("accounts").forEachIndexed { index, element ->
            val account = element.requiredObject("$.accounts[$index]")
            account.requireKeys("$.accounts[$index]", ACCOUNT_KEYS)
            account.requireStrings("$.accounts[$index]", setOf("id", "displayName", "normalizedName"))
            account.requireNumbers("$.accounts[$index]", setOf("createdAtEpochMillis", "updatedAtEpochMillis"))
            account.objectValue("profile").apply {
                requireKeys("$.accounts[$index].profile", PROFILE_KEYS)
                requireNullableNumbers("$.accounts[$index].profile", setOf("heightCm", "birthDateEpochDay"))
                requireNullableStrings("$.accounts[$index].profile", setOf("sex"))
                requireBooleans("$.accounts[$index].profile", setOf("complete"))
            }
        }
        root.objectValue("appState").apply {
            requireKeys("$.appState", APP_STATE_KEYS)
            requireNullableStrings("$.appState", setOf("primaryAccountId"))
            requireNumbers("$.appState", setOf("weightDeltaKg"))
            requireBooleans("$.appState", setOf("ignoreUnknownMeasurements"))
        }
        root.array("measurements").forEachIndexed { index, element ->
            element.requiredObject("$.measurements[$index]").apply {
                val path = "$.measurements[$index]"
                val expectedKeys = when {
                    version >= BACKUP_SCHEMA_VERSION -> MEASUREMENT_KEYS_CURRENT + "origin"
                    version >= BACKUP_SCHEMA_VERSION_V5 -> MEASUREMENT_KEYS_CURRENT + setOf("ratingHeightCm", "ratingHeightOrigin", "origin")
                    version >= BACKUP_SCHEMA_VERSION_V3 -> MEASUREMENT_KEYS_CURRENT + setOf("ratingHeightCm", "ratingHeightOrigin")
                    else -> MEASUREMENT_KEYS_CURRENT - setOf("ratingHeightCm", "ratingHeightOrigin")
                }
                if (version < BACKUP_SCHEMA_VERSION) {
                    requireKeys(path, expectedKeys, LEGACY_HUAWEI_MEASUREMENT_KEYS)
                } else {
                    requireKeys(path, expectedKeys)
                }
                if (version < BACKUP_SCHEMA_VERSION) {
                    LEGACY_HUAWEI_MEASUREMENT_KEYS.forEach(::remove)
                }
                requireStrings(path, MEASUREMENT_STRING_KEYS_CURRENT)
                requireNullableStrings(path, MEASUREMENT_NULLABLE_STRING_KEYS_CURRENT)
                if (version >= BACKUP_SCHEMA_VERSION_V5) requireEnum(path, "origin", setOf("LEGACY", "SCALE", "MANUAL"))
                if (version >= BACKUP_SCHEMA_VERSION_V3) requireStrings(path, setOf("ratingHeightOrigin"))
                requireNumbers(path, MEASUREMENT_NUMBER_KEYS)
                requireNullableNumbers(
                    path,
                    if (version >= BACKUP_SCHEMA_VERSION_V3) {
                        MEASUREMENT_NULLABLE_NUMBER_KEYS + "ratingHeightCm"
                    } else {
                        MEASUREMENT_NULLABLE_NUMBER_KEYS
                    },
                )
                requireBooleans(path, MEASUREMENT_BOOLEAN_KEYS_CURRENT)
            }
        }
        root.objectValue("settings").apply {
            requireKeys("$.settings", SETTINGS_KEYS)
            requireNullableStrings("$.settings", setOf("scaleAddress", "scaleName"))
            requireBooleans("$.settings", setOf("reliabilityMode"))
            requireNullableStringArrays("$.settings", setOf("selectedChartMetricKeys", "homeKgChartSeriesKeys"))
        }
        if (version >= BACKUP_SCHEMA_VERSION_V2) {
            root.array("pets").forEachIndexed { index, element ->
                val path = "$.pets[$index]"
                element.requiredObject(path).apply {
                    requireKeys(path, if (version < BACKUP_SCHEMA_VERSION_V4) PET_KEYS_V2 else PET_KEYS_V3)
                    requireStrings(path, setOf("id", "displayName", "normalizedName", "species"))
                    requireEnum(path, "species", setOf("CAT", "DOG", "UNSPECIFIED"))
                    requireNumbers(path, setOf("createdAtEpochMillis", "updatedAtEpochMillis"))
                    if (version >= BACKUP_SCHEMA_VERSION_V4) {
                        requireNullableStrings(path, setOf("sex", "breedId", "dogAdultWeightCategory"))
                        requireNullableNumbers(path, setOf("birthYear", "birthMonth", "birthDay"))
                        requireNullableEnum(path, "sex", setOf("MALE", "FEMALE"))
                        requireNullableEnum(path, "dogAdultWeightCategory", enumValues<com.palixander.scalesync.domain.reference.DogAdultWeightCategory>().map { it.name }.toSet())
                    }
                }
            }
            root.array("petMeasurements").forEachIndexed { index, element ->
                val path = "$.petMeasurements[$index]"
                element.requiredObject(path).apply {
                    requireKeys(path, when {
                        version >= BACKUP_SCHEMA_VERSION -> PET_MEASUREMENT_KEYS + setOf("origin", "isManuallyEdited")
                        version >= BACKUP_SCHEMA_VERSION_V5 -> PET_MEASUREMENT_KEYS + "origin"
                        else -> PET_MEASUREMENT_KEYS
                    })
                    requireStrings(path, setOf("id", "petId"))
                    requireNumbers(path, setOf("measuredAtEpochSecond", "petWeightKg"))
                    if (version >= BACKUP_SCHEMA_VERSION_V5) {
                        requireNullableNumbers(path, setOf("firstWeightKg", "secondWeightKg"))
                        requireEnum(path, "origin", setOf("LEGACY", "SCALE", "MANUAL"))
                    } else {
                        requireNumbers(path, setOf("firstWeightKg", "secondWeightKg"))
                    }
                    if (version >= BACKUP_SCHEMA_VERSION) requireBooleans(path, setOf("isManuallyEdited"))
                }
            }
        }
    }

    private fun validate(document: BackupDocumentV1) {
        invalidUnless(document.format == BACKUP_FORMAT_ID, "$.format", "unexpected format")
        if (document.schemaVersion !in SUPPORTED_VERSIONS) {
            throw BackupException.UnsupportedVersion(document.schemaVersion)
        }
        invalidUnless(runCatching { Instant.parse(document.exportedAt) }.isSuccess, "$.exportedAt", "expected ISO-8601 instant")
        if (document.accounts.size > MAX_BACKUP_ACCOUNTS) {
            throw BackupException.Limits("$.accounts", MAX_BACKUP_ACCOUNTS)
        }
        if (document.measurements.size > MAX_BACKUP_MEASUREMENTS) {
            throw BackupException.Limits("$.measurements", MAX_BACKUP_MEASUREMENTS)
        }
        if (document.pets.size > MAX_BACKUP_PETS) {
            throw BackupException.Limits("$.pets", MAX_BACKUP_PETS)
        }
        if (document.petMeasurements.size > MAX_BACKUP_PET_MEASUREMENTS) {
            throw BackupException.Limits("$.petMeasurements", MAX_BACKUP_PET_MEASUREMENTS)
        }

        unique(document.accounts.map { it.id }, "account id")
        document.accounts.forEachIndexed { index, account ->
            val path = "$.accounts[$index]"
            invalidUnless(account.id.isNotBlank(), "$path.id", "must not be blank")
            invalidUnless(account.displayName.isNotBlank(), "$path.displayName", "must not be blank")
            invalidUnless(account.normalizedName.isNotBlank(), "$path.normalizedName", "must not be blank")
            invalidUnless(account.updatedAtEpochMillis >= account.createdAtEpochMillis, "$path.updatedAtEpochMillis", "precedes creation")
            account.profile.heightCm?.let {
                invalidUnless(it.isFinite() && it > 0.0, "$path.profile.heightCm", "must be positive and finite")
            }
            if (account.profile.complete) {
                invalidUnless(account.profile.heightCm != null && account.profile.birthDateEpochDay != null && account.profile.sex != null, "$path.profile", "complete profile has missing fields")
            }
        }

        finiteInRange(document.appState.weightDeltaKg, 0.1, 50.0, "$.appState.weightDeltaKg")
        val accountIds = document.accounts.mapTo(hashSetOf()) { it.id }
        document.appState.primaryAccountId?.let {
            if (it !in accountIds) throw BackupException.MissingAccount(it)
        }
        validateKeys(document.settings.selectedChartMetricKeys, "$.settings.selectedChartMetricKeys")
        validateKeys(document.settings.homeKgChartSeriesKeys, "$.settings.homeKgChartSeriesKeys")

        unique(document.measurements.map { it.id }, "measurement id")
        unique(document.measurements.map { it.fingerprint }, "measurement fingerprint")
        unique(document.measurements.mapNotNull { it.sourcePendingId }, "measurement source pending id")
        unique(document.measurements.mapNotNull { it.deduplicationHash }, "measurement deduplication hash")
        document.measurements.forEachIndexed { index, measurement ->
            val path = "$.measurements[$index]"
            invalidUnless(measurement.origin != null, "$path.origin", "unknown origin")
            if (document.schemaVersion < BACKUP_SCHEMA_VERSION_V5) {
                invalidUnless(measurement.origin == com.palixander.scalesync.domain.MeasurementOrigin.LEGACY, "$path.origin", "requires v5")
            }
            if (measurement.origin == com.palixander.scalesync.domain.MeasurementOrigin.MANUAL) {
                invalidUnless(measurement.measurementType == MeasurementType.WEIGHT_ONLY, "$path.measurementType", "manual input is weight only")
            }
            if (measurement.accountId !in accountIds) throw BackupException.MissingAccount(measurement.accountId)
            invalidUnless(measurement.id.isNotBlank(), "$path.id", "must not be blank")
            invalidUnless(measurement.fingerprint.isNotBlank(), "$path.fingerprint", "must not be blank")
            invalidUnless(measurement.deviceAddress.isNotBlank(), "$path.deviceAddress", "must not be blank")
            invalidUnless(measurement.rawPayloadHex.length % 2 == 0 && measurement.rawPayloadHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }, "$path.rawPayloadHex", "must be hexadecimal")
            invalidUnless(measurement.weightKg.isFinite() && measurement.weightKg > 0.0, "$path.weightKg", "must be positive and finite")
            invalidUnless(measurement.measurementType != null, "$path.measurementType", "unknown enum value")
            invalidUnless(measurement.healthConnectStatus != null, "$path.healthConnectStatus", "unknown enum value")
            invalidUnless(measurement.externalSyncPolicy != null, "$path.externalSyncPolicy", "unknown enum value")
            val calculated = listOf(
                measurement.bmi,
                measurement.bodyFatPercent,
                measurement.bodyFatMassKg,
                measurement.waterPercent,
                measurement.waterMassKg,
                measurement.muscleMassKg,
                measurement.skeletalMuscleMassKg,
                measurement.boneMassKg,
                measurement.proteinPercent,
                measurement.proteinMassKg,
                measurement.visceralFatLevel,
                measurement.basalMetabolicRateKcal,
                measurement.leanBodyMassKg,
            )
            invalidUnless(calculated.all { it == null || it.isFinite() }, path, "calculated values must be finite")
            if (document.schemaVersion >= BACKUP_SCHEMA_VERSION_V3) {
                measurement.ratingHeightCm?.let {
                    invalidUnless(it.isFinite() && it > 0.0, "$path.ratingHeightCm", "must be positive and finite")
                }
                invalidUnless(
                    measurement.ratingHeightOrigin != null,
                    "$path.ratingHeightOrigin",
                    "must be a known origin",
                )
            }
            if (measurement.measurementType == MeasurementType.FULL) {
                invalidUnless(measurement.impedanceOhm != null && measurement.algorithmVersion != null && calculated.all { it != null } && measurement.metabolicAge != null, path, "full measurement has missing calculated fields")
            }
        }
        if (document.schemaVersion == BACKUP_SCHEMA_VERSION_V1 &&
            (document.pets.isNotEmpty() || document.petMeasurements.isNotEmpty())
        ) {
            throw BackupException.Invalid("$", "schema v1 cannot contain pet data")
        }
        if (document.schemaVersion in BACKUP_SCHEMA_VERSION_V2..BACKUP_SCHEMA_VERSION_V3) {
            document.pets.forEachIndexed { index, pet ->
                val path = "$.pets[$index]"
                invalidUnless(pet.sex == null, "$path.sex", "requires schema v4")
                invalidUnless(pet.breedId == null, "$path.breedId", "requires schema v4")
                invalidUnless(pet.birthYear == null, "$path.birthYear", "requires schema v4")
                invalidUnless(pet.birthMonth == null, "$path.birthMonth", "requires schema v4")
                invalidUnless(pet.birthDay == null, "$path.birthDay", "requires schema v4")
                invalidUnless(pet.dogAdultWeightCategory == null, "$path.dogAdultWeightCategory", "requires schema v4")
            }
        }
        unique(document.pets.map { it.id }, "pet id")
        unique(document.pets.map { it.normalizedName }, "pet normalized name")
        document.pets.forEachIndexed { index, pet ->
            val path = "$.pets[$index]"
            invalidUnless(pet.id.isNotBlank(), "$path.id", "must not be blank")
            invalidUnless(pet.displayName.isNotBlank(), "$path.displayName", "must not be blank")
            invalidUnless(pet.normalizedName.isNotBlank(), "$path.normalizedName", "must not be blank")
            invalidUnless(pet.species != null, "$path.species", "unknown enum value")
            invalidUnless(pet.updatedAtEpochMillis >= pet.createdAtEpochMillis, "$path.updatedAtEpochMillis", "precedes creation")
            pet.breedId?.let { breedId ->
                invalidUnless(breedId.isNotBlank() && breedId == breedId.trim(), "$path.breedId", "must be non-blank and trimmed")
                breedCatalog.findById(breedId)?.let { breed ->
                    val expectedSpecies = if (breed.species == BreedSpecies.CAT) PetSpecies.CAT else PetSpecies.DOG
                    invalidUnless(pet.species == expectedSpecies, "$path.breedId", "breed species does not match pet species")
                }
            }
            invalidUnless(pet.species == PetSpecies.DOG || pet.dogAdultWeightCategory == null,
                "$path.dogAdultWeightCategory", "requires DOG species")
            validateBirthDate(pet, path)
        }
        val petIds = document.pets.mapTo(hashSetOf()) { it.id }
        unique(document.petMeasurements.map { it.id }, "pet measurement id")
        document.petMeasurements.forEachIndexed { index, measurement ->
            val path = "$.petMeasurements[$index]"
            if (document.schemaVersion < BACKUP_SCHEMA_VERSION_V5) {
                invalidUnless(measurement.origin == com.palixander.scalesync.domain.MeasurementOrigin.LEGACY, "$path.origin", "requires v5")
            }
            if (document.schemaVersion < BACKUP_SCHEMA_VERSION) {
                invalidUnless(!measurement.isManuallyEdited, "$path.isManuallyEdited", "requires v6")
            }
            if (measurement.petId !in petIds) throw BackupException.MissingPet(measurement.petId)
            invalidUnless(measurement.id.isNotBlank(), "$path.id", "must not be blank")
            invalidUnless(measurement.petId.isNotBlank(), "$path.petId", "must not be blank")
            val valid = runCatching {
                com.palixander.scalesync.domain.PetMeasurement(
                    measurement.id, com.palixander.scalesync.domain.PetId(measurement.petId),
                    java.time.Instant.ofEpochSecond(measurement.measuredAtEpochSecond),
                    measurement.firstWeightKg, measurement.secondWeightKg, measurement.petWeightKg,
                    measurement.origin, measurement.isManuallyEdited,
                )
            }.isSuccess
            invalidUnless(valid, path, "invalid pet weight or source readings")
        }
    }

    private fun validateBirthDate(pet: BackupPetV2, path: String) {
        val year = pet.birthYear
        val month = pet.birthMonth
        val day = pet.birthDay
        invalidUnless(year != null || month == null, "$path.birthMonth", "requires birthYear")
        invalidUnless(month != null || day == null, "$path.birthDay", "requires birthMonth")
        if (year != null) {
            val valid = runCatching {
                when {
                    month == null -> java.time.Year.of(year)
                    day == null -> java.time.YearMonth.of(year, month)
                    else -> java.time.LocalDate.of(year, month, day)
                }
            }.isSuccess
            invalidUnless(valid, "$path.birthYear", "invalid partial birth date")
        }
    }

    private fun validateKeys(keys: List<String>?, path: String) {
        if (keys == null) return
        if (keys.size > MAX_BACKUP_SERIES_KEYS) throw BackupException.Limits(path, MAX_BACKUP_SERIES_KEYS)
        invalidUnless(keys.all(String::isNotBlank), path, "contains a blank key")
        if (keys.size != keys.toSet().size) throw BackupException.Duplicate(path, "key")
    }

    private fun finiteInRange(value: Double, minimum: Double, maximum: Double, path: String) {
        invalidUnless(value.isFinite() && value in minimum..maximum, path, "must be between $minimum and $maximum")
    }

    private fun unique(values: List<String>, path: String) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw BackupException.Duplicate(path, duplicate)
    }

    private fun invalidUnless(condition: Boolean, path: String, detail: String) {
        if (!condition) throw BackupException.Invalid(path, detail)
    }

    private fun JsonObject.requireKeys(path: String, expected: Set<String>) {
        requireKeys(path, expected, emptySet())
    }

    private fun JsonObject.requireKeys(path: String, expected: Set<String>, optional: Set<String>) {
        val missing = expected - keySet()
        if (missing.isNotEmpty()) throw BackupException.Invalid(path, "missing ${missing.first()}")
        val unknown = keySet() - expected - optional
        if (unknown.isNotEmpty()) throw BackupException.Invalid(path, "unknown ${unknown.first()}")
    }

    private fun JsonObject.requireStrings(path: String, names: Set<String>) = names.forEach { name ->
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw BackupException.Invalid("$path.$name", "expected string")
    }

    private fun JsonObject.requireNullableStrings(path: String, names: Set<String>) = names.forEach { name ->
        if (!get(name).isJsonNull) requireStrings(path, setOf(name))
    }

    private fun JsonObject.requireNumbers(path: String, names: Set<String>) = names.forEach { name ->
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw BackupException.Invalid("$path.$name", "expected number")
    }

    private fun JsonObject.requireNullableNumbers(path: String, names: Set<String>) = names.forEach { name ->
        if (!get(name).isJsonNull) requireNumbers(path, setOf(name))
    }

    private fun JsonObject.requireBooleans(path: String, names: Set<String>) = names.forEach { name ->
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw BackupException.Invalid("$path.$name", "expected boolean")
    }

    private fun JsonObject.requireNullableStringArrays(path: String, names: Set<String>) = names.forEach { name ->
        val value = get(name)
        if (value.isJsonNull) return@forEach
        if (!value.isJsonArray || value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) {
            throw BackupException.Invalid("$path.$name", "expected string array")
        }
    }

    private fun JsonObject.requireEnum(path: String, name: String, values: Set<String>) {
        requireStrings(path, setOf(name))
        if (get(name).asString !in values) throw BackupException.Invalid("$path.$name", "unknown enum value")
    }

    private fun JsonObject.requireNullableEnum(path: String, name: String, values: Set<String>) {
        if (!get(name).isJsonNull) requireEnum(path, name, values)
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        get(name)?.requiredObject("$.$name") ?: throw BackupException.Invalid("$.$name", "missing")

    private fun JsonObject.array(name: String) = try {
        get(name)?.asJsonArray ?: throw BackupException.Invalid("$.$name", "missing")
    } catch (error: IllegalStateException) {
        throw BackupException.Invalid("$.$name", "expected array")
    }

    private fun JsonElement.requiredObject(path: String): JsonObject = try {
        asJsonObject
    } catch (error: IllegalStateException) {
        throw BackupException.Invalid(path, "expected object")
    }

    private companion object {
        val ROOT_KEYS_V1 = setOf("format", "schemaVersion", "exportedAt", "accounts", "appState", "measurements", "settings")
        val ROOT_KEYS_V2 = ROOT_KEYS_V1 + setOf("pets", "petMeasurements")
        val PET_KEYS_V2 = setOf("id", "displayName", "normalizedName", "species", "createdAtEpochMillis", "updatedAtEpochMillis")
        val PET_KEYS_V3 = PET_KEYS_V2 + setOf("sex", "breedId", "birthYear", "birthMonth", "birthDay", "dogAdultWeightCategory")
        val PET_MEASUREMENT_KEYS = setOf("id", "petId", "measuredAtEpochSecond", "firstWeightKg", "secondWeightKg", "petWeightKg")
        val ACCOUNT_KEYS = setOf("id", "displayName", "normalizedName", "profile", "createdAtEpochMillis", "updatedAtEpochMillis")
        val PROFILE_KEYS = setOf("heightCm", "birthDateEpochDay", "sex", "complete")
        val APP_STATE_KEYS = setOf("primaryAccountId", "weightDeltaKg", "ignoreUnknownMeasurements")
        val SETTINGS_KEYS = setOf("scaleAddress", "scaleName", "reliabilityMode", "selectedChartMetricKeys", "homeKgChartSeriesKeys")
        val MEASUREMENT_KEYS_V1_V2 = setOf(
            "id", "fingerprint", "measurementType", "deviceAddress", "measuredAtEpochSecond", "rawPayloadHex", "weightKg", "rawWeight",
            "impedanceOhm", "bmi", "bodyFatPercent", "bodyFatMassKg", "waterPercent", "waterMassKg", "muscleMassKg", "skeletalMuscleMassKg",
            "boneMassKg", "proteinPercent", "proteinMassKg", "visceralFatLevel", "basalMetabolicRateKcal", "metabolicAge", "leanBodyMassKg",
            "algorithmVersion", "huaweiStatus", "healthConnectStatus", "huaweiError", "healthConnectError", "huaweiWeightSynced",
            "healthConnectWeightSynced", "createdAtEpochMillis", "accountId", "externalSyncPolicy", "sourcePendingId", "deduplicationHash",
            "huaweiSyncedCalculatedValues", "healthConnectSyncedCalculatedValues",
        )
        val MEASUREMENT_KEYS_V3 = MEASUREMENT_KEYS_V1_V2 + setOf("ratingHeightCm", "ratingHeightOrigin")
        val MEASUREMENT_KEYS_CURRENT = MEASUREMENT_KEYS_V3 - setOf(
            "huaweiStatus", "huaweiError", "huaweiWeightSynced", "huaweiSyncedCalculatedValues",
        )
        val LEGACY_HUAWEI_MEASUREMENT_KEYS = setOf(
            "huaweiStatus", "huaweiError", "huaweiWeightSynced", "huaweiSyncedCalculatedValues",
        )
        val MEASUREMENT_STRING_KEYS_CURRENT = setOf("id", "fingerprint", "measurementType", "deviceAddress", "rawPayloadHex", "healthConnectStatus", "accountId", "externalSyncPolicy")
        val MEASUREMENT_NULLABLE_STRING_KEYS_CURRENT = setOf("algorithmVersion", "healthConnectError", "sourcePendingId", "deduplicationHash", "healthConnectSyncedCalculatedValues")
        val MEASUREMENT_NUMBER_KEYS = setOf("measuredAtEpochSecond", "weightKg", "rawWeight", "createdAtEpochMillis")
        val MEASUREMENT_NULLABLE_NUMBER_KEYS = setOf("impedanceOhm", "bmi", "bodyFatPercent", "bodyFatMassKg", "waterPercent", "waterMassKg", "muscleMassKg", "skeletalMuscleMassKg", "boneMassKg", "proteinPercent", "proteinMassKg", "visceralFatLevel", "basalMetabolicRateKcal", "metabolicAge", "leanBodyMassKg")
        val MEASUREMENT_BOOLEAN_KEYS_CURRENT = setOf("healthConnectWeightSynced")
        val SUPPORTED_VERSIONS = setOf(
            BACKUP_SCHEMA_VERSION_V1,
            BACKUP_SCHEMA_VERSION_V2,
            BACKUP_SCHEMA_VERSION_V3,
            BACKUP_SCHEMA_VERSION_V4,
            BACKUP_SCHEMA_VERSION_V5,
            BACKUP_SCHEMA_VERSION,
        )
    }
}
