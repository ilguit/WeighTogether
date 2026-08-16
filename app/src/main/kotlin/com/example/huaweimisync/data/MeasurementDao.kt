package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Transaction
    suspend fun upsertScaleMeasurement(measurement: MeasurementEntity): MeasurementUpsertResult {
        if (insert(measurement) != -1L) return MeasurementUpsertResult.Inserted(measurement)

        val current = getByFingerprint(measurement.fingerprint)
            ?: return MeasurementUpsertResult.Duplicate
        if (current.measurementType != MeasurementType.WEIGHT_ONLY ||
            measurement.measurementType != MeasurementType.FULL
        ) {
            return MeasurementUpsertResult.Duplicate
        }

        val upgraded = measurement.copy(
            id = current.id,
            huaweiStatus = current.huaweiStatus.requeueUnlessTerminal(),
            healthConnectStatus = current.healthConnectStatus.requeueUnlessTerminal(),
            huaweiError = current.huaweiError.preserveForTerminalStatus(current.huaweiStatus),
            healthConnectError = current.healthConnectError.preserveForTerminalStatus(
                current.healthConnectStatus,
            ),
            huaweiWeightSynced = current.huaweiWeightSynced,
            healthConnectWeightSynced = current.healthConnectWeightSynced,
            createdAtEpochMillis = current.createdAtEpochMillis,
            accountId = current.accountId,
            externalSyncPolicy = current.externalSyncPolicy,
            sourcePendingId = current.sourcePendingId,
            deduplicationHash = current.deduplicationHash,
        )
        return if (update(upgraded) == 1) {
            MeasurementUpsertResult.Upgraded(upgraded)
        } else {
            MeasurementUpsertResult.Duplicate
        }
    }

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: String): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): MeasurementEntity?

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

    /**
     * Prevents an editor opened for a partial row from overwriting a concurrent BLE upgrade.
     * Room serializes this type check and update with [upsertScaleMeasurement].
     */
    @Transaction
    suspend fun updateIfSameType(measurement: MeasurementEntity): Int {
        val current = get(measurement.id) ?: return 0
        if (current.measurementType != measurement.measurementType) return 0
        return update(measurement)
    }

    @Query(
        """
        UPDATE measurements
        SET externalSyncPolicy = 'USER_LOCAL',
            huaweiStatus = CASE
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
        SET huaweiStatus = CASE
                WHEN measurementType = :expectedMeasurementType THEN :status
                ELSE huaweiStatus
            END,
            huaweiError = CASE
                WHEN measurementType = :expectedMeasurementType THEN :error
                ELSE huaweiError
            END,
            huaweiWeightSynced = CASE
                WHEN :markWeightSynced THEN 1
                ELSE huaweiWeightSynced
            END
        WHERE id = :id
            AND externalSyncPolicy = 'AUTO'
            AND huaweiStatus != 'LOCAL_ONLY'
        """,
    )
    suspend fun applyHuaweiSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
    ): Int

    @Query(
        """
        UPDATE measurements
        SET healthConnectStatus = CASE
                WHEN measurementType = :expectedMeasurementType THEN :status
                ELSE healthConnectStatus
            END,
            healthConnectError = CASE
                WHEN measurementType = :expectedMeasurementType THEN :error
                ELSE healthConnectError
            END,
            healthConnectWeightSynced = CASE
                WHEN :markWeightSynced THEN 1
                ELSE healthConnectWeightSynced
            END
        WHERE id = :id
            AND externalSyncPolicy = 'AUTO'
            AND healthConnectStatus != 'LOCAL_ONLY'
        """,
    )
    suspend fun applyHealthConnectSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
    ): Int

    @Query(
        """
        SELECT id FROM measurements
        WHERE externalSyncPolicy = 'AUTO'
            AND (
                huaweiStatus NOT IN ('SYNCED', 'DISABLED', 'LOCAL_ONLY')
                OR healthConnectStatus NOT IN ('SYNCED', 'LOCAL_ONLY')
            )
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE externalSyncPolicy = 'AUTO'
            AND healthConnectStatus NOT IN ('SYNCED', 'LOCAL_ONLY')
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHealthConnectSync(): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE externalSyncPolicy = 'AUTO'
            AND huaweiStatus NOT IN ('SYNCED', 'DISABLED', 'LOCAL_ONLY')
        ORDER BY measuredAtEpochMillis ASC
        """,
    )
    suspend fun idsNeedingHuaweiSync(): List<String>
}

sealed interface MeasurementUpsertResult {
    data class Inserted(val value: MeasurementEntity) : MeasurementUpsertResult
    data class Upgraded(val value: MeasurementEntity) : MeasurementUpsertResult
    data object Duplicate : MeasurementUpsertResult
}

private fun String.requeueUnlessTerminal(): String = when (this) {
    SyncStatus.DISABLED.name, SyncStatus.LOCAL_ONLY.name -> this
    else -> SyncStatus.PENDING.name
}

private fun String?.preserveForTerminalStatus(status: String): String? = when (status) {
    SyncStatus.DISABLED.name, SyncStatus.LOCAL_ONLY.name -> this
    else -> null
}
