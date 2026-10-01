package com.palixander.weightogether

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelPendingResolutionContractTest {
    private val source by lazy {
        File("src/main/kotlin/com/palixander/weightogether/MainViewModel.kt").readText()
    }
    private val measurementsScreenSource by lazy {
        File("src/main/kotlin/com/palixander/weightogether/measurements/MeasurementsScreen.kt")
            .readText()
    }

    @Test
    fun `successful foreground scan processes reading without success snackbar`() {
        val onScanResult = source.substringBetween(
            "    private fun onScanResult(result: ScanResult) {",
            "    @SuppressLint(\"MissingPermission\")\n    private fun onRefreshScanResult(",
        )

        assertTrue(onScanResult.contains("container.profileStore.saveScale(address, name)"))
        assertTrue(onScanResult.contains("ScanWorkScheduler.processDirect(getApplication(), result)"))
        assertTrue(onScanResult.contains("restoreAutomaticScanning()"))
        assertFalse(onScanResult.contains("showMessage("))
        assertFalse(onScanResult.contains("Измерение принято"))
    }

    @Test
    fun `successful pending assignment completes navigation without success snackbar`() {
        val choosePendingAccount = source.substringBetween(
            "    fun choosePendingAccount(",
            "    fun startCreateAccountForPending(",
        )
        val finalizedBranch = choosePendingAccount.substringBetween(
            "is FinalizePendingResult.Finalized -> {",
            "is FinalizePendingResult.AlreadyFinalized -> {",
        )
        val completion = source.substringBetween(
            "    private fun completePendingResolution(",
            "    private fun clearResolverSession(",
        )

        assertTrue(finalizedBranch.contains("completePendingResolution(completion)"))
        assertFalse(finalizedBranch.contains("showMessage("))
        assertTrue(completion.contains("eventEmitter.pendingResolutionCompleted("))
    }

    @Test
    fun `duplicate and failure pending assignment messages remain`() {
        val choosePendingAccount = source.substringBetween(
            "    fun choosePendingAccount(",
            "    fun startCreateAccountForPending(",
        )

        assertTrue(
            choosePendingAccount.contains(
                "showMessage(uiText(R.string.message_measurement_already_assigned))",
            ),
        )
        assertTrue(
            choosePendingAccount.contains(
                "showMessage(error.userFacingMessage(uiText(R.string.error_assign_measurement)))",
            ),
        )
    }

    @Test
    fun `snackbar resources remain deferred until presentation`() {
        assertFalse(source.contains("private fun localized("))
        assertFalse(source.contains("private fun showMessage(message: String)"))
        assertFalse(source.contains("getString(id, *arguments)"))
        assertTrue(source.contains("showMessage(uiText(R.string.message_backup_saved))"))
        assertTrue(source.contains("message?.takeIf(String::isNotBlank)?.let(UiText::Raw) ?: fallback"))
    }

    @Test
    fun `local-only measurement has no sync presentation entry point or copy`() {
        val measurementsContractSource = File(
            "src/main/kotlin/com/palixander/weightogether/measurements/MeasurementsContract.kt",
        ).readText()

        assertTrue(measurementsScreenSource.contains("if (summary.latest.hasSyncPresentation)"))
        assertTrue(measurementsScreenSource.contains("if (item.hasSyncPresentation)"))
        assertTrue(measurementsScreenSource.contains("it.hasSyncPresentation"))
        listOf(
            "Локальная запись",
            "Только локально",
            "Запись хранится только на этом устройстве",
        ).forEach { removedCopy ->
            assertFalse(measurementsScreenSource.contains(removedCopy))
            assertFalse(measurementsContractSource.contains(removedCopy))
        }
    }
}

private fun String.substringBetween(start: String, end: String): String {
    val startIndex = indexOf(start)
    require(startIndex >= 0) { "Missing contract start: $start" }
    val contentStart = startIndex + start.length
    val endIndex = indexOf(end, contentStart)
    require(endIndex >= 0) { "Missing contract end: $end" }
    return substring(contentStart, endIndex)
}
