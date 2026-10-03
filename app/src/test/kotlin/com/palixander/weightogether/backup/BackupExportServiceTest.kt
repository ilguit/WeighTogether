package com.palixander.weightogether.backup

import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppSettings
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.data.MeasurementType
import com.palixander.weightogether.data.PortableProfileSettings
import com.palixander.weightogether.data.PetEntity
import com.palixander.weightogether.data.PetMeasurementEntity
import com.palixander.weightogether.data.RatingHeightOrigin
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.data.toPortableSnapshot
import com.palixander.weightogether.data.WeighingReminderOwnerType
import com.palixander.weightogether.data.WeighingReminderScheduleEntity
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.domain.WeighingReminderImportance
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExportServiceTest {
    @Test
    fun `export canonicalizes retired Canadian Sphynx id and preserves unknown id`() = runBlocking {
        val source = BackupDatabaseSnapshot(
            accounts = listOf(account()),
            appState = AppStateEntity(primaryAccountId = "account"),
            measurements = listOf(measurement()),
            pets = listOf(
                PetEntity("alias", "Alias", "alias", PetSpecies.CAT, 1, 2, breedId = "VBO:0100061"),
                PetEntity("future", "Future", "future", PetSpecies.CAT, 1, 2, breedId = "external:cat:future"),
            ),
        )

        val pets = service(source).createDocument().pets.associateBy { it.id }

        assertEquals("VBO:0100230", pets.getValue("alias").breedId)
        assertEquals("external:cat:future", pets.getValue("future").breedId)
    }

    @Test
    fun `export maps complete database state and deterministic portable settings`() = runBlocking {
        val source = BackupDatabaseSnapshot(
            accounts = listOf(account()),
            appState = AppStateEntity(primaryAccountId = "account", weightDeltaKg = 2.5, ignoreUnknownMeasurements = true),
            measurements = listOf(measurement()),
            pets = listOf(PetEntity("pet", "Dog", "dog", PetSpecies.DOG, 5, 6,
                PetSex.FEMALE, "external:dog:breed", 2020, 2, null, DogAdultWeightCategory.II, heightCm = 31.5)),
            petMeasurements = listOf(PetMeasurementEntity("pet-m", "pet", 7, 70.0, 74.0, 4.0)),
            reminderSchedules = listOf(
                WeighingReminderScheduleEntity("reminder", WeighingReminderOwnerType.ACCOUNT, "account", 540, 5,
                    WeighingReminderImportance.ALARM, true, 8, 9),
            ),
        )
        val service = service(source)

        val output = ByteArrayOutputStream()
        val document = service.writeTo(output)
        val decoded = BackupJsonCodec().decode(output.toString(Charsets.UTF_8.name()))

        assertEquals(document, decoded)
        assertEquals(BACKUP_SCHEMA_VERSION, document.schemaVersion)
        assertEquals("2026-08-25T12:00:00Z", document.exportedAt)
        assertEquals(Sex.MALE, document.accounts.single().profile.sex)
        assertEquals(SyncStatus.LOCAL_ONLY, document.measurements.single().healthConnectStatus)
        assertEquals(ExternalSyncPolicy.AUTO, document.measurements.single().externalSyncPolicy)
        assertEquals(179.5, document.measurements.single().ratingHeightCm)
        assertEquals(RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT, document.measurements.single().ratingHeightOrigin)
        assertEquals(31.5, document.pets.single().heightCm!!, 0.0)
        assertEquals(PetSpecies.DOG, document.pets.single().species)
        assertEquals(PetSex.FEMALE, document.pets.single().sex)
        assertEquals("external:dog:breed", document.pets.single().breedId)
        assertEquals(2020, document.pets.single().birthYear)
        assertEquals(2, document.pets.single().birthMonth)
        assertEquals(DogAdultWeightCategory.II, document.pets.single().dogAdultWeightCategory)
        assertEquals(4.0, document.petMeasurements.single().petWeightKg, 0.0)
        assertEquals("reminder", document.reminderSchedules.single().id)
        assertEquals(WeighingReminderImportance.ALARM, document.reminderSchedules.single().importance)
        assertEquals(listOf("bmi", "weight"), document.settings.selectedChartMetricKeys)
        assertEquals(listOf("fat", "weight"), document.settings.homeKgChartSeriesKeys)
    }

    @Test
    fun `portable settings snapshot excludes device local sync controls and copies sets`() {
        val keys = linkedSetOf("weight")
        val settings = AppSettings(
            scaleAddress = "AA:BB",
            scaleName = "Scale",
            reliabilityMode = true,
            selectedChartMetricKeys = keys,
            externalSyncPaused = true,
            healthConnectSyncEnabled = false,
        )

        val snapshot = settings.toPortableSnapshot()
        keys += "bmi"

        assertEquals(setOf("weight"), snapshot.selectedChartMetricKeys)
        assertFalse(snapshot::class.java.declaredFields.any { it.name.contains("paused", ignoreCase = true) })
        assertFalse(snapshot::class.java.declaredFields.any { it.name.contains("enabled", ignoreCase = true) })
    }

    @Test
    fun `export round trip preserves migrated pet species and reverse reading order`() = runBlocking {
        val source = BackupDatabaseSnapshot(
            accounts = listOf(account()),
            appState = AppStateEntity(primaryAccountId = "account"),
            measurements = listOf(measurement()),
            pets = listOf(PetEntity("pet", "Legacy pet", "legacy pet", PetSpecies.UNSPECIFIED, 5, 6)),
            petMeasurements = listOf(PetMeasurementEntity("pet-m", "pet", 7, 74.0, 70.0, 4.0)),
        )

        val output = ByteArrayOutputStream()
        val exported = service(source).writeTo(output)
        val decoded = BackupJsonCodec().decode(output.toString(Charsets.UTF_8.name()))

        assertEquals(exported, decoded)
        assertEquals(PetSpecies.UNSPECIFIED, decoded.pets.single().species)
        assertEquals(4.0, decoded.petMeasurements.single().petWeightKg, 0.0)
    }

    @Test
    fun `stream failures are typed and stream remains caller owned`() = runBlocking {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }
        assertThrows(BackupException.Io::class.java) { runBlocking { service(snapshot()).writeTo(failing) } }

        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        service(snapshot()).writeTo(output)
        assertFalse(closed)
        assertTrue(output.size() > 0)
    }

    private fun service(source: BackupDatabaseSnapshot) = BackupExportService(
        snapshotSource = BackupSnapshotSource { source },
        settingsSnapshot = {
            PortableProfileSettings("AA:BB", "Scale", true, setOf("weight", "bmi"), setOf("weight", "fat"))
        },
        clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC),
    )

    private fun snapshot() = BackupDatabaseSnapshot(listOf(account()), AppStateEntity(primaryAccountId = "account"), listOf(measurement()))

    private fun account() = AccountEntity("account", "Alex", "alex", 180.0, 0, Sex.MALE.name, true, 1, 2)

    private fun measurement() = MeasurementEntity(
        id = "measurement", fingerprint = "fingerprint", measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB", measuredAtEpochSecond = 3, rawPayloadHex = "00ff",
        weightKg = 70.0, impedanceOhm = null, bmi = null, bodyFatPercent = null,
        bodyFatMassKg = null, waterPercent = null, waterMassKg = null, muscleMassKg = null,
        skeletalMuscleMassKg = null, boneMassKg = null, proteinPercent = null,
        proteinMassKg = null, visceralFatLevel = null, basalMetabolicRateKcal = null,
        metabolicAge = null, leanBodyMassKg = null, algorithmVersion = null,
        healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        accountId = "account", externalSyncPolicy = ExternalSyncPolicy.AUTO.name,
        deduplicationHash = "dedupe",
        ratingHeightCm = 179.5,
        ratingHeightOrigin = RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
    )
}
