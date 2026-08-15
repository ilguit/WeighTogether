package com.example.huaweimisync

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenContractTest {
    @Test
    fun `profile summary contains real height date and sex`() {
        assertEquals(
            "181,5 см · 29.02.1988 · женский",
            formatProfileSummary(UserProfile(181.5, LocalDate.of(1988, 2, 29), Sex.FEMALE)),
        )
        assertEquals("Профиль не настроен", formatProfileSummary(null))
    }

    @Test
    fun `additional settings expansion is a reversible state transition`() {
        assertEquals(AdditionalExpansion.Expanded, AdditionalExpansion.Collapsed.toggled())
        assertEquals(AdditionalExpansion.Collapsed, AdditionalExpansion.Expanded.toggled())
    }

    @Test
    fun `partial health connect access stays disconnected and requests permissions`() {
        val presentation = healthConnectPresentation(
            HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = setOf("weight", "fat", "water"),
                grantedPermissions = setOf("weight", "fat"),
            ),
        )

        assertEquals("Разрешено 2 из 3", presentation.supportingText)
        assertEquals("Подключить", presentation.actionLabel)
        assertFalse(presentation.actionOpensManagement)
    }

    @Test
    fun `complete health connect access opens system management`() {
        val required = setOf("weight", "fat")
        val presentation = healthConnectPresentation(
            HealthConnectPermissionsUiState.snapshot(true, required, required),
        )

        assertEquals("Подключено · все разрешения выданы", presentation.supportingText)
        assertEquals("Отключить", presentation.actionLabel)
        assertTrue(presentation.actionOpensManagement)
    }

    @Test
    fun `huawei status is flavor aware and only configured enterprise can authorize`() {
        val personal = huaweiIntegrationPresentation(
            HuaweiIntegrationUiState(HuaweiIntegrationStatus.UNAVAILABLE_IN_BUILD),
        )
        val needsSetup = huaweiIntegrationPresentation(
            HuaweiIntegrationUiState(HuaweiIntegrationStatus.CONFIGURATION_REQUIRED),
        )
        val authorize = huaweiIntegrationPresentation(
            HuaweiIntegrationUiState(HuaweiIntegrationStatus.AUTHORIZATION_REQUIRED),
        )

        assertEquals("Недоступно в personal-сборке", personal.supportingText)
        assertNull(personal.actionLabel)
        assertEquals("Нужны enterprise appId и write-scope", needsSetup.supportingText)
        assertNull(needsSetup.actionLabel)
        assertEquals("Разрешить", authorize.actionLabel)
    }
}
