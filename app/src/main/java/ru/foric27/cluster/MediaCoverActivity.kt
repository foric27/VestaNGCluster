package ru.foric27.cluster

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
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
    private lateinit var titleScroll: HorizontalScrollView
    private lateinit var artistScroll: HorizontalScrollView
    private lateinit var playbackPosition: TextView
    private lateinit var playbackDuration: TextView
    private lateinit var playbackProgress: ProgressBar

    private var titleAnimator: ValueAnimator? = null
    private var artistAnimator: ValueAnimator? = null

    private var lastProgressTrackKey: String? = null
    private var lastProgressPositionMs: Long? = null
    private var lastProgressDurationMs: Long? = null

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId else windowManager.defaultDisplay.displayId
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
        titleScroll = findViewById(R.id.title_scroll)
        artistScroll = findViewById(R.id.artist_scroll)
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

        Timber.tag(TAG).i("MediaCoverActivity создан на display=%d", currentDisplayId)
    }

    override fun onResume() {
        super.onResume()
        val currentDisplayId = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId else windowManager.defaultDisplay.displayId
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).w("MediaCoverActivity возобновлена на основном дисплее (display=%d) — завершаю", currentDisplayId)
            finish()
            return
        }
    }

    private fun updateUI(track: MediaCoverState.TrackInfo?) {
        Timber.tag(TAG).i("updateUI: track=%s, hasContent=%b", track?.title, track?.hasContent)
        if (track == null || !track.hasContent) {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
            sourceLabel.text = getString(R.string.stream_mode_med)
            trackTitle.text = getString(R.string.media_cover_no_media_title)
            trackArtist.text = getString(R.string.media_cover_no_media_subtitle)
            startMarquee(titleScroll, getString(R.string.media_cover_no_media_title))
            startMarquee(artistScroll, getString(R.string.media_cover_no_media_subtitle))
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
        startMarquee(titleScroll, track.title ?: "")
        startMarquee(artistScroll, track.artist ?: "")
        updateProgress(track.progressKey(), track.positionMs, track.durationMs)
    }

    private fun startMarquee(scrollView: HorizontalScrollView, text: String) {
        scrollView.post {
            val child = scrollView.getChildAt(0) as? TextView ?: return@post
            val textWidth = child.paint.measureText(text).toInt()
            val viewWidth = scrollView.width

            // Отменяем предыдущий аниматор
            when (scrollView.id) {
                R.id.title_scroll -> titleAnimator?.cancel()
                R.id.artist_scroll -> artistAnimator?.cancel()
            }

            if (textWidth <= viewWidth) {
                // Текст влезает — центрируем
                val offset = (viewWidth - textWidth) / 2
                scrollView.scrollTo(-offset.coerceAtLeast(0), 0)
                return@post
            }

            // Запускаем бегущую строку по кругу (карусель)
            val maxScroll = textWidth - viewWidth + 32
            val scrollDuration = (maxScroll * 25).toLong() // ~40px/sec

            fun runCycle() {
                scrollView.scrollTo(0, 0)
                val animator = ValueAnimator.ofInt(0, maxScroll)
                animator.apply {
                    duration = scrollDuration
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { animation ->
                        scrollView.scrollTo(animation.animatedValue as Int, 0)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            // Пауза 1.5с перед следующим циклом
                            scrollView.postDelayed({ runCycle() }, 1500)
                        }
                    })
                    start()
                }
                when (scrollView.id) {
                    R.id.title_scroll -> titleAnimator = animator
                    R.id.artist_scroll -> artistAnimator = animator
                }
            }

            runCycle()
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

    override fun onDestroy() {
        super.onDestroy()
        titleAnimator?.cancel()
        artistAnimator?.cancel()
        titleScroll.removeCallbacks(null)
        artistScroll.removeCallbacks(null)
    }

    companion object {
        private const val TAG = "MediaCoverActivity"
    }
}
