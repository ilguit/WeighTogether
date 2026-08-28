package com.palixander.scalesync.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundScanRegistrar.register(context)
            if (com.palixander.scalesync.data.ProfileStore(context).settings.value.reliabilityMode) {
                runCatching { ReliabilityScanService.setEnabled(context, true) }
            }
        }
    }
}
