package com.palixander.scalesync

enum class ScaleAvailability {
    AVAILABLE,
    PERMISSION_REQUIRED,
    BLUETOOTH_DISABLED,
}

internal enum class ScaleSettingsStatus { READY, EMPTY, CHECKING, UNAVAILABLE, ERROR }

internal enum class ScaleSettingsAction { SEARCH, OPEN_APP_SETTINGS, RETRY }

internal data class ScaleSettingsPresentation(
    val status: ScaleSettingsStatus,
    val supportingText: String,
    val action: ScaleSettingsAction?,
    val actionLabel: String?,
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
                "Нет разрешения на поиск Bluetooth-устройств"
            } else {
                "Bluetooth выключен"
            },
            action = ScaleSettingsAction.OPEN_APP_SETTINGS.takeIf { permissionMissing },
            actionLabel = "Открыть настройки".takeIf { permissionMissing },
            actionEnabled = permissionMissing,
            showProgress = false,
            allowForget = false,
        )
    }
    if (scanning) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.CHECKING,
        supportingText = "Идёт поиск весов…",
        action = null,
        actionLabel = "Поиск…",
        actionEnabled = false,
        showProgress = true,
        allowForget = false,
    )
    if (scanError != null) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.ERROR,
        supportingText = scanError,
        action = ScaleSettingsAction.RETRY,
        actionLabel = "Повторить",
        actionEnabled = true,
        showProgress = false,
        allowForget = false,
    )
    if (selectedAddress == null) return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.EMPTY,
        supportingText = "Весы ещё не выбраны",
        action = ScaleSettingsAction.SEARCH,
        actionLabel = "Найти весы",
        actionEnabled = true,
        showProgress = false,
        allowForget = false,
    )
    return ScaleSettingsPresentation(
        status = ScaleSettingsStatus.READY,
        supportingText = "${selectedName ?: "Mi Body Composition Scale 2"} · $selectedAddress",
        action = ScaleSettingsAction.SEARCH,
        actionLabel = "Выбрать другие",
        actionEnabled = true,
        showProgress = false,
        allowForget = true,
    )
}

/** Keeps the compact settings root aligned with the selected model while detail retains address. */
internal fun scaleRootSupportingText(
    presentation: ScaleSettingsPresentation,
    selectedName: String?,
): String = if (presentation.status == ScaleSettingsStatus.READY) {
    selectedName ?: "Mi Body Composition Scale 2"
} else {
    presentation.supportingText
}
