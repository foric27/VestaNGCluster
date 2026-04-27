package ru.foric27.cluster

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber
import java.security.MessageDigest

/**
 * Проверка подписи APK для защиты от несанкционированной модификации.
 *
 * Ожидаемый SHA-256 хеш подписи задаётся в [ProductConfig.Security.EXPECTED_SIGNATURE_SHA256].
 * При пустом значении проверка пропускается (debug-режим).
 */
internal object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    data class VerificationResult(
        val valid: Boolean,
        val actualSha256: String,
        val expectedSha256: String,
    )

    /**
     * Выполняет проверку подписи текущего APK.
     *
     * @return результат проверки с фактическим и ожидаемым хешем
     */
    fun verify(context: Context): VerificationResult {
        val expected = ProductConfig.Security.EXPECTED_SIGNATURE_SHA256
        val actual = getCurrentSignatureSha256(context)

        Timber.tag(TAG).i("Подпись APK: actual=$actual, expected=$expected")

        if (expected.isBlank()) {
            Timber.tag(TAG).w("Ожидаемая подпись не задана — проверка пропущена (debug-режим)")
            return VerificationResult(valid = true, actualSha256 = actual, expectedSha256 = expected)
        }

        val valid = actual.equals(expected, ignoreCase = true)
        return VerificationResult(valid = valid, actualSha256 = actual, expectedSha256 = expected)
    }

    /**
     * Возвращает SHA-256 первого сертификата подписи текущего APK.
     */
    fun getCurrentSignatureSha256(context: Context): String {
        return try {
            val packageName = context.packageName
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val packageInfo = context.packageManager.getPackageInfo(packageName, flags)

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val firstSignature = signatures?.firstOrNull()
                ?: return ""

            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(firstSignature.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось получить подпись APK")
            ""
        }
    }
}