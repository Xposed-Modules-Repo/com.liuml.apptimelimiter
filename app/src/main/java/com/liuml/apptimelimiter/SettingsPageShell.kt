package com.liuml.apptimelimiter

import android.graphics.Point
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.font.FontWeight
import com.liuml.apptimelimiter.ui.FunctionIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** Full-screen settings keeps drafts alive while switching categories. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SettingsPageShell(
    onDismissRequest: () -> Unit,
    selectedCategory: Int,
    onCategoryChange: (Int) -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val zh = context.resources.configuration.locales[0].language == "zh"
    val density = LocalDensity.current
    val screenHeightPx = remember(context.resources.configuration.orientation) {
        Point().also { context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealSize(it) }.y
    }
    var windowTopPx by remember { mutableIntStateOf(0) }
    val categories = if (zh) listOf("PIN 与安全", "保护方式", "外观", "提醒与延时", "统计与诊断", "应用设置", "数据与备份", "帮助与支持")
        else listOf("PIN & security", "Protection", "Appearance", "Reminders & extensions", "Statistics & diagnostics", "App settings", "Data & backups", "Help & support")
    val descriptions = if (zh) listOf(
        "管控锁、PIN 与每日解锁次数", "LSPosed、普通保护与强停增强", "颜色主题、明暗模式与时间短句",
        "使用时间提示、提醒与延时额度", "使用记录、诊断与日志", "桌面图标、最近任务与设备管理",
        "备份、恢复与远程备份", "使用说明、反馈与关于",
    ) else listOf(
        "Control Lock, PIN and daily unlock limit", "LSPosed, accessibility and force-stop enhancements",
        "Color theme, light/dark mode and phrases", "Usage tips, reminders and extension limits",
        "Usage records, diagnostics and logs", "Launcher icon, recent tasks and device administration",
        "Backup, restore and remote backups", "User guide, feedback and about",
    )
    var showingCategory by rememberSaveable { mutableStateOf(false) }
    val categoryIcons = listOf("lock", "shield", "appearance", "bell", "stats", "apps", "backup", "help")
    val keyboard = LocalSoftwareKeyboardController.current
    val navigateBack: () -> Unit = {
        if (showingCategory) {
            keyboard?.hide()
            showingCategory = false
        } else onDismissRequest()
    }
    // Some ROMs place a screen-height dialog below the status bar without reporting that
    // offset as a window inset. Measure its real screen position before sizing the footer.
    Dialog(
        onDismissRequest = navigateBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val top = coordinates.positionOnScreen().y.roundToInt().coerceAtLeast(0)
                if (windowTopPx != top) windowTopPx = top
            }) {
                val visibleHeight = with(density) {
                    (screenHeightPx - windowTopPx).coerceAtLeast(0).toDp()
                }
                Column(Modifier.fillMaxWidth().height(minOf(maxHeight, visibleHeight))
                    .safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ProvideTextStyle(if (showingCategory) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge) {
                            if (showingCategory) Text(categories[selectedCategory]) else title()
                        }
                    }
                    TextButton(onClick = navigateBack) {
                        FunctionIcon("back", Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showingCategory) { if (zh) "返回设置" else "Settings" } else { if (zh) "返回" else "Back" })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
                    if (showingCategory) Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) { Box(Modifier.padding(16.dp)) { text() } }
                    else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(categories, key = { index, _ -> index }) { index, label ->
                            if (index in listOf(2, 4, 6)) Spacer(Modifier.height(16.dp))
                            val first = index % 2 == 0
                            ListItem(
                                headlineContent = { Text(label, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(descriptions[index]) },
                                leadingContent = {
                                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                            FunctionIcon(categoryIcons[index], Modifier.size(23.dp), MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                },
                                trailingContent = { FunctionIcon("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(
                                    topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                                    bottomStart = if (!first) 16.dp else 0.dp, bottomEnd = if (!first) 16.dp else 0.dp,
                                )).clickable(role = Role.Button) {
                                    onCategoryChange(index)
                                    showingCategory = true
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            )
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    confirmButton()
                    if (showingCategory) TextButton(onClick = navigateBack) { Text(if (zh) "返回设置" else "Settings") }
                    else dismissButton()
                }
            }
            }
        }
    }
}
