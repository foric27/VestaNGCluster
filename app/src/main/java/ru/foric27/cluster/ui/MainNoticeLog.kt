package ru.foric27.cluster.ui
import ru.foric27.cluster.util.*

import java.util.ArrayDeque

/**
 * Журнал пользовательских уведомлений для отображения в UI.
 *
 * Хранит ограниченный список предупреждений из [AppWarningCenter]
 * и уведомлений сервиса. Поддерживает callback для обновления UI.
 */
/**
 * Журнал уведомлений для отображения в UI главного экрана.
 *
 * Хранит ограниченную очередь сообщений с поддержкой дедупликации
 * и интеграции с [AppWarningCenter].
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

    /**
     * Устанавливает слушатель изменений для обновления UI.
     *
     * @param listener колбэк при изменении содержимого
     */
    fun setChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    /**
     * Поглощает все накопленные предупреждения из [AppWarningCenter] и добавляет их в журнал.
     */
    fun consumePendingWarnings() {
        AppWarningCenter.consumeAll().forEach { show(it, isError = true) }
    }

    /**
     * Добавляет сообщение в журнал с дедупликацией.
     *
     * @param message текст сообщения
     * @param isError true для ошибок, false для информации
     */
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

    /**
     * Очищает журнал и сбрасывает предупреждения.
     */
    fun clear() {
        entries.clear()
        AppWarningCenter.clear()
        onChange?.invoke()
    }

    /**
     * Удаляет записи, соответствующие предикату.
     *
     * @param predicate условие удаления
     */
    fun removeMatching(predicate: (String) -> Boolean) {
        entries.removeAll { predicate(it.text) }
        AppWarningCenter.removeMatching(predicate)
        onChange?.invoke()
    }

    /**
     * Проверяет наличие ошибок в журнале.
     *
     * @return true, если есть хотя бы одна ошибка
     */
    fun hasErrors(): Boolean = entries.any { it.isError }

    /**
     * Проверяет, пуст ли журнал.
     *
     * @return true, если записей нет
     */
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Возвращает отформатированный текст всех записей.
     *
     * @return текст для отображения в UI
     */
    fun renderText(): String = entries.joinToString("\n\n") { it.text }

    fun renderTitle(hasErrors: Boolean): String = ""

    private companion object {
        private const val MAX_ENTRIES = 120
    }
}
