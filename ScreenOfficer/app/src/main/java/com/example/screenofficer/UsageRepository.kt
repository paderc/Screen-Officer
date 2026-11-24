package com.example.screenofficer

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager

data class UsageSnapshot(
    val dayStartMs: Long,
    val checkedAtMs: Long,
    val usageMs: Map<String, Long>,
    val foregroundPackage: String?,
    val available: Boolean,
    val error: String? = null,
)

internal data class AppUsage(val packageName: String, val label: String, val usedMs: Long)

internal data class DailyUsage(
    val dayStartMs: Long,
    val apps: List<AppUsage>,
    val error: String?,
)

class UsageRepository(context: Context) {
    private val context = context.applicationContext
    private val blockedUsage = BlockedUsageStore(this.context)

    @Suppress("DEPRECATION")
    fun hasAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Call from a background dispatcher; no unselected-app totals are returned or retained. */
    fun snapshot(packages: Set<String>, now: Long = System.currentTimeMillis()): UsageSnapshot =
        readUsage(localDayStart(now), now, packages)

    /** Read one local calendar day, including an in-progress day, on a background dispatcher. */
    internal fun history(dayStart: Long, now: Long = System.currentTimeMillis()): DailyUsage {
        val end = minOf(now, localDayStart(dayStart, dayOffset = 1)).coerceAtLeast(dayStart)
        val snapshot = readUsage(dayStart, end, packages = null)
        val apps = snapshot.usageMs.entries
            .filter { it.value > 0L }
            .sortedByDescending { it.value }
            .map { (packageName, usedMs) -> AppUsage(packageName, appLabel(packageName), usedMs) }
        return DailyUsage(dayStart, apps, snapshot.error)
    }

    private fun readUsage(dayStart: Long, end: Long, packages: Set<String>?): UsageSnapshot {
        fun unavailable(message: String) = UsageSnapshot(dayStart, end, emptyMap(), null, false, message)
        if (!hasAccess()) return unavailable("Usage Access is off. Enable it to measure time.")
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) return unavailable("Unlock the phone to read usage.")
        return try {
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return unavailable("Usage information is unavailable on this device.")
            val lookbackStart = localDayStart(dayStart, dayOffset = -1)
            val stream = manager.queryEvents(lookbackStart, end)
                ?: return unavailable("Android has not made usage information available yet.")
            val events = mutableListOf<UsageTimeEvent>()
            val event = UsageEvents.Event()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event)
                val kind = eventKind(event.eventType) ?: continue
                events += UsageTimeEvent(event.timeStamp, kind, event.packageName, event.className)
            }
            val tracked = packages ?: events.asSequence()
                .filter { it.kind == UsageEventKind.RESUMED }
                .mapNotNull { it.packageName }
                .toSet()
            val result = UsageTimeCalculator.calculate(
                events, dayStart, end, tracked,
                blockedIntervals = blockedUsage.intervals(dayStart, end),
            )
            // Access can be revoked while the system query is in flight.
            if (!hasAccess()) return unavailable("Usage Access was turned off.")
            UsageSnapshot(dayStart, end, result.usageMs, result.foregroundPackage, true)
        } catch (_: SecurityException) {
            unavailable("Usage Access is off. Enable it to measure time.")
        } catch (_: RuntimeException) {
            unavailable("Android could not read usage. Try refreshing in a moment.")
        }
    }

    private fun appLabel(packageName: String): String {
        val manager = context.packageManager
        return try {
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    @Suppress("DEPRECATION")
    private fun eventKind(type: Int): UsageEventKind? = when (type) {
        // These are aliases of ACTIVITY_RESUMED / ACTIVITY_PAUSED and support API 24–28 too.
        UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageEventKind.RESUMED
        UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageEventKind.PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.STOPPED
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
        UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_ON
        UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.KEYGUARD_SHOWN
        UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventKind.KEYGUARD_HIDDEN
        UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.SHUTDOWN
        UsageEvents.Event.DEVICE_STARTUP -> UsageEventKind.STARTUP
        else -> null
    }
}
