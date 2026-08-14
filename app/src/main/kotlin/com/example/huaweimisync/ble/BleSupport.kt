package com.example.huaweimisync.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

object BleSupport {
    val BODY_COMPOSITION_SERVICE_UUID: UUID =
        UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")

    fun scanFilter(address: String? = null): ScanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(BODY_COMPOSITION_SERVICE_UUID))
        .apply { if (address != null) setDeviceAddress(address) }
        .build()

    fun balancedSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .build()

    fun lowLatencySettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    fun scanner(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    fun serviceData(result: ScanResult): ByteArray? = result.scanRecord
        ?.getServiceData(ParcelUuid(BODY_COMPOSITION_SERVICE_UUID))

    fun hasScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    fun requiredBluetoothPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    fun requiredNotificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
}
