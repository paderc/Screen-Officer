package com.example.screenofficer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPolicyTest {
    private val rule = AppRule("app.one", "One", limitMinutes = 30)
    private val group = LimitTarget(
        rule.copy(packageName = "group.test", sharedApps = mapOf("app.one" to "One", "app.two" to "Two")),
    )
    private val sharedUsage = mapOf("app.one" to 20 * 60_000L, "app.two" to 10 * 60_000L)

    @Test fun sharedAllowanceUsesTheSumOfItsMembersOnly() {
        val usage = sharedUsage + ("app.three" to 30 * 60_000L)

        assertEquals(group, BlockPolicy.exceededLimit(listOf(group), "app.one", usage) { null })
        assertEquals(group, BlockPolicy.exceededLimit(listOf(group), "app.two", usage) { null })
        assertNull(BlockPolicy.exceededLimit(listOf(group), "app.three", usage) { null })
        assertNull(BlockPolicy.exceededLimit(listOf(group), "app.one", usage) { 35 * 60_000L })
    }

    @Test fun anOverlappingLimitCanBlockEvenWhenAnotherIsUnderItsThreshold() {
        val individual = LimitTarget(rule)

        assertEquals(group, BlockPolicy.exceededLimit(listOf(individual, group), "app.one", sharedUsage) { null })
    }

    @Test fun aReachedHardLimitTakesPriorityOverASoftLimit() {
        val hard = LimitTarget(rule.copy(mode = BlockMode.HARD, limitMinutes = 10))

        assertEquals(hard, BlockPolicy.exceededLimit(listOf(group, hard), "app.one", sharedUsage) { null })
    }
    private val limitMs = 30 * 60_000L

    @Test fun blocksAtExactlyTheLimit() {
        assertFalse(BlockPolicy.shouldBlock(rule, limitMs - 1, null))
        assertTrue(BlockPolicy.shouldBlock(rule, limitMs, null))
        assertTrue(BlockPolicy.shouldBlock(rule, limitMs + 1, null))
    }

    @Test fun pausedRuleNeverBlocks() {
        assertFalse(BlockPolicy.shouldBlock(rule.copy(enabled = false), limitMs * 10, null))
    }

    @Test fun softBlockRespectsFiveMoreMinutesOfUsage() {
        val extension = limitMs + 300_000L
        assertFalse(BlockPolicy.shouldBlock(rule, limitMs, extension))
        assertFalse(BlockPolicy.shouldBlock(rule, extension - 1, extension))
        assertTrue(BlockPolicy.shouldBlock(rule, extension, extension))
    }

    @Test fun expiredSnoozeRestoresDailyLimit() {
        assertTrue(BlockPolicy.shouldBlock(rule, limitMs, null))
    }

    @Test fun snoozeCannotReduceTheSavedDailyGoal() {
        assertFalse(BlockPolicy.shouldBlock(rule, limitMs - 1, limitMs - 100))
    }

    @Test fun hardBlockIgnoresAnySoftSnoozeState() {
        assertTrue(BlockPolicy.shouldBlock(rule.copy(mode = BlockMode.HARD), limitMs, limitMs * 10))
    }
}
