package com.palixander.weightogether

import com.palixander.weightogether.worker.PendingMeasurementNotificationHelper
import com.palixander.weightogether.worker.SuccessfulMeasurementNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityContractTest {
    @Test
    fun supportedBuildUsesCanonicalApplicationId() {
        assertEquals("com.palixander.weightogether", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun notificationActionsUseNewBasePackage() {
        assertEquals(
            "com.palixander.weightogether.action.OPEN_SAVED_MEASUREMENT",
            SuccessfulMeasurementNotificationHelper.ACTION_OPEN_SAVED_MEASUREMENT,
        )
        assertEquals(
            "com.palixander.weightogether.action.RESOLVE_PENDING_MEASUREMENT",
            PendingMeasurementNotificationHelper.ACTION_RESOLVE_PENDING,
        )
        assertEquals(
            "com.palixander.weightogether.action.DISMISS_PENDING_MEASUREMENT",
            PendingMeasurementNotificationHelper.ACTION_DISMISS_PENDING,
        )
    }
}
