package com.example.huaweimisync

import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import java.time.LocalDate
import com.example.huaweimisync.domain.PetSpecies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsScreenContractTest {
    @Test
    fun `pet editor requires a nonblank name and explicit supported species`() {
        assertFalse(isPetEditorValid("Барсик", null))
        assertFalse(isPetEditorValid("Барсик", PetSpecies.UNSPECIFIED))
        assertFalse(isPetEditorValid("   ", PetSpecies.CAT))
        assertTrue(isPetEditorValid(" Барсик ", PetSpecies.CAT))
        assertTrue(isPetEditorValid("Шарик", PetSpecies.DOG))
    }

    @Test
    fun `legacy unspecified species has a readable label`() {
        assertEquals("Кошка", petSpeciesLabel(PetSpecies.CAT))
        assertEquals("Собака", petSpeciesLabel(PetSpecies.DOG))
        assertEquals("Вид не указан", petSpeciesLabel(PetSpecies.UNSPECIFIED))
    }

    @Test
    fun `latest pet weight uses locale and no-history state is explicit`() {
        assertEquals("Последний вес: 4,25 кг", formatLatestPetWeight(4.25, Locale.forLanguageTag("ru-RU")))
        assertEquals("Измерений пока нет", formatLatestPetWeight(null, Locale.forLanguageTag("ru-RU")))
    }

    @Test
    fun `replace warning names every human and pet data group`() {
        assertTrue(BACKUP_REPLACE_WARNING.contains("аккаунты"))
        assertTrue(BACKUP_REPLACE_WARNING.contains("измерения людей"))
        assertTrue(BACKUP_REPLACE_WARNING.contains("ожидающие измерения"))
        assertTrue(BACKUP_REPLACE_WARNING.contains("питомцы"))
        assertTrue(BACKUP_REPLACE_WARNING.contains("измерения питомцев"))
    }

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
        assertEquals("Открыть", presentation.actionLabel)
        assertTrue(presentation.actionOpensManagement)
    }

    @Test
    fun `unavailable health connect explains cause and exposes no action`() {
        val unsupported = healthConnectPresentation(
            HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.UNAVAILABLE,
            ),
        )
        val providerMissing = healthConnectPresentation(
            HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            ),
        )

        assertEquals(
            "Недоступно: устройство не поддерживает Health Connect",
            unsupported.supportingText,
        )
        assertNull(unsupported.actionLabel)
        assertEquals(
            "Недоступно: установите или обновите Health Connect",
            providerMissing.supportingText,
        )
        assertNull(providerMissing.actionLabel)
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

    @Test
    fun `huawei check blocks authorization and failed check offers retry`() {
        val checking = huaweiIntegrationPresentation(
            HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECKING),
        )
        val failed = huaweiIntegrationPresentation(
            HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECK_FAILED),
        )

        assertEquals("Проверка разрешения…", checking.supportingText)
        assertFalse(checking.actionEnabled)
        assertEquals("Не удалось проверить разрешение", failed.supportingText)
        assertEquals("Повторить", failed.actionLabel)
        assertTrue(failed.actionRetriesCheck)
    }
}
