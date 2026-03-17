package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigTest {

    @Test
    fun `parse boolean value accepts known true variants`() {
        assertTrue(RuntimeConfig.parseBooleanValue("true", default = false))
        assertTrue(RuntimeConfig.parseBooleanValue("1", default = false))
        assertTrue(RuntimeConfig.parseBooleanValue(" yes ", default = false))
        assertTrue(RuntimeConfig.parseBooleanValue("on", default = false))
    }

    @Test
    fun `parse boolean value accepts known false variants`() {
        assertFalse(RuntimeConfig.parseBooleanValue("false", default = true))
        assertFalse(RuntimeConfig.parseBooleanValue("0", default = true))
        assertFalse(RuntimeConfig.parseBooleanValue(" no ", default = true))
        assertFalse(RuntimeConfig.parseBooleanValue("off", default = true))
    }

    @Test
    fun `parse boolean value falls back to default for unknown values`() {
        assertTrue(RuntimeConfig.parseBooleanValue("maybe", default = true))
        assertFalse(RuntimeConfig.parseBooleanValue("maybe", default = false))
        assertTrue(RuntimeConfig.parseBooleanValue(null, default = true))
    }
}
