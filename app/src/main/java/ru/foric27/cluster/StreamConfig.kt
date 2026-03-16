package ru.foric27.cluster

import android.content.Context
import java.io.Serializable

/**
 * Рабочая конфигурация потока, собираемая из ProductConfig и runtime-настроек.
 */
data class StreamConfig(
    val ip: String,
    val port: Int,
    val launchComponent: String?,
    val useRootNet: Boolean,
    val localCidr: String?,
    val gateway: String?,
    val bindIp: String?,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val dynamicFps: Boolean,
    val bitrate: Int,
    val iframeIntervalSec: Int,
) : Serializable {

    companion object {
        fun fixedConfig(context: Context? = null): StreamConfig {
            val dpiValue = RuntimeConfig.Video.DPI
            return StreamConfig(
                ip = RuntimeConfig.Network.TARGET_IP,
                port = RuntimeConfig.Network.VIDEO_PORT,
                launchComponent = RuntimeConfig.TargetApp.CLUSTER_COMPONENT,
                useRootNet = RuntimeConfig.Network.USE_ROOT_NET,
                localCidr = RuntimeConfig.Network.LOCAL_CIDR,
                gateway = RuntimeConfig.Network.GATEWAY,
                bindIp = RuntimeConfig.Network.BIND_IP,
                width = RuntimeConfig.Video.WIDTH,
                height = RuntimeConfig.Video.HEIGHT,
                dpi = dpiValue,
                fps = RuntimeConfig.Video.FPS_LIMIT,
                dynamicFps = RuntimeConfig.Video.DYNAMIC_FPS,
                bitrate = RuntimeConfig.Video.BITRATE,
                iframeIntervalSec = RuntimeConfig.Video.IFRAME_INTERVAL_SEC,
            )
        }
    }
}
