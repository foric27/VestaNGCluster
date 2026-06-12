package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppWarningCenterTest {

    @Before
    fun setUp() {
        AppWarningCenter.clear()
    }

    @Test
    fun `publish trims and deduplicates warnings`() {
        AppWarningCenter.publish("  warning  ")
        AppWarningCenter.publish("warning")

        assertEquals(listOf("warning"), AppWarningCenter.consumeAll())
    }

    @Test
    fun `consumeAll preserves order and clears queue`() {
        AppWarningCenter.publish("one")
        AppWarningCenter.publish("two")

        assertEquals(listOf("one", "two"), AppWarningCenter.consumeAll())
        assertTrue(AppWarningCenter.consumeAll().isEmpty())
    }

    @Test
    fun `removeMatching removes stale domain warnings`() {
        AppWarningCenter.publish("root missing")
        AppWarningCenter.publish("network missing")

        AppWarningCenter.removeMatching { it.startsWith("root") }

        assertFalse(AppWarningCenter.contains("root missing"))
        assertEquals(listOf("network missing"), AppWarningCenter.consumeAll())
    }

    @Test
    fun `listener failure does not break publication`() {
        val events = ArrayList<String>()
        val badListener = object : AppWarningCenter.WarningListener {
            override fun onWarningPublished(message: String) {
                error("listener failed")
            }
        }
        val goodListener = object : AppWarningCenter.WarningListener {
            override fun onWarningPublished(message: String) {
                events += message
            }
        }

        AppWarningCenter.registerListener(badListener)
        AppWarningCenter.registerListener(goodListener)
        AppWarningCenter.publish("warning")

        assertEquals(listOf("warning"), events)
        assertEquals(listOf("warning"), AppWarningCenter.consumeAll())

        AppWarningCenter.unregisterListener(badListener)
        AppWarningCenter.unregisterListener(goodListener)
    }
}
