package com.leap8.offline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leap8.offline.LearningViewModel
import com.leap8.offline.diagnostics.DeveloperTelemetrySnapshot
import com.leap8.offline.diagnostics.InvocationOutcome
import com.leap8.offline.diagnostics.InvocationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun DeveloperScreen(viewModel: LearningViewModel) {
    val state by viewModel.developerTelemetry.collectAsStateWithLifecycle()
    DeveloperDashboard(state, { viewModel.setDeveloperMonitoringEnabled(it) }, { viewModel.clearDeveloperHistory() })
}

/** Only receives redacted numeric telemetry. Never receives prompt, result, key or error bodies. */
@Composable
internal fun DeveloperDashboard(
    state: DeveloperTelemetrySnapshot,
    onEnabled: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val records = remember(state.active, state.recent) {
        (state.active + state.recent).distinctBy { it.id }
    }
    val selected = records.firstOrNull { it.id == selectedId } ?: records.firstOrNull()
    val groups = remember(records) { records.groupBy { Triple(it.mode, it.provider, it.processor) } }

    LazyColumn(
        modifier = Modifier.testTag("developer-dashboard"),
        state = listState,
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            DiagnosticCard {
                Text("开发者 · 性能监控", style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("记录调用性能", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.enabled) "已开启；仅 AI 调用期间采样" else "已关闭；历史记录仍可查看")
                    }
                    Switch(checked = state.enabled, onCheckedChange = onEnabled,
                        modifier = Modifier.semantics { contentDescription = "记录调用性能" })
                }
                Text("记录仅保存在本机。不会记录作文原文、生成内容、API 密钥或错误正文。关闭不会取消正在执行的 AI 任务。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("清空已完成的诊断记录")
                }
                if (state.storageWarning) Text("诊断存储暂时不可用；当前显示可能只保留在内存中。", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            DiagnosticCard {
                Text("统计口径与传感器", style = MaterialTheme.typography.titleLarge)
                Text("保留最近 100 次调用，每次最多 120 个采样点；汇总只覆盖这些保留记录，不是全时账单。", style = MaterialTheme.typography.bodySmall)
                Text("token 按来源区分：本地分词器、API 已返回用量、未报告。未知不计为 0，也不按字符数推算。失败、取消或进程结束可能没有完整计数。", style = MaterialTheme.typography.bodySmall)
                Text("本地量表不调用大模型，不消耗模型 token，未计入这里。API 输入用量缺失时显示未知；本页不是供应商计费账单。", style = MaterialTheme.typography.bodySmall)
                Text("状态只描述 AI 生成调用，不代表评分结果或 JSON 校验已通过。", style = MaterialTheme.typography.bodySmall)
                Text("温度曲线来源：Android 电池温度，不是芯片、NPU 或 GPU 温度。系统未开放的 SoC 温度及 GPU/NPU 占用率不作估算。", style = MaterialTheme.typography.bodySmall)
                Text("API 调用时曲线仍来自这部手机，不是云端服务器；调用耗时包含网络等待。", style = MaterialTheme.typography.bodySmall)
                Text("Java 堆仅是界面进程堆内存，不包含模型原生缓冲、DSP 或 GPU 显存。热状态是 Android 系统等级，不是摄氏温度。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            DiagnosticCard {
                Text("保留记录总览", style = MaterialTheme.typography.titleLarge)
                Text("调用中 ${state.active.size} · 已保留 ${state.recent.size} · 成功 ${records.count { it.outcome == InvocationOutcome.SUCCESS }}")
                TokenTotals(records)
            }
        }
        if (records.isEmpty()) {
            item {
                DiagnosticCard {
                    Text("尚无调用记录", style = MaterialTheme.typography.titleLarge)
                    Text("开启记录后，使用作文评分、精修对比或 AI 教练即可采集。进入本页不会自动运行模型或调用 API。")
                }
            }
        }
        if (state.active.isNotEmpty()) {
            item { Text("正在调用", style = MaterialTheme.typography.titleLarge) }
            items(state.active, key = { "active-${it.id}" }) { record ->
                InvocationSummary(record, selected?.id == record.id) { selectedId = record.id }
            }
        }
        selected?.let { record ->
            item {
                DiagnosticCard {
                    Text("选中调用 · ${record.mode.label}", style = MaterialTheme.typography.titleLarge)
                    Text("${startedTime(record.startedAtMs)} · ${record.outcome.label}")
                    Text("${record.provider.label} · 最后上报处理器：${record.processor?.label ?: "未报告"}")
                    if (record.model.isNotBlank()) Text("模型：${record.model}")
                    Text("尝试路由：${record.route.joinToString(" → ") { it.label }.ifBlank { "未报告" }}")
                    Text("输入 token：${tokenValue(record.inputTokens)} · 输出 token：${tokenValue(record.outputTokens)}")
                    Text("计数来源：${tokenSourceLabel(record.tokenSource)}")
                    Text("调用端到端耗时：${seconds(record.elapsedMs)} 秒（含启动、检查和整理阶段）")
                    Text("首次带计数进度到达：${record.firstProgressMs?.let { "${seconds(it)} 秒" } ?: "未报告"}；后端可能仅完成时上报，不代表首 token 延迟")
                    Text("原生生成阶段速度：${decodeRate(record)}")
                    Text("端到端输出吞吐：${endToEndRate(record)}（不是纯解码速度）")
                    Text("请求参数 temperature：${record.samplingTemperature?.let { decimal(it.toDouble()) } ?: "未发送/未报告"}；非温度传感器，后端可能忽略。当前 Hexagon NPU 使用贪心采样。", style = MaterialTheme.typography.bodySmall)
                    Text("采样 ${record.samples.size} 点 · 记录中的处理器标签不代表硬件利用率。", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                DiagnosticCard {
                    MetricChart("电池温度趋势", "°C", record.samples.map { it.elapsedMs to it.batteryTempC?.toDouble() }, "battery-temperature-chart")
                    Text("仅显示实际获得的电池温度，缺失点不连线；不能据此判定 NPU/GPU 核心温度。", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                DiagnosticCard {
                    MetricChart("系统可用内存趋势", "GiB", record.samples.map { it.elapsedMs to it.availableMemoryBytes?.div(1073741824.0) }, "available-memory-chart")
                    MetricChart("界面进程 Java 堆趋势", "MiB", record.samples.map { it.elapsedMs to it.javaHeapBytes?.div(1048576.0) }, "java-heap-chart")
                    Text("最近系统热状态：${thermalLabel(record.samples.lastOrNull()?.thermalStatus)}")
                }
            }
        }
        if (groups.isNotEmpty()) {
            item { Text("按功能与运行模式汇总", style = MaterialTheme.typography.titleLarge) }
            items(groups.entries.toList(), key = { "group-${it.key}" }) { (key, values) ->
                DiagnosticCard {
                    Text("${key.first.label} · ${key.second.label} · ${key.third?.label ?: "未报告处理器"}", fontWeight = FontWeight.SemiBold)
                    Text("${values.size} 次 · 成功 ${values.count { it.outcome == InvocationOutcome.SUCCESS }} · 端到端合计 ${seconds(values.sumOf { it.elapsedMs })} 秒")
                    TokenTotals(values)
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item { Text("最近调用 · 点击查看曲线", style = MaterialTheme.typography.titleLarge) }
            items(state.recent, key = { "recent-${it.id}" }) { record ->
                InvocationSummary(record, selected?.id == record.id) {
                    selectedId = record.id
                    scope.launch { listState.animateScrollToItem(3 + if (state.active.isEmpty()) 0 else state.active.size + 1) }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空诊断记录？") },
        text = { Text("只清除已完成的性能记录；正在调用的记录、学习进度、模型和 API 配置均保留。") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("仅清空诊断") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
    )
}

@Composable
private fun DiagnosticCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun TokenTotals(records: List<InvocationRecord>) {
    Text("已报告输入：${records.sumOf { (it.inputTokens ?: 0).toLong() }} token · 未知 ${records.count { it.inputTokens == null }} 次")
    Text("已报告输出：${records.sumOf { (it.outputTokens ?: 0).toLong() }} token · 未知 ${records.count { it.outputTokens == null }} 次")
}

@Composable
private fun InvocationSummary(record: InvocationRecord, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("invocation-${record.id}"),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${record.mode.label} · ${record.outcome.label}", fontWeight = FontWeight.SemiBold)
            Text("${startedTime(record.startedAtMs)} · ${record.provider.label} · ${record.processor?.label ?: "未报告处理器"}")
            Text("输入 ${tokenValue(record.inputTokens)} / 输出 ${tokenValue(record.outputTokens)} token · ${seconds(record.elapsedMs)} 秒")
            if (selected) Text("当前选中", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MetricChart(title: String, unit: String, series: List<Pair<Long, Double?>>, tag: String) {
    val actual = series.mapNotNull { it.second?.takeIf { value -> value.isFinite() } }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (actual.isEmpty()) {
        Text("没有可用采样，传感器可能不可读或调用过短。", Modifier.testTag(tag))
        return
    }
    val minimum = actual.min()
    val maximum = actual.max()
    val spread = (maximum - minimum).coerceAtLeast(if (unit == "°C") 1.0 else .1)
    val duration = (series.maxOfOrNull { it.first } ?: 0).coerceAtLeast(1)
    Text("最小 ${decimal(minimum)} / 最大 ${decimal(maximum)} / 最近 ${decimal(actual.last())} $unit")
    Canvas(Modifier.fillMaxWidth().height(128.dp).testTag(tag).semantics {
        contentDescription = "$title，最小 ${decimal(minimum)}，最大 ${decimal(maximum)} $unit，时间跨度 ${seconds(duration)} 秒"
    }) {
        val padding = 6.dp.toPx()
        val chartWidth = (size.width - 2 * padding).coerceAtLeast(1f)
        val chartHeight = (size.height - 2 * padding).coerceAtLeast(1f)
        repeat(3) { row ->
            val y = padding + chartHeight * row / 2
            drawLine(grid, Offset(padding, y), Offset(padding + chartWidth, y), 1.dp.toPx())
        }
        var previous: Offset? = null
        series.forEach { (elapsed, raw) ->
            val value = raw?.takeIf { it.isFinite() }
            if (value == null) previous = null else {
                val point = Offset(
                    padding + chartWidth * (elapsed.toDouble() / duration).coerceIn(0.0, 1.0).toFloat(),
                    padding + chartHeight * (1.0 - (value - minimum) / spread).coerceIn(0.0, 1.0).toFloat()
                )
                previous?.let { drawLine(line, it, point, 2.dp.toPx()) }
                drawCircle(line, 2.5.dp.toPx(), point)
                previous = point
            }
        }
    }
    Text("0 → ${seconds(duration)} 秒 · 时间与采样点同步，未插补缺失值", style = MaterialTheme.typography.bodySmall)
}

private fun tokenValue(value: Int?) = value?.toString() ?: "未知"
private fun tokenSourceLabel(value: String): String = when (value) {
    "LOCAL_TOKENIZER" -> "本地分词器（真实 token）"
    "API_COMPLETION_USAGE" -> "API 已返回的输出用量（输入未知）"
    "UNAVAILABLE", "", "未报告" -> "未报告（不使用字符数估算）"
    else -> value
}
private fun decimal(value: Double) = String.format(Locale.ROOT, "%.2f", value)
private fun seconds(ms: Long) = decimal(ms.coerceAtLeast(0) / 1000.0)
private fun startedTime(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
private fun decodeRate(record: InvocationRecord): String =
    if (record.outputTokens != null && record.decodeMs != null && record.decodeMs > 0)
        "${decimal(record.outputTokens * 1000.0 / record.decodeMs)} token/s（含原生采样/流回调，非纯内核时间）" else "未知/未报告原生耗时"
private fun endToEndRate(record: InvocationRecord): String =
    if (record.outputTokens != null && record.elapsedMs > 0)
        "${decimal(record.outputTokens * 1000.0 / record.elapsedMs)} token/s" else "未知"
private fun thermalLabel(status: Int?): String = when (status) {
    0 -> "NONE · 无热限制"
    1 -> "LIGHT · 轻度"
    2 -> "MODERATE · 中度"
    3 -> "SEVERE · 严重"
    4 -> "CRITICAL · 临界"
    5 -> "EMERGENCY · 紧急"
    6 -> "SHUTDOWN · 关机级"
    else -> "不可读/未采样"
}
