package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `sanitize redacts common secret values`() {
        val value = "password=fetisov token:abc secret = xyz authorization=Bearer123 keep=visible"

        assertEquals(
            "password=<redacted> token:<redacted> secret = <redacted> authorization=<redacted> keep=visible",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize leaves regular diagnostic text unchanged`() {
        val value = "route ok iface=eth0 target=192.168.40.2"

        assertEquals(value, LogSanitizer.sanitize(value))
    }
}
