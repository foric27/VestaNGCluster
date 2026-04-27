package ru.foric27.cluster

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

internal class VideoDisplayLauncher(
    private val context: Context,
    private val preferredLaunchComponent: String?,
    private val intentStarter: IntentStarter = ActivityOptionsIntentStarter,
    private val rootActivityStarter: RootActivityStarter = RootCommandActivityStarter,
) {

    internal data class ProxyIntentSpec(
        val proxyComponent: String,
        val targetComponent: String,
        val targetAction: String,
        val targetCategory: String,
    )

    internal fun interface IntentStarter {
        fun start(context: Context, spec: ProxyIntentSpec, displayId: Int)
    }

    internal fun interface RootActivityStarter {
        fun start(command: String): RootLaunchAttempt
    }

    internal data class RootLaunchAttempt(
        val success: Boolean,
        val errorMessage: String?,
    )

    fun launchOnDisplay(displayId: Int) {
        val commands = YandexLaunchTarget.buildPreferredCommands(preferredLaunchComponent)
        var started: YandexLaunchTarget.LaunchCommand? = null
        var lastDirectError: Throwable? = null

        for (command in commands) {
            val direct = launchViaIntentBestEffort(displayId, command)
            if (direct.success) {
                started = command
                Timber.tag(TAG).i("Proxy-activity запрошена через Context.startActivity на дисплее $displayId: ${command.component}, видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                break
            }
            lastDirectError = direct.error
        }

        if (started != null) {
            Timber.tag(TAG).i("Запрос на активацию activity для вывода отправлен на дисплей $displayId: ${started.component} (${started.note}), видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
            return
        }

        val rootStarted = launchViaRootFallback(displayId, commands)
        if (rootStarted != null) {
            Timber.tag(TAG).i("Root fallback отправил proxy-запуск навигатора на display=$displayId: ${rootStarted.component} (${rootStarted.note}), видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
            return
        }

        if (lastDirectError is ActivityNotFoundException) {
            notifyLaunchAppMissing(commands.lastOrNull())
        }
        Timber.tag(TAG).w("Не удалось запустить Яндекс.Навигатор на display=$displayId. " +
                "Последняя попытка: ${commands.lastOrNull()?.component ?: "не задана"}. " +
                "DirectError=${lastDirectError?.message ?: "null"}",
        )
    }

    private fun launchViaIntentBestEffort(
        displayId: Int,
        command: YandexLaunchTarget.LaunchCommand,
    ): LaunchAttempt {
        val spec = buildProxyIntentSpec(command)

        return try {
            intentStarter.start(context, spec, displayId)
            LaunchAttempt(true, null)
        } catch (t: Throwable) {
            LaunchAttempt(false, t)
        }
    }

    private fun launchViaRootFallback(
        displayId: Int,
        commands: List<YandexLaunchTarget.LaunchCommand>,
    ): YandexLaunchTarget.LaunchCommand? {
        var lastRootError: String? = null
        for (command in commands) {
            val shellCommand = YandexLaunchTarget.buildProxyAmStartCommand(displayId, command)
            val root = rootActivityStarter.start(shellCommand)
            if (root.success) {
                return command
            }
            lastRootError = root.errorMessage
        }

        Timber.tag(TAG).w("Root fallback не смог запустить proxy-activity на display=$displayId. " +
                "Последняя попытка: ${commands.lastOrNull()?.component ?: "не задана"}. " +
                "RootError=${lastRootError ?: "null"}",
        )
        return null
    }

    private fun notifyLaunchAppMissing(command: YandexLaunchTarget.LaunchCommand?) {
        val msg = context.getString(
            R.string.msg_output_app_not_found_fmt,
            command?.component ?: YandexLaunchTarget.COMPONENT_AUTO_CLUSTER,
        )
        Timber.tag(TAG).w(msg)
    }

    private data class LaunchAttempt(
        val success: Boolean,
        val error: Throwable?,
    )

    private companion object {
        private const val TAG = "VideoDisplayLauncher"

        private fun buildProxyIntentSpec(command: YandexLaunchTarget.LaunchCommand): ProxyIntentSpec {
            return ProxyIntentSpec(
                proxyComponent = "${BuildConfig.APPLICATION_ID}/${ClusterLaunchProxyActivity::class.java.name}",
                targetComponent = command.component,
                targetAction = command.action,
                targetCategory = command.category,
            )
        }

        private fun buildProxyIntent(spec: ProxyIntentSpec): Intent {
            return Intent().apply {
                component = ComponentName.unflattenFromString(spec.proxyComponent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("ru.foric27.cluster.extra.TARGET_COMPONENT", spec.targetComponent)
                putExtra("ru.foric27.cluster.extra.TARGET_ACTION", spec.targetAction)
                putExtra("ru.foric27.cluster.extra.TARGET_CATEGORY", spec.targetCategory)
            }
        }

        private val ActivityOptionsIntentStarter = IntentStarter { context, spec, displayId ->
            val intent = buildProxyIntent(spec)
            val options = ActivityOptions.makeBasic()
            try {
                options.setLaunchDisplayId(displayId)
            } catch (securityException: SecurityException) {
                Timber.tag(TAG).w("Не удалось задать launch display $displayId для ${spec.proxyComponent}: ${securityException.message}",
                    securityException,
                )
            }
            context.startActivity(intent, options.toBundle())
        }

        private val RootCommandActivityStarter = RootActivityStarter { command ->
            val result = RootCommandRunner.run(
                cmds = listOf(command),
                timeoutMs = RuntimeConfig.Root.SU_TIMEOUT_MS,
            )
            RootLaunchAttempt(
                success = result.ok(),
                errorMessage = result.combinedText().trim().ifEmpty { "code=${result.code}, timedOut=${result.timedOut}" },
            )
        }
    }
}
