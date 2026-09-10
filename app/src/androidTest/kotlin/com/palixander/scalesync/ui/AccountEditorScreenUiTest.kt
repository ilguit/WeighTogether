package com.palixander.scalesync.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.ui.accounts.AccountEditorDraft
import com.palixander.scalesync.ui.accounts.AccountEditorScreen
import com.palixander.scalesync.ui.accounts.AccountManagementTestTags
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountEditorScreenUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun editorUsesRequiredFieldOrderAndPinnedSave() {
        composeRule.setContent {
            ScaleSyncTheme {
                AccountEditorScreen(
                    draft = AccountEditorDraft.add(),
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = {},
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = {},
                    today = LocalDate.of(2026, 8, 20),
                )
            }
        }

        val name = bounds(AccountManagementTestTags.EditorName)
        val sex = bounds(AccountManagementTestTags.EditorSexMale)
        val birthDate = bounds(AccountManagementTestTags.EditorBirthDate)
        val height = bounds(AccountManagementTestTags.EditorHeight)
        val save = bounds(AccountManagementTestTags.EditorSave)
        assertTrue(name.bottom <= sex.top)
        assertTrue(sex.bottom <= birthDate.top)
        assertTrue(birthDate.bottom <= height.top)
        assertTrue(save.top >= height.bottom)
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorTitle)
            .assertIsFocused()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorSexGroup)
            .assertContentDescriptionEquals("Пол")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
    }

    @Test
    fun saveWithInvalidDraftFocusesFirstErrorAndKeepsDraft() {
        var draft by mutableStateOf(AccountEditorDraft.add())
        composeRule.setContent {
            ScaleSyncTheme {
                AccountEditorScreen(
                    draft = draft,
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = { draft = it },
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorName).performTextInput("А")
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorSave).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorName).assertIsFocused()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorName).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(draft.name == "А") }
    }

    @Test
    fun sexChoicesUseEqualWidthFilledSelectionAndRadioSemanticsAtLargeFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ScaleSyncTheme {
                    AccountEditorScreen(
                        draft = AccountEditorDraft.add().copy(sex = Sex.MALE),
                        accounts = emptyList(),
                        operationInProgress = false,
                        onDraftChanged = {},
                        onCreate = {},
                        onUpdate = {},
                        onDismiss = {},
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        val male = composeRule.onNodeWithTag(AccountManagementTestTags.EditorSexMale)
            .assertTextEquals("♂ Мужчина")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .getUnclippedBoundsInRoot()
        val female = composeRule.onNodeWithTag(AccountManagementTestTags.EditorSexFemale)
            .assertTextEquals("♀ Женщина")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .getUnclippedBoundsInRoot()
        assertEquals(male.right - male.left, female.right - female.left)
        assertEquals(male.bottom - male.top, female.bottom - female.top)
        assertTrue(male.bottom > male.top)
        assertTrue(male.right <= female.left)
    }

    @Test
    fun backWithDirtyDraftRequiresDiscardConfirmation() {
        var draft by mutableStateOf(AccountEditorDraft.add())
        var dismissed = false
        composeRule.setContent {
            ScaleSyncTheme {
                AccountEditorScreen(
                    draft = draft,
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = { draft = it },
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorName).performTextInput("Анна")
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorBack).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorDiscardPrompt).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!dismissed) }
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorDiscardConfirm).performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag)
        .assertIsDisplayed()
        .getUnclippedBoundsInRoot()
}
