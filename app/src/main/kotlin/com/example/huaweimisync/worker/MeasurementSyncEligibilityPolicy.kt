package com.example.huaweimisync.worker

import com.example.huaweimisync.data.AccountDao
import com.example.huaweimisync.data.AppStateDao
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.domain.ExternalSyncPolicy

/**
 * Cross-integration policy kept outside every gateway. It is deliberately evaluated immediately
 * before each destination, so a primary switch or manual edit takes effect for an in-flight worker.
 */
class MeasurementSyncEligibilityPolicy(
    private val accountDao: AccountDao,
    private val appStateDao: AppStateDao,
) {
    suspend fun isEligible(measurement: MeasurementEntity): Boolean {
        if (measurement.externalSyncPolicy != ExternalSyncPolicy.AUTO.name) return false
        val primaryId = appStateDao.get()?.primaryAccountId ?: return false
        if (measurement.accountId != primaryId) return false
        val account = accountDao.get(primaryId) ?: return false
        return account.isProfileComplete
    }
}
