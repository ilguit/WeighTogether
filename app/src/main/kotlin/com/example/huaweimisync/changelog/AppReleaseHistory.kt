package com.example.huaweimisync.changelog

import androidx.compose.runtime.Immutable

@Immutable
data class ReleaseChange(
    val issueNumber: Int,
    val description: String,
)

@Immutable
data class AppRelease(
    val version: String,
    val changes: List<ReleaseChange>,
)

object AppReleaseHistory {
    val releases: List<AppRelease> = listOf(
        AppRelease(
            version = "0.1.4",
            changes = listOf(
                ReleaseChange(16, "Добавлена встроенная история версий"),
            ),
        ),
        AppRelease(
            version = "0.1.3",
            changes = listOf(
                ReleaseChange(13, "Селектор аккаунтов скрыт на экранах без данных аккаунта"),
            ),
        ),
        AppRelease(
            version = "0.1.2",
            changes = listOf(
                ReleaseChange(12, "Добавлена ручная сборка APK с уникальными именами артефактов"),
                ReleaseChange(4, "Обновлена монохромная иконка уведомлений"),
            ),
        ),
        AppRelease(
            version = "0.1.1",
            changes = listOf(
                ReleaseChange(6, "Сводка измерений стала компактнее"),
                ReleaseChange(7, "Huawei Health скрыт в personal-сборке"),
                ReleaseChange(8, "Переход к истории измерений перенесён в заголовок"),
                ReleaseChange(11, "Очередь необработанных измерений перенесена в заголовок"),
            ),
        ),
    )
}
