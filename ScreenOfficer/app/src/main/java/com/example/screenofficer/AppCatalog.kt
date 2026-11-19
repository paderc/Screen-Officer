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
                TrackedApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(context.packageManager).toString(),
                    category = AppCategoryClassifier.categoryOf(
                        packageName = it.activityInfo.packageName,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            it.activityInfo.applicationInfo.category else null
                    )
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy { it.label.lowercase(Locale.getDefault()) })
            .toList()
            .also { intents ->
                intents.forEach { add(it.packageName) }
            }
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

object PermissionAccess {
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