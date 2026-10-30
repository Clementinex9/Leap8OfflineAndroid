@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.leap8.offline.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leap8.offline.LearningViewModel
import com.leap8.offline.domain.*
import com.leap8.offline.speech.OfflineSpeechEngine
import com.leap8.offline.speech.OfflineTts
import com.leap8.offline.speech.NaturalVoice
import com.leap8.offline.speech.PronunciationResult
import com.leap8.offline.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale

@Composable
internal fun VocabularyScreen(viewModel: LearningViewModel, onOpenWordbook: () -> Unit = {}) {
    val words by viewModel.dueWords.collectAsStateWithLifecycle()
    val wordbook by viewModel.wordbook.collectAsStateWithLifecycle()
    val card = words.firstOrNull()
    var revealed by rememberSaveable(card?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    var ttsMessage by remember { mutableStateOf("正在检查离线语音…") }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        NaturalVoice(context) { ready, message -> ttsReady = ready; ttsMessage = message }
    }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    if (card == null) {
        EmptyState(Icons.Rounded.Celebration, "今日复习完成", "可以进入阅读或听力，让新词在语境中再次出现。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column {
                    Text("今日剩余 ${words.size} 张", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("总词本 ${wordbook.learnedCount}/${wordbook.total}", style = MaterialTheme.typography.labelSmall, color = Pine)
                }
                TextButton(onClick = onOpenWordbook) { Text("查看总词本"); Icon(Icons.Rounded.ChevronRight, null) }
            }
            LinearProgressIndicator(
                progress = { 1f / words.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape),
                color = Gold,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 280.dp).clickable { revealed = true },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(card.word, modifier = Modifier.weight(1f), fontSize = 32.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { if (ttsReady) tts.speak(card.word, slower = true) }) {
                            Icon(Icons.Rounded.VolumeUp, "朗读", tint = if (ttsReady) Pine else MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text("${card.phonetic}  ${card.partOfSpeech}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!revealed) {
                        Spacer(Modifier.heightIn(min = 52.dp))
                        Icon(Icons.Rounded.TouchApp, null, tint = Gold, modifier = Modifier.size(34.dp))
                        Text("先回忆含义、搭配和一个例句", fontWeight = FontWeight.SemiBold)
                        Text("轻触卡片查看答案", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Text(card.meaningZh, style = MaterialTheme.typography.headlineMedium, color = Pine)
                        if (card.roots.isNotBlank()) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("构词", style = MaterialTheme.typography.labelMedium, color = Pine)
                                    Text(card.roots)
                                }
                            }
                        }
                        if (card.collocations.isNotEmpty()) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                card.collocations.forEach { collocation ->
                                    AssistChip(onClick = { tts.speak(collocation) }, label = { Text(collocation) }, leadingIcon = { Icon(Icons.Rounded.VolumeUp, null, Modifier.size(15.dp)) })
                                }
                            }
                        }
                        if (card.example.isNotBlank()) Text(card.example, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                        if (card.exampleZh.isNotBlank()) Text(card.exampleZh, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        item {
            if (!revealed) {
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("显示答案") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewRating.entries.forEach { rating ->
                        val color = when (rating) { ReviewRating.AGAIN -> Coral; ReviewRating.HARD -> Gold; ReviewRating.GOOD -> PineLight; ReviewRating.EASY -> Pine }
                        Button(
                            onClick = { viewModel.reviewWord(card, rating); revealed = false },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = color)
                        ) { Text(rating.label, fontSize = 12.sp) }
                    }
                }
            }
        }
        item { Text(ttsMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun WordbookScreen(viewModel: LearningViewModel) {
    val snapshot by viewModel.wordbook.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val term = query.trim()
    val filtered by produceState(initialValue = snapshot.entries, snapshot.entries, term, filter) {
        val entries = snapshot.entries
        value = withContext(Dispatchers.Default) {
            entries.filter { entry ->
                val matches = when (filter) {
                    "新词" -> entry.state == WordLearningState.NEW
                    "学习中" -> entry.state == WordLearningState.LEARNING
                    "已掌握" -> entry.state == WordLearningState.MASTERED
                    "待复习" -> entry.isDue
                    else -> true
                }
                matches && (term.isBlank() || entry.card.word.contains(term, true) || entry.card.meaningZh.contains(term, true))
            }
        }
    }
    val context = LocalContext.current
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { NaturalVoice(context) { ready, _ -> ttsReady = ready } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("IELTS 全量覆盖离线词库", style = MaterialTheme.typography.headlineMedium)
            Text("ECDICT 全部 5,040 个 IELTS 标签词 + 8 个不重复精选词，共 ${snapshot.total} 词", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("IELTS 官方没有发布唯一的“全部词汇表”；这里按开源词典标签完整收录，而非抽样。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("学习进度", fontWeight = FontWeight.Bold)
                        Text("${snapshot.learnedCount}/${snapshot.total}", color = Pine)
                    }
                    LinearProgressIndicator(progress = { snapshot.learnedFraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Pine)
                    Text("新词 ${snapshot.newCount} · 学习中 ${snapshot.learningCount} · 已掌握 ${snapshot.masteredCount} · 待复习 ${snapshot.dueCount}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索英文或中文释义") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "清除") } }} else null,
                singleLine = true, shape = RoundedCornerShape(16.dp)
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("全部", "新词", "学习中", "已掌握", "待复习").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                }
            }
            Text("找到 ${filtered.size} 词", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(filtered, key = { it.card.id }, contentType = { "vocabulary-entry" }) { entry ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.card.word, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Text(entry.card.level, style = MaterialTheme.typography.labelSmall, color = Pine)
                        }
                        if (entry.card.phonetic.isNotBlank()) Text(entry.card.phonetic, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.card.meaningZh, style = MaterialTheme.typography.bodyMedium)
                        Text("${entry.state.label} · 复习 ${entry.progress.repetitions} 次 · 间隔 ${entry.progress.intervalDays} 天", style = MaterialTheme.typography.labelSmall, color = if (entry.state == WordLearningState.MASTERED) Pine else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { tts.speak(entry.card.word, slower = true) }, enabled = ttsReady) { Icon(Icons.Rounded.VolumeUp, "发音") }
                }
            }
        }
    }
}

@Composable
internal fun ListeningScreen(viewModel: LearningViewModel) {
    val context = LocalContext.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val lessons = viewModel.listening
    if (lessons.isEmpty()) {
        EmptyState(Icons.Rounded.Headphones, "听力内容暂不可用", "请稍后重新打开课程。")
        return
    }
    val safeIndex = Math.floorMod(index, lessons.size)
    val lesson = lessons[safeIndex]
    var showTranscript by rememberSaveable(lesson.id) { mutableStateOf(false) }
    var answer by rememberSaveable(lesson.id) { mutableStateOf("") }
    var checked by rememberSaveable(lesson.id) { mutableStateOf(false) }
    var slow by rememberSaveable { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsMessage by remember { mutableStateOf("正在检查离线英语语音…") }
    val tts = remember { NaturalVoice(context) { ready, message -> ttsReady = ready; ttsMessage = message } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) { Text(lesson.title, style = MaterialTheme.typography.headlineMedium); Text("${lesson.level} · ${lesson.focus}", color = Pine) }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) { Text("${safeIndex + 1}/${lessons.size}", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Headphones, null, tint = Color(0xFF2997FF), modifier = Modifier.size(40.dp))
                    Text("第一遍抓主旨，第二遍记关键词", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(onClick = { if (ttsReady) tts.speak(lesson.transcript, slow) }, enabled = ttsReady) {
                            Icon(Icons.Rounded.PlayArrow, null); Text(if (slow) "慢速播放" else "正常播放")
                        }
                        FilterChip(selected = slow, onClick = { slow = !slow }, label = { Text("慢速") })
                    }
                    Text(ttsMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("理解问题", style = MaterialTheme.typography.titleLarge)
                    Text(lesson.question)
                    OutlinedTextField(answer, { answer = it }, modifier = Modifier.fillMaxWidth(), label = { Text("用英文简答") }, minLines = 2)
                    Button(
                        onClick = { checked = true; viewModel.logPractice(Skill.LISTENING, if (similarAnswer(answer, lesson.answer)) 7.5 else 5.0, 300) },
                        enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()
                    ) { Text("检查证据") }
                    AnimatedVisibility(checked) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (similarAnswer(answer, lesson.answer)) "回答抓住了关键信息" else "再听一次，关注同义替换", color = if (similarAnswer(answer, lesson.answer)) Pine else Coral, fontWeight = FontWeight.Bold)
                            Text("参考：${lesson.answer}")
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { showTranscript = !showTranscript }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (showTranscript) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                Spacer(Modifier.width(8.dp)); Text(if (showTranscript) "隐藏原文" else "查看原文与翻译")
            }
        }
        item {
            AnimatedVisibility(showTranscript) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(lesson.transcript, lineHeight = 26.sp)
                        HorizontalDivider(); Text(lesson.translation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { index = (safeIndex + 1) % lessons.size }) {
                Text("下一课"); Icon(Icons.Rounded.ArrowForward, null)
            }
        }
    }
}

private fun similarAnswer(answer: String, expected: String): Boolean {
    val a = Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(answer.lowercase()).map { it.value }.toSet()
    val b = Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(expected.lowercase()).map { it.value }.filter { it.length > 3 }.toSet()
    return a.intersect(b).size >= (b.size / 2).coerceAtLeast(1)
}

@Composable
internal fun SpeakingScreen(viewModel: LearningViewModel) {
    val context = LocalContext.current
    val phrases = remember { listOf(
        "I tend to learn more effectively when feedback is immediate.",
        "Public transport can reduce congestion if the service is reliable.",
        "Although technology saves time, it can also fragment our attention."
    ) }
    var phraseIndex by rememberSaveable { mutableIntStateOf(0) }
    if (phrases.isEmpty()) {
        EmptyState(Icons.Rounded.GraphicEq, "口语内容暂不可用", "请稍后重新打开课程。")
        return
    }
    val safePhraseIndex = Math.floorMod(phraseIndex, phrases.size)
    val phrase = phrases[safePhraseIndex]
    var stateMessage by remember { mutableStateOf("准备就绪") }
    var result by remember { mutableStateOf<PronunciationResult?>(null) }
    val speech = remember { OfflineSpeechEngine(context) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsMessage by remember { mutableStateOf("") }
    val tts = remember { NaturalVoice(context) { ready, message -> ttsReady = ready; ttsMessage = message } }
    DisposableEffect(Unit) { onDispose { speech.destroy(); tts.shutdown() } }

    fun startRecognition() {
        result = null
        speech.listen(
            reference = phrase,
            onState = { stateMessage = it },
            onResult = { result = it; stateMessage = "本地比对完成"; viewModel.logPractice(Skill.SPEAKING, it.accuracy / 100.0 * 9.0, 120) },
            onError = { stateMessage = it }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecognition() else stateMessage = "麦克风权限未开启。"
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("跟读—识别—修正", style = MaterialTheme.typography.headlineMedium)
            Text("只调用 Android 设备端识别器，不允许网络识别。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("影子跟读 ${safePhraseIndex + 1}/${phrases.size}", color = Pine, fontWeight = FontWeight.Bold)
                    Text(phrase, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { tts.speak(phrase) }, enabled = ttsReady) { Icon(Icons.Rounded.VolumeUp, null); Text("听示范") }
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecognition()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Icon(Icons.Rounded.Mic, null); Text("开始跟读") }
                    }
                    Text(stateMessage, color = if (speech.isAvailable) Pine else Coral)
                    if (!speech.isAvailable) Text("请先在系统语言设置中安装离线英语识别包。应用本身不会联网补充。", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (ttsMessage.isNotBlank()) Text(ttsMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            result?.let { PronunciationResultCard(it) }
        }
        item {
            SectionTitle("高分口语动作", null)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. 先直接回答", fontWeight = FontWeight.Bold); Text("不要用背诵式开场拖延立场。")
                    Text("2. 给出原因机制", fontWeight = FontWeight.Bold); Text("说明 why/how，而不是只堆例子。")
                    Text("3. 用一个具体细节收尾", fontWeight = FontWeight.Bold); Text("例子服务于观点，不替代观点。")
                }
            }
        }
        item {
            TextButton(onClick = {
                speech.destroy()
                tts.stop()
                phraseIndex = (safePhraseIndex + 1) % phrases.size
                result = null
                stateMessage = "准备就绪"
            }) { Text("下一句"); Icon(Icons.Rounded.ArrowForward, null) }
        }
    }
}

@Composable
internal fun PronunciationResultCard(result: PronunciationResult) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A score is text, not a fixed-size icon. Let both the score and feedback
            // grow with accessibility font scaling and long recognition feedback.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (result.accuracy >= 75) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${result.accuracy}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (result.accuracy >= 75) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text("词序与识别准确度", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(result.feedback, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("识别：${result.transcript.ifBlank { "—" }}")
            if (result.missingWords.isNotEmpty()) Text("重点重读：${result.missingWords.joinToString(" · ")}", color = Coral)
        }
    }
}

@Composable
internal fun ReadingScreen(viewModel: LearningViewModel) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val lessons = viewModel.reading
    if (lessons.isEmpty()) {
        EmptyState(Icons.Rounded.MenuBook, "阅读内容暂不可用", "请稍后重新打开课程。")
        return
    }
    val safeIndex = Math.floorMod(index, lessons.size)
    val lesson = lessons[safeIndex]
    var selected by rememberSaveable(lesson.id) { mutableIntStateOf(-1) }
    var checked by rememberSaveable(lesson.id) { mutableStateOf(false) }
    var showTranslationHint by rememberSaveable(lesson.id) { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column { Text(lesson.title, style = MaterialTheme.typography.headlineMedium); Text("${lesson.level} · 建议 4 分钟", color = Pine) }
                Icon(Icons.Rounded.Timer, null, tint = Gold)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text(lesson.passage, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
                    TextButton(onClick = { showTranslationHint = !showTranslationHint }) { Text(if (showTranslationHint) "隐藏句法提示" else "显示句法提示") }
                    AnimatedVisibility(showTranslationHint) {
                        Text("阅读顺序：先找每句谓语，再识别 although / because / by contrast 等逻辑关系，最后合并细节。", color = Pine)
                    }
                }
            }
        }
        item { Text(lesson.question, style = MaterialTheme.typography.titleLarge) }
        items(lesson.options.indices.toList()) { optionIndex ->
            val correct = checked && optionIndex == lesson.correctIndex
            val wrong = checked && optionIndex == selected && selected != lesson.correctIndex
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !checked) { selected = optionIndex },
                shape = RoundedCornerShape(16.dp),
                color = when { correct -> MaterialTheme.colorScheme.primaryContainer; wrong -> Coral.copy(alpha = .12f); selected == optionIndex -> Gold.copy(alpha = .18f); else -> MaterialTheme.colorScheme.surface }
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    RadioButton(selected = selected == optionIndex, onClick = null)
                    Text(lesson.options[optionIndex], Modifier.weight(1f))
                    if (correct) Icon(Icons.Rounded.CheckCircle, null, tint = Pine)
                }
            }
        }
        item {
            Button(onClick = { checked = true; viewModel.logPractice(Skill.READING, if (selected == lesson.correctIndex) 8.0 else 5.0, 240) }, enabled = selected in lesson.options.indices && !checked, modifier = Modifier.fillMaxWidth()) { Text("提交答案") }
        }
        item {
            AnimatedVisibility(checked) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (selected == lesson.correctIndex) "证据定位正确" else "回到原文找同义替换", fontWeight = FontWeight.Bold, color = if (selected == lesson.correctIndex) Pine else Coral)
                        Text(lesson.explanationZh)
                        TextButton(onClick = { index = (safeIndex + 1) % lessons.size }) { Text("下一篇"); Icon(Icons.Rounded.ArrowForward, null) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WritingScreen(viewModel: LearningViewModel) {
    val score by viewModel.essayScore.collectAsStateWithLifecycle()
    val isScoring by viewModel.isScoring.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val progress by viewModel.aiProgress.collectAsStateWithLifecycle()
    val revision by viewModel.aiRevisionResult.collectAsStateWithLifecycle()
    val revisionError by viewModel.aiRevisionError.collectAsStateWithLifecycle()
    val revisionProgress by viewModel.revisionProgress.collectAsStateWithLifecycle()
    val isRevising by viewModel.isRevising.collectAsStateWithLifecycle()
    var taskIndex by rememberSaveable { mutableIntStateOf(0) }
    val tasks = viewModel.essayTasks
    if (tasks.isEmpty()) {
        EmptyState(Icons.Rounded.EditNote, "写作题目暂不可用", "请稍后重新打开课程。")
        return
    }
    val safeTaskIndex = Math.floorMod(taskIndex, tasks.size)
    val task = tasks[safeTaskIndex]
    var essay by rememberSaveable(task.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var showPlan by rememberSaveable(task.id) { mutableStateOf(true) }
    val wordCount = remember(essay.text) { Regex("[A-Za-z]+(?:['’-][A-Za-z]+)?").findAll(essay.text).count() }

    LaunchedEffect(task.id) { viewModel.clearAiRevisionResult() }

    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) { Text(task.type, color = Pine, fontWeight = FontWeight.Bold); Text(task.title, style = MaterialTheme.typography.headlineMedium) }
                AssistChip(onClick = { taskIndex = (safeTaskIndex + 1) % tasks.size; viewModel.clearEssayScore(); viewModel.clearAiRevisionResult() }, label = { Text("换题") }, leadingIcon = { Icon(Icons.Rounded.Cached, null, Modifier.size(16.dp)) })
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(task.prompt, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                    TextButton(onClick = { showPlan = !showPlan }) { Icon(Icons.Rounded.Lightbulb, null); Text(if (showPlan) "收起规划提示" else "查看规划提示") }
                    AnimatedVisibility(showPlan) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { task.planningHints.forEachIndexed { i, hint -> Text("${i + 1}. $hint", style = MaterialTheme.typography.bodyMedium) } }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = essay,
                onValueChange = {
                    essay = it
                    if (score != null) viewModel.clearEssayScore()
                    if (revision != null || revisionError != null) viewModel.clearAiRevisionResult()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                enabled = !isScoring && !isRevising,
                label = { Text("在此写作 · 自动保存到本机历史") },
                supportingText = { Text("$wordCount / 至少 ${task.minimumWords} 词") },
                shape = RoundedCornerShape(18.dp)
            )
        }
        item {
            Button(
                onClick = { viewModel.scoreEssay(task, essay.text) },
                enabled = essay.text.isNotBlank() && !isScoring && !isRevising,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                if (isScoring) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current); Spacer(Modifier.width(8.dp)); Text(progress.stage) }
                else { Icon(if (settings.selectedProvider == AiProvider.LOCAL) Icons.Rounded.Memory else Icons.Rounded.Cloud, null); Spacer(Modifier.width(8.dp)); Text(if (settings.selectedProvider == AiProvider.LOCAL) "本地评分与反馈" else "使用 ${settings.selectedProvider.label} 评分") }
            }
            Text(
                if (runtime.ready) "${if (runtime.processor == AiProcessor.RULES) "处理器待验证" else runtime.processor.label} · ${runtime.model}" else "深度 AI 未就绪；将使用本地确定性量表",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        item {
            OutlinedButton(
                onClick = { viewModel.runAiRevision(AiRevisionMode.IELTS, essay.text.take(4_000), task.prompt) },
                enabled = runtime.ready && essay.text.isNotBlank() && essay.text.length <= 4_000 && !isScoring && !isRevising,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                if (isRevising) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(revisionProgress.stage)
                } else {
                    Icon(Icons.Rounded.AutoFixHigh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.selectedProvider == AiProvider.LOCAL) "AI 直接精修并对比" else "使用 ${settings.selectedProvider.label} 精修并对比")
                }
            }
            Text(
                when {
                    essay.text.length > 4_000 -> "精修文本不能超过 4,000 字符"
                    runtime.ready -> "直接给出完整修正版，并逐项展示修正前、修正后和中文理由"
                    settings.selectedProvider == AiProvider.LOCAL -> "请先在“我的”中导入本地 GGUF 模型"
                    else -> "请先在“我的”中配置 ${settings.selectedProvider.label} API Key"
                },
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (essay.text.length > 4_000) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (isScoring) item { AiProgressPanel(progress) }
        if (isRevising) item { AiProgressPanel(revisionProgress) }
        revisionError?.let { message ->
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Coral.copy(alpha = .12f)) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp), color = Coral)
                }
            }
        }
        revision?.let { answer ->
            item {
                RevisionResultPanel(answer) {
                    essay = TextFieldValue(answer.revisedText, TextRange(answer.revisedText.length))
                    viewModel.clearEssayScore()
                }
            }
        }
        item { score?.let { EssayScorePanel(it) } }
    }
}

@Composable
internal fun AiRevisionScreen(viewModel: LearningViewModel) {
    val runtime by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val progress by viewModel.revisionProgress.collectAsStateWithLifecycle()
    val result by viewModel.aiRevisionResult.collectAsStateWithLifecycle()
    val error by viewModel.aiRevisionError.collectAsStateWithLifecycle()
    val revising by viewModel.isRevising.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AiRevisionMode.IELTS) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(mode) { viewModel.clearAiRevisionResult() }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("直接看到怎样改、为什么改", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (settings.selectedProvider == AiProvider.LOCAL) "原文和反馈留在设备；实际使用的 NPU/GPU/CPU 会显示在结果中。"
                else "当前使用 ${settings.selectedProvider.label}；仅在你点击精修时发送这段文本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AiRevisionMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        enabled = !revising,
                        onClick = { mode = option },
                        label = { Text(option.label) },
                        leadingIcon = if (mode == option) {{ Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }} else null
                    )
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(mode.label, fontWeight = FontWeight.Bold, color = Pine)
                    Text(mode.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    if (result != null || error != null) viewModel.clearAiRevisionResult()
                },
                enabled = !revising,
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                label = { Text("粘贴需要修改的英文句子、段落或作文") },
                supportingText = { Text("${input.text.length} / 4,000 字符") },
                isError = input.text.length > 4_000,
                shape = RoundedCornerShape(18.dp)
            )
        }
        item {
            Button(
                onClick = { viewModel.runAiRevision(mode, input.text) },
                enabled = runtime.ready && input.text.isNotBlank() && input.text.length <= 4_000 && !revising,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                if (revising) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text(progress.stage)
                } else {
                    Icon(if (settings.selectedProvider == AiProvider.LOCAL) Icons.Rounded.Memory else Icons.Rounded.Cloud, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.selectedProvider == AiProvider.LOCAL) "开始本地精修" else "使用 ${settings.selectedProvider.label} 精修")
                }
            }
            if (!runtime.ready) {
                Text(
                    if (settings.selectedProvider == AiProvider.LOCAL) "请先在“我的”中导入本地 GGUF 模型。" else "请先在“我的”中配置 ${settings.selectedProvider.label} API Key。",
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = Coral
                )
            }
        }
        if (revising) item { AiProgressPanel(progress) }
        error?.let { message ->
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Coral.copy(alpha = .12f)) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp), color = Coral)
                }
            }
        }
        result?.let { answer ->
            item {
                RevisionResultPanel(answer) {
                    input = TextFieldValue(answer.revisedText, TextRange(answer.revisedText.length))
                }
            }
        }
    }
}

@Composable
internal fun AiCoachScreen(viewModel: LearningViewModel) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val progress by viewModel.aiProgress.collectAsStateWithLifecycle()
    val result by viewModel.aiCoachResult.collectAsStateWithLifecycle()
    val error by viewModel.aiCoachError.collectAsStateWithLifecycle()
    val generating by viewModel.isAiGenerating.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AiCoachMode.GRAMMAR) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(mode) { viewModel.clearAiCoachResult() }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("你的 AI 英语教练", style = MaterialTheme.typography.headlineMedium)
            Text(if (settings.selectedProvider == AiProvider.LOCAL) "纠错、改写和讲解都在设备内完成。" else "当前使用 ${settings.selectedProvider.label}；可在“我的”随时切回本地。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AiCoachMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { mode = option },
                        label = { Text(option.label) },
                        leadingIcon = if (mode == option) {{ Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }} else null
                    )
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(mode.label, fontWeight = FontWeight.Bold, color = Pine)
                    Text(mode.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (settings.selectedProvider == AiProvider.LOCAL && !runtime.ready) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = Gold.copy(alpha = .16f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DownloadForOffline, null, tint = Coral)
                        Column {
                            Text("尚未安装本地大模型", fontWeight = FontWeight.Bold)
                            Text("前往“我的 → 离线模型”导入推荐 GGUF 文件。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; if (result != null || error != null) viewModel.clearAiCoachResult() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                label = { Text("粘贴英文句子、段落或你的表达") },
                supportingText = { Text("${input.text.length} 字符 · 建议不超过 2,500 字符") },
                shape = RoundedCornerShape(18.dp)
            )
        }
        item {
            Button(
                onClick = { viewModel.runAiCoach(mode, input.text.take(2500)) },
                enabled = runtime.ready && input.text.isNotBlank() && !generating,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                if (generating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                    Text(progress.stage)
                } else {
                    Icon(Icons.Rounded.Psychology, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.selectedProvider == AiProvider.LOCAL) "开始本地深度讲解" else "使用 ${settings.selectedProvider.label} 讲解")
                }
            }
        }
        if (generating) item { AiProgressPanel(progress) }
        error?.let { message ->
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Coral.copy(alpha = .12f)) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp), color = Coral)
                }
            }
        }
        result?.let { answer ->
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = Pine)
                            Text("${answer.mode.label}反馈", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(answer.response, lineHeight = 25.sp)
                        HorizontalDivider()
                        Text("${answer.engine} · ${answer.elapsedMs / 1000.0} 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiProgressPanel(progress: AiGenerationProgress) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Psychology, null, tint = Pine)
                Text(progress.stage, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            progress.processor?.let {
                Text("${if (progress.generatedTokens > 0) "当前运行" else "正在验证"} · ${it.label}", color = Pine)
            }
            if (progress.processor == AiProcessor.NPU) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Pine)
            } else {
                LinearProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = Pine, trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Text(
                when {
                    progress.processor == AiProcessor.NPU -> "已收到 ${progress.generatedTokens} 个输出片段"
                    progress.generatedTokens > 0 -> "已生成 ${progress.generatedTokens} / 上限 ${progress.maxTokens} tokens · 阶段估算 ${(progress.fraction * 100).toInt()}%"
                    else -> "阶段估算 ${(progress.fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress.processor in listOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU)) {
                Text("本地生成时请保持应用在前台。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RevisionResultPanel(result: AiRevisionResult, onUseRevised: (() -> Unit)? = null) {
    val context = LocalContext.current
    var copied by remember(result.revisedText) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Pine)
                        Text("完整修正版", style = MaterialTheme.typography.titleLarge)
                    }
                    AssistChip(onClick = {}, label = { Text(result.processor.label) })
                }
                Text(result.summaryZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(16.dp), color = Pine.copy(alpha = .08f)) {
                    SelectionContainer {
                        Text(
                            result.revisedText,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            lineHeight = 25.sp
                        )
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.2f) 1 else 2, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("AI 完整修正版", result.revisedText))
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "已复制" else "复制修正版")
                    }
                    onUseRevised?.let { use ->
                        Button(onClick = use, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.DriveFileRenameOutline, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("替换输入内容")
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    "${result.mode.label} · ${result.engine} · ${result.elapsedMs / 1000.0} 秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text("修正前后对比", style = MaterialTheme.typography.titleLarge)
        if (result.changes.isEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = Pine.copy(alpha = .08f)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Pine)
                    Text("没有发现必须修改的内容。请继续关注上下文和任务要求。")
                }
            }
        } else {
            result.changes.forEachIndexed { index, change ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${index + 1}. ${change.category}", fontWeight = FontWeight.Bold)
                            Icon(Icons.AutoMirrored.Rounded.CompareArrows, null, tint = Pine)
                        }
                        Surface(shape = RoundedCornerShape(13.dp), color = Coral.copy(alpha = .09f)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("修正前", style = MaterialTheme.typography.labelMedium, color = Coral, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(change.before, lineHeight = 23.sp) }
                            }
                        }
                        Surface(shape = RoundedCornerShape(13.dp), color = Pine.copy(alpha = .09f)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("修正后", style = MaterialTheme.typography.labelMedium, color = Pine, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(change.after, lineHeight = 23.sp) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Lightbulb, null, tint = Gold, modifier = Modifier.size(18.dp))
                            Text(change.reasonZh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EssayScorePanel(score: EssayScore) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = Ink) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${score.overallBand} · 能力估算", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text(score.engine, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodyMedium)
                Text("${score.wordCount} 词 · ${score.elapsedMs} ms", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(score.disclaimer, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelSmall)
            }
        }
        score.criteria.forEach { criterion ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(criterion.name, fontWeight = FontWeight.Bold); Text(criterion.band.toString(), color = Pine, style = MaterialTheme.typography.titleLarge) }
                    LinearProgressIndicator(progress = { (criterion.band / 9).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Pine, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text(criterion.evidence, style = MaterialTheme.typography.bodyMedium)
                    Text("下一步：${criterion.nextStep}", style = MaterialTheme.typography.bodyMedium, color = Coral)
                }
            }
        }
        FeedbackList("做得好的证据", score.strengths, Icons.Rounded.CheckCircle, Pine)
        FeedbackList("优先修改", score.priorities, Icons.Rounded.BuildCircle, Coral)
        FeedbackList("改写提示", score.correctedExamples, Icons.Rounded.AutoFixHigh, Blue)
    }
}

@Composable
private fun FeedbackList(title: String, lines: List<String>, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    if (lines.isEmpty()) return
    Surface(shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .09f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = color); Text(title, fontWeight = FontWeight.Bold) }
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun GrammarScreen(viewModel: LearningViewModel) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val questions = viewModel.grammar
    if (questions.isEmpty()) {
        EmptyState(Icons.Rounded.AccountTree, "语法内容暂不可用", "请稍后重新打开课程。")
        return
    }
    val safeIndex = Math.floorMod(index, questions.size)
    val question = questions[safeIndex]
    var selected by rememberSaveable(question.id) { mutableIntStateOf(-1) }
    var checked by rememberSaveable(question.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("错因比题量更重要", style = MaterialTheme.typography.headlineMedium); Text("${question.level} · 句法选择", color = Pine) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(question.stem, style = MaterialTheme.typography.headlineMedium)
                    question.options.forEachIndexed { optionIndex, option ->
                        val correct = checked && optionIndex == question.correctIndex
                        val wrong = checked && selected == optionIndex && !correct
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !checked) { selected = optionIndex },
                            shape = RoundedCornerShape(14.dp),
                            color = when { correct -> MaterialTheme.colorScheme.primaryContainer; wrong -> Coral.copy(alpha = .12f); selected == optionIndex -> Gold.copy(alpha = .15f); else -> MaterialTheme.colorScheme.surfaceVariant }
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == optionIndex, null); Text(option, Modifier.weight(1f)); if (correct) Icon(Icons.Rounded.Check, null, tint = Pine) }
                        }
                    }
                    Button(onClick = { checked = true; viewModel.logPractice(Skill.GRAMMAR, if (selected == question.correctIndex) 8.0 else 4.5, 90) }, enabled = selected in question.options.indices && !checked, modifier = Modifier.fillMaxWidth()) { Text("检查") }
                }
            }
        }
        item {
            AnimatedVisibility(checked) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (selected == question.correctIndex) "规则调用正确" else "把错误归入规则，而不是记答案", fontWeight = FontWeight.Bold, color = if (selected == question.correctIndex) Pine else Coral)
                        Text(question.explanationZh)
                        TextButton(onClick = { index = (safeIndex + 1) % questions.size }) { Text("下一题"); Icon(Icons.Rounded.ArrowForward, null) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MockTestScreen(viewModel: LearningViewModel) {
    var selected by rememberSaveable { mutableStateOf("20 分钟迷你模考") }
    var running by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableIntStateOf(20 * 60) }
    LaunchedEffect(running, seconds) {
        if (running && seconds > 0) { delay(1000); seconds-- }
        if (running && seconds == 0) { running = false; viewModel.logPractice(Skill.READING, 6.0, 1200) }
    }
    val options = listOf("20 分钟迷你模考", "60 分钟阅读套题", "完整听说读写模拟")
    LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("先练稳定，再练速度", style = MaterialTheme.typography.headlineMedium); Text("试题与计时都在本机运行。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(options) { option ->
            Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !running) { selected = option }, shape = RoundedCornerShape(18.dp), color = if (selected == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == option, null); Column { Text(option, fontWeight = FontWeight.Bold); Text(if (option.startsWith("20")) "听力 5 题 + 阅读 5 题 + 写作提纲" else "建议完成专项后使用", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = Ink) {
                Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text(if (running) "模考进行中 · 所有提示已隐藏" else selected, color = Color.White.copy(alpha = .75f))
                    Button(onClick = { running = !running }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0066CC))) { Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Text(if (running) "暂停" else "开始") }
                }
            }
        }
        item {
            SectionTitle("模考规则", null)
            Text("• 开始后不显示翻译、词义和即时答案\n• 完成整段后再查看错题标签\n• 复盘时间至少等于答题时间的一半", lineHeight = 26.sp)
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = Gold, modifier = Modifier.size(56.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
