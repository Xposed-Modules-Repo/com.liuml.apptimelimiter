package com.liuml.apptimelimiter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original 24-unit symbols shared by Compose and native restriction surfaces. */
internal object FunctionIconPaths {
    val paths = mapOf(
        "home" to "M4 10 L12 3 L20 10 V20 H15 V14 H9 V20 H4 Z",
        "apps" to "M5 3 H8 Q10 3 10 5 V8 Q10 10 8 10 H5 Q3 10 3 8 V5 Q3 3 5 3 Z M16 3 H19 Q21 3 21 5 V8 Q21 10 19 10 H16 Q14 10 14 8 V5 Q14 3 16 3 Z M5 14 H8 Q10 14 10 16 V19 Q10 21 8 21 H5 Q3 21 3 19 V16 Q3 14 5 14 Z M16 14 H19 Q21 14 21 16 V19 Q21 21 19 21 H16 Q14 21 14 19 V16 Q14 14 16 14 Z",
        "groups" to "M12 7 A3 3 0 1 1 6 7 A3 3 0 1 1 12 7 M2 20 V18 C2 12 16 12 16 18 V20 M17 4 C22 4 22 10 17 10 M19 14 C22 15 22 17 22 20",
        "stats" to "M4 20 V13 Q4 12 5 12 H7 Q8 12 8 13 V20 Z M10 20 V5 Q10 4 11 4 H13 Q14 4 14 5 V20 Z M16 20 V9 Q16 8 17 8 H19 Q20 8 20 9 V20 Z",
        "timer" to "M20 13 A8 8 0 1 1 4 13 A8 8 0 1 1 20 13 M12 13 V8 M9 2 H15 M12 2 V5 M18 5 L20 3",
        "shield" to "M12 3 L20 6 V12 C20 17 16 20 12 22 C8 20 4 17 4 12 V6 Z M8 12 L11 15 L16 9",
        "lock" to "M7 10 V7 A5 5 0 0 1 17 7 V10 M6 10 H18 Q20 10 20 12 V19 Q20 21 18 21 H6 Q4 21 4 19 V12 Q4 10 6 10 Z M12 14 V17",
        "appearance" to "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M12 3 V21 M12 7 H18 M12 11 H20 M12 15 H19 M12 19 H16",
        "bell" to "M5 17 H19 L17 14 V10 C17 3 7 3 7 10 V14 Z M10 20 Q12 23 14 20 M12 3 V2",
        "history" to "M4 8 A9 9 0 1 1 3 14 M4 3 V8 H9 M12 7 V12 L16 14",
        "backup" to "M4 14 V19 Q4 21 6 21 H18 Q20 21 20 19 V14 M12 3 V15 M7 10 L12 15 L17 10",
        "help" to "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M9 8 C9 4 17 5 15 10 L12 12 V14 M12 17 V17.2",
        "chevron" to "M9 5 L16 12 L9 19",
        "back" to "M15 5 L8 12 L15 19",
        "close" to "M6 6 L18 18 M18 6 L6 18",
        "exit" to "M10 4 H5 Q3 4 3 6 V18 Q3 20 5 20 H10 M9 12 H21 M17 8 L21 12 L17 16",
        "refresh" to "M4 8 A9 9 0 0 1 20 8 M4 3 V8 H9 M20 16 A9 9 0 0 1 4 16 M20 21 V16 H15",
        "calendar" to "M5 5 H19 Q21 5 21 7 V19 Q21 21 19 21 H5 Q3 21 3 19 V7 Q3 5 5 5 Z M7 2 V7 M17 2 V7 M3 10 H21 M7 14 H9 M15 14 H17 M7 17 H9",
        "check" to "M5 12 L10 17 L20 6",
        "settings" to "M10.258 5.014 L10.368 2.743 L13.632 2.743 L13.742 5.014 L15.708 5.828 L17.392 4.300 L19.700 6.608 L18.172 8.292 L18.986 10.258 L21.257 10.368 L21.257 13.632 L18.986 13.742 L18.172 15.708 L19.700 17.392 L17.392 19.700 L15.708 18.172 L13.742 18.986 L13.632 21.257 L10.368 21.257 L10.258 18.986 L8.292 18.172 L6.608 19.700 L4.300 17.392 L5.828 15.708 L5.014 13.742 L2.743 13.632 L2.743 10.368 L5.014 10.258 L5.828 8.292 L4.300 6.608 L6.608 4.300 L8.292 5.828 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12",
    )
}

@Composable
internal fun FunctionIcon(
    iconKey: String,
    modifier: Modifier = Modifier.size(26.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val path = remember(iconKey) {
        PathParser().parsePathString(FunctionIconPaths.paths[iconKey] ?: FunctionIconPaths.paths.getValue("settings")).toPath()
    }
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        withTransform({
            translate((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f)
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
        }) {
            drawPath(path, tint, style = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

internal class FunctionIconDrawable(key: String, tint: Int) : android.graphics.drawable.Drawable() {
    private val path = androidx.core.graphics.PathParser.createPathFromPathData(
        FunctionIconPaths.paths[key] ?: FunctionIconPaths.paths.getValue("settings"),
    )!!
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    override fun draw(canvas: android.graphics.Canvas) {
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        val save = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - side) / 2f, bounds.top + (bounds.height() - side) / 2f)
        canvas.scale(side / 24f, side / 24f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
