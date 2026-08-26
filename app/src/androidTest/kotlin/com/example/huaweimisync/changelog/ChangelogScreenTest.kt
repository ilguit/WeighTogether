package com.example.huaweimisync.changelog

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle("0.1.1")).performClick()
        composeRule.onNodeWithText("Задача #11").assertIsDisplayed()
    }

    @Test
    fun newestReleaseStartsExpandedAndPreviousReleasesToggleIndependently() {
        setContent()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.6")).assertIsDisplayed()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.4")).assertDoesNotExist()
        val previousToggle = composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle("0.1.4"))
        assertEquals(
            "Свёрнуто",
            previousToggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        previousToggle.performClick()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.4")).assertIsDisplayed()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle("0.1.6")).performClick()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.6")).assertDoesNotExist()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.4")).assertIsDisplayed()
        assertEquals(
            "Развёрнуто",
            previousToggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun emptyReleaseListShowsNoReleaseCards() {
        setContent(releases = emptyList())

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithText("Версия", substring = true).assertDoesNotExist()
    }

    @Test
    fun singleReleaseStartsExpandedAndCanCollapseAndReExpand() {
        val release = AppRelease(
            version = "0.1.6",
            changes = listOf(ReleaseChange(20, "Исправление")),
        )
        setContent(releases = listOf(release))

        val toggle = composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle(release.version))
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges(release.version)).assertIsDisplayed()

        toggle.performClick()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges(release.version)).assertDoesNotExist()

        toggle.performClick()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges(release.version)).assertIsDisplayed()
    }

    @Test
    fun releaseExpansionSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HuaweiMiSyncTheme {
                ChangelogScreen(
                    releases = correctedHistoricalReleases,
                    modifier = Modifier.height(280.dp),
                )
            }
        }
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle("0.1.6")).performClick()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseToggle("0.1.4")).performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.6")).assertDoesNotExist()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.releaseChanges("0.1.4")).assertIsDisplayed()
    }

    private fun setContent(releases: List<AppRelease> = correctedHistoricalReleases) {
        composeRule.setContent {
            HuaweiMiSyncTheme {
                ChangelogScreen(
                    releases = releases,
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
