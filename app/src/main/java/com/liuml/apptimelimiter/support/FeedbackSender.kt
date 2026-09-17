package com.liuml.apptimelimiter.support

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.localization.SupportedLanguage

object FeedbackSender {
    const val EMAIL = "liuml.yx@139.com"

    data class Draft(
        val subject: String,
        val body: String,
        val attachment: java.io.File,
        val attachmentBytes: Long,
    )

    enum class ShareLaunchResult {
        OPENED,
        NO_SHARE_TARGET,
        FAILED,
    }

    /** Prepares everything locally. It never starts another task or Activity. */
    fun prepare(context: Context, diagnosticsRepository: DiagnosticsRepository): Result<Draft> = runCatching {
        val english = AppLocaleController.resolvedLanguage(
            context,
            RuleRepository(context).getGlobalSettings().languageMode,
        ) == SupportedLanguage.ENGLISH
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
        val attachment = diagnosticsRepository.exportForFeedback()
        Draft(
            subject = subject,
            body = body,
            attachment = attachment,
            attachmentBytes = attachment.length(),
        )
    }

    /** Opens the platform chooser so the user can select QQ, WeChat, email, or another app. */
    fun share(context: Context, draft: Draft): ShareLaunchResult {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                draft.attachment,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, draft.subject)
                putExtra(Intent.EXTRA_TEXT, draft.body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Diagnostic logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Package visibility can hide valid handlers from queries. Let the system chooser
            // resolve the intent instead of incorrectly rejecting sharing before it is launched.
            val chooser = Intent.createChooser(shareIntent, "选择分享方式").apply {
                // ColorOS destroys an alias-launched host task together with a chooser in the
                // same task. Keep the platform resolver in its own task so it remains visible.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            ShareLaunchResult.OPENED
        }.getOrElse { error ->
            diagnosticsRepositorySafeAppend(context, error)
            if (error is android.content.ActivityNotFoundException) {
                ShareLaunchResult.NO_SHARE_TARGET
            } else {
                ShareLaunchResult.FAILED
            }
        }
    }

    private fun diagnosticsRepositorySafeAppend(context: Context, error: Throwable) {
        runCatching {
            DiagnosticsRepository(context).append(
                level = "WARN",
                packageName = context.packageName,
                event = "FEEDBACK_SHARE_UNAVAILABLE",
                message = error.javaClass.simpleName,
            )
        }
    }
}
