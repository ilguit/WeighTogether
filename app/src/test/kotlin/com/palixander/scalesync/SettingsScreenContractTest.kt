package com.palixander.scalesync

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.data.AppSettings
import com.palixander.scalesync.domain.PetSpecies
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsScreenContractTest {
    @Test
    fun `profile root summary explicitly describes empty state`() {
        assertEquals("Добавьте первый профиль", profilesRootSummary(0, 0))
    }

    @Test
    fun `profile root summary uses correct russian count forms`() {
        assertEquals("1 человек · 1 питомец", profilesRootSummary(1, 1))
        assertEquals("2 человека · 4 питомца", profilesRootSummary(2, 4))
        assertEquals("5 человек · 5 питомцев", profilesRootSummary(5, 5))
        assertEquals("11 человек · 11 питомцев", profilesRootSummary(11, 11))
        assertEquals("21 человек · 22 питомца", profilesRootSummary(21, 22))
    }

    @Test
    fun `connected health connect explains manual management when system destination is missing`() {
        val presentation = IntegrationPresentation(
            supportingText = "Подключено · все разрешения выданы",
            actionLabel = "Открыть",
            actionOpensManagement = true,
        ).withHealthConnectManagementFallback(systemManagementAvailable = false)

        assertEquals(
            "Подключено · все разрешения выданы · управляйте доступом вручную в Health Connect",
            presentation.supportingText,
        )
        assertNull(presentation.actionLabel)
    }

    @Test
    fun `connected health connect keeps canonical system action when destination is available`() {
        val presentation = IntegrationPresentation(
            supportingText = "Подключено",
            actionLabel = "Открыть",
            actionOpensManagement = true,
        )

        assertEquals(
            presentation,
            presentation.withHealthConnectManagementFallback(systemManagementAvailable = true),
        )
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
        assertTrue(BACKUP_REPLACE_WARNING.contains("профили"))
        assertFalse(BACKUP_REPLACE_WARNING.contains("аккаунт", ignoreCase = true))
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
    fun `settings root destinations have the required stable order`() {
        assertEquals(
            listOf(
                SettingsDestination.PROFILES,
                SettingsDestination.SCALE,
                SettingsDestination.HEALTH_CONNECT,
                SettingsDestination.BACKUP,
                SettingsDestination.DIAGNOSTICS,
            ),
            settingsRootDestinations(),
        )
    }

    @Test
    fun `root focus restoration targets actual lazy column group items`() {
        assertEquals(0, settingsRootGroupItemIndex(SettingsDestination.PROFILES))
        assertEquals(2, settingsRootGroupItemIndex(SettingsDestination.SCALE))
        assertEquals(2, settingsRootGroupItemIndex(SettingsDestination.HEALTH_CONNECT))
        assertEquals(4, settingsRootGroupItemIndex(SettingsDestination.BACKUP))
        assertEquals(4, settingsRootGroupItemIndex(SettingsDestination.DIAGNOSTICS))
        assertNull(settingsRootGroupItemIndex(SettingsDestination.ROOT))
    }

    @Test
    fun `scale detail identity never invents an unselected device`() {
        assertEquals("Устройство не выбрано", scaleDetailIdentity(AppSettings()))
        assertEquals(
            "MIBFS · AA:BB",
            scaleDetailIdentity(AppSettings(scaleAddress = "AA:BB", scaleName = "MIBFS")),
        )
        assertEquals("AA:BB", scaleDetailIdentity(AppSettings(scaleAddress = "AA:BB")))
    }

    @Test
    fun `settings destinations expose detail chrome titles`() {
        assertEquals("Профили", SettingsDestination.PROFILES.title)
        assertEquals("Резервная копия", SettingsDestination.BACKUP.title)
    }

    @Test
    fun `settings root destinations keep their approved thematic line icons`() {
        assertEquals("Huawei.Users", settingsRootIcon(SettingsDestination.PROFILES).name)
        assertEquals("Huawei.Bluetooth", settingsRootIcon(SettingsDestination.SCALE).name)
        assertEquals("Huawei.HealthConnect", settingsRootIcon(SettingsDestination.HEALTH_CONNECT).name)
        assertEquals("Huawei.Archive", settingsRootIcon(SettingsDestination.BACKUP).name)
        assertEquals("Huawei.Stethoscope", settingsRootIcon(SettingsDestination.DIAGNOSTICS).name)
    }

    @Test
    fun `root status marks are successful only for actually ready integrations`() {
        val permissions = setOf("weight")
        val connectedHealth = HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = permissions,
            grantedPermissions = permissions,
        )
        assertTrue(healthRootStatusSuccessful(connectedHealth, locallyEnabled = true))
        assertFalse(healthRootStatusSuccessful(connectedHealth, locallyEnabled = false))
        assertFalse(
            healthRootStatusSuccessful(
                HealthConnectPermissionsUiState(HealthConnectAvailability.CHECKING),
                locallyEnabled = true,
            ),
        )
    }

    @Test
    fun `detail destructive actions exist only for connected usable targets`() {
        val permissions = setOf("weight")
        val connected = HealthConnectPermissionsUiState.snapshot(true, permissions, permissions)

        assertEquals(
            DestructiveSettingsAction.SCALE,
            settingsDetailDestructiveAction(
                SettingsDestination.SCALE,
                MainUiState(settings = AppSettings(scaleAddress = "AA:BB", scaleName = "MIBFS")),
            ),
        )
        assertEquals(
            DestructiveSettingsAction.HEALTH_CONNECT,
            settingsDetailDestructiveAction(
                SettingsDestination.HEALTH_CONNECT,
                MainUiState(healthConnect = connected),
            ),
        )
        assertNull(
            settingsDetailDestructiveAction(
                SettingsDestination.HEALTH_CONNECT,
                MainUiState(
                    settings = AppSettings(healthConnectSyncEnabled = false),
                    healthConnect = connected,
                ),
            ),
        )
        assertNull(settingsDetailDestructiveAction(SettingsDestination.BACKUP, MainUiState()))
        assertNull(settingsDetailDestructiveAction(SettingsDestination.DIAGNOSTICS, MainUiState()))
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
    fun `locally disabled health connect offers explicit reconnect`() {
        val required = setOf("weight", "fat")
        val presentation = healthConnectPresentation(
            HealthConnectPermissionsUiState.snapshot(true, required, required),
            locallyEnabled = false,
        )

        assertEquals("Отключено в приложении", presentation.supportingText)
        assertEquals("Подключить снова", presentation.actionLabel)
        assertFalse(presentation.actionOpensManagement)
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
    fun `health connect checking disables connect while failed check keeps authorization flow`() {
        val checking = healthConnectPresentation(
            HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.CHECKING,
            ),
        )
        val failed = healthConnectPresentation(
            HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.CHECK_FAILED,
            ),
        )

        assertEquals("Проверка разрешений…", checking.supportingText)
        assertEquals("Подключить", checking.actionLabel)
        assertFalse(checking.actionEnabled)
        assertEquals("Не удалось проверить разрешения", failed.supportingText)
        assertEquals("Подключить", failed.actionLabel)
        assertTrue(failed.actionEnabled)
        assertFalse(failed.actionOpensManagement)
    }

}
