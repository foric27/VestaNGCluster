package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VdspStateTest {

    @Before
    fun setUp() {
        VdspState.clear()
    }

    @Test
    fun `initial state after clear is REMOVED with displayId -1`() {
        assertEquals(-1, VdspState.getDisplayId())
        assertEquals(VdspState.DisplayState.REMOVED, VdspState.getDisplayState())
    }

    @Test
    fun `set from initial state returns ADDED`() {
        val state = VdspState.set(10, 1920, 640)
        assertEquals(VdspState.DisplayState.ADDED, state)
        assertEquals(10, VdspState.getDisplayId())
    }

    @Test
    fun `set same values again returns ADDED`() {
        VdspState.set(10, 1920, 640)
        val state = VdspState.set(10, 1920, 640)
        assertEquals(VdspState.DisplayState.ADDED, state)
    }

    @Test
    fun `set different height returns CHANGED`() {
        VdspState.set(10, 1920, 640)
        val state = VdspState.set(10, 1280, 480)
        assertEquals(VdspState.DisplayState.CHANGED, state)
    }

    @Test
    fun `set different displayId returns CHANGED`() {
        VdspState.set(10, 1920, 640)
        val state = VdspState.set(20, 1920, 640)
        assertEquals(VdspState.DisplayState.CHANGED, state)
    }

    @Test
    fun `clear resets to REMOVED and displayId -1`() {
        VdspState.set(10, 1920, 640)
        VdspState.clear()
        assertEquals(-1, VdspState.getDisplayId())
        assertEquals(VdspState.DisplayState.REMOVED, VdspState.getDisplayState())
    }

    @Test
    fun `clear is idempotent`() {
        VdspState.clear()
        assertEquals(VdspState.DisplayState.REMOVED, VdspState.getDisplayState())
        VdspState.clear()
        assertEquals(VdspState.DisplayState.REMOVED, VdspState.getDisplayState())
    }

    @Test
    fun `set after clear returns ADDED`() {
        VdspState.set(10, 1920, 640)
        VdspState.clear()
        val state = VdspState.set(20, 1280, 480)
        assertEquals(VdspState.DisplayState.ADDED, state)
    }
}
