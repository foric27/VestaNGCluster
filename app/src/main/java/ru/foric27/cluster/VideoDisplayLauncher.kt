package ru.foric27.cluster

import android.os.SystemClock
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
    private val clock: () -> Long = { safeElapsedRealtime() },
) {

    internal fun interface RootActivityStarter {
        fun start(command: String): RootLaunchAttempt
    }

    internal data class RootLaunchAttempt(
        val success: Boolean,
        val errorMessage: String?,
    )

    fun launchOnDisplay(displayId: Int, force: Boolean = false) {
        val component = preferredLaunchComponent
        if (force) {
            synchronized(throttleLock) {
                val now = clock()
                val sinceLast = now - lastForceLaunchMs
                if (lastForceLaunchMs != 0L && sinceLast < FORCE_RELAUNCH_THROTTLE_MS) {
                    Timber.tag(TAG).i("Пропускаю принудительный перезапуск на display=$displayId — прошло ${sinceLast}мс с прошлого force=true (throttle=${FORCE_RELAUNCH_THROTTLE_MS}мс)")
                    return
                }
                lastForceLaunchMs = now
            }
        } else {
            synchronized(throttleLock) {
                if (lastNormalLaunchMs != 0L) {
                    val now = clock()
                    val sinceLast = now - lastNormalLaunchMs
                    if (sinceLast < NORMAL_RELAUNCH_THROTTLE_MS) {
                        Timber.tag(TAG).i("Пропускаю force=false relaunch на display=$displayId — прошло ${sinceLast}мс (throttle=${NORMAL_RELAUNCH_THROTTLE_MS}мс)")
                        return
                    }
                }
            }
            if (shouldReuseLaunch(displayId, component)) {
                Timber.tag(TAG).i("Пропускаю повторный root-запуск на display=$displayId, component=${component ?: "null"}")
                return
            }
            synchronized(throttleLock) {
                lastNormalLaunchMs = clock()
            }
        }

        // Cleanup (закрытие overlay + чёрный экран) — best-effort fire-and-forget.
        // Запускаем в фоне, чтобы медленный/нестабильный libsu shell не блокировал
        // основной запуск навигатора (раньше 4 root команды шли последовательно
        // и каждая могла занять 10s на таймауте → 20-40s freeze UI).
        Thread({
            try {
                closeOwnClusterOverlays()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "closeOwnClusterOverlays: непредвиденная ошибка")
            }
            try {
                launchBlackScreen(displayId)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "launchBlackScreen: непредвиденная ошибка")
            }
        }, "LauncherCleanup").start()

        val isOwnComponent = component != null && component.startsWith(BuildConfig.APPLICATION_ID)

        if (isOwnComponent) {
            // Для собственных activity: перед запуском закрываем сторонний навигатор
            // (RuntimeConfig.TargetApp.PACKAGE_NAME), чтобы он не отображался поверх MED.
            val targetPackage = RuntimeConfig.TargetApp.PACKAGE_NAME
            if (targetPackage.isNotBlank() && targetPackage != BuildConfig.APPLICATION_ID) {
                val stopNavCommand = YandexLaunchTarget.buildForceStopCommand(targetPackage)
                rootActivityStarter.start(stopNavCommand)
                Timber.tag(TAG).i("Закрыт предыдущий навигатор: $targetPackage")
            }
            // Для собственных activity используем прямой root am start --display
            val directCommand = YandexLaunchTarget.buildDirectAmStartCommand(displayId, component!!)
            val root = rootActivityStarter.start(directCommand)
            if (root.success) {
                markLaunchSuccess(displayId, component)
                Timber.tag(TAG).i("Root-запуск собственной activity на display=$displayId: $component")
                return
            }
            Timber.tag(TAG).w("Не удалось запустить собственную activity на display=$displayId. RootError=${root.errorMessage ?: "null"}")
            return
        }

        val commands = YandexLaunchTarget.buildPreferredCommands(preferredLaunchComponent)
        var lastRootError: String? = null

        for (command in commands) {
            val shellCommand = YandexLaunchTarget.buildProxyAmStartCommand(displayId, command)
            val root = rootActivityStarter.start(shellCommand)
            if (root.success) {
                markLaunchSuccess(displayId, command.component)
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
    }

    private fun closeOwnClusterOverlays() {
        val actions = listOf(
            MediaCoverActivity.ACTION_FINISH_MEDIA_COVER,
            ClusterBlackScreenActivity.ACTION_FINISH_CLUSTER_BLACK_SCREEN,
        )
        for (action in actions) {
            val root = rootActivityStarter.start(YandexLaunchTarget.buildBroadcastCommand(action))
            if (!root.success) {
                Timber.tag(TAG).w("Не удалось закрыть overlay action=$action. RootError=${root.errorMessage ?: "null"}")
            }
        }
    }

    private fun launchBlackScreen(displayId: Int) {
        val component = BuildConfig.APPLICATION_ID + "/." + ClusterBlackScreenActivity::class.java.simpleName
        val command = YandexLaunchTarget.buildDirectAmStartCommand(displayId, component)
        val root = rootActivityStarter.start(command)
        if (root.success) {
            Timber.tag(TAG).i("Запущен кратковременный чёрный экран на display=$displayId")
        } else {
            Timber.tag(TAG).w("Не удалось запустить чёрный экран на display=$displayId. RootError=${root.errorMessage ?: "null"}")
        }
    }

    companion object {
        private const val TAG = "VideoDisplayLauncher"
        private const val FORCE_RELAUNCH_THROTTLE_MS = 1_500L
        private const val NORMAL_RELAUNCH_THROTTLE_MS = 800L
        @Volatile private var lastLaunchedDisplayId: Int = -1
        @Volatile private var lastLaunchedComponent: String? = null
        @Volatile private var lastForceLaunchMs = 0L
        @Volatile private var lastNormalLaunchMs = 0L
        private val throttleLock = Any()

        private val RootCommandActivityStarter = rootCommandActivityStarter(RootCommandRunner)

        private fun safeElapsedRealtime(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            0L
        }

        fun clearLaunchState() {
            synchronized(throttleLock) {
                lastLaunchedDisplayId = -1
                lastLaunchedComponent = null
                lastForceLaunchMs = 0L
                lastNormalLaunchMs = 0L
            }
        }

        private fun shouldReuseLaunch(displayId: Int, component: String?): Boolean {
            return displayId >= 0 &&
                lastLaunchedDisplayId == displayId &&
                !lastLaunchedComponent.isNullOrBlank() &&
                lastLaunchedComponent == component
        }

        private fun markLaunchSuccess(displayId: Int, component: String?) {
            lastLaunchedDisplayId = displayId
            lastLaunchedComponent = component
        }

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
