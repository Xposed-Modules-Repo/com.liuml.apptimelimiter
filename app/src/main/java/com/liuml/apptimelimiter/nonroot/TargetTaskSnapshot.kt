package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.core.TargetTaskExitPolicy
import com.liuml.apptimelimiter.core.TargetWindowMode

data class TargetTaskSnapshot(val taskId: Int, val windowMode: TargetWindowMode,
    val bounds: com.liuml.apptimelimiter.core.TargetWindowBounds? = null) {
    companion object {
        fun parse(raw: String?): TargetTaskSnapshot? {
            val fields = raw?.trim()?.split('|') ?: return null
            if (fields.size != 2 && fields.size != 6) return null
            val id = fields[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val mode = fields[1].toIntOrNull() ?: return null
            val bounds = if (fields.size == 6) com.liuml.apptimelimiter.core.TargetWindowBounds.parse(fields.drop(2)) ?: return null else null
            return TargetTaskSnapshot(id, TargetTaskExitPolicy.mode(mode), bounds)
        }
    }
}
