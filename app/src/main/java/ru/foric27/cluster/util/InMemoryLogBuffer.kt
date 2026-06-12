package ru.foric27.cluster.util

import android.util.Log
import java.util.Collections

/**
 * Кольцевой буфер логов в RAM.
 *
 * Хранит сообщения уровня INFO и выше (INFO/WARN/ERROR/ASSERT).
 * Используется для сохранения контекста работы приложения без постоянной записи во flash.
 *
 * Thread-safe.
 */
internal object InMemoryLogBuffer {

    private const val MAX_LINES = 5_000
    private const val TAG = "InMemoryLogBuffer"

    private val buffer = Collections.synchronizedList(ArrayList<LogLine>())

    data class LogLine(
        val timestamp: String,
        val priority: String,
        val tag: String,
        val message: String,
    ) {
        override fun toString(): String = "$timestamp $priority/$tag: $message"
    }

    /**
     * Добавляет строку в буфер если priority >= INFO.
     *
     * @param priority уровень приоритета (Log.INFO и выше)
     * @param tag тег лога
     * @param message текст сообщения
     */
    fun append(priority: Int, tag: String, message: String) {
        if (priority < Log.INFO) return
        val line = LogLine(
            timestamp = buildTimestamp(),
            priority = priorityLetter(priority),
            tag = tag,
            message = message,
        )
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeAt(0)
            }
        }
    }

    /**
     * Возвращает копию текущего содержимого буфера.
     *
     * @return список строк логов
     */
    fun toList(): List<LogLine> = synchronized(buffer) { ArrayList(buffer) }

    /**
     * Очищает буфер.
     */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    /**
     * Возвращает текущий размер буфера.
     *
     * @return количество строк в буфере
     */
    fun size(): Int = synchronized(buffer) { buffer.size }

    private fun buildTimestamp(): String {
        val now = java.util.Date()
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(now)
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
}
