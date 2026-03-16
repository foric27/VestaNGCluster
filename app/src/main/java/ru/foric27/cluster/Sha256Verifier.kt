package ru.foric27.cluster

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Locale

/**
 * Проверка контрольной суммы SHA-256 для пары ICUpdate.zip / ICUpdate.zip.sig.
 */
class Sha256Verifier {

    data class VerificationResult(
        val valid: Boolean,
        val expectedSha256: String,
        val actualSha256: String,
        val details: String,
    )

    fun verify(
        context: Context,
        zipFile: UpdateFileLocator.UpdateSourceFile,
        sigFile: UpdateFileLocator.UpdateSourceFile,
    ): VerificationResult {
        return try {
            val actualSha256 = calculateSha256(context, zipFile)
            val expectedSha256 = readSignature(context, sigFile)
            val valid = actualSha256.length == SHA256_HEX_LENGTH && actualSha256 == expectedSha256
            val details = if (valid) {
                "SHA-256 подтверждён"
            } else {
                "SHA-256 не совпадает"
            }
            VerificationResult(
                valid = valid,
                expectedSha256 = expectedSha256,
                actualSha256 = actualSha256,
                details = details,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка проверки SHA-256", t)
            VerificationResult(
                valid = false,
                expectedSha256 = "",
                actualSha256 = "",
                details = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun calculateSha256(
        context: Context,
        zipFile: UpdateFileLocator.UpdateSourceFile,
    ): String {
        val digest = MessageDigest.getInstance(ALGORITHM_SHA256)
        zipFile.openInputStream(context).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xFF)
        }
    }

    private fun readSignature(
        context: Context,
        sigFile: UpdateFileLocator.UpdateSourceFile,
    ): String {
        val rawText = sigFile.openInputStream(context).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        val normalized = rawText
            .replace("\u0000", "")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
            .lowercase(Locale.US)
        require(normalized.length == SHA256_HEX_LENGTH) {
            "Файл подписи должен содержать SHA-256 длиной 64 символа"
        }
        require(normalized.matches(SHA256_REGEX)) {
            "Файл подписи содержит некорректный SHA-256"
        }
        return normalized
    }

    private companion object {
        private const val TAG = "Sha256Verifier"
        private const val ALGORITHM_SHA256 = "SHA-256"
        private const val BUFFER_SIZE = 64 * 1024
        private const val SHA256_HEX_LENGTH = 64
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
