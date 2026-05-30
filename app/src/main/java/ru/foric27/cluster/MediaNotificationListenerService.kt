package ru.foric27.cluster

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Быстрый универсальный захват медиа-обложек из уведомлений и MediaSession.
 *
 * Первый контур обновляет UI сразу из media-уведомления: title/text/largeIcon
 * обычно приходят быстрее, чем callback metadata. Второй контур держит
 * MediaController для любых приложений и уточняет данные через MediaSession.
 */
internal class MediaNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableMapOf<String, ControllerHolder>()

    private var mediaSessionManager: MediaSessionManager? = null
    private var activePackage: String? = null
    private var notificationScanJob: Job? = null
    private var lastPublishedKey: String? = null
    private var lastPublishedSnapshot: TrackSnapshot? = null
    private var mediaSessionAccessDeniedLogged = false
    private var mediaControllerStateAccessDeniedLogged = false

    // Debounce: подавляем повторяющиеся события в течение 300 мс
    private var lastPublishTimeMs: Long = 0L
    private var pendingPublishSnapshot: TrackSnapshot? = null
    private var pendingPublishSource: String = ""
    private var publishDebounceJob: Job? = null

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { activeControllers ->
            activeControllers.orEmpty().forEach { controller ->
                attachToMediaController(controller)
            }
            promoteBestController(activeControllers.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.tag(TAG).i("NotificationListener подключён")

        val manager = getSystemService(MediaSessionManager::class.java)
        mediaSessionManager = manager
        try {
            manager?.addOnActiveSessionsChangedListener(activeSessionsChangedListener, null)
            manager?.getActiveSessions(null).orEmpty().forEach { controller ->
                attachToMediaController(controller)
            }
        } catch (e: SecurityException) {
            if (!mediaSessionAccessDeniedLogged) {
                mediaSessionAccessDeniedLogged = true
                Timber.tag(TAG).i(e, "Нет доступа к MediaSession, продолжаю работу только по медиа-уведомлениям")
            } else {
                Timber.tag(TAG).i("Нет доступа к MediaSession, продолжаю работу только по медиа-уведомлениям")
            }
        }

        processActiveNotifications()
        startNotificationScan()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMediaNotification(sbn)) return

        val snapshot = buildSnapshotFromNotification(sbn)
        if (snapshot.hasContent) {
            publishSnapshot(snapshot, source = "notification")
        }
        extractMediaSessionToken(sbn.notification)?.let { token ->
            attachToMediaController(MediaController(this, token), sbn.packageName)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMediaNotification(sbn)) return
        if (sbn.packageName == activePackage) {
            Timber.tag(TAG).i("Активное медиа-уведомление удалено: %s", sbn.packageName)
            activePackage = null
            processActiveNotifications()
            if (activePackage == null) {
                MediaCoverState.clear()
            }
        }
    }

    private fun startNotificationScan() {
        notificationScanJob?.cancel()
        notificationScanJob = serviceScope.launch {
            while (isActive) {
                processActiveNotifications()
                delay(ACTIVE_NOTIFICATION_SCAN_MS)
            }
        }
    }

    private fun processActiveNotifications() {
        val best = activeNotifications
            ?.asSequence()
            ?.filter { isMediaNotification(it) }
            ?.map { buildSnapshotFromNotification(it) }
            ?.filter { it.hasContent }
            ?.maxWithOrNull(compareBy<TrackSnapshot> { it.isPlaying }.thenBy { it.postTime })
            ?: return
        publishSnapshot(best, source = "active-notification")
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras ?: return notification.category == Notification.CATEGORY_TRANSPORT
        val template = extras.getString("android.template")
        return template == "android.app.Notification\$MediaStyle" ||
            extras.containsKey(NotificationCompat.EXTRA_MEDIA_SESSION) ||
            extras.containsKey(EXTRA_MEDIA_SESSION_LEGACY) ||
            notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.actions.orEmpty().any { it.title?.toString()?.contains("play", ignoreCase = true) == true }
    }

    private fun buildSnapshotFromNotification(sbn: StatusBarNotification): TrackSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val artist = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val album = extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        val bitmap = bitmapFromNotification(notification)
        val isPlaying = controllers[sbn.packageName]?.isPlaying == true || hasPauseAction(notification)

        return TrackSnapshot(
            packageName = sbn.packageName,
            sourceLabel = appLabelForPackage(sbn.packageName),
            title = title,
            artist = artist,
            album = album,
            coverBitmap = bitmap,
            isPlaying = isPlaying,
            postTime = sbn.postTime,
            notification = notification,
            positionMs = controllers[sbn.packageName]?.currentPositionMs(),
            durationMs = null,
        )
    }

    @Suppress("DEPRECATION")
    private fun bitmapFromNotification(notification: Notification): Bitmap? {
        val extras = notification.extras
        return bitmapFromValue(extras?.get(Notification.EXTRA_LARGE_ICON))
            ?: bitmapFromValue(extras?.get(EXTRA_LARGE_ICON_BIG))
            ?: bitmapFromValue(extras?.get(EXTRA_PICTURE))
            ?: bitmapFromValue(notification.largeIcon)
    }

    private fun bitmapFromValue(value: Any?): Bitmap? {
        return when (value) {
            is Bitmap -> value
            is Icon -> bitmapFromIcon(value)
            is BitmapDrawable -> value.bitmap
            else -> null
        }
    }

    private fun bitmapFromIcon(icon: Icon?): Bitmap? {
        icon ?: return null
        return try {
            val drawable = icon.loadDrawable(this) ?: return null
            if (drawable is BitmapDrawable) return drawable.bitmap

            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_SIZE
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_SIZE
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось прочитать largeIcon из уведомления")
            null
        }
    }

    private fun extractMediaSessionToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(NotificationCompat.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)?.let { return it }
            return extras.getParcelable(EXTRA_MEDIA_SESSION_LEGACY, MediaSession.Token::class.java)
        }
        @Suppress("DEPRECATION")
        return extras.getParcelable(NotificationCompat.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            ?: extras.getParcelable(EXTRA_MEDIA_SESSION_LEGACY) as? MediaSession.Token
    }

    private fun attachToMediaController(controller: MediaController) {
        attachToMediaController(controller, controller.packageName)
    }

    private fun attachToMediaController(controller: MediaController, packageName: String) {
        val existing = controllers[packageName]
        if (existing?.controller?.sessionToken == controller.sessionToken) {
            updateFromController(controller, packageName)
            return
        }
        existing?.unregister()

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateFromMetadata(metadata, packageName)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                controllers[packageName]?.isPlaying = state?.state == PlaybackState.STATE_PLAYING
                if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_NONE) {
                    if (activePackage == packageName) activePackage = null
                } else {
                    updateFromController(controller, packageName)
                }
            }

            override fun onSessionDestroyed() {
                Timber.tag(TAG).i("MediaSession уничтожена: %s", packageName)
                controllers.remove(packageName)?.unregister()
                if (activePackage == packageName) {
                    activePackage = null
                }
            }
        }

        controllers[packageName] = ControllerHolder(controller, callback)
        controller.registerCallback(callback)
        updateFromController(controller, packageName)
        Timber.tag(TAG).i("Подписан на MediaSession: %s", packageName)
    }

    private fun promoteBestController(activeControllers: List<MediaController>) {
        val best = activeControllers.firstOrNull {
            safePlaybackState(it, it.packageName)?.state == PlaybackState.STATE_PLAYING
        }
            ?: activeControllers.firstOrNull()
            ?: return
        updateFromController(best, best.packageName)
    }

    private fun updateFromController(controller: MediaController, packageName: String) {
        val playbackState = safePlaybackState(controller, packageName)
        controllers[packageName]?.isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        updateFromMetadata(safeMetadata(controller, packageName), packageName)
    }

    private fun safePlaybackState(controller: MediaController, packageName: String): PlaybackState? {
        return try {
            controller.playbackState
        } catch (e: SecurityException) {
            if (!mediaControllerStateAccessDeniedLogged) {
                mediaControllerStateAccessDeniedLogged = true
                Timber.tag(TAG).i(e, "Нет доступа к playbackState MediaSession, продолжаю без transport state: %s", packageName)
            }
            null
        }
    }

    private fun safeMetadata(controller: MediaController, packageName: String): MediaMetadata? {
        return try {
            controller.metadata
        } catch (e: SecurityException) {
            if (!mediaControllerStateAccessDeniedLogged) {
                mediaControllerStateAccessDeniedLogged = true
                Timber.tag(TAG).i(e, "Нет доступа к metadata MediaSession, продолжаю только по уведомлениям: %s", packageName)
            }
            null
        }
    }

    private fun updateFromMetadata(metadata: MediaMetadata?, packageName: String) {
        metadata ?: return
        val description = metadata.description
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: description.title?.toString()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: description.subtitle?.toString()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }

        val bitmap = description.iconBitmap
            ?: bitmapFromUri(description.iconUri)
            ?: bitmapFromMetadataUri(metadata)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val snapshot = TrackSnapshot(
            packageName = packageName,
            sourceLabel = appLabelForPackage(packageName),
            title = title,
            artist = artist,
            album = album,
            coverBitmap = bitmap,
            isPlaying = controllers[packageName]?.isPlaying == true,
            postTime = System.currentTimeMillis(),
            notification = null,
            positionMs = controllers[packageName]?.currentPositionMs(),
            durationMs = durationMs,
        )
        if (snapshot.hasContent) {
            publishSnapshot(snapshot, source = "media-session")
        }
    }

    private fun bitmapFromMetadataUri(metadata: MediaMetadata): Bitmap? {
        val uriValue = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return bitmapFromUri(Uri.parse(uriValue))
    }

    private fun bitmapFromUri(uri: Uri?): Bitmap? {
        uri ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file" && scheme != "android.resource") return null
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось прочитать обложку по URI: %s", uri)
            null
        }
    }

    private fun publishSnapshot(snapshot: TrackSnapshot, source: String) {
        val mergedSnapshot = snapshot.withProgressFrom(lastPublishedSnapshot)
        if (!mergedSnapshot.isPlaying && activePackage != null && mergedSnapshot.packageName != activePackage) return
        val publishKey = mergedSnapshot.publishKey
        if (publishKey == lastPublishedKey) return

        // Debounce: если ключ изменился, но прошло менее 300 мс с последней публикации,
        // откладываем публикацию и ждём стабилизации данных
        val nowMs = SystemClock.elapsedRealtime()
        val timeSinceLastPublish = nowMs - lastPublishTimeMs
        if (timeSinceLastPublish < PUBLISH_DEBOUNCE_MS && publishDebounceJob?.isActive == true) {
            // Обновляем pending snapshot на более свежий
            pendingPublishSnapshot = mergedSnapshot
            pendingPublishSource = source
            return
        }

        // Отменяем предыдущий debounce job
        publishDebounceJob?.cancel()

        if (timeSinceLastPublish < PUBLISH_DEBOUNCE_MS) {
            // Запускаем debounce job
            pendingPublishSnapshot = mergedSnapshot
            pendingPublishSource = source
            publishDebounceJob = serviceScope.launch {
                delay(PUBLISH_DEBOUNCE_MS - timeSinceLastPublish)
                val pending = pendingPublishSnapshot ?: return@launch
                doPublishSnapshot(pending, pendingPublishSource)
                pendingPublishSnapshot = null
            }
        } else {
            // Публикуем сразу
            doPublishSnapshot(mergedSnapshot, source)
        }
    }

    private fun doPublishSnapshot(snapshot: TrackSnapshot, source: String) {
        lastPublishTimeMs = SystemClock.elapsedRealtime()
        lastPublishedKey = snapshot.publishKey
        lastPublishedSnapshot = snapshot
        activePackage = snapshot.packageName
        Timber.tag(TAG).i(
            "Медиа обновлено из %s: pkg=%s, title=%s, artist=%s, cover=%s",
            source,
            snapshot.packageName,
            snapshot.title,
            snapshot.artist,
            snapshot.coverBitmap != null,
        )
        MediaCoverState.update(
            sourceLabel = snapshot.sourceLabel,
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            coverBitmap = snapshot.coverBitmap,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
        )
    }

    private fun appLabelForPackage(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Throwable) {
            packageName
        }
    }

    private fun hasPauseAction(notification: Notification): Boolean {
        return notification.actions.orEmpty().any { action ->
            val title = action.title?.toString().orEmpty().lowercase()
            "pause" in title || "пауза" in title || "стоп" in title
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupListenerState()
        Timber.tag(TAG).i("NotificationListener отключён")
    }

    override fun onDestroy() {
        cleanupListenerState()
        serviceScope.cancel()
        Timber.tag(TAG).i("NotificationListener уничтожен")
        super.onDestroy()
    }

    private fun cleanupListenerState() {
        notificationScanJob?.cancel()
        notificationScanJob = null
        controllers.values.forEach { it.unregister() }
        controllers.clear()
        activePackage = null
        lastPublishedKey = null
        lastPublishedSnapshot = null
        MediaCoverState.clear()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Не удалось удалить слушатель активных MediaSession")
        }
        mediaSessionManager = null
    }

    private data class TrackSnapshot(
        val packageName: String,
        val sourceLabel: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val coverBitmap: Bitmap?,
        val isPlaying: Boolean,
        val postTime: Long,
        val notification: Notification?,
        val positionMs: Long?,
        val durationMs: Long?,
    ) {
        val hasContent: Boolean
            get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || coverBitmap != null

        val publishKey: String
            get() = listOf(
                packageName,
                sourceLabel.orEmpty(),
                title.orEmpty(),
                artist.orEmpty(),
                coverBitmap?.width?.toString().orEmpty(),
                coverBitmap?.height?.toString().orEmpty(),
                positionMs?.div(PROGRESS_DEDUPE_BUCKET_MS)?.toString().orEmpty(),
                durationMs?.toString().orEmpty(),
            ).joinToString(separator = "|")

        fun withProgressFrom(previous: TrackSnapshot?): TrackSnapshot {
            if (previous == null || !isSameTrack(previous)) return this
            val mergedBitmap = pickLargerBitmap(coverBitmap, previous.coverBitmap)
            return copy(
                coverBitmap = mergedBitmap,
                positionMs = positionMs ?: previous.positionMs,
                durationMs = durationMs ?: previous.durationMs,
            )
        }

        private fun isSameTrack(other: TrackSnapshot): Boolean {
            return packageName == other.packageName &&
                title == other.title &&
                artist == other.artist
        }

        private fun pickLargerBitmap(a: Bitmap?, b: Bitmap?): Bitmap? {
            if (a == null) return b
            if (b == null) return a
            return if (a.width * a.height >= b.width * b.height) a else b
        }
    }

    private data class ControllerHolder(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var isPlaying: Boolean = false,
    ) {
        fun unregister() {
            try {
                controller.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }

        fun currentPositionMs(): Long? {
            val state = try {
                controller.playbackState
            } catch (_: SecurityException) {
                return null
            } ?: return null
            val basePosition = state.position.takeIf { it >= 0L } ?: return null
            if (state.state != PlaybackState.STATE_PLAYING) return basePosition
            val elapsedMs = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            return (basePosition + (elapsedMs * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        }
    }

    companion object {
        private const val TAG = "MediaNotifListener"
        private const val ACTIVE_NOTIFICATION_SCAN_MS = 500L
        private const val ICON_FALLBACK_SIZE = 256
        private const val EXTRA_MEDIA_SESSION_LEGACY = "android.mediaSession"
        private const val EXTRA_LARGE_ICON_BIG = "android.largeIcon.big"
        private const val EXTRA_PICTURE = "android.picture"
        private const val PROGRESS_DEDUPE_BUCKET_MS = 1_000L
        private const val PUBLISH_DEBOUNCE_MS = 300L
    }
}
