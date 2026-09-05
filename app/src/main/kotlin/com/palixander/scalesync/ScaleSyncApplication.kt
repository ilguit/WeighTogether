package com.palixander.scalesync

import android.app.Application
import android.util.Log
import com.palixander.scalesync.backup.BackupExportService
import com.palixander.scalesync.backup.BackupImportApplier
import com.palixander.scalesync.backup.BackupImportCompletionHook
import com.palixander.scalesync.backup.BackupImportService
import com.palixander.scalesync.backup.RoomBackupImportGateway
import com.palixander.scalesync.backup.RoomBackupSnapshotSource
import com.palixander.scalesync.backup.asPortableSettingsWriter
import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.MiScalePacketParser
import com.palixander.scalesync.data.AppDatabase
import com.palixander.scalesync.data.MeasurementRepository
import com.palixander.scalesync.data.ProfileStore
import com.palixander.scalesync.data.RoomAccountRepository
import com.palixander.scalesync.data.RoomMeasurementPersistence
import com.palixander.scalesync.data.RoomPetRepository
import com.palixander.scalesync.data.SyncAwareAccountRepository
import com.palixander.scalesync.sync.HealthConnectGateway
import com.palixander.scalesync.worker.ExternalSyncPauseCoordinator
import com.palixander.scalesync.worker.ExternalSyncOperationSerializer
import com.palixander.scalesync.worker.MeasurementWorkSweepScheduler
import com.palixander.scalesync.worker.PendingMeasurementNotificationHelper
import com.palixander.scalesync.worker.PetMeasurementIngestionGate
import com.palixander.scalesync.worker.ScalePacketProcessor
import com.palixander.scalesync.worker.SyncWorkScheduler
import com.palixander.scalesync.worker.SuccessfulMeasurementNotificationHelper
import com.palixander.scalesync.worker.WorkManagerPendingFinalizationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import com.palixander.scalesync.ble.ScalePacketProcessingGate

class ScaleSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationChannelRegistry.registerAll(this)
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
    val healthConnect = HealthConnectGateway(application)
    val syncScheduler = SyncWorkScheduler(
        context = application,
        isPaused = { profileStore.externalSyncPaused },
    )
    val finalizationScheduler = WorkManagerPendingFinalizationScheduler(application)
    val petMeasurementIngestionGate = PetMeasurementIngestionGate()
    val scalePacketProcessingGate = ScalePacketProcessingGate()
    val pendingMeasurementNotifications = PendingMeasurementNotificationHelper(application)
    val successfulMeasurementNotifications = SuccessfulMeasurementNotificationHelper(application)
    private val calculator = BodyCompositionCalculator()
    val measurementPersistence = RoomMeasurementPersistence(
        database = database,
        calculator = calculator,
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
        multiAccountPersistence = measurementPersistence,
        accountRepository = baseAccounts,
        pendingDecisionNotifier = pendingMeasurementNotifications,
        successfulMeasurementNotifier = successfulMeasurementNotifications,
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
