package ru.foric27.cluster.video

import java.nio.ByteBuffer

/**
 * Приводит H.264-поток к формату Annex B с префиксами `00 00 00 01`.
 *
 * В зависимости от устройства `MediaCodec` может возвращать:
 * - готовый Annex B;
 * - AVCC с префиксом длины NAL-блока;
 * - «сырой» NAL-блок без стартового префикса.
 *
 * Приёмники в этом проекте ожидают именно Annex B, поэтому вся нормализация
 * сосредоточена в одном месте.
 */
internal object H264AnnexBUtil {

    private val startCode4 = byteArrayOf(0, 0, 0, 1)

    fun looksLikeAnnexB(data: ByteArray?): Boolean {
        if (data == null || data.size < 4) return false
        return (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)
    }

    fun ensureAnnexB(input: ByteArray?): ByteArray? {
        if (input == null || input.isEmpty()) return input
        return normalizeToAnnexB(input)
    }

    /**
     * Нормализует входные данные к Annex B.
     *
     * Если буфер уже содержит стартовые префиксы, он возвращается как есть.
     * Если буфер похож на AVCC, длины NAL-блоков заменяются на `00 00 00 01`.
     * В остальных случаях буфер считается одиночным NAL-блоком без префикса.
     */
    fun normalizeToAnnexB(input: ByteArray?): ByteArray? {
        if (input == null || input.isEmpty()) return input
        if (looksLikeAnnexB(input)) return input

        // AVCC с одним или несколькими NAL-блоками.
        if (looksLikeAvcc(input)) {
            return avccToAnnexB(input)
        }

        // Одиночный NAL-блок без стартового префикса.
        val out = ByteArray(4 + input.size)
        System.arraycopy(startCode4, 0, out, 0, 4)
        System.arraycopy(input, 0, out, 4, input.size)
        return out
    }

    /**
     * Конвертирует AVCC с 4-байтовым префиксом длины в Annex B.
     *
     * Если буфер не похож на AVCC или повреждён, возвращается исходный массив.
     */
    fun avccToAnnexB(avcc: ByteArray?): ByteArray? {
        if (avcc == null || avcc.size < 4) return avcc

        val firstLen = u32be(avcc, 0)
        if (firstLen <= 0 || firstLen > avcc.size - 4) {
            return avcc
        }

        // Сначала считаем итоговый размер выходного буфера.
        var pos = 0
        var outSize = 0
        while (pos + 4 <= avcc.size) {
            val n = u32be(avcc, pos)
            pos += 4
            if (n <= 0 || pos + n > avcc.size) return avcc
            outSize += 4 + n
            pos += n
        }

        val out = ByteArray(outSize)
        pos = 0
        var w = 0
        while (pos + 4 <= avcc.size) {
            val n = u32be(avcc, pos)
            pos += 4
            if (n <= 0 || pos + n > avcc.size) return avcc

            System.arraycopy(startCode4, 0, out, w, 4)
            w += 4

            System.arraycopy(avcc, pos, out, w, n)
            w += n
            pos += n
        }
        return out
    }

    /**
     * Собирает SPS/PPS в виде буфера Annex B.
     *
     * Если `csd-0` и `csd-1` уже содержат стартовые префиксы, они сохраняются без изменений.
     */
    fun buildConfigAnnexB(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        var b0 = if (csd0 != null && csd0.remaining() > 0) toByteArray(csd0) else null
        var b1 = if (csd1 != null && csd1.remaining() > 0) toByteArray(csd1) else null

        b0 = b0?.let { normalizeToAnnexB(it) }
        b1 = b1?.let { normalizeToAnnexB(it) }

        val n0 = b0?.size ?: 0
        val n1 = b1?.size ?: 0
        if (n0 + n1 == 0) return null

        val out = ByteArray(n0 + n1)
        var p = 0
        if (n0 > 0) {
            val bytes0 = b0 ?: return null
            System.arraycopy(bytes0, 0, out, p, n0)
            p += n0
        }
        if (n1 > 0) {
            val bytes1 = b1 ?: return null
            System.arraycopy(bytes1, 0, out, p, n1)
        }
        return out
    }

    private fun looksLikeAvcc(data: ByteArray?): Boolean {
        if (data == null || data.size < 4) return false
        val firstLen = u32be(data, 0)
        return firstLen > 0 && firstLen <= data.size - 4
    }

    private fun toByteArray(buf: ByteBuffer): ByteArray {
        val d = buf.duplicate()
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }

    private fun u32be(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }
}
