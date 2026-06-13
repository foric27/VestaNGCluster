package ru.foric27.cluster.util

import android.content.Context
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import ru.foric27.cluster.BuildConfig
import timber.log.Timber

/**
 * Конфигурация AppMetrica для удалённого репорта crash'ей и ошибок.
 *
 * Инициализирует AppMetrica SDK с:
 * - Автоматический сбор crash'ей
 * - Timber integration для логирования
 * - Environment = debug/release
 */
internal object CrashAnalytics {

    private var initialized = false

    /**
     * Инициализирует AppMetrica.
     *
     * Вызывается из [ClusterApp.onCreate] после Timber.
     * API key берётся из переменной окружения или BuildConfig.
     *
     * @param context контекст приложения
     * @param apiKey API key AppMetrica (если null, работает в no-op режиме)
     * @param debug режим отладки (логирует отправку событий)
     */
    fun init(context: Context, apiKey: String? = null, debug: Boolean = false) {
        if (initialized) return
        initialized = true

        val effectiveApiKey = apiKey ?: resolveApiKey(context)
        if (effectiveApiKey.isNullOrBlank()) {
            Timber.tag("CrashAnalytics").i("AppMetrica API key не настроен, crash analytics отключён")
            return
        }

        try {
            val config = AppMetricaConfig.newConfigBuilder(effectiveApiKey)
                .withLogs()
                .withCrashReporting(true)
                .withNativeCrashReporting(true)
                .withSessionsAutoTrackingEnabled(true)
                .build()

            AppMetrica.activate(context, config)

            Timber.tag("CrashAnalytics").i("AppMetrica инициализирован (key=***${effectiveApiKey.takeLast(8)})")
        } catch (t: Throwable) {
            Timber.tag("CrashAnalytics").e(t, "Ошибка инициализации AppMetrica")
        }
    }

    /**
     * Отправляет сообщение в AppMetrica (не crash).
     *
     * @param message текст сообщения
     * @param extras дополнительные параметры
     */
    fun captureMessage(message: String, extras: Map<String, String> = emptyMap()) {
        if (!initialized) return
        try {
            AppMetrica.reportEvent(message, extras)
        } catch (t: Throwable) {
            Timber.tag("CrashAnalytics").w(t, "Не удалось отправить сообщение в AppMetrica")
        }
    }

    /**
     * Отправляет exception в AppMetrica.
     *
     * @param throwable исключение
     * @param message дополнительное сообщение
     */
    fun captureException(throwable: Throwable, message: String? = null) {
        if (!initialized) return
        try {
            if (message != null) {
                AppMetrica.reportError(message, throwable)
            } else {
                AppMetrica.reportUnhandledException(throwable)
            }
        } catch (t: Throwable) {
            Timber.tag("CrashAnalytics").w(t, "Не удалось отправить exception в AppMetrica")
        }
    }

    /**
     * Устанавливает контекст пользователя для crash-отчётов.
     *
     * @param userId идентификатор устройства
     * @param extras дополнительные данные
     */
    fun setDeviceContext(userId: String, extras: Map<String, String> = emptyMap()) {
        if (!initialized) return
        try {
            AppMetrica.setUserProfileID(userId)
            extras.forEach { (key, value) ->
                AppMetrica.reportEvent("device_context", mapOf(key to value))
            }
        } catch (t: Throwable) {
            Timber.tag("CrashAnalytics").w(t, "Не удалось установить контекст устройства")
        }
    }

    private fun resolveApiKey(context: Context): String? {
        // 1. Из BuildConfig (самый надёжный способ)
        val buildConfigKey = BuildConfig.APPMETRICA_API_KEY
        if (!buildConfigKey.isNullOrBlank() && buildConfigKey != "null") return buildConfigKey

        // 2. Из переменной окружения (для CI)
        System.getenv("APPMETRICA_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }

        // 3. Из assets/appmetrica.properties (fallback)
        try {
            context.assets.open("appmetrica.properties").use { stream ->
                val props = java.util.Properties()
                props.load(stream)
                val key = props.getProperty("api.key")
                if (!key.isNullOrBlank()) return key
            }
        } catch (_: Throwable) {
            // Файл не существует
        }

        return null
    }
}
