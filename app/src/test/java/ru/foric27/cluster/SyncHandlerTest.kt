package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.SimpleTimeZone

class SyncHandlerTest {

    @Test
    fun `sync time uses stable formatting independent of locale`() {
        val syncTime = SyncHandler.SyncTime(
            utcInst = Instant.parse("2026-03-17T08:05:09Z"),
            zone = SimpleTimeZone(5 * 3_600_000 + 30 * 60_000, "UTC+05:30"),
        )

        assertEquals("080509+0530", syncTime.toString())
    }
}
