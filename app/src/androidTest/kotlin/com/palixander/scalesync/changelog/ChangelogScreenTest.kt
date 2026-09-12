package com.palixander.scalesync.changelog

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.BuildConfig
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChangelogScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun newestReleaseIsAlwaysExpandedAndNotToggleable() {
        setContent()
        composeRule.onNodeWithTag(tags.releaseChanges("0.1.6")).assertIsDisplayed()
        val newest = composeRule.onNodeWithTag(tags.release("0.1.6")).fetchSemanticsNode().config
        assertFalse(newest.contains(SemanticsActions.OnClick))
        assertFalse(newest.contains(SemanticsProperties.StateDescription))
    }

    @Test
    fun latestChangesAppearInASeparateTopCard() {
        setContent(latestChanges = latestChanges)

        composeRule.onNodeWithTag(tags.LatestChanges).assertIsDisplayed()
        composeRule.onNodeWithTag(tags.LatestChangesHeading).assertIsDisplayed()
        composeRule.onNodeWithTag(tags.LatestChangesContent).assertIsDisplayed()
        assertTrue(
            composeRule.onNodeWithTag(tags.LatestChanges).fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithTag(tags.release("0.1.6")).fetchSemanticsNode().boundsInRoot.top,
        )
        latestChanges.forEach {
            composeRule.onNodeWithText("Задача #${it.issueNumber}").assertIsDisplayed()
        }
        assertTrue(
            composeRule.onNodeWithTag(tags.LatestChangesHeading).fetchSemanticsNode().config
                .contains(SemanticsProperties.Heading),
        )
    }

    @Test
    fun latestChangesCardIsAbsentWhenThereAreNoLatestChanges() {
        setContent(latestChanges = emptyList())
        composeRule.onNodeWithTag(tags.LatestChanges).assertDoesNotExist()
        composeRule.onNodeWithText("Последние изменения (${BuildConfig.VERSION_CODE})").assertDoesNotExist()
    }

    @Test
    fun previousReleasesShareOneInitiallyCollapsedBlock() {
        setContent()
        composeRule.onNodeWithText("Предыдущие версии").assertIsDisplayed()
        composeRule.onNodeWithTag(tags.PreviousReleasesContent).assertDoesNotExist()
        releases.drop(1).forEach { composeRule.onNodeWithTag(tags.release(it.version)).assertDoesNotExist() }

        composeRule.onNodeWithTag(tags.PreviousReleasesToggle).performClick()
        composeRule.onNodeWithTag(tags.PreviousReleasesContent).assertIsDisplayed()
        releases.drop(1).forEach {
            composeRule.onNodeWithTag(tags.release(it.version)).assertExists()
            composeRule.onNodeWithTag(tags.releaseChanges(it.version)).assertExists()
        }
    }

    @Test
    fun previousBlockPreservesReleaseAndChangeOrder() {
        setContent()
        composeRule.onNodeWithTag(tags.PreviousReleasesToggle).performClick()
        val tops = releases.drop(1).map {
            composeRule.onNodeWithTag(tags.release(it.version)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        val changeTops = listOf(6, 8, 11).map {
            composeRule.onNodeWithText("Задача #$it").fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue(changeTops.zipWithNext().all { (first, second) -> first < second })
    }

    @Test
    fun previousToggleExposesButtonStateAndNamedAction() {
        setContent()
        val toggle = composeRule.onNodeWithTag(tags.PreviousReleasesToggle)
        toggle.assertHasClickAction()
        var semantics = toggle.fetchSemanticsNode().config
        assertEquals(Role.Button, semantics[SemanticsProperties.Role])
        assertEquals("Свёрнуто", semantics[SemanticsProperties.StateDescription])
        assertEquals("Развернуть предыдущие версии", semantics[SemanticsActions.OnClick].label)
        toggle.performClick()
        semantics = toggle.fetchSemanticsNode().config
        assertEquals("Развёрнуто", semantics[SemanticsProperties.StateDescription])
        assertEquals("Свернуть предыдущие версии", semantics[SemanticsActions.OnClick].label)
    }

    @Test
    fun sharedExpansionSurvivesSavedStateRestoration() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent { Content(releases) }
        composeRule.onNodeWithTag(tags.PreviousReleasesToggle).performClick()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(tags.PreviousReleasesContent).assertIsDisplayed()
        composeRule.onNodeWithTag(tags.releaseChanges("0.1.1")).assertExists()
    }

    @Test
    fun emptyListShowsNoReleaseOrPreviousBlock() {
        setContent(emptyList())
        composeRule.onNodeWithTag(tags.List).assertIsDisplayed()
        composeRule.onNodeWithText("Версия", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(tags.PreviousReleases).assertDoesNotExist()
    }

    @Test
    fun singleReleaseIsExpandedWithoutPreviousBlock() {
        setContent(releases.take(1))
        composeRule.onNodeWithTag(tags.releaseChanges("0.1.6")).assertIsDisplayed()
        composeRule.onNodeWithTag(tags.PreviousReleases).assertDoesNotExist()
    }

    @Test
    fun disclosureIndicatorKeepsItsCenterWhenToggled() {
        setContent(items = releases.take(2))
        assertStableDisclosureGeometry()
    }

    @Test
    fun narrowLargeTextDoesNotOverlapDisclosureIndicator() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ScaleSyncTheme {
                    ChangelogScreen(
                        modifier = Modifier.width(280.dp),
                        releases = releases.take(2),
                        latestChanges = emptyList(),
                    )
                }
            }
        }
        assertStableDisclosureGeometry()
    }

    private fun assertStableDisclosureGeometry() {
        fun geometry(): Pair<Float, Float> {
            val row = composeRule.onNodeWithTag(tags.PreviousReleasesToggle).getUnclippedBoundsInRoot()
            val icon = composeRule.onNodeWithTag(tags.PreviousReleasesIndicator, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val title = composeRule.onNodeWithTag(tags.PreviousReleasesTitle, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals(24f, (icon.right - icon.left).value, 0.5f)
            assertEquals(24f, (icon.bottom - icon.top).value, 0.5f)
            assertTrue(row.bottom - row.top >= 48.dp)
            assertTrue(title.right <= icon.left)
            assertEquals(((row.top + row.bottom) / 2).value, ((icon.top + icon.bottom) / 2).value, 0.5f)
            return ((icon.left + icon.right) / 2 - row.left).value to
                ((icon.top + icon.bottom) / 2 - row.top).value
        }
        val before = geometry()
        composeRule.onNodeWithTag(tags.PreviousReleasesToggle).performClick()
        composeRule.onNodeWithTag(tags.PreviousReleasesContent).assertExists()
        val expanded = geometry()
        assertEquals(before.first, expanded.first, 0.5f)
        assertEquals(before.second, expanded.second, 0.5f)
        composeRule.onNodeWithTag(tags.PreviousReleasesToggle).performClick()
        composeRule.onNodeWithTag(tags.PreviousReleasesContent).assertDoesNotExist()
        assertEquals(before, geometry())
    }

    private fun setContent(
        items: List<AppRelease> = releases,
        latestChanges: List<ReleaseChange> = emptyList(),
    ) = composeRule.setContent { Content(items, latestChanges) }

    @androidx.compose.runtime.Composable
    private fun Content(items: List<AppRelease>, latestChanges: List<ReleaseChange> = emptyList()) {
        ScaleSyncTheme {
            ChangelogScreen(
                modifier = Modifier.height(900.dp),
                releases = items,
                latestChanges = latestChanges,
            )
        }
    }

    private val tags = ChangelogScreenTestTags
    private val latestChanges = listOf(
        ReleaseChange(45, "Последнее улучшение"),
        ReleaseChange(44, "Ещё одно улучшение"),
    )
    private val releases = listOf(
        AppRelease("0.1.6", listOf(ReleaseChange(20, "Новое"))),
        AppRelease("0.1.4", listOf(ReleaseChange(16, "Первое"))),
        AppRelease("0.1.3", listOf(ReleaseChange(13, "Второе"))),
        AppRelease("0.1.2", listOf(ReleaseChange(4, "Третье"))),
        AppRelease("0.1.1", listOf(
            ReleaseChange(6, "Четвёртое"),
            ReleaseChange(8, "Пятое"),
            ReleaseChange(11, "Шестое"),
        )),
    )
}
