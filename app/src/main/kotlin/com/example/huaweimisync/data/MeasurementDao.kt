package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: String): MeasurementEntity?

    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int = 30): Flow<List<MeasurementEntity>>

    @Query(
        """
        UPDATE measurements
        SET huaweiStatus = :status, huaweiError = :error
        WHERE id = :id
        """,
    )
    suspend fun updateHuaweiStatus(id: String, status: String, error: String?)

    @Query(
        """
        UPDATE measurements
        SET healthConnectStatus = :status, healthConnectError = :error
        WHERE id = :id
        """,
    )
    suspend fun updateHealthConnectStatus(id: String, status: String, error: String?)

    @Query(
        """
        SELECT id FROM measurements
        WHERE huaweiStatus NOT IN ('SYNCED', 'DISABLED') OR healthConnectStatus != 'SYNCED'
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE healthConnectStatus != 'SYNCED'
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHealthConnectSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE huaweiStatus NOT IN ('SYNCED', 'DISABLED')
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHuaweiSync(): List<String>
}
