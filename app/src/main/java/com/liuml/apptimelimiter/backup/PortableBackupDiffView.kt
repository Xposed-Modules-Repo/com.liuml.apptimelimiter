package com.liuml.apptimelimiter.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.localization.SupportedLanguage

@Composable
fun PortableBackupDiffView(preview: PortableBackupPreview, languageMode: AppLanguageMode) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val wording = remember(context, languageMode, configuration) {
        BackupPreviewText(AppLocaleController.resolvedLanguage(context, languageMode) == SupportedLanguage.ENGLISH)
    }
    Text(wording.text("基于当前配置", "Based on the current configuration"), style = MaterialTheme.typography.labelSmall)
    Text(wording.text("点击条目查看字段前后值；导入仍为整体替换。", "Tap an entry to compare values. Import replaces the entire configuration."), style = MaterialTheme.typography.bodySmall)
    LazyColumn(Modifier.heightIn(max = 280.dp)) {
        listOf(wording.text("规则", "Rules") to preview.diff.rules, wording.text("分组", "Groups") to preview.diff.groups, wording.text("设置", "Settings") to preview.diff.settings).forEachIndexed { section, (title, entries) ->
            item(key = title) {
                Text(title + wording.text("：", ": ") + BackupChange.entries.joinToString(" · ") { change ->
                    "${wording.change(change)} ${entries.count { it.change == change }}"
                }, style = MaterialTheme.typography.titleSmall)
            }
            items(entries, key = { "$title:${it.key}" }) { entry ->
                var expanded by remember(preview, entry.key) { mutableStateOf(false) }
                var advanced by remember(preview, entry.key) { mutableStateOf(false) }
                val nameField = entry.fields.firstOrNull { it.name == "name" }
                val entryTitle = when (section) {
                    2 -> wording.label(entry.key)
                    1 -> nameField?.let { wording.value("name", it.after ?: it.before) } ?: entry.key
                    else -> entry.key
                }
                Column {
                    Text(
                        "${if (expanded) "▾" else "▸"} [${wording.change(entry.change)}] $entryTitle",
                        modifier = Modifier.clickable { expanded = !expanded },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (expanded) {
                        entry.fields.forEach { field ->
                            Text("${wording.label(field.name)}\n${wording.text("当前", "Current")}: ${wording.value(field.name, field.before)}\n${wording.text("导入后", "After import")}: ${wording.value(field.name, field.after)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(wording.text("高级详情（原始数据）", "Advanced details (raw data)"),
                            modifier = Modifier.clickable { advanced = !advanced }, style = MaterialTheme.typography.labelMedium)
                        if (advanced) entry.fields.forEach { field ->
                            Text("${field.name}: ${field.before ?: "null"} → ${field.after ?: "null"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
