package ru.foric27.cluster.video
import ru.foric27.cluster.BuildConfig
import ru.foric27.cluster.config.*
import ru.foric27.cluster.network.*
import ru.foric27.cluster.ui.*

import android.os.SystemClock
import timber.log.Timber

// Импорт BuildConfig для проверки принадлежности компонента к пакету приложения

/**
 * Запуск целевой cluster-activity на VirtualDisplay через root-команду `am start --display`.
 *
 * Все запуски выполняются через root `am start`, что обеспечивает совместимость
 * на устройствах, где прямой `Context.startActivity` с `setLaunchDisplayId`
 * не работает из-за ограничений системы (Permission Denial).
 *
 * **Throttle:**
 * - Normal launch: [NORMAL_RELAUNCH_THROTTLE_MS] между попытками
 * - Force launch: [FORCE_RELAUNCH_THROTTLE_MS] между принудительными запусками
 * - Reuse guard: пропускает запуск, если activity уже запущена на том же display
 *
 * **Launch order (критичен):**
 * 1. `launchBlackScreen()` — синхронно в foreground для предотвращения race condition
 * 2. `closeOwnClusterOverlays()` — background fire-and-forget для закрытия старых overlay
 * 3. Root `am start --display <id>` — запуск target activity
 *
 * @param preferredLaunchComponent полный компонент (`package/class`) для запуска.
 *   Если null — используется [RuntimeConfig.TargetApp.CLUSTER_COMPONENT]
 * @param rootActivityStarter интерфейс для выполнения root-команд.
 *   По умолчанию [RootCommandActivityStarter] через [NetworkRootShell]
 * @param clock функция получения текущего времени (для тестирования)
 */
internal class VideoDisplayLauncher(
    private val preferredLaunchComponent: String?,
    private val rootActivityStarter: RootActivityStarter = RootCommandActivityStarter,
    private val clock: () -> Long = { safeElapsedRealtime() },
) {

    /**
     * Интерфейс для выполнения root-команд запуска activity.
     *
     * Абстрагирует способ выполнения `am start` — через libsu, mock или другой executor.
     */
    internal fun interface RootActivityStarter {
        fun start(command: String): RootLaunchAttempt
    }

    /**
     * Результат попытки root-запуска activity.
     *
     * @param success true если команда выполнена успешно
     * @param errorMessage текст ошибки при неуспешном запуске
     */
    internal data class RootLaunchAttempt(
        val success: Boolean,
        val errorMessage: String?,
    )

    /**
     * Запускает целевую activity на указанном display.
     *
     * Порядок запуска критичен:
     * 1. [launchBlackScreen] — синхронно для предотвращения race condition
     * 2. [closeOwnClusterOverlays] — fire-and-forget в фоне
     * 3. Root `am start --display` — запуск target activity
     *
     * @param displayId ID VirtualDisplay для запуска
     * @param force если `true`, игнорирует reuse guard и throttle (с собственным throttle)
     */
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

        // Чёрный экран запускается синхронно ДО навигатора, чтобы гарантированно
        // оказаться на display раньше целевой activity. Ранее оба вызова шли
        // в фоновом потоке, и навигатор мог стартовать раньше чёрного экрана —
        // в итоге чёрный экран оставался поверх навигации.
        try {
            launchBlackScreen(displayId)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "launchBlackScreen: непредвиденная ошибка")
        }

        // Cleanup старых overlay — fire-and-forget в фоне, чтобы не блокировать
        // основной запуск навигатора.
        Thread({
            try {
                closeOwnClusterOverlays()
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "closeOwnClusterOverlays: непредвиденная ошибка")
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

    /**
     * Закрывает собственные overlay-activity (MediaCover, BlackScreen) через broadcast.
     *
     * Выполняется в фоновом потоке, чтобы не блокировать основной запуск навигатора.
     */
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

    /**
     * Запускает чёрный экран на указанном display для предотвращения race condition
     * с основной activity.
     *
     * Чёрный экран должен стартовать синхронно ДО навигатора, чтобы гарантированно
     * оказаться на display раньше целевой activity.
     *
     * @param displayId ID VirtualDisplay для запуска
     */
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

        /**
         * Сбрасывает состояние throttle и reuse guard.
         *
         * Вызывается при остановке видеопайплайна, чтобы следующий запуск
         * не был пропущен из-за устаревшего состояния.
         */
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

        /**
         * Создаёт [RootActivityStarter] на основе [RootCommandExecutor].
         *
         * Оборачивает вызов [RootCommandExecutor.run] в [RootLaunchAttempt],
         * преобразуя код возврата и текст вывода.
         *
         * @param executor executor для выполнения root-команд
         * @return адаптер [RootActivityStarter]
         */
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
