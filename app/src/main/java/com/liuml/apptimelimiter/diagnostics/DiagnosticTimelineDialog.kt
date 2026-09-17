package com.liuml.apptimelimiter.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticTimelineDialog(repository: DiagnosticsRepository, english: Boolean,
                             onFeedback: () -> Unit, onClear: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    fun text(zh: String, en: String) = if (english) en else zh
    var raw by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DiagnosticIncident?>(null) }
    var incidents by remember { mutableStateOf(emptyList<DiagnosticIncident>()) }
    var events by remember { mutableStateOf(emptyList<DiagnosticTimelineEvent>()) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var cursor by remember { mutableStateOf(0L) }
    var revision by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    fun back() { if (selected != null) { selected = null; cursor = 0L } else onDismiss() }
    BackHandler { back() }
    LaunchedEffect(raw, selected?.incident, cursor, revision) {
        loading = true
        error = false
        try {
            val selection = selected
            if (raw) {
                logs = withContext(Dispatchers.IO) { repository.readLatest() }
                hasMore = false
            } else if (selection == null) {
                val page = withContext(Dispatchers.IO) { repository.timeline().incidents(if (cursor == 0L) Long.MAX_VALUE else cursor) }
                incidents = if (cursor == 0L) page else (incidents + page).distinctBy { it.incident }
                hasMore = page.size == DiagnosticTimelinePolicy.PAGE_SIZE
            } else {
                val page = withContext(Dispatchers.IO) { repository.timeline().events(selection.incident, cursor) }
                events = if (cursor == 0L) page else (events + page).distinctBy { it.id }
                hasMore = page.size == DiagnosticTimelinePolicy.PAGE_SIZE
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = { back() },
        title = { Text(if (raw) text("诊断日志", "Diagnostic log") else if (selected != null) text("事件时间线", "Event timeline") else text("管控事件", "Restriction events")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error) Text(text("读取失败，请刷新重试。", "Could not load. Refresh to retry."), color = MaterialTheme.colorScheme.error)
                if (loading) Text(text("读取中…", "Loading…"))
                selected?.let { Text("${it.packageName}\n${text("事件", "Event")} ${it.incident.take(12)}", style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (raw) {
                        items(logs) { Text(it.replace('\t', '\n'), style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                    } else if (selected == null) {
                        if (incidents.isEmpty() && !loading) item { Text(text("暂无事件记录。仅记录升级后产生的事件；旧记录可在原始日志中查看。", "No events yet. Timeline starts with this update; older records remain in the raw log.")) }
                        items(incidents, key = { it.incident }) { incident ->
                            Column(Modifier.fillMaxWidth().clickable { selected = incident; events = emptyList(); cursor = 0L }.padding(vertical = 8.dp)) {
                                Text(incident.packageName, style = MaterialTheme.typography.titleSmall)
                                Text("${dateFormat.format(Date(incident.wallMillis))} · ${incident.eventCount} ${text("条记录", "records")}", style = MaterialTheme.typography.bodySmall)
                                Text(incident.incident.take(12), style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider()
                        }
                    } else {
                        if (events.isEmpty() && !loading) item { Text(text("此事件记录已清理。", "This event has expired or was cleared.")) }
                        items(events, key = { it.id }) { event ->
                            Text(dateFormat.format(Date(event.wallMillis)), style = MaterialTheme.typography.labelSmall)
                            Text(stageLabel(event.stage, english), style = MaterialTheme.typography.titleSmall)
                            Text(listOf(event.result, event.reason).filter { it.isNotBlank() && it != "NONE" }
                                .joinToString(" · ") { resultLabel(it, english) }, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                    if (hasMore && !raw) item {
                        TextButton(enabled = !loading, onClick = { cursor = if (selected == null) incidents.lastOrNull()?.lastId ?: 0L else events.lastOrNull()?.id ?: 0L }) {
                            Text(text("加载更多", "Load more"))
                        }
                    }
                }
                if (!raw) Text(text("保留最近 7 天，最多 10,000 条。缺少记录不代表动作未发生。", "Last 7 days, up to 10,000 records. Missing records do not prove an action did not occur."), style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { raw = !raw; selected = null; cursor = 0L }) { Text(if (raw) text("按事件查看", "Events") else text("原始日志", "Raw log")) }
                    TextButton(enabled = !loading, onClick = { cursor = 0L; revision++ }) { Text(text("刷新", "Refresh")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { back() }) { Text(if (selected != null) text("返回", "Back") else text("关闭", "Close")) } },
        dismissButton = { Row {
            TextButton(onClick = onFeedback) { Text(text("文件反馈", "Share file")) }
            TextButton(onClick = { onClear { kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try { repository.clear(); withContext(Dispatchers.Main) { incidents = emptyList(); events = emptyList(); logs = emptyList(); selected = null; cursor = 0L; revision++ } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { withContext(Dispatchers.Main) { error = true } }
            } } }) { Text(text("清空", "Clear")) }
        } },
    )
}

private fun stageLabel(stage: String, english: Boolean): String {
    if (english) return stage.replace('_', ' ')
    val label = when {
        stage.contains("OVERRIDE_ACTIVE") -> "临时放行已激活"
        stage.contains("OVERRIDE_PENDING") -> "等待恢复目标应用"
        stage.startsWith("PIN_") || stage.contains("PARENT") || stage.contains("AUTH") -> "PIN 验证"
        stage.contains("AD") && (stage.startsWith("REWARDED") || stage.contains("WAITING_AD")) -> "广告延时"
        stage.contains("EXECUT") || stage.contains("FORCE_STOP") -> "限制执行与回退"
        stage.contains("RESTRICTION_VISIBLE") || stage.startsWith("BREAK_PAGE") -> "限制页面"
        stage.contains("CLAIM") -> "限制事件认领"
        stage.contains("COOLDOWN") -> "冷却"
        stage.contains("CANCEL") -> "事件结束"
        else -> "管控状态"
    }
    return "$label · $stage"
}

private fun resultLabel(value: String, english: Boolean): String = if (english) value.replace('_', ' ') else when (value) {
    "ACCEPTED", "OK" -> "已确认"
    "REQUESTED" -> "已请求"
    "DENIED", "REJECTED" -> "已拒绝"
    "FALLBACK_REQUIRED" -> "需回退基础限制"
    "EXECUTED", "SUCCESS" -> "执行成功"
    "ALREADY_EXECUTED" -> "此前已执行"
    "FAILED", "ERROR" -> "失败"
    "CANCELLED" -> "已取消"
    "EXPIRED" -> "已过期"
    "INFO" -> "状态记录"
    "WARN" -> "异常记录"
    else -> value
}
