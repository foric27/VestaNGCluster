package ru.foric27.cluster.ui
import ru.foric27.cluster.util.*

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние текущего медиа-трека для отображения на кластере.
 *
 * Обновляется [MediaNotificationListenerService] при получении медиа-сессии,
 * читается [MediaCoverActivity] для отрисовки обложки и метаданных.
 */
internal object MediaCoverState {

    data class TrackInfo(
        val sourceLabel: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val coverBitmap: Bitmap?,
        val positionMs: Long?,
        val durationMs: Long?,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        val hasContent: Boolean
            get() = !title.isNullOrBlank() || coverBitmap != null
    }

    private val _trackFlow = MutableStateFlow<TrackInfo?>(null)
    val trackFlow: StateFlow<TrackInfo?> = _trackFlow.asStateFlow()

    fun update(
        sourceLabel: String?,
        title: String?,
        artist: String?,
        album: String?,
        coverBitmap: Bitmap?,
        positionMs: Long?,
        durationMs: Long?,
    ) {
        _trackFlow.value = TrackInfo(sourceLabel, title, artist, album, coverBitmap, positionMs, durationMs)
    }

    fun clear() {
        _trackFlow.value = null
    }

    val current: TrackInfo?
        get() = _trackFlow.value
}
