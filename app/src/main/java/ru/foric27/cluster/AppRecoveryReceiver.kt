package ru.foric27.cluster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Восстанавливает сервис и при необходимости поднимает UI после закрытия задачи
 * или уничтожения процесса.
 */
class AppRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        RuntimeConfig.init(context.applicationContext)
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty()
        val launchUi = intent?.getBooleanExtra(EXTRA_LAUNCH_UI, false) == true
        val keepInForeground = intent?.getBooleanExtra(EXTRA_KEEP_IN_FOREGROUND, false) == true

        try {
            UdpStreamService.startServiceCompat(context.applicationContext)
            if (launchUi) {
                context.startActivity(
                    MainActivity.createLaunchIntent(
                        context = context,
                        keepInForeground = keepInForeground,
                    ),
                )
                Timber.tag(TAG).i("Восстановлены сервис и UI, reason=$reason, keepInForeground=$keepInForeground")
            } else {
                Timber.tag(TAG).i("Восстановлен только сервис, reason=$reason")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось выполнить восстановление, reason=$reason, launchUi=$launchUi")
        }
    }

    companion object {
        private const val TAG = "AppRecoveryReceiver"
        private const val ACTION_RECOVER_APP = "ru.foric27.cluster.action.RECOVER_APP"
        private const val EXTRA_REASON = "ru.foric27.cluster.extra.RECOVERY_REASON"
        private const val EXTRA_LAUNCH_UI = "ru.foric27.cluster.extra.RECOVERY_LAUNCH_UI"
        private const val EXTRA_KEEP_IN_FOREGROUND = "ru.foric27.cluster.extra.RECOVERY_KEEP_IN_FOREGROUND"

        fun createPendingIntent(
            context: Context,
            requestCode: Int,
            reason: String,
            launchUi: Boolean = false,
            keepInForeground: Boolean = false,
        ): PendingIntent {
            val intent = Intent(context, AppRecoveryReceiver::class.java).apply {
                action = ACTION_RECOVER_APP
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_LAUNCH_UI, launchUi)
                putExtra(EXTRA_KEEP_IN_FOREGROUND, keepInForeground)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
        }

        private fun immutableFlag(): Int {
            return PendingIntent.FLAG_IMMUTABLE
        }
    }
}
