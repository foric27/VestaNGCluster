package ru.foric27.cluster

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity
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
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_cover)

        coverImage = findViewById(R.id.cover_image)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)

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
            trackTitle.text = getString(R.string.stream_mode_med)
            trackArtist.text = ""
            return
        }

        if (track.coverBitmap != null) {
            Timber.tag(TAG).i("Устанавливаю обложку %dx%d", track.coverBitmap.width, track.coverBitmap.height)
            coverImage.setImageBitmap(track.coverBitmap)
        } else {
            coverImage.setImageResource(android.R.drawable.ic_media_play)
        }

        trackTitle.text = track.title ?: ""
        trackArtist.text = track.artist ?: ""
    }

    companion object {
        private const val TAG = "MediaCoverActivity"
    }
}
