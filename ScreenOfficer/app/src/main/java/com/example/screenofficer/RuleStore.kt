package com.example.screenofficer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

/** Persists selected apps, limit settings and soft-block extensions. */
class RuleStore(context: Context) {
    val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences("screenofficer_rules", Context.MODE_PRIVATE)

    fun allRules(): List<AppRule> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(RULE_PREFIX) || value !is String) return@mapNotNull null
        runCatching {
            val json = JSONObject(value)
            val sharedApps = if (json.has("apps")) json.getJSONObject("apps") else null
            AppRule(
                packageName = key.removePrefix(RULE_PREFIX),
                label = json.getString("label"),
                limitMinutes = json.getInt("limitMinutes"),
                message = json.getString("message"),
                mode = BlockMode.valueOf(json.getString("mode")),
                enabled = json.optBoolean("enabled", true),
                sharedApps = sharedApps?.let { apps ->
                    apps.keys().asSequence().associateWith(apps::getString)
                }.orEmpty(),
            ).also(::validate)
        }.getOrNull()
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun save(rule: AppRule) {
        validate(rule)
        val json = JSONObject()
            .put("label", rule.label)
            .put("limitMinutes", rule.limitMinutes)
            .put("message", rule.message.trim())
            .put("mode", rule.mode.name)
            .put("enabled", rule.enabled)
        if (rule.sharedApps.isNotEmpty()) json.put("apps", JSONObject(rule.sharedApps))
        preferences.edit()
            .putString(RULE_PREFIX + rule.id, json.toString())
            .remove(SNOOZE_DAY_PREFIX + rule.id)
            .remove(SNOOZE_USAGE_PREFIX + rule.id)
            .apply()
    }

    fun allCategoryRules(): List<CategoryRule> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(CATEGORY_RULE_PREFIX) || value !is String) return@mapNotNull null
        runCatching {
            val json = JSONObject(value)
            CategoryRule(
                category = AppCategoryPreset.valueOf(key.removePrefix(CATEGORY_RULE_PREFIX)),
                limitMinutes = json.getInt("limitMinutes"),
                message = json.getString("message"),
                mode = BlockMode.valueOf(json.getString("mode")),
                enabled = json.optBoolean("enabled", true),
            ).also(::validate)
        }.getOrNull()
    }.sortedBy { it.label }

    fun save(rule: CategoryRule) {
        validate(rule)
        val json = JSONObject()
            .put("limitMinutes", rule.limitMinutes)
            .put("message", rule.message.trim())
            .put("mode", rule.mode.name)
            .put("enabled", rule.enabled)
        preferences.edit()
            .putString(CATEGORY_RULE_PREFIX + rule.category.name, json.toString())
            .remove(SNOOZE_DAY_PREFIX + rule.id)
            .remove(SNOOZE_USAGE_PREFIX + rule.id)
            .apply()
    }

    fun remove(id: String) {
        preferences.edit()
            .remove(RULE_PREFIX + id)
            .remove(SNOOZE_DAY_PREFIX + id)
            .remove(SNOOZE_USAGE_PREFIX + id)
            .remove(RESET_DAY_PREFIX + id)
            .remove(RESET_USAGE_PREFIX + id)
            .apply()
    }

    fun removeCategory(category: AppCategoryPreset) {
        val id = "category.${category.name}"
        preferences.edit()
            .remove(CATEGORY_RULE_PREFIX + category.name)
            .remove(SNOOZE_DAY_PREFIX + id)
            .remove(SNOOZE_USAGE_PREFIX + id)
            .remove(RESET_DAY_PREFIX + id)
            .remove(RESET_USAGE_PREFIX + id)
            .apply()
    }

    /** Five more minutes of measured app use, rather than five minutes on the clock. */
    fun snooze(id: String, usedMs: Long, mode: BlockMode, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        if (mode != BlockMode.SOFT || !enabled) return
        val thresholdMs = usedMs.coerceIn(0L, Long.MAX_VALUE - FIVE_MINUTES_MS) + FIVE_MINUTES_MS
        preferences.edit()
            .putLong(SNOOZE_DAY_PREFIX + id, localDayStart(now))
            .putLong(SNOOZE_USAGE_PREFIX + id, thresholdMs)
            .apply()
    }

    fun snoozeThreshold(id: String, now: Long = System.currentTimeMillis()): Long? {
        if (preferences.getLong(SNOOZE_DAY_PREFIX + id, Long.MIN_VALUE) != localDayStart(now)) {
            return null
        }
        return preferences.getLong(SNOOZE_USAGE_PREFIX + id, 0L).takeIf { it > 0L }
    }

    /**
     * Zero the current usage for [id] for the rest of today. The raw measurement
     * stays intact; [usageOffset] is subtracted wherever the limit is evaluated or
     * displayed, so History continues to reflect the true total.
     */
    fun resetUsage(id: String, measuredUsageMs: Long, now: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(RESET_DAY_PREFIX + id, localDayStart(now))
            .putLong(RESET_USAGE_PREFIX + id, measuredUsageMs.coerceAtLeast(0L))
            .remove(SNOOZE_DAY_PREFIX + id)
            .remove(SNOOZE_USAGE_PREFIX + id)
            .apply()
    }

    fun usageOffset(id: String, now: Long = System.currentTimeMillis()): Long {
        if (preferences.getLong(RESET_DAY_PREFIX + id, Long.MIN_VALUE) != localDayStart(now)) return 0L
        return preferences.getLong(RESET_USAGE_PREFIX + id, 0L)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun validate(rule: AppRule) {
        require(rule.id.isNotBlank()) { "Choose an app." }
        require(rule.label.isNotBlank()) { "The app name cannot be blank." }
        if (rule.sharedApps.isNotEmpty()) {
            require(rule.sharedApps.size >= 2 && rule.sharedApps.all { (pkg, label) ->
                pkg.isNotBlank() && label.isNotBlank()
            }) { "Choose at least two apps for a shared limit." }
        }
        require(rule.limitMinutes in 1..1440) { "Choose a limit from 1 to 1440 minutes." }
        require(rule.message.isNotBlank() && rule.message.length <= 120) {
            "Enter a reminder of 1 to 120 characters."
        }
    }

    private fun validate(rule: CategoryRule) {
        require(rule.limitMinutes in 1..1440) { "Choose a limit from 1 to 1440 minutes." }
        require(rule.message.isNotBlank() && rule.message.length <= 120) {
            "Enter a reminder of 1 to 120 characters."
        }
    }

    private companion object {
        const val RULE_PREFIX = "rule."
        const val CATEGORY_RULE_PREFIX = "category.rule."
        const val SNOOZE_DAY_PREFIX = "snooze.day."
        const val SNOOZE_USAGE_PREFIX = "snooze.usage."
        const val RESET_DAY_PREFIX = "reset.day."
        const val RESET_USAGE_PREFIX = "reset.usage."
        const val FIVE_MINUTES_MS = 300_000L
    }
}

internal fun localDayStart(now: Long, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
    timeInMillis = now
    add(Calendar.DATE, dayOffset)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis