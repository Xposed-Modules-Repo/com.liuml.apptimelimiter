package com.liuml.apptimelimiter

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.GlobalSettings
import com.liuml.apptimelimiter.data.InstalledApp
import com.liuml.apptimelimiter.data.InstalledAppsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.support.FeedbackSender
import com.liuml.apptimelimiter.update.ReleaseInfo
import com.liuml.apptimelimiter.update.UpdateCheckResult
import com.liuml.apptimelimiter.update.UpdateChecker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruleRepository = RuleRepository(this)
        val diagnosticsRepository = DiagnosticsRepository(this)
        val apps = InstalledAppsRepository(this).loadLaunchableApps()
        setContent {
            AppTimeLimiterTheme {
                TimeLimiterScreen(apps, ruleRepository, diagnosticsRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeLimiterScreen(
    apps: List<InstalledApp>,
    repository: RuleRepository,
    diagnosticsRepository: DiagnosticsRepository,
) {
    val context = LocalContext.current
    val rules = remember {
        mutableStateMapOf<String, AppRule>().also { map ->
            apps.forEach { map[it.packageName] = repository.getRule(it.packageName) }
        }
    }
    var search by remember { mutableStateOf("") }
    var onlyEnabled by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showSetupGuide by remember {
        mutableStateOf(
            !context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("setup_guide_acknowledged", false),
        )
    }

    val visibleApps = remember(apps, search, onlyEnabled, rules.toMap()) {
        apps.filter { app ->
            (!onlyEnabled || rules[app.packageName]?.enabled == true) &&
                (search.isBlank() || app.label.contains(search, true) || app.packageName.contains(search, true))
        }
    }

    Scaffold(
        containerColor = Color(0xFFF7F8FC),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("应用计时退出", fontWeight = FontWeight.SemiBold)
                        Text(
                            "已启用 ${rules.values.count { it.enabled }} 个应用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F8FC)),
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SetupCard(
                    onOpenGuide = { showSetupGuide = true },
                    onOpenLogs = { showLogs = true },
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用或包名") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(10.dp))
                FilterChip(
                    selected = onlyEnabled,
                    onClick = { onlyEnabled = !onlyEnabled },
                    label = { Text("仅看已启用") },
                )
            }

            items(visibleApps, key = { it.packageName }) { app ->
                val rule = rules.getValue(app.packageName)
                AppRow(
                    app = app,
                    rule = rule,
                    onClick = { editingApp = app },
                    onToggle = { enabled ->
                        if (enabled) {
                            editingApp = app
                        } else {
                            val updated = rule.copy(enabled = false)
                            repository.save(updated)
                            if (repository.getGlobalSettings().diagnosticsEnabled) {
                                diagnosticsRepository.append(
                                    "INFO",
                                    app.packageName,
                                    "RULE_SAVED",
                                    "规则已停用",
                                )
                            }
                            rules[app.packageName] = repository.getRule(app.packageName)
                        }
                    },
                )
            }
        }
    }

    editingApp?.let { app ->
        RuleDialog(
            app = app,
            initialRule = rules.getValue(app.packageName),
            onDismiss = { editingApp = null },
            onSave = { configuredRule ->
                repository.save(configuredRule)
                if (repository.getGlobalSettings().diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        app.packageName,
                        "RULE_SAVED",
                        "enabled=${configuredRule.enabled}, daily=${configuredRule.dailyEnabled}/${configuredRule.dailyLimitSeconds}s, perLaunch=${configuredRule.perLaunchEnabled}/${configuredRule.perLaunchLimitSeconds}s",
                    )
                }
                rules[app.packageName] = repository.getRule(app.packageName)
                editingApp = null
            },
        )
    }

    if (showLogs) {
        DiagnosticLogDialog(
            repository = diagnosticsRepository,
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
            onDismiss = { showLogs = false },
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialSettings = repository.getGlobalSettings(),
            onDismiss = { showSettings = false },
            onDonate = { openAlipayDonation(context) },
            onCheckUpdates = {
                showSettings = false
                checkingUpdate = true
                UpdateChecker.check { result ->
                    checkingUpdate = false
                    updateResult = result
                }
            },
            onAbout = {
                showSettings = false
                showAbout = true
            },
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
            onSave = { settings ->
                repository.saveGlobalSettings(settings)
                if (settings.diagnosticsEnabled) {
                    diagnosticsRepository.append(
                        "INFO",
                        context.packageName,
                        "SETTINGS_SAVED",
                        "warning=${settings.exitWarningEnabled}, extension=${settings.extensionSeconds}s, diagnostics=true",
                    )
                }
                showSettings = false
            },
        )
    }

    if (checkingUpdate) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Text("正在连接 GitHub…") },
            confirmButton = {},
        )
    }

    updateResult?.let { result ->
        UpdateResultDialog(
            result = result,
            onDismiss = { updateResult = null },
            onDownload = { release ->
                runCatching { UpdateChecker.download(context, release) }
                    .onSuccess {
                        Toast.makeText(
                            context,
                            "已加入系统下载队列；完成后点击系统通知安装",
                            Toast.LENGTH_LONG,
                        ).show()
                        updateResult = null
                    }
                    .onFailure {
                        Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
            },
            onOpenPage = { release -> openUrl(context, release.pageUrl) },
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenRepository = { openUrl(context, UpdateChecker.REPOSITORY_URL) },
            onFeedback = { FeedbackSender.send(context, diagnosticsRepository) },
        )
    }

    if (showSetupGuide) {
        SetupGuideDialog(
            onDismiss = {
                context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("setup_guide_acknowledged", true)
                    .apply()
                showSetupGuide = false
            },
        )
    }
}

@Composable
private fun SetupCard(
    onOpenGuide: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FF)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("需要 LSPosed", fontWeight = FontWeight.Bold, color = Color(0xFF154B99))
            Text(
                "保存规则后，请在 LSPosed 中启用本模块并勾选目标应用。首次启用或修改作用域后，需要强制停止目标应用再打开。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF294D7A),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenGuide) { Text("配置要求") }
                Button(onClick = onOpenLogs) { Text("诊断日志") }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    rule: AppRule,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (rule.enabled) Color(0xFF315EA8) else Color(0xFFE6E8EE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(app = app)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (rule.enabled) {
                        ruleSummary(rule)
                    } else {
                        app.packageName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap = remember(app.packageName, sizePx) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "${app.label}图标",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape),
        )
    } else {
        Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold)
    }
}

private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable && bitmap.width == width && bitmap.height == height) return bitmap
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
        val canvas = Canvas(target)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
    }
}

@Composable
private fun SetupGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行前需要完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本版本不需要相机、存储、通知等 Android 运行时权限。")
                Text("1. 手机已 Root，并安装可用的 LSPosed。")
                Text("2. 在 LSPosed 中启用“应用计时退出”模块。")
                Text("3. 在模块作用域中勾选需要限制的目标应用。")
                Text("4. 保存规则后，强制停止目标应用并重新打开。")
                Text(
                    "若诊断日志没有 HOOK_READY，说明 Hook 没有进入目标进程，请先检查第 2、3 项。",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("我知道了") } },
    )
}

@Composable
private fun SettingsDialog(
    initialSettings: GlobalSettings,
    onDismiss: () -> Unit,
    onDonate: () -> Unit,
    onCheckUpdates: () -> Unit,
    onAbout: () -> Unit,
    onFeedback: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
) {
    var warningEnabled by remember { mutableStateOf(initialSettings.exitWarningEnabled) }
    var diagnosticsEnabled by remember { mutableStateOf(initialSettings.diagnosticsEnabled) }
    var extensionMinutes by remember {
        mutableStateOf((initialSettings.extensionSeconds / 60L).coerceAtLeast(1L).toString())
    }
    val parsedMinutes = extensionMinutes.toLongOrNull()?.takeIf { it in 1L..60L }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出与延时设置") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("退出前提醒", fontWeight = FontWeight.Medium)
                        Text("到期前 5 秒弹出倒计时", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = warningEnabled, onCheckedChange = { warningEnabled = it })
                }
                }
                item {
                HorizontalDivider()
                }
                item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("诊断日志", fontWeight = FontWeight.Medium)
                        Text("记录 Hook、计时、延时和退出事件", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = diagnosticsEnabled, onCheckedChange = { diagnosticsEnabled = it })
                }
                }
                item {
                TextField(
                    value = extensionMinutes,
                    onValueChange = { extensionMinutes = it.filter(Char::isDigit).take(2) },
                    label = { Text("每次点击延时（分钟）") },
                    supportingText = { Text("可设置 1–60 分钟；每次点击都会追加") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = extensionMinutes.isNotEmpty() && parsedMinutes == null,
                    enabled = warningEnabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                item { HorizontalDivider() }
                item {
                    SettingsEntry(
                        title = "检查更新",
                        description = "从 GitHub Releases 检查并下载新版 APK",
                        action = "检查 ›",
                        onClick = onCheckUpdates,
                    )
                }
                item {
                    SettingsEntry(
                        title = "反馈问题",
                        description = "通过邮件发送设备信息和诊断日志",
                        action = "反馈 ›",
                        onClick = onFeedback,
                    )
                }
                item {
                    SettingsEntry(
                        title = "关于",
                        description = "版本、项目主页和联系方式",
                        action = "查看 ›",
                        onClick = onAbout,
                    )
                }
                item {
                HorizontalDivider()
                }
                item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDonate).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("支持开发", fontWeight = FontWeight.Medium)
                        Text(
                            "支付宝捐赠：$DONATION_ACCOUNT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("去转账 ›", color = MaterialTheme.colorScheme.primary)
                }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialSettings.copy(
                            exitWarningEnabled = warningEnabled,
                            extensionSeconds = (parsedMinutes ?: 5L) * 60L,
                            diagnosticsEnabled = diagnosticsEnabled,
                        ),
                    )
                },
                enabled = !warningEnabled || parsedMinutes != null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsEntry(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(action, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UpdateResultDialog(
    result: UpdateCheckResult,
    onDismiss: () -> Unit,
    onDownload: (ReleaseInfo) -> Unit,
    onOpenPage: (ReleaseInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (result) {
                    is UpdateCheckResult.Available -> "发现新版本 ${result.release.version}"
                    is UpdateCheckResult.UpToDate -> "已是最新版本"
                    is UpdateCheckResult.Error -> "检查更新失败"
                },
            )
        },
        text = {
            when (result) {
                is UpdateCheckResult.Available -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前版本：${BuildConfig.VERSION_NAME}")
                        Text(
                            result.release.notes.ifBlank { "该版本没有填写更新说明。" },
                            modifier = Modifier.heightIn(max = 360.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onOpenPage(result.release) }) {
                            Text("在 GitHub 查看发布页")
                        }
                    }
                }
                is UpdateCheckResult.UpToDate -> Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}，GitHub 最新版本 ${result.latestVersion}。",
                )
                is UpdateCheckResult.Error -> Text(
                    "${result.message}\n请检查网络连接，或稍后重试。",
                )
            }
        },
        confirmButton = {
            if (result is UpdateCheckResult.Available) {
                Button(onClick = { onDownload(result.release) }) { Text("下载 APK") }
            } else {
                Button(onClick = onDismiss) { Text("确定") }
            }
        },
        dismissButton = {
            if (result is UpdateCheckResult.Available) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenRepository: () -> Unit,
    onFeedback: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于应用计时退出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Android / LSPosed 应用前台使用时长限制模块。")
                Text("单次打开与每日累计可同时启用，任一阈值到期即退出目标应用。")
                Text("反馈邮箱：${FeedbackSender.EMAIL}")
                TextButton(onClick = onOpenRepository) { Text("打开 GitHub 项目主页") }
                TextButton(onClick = onFeedback) { Text("发送问题反馈") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
}

private fun openAlipayDonation(context: Context) {
    val transferUri = Uri.Builder()
        .scheme("alipays")
        .authority("platformapi")
        .appendPath("startapp")
        .appendQueryParameter("appId", "20000123")
        .appendQueryParameter("actionType", "toAccount")
        .appendQueryParameter("goBack", "YES")
        .appendQueryParameter("account", DONATION_ACCOUNT)
        .build()
    val transferIntent = Intent(Intent.ACTION_VIEW, transferUri)
        .setPackage(ALIPAY_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(transferIntent) }
        .onFailure {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("支付宝账号", DONATION_ACCOUNT))
            val launchIntent = context.packageManager.getLaunchIntentForPackage(ALIPAY_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) runCatching { context.startActivity(launchIntent) }
            Toast.makeText(
                context,
                "支付宝账号已复制：$DONATION_ACCOUNT，请在转账前核对账号",
                Toast.LENGTH_LONG,
            ).show()
        }
}

private const val DONATION_ACCOUNT = "liuml.yx@139.com"
private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

@Composable
private fun DiagnosticLogDialog(
    repository: DiagnosticsRepository,
    onFeedback: () -> Unit,
    onDismiss: () -> Unit,
) {
    var logs by remember { mutableStateOf(repository.readLatest()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志（${logs.size}）") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无日志。打开一次已配置的目标应用；若仍为空，请检查 LSPosed 是否启用模块及目标应用作用域。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs) { line ->
                        Text(line.replace('\t', '\n'), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row {
                TextButton(onClick = onFeedback) { Text("反馈") }
                TextButton(onClick = { logs = repository.readLatest() }) { Text("刷新") }
                TextButton(
                    onClick = {
                        repository.clear()
                        logs = emptyList()
                    },
                ) { Text("清空") }
            }
        },
    )
}

@Composable
private fun RuleDialog(
    app: InstalledApp,
    initialRule: AppRule,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
) {
    var dailyEnabled by remember(app.packageName) { mutableStateOf(initialRule.dailyEnabled) }
    var dailyMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.dailyLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    var perLaunchEnabled by remember(app.packageName) { mutableStateOf(initialRule.perLaunchEnabled) }
    var perLaunchMinutes by remember(app.packageName) {
        mutableStateOf((initialRule.perLaunchLimitSeconds / 60L).coerceAtLeast(1L).toString())
    }
    val parsedDailyMinutes = dailyMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val parsedPerLaunchMinutes = perLaunchMinutes.toLongOrNull()?.takeIf { it in 1..1440 }
    val canSave = (dailyEnabled || perLaunchEnabled) &&
        (!dailyEnabled || parsedDailyMinutes != null) &&
        (!perLaunchEnabled || parsedPerLaunchMinutes != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                item {
                    ThresholdEditor(
                        title = "每日累计",
                        description = "当天多次打开累计，第二天自动重置",
                        enabled = dailyEnabled,
                        minutes = dailyMinutes,
                        parsedMinutes = parsedDailyMinutes,
                        onEnabledChange = { dailyEnabled = it },
                        onMinutesChange = { dailyMinutes = it },
                    )
                }
                item { HorizontalDivider() }
                item {
                    ThresholdEditor(
                        title = "单次打开",
                        description = "每次目标应用进程启动后重新计时",
                        enabled = perLaunchEnabled,
                        minutes = perLaunchMinutes,
                        parsedMinutes = parsedPerLaunchMinutes,
                        onEnabledChange = { perLaunchEnabled = it },
                        onMinutesChange = { perLaunchMinutes = it },
                    )
                }
                item {
                    Text(
                        "两个限制可同时开启，任何一个先到期都会退出应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialRule.copy(
                            enabled = true,
                            dailyEnabled = dailyEnabled,
                            dailyLimitSeconds = (parsedDailyMinutes ?: 1L) * 60L,
                            perLaunchEnabled = perLaunchEnabled,
                            perLaunchLimitSeconds = (parsedPerLaunchMinutes ?: 1L) * 60L,
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text("保存并启用")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSave(
                            initialRule.copy(
                                enabled = true,
                                dailyEnabled = false,
                                perLaunchEnabled = true,
                                perLaunchLimitSeconds = 10L,
                            ),
                        )
                    },
                ) { Text("10 秒测试") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ThresholdEditor(
    title: String,
    description: String,
    enabled: Boolean,
    minutes: String,
    parsedMinutes: Long?,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        TextField(
            value = minutes,
            onValueChange = { value -> onMinutesChange(value.filter(Char::isDigit).take(4)) },
            label = { Text("${title}时长（分钟）") },
            supportingText = { Text("范围 1–1440 分钟") },
            isError = enabled && minutes.isNotEmpty() && parsedMinutes == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatDuration(seconds: Long): String =
    if (seconds < 60L) "$seconds 秒" else "${seconds / 60L} 分钟"

private fun ruleSummary(rule: AppRule): String = buildList {
    if (rule.dailyEnabled) add("每日 ${formatDuration(rule.dailyLimitSeconds)}")
    if (rule.perLaunchEnabled) add("单次 ${formatDuration(rule.perLaunchLimitSeconds)}")
}.joinToString(" · ")

@Composable
private fun AppTimeLimiterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF315EA8),
            secondary = Color(0xFF536F9E),
            surface = Color.White,
            background = Color(0xFFF7F8FC),
        ),
        content = content,
    )
}
