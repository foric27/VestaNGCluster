package ru.foric27.cluster.util
import ru.foric27.cluster.BuildConfig
import ru.foric27.cluster.config.*

import android.util.Log
import timber.log.Timber

/**
 * Единая точка настройки логирования приложения.
 *
 * В debug-сборке сохраняет подробные теги Timber. В release-сборке пропускает
 * только безопасные диагностические сообщения уровня info/warn/error, а
 * расширенный вывод можно временно включить через экран разработчика.
 *
 * Сообщения уровня INFO и выше (INFO/WARN/ERROR/ASSERT) сохраняются в RAM-буфер [InMemoryLogBuffer]
 * для последующего экспорта без постоянной записи во flash.
 */
internal class AppTimberTree : Timber.Tree() {

    /**
     * Проверяет, нужно ли логировать сообщение с данным приоритетом.
     *
     * @param tag тег лога
     * @param priority уровень приоритета (Log.VERBOSE, Log.DEBUG и т.д.)
     * @return true, если сообщение должно быть залогировано
     */
    override fun isLoggable(tag: String?, priority: Int): Boolean {
        if (BuildConfig.DEBUG) return true
        return priority >= Log.INFO || RuntimeConfig.Logging.VERBOSE_ENABLED
    }

    /**
     * Записывает лог-сообщение в системный log и RAM-буфер.
     *
     * @param priority уровень приоритета
     * @param tag тег лога
     * @param message текст сообщения
     * @param t необязательное исключение
     */
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) return
        val safeMessage = LogSanitizer.sanitize(message)
        val safeTag = tag ?: DEFAULT_TAG
        val fullMessage = if (t == null) {
            safeMessage
        } else {
            "$safeMessage\n${LogSanitizer.sanitize(Log.getStackTraceString(t))}"
        }
        if (t == null) {
            Log.println(priority, safeTag, safeMessage)
        } else {
            Log.println(priority, safeTag, "$safeMessage\n${Log.getStackTraceString(t)}")
        }
        InMemoryLogBuffer.append(priority, safeTag, fullMessage)

        if (t != null && priority >= Log.WARN) {
            CrashAnalytics.captureException(t, "[$safeTag] $safeMessage")
        }
    }

    private companion object {
        const val DEFAULT_TAG = "VestaCluster"
    }
}
