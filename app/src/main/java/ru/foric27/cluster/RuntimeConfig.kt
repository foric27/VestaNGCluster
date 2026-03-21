package ru.foric27.cluster

import android.content.Context
import android.graphics.Rect

/**
 * Единая runtime-конфигурация приложения.
 *
 * Значения по умолчанию берутся из [ProductConfig], а локальные переопределения
 * сохраняются в SharedPreferences. Через этот объект приложение получает и
 * метаданные для developer-экрана, и уже нормализованные runtime-значения.
 */
object RuntimeConfig {

    private const val PREFS_NAME = "runtime_config_prefs"

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

    private object Keys {
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
        const val NETWORK_USE_ROOT_NET = "network_use_root_net"
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
        const val SERVICE_ROUTE_WAIT_TIMEOUT_MS = "service_route_wait_timeout_ms"
        const val SERVICE_ROUTE_WAIT_STEP_MS = "service_route_wait_step_ms"
        const val SERVICE_NO_ROUTE_RESTART_BACKOFF_MIN_MS = "service_no_route_restart_backoff_min_ms"
        const val SERVICE_IFACE_MISSING_RESTART_BACKOFF_MIN_MS = "service_iface_missing_restart_backoff_min_ms"
        const val SERVICE_NETWORK_LOST_RESTART_BACKOFF_MS = "service_network_lost_restart_backoff_ms"
        const val SERVICE_CONNECTIVITY_WATCHDOG_PERIOD_MS = "service_connectivity_watchdog_period_ms"
        const val SERVICE_ROUTE_RECENT_SEND_GRACE_MS = "service_route_recent_send_grace_ms"
        const val SERVICE_ROUTE_FAILURES_BEFORE_RESTART = "service_route_failures_before_restart"
        const val SERVICE_THREAD_JOIN_TIMEOUT_MS = "service_thread_join_timeout_ms"
        const val SERVICE_STATUS_PERIOD_MS = "service_status_period_ms"
        const val SERVICE_STATUS_KEY_SYNC_INTERVAL = "service_status_key_sync_interval"
        const val SERVICE_STATUS_ERROR_LOG_EVERY = "service_status_error_log_every"
    }

    private val fieldSpecs: List<FieldSpec> = listOf(
        FieldSpec(Keys.TARGET_ACTION_MAIN, R.string.runtime_section_target_app, R.string.runtime_field_target_action_main, ValueType.STRING, ProductConfig.TargetApp.ACTION_MAIN),
        FieldSpec(Keys.TARGET_CATEGORY_CLUSTER_NAVIGATION, R.string.runtime_section_target_app, R.string.runtime_field_target_category_cluster_navigation, ValueType.STRING, ProductConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION),
        FieldSpec(Keys.TARGET_PACKAGE_NAME, R.string.runtime_section_target_app, R.string.runtime_field_target_package_name, ValueType.STRING, ProductConfig.TargetApp.PACKAGE_NAME),
        FieldSpec(Keys.TARGET_CLUSTER_COMPONENT, R.string.runtime_section_target_app, R.string.runtime_field_target_cluster_component, ValueType.STRING, ProductConfig.TargetApp.CLUSTER_COMPONENT),

        FieldSpec(Keys.VISIBLE_LEFT, R.string.runtime_section_visible_area, R.string.runtime_field_visible_left, ValueType.INT, ProductConfig.VisibleArea.LEFT.toString()),
        FieldSpec(Keys.VISIBLE_TOP, R.string.runtime_section_visible_area, R.string.runtime_field_visible_top, ValueType.INT, ProductConfig.VisibleArea.TOP.toString()),
        FieldSpec(Keys.VISIBLE_RIGHT, R.string.runtime_section_visible_area, R.string.runtime_field_visible_right, ValueType.INT, ProductConfig.VisibleArea.RIGHT.toString()),
        FieldSpec(Keys.VISIBLE_BOTTOM, R.string.runtime_section_visible_area, R.string.runtime_field_visible_bottom, ValueType.INT, ProductConfig.VisibleArea.BOTTOM.toString()),

        FieldSpec(Keys.VIDEO_WIDTH, R.string.runtime_section_video, R.string.runtime_field_video_width, ValueType.INT, ProductConfig.Video.WIDTH.toString()),
        FieldSpec(Keys.VIDEO_HEIGHT, R.string.runtime_section_video, R.string.runtime_field_video_height, ValueType.INT, ProductConfig.Video.HEIGHT.toString()),
        FieldSpec(Keys.VIDEO_FPS_LIMIT, R.string.runtime_section_video, R.string.runtime_field_video_fps_limit, ValueType.INT, ProductConfig.Video.FPS_LIMIT.toString()),
        FieldSpec(Keys.VIDEO_DYNAMIC_FPS, R.string.runtime_section_video, R.string.runtime_field_video_dynamic_fps, ValueType.BOOLEAN, ProductConfig.Video.DYNAMIC_FPS.toString()),
        FieldSpec(Keys.VIDEO_DEFAULT_DPI, R.string.runtime_section_video, R.string.runtime_field_video_default_dpi, ValueType.INT, ProductConfig.Video.DEFAULT_DPI.toString()),
        FieldSpec(Keys.VIDEO_BITRATE, R.string.runtime_section_video, R.string.runtime_field_video_bitrate, ValueType.INT, ProductConfig.Video.BITRATE.toString()),
        FieldSpec(Keys.VIDEO_IFRAME_INTERVAL_SEC, R.string.runtime_section_video, R.string.runtime_field_video_iframe_interval_sec, ValueType.INT, ProductConfig.Video.IFRAME_INTERVAL_SEC.toString()),
        FieldSpec(Keys.VIDEO_BLACK_BOTTOM_PX, R.string.runtime_section_video, R.string.runtime_field_video_black_bottom_px, ValueType.INT, ProductConfig.Video.BLACK_BOTTOM_PX.toString()),

        FieldSpec(Keys.NETWORK_TARGET_IP, R.string.runtime_section_network, R.string.runtime_field_network_target_ip, ValueType.STRING, ProductConfig.Network.TARGET_IP),
        FieldSpec(Keys.NETWORK_VIDEO_PORT, R.string.runtime_section_network, R.string.runtime_field_network_video_port, ValueType.INT, ProductConfig.Network.VIDEO_PORT.toString()),
        FieldSpec(Keys.NETWORK_STATUS_PORT, R.string.runtime_section_network, R.string.runtime_field_network_status_port, ValueType.INT, ProductConfig.Network.STATUS_PORT.toString()),
        FieldSpec(Keys.NETWORK_USE_ROOT_NET, R.string.runtime_section_network, R.string.runtime_field_network_use_root_net, ValueType.BOOLEAN, ProductConfig.Network.USE_ROOT_NET.toString()),
        FieldSpec(Keys.NETWORK_LOCAL_CIDR, R.string.runtime_section_network, R.string.runtime_field_network_local_cidr, ValueType.STRING, ProductConfig.Network.LOCAL_CIDR),
        FieldSpec(Keys.NETWORK_GATEWAY, R.string.runtime_section_network, R.string.runtime_field_network_gateway, ValueType.STRING, ProductConfig.Network.GATEWAY),
        FieldSpec(Keys.NETWORK_BIND_IP, R.string.runtime_section_network, R.string.runtime_field_network_bind_ip, ValueType.STRING, ProductConfig.Network.BIND_IP),
        FieldSpec(Keys.NETWORK_UDP_MAX_PAYLOAD_BYTES, R.string.runtime_section_network, R.string.runtime_field_network_udp_max_payload_bytes, ValueType.INT, ProductConfig.Network.UDP_MAX_PAYLOAD_BYTES.toString()),
        FieldSpec(Keys.NETWORK_UDP_PACING_MAX_BPS, R.string.runtime_section_network, R.string.runtime_field_network_udp_pacing_max_bps, ValueType.INT, ProductConfig.Network.UDP_PACING_MAX_BPS.toString()),

        FieldSpec(Keys.ROOT_IFACE, R.string.runtime_section_root, R.string.runtime_field_root_iface, ValueType.STRING, ProductConfig.Root.IFACE),
        FieldSpec(Keys.ROOT_IFACE_CACHE_TTL_MS, R.string.runtime_section_root, R.string.runtime_field_root_iface_cache_ttl_ms, ValueType.LONG, ProductConfig.Root.IFACE_CACHE_TTL_MS.toString()),
        FieldSpec(Keys.ROOT_ROUTE_CACHE_TTL_MS, R.string.runtime_section_root, R.string.runtime_field_root_route_cache_ttl_ms, ValueType.LONG, ProductConfig.Root.ROUTE_CACHE_TTL_MS.toString()),
        FieldSpec(Keys.ROOT_SU_TIMEOUT_MS, R.string.runtime_section_root, R.string.runtime_field_root_su_timeout_ms, ValueType.LONG, ProductConfig.Root.SU_TIMEOUT_MS.toString()),

        FieldSpec(Keys.FTP_UPDATE_ZIP_NAME, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_update_zip_name, ValueType.STRING, ProductConfig.UpdateFtp.UPDATE_ZIP_NAME),
        FieldSpec(Keys.FTP_UPDATE_SIG_NAME, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_update_sig_name, ValueType.STRING, ProductConfig.UpdateFtp.UPDATE_SIG_NAME),
        FieldSpec(Keys.FTP_PORT, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_port, ValueType.INT, ProductConfig.UpdateFtp.PORT.toString()),
        FieldSpec(Keys.FTP_PASSIVE_PORT_START, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_passive_port_start, ValueType.INT, ProductConfig.UpdateFtp.PASSIVE_PORT_START.toString()),
        FieldSpec(Keys.FTP_PASSIVE_PORT_END, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_passive_port_end, ValueType.INT, ProductConfig.UpdateFtp.PASSIVE_PORT_END.toString()),
        FieldSpec(Keys.FTP_USER, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_user, ValueType.STRING, ProductConfig.UpdateFtp.USER),
        FieldSpec(Keys.FTP_PASSWORD, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_password, ValueType.STRING, ProductConfig.UpdateFtp.PASSWORD),
        FieldSpec(Keys.FTP_ROOT_DIR_NAME, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_root_dir_name, ValueType.STRING, ProductConfig.UpdateFtp.ROOT_DIR_NAME),
        FieldSpec(Keys.FTP_INTERNAL_POLL_PERIOD_MS, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_internal_poll_period_ms, ValueType.LONG, ProductConfig.UpdateFtp.INTERNAL_POLL_PERIOD_MS.toString()),
        FieldSpec(Keys.FTP_RETRY_DELAY_MS, R.string.runtime_section_ftp_update, R.string.runtime_field_ftp_retry_delay_ms, ValueType.LONG, ProductConfig.UpdateFtp.RETRY_DELAY_MS.toString()),

        FieldSpec(Keys.SERVICE_NOTIFICATION_ID, R.string.runtime_section_service, R.string.runtime_field_service_notification_id, ValueType.INT, ProductConfig.Service.NOTIFICATION_ID.toString()),
        FieldSpec(Keys.SERVICE_NOTIFICATION_CHANNEL_ID, R.string.runtime_section_service, R.string.runtime_field_service_notification_channel_id, ValueType.STRING, ProductConfig.Service.NOTIFICATION_CHANNEL_ID),
        FieldSpec(Keys.SERVICE_NOTIFICATION_CHANNEL_NAME, R.string.runtime_section_service, R.string.runtime_field_service_notification_channel_name, ValueType.STRING, ProductConfig.Service.NOTIFICATION_CHANNEL_NAME),
        FieldSpec(Keys.SERVICE_RESTART_BACKOFF_START_MS, R.string.runtime_section_service, R.string.runtime_field_service_restart_backoff_start_ms, ValueType.LONG, ProductConfig.Service.RESTART_BACKOFF_START_MS.toString()),
        FieldSpec(Keys.SERVICE_RESTART_BACKOFF_MAX_MS, R.string.runtime_section_service, R.string.runtime_field_service_restart_backoff_max_ms, ValueType.LONG, ProductConfig.Service.RESTART_BACKOFF_MAX_MS.toString()),
        FieldSpec(Keys.SERVICE_RECOVERY_DELAY_MS, R.string.runtime_section_service, R.string.runtime_field_service_recovery_delay_ms, ValueType.LONG, ProductConfig.Service.SERVICE_RECOVERY_DELAY_MS.toString()),
        FieldSpec(Keys.SERVICE_TASK_REMOVED_RECOVERY_DELAY_MS, R.string.runtime_section_service, R.string.runtime_field_service_task_removed_recovery_delay_ms, ValueType.LONG, ProductConfig.Service.TASK_REMOVED_RECOVERY_DELAY_MS.toString()),
        FieldSpec(Keys.SERVICE_RECOVERY_REQUEST_CODE, R.string.runtime_section_service, R.string.runtime_field_service_recovery_request_code, ValueType.INT, ProductConfig.Service.SERVICE_RECOVERY_REQUEST_CODE.toString()),
        FieldSpec(Keys.SERVICE_RESTART_REQUEST_DEBOUNCE_MS, R.string.runtime_section_service, R.string.runtime_field_service_restart_request_debounce_ms, ValueType.LONG, ProductConfig.Service.RESTART_REQUEST_DEBOUNCE_MS.toString()),
        FieldSpec(Keys.SERVICE_ROUTE_WAIT_TIMEOUT_MS, R.string.runtime_section_service, R.string.runtime_field_service_route_wait_timeout_ms, ValueType.LONG, ProductConfig.Service.ROUTE_WAIT_TIMEOUT_MS.toString()),
        FieldSpec(Keys.SERVICE_ROUTE_WAIT_STEP_MS, R.string.runtime_section_service, R.string.runtime_field_service_route_wait_step_ms, ValueType.LONG, ProductConfig.Service.ROUTE_WAIT_STEP_MS.toString()),
        FieldSpec(Keys.SERVICE_NO_ROUTE_RESTART_BACKOFF_MIN_MS, R.string.runtime_section_service, R.string.runtime_field_service_no_route_restart_backoff_min_ms, ValueType.LONG, ProductConfig.Service.NO_ROUTE_RESTART_BACKOFF_MIN_MS.toString()),
        FieldSpec(Keys.SERVICE_IFACE_MISSING_RESTART_BACKOFF_MIN_MS, R.string.runtime_section_service, R.string.runtime_field_service_iface_missing_restart_backoff_min_ms, ValueType.LONG, ProductConfig.Service.IFACE_MISSING_RESTART_BACKOFF_MIN_MS.toString()),
        FieldSpec(Keys.SERVICE_NETWORK_LOST_RESTART_BACKOFF_MS, R.string.runtime_section_service, R.string.runtime_field_service_network_lost_restart_backoff_ms, ValueType.LONG, ProductConfig.Service.NETWORK_LOST_RESTART_BACKOFF_MS.toString()),
        FieldSpec(Keys.SERVICE_CONNECTIVITY_WATCHDOG_PERIOD_MS, R.string.runtime_section_service, R.string.runtime_field_service_connectivity_watchdog_period_ms, ValueType.LONG, ProductConfig.Service.CONNECTIVITY_WATCHDOG_PERIOD_MS.toString()),
        FieldSpec(Keys.SERVICE_ROUTE_RECENT_SEND_GRACE_MS, R.string.runtime_section_service, R.string.runtime_field_service_route_recent_send_grace_ms, ValueType.LONG, ProductConfig.Service.ROUTE_RECENT_SEND_GRACE_MS.toString()),
        FieldSpec(Keys.SERVICE_ROUTE_FAILURES_BEFORE_RESTART, R.string.runtime_section_service, R.string.runtime_field_service_route_failures_before_restart, ValueType.INT, ProductConfig.Service.ROUTE_FAILURES_BEFORE_RESTART.toString()),
        FieldSpec(Keys.SERVICE_THREAD_JOIN_TIMEOUT_MS, R.string.runtime_section_service, R.string.runtime_field_service_thread_join_timeout_ms, ValueType.LONG, ProductConfig.Service.THREAD_JOIN_TIMEOUT_MS.toString()),
        FieldSpec(Keys.SERVICE_STATUS_PERIOD_MS, R.string.runtime_section_service, R.string.runtime_field_service_status_period_ms, ValueType.INT, ProductConfig.Service.STATUS_PERIOD_MS.toString()),
        FieldSpec(Keys.SERVICE_STATUS_KEY_SYNC_INTERVAL, R.string.runtime_section_service, R.string.runtime_field_service_status_key_sync_interval, ValueType.INT, ProductConfig.Service.STATUS_KEY_SYNC_INTERVAL.toString()),
        FieldSpec(Keys.SERVICE_STATUS_ERROR_LOG_EVERY, R.string.runtime_section_service, R.string.runtime_field_service_status_error_log_every, ValueType.INT, ProductConfig.Service.STATUS_ERROR_LOG_EVERY.toString()),
    )

    @Volatile
    private var rawValues: Map<String, String> = emptyMap()

    /**
     * Инициализирует кеш runtime-значений при старте процесса или экрана.
     */
    fun init(context: Context) {
        reload(context)
    }

    /**
     * Полностью перечитывает пользовательские overrides из SharedPreferences.
     */
    fun reload(context: Context) {
        val prefs = getPrefs(context)
        rawValues = buildMap {
            fieldSpecs.forEach { spec ->
                if (prefs.contains(spec.key)) {
                    put(spec.key, prefs.getString(spec.key, spec.defaultValue) ?: spec.defaultValue)
                }
            }
        }
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
        val normalizedValue = normalizeFieldValue(spec, rawValue) ?: return when (spec.type) {
            ValueType.INT -> SaveResult(false, context.getString(R.string.runtime_error_int_field_fmt, fieldTitle), spec.key)
            ValueType.LONG -> SaveResult(false, context.getString(R.string.runtime_error_long_field_fmt, fieldTitle), spec.key)
            ValueType.BOOLEAN -> SaveResult(false, context.getString(R.string.runtime_error_boolean_field_fmt, fieldTitle), spec.key)
            ValueType.STRING -> SaveResult(false, context.getString(R.string.runtime_error_string_field_fmt, fieldTitle), spec.key)
        }

        val prefs = getPrefs(context)
        val editor = prefs.edit()
        editor.putString(spec.key, normalizedValue)
        val ok = editor.commit()
        if (!ok) {
            return SaveResult(false, context.getString(R.string.runtime_error_save_setting_fmt, fieldTitle), spec.key)
        }
        reload(context)
        return SaveResult(true, context.getString(R.string.runtime_save_setting_ok_fmt, fieldTitle), spec.key, normalizedValue)
    }

    /**
     * Сбрасывает все runtime-overrides и возвращает проект к ProductConfig.
     */
    fun resetToDefaults(context: Context): SaveResult {
        val ok = getPrefs(context).edit().clear().commit()
        if (!ok) {
            return SaveResult(false, context.getString(R.string.runtime_error_reset_defaults))
        }
        reload(context)
        return SaveResult(true, context.getString(R.string.runtime_reset_defaults_ok))
    }


    private fun normalizeFieldValue(spec: FieldSpec, rawValue: String): String? {
        val input = rawValue.trim()
        return when (spec.type) {
            ValueType.STRING -> input
            ValueType.INT -> {
                val source = input.ifBlank { spec.defaultValue }
                source.toIntOrNull()?.toString()
            }

            ValueType.LONG -> {
                val source = input.ifBlank { spec.defaultValue }
                source.toLongOrNull()?.toString()
            }

            ValueType.BOOLEAN -> normalizeBooleanString(input, spec)
        }
    }

    private fun normalizeBooleanString(raw: String, spec: FieldSpec): String? {
        val source = raw.ifBlank { spec.defaultValue }.trim().lowercase()
        return when (source) {
            "true", "1", "yes", "on" -> "true"
            "false", "0", "no", "off" -> "false"
            else -> null
        }
    }

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun string(key: String, default: String): String = rawValues[key] ?: default

    private fun int(key: String, default: Int): Int = rawValues[key]?.toIntOrNull() ?: default

    private fun long(key: String, default: Long): Long = rawValues[key]?.toLongOrNull() ?: default

    private fun boolean(key: String, default: Boolean): Boolean {
        return parseBooleanValue(rawValues[key], default)
    }

    /**
     * Разбирает булево значение с учётом старых человекочитаемых форматов вроде
     * yes/no и on/off, которые могут остаться в пользовательских prefs.
     */
    internal fun parseBooleanValue(rawValue: String?, default: Boolean): Boolean {
        return when ((rawValue ?: default.toString()).trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
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
        val USE_ROOT_NET: Boolean get() = boolean(Keys.NETWORK_USE_ROOT_NET, ProductConfig.Network.USE_ROOT_NET)
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
        val ROUTE_WAIT_TIMEOUT_MS: Long get() = long(Keys.SERVICE_ROUTE_WAIT_TIMEOUT_MS, ProductConfig.Service.ROUTE_WAIT_TIMEOUT_MS)
        val ROUTE_WAIT_STEP_MS: Long get() = long(Keys.SERVICE_ROUTE_WAIT_STEP_MS, ProductConfig.Service.ROUTE_WAIT_STEP_MS)
        val NO_ROUTE_RESTART_BACKOFF_MIN_MS: Long get() = long(Keys.SERVICE_NO_ROUTE_RESTART_BACKOFF_MIN_MS, ProductConfig.Service.NO_ROUTE_RESTART_BACKOFF_MIN_MS)
        val IFACE_MISSING_RESTART_BACKOFF_MIN_MS: Long get() = long(Keys.SERVICE_IFACE_MISSING_RESTART_BACKOFF_MIN_MS, ProductConfig.Service.IFACE_MISSING_RESTART_BACKOFF_MIN_MS)
        val NETWORK_LOST_RESTART_BACKOFF_MS: Long get() = long(Keys.SERVICE_NETWORK_LOST_RESTART_BACKOFF_MS, ProductConfig.Service.NETWORK_LOST_RESTART_BACKOFF_MS)
        val CONNECTIVITY_WATCHDOG_PERIOD_MS: Long get() = long(Keys.SERVICE_CONNECTIVITY_WATCHDOG_PERIOD_MS, ProductConfig.Service.CONNECTIVITY_WATCHDOG_PERIOD_MS)
        val ROUTE_RECENT_SEND_GRACE_MS: Long get() = long(Keys.SERVICE_ROUTE_RECENT_SEND_GRACE_MS, ProductConfig.Service.ROUTE_RECENT_SEND_GRACE_MS)
        val ROUTE_FAILURES_BEFORE_RESTART: Int get() = int(Keys.SERVICE_ROUTE_FAILURES_BEFORE_RESTART, ProductConfig.Service.ROUTE_FAILURES_BEFORE_RESTART)
        val THREAD_JOIN_TIMEOUT_MS: Long get() = long(Keys.SERVICE_THREAD_JOIN_TIMEOUT_MS, ProductConfig.Service.THREAD_JOIN_TIMEOUT_MS)
        val STATUS_PERIOD_MS: Int get() = int(Keys.SERVICE_STATUS_PERIOD_MS, ProductConfig.Service.STATUS_PERIOD_MS)
        val STATUS_KEY_SYNC_INTERVAL: Int get() = int(Keys.SERVICE_STATUS_KEY_SYNC_INTERVAL, ProductConfig.Service.STATUS_KEY_SYNC_INTERVAL)
        val STATUS_ERROR_LOG_EVERY: Int get() = int(Keys.SERVICE_STATUS_ERROR_LOG_EVERY, ProductConfig.Service.STATUS_ERROR_LOG_EVERY)
    }
}
