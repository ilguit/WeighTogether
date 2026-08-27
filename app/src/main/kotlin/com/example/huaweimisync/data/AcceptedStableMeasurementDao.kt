package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AcceptedStableMeasurementDao {
    @Query("SELECT * FROM accepted_stable_measurements WHERE id = :id")
    suspend fun get(id: String): AcceptedStableMeasurementEntity?

    @Upsert
    suspend fun upsert(entity: AcceptedStableMeasurementEntity)

    suspend fun getLatest(): AcceptedStableMeasurementEntity? =
        get(AcceptedStableMeasurementEntity.LATEST_ID)

    suspend fun getForDevice(deviceAddress: String): AcceptedStableMeasurementEntity? =
        get(AcceptedStableMeasurementEntity.deviceId(deviceAddress))

    suspend fun replaceLatest(entity: AcceptedStableMeasurementEntity) {
        require(entity.id == AcceptedStableMeasurementEntity.LATEST_ID) {
            "Latest accepted stable measurement must use the reserved latest id"
        }
        upsert(entity)
    }

    suspend fun replaceLatestAndDevice(entity: AcceptedStableMeasurementEntity) {
        replaceLatest(entity)
        upsert(entity.copy(id = AcceptedStableMeasurementEntity.deviceId(entity.deviceAddress)))
    }
}
