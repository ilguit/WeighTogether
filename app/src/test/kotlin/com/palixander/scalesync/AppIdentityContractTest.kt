package com.palixander.scalesync

import com.palixander.scalesync.worker.PendingMeasurementNotificationHelper
import com.palixander.scalesync.worker.SuccessfulMeasurementNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityContractTest {
    @Test
    fun personalBuildKeepsSuffixOnNewApplicationId() {
        assertEquals("com.palixander.scalesync.personal", BuildConfig.APPLICATION_ID)
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
