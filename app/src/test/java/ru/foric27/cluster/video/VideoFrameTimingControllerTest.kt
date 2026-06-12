package ru.foric27.cluster.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameTimingControllerTest {

    @Test
    fun `presentation timestamps stay monotonic even if source goes backward`() {
        val controller = VideoFrameTimingController(fps = 30)

        val first = controller.sanitizePresentationTimestamp(10_000L)
        val second = controller.sanitizePresentationTimestamp(9_000L)
        val third = controller.sanitizePresentationTimestamp(10_000L)

        assertEquals(10_000L, first)
        assertEquals(10_001L, second)
        assertEquals(10_002L, third)
    }

    @Test
    fun `scheduled presentation timestamps follow target frame interval`() {
        val controller = VideoFrameTimingController(fps = 8)

        val first = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)
        val second = controller.nextScheduledPresentationTimestampNs(1_000_001_000L)

        assertEquals(1_000_000_000L, first)
        assertEquals(1_125_000_000L, second)
    }

    @Test
    fun `double call to nextScheduledPresentationTimestampNs does not compound drift`() {
        val controller = VideoFrameTimingController(fps = 30)
        val intervalNs = 1_000_000_000L / 30

        val first = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)
        // Второй вызов должен дать следующий scheduled timestamp, а не wall-clock
        val second = controller.nextScheduledPresentationTimestampNs(1_000_000_001L)

        assertEquals("Интервал между кадрами должен быть равен frameIntervalNs", intervalNs, second - first)
    }

    @Test
    fun `setTargetFps changes frame interval`() {
        val controller30 = VideoFrameTimingController(fps = 30)
        val controller60 = VideoFrameTimingController(fps = 60)

        val ts30a = controller30.nextScheduledPresentationTimestampNs(1_000_000_000L)
        val ts30b = controller30.nextScheduledPresentationTimestampNs(1_000_000_001L)
        val interval30 = ts30b - ts30a

        val ts60a = controller60.nextScheduledPresentationTimestampNs(1_000_000_000L)
        val ts60b = controller60.nextScheduledPresentationTimestampNs(1_000_000_001L)
        val interval60 = ts60b - ts60a

        assertTrue("60fps interval ($interval60) should be smaller than 30fps interval ($interval30)", interval60 < interval30)
    }

    @Test
    fun `reset clears last presentation timestamp`() {
        val controller = VideoFrameTimingController(fps = 30)
        controller.nextScheduledPresentationTimestampNs(1_000_000_000L)
        controller.reset()

        val afterReset = controller.nextScheduledPresentationTimestampNs(2_000_000_000L)
        assertEquals(2_000_000_000L, afterReset)
    }

    @Test
    fun `markFrameScheduled with wall-clock overwrites scheduled timestamp`() {
        val controller = VideoFrameTimingController(fps = 30)
        val scheduled = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)

        // markFrameScheduled перезаписывает lastPresentationTimestampNs wall-clock значением
        controller.markFrameScheduled(1_000_000_050L)

        val nextAfterMark = controller.nextScheduledPresentationTimestampNs(1_000_000_051L)
        // После markFrameScheduled следующий timestamp должен быть от wall-clock + interval
        assertTrue("nextAfterMark should be based on wall-clock mark", nextAfterMark > scheduled)
    }

    @Test
    fun `overrun resync when real time is far behind schedule`() {
        val controller = VideoFrameTimingController(fps = 30)
        val intervalNs = 1_000_000_000L / 30
        val maxOverrunNs = intervalNs * 2

        // Первый кадр
        val first = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)

        // Симулируем большой overrun: nowNs ушёл на 500ms вперёд от расписания
        val hugeNowNs = first + maxOverrunNs + 500_000_000L
        val afterOverrun = controller.nextScheduledPresentationTimestampNs(hugeNowNs)

        // После resync timestamp должен быть близок к nowNs, а не к старому расписанию
        assertTrue("afterOverrun ($afterOverrun) should be close to hugeNowNs ($hugeNowNs)", afterOverrun >= hugeNowNs)
    }

    @Test
    fun `no resync for small overrun within tolerance`() {
        val controller = VideoFrameTimingController(fps = 30)
        val intervalNs = 1_000_000_000L / 30
        val maxOverrunNs = intervalNs * 2

        val first = controller.nextScheduledPresentationTimestampNs(1_000_000_000L)

        // Small overrun: nowNs ушёл на пол-интервала вперёд — в пределах tolerance
        val smallNowNs = first + intervalNs / 2
        val afterSmallOverrun = controller.nextScheduledPresentationTimestampNs(smallNowNs)

        // Должен использовать scheduled, а не resync
        assertEquals(first + intervalNs, afterSmallOverrun)
    }
}
