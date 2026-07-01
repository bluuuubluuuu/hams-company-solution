package com.klk.hams.ui.count

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryMonitor(private val context: Context) {

    private val _batteryPct = MutableStateFlow(readCurrentPct())
    val batteryPctFlow: StateFlow<Int> = _batteryPct.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            _batteryPct.value = intent.toPct()
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun currentPct(): Int = _batteryPct.value

    private fun readCurrentPct(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.toPct() ?: 0
    }

    private fun Intent.toPct(): Int {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    }
}
