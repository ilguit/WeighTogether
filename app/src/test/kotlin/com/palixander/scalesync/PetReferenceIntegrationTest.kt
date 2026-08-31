package com.palixander.scalesync

import com.palixander.scalesync.backup.BACKUP_SCHEMA_VERSION
import com.palixander.scalesync.backup.BackupDatabaseSnapshot
import com.palixander.scalesync.backup.BackupExportService
import com.palixander.scalesync.backup.BackupImportMode
import com.palixander.scalesync.backup.BackupImportService
import com.palixander.scalesync.backup.BackupSnapshotSource
import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.reference.ReferenceBasis
import com.palixander.scalesync.data.AccountEntity
import com.palixander.scalesync.data.AppStateEntity
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementType
import com.palixander.scalesync.data.PortableProfileSettings
import com.palixander.scalesync.data.PetEntity
import com.palixander.scalesync.data.toPetEntity
import com.palixander.scalesync.data.withUpdate
import com.palixander.scalesync.domain.BreedId
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.PartialBirthDate
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.domain.reference.WeightReferenceUnavailableReason
import com.palixander.scalesync.ui.profiles.PetHistoryReferencePresenter
import com.palixander.scalesync.ui.profiles.PetHistoryWeightReference
import com.palixander.scalesync.ui.profiles.PetProfileSummaryItem
import com.palixander.scalesync.ui.profiles.petProfileSummary
import com.palixander.scalesync.ui.profiles.petWeightChartRange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetReferenceIntegrationTest {
    private val catalog = PetBreedCatalog()
    private val referenceDate = LocalDate.of(2025, 1, 15)
    private val timestamp = Instant.parse("2026-08-31T10:00:00Z")

    @Test
    fun `catalog profile survives entity and backup round trip into reference chart`() = runBlocking {
        val stableBreedId = BreedId("scalesync:dog:mixed-breed")
        val breed = catalog.resolve(stableBreedId, PetSpecies.DOG)
        assertTrue(breed is PetBreedSelection.Available)
        assertEquals(stableBreedId, breed.id)

        val validated = validatePetProfileDraft(
            PetProfileDraft(
                mode = PetProfileEditorMode.Create,
                displayName = "Бим",
                species = PetSpecies.DOG,
                sex = PetSex.MALE,
                breed = breed,
                birthDate = PetBirthDateInput.Day(
                    referenceDate.minusDays(100).year.toString(),
                    referenceDate.minusDays(100).monthValue.toString(),
                    referenceDate.minusDays(100).dayOfMonth.toString(),
                ),
                dogAdultWeightCategory = DogAdultWeightCategory.II,
            ),
            today = referenceDate,
        )
        assertTrue(validated.isValid)
        val birthDate = referenceDate.minusDays(100)
        val knownEntity = requireNotNull(validated.newPet).toPetEntity("known-pet", timestamp)
        assertEquals(stableBreedId, knownEntity.toDomain().breedId)
        assertEquals(birthDate.year, knownEntity.birthYear)
        assertEquals(birthDate.monthValue, knownEntity.birthMonth)
        assertEquals(birthDate.dayOfMonth, knownEntity.birthDay)
        assertEquals(PartialBirthDate.Day(birthDate), knownEntity.toDomain().birthDate)

        val unknownBreedId = BreedId("external:dog:retired-breed")
        val unknownExisting = knownEntity.copy(
            id = "unknown-pet",
            displayName = "Рекс",
            normalizedName = "рекс",
            breedId = unknownBreedId.value,
            dogAdultWeightCategory = null,
        )
        val unknownOriginal = unknownExisting.toDomain()
        val unknownDraft = PetProfileDraft.edit(unknownOriginal, catalog)
        assertTrue(unknownDraft.breed is PetBreedSelection.Unavailable)
        val unknownUpdate = requireNotNull(
            validatePetProfileDraft(unknownDraft, referenceDate, listOf(unknownOriginal)).petUpdate,
        )
        val unknownEntity = unknownExisting.withUpdate(unknownUpdate, timestamp)

        val humanAccount = humanAccount()
        val humanMeasurement = humanMeasurement()
        val source = BackupDatabaseSnapshot(
            accounts = listOf(humanAccount),
            appState = AppStateEntity(primaryAccountId = humanAccount.id),
            measurements = listOf(humanMeasurement),
            pets = listOf(knownEntity, unknownEntity),
        )
        val output = ByteArrayOutputStream()
        val exported = BackupExportService(
            snapshotSource = BackupSnapshotSource { source },
            settingsSnapshot = { emptySettings() },
            clock = Clock.fixed(timestamp, ZoneOffset.UTC),
        ).writeTo(output)
        assertEquals(BACKUP_SCHEMA_VERSION, exported.schemaVersion)
        val backedUpKnown = exported.pets.single { it.id == knownEntity.id }
        assertEquals(birthDate.year, backedUpKnown.birthYear)
        assertEquals(birthDate.monthValue, backedUpKnown.birthMonth)
        assertEquals(birthDate.dayOfMonth, backedUpKnown.birthDay)

        val importedDocument = BackupImportService().read(
            ByteArrayInputStream(output.toByteArray()),
        )
        val imported = BackupImportService().preview(
            document = importedDocument,
            current = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList()),
            currentSettings = emptySettings(),
            mode = BackupImportMode.REPLACE,
        ).result

        assertEquals(humanAccount, imported.accounts.single())
        assertEquals(humanMeasurement, imported.measurements.single())
        val restoredKnown = imported.pets.single { it.id == knownEntity.id }
        val restoredUnknown = imported.pets.single { it.id == unknownEntity.id }
        assertEquals(knownEntity, restoredKnown)
        assertEquals(unknownEntity, restoredUnknown)
        assertEquals(birthDate.year, restoredKnown.birthYear)
        assertEquals(birthDate.monthValue, restoredKnown.birthMonth)
        assertEquals(birthDate.dayOfMonth, restoredKnown.birthDay)

        val knownPet = restoredKnown.toDomain()
        assertEquals(PartialBirthDate.Day(birthDate), knownPet.birthDate)
        val reference = PetHistoryReferencePresenter().present(
            knownPet,
            ChartDateRange(referenceDate, referenceDate.plusDays(2)),
        ) as PetHistoryWeightReference.Available
        assertEquals(ReferenceBasis.WEIGHT_CATEGORY, reference.basis)
        assertTrue(reference.sourceLabel.contains(reference.citation))
        assertTrue(reference.citation.isNotBlank())
        assertTrue(reference.license.isNotBlank())
        assertTrue(reference.constraints.isNotEmpty())
        val points = reference.segments.flatten()
        assertEquals(3, points.size)
        points.forEach { point ->
            assertTrue(point.lowerKg.isFinite())
            assertTrue(point.lowerKg <= point.medianLowerKg)
            assertTrue(point.medianLowerKg <= point.medianUpperKg)
            assertTrue(point.medianUpperKg <= point.upperKg)
        }
        val chartRange = requireNotNull(petWeightChartRange(emptyList(), reference))
        assertTrue(chartRange.min <= points.minOf { it.lowerKg })
        assertTrue(chartRange.max >= points.maxOf { it.upperKg })

        val unknownPet = restoredUnknown.toDomain()
        assertEquals(unknownBreedId, unknownPet.breedId)
        assertEquals(
            PetProfileSummaryItem("Порода", "Недоступна: ${unknownBreedId.value}"),
            petProfileSummary(unknownPet, catalog).items.first { it.label == "Порода" },
        )
        val unavailable = PetHistoryReferencePresenter().present(
            unknownPet,
            ChartDateRange(referenceDate, referenceDate),
        ) as PetHistoryWeightReference.Unavailable
        assertEquals(
            WeightReferenceUnavailableReason.UnknownBreed(unknownBreedId.value),
            unavailable.reason,
        )
    }

    @Test
    fun `year and month birth date precision survives entity and backup round trip`() = runBlocking {
        val yearBirthDate = PartialBirthDate.Year(Year.of(2020))
        val monthBirthDate = PartialBirthDate.Month(YearMonth.of(2021, 4))
        val yearEntity = NewPet(
            displayName = "Год",
            species = PetSpecies.CAT,
            birthDate = yearBirthDate,
        ).toPetEntity("year-pet", timestamp)
        val monthEntity = NewPet(
            displayName = "Месяц",
            species = PetSpecies.DOG,
            birthDate = monthBirthDate,
        ).toPetEntity("month-pet", timestamp)

        assertEquals(2020, yearEntity.birthYear)
        assertNull(yearEntity.birthMonth)
        assertNull(yearEntity.birthDay)
        assertEquals(yearBirthDate, yearEntity.toDomain().birthDate)
        assertEquals(2021, monthEntity.birthYear)
        assertEquals(4, monthEntity.birthMonth)
        assertNull(monthEntity.birthDay)
        assertEquals(monthBirthDate, monthEntity.toDomain().birthDate)

        val output = ByteArrayOutputStream()
        val exported = BackupExportService(
            snapshotSource = BackupSnapshotSource {
                BackupDatabaseSnapshot(
                    accounts = emptyList(),
                    appState = AppStateEntity(),
                    measurements = emptyList(),
                    pets = listOf(yearEntity, monthEntity),
                )
            },
            settingsSnapshot = { emptySettings() },
            clock = Clock.fixed(timestamp, ZoneOffset.UTC),
        ).writeTo(output)
        val backedUpYear = exported.pets.single { it.id == yearEntity.id }
        val backedUpMonth = exported.pets.single { it.id == monthEntity.id }

        assertEquals(2020, backedUpYear.birthYear)
        assertNull(backedUpYear.birthMonth)
        assertNull(backedUpYear.birthDay)
        assertEquals(2021, backedUpMonth.birthYear)
        assertEquals(4, backedUpMonth.birthMonth)
        assertNull(backedUpMonth.birthDay)

        val restored = BackupImportService().preview(
            document = BackupImportService().read(ByteArrayInputStream(output.toByteArray())),
            current = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList()),
            currentSettings = emptySettings(),
            mode = BackupImportMode.REPLACE,
        ).result.pets.associateBy(PetEntity::id)
        val restoredYear = requireNotNull(restored[yearEntity.id])
        val restoredMonth = requireNotNull(restored[monthEntity.id])

        assertEquals(2020, restoredYear.birthYear)
        assertNull(restoredYear.birthMonth)
        assertNull(restoredYear.birthDay)
        assertEquals(yearBirthDate, restoredYear.toDomain().birthDate)
        assertEquals(2021, restoredMonth.birthYear)
        assertEquals(4, restoredMonth.birthMonth)
        assertNull(restoredMonth.birthDay)
        assertEquals(monthBirthDate, restoredMonth.toDomain().birthDate)
    }

    @Test
    fun `missing required pet data cannot produce reference chart bounds`() {
        val incomplete = NewPet(
            displayName = "Бим",
            species = PetSpecies.DOG,
            sex = null,
            birthDate = PartialBirthDate.Day(referenceDate.minusDays(100)),
            dogAdultWeightCategory = DogAdultWeightCategory.II,
        ).toPetEntity("incomplete-pet", timestamp).toDomain()

        val reference = PetHistoryReferencePresenter().present(
            incomplete,
            ChartDateRange(referenceDate, referenceDate),
        ) as PetHistoryWeightReference.Unavailable

        assertEquals(WeightReferenceUnavailableReason.MissingSex, reference.reason)
        assertFalse(reference.explanation.isBlank())
        assertNull(petWeightChartRange(emptyList(), reference))
    }

    private fun humanAccount() = AccountEntity(
        id = "human-account",
        displayName = "Анна",
        normalizedName = "анна",
        heightCm = 168.0,
        birthDateEpochDay = LocalDate.of(1990, 4, 12).toEpochDay(),
        sex = Sex.FEMALE.name,
        isProfileComplete = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun humanMeasurement() = MeasurementEntity(
        id = "human-measurement",
        fingerprint = "human-fingerprint",
        measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAtEpochSecond = 3,
        rawPayloadHex = "00ff",
        weightKg = 62.5,
        impedanceOhm = null,
        bmi = null,
        bodyFatPercent = null,
        bodyFatMassKg = null,
        waterPercent = null,
        waterMassKg = null,
        muscleMassKg = null,
        skeletalMuscleMassKg = null,
        boneMassKg = null,
        proteinPercent = null,
        proteinMassKg = null,
        visceralFatLevel = null,
        basalMetabolicRateKcal = null,
        metabolicAge = null,
        leanBodyMassKg = null,
        algorithmVersion = null,
        createdAtEpochMillis = 4,
        accountId = "human-account",
    )

    private fun emptySettings() = PortableProfileSettings(
        scaleAddress = null,
        scaleName = null,
        reliabilityMode = false,
        selectedChartMetricKeys = null,
        homeKgChartSeriesKeys = null,
    )
}
