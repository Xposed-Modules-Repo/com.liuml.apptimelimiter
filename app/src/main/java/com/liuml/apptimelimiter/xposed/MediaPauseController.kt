package com.liuml.apptimelimiter.xposed

import android.media.MediaPlayer
import android.webkit.WebView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

internal data class MediaPauseResult(
    val trackedPlayers: Int,
    val pausedPlayers: Int,
    val webViewsSignaled: Int,
)

/**
 * Best-effort, target-process-only media pause support for the standalone break page.
 *
 * No global media key is sent. Objects are weakly tracked so this layer cannot keep host players
 * or WebViews alive. Playback is not force-resumed; the host app decides what to do on Activity
 * resume, which avoids unexpected sound after a long break.
 */
internal class MediaPauseController(
    private val classLoader: ClassLoader,
) {
    private val platformPlayers = Collections.synchronizedMap(WeakHashMap<MediaPlayer, Unit>())
    private val exoPlayers = Collections.synchronizedMap(WeakHashMap<Any, Unit>())
    private val webViews = Collections.synchronizedMap(WeakHashMap<WebView, Unit>())

    fun installHooks(): List<String> = buildList {
        hookConstructors(MediaPlayer::class.java) { instance ->
            (instance as? MediaPlayer)?.let { player -> platformPlayers[player] = Unit }
        }.takeIf { it }?.let { add("MediaPlayer") }

        EXO_PLAYER_CLASSES.forEach { className ->
            val playerClass = runCatching {
                Class.forName(className, false, classLoader)
            }.getOrNull() ?: return@forEach
            hookConstructors(playerClass) { instance ->
                if (instance != null) exoPlayers[instance] = Unit
            }.takeIf { it }?.let { add(className.substringAfterLast('.')) }
        }

        hookConstructors(WebView::class.java) { instance ->
            (instance as? WebView)?.let { webView -> webViews[webView] = Unit }
        }.takeIf { it }?.let { add("WebView") }
    }

    fun pauseCommonMedia(): MediaPauseResult {
        var trackedPlayers = 0
        var pausedPlayers = 0
        snapshot(platformPlayers).forEach { player ->
            trackedPlayers++
            val playing = runCatching { player.isPlaying }.getOrDefault(false)
            if (playing && runCatching { player.pause() }.isSuccess) pausedPlayers++
        }
        snapshot(exoPlayers).forEach { player ->
            trackedPlayers++
            if (pauseExoPlayer(player)) pausedPlayers++
        }
        var webViewsSignaled = 0
        snapshot(webViews).forEach { webView ->
            if (
                runCatching {
                    webView.evaluateJavascript(PAUSE_WEB_MEDIA_SCRIPT, null)
                }.isSuccess
            ) {
                webViewsSignaled++
            }
        }
        return MediaPauseResult(trackedPlayers, pausedPlayers, webViewsSignaled)
    }

    private fun pauseExoPlayer(player: Any): Boolean {
        val playerClass = player.javaClass
        val isPlaying = runCatching {
            playerClass.methods.firstOrNull {
                it.name == "isPlaying" && it.parameterTypes.isEmpty()
            }?.invoke(player) as? Boolean
        }.getOrNull() ?: runCatching {
            playerClass.methods.firstOrNull {
                it.name == "getPlayWhenReady" && it.parameterTypes.isEmpty()
            }?.invoke(player) as? Boolean
        }.getOrNull() ?: false
        if (!isPlaying) return false
        val pauseMethod = playerClass.methods.firstOrNull {
            it.name == "pause" && it.parameterTypes.isEmpty()
        }
        if (pauseMethod != null && runCatching { pauseMethod.invoke(player) }.isSuccess) {
            return true
        }
        val setPlayWhenReady = playerClass.methods.firstOrNull {
            it.name == "setPlayWhenReady" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return false
        return runCatching { setPlayWhenReady.invoke(player, false) }.isSuccess
    }

    private fun hookConstructors(
        targetClass: Class<*>,
        onCreated: (Any?) -> Unit,
    ): Boolean = runCatching {
        XposedBridge.hookAllConstructors(
            targetClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onCreated(param.thisObject)
                }
            },
        )
        true
    }.getOrDefault(false)

    private fun <T : Any> snapshot(source: MutableMap<T, Unit>): List<T> =
        synchronized(source) { source.keys.toList() }

    private companion object {
        val EXO_PLAYER_CLASSES = listOf(
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl",
            "com.google.android.exoplayer2.SimpleExoPlayer",
        )
        const val PAUSE_WEB_MEDIA_SCRIPT =
            "javascript:(()=>{document.querySelectorAll('video,audio').forEach(e=>{if(!e.paused)e.pause()})})()"
    }
}
