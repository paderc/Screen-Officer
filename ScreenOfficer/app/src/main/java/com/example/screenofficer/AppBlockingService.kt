package com.example.screenofficer

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Observes package changes only; it never reads app text, views, or screenshots. */
class AppBlockingService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private lateinit var store: RuleStore
    private lateinit var usage: UsageRepository
    private lateinit var blockedUsage: BlockedUsageStore
    private lateinit var windowManager: WindowManager
    private var registered = false
    private var foregroundHint: String? = null
    private var foregroundHintAt = 0L
    private var stateVersion = 0L
    private var inputMethodPackages: Set<String> = emptySet()
    private var overlay: View? = null
    private var overlayKey: OverlayKey? = null

    private data class OverlayKey(val rule: AppRule, val foregroundPackage: String, val dayStart: Long)

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        stateVersion++
        changes.trySend(Unit)
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_INPUT_METHOD_CHANGED) refreshInputMethods()
            stateVersion++
            foregroundHint = null
            hideOverlay()
            changes.trySend(Unit)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (registered) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connected = true
        store = RuleStore(this)
        usage = UsageRepository(this)
        blockedUsage = BlockedUsageStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        refreshInputMethods()
        store.preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        ContextCompat.registerReceiver(
            this,
            deviceReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_INPUT_METHOD_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        scope.launch { monitor() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!registered || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // IME windows belong to their keyboard package, but do not represent an app switch.
        // In particular, opening a keyboard must not suspend enforcement of the target app.
        if (packageName in inputMethodPackages) return
        // Our accessibility overlay emits window events under our package too. Treating those
        // as a foreground switch would remove and recreate the same block indefinitely.
        if (overlay != null && packageName == this.packageName &&
            event.className?.toString() != MainActivity::class.java.name
        ) return
        foregroundHint = packageName
        foregroundHintAt = SystemClock.elapsedRealtime()
        stateVersion++
        if (overlayKey?.foregroundPackage != packageName) hideOverlay()
        changes.trySend(Unit)
    }

    private suspend fun monitor() {
        while (scope.isActive) {
            val version = stateVersion
            val limits = withContext(Dispatchers.IO) {
                val protected = SafetyApps.protectedPackages(this@AppBlockingService)
                val appLimits = store.allRules().filter { it.enabled }.map { rule ->
                    LimitTarget(rule, rule.packages - protected)
                }
                val categoryRules = store.allCategoryRules().filter { it.enabled }
                val apps = if (categoryRules.isEmpty()) emptyList() else AppCatalog(this@AppBlockingService).loadApps()
                appLimits + categoryRules.map { it.asTarget(apps) }
            }
            if (version != stateVersion) continue
            if (limits.isEmpty() || !canDisplay()) {
                hideOverlay()
                // Package events, rule edits and unlock broadcasts wake an idle service.
                changes.receive()
                continue
            }
            val hint = foregroundHint
            val snapshot = withContext(Dispatchers.IO) {
                usage.snapshot(limits.flatMap { it.packages }.toSet())
            }
            if (version != stateVersion) continue
            // Window events can be transient dialogs and may never be followed by another
            // package event when that dialog closes. Reconcile them with measured resumed
            // activity state instead of trusting an untracked hint indefinitely. A short
            // grace interval prevents lagging UsageEvents from redrawing over Settings/Home;
            // SystemUI gets extra time for notification-shade and system-panel transitions.
            val graceMs = if (hint == "com.android.systemui") 5_000L else 1_500L
            val freshHint = hint != null && SystemClock.elapsedRealtime() - foregroundHintAt < graceMs
            val current = if (freshHint) hint else snapshot.foregroundPackage
            if (!freshHint && snapshot.available) foregroundHint = snapshot.foregroundPackage
            // A window event can precede the corresponding UsageEvents event slightly.
            val exceeded = if (snapshot.available && canDisplay() && snapshot.foregroundPackage == current) {
                BlockPolicy.exceededLimit(
                    limits, current, snapshot.usageMs,
                    snoozeThreshold = { store.snoozeThreshold(it, snapshot.checkedAtMs) },
                    usageOffset = { store.usageOffset(it, snapshot.checkedAtMs) },
                )
            } else null
            if (exceeded != null && current != null) {
                showOverlay(exceeded, current, snapshot)
            } else {
                hideOverlay()
            }
            val trackingForeground = snapshot.available && current != null && limits.any { current in it.packages }
            withTimeoutOrNull(if (trackingForeground) 1_000L else 5_000L) { changes.receive() }
        }
    }

    private fun refreshInputMethods() {
        inputMethodPackages = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.map { it.packageName }.toSet()
    }

    private fun canDisplay(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun showOverlay(limit: LimitTarget, foregroundPackage: String, snapshot: UsageSnapshot) {
        val rule = limit.rule
        val key = OverlayKey(rule, foregroundPackage, snapshot.dayStartMs)
        if (overlayKey == key && overlay != null) {
            blockedUsage.checkpoint()
            return
        }
        hideOverlay()
        val measuredMs = limit.usedMs(snapshot.usageMs, store.usageOffset(rule.id, snapshot.checkedAtMs))
        val viewContext = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar)
        val content = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
        }
        fun text(value: String, size: Float, bold: Boolean = false) {
            content.addView(TextView(viewContext).apply {
                text = value
                textSize = size
                setTextColor(Color.rgb(22, 61, 54))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
        }
        fun button(value: String, action: (Button) -> Unit) {
            content.addView(Button(viewContext).apply {
                text = value
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { action(this) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
        text(if (rule.mode == BlockMode.HARD) "Daily limit reached" else "Time for a check-in?", 23f, true)
        val limitName = rule.sharedApps[foregroundPackage]?.let { "Shared limit · $it" } ?: rule.label
        text(limitName, 16f, true)
        val timeUsed = if (limit.packages.size > 1) "min combined today" else "min used today"
        text("${measuredMs / 60_000} $timeUsed · ${rule.limitMinutes} min limit", 14f)
        text(rule.message, 16f)
        text(
            if (rule.mode == BlockMode.HARD)
                "Blocked until midnight."
            else "Take a break or continue for five minutes.",
            14f,
        )
        button("Go home") { goHome() }
        if (rule.mode == BlockMode.SOFT) {
            button("Continue for 5 more minutes") { clicked ->
                clicked.isEnabled = false
                scope.launch {
                    // The snapshot excludes the live blocking interval. Persist the extension
                    // against that usage before closing the card.
                    val version = stateVersion
                    val fresh = withContext(Dispatchers.IO) { usage.snapshot(limit.packages) }
                    if (overlayKey != key) return@launch
                    if (fresh.available && fresh.dayStartMs == key.dayStart && version == stateVersion) {
                        store.snooze(
                            rule.id,
                            limit.usedMs(fresh.usageMs, store.usageOffset(rule.id, fresh.checkedAtMs)),
                            rule.mode, rule.enabled, fresh.checkedAtMs,
                        )
                        hideOverlay()
                    } else {
                        clicked.isEnabled = true
                    }
                    changes.trySend(Unit)
                }
            }
        }
        button("Manage limits") {
            foregroundHint = packageName
            foregroundHintAt = SystemClock.elapsedRealtime()
            stateVersion++
            hideOverlay()
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            changes.trySend(Unit)
        }
        val scroller = ScrollView(viewContext).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 252, 245))
                cornerRadius = dp(22).toFloat()
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
            addView(content)
        }
        // The scrim consumes touches outside the card while the blocked app stays visible.
        val scrim = object : FrameLayout(viewContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) goHome()
                return true
            }
        }.apply {
            setBackgroundColor(0x66000000)
            fitsSystemWindows = true
            isClickable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val width = minOf(360, (resources.configuration.screenWidthDp - 40).coerceAtLeast(1))
            addView(scroller, FrameLayout.LayoutParams(dp(width), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            title = "ScreenOfficer daily limit"
        }
        try {
            windowManager.addView(scrim, params)
            overlay = scrim
            overlayKey = key
            blockedUsage.start(foregroundPackage)
            scrim.requestFocus()
            scroller.announceForAccessibility("${rule.label}. Daily limit reached. ${rule.message}")
        } catch (_: WindowManager.BadTokenException) {
            // Android may detach the service while a check is completing.
            hideOverlay()
        } catch (_: SecurityException) {
            hideOverlay()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stateVersion++
        hideOverlay()
        changes.trySend(Unit)
    }

    private fun goHome() {
        // Suppress checks until the HOME transition supplies its new foreground package.
        foregroundHint = packageName
        foregroundHintAt = SystemClock.elapsedRealtime()
        stateVersion++
        hideOverlay()
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        changes.trySend(Unit)
    }

    private fun hideOverlay() {
        val old = overlay
        overlay = null
        overlayKey = null
        if (old != null) {
            try {
                windowManager.removeViewImmediate(old)
            } catch (_: IllegalArgumentException) {
                // Window already removed as the service was detached.
            } finally {
                blockedUsage.stop()
            }
        }
    }

    override fun onInterrupt() {
        hideOverlay()
        changes.trySend(Unit)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        connected = false
        hideOverlay()
        if (registered) {
            store.preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            unregisterReceiver(deviceReceiver)
            registered = false
        }
        scope.cancel()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
    }
}
