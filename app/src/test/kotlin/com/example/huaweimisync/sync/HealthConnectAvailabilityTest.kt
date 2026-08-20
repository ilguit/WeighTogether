package com.example.huaweimisync.sync

import androidx.health.connect.client.HealthConnectClient
import com.example.huaweimisync.HealthConnectAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectAvailabilityTest {
    @Test
    fun `sdk availability distinguishes missing provider from unsupported device`() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            healthConnectAvailability(HealthConnectClient.SDK_AVAILABLE),
        )
        assertEquals(
            HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            healthConnectAvailability(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
            ),
        )
        assertEquals(
            HealthConnectAvailability.UNAVAILABLE,
            healthConnectAvailability(HealthConnectClient.SDK_UNAVAILABLE),
        )
    }
}
