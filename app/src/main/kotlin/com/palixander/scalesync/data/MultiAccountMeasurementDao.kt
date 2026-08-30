package com.palixander.scalesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MultiAccountMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: String): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE sourcePendingId = :pendingId")
    suspend fun getByPendingId(pendingId: String): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE deduplicationHash = :deduplicationHash")
    suspend fun getByDeduplicationHash(deduplicationHash: String): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): MeasurementEntity?

    @Query(
        """
        SELECT * FROM measurements
        WHERE accountId = :accountId
            AND externalSyncPolicy != 'USER_LOCAL'
        ORDER BY measuredAtEpochSecond ASC, id ASC
        """,
    )
    suspend fun getProfileRecalculationCandidates(accountId: String): List<MeasurementEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM measurements
            WHERE accountId = :accountId
                AND externalSyncPolicy != 'USER_LOCAL'
        )
        """,
    )
    suspend fun hasProfileRecalculationCandidates(accountId: String): Boolean

    @Query(
        """
        SELECT * FROM measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
        ORDER BY ABS(measuredAtEpochSecond - :measuredAtEpochSecond),
            measuredAtEpochSecond ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findNearestDeduplicationCandidate(
        deviceAddress: String,
        rawWeight: Int,
        measuredAtEpochSecond: Long,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
    ): MeasurementEntity?

    @Query(
        """
        SELECT * FROM measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measurementType = 'FULL'
            AND rawPayloadHex = :rawPayloadHex
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
        ORDER BY measuredAtEpochSecond DESC, createdAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findExactFinalizedFullReplay(
        deviceAddress: String,
        rawWeight: Int,
        rawPayloadHex: String,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
    ): MeasurementEntity?

    @Query(
        """
        SELECT * FROM measurements
        WHERE deviceAddress = :deviceAddress COLLATE NOCASE
            AND rawWeight = :rawWeight
            AND measurementType = 'WEIGHT_ONLY'
            AND measuredAtEpochSecond BETWEEN :minimumEpochSecond AND :maximumEpochSecond
        ORDER BY measuredAtEpochSecond DESC, createdAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findNearestFinalizedEnrichmentPredecessor(
        deviceAddress: String,
        rawWeight: Int,
        minimumEpochSecond: Long,
        maximumEpochSecond: Long,
    ): MeasurementEntity?

    @Update
    suspend fun update(measurement: MeasurementEntity): Int

    @Query(
        "SELECT * FROM measurements " +
            "WHERE accountId = :accountId " +
            "ORDER BY measuredAtEpochSecond DESC, id DESC",
    )
    fun observeAll(accountId: String): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements " +
            "WHERE accountId = :accountId " +
            "ORDER BY measuredAtEpochSecond DESC, id DESC LIMIT :limit",
    )
    fun observeLatest(accountId: String, limit: Int): Flow<List<MeasurementEntity>>

    @Query(
        """
        SELECT * FROM measurements
        WHERE accountId = :accountId
            AND measuredAtEpochSecond >= :startInclusive
            AND measuredAtEpochSecond < :endExclusive
        ORDER BY measuredAtEpochSecond ASC, id ASC
        """,
    )
    fun observeRange(
        accountId: String,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<MeasurementEntity>>

    @Query(
        """
        SELECT weightKg FROM measurements
        WHERE accountId = :accountId AND measuredAtEpochSecond < :measuredAtExclusive
        ORDER BY measuredAtEpochSecond DESC, id DESC
        LIMIT 3
        """,
    )
    suspend fun latestWeightsBefore(
        accountId: String,
        measuredAtExclusive: Long,
    ): List<Double>

    @Query(
        """
        SELECT * FROM measurements
        WHERE accountId = :accountId AND measuredAtEpochSecond < :measuredAtExclusive
        ORDER BY measuredAtEpochSecond DESC, id DESC
        LIMIT 3
        """,
    )
    suspend fun latestHistoryBefore(
        accountId: String,
        measuredAtExclusive: Long,
    ): List<MeasurementEntity>

    @Query(
        """
        UPDATE measurements
        SET externalSyncPolicy = 'AUTO',
            huaweiStatus = CASE
                WHEN huaweiStatus IN ('SYNCED', 'DISABLED') THEN huaweiStatus
                ELSE 'PENDING'
            END,
            healthConnectStatus = CASE
                WHEN healthConnectStatus = 'SYNCED' THEN healthConnectStatus
                ELSE 'PENDING'
            END,
            huaweiError = CASE
                WHEN huaweiStatus IN ('SYNCED', 'DISABLED') THEN huaweiError
                ELSE NULL
            END,
            healthConnectError = CASE
                WHEN healthConnectStatus = 'SYNCED' THEN healthConnectError
                ELSE NULL
            END
        WHERE accountId = :accountId
            AND externalSyncPolicy = 'ACCOUNT_LOCAL'
        """,
    )
    suspend fun promoteEligibleHistory(accountId: String): Int

    @Query(
        """
        UPDATE measurements
        SET externalSyncPolicy = 'ACCOUNT_LOCAL',
            huaweiStatus = CASE
                WHEN huaweiStatus IN ('SYNCED', 'DISABLED') THEN huaweiStatus
                ELSE 'LOCAL_ONLY'
            END,
            healthConnectStatus = CASE
                WHEN healthConnectStatus = 'SYNCED' THEN healthConnectStatus
                ELSE 'LOCAL_ONLY'
            END,
            huaweiError = CASE
                WHEN huaweiStatus IN ('SYNCED', 'DISABLED') THEN huaweiError
                ELSE NULL
            END,
            healthConnectError = CASE
                WHEN healthConnectStatus = 'SYNCED' THEN healthConnectError
                ELSE NULL
            END
        WHERE accountId = :accountId
            AND externalSyncPolicy = 'AUTO'
            AND (
                huaweiStatus NOT IN ('SYNCED', 'DISABLED')
                OR healthConnectStatus != 'SYNCED'
            )
        """,
    )
    suspend fun demoteUnfinishedHistory(accountId: String): Int

    @Query(
        """
        SELECT id FROM measurements
        WHERE accountId = :primaryAccountId
            AND externalSyncPolicy = 'AUTO'
            AND (
                huaweiStatus NOT IN ('SYNCED', 'DISABLED')
                OR healthConnectStatus != 'SYNCED'
            )
        ORDER BY measuredAtEpochSecond ASC, id ASC
        """,
    )
    suspend fun eligiblePendingSyncIds(primaryAccountId: String): List<String>

    @Query(
        """
        SELECT id FROM measurements
        WHERE accountId = :accountId
            AND externalSyncPolicy = 'AUTO'
            AND (
                huaweiStatus NOT IN ('SYNCED', 'DISABLED', 'LOCAL_ONLY')
                OR healthConnectStatus NOT IN ('SYNCED', 'LOCAL_ONLY')
            )
        ORDER BY measuredAtEpochSecond ASC, id ASC
        """,
    )
    suspend fun activeSyncWorkIds(accountId: String): List<String>
}
