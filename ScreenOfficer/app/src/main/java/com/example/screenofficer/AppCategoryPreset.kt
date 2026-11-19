package com.example.screenofficer

import android.content.pm.ApplicationInfo

/**
 * Friendly groups for the app picker. Android only supplies a reliable category for
 * some apps, so common package families fill in the gaps for the useful presets.
 */
enum class AppCategoryPreset(val title: String) {
    SOCIAL_MEDIA("Social media"),
    GAMES("Games"),
    STREAMING("Video & streaming"),
    MESSAGING("Messaging"),
    SHOPPING("Shopping"),
}

object AppCategoryClassifier {
    fun categoriesFor(packageName: String, androidCategory: Int): Set<AppCategoryPreset> {
        val normalized = packageName.lowercase()
        return buildSet {
            if (androidCategory == ApplicationInfo.CATEGORY_GAME) {
                add(AppCategoryPreset.GAMES)
            }
            if (normalized.startsWithAny(SOCIAL_PREFIXES)) add(AppCategoryPreset.SOCIAL_MEDIA)
            if (normalized.startsWithAny(STREAMING_PREFIXES)) add(AppCategoryPreset.STREAMING)
            if (normalized.startsWithAny(MESSAGING_PREFIXES)) add(AppCategoryPreset.MESSAGING)
            if (normalized.startsWithAny(SHOPPING_PREFIXES)) add(AppCategoryPreset.SHOPPING)
        }
    }

    private fun String.startsWithAny(prefixes: Set<String>) = prefixes.any { prefix -> startsWith(prefix) }

    private val SOCIAL_PREFIXES = setOf(
        "com.instagram", "com.facebook.", "com.twitter.", "com.x.", "com.reddit.",
        "com.snapchat.", "com.linkedin.", "com.pinterest.", "com.tumblr.", "com.threads.",
    )
    private val STREAMING_PREFIXES = setOf(
        "com.netflix.", "com.google.android.youtube", "com.disney.", "com.amazon.avod.",
        "tv.twitch.", "com.hbo.", "com.spotify.",
    )
    private val MESSAGING_PREFIXES = setOf(
        "com.whatsapp", "org.telegram.", "com.discord", "com.slack", "com.facebook.orca",
        "com.google.android.apps.messaging", "com.viber.", "com.skype.",
    )
    private val SHOPPING_PREFIXES = setOf(
        "com.amazon.", "com.ebay.", "com.etsy.", "com.alibaba.", "com.shein.", "com.temu.",
    )
}
