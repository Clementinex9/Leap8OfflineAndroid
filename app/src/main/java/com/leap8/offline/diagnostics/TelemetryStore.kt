package com.leap8.offline.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID

/** Metadata only. Construct anywhere; callers must perform load/save on their IO executor. */
internal class TelemetryStore(context: Context) {
    private val atomic = AtomicFile(File(context.noBackupFilesDir, "diagnostics/performance-v1.json"))

    @Synchronized fun load(): List<InvocationRecord> {
        val input = try { atomic.openRead() } catch (_: FileNotFoundException) {
            if (!atomic.baseFile.exists()) return emptyList()
            throw IllegalStateException("诊断记录不可读取")
        }
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(output.size() + count <= TelemetryCodec.MAX_BYTES) { "诊断记录超出大小限制" }
                output.write(buffer, 0, count)
            }
            TelemetryCodec.decode(output.toString(Charsets.UTF_8.name()))
        }
    }

    @Synchronized fun save(records: List<InvocationRecord>) {
        val bytes = TelemetryCodec.encode(records).toByteArray(Charsets.UTF_8)
        check(bytes.size <= TelemetryCodec.MAX_BYTES) { "诊断记录超出大小限制" }
        val parent = checkNotNull(atomic.baseFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建诊断记录目录" }
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            stream?.let { atomic.failWrite(it) }
            throw failure
        }
    }
}

/** Pure JSON codec: no prompts, responses, endpoints, credentials or free-form errors. */
internal object TelemetryCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_RECORDS = 100
    private const val MAX_SAMPLES = 120
    private const val MAX_ELAPSED_MS = 24L * 60 * 60 * 1000
    private const val MAX_TIMESTAMP_MS = 4_102_444_800_000L // 2100-01-01, not an arbitrary string.
    private const val MAX_MEMORY_BYTES = 1024L * 1024 * 1024 * 1024
    private const val MAX_TOKENS = 10_000_000L
    private val sources = setOf("LOCAL_TOKENIZER", "API_COMPLETION_USAGE", "UNAVAILABLE")
    private val sensitive = Regex("(?i)(bearer|sk-|api[_-]?key|https?://|(?:token|key)=)")

    fun encode(records: List<InvocationRecord>): String {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { record ->
            // The schema whitelist below is intentionally explicit; never serialize data classes
            // reflectively, because future fields might contain the learner's actual text.
            val json = JSONObject()
                .put("id", record.id)
                .put("startedAtMs", record.startedAtMs)
                .put("mode", record.mode.name)
                .put("provider", record.provider.name)
                .put("processor", record.processor?.name)
                .put("model", safeModel(record.model))
                .put("outcome", record.outcome.name)
                .put("elapsedMs", record.elapsedMs)
                .put("tokenSource", safeSource(record.tokenSource))
            record.samplingTemperature?.takeIf { it.isFinite() && it in 0f..2f }
                ?.let { json.put("samplingTemperature", it.toDouble()) }
            putCount(json, "inputTokens", record.inputTokens)
            putCount(json, "outputTokens", record.outputTokens)
            record.decodeMs?.takeIf { it in 0..MAX_ELAPSED_MS }?.let { json.put("decodeMs", it) }
            record.firstProgressMs?.takeIf { it in 0..MAX_ELAPSED_MS }?.let { json.put("firstProgressMs", it) }
            json.put("route", JSONArray(record.route.distinct().take(AiProcessor.entries.size).map { it.name }))
            val samples = JSONArray()
            compactSamples(record.samples, MAX_SAMPLES).forEach sampleLoop@ { sample ->
                if (sample.elapsedMs !in 0..MAX_ELAPSED_MS) return@sampleLoop
                val item = JSONObject().put("elapsedMs", sample.elapsedMs)
                sample.batteryTempC?.takeIf { it.isFinite() && it in -40f..100f }
                    ?.let { item.put("batteryTempC", it.toDouble()) }
                sample.availableMemoryBytes?.takeIf { it in 0..MAX_MEMORY_BYTES }
                    ?.let { item.put("availableMemoryBytes", it) }
                sample.javaHeapBytes?.takeIf { it in 0..MAX_MEMORY_BYTES }?.let { item.put("javaHeapBytes", it) }
                sample.thermalStatus?.takeIf { it in 0..6 }?.let { item.put("thermalStatus", it) }
                putCount(item, "generatedTokens", sample.generatedTokens)
                samples.put(item)
            }
            json.put("samples", samples)
            // Apply the same validation before writing as on restore, preserving RUNNING here.
            decodeRecord(json, restoring = false)?.let { array.put(json) }
        }
        return JSONObject().put("version", 1).put("records", array).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "诊断记录超出大小限制" }
        }
    }

    fun decode(text: String): List<InvocationRecord> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "诊断记录超出大小限制" }
        val root = try { JSONObject(text) } catch (_: Exception) {
            throw IllegalArgumentException("诊断记录格式无效")
        }
        require(root.number("version", 1) == 1L) { "诊断记录版本不受支持" }
        val array = root.optJSONArray("records") ?: throw IllegalArgumentException("诊断记录格式无效")
        return (0 until minOf(array.length(), MAX_RECORDS)).mapNotNull { index ->
            array.optJSONObject(index)?.let { decodeRecord(it, restoring = true) }
        }.distinctBy { it.id }
    }

    private fun decodeRecord(json: JSONObject, restoring: Boolean): InvocationRecord? {
        val id = (json.opt("id") as? String)?.let { value ->
            runCatching { UUID.fromString(value).toString() }.getOrNull()?.takeIf { it == value.lowercase() }
        } ?: return null
        val started = json.number("startedAtMs", MAX_TIMESTAMP_MS) ?: return null
        val mode = enumValue<InvocationMode>(json.opt("mode")) ?: return null
        val provider = enumValue<AiProvider>(json.opt("provider")) ?: return null
        val recordedOutcome = enumValue<InvocationOutcome>(json.opt("outcome")) ?: return null
        // A killed process cannot confirm whether a saved progress counter was final. Keep
        // observed samples for the chart but never aggregate their counts as completed usage.
        val incomplete = restoring && recordedOutcome in setOf(InvocationOutcome.RUNNING, InvocationOutcome.INTERRUPTED)
        val elapsed = json.number("elapsedMs", MAX_ELAPSED_MS) ?: return null
        val samples = json.optJSONArray("samples")
        val readSamples = if (samples == null) emptyList() else (0 until minOf(samples.length(), 1000)).mapNotNull { index ->
            val sample = samples.optJSONObject(index) ?: return@mapNotNull null
            val offset = sample.number("elapsedMs", MAX_ELAPSED_MS) ?: return@mapNotNull null
            PerformanceSample(
                elapsedMs = offset,
                batteryTempC = sample.finiteFloat("batteryTempC", -40f, 100f),
                availableMemoryBytes = sample.number("availableMemoryBytes", MAX_MEMORY_BYTES),
                javaHeapBytes = sample.number("javaHeapBytes", MAX_MEMORY_BYTES),
                thermalStatus = sample.number("thermalStatus", 6)?.toInt(),
                generatedTokens = sample.number("generatedTokens", MAX_TOKENS)?.toInt()
            )
        }.sortedBy { it.elapsedMs }.let { compactSamples(it, MAX_SAMPLES) }
        val routeJson = json.optJSONArray("route")
        val route = if (routeJson == null) emptyList() else (0 until minOf(routeJson.length(), 16))
            .mapNotNull { enumValue<AiProcessor>(routeJson.opt(it)) }.distinct()
        return InvocationRecord(
            id = id,
            startedAtMs = started,
            mode = mode,
            provider = provider,
            processor = enumValue<AiProcessor>(json.opt("processor")),
            model = safeModel((json.opt("model") as? String).orEmpty()),
            samplingTemperature = json.finiteFloat("samplingTemperature", 0f, 2f),
            outcome = if (restoring && recordedOutcome == InvocationOutcome.RUNNING) InvocationOutcome.INTERRUPTED else recordedOutcome,
            elapsedMs = elapsed,
            inputTokens = if (incomplete) null else json.number("inputTokens", MAX_TOKENS)?.toInt(),
            outputTokens = if (incomplete) null else json.number("outputTokens", MAX_TOKENS)?.toInt(),
            decodeMs = if (incomplete) null else json.number("decodeMs", MAX_ELAPSED_MS),
            firstProgressMs = json.number("firstProgressMs", MAX_ELAPSED_MS),
            tokenSource = if (incomplete) "UNAVAILABLE" else safeSource((json.opt("tokenSource") as? String).orEmpty()),
            route = route,
            samples = readSamples
        )
    }

    private fun safeModel(value: String): String {
        if (sensitive.containsMatchIn(value)) return ""
        return value.take(120).filter { it.isLetterOrDigit() || it in " ._-/+():@" }.trim()
    }
    private fun safeSource(value: String) = value.takeIf { it in sources } ?: "UNAVAILABLE"
    private fun putCount(json: JSONObject, key: String, value: Int?) {
        value?.takeIf { it.toLong() in 0..MAX_TOKENS }?.let { json.put(key, it) }
    }
    private fun JSONObject.number(key: String, maximum: Long): Long? {
        val number = opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value < 0 || value > maximum.toDouble() || value % 1.0 != 0.0) return null
        return value.toLong()
    }
    private fun JSONObject.finiteFloat(key: String, minimum: Float, maximum: Float): Float? =
        (opt(key) as? Number)?.toFloat()?.takeIf { it.isFinite() && it in minimum..maximum }
    private inline fun <reified T : Enum<T>> enumValue(value: Any?): T? =
        (value as? String)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
