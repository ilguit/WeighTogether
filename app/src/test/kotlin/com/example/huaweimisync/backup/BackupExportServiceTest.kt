package com.example.huaweimisync.backup

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.PetEntity
import com.example.huaweimisync.data.PetMeasurementEntity
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.data.toPortableSnapshot
import com.example.huaweimisync.domain.ExternalSyncPolicy
import com.example.huaweimisync.domain.PetSpecies
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
    fun `export maps complete database state and deterministic portable settings`() = runBlocking {
        val source = BackupDatabaseSnapshot(
            accounts = listOf(account()),
            appState = AppStateEntity(primaryAccountId = "account", weightDeltaKg = 2.5, ignoreUnknownMeasurements = true),
            measurements = listOf(measurement()),
            pets = listOf(PetEntity("pet", "Cat", "cat", PetSpecies.CAT, 5, 6)),
            petMeasurements = listOf(PetMeasurementEntity("pet-m", "pet", 7, 70.0, 74.0, 4.0)),
        )
        val service = service(source)

        val output = ByteArrayOutputStream()
        val document = service.writeTo(output)
        val decoded = BackupJsonCodec().decode(output.toString(Charsets.UTF_8.name()))

        assertEquals(document, decoded)
        assertEquals("2026-08-25T12:00:00Z", document.exportedAt)
        assertEquals(Sex.MALE, document.accounts.single().profile.sex)
        assertEquals(SyncStatus.SYNCED, document.measurements.single().huaweiStatus)
        assertEquals(ExternalSyncPolicy.AUTO, document.measurements.single().externalSyncPolicy)
        assertEquals(PetSpecies.CAT, document.pets.single().species)
        assertEquals(4.0, document.petMeasurements.single().petWeightKg, 0.0)
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
            huaweiSyncEnabled = false,
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
        huaweiStatus = SyncStatus.SYNCED.name, healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
        accountId = "account", externalSyncPolicy = ExternalSyncPolicy.AUTO.name,
        deduplicationHash = "dedupe",
    )
}
