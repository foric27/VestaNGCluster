package ru.foric27.cluster

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.core.os.ConfigurationCompat
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
 */
internal class SyncHandler(
    private val context: Context,
    private val keySyncInterval: Int,
) {

    private var periodicSyncCount: Long = 0
    @Volatile private var timeChanged: Boolean = false
    @Volatile private var langChanged: Boolean = false

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
            val json = SimpleJsonContainer()
            streamMode?.let { json.addValue("vid", it) }
            syncTime?.let { json.addValue("time", it.toString()) }
            lang?.let { json.addValue("lang", it) }
            return json.toString()
        }
    }

    fun setTimeChanged() {
        timeChanged = true
    }

    fun setLangChanged() {
        langChanged = true
    }

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
