package ru.foric27.cluster.ui
import ru.foric27.cluster.util.*

import java.util.ArrayDeque

/**
 * Журнал пользовательских уведомлений для отображения в UI.
 *
 * Хранит ограниченный список предупреждений из [AppWarningCenter]
 * и уведомлений сервиса. Поддерживает callback для обновления UI.
 */
internal class MainNoticeLog {

    private data class NoticeEntry(
        val text: String,
        val isError: Boolean,
    )

    private val entries = ArrayDeque<NoticeEntry>()
    private var onChange: (() -> Unit)? = null

    val warningListener = object : AppWarningCenter.WarningListener {
        override fun onWarningPublished(message: String) {
            show(message, isError = true)
        }
    }

    fun setChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    fun consumePendingWarnings() {
        AppWarningCenter.consumeAll().forEach { show(it, isError = true) }
    }

    fun show(message: String, isError: Boolean) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return

        val existing = entries.firstOrNull { it.text == normalized }
        if (existing != null && (!isError || existing.isError)) {
            onChange?.invoke()
            return
        }

        entries.removeAll { it.text == normalized }
        entries.addFirst(NoticeEntry(normalized, isError))
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
        onChange?.invoke()
    }

    fun clear() {
        entries.clear()
        AppWarningCenter.clear()
        onChange?.invoke()
    }

    fun removeMatching(predicate: (String) -> Boolean) {
        entries.removeAll { predicate(it.text) }
        AppWarningCenter.removeMatching(predicate)
        onChange?.invoke()
    }

    fun hasErrors(): Boolean = entries.any { it.isError }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun renderText(): String = entries.joinToString("\n\n") { it.text }

    fun renderTitle(hasErrors: Boolean): String = ""

    private companion object {
        private const val MAX_ENTRIES = 120
    }
}
