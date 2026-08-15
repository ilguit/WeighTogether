package com.example.huaweimisync.sync

import com.huawei.hihealth.error.HiHealthError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiWritePermissionCheckerTest {
    @Test
    fun `adapter requests write weight and maps granted write callback`() = runBlocking {
        val api = FakeApi { callback ->
            callback.onResult(
                HiHealthError.SUCCESS,
                "success",
                intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION),
                intArrayOf(),
            )
        }

        val result = HuaweiWritePermissionChecker(api).check()

        assertArrayEquals(intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION), api.requestedWriteTypes)
        assertArrayEquals(intArrayOf(), api.requestedReadTypes)
        assertEquals(HuaweiPermissionCheckResult.AUTHORIZED, result)
    }

    @Test
    fun `write permission must be in third callback array not read array`() = runBlocking {
        val api = FakeApi { callback ->
            callback.onResult(
                HiHealthError.SUCCESS,
                "success",
                intArrayOf(),
                intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION),
            )
        }

        assertEquals(
            HuaweiPermissionCheckResult.NOT_AUTHORIZED,
            HuaweiWritePermissionChecker(api).check(),
        )
    }

    @Test
    fun `missing write type is denied while ambiguous success is check failure`() = runBlocking {
        val denied = FakeApi { callback ->
            callback.onResult(HiHealthError.SUCCESS, "success", intArrayOf(42), intArrayOf())
        }
        val ambiguous = FakeApi { callback ->
            callback.onResult(HiHealthError.SUCCESS, "success", null, null)
        }

        assertEquals(
            HuaweiPermissionCheckResult.NOT_AUTHORIZED,
            HuaweiWritePermissionChecker(denied).check(),
        )
        assertEquals(
            HuaweiPermissionCheckResult.CHECK_FAILED,
            HuaweiWritePermissionChecker(ambiguous).check(),
        )
    }

    @Test
    fun `SDK error and synchronous exception are check failures`() = runBlocking {
        val callbackError = FakeApi { callback ->
            callback.onResult(HiHealthError.ERR_NETWORK, "network", null, null)
        }
        val throwing = HuaweiDataAuthStatusApi { _, _, _ -> error("binder failed") }

        assertEquals(
            HuaweiPermissionCheckResult.CHECK_FAILED,
            HuaweiWritePermissionChecker(callbackError).check(),
        )
        assertEquals(
            HuaweiPermissionCheckResult.CHECK_FAILED,
            HuaweiWritePermissionChecker(throwing).check(),
        )
    }

    @Test
    fun `only first SDK callback resumes suspended check`() = runBlocking {
        val api = FakeApi { callback ->
            callback.onResult(
                HiHealthError.SUCCESS,
                "success",
                intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION),
                intArrayOf(),
            )
            callback.onResult(HiHealthError.ERR_NETWORK, "late", null, null)
        }

        assertEquals(
            HuaweiPermissionCheckResult.AUTHORIZED,
            HuaweiWritePermissionChecker(api).check(),
        )
    }

    @Test
    fun `late SDK callback after cancellation is ignored`() = runBlocking {
        lateinit var callback: HuaweiDataAuthStatusCallback
        val api = HuaweiDataAuthStatusApi { _, _, value -> callback = value }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            HuaweiWritePermissionChecker(api).check()
        }

        job.cancelAndJoin()
        callback.onResult(
            HiHealthError.SUCCESS,
            "late",
            intArrayOf(REQUIRED_WRITE_WEIGHT_PERMISSION),
            intArrayOf(),
        )

        assertTrue(job.isCancelled)
    }

    @Test
    fun `authorization adapter ignores duplicate and late callbacks`() = runBlocking {
        lateinit var complete: (SyncResult) -> Unit
        val result = awaitSingleHuaweiResult(
            synchronousFailure = SyncResult.Retryable("sync failure"),
        ) { callback ->
            complete = callback
            callback(SyncResult.Success)
            callback(SyncResult.Blocked("duplicate"))
        }

        complete(SyncResult.Blocked("late"))

        assertEquals(SyncResult.Success, result)
    }

    @Test
    fun `authorization adapter maps synchronous exception and ignores callback after cancellation`() =
        runBlocking {
            val expectedFailure = SyncResult.Retryable("sync failure")
            assertEquals(
                expectedFailure,
                awaitSingleHuaweiResult(expectedFailure) { error("binder failed") },
            )

            lateinit var complete: (SyncResult) -> Unit
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                awaitSingleHuaweiResult(expectedFailure) { complete = it }
            }
            job.cancelAndJoin()
            complete(SyncResult.Success)

            assertTrue(job.isCancelled)
        }

    private class FakeApi(
        private val response: (HuaweiDataAuthStatusCallback) -> Unit,
    ) : HuaweiDataAuthStatusApi {
        lateinit var requestedWriteTypes: IntArray
        lateinit var requestedReadTypes: IntArray

        override fun getDataAuthStatusEx(
            requestedWriteTypes: IntArray,
            requestedReadTypes: IntArray,
            callback: HuaweiDataAuthStatusCallback,
        ) {
            this.requestedWriteTypes = requestedWriteTypes
            this.requestedReadTypes = requestedReadTypes
            response(callback)
        }
    }
}
