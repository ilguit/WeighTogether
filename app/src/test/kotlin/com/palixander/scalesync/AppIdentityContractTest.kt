package com.palixander.scalesync

import com.palixander.scalesync.worker.PendingMeasurementNotificationHelper
import com.palixander.scalesync.worker.SuccessfulMeasurementNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityContractTest {
    @Test
    fun eachFlavorKeepsItsApplicationId() {
        val expected = when (BuildConfig.FLAVOR) {
            "personal" -> "com.palixander.scalesync.personal"
            "huaweiEnterprise" -> "com.palixander.scalesync"
            else -> error("Unexpected flavor: ${BuildConfig.FLAVOR}")
        }
        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }

    @Test
    fun notificationActionsUseNewBasePackage() {
        assertEquals(
            "com.palixander.scalesync.action.OPEN_SAVED_MEASUREMENT",
            SuccessfulMeasurementNotificationHelper.ACTION_OPEN_SAVED_MEASUREMENT,
        )
        assertEquals(
            "com.palixander.scalesync.action.RESOLVE_PENDING_MEASUREMENT",
            PendingMeasurementNotificationHelper.ACTION_RESOLVE_PENDING,
        )
        assertEquals(
            "com.palixander.scalesync.action.DISMISS_PENDING_MEASUREMENT",
            PendingMeasurementNotificationHelper.ACTION_DISMISS_PENDING,
        )
    }
}
