package com.example.screenofficer

const val DEFAULT_MESSAGE = "Is this still how you want to spend your time?"

data class TrackedApp(
    val packageName: String,
    val label: String,
    val categories: Set<AppCategoryPreset> = emptySet(),
)

enum class BlockMode { SOFT, HARD }

data class AppRule(
    // Single-app rules use the package name as their ID.
    val packageName: String,
    val label: String,
    val limitMinutes: Int = 30,
    val message: String = DEFAULT_MESSAGE,
    val mode: BlockMode = BlockMode.SOFT,
    val enabled: Boolean = true,
    val sharedApps: Map<String, String> = emptyMap(),
) {
    val id: String get() = packageName
    val packages: Set<String> get() = sharedApps.keys.ifEmpty { setOf(packageName) }
}

/** A single allowance shared by every installed app recognised in this category. */
data class CategoryRule(
    val category: AppCategoryPreset,
    val limitMinutes: Int = 30,
    val message: String = DEFAULT_MESSAGE,
    val mode: BlockMode = BlockMode.SOFT,
    val enabled: Boolean = true,
) {
    val id: String get() = "category.${category.name}"
    val label: String get() = category.title

    internal fun asAppRule(): AppRule = AppRule(id, label, limitMinutes, message, mode, enabled)

    internal fun asTarget(apps: List<TrackedApp>): LimitTarget = LimitTarget(
        asAppRule(),
        apps.filter { category in it.categories }.map { it.packageName }.toSet(),
    )
}

internal data class LimitTarget(val rule: AppRule, val packages: Set<String> = rule.packages) {
    fun usedMs(usage: Map<String, Long>): Long = packages.sumOf { usage[it] ?: 0L }
}

object BlockPolicy {
    fun shouldBlock(rule: AppRule, usedMs: Long, snoozeThresholdMs: Long?): Boolean {
        if (!rule.enabled) return false
        val dailyLimitMs = rule.limitMinutes.toLong() * 60_000L
        val thresholdMs = if (rule.mode == BlockMode.SOFT) {
            maxOf(dailyLimitMs, snoozeThresholdMs ?: 0L)
        } else {
            dailyLimitMs
        }
        return usedMs >= thresholdMs
    }

    internal fun exceededLimit(
        limits: List<LimitTarget>,
        foregroundPackage: String?,
        usage: Map<String, Long>,
        snoozeThreshold: (String) -> Long?,
    ): LimitTarget? {
        if (foregroundPackage == null) return null
        return limits.asSequence()
            .filter { foregroundPackage in it.packages }
            .filter { shouldBlock(it.rule, it.usedMs(usage), snoozeThreshold(it.rule.id)) }
            .minByOrNull { if (it.rule.mode == BlockMode.HARD) 0 else 1 }
    }
}
