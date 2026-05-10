package ru.foric27.cluster

import timber.log.Timber

// Импорт BuildConfig для проверки принадлежности компонента к пакету приложения

/**
 * Запуск целевой cluster-activity на VirtualDisplay через root-команду `am start --display`.
 *
 * Все запуски выполняются через root `am start`, что обеспечивает совместимость
 * на устройствах, где прямой `Context.startActivity` с `setLaunchDisplayId`
 * не работает из-за ограничений системы (Permission Denial).
 */
internal class VideoDisplayLauncher(
    private val preferredLaunchComponent: String?,
    private val rootActivityStarter: RootActivityStarter = RootCommandActivityStarter,
) {

    internal fun interface RootActivityStarter {
        fun start(command: String): RootLaunchAttempt
    }

    internal data class RootLaunchAttempt(
        val success: Boolean,
        val errorMessage: String?,
    )

    fun launchOnDisplay(displayId: Int) {
        val component = preferredLaunchComponent
        val isOwnComponent = component != null && component.startsWith(BuildConfig.APPLICATION_ID)

        if (isOwnComponent) {
            // Для собственных activity: если предыдущий запуск — сторонний навигатор,
            // закрываем его перед запуском собственной activity
            if (!component!!.startsWith(BuildConfig.APPLICATION_ID)) {
                val stopNavCommand = YandexLaunchTarget.buildForceStopCommand(
                    YandexLaunchTarget.extractPackageName(preferredLaunchComponent) ?: return
                )
                rootActivityStarter.start(stopNavCommand)
                Timber.tag(TAG).i("Закрыт предыдущий навигатор: ${preferredLaunchComponent}")
            }
            // Для собственных activity используем прямой root am start --display
            val directCommand = YandexLaunchTarget.buildDirectAmStartCommand(displayId, component)
            val root = rootActivityStarter.start(directCommand)
            if (root.success) {
                Timber.tag(TAG).i("Root-запуск собственной activity на display=$displayId: $component")
                return
            }
            Timber.tag(TAG).w("Не удалось запустить собственную activity на display=$displayId. RootError=${root.errorMessage ?: "null"}")
            launchPlaceholder(displayId)
            return
        }

        val commands = YandexLaunchTarget.buildPreferredCommands(preferredLaunchComponent)
        var lastRootError: String? = null

        for (command in commands) {
            val shellCommand = YandexLaunchTarget.buildProxyAmStartCommand(displayId, command)
            val root = rootActivityStarter.start(shellCommand)
            if (root.success) {
                Timber.tag(TAG).i("Root-запуск навигатора на display=$displayId: ${command.component} (${command.note}), видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                return
            }
            lastRootError = root.errorMessage
        }

        val lastCommand = commands.lastOrNull()
        Timber.tag(TAG).w("Не удалось запустить Яндекс.Навигатор на display=$displayId. " +
                "Последняя попытка: ${lastCommand?.component ?: "не задана"}. " +
                "RootError=${lastRootError ?: "null"}",
        )
        launchPlaceholder(displayId)
    }

    private fun launchPlaceholder(displayId: Int) {
        val component = BuildConfig.APPLICATION_ID + "/." + StreamPlaceholderActivity::class.java.simpleName
        val command = YandexLaunchTarget.buildDirectAmStartCommand(displayId, component)
        val root = rootActivityStarter.start(command)
        if (root.success) {
            Timber.tag(TAG).i("Root-запуск заглушки потока на display=$displayId")
        } else {
            Timber.tag(TAG).w("Не удалось запустить заглушку потока на display=$displayId. RootError=${root.errorMessage ?: "null"}")
        }
    }

    companion object {
        private const val TAG = "VideoDisplayLauncher"

        private val RootCommandActivityStarter = rootCommandActivityStarter(RootCommandRunner)

        internal fun rootCommandActivityStarter(executor: RootCommandExecutor): RootActivityStarter {
            return RootActivityStarter { command ->
                val result = executor.run(
                cmds = listOf(command),
                logOnFailure = true,
                timeoutMs = RuntimeConfig.Root.SU_TIMEOUT_MS,
                )
                RootLaunchAttempt(
                    success = result.ok(),
                    errorMessage = result.combinedText().trim().ifEmpty { "code=${result.code}, timedOut=${result.timedOut}" },
                )
            }
        }
    }
}
