package com.example.huaweimisync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelPendingResolutionContractTest {
    private val source by lazy {
        File("src/main/kotlin/com/example/huaweimisync/MainViewModel.kt").readText()
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

        assertTrue(choosePendingAccount.contains("showMessage(\"Измерение уже назначено\")"))
        assertTrue(
            choosePendingAccount.contains(
                "showMessage(error.userFacingMessage(\"Не удалось назначить измерение\"))",
            ),
        )
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
