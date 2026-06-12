package ru.foric27.cluster.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * SHA-256 хеширование файлов и потоков для верификации обновлений.
 *
 * Возвращает hex-строку нижнего регистра длиной 64 символа.
 */
/**
 * Утилита для вычисления SHA-256 хеша файлов и потоков.
 */
internal object Sha256Util {

    /**
     * Вычисляет SHA-256 хеш файла.
     *
     * @param file файл для хеширования
     * @return hex-строка хеша
     */
    fun sha256Hex(file: File): String =
        file.inputStream().use { sha256Hex(it) }

    /**
     * Вычисляет SHA-256 хеш входного потока.
     *
     * @param input входной поток
     * @return hex-строка хеша
     */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xFF)
        }
    }
}
