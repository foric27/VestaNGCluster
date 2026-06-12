package ru.foric27.cluster.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class StreamConfigTest {

    private fun sampleConfig() = StreamConfig(
        ip = "192.168.40.2",
        port = 5004,
        launchComponent = "ru.foric27.cluster/.MediaCoverActivity",
        localCidr = "192.168.40.1/24",
        gateway = "192.168.40.2",
        bindIp = "192.168.40.1",
        width = 1920,
        height = 640,
        dpi = 320,
        fps = 30,
        bitrate = 8000000,
        iframeIntervalSec = 2,
    )

    @Test
    fun `data class equals`() {
        val a = sampleConfig()
        val b = sampleConfig()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes only specified fields`() {
        val original = sampleConfig()
        val copy = original.copy(port = 6000, fps = 60)
        assertEquals(6000, copy.port)
        assertEquals(60, copy.fps)
        assertEquals(original.ip, copy.ip)
        assertEquals(original.width, copy.width)
    }

    @Test
    fun `serialization round-trip preserves all fields`() {
        val original = sampleConfig()
        val bytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).writeObject(original)
            bos.toByteArray()
        }
        val restored = ByteArrayInputStream(bytes).use { bis ->
            ObjectInputStream(bis).readObject() as StreamConfig
        }
        assertEquals(original, restored)
    }

    @Test
    fun `data class toString contains key fields`() {
        val config = sampleConfig()
        val str = config.toString()
        assert(str.contains("192.168.40.2"))
        assert(str.contains("5004"))
    }
}
