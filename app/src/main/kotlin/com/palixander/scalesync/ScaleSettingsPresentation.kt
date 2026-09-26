package com.palixander.scalesync

import com.palixander.scalesync.ui.text.UiText
import com.palixander.scalesync.ui.text.uiText

enum class ScaleAvailability {
    AVAILABLE,
    PERMISSION_REQUIRED,
    BLUETOOTH_DISABLED,
}

internal enum class ScaleSettingsStatus { READY, EMPTY, CHECKING, UNAVAILABLE, ERROR }

internal enum class ScaleSettingsAction { SEARCH, OPEN_APP_SETTINGS, RETRY }

internal data class ScaleSettingsPresentation(
    val status: ScaleSettingsStatus,
    val supportingText: UiText,
    val action: ScaleSettingsAction?,
    val actionLabel: UiText?,
    val actionEnabled: Boolean,
    val showProgress: Boolean,
    val allowForget: Boolean,
)

internal fun scaleSettingsPresentation(
    selectedAddress: String?,
    selectedName: String?,
    scanning: Boolean,
    availability: ScaleAvailability,
    scanError: String?,
): ScaleSettingsPresentation {
    if (availability != ScaleAvailability.AVAILABLE) {
        val permissionMissing = availability == ScaleAvailability.PERMISSION_REQUIRED
        return ScaleSettingsPresentation(
            status = ScaleSettingsStatus.UNAVAILABLE,
            supportingText = if (permissionMissing) {
                uiText(R.string.settings_scale_permission_missing)
            } else {
                uiText(R.string.settings_bluetooth_off)
            },
            action = ScaleSettingsAction.OPEN_APP_SETTINGS.takeIf { permissionMissing },
            actionLabel = uiText(R.string.settings_open_settings).takeIf { permissionMissing },
            actionEnabled = permissionMissing,
            showProgress = false,
            allowForget = false,
        )
    }
    if (scanning) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.CHECKING,
        supportingText = uiText(R.string.settings_scale_searching),
        action = null,
        actionLabel = uiText(R.string.settings_searching),
        actionEnabled = false,
        showProgress = true,
        allowForget = false,
    )
    if (scanError != null) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.ERROR,
        supportingText = uiText(R.string.settings_raw_value, scanError),
        action = ScaleSettingsAction.RETRY,
        actionLabel = uiText(R.string.settings_retry),
        actionEnabled = true,
        showProgress = false,
        allowForget = false,
    )
    if (selectedAddress == null) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.EMPTY,
        supportingText = uiText(R.string.settings_scale_not_chosen),
        action = ScaleSettingsAction.SEARCH,
        actionLabel = uiText(R.string.settings_find_scale),
        actionEnabled = true,
        showProgress = false,
        allowForget = false,
    )
    return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.READY,
        supportingText = uiText(
            R.string.settings_scale_identity,
            selectedName?.let { uiText(R.string.settings_raw_value, it) }
                ?: uiText(R.string.settings_default_scale_name),
            selectedAddress,
        ),
        action = ScaleSettingsAction.SEARCH,
        actionLabel = uiText(R.string.settings_choose_another),
        actionEnabled = true,
        showProgress = false,
        allowForget = true,
    )
}

/** Keeps the compact settings root aligned with the selected model while detail retains address. */
internal fun scaleRootSupportingText(
    presentation: ScaleSettingsPresentation,
    selectedName: String?,
): UiText = if (presentation.status == ScaleSettingsStatus.READY) {
    selectedName?.let { uiText(R.string.settings_raw_value, it) }
        ?: uiText(R.string.settings_default_scale_name)
} else {
    presentation.supportingText
}
