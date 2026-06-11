package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PersistentVirtualDisplayTest {

    @Before
    fun setUp() {
        PersistentVirtualDisplay.releaseAll()
    }

    @Test
    fun `releaseAll clears state`() {
        PersistentVirtualDisplay.releaseAll()
        assertNull("virtualDisplay should be null after releaseAll", PersistentVirtualDisplay.javaClass.getDeclaredField("virtualDisplay").apply { isAccessible = true }.get(PersistentVirtualDisplay))
    }

    @Test
    fun `detachSurface does not throw when no display`() {
        PersistentVirtualDisplay.detachSurface()
    }

    @Test
    fun `releaseAll is idempotent`() {
        PersistentVirtualDisplay.releaseAll()
        PersistentVirtualDisplay.releaseAll()
    }
}
