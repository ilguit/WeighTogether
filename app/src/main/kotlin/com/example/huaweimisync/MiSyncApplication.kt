package com.example.huaweimisync

import android.app.Application
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.MeasurementRepository
import com.example.huaweimisync.data.ProfileStore
import com.example.huaweimisync.data.RoomAccountRepository
import com.example.huaweimisync.data.RoomMeasurementPersistence
import com.example.huaweimisync.data.SyncAwareAccountRepository
import com.example.huaweimisync.sync.HealthConnectGateway
import com.example.huaweimisync.sync.HuaweiHealthGateway
import com.example.huaweimisync.sync.createHuaweiHealthGateway
import com.example.huaweimisync.worker.ExternalSyncPauseCoordinator
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
import com.example.huaweimisync.worker.MeasurementWorkSweepScheduler
import com.example.huaweimisync.worker.PendingMeasurementNotificationHelper
import com.example.huaweimisync.worker.ScalePacketProcessor
import com.example.huaweimisync.worker.SyncWorkScheduler
import com.example.huaweimisync.worker.WorkManagerPendingFinalizationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MiSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MeasurementWorkSweepScheduler.enqueue(this)
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = AppDatabase.build(application)
    val profileStore = ProfileStore(application)
    val packetParser = MiScalePacketParser()
    val huaweiHealth: HuaweiHealthGateway = createHuaweiHealthGateway(application)
    val healthConnect = HealthConnectGateway(application)
    val syncScheduler = SyncWorkScheduler(
        context = application,
        pausedUntilProvider = { profileStore.externalSyncPausedUntilEpochMillis },
    )
    val finalizationScheduler = WorkManagerPendingFinalizationScheduler(application)
    val pendingMeasurementNotifications = PendingMeasurementNotificationHelper(application)
    private val calculator = BodyCompositionCalculator()
    internal val externalSyncOperations = ExternalSyncOperationSerializer()
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
        { profileStore.settings.value.scaleAddress },
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

    /** One application-wide, generation-tracked selection shared by every writer. */
    internal val accountSelection = AccountSelectionCoordinator()
}
