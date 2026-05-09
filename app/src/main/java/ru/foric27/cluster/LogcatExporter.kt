package ru.foric27.cluster

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохраняет ограниченный снимок logcat и отдаёт URI для системного share-меню. */
internal object LogcatExporter {

    data class Result(
        val file: File,
        val uri: android.net.Uri,
    )

    fun export(context: Context): Result {
        val appContext = context.applicationContext
        val logDir = File(appContext.cacheDir, LOG_DIR_NAME).apply { mkdirs() }
        cleanupOldLogs(logDir)

        val file = File(logDir, "logcat-${timestamp()}.txt")
        val output = readLogcat()
            .lines()
            .map(LogSanitizer::sanitize)
            .takeLast(MAX_LINES)
            .joinToString(separator = System.lineSeparator())
        file.writeText(output, Charsets.UTF_8)

        return Result(
            file = file,
            uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file),
        )
    }

    private fun readLogcat(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-t", MAX_LINES.toString())
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor()
            text
        } catch (t: Throwable) {
            "Не удалось прочитать logcat: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun cleanupOldLogs(logDir: File) {
        logDir.listFiles { file -> file.isFile && file.name.startsWith("logcat-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES - 1)
            ?.forEach { it.delete() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private const val LOG_DIR_NAME = "shared-logcat"
    private const val MAX_LINES = 2_000
    private const val MAX_FILES = 5
}
