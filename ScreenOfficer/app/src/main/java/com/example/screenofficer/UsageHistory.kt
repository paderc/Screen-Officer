package com.example.screenofficer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal const val HISTORY_DAY_COUNT = 7

@Composable
internal fun UsageHistory(
    dayStart: Long,
    daysAgo: Int,
    history: DailyUsage?,
    usageAllowed: Boolean,
    onDayChange: (Int) -> Unit,
    onEnableUsage: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Usage history",
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    val date = remember(dayStart) { DateFormat.getDateInstance(DateFormat.FULL).format(Date(dayStart)) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (daysAgo) {
                0 -> Text("Today", style = MaterialTheme.typography.labelLarge)
                1 -> Text("Yesterday", style = MaterialTheme.typography.labelLarge)
            }
            Text(date, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (history?.apps?.isNotEmpty() == true) {
                Text("${formatUsage(history.apps.sumOf { it.usedMs })} total")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { onDayChange(daysAgo + 1) },
                    enabled = daysAgo < HISTORY_DAY_COUNT - 1,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Previous day") }
                TextButton(
                    onClick = { onDayChange(daysAgo - 1) },
                    enabled = daysAgo > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Next day") }
            }
        }
    }

    when {
        !usageAllowed -> {
            Text("Enable Usage Access to see app activity by day.")
            TextButton(onClick = onEnableUsage) { Text("Enable Usage Access") }
        }
        history == null -> Text("Loading usage…")
        history.error != null -> Text(history.error, color = MaterialTheme.colorScheme.error)
        history.apps.isEmpty() -> Text("No app activity is available for this day. Android keeps detailed usage for a few days.")
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                history.apps.forEach { app ->
                    key(app.packageName) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(formatUsage(app.usedMs), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
