package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: String): MeasurementEntity?

    @Query(
        """
        SELECT * FROM measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
        ORDER BY measuredAtEpochMillis DESC, createdAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestForDevice(deviceAddress: String): MeasurementEntity?

    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int = 30): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMillis DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query(
        """
        SELECT * FROM measurements
        WHERE measuredAtEpochMillis >= :startInclusive
            AND measuredAtEpochMillis < :endExclusive
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    fun observeRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<MeasurementEntity>>

    @Update
    suspend fun update(measurement: MeasurementEntity): Int

    @Query(
        """
        UPDATE measurements
        SET huaweiStatus = CASE
                WHEN huaweiStatus = 'DISABLED' THEN 'DISABLED'
                ELSE 'LOCAL_ONLY'
            END,
            healthConnectStatus = 'LOCAL_ONLY',
            huaweiError = NULL,
            healthConnectError = NULL
        WHERE id = :id
        """,
    )
    suspend fun markLocalOnly(id: String): Int

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query(
        """
        UPDATE measurements
        SET huaweiStatus = :status, huaweiError = :error
        WHERE id = :id AND huaweiStatus != 'LOCAL_ONLY'
        """,
    )
    suspend fun updateHuaweiStatus(id: String, status: String, error: String?): Int

    @Query(
        """
        UPDATE measurements
        SET healthConnectStatus = :status, healthConnectError = :error
        WHERE id = :id AND healthConnectStatus != 'LOCAL_ONLY'
        """,
    )
    suspend fun updateHealthConnectStatus(id: String, status: String, error: String?): Int

    @Query(
        """
        SELECT id FROM measurements
        WHERE huaweiStatus != 'LOCAL_ONLY'
            AND healthConnectStatus != 'LOCAL_ONLY'
            AND (
                huaweiStatus NOT IN ('SYNCED', 'DISABLED')
                OR healthConnectStatus != 'SYNCED'
            )
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE huaweiStatus != 'LOCAL_ONLY'
            AND healthConnectStatus NOT IN ('SYNCED', 'LOCAL_ONLY')
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHealthConnectSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE huaweiStatus NOT IN ('SYNCED', 'DISABLED', 'LOCAL_ONLY')
            AND healthConnectStatus != 'LOCAL_ONLY'
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHuaweiSync(): List<String>
}
