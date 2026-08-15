package com.example.huaweimisync

import android.app.Application
import androidx.room.Room
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.MeasurementRepository
import com.example.huaweimisync.data.ProfileStore
import com.example.huaweimisync.sync.HealthConnectGateway
import com.example.huaweimisync.sync.HuaweiHealthGateway
import com.example.huaweimisync.sync.createHuaweiHealthGateway
import com.example.huaweimisync.worker.SyncWorkScheduler

class MiSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "huawei-mi-sync.db",
    ).build()
    val profileStore = ProfileStore(application)
    val packetParser = MiScalePacketParser()
    val huaweiHealth: HuaweiHealthGateway = createHuaweiHealthGateway(application)
    val healthConnect = HealthConnectGateway(application)
    val repository = MeasurementRepository(
        database.measurementDao(),
        { profileStore.settings.value.profile },
        { profileStore.settings.value.scaleAddress },
        BodyCompositionCalculator(),
        SyncWorkScheduler(application),
        huaweiHealth.isAvailableInBuild,
    )
}
