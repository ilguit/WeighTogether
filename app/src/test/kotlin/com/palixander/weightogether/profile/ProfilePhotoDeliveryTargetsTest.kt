package com.palixander.weightogether.profile

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `delivery waits through temporary detach and reaches reattached target exactly once`() = runTest {
        val targets = ProfilePhotoDeliveryTargets()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val deliveries = mutableListOf<String>()

        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            targets.deliver(dispatcher, "photo.jpg")
        }
        testScheduler.runCurrent()
        assertTrue(deliveries.isEmpty())

        targets.attach(Any(), { deliveries += it }, {})
        testScheduler.runCurrent()
        delivery.await()

        assertEquals(listOf("photo.jpg"), deliveries)
    }

    @Test
    fun `permanent close fails a detached delivery promptly`() = runTest {
        val targets = ProfilePhotoDeliveryTargets()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val delivery = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { targets.deliver(dispatcher, "photo.jpg") }.exceptionOrNull()
        }
        testScheduler.runCurrent()

        targets.close()
        testScheduler.runCurrent()

        assertTrue(delivery.await() is ProfilePhotoDeliveryClosedException)
    }
}
