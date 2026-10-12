package com.liuml.apptimelimiter.core

data class TargetWindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun fits(width: Int, height: Int): Boolean = left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
    companion object {
        fun parse(fields: List<String>): TargetWindowBounds? {
            if (fields.size != 4) return null
            val v = fields.map { it.toIntOrNull() ?: return null }
            return TargetWindowBounds(v[0], v[1], v[2], v[3]).takeIf { it.fits(100_000, 100_000) }
        }
    }
}
