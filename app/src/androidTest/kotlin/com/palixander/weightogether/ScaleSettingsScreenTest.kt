package com.palixander.weightogether

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScaleSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingShowsProgressWithoutStopOrDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scanning = true))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("Идёт поиск")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleProgress).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction)
            .assertTextContains("Поиск…")
            .assertIsNotEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    @Test fun readyEnablesSelectionAndShowsDestructiveAction() {
        show(MainUiState(settings = selectedScale()))
        compose.onNodeWithTag(SettingsScreenTestTags.DetailHero).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailStatusGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailDangerZone).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("MIBFS · AA:BB")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertIsEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertExists()
    }

    @Test fun unavailablePermissionShowsRecoveryAndHidesDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scaleAvailability = ScaleAvailability.PERMISSION_REQUIRED))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertTextContains("Нет разрешения")
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertTextContains("Открыть настройки")
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    @Test fun errorOffersRetryAndHidesDestructiveAction() {
        show(MainUiState(settings = selectedScale(), scaleScanError = "Ошибка поиска"))
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleAction).assertTextContains("Повторить")
        compose.onNodeWithTag(SettingsScreenTestTags.ForgetScale).assertDoesNotExist()
    }

    @Test fun unselectedStatesUseExplicitIdentityInsteadOfInventedModel() {
        listOf(
            MainUiState(),
            MainUiState(scanning = true),
            MainUiState(scaleScanError = "Ошибка поиска"),
        ).forEach { state ->
            show(state)
            compose.onNodeWithText("Устройство не выбрано").assertExists()
            compose.onNodeWithText("Mi Body Composition Scale 2").assertDoesNotExist()
        }
    }

    @Test fun connectionHeroUsesCompactIconContainerOnTransparentLayout() {
        show(MainUiState(settings = selectedScale()))

        val hero = compose.onNodeWithTag(SettingsScreenTestTags.DetailHero)
            .getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithTag(SettingsScreenTestTags.DetailHeroIcon)
            .getUnclippedBoundsInRoot()
        compose.onNodeWithText("Весы").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.ScaleStatus).assertExists()
        val iconWidth = icon.right - icon.left
        val iconHeight = icon.bottom - icon.top
        assertEquals(52.dp, iconWidth)
        assertEquals(52.dp, iconHeight)
        assertTrue(hero.right - hero.left > iconWidth)
    }

    private fun show(state: MainUiState) = compose.setContent {
        SettingsScreen(state, callbacks(), PaddingValues(), destination = SettingsDestination.SCALE)
    }

    private fun selectedScale() = AppSettings(scaleAddress = "AA:BB", scaleName = "MIBFS")

    private fun callbacks() = SettingsCallbacks(
        onHealthConnectAuthorization = {}, onHealthConnectAccessManagement = {},
        onManualScan = {}, onReliabilityMode = {},
        openBatterySettings = {}, openApplicationSettings = {},
    )
}
