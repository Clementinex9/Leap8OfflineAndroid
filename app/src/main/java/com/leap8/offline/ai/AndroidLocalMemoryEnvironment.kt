package com.leap8.offline.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Looper
import com.leap8.offline.speech.SpeechMemoryCoordinator

internal class AndroidLocalMemoryEnvironment(context: Context) : LocalMemoryEnvironment {
    private val manager = context.applicationContext.getSystemService(ActivityManager::class.java)

    override fun snapshot(): LocalMemorySnapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "内存保护不能阻塞界面线程" }
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return LocalMemorySnapshot(info.availMem, info.lowMemory, info.threshold)
    }

    override fun pauseReclaimableResources(): AutoCloseable = SpeechMemoryCoordinator.acquirePause()

    override fun requestGarbageCollection() {
        // A best-effort hint ONLY, off the main thread; native voice unloading is the real cleanup.
        Runtime.getRuntime().gc()
    }

    override fun awaitReclaim() = Thread.sleep(250)
}
