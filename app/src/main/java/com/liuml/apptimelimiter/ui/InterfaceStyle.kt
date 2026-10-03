package com.liuml.apptimelimiter.ui

/** Neutral grouped surfaces shared by the manager, PIN, planning and restriction UI. */
internal data class InterfaceSurfaces(
    val background: Int,
    val card: Int,
    val elevated: Int,
    val field: Int,
    val text: Int,
    val secondaryText: Int,
    val separator: Int,
)

internal object InterfaceStyle {
    fun surfaces(dark: Boolean) = if (dark) InterfaceSurfaces(
        0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(), 0xFF363638.toInt(),
        0xFFF5F5F7.toInt(), 0xFFAEAEB2.toInt(), 0xFF38383A.toInt(),
    ) else InterfaceSurfaces(
        0xFFF2F2F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE9E9EF.toInt(),
        0xFF1C1C1E.toInt(), 0xFF636366.toInt(), 0xFFD1D1D6.toInt(),
    )
}
