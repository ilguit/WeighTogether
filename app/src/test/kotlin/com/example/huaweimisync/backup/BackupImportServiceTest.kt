package com.example.huaweimisync.backup

import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementType
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
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer

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
            successHooks = listOf(BackupImportSuccessHook { events += "hook:${it.mode}" }),
        ).apply(preview)

        assertEquals(listOf("database", "settings:AA:BB", "checkpoint-cleanup", "hook:REPLACE"), events)
        assertEquals(BackupImportMode.REPLACE, result.mode)
        assertEquals(preview.counts, result.counts)
    }

    @Test
    fun `merge and replace trigger completion sweep after checkpoint cleanup outside import mutex`() =
        runBlocking {
            BackupImportMode.entries.forEach { mode ->
                val events = mutableListOf<String>()
                val operations = ExternalSyncOperationSerializer()
                val preview = service.preview(document(), emptySnapshot(), emptySettings, mode)
                BackupImportApplier(
                    gateway = TrackingGateway(events),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    operations = operations,
                    completionHooks = listOf(
                        BackupImportCompletionHook {
                            operations.runExclusive { events += "sweep:$mode" }
                        },
                    ),
                ).apply(preview)

                assertEquals(
                    listOf("database", "settings", "checkpoint-cleanup", "sweep:$mode"),
                    events,
                )
            }
        }

    @Test
    fun `post commit hook failures do not change completed result or prevent durable scheduling`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.MERGE,
            )
            val result = BackupImportApplier(
                gateway = TrackingGateway(events),
                settingsWriter = PortableSettingsWriter { events += "settings" },
                successHooks = listOf(
                    BackupImportSuccessHook {
                        events += "failed-success-hook"
                        error("analytics unavailable")
                    },
                    BackupImportSuccessHook { events += "later-success-hook" },
                ),
                completionHooks = listOf(
                    BackupImportCompletionHook {
                        events += "failed-scheduler"
                        error("WorkManager unavailable")
                    },
                    BackupImportCompletionHook { events += "scheduled" },
                ),
            ).apply(preview)

            assertEquals(true, result is BackupImportApplyResult.Completed)
            assertEquals(
                listOf(
                    "database",
                    "settings",
                    "checkpoint-cleanup",
                    "failed-success-hook",
                    "later-success-hook",
                    "failed-scheduler",
                    "scheduled",
                ),
                events,
            )
        }

    @Test
    fun `database failure does not change settings or invoke post success hooks`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val failure = IllegalStateException("transaction rolled back")
        var sweepCount = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(events, stageFailure = failure),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
                    completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
                ).apply(preview)
            }
        }

        assertEquals(failure, thrown)
        assertEquals(emptyList<String>(), events)
        assertEquals(0, sweepCount)
    }

    @Test
    fun `stale preview does not trigger completion sweep`() = runBlocking {
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var sweepCount = 0

        assertThrows(BackupPreviewStale::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(
                        mutableListOf(),
                        stageFailure = BackupPreviewStale(preview),
                    ),
                    settingsWriter = PortableSettingsWriter {},
                    completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
                ).apply(preview)
            }
        }

        assertEquals(0, sweepCount)
    }

    @Test
    fun `settings failure returns completed pending recovery without rolling database back`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var firstWrite = true
        var sweepCount = 0
        val result = BackupImportApplier(
            gateway = TrackingGateway(events),
            settingsWriter = PortableSettingsWriter {
                events += "settings:${it.scaleAddress}"
                if (firstWrite) {
                    firstWrite = false
                    throw IllegalArgumentException("preferences")
                }
            },
            successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
            completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
        ).apply(preview)

        assertEquals(true, result is BackupImportApplyResult.CompletedPendingRecovery)
        assertEquals(listOf("database", "settings:AA:BB"), events)
        assertEquals(0, sweepCount)
    }

    @Test
    fun `pending settings commit is retried idempotently on recovery`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var settingsAttempts = 0
        var sweeps = 0
        val applier = BackupImportApplier(
            gateway = TrackingGateway(events),
            settingsWriter = PortableSettingsWriter {
                settingsAttempts++
                events += "settings:$settingsAttempts"
                if (settingsAttempts == 1) error("preferences")
            },
            completionHooks = listOf(BackupImportCompletionHook { sweeps++ }),
        )

        assertEquals(
            true,
            applier.apply(preview) is BackupImportApplyResult.CompletedPendingRecovery,
        )
        applier.recoverPendingImport()
        applier.recoverPendingImport()

        assertEquals(listOf("database", "settings:1", "settings:2", "checkpoint-cleanup"), events)
        assertEquals(1, sweeps)
    }

    @Test
    fun `pending import schedules only after recovery cleanup and scheduler failure is best effort`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.MERGE,
            )
            var settingsAttempts = 0
            var schedulingAttempts = 0
            val applier = BackupImportApplier(
                gateway = TrackingGateway(events),
                settingsWriter = PortableSettingsWriter {
                    settingsAttempts++
                    events += "settings:$settingsAttempts"
                    if (settingsAttempts == 1) error("preferences unavailable")
                },
                completionHooks = listOf(
                    BackupImportCompletionHook {
                        schedulingAttempts++
                        events += "schedule:$schedulingAttempts"
                        error("WorkManager unavailable")
                    },
                ),
            )

            val applyResult = applier.apply(preview)
            assertEquals(true, applyResult is BackupImportApplyResult.CompletedPendingRecovery)
            assertEquals(0, schedulingAttempts)

            applier.recoverPendingImport()
            applier.recoverPendingImport()

            assertEquals(
                listOf(
                    "database",
                    "settings:1",
                    "settings:2",
                    "checkpoint-cleanup",
                    "schedule:1",
                ),
                events,
            )
            assertEquals(1, schedulingAttempts)
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
        ).recoverPendingImport()

        assertEquals(listOf("settings:AA:BB", "checkpoint-cleanup"), events)
    }

    @Test
    fun `successful startup recovery sweeps only after cleanup while rollback recovery does not`() =
        runBlocking {
            listOf(true, false).forEach { successfulImport ->
                val events = mutableListOf<String>()
                val operations = ExternalSyncOperationSerializer()
                BackupImportApplier(
                    gateway = TrackingGateway(
                        events,
                        recoverySettings = emptySettings,
                        recoverySweepNeeded = successfulImport,
                    ),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    operations = operations,
                    completionHooks = listOf(
                        BackupImportCompletionHook {
                            operations.runExclusive { events += "sweep" }
                        },
                    ),
                ).recoverPendingImport()

                assertEquals(
                    if (successfulImport) {
                        listOf("settings", "checkpoint-cleanup", "sweep")
                    } else {
                        listOf("settings", "checkpoint-cleanup")
                    },
                    events,
                )
            }
        }

    @Test
    fun `checkpoint codec round trips nullable portable settings`() {
        val codec = BackupImportCheckpointCodec()
        val settings = PortableProfileSettings(null, "Scale", true, setOf("weight"), emptySet())

        val decodedSettings = codec.decodeSettings(codec.encodeSettings(settings))

        assertEquals(settings, decodedSettings)
    }

    private class TrackingGateway(
        private val events: MutableList<String>,
        private val stageFailure: Throwable? = null,
        private var recoverySettings: PortableProfileSettings? = null,
        private var recoverySweepNeeded: Boolean = true,
    ) : BackupImportGateway {
        override suspend fun stage(preview: BackupImportPreview) {
            stageFailure?.let { throw it }
            events += "database"
            recoverySettings = preview.settings
        }

        override suspend fun pendingRecovery(): BackupImportRecovery? = recoverySettings?.let {
            BackupImportRecovery("operation", it, recoverySweepNeeded)
        }

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
