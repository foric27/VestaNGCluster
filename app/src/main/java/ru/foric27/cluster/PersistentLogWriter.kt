package ru.foric27.cluster

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class PersistentLogWriter(private val rootDir: File) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PersistentLogWriter").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    init {
        rootDir.mkdirs()
    }

    fun write(priority: Int, tag: String, message: String) {
        if (closed) return
        runCatching { buildLine(priority, tag, message) }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось подготовить persisted log line") }
            .onSuccess { rendered ->
                runCatching {
                    executor.execute {
                        runCatching { appendLine(rendered) }
                            .onFailure { Timber.tag(TAG).w(it, "Не удалось записать persisted log") }
                    }
                }.onFailure {
                    Timber.tag(TAG).w(it, "Не удалось поставить persisted log в очередь")
                }
            }
    }

    fun flushAndShutdown(timeoutMs: Long = 1_500L) {
        closed = true
        executor.shutdown()
        runCatching { executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось дождаться завершения persisted log writer") }
    }

    private fun appendLine(line: String) {
        rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size.toLong())
        currentFile().parentFile?.mkdirs()
        FileWriter(currentFile(), true).use { writer ->
            writer.append(line)
            writer.flush()
        }
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        val current = currentFile()
        if (current.exists() && current.length() + incomingBytes < ProductConfig.Logging.FILE_MAX_BYTES) {
            return
        }
        val lastIndex = ProductConfig.Logging.FILE_COUNT - 1
        File(rootDir, "${ProductConfig.Logging.FILE_NAME}.$lastIndex").delete()
        for (index in lastIndex - 1 downTo 1) {
            val source = File(rootDir, "${ProductConfig.Logging.FILE_NAME}.$index")
            if (source.exists()) {
                source.renameTo(File(rootDir, "${ProductConfig.Logging.FILE_NAME}.${index + 1}"))
            }
        }
        if (current.exists()) {
            current.renameTo(File(rootDir, "${ProductConfig.Logging.FILE_NAME}.1"))
        }
    }

    private fun currentFile(): File = File(rootDir, ProductConfig.Logging.FILE_NAME)

    private fun buildLine(priority: Int, tag: String, message: String): String {
        val timestamp = synchronized(DATE_FORMAT_LOCK) {
            DATE_FORMAT.format(Date())
        }
        return "$timestamp ${priorityLetter(priority)}/$tag: $message\n"
    }

    private fun priorityLetter(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    companion object {
        private const val TAG = "PersistentLogWriter"
        private val DATE_FORMAT_LOCK = Any()
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun defaultDirectory(context: Context): File {
            return File(context.cacheDir, ProductConfig.Logging.FILE_DIR_NAME)
        }

        fun orderedLogFiles(context: Context): List<File> {
            val dir = defaultDirectory(context)
            val files = mutableListOf<File>()
            for (index in ProductConfig.Logging.FILE_COUNT - 1 downTo 1) {
                val archived = File(dir, "${ProductConfig.Logging.FILE_NAME}.$index")
                if (archived.exists()) files += archived
            }
            val current = File(dir, ProductConfig.Logging.FILE_NAME)
            if (current.exists()) files += current
            return files
        }

        fun crashLogFile(context: Context): File {
            return File(defaultDirectory(context), ProductConfig.Logging.CRASH_LOG_FILE)
        }
    }
}
