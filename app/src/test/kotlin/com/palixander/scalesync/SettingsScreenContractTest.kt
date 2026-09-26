package com.palixander.scalesync

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.data.AppSettings
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.domain.PetWithLatestWeight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import com.palixander.scalesync.ui.text.uiText
import com.palixander.scalesync.ui.text.pluralUiText
import com.palixander.scalesync.ui.text.UiText

class SettingsScreenContractTest {
    @Test
    fun `profile root summary explicitly describes empty state`() {
        assertEquals(uiText(R.string.settings_add_first_profile), profilesRootSummary(0, 0))
    }

    @Test
    fun `profile root summary uses correct russian count forms`() {
        assertEquals(
            UiText.Joined(listOf(pluralUiText(R.plurals.settings_people_count, 1, 1), pluralUiText(R.plurals.settings_pet_count, 1, 1)), " · "),
            profilesRootSummary(1, 1),
        )
        assertEquals(
            UiText.Joined(listOf(pluralUiText(R.plurals.settings_people_count, 21, 21), pluralUiText(R.plurals.settings_pet_count, 22, 22)), " · "),
            profilesRootSummary(21, 22),
        )
    }

    @Test
    fun `connected health connect explains manual management when system destination is missing`() {
        val presentation = IntegrationPresentation(
            supportingText = uiText(R.string.settings_hc_connected),
            actionLabel = uiText(R.string.settings_open),
            actionOpensManagement = true,
        ).withHealthConnectManagementFallback(systemManagementAvailable = false)

        assertEquals(
            uiText(R.string.settings_hc_manual_fallback, uiText(R.string.settings_hc_connected)),
            presentation.supportingText,
        )
        assertNull(presentation.actionLabel)
    }

    @Test
    fun `connected health connect keeps canonical system action when destination is available`() {
        val presentation = IntegrationPresentation(
            supportingText = uiText(R.string.settings_hc_connected),
            actionLabel = uiText(R.string.settings_open),
            actionOpensManagement = true,
        )

        assertEquals(
            presentation,
            presentation.withHealthConnectManagementFallback(systemManagementAvailable = true),
        )
    }

    @Test
    fun `legacy unspecified species has a readable label`() {
        assertEquals(uiText(R.string.pet_species_cat), petSpeciesLabel(PetSpecies.CAT))
        assertEquals(uiText(R.string.pet_species_dog), petSpeciesLabel(PetSpecies.DOG))
        assertEquals(uiText(R.string.pet_species_unspecified), petSpeciesLabel(PetSpecies.UNSPECIFIED))
    }

    @Test
    fun `pet subtitle combines localized weight with relative date and time`() {
        val measuredAt = Instant.parse("2026-08-26T10:32:00Z")
        val pet = Pet(
            id = PetId("pet"),
            displayName = "Барсик",
            species = PetSpecies.CAT,
            createdAt = measuredAt,
            updatedAt = measuredAt,
        )
        val measured = PetWithLatestWeight(
            pet,
            PetMeasurement("measurement", pet.id, measuredAt, 70.0, 75.4),
        )
        val zone = ZoneId.of("Europe/Moscow")

        assertEquals(
            uiText(R.string.settings_pet_weight_summary, "5,4", uiText(R.string.settings_yesterday), "13:32"),
            formatLatestPetWeight(
                measured,
                Locale.forLanguageTag("ru-RU"),
                Instant.parse("2026-08-27T12:00:00Z"),
                zone,
            ),
        )
        assertEquals(
            uiText(R.string.settings_no_value),
            formatLatestPetWeight(
                PetWithLatestWeight(pet, null),
                Locale.forLanguageTag("ru-RU"),
                measuredAt,
                zone,
            ),
        )
    }

    @Test
    fun `replace warning names every human and pet data group`() {
        assertEquals(uiText(R.string.settings_backup_replace_warning), BACKUP_REPLACE_WARNING)
    }

    @Test
    fun `profile summary contains real height date and sex`() {
        assertEquals(
            uiText(R.string.settings_profile_summary, "181,5", "29.02.1988", uiText(R.string.settings_sex_female_lower)),
            formatProfileSummary(UserProfile(181.5, LocalDate.of(1988, 2, 29), Sex.FEMALE), Locale.forLanguageTag("ru-RU")),
        )
        assertEquals(uiText(R.string.settings_profile_not_configured), formatProfileSummary(null))
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
        assertEquals(uiText(R.string.settings_device_not_selected), scaleDetailIdentity(AppSettings()))
        assertEquals(
            uiText(R.string.settings_raw_value, "MIBFS · AA:BB"),
            scaleDetailIdentity(AppSettings(scaleAddress = "AA:BB", scaleName = "MIBFS")),
        )
        assertEquals(uiText(R.string.settings_raw_value, "AA:BB"), scaleDetailIdentity(AppSettings(scaleAddress = "AA:BB")))
    }

    @Test
    fun `settings destinations expose localized detail chrome title resources`() {
        assertEquals(R.string.settings_profiles, SettingsDestination.PROFILES.titleRes)
        assertEquals(R.string.settings_backup, SettingsDestination.BACKUP.titleRes)
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

        assertEquals(uiText(R.string.settings_hc_permissions_count, 2, 3), presentation.supportingText)
        assertEquals(uiText(R.string.settings_connect), presentation.actionLabel)
        assertFalse(presentation.actionOpensManagement)
    }

    @Test
    fun `complete health connect access opens system management`() {
        val required = setOf("weight", "fat")
        val presentation = healthConnectPresentation(
            HealthConnectPermissionsUiState.snapshot(true, required, required),
        )

        assertEquals(uiText(R.string.settings_hc_connected), presentation.supportingText)
        assertEquals(uiText(R.string.settings_open), presentation.actionLabel)
        assertTrue(presentation.actionOpensManagement)
    }

    @Test
    fun `locally disabled health connect offers explicit reconnect`() {
        val required = setOf("weight", "fat")
        val presentation = healthConnectPresentation(
            HealthConnectPermissionsUiState.snapshot(true, required, required),
            locallyEnabled = false,
        )

        assertEquals(uiText(R.string.settings_hc_disabled), presentation.supportingText)
        assertEquals(uiText(R.string.settings_connect_again), presentation.actionLabel)
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
            uiText(R.string.settings_hc_unsupported),
            unsupported.supportingText,
        )
        assertNull(unsupported.actionLabel)
        assertEquals(
            uiText(R.string.settings_hc_update_required),
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

        assertEquals(uiText(R.string.settings_hc_checking), checking.supportingText)
        assertEquals(uiText(R.string.settings_connect), checking.actionLabel)
        assertFalse(checking.actionEnabled)
        assertEquals(uiText(R.string.settings_hc_check_failed), failed.supportingText)
        assertEquals(uiText(R.string.settings_connect), failed.actionLabel)
        assertTrue(failed.actionEnabled)
        assertFalse(failed.actionOpensManagement)
    }

}
