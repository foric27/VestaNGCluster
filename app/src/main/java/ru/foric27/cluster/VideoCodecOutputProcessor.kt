package ru.foric27.cluster

import android.media.MediaCodec
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * Обработчик выходных буферов MediaCodec H.264.
 *
 * Гарантирует что SPS/PPS (codec config) всегда предшествует IDR-кадру,
 * и нормализует поток к Annex B (00 00 00 01).
 */
internal class VideoCodecOutputProcessor(
    private val udpSender: UdpSender,
    private val onFrameSent: () -> Unit,
    private val streamConfig: StreamConfig,
) {

    private val minFrameIntervalUs: Long = 1_000_000L / streamConfig.fps.coerceAtLeast(1)
    private var lastSentPresentationTimeUs: Long = Long.MIN_VALUE

    // Храним SPS/PPS из codec config буферов на случай если onOutputFormatChanged
    // ещё не вызвался к моменту первого keyframe
    @Volatile private var storedCodecConfig: ByteArray? = null
    @Volatile private var hasLoggedConfig: Boolean = false

    /**
     * Сбрасывает хранимый codec config. Вызывается при остановке энкодера,
     * чтобы старый SPS/PPS не использовался после перезапуска.
     */
    fun clearStoredConfig() {
        storedCodecConfig = null
        hasLoggedConfig = false
    }

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

        // Если буфер помечен как codec config — извлекаем и сохраняем SPS/PPS
        if (codecConfig) {
            storedCodecConfig = payloadAnnexB
            val firstByte = if (payloadAnnexB.isNotEmpty()) payloadAnnexB[0].toInt() and 0xFF else -1
            val nalType = if (payloadAnnexB.size > 4) payloadAnnexB[4].toInt() and 0x1F else -1
            Timber.tag(TAG).d("Codec config buffer: size=%d, firstByte=0x%02X, nalType=%d", payloadAnnexB.size, firstByte, nalType)
            return
        }

        if (shouldDropFrame(info.presentationTimeUs)) return

        // Используем config из onOutputFormatChanged, либо из codec config буфера
        val effectiveConfig = configAnnexB ?: storedCodecConfig

        // Гарантируем что SPS/PPS добавлен к каждому keyframe
        val annexB = if (keyFrame) {
            prependCodecConfigIfNeeded(payloadAnnexB, effectiveConfig)
        } else {
            payloadAnnexB
        }

        if (keyFrame) {
            if (effectiveConfig == null) {
                Timber.tag(TAG).w("Keyframe без SPS/PPS — configAnnexB=%s, storedCodecConfig=%s", configAnnexB?.size, storedCodecConfig?.size)
            } else {
                Timber.tag(TAG).w("Keyframe с SPS/PPS: payload=%d, config=%d, result=%d", payloadAnnexB.size, effectiveConfig.size, annexB.size)
            }
        }

        // Для keyframe отправляем SPS/PPS отдельным пакетом перед кадром,
        // чтобы приёмник имел два шанса получить конфигурацию (при потере UDP)
        if (keyFrame && effectiveConfig != null && effectiveConfig.isNotEmpty()) {
            udpSender.sendFrame(effectiveConfig)
        }

        udpSender.sendFrame(annexB)
        lastSentPresentationTimeUs = info.presentationTimeUs
        onFrameSent()
    }

    private fun shouldDropFrame(presentationTimeUs: Long): Boolean {
        if (lastSentPresentationTimeUs == Long.MIN_VALUE) return false
        if (presentationTimeUs <= lastSentPresentationTimeUs) return true
        return presentationTimeUs - lastSentPresentationTimeUs < minFrameIntervalUs
    }

    companion object {
        private const val TAG = "VideoCodecOutput"

        internal fun shouldDropFrameForConstantFps(
            lastSentPresentationTimeUs: Long,
            presentationTimeUs: Long,
            fps: Int,
        ): Boolean {
            if (lastSentPresentationTimeUs == Long.MIN_VALUE) return false
            if (presentationTimeUs <= lastSentPresentationTimeUs) return true
            val requiredIntervalUs = 1_000_000L / fps.coerceAtLeast(1)
            return presentationTimeUs - lastSentPresentationTimeUs < requiredIntervalUs
        }

        /**
         * Добавляет SPS/PPS к keyframe если их ещё нет в начале кадра.
         */
        internal fun prependCodecConfigIfNeeded(frameAnnexB: ByteArray, configAnnexB: ByteArray?): ByteArray {
            val config = configAnnexB ?: return frameAnnexB
            if (config.isEmpty()) return frameAnnexB
            // Если начало кадра уже содержит config — не дублируем
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
