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
        val output = buildExportPayload(appContext)
        file.writeText(output, Charsets.UTF_8)

        return Result(
            file = file,
            uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file),
        )
    }

    fun exportOnCrash(context: Context) {
        val appContext = context.applicationContext
        val logDir = File(appContext.cacheDir, LOG_DIR_NAME).apply { mkdirs() }
        cleanupOldLogs(logDir)
        val file = File(logDir, "crash-logcat-${timestamp()}.txt")
        runCatching {
            file.writeText(buildExportPayload(appContext), Charsets.UTF_8)
        }
    }

    private fun buildExportPayload(context: Context): String {
        RuntimeConfig.init(context)
        val recoveryState = ProcessRecoveryManager.readDebugState(context)
        return buildString {
            appendLine("=== EXPORT META ===")
            appendLine("timestamp=${timestamp()}")
            appendLine("app=${BuildConfig.APPLICATION_ID}")
            appendLine("version=${BuildConfig.VERSION_NAME}")
            appendLine("buildType=${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("verboseEnabled=${RuntimeConfig.Logging.VERBOSE_ENABLED}")
            appendLine()

            appendLine("=== RUNTIME CONTEXT ===")
            appendLine("target=${RuntimeConfig.Network.TARGET_IP}:${RuntimeConfig.Network.VIDEO_PORT}")
            appendLine("statusTarget=${RuntimeConfig.Network.STATUS_ENDPOINT}")
            appendLine("localCidr=${RuntimeConfig.Network.LOCAL_CIDR}")
            appendLine("gateway=${RuntimeConfig.Network.GATEWAY}")
            appendLine("bindIp=${RuntimeConfig.Network.BIND_IP}")
            appendLine("iface=${RuntimeConfig.Root.IFACE}")
            appendLine()

            appendLine("=== ROOT SNAPSHOT ===")
            appendLine(LogSanitizer.sanitize(RootNetUtil.getIfaceProbeState(force = false).details))
            runCatching {
                RootNetUtil.checkRouteTo(RuntimeConfig.Network.TARGET_IP, RuntimeConfig.Network.BIND_IP, forceProbe = false)
            }.onSuccess { route ->
                appendLine(route.summary())
                appendLine(LogSanitizer.sanitize(route.output))
            }.onFailure {
                appendLine("routeCheckFailed=${it.message ?: it.javaClass.simpleName}")
            }
            appendLine()

            appendLine("=== PROCESS RECOVERY STATE ===")
            appendLine("crashCountInWindow=${recoveryState.crashCountInWindow}")
            appendLine("suppressedUntilElapsedMs=${recoveryState.suppressedUntilElapsedMs}")
            appendLine("timestamps=${recoveryState.timestamps.joinToString(",")}")
            appendLine()

            appendLine("=== PERSISTENT LOG ===")
            val persistedFiles = PersistentLogWriter.orderedLogFiles(context)
            if (persistedFiles.isEmpty()) {
                appendLine("<empty>")
            } else {
                persistedFiles.forEach { persistedFile ->
                    appendLine("--- ${persistedFile.name} ---")
                    val persistedText = runCatching { persistedFile.readText(Charsets.UTF_8) }
                        .getOrElse { "<read_failed:${it.message ?: it.javaClass.simpleName}>" }
                    appendLine(LogSanitizer.sanitize(persistedText))
                }
            }
            val crashFile = PersistentLogWriter.crashLogFile(context)
            if (crashFile.exists()) {
                appendLine("--- ${crashFile.name} ---")
                val crashText = runCatching { crashFile.readText(Charsets.UTF_8) }
                    .getOrElse { "<read_failed:${it.message ?: it.javaClass.simpleName}>" }
                appendLine(LogSanitizer.sanitize(crashText))
            }
            appendLine()

            appendLine("=== LOGCAT SNAPSHOT ===")
            appendLine(
                readLogcat()
                    .lines()
                    .map(LogSanitizer::sanitize)
                    .takeLast(MAX_LINES)
                    .joinToString(separator = System.lineSeparator()),
            )
        }
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
    private const val MAX_LINES = ProductConfig.Logging.EXPORT_MAX_LINES
    private const val MAX_FILES = 5
}
