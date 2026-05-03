package ru.foric27.cluster

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.palette.graphics.Palette
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
    private lateinit var playbackProgress: AppCompatSeekBar

    private var finishReceiver: BroadcastReceiver? = null

    private var lastProgressTrackKey: String? = null
    private var lastProgressPositionMs: Long? = null
    private var lastProgressDurationMs: Long? = null

    private var titleAnimator: ValueAnimator? = null
    private lateinit var titleScroll: HorizontalScrollView

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = try {
            if (Build.VERSION.SDK_INT >= 30) {
                display?.displayId
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.displayId
            }
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).w("MediaCoverActivity запущена на основном дисплее (display=%d) — завершаю", currentDisplayId)
            finish()
            return
        }

        setContentView(R.layout.activity_media_cover)

        coverImage = findViewById(R.id.cover_image)
        sourceLabel = findViewById(R.id.media_source_label)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        playbackPosition = findViewById(R.id.playback_position)
        playbackDuration = findViewById(R.id.playback_duration)
        playbackProgress = findViewById(R.id.playback_progress)

        titleScroll = findViewById(R.id.title_scroll)
        trackTitle.isSelected = true
        trackArtist.isSelected = true

        // Учитываем черную маску снизу экрана (настройка video_black_bottom_px)
        val blackBottomPx = RuntimeConfig.Video.BLACK_BOTTOM_PX
        if (blackBottomPx > 0) {
            val root = findViewById<android.widget.FrameLayout>(R.id.media_cover_root)
            root.setPadding(0, 0, 0, blackBottomPx)
            Timber.tag(TAG).i("Установлен отступ снизу для черной маски: %d px", blackBottomPx)
        }

        registerFinishReceiver()

        GlobalScope.launch(Dispatchers.Main) {
            MediaCoverState.trackFlow.collect { track ->
                updateUI(track)
            }
        }

        Timber.tag(TAG).i("MediaCoverActivity создан на display=%d", currentDisplayId)
    }

    override fun onResume() {
        super.onResume()
        val currentDisplayId = try {
            if (Build.VERSION.SDK_INT >= 30) {
                display?.displayId
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.displayId
            }
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).w("MediaCoverActivity возобновлена на основном дисплее (display=%d) — завершаю", currentDisplayId)
            finish()
            return
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.tag(TAG).i("MediaCoverActivity onPause — завершаю")
        finish()
    }

    private fun updateUI(track: MediaCoverState.TrackInfo?) {
        Timber.tag(TAG).i("updateUI: track=%s, hasContent=%b", track?.title, track?.hasContent)
        if (track == null || !track.hasContent) {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
            sourceLabel.text = getString(R.string.stream_mode_med)
            trackTitle.text = getString(R.string.media_cover_no_media_title)
            trackArtist.text = getString(R.string.media_cover_no_media_subtitle)
            applySeekbarColor(getColor(R.color.oem_cluster_accent))
            updateProgress(null, null, null)
            return
        }

        if (track.coverBitmap != null) {
            val dominantColor = extractDominantColor(track.coverBitmap)
            val resized = resizeBitmap(track.coverBitmap, 960, 640)
            Timber.tag(TAG).i("Устанавливаю обложку %dx%d (ресайз с %dx%d)", resized.width, resized.height, track.coverBitmap.width, track.coverBitmap.height)
            coverImage.setImageBitmap(resized)
            applySeekbarColor(dominantColor)
        } else {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
            applySeekbarColor(getColor(R.color.oem_cluster_accent))
        }

        sourceLabel.text = track.sourceLabel?.takeIf { it.isNotBlank() } ?: getString(R.string.stream_mode_med)
        setupCarousel(track.title ?: "")
        trackArtist.text = track.artist ?: ""
        updateProgress(track.progressKey(), track.positionMs, track.durationMs)
    }

    private fun setupCarousel(text: String) {
        titleAnimator?.cancel()
        trackTitle.text = text

        titleScroll.post {
            val child = titleScroll.getChildAt(0)
            val scrollWidth = child?.width ?: 0
            val viewWidth = titleScroll.width

            if (scrollWidth > viewWidth && viewWidth > 0) {
                val scrollDistance = scrollWidth - viewWidth
                val durationPerPx = 30L
                val duration = (scrollDistance * durationPerPx).coerceIn(2000L, 20000L)

                titleAnimator = ValueAnimator.ofInt(0, scrollDistance).apply {
                    this.duration = duration
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { animator ->
                        titleScroll.scrollTo(animator.animatedValue as Int, 0)
                    }
                    start()
                }
            } else {
                titleScroll.scrollTo(0, 0)
            }
        }
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

    private fun extractDominantColor(bitmap: Bitmap): Int {
        return try {
            val palette = Palette.from(bitmap).generate()
            palette.getDominantColor(getColor(R.color.oem_cluster_accent))
        } catch (e: Exception) {
            getColor(R.color.oem_cluster_accent)
        }
    }

    private fun applySeekbarColor(color: Int) {
        val layerDrawable = playbackProgress.progressDrawable.mutate() as android.graphics.drawable.LayerDrawable
        val progressDrawable = layerDrawable.findDrawableByLayerId(android.R.id.progress)
        progressDrawable?.let {
            val mutated = it.mutate()
            DrawableCompat.setTint(mutated, color)
        }
        playbackProgress.progressDrawable = layerDrawable
    }

    private fun registerFinishReceiver() {
        finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_FINISH_MEDIA_COVER) {
                    Timber.tag(TAG).i("Получен broadcast finish — завершаю MediaCoverActivity")
                    finish()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_MEDIA_COVER), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_MEDIA_COVER))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        titleAnimator?.cancel()
        finishReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver уже разрегистрирован
            }
        }
    }

    companion object {
        private const val TAG = "MediaCoverActivity"
        const val ACTION_FINISH_MEDIA_COVER = "ru.foric27.cluster.action.FINISH_MEDIA_COVER"
    }
}
