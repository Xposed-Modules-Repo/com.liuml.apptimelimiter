package com.liuml.apptimelimiter.core

import java.util.IdentityHashMap

/**
 * Tracks resumed Activity instances by identity rather than equals/hashCode.
 *
 * Android may resume the destination Activity before pausing the source Activity. Treating one
 * Activity's pause as a process background transition therefore produces false pauses during
 * navigation and configuration changes. This registry lets the Hook make that decision from the
 * process-wide resumed set instead.
 */
class ResumedActivityRegistry<T : Any> {
    private val activities = IdentityHashMap<T, Unit>()

    val size: Int
        get() = activities.size

    val isEmpty: Boolean
        get() = activities.isEmpty()

    fun markResumed(activity: T): Boolean = activities.put(activity, Unit) == null

    fun markPaused(activity: T): Boolean = activities.remove(activity) != null

    fun contains(activity: T): Boolean = activities.containsKey(activity)

    fun anyOrNull(): T? = activities.keys.firstOrNull()
}
