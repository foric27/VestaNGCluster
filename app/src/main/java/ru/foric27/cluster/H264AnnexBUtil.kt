package ru.foric27.cluster

import java.nio.ByteBuffer

/**
 * Приведение H.264 потока к AnnexB (00 00 00 01 start codes).
 *
 * На разных устройствах MediaCodec может отдавать:
 *  - AnnexB (уже со start-code),
 *  - AVCC (length-prefixed NAL, как в mp4).
 *
 * Python/ffmpeg приёмники чаще ожидают AnnexB.
 */
object H264AnnexBUtil {

    private val SC4 = byteArrayOf(0, 0, 0, 1)

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
     * Нормализует данные в AnnexB:
     *  - если уже AnnexB — возвращает как есть;
     *  - если похоже на AVCC (length-prefixed) — конвертирует в AnnexB;
     *  - иначе считает, что это «сырой» NAL без start-code и добавляет 00 00 00 01.
     */
    fun normalizeToAnnexB(input: ByteArray?): ByteArray? {
        if (input == null || input.isEmpty()) return input
        if (looksLikeAnnexB(input)) return input

        // AVCC single/multi-NAL: 4-byte length prefix
        if (looksLikeAvcc(input)) {
            return avccToAnnexB(input)
        }

        // raw NAL without start-code
        val out = ByteArray(4 + input.size)
        System.arraycopy(SC4, 0, out, 0, 4)
        System.arraycopy(input, 0, out, 4, input.size)
        return out
    }

    /**
     * Конвертирует AVCC (4-byte length) в AnnexB.
     * Если формат неизвестен/битый — вернёт исходный массив.
     */
    fun avccToAnnexB(avcc: ByteArray?): ByteArray? {
        if (avcc == null || avcc.size < 4) return avcc

        val firstLen = u32be(avcc, 0)
        if (firstLen <= 0 || firstLen > avcc.size - 4) {
            return avcc
        }

        // 1) Посчитаем итоговый размер
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

            System.arraycopy(SC4, 0, out, w, 4)
            w += 4

            System.arraycopy(avcc, pos, out, w, n)
            w += n
            pos += n
        }
        return out
    }

    /**
     * Собирает SPS/PPS в AnnexB. Если csd буферы уже содержат start codes — оставляем как есть.
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
