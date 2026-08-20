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
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.sync.HealthConnectGateway
import com.example.huaweimisync.sync.HuaweiHealthGateway
import com.example.huaweimisync.sync.createHuaweiHealthGateway
import com.example.huaweimisync.worker.MeasurementWorkSweepScheduler
import com.example.huaweimisync.worker.PendingMeasurementNotificationHelper
import com.example.huaweimisync.worker.SyncWorkScheduler
import com.example.huaweimisync.worker.WorkManagerPendingFinalizationScheduler
import kotlinx.coroutines.flow.MutableStateFlow

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
    val database: AppDatabase = AppDatabase.build(application)
    val profileStore = ProfileStore(application)
    val packetParser = MiScalePacketParser()
    val huaweiHealth: HuaweiHealthGateway = createHuaweiHealthGateway(application)
    val healthConnect = HealthConnectGateway(application)
    val syncScheduler = SyncWorkScheduler(application)
    val finalizationScheduler = WorkManagerPendingFinalizationScheduler(application)
    val pendingMeasurementNotifications = PendingMeasurementNotificationHelper(application)
    private val calculator = BodyCompositionCalculator()
    val measurementPersistence = RoomMeasurementPersistence(
        database = database,
        calculator = calculator,
        huaweiSyncEnabled = huaweiHealth.isAvailableInBuild,
    )
    /** Read-side dependency for ingestion. It must not depend on measurement orchestration. */
    val baseAccounts = RoomAccountRepository(database)
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
    )
    val accounts = SyncAwareAccountRepository(
        delegate = baseAccounts,
        settingsWriter = baseAccounts,
        persistence = measurementPersistence,
        measurements = repository,
        syncScheduler = syncScheduler,
    )

    /** One application-wide selection shared by Measurements and Charts. */
    val selectedAccountId = MutableStateFlow<AccountId?>(null)
}
