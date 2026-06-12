package ru.foric27.cluster.video
import ru.foric27.cluster.service.*
import ru.foric27.cluster.util.*

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

    /**
     * Обрабатывает выходной буфер MediaCodec: нормализует к Annex B, добавляет SPS/PPS
     * к keyframe и отправляет по UDP.
     *
     * При codec-config буфере сохраняет SPS/PPS для последующих keyframe'ов.
     *
     * @param codec источник выходного буфера
     * @param index индекс выходного буфера
     * @param info метаданные буфера
     * @param configAnnexB SPS/PPS из [onOutputFormatChanged], если уже доступны
     */
    fun process(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, configAnnexB: ByteArray?) {
        if (info.size <= 0) {
            VideoCodecUtil.releaseOutputBufferQuietly(codec, index)
            return
        }

        val outBuffer = codec.getOutputBuffer(index)
        if (outBuffer == null) {
            VideoCodecUtil.releaseOutputBufferQuietly(codec, index)
            return
        }

        val payload = outBuffer.readBytes(info.offset, info.size)
        VideoCodecUtil.releaseOutputBufferQuietly(codec, index)

        val payloadAnnexB = H264AnnexBUtil.ensureAnnexB(payload) ?: return
        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

        // Если буфер помечен как codec config — извлекаем и сохраняем SPS/PPS
        if (codecConfig) {
            storedCodecConfig = payloadAnnexB
            if (!hasLoggedConfig) {
                hasLoggedConfig = true
                Timber.tag(TAG).i("Codec config сохранён из output buffer: size=%d", payloadAnnexB.size)
            }
            return
        }

        // Используем config из onOutputFormatChanged, либо из codec config буфера
        val effectiveConfig = configAnnexB ?: storedCodecConfig

        // Гарантируем что SPS/PPS добавлен к каждому keyframe
        val annexB = if (keyFrame) {
            prependCodecConfigIfNeeded(payloadAnnexB, effectiveConfig)
        } else {
            payloadAnnexB
        }

        // Для keyframe отправляем SPS/PPS отдельным пакетом перед кадром,
        // чтобы приёмник имел два шанса получить конфигурацию (при потере UDP)
        if (keyFrame && effectiveConfig != null && effectiveConfig.isNotEmpty()) {
            udpSender.sendFrame(effectiveConfig)
        }

        udpSender.sendFrame(annexB)

        // Сохраняем I-frame для переотправки новым клиентам (keep-alive)
        if (keyFrame) {
            udpSender.bufferIframe(annexB, effectiveConfig)
        }

        onFrameSent()
    }

    companion object {
        private const val TAG = "VideoCodecOutput"

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

        private fun ByteBuffer.readBytes(offset: Int, size: Int): ByteArray {
            val dup = duplicate()
            dup.position(offset)
            dup.limit(offset + size)
            return ByteArray(size).also { dup.get(it) }
        }
    }
}
