package com.leap8.offline.ai

import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import com.leap8.offline.domain.AiProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiTextGeneration(
    val text: String,
    val elapsedMs: Long,
    val engine: String,
    val processor: AiProcessor,
    val generatedTokens: Int = 0
)

class ApiChatService {
    fun generate(
        config: AiProviderConfig,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        onProgress: (AiGenerationProgress) -> Unit
    ): AiTextGeneration {
        require(config.provider != AiProvider.LOCAL)
        require(apiKey.isNotBlank()) { "${config.provider.label} 尚未配置 API Key" }
        val started = System.nanoTime()
        onProgress(AiGenerationProgress(true, .08f, "正在建立加密连接", maxTokens = maxTokens, processor = AiProcessor.CLOUD))
        val url = URL("${config.endpoint.trimEnd('/')}/chat/completions")
        require(url.protocol.equals("https", true)) { "仅允许 HTTPS API 地址" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val payload = JSONObject()
                .put("model", config.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)))
            if (config.provider == AiProvider.OPENAI) {
                payload.put("max_completion_tokens", maxTokens)
            } else {
                payload.put("max_tokens", maxTokens).put("temperature", temperature.toDouble())
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            onProgress(AiGenerationProgress(true, .25f, "云端模型正在思考", maxTokens = maxTokens, processor = AiProcessor.CLOUD))
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(config.provider, status, response))
            val json = JSONObject(response)
            val text = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .optString("content").trim()
            require(text.isNotEmpty()) { "${config.provider.label} 返回了空内容" }
            val tokens = json.optJSONObject("usage")?.optInt("completion_tokens", 0) ?: 0
            onProgress(AiGenerationProgress(true, .98f, "正在整理结果", tokens, maxTokens, text, AiProcessor.CLOUD))
            return AiTextGeneration(
                text = text,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                engine = "${config.provider.label} · ${config.model}",
                processor = AiProcessor.CLOUD,
                generatedTokens = tokens
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(provider: AiProvider, status: Int, response: String): String {
        val message = runCatching {
            val error = JSONObject(response).opt("error")
            when (error) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> ""
            }
        }.getOrDefault("").replace(Regex("(?i)(bearer\\s+|sk-)[A-Za-z0-9._-]+"), "[已隐藏]")
        return "${provider.label} 请求失败（HTTP $status）${if (message.isBlank()) "" else "：${message.take(240)}"}"
    }

    private companion object { const val MAX_RESPONSE_CHARS = 1_000_000 }
}
