package com.palixander.weightogether.worker

import androidx.room.withTransaction
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppDatabase
import com.palixander.weightogether.data.MeasurementEntity
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.isComplete

/**
 * Cross-integration policy kept outside every gateway. It is deliberately evaluated immediately
 * before each destination, so a primary switch or manual edit takes effect for an in-flight worker.
 */
class MeasurementSyncEligibilityPolicy internal constructor(
    private val loadCurrentPrimary: suspend () -> AccountEntity?,
) {
    constructor(database: AppDatabase) : this(
        loadCurrentPrimary = {
            database.withTransaction {
                val primaryId = database.appStateDao().get()?.primaryAccountId
                    ?: return@withTransaction null
                database.accountDao().get(primaryId)
            }
        },
    )

    suspend fun isEligible(measurement: MeasurementEntity): Boolean {
        if (measurement.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return false
        val primary = loadCurrentPrimary() ?: return false
        if (measurement.accountId != primary.id) return false
        return runCatching { primary.toDomain().profile.isComplete }.getOrDefault(false)
    }
}
