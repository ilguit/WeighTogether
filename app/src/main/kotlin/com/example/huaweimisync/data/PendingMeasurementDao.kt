package com.example.huaweimisync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMeasurementDao {
    @Query("SELECT * FROM pending_measurements ORDER BY enqueuedAtEpochMillis ASC, id ASC")
    fun observeAll(): Flow<List<PendingMeasurementEntity>>

    @Query(
        "SELECT * FROM pending_measurements WHERE provisionalAccountId IS NULL " +
            "ORDER BY enqueuedAtEpochMillis ASC, id ASC",
    )
    fun observeUnassigned(): Flow<List<PendingMeasurementEntity>>

    @Query(
        "SELECT * FROM pending_measurements WHERE provisionalAccountId = :accountId " +
            "ORDER BY measuredAtEpochSecond DESC, id DESC",
    )
    fun observeForAccount(accountId: String): Flow<List<PendingMeasurementEntity>>

    @Query("SELECT * FROM pending_measurements ORDER BY enqueuedAtEpochMillis ASC, id ASC")
    suspend fun getAll(): List<PendingMeasurementEntity>

    @Query("SELECT * FROM pending_measurements WHERE id = :id")
    suspend fun get(id: String): PendingMeasurementEntity?

    @Query("SELECT * FROM pending_measurements WHERE deduplicationHash = :deduplicationHash")
    suspend fun getByHash(deduplicationHash: String): PendingMeasurementEntity?

    @Query(
        """
        SELECT * FROM pending_measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
        ORDER BY ABS(measuredAtEpochSecond - :measuredAtEpochSecond),
            measuredAtEpochSecond ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findNearestAggregate(
        deviceAddress: String,
        rawWeight: Int,
        measuredAtEpochSecond: Long,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
    ): PendingMeasurementEntity?

    @Query(
        """
        SELECT * FROM pending_measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
            AND finalizeAfterEpochMillis > :nowEpochMillis
            AND isStable = 1
            AND (
                hasImpedance = 0
                OR impedanceOhm < :minimumImpedanceOhm
                OR impedanceOhm > :maximumImpedanceOhm
            )
        ORDER BY measuredAtEpochSecond DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findNearestActiveIncompletePredecessor(
        deviceAddress: String,
        rawWeight: Int,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
        nowEpochMillis: Long,
        minimumImpedanceOhm: Int,
        maximumImpedanceOhm: Int,
    ): PendingMeasurementEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pending: PendingMeasurementEntity): Long

    @Update
    suspend fun update(pending: PendingMeasurementEntity): Int

    /** Invalidates observers when a due aggregate becomes resolver-visible without changing data. */
    @Query(
        "UPDATE pending_measurements SET finalizeAfterEpochMillis = finalizeAfterEpochMillis " +
            "WHERE id = :id",
    )
    suspend fun notifyAwaitingDecision(id: String): Int

    @Query("DELETE FROM pending_measurements WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query(
        "SELECT * FROM measurement_tombstones " +
            "WHERE deduplicationHash = :deduplicationHash AND expiresAtEpochMillis > :nowEpochMillis",
    )
    suspend fun getActiveTombstone(
        deduplicationHash: String,
        nowEpochMillis: Long,
    ): MeasurementTombstoneEntity?

    @Query(
        """
        SELECT * FROM measurement_tombstones
        WHERE expiresAtEpochMillis > :nowEpochMillis
            AND deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
        ORDER BY ABS(measuredAtEpochSecond - :measuredAtEpochSecond),
            measuredAtEpochSecond ASC, deduplicationHash ASC
        LIMIT 1
        """,
    )
    suspend fun findNearestActiveTombstone(
        deviceAddress: String,
        rawWeight: Int,
        measuredAtEpochSecond: Long,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
        nowEpochMillis: Long,
    ): MeasurementTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: MeasurementTombstoneEntity)

    @Query("DELETE FROM measurement_tombstones WHERE deduplicationHash = :deduplicationHash")
    suspend fun deleteTombstone(deduplicationHash: String): Int

    @Query("DELETE FROM measurement_tombstones WHERE expiresAtEpochMillis <= :nowEpochMillis")
    suspend fun deleteExpiredTombstones(nowEpochMillis: Long): Int

    @Query("SELECT COUNT(*) FROM measurement_tombstones")
    suspend fun tombstoneCount(): Int
}
