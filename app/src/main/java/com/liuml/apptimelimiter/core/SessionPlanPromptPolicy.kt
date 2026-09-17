package com.liuml.apptimelimiter.core

/** The initial plan prompt is shown at most once during a target-process session. */
object SessionPlanPromptPolicy {
    fun shouldShowInitialPrompt(promptHandled: Boolean): Boolean = !promptHandled
}
