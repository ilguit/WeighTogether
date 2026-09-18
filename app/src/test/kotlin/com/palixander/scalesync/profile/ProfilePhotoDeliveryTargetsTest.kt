package com.palixander.scalesync.profile

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePhotoDeliveryTargetsTest {
    @Test
    fun `delivery revalidates target after dispatcher boundary`() = runTest {
        val targets = ProfilePhotoDeliveryTargets()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldToken = Any()
        val newToken = Any()
        val deliveries = mutableListOf<String>()
        targets.attach(oldToken, { deliveries += "old:$it" }, {})

        val delivery = launch(start = CoroutineStart.UNDISPATCHED) {
            targets.deliver(dispatcher, "photo.jpg")
        }
        targets.detach(oldToken)
        targets.attach(newToken, { deliveries += "new:$it" }, {})
        testScheduler.runCurrent()

        delivery.join()
        assertEquals(listOf("new:photo.jpg"), deliveries)
    }
}
