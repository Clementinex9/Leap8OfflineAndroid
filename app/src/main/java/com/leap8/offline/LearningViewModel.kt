package com.leap8.offline

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leap8.offline.ai.LocalAiMemoryStatus
import com.leap8.offline.data.SeedContent
import com.leap8.offline.diagnostics.DeveloperTelemetrySnapshot
import com.leap8.offline.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as Leap8Application
    private val repository by lazy { app.repository }
    private val storageMutex = Mutex()
    private val aiSettingsMutex = Mutex()

    private val _dashboard = MutableStateFlow(DashboardSnapshot())
    val dashboard: StateFlow<DashboardSnapshot> = _dashboard.asStateFlow()

    private val _dueWords = MutableStateFlow<List<VocabularyCard>>(emptyList())
    val dueWords: StateFlow<List<VocabularyCard>> = _dueWords.asStateFlow()

    private val _wordbook = MutableStateFlow(WordbookSnapshot())
    val wordbook: StateFlow<WordbookSnapshot> = _wordbook.asStateFlow()

    private val _essayScore = MutableStateFlow<EssayScore?>(null)
    val essayScore: StateFlow<EssayScore?> = _essayScore.asStateFlow()

    private val _isScoring = MutableStateFlow(false)
    val isScoring: StateFlow<Boolean> = _isScoring.asStateFlow()

    // Accessing these lazy Application properties opens preferences, validates model files,
    // and may enter AndroidKeyStore. Initialize the upstream on IO, never in Activity creation.
    val modelState: StateFlow<AiModelState> = flow {
        emitAll(app.aiModelManager.state)
    }.flowOn(Dispatchers.IO).stateIn(
        viewModelScope, SharingStarted.Eagerly, AiModelState(message = "正在读取本地模型状态…")
    )

    private val _runtimeStatus = MutableStateFlow(AiRuntimeStatus(
        mode = "正在检查 AI 引擎", accelerator = "后台检查中", model = "本地模型",
        ready = false, detail = "模型与硬件状态正在后台读取，学习功能可正常使用。"
    ))
    val runtimeStatus: StateFlow<AiRuntimeStatus> = _runtimeStatus.asStateFlow()

    val memoryStatus: StateFlow<LocalAiMemoryStatus> = flow {
        emitAll(app.offlineAiService.memoryStatus)
    }.flowOn(Dispatchers.IO).stateIn(
        viewModelScope, SharingStarted.Eagerly, LocalAiMemoryStatus()
    )

    val developerTelemetry: StateFlow<DeveloperTelemetrySnapshot> = flow {
        emitAll(app.developerTelemetry.state)
    }.flowOn(Dispatchers.IO).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), DeveloperTelemetrySnapshot()
    )

    fun setDeveloperMonitoringEnabled(enabled: Boolean) = launchAiSettings {
        app.developerTelemetry.setEnabled(enabled)
    }

    fun clearDeveloperHistory() = launchAiSettings {
        app.developerTelemetry.clearHistory()
    }

    val aiSettings: StateFlow<AiBackendSettings> = flow {
        emitAll(app.aiServiceRouter.settings)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, AiBackendSettings())

    private val _aiSettingsMessage = MutableStateFlow<String?>(null)
    val aiSettingsMessage: StateFlow<String?> = _aiSettingsMessage.asStateFlow()

    private val _aiProgress = MutableStateFlow(AiGenerationProgress())
    val aiProgress: StateFlow<AiGenerationProgress> = _aiProgress.asStateFlow()

    private val _aiCoachResult = MutableStateFlow<AiCoachResult?>(null)
    val aiCoachResult: StateFlow<AiCoachResult?> = _aiCoachResult.asStateFlow()

    private val _aiCoachError = MutableStateFlow<String?>(null)
    val aiCoachError: StateFlow<String?> = _aiCoachError.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    private val _aiRevisionResult = MutableStateFlow<AiRevisionResult?>(null)
    val aiRevisionResult: StateFlow<AiRevisionResult?> = _aiRevisionResult.asStateFlow()

    private val _aiRevisionError = MutableStateFlow<String?>(null)
    val aiRevisionError: StateFlow<String?> = _aiRevisionError.asStateFlow()

    private val _revisionProgress = MutableStateFlow(AiGenerationProgress())
    val revisionProgress: StateFlow<AiGenerationProgress> = _revisionProgress.asStateFlow()

    private val _isRevising = MutableStateFlow(false)
    val isRevising: StateFlow<Boolean> = _isRevising.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    init {
        // Asset decompression and SQLite startup must not block Activity creation.
        launchStorage {
            _dueWords.value = repository.dueVocabulary()
            _wordbook.value = repository.wordbook()
            refreshDashboardInternal()
        }
        launchAiSettings { refreshRuntimeStatus() }
    }

    private fun launchStorage(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try {
            storageMutex.withLock { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _storageError.value = "学习记录读写失败：${error.message ?: "请检查设备可用空间后重试"}"
        }
    }

    private fun launchAiSettings(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try {
            aiSettingsMutex.withLock { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _aiSettingsMessage.value = error.message ?: "AI 配置读取失败"
        }
    }

    private fun refreshRuntimeStatus() {
        _runtimeStatus.value = app.essayScoringCoordinator.runtimeStatus()
    }

    fun clearStorageError(message: String) {
        _storageError.compareAndSet(message, null)
    }

    fun reviewWord(card: VocabularyCard, rating: ReviewRating) {
        launchStorage {
            repository.review(card, rating)
            val due = repository.dueVocabulary()
            _dueWords.value = due.filterNot { it.id == card.id }.ifEmpty { due }
            _wordbook.value = repository.wordbook()
            refreshDashboardInternal()
        }
    }

    fun logPractice(skill: Skill, score: Double, durationSeconds: Int = 180) {
        launchStorage {
            repository.logSession(skill, score, durationSeconds)
            refreshDashboardInternal()
        }
    }

    fun scoreEssay(task: EssayTask, essay: String) {
        if (essay.isBlank() || _isScoring.value || _isAiGenerating.value || _isRevising.value) return
        _essayScore.value = null
        _isScoring.value = true
        _aiProgress.value = AiGenerationProgress(true, .02f, "正在准备评分")
        val publishProgress = AiProgressPublisher { _aiProgress.value = it }
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.Default) {
                    app.essayScoringCoordinator.score(task, essay, publishProgress::publish)
                }
                _essayScore.value = result
                launchStorage {
                    repository.saveEssay(task, essay, result)
                    refreshDashboardInternal()
                }
                withContext(Dispatchers.IO) { refreshRuntimeStatus() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _aiProgress.value = _aiProgress.value.copy(stage = error.message ?: "评分失败")
            } finally {
                _isScoring.value = false
                _aiProgress.value = _aiProgress.value.copy(running = false, fraction = 1f, stage = if (_essayScore.value != null) "评分完成" else _aiProgress.value.stage)
            }
        }
    }

    fun importAiModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            app.aiModelManager.importFromUri(uri)
            refreshRuntimeStatus()
        }
    }

    fun runAiCoach(mode: AiCoachMode, input: String) {
        if (input.isBlank() || _isAiGenerating.value || _isScoring.value || _isRevising.value) return
        _aiCoachResult.value = null
        _aiCoachError.value = null
        _isAiGenerating.value = true
        _aiProgress.value = AiGenerationProgress(true, .02f, "正在准备 AI 教练")
        val publishProgress = AiProgressPublisher { _aiProgress.value = it }
        viewModelScope.launch {
            try {
                _aiCoachResult.value = runInterruptible(Dispatchers.Default) {
                    app.aiServiceRouter.coach(mode, input, publishProgress::publish)
                }
                withContext(Dispatchers.IO) { refreshRuntimeStatus() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _aiCoachError.value = error.message ?: "AI 生成失败"
            } finally {
                _isAiGenerating.value = false
                _aiProgress.value = _aiProgress.value.copy(running = false, fraction = 1f, stage = if (_aiCoachResult.value != null) "生成完成" else "生成失败")
            }
        }
    }

    fun runAiRevision(
        mode: AiRevisionMode,
        input: String,
        taskPrompt: String? = null
    ) {
        if (input.isBlank() || _isRevising.value || _isScoring.value || _isAiGenerating.value) return
        _aiRevisionResult.value = null
        _aiRevisionError.value = null
        _isRevising.value = true
        _revisionProgress.value = AiGenerationProgress(true, .02f, "正在准备 AI 精修")
        val publishProgress = AiProgressPublisher { _revisionProgress.value = it }
        viewModelScope.launch {
            try {
                _aiRevisionResult.value = runInterruptible(Dispatchers.Default) {
                    app.aiRevisionCoordinator.revise(mode, input, taskPrompt, publishProgress::publish)
                }
                withContext(Dispatchers.IO) { refreshRuntimeStatus() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _aiRevisionError.value = error.message ?: "AI 精修失败"
            } finally {
                _isRevising.value = false
                _revisionProgress.value = _revisionProgress.value.copy(
                    running = false,
                    fraction = 1f,
                    stage = if (_aiRevisionResult.value != null) "精修与对比已完成" else "精修失败"
                )
            }
        }
    }

    fun selectAiProvider(provider: AiProvider) {
        launchAiSettings {
            app.aiServiceRouter.selectProvider(provider)
            refreshRuntimeStatus()
            _aiSettingsMessage.value = if (provider == AiProvider.LOCAL) "已切换为完全本地模式" else "已选择 ${provider.label}"
        }
    }

    fun saveAiProvider(provider: AiProvider, endpoint: String, model: String, apiKey: String?) {
        launchAiSettings {
            app.aiServiceRouter.saveProvider(provider, endpoint, model, apiKey)
            refreshRuntimeStatus()
            _aiSettingsMessage.value = "${provider.label} 配置已安全保存"
        }
    }

    fun clearAiProviderKey(provider: AiProvider) {
        launchAiSettings {
            app.aiServiceRouter.clearApiKey(provider)
            refreshRuntimeStatus()
            _aiSettingsMessage.value = "${provider.label} API Key 已从本机删除"
        }
    }

    fun clearAiSettingsMessage() { _aiSettingsMessage.value = null }

    fun clearAiCoachResult() {
        _aiCoachResult.value = null
        _aiCoachError.value = null
    }

    fun clearAiRevisionResult() {
        if (_isRevising.value) return
        _aiRevisionResult.value = null
        _aiRevisionError.value = null
        _revisionProgress.value = AiGenerationProgress()
    }

    fun clearEssayScore() { _essayScore.value = null }

    fun resetDemoProgress() {
        // Intentionally leaves durable learning history intact. A production reset is destructive
        // and should require an explicit confirmation surface.
        _essayScore.value = null
    }

    private fun refreshDashboardInternal() { _dashboard.value = repository.dashboard() }

    val levels get() = SeedContent.levels
    val listening get() = SeedContent.listening
    val reading get() = SeedContent.reading
    val grammar get() = SeedContent.grammar
    val essayTasks get() = SeedContent.essayTasks
}
