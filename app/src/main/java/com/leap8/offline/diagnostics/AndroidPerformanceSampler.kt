package com.leap8.offline.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Public Android APIs only: no root, thermal sysfs scraping, background service or network. */
internal class AndroidPerformanceSampler(context: Context) {
    private val context = context.applicationContext ?: context
    private val memoryManager = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun sample(elapsedMs: Long, generatedTokens: Int?): PerformanceSample {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)?.takeIf { it.isFinite() && it in -20f..85f }
        val available = runCatching {
            ActivityManager.MemoryInfo().also(memoryManager::getMemoryInfo).availMem
        }.getOrNull()
        val runtime = Runtime.getRuntime()
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { power.currentThermalStatus }.getOrNull() else null
        return PerformanceSample(elapsedMs, temperature, available,
            (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0), thermal, generatedTokens)
    }
}
