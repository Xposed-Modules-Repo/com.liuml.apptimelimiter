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
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.localization.SupportedLanguage

object FeedbackSender {
    const val EMAIL = "liuml.yx@139.com"

    fun send(context: Context, diagnosticsRepository: DiagnosticsRepository) {
        val english = AppLocaleController.resolvedLanguage(
            context,
            RuleRepository(context).getGlobalSettings().languageMode,
        ) == SupportedLanguage.ENGLISH
        val attachment = diagnosticsRepository.exportForFeedback()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            attachment,
        )
        val body = buildString {
            appendLine(if (english) "Describe the issue, reproduction steps and expected result:" else "请描述问题、复现步骤和期望结果：")
            appendLine()
            appendLine("--------------------")
            appendLine(if (english) "App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" else "应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                if (english) "Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"
                else "Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            )
            appendLine(if (english) "Device: ${Build.MANUFACTURER} ${Build.MODEL}" else "设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(if (english) "LSPosed version: please provide" else "LSPosed 版本：请补充")
        }
        val subject = if (english) {
            "[Time Stop] Feedback ${BuildConfig.VERSION_NAME}"
        } else {
            "[时停] 问题反馈 ${BuildConfig.VERSION_NAME}"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, if (english) "Diagnostic logs" else "诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, if (english) "Choose email app" else "选择邮件应用"))
        }.onFailure {
            val fallback = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:$EMAIL?subject=${Uri.encode(subject)}"),
            )
            runCatching { context.startActivity(fallback) }
                .onFailure {
                    Toast.makeText(
                        context,
                        if (english) "No email app found. Send to $EMAIL" else "未找到邮件应用，请发送至 $EMAIL",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }
}
