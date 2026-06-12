package ru.foric27.cluster

/**
 * Режим работы кластера.
 *
 * Определяет тип контента (навигация / медиа / abs) и визуальный стиль
 * цифровой комбинации приборов (CLASSIC / ADVANCED / MODERN / SPORT).
 * Преобразуется в настройки стрима и значение [android.provider.Settings.System].
 *
 * @property prefValue строковый ключ для SharedPreferences ("nav", "med", "abs")
 * @property settingValue числовое значение Settings.System для выбора стиля
 * @property streamModeValue тип стрима ("nav", "med", "abs")
 * @property labelResId строковый ресурс для отображения в UI
 */
internal enum class ClusterMode(
    val prefValue: String,
    val settingValue: Int,
    val streamModeValue: String,
    val labelResId: Int,
) {
    TRIP("abs", 0, "abs", R.string.stream_mode_abs),
    CLASSIC_NAV("nav", 1, "nav", R.string.stream_mode_nav),
    CLASSIC_MEDIA("med", 2, "med", R.string.stream_mode_med),
    ADVANCED_NAV("nav", 3, "nav", R.string.stream_mode_nav),
    ADVANCED_MEDIA("med", 4, "med", R.string.stream_mode_med),
    MODERN_NAV("nav", 5, "nav", R.string.stream_mode_nav),
    MODERN_MEDIA("med", 6, "med", R.string.stream_mode_med),
    SPORT_NAV("nav", 7, "nav", R.string.stream_mode_nav),
    SPORT_MEDIA("med", 8, "med", R.string.stream_mode_med),
    NOT_USED("abs", 9, "abs", R.string.stream_mode_abs),
    ;

    val isNavigation: Boolean
        get() = streamModeValue == "nav"

    val isMedia: Boolean
        get() = streamModeValue == "med"

    val isTrip: Boolean
        get() = streamModeValue == "abs"

    val isVideoStreamMode: Boolean
        get() = settingValue in 1..8

    companion object {
        fun fromPref(value: String?): ClusterMode {
            return when (value?.trim()?.lowercase()) {
                "med" -> CLASSIC_MEDIA
                "abs" -> TRIP
                else -> CLASSIC_NAV
            }
        }

        fun fromSetting(value: Int): ClusterMode {
            return entries.firstOrNull { it.settingValue == value } ?: CLASSIC_NAV
        }
    }
}
