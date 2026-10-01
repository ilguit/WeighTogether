package com.palixander.weightogether.ble

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.R
import com.palixander.weightogether.data.ProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RecordingLeScanner::class])
class ReliabilityScanServiceLifecycleTest {
    private lateinit var context: Application
    private lateinit var adapter: BluetoothAdapter
    private lateinit var platform: RecordingLeScanner
    private lateinit var service: ReliabilityScanService
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        platform = Shadow.extract(adapter.bluetoothLeScanner)
        store = ProfileStore(context)
        store.saveScale("02:00:00:00:00:01", "Test scale")
        store.setReliabilityMode(true)
        service = Robolectric.buildService(ReliabilityScanService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun createPostsOngoingLowPriorityForegroundNotificationOnScanningChannel() {
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertEquals(181, shadow.lastForegroundNotificationId)
        assertEquals("scale_scanning", notification.channelId)
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(context.getString(R.string.app_name), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(
            context.getString(R.string.notification_reliability_scan_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.PRIORITY_LOW, notification.priority)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("scale_scanning")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(context.getString(R.string.notification_channel_scale_scanning), channel.name)
        assertTrue(platform.events.isEmpty())
        assertNull(service.onBind(null))
    }

    @Test
    fun startRegistersSelectedScaleBodyCompositionFilterWithLowLatencyAndReturnsSticky() {
        assertEquals(Service.START_STICKY, start())
        val scan = platform.activeScans.single()
        assertEquals("02:00:00:00:00:01", scan.scanFilters().single().deviceAddress)
        assertEquals(
            UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb"),
            scan.scanFilters().single().serviceUuid?.uuid,
        )
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, scan.scanSettings()?.scanMode)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun restartStopsOldCallbackBeforeRegisteringNewCallbackWithCurrentAddress() {
        start()
        val old = platform.scanCallbacks.single()
        platform.duringStop = { assertSame(old, it) }
        store.saveScale("02:00:00:00:00:02", "Another test scale")

        assertEquals(Service.START_STICKY, start())

        assertEquals(listOf("start", "stop", "start"), platform.events)
        assertNotSame(old, platform.scanCallbacks.single())
        assertEquals("02:00:00:00:00:02", platform.activeScans.single().scanFilters().single().deviceAddress)
        platform.duringStop = null
    }

    @Test
    fun destroyStopsRegisteredCallbackAndClearsItBeforeAnyFurtherCleanup() {
        start()
        val active = platform.scanCallbacks.single()
        platform.duringStop = { assertSame(active, it) }

        service.onDestroy()
        // A second cleanup probes callback clearing; Android normally destroys a service only once.
        service.onDestroy()

        assertEquals(listOf("start", "stop"), platform.events)
        assertTrue(platform.activeScans.isEmpty())
    }

    @Test
    fun deniedPermissionLeavesStickyServiceWithoutScannerRegistration() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)

        assertEquals(Service.START_STICKY, start())
        assertTrue(platform.events.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun permissionRevokedAtDestroySkipsPlatformStopAndClearsCallback() {
        start()
        val active = platform.scanCallbacks.single()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        service.onDestroy()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        service.onDestroy()

        assertEquals(listOf("start"), platform.events)
        // The shadow retains registration: this is not a device cleanup or callback-delivery guarantee.
        assertSame(active, platform.scanCallbacks.single())
    }

    @Test
    fun permissionRevokedAtRestartSkipsStopAndStartAndClearsCallback() {
        start()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)

        assertEquals(Service.START_STICKY, start())
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        service.onDestroy()

        assertEquals(listOf("start"), platform.events)
    }

    @Test
    fun disabledBluetoothLeavesStickyServiceWithoutScannerRegistration() {
        disableBluetooth()

        assertEquals(Service.START_STICKY, start())
        assertTrue(platform.events.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun bluetoothDisabledAtDestroySkipsPlatformStopAndClearsCallback() {
        start()
        disableBluetooth()
        service.onDestroy()
        shadowOf(adapter).setEnabled(true)
        service.onDestroy()

        assertEquals(listOf("start"), platform.events)
    }

    @Test
    fun missingScaleStopsSelfWithoutRegistrationButStillReturnsSticky() {
        store.forgetScale()
        store.setReliabilityMode(true)

        assertEquals(Service.START_STICKY, start())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(platform.events.isEmpty())
    }

    @Test
    fun disabledReliabilityModeReturnsNotStickyAndDefersActiveScannerStopUntilDestroy() {
        start()
        val active = platform.scanCallbacks.single()
        store.setReliabilityMode(false)

        assertEquals(Service.START_NOT_STICKY, start())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(listOf("start"), platform.events)
        assertSame(active, platform.scanCallbacks.single())

        service.onDestroy()
        assertEquals(listOf("start", "stop"), platform.events)
        assertTrue(platform.activeScans.isEmpty())
    }

    @Test
    fun platformStopExceptionAtDestroyIsSuppressedAndCallbackIsCleared() {
        start()
        platform.duringStop = { throw IllegalStateException("Platform stop failed") }

        service.onDestroy()
        service.onDestroy()

        assertEquals(listOf("start", "stop"), platform.events)
    }

    @Test
    fun platformStopExceptionAtRestartDoesNotPreventNewRegistration() {
        start()
        val old = platform.scanCallbacks.single()
        platform.duringStop = { throw IllegalStateException("Platform stop failed") }

        assertEquals(Service.START_STICKY, start())
        assertEquals(listOf("start", "stop", "start"), platform.events)
        // Failed stop remains recorded by the shadow, alongside the new registration.
        val current = platform.scanCallbacks.single { it !== old }
        platform.duringStop = { assertSame(current, it) }
        service.onDestroy()
        service.onDestroy()
        assertEquals(listOf("start", "stop", "start", "stop"), platform.events)
        assertSame(old, platform.scanCallbacks.single())
    }

    private fun start(): Int = service.onStartCommand(null, 0, 1)

    private fun disableBluetooth() {
        shadowOf(adapter).setEnabled(false)
        shadowOf(adapter).setBleScanAlwaysAvailable(false)
        assertNull(adapter.bluetoothLeScanner)
    }
}
