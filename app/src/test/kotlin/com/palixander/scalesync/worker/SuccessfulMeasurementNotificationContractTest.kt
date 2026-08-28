package com.palixander.scalesync.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuccessfulMeasurementNotificationContractTest {
    @Test
    fun repeatedMeasurementUsesSameNotificationAndRequestIdentity() {
        val first = SuccessfulMeasurementNotificationContract.identityFor("measurement-42")
        val repeated = SuccessfulMeasurementNotificationContract.identityFor("measurement-42")

        assertEquals(first, repeated)
    }

    @Test
    fun differentMeasurementsDoNotReplaceEachOthersNotificationOrPendingIntent() {
        val first = SuccessfulMeasurementNotificationContract.identityFor("measurement-42")
        val second = SuccessfulMeasurementNotificationContract.identityFor("measurement-43")

        assertNotEquals(first.notificationTag, second.notificationTag)
        assertNotEquals(first.notificationId, second.notificationId)
        assertNotEquals(first.requestCode, second.requestCode)
        assertNotEquals(first.intentData, second.intentData)
    }

    @Test
    fun identityEscapesMeasurementIdInIntentData() {
        val identity = SuccessfulMeasurementNotificationContract.identityFor("row/42 with space")

        assertEquals(
            "scalesync://measurement/saved/row%2F42%20with%20space",
            identity.intentData,
        )
    }

    @Test
    fun postingRequiresPermissionGlobalAndChannelCapability() {
        assertTrue(SuccessfulMeasurementNotificationContract.shouldPost(true, true, true))
        assertFalse(SuccessfulMeasurementNotificationContract.shouldPost(false, true, true))
        assertFalse(SuccessfulMeasurementNotificationContract.shouldPost(true, false, true))
        assertFalse(SuccessfulMeasurementNotificationContract.shouldPost(true, true, false))
    }

    @Test
    fun contentNamesSuccessfulSaveAndTargetAccount() {
        val text = SuccessfulMeasurementNotificationContract.contentText("Александр")

        assertTrue(text.contains("успешно сохранено"))
        assertTrue(text.contains("Александр"))
    }
}
