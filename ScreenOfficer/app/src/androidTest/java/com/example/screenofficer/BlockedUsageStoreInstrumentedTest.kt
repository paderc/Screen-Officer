package com.example.screenofficer

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a test device because this suite resets ScreenOfficer's blocked usage records. */
@RunWith(AndroidJUnit4::class)
class BlockedUsageStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(BlockedUsageStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val start = 1_800_000_000_000L
    private var wall = start
    private var elapsed = 10_000L
    private val packageName = "test.blocked.app"

    @Before
    @After
    fun resetStore() {
        store().stop()
        preferences.edit().clear().apply()
    }

    @Test fun liveAndClosedIntervalsAreSharedAcrossStoreInstances() {
        val writer = store()
        val reader = store()
        writer.start(packageName)
        advance(15_000L)

        assertEquals(
            listOf(BlockedUsageInterval(packageName, start + 2_000L, wall - 2_000L)),
            reader.intervals(start + 2_000L, wall - 2_000L),
        )

        val closedAt = wall
        writer.stop()
        advance(20_000L)
        assertEquals(
            listOf(BlockedUsageInterval(packageName, start, closedAt)),
            store().intervals(start, wall),
        )
    }

    @Test fun aRestoredCheckpointHasAFiniteEnd() {
        val writer = store()
        writer.start(packageName)
        advance(12_000L)
        writer.checkpoint()
        val lastCheckpoint = wall
        val savedEntries = preferences.all.mapValues { (_, value) -> value as String }
        advance(7_000L)
        writer.checkpoint()
        assertEquals(savedEntries, preferences.all)

        // Restore the saved entries with the in-memory overlay cleared to simulate process loss.
        writer.stop()
        val editor = preferences.edit().clear()
        savedEntries.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
        advance(60_000L)

        val restored = store()
        assertEquals(
            listOf(BlockedUsageInterval(packageName, start, lastCheckpoint)),
            restored.intervals(start, wall),
        )
        restored.start("test.other.app")
        advance(1_000L)
        assertEquals(
            listOf(
                BlockedUsageInterval(packageName, start, lastCheckpoint),
                BlockedUsageInterval("test.other.app", wall - 1_000L, wall),
            ),
            restored.intervals(start, wall),
        )
    }

    @Test fun clearingRulesKeepsClosedUsageAdjustments() {
        val usage = store()
        usage.start(packageName)
        advance(15_000L)
        usage.stop()

        RuleStore(context).clear()

        assertEquals(listOf(BlockedUsageInterval(packageName, start, wall)), usage.intervals(start, wall))
    }

    @Test fun aForwardClockChangeDoesNotAddBlockedTime() {
        val usage = store()
        usage.start(packageName)
        advance(12_000L)
        wall += 60 * 60 * 1_000L
        usage.stop()

        assertEquals(listOf(BlockedUsageInterval(packageName, start, start + 12_000L)), usage.intervals(start, wall))
    }

    @Test fun expiredAdjustmentsAreRemoved() {
        val usage = store()
        usage.start(packageName)
        advance(1_000L)
        usage.stop()
        advance(8 * 24 * 60 * 60 * 1_000L)

        assertTrue(usage.intervals(start, wall).isEmpty())
        assertTrue(preferences.all.isEmpty())
    }

    private fun store() = BlockedUsageStore(context, { wall }, { elapsed })

    private fun advance(milliseconds: Long) {
        wall += milliseconds
        elapsed += milliseconds
    }
}
