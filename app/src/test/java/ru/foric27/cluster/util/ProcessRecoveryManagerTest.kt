package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRecoveryManagerTest {

    @Test
    fun `RecoveryDebugState data class construction`() {
        val state = ProcessRecoveryManager.RecoveryDebugState(
            crashCountInWindow = 3,
            suppressedUntilElapsedMs = 12345L,
            timestamps = listOf(100L, 200L, 300L),
        )
        assertEquals(3, state.crashCountInWindow)
        assertEquals(12345L, state.suppressedUntilElapsedMs)
        assertEquals(listOf(100L, 200L, 300L), state.timestamps)
    }

    @Test
    fun `RecoveryDebugState data class equals`() {
        val a = ProcessRecoveryManager.RecoveryDebugState(
            crashCountInWindow = 2,
            suppressedUntilElapsedMs = 0L,
            timestamps = listOf(100L, 200L),
        )
        val b = ProcessRecoveryManager.RecoveryDebugState(
            crashCountInWindow = 2,
            suppressedUntilElapsedMs = 0L,
            timestamps = listOf(100L, 200L),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `RecoveryDebugState with empty timestamps`() {
        val state = ProcessRecoveryManager.RecoveryDebugState(
            crashCountInWindow = 0,
            suppressedUntilElapsedMs = 0L,
            timestamps = emptyList(),
        )
        assertEquals(0, state.crashCountInWindow)
        assertTrue(state.timestamps.isEmpty())
    }

    @Test
    fun `RecoveryDebugState copy`() {
        val original = ProcessRecoveryManager.RecoveryDebugState(
            crashCountInWindow = 1,
            suppressedUntilElapsedMs = 500L,
            timestamps = listOf(100L),
        )
        val copy = original.copy(crashCountInWindow = 5)
        assertEquals(5, copy.crashCountInWindow)
        assertEquals(original.suppressedUntilElapsedMs, copy.suppressedUntilElapsedMs)
        assertEquals(original.timestamps, copy.timestamps)
    }
}
