package ru.foric27.cluster.util

import android.media.MediaCodec

/**
 * Утилита для безопасного releaseOutputBuffer MediaCodec.
 *
 * Игнорирует ошибки при остановке, когда буфер уже может быть недоступен.
 */
internal object VideoCodecUtil {

    fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
        try {
            codec.releaseOutputBuffer(index, false)
        } catch (_: Throwable) {
            // На остановке буфер уже может быть недоступен.
        }
    }
}
