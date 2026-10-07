package com.palixander.weightogether

import android.app.Application
import android.util.Log
import com.palixander.weightogether.backup.BackupExportService
import com.palixander.weightogether.backup.BackupImportApplier
import com.palixander.weightogether.backup.BackupImportCompletionHook
import com.palixander.weightogether.backup.BackupImportService
import com.palixander.weightogether.backup.RoomBackupImportGateway
import com.palixander.weightogether.backup.RoomBackupSnapshotSource
import com.palixander.weightogether.backup.asPortableSettingsWriter
import com.palixander.weightogether.core.BodyCompositionCalculator
import com.palixander.weightogether.core.MiScalePacketParser
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.MeasurementRepository
import com.palixander.weightogether.data.ProfilePhotoReferenceCoordinator
import com.palixander.weightogether.data.ProfileStore
import com.palixander.weightogether.data.RoomAccountRepository
import com.palixander.weightogether.data.RoomMeasurementPersistence
import com.palixander.weightogether.data.RoomPetRepository
import com.palixander.weightogether.data.RoomWeighingReminderRepository
import com.palixander.weightogether.data.SyncAwareAccountRepository
import com.palixander.weightogether.sync.HealthConnectGateway
import com.palixander.weightogether.profile.ProfilePhotoStore
import com.palixander.weightogether.worker.ExternalSyncPauseCoordinator
import com.palixander.weightogether.worker.ExternalSyncOperationSerializer
import com.palixander.weightogether.worker.MeasurementWorkSweepScheduler
import com.palixander.weightogether.worker.PendingMeasurementNotificationHelper
import com.palixander.weightogether.worker.PetMeasurementIngestionGate
import com.palixander.weightogether.worker.ScalePacketProcessor
import com.palixander.weightogether.worker.SyncWorkScheduler
import com.palixander.weightogether.worker.SuccessfulMeasurementNotificationHelper
import com.palixander.weightogether.worker.WorkManagerPendingFinalizationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import com.palixander.weightogether.ble.ScalePacketProcessingGate
import com.palixander.weightogether.reminder.WeighingReminderAlarmGateway
import com.palixander.weightogether.reminder.WeighingReminderCapabilityGateway
import com.palixander.weightogether.reminder.WeighingReminderCoordinator

class ScaleSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationChannelRegistry.registerAll(this)
        container = AppContainer(this)
        MeasurementWorkSweepScheduler.enqueueBestEffort(this)
        container.applicationScope.launch { container.weighingReminders.reconcile() }
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = AppDatabase.build(application)
    val weighingReminderRepository = RoomWeighingReminderRepository(database)
    val weighingReminderCapabilities = WeighingReminderCapabilityGateway(application)
    val weighingReminderAlarmGateway = WeighingReminderAlarmGateway(application)
    val weighingReminders = WeighingReminderCoordinator(
        context = application,
        repository = weighingReminderRepository,
        alarmGateway = weighingReminderAlarmGateway,
        capabilityGateway = weighingReminderCapabilities,
    )
    internal val externalSyncOperations = ExternalSyncOperationSerializer()
    val profileStore = ProfileStore(application, externalSyncOperations)
    val profilePhotos = ProfilePhotoStore(application)
    private val profilePhotoReferences = ProfilePhotoReferenceCoordinator(database, profilePhotos)
    val packetParser = MiScalePacketParser()
    val pets = RoomPetRepository(
        database,
        photoLifecycle = profilePhotos,
        photoReferences = profilePhotoReferences,
    )
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
    val baseAccounts = RoomAccountRepository(
        database,
        calculator = calculator,
        photoLifecycle = profilePhotos,
        photoReferences = profilePhotoReferences,
    )
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
    private val backupArchive = com.palixander.weightogether.backup.BackupArchiveCodec(
        java.io.File(application.cacheDir, "backup-sessions"),
    )
    val backupExport = BackupExportService(
        backupSnapshotSource,
        profileStore::portableSnapshot,
        archiveCodec = backupArchive,
        photoStore = profilePhotos,
        photoReferences = profilePhotoReferences,
    )
    val backupImport = BackupImportService(archiveCodec = backupArchive)
    val backupImportApplier = BackupImportApplier(
        RoomBackupImportGateway(
            database,
            profileStore::versionedPortableSnapshot,
            backupImport,
            photoReferences = profilePhotoReferences,
            photoStore = profilePhotos,
        ),
        profileStore.asPortableSettingsWriter(),
        externalSyncOperations,
        completionHooks = listOf(
            BackupImportCompletionHook {
                MeasurementWorkSweepScheduler.enqueueBestEffort(application)
            },
            BackupImportCompletionHook {
                weighingReminders.reconcile()
            },
        ),
    )

    init {
        runBlocking(Dispatchers.IO) {
            runCatching {
                backupArchive.clearAbandonedSessions()
                profilePhotoReferences.withStableReferences {
                    val snapshot = backupSnapshotSource.readSnapshot()
                    profilePhotos.removeAbandonedBackupPhotos(buildSet {
                        snapshot.accounts.mapNotNullTo(this) { it.photoPath }
                        snapshot.pets.mapNotNullTo(this) { it.photoPath }
                    })
                }
            }.onFailure { Log.e("AppContainer", "Backup photo cleanup will be retried on next startup", it) }
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
