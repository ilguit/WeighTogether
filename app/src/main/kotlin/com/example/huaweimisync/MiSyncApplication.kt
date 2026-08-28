package com.example.huaweimisync

import android.app.Application
import android.util.Log
import com.example.huaweimisync.backup.BackupExportService
import com.example.huaweimisync.backup.BackupImportApplier
import com.example.huaweimisync.backup.BackupImportCompletionHook
import com.example.huaweimisync.backup.BackupImportService
import com.example.huaweimisync.backup.RoomBackupImportGateway
import com.example.huaweimisync.backup.RoomBackupSnapshotSource
import com.example.huaweimisync.backup.asPortableSettingsWriter
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.MeasurementRepository
import com.example.huaweimisync.data.ProfileStore
import com.example.huaweimisync.data.RoomAccountRepository
import com.example.huaweimisync.data.RoomMeasurementPersistence
import com.example.huaweimisync.data.RoomPetRepository
import com.example.huaweimisync.data.SyncAwareAccountRepository
import com.example.huaweimisync.sync.HealthConnectGateway
import com.example.huaweimisync.sync.HuaweiHealthGateway
import com.example.huaweimisync.sync.createHuaweiHealthGateway
import com.example.huaweimisync.worker.ExternalSyncPauseCoordinator
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
import com.example.huaweimisync.worker.MeasurementWorkSweepScheduler
import com.example.huaweimisync.worker.PendingMeasurementNotificationHelper
import com.example.huaweimisync.worker.PetMeasurementIngestionGate
import com.example.huaweimisync.worker.ScalePacketProcessor
import com.example.huaweimisync.worker.SyncWorkScheduler
import com.example.huaweimisync.worker.WorkManagerPendingFinalizationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import com.example.huaweimisync.ble.ScalePacketProcessingGate

class MiSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MeasurementWorkSweepScheduler.enqueueBestEffort(this)
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = AppDatabase.build(application)
    internal val externalSyncOperations = ExternalSyncOperationSerializer()
    val profileStore = ProfileStore(application, externalSyncOperations)
    val packetParser = MiScalePacketParser()
    val pets = RoomPetRepository(database)
    val huaweiHealth: HuaweiHealthGateway = createHuaweiHealthGateway(application)
    val healthConnect = HealthConnectGateway(application)
    val syncScheduler = SyncWorkScheduler(
        context = application,
        isPaused = { profileStore.externalSyncPaused },
    )
    val finalizationScheduler = WorkManagerPendingFinalizationScheduler(application)
    val petMeasurementIngestionGate = PetMeasurementIngestionGate()
    val scalePacketProcessingGate = ScalePacketProcessingGate()
    val pendingMeasurementNotifications = PendingMeasurementNotificationHelper(application)
    private val calculator = BodyCompositionCalculator()
    val measurementPersistence = RoomMeasurementPersistence(
        database = database,
        calculator = calculator,
        huaweiSyncEnabled = huaweiHealth.isAvailableInBuild,
    )
    /** Read-side dependency for ingestion. It must not depend on measurement orchestration. */
    val baseAccounts = RoomAccountRepository(database, calculator = calculator)
    val repository = MeasurementRepository(
        database.measurementDao(),
        // The legacy provider is never reached in live DI because all writes use the configured
        // durable multi-account coordinator below.
        { null },
        calculator,
        syncScheduler,
        huaweiHealth.isAvailableInBuild,
        multiAccountPersistence = measurementPersistence,
        accountRepository = baseAccounts,
        pendingDecisionNotifier = pendingMeasurementNotifications,
        pendingFinalizationScheduler = finalizationScheduler,
        externalSyncOperations = externalSyncOperations,
    )
    val packetProcessor = ScalePacketProcessor(
        parser = packetParser,
        ingest = repository::ingest,
        finalizationScheduler = finalizationScheduler,
        petMeasurementGate = petMeasurementIngestionGate,
    )
    val externalSyncPause = ExternalSyncPauseCoordinator(
        settings = profileStore,
        currentSyncIds = repository::currentPendingSyncIds,
        scheduler = syncScheduler,
        operations = externalSyncOperations,
    )
    val accounts = SyncAwareAccountRepository(
        delegate = baseAccounts,
        settingsWriter = baseAccounts,
        persistence = measurementPersistence,
        measurements = repository,
        syncScheduler = syncScheduler,
        externalSyncOperations = externalSyncOperations,
    )
    val backupSnapshotSource = RoomBackupSnapshotSource(database)
    val backupExport = BackupExportService(backupSnapshotSource, profileStore::portableSnapshot)
    val backupImport = BackupImportService()
    val backupImportApplier = BackupImportApplier(
        RoomBackupImportGateway(
            database,
            profileStore::versionedPortableSnapshot,
            backupImport,
        ),
        profileStore.asPortableSettingsWriter(),
        externalSyncOperations,
        completionHooks = listOf(
            BackupImportCompletionHook {
                MeasurementWorkSweepScheduler.enqueueBestEffort(application)
            },
        ),
    )

    init {
        runBlocking(Dispatchers.IO) {
            recoverBackupImportAtStartup(
                recovery = backupImportApplier::recoverPendingImport,
                reportFailure = { failure ->
                    Log.e(
                        "AppContainer",
                        "Pending backup import recovery will be retried on next startup",
                        failure,
                    )
                },
            )
        }
    }

    /** One application-wide, generation-tracked selection shared by every writer. */
    internal val accountSelection = AccountSelectionCoordinator()
}

internal suspend fun recoverBackupImportAtStartup(
    recovery: suspend () -> Unit,
    reportFailure: (Exception) -> Unit,
) {
    try {
        recovery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        reportFailure(failure)
    }
}
