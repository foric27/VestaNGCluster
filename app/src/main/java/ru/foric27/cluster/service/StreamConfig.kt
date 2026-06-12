package ru.foric27.cluster.service
import ru.foric27.cluster.BuildConfig
import ru.foric27.cluster.config.*
import ru.foric27.cluster.ui.*

import android.content.Context
import java.io.Serializable

/**
 * Рабочая конфигурация потока, собираемая из ProductConfig и runtime-настроек.
 *
 * Содержит все параметры, необходимые для запуска video pipeline:
 * - сетевые параметры (ip, port, cidr, gateway, bindIp)
 * - параметры видео (width, height, dpi, fps, bitrate, iframeInterval)
 * - launch component для target activity на secondary display
 *
 * Создаётся через [fixedConfig] из [RuntimeConfig].
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
        /**
         * Создаёт [StreamConfig] из текущих значений [RuntimeConfig].
         *
         * @param context контекст (не используется в текущей реализации, зарезервирован)
         * @param mode режим работы — определяет launch component:
         *   - [AppSettings.UiStreamMode.MED] → `MediaCoverActivity`
         *   - все остальные → `RuntimeConfig.TargetApp.CLUSTER_COMPONENT`
         * @return конфигурация потока, готовая к запуску pipeline
         */
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
