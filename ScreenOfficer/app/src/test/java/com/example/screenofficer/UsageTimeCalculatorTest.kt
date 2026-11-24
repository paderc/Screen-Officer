package com.example.screenofficer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UsageTimeCalculatorTest {
    private val app = "app.one"
    private val other = "app.two"
    private fun event(at: Long, kind: UsageEventKind, pkg: String? = app, activity: String? = null) =
        UsageTimeEvent(at, kind, pkg, activity)

    @Test fun countsClosedSessionsAndAnOpenForegroundSession() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED), event(30, UsageEventKind.PAUSED),
                event(50, UsageEventKind.RESUMED),
            ), 0, 90, setOf(app),
        )
        assertEquals(60L, result.usageMs[app])
        assertEquals(app, result.foregroundPackage)
    }

    @Test fun waitingOnABlockDoesNotSpendTheFiveMinuteExtension() {
        val rule = AppRule(app, "One", limitMinutes = 1)
        val blockStartedAt = 60_000L
        val continuedAt = blockStartedAt + 600_000L
        val events = listOf(event(0, UsageEventKind.RESUMED))
        val blocked = listOf(BlockedUsageInterval(app, blockStartedAt, continuedAt))
        val continuedUsage = UsageTimeCalculator.calculate(events, 0, continuedAt, setOf(app), blocked)
        assertEquals(blockStartedAt, continuedUsage.usageMs[app])
        assertEquals(app, continuedUsage.foregroundPackage)
        assertTrue(BlockPolicy.shouldBlock(rule, continuedUsage.usageMs.getValue(app), null))

        val snoozeThreshold = continuedUsage.usageMs.getValue(app) + 300_000L
        val beforeThreshold = UsageTimeCalculator.calculate(events, 0, continuedAt + 299_999, setOf(app), blocked)
        val atThreshold = UsageTimeCalculator.calculate(events, 0, continuedAt + 300_000, setOf(app), blocked)
        assertFalse(BlockPolicy.shouldBlock(rule, beforeThreshold.usageMs.getValue(app), snoozeThreshold))
        assertTrue(BlockPolicy.shouldBlock(rule, atThreshold.usageMs.getValue(app), snoozeThreshold))
    }

    @Test fun aBlockExcludesOnlyItsOwnForegroundAppWhileTheScreenIsOn() {
        val events = listOf(
            event(0, UsageEventKind.RESUMED),
            event(20, UsageEventKind.SCREEN_OFF, null),
            event(40, UsageEventKind.SCREEN_ON, null),
            event(60, UsageEventKind.RESUMED, other),
        )
        val blocked = listOf(BlockedUsageInterval(app, 10, 80))

        val result = UsageTimeCalculator.calculate(events, 0, 90, setOf(app, other), blocked)

        assertEquals(mapOf(app to 10L, other to 30L), result.usageMs)
        assertEquals(other, result.foregroundPackage)
    }

    @Test fun overlappingBlocksExcludeEachMillisecondOnce() {
        val events = listOf(event(0, UsageEventKind.RESUMED))
        val blocked = listOf(
            BlockedUsageInterval(app, 20, 70),
            BlockedUsageInterval(app, 10, 50),
            BlockedUsageInterval(app, 30, 40),
        )

        val result = UsageTimeCalculator.calculate(events, 0, 100, setOf(app), blocked)

        assertEquals(40L, result.usageMs[app])
    }

    @Test fun aBlockAcrossMidnightIsClippedToEachDay() {
        val events = listOf(event(0, UsageEventKind.RESUMED), event(200, UsageEventKind.PAUSED))
        val blocked = listOf(BlockedUsageInterval(app, 80, 140))
        val previousDay = UsageTimeCalculator.calculate(events, 0, 100, setOf(app), blocked)
        val nextDay = UsageTimeCalculator.calculate(events, 100, 250, setOf(app), blocked)

        assertEquals(80L, previousDay.usageMs[app])
        assertEquals(60L, nextDay.usageMs[app])
    }

    @Test fun clipsAMidnightSessionWithoutCountingYesterday() {
        val result = UsageTimeCalculator.calculate(
            listOf(event(50, UsageEventKind.RESUMED), event(150, UsageEventKind.PAUSED)),
            100, 200, setOf(app),
        )
        assertEquals(50L, result.usageMs[app])
        assertNull(result.foregroundPackage)
    }

    @Test fun historyAllocatesASessionAcrossTwoDaysWithoutDoubleCounting() {
        val events = listOf(event(70, UsageEventKind.RESUMED), event(135, UsageEventKind.PAUSED))
        val previousDay = UsageTimeCalculator.calculate(events, 0, 100, setOf(app))
        val nextDay = UsageTimeCalculator.calculate(events, 100, 200, setOf(app))

        assertEquals(30L, previousDay.usageMs[app])
        assertEquals(35L, nextDay.usageMs[app])
    }

    @Test fun switchingAppsDoesNotRequireAPauseEventAndDoesNotOverlapTotals() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED),
                event(30, UsageEventKind.RESUMED, other),
                event(40, UsageEventKind.PAUSED), // Late pause for the previous app.
                event(70, UsageEventKind.PAUSED, other),
            ), 0, 90, setOf(app, other),
        )
        assertEquals(mapOf(app to 20L, other to 40L), result.usageMs)
        assertNull(result.foregroundPackage)
    }

    @Test fun oldActivityPauseDoesNotEndItsReplacementActivity() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED, activity = "Feed"),
                event(30, UsageEventKind.RESUMED, activity = "Video"),
                event(31, UsageEventKind.PAUSED, activity = "Feed"),
                event(50, UsageEventKind.STOPPED, activity = "Feed"),
                event(70, UsageEventKind.PAUSED, activity = "Video"),
            ), 0, 90, setOf(app),
        )
        assertEquals(60L, result.usageMs[app])
    }

    @Test fun screenOffAndLockedIntervalsAreExcluded() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED),
                event(30, UsageEventKind.SCREEN_OFF, null),
                event(31, UsageEventKind.KEYGUARD_SHOWN, null),
                event(60, UsageEventKind.SCREEN_ON, null),
                event(70, UsageEventKind.KEYGUARD_HIDDEN, null),
            ), 0, 90, setOf(app),
        )
        assertEquals(40L, result.usageMs[app])
        assertEquals(app, result.foregroundPackage)
    }

    @Test fun lockedDeviceHasNoForegroundAppAndNoGrowingUsage() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED),
                event(30, UsageEventKind.KEYGUARD_SHOWN, null),
            ), 0, 900, setOf(app),
        )
        assertEquals(20L, result.usageMs[app])
        assertNull(result.foregroundPackage)
    }

    @Test fun shutdownClosesTheSessionAndDoesNotCountPoweredOffTime() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED),
                event(30, UsageEventKind.SHUTDOWN, null),
                event(70, UsageEventKind.STARTUP, null),
            ), 0, 900, setOf(app),
        )
        assertEquals(20L, result.usageMs[app])
        assertNull(result.foregroundPackage)
    }

    @Test fun orphanedResumeBetweenShutdownAndStartupIsIgnored() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED),
                event(30, UsageEventKind.SHUTDOWN, null),
                event(35, UsageEventKind.RESUMED),
                event(70, UsageEventKind.STARTUP, null),
                event(80, UsageEventKind.RESUMED),
            ), 0, 90, setOf(app),
        )
        assertEquals(30L, result.usageMs[app])
    }

    @Test fun repeatedResumeEventsDoNotDoubleCountAndFutureEventsAreIgnored() {
        val result = UsageTimeCalculator.calculate(
            listOf(
                event(10, UsageEventKind.RESUMED), event(20, UsageEventKind.RESUMED),
                event(30, UsageEventKind.RESUMED), event(100, UsageEventKind.PAUSED),
            ), 0, 90, setOf(app),
        )
        assertEquals(80L, result.usageMs[app])
        assertEquals(app, result.foregroundPackage)
    }

    @Test fun unselectedAppsDoNotAppearInReturnedTotals() {
        val result = UsageTimeCalculator.calculate(
            listOf(event(10, UsageEventKind.RESUMED, other)), 0, 90, setOf(app),
        )
        assertEquals(mapOf(app to 0L), result.usageMs)
        assertEquals(other, result.foregroundPackage)
    }

    @Test fun noEventsMeansZeroUsageRatherThanAnInventedSession() {
        val result = UsageTimeCalculator.calculate(emptyList(), 100, 200, setOf(app))
        assertEquals(mapOf(app to 0L), result.usageMs)
        assertNull(result.foregroundPackage)
    }

    @Test fun localMidnightHandlesDaylightSavingWithoutAssuming24Hours() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            val previousDay = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.MARCH, 29, 12, 0)
            }.timeInMillis
            val nextDay = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.MARCH, 30, 12, 0)
            }.timeInMillis
            assertEquals(23 * 60 * 60 * 1000L, localDayStart(nextDay) - localDayStart(previousDay))
            assertEquals(localDayStart(nextDay), localDayStart(previousDay, dayOffset = 1))
            assertEquals(localDayStart(previousDay), localDayStart(nextDay, dayOffset = -1))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
