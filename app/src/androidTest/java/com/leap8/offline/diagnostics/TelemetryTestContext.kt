package com.leap8.offline.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

/**
 * Instrumentation executes as the target UID and cannot write the test APK's private directory.
 * Use a unique cache sandbox plus unique preferences, NEVER the production telemetry path/name.
 * Small sandbox files are intentionally left for Android cache eviction; no recursive cleanup.
 */
internal class TelemetryTestContext(base: Context) : ContextWrapper(base) {
    private val namespace = UUID.randomUUID().toString()
    private val sandbox = File(base.cacheDir, "_developer-tests/$namespace").canonicalFile
    private val noBackup = File(sandbox, "noBackup")

    init {
        val cache = base.cacheDir.canonicalFile
        check(sandbox.path.startsWith(cache.path + File.separator))
        check(noBackup.canonicalFile != base.noBackupFilesDir.canonicalFile)
    }

    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = noBackup
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("_developer_tests_${namespace}_$name", mode)
}
