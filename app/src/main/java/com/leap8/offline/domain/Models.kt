package com.leap8.offline.domain

enum class Skill(val zh: String, val shortLabel: String) {
    VOCABULARY("词汇", "词"),
    LISTENING("听力", "听"),
    SPEAKING("口语", "说"),
    READING("阅读", "读"),
    WRITING("写作", "写"),
    GRAMMAR("语法", "语")
}

enum class ReviewRating(val quality: Int, val label: String) {
    AGAIN(1, "重来"), HARD(3, "困难"), GOOD(4, "掌握"), EASY(5, "简单")
}

data class VocabularyCard(
    val id: String,
    val word: String,
    val phonetic: String,
    val partOfSpeech: String,
    val meaningZh: String,
    val meaningZhTw: String,
    val example: String,
    val exampleZh: String,
    val roots: String,
    val collocations: List<String>,
    val level: String,
    val topic: String
)

data class WordProgress(
    val wordId: String,
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val dueEpochDay: Long = 0,
    val lapses: Int = 0,
    val lastRating: ReviewRating? = null
)

data class StudyLevel(
    val id: String,
    val title: String,
    val subtitle: String,
    val target: String,
    val weeks: Int,
    val color: Long,
    val modules: List<String>
)

data class ListeningLesson(
    val id: String,
    val title: String,
    val level: String,
    val transcript: String,
    val translation: String,
    val question: String,
    val answer: String,
    val focus: String
)

data class ReadingLesson(
    val id: String,
    val title: String,
    val level: String,
    val passage: String,
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanationZh: String
)

data class GrammarQuestion(
    val id: String,
    val level: String,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanationZh: String
)

data class EssayTask(
    val id: String,
    val type: String,
    val title: String,
    val prompt: String,
    val minimumWords: Int,
    val planningHints: List<String>
)

data class CriterionScore(
    val name: String,
    val band: Double,
    val evidence: String,
    val nextStep: String
)

data class EssayScore(
    val overallBand: Double,
    val wordCount: Int,
    val criteria: List<CriterionScore>,
    val strengths: List<String>,
    val priorities: List<String>,
    val correctedExamples: List<String>,
    val engine: String,
    val elapsedMs: Long,
    val disclaimer: String = "本结果用于学习诊断，不是 IELTS 官方成绩。"
)

data class DashboardSnapshot(
    val streakDays: Int = 1,
    val todayMinutes: Int = 0,
    val todayGoalMinutes: Int = 45,
    val dueWords: Int = 0,
    val learnedWords: Int = 0,
    val totalWords: Int = 0,
    val newWords: Int = 0,
    val learningWords: Int = 0,
    val masteredWords: Int = 0,
    val completedSessions: Int = 0,
    val estimatedBand: Double = 4.5,
    val skillScores: Map<Skill, Float> = Skill.entries.associateWith { 0.35f }
)

enum class WordLearningState(val label: String) {
    NEW("未学习"), LEARNING("学习中"), MASTERED("已掌握")
}

data class WordbookEntry(
    val card: VocabularyCard,
    val progress: WordProgress,
    val state: WordLearningState,
    val isDue: Boolean
)

data class WordbookSnapshot(
    val entries: List<WordbookEntry> = emptyList(),
    val total: Int = 0,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val masteredCount: Int = 0,
    val dueCount: Int = 0
) {
    val learnedCount: Int get() = learningCount + masteredCount
    val learnedFraction: Float get() = if (total == 0) 0f else learnedCount.toFloat() / total
    val masteredFraction: Float get() = if (total == 0) 0f else masteredCount.toFloat() / total
}

enum class AiProvider(
    val label: String,
    val description: String,
    val defaultEndpoint: String,
    val defaultModel: String
) {
    LOCAL("本地模型", "默认离线；按 NPU → GPU → CPU 自动回退", "", "Qwen3-4B-Instruct-2507 Q4_K_M"),
    OPENAI("OpenAI", "OpenAI Chat Completions 兼容接口", "https://api.openai.com/v1", "gpt-5-mini"),
    DEEPSEEK("DeepSeek", "DeepSeek Chat Completions 接口", "https://api.deepseek.com", "deepseek-chat"),
    QWEN("Qwen", "阿里云百炼 OpenAI 兼容接口", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus")
}

data class AiProviderConfig(
    val provider: AiProvider,
    val endpoint: String = provider.defaultEndpoint,
    val model: String = provider.defaultModel,
    val keyConfigured: Boolean = false
)

data class AiBackendSettings(
    val selectedProvider: AiProvider = AiProvider.LOCAL,
    val providers: Map<AiProvider, AiProviderConfig> = AiProvider.entries.associateWith { AiProviderConfig(it) }
) {
    fun config(provider: AiProvider = selectedProvider): AiProviderConfig =
        providers[provider] ?: AiProviderConfig(provider)
}

enum class AiProcessor(val label: String) {
    NPU("Hexagon NPU"), GPU("GPU+CPU"), CPU("ARM CPU"),
    CLOUD("云端 API"), RULES("本地量表")
}

data class AiGenerationProgress(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val stage: String = "等待开始",
    val generatedTokens: Int = 0,
    val maxTokens: Int = 0,
    val partialText: String = "",
    val processor: AiProcessor? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val decodeMs: Long? = null,
    val tokenSource: String? = null,
    val promptMs: Long? = null
)

data class AiRuntimeStatus(
    val mode: String,
    val accelerator: String,
    val model: String,
    val ready: Boolean,
    val detail: String,
    val processor: AiProcessor = AiProcessor.RULES,
    val fallbackChain: List<AiProcessor> = emptyList(),
    val isSnapdragon8Elite: Boolean = false
)

enum class AiModelPhase {
    NOT_INSTALLED, IMPORTING, READY, ERROR
}

data class AiModelState(
    val phase: AiModelPhase = AiModelPhase.NOT_INSTALLED,
    val displayName: String = "Qwen3-4B-Instruct-2507 Q4_K_M",
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val progress: Float = 0f,
    val message: String = "请从本地存储导入推荐的 GGUF 模型包。"
) {
    val ready: Boolean get() = phase == AiModelPhase.READY
    val sizeGiB: String get() = if (sizeBytes <= 0) "—" else "%.2f GiB".format(sizeBytes / 1024.0 / 1024.0 / 1024.0)
}

enum class AiCoachMode(val label: String, val description: String) {
    GRAMMAR("语法纠错", "定位错误、解释规则并给出可迁移练习"),
    WRITING("段落改写", "按 IELTS 7–8 分标准改写并解释取舍"),
    SPEAKING("口语表达", "把中式表达改成自然、可说出口的英语"),
    READING("阅读精讲", "拆解长难句、逻辑关系与同义替换"),
    VOCABULARY("词汇教练", "讲清搭配、语域、近义词边界与例句")
}

data class AiCoachResult(
    val mode: AiCoachMode,
    val input: String,
    val response: String,
    val engine: String,
    val elapsedMs: Long
)

enum class AiRevisionMode(val label: String, val description: String) {
    IELTS(
        "IELTS 写作精修",
        "保留立场与事实，重点提升任务回应、衔接、词汇和语法准确性"
    ),
    GRAMMAR(
        "语法纠错",
        "只修正语法、拼写、标点和明显搭配错误，尽量保留原有表达"
    ),
    NATURAL(
        "自然表达",
        "减少中式英语，让句子更自然、清晰且适合真实交流"
    ),
    ACADEMIC(
        "学术润色",
        "提升正式度、论证清晰度和用词精确度，避免无意义的生僻词堆砌"
    )
}

data class AiRevisionChange(
    val before: String,
    val after: String,
    val reasonZh: String,
    val category: String
)

data class AiRevisionResult(
    val mode: AiRevisionMode,
    val originalText: String,
    val revisedText: String,
    val summaryZh: String,
    val changes: List<AiRevisionChange>,
    val engine: String,
    val processor: AiProcessor,
    val elapsedMs: Long
)
