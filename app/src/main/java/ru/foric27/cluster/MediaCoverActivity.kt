package ru.foric27.cluster

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.app.Activity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Activity для отображения обложки и метаданных текущего медиа-трека
 * на VirtualDisplay (кластер) в режиме MED (мультимедиа).
 *
 * Получает данные из [MediaCoverState], который обновляется
 * [MediaNotificationListenerService] при активной медиа-сессии.
 *
 * Использует [Activity] вместо AppCompatActivity, т.к. VirtualDisplay
 * не требует AppCompat темы и lifecycleScope.
 */
internal class MediaCoverActivity : Activity() {

    private lateinit var coverImage: ImageView
    private lateinit var sourceLabel: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var playbackPosition: TextView
    private lateinit var playbackDuration: TextView
    private lateinit var playbackProgress: ProgressBar

    private var lastProgressTrackKey: String? = null
    private var lastProgressPositionMs: Long? = null
    private var lastProgressDurationMs: Long? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_cover)

        coverImage = findViewById(R.id.cover_image)
        sourceLabel = findViewById(R.id.media_source_label)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        playbackPosition = findViewById(R.id.playback_position)
        playbackDuration = findViewById(R.id.playback_duration)
        playbackProgress = findViewById(R.id.playback_progress)

        // Учитываем черную маску снизу экрана (настройка video_black_bottom_px)
        val blackBottomPx = RuntimeConfig.Video.BLACK_BOTTOM_PX
        if (blackBottomPx > 0) {
            val root = findViewById<android.widget.FrameLayout>(R.id.media_cover_root)
            root.setPadding(0, 0, 0, blackBottomPx)
            Timber.tag(TAG).i("Установлен отступ снизу для черной маски: %d px", blackBottomPx)
        }

        GlobalScope.launch(Dispatchers.Main) {
            MediaCoverState.trackFlow.collect { track ->
                updateUI(track)
            }
        }

        Timber.tag(TAG).i("MediaCoverActivity создан")
    }

    private fun updateUI(track: MediaCoverState.TrackInfo?) {
        Timber.tag(TAG).i("updateUI: track=%s, hasContent=%b", track?.title, track?.hasContent)
        if (track == null || !track.hasContent) {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
            sourceLabel.text = getString(R.string.stream_mode_med)
            trackTitle.text = getString(R.string.media_cover_no_media_title)
            trackArtist.text = getString(R.string.media_cover_no_media_subtitle)
            updateProgress(null, null, null)
            return
        }

        if (track.coverBitmap != null) {
            val resized = resizeBitmap(track.coverBitmap, 960, 640)
            Timber.tag(TAG).i("Устанавливаю обложку %dx%d (ресайз с %dx%d)", resized.width, resized.height, track.coverBitmap.width, track.coverBitmap.height)
            coverImage.setImageBitmap(resized)
        } else {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
        }

        sourceLabel.text = track.sourceLabel?.takeIf { it.isNotBlank() } ?: getString(R.string.stream_mode_med)
        trackTitle.text = track.title ?: ""
        trackArtist.text = track.artist ?: ""
        updateProgress(track.progressKey(), track.positionMs, track.durationMs)
    }

    private fun updateProgress(trackKey: String?, positionMs: Long?, durationMs: Long?) {
        val restoredPosition = positionMs ?: lastProgressPositionMs.takeIf { trackKey != null && trackKey == lastProgressTrackKey }
        val restoredDuration = durationMs ?: lastProgressDurationMs.takeIf { trackKey != null && trackKey == lastProgressTrackKey }

        if (restoredPosition == null || restoredDuration == null || restoredDuration <= 0L) {
            playbackPosition.visibility = View.INVISIBLE
            playbackDuration.visibility = View.INVISIBLE
            playbackProgress.progress = 0
            return
        }

        lastProgressTrackKey = trackKey
        lastProgressPositionMs = restoredPosition
        lastProgressDurationMs = restoredDuration

        playbackPosition.visibility = View.VISIBLE
        playbackDuration.visibility = View.VISIBLE
        playbackPosition.text = formatTime(restoredPosition)
        playbackDuration.text = formatTime(restoredDuration)
        playbackProgress.progress = ((restoredPosition.coerceIn(0L, restoredDuration) * playbackProgress.max) / restoredDuration).toInt()
    }

    private fun MediaCoverState.TrackInfo.progressKey(): String {
        return listOf(sourceLabel.orEmpty(), title.orEmpty(), artist.orEmpty()).joinToString("|")
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun resizeBitmap(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxWidth && height <= maxHeight) return source

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    companion object {
        private const val TAG = "MediaCoverActivity"
    }
}
