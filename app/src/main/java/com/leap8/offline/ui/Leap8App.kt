@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.leap8.offline.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.leap8.offline.speech.NaturalVoice
import com.leap8.offline.speech.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leap8.offline.LearningViewModel
import com.leap8.offline.ai.LocalAiMemoryStage
import com.leap8.offline.ai.LocalAiMemoryStatus
import com.leap8.offline.domain.*
import com.leap8.offline.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    NavItem("home", "今日", Icons.Rounded.Home),
    NavItem("path", "路径", Icons.Rounded.Route),
    NavItem("practice", "练习", Icons.Rounded.Widgets),
    NavItem("progress", "进度", Icons.Rounded.Insights),
    NavItem("profile", "我的", Icons.Rounded.Person)
)

private val routeTitles = mapOf(
    "home" to "跃升英语", "path" to "学习路径", "practice" to "专项练习",
    "progress" to "能力进度", "profile" to "AI 与离线中心", "vocabulary" to "记忆工坊",
    "wordbook" to "IELTS 总词本",
    "listening" to "精听训练", "speaking" to "口语实验室", "reading" to "阅读训练",
    "writing" to "写作评阅", "revision" to "AI 精修对比", "coach" to "离线 AI 教练", "grammar" to "语法诊所", "mock" to "模考中心",
    "developer" to "开发者 · 性能监控"
)

@Composable
fun Leap8App(viewModel: LearningViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val isMain = bottomItems.any { it.route == route }
    val storageError by viewModel.storageError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(storageError) {
        storageError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStorageError(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(viewModel, routeTitles[route] ?: "跃升英语", !isMain) { navController.popBackStack() } },
        bottomBar = {
            if (isMain && !WindowInsets.isImeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        ) {
            composable("home") { HomeScreen(viewModel, navController) }
            composable("path") { LearningPathScreen(viewModel, navController) }
            composable("practice") { PracticeHubScreen(navController) }
            composable("progress") { ProgressScreen(viewModel) }
            composable("profile") { ProfileScreen(viewModel, navController) }
            composable("developer") { DeveloperScreen(viewModel) }
            composable("vocabulary") { VocabularyScreen(viewModel) { navController.navigate("wordbook") } }
            composable("wordbook") { WordbookScreen(viewModel) }
            composable("listening") { ListeningScreen(viewModel) }
            composable("speaking") { SpeakingScreen(viewModel) }
            composable("reading") { ReadingScreen(viewModel) }
            composable("writing") { WritingScreen(viewModel) }
            composable("revision") { AiRevisionScreen(viewModel) }
            composable("coach") { AiCoachScreen(viewModel) }
            composable("grammar") { GrammarScreen(viewModel) }
            composable("mock") { MockTestScreen(viewModel) }
        }
    }
}

@Composable
private fun AppHeader(viewModel: LearningViewModel, title: String, canGoBack: Boolean, onBack: () -> Unit) {
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            RuntimePill(settings.selectedProvider, runtime)
        }
    }
}

@Composable
private fun RuntimePill(provider: AiProvider, runtime: AiRuntimeStatus) {
    val label = when {
        provider != AiProvider.LOCAL -> "${provider.label} · API 模式"
        runtime.processor == AiProcessor.GPU -> "完全离线 · 上次使用 GPU + CPU"
        runtime.processor != AiProcessor.RULES -> "完全离线 · 上次使用 ${runtime.processor.label}"
        runtime.ready -> "完全离线 · 模型已就绪"
        else -> "完全离线 · 本地学习与量表"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (provider == AiProvider.LOCAL) Icons.Rounded.OfflineBolt else Icons.Rounded.Cloud, null, tint = Pine, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HomeScreen(viewModel: LearningViewModel, nav: NavHostController) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("今天，继续向前。", style = MaterialTheme.typography.headlineMedium)
                Text("记住一个词，听懂一句话，把进步留在今天。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { BandHero(dashboard) }
        item {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.2f) 1 else 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactMetric("连续", "${dashboard.streakDays} 天", Icons.Rounded.LocalFireDepartment, Coral, Modifier.weight(1f))
                CompactMetric("今日", "${dashboard.todayMinutes}/${dashboard.todayGoalMinutes} 分", Icons.Rounded.Timer, Blue, Modifier.weight(1f))
                CompactMetric("总词本", "${dashboard.learnedWords}/${dashboard.totalWords}", Icons.Rounded.AutoStories, Pine, Modifier.weight(1f))
            }
        }
        item { SectionTitle("今日训练", "约 45 分钟") }
        item {
            DailyTaskCard(
                index = "01", title = "间隔复习", subtitle = "先清理到期词，再学 8 个新词",
                meta = "12 分钟 · ${dashboard.dueWords} 张", icon = Icons.Rounded.Style,
                onClick = { nav.navigate("vocabulary") }
            )
        }
        item {
            DailyTaskCard(
                index = "02", title = "精听与复述", subtitle = "抓住转折后的关键信息",
                meta = "10 分钟 · B1", icon = Icons.Rounded.Headphones,
                onClick = { nav.navigate("listening") }
            )
        }
        item {
            DailyTaskCard(
                index = "03", title = "Task 2 微写作", subtitle = "只改进一个主体段的因果链",
                meta = "20 分钟 · 本地评分", icon = Icons.Rounded.EditNote,
                onClick = { nav.navigate("writing") }
            )
        }
        item { SectionTitle("快速开始", "六项能力") }
        item { QuickSkills(nav) }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Rounded.TipsAndUpdates, null, tint = Gold, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text("今日策略", fontWeight = FontWeight.Bold)
                        Text("先尝试回忆，再查看答案。检索本身就是学习，而不只是检测。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BandHero(dashboard: DashboardSnapshot) {
    Surface(color = Ink, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YOUR LEARNING JOURNEY", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelSmall)
            Text("向 IELTS 8.0 前进", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("${dashboard.estimatedBand} / 8.0", color = Color.White, style = MaterialTheme.typography.displaySmall)
            LinearProgressIndicator(progress = { (dashboard.estimatedBand / 8.0).toFloat().coerceIn(0f, 1f) },
                color = Color(0xFF2997FF), trackColor = Color.White.copy(alpha = .18f),
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            Text("本机训练能力估算 · 不代表正式考试成绩", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyTaskCard(index: String, title: String, subtitle: String, meta: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Pine)
            }
            Column(Modifier.weight(1f)) {
                Text("$index  $title", fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(meta, style = MaterialTheme.typography.labelSmall, color = PineLight)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun QuickSkills(nav: NavHostController) {
    val skills = listOf(
        Triple("词汇", Icons.Rounded.Style, "vocabulary"), Triple("听力", Icons.Rounded.Headphones, "listening"),
        Triple("口语", Icons.Rounded.GraphicEq, "speaking"), Triple("阅读", Icons.Rounded.MenuBook, "reading"),
        Triple("写作", Icons.Rounded.EditNote, "writing"), Triple("语法", Icons.Rounded.AccountTree, "grammar")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        skills.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, icon, route) ->
                    Surface(
                        modifier = Modifier.weight(1f).clickable { nav.navigate(route) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon, null, tint = Pine)
                            Spacer(Modifier.height(6.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningPathScreen(viewModel: LearningViewModel, nav: NavHostController) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("从基础到 8 分的能力地图", style = MaterialTheme.typography.headlineMedium)
            Text("阶段可自由进入；诊断结果只改变每日任务比例，不锁住内容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(viewModel.levels, key = { it.id }, contentType = { "learning-level" }) { level ->
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(Color(level.color), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Route, null, tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(level.title, style = MaterialTheme.typography.titleLarge)
                            Text(level.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${level.weeks} 周", style = MaterialTheme.typography.labelLarge, color = Pine)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        level.modules.take(4).forEach { module ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(99.dp)) {
                                Text(module, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { if (level.id == "upper") .38f else if (level.id in listOf("foundation", "elementary", "intermediate")) 1f else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Color(level.color),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    TextButton(onClick = { nav.navigate(if (level.id.startsWith("ielts")) "mock" else "practice") }) {
                        Text(if (level.id == "upper") "继续本阶段" else "查看阶段训练")
                        Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeHubScreen(nav: NavHostController) {
    val modules = listOf(
        PracticeModule("vocabulary", "记忆工坊", "间隔复习 · 词根词缀 · 搭配", Icons.Rounded.Style, Pine),
        PracticeModule("listening", "精听训练", "盲听 · 听写 · 影子跟读", Icons.Rounded.Headphones, Blue),
        PracticeModule("speaking", "口语实验室", "离线识别 · 跟读 · 复述", Icons.Rounded.GraphicEq, Coral),
        PracticeModule("reading", "阅读训练", "定位 · 同义替换 · 计时", Icons.Rounded.MenuBook, Color(0xFF76639E)),
        PracticeModule("writing", "写作评阅", "四项量表 · 本地大模型深度反馈", Icons.Rounded.EditNote, Color(0xFF99624A)),
        PracticeModule("revision", "AI 精修对比", "完整修正版 · 修正前后 · 中文理由", Icons.Rounded.AutoFixHigh, Color(0xFF3F7F73)),
        PracticeModule("coach", "离线 AI 教练", "纠错 · 改写 · 精讲 · 迁移练习", Icons.Rounded.Psychology, Gold),
        PracticeModule("grammar", "语法诊所", "错因归类 · 句型改写", Icons.Rounded.AccountTree, Color(0xFF5B8C85)),
        PracticeModule("mock", "模考中心", "限时套题 · 分项复盘", Icons.Rounded.Assignment, Color(0xFF334B62))
    )
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("把弱项练成可迁移能力", style = MaterialTheme.typography.headlineMedium)
            Text("每次只练一个认知动作，并立即查看证据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(modules, key = { it.route }, contentType = { "practice-module" }) { module ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { nav.navigate(module.route) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Icon(module.icon, null, tint = Pine, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(module.title, style = MaterialTheme.typography.titleMedium)
                        Text(module.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
    }
}

private data class PracticeModule(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

@Composable
private fun ProgressScreen(viewModel: LearningViewModel) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("证据，而不是感觉", style = MaterialTheme.typography.headlineMedium)
            Text("能力估算只使用本机训练记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("六项能力概览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
                    SkillRadar(dashboard.skillScores, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.2f) 1 else 3, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressMetric("已学词汇", "${dashboard.learnedWords}/${dashboard.totalWords}", "总词本", Modifier.weight(1f))
                ProgressMetric("训练回合", dashboard.completedSessions.toString(), "本机", Modifier.weight(1f))
                ProgressMetric("已掌握", dashboard.masteredWords.toString(), "间隔 ≥21天", Modifier.weight(1f))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("IELTS 总词本", fontWeight = FontWeight.Bold)
                        Text("${dashboard.learnedWords} / ${dashboard.totalWords}", color = Pine)
                    }
                    LinearProgressIndicator(
                        progress = { if (dashboard.totalWords == 0) 0f else dashboard.learnedWords.toFloat() / dashboard.totalWords },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Pine
                    )
                    Text("新词 ${dashboard.newWords} · 学习中 ${dashboard.learningWords} · 已掌握 ${dashboard.masteredWords} · 待复习 ${dashboard.dueWords}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle("能力诊断", "最近记录") }
        items(Skill.entries) { skill ->
            val value = dashboard.skillScores[skill] ?: .35f
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(skill.zh, fontWeight = FontWeight.Bold)
                        Text("${(value * 100).toInt()}%", color = Pine)
                    }
                    LinearProgressIndicator(
                        progress = { value }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                        color = if (value < .5f) Coral else Pine, trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(skillAdvice(skill, value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SkillRadar(values: Map<Skill, Float>, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Skill.entries.forEach { skill ->
            val value = (values[skill] ?: .35f).coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(skill.zh, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Pine)
            }
            LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Pine)
        }
    }
}

@Composable
private fun ProgressMetric(label: String, value: String, suffix: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = Pine)
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(suffix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun skillAdvice(skill: Skill, value: Float): String = if (value < .5f) when (skill) {
    Skill.VOCABULARY -> "先稳定提取高频词块，再扩大词量。"
    Skill.LISTENING -> "优先练转折、数字与同义替换。"
    Skill.SPEAKING -> "用短句准确复述，再延长回答。"
    Skill.READING -> "先定位证据，避免凭常识选择。"
    Skill.WRITING -> "每段补齐主张—机制—结果链。"
    Skill.GRAMMAR -> "建立错因标签，每次只修一种错误。"
} else "进入稳定区：增加限时与陌生题迁移。"

@Composable
private fun ProfileScreen(viewModel: LearningViewModel, nav: NavHostController) {
    val status by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val memoryStatus by viewModel.memoryStatus.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val settings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val settingsMessage by viewModel.aiSettingsMessage.collectAsStateWithLifecycle()
    val selected = settings.selectedProvider
    val selectedConfig = settings.config(selected)
    var endpoint by rememberSaveable(selected) { mutableStateOf(selectedConfig.endpoint) }
    var model by rememberSaveable(selected) { mutableStateOf(selectedConfig.model) }
    var apiKey by rememberSaveable(selected) { mutableStateOf("") }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importAiModel(uri)
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("你的学习设置", style = MaterialTheme.typography.headlineMedium)
            Text("自然发音、AI 引擎和离线内容，都在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("developer-entry").clickable { nav.navigate("developer") },
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Insights, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text("开发者 · 性能监控", style = MaterialTheme.typography.titleMedium)
                        Text("token 用量 · 调用耗时 · 电池温度与内存趋势", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { VoicePreferencesPanel() }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("AI 引擎", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AiProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = selected == provider,
                                onClick = { viewModel.selectAiProvider(provider) },
                                label = { Text(provider.label) },
                                leadingIcon = if (selected == provider) {{ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }} else null
                            )
                        }
                    }
                    Text(selected.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected != AiProvider.LOCAL) {
                        OutlinedTextField(
                            value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("HTTPS API 地址") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("模型名称（可编辑）") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (selectedConfig.keyConfigured) "API Key（已保存；留空表示不更换）" else "API Key") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true
                        )
                        Text("密钥由 Android Keystore 加密且禁止备份。只有主动运行 AI 功能时，当前输入文本才会发送给所选服务商。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.saveAiProvider(selected, endpoint, model, apiKey.ifBlank { null }); apiKey = "" },
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(6.dp)); Text("安全保存") }
                            if (selectedConfig.keyConfigured) {
                                OutlinedButton(onClick = { viewModel.clearAiProviderKey(selected); apiKey = "" }) { Text("删除密钥") }
                            }
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("本地模式不会建立网络连接；实际路由和当前处理器显示在下方。", Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    settingsMessage?.let { Text(it, color = if (it.contains("失败")) Coral else Pine, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (selected == AiProvider.LOCAL) {
            item { LocalAiMemoryPanel(memoryStatus) }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = if (status.ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).background(Pine, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("当前 AI 运行状态", style = MaterialTheme.typography.titleLarge)
                            Text(
                                when {
                                    selected != AiProvider.LOCAL && status.ready -> "API 已配置"
                                    selected != AiProvider.LOCAL -> "API 尚未配置"
                                    status.ready && status.processor == AiProcessor.RULES -> "模型已就绪，处理器待验证"
                                    status.ready -> "本地推理已验证"
                                    else -> "离线模型未就绪"
                                },
                                color = if (status.ready) Pine else Coral
                            )
                        }
                    }
                    StatusRow("运行模式", status.mode)
                    if (selected == AiProvider.LOCAL) {
                        StatusRow("此模型上次成功", if (status.ready && status.processor == AiProcessor.RULES) "尚未运行" else status.processor.label)
                    } else {
                        StatusRow("API 提供商", selected.label)
                    }
                    if (status.fallbackChain.isNotEmpty()) StatusRow("候选与回退", status.fallbackChain.joinToString(" → ") { it.label })
                    if (status.isSnapdragon8Elite) StatusRow("芯片优化", "Snapdragon 8 Elite · SM8750")
                    StatusRow("模型", status.model)
                    Text(status.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).background(Gold.copy(alpha = .18f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DownloadForOffline, null, tint = Pine)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("离线大模型", style = MaterialTheme.typography.titleLarge)
                            Text(if (modelState.ready) "${modelState.fileName} · ${modelState.sizeGiB}" else "推荐 Qwen3-4B · Q4_K_M", color = Pine)
                        }
                    }
                    if (modelState.phase == AiModelPhase.IMPORTING) {
                        LinearProgressIndicator(
                            progress = { modelState.progress },
                            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                            color = Pine,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    Text(modelState.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (modelState.sha256 != null) {
                        StatusRow("SHA-256", "${modelState.sha256!!.take(12)}…")
                    }
                    Text(
                        "推荐文件 2.33 GiB，4B 参数，4096-token 上下文；是否能安全加载取决于当前可用内存，每次运行前自动检查。导入过程会复制到 App 私有存储。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                        enabled = modelState.phase != AiModelPhase.IMPORTING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (modelState.ready) Icons.Rounded.SwapHoriz else Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (modelState.ready) "更换本地 GGUF 模型" else "从本地存储导入 GGUF")
                    }
                }
            }
        }
        item { SectionTitle("隐私与存储", null) }
        item { SettingRow(Icons.Rounded.WifiOff, "网络访问", if (selected == AiProvider.LOCAL) "本地模式：不发起网络请求" else "API 模式：仅主动生成时请求 ${selected.label}", selected == AiProvider.LOCAL) }
        item { SettingRow(Icons.Rounded.Storage, "学习记录", "仅保存在设备 SQLite", true) }
        item { SettingRow(Icons.Rounded.Translate, "界面语言", "简体中文 · 繁體包预留", false) }
        item { SettingRow(Icons.Rounded.VolumeUp, "离线语音", "Kokoro 自然语音 · 英美口音 · 系统 TTS 回退", true) }
        item { SectionTitle("内容包", "安装后无需联网") }
        item { ContentPack("基础—B1 核心课程", "词汇、语法、生活场景", "已安装", 1f) }
        item { ContentPack("B2—IELTS 学术课程", "精听、阅读、写作题库", "已安装", 1f) }
        item { ContentPack("IELTS 总词本", "ECDICT 全部 5,040 个 IELTS 标签词 + 8 个不重复精选词", "5,048 词", 1f) }
        item { ContentPack("Kokoro 自然语音", "82M 模型 · 英美口音 · 多种声线 · 完全离线", "已安装", 1f) }
        item { ContentPack("Qwen3-4B 本地模型", "GGUF Q4_K_M · 中英双语 AI 教练", if (modelState.ready) "已安装" else "待导入", if (modelState.ready) 1f else modelState.progress) }
        item {
            val npuCandidate = AiProcessor.NPU in status.fallbackChain
            ContentPack(
                "Snapdragon NPU 通道",
                "Hexagon HTP v79 · 直接运行本地 GGUF · 自动故障回退",
                when {
                    selected != AiProvider.LOCAL -> "切换本地模式查看状态"
                    status.processor == AiProcessor.NPU -> "运行已验证"
                    npuCandidate -> "待真机验证"
                    else -> "此设备暂无可用 NPU 候选"
                },
                if (status.processor == AiProcessor.NPU) 1f else 0f
            )
        }
        item {
            Text(
                "跃升英语提供学习路径与诊断工具，但任何应用都不能保证特定考试成绩。真实提分取决于起点、练习质量、学习时间与考试发挥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun LocalAiMemoryPanel(status: LocalAiMemoryStatus) {
    val busy = status.stage == LocalAiMemoryStage.CHECKING || status.stage == LocalAiMemoryStage.CLEANING
    Surface(modifier = Modifier.testTag("local-ai-memory-protection"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("自动内存保护 · 已开启", style = MaterialTheme.typography.titleLarge)
            Text(
                when (status.stage) {
                    LocalAiMemoryStage.IDLE -> "等待本地 AI 任务"
                    LocalAiMemoryStage.CHECKING -> "正在检查可用内存"
                    LocalAiMemoryStage.CLEANING -> "内存不足，正在释放本应用可回收资源"
                    LocalAiMemoryStage.READY -> "本次检查通过"
                    LocalAiMemoryStage.BLOCKED -> "当前内存仍不足，已阻止高风险加载"
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = if (status.stage == LocalAiMemoryStage.BLOCKED) Coral else Pine
            )
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (status.requiredBytes > 0) {
                StatusRow("最近采样可用内存 / 预计启动需求", "${memoryGiB(status.availableBytes)} / ${memoryGiB(status.requiredBytes)}")
            }
            if (status.cleanupAttempted) {
                StatusRow("清理前可用 / 最近复检可用", "${memoryGiB(status.beforeBytes)} / ${memoryGiB(status.availableBytes)}")
                Text("这些是系统可用内存快照，会受其他应用影响，不代表本应用实际释放量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(status.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "不足时自动释放本应用的语音模型和缓存，再次检测；不会删除模型文件、学习记录或 API 密钥，也不会强制关闭其他应用。仍不足时停止本地加载，请关闭其他大型应用后重试；如需 API，可自行切换，不会自动联网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun memoryGiB(bytes: Long): String = java.lang.String.format(
    java.util.Locale.ROOT, "%.2f GiB", bytes.coerceAtLeast(0) / 1024.0 / 1024.0 / 1024.0
)

@Composable
private fun StatusRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, verified: Boolean) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Icon(icon, null, tint = Pine)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (verified) Icon(Icons.Rounded.Verified, null, tint = Pine)
        }
    }
}

@Composable
private fun ContentPack(title: String, subtitle: String, state: String, progress: Float) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(state, style = MaterialTheme.typography.labelMedium, color = if (progress == 1f) Pine else Coral)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Pine, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(title: String, trailing: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun VoicePreferencesPanel() {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(VoiceCatalog.DEFAULT_VOICE_ID) }
    var speed by rememberSaveable { mutableFloatStateOf(VoiceCatalog.DEFAULT_SPEED) }
    var status by remember { mutableStateOf("Kokoro · 英美口音 · 无需联网") }
    val voice = remember { NaturalVoice(context) { _, message -> status = message } }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("natural_voice", android.content.Context.MODE_PRIVATE)
            VoiceCatalog.resolve(prefs.getString("voice_id", null)).id to VoiceCatalog.normalizeSpeed(prefs.getFloat("speed", 1f))
        }
        selected = loaded.first
        speed = loaded.second
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("听见更自然的英语", style = MaterialTheme.typography.titleLarge)
            Text("选择你喜欢的声音。词汇、例句和听力会使用同一声线。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VoiceCatalog.voices.forEach { option ->
                    FilterChip(selected = selected == option.id, onClick = {
                        selected = option.id
                        voice.configure(selected, speed)
                    }, label = { Text(option.label) })
                }
            }
            Text("语速 ${"%.2f".format(speed)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = { voice.configure(selected, speed) }, valueRange = .7f..1.3f, steps = 11)
            Button(onClick = {
                voice.configure(selected, speed)
                voice.speak("Learning English opens a world of possibilities. Take one small step every day.")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.VolumeUp, null)
                Spacer(Modifier.width(8.dp))
                Text("试听所选声音", modifier = Modifier.weight(1f))
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
