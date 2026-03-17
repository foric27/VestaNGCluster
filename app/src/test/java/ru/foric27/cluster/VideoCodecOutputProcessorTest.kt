package ru.foric27.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VideoCodecOutputProcessorTest {

    @Test
    fun `prependCodecConfigIfNeeded adds config before keyframe payload`() {
        val config = byteArrayOf(0, 0, 0, 1, 103, 66)
        val frame = byteArrayOf(0, 0, 0, 1, 101, 1, 2, 3)

        val result = VideoCodecOutputProcessor.prependCodecConfigIfNeeded(frame, config)

        assertArrayEquals(config + frame, result)
    }

    @Test
    fun `prependCodecConfigIfNeeded does not duplicate existing config`() {
        val config = byteArrayOf(0, 0, 0, 1, 103, 66)
        val frameWithConfig = config + byteArrayOf(0, 0, 0, 1, 101, 1, 2, 3)

        val result = VideoCodecOutputProcessor.prependCodecConfigIfNeeded(frameWithConfig, config)

        assertArrayEquals(frameWithConfig, result)
    }
}
