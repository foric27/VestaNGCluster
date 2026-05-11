package ru.foric27.cluster

import android.content.Context
import java.io.Serializable

/**
 * Рабочая конфигурация потока, собираемая из ProductConfig и runtime-настроек.
 */
internal data class StreamConfig(
    val ip: String,
    val port: Int,
    val launchComponent: String?,
    val localCidr: String?,
    val gateway: String?,
    val bindIp: String?,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int,
    val iframeIntervalSec: Int,
) : Serializable {

    companion object {
        fun fixedConfig(context: Context? = null, mode: AppSettings.UiStreamMode = AppSettings.UiStreamMode.NAV): StreamConfig {
            val dpiValue = RuntimeConfig.Video.DPI
            val component = when (mode) {
                AppSettings.UiStreamMode.MED -> BuildConfig.APPLICATION_ID + "/." + MediaCoverActivity::class.java.simpleName
                else -> RuntimeConfig.TargetApp.CLUSTER_COMPONENT
            }
            return StreamConfig(
                ip = RuntimeConfig.Network.TARGET_IP,
                port = RuntimeConfig.Network.VIDEO_PORT,
                launchComponent = component,
                localCidr = RuntimeConfig.Network.LOCAL_CIDR,
                gateway = RuntimeConfig.Network.GATEWAY,
                bindIp = RuntimeConfig.Network.BIND_IP,
                width = RuntimeConfig.Video.WIDTH,
                height = RuntimeConfig.Video.HEIGHT,
                dpi = dpiValue,
                fps = RuntimeConfig.Video.FPS_LIMIT,
                bitrate = RuntimeConfig.Video.BITRATE,
                iframeIntervalSec = RuntimeConfig.Video.IFRAME_INTERVAL_SEC,
            )
        }
    }
}
