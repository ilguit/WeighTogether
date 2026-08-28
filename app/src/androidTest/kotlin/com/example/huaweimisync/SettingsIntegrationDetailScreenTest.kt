package com.example.huaweimisync

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import com.example.huaweimisync.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
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

        compose.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).performClick()
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
    fun personalFlavorHasNoHuaweiRootDestination() {
        assumeFalse(BuildConfig.HUAWEI_EXTENDED_ENABLED)
        assertEquals(
            false,
            settingsRootDestinations(huaweiEnabled = BuildConfig.HUAWEI_EXTENDED_ENABLED)
                .contains(SettingsDestination.HUAWEI_HEALTH),
        )
    }

    @Test
    fun enterpriseHuaweiDetailsUseRetryAndAuthorizedDisconnectContracts() {
        assumeTrue(BuildConfig.HUAWEI_EXTENDED_ENABLED)
        var refreshCalls = 0
        setDetail(
            SettingsDestination.HUAWEI_HEALTH,
            MainUiState(huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECK_FAILED)),
            callbacks(onHuaweiPermissionRefresh = { refreshCalls++ }),
        )
        compose.onNodeWithText("Не удалось проверить разрешение").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthAction).performClick()
        compose.runOnIdle { assertEquals(1, refreshCalls) }

        setDetail(
            SettingsDestination.HUAWEI_HEALTH,
            MainUiState(huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.AUTHORIZED)),
        )
        compose.onNodeWithText("Подключено").assertExists()
        compose.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthAction).assertDoesNotExist()
        compose.onNodeWithTag(SettingsScreenTestTags.DisableHuawei).assertExists()
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
        onHuaweiPermissionRefresh: () -> Unit = {},
        onHealthConnectAuthorization: () -> Unit = {},
        onHealthConnectAccessManagement: () -> Unit = {},
        onDisableHealthConnect: () -> Unit = {},
    ) = SettingsCallbacks(
        onHuaweiAuthorization = {},
        onHuaweiPermissionRefresh = onHuaweiPermissionRefresh,
        onHealthConnectAuthorization = onHealthConnectAuthorization,
        onHealthConnectAccessManagement = onHealthConnectAccessManagement,
        onManualTest = { _, _ -> },
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
        onDisableHealthConnect = onDisableHealthConnect,
    )
}
