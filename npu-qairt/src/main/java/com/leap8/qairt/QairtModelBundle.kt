package com.leap8.qairt

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale

/** Asset-only probe: safe to call from the UI process; never loads a native library. */
object QairtModelBundle {
    internal const val ASSET_DIR = "models/essay"
    internal data class Bundle(
        val soc: String,
        val htpConfig: String,
        val dsp: String,
        val config: JSONObject,
        val files: List<String>,
        val checksums: Map<String, String>
    )
    private val devices = mapOf(
        "SM8750" to ("qualcomm-snapdragon-8-elite.json" to "79"),
        "SM8650" to ("qualcomm-snapdragon-8-gen3.json" to "75")
    )

    @JvmStatic
    fun isCandidate(context: Context): Boolean = runCatching { read(context); true }.getOrDefault(false)

    @JvmStatic
    fun estimatedModelBytes(context: Context): Long = read(context).files.sumOf { path ->
        context.assets.openFd("$ASSET_DIR/$path").use { it.length }
    }

    internal fun read(context: Context): Bundle {
        check(Build.VERSION.SDK_INT >= 35 && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Genie requires an ARM64 Android 15+ device" }
        val soc = Build.SOC_MODEL.trim().uppercase(Locale.ROOT).substringBefore('-')
        val device = checkNotNull(devices[soc]) { "Unsupported Genie SoC: $soc" }
        val metadata = json(context, "metadata.json")
        check(metadata.getString("soc_model").uppercase(Locale.ROOT) == soc) { "Genie model was compiled for a different SoC" }
        check(metadata.getString("qairt_version") == BuildConfig.QAIRT_VERSION) { "Genie model and packaged QAIRT SDK versions differ" }
        check(metadata.getString("prompt_format") == "chatml") { "This bridge requires a ChatML model bundle" }
        val config = json(context, "genie_config.json")
        val dialog = config.getJSONObject("dialog")
        val engine = dialog.getJSONObject("engine")
        check(engine.getJSONObject("backend").getString("type") == "QnnHtp") { "NPU bundle must use QnnHtp" }
        check(engine.getJSONObject("model").getString("type") == "binary") { "NPU bundle must use context binaries" }
        check(dialog.getJSONObject("context").getInt("size") > 0) { "Invalid Genie context size" }
        val bins = engine.getJSONObject("model").getJSONObject("binary").getJSONArray("ctx-bins")
        check(bins.length() > 0) { "Genie context binaries are missing" }
        val files = (0 until bins.length()).map { relativePath(bins.getString(it)) }
        check(files.distinct().size == files.size && files.all { it.endsWith(".bin", ignoreCase = true) }) {
            "NPU context binaries must be distinct .bin files"
        }
        val checksumsJson = metadata.getJSONObject("file_sha256")
        val checksums = (files + "tokenizer.json" + "genie_config.json").associateWith { path ->
            checksumsJson.getString(path).lowercase(Locale.ROOT).also {
                check(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $path" }
            }
        }
        (files + "tokenizer.json").forEach { path ->
            context.assets.openFd("$ASSET_DIR/$path").use { check(it.length > 0) { "Empty Genie asset: $path" } }
        }
        val htp = context.assets.open("htp_config/${device.first}").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val htpDevice = htp.getJSONArray("devices").getJSONObject(0)
        check(htpDevice.getString("dsp_arch") == "v${device.second}") { "HTP architecture does not match model target" }
        check(htpDevice.getInt("soc_model") == if (device.second == "79") 69 else 57) {
            "HTP SoC id does not match model target"
        }
        return Bundle(soc, device.first, device.second, config, files, checksums)
    }

    internal fun relativePath(value: String): String {
        val path = value.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && ':' !in path &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid relative Genie asset path" }
        return path
    }

    private fun json(context: Context, name: String): JSONObject =
        context.assets.open("$ASSET_DIR/$name").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
}
