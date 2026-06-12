package ru.foric27.cluster.util

import java.util.Locale

/**
 * Определение USB-накопителей по пути.
 *
 * Пути вида `/storage/<id>` без `emulated`, `self`, `enc_emulated`
 * считаются USB-хранилищем.
 */
/**
 * Определяет, относится ли путь к USB-накопителю.
 */
internal object UsbStoragePathMatcher {

    /**
     * Проверяет, является ли путь путём к USB-накопителю.
     *
     * @param path путь для проверки
     * @return true, если путь относится к USB
     */
    fun isUsbStoragePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.lowercase(Locale.US)
        if (!normalized.startsWith("/storage/")) return false
        if (normalized.startsWith("/storage/emulated")) return false
        if (normalized.startsWith("/storage/self")) return false
        if (normalized.startsWith("/storage/enc_emulated")) return false
        return true
    }
}
