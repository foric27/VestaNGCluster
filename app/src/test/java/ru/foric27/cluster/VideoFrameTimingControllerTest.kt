package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameTimingControllerTest {

    @Test
    fun `dynamic fps limiter delays frames until interval passes`() {
        val controller = VideoFrameTimingController(fps = 8, keepalivePeriodMs = 1_500L)

        assertEquals(0L, controller.delayUntilNextDynamicRender(nowMs = 100L))

        controller.markRendered(nowMs = 100L)

        assertTrue(controller.delayUntilNextDynamicRender(nowMs = 150L) > 0L)
        assertEquals(0L, controller.delayUntilNextDynamicRender(nowMs = 225L))
    }

    @Test
    fun `keepalive is emitted only after idle period`() {
        val controller = VideoFrameTimingController(fps = 8, keepalivePeriodMs = 1_500L)

        controller.markRendered(nowMs = 1_000L)

        assertFalse(controller.shouldEmitKeepalive(nowMs = 2_000L))
        assertTrue(controller.shouldEmitKeepalive(nowMs = 2_500L))
    }

    @Test
    fun `presentation timestamps stay monotonic even if source goes backward`() {
        val controller = VideoFrameTimingController(fps = 8, keepalivePeriodMs = 1_500L)

        val first = controller.sanitizePresentationTimestamp(10_000L)
        val second = controller.sanitizePresentationTimestamp(9_000L)
        val third = controller.sanitizePresentationTimestamp(10_000L)

        assertEquals(10_000L, first)
        assertEquals(10_001L, second)
        assertEquals(10_002L, third)
    }

    @Test
    fun `scheduled presentation timestamps follow target frame interval`() {
        val controller = VideoFrameTimingController(fps = 8, keepalivePeriodMs = 1_500L)

        val first = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)
        val second = controller.nextScheduledPresentationTimestampNs(1_000_001_000L)

        assertEquals(1_000_000_000L, first)
        assertEquals(1_125_000_000L, second)
    }
}
