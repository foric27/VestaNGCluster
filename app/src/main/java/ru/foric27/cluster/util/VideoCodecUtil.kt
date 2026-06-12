package ru.foric27.cluster.util

import android.media.MediaCodec

/**
 * Утилита для безопасного releaseOutputBuffer MediaCodec.
 *
 * Игнорирует ошибки при остановке, когда буфер уже может быть недоступен.
 */
/**
 * Утилиты для работы с MediaCodec видео.
 */
internal object VideoCodecUtil {

    /**
     * Освобождает output buffer, подавляя любые исключения.
     *
     * @param codec экземпляр MediaCodec
     * @param index индекс буфера
     */
    fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
        try {
            codec.releaseOutputBuffer(index, false)
        } catch (_: Throwable) {
            // На остановке буфер уже может быть недоступен.
        }
    }
}
