package com.liuml.apptimelimiter.support

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository

object FeedbackSender {
    const val EMAIL = "liuml.yx@139.com"

    fun send(context: Context, diagnosticsRepository: DiagnosticsRepository) {
        val attachment = diagnosticsRepository.exportForFeedback()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            attachment,
        )
        val body = buildString {
            appendLine("请描述问题、复现步骤和期望结果：")
            appendLine()
            appendLine("--------------------")
            appendLine("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("LSPosed 版本：请补充")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[时停] 问题反馈 ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "选择邮件应用"))
        }.onFailure {
            val fallback = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:$EMAIL?subject=${Uri.encode("[时停] 问题反馈")}"),
            )
            runCatching { context.startActivity(fallback) }
                .onFailure {
                    Toast.makeText(context, "未找到邮件应用，请发送至 $EMAIL", Toast.LENGTH_LONG).show()
                }
        }
    }
}
