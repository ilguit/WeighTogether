package com.palixander.weightogether.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RecordingLeScanner::class])
class ManualScaleScannerTest {
    private lateinit var context: Application
    private lateinit var adapter: BluetoothAdapter
    private lateinit var platform: RecordingLeScanner
    private lateinit var scanner: ManualScaleScanner
    private val results = mutableListOf<ScanResult>()
    private val errors = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        platform = Shadow.extract(adapter.bluetoothLeScanner)
        scanner = ManualScaleScanner(context)
    }

    @Test
    fun startRegistersBodyCompositionFilterAndLowLatencyWithOptionalAddress() {
        start()
        val discovery = platform.activeScans.single()
        assertEquals(
            UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb"),
            discovery.scanFilters().single().serviceUuid?.uuid,
        )
        assertNull(discovery.scanFilters().single().deviceAddress)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, discovery.scanSettings()?.scanMode)

        assertTrue(scanner.start("02:00:00:00:00:01", results::add, errors::add).isSuccess)
        val selected = platform.activeScans.single()
        assertEquals(discovery.scanFilters().single().serviceUuid, selected.scanFilters().single().serviceUuid)
        assertEquals("02:00:00:00:00:01", selected.scanFilters().single().deviceAddress)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, selected.scanSettings()?.scanMode)
    }

    @Test
    fun currentCallbackDeliversSingleBatchInOrderAndLocalizedError() {
        val callback = start()
        val first = result(1)
        val second = result(2)
        val third = result(3)

        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, first)
        callback.onBatchScanResults(mutableListOf(second, third))
        callback.onBatchScanResults(mutableListOf())
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        assertEquals(listOf(first, second, third), results)
        assertEquals(
            listOf(context.getString(R.string.error_bluetooth_scan, ScanCallback.SCAN_FAILED_INTERNAL_ERROR)),
            errors,
        )
        // Android failure reporting currently keeps the callback active.
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, first)
        assertEquals(listOf(first, second, third, first), results)
    }

    @Test
    fun stopInvalidatesCallbackBeforePlatformStopAndIsIdempotent() {
        val callback = start()
        platform.duringStop = { emitAll(it) }

        scanner.stop()
        scanner.stop()
        emitAll(callback)

        assertEquals(listOf("start", "stop"), platform.events)
        assertTrue(platform.activeScans.isEmpty())
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun restartStopsOldRegistrationBeforeStartingNewAndRejectsOldEvents() {
        val old = start()
        platform.duringStop = { emitAll(it) }
        val current = start()
        assertNotSame(old, current)
        assertEquals(listOf("start", "stop", "start"), platform.events)
        assertEquals(setOf(current), platform.scanCallbacks)

        emitAll(old)
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        val reading = result(4)
        current.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, reading)
        assertEquals(listOf(reading), results)
    }

    @Test
    fun deniedPermissionReturnsExistingMessageWithoutRegistering() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val outcome = scanner.start(onResult = results::add, onError = errors::add)

        assertTrue(outcome.isFailure)
        assertEquals(context.getString(R.string.error_bluetooth_scan_permission), outcome.exceptionOrNull()?.message)
        assertTrue(platform.events.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun deniedRestartLeavesPreviousCallbackActiveBecausePermissionIsCheckedBeforeStop() {
        val old = start()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)

        assertTrue(scanner.start(onResult = results::add, onError = errors::add).isFailure)
        assertEquals(listOf("start"), platform.events)
        assertSame(old, platform.scanCallbacks.single())
        val reading = result(5)
        old.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, reading)
        assertEquals(listOf(reading), results)
    }

    @Test
    fun revokedPermissionAtStopInvalidatesCallbackWithoutCallingPlatformEvenAfterPermissionReturns() {
        val callback = start()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        scanner.stop()
        emitAll(callback)
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        scanner.stop()

        assertEquals(listOf("start"), platform.events)
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        // This describes the existing permission-revocation behavior, not a device cleanup guarantee.
        assertSame(callback, platform.scanCallbacks.single())
    }

    @Test
    fun disabledBluetoothReturnsExistingMessageWithoutRegistering() {
        shadowOf(adapter).setEnabled(false)
        shadowOf(adapter).setBleScanAlwaysAvailable(false)
        assertNull(adapter.bluetoothLeScanner)

        val outcome = scanner.start(onResult = results::add, onError = errors::add)

        assertTrue(outcome.isFailure)
        assertEquals(context.getString(R.string.error_bluetooth_disabled), outcome.exceptionOrNull()?.message)
        assertTrue(platform.events.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun batchAlreadyBeingDeliveredContinuesAfterConsumerStopsScanner() {
        val first = result(6)
        val second = result(7)
        assertTrue(scanner.start(onResult = {
            results += it
            scanner.stop()
        }, onError = errors::add).isSuccess)
        val callback = platform.scanCallbacks.single()

        callback.onBatchScanResults(mutableListOf(first, second))
        emitAll(callback)

        assertEquals(listOf(first, second), results)
        assertTrue(errors.isEmpty())
        assertEquals(listOf("start", "stop"), platform.events)
    }

    @Test
    fun platformStopExceptionIsSuppressedAndOldCallbackRemainsInvalid() {
        val callback = start()
        platform.duringStop = { throw IllegalStateException("Platform stop failed") }

        scanner.stop()
        scanner.stop()
        emitAll(callback)

        assertEquals(listOf("start", "stop"), platform.events)
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
    }

    private fun start(): ScanCallback {
        val outcome = scanner.start(onResult = results::add, onError = errors::add)
        assertFalse(outcome.exceptionOrNull()?.toString(), outcome.isFailure)
        return platform.scanCallbacks.single()
    }

    private fun result(timestamp: Long): ScanResult = ScanResult(
        adapter.getRemoteDevice("02:00:00:00:00:01"), null, -50, timestamp,
    )

    private fun emitAll(callback: ScanCallback) {
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(10))
        callback.onBatchScanResults(mutableListOf(result(11)))
        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
    }
}

/** Records the Android boundary while retaining Robolectric's registration bookkeeping. */
@Implements(BluetoothLeScanner::class)
class RecordingLeScanner : ShadowBluetoothLeScanner() {
    val events = mutableListOf<String>()
    var duringStop: ((ScanCallback) -> Unit)? = null

    @Implementation
    public override fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback) {
        events += "start"
        super.startScan(filters, settings, callback)
    }

    @Implementation
    public override fun stopScan(callback: ScanCallback) {
        events += "stop"
        duringStop?.invoke(callback)
        super.stopScan(callback)
    }
}
