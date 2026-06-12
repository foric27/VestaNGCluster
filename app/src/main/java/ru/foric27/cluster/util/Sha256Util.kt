package ru.foric27.cluster.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal object Sha256Util {

    fun sha256Hex(file: File): String =
        file.inputStream().use { sha256Hex(it) }

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
