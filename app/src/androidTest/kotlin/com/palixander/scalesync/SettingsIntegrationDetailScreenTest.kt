package com.palixander.scalesync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import com.palixander.scalesync.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsIntegrationDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun healthConnectDetailsFollowCheckingUnavailableAndFailedPresentation() {
        setDetail(
            SettingsDestination.HEALTH_CONNECT,
            MainUiState(
                healthConnect = HealthConnectPermissionsUiState(
                    availability = HealthConnectAvailability.CHECKING,
                ),
            ),
        )
        compose.onNodeWithText("Проверка разрешений…").assertExists()
        assertEquals(
            "Проверка разрешений…",
            compose.onNodeWithTag(SettingsScreenTestTags.IntegrationStatus)
                .fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailHero).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailStatusGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailStatusDivider).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DisableHealthConnect).assertDoesNotExist()

        setDetail(
            SettingsDestination.HEALTH_CONNECT,
            MainUiState(
                healthConnect = HealthConnectPermissionsUiState(
                    availability = HealthConnectAvailability.UNAVAILABLE,
                ),
            ),
        )
        compose.onNodeWithText("Недоступно: устройство не поддерживает Health Connect").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).assertDoesNotExist()

        setDetail(
            SettingsDestination.HEALTH_CONNECT,
            MainUiState(
                healthConnect = HealthConnectPermissionsUiState(
                    availability = HealthConnectAvailability.CHECK_FAILED,
                ),
            ),
        )
        compose.onNodeWithText("Не удалось проверить разрешения").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).assertIsEnabled()
    }

    @Test
    fun connectedHealthConnectOpensManagementAndDisconnectsOnlyAfterConfirmation() {
        var openCalls = 0
        var disconnectCalls = 0
        val required = setOf("weight", "fat")
        setDetail(
            SettingsDestination.HEALTH_CONNECT,
            MainUiState(
                healthConnect = HealthConnectPermissionsUiState.snapshot(true, required, required),
                healthConnectSystemManagementAvailable = true,
            ),
            callbacks(
                onHealthConnectAccessManagement = { openCalls++ },
                onDisableHealthConnect = { disconnectCalls++ },
            ),
        )

        compose.onNodeWithText("Устройство").assertExists()
        compose.onNodeWithText("Интеграция").assertDoesNotExist()
        compose.onNodeWithText("Системная интеграция").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailPrimaryAction).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DetailDangerZone).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DisableHealthConnect).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.DestructiveConfirm).performClick()
        compose.runOnIdle {
            assertEquals(1, openCalls)
            assertEquals(1, disconnectCalls)
        }
    }

    @Test
    fun locallyDisabledHealthConnectReconnectsWithoutDangerousAction() {
        var authorizationCalls = 0
        val required = setOf("weight")
        setDetail(
            SettingsDestination.HEALTH_CONNECT,
            MainUiState(
                settings = AppSettings(healthConnectSyncEnabled = false),
                healthConnect = HealthConnectPermissionsUiState.snapshot(true, required, required),
            ),
            callbacks(onHealthConnectAuthorization = { authorizationCalls++ }),
        )

        compose.onNodeWithText("Отключено в приложении").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).performClick()
        compose.onNodeWithTag(SettingsScreenTestTags.DisableHealthConnect).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, authorizationCalls) }
    }

    @Test
    fun diagnosticsKeepsBackgroundActionsWithoutManualInput() {
        setDetail(SettingsDestination.DIAGNOSTICS, MainUiState())

        compose.onNodeWithTag(SettingsScreenTestTags.DiagnosticsMeasurementGroup).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.ManualTestWeight).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.ManualTestImpedance).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.ManualTestAction).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.DiagnosticsBackgroundGroup).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DiagnosticsBackgroundDivider).assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.DiagnosticsBackgroundSecondDivider).assertExists()
    }

    private fun setDetail(
        destination: SettingsDestination,
        state: MainUiState,
        callbacks: SettingsCallbacks = callbacks(),
    ) {
        compose.setContent {
            SettingsScreen(
                state = state,
                callbacks = callbacks,
                contentPadding = PaddingValues(),
                destination = destination,
            )
        }
    }

    private fun callbacks(
        onHealthConnectAuthorization: () -> Unit = {},
        onHealthConnectAccessManagement: () -> Unit = {},
        onDisableHealthConnect: () -> Unit = {},
    ) = SettingsCallbacks(
        onHealthConnectAuthorization = onHealthConnectAuthorization,
        onHealthConnectAccessManagement = onHealthConnectAccessManagement,
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
        onDisableHealthConnect = onDisableHealthConnect,
    )
}
