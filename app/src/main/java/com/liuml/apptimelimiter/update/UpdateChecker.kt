package com.liuml.apptimelimiter.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.liuml.apptimelimiter.BuildConfig
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class ReleaseInfo(
    val version: String,
    val pageUrl: String,
    val notes: String,
    val apkName: String,
    val apkDownloadUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object UpdateChecker {
    fun check(callback: (UpdateCheckResult) -> Unit) {
        thread(name = "github-update-check", isDaemon = true) {
            val result = runCatching { requestLatestRelease() }
                .getOrElse { UpdateCheckResult.Error(it.message ?: "无法连接 GitHub") }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }
    }

    fun download(context: Context, release: ReleaseInfo): Long {
        val safeName = release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, safeName) }
            ?.takeIf(File::exists)
            ?.delete()
        val request = DownloadManager.Request(Uri.parse(release.apkDownloadUrl))
            .setTitle("应用计时退出 ${release.version}")
            .setDescription("正在从 GitHub 下载更新")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeName)
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("系统下载管理器不可用")
        return manager.enqueue(request)
    }

    private fun requestLatestRelease(): UpdateCheckResult {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AppTimeLimiter/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("GitHub 返回 HTTP $responseCode")
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val release = (0 until releases.length())
                .asSequence()
                .map(releases::getJSONObject)
                .firstOrNull { !it.optBoolean("draft", false) }
                ?: return UpdateCheckResult.Error("GitHub 中暂时没有可用版本")
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val apkAsset = (0 until assets.length())
                .asSequence()
                .map(assets::getJSONObject)
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return UpdateCheckResult.Error("最新版本没有附带 APK")
            val info = ReleaseInfo(
                version = release.optString("tag_name").ifBlank { release.optString("name") },
                pageUrl = release.optString("html_url", REPOSITORY_URL),
                notes = release.optString("body"),
                apkName = apkAsset.getString("name"),
                apkDownloadUrl = apkAsset.getString("browser_download_url"),
            )
            if (isVersionNewer(info.version, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(info)
            } else {
                UpdateCheckResult.UpToDate(info.version)
            }
        } finally {
            connection.disconnect()
        }
    }

    const val REPOSITORY_URL = "https://github.com/TACPR/android-app-time-limiter"
    private const val RELEASES_API = "https://api.github.com/repos/TACPR/android-app-time-limiter/releases?per_page=10"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}

internal fun isVersionNewer(candidate: String, current: String): Boolean {
    val candidateParts = versionParts(candidate)
    val currentParts = versionParts(current)
    val size = maxOf(candidateParts.size, currentParts.size)
    repeat(size) { index ->
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}

private fun versionParts(value: String): List<Int> = value
    .trim()
    .removePrefix("v")
    .removePrefix("V")
    .substringBefore('-')
    .split('.')
    .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
