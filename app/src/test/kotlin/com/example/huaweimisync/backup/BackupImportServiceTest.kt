package com.example.huaweimisync.backup

import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.MeasurementTombstoneEntity
import com.example.huaweimisync.data.PendingMeasurementEntity
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.domain.ExternalSyncPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BackupImportServiceTest {
    private val service = BackupImportService()
    private val emptySettings = PortableProfileSettings(null, null, false, null, null)

    @Test
    fun `read uses codec validation and enforces byte limit without closing stream`() {
        val json = BackupJsonCodec().encode(document())
        val input = TrackingInputStream(json.toByteArray())
        assertEquals("a", service.read(input).accounts.single().id)
        assertEquals(false, input.closed)
        assertThrows(BackupException.Limits::class.java) {
            BackupImportService(byteLimit = 4).read(ByteArrayInputStream(json.toByteArray()))
        }
        assertThrows(BackupException.Corrupt::class.java) {
            service.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(BackupException.Io::class.java) { service.read(object : InputStream() {
            override fun read(): Int = throw IOException("lost provider")
        }) }
    }

    @Test
    fun `merge is idempotent and keeps stable identities and local configuration`() {
        val document = document()
        val imported = service.preview(document, emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val localSettings = PortableProfileSettings("LOCAL", "Local", false, setOf("weight"), null)
        val repeated = service.preview(document, imported.result, localSettings, BackupImportMode.MERGE)

        assertEquals(1, imported.counts.accountsAdded)
        assertEquals(1, imported.counts.measurementsAdded)
        assertEquals(1, repeated.counts.accountsSkipped)
        assertEquals(1, repeated.counts.measurementsSkipped)
        assertEquals("m", repeated.result.measurements.single().id)
        assertEquals("f", repeated.result.measurements.single().fingerprint)
        assertEquals("d", repeated.result.measurements.single().deduplicationHash)
        assertEquals("a", repeated.result.measurements.single().accountId)
        assertEquals("LOCAL", repeated.settings.scaleAddress)
        assertEquals(emptySet<String>(), repeated.settings.homeKgChartSeriesKeys)
    }

    @Test
    fun `merge blocks incompatible ids names fingerprints and hashes before producing a result`() {
        val base = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE).result
        assertThrows(BackupImportConflicts::class.java) {
            service.preview(document(accountDisplayName = "Changed"), base, emptySettings, BackupImportMode.MERGE)
        }.also { assertEquals(BackupImportConflict.AccountId("a"), it.conflicts.first()) }

        val renamedId = document().copy(accounts = listOf(document().accounts.single().copy(id = "other")),
            appState = document().appState.copy(primaryAccountId = "other"),
            measurements = listOf(document().measurements.single().copy(id = "other-m", accountId = "other")))
        assertThrows(BackupImportConflicts::class.java) {
            service.preview(renamedId, base, emptySettings, BackupImportMode.MERGE)
        }.also { assertEquals(true, it.conflicts.contains(BackupImportConflict.AccountName("account"))) }

        val collision = document().copy(measurements = listOf(document().measurements.single().copy(id = "new", weightKg = 71.0)))
        assertThrows(BackupImportConflicts::class.java) {
            service.preview(collision, base, emptySettings, BackupImportMode.MERGE)
        }.also {
            assertEquals(true, it.conflicts.contains(BackupImportConflict.MeasurementFingerprint("f")))
            assertEquals(true, it.conflicts.contains(BackupImportConflict.MeasurementDeduplicationHash("d")))
        }
    }

    @Test
    fun `replace reports replaced local rows and uses imported links and settings`() {
        val base = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE).result
        val replacement = document().copy(
            accounts = listOf(document().accounts.single().copy(id = "b", normalizedName = "b")),
            appState = document().appState.copy(primaryAccountId = "b"),
            measurements = listOf(document().measurements.single().copy(id = "n", fingerprint = "nf", deduplicationHash = "nd", accountId = "b")),
        )
        val preview = service.preview(replacement, base, emptySettings, BackupImportMode.REPLACE)
        assertEquals(1, preview.counts.accountsReplaced)
        assertEquals(1, preview.counts.measurementsReplaced)
        assertEquals("b", preview.result.appState.primaryAccountId)
        assertEquals("b", preview.result.measurements.single().accountId)
        assertEquals("AA:BB", preview.settings.scaleAddress)
    }

    @Test
    fun `apply persists database before settings and invokes hooks only after success`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.REPLACE)
        val gateway = TrackingGateway(events)
        val result = BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
            settingsSnapshot = { emptySettings },
            successHooks = listOf(BackupImportSuccessHook { events += "hook:${it.mode}" }),
        ).apply(preview)

        assertEquals(listOf("database", "settings:AA:BB", "checkpoint-cleanup", "hook:REPLACE"), events)
        assertEquals(BackupImportMode.REPLACE, result.mode)
        assertEquals(preview.counts, result.counts)
    }

    @Test
    fun `database failure does not change settings or invoke post success hooks`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val failure = IllegalStateException("transaction rolled back")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(events, stageFailure = failure),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    settingsSnapshot = { emptySettings },
                    successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
                ).apply(preview)
            }
        }

        assertEquals(failure, thrown)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `settings failure compensates database and settings before returning`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var firstWrite = true
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(events),
                    settingsWriter = PortableSettingsWriter {
                        events += "settings:${it.scaleAddress}"
                        if (firstWrite) {
                            firstWrite = false
                            throw IllegalArgumentException("preferences")
                        }
                    },
                    settingsSnapshot = { emptySettings },
                    successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
                ).apply(preview)
            }
        }

        assertEquals(
            listOf(
                "database",
                "settings:AA:BB",
                "database-rollback",
                "settings:null",
                "checkpoint-cleanup",
            ),
            events,
        )
    }

    @Test
    fun `startup recovery commits journal settings and cleans checkpoint`() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = TrackingGateway(events, recoverySettings = document().settings.let {
            PortableProfileSettings(
                it.scaleAddress,
                it.scaleName,
                it.reliabilityMode,
                it.selectedChartMetricKeys?.toSet(),
                it.homeKgChartSeriesKeys?.toSet(),
            )
        })

        BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
            settingsSnapshot = { emptySettings },
        ).recoverPendingImport()

        assertEquals(listOf("settings:AA:BB", "checkpoint-cleanup"), events)
    }

    @Test
    fun `checkpoint codec round trips rollback rows and nullable portable settings`() {
        val codec = BackupImportCheckpointCodec()
        val rollback = BackupImportRollbackSnapshot(
            accounts = emptyList(),
            appState = AppStateEntity(),
            measurements = emptyList(),
            pendingMeasurements = listOf(
                PendingMeasurementEntity(
                    id = "pending",
                    deviceAddress = "AA:BB",
                    measuredAtEpochSecond = 1,
                    weightKg = 70.0,
                    impedanceOhm = 0,
                    isStable = true,
                    hasImpedance = false,
                    rawPayload = byteArrayOf(0, 1, -1),
                    deduplicationHash = "hash",
                    enqueuedAtEpochMillis = 2,
                ),
            ),
            tombstones = listOf(MeasurementTombstoneEntity("hash", 3)),
        )
        val settings = PortableProfileSettings(null, "Scale", true, setOf("weight"), emptySet())

        val decodedRollback = codec.decodeRollback(codec.encodeRollback(rollback))
        val decodedSettings = codec.decodeSettings(codec.encodeSettings(settings))

        assertEquals(rollback.pendingMeasurements.single().id, decodedRollback.pendingMeasurements.single().id)
        assertEquals(
            rollback.pendingMeasurements.single().rawPayload.toList(),
            decodedRollback.pendingMeasurements.single().rawPayload.toList(),
        )
        assertEquals(rollback.tombstones, decodedRollback.tombstones)
        assertEquals(settings, decodedSettings)
    }

    private class TrackingGateway(
        private val events: MutableList<String>,
        private val stageFailure: Throwable? = null,
        private var recoverySettings: PortableProfileSettings? = null,
    ) : BackupImportGateway {
        private var previousSettings: PortableProfileSettings? = null

        override suspend fun stage(
            preview: BackupImportPreview,
            previousSettings: PortableProfileSettings,
        ) {
            stageFailure?.let { throw it }
            events += "database"
            this.previousSettings = previousSettings
            recoverySettings = preview.settings
        }

        override suspend fun beginRollback() {
            events += "database-rollback"
            recoverySettings = previousSettings
        }

        override suspend fun pendingRecoverySettings(): PortableProfileSettings? = recoverySettings

        override suspend fun complete() {
            events += "checkpoint-cleanup"
            recoverySettings = null
        }
    }

    private fun emptySnapshot() = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList())

    private fun document(accountDisplayName: String = "Account") = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = listOf(BackupAccountV1("a", accountDisplayName, "account", BackupAccountProfileV1(null, null, null, false), 1, 2)),
        appState = BackupAppStateV1("a", 3.0, false),
        measurements = listOf(BackupMeasurementV1(
            "m", "f", MeasurementType.WEIGHT_ONLY, "AA:BB", 3, "00ff", 70.0, 14000,
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, SyncStatus.LOCAL_ONLY, SyncStatus.LOCAL_ONLY, null, null, false,
            false, 4, "a", ExternalSyncPolicy.USER_LOCAL, null, "d", null, null,
        )),
        settings = BackupSettingsV1("AA:BB", "Scale", true, emptyList(), emptyList()),
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}
