package ru.foric27.cluster

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

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        if (BuildConfig.DEBUG) return true
        return priority >= Log.INFO || RuntimeConfig.Logging.VERBOSE_ENABLED
    }

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
    }

    private companion object {
        const val DEFAULT_TAG = "VestaCluster"
    }
}
