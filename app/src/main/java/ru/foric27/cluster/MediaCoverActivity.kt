package ru.foric27.cluster

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Activity для отображения обложки и метаданных текущего медиа-трека
 * на VirtualDisplay (кластер) в режиме MED (мультимедиа).
 *
 * Получает данные из [MediaCoverState], который обновляется
 * [MediaNotificationListenerService] при активной медиа-сессии.
 *
 * Использует [ComponentActivity] вместо AppCompatActivity, т.к. VirtualDisplay
 * не требует AppCompat темы, но поток состояния должен жить в lifecycleScope.
 */
internal class MediaCoverActivity : ComponentActivity() {

    private lateinit var coverImage: ImageView
    private lateinit var sourceLabel: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var playbackPosition: TextView
    private lateinit var playbackDuration: TextView
    private lateinit var playbackProgress: AppCompatSeekBar
    private lateinit var mediaLowerGroup: View

    private var finishReceiver: BroadcastReceiver? = null

    private var lastProgressTrackKey: String? = null
    private var lastProgressPositionMs: Long? = null
    private var lastProgressDurationMs: Long? = null

    private var titleAnimator: ValueAnimator? = null
    private var artistAnimator: ValueAnimator? = null
    private var trackStateJob: Job? = null
    private lateinit var titleScroll: HorizontalScrollView
    private lateinit var artistScroll: HorizontalScrollView

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

        configureMediaVisibleArea()

        coverImage = findViewById(R.id.cover_image)
        sourceLabel = findViewById(R.id.media_source_label)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        playbackPosition = findViewById(R.id.playback_position)
        playbackDuration = findViewById(R.id.playback_duration)
        playbackProgress = findViewById(R.id.playback_progress)
        mediaLowerGroup = findViewById(R.id.media_lower_group)

        titleScroll = findViewById(R.id.title_scroll)
        artistScroll = findViewById(R.id.artist_scroll)

        // Учитываем черную маску снизу экрана (настройка video_black_bottom_px)
        val blackBottomPx = RuntimeConfig.Video.BLACK_BOTTOM_PX
        if (blackBottomPx > 0) {
            val root = findViewById<android.widget.FrameLayout>(R.id.media_cover_root)
            root.setPadding(0, 0, 0, blackBottomPx)
            mediaLowerGroup.translationY = -blackBottomPx.toFloat()
            Timber.tag(TAG).i("Установлен отступ снизу для черной маски: %d px", blackBottomPx)
        }

        registerFinishReceiver()

        trackStateJob = lifecycleScope.launch(Dispatchers.Main) {
            MediaCoverState.trackFlow.collect { track ->
                updateUI(track)
            }
        }

        Timber.tag(TAG).i("MediaCoverActivity создан на display=%d", currentDisplayId)
    }

    private fun configureMediaVisibleArea() {
        val visibleArea = RuntimeConfig.VisibleArea.rect()
        val mediaVisibleArea = findViewById<FrameLayout>(R.id.media_visible_area)
        mediaVisibleArea.layoutParams = FrameLayout.LayoutParams(
            visibleArea.width(),
            visibleArea.height(),
        ).apply {
            leftMargin = visibleArea.left
            topMargin = visibleArea.top
        }
        Timber.tag(TAG).i("MED UI вписан в visibleArea=%s", RuntimeConfig.VisibleArea.SHORT)
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
            setupCarousel(getString(R.string.media_cover_no_media_title))
            setupArtistScroll(getString(R.string.media_cover_no_media_subtitle))
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
        setupArtistScroll(track.artist ?: "")
        updateProgress(track.progressKey(), track.positionMs, track.durationMs)
    }

    private fun setupCarousel(text: String) {
        setupCylindricalTextScroll(titleScroll, trackTitle, text, titleAnimator) { titleAnimator = it }
    }

    private fun setupArtistScroll(text: String) {
        setupCylindricalTextScroll(artistScroll, trackArtist, text, artistAnimator) { artistAnimator = it }
    }

    private fun setupCylindricalTextScroll(
        container: HorizontalScrollView,
        textView: TextView,
        text: String,
        currentAnimator: ValueAnimator?,
        assignAnimator: (ValueAnimator?) -> Unit,
    ) {
        currentAnimator?.cancel()
        assignAnimator(null)
        container.scrollTo(0, 0)
        textView.translationX = 0f
        textView.text = text

        container.post {
            if (textView.text.toString() != text) return@post
            val viewWidth = container.width
            if (viewWidth <= 0) return@post

            textView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val textWidth = textView.measuredWidth
            if (textWidth <= viewWidth) {
                // Текст помещается — центрируем без скролла
                textView.layoutParams = textView.layoutParams.apply { width = viewWidth }
                textView.gravity = Gravity.CENTER
                textView.translationX = 0f
                textView.requestLayout()
                return@post
            }

            // Текст не помещается — WRAP_CONTENT и скроллим через scrollTo()
            textView.layoutParams = textView.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            textView.gravity = Gravity.CENTER
            textView.requestLayout()
            // Скроллим от textWidth (полностью справа) до 0 (полностью слева)
            // Container.clipChildren=false и clipToPadding=false в родителе позволяют видеть
            // текст на всём пути без обрезки
            val scrollFrom = textWidth
            val scrollTo = 0
            val duration = ((textWidth + viewWidth) * TEXT_SCROLL_DURATION_PER_PX_MS).coerceIn(
                TEXT_SCROLL_MIN_DURATION_MS,
                TEXT_SCROLL_MAX_DURATION_MS,
            )

            container.scrollTo(scrollFrom, 0)
            val animator = ValueAnimator.ofInt(scrollFrom, scrollTo).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { animator ->
                    container.scrollTo(animator.animatedValue as Int, 0)
                }
            }
            assignAnimator(animator)
            animator.start()
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
        trackStateJob?.cancel()
        trackStateJob = null
        titleAnimator?.cancel()
        artistAnimator?.cancel()
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
        private const val TEXT_SCROLL_DURATION_PER_PX_MS = 18L
        private const val TEXT_SCROLL_MIN_DURATION_MS = 4_000L
        private const val TEXT_SCROLL_MAX_DURATION_MS = 24_000L
        const val ACTION_FINISH_MEDIA_COVER = "ru.foric27.cluster.action.FINISH_MEDIA_COVER"
    }
}
