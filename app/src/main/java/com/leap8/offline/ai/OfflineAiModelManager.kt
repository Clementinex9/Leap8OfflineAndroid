package com.leap8.offline.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.leap8.offline.domain.AiModelPhase
import com.leap8.offline.domain.AiModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class OfflineAiModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val modelsDirectory = File(appContext.filesDir, "models").apply { mkdirs() }
    private val importMutex = Mutex()

    private val _state = MutableStateFlow(readStoredState())
    val state: StateFlow<AiModelState> = _state.asStateFlow()

    fun modelFile(): File? {
        val path = preferences.getString(KEY_PATH, null) ?: return null
        return runCatching {
            File(path).canonicalFile.takeIf {
                it.parentFile == modelsDirectory.canonicalFile &&
                    it.isFile && it.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES && hasGgufMagic(it)
            }
        }.getOrNull()
    }

    suspend fun importFromUri(uri: Uri): Result<AiModelState> = withContext(Dispatchers.IO) {
        importMutex.withLock {
        runCatching {
            val metadata = queryMetadata(uri)
            require(metadata.name.endsWith(".gguf", ignoreCase = true)) {
                "请选择 .gguf 格式的本地模型文件"
            }
            if (metadata.size > 0) {
                require(metadata.size in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                    "模型大小应在 256 MiB 至 8 GiB 之间"
                }
                val available = StatFs(modelsDirectory.absolutePath).availableBytes
                require(available > metadata.size + 512L * 1024 * 1024) {
                    "设备可用空间不足；至少还需预留 512 MiB"
                }
            }

            val partial = File(modelsDirectory, "model-import.partial")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            _state.value = AiModelState(
                phase = AiModelPhase.IMPORTING,
                fileName = metadata.name,
                sizeBytes = metadata.size,
                progress = 0f,
                message = "正在复制并校验模型…"
            )
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开所选模型文件" }
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var read: Int
                    var lastProgressUpdate = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (read == 0) continue
                        require(copied <= MAX_MODEL_BYTES - read) { "模型超过 8 GiB，已停止导入" }
                        if (copied == 0L || copied - lastProgressUpdate >= 16L * 1024 * 1024) {
                            require(StatFs(modelsDirectory.absolutePath).availableBytes > read + 512L * 1024 * 1024) {
                                "设备可用空间不足，已停止导入并保留原模型"
                            }
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (copied - lastProgressUpdate >= 16L * 1024 * 1024) {
                            lastProgressUpdate = copied
                            _state.value = _state.value.copy(
                                sizeBytes = if (metadata.size > 0) metadata.size else copied,
                                progress = if (metadata.size > 0) (copied.toFloat() / metadata.size).coerceIn(0f, 1f) else 0f,
                                message = "已复制 ${formatGiB(copied)}"
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            require(copied in MIN_MODEL_BYTES..MAX_MODEL_BYTES) { "模型文件大小不受支持" }
            require(metadata.size <= 0 || copied == metadata.size) { "模型未完整复制，请重新导入" }
            require(hasGgufMagic(partial)) { "文件不是有效的 GGUF 模型" }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(modelsDirectory, "model-${hash.take(12)}.gguf")
            // Re-import also repairs a truncated/corrupt existing file with the same name.
            // Replacing the directory entry preserves any mmap already held by a worker.
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            val previous = preferences.getString(KEY_PATH, null)?.let(::File)
            preferences.edit()
                .putString(KEY_PATH, destination.absolutePath)
                .putString(KEY_NAME, metadata.name)
                .putString(KEY_SHA256, hash)
                .putLong(KEY_SIZE, copied)
                .commit().also { check(it) { "模型配置保存失败，请重试" } }
            if (previous != null && previous.absolutePath != destination.absolutePath && previous.parentFile == modelsDirectory) {
                previous.delete()
            }

            AiModelState(
                phase = AiModelPhase.READY,
                fileName = metadata.name,
                sizeBytes = copied,
                sha256 = hash,
                progress = 1f,
                message = if (hash == RECOMMENDED_SHA256) "推荐模型校验通过，可完全离线运行。" else "GGUF 模型已安装；不是推荐文件，效果与兼容性可能不同。"
            ).also { _state.value = it }
        }.onFailure { error ->
            File(modelsDirectory, "model-import.partial").delete()
            val stored = readStoredState()
            _state.value = if (stored.ready) stored.copy(message = "导入未完成，原模型仍可使用：${error.message.orEmpty()}")
                else AiModelState(phase = AiModelPhase.ERROR, message = error.message ?: "模型导入失败")
            if (error is CancellationException) throw error
        }
        }
    }

    private fun readStoredState(): AiModelState {
        val file = modelFile()
        return if (file != null) {
            AiModelState(
                phase = AiModelPhase.READY,
                fileName = preferences.getString(KEY_NAME, file.name),
                sizeBytes = preferences.getLong(KEY_SIZE, file.length()),
                sha256 = preferences.getString(KEY_SHA256, null),
                progress = 1f,
                message = "模型已安装，可完全离线运行。"
            )
        } else AiModelState()
    }

    private fun hasGgufMagic(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        }
    }.getOrDefault(false)

    private fun queryMetadata(uri: Uri): ModelMetadata {
        var name = "local-model.gguf"
        var size = -1L
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return ModelMetadata(name.substringAfterLast('/').substringAfterLast('\\'), size)
    }

    private fun formatGiB(bytes: Long): String = "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private data class ModelMetadata(val name: String, val size: Long)

    companion object {
        const val RECOMMENDED_MODEL = "Qwen3-4B-Instruct-2507 Q4_K_M"
        const val RECOMMENDED_FILE = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        const val RECOMMENDED_BYTES = 2_497_280_736L
        const val RECOMMENDED_SHA256 = "2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e"
        const val RECOMMENDED_SOURCE = "bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF"

        private const val PREFERENCES = "offline_ai_model"
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_SIZE = "size"
        private const val MIN_MODEL_BYTES = 256L * 1024 * 1024
        private const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024
    }
}
