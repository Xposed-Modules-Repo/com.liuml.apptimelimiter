package com.liuml.apptimelimiter.core

object PackageNamePolicy {
    const val MAX_LENGTH = 255

    fun isValid(packageName: String): Boolean {
        if (
            packageName.isBlank() ||
            packageName.length > MAX_LENGTH ||
            packageName.startsWith('.') ||
            packageName.endsWith('.')
        ) return false
        return packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_') &&
                segment.drop(1).all { it.isLetterOrDigit() || it == '_' }
        }
    }
}
