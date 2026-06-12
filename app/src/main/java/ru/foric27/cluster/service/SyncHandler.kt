package ru.foric27.cluster.service
import ru.foric27.cluster.R
import ru.foric27.cluster.*
import ru.foric27.cluster.config.*

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.core.os.ConfigurationCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Формирование JSON-пакета синхронизации для приёмника кластера.
 *
 * Пакет всегда содержит `vid`, а `time` и `lang` отправляются периодически
 * или при явном изменении соответствующего состояния.
 *
 * @param context контекст приложения
 * @param keySyncInterval интервал периодической синхронизации ключевых полей
 */
internal class SyncHandler(
    private val context: Context,
    private val keySyncInterval: Int,
) {

    private var periodicSyncCount: Long = 0
    @Volatile private var timeChanged: Boolean = false
    @Volatile private var langChanged: Boolean = false

    /**
     * Представление времени для синхронизации в формате кластера.
     *
     * Формат: HHMMSS+HHMM (UTC time + timezone offset).
     *
     * @param utcInst момент времени в UTC
     * @param zone часовой пояс устройства
     */
    class SyncTime(
        private val utcInst: Instant,
        private val zone: TimeZone,
    ) {
        override fun toString(): String {
            val utc: ZonedDateTime = utcInst.atZone(ZoneOffset.UTC)
            val totalOffsetMs = zone.getOffset(utcInst.toEpochMilli())
            return String.format(
                Locale.US,
                "%02d%02d%02d%+03d%02d",
                utc.hour,
                utc.minute,
                utc.second,
                totalOffsetMs / 3_600_000,
                (abs(totalOffsetMs) / 60_000) % 60,
            )
        }
    }

    private class SyncData {
        var lang: String? = null
        var streamMode: String? = null
        var syncTime: SyncTime? = null

        override fun toString(): String {
            val payload = buildJsonObject {
                streamMode?.let { put("vid", it) }
                syncTime?.let { put("time", it.toString()) }
                lang?.let { put("lang", it) }
            }
            return Json.encodeToString(payload)
        }
    }

    /**
     * Помечает, что время или часовой пояс изменились.
     *
     * Следующий вызов [sync] включит `time` в пакет.
     */
    fun setTimeChanged() {
        timeChanged = true
    }

    /**
     * Помечает, что язык системы изменился.
     *
     * Следующий вызов [sync] включит `lang` в пакет.
     */
    fun setLangChanged() {
        langChanged = true
    }

    /**
     * Формирует JSON-пакет синхронизации.
     *
     * Всегда включает `vid`. `time` и `lang` добавляются периодически
     * или при флаге изменения.
     *
     * @return JSON-строка с данными синхронизации
     */
    fun sync(): String {
        val data = SyncData()
        data.streamMode = readStreamModeSafely()
        val periodic = (periodicSyncCount % keySyncInterval.toLong()) == 0L
        if (shouldIncludeTime(periodic, timeChanged)) {
            data.syncTime = SyncTime(Instant.now(), TimeZone.getDefault())
        }
        if (shouldIncludeLang(periodic, langChanged)) {
            data.lang = getLocalLang()
        }
        if (periodic || timeChanged || langChanged) {
            Timber.tag(TAG).v("Сформирован пакет sync: periodic=$periodic timeChanged=$timeChanged langChanged=$langChanged")
        }
        timeChanged = false
        langChanged = false
        periodicSyncCount++

        return data.toString()
    }

    private fun readStreamModeSafely(): String {
        return try {
            AppSettings.getSelectedClusterMode(context).streamModeValue
        } catch (t: Throwable) {
            if (!legacyStreamModeSettingLookupEnabled) {
                return ClusterMode.CLASSIC_NAV.streamModeValue
            }
            try {
                val raw = Settings.Global.getInt(context.contentResolver, STREAM_MODE_PARAM)
                try {
                    getStreamMode(raw)
                } catch (_: StreamModeException) {
                    if (!warnedBadStreamModeValue) {
                        warnedBadStreamModeValue = true
                        Timber.tag(TAG).w(context.getString(R.string.sync_invalid_stream_mode_value_fmt, STREAM_MODE_PARAM, raw))
                    }
                    ClusterMode.CLASSIC_NAV.streamModeValue
                }
            } catch (_: Settings.SettingNotFoundException) {
                legacyStreamModeSettingLookupEnabled = false
                if (!warnedMissingStreamModeSetting) {
                    warnedMissingStreamModeSetting = true
                    Timber.tag(TAG).w(context.getString(R.string.sync_missing_stream_mode_setting_fmt, STREAM_MODE_PARAM))
                }
                ClusterMode.CLASSIC_NAV.streamModeValue
            } catch (inner: Throwable) {
                if (!warnedMissingStreamModeSetting) {
                    warnedMissingStreamModeSetting = true
                    Timber.tag(TAG).w(inner, context.getString(R.string.sync_read_stream_mode_failed_fmt, STREAM_MODE_PARAM))
                }
                ClusterMode.CLASSIC_NAV.streamModeValue
            }
        }
    }

    @Throws(StreamModeException::class)
    private fun getStreamMode(v: Int): String {
        return try {
            ClusterMode.fromSetting(v).streamModeValue
        } catch (_: Throwable) {
            throw StreamModeException()
        }
    }

    /**
     * Исключение при получении неизвестного значения режима видеопотока.
     */
    class StreamModeException : Exception("Неизвестное значение режима видеопотока")

    companion object {
        private const val TAG = "SyncHandler"
        const val STREAM_MODE_PARAM: String = "android.extension.car.cluster.CLUSTER_VIDEO_STREAM_STATUS"
        private const val SUPPORTED_LANG_RU = "ru"
        private const val SUPPORTED_LANG_EN = "en"

        @Volatile
        private var warnedMissingStreamModeSetting: Boolean = false

        @Volatile
        private var warnedBadStreamModeValue: Boolean = false

        @Volatile
        private var legacyStreamModeSettingLookupEnabled: Boolean = true

        internal fun shouldIncludeTime(periodic: Boolean, timeChanged: Boolean): Boolean {
            return periodic || timeChanged
        }

        internal fun shouldIncludeLang(periodic: Boolean, langChanged: Boolean): Boolean {
            return periodic || langChanged
        }

        internal fun mapSupportedClusterLang(rawLanguage: String?): String {
            val normalized = rawLanguage?.trim()?.lowercase(Locale.US).orEmpty()
            return if (normalized == SUPPORTED_LANG_RU) {
                SUPPORTED_LANG_RU
            } else {
                SUPPORTED_LANG_EN
            }
        }

        private fun getLocalLang(): String {
            val locale = ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
            val rawLanguage = locale?.language ?: Locale.getDefault().language
            return mapSupportedClusterLang(rawLanguage)
        }
    }
}
