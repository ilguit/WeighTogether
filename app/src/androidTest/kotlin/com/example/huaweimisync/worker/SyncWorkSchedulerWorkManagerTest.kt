package com.example.huaweimisync.worker

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncWorkSchedulerWorkManagerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var workerExecutor: ExecutorService
    private lateinit var taskExecutor: ExecutorService

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        workerExecutor = Executors.newFixedThreadPool(2)
        taskExecutor = Executors.newSingleThreadExecutor()
        val configuration = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(taskExecutor)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        SelfDeferCurrentWorker.release.countDown()
        ReplaceCurrentWorker.release.countDown()
        ManualRunningWorker.release.countDown()
        workManager.cancelAllWork()
        waitForUniqueWorkToFinish("sync-self-defer")
        waitForUniqueWorkToFinish("sync-external-reschedule")
        waitForUniqueWorkToFinish("sync-initial-retry")
        waitForUniqueWorkToFinish("sync-manual-running")
        waitForUniqueWorkToFinish("sync-kickoff-initial-retry")
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        workerExecutor.shutdownNow()
        taskExecutor.shutdownNow()
    }

    @Test
    fun selfDeferAppendsWithoutCancellingCurrentAndExternalRescheduleReplaces() {
        verifySelfDeferAppendsWithoutCancellingCurrent()
        verifyExternalRescheduleReplacesCurrentChain()
        verifyImmediateRetryCancelsDelayedKickoff()
        verifyImmediateRetryKeepsRunningActualWork()
    }

    private fun verifyImmediateRetryCancelsDelayedKickoff() {
        val measurementId = "initial-retry"
        val kickoffWorkName = "sync-kickoff-$measurementId"
        val actualWorkName = "sync-$measurementId"
        val now = System.currentTimeMillis()
        val scheduler = SyncWorkScheduler(
            context = context,
            pausedUntilProvider = { now + DEFER_MILLIS },
            nowEpochMillis = { now },
        )

        scheduler.enqueueInitial(measurementId)
        val initial = waitForUniqueWorkCount(kickoffWorkName, 1).single()
        assertEquals(WorkInfo.State.ENQUEUED, initial.state)
        assertEquals(INITIAL_SYNC_DELAY_MILLIS, initial.initialDelayMillis)

        scheduler.enqueueImmediately(measurementId)

        assertEquals(WorkInfo.State.CANCELLED, waitForState(initial.id, WorkInfo.State.CANCELLED).state)
        val replacement = waitForUniqueWorkCount(actualWorkName, 1).single()
        assertEquals(WorkInfo.State.ENQUEUED, waitForState(replacement.id, WorkInfo.State.ENQUEUED).state)
        assertEquals(DEFER_MILLIS, replacement.initialDelayMillis)
    }

    private fun verifyImmediateRetryKeepsRunningActualWork() {
        val measurementId = "manual-running"
        val uniqueWorkName = "sync-$measurementId"
        val current = OneTimeWorkRequestBuilder<ManualRunningWorker>().build()
        workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, current)
        assertTrue(ManualRunningWorker.started.await(10, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, waitForState(current.id, WorkInfo.State.RUNNING).state)

        SyncWorkScheduler(context = context).enqueueImmediately(measurementId)

        val chain = waitForUniqueWorkCount(uniqueWorkName, 1)
        assertEquals(current.id, chain.single().id)
        assertEquals(WorkInfo.State.RUNNING, workInfo(current.id).state)

        ManualRunningWorker.release.countDown()
        assertEquals(WorkInfo.State.SUCCEEDED, waitForState(current.id, WorkInfo.State.SUCCEEDED).state)
    }

    private fun verifySelfDeferAppendsWithoutCancellingCurrent() {
        val measurementId = "self-defer"
        val uniqueWorkName = "sync-$measurementId"
        val current = OneTimeWorkRequestBuilder<SelfDeferCurrentWorker>().build()
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            current,
        )
        assertTrue(SelfDeferCurrentWorker.started.await(10, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, waitForState(current.id, WorkInfo.State.RUNNING).state)

        val now = System.currentTimeMillis()
        val deadline = now + DEFER_MILLIS
        SyncWorkScheduler(
            context = context,
            pausedUntilProvider = { deadline },
            nowEpochMillis = { now },
        ).deferCurrent(measurementId, deadline - 1_000L)
        val successor = waitForUniqueWorkCount(uniqueWorkName, 2)
            .single { it.id != current.id }

        assertEquals(WorkInfo.State.RUNNING, workInfo(current.id).state)
        assertNotEquals(WorkInfo.State.CANCELLED, workInfo(current.id).state)
        assertEquals(WorkInfo.State.BLOCKED, successor.state)

        SelfDeferCurrentWorker.release.countDown()
        assertEquals(WorkInfo.State.SUCCEEDED, waitForState(current.id, WorkInfo.State.SUCCEEDED).state)
        val deferred = waitForState(successor.id, WorkInfo.State.ENQUEUED)
        assertEquals(DEFER_MILLIS, deferred.initialDelayMillis)
        assertTrue(deferred.nextScheduleTimeMillis >= deadline)
        assertEquals(WorkInfo.State.ENQUEUED, workInfo(successor.id).state)
    }

    private fun verifyExternalRescheduleReplacesCurrentChain() {
        val measurementId = "external-reschedule"
        val uniqueWorkName = "sync-$measurementId"
        val current = OneTimeWorkRequestBuilder<ReplaceCurrentWorker>().build()
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            current,
        )
        assertTrue(ReplaceCurrentWorker.started.await(10, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, waitForState(current.id, WorkInfo.State.RUNNING).state)

        val now = System.currentTimeMillis()
        val deadline = now + DEFER_MILLIS
        SyncWorkScheduler(
            context = context,
            pausedUntilProvider = { deadline },
            nowEpochMillis = { now },
        ).reschedule(measurementId, deadline)

        assertEquals(WorkInfo.State.CANCELLED, waitForState(current.id, WorkInfo.State.CANCELLED).state)
        val replacement = waitForUniqueWorkCount(uniqueWorkName, 2)
            .single { it.id != current.id }
        assertEquals(WorkInfo.State.ENQUEUED, waitForState(replacement.id, WorkInfo.State.ENQUEUED).state)
        assertEquals(DEFER_MILLIS, workInfo(replacement.id).initialDelayMillis)
    }

    private fun waitForState(id: UUID, expectedState: WorkInfo.State): WorkInfo =
        waitUntil("work $id to reach $expectedState") {
            workInfo(id).takeIf { it.state == expectedState }
        }

    private fun waitForUniqueWorkCount(uniqueWorkName: String, expectedCount: Int): List<WorkInfo> =
        runBlocking {
            withTimeout(10_000L) {
                workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
                    .first { it.size == expectedCount }
            }
        }

    private fun workInfo(id: UUID): WorkInfo = runBlocking {
        withTimeout(10_000L) {
            workManager.getWorkInfoByIdFlow(id).filterNotNull().first()
        }
    }

    private fun waitForUniqueWorkToFinish(uniqueWorkName: String) {
        runBlocking {
            withTimeout(10_000L) {
                workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
                    .first { infos -> infos.all { it.state.isFinished } }
            }
        }
    }

    private fun <T> waitUntil(description: String, block: () -> T?): T {
        val timeoutAt = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < timeoutAt) {
            block()?.let { return it }
            Thread.sleep(20L)
        }
        error("Timed out waiting for $description")
    }

    private companion object {
        const val DEFER_MILLIS = 60_000L
    }
}

class SelfDeferCurrentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        started.countDown()
        release.await()
        return Result.success()
    }

    companion object {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}

class ReplaceCurrentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        started.countDown()
        release.await()
        return Result.success()
    }

    companion object {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}

class ManualRunningWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        started.countDown()
        release.await()
        return Result.success()
    }

    companion object {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}
