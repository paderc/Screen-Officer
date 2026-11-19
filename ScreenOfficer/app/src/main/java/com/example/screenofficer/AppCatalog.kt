package com.example.screenofficer

import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

/* Only launchable apps are offered; essential phone controls remain hidden. */
class AppCatalog(private val context: Context) {
    fun loadApps(): List<TrackedApp> {
        val protectedPackages = SafetyApps.protectedPackages(context)

        return context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL
        )
            .filter {
                val packageName = it.activityInfo.packageName
                (packageName !in protectedPackages) &&
                        !packageName.contains("gov")
            }
            .map {
                val packageName = it.activityInfo.packageName
                val appInfo = it.activityInfo.applicationInfo
                TrackedApp(
                    packageName = packageName,
                    label = it.loadLabel(context.packageManager).toString(),
                    categories = AppCategoryClassifier.categoriesFor(
                        packageName,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appInfo.category else -1,
                    ),
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy { it.label.lowercase(Locale.getDefault()) })
            .toList()
    }
}

object ActiveApps {
    internal fun usedSinceLastMonth(context: Context): Set<String> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val now = Calendar.getInstance()
        val lastWeek = now.clone() as Calendar
        lastWeek.add(Calendar.DAY_OF_YEAR, -30)

        val stats = usageStatsManager.queryAndAggregateUsageStats(
            lastWeek.timeInMillis,
            now.timeInMillis
        )

        return stats.values
            .filter { it.totalTimeInForeground > 0 }
            .map { it.packageName }
            .toSet()
    }
}

object SafetyApps {
    fun isProtected(context: Context, packageName: String): Boolean =
        packageName in protectedPackages(context)

    // Resolve on demand so newly installed launchers and dialers are protected too.
    internal fun protectedPackages(context: Context): Set<String> = buildSet {
        add(context.packageName)
        addAll(listOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.server.telecom",
            "com.android.emergency", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
        ))
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Intent.ACTION_DIAL),
            Intent(Settings.ACTION_SETTINGS),
            Intent("android.intent.action.EMERGENCY_DIAL"),
        )
        intents.forEach { intent ->
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .forEach { add(it.activityInfo.packageName) }
        }
    }
}

object PermissionAccess {
    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
    fun openUsageSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}