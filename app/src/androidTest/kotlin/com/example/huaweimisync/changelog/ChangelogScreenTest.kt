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

        composeRule.onNodeWithText("Версия 0.1.4").assertIsDisplayed()
        composeRule.onNodeWithText("Добавлена встроенная история версий").assertIsDisplayed()
        composeRule.onNodeWithText("Задача #16").assertIsDisplayed()

        val newestTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.4"))
            .fetchSemanticsNode().boundsInRoot.top
        val previousTop = composeRule
            .onNodeWithTag(ChangelogScreenTestTags.release("0.1.3"))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(newestTop < previousTop)
    }

    @Test
    fun listScrollsToOldestRelease() {
        setContent()

        composeRule.onNodeWithTag(ChangelogScreenTestTags.release("0.1.1")).assertDoesNotExist()
        composeRule.onNodeWithTag(ChangelogScreenTestTags.List).performScrollToIndex(3)
        composeRule.onNodeWithTag(ChangelogScreenTestTags.release("0.1.1")).assertIsDisplayed()
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
