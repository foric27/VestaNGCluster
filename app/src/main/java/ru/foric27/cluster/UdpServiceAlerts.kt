package ru.foric27.cluster

import android.content.Context
import android.util.Log

internal class UdpServiceAlerts(
    private val context: Context,
    private val tag: String,
    private val updateNotification: (String) -> Unit,
) {

    @Volatile private var noLinkNotified = false
    @Volatile private var rootWarningShown = false

    fun resetNoLinkWarning() {
        noLinkNotified = false
    }

    fun notifyNoLinkOnce(message: String) {
        updateNotification(message)
        if (noLinkNotified) return
        noLinkNotified = true
        AppWarningCenter.publish(message)
    }

    fun notifyRootRequiredOnce() {
        val message = context.getString(R.string.msg_root_required)
        updateNotification(message)
        if (rootWarningShown) return
        rootWarningShown = true
        AppWarningCenter.publish(message)
    }

    fun replayRootWarningIfPresent() {
        if (AppWarningCenter.contains(context.getString(R.string.msg_root_required))) {
            notifyRootRequiredOnce()
        }
    }

    fun logRouteVerdict(stage: String, result: RootNetUtil.RouteCheckResult) {
        if (result.ok) {
            Log.i(tag, "Проверка маршрута ($stage): ${result.summary()}")
            return
        }

        val output = result.output.replace('\n', ' ').trim()
        Log.w(
            tag,
            "Проверка маршрута ($stage): ${result.summary()}, devOk=${result.devOk}, srcOk=${result.srcOk}, rootRequired=${result.rootRequired}, output=$output",
        )
    }
}
