package com.example.screenofficer

enum class UsageEventKind {
    RESUMED, PAUSED, STOPPED, SCREEN_OFF, SCREEN_ON, KEYGUARD_SHOWN, KEYGUARD_HIDDEN,
    SHUTDOWN, STARTUP,
}

data class UsageTimeEvent(
    val timestampMs: Long,
    val kind: UsageEventKind,
    val packageName: String? = null,
    val activityClass: String? = null,
)

data class BlockedUsageInterval(val packageName: String, val startMs: Long, val endMs: Long)

data class UsageCalculation(val usageMs: Map<String, Long>, val foregroundPackage: String?)

/**
 * Reconstructs focused foreground sessions, clipped to the requested local day.
 * Events before midnight establish the initial state but do not add yesterday's usage.
 * Only one focused app is counted at a time, including on multi-window devices.
 */
object UsageTimeCalculator {
    fun calculate(
        events: List<UsageTimeEvent>,
        dayStartMs: Long,
        nowMs: Long,
        trackedPackages: Set<String>,
        blockedIntervals: List<BlockedUsageInterval> = emptyList(),
    ): UsageCalculation {
        require(nowMs >= dayStartMs)
        val totals = trackedPackages.associateWith { 0L }.toMutableMap()
        val blocksByPackage = blockedIntervals.asSequence()
            .filter { it.packageName in trackedPackages && it.endMs > dayStartMs && it.startMs < nowMs }
            .sortedBy { it.startMs }
            .groupBy { it.packageName }
        var foreground: String? = null
        var foregroundClass: String? = null
        var sessionStart = dayStartMs
        var interactive = true
        var locked = false
        var poweredOff = false

        fun closeSession(at: Long) {
            val pkg = foreground
            if (pkg != null && pkg in trackedPackages && interactive && !locked) {
                val start = maxOf(sessionStart, dayStartMs)
                val end = minOf(at, nowMs)
                if (end > start) {
                    var used = end - start
                    var excludedThrough = start
                    for (interval in blocksByPackage[pkg].orEmpty()) {
                        if (interval.startMs >= end) break
                        val overlapStart = maxOf(interval.startMs, excludedThrough)
                        val overlapEnd = minOf(interval.endMs, end)
                        if (overlapEnd > overlapStart) {
                            used -= overlapEnd - overlapStart
                            excludedThrough = overlapEnd
                        }
                    }
                    totals[pkg] = totals.getValue(pkg) + used
                }
            }
            sessionStart = at
        }

        events.asSequence().filter { it.timestampMs <= nowMs }.sortedBy { it.timestampMs }.forEach { event ->
            // Android's shutdown timestamp is its last database flush. Orphaned lifecycle
            // events after that point have no reliable end time and must not be counted.
            if (poweredOff && event.kind != UsageEventKind.STARTUP) return@forEach
            when (event.kind) {
                UsageEventKind.RESUMED -> {
                    closeSession(event.timestampMs)
                    foreground = event.packageName
                    foregroundClass = event.activityClass
                }
                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> {
                    // An old activity can pause after its replacement in the same app resumes.
                    if (event.packageName == foreground &&
                        (event.activityClass == null || foregroundClass == null ||
                            event.activityClass == foregroundClass)
                    ) {
                        closeSession(event.timestampMs)
                        foreground = null
                        foregroundClass = null
                    }
                }
                UsageEventKind.SCREEN_OFF -> {
                    closeSession(event.timestampMs)
                    interactive = false
                }
                UsageEventKind.SCREEN_ON -> {
                    closeSession(event.timestampMs)
                    interactive = true
                }
                UsageEventKind.KEYGUARD_SHOWN -> {
                    closeSession(event.timestampMs)
                    locked = true
                }
                UsageEventKind.KEYGUARD_HIDDEN -> {
                    closeSession(event.timestampMs)
                    locked = false
                }
                UsageEventKind.SHUTDOWN, UsageEventKind.STARTUP -> {
                    closeSession(event.timestampMs)
                    foreground = null
                    foregroundClass = null
                    poweredOff = event.kind == UsageEventKind.SHUTDOWN
                    interactive = true
                    locked = false
                }
            }
        }
        closeSession(nowMs)
        return UsageCalculation(totals, foreground.takeIf { interactive && !locked })
    }
}
