package ru.foric27.cluster.service
import ru.foric27.cluster.R
import ru.foric27.cluster.network.*
import ru.foric27.cluster.util.*

import android.content.Context
import timber.log.Timber

/**
 * Управление пользовательскими уведомлениями и предупреждениями сервиса.
 *
 * Публикует уведомления о проблемах со связью, необходимости root-прав
 * и логирует результаты проверки маршрута. Все уведомления дедуплицируются
 * через флаги [noLinkNotified] и [rootWarningShown].
 */
internal class UdpServiceAlerts(
    private val context: Context,
    private val tag: String,
    private val updateNotification: (String) -> Unit,
) {

    @Volatile private var noLinkNotified = false
    @Volatile private var rootWarningShown = false

    /**
     * Сбрасывает флаг уведомления о потере связи.
     *
     * Вызывается при успешном запуске стрима, чтобы следующая ошибка
     * снова показала уведомление.
     */
    fun resetNoLinkWarning() {
        noLinkNotified = false
    }

    /**
     * Публикует уведомление о проблеме со связью однократно.
     *
     * Обновляет foreground-уведомление и публикует предупреждение
     * в [AppWarningCenter] только при первом вызове (до [resetNoLinkWarning]).
     *
     * @param message текст уведомления для пользователя
     */
    fun notifyNoLinkOnce(message: String) {
        updateNotification(message)
        if (noLinkNotified) return
        noLinkNotified = true
        AppWarningCenter.publish(message)
    }

    /**
     * Публикует уведомление о необходимости root-прав однократно.
     *
     * Использует строку [R.string.msg_root_required] как текст уведомления.
     */
    fun notifyRootRequiredOnce() {
        val message = context.getString(R.string.msg_root_required)
        updateNotification(message)
        if (rootWarningShown) return
        rootWarningShown = true
        AppWarningCenter.publish(message)
    }

    /**
     * Повторно публикует root-уведомление, если оно уже присутствует в [AppWarningCenter].
     */
    fun replayRootWarningIfPresent() {
        if (AppWarningCenter.contains(context.getString(R.string.msg_root_required))) {
            notifyRootRequiredOnce()
        }
    }

    /**
     * Логирует результат проверки маршрута через [RootNetUtil].
     *
     * При успешном результате логирует на уровне info, при неуспешном — warning
     * с детализацией отдельных флагов проверки.
     *
     * @param stage этап проверки (например, "startup", "watchdog")
     * @param result результат проверки маршрута
     */
    fun logRouteVerdict(stage: String, result: RootNetUtil.RouteCheckResult) {
        if (result.ok) {
            Timber.tag(tag).i("Проверка маршрута ($stage): ${result.summary()}")
            return
        }

        val output = result.output.replace('\n', ' ').trim()
        Timber.tag(tag).w("Проверка маршрута ($stage): ${result.summary()}, devOk=${result.devOk}, srcOk=${result.srcOk}, rootRequired=${result.rootRequired}, output=$output",
        )
    }
}
