package ru.foric27.cluster

import android.content.Context
import android.graphics.Rect

/**
 * Единая runtime-конфигурация приложения.
 *
 * Значения по умолчанию берутся из [ProductConfig], а локальные переопределения
 * сохраняются в DataStore. Через этот объект приложение получает и
 * метаданные для developer-экрана, и уже нормализованные runtime-значения.
 */
object RuntimeConfig {

    internal const val PREFS_NAME = "runtime_config_prefs"

    enum class ValueType {
        STRING,
        INT,
        LONG,
        BOOLEAN,
    }

    data class FieldSpec(
        val key: String,
        val sectionResId: Int,
        val titleResId: Int,
        val type: ValueType,
        val defaultValue: String,
    )

    data class SaveResult(
        val ok: Boolean,
        val message: String,
        val invalidKey: String? = null,
        val normalizedValue: String? = null,
    )

    internal object Keys {
        const val TARGET_ACTION_MAIN = "target_action_main"
        const val TARGET_CATEGORY_CLUSTER_NAVIGATION = "target_category_cluster_navigation"
        const val TARGET_PACKAGE_NAME = "target_package_name"
        const val TARGET_CLUSTER_COMPONENT = "target_cluster_component"

        const val VISIBLE_LEFT = "visible_left"
        const val VISIBLE_TOP = "visible_top"
        const val VISIBLE_RIGHT = "visible_right"
        const val VISIBLE_BOTTOM = "visible_bottom"

        const val VIDEO_WIDTH = "video_width"
        const val VIDEO_HEIGHT = "video_height"
        const val VIDEO_FPS_LIMIT = "video_fps_limit"
        const val VIDEO_DYNAMIC_FPS = "video_dynamic_fps"
        const val VIDEO_DEFAULT_DPI = "video_default_dpi"
        const val VIDEO_BITRATE = "video_bitrate"
        const val VIDEO_IFRAME_INTERVAL_SEC = "video_iframe_interval_sec"
        const val VIDEO_BLACK_BOTTOM_PX = "video_black_bottom_px"

        const val NETWORK_TARGET_IP = "network_target_ip"
        const val NETWORK_VIDEO_PORT = "network_video_port"
        const val NETWORK_STATUS_PORT = "network_status_port"
        const val NETWORK_LOCAL_CIDR = "network_local_cidr"
        const val NETWORK_GATEWAY = "network_gateway"
        const val NETWORK_BIND_IP = "network_bind_ip"
        const val NETWORK_UDP_MAX_PAYLOAD_BYTES = "network_udp_max_payload_bytes"
        const val NETWORK_UDP_PACING_MAX_BPS = "network_udp_pacing_max_bps"

        const val ROOT_IFACE = "root_iface"
        const val ROOT_IFACE_CACHE_TTL_MS = "root_iface_cache_ttl_ms"
        const val ROOT_ROUTE_CACHE_TTL_MS = "root_route_cache_ttl_ms"
        const val ROOT_SU_TIMEOUT_MS = "root_su_timeout_ms"

        const val FTP_UPDATE_ZIP_NAME = "ftp_update_zip_name"
        const val FTP_UPDATE_SIG_NAME = "ftp_update_sig_name"
        const val FTP_PORT = "ftp_port"
        const val FTP_PASSIVE_PORT_START = "ftp_passive_port_start"
        const val FTP_PASSIVE_PORT_END = "ftp_passive_port_end"
        const val FTP_USER = "ftp_user"
        const val FTP_PASSWORD = "ftp_password"
        const val FTP_ROOT_DIR_NAME = "ftp_root_dir_name"
        const val FTP_INTERNAL_POLL_PERIOD_MS = "ftp_internal_poll_period_ms"
        const val FTP_RETRY_DELAY_MS = "ftp_retry_delay_ms"

        const val SERVICE_NOTIFICATION_ID = "service_notification_id"
        const val SERVICE_NOTIFICATION_CHANNEL_ID = "service_notification_channel_id"
        const val SERVICE_NOTIFICATION_CHANNEL_NAME = "service_notification_channel_name"
        const val SERVICE_RESTART_BACKOFF_START_MS = "service_restart_backoff_start_ms"
        const val SERVICE_RESTART_BACKOFF_MAX_MS = "service_restart_backoff_max_ms"
        const val SERVICE_RECOVERY_DELAY_MS = "service_recovery_delay_ms"
        const val SERVICE_TASK_REMOVED_RECOVERY_DELAY_MS = "service_task_removed_recovery_delay_ms"
        const val SERVICE_RECOVERY_REQUEST_CODE = "service_recovery_request_code"
        const val SERVICE_RESTART_REQUEST_DEBOUNCE_MS = "service_restart_request_debounce_ms"
        const val SERVICE_CODEC_ERROR_RESTART_DEBOUNCE_MS = "service_codec_error_restart_debounce_ms"
        const val SERVICE_ROUTE_WAIT_TIMEOUT_MS = "service_route_wait_timeout_ms"
        const val SERVICE_ROUTE_WAIT_STEP_MS = "service_route_wait_step_ms"
        const val SERVICE_NO_ROUTE_RESTART_BACKOFF_MIN_MS = "service_no_route_restart_backoff_min_ms"
        const val SERVICE_IFACE_MISSING_RESTART_BACKOFF_MIN_MS = "service_iface_missing_restart_backoff_min_ms"
        const val SERVICE_CONNECTIVITY_WATCHDOG_PERIOD_MS = "service_connectivity_watchdog_period_ms"
        const val SERVICE_ROUTE_RECENT_SEND_GRACE_MS = "service_route_recent_send_grace_ms"
        const val SERVICE_ROUTE_FAILURES_BEFORE_RESTART = "service_route_failures_before_restart"
        const val SERVICE_THREAD_JOIN_TIMEOUT_MS = "service_thread_join_timeout_ms"
        const val SERVICE_STATUS_PERIOD_MS = "service_status_period_ms"
        const val SERVICE_STATUS_KEY_SYNC_INTERVAL = "service_status_key_sync_interval"
        const val SERVICE_STATUS_ERROR_LOG_EVERY = "service_status_error_log_every"
        const val SERVICE_PROCESS_CRASH_RECOVERY_DELAY_MS = "service_process_crash_recovery_delay_ms"
        const val SERVICE_PROCESS_CRASH_WINDOW_MS = "service_process_crash_window_ms"
        const val SERVICE_PROCESS_CRASH_MAX_IN_WINDOW = "service_process_crash_max_in_window"
        const val SERVICE_PROCESS_CRASH_SUPPRESS_MS = "service_process_crash_suppress_ms"
    }

    private val fieldSpecs: List<FieldSpec> = RuntimeConfigFieldSpecs.create()

    @Volatile
    private var rawValues: Map<String, String> = emptyMap()

    /**
     * Инициализирует кеш runtime-значений при старте процесса или экрана.
     */
    fun init(context: Context) {
        reload(context)
    }

    /**
     * Полностью перечитывает пользовательские overrides из DataStore.
     */
    fun reload(context: Context) {
        rawValues = RuntimeConfigStore.load(context, fieldSpecs)
    }

    /**
     * Возвращает описание всех полей для динамического построения developer UI.
     */
    fun getFieldSpecs(): List<FieldSpec> = fieldSpecs

    fun getFieldValue(spec: FieldSpec): String {
        return rawValues[spec.key] ?: spec.defaultValue
    }

    fun getFieldTitle(context: Context, spec: FieldSpec): String = context.getString(spec.titleResId)

    fun getFieldSectionTitle(context: Context, spec: FieldSpec): String = context.getString(spec.sectionResId)

    internal fun createPreferenceDataStore(context: Context): RuntimeConfigPreferenceDataStore {
        val appContext = context.applicationContext
        return RuntimeConfigPreferenceDataStore(
            saveString = { key, value ->
                val spec = fieldSpecs.firstOrNull { it.key == key }
                spec != null && saveField(appContext, spec, value).ok
            },
            readString = { key, defaultValue ->
                fieldSpecs.firstOrNull { it.key == key }?.let(::getFieldValue) ?: defaultValue
            },
        )
    }

    fun isFtpOnlyField(spec: FieldSpec): Boolean {
        return when (spec.key) {
            Keys.FTP_INTERNAL_POLL_PERIOD_MS,
            Keys.FTP_RETRY_DELAY_MS -> false
            else -> spec.sectionResId == R.string.runtime_section_ftp_update
        }
    }

    /**
     * Валидирует и сохраняет одно runtime-поле, сразу обновляя внутренний кеш.
     */
    fun saveField(context: Context, spec: FieldSpec, rawValue: String): SaveResult {
        val fieldTitle = getFieldTitle(context, spec)
        val result = RuntimeConfigStore.saveField(context, spec, rawValue, fieldTitle)
        if (!result.ok) return result
        reload(context)
        return result
    }

    /**
     * Сбрасывает все runtime-overrides и возвращает проект к ProductConfig.
     */
    fun resetToDefaults(context: Context): SaveResult {
        if (!RuntimeConfigStore.resetToDefaults(context)) {
            return SaveResult(false, context.getString(R.string.runtime_error_reset_defaults))
        }
        reload(context)
        return SaveResult(true, context.getString(R.string.runtime_reset_defaults_ok))
    }

    private fun string(key: String, default: String): String = rawValues[key] ?: default

    private fun int(key: String, default: Int): Int = rawValues[key]?.toIntOrNull() ?: default

    private fun long(key: String, default: Long): Long = rawValues[key]?.toLongOrNull() ?: default

    private fun boolean(key: String, default: Boolean): Boolean {
        return RuntimeConfigStore.parseBooleanValue(rawValues[key], default)
    }

    /**
     * Оставлен как фасад для unit tests и внешнего кода, который проверяет
     * совместимость старых человекочитаемых boolean-значений.
     */
    internal fun parseBooleanValue(rawValue: String?, default: Boolean): Boolean {
        return RuntimeConfigStore.parseBooleanValue(rawValue, default)
    }

    object TargetApp {
        val ACTION_MAIN: String get() = string(Keys.TARGET_ACTION_MAIN, ProductConfig.TargetApp.ACTION_MAIN)
        val CATEGORY_CLUSTER_NAVIGATION: String get() = string(Keys.TARGET_CATEGORY_CLUSTER_NAVIGATION, ProductConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION)
        val PACKAGE_NAME: String get() = string(Keys.TARGET_PACKAGE_NAME, ProductConfig.TargetApp.PACKAGE_NAME)
        val CLUSTER_COMPONENT: String get() = string(Keys.TARGET_CLUSTER_COMPONENT, ProductConfig.TargetApp.CLUSTER_COMPONENT)
    }

    object VisibleArea {
        val LEFT: Int get() = int(Keys.VISIBLE_LEFT, ProductConfig.VisibleArea.LEFT)
        val TOP: Int get() = int(Keys.VISIBLE_TOP, ProductConfig.VisibleArea.TOP)
        val RIGHT: Int get() = int(Keys.VISIBLE_RIGHT, ProductConfig.VisibleArea.RIGHT)
        val BOTTOM: Int get() = int(Keys.VISIBLE_BOTTOM, ProductConfig.VisibleArea.BOTTOM)
        val SHORT: String get() = "Rect($LEFT, $TOP, $RIGHT, $BOTTOM)"
        fun rect(): Rect = Rect(LEFT, TOP, RIGHT, BOTTOM)
    }

    object Video {
        val WIDTH: Int get() = int(Keys.VIDEO_WIDTH, ProductConfig.Video.WIDTH)
        val HEIGHT: Int get() = int(Keys.VIDEO_HEIGHT, ProductConfig.Video.HEIGHT)
        val SIZE_SHORT: String get() = "${WIDTH}x${HEIGHT}"
        val FPS_LIMIT: Int get() = int(Keys.VIDEO_FPS_LIMIT, ProductConfig.Video.FPS_LIMIT)
        val DYNAMIC_FPS: Boolean get() = boolean(Keys.VIDEO_DYNAMIC_FPS, ProductConfig.Video.DYNAMIC_FPS)
        val DPI: Int get() = int(Keys.VIDEO_DEFAULT_DPI, ProductConfig.Video.DEFAULT_DPI)
        val BITRATE: Int get() = int(Keys.VIDEO_BITRATE, ProductConfig.Video.BITRATE)
        val IFRAME_INTERVAL_SEC: Int get() = int(Keys.VIDEO_IFRAME_INTERVAL_SEC, ProductConfig.Video.IFRAME_INTERVAL_SEC)
        val BLACK_BOTTOM_PX: Int get() = int(Keys.VIDEO_BLACK_BOTTOM_PX, ProductConfig.Video.BLACK_BOTTOM_PX)
    }

    object Network {
        val TARGET_IP: String get() = string(Keys.NETWORK_TARGET_IP, ProductConfig.Network.TARGET_IP)
        val VIDEO_PORT: Int get() = int(Keys.NETWORK_VIDEO_PORT, ProductConfig.Network.VIDEO_PORT)
        val STATUS_PORT: Int get() = int(Keys.NETWORK_STATUS_PORT, ProductConfig.Network.STATUS_PORT)
        val VIDEO_ENDPOINT: String get() = "$TARGET_IP:$VIDEO_PORT"
        val STATUS_ENDPOINT: String get() = "$TARGET_IP:$STATUS_PORT"
        val LOCAL_CIDR: String get() = string(Keys.NETWORK_LOCAL_CIDR, ProductConfig.Network.LOCAL_CIDR)
        val GATEWAY: String get() = string(Keys.NETWORK_GATEWAY, ProductConfig.Network.GATEWAY)
        val BIND_IP: String get() = string(Keys.NETWORK_BIND_IP, ProductConfig.Network.BIND_IP)
        val UDP_MAX_PAYLOAD_BYTES: Int get() = int(Keys.NETWORK_UDP_MAX_PAYLOAD_BYTES, ProductConfig.Network.UDP_MAX_PAYLOAD_BYTES)
        val UDP_PACING_MAX_BPS: Int get() = int(Keys.NETWORK_UDP_PACING_MAX_BPS, ProductConfig.Network.UDP_PACING_MAX_BPS)
    }

    object Root {
        val IFACE: String get() = string(Keys.ROOT_IFACE, ProductConfig.Root.IFACE)
        val IFACE_EXISTS_LABEL: String get() = "${IFACE}_exists"
        val MISSING_REASON: String get() = "${IFACE}_missing"
        val MISSING_RUNTIME_REASON: String get() = "${IFACE}_missing_runtime"
        val IFACE_CACHE_TTL_MS: Long get() = long(Keys.ROOT_IFACE_CACHE_TTL_MS, ProductConfig.Root.IFACE_CACHE_TTL_MS)
        val ROUTE_CACHE_TTL_MS: Long get() = long(Keys.ROOT_ROUTE_CACHE_TTL_MS, ProductConfig.Root.ROUTE_CACHE_TTL_MS)
        val SU_TIMEOUT_MS: Long get() = long(Keys.ROOT_SU_TIMEOUT_MS, ProductConfig.Root.SU_TIMEOUT_MS)
    }

    object UpdateFtp {
        val UPDATE_ZIP_NAME: String get() = string(Keys.FTP_UPDATE_ZIP_NAME, ProductConfig.UpdateFtp.UPDATE_ZIP_NAME)
        val UPDATE_SIG_NAME: String get() = string(Keys.FTP_UPDATE_SIG_NAME, ProductConfig.UpdateFtp.UPDATE_SIG_NAME)
        val PORT: Int get() = int(Keys.FTP_PORT, ProductConfig.UpdateFtp.PORT)
        val PASSIVE_PORT_START: Int get() = int(Keys.FTP_PASSIVE_PORT_START, ProductConfig.UpdateFtp.PASSIVE_PORT_START)
        val PASSIVE_PORT_END: Int get() = int(Keys.FTP_PASSIVE_PORT_END, ProductConfig.UpdateFtp.PASSIVE_PORT_END)
        val USER: String get() = string(Keys.FTP_USER, ProductConfig.UpdateFtp.USER)
        val PASSWORD: String get() = string(Keys.FTP_PASSWORD, ProductConfig.UpdateFtp.PASSWORD)
        val ROOT_DIR_NAME: String get() = string(Keys.FTP_ROOT_DIR_NAME, ProductConfig.UpdateFtp.ROOT_DIR_NAME)
        val INTERNAL_POLL_PERIOD_MS: Long get() = long(Keys.FTP_INTERNAL_POLL_PERIOD_MS, ProductConfig.UpdateFtp.INTERNAL_POLL_PERIOD_MS)
        val RETRY_DELAY_MS: Long get() = long(Keys.FTP_RETRY_DELAY_MS, ProductConfig.UpdateFtp.RETRY_DELAY_MS)
    }

    object Service {
        val NOTIFICATION_ID: Int get() = int(Keys.SERVICE_NOTIFICATION_ID, ProductConfig.Service.NOTIFICATION_ID)
        val NOTIFICATION_CHANNEL_ID: String get() = string(Keys.SERVICE_NOTIFICATION_CHANNEL_ID, ProductConfig.Service.NOTIFICATION_CHANNEL_ID)
        val NOTIFICATION_CHANNEL_NAME: String get() = string(Keys.SERVICE_NOTIFICATION_CHANNEL_NAME, ProductConfig.Service.NOTIFICATION_CHANNEL_NAME)
        val RESTART_BACKOFF_START_MS: Long get() = long(Keys.SERVICE_RESTART_BACKOFF_START_MS, ProductConfig.Service.RESTART_BACKOFF_START_MS)
        val RESTART_BACKOFF_MAX_MS: Long get() = long(Keys.SERVICE_RESTART_BACKOFF_MAX_MS, ProductConfig.Service.RESTART_BACKOFF_MAX_MS)
        val SERVICE_RECOVERY_DELAY_MS: Long get() = long(Keys.SERVICE_RECOVERY_DELAY_MS, ProductConfig.Service.SERVICE_RECOVERY_DELAY_MS)
        val TASK_REMOVED_RECOVERY_DELAY_MS: Long get() = long(Keys.SERVICE_TASK_REMOVED_RECOVERY_DELAY_MS, ProductConfig.Service.TASK_REMOVED_RECOVERY_DELAY_MS)
        val SERVICE_RECOVERY_REQUEST_CODE: Int get() = int(Keys.SERVICE_RECOVERY_REQUEST_CODE, ProductConfig.Service.SERVICE_RECOVERY_REQUEST_CODE)
        val RESTART_REQUEST_DEBOUNCE_MS: Long get() = long(Keys.SERVICE_RESTART_REQUEST_DEBOUNCE_MS, ProductConfig.Service.RESTART_REQUEST_DEBOUNCE_MS)
        val CODEC_ERROR_RESTART_DEBOUNCE_MS: Long get() = long(Keys.SERVICE_CODEC_ERROR_RESTART_DEBOUNCE_MS, ProductConfig.Service.CODEC_ERROR_RESTART_DEBOUNCE_MS)
        val ROUTE_WAIT_TIMEOUT_MS: Long get() = long(Keys.SERVICE_ROUTE_WAIT_TIMEOUT_MS, ProductConfig.Service.ROUTE_WAIT_TIMEOUT_MS)
        val ROUTE_WAIT_STEP_MS: Long get() = long(Keys.SERVICE_ROUTE_WAIT_STEP_MS, ProductConfig.Service.ROUTE_WAIT_STEP_MS)
        val NO_ROUTE_RESTART_BACKOFF_MIN_MS: Long get() = long(Keys.SERVICE_NO_ROUTE_RESTART_BACKOFF_MIN_MS, ProductConfig.Service.NO_ROUTE_RESTART_BACKOFF_MIN_MS)
        val IFACE_MISSING_RESTART_BACKOFF_MIN_MS: Long get() = long(Keys.SERVICE_IFACE_MISSING_RESTART_BACKOFF_MIN_MS, ProductConfig.Service.IFACE_MISSING_RESTART_BACKOFF_MIN_MS)
        val CONNECTIVITY_WATCHDOG_PERIOD_MS: Long get() = long(Keys.SERVICE_CONNECTIVITY_WATCHDOG_PERIOD_MS, ProductConfig.Service.CONNECTIVITY_WATCHDOG_PERIOD_MS)
        val ROUTE_RECENT_SEND_GRACE_MS: Long get() = long(Keys.SERVICE_ROUTE_RECENT_SEND_GRACE_MS, ProductConfig.Service.ROUTE_RECENT_SEND_GRACE_MS)
        val ROUTE_FAILURES_BEFORE_RESTART: Int get() = int(Keys.SERVICE_ROUTE_FAILURES_BEFORE_RESTART, ProductConfig.Service.ROUTE_FAILURES_BEFORE_RESTART)
        val THREAD_JOIN_TIMEOUT_MS: Long get() = long(Keys.SERVICE_THREAD_JOIN_TIMEOUT_MS, ProductConfig.Service.THREAD_JOIN_TIMEOUT_MS)
        val STATUS_PERIOD_MS: Int get() = int(Keys.SERVICE_STATUS_PERIOD_MS, ProductConfig.Service.STATUS_PERIOD_MS)
        val STATUS_KEY_SYNC_INTERVAL: Int get() = int(Keys.SERVICE_STATUS_KEY_SYNC_INTERVAL, ProductConfig.Service.STATUS_KEY_SYNC_INTERVAL)
        val STATUS_ERROR_LOG_EVERY: Int get() = int(Keys.SERVICE_STATUS_ERROR_LOG_EVERY, ProductConfig.Service.STATUS_ERROR_LOG_EVERY)
        val PROCESS_CRASH_RECOVERY_DELAY_MS: Long get() = long(Keys.SERVICE_PROCESS_CRASH_RECOVERY_DELAY_MS, ProductConfig.Service.PROCESS_CRASH_RECOVERY_DELAY_MS)
        val PROCESS_CRASH_WINDOW_MS: Long get() = long(Keys.SERVICE_PROCESS_CRASH_WINDOW_MS, ProductConfig.Service.PROCESS_CRASH_WINDOW_MS)
        val PROCESS_CRASH_MAX_IN_WINDOW: Int get() = int(Keys.SERVICE_PROCESS_CRASH_MAX_IN_WINDOW, ProductConfig.Service.PROCESS_CRASH_MAX_IN_WINDOW)
        val PROCESS_CRASH_SUPPRESS_MS: Long get() = long(Keys.SERVICE_PROCESS_CRASH_SUPPRESS_MS, ProductConfig.Service.PROCESS_CRASH_SUPPRESS_MS)
    }
}
