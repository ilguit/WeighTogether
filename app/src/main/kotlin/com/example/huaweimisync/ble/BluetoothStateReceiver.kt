package com.example.huaweimisync.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.huaweimisync.data.ProfileStore

class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        if (state != BluetoothAdapter.STATE_ON) return

        val settings = ProfileStore(context).settings.value
        if (settings.scaleAddress != null) BackgroundScanRegistrar.register(context)
        if (settings.reliabilityMode) {
            runCatching { ReliabilityScanService.setEnabled(context, true) }
        }
    }
}
