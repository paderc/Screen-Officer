package com.example.screenofficer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Keeps usage adjustments for intervals when the blocking card denied access to an app. */
internal class BlockedUsageStore(
    context: Context,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedClockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun start(packageName: String) {
        synchronized(lock) {
            if (active?.packageName == packageName) return
            val now = wallClockMs()
            val elapsed = elapsedClockMs()
            val previous = active?.interval(now, elapsed) ?: readCheckpoint()
            val next = ActiveOverlay(packageName, now, elapsed)
            val editor = preferences.edit()
            saveCompleted(editor, recentCompleted(now, previous))
            editor.putString(CHECKPOINT_KEY, next.interval(now, elapsed).encode().toString()).apply()
            active = next
        }
    }

    fun checkpoint() {
        synchronized(lock) {
            val overlay = active ?: return
            val elapsed = elapsedClockMs()
            if (elapsed - overlay.lastCheckpointElapsedMs < CHECKPOINT_INTERVAL_MS) return
            val interval = overlay.interval(wallClockMs(), elapsed)
            preferences.edit().putString(CHECKPOINT_KEY, interval.encode().toString()).apply()
            overlay.lastCheckpointElapsedMs = elapsed
            overlay.lastCheckpointEndMs = interval.endMs
        }
    }

    fun stop() {
        synchronized(lock) {
            val overlay = active ?: return
            val now = wallClockMs()
            val interval = overlay.interval(now, elapsedClockMs())
            val editor = preferences.edit()
            saveCompleted(editor, recentCompleted(now, interval))
            editor.remove(CHECKPOINT_KEY).apply()
            active = null
        }
    }

    fun intervals(from: Long, until: Long): List<BlockedUsageInterval> = synchronized(lock) {
        if (until <= from) return@synchronized emptyList()
        val now = wallClockMs()
        val cutoff = now - RETENTION_MS
        val completed = readCompleted()
        val recent = completed.filter { it.endMs > cutoff }
        val checkpoint = if (active == null) readCheckpoint() else null
        val expiredCheckpoint = checkpoint != null && checkpoint.endMs <= cutoff
        if (recent.size != completed.size || expiredCheckpoint) {
            val editor = preferences.edit()
            if (recent.size != completed.size) saveCompleted(editor, recent)
            if (expiredCheckpoint) editor.remove(CHECKPOINT_KEY)
            editor.apply()
        }

        // After process death, the saved checkpoint has a finite end even if the app stayed open.
        val ongoing = active?.interval(now, elapsedClockMs()) ?: checkpoint?.takeUnless { expiredCheckpoint }
        (recent + listOfNotNull(ongoing)).mapNotNull { interval ->
            val start = maxOf(interval.startMs, from)
            val end = minOf(interval.endMs, until)
            if (end > start) BlockedUsageInterval(interval.packageName, start, end) else null
        }
    }

    private fun recentCompleted(now: Long, ending: BlockedUsageInterval?): List<BlockedUsageInterval> =
        (readCompleted() + listOfNotNull(ending)).filter { it.endMs > it.startMs && it.endMs > now - RETENTION_MS }

    private fun readCompleted(): List<BlockedUsageInterval> {
        val encoded = preferences.getString(COMPLETED_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }
    }

    private fun readCheckpoint(): BlockedUsageInterval? {
        val encoded = preferences.getString(CHECKPOINT_KEY, null) ?: return null
        return decode(runCatching { JSONObject(encoded) }.getOrNull())
    }

    private fun decode(json: JSONObject?): BlockedUsageInterval? {
        if (json == null) return null
        val packageName = json.optString("packageName")
        val start = json.optLong("startMs", -1L)
        val end = json.optLong("endMs", -1L)
        return BlockedUsageInterval(packageName, start, end).takeIf {
            packageName.isNotBlank() && start >= 0L && end >= start
        }
    }

    private fun saveCompleted(editor: SharedPreferences.Editor, intervals: List<BlockedUsageInterval>) {
        if (intervals.isEmpty()) {
            editor.remove(COMPLETED_KEY)
        } else {
            editor.putString(COMPLETED_KEY, JSONArray(intervals.map { it.encode() }).toString())
        }
    }

    private fun BlockedUsageInterval.encode() = JSONObject()
        .put("packageName", packageName)
        .put("startMs", startMs)
        .put("endMs", endMs)

    private data class ActiveOverlay(
        val packageName: String,
        val startMs: Long,
        val startElapsedMs: Long,
        var lastCheckpointElapsedMs: Long = startElapsedMs,
        var lastCheckpointEndMs: Long = startMs,
    ) {
        fun interval(now: Long, elapsed: Long): BlockedUsageInterval {
            val elapsedEnd = startMs + (elapsed - startElapsedMs).coerceAtLeast(0L)
            val end = maxOf(lastCheckpointEndMs, minOf(now, elapsedEnd))
            return BlockedUsageInterval(packageName, startMs, end)
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "screenofficer_blocked_usage"
        private const val COMPLETED_KEY = "completed"
        private const val CHECKPOINT_KEY = "checkpoint"
        private const val CHECKPOINT_INTERVAL_MS = 10_000L
        private const val RETENTION_MS = 8 * 24 * 60 * 60 * 1_000L

        // The service and UI use the same process. Readers see the live interval through this lock.
        private val lock = Any()
        private var active: ActiveOverlay? = null
    }
}
