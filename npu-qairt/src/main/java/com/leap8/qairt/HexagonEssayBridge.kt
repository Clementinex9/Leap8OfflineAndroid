package com.leap8.qairt

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.function.Consumer

/** Metadata-only UI probe. It never loads or executes a native library. */
object HexagonModelBundle {
    internal const val ASSETS = "npu-runtime/arm64-v8a"
    // Dependency order matters: libggml/libllama have DT_NEEDED entries for every bundled backend.
    internal val libraries = listOf("libggml-base.so", "libggml-cpu.so", "libggml-rpc.so", "libggml-opencl.so", "libggml-hexagon.so", "libggml.so", "libllama.so", "libggml-htp-v79.so")

    @JvmStatic
    fun isCandidate(context: Context): Boolean = runCatching {
        check(Build.VERSION.SDK_INT >= 35 && Build.SOC_MODEL.uppercase().startsWith("SM8750") && "arm64-v8a" in Build.SUPPORTED_ABIS)
        val manifest = manifest(context)
        check(manifest.getString("release") == "b11982" && manifest.getString("dsp_arch") == "v79")
        libraries.forEach { file ->
            check(manifest.getJSONObject("file_sha256").getString(file).matches(Regex("[0-9a-f]{64}")))
            context.assets.openFd("$ASSETS/$file").use { check(it.length > 0) }
        }
        true
    }.getOrDefault(false)

    internal fun manifest(context: Context): JSONObject = context.assets.open("$ASSETS/runtime-manifest.json")
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
}

/** b11982 lives only in :leap8_npu. CPU/GPU workers retain their independent b10333 ABI. */
object HexagonEssayBridge {
    private var libraryDirectory: File? = null

    @JvmStatic fun isAvailable(context: Context): Boolean = HexagonModelBundle.isCandidate(context)

    @JvmStatic
    @Synchronized
    fun generate(context: Context, modelPath: String, systemPrompt: String, userPrompt: String, maxTokens: Int, tokenConsumer: Consumer<String>?): String {
        check(isAvailable(context)) { "Matched SM8750 Hexagon v79 runtime is unavailable" }
        val modelsRoot = File(context.filesDir, "models").canonicalFile
        val model = File(modelPath).canonicalFile
        require(model.parentFile == modelsRoot && model.isFile && model.canRead()) { "Invalid app-private NPU GGUF model path" }
        val directory = ensureNative(context)
        val prompt = "<|im_start|>system\n${systemPrompt.replace('\u0000', '\uFFFD').trim()}<|im_end|>\n<|im_start|>user\n${userPrompt.replace('\u0000', '\uFFFD').trim()}<|im_end|>\n<|im_start|>assistant\n"
        // Close Qwen's thinking block explicitly: scoring needs bounded structured output,
        // not a reasoning stream that exhausts the budget before the JSON answer.
        val scoringPrompt = prompt + "<think>\n\n</think>\n\n"
        val stream = tokenConsumer?.let { Utf8TokenStream(it) }
        val result = nativeGenerate(directory.absolutePath.toByteArray(Charsets.UTF_8), model.absolutePath.toByteArray(Charsets.UTF_8), scoringPrompt.toByteArray(Charsets.UTF_8), maxTokens.coerceIn(1, 1536), stream?.consumer)
        stream?.finish()
        return result.toString(Charsets.UTF_8).trim()
    }

    @JvmStatic fun runtimeInfo(): String = "SM8750 · llama.cpp b11982 · Hexagon v79 HTP0 NPU + ARM sampling"

    /** Version, prompt tokens, non-EOG output tokens, prompt wall ms, decode-loop wall ms. */
    @JvmStatic @Synchronized fun lastUsage(): LongArray? =
        if (libraryDirectory == null) null else nativeLastUsage()

    @JvmStatic @Synchronized fun close() { if (libraryDirectory != null) nativeClose() }

    private fun ensureNative(context: Context): File {
        libraryDirectory?.let { return it }
        val update = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val directory = File(context.noBackupFilesDir, "hexagon-runtime/b11982-$update")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create isolated NPU runtime directory" }
        val manifest = HexagonModelBundle.manifest(context).getJSONObject("file_sha256")
        HexagonModelBundle.libraries.forEach { name ->
            val output = File(directory, name)
            val expected = manifest.getString(name)
            if (!output.isFile || sha256(output) != expected) {
                val partial = File(directory, "$name.part")
                context.assets.open("${HexagonModelBundle.ASSETS}/$name").use { input ->
                    FileOutputStream(partial).use { target -> input.copyTo(target, 1024 * 1024); target.fd.sync() }
                }
                check(sha256(partial) == expected) { "Official Hexagon runtime checksum mismatch: $name" }
                check(partial.setReadOnly()) { "Cannot protect native runtime file" }
                if (output.exists()) check(output.delete()) { "Cannot replace old native runtime file" }
                check(partial.renameTo(output)) { "Cannot install native runtime file" }
            }
        }
        // Do not add /vendor/lib64 to LD_LIBRARY_PATH: it can interpose incompatible system libs.
        Os.setenv("ADSP_LIBRARY_PATH", "${directory.absolutePath};/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp", true)
        HexagonModelBundle.libraries.filterNot { it.contains("-htp-") }
            .forEach { System.load(File(directory, it).absolutePath) }
        System.loadLibrary("leap8_hexagon_jni")
        libraryDirectory = directory
        return directory
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private external fun nativeGenerate(directory: ByteArray, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, consumer: Consumer<ByteArray>?): ByteArray
    private external fun nativeClose()
    private external fun nativeLastUsage(): LongArray?
}
