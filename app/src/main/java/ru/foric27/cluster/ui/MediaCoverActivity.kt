package ru.foric27.cluster.ui
import ru.foric27.cluster.R
import ru.foric27.cluster.config.*
import ru.foric27.cluster.util.*
import ru.foric27.cluster.ui.theme.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import timber.log.Timber

/**
 * Activity для отображения обложки и метаданных текущего медиа-трека
 * на VirtualDisplay (кластер) в режиме MED (мультимедиа).
 */
internal class MediaCoverActivity : ComponentActivity() {

    private var finishReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = try {
            if (Build.VERSION.SDK_INT >= 30) display?.displayId
            else windowManager.defaultDisplay.displayId
        } catch (_: Throwable) { null } ?: 0

        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).w("MediaCoverActivity запущена на основном дисплее — завершаю")
            finish()
            return
        }

        registerFinishReceiver()

        setContent {
            ClusterTheme {
                MediaCoverScreen()
            }
        }

        Timber.tag(TAG).i("MediaCoverActivity создан на display=%d", currentDisplayId)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        val currentDisplayId = try {
            if (Build.VERSION.SDK_INT >= 30) display?.displayId
            else windowManager.defaultDisplay.displayId
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    private fun registerFinishReceiver() {
        finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_FINISH_MEDIA_COVER) finish()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_MEDIA_COVER), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_MEDIA_COVER))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishReceiver?.let { runCatching { unregisterReceiver(it) } }
    }

    companion object {
        private const val TAG = "MediaCoverActivity"
        const val ACTION_FINISH_MEDIA_COVER = "ru.foric27.cluster.action.FINISH_MEDIA_COVER"
    }
}

@Composable
private fun MediaCoverScreen() {
    val track by MediaCoverState.trackFlow.collectAsState()
    val context = LocalContext.current

    val visibleArea = remember { RuntimeConfig.VisibleArea.rect() }
    val accentColor = colorResource(R.color.oem_cluster_accent)

    val trackTitle = track?.title?.takeIf { it.isNotBlank() } ?: ""
    val artist = track?.artist?.takeIf { it.isNotBlank() } ?: ""
    val sourceLabel = track?.sourceLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.stream_mode_med)
    val coverBitmap = track?.coverBitmap
    val positionMs = track?.positionMs?.takeIf { it > 0L }
    val durationMs = track?.durationMs?.takeIf { it > 0L }

    val dominantColor = remember(coverBitmap) {
        if (coverBitmap != null) {
            extractDominantColor(coverBitmap, context.getColor(R.color.oem_cluster_accent))
        } else {
            context.getColor(R.color.oem_cluster_accent)
        }
    }

    Box(
        modifier = Modifier
            .offset(x = visibleArea.left.dp, y = visibleArea.top.dp)
            .width(visibleArea.width().dp)
            .height(visibleArea.height().dp)
            .background(Color(android.graphics.Color.parseColor("#050505"))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = sourceLabel,
                color = OemClusterSubtitle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            if (coverBitmap != null) {
                val resized = remember(coverBitmap) { resizeBitmap(coverBitmap, 960, 640) }
                Image(
                    bitmap = resized.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1e1d1d)),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = trackTitle.ifEmpty { stringResource(R.string.media_cover_no_media_title) },
                color = OemClusterTitle,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = artist.ifEmpty { stringResource(R.string.media_cover_no_media_subtitle) },
                color = OemClusterSubtitle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = formatTime(positionMs ?: 0L),
                    color = OemClusterTitle,
                    fontSize = 12.sp,
                    modifier = Modifier.width(40.dp),
                )
                LinearProgressIndicator(
                    progress = {
                        if (durationMs != null && durationMs > 0L && positionMs != null) {
                            (positionMs.coerceIn(0L, durationMs).toFloat() / durationMs)
                        } else 0f
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(4.dp),
                    color = Color(dominantColor),
                    trackColor = OemClusterSeekbarBg,
                )
                Text(
                    text = formatTime(durationMs ?: 0L),
                    color = OemClusterTitle,
                    fontSize = 12.sp,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun resizeBitmap(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width <= maxWidth && height <= maxHeight) return source
    val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return Bitmap.createScaledBitmap(source, (width * ratio).toInt(), (height * ratio).toInt(), true)
}

private fun extractDominantColor(bitmap: Bitmap, fallback: Int): Int {
    return try {
        Palette.from(bitmap).generate().getDominantColor(fallback)
    } catch (_: Exception) {
        fallback
    }
}
