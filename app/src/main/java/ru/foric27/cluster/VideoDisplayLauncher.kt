package ru.foric27.cluster

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log

internal class VideoDisplayLauncher(
    private val context: Context,
    private val streamConfig: StreamConfig,
    private val preferredLaunchComponent: String?,
) {

    fun launchOnDisplay(displayId: Int) {
        val commands = YandexLaunchTarget.buildPreferredCommands(preferredLaunchComponent)
        var started: YandexLaunchTarget.LaunchCommand? = null
        var lastShellResult = RootShell.Result(-1, "", "not_started")
        var lastDirectError: Throwable? = null

        for (command in commands) {
            val direct = launchViaIntentBestEffort(displayId, command)
            if (direct.success) {
                started = command
                Log.i(TAG, "Proxy-activity запрошена через Context.startActivity на дисплее $displayId: ${command.component}, видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                break
            }
            lastDirectError = direct.error

            if (!shouldTryRootLaunchFallback(direct.error)) {
                continue
            }

            if (!RootShell.ensurePrivilegedToolAvailable("am")) {
                continue
            }

            val shellResult = RootShell.exec(listOf(YandexLaunchTarget.buildProxyAmStartCommand(displayId, command)))
            lastShellResult = shellResult
            if (shellResult.ok()) {
                started = command
                Log.i(TAG, "Proxy-activity запрошена через su/am start на дисплее $displayId после отказа прямого запуска: ${command.component}, видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                break
            }
        }

        if (started != null) {
            Log.i(TAG, "Запрос на активацию activity для вывода отправлен на дисплей $displayId: ${started.component} (${started.note}), видимая область=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
            return
        }

        if (isLaunchTargetMissing(lastShellResult) || lastDirectError is ActivityNotFoundException) {
            notifyLaunchAppMissing(commands.lastOrNull())
        }
        Log.w(
            TAG,
            "Не удалось запустить Яндекс.Навигатор на display=$displayId. " +
                "Последняя попытка: ${commands.lastOrNull()?.component ?: "не задана"}. " +
                "DirectError=${lastDirectError?.message ?: "null"}. " +
                "STDOUT=${lastShellResult.out} STDERR=${lastShellResult.err}",
        )
    }

    private fun launchViaIntentBestEffort(
        displayId: Int,
        command: YandexLaunchTarget.LaunchCommand,
    ): LaunchAttempt {
        val intent = YandexLaunchTarget.buildProxyIntent(command)

        return try {
            val options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(displayId)
                .toBundle()
            context.startActivity(intent, options)
            LaunchAttempt(true, null)
        } catch (t: Throwable) {
            LaunchAttempt(false, t)
        }
    }

    private fun shouldTryRootLaunchFallback(error: Throwable?): Boolean {
        val message = error?.message?.lowercase().orEmpty()
        return error is SecurityException ||
            message.contains("permission denial") ||
            message.contains("launchdisplayid") ||
            message.contains("display")
    }

    private fun isLaunchTargetMissing(result: RootShell.Result): Boolean {
        val text = buildString {
            append(result.out)
            append('\n')
            append(result.err)
        }.lowercase()

        return text.contains("error type 3") ||
            text.contains("does not exist") ||
            text.contains("activity class")
    }

    private fun notifyLaunchAppMissing(command: YandexLaunchTarget.LaunchCommand?) {
        val msg = context.getString(
            R.string.msg_output_app_not_found_fmt,
            command?.component ?: YandexLaunchTarget.COMPONENT_AUTO_CLUSTER,
        )
        RootShell.publishUserWarning(msg)
    }

    private data class LaunchAttempt(
        val success: Boolean,
        val error: Throwable?,
    )

    private companion object {
        private const val TAG = "VideoDisplayLauncher"
    }
}
