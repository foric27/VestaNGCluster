package ru.foric27.cluster

import android.app.Notification
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import timber.log.Timber

/**
 * NotificationListenerService для получения метаданных и обложек
 * из медиа-уведомлений (Yandex.Music, YouTube и др.).
 *
 * Извлекает MediaSession token из уведомления, создаёт MediaController
 * и слушает изменения воспроизведения для получения актуальной обложки.
 *
 * Требует включения в настройках уведомлений или через root:
 * `cmd notification allow_listener ru.foric27.cluster/.MediaNotificationListenerService`
 */
internal class MediaNotificationListenerService : NotificationListenerService() {

    private var activeMediaController: MediaController? = null
    private var mediaCallback: MediaController.Callback? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.tag(TAG).i("NotificationListener подключён")
        processActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        android.util.Log.d(TAG, "onNotificationPosted: pkg=$pkg")
        if (!isMediaNotification(sbn)) {
            val template = sbn.notification.extras?.getString("android.template")
            val category = sbn.notification.category
            android.util.Log.d(TAG, "Not a media notification: $pkg, template=$template, category=$category")
            return
        }
        Timber.tag(TAG).d("Получено медиа-уведомление: %s", pkg)
        extractAndSubscribe(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMediaNotification(sbn)) return
        if (sbn.packageName == activeMediaController?.packageName) {
            Timber.tag(TAG).i("Медиа-уведомление удалено, сбрасываю состояние")
            cleanupController()
            MediaCoverState.clear()
        }
    }

    private fun processActiveNotifications() {
        val notifications = activeNotifications ?: return
        for (sbn in notifications) {
            if (isMediaNotification(sbn)) {
                extractAndSubscribe(sbn)
                return
            }
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras ?: return false
        val template = extras.getString("android.template")
        if (template == "android.app.Notification\$MediaStyle") return true
        // fallback: наличие media session token
        if (extras.containsKey(NotificationCompat.EXTRA_MEDIA_SESSION)) return true
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return true
        return false
    }

    private fun extractAndSubscribe(sbn: StatusBarNotification) {
        try {
            val token = extractMediaSessionToken(sbn.notification) ?: return
            subscribeToSession(token, sbn.packageName)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось извлечь MediaSession из уведомления")
        }
    }

    private fun extractMediaSessionToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return extras.getParcelable(
                NotificationCompat.EXTRA_MEDIA_SESSION,
                MediaSession.Token::class.java
            )
        }
        @Suppress("DEPRECATION")
        return extras.getParcelable(NotificationCompat.EXTRA_MEDIA_SESSION) as? MediaSession.Token
    }

    private fun subscribeToSession(token: MediaSession.Token, packageName: String) {
        cleanupController()

        val controller = MediaController(this, token)
        activeMediaController = controller

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                updateFromMetadata(metadata, packageName)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_NONE) {
                    MediaCoverState.clear()
                }
            }

            override fun onSessionDestroyed() {
                Timber.tag(TAG).i("MediaSession уничтожена")
                cleanupController()
                MediaCoverState.clear()
            }
        }

        mediaCallback = callback
        controller.registerCallback(callback)

        // Немедленно обновить текущим metadata
        updateFromMetadata(controller.metadata, packageName)
        Timber.tag(TAG).i("Подписан на MediaSession: %s", packageName)
    }

    private fun updateFromMetadata(metadata: android.media.MediaMetadata?, packageName: String) {
        if (metadata == null) return

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)

        // Пытаемся получить bitmap обложки
        val bitmap = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        Timber.tag(TAG).i("Обновление трека: %s — %s (albumArt=%s)", title, artist, bitmap != null)
        MediaCoverState.update(title, artist, album, bitmap)
    }

    private fun cleanupController() {
        try {
            mediaCallback?.let { activeMediaController?.unregisterCallback(it) }
        } catch (_: Throwable) {}
        mediaCallback = null
        activeMediaController = null
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupController()
        MediaCoverState.clear()
        Timber.tag(TAG).i("NotificationListener отключён")
    }

    companion object {
        private const val TAG = "MediaNotifListener"
    }
}
