package com.palixander.scalesync.charts

import com.palixander.scalesync.domain.AccountId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticalChartControllerTest {
    private class Store : AnalyticalChartSettingsStore {
        val data = mutableMapOf<AccountId, List<AnalyticalChartSettings>>()
        override fun read(account: AccountId) = data[account].orEmpty()
        override fun write(account: AccountId, settings: List<AnalyticalChartSettings>) { data[account] = settings }
    }
    private val a = AccountId("a")
    private val b = AccountId("b")
    @Test fun saveCancelRecreationAndAccountsAreIndependent() = runTest {
        val store = Store()
        val controller = AnalyticalChartController(store, this)
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.setWindow(420, 600)
        controller.setSeries(emptySet())
        controller.save()
        controller.edit(AnalyticalChartType.MORNING)
        controller.setWindow(100, 500)
        controller.cancel()
        assertEquals(MorningWindow(420, 600), controller.state.value.settings.single().morningWindow)
        controller.selectAccount(b)
        assertTrue(controller.state.value.settings.isEmpty())
        controller.edit(AnalyticalChartType.HOURLY)
        controller.save()
        val recreated = AnalyticalChartController(store, this)
        recreated.selectAccount(a)
        assertEquals(emptySet<String>(), recreated.state.value.settings.single().activeSeriesKeys)
        assertEquals(AnalyticalChartType.MORNING, recreated.state.value.settings.single().type)
    }
    @Test fun removeUndoRestoresSettingsButReaddUsesDefaultsAndAccountSwitchDropsUndo() = runTest {
        val controller = AnalyticalChartController(Store(), this)
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.setWindow(360, 500)
        controller.save()
        controller.remove(AnalyticalChartType.MORNING)
        controller.undoRemove()
        assertEquals(MorningWindow(360, 500), controller.state.value.settings.single().morningWindow)
        controller.remove(AnalyticalChartType.MORNING)
        controller.dismissUndo()
        controller.edit(AnalyticalChartType.MORNING)
        assertEquals(MorningWindow(), controller.state.value.draft!!.settings.morningWindow)
        controller.selectAccount(b)
        assertNull(controller.state.value.draft)
        assertNull(controller.state.value.removed)
    }
    @Test fun savedSeriesToggleDoesNotChangeDraftOrAnotherAccount() = runTest {
        val controller = AnalyticalChartController(Store(), this)
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.save()
        controller.edit(AnalyticalChartType.MORNING)
        controller.toggleSavedSeries(AnalyticalChartType.MORNING, "weight_kg")
        assertFalse("weight_kg" in controller.state.value.settings.single().activeSeriesKeys)
        assertTrue("weight_kg" in controller.state.value.draft!!.settings.activeSeriesKeys)
        controller.selectAccount(b)
        controller.edit(AnalyticalChartType.MORNING)
        controller.save()
        assertTrue("weight_kg" in controller.state.value.settings.single().activeSeriesKeys)
        controller.selectAccount(a)
        assertFalse("weight_kg" in controller.state.value.settings.single().activeSeriesKeys)
    }
    @Test fun invalidManualWindowCannotSave() = runTest {
        val controller = AnalyticalChartController(Store(), this)
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.setWindow(700, 600)
        controller.save()
        assertFalse(controller.state.value.draft!!.valid)
        assertTrue(controller.state.value.settings.isEmpty())
    }
    @Test fun cancelledOrAccountSwitchedCalculationCannotApply() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = AnalyticalChartController(Store(), this, dispatcher)
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.autoSelect(a, emptyList(), ZoneOffset.UTC)
        controller.cancel()
        advanceUntilIdle()
        assertNull(controller.state.value.draft)
        controller.edit(AnalyticalChartType.MORNING)
        controller.autoSelect(a, emptyList(), ZoneOffset.UTC)
        controller.selectAccount(b)
        controller.edit(AnalyticalChartType.HOURLY)
        advanceUntilIdle()
        assertEquals(AnalyticalChartType.HOURLY, controller.state.value.draft!!.settings.type)
    }
    @Test fun failurePreservesFieldsAndRetryCanSucceedWithoutSaving() = runTest {
        var fails = true
        val preview = MorningPreview(MorningWindow(360, 540), 7, emptyList(), 7, emptyList(), true)
        val controller = AnalyticalChartController(Store(), this, StandardTestDispatcher(testScheduler)) { _, _ ->
            if (fails) error("failure")
            MorningSelectionResult.Success(preview, emptyList())
        }
        controller.selectAccount(a)
        controller.edit(AnalyticalChartType.MORNING)
        controller.setWindow(301, 701)
        controller.autoSelect(a, emptyList(), ZoneOffset.UTC)
        advanceUntilIdle()
        assertEquals(MorningCalculationStatus.ERROR, controller.state.value.draft!!.status)
        assertEquals(301, controller.state.value.draft!!.startMinute)
        fails = false
        controller.autoSelect(a, emptyList(), ZoneOffset.UTC)
        advanceUntilIdle()
        assertEquals(MorningCalculationStatus.SUCCESS, controller.state.value.draft!!.status)
        assertEquals(360, controller.state.value.draft!!.startMinute)
        assertTrue(controller.state.value.settings.isEmpty())
        controller.save()
        assertEquals(MorningFilterMode.AUTOMATIC, controller.state.value.settings.single().morningMode)
    }
}
