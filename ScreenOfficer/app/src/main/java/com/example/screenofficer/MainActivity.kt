package com.example.screenofficer

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anhaki.picktime.PickHourMinute
import com.anhaki.picktime.utils.PickTimeFocusIndicator
import com.anhaki.picktime.utils.PickTimeTextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class SavedRules(val apps: List<AppRule>, val categories: List<CategoryRule>)
private enum class Screen { LIMITS, HISTORY, PICKER, EDIT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OfficerTheme { ScreenOfficerApp(lifecycle) } }
    }
}

@Composable
private fun OfficerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF9BD5C4), onPrimary = Color(0xFF00382E),
        primaryContainer = Color(0xFF1F3B32), onPrimaryContainer = Color(0xFFFFFCF5),
        secondaryContainer = Color(0xFF3A2F1E), onSecondaryContainer = Color(0xFFF5DCA6),
        background = Color(0xFF121814), surface = Color(0xFF121814),
        onBackground = Color(0xFFFFFCF5), onSurface = Color(0xFFFFFCF5),
        surfaceVariant = Color(0xFF24372E), onSurfaceVariant = Color(0xFFC7D5CC),
        outline = Color(0xFF81958A),
    ) else lightColorScheme(
        primary = Color(0xFF145E52), onPrimary = Color.White,
        primaryContainer = Color(0xFFEDF5EE), onPrimaryContainer = Color(0xFF163D36),
        secondaryContainer = Color(0xFFF5DCA6), onSecondaryContainer = Color(0xFF163D36),
        background = Color(0xFFFFFCF5), surface = Color(0xFFFFFCF5),
        onBackground = Color(0xFF163D36), onSurface = Color(0xFF163D36),
        surfaceVariant = Color(0xFFEDF5EE), onSurfaceVariant = Color(0xFF4C6159),
        outline = Color(0xFF74867B),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ScreenOfficerApp(lifecycle: Lifecycle) {
    val context = LocalContext.current
    val store = remember { RuleStore(context.applicationContext) }
    val repository = remember { UsageRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf(emptyList<AppRule>()) }
    var categoryRules by remember { mutableStateOf(emptyList<CategoryRule>()) }
    var apps by remember { mutableStateOf(emptyList<TrackedApp>()) }
    var appsLoading by remember { mutableStateOf(true) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }
    var usageAllowed by remember { mutableStateOf(false) }
    var serviceConnected by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(Screen.LIMITS) }
    var editBackScreen by rememberSaveable { mutableStateOf(Screen.LIMITS) }
    var selectedRuleId by rememberSaveable { mutableStateOf("") }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var selectedCategoryRule by rememberSaveable { mutableStateOf<String?>(null) }
    var draftApps by rememberSaveable { mutableStateOf(linkedMapOf<String, String>()) }
    var draftHours by rememberSaveable { mutableStateOf(0) }
    var draftMinutes by rememberSaveable { mutableStateOf(30) }
    var draftMessage by rememberSaveable { mutableStateOf(DEFAULT_MESSAGE) }
    var draftMode by rememberSaveable { mutableStateOf(BlockMode.SOFT.name) }
    var draftEnabled by rememberSaveable { mutableStateOf(true) }
    var validation by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var appSelection by rememberSaveable { mutableStateOf(linkedMapOf<String, String>()) }
    var historyDaysAgo by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<DailyUsage?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var permissionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var clearDialog by rememberSaveable { mutableStateOf(false) }
    var limitToClear by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshVersion++ }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(resumed, refreshVersion, apps) {
        if (!resumed) return@LaunchedEffect
        do {
            try {
                val result = withContext(Dispatchers.IO) {
                    val currentRules = store.allRules()
                    val currentCategories = store.allCategoryRules()
                    val trackedPackages = currentRules.flatMap { it.packages }.toSet() +
                        currentCategories.flatMap { it.asTarget(apps).packages }
                    Triple(SavedRules(currentRules, currentCategories), repository.hasAccess(), repository.snapshot(trackedPackages))
                }
                rules = result.first.apps
                categoryRules = result.first.categories
                usageAllowed = result.second
                snapshot = result.third
                serviceConnected = AppBlockingService.connected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = null
                usageAllowed = false
                serviceConnected = AppBlockingService.connected
            }
            delay(3_000)
        } while (resumed)
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        appsLoading = true
        appsError = null
        try {
            apps = withContext(Dispatchers.IO) { AppCatalog(context.applicationContext).loadApps() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            appsError = "Installed apps could not be loaded. Return to this screen to try again."
        } finally {
            appsLoading = false
        }
    }

    val historyDay = localDayStart(snapshot?.checkedAtMs ?: System.currentTimeMillis(), -historyDaysAgo)
    LaunchedEffect(resumed, screen, historyDay, refreshVersion, usageAllowed) {
        if (resumed && screen == Screen.HISTORY && usageAllowed) {
            history = withContext(Dispatchers.IO) { repository.history(historyDay) }
        }
    }

    fun editRule(rule: AppRule, category: AppCategoryPreset? = null) {
        editBackScreen = screen
        selectedRuleId = rule.id
        selectedCategoryRule = category?.name
        selectedLabel = rule.label
        draftApps = LinkedHashMap(rule.sharedApps)
        draftHours = rule.limitMinutes / 60
        draftMinutes = rule.limitMinutes % 60
        draftMessage = rule.message
        draftMode = rule.mode.name
        draftEnabled = rule.enabled
        validation = false
        notice = null
        keyboard?.hide()
        screen = Screen.EDIT
    }

    fun editCategory(category: AppCategoryPreset) {
        val rule = categoryRules.firstOrNull { it.category == category } ?: CategoryRule(category)
        editRule(rule.asAppRule(), category)
    }

    fun editSelection() {
        if (appSelection.isEmpty()) return
        val existing = rules.firstOrNull { it.packages == appSelection.keys }
        val rule = existing ?: if (appSelection.size == 1) {
            val (packageName, label) = appSelection.entries.single()
            AppRule(packageName, label)
        } else {
            AppRule("group.${UUID.randomUUID()}", "Shared limit", sharedApps = appSelection)
        }
        editRule(rule)
    }

    fun changeRule(action: () -> Unit, success: String) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    action()
                    SavedRules(store.allRules(), store.allCategoryRules())
                }
                rules = updated.apps
                categoryRules = updated.categories
                refreshVersion++
                notice = success
                keyboard?.hide()
                screen = Screen.LIMITS
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "Your change could not be saved. Please try again."
            } finally {
                saving = false
            }
        }
    }

    fun goBack() {
        if (saving) return
        keyboard?.hide()
        notice = null
        screen = if (screen == Screen.EDIT) editBackScreen else Screen.LIMITS
    }
    BackHandler(screen != Screen.LIMITS, onBack = ::goBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (screen == Screen.PICKER || screen == Screen.EDIT) {
                        TextButton(onClick = ::goBack, enabled = !saving) { Text("Back") }
                    }
                    Text("ScreenOfficer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (screen == Screen.LIMITS || screen == Screen.HISTORY) {
                    PrimaryTabRow(selectedTabIndex = if (screen == Screen.LIMITS) 0 else 1) {
                        Tab(selected = screen == Screen.LIMITS, onClick = { screen = Screen.LIMITS }, text = { Text("Limits") })
                        Tab(selected = screen == Screen.HISTORY, onClick = { screen = Screen.HISTORY }, text = { Text("History") })
                    }
                }
            }
        },
        bottomBar = {
            if (screen == Screen.PICKER) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = ::editSelection,
                        enabled = appSelection.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
                    ) {
                        Text(when (appSelection.size) {
                            0 -> "Continue"
                            1 -> "Continue with 1 app"
                            else -> "Continue with ${appSelection.size} apps"
                        })
                    }
                }
            }
        },
    ) { padding ->
        key(screen) {
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                notice?.takeIf { screen != Screen.HISTORY }?.let {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                when (screen) {
                    Screen.PICKER -> {
                        Heading("Choose apps", "Check the apps that should share one daily limit.")
                        Text("Browse by category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = categoryFilter == null,
                                onClick = { categoryFilter = null },
                                label = { Text("All apps") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            AppCategoryPreset.entries.forEach { category ->
                                FilterChip(
                                    selected = categoryFilter == category.name,
                                    onClick = { categoryFilter = category.name },
                                    label = { Text(category.title) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        val selectedCategory = categoryFilter?.let { AppCategoryPreset.valueOf(it) }
                        selectedCategory?.let { category ->
                            Button(
                                onClick = { editCategory(category) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) { Text("Limit all ${category.title.lowercase()} apps together") }
                        }
                        OutlinedTextField(
                            value = search, onValueChange = { search = it }, label = { Text("Search apps") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        if (appsLoading) Text("Loading installed apps…")
                        appsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val matching = apps.filter {
                            (selectedCategory == null || selectedCategory in it.categories) &&
                                (it.label.contains(search, true) || it.packageName.contains(search, true))
                        }
                        if (!appsLoading && matching.isEmpty()) {
                            if (usageAllowed) {
                                Text("No matching apps in your recent usage.")
                            } else {
                                Text("Enable Usage Access to find apps you’ve used recently.")
                                TextButton(onClick = { permissionDialog = "usage" }) { Text("Enable Usage Access") }
                            }
                        }
                        matching.forEach { app ->
                            AppSelectionRow(app, checked = app.packageName in appSelection, onCheckedChange = { checked ->
                                appSelection = LinkedHashMap(appSelection).apply {
                                    if (checked) put(app.packageName, app.label) else remove(app.packageName)
                                }
                            })
                        }
                    }
                    Screen.EDIT -> {
                        val category = selectedCategoryRule?.let { AppCategoryPreset.valueOf(it) }
                        Heading(
                            if (category != null || draftApps.isNotEmpty()) "Shared daily limit" else "Daily limit",
                            when {
                                category != null -> "All ${category.title.lowercase()} apps share this allowance."
                                draftApps.isNotEmpty() -> draftApps.values.joinToString(", ")
                                else -> selectedLabel
                            },
                        )
                        Text("Usage resets at midnight.", style = MaterialTheme.typography.bodySmall)
                        if (draftHours == 24) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("24 hours", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    draftHours = 23
                                    draftMinutes = 59
                                }) { Text("Change duration") }
                            }
                        } else {
                            PickHourMinute(
                                initialHour = draftHours,
                                initialMinute = draftMinutes,
                                onHourChange = { draftHours = it },
                                onMinuteChange = { draftMinutes = it },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextStyle = PickTimeTextStyle(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                focusIndicator = PickTimeFocusIndicator(
                                    enabled = true,
                                    background = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                        val minutes = draftMinutes + draftHours * 60
                        if (validation && minutes !in 1..1440) {
                            Text("Choose a limit of at least one minute.", color = MaterialTheme.colorScheme.error)
                        }
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("When the limit is reached", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            ModeChoice("Soft block", "Pause with a reminder. You can choose five more minutes of app use.", draftMode == BlockMode.SOFT.name) { draftMode = BlockMode.SOFT.name }
                            ModeChoice("Hard block", "The app stays blocked until midnight. There is no continue button.", draftMode == BlockMode.HARD.name) { draftMode = BlockMode.HARD.name }
                        }
                        OutlinedTextField(
                            value = draftMessage, onValueChange = { draftMessage = it.take(120) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Your reminder message") }, minLines = 2, maxLines = 4,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            isError = validation && draftMessage.isBlank(),
                            supportingText = { Text(if (validation && draftMessage.isBlank()) "Enter a reminder message." else "${draftMessage.length}/120 characters") },
                        )
                        Button(
                            onClick = {
                                validation = true
                                if (minutes in 1..1440 && draftMessage.isNotBlank()) {
                                    val mode = BlockMode.valueOf(draftMode)
                                    if (category == null) {
                                        val rule = AppRule(selectedRuleId, selectedLabel, minutes, draftMessage.trim(), mode, draftEnabled, draftApps)
                                        val message = if (draftApps.isEmpty()) "Limit saved for $selectedLabel." else "Shared limit saved for ${draftApps.size} apps."
                                        changeRule({ store.save(rule) }, message)
                                    } else {
                                        val rule = CategoryRule(category, minutes, draftMessage.trim(), mode, draftEnabled)
                                        changeRule({ store.save(rule) }, "Shared limit saved for ${category.title}.")
                                    }
                                }
                            },
                            enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text(if (saving) "Saving…" else "Save limit") }
                    }
                    Screen.HISTORY -> UsageHistory(
                        dayStart = historyDay,
                        daysAgo = historyDaysAgo,
                        history = history?.takeIf { it.dayStartMs == historyDay },
                        usageAllowed = usageAllowed,
                        onDayChange = { historyDaysAgo = it },
                        onRefresh = { refreshVersion++ },
                        onEnableUsage = { permissionDialog = "usage" },
                    )
                    Screen.LIMITS -> {
                        Heading("Make room for what matters.", "See today's measured app use and choose when to pause.")
                        AccessCard(
                            usageAllowed, serviceConnected,
                            onUsage = { permissionDialog = "usage" },
                            onBlocking = { permissionDialog = "blocking" },
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Today", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { refreshVersion++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                        }
                        snapshot?.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (rules.isEmpty() && categoryRules.isEmpty()) {
                            Text("No limits yet. Add a limit for one app or a group.")
                        }
                        val todayUsage = snapshot?.takeIf { it.available && usageAllowed }?.usageMs
                        rules.forEach { rule ->
                            val target = LimitTarget(rule)
                            RuleCard(
                                rule,
                                todayUsage?.takeIf { it.keys.containsAll(target.packages) }?.let(target::usedMs),
                                members = rule.sharedApps.values.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                onEdit = { editRule(rule) },
                                onToggle = { changeRule({ store.save(rule.copy(enabled = !rule.enabled)) }, if (rule.enabled) "${rule.label} limit paused." else "${rule.label} limit resumed.") },
                                onClear = { limitToClear = rule.id },
                                enabled = !saving,
                            )
                        }
                        categoryRules.forEach { rule ->
                            val target = rule.asTarget(apps)
                            RuleCard(
                                target.rule,
                                todayUsage?.takeIf { it.keys.containsAll(target.packages) }?.let(target::usedMs),
                                members = "Shared across ${rule.label.lowercase()} apps",
                                onEdit = { editCategory(rule.category) },
                                onToggle = { changeRule({ store.save(rule.copy(enabled = !rule.enabled)) }, if (rule.enabled) "${rule.label} limit paused." else "${rule.label} limit resumed.") },
                                onClear = { limitToClear = rule.id },
                                enabled = !saving,
                            )
                        }
                        Button(
                            onClick = {
                                search = ""
                                categoryFilter = null
                                appSelection = linkedMapOf()
                                notice = null
                                screen = Screen.PICKER
                            },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text("Add a limit") }
                        Text("Soft blocks let you extend use by five minutes. Hard blocks last until midnight. The phone owner can always change these settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Usage and limits stay on this device. ScreenOfficer does not read app content. Android provides the usage history; timing can vary slightly by device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (rules.isNotEmpty() || categoryRules.isNotEmpty()) TextButton(onClick = { clearDialog = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clear all limits") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (permissionDialog != null) {
        val usage = permissionDialog == "usage"
        val blockingServiceLabel = stringResource(R.string.blocking_service_label)
        AlertDialog(
            onDismissRequest = { permissionDialog = null },
            title = { Text(if (usage) "Allow usage tracking" else "Set up app blocking") },
            text = {
                if (usage) {
                    Text("Usage Access lets ScreenOfficer measure app use for daily limits and show recent daily totals in History. Results stay on this device. You can revoke access in Android Settings.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("The accessibility service detects the open app so ScreenOfficer can show your blocking card.")
                        Text("On Samsung, tap Installed apps in Accessibility. On other phones, look under Downloaded apps or in the list of services.")
                        Text("Select $blockingServiceLabel, turn it on, and confirm. Return to ScreenOfficer to check that the service is connected.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionDialog = null
                    try {
                        if (usage) PermissionAccess.openUsageSettings(context) else PermissionAccess.openAccessibilitySettings(context)
                    } catch (_: Exception) {
                        notice = if (usage) {
                            "Open Android Settings to enable Usage Access."
                        } else {
                            "Open Accessibility in Android Settings and select $blockingServiceLabel."
                        }
                    }
                }) { Text(if (usage) "Open settings" else "Open Accessibility settings") }
            },
            dismissButton = { TextButton(onClick = { permissionDialog = null }) { Text("Cancel") } },
        )
    }
    if (clearDialog) AlertDialog(
        onDismissRequest = { clearDialog = false }, title = { Text("Clear all limits?") },
        text = { Text("Remove all daily limits and their five-minute extensions.") },
        confirmButton = { TextButton(onClick = { clearDialog = false; changeRule({ store.clear() }, "All limits cleared.") }) { Text("Clear all limits") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } },
    )
    limitToClear?.let { id ->
        val rule = rules.firstOrNull { it.id == id }
        val categoryRule = categoryRules.firstOrNull { it.id == id }
        if (rule != null || categoryRule != null) {
            val group = rule?.sharedApps?.values.orEmpty()
            val title = if (group.isNotEmpty()) "Clear shared limit?" else "Clear limit for ${rule?.label ?: categoryRule?.label}?"
            AlertDialog(
                onDismissRequest = { limitToClear = null },
                title = { Text(title) },
                text = {
                    if (group.isNotEmpty()) {
                        Text(group.joinToString(", "), Modifier.verticalScroll(rememberScrollState()))
                    } else {
                        Text("Remove this daily limit and its five-minute extension.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        limitToClear = null
                        changeRule(
                            action = {
                                if (categoryRule == null) store.remove(id) else store.removeCategory(categoryRule.category)
                            },
                            success = "Limit cleared.",
                        )
                    }) { Text("Clear limit") }
                },
                dismissButton = { TextButton(onClick = { limitToClear = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun AppSelectionRow(app: TrackedApp, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    app.categories.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.title } ?: app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Heading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccessCard(usage: Boolean, blocking: Boolean, onUsage: () -> Unit, onBlocking: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (usage && blocking) "Tracking and blocking ready" else "Set up tracking and blocking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Usage Access: ${if (usage) "on" else "off"}\nBlocking service: ${if (blocking) "connected" else "off"}", style = MaterialTheme.typography.bodyMedium)
            if (!usage || !blocking) Text("Both permissions are needed to interrupt an app at its limit.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onUsage, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (usage) "Manage Usage Access" else "Enable Usage Access") }
            TextButton(onClick = onBlocking, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (blocking) "Manage blocking service" else "Set up app blocking") }
        }
    }
}

@Composable
private fun ModeChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).semantics(mergeDescendants = true) {},
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            RadioButton(selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun RuleCard(
    rule: AppRule,
    usedMs: Long?,
    members: String?,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(rule.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            members?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(if (usedMs == null) "Usage unavailable / ${rule.limitMinutes} min limit" else "${formatUsage(usedMs)} today / ${rule.limitMinutes} min limit", style = MaterialTheme.typography.bodyLarge)
            if (usedMs != null) LinearProgressIndicator(
                progress = { (usedMs.toFloat() / (rule.limitMinutes * 60_000L)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${rule.label} daily usage" },
            )
            Text("${if (rule.mode == BlockMode.SOFT) "Soft block" else "Hard block"} · ${if (rule.enabled) "Active" else "Paused"}", style = MaterialTheme.typography.labelLarge)
            Text("“${rule.message}”", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Edit limit for ${rule.label}" }) { Text("Edit") }
                TextButton(onClick = onToggle, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${if (rule.enabled) "Pause" else "Resume"} limit for ${rule.label}" }) { Text(if (rule.enabled) "Pause" else "Resume") }
                TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear limit for ${rule.label}" }) { Text("Clear limit") }
            }
        }
    }
}
internal fun formatUsage(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return if (seconds < 60) "$seconds sec" else "${seconds / 60} min ${seconds % 60} sec"
}

