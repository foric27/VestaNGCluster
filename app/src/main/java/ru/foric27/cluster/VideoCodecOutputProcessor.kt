package ru.foric27.cluster

import android.media.MediaCodec
import java.nio.ByteBuffer

internal class VideoCodecOutputProcessor(
    private val udpSender: UdpSender,
    private val onFrameSent: () -> Unit,
) {

    fun process(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, configAnnexB: ByteArray?) {
        if (info.size <= 0) {
            releaseOutputBufferQuietly(codec, index)
            return
        }

        val outBuffer = codec.getOutputBuffer(index)
        if (outBuffer == null) {
            releaseOutputBufferQuietly(codec, index)
            return
        }

        val payload = outBuffer.readBytes(info.offset, info.size)
        releaseOutputBufferQuietly(codec, index)

        val payloadAnnexB = H264AnnexBUtil.ensureAnnexB(payload) ?: return
        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (codecConfig) return

        val annexB = if (keyFrame) prependCodecConfigIfNeeded(payloadAnnexB, configAnnexB) else payloadAnnexB
        udpSender.sendFrame(annexB)
        onFrameSent()
    }

    companion object {
        internal fun prependCodecConfigIfNeeded(frameAnnexB: ByteArray, configAnnexB: ByteArray?): ByteArray {
            val config = configAnnexB ?: return frameAnnexB
            if (config.isEmpty()) return frameAnnexB
            if (frameAnnexB.size >= config.size && frameAnnexB.copyOfRange(0, config.size).contentEquals(config)) {
                return frameAnnexB
            }
            return ByteArray(config.size + frameAnnexB.size).also { out ->
                System.arraycopy(config, 0, out, 0, config.size)
                System.arraycopy(frameAnnexB, 0, out, config.size, frameAnnexB.size)
            }
        }

        private fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Throwable) {
            }
        }

        private fun ByteBuffer.readBytes(offset: Int, size: Int): ByteArray {
            val dup = duplicate()
            dup.position(offset)
            dup.limit(offset + size)
            return ByteArray(size).also { dup.get(it) }
        }
    }
}
