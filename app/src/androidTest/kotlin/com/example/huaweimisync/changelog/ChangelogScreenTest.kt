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

        composeRule.onNodeWithText("Версия 0.1.13").assertIsDisplayed()
        composeRule.onNodeWithText("История версий теперь формируется автоматически из релизных заметок")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Задача #25").assertIsDisplayed()

        val newestTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.13"))
            .fetchSemanticsNode().boundsInRoot.top
        val previousTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.6"))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(newestTop < previousTop)
    }

    @Test
    fun listContainsCompleteHistoryIncludingVersion016() {
        setContent()

        val versions = listOf("0.1.13", "0.1.6", "0.1.5", "0.1.4", "0.1.3", "0.1.2", "0.1.1")
        versions.forEachIndexed { index, version ->
            composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(index)
            composeRule.onNodeWithTag(ChangelogScreenTestTags.release(version)).assertIsDisplayed()
        }

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(1)
        composeRule.onNodeWithText("Задача #19").assertIsDisplayed()
        composeRule.onNodeWithText("Задача #20").assertIsDisplayed()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(6)
        composeRule.onNodeWithText("Задача #11").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            HuaweiMiSyncTheme {
                ChangelogScreen(modifier = Modifier.height(280.dp))
            }
        }
    }
}
