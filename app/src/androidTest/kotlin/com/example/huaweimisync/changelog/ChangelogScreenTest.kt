package com.example.huaweimisync.changelog

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChangelogScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenShowsReleaseDetailsInNewestFirstOrder() {
        setContent()

        composeRule.onNodeWithText("Версия 0.1.6").assertIsDisplayed()
        composeRule.onNodeWithText("Убрано уведомление об успешном локальном сохранении измерения")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Задача #20").assertIsDisplayed()

        val newestTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.6"))
            .fetchSemanticsNode().boundsInRoot.top
        val previousTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.4"))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(newestTop < previousTop)
    }

    @Test
    fun listContainsCorrectedHistoricalReleases() {
        setContent()

        val versions = listOf("0.1.6", "0.1.4", "0.1.3", "0.1.2", "0.1.1")
        versions.forEachIndexed { index, version ->
            composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(index)
            composeRule.onNodeWithTag(ChangelogScreenTestTags.release(version)).assertIsDisplayed()
        }

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(0)
        composeRule.onNodeWithText("Задача #20").assertIsDisplayed()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(4)
        composeRule.onNodeWithText("Задача #11").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            HuaweiMiSyncTheme {
                ChangelogScreen(
                    releases = correctedHistoricalReleases,
                    modifier = Modifier.height(280.dp),
                )
            }
        }
    }

    private val correctedHistoricalReleases = listOf(
        AppRelease("0.1.6", listOf(ReleaseChange(20, "Убрано уведомление об успешном локальном сохранении измерения"))),
        AppRelease("0.1.4", listOf(ReleaseChange(16, "Добавлена встроенная история версий"))),
        AppRelease("0.1.3", listOf(ReleaseChange(13, "Селектор аккаунтов скрыт на экранах без данных аккаунта"))),
        AppRelease("0.1.2", listOf(ReleaseChange(4, "Обновлена монохромная иконка уведомлений"))),
        AppRelease(
            "0.1.1",
            listOf(
                ReleaseChange(6, "Сводка измерений стала компактнее"),
                ReleaseChange(8, "Переход к истории измерений перенесён в заголовок"),
                ReleaseChange(11, "Очередь необработанных измерений перенесена в заголовок"),
            ),
        ),
    )
}
