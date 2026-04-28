package ru.foric27.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class H264AnnexBUtilTest {

    @Test
    fun `normalizeToAnnexB keeps existing Annex B buffer`() {
        val input = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)

        val result = H264AnnexBUtil.normalizeToAnnexB(input)

        assertSame(input, result)
    }

    @Test
    fun `normalizeToAnnexB converts multiple AVCC NAL units`() {
        val avcc = byteArrayOf(
            0, 0, 0, 2, 0x67, 0x42,
            0, 0, 0, 3, 0x68, 0x01, 0x02,
        )

        val result = H264AnnexBUtil.normalizeToAnnexB(avcc)

        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x67, 0x42,
                0, 0, 0, 1, 0x68, 0x01, 0x02,
            ),
            result,
        )
    }

    @Test
    fun `normalizeToAnnexB adds start code to raw NAL`() {
        val result = H264AnnexBUtil.normalizeToAnnexB(byteArrayOf(0x65, 0x01, 0x02))

        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0x02), result)
    }

    @Test
    fun `buildConfigAnnexB normalizes and joins csd buffers`() {
        val csd0 = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 2, 0x67, 0x42))
        val csd1 = ByteBuffer.wrap(byteArrayOf(0x68, 0x01))

        val result = H264AnnexBUtil.buildConfigAnnexB(csd0, csd1)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x01),
            result,
        )
    }

    @Test
    fun `buildConfigAnnexB returns null for empty inputs`() {
        assertNull(H264AnnexBUtil.buildConfigAnnexB(null, ByteBuffer.allocate(0)))
    }

    @Test
    fun `looksLikeAnnexB accepts three-byte start code`() {
        assertTrue(H264AnnexBUtil.looksLikeAnnexB(byteArrayOf(0, 0, 1, 0x65)))
    }
}
