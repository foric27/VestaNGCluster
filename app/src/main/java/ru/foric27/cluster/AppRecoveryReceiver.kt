package ru.foric27.cluster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Восстанавливает сервис и фоновый launcher-экран после закрытия задачи или убийства процесса.
 */
class AppRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        RuntimeConfig.init(context.applicationContext)
        val reason = intent?.getStringExtra(EXTRA_REASON).orEmpty()

        try {
            UdpStreamService.startServiceCompat(context.applicationContext)
            context.startActivity(MainActivity.createLaunchIntent(context, keepInForeground = false))
            Log.i(TAG, "Приложение восстановлено, reason=$reason")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось восстановить приложение, reason=$reason", t)
        }
    }

    companion object {
        private const val TAG = "AppRecoveryReceiver"
        private const val ACTION_RECOVER_APP = "ru.foric27.cluster.action.RECOVER_APP"
        private const val EXTRA_REASON = "ru.foric27.cluster.extra.RECOVERY_REASON"

        fun createPendingIntent(context: Context, requestCode: Int, reason: String): PendingIntent {
            val intent = Intent(context, AppRecoveryReceiver::class.java).apply {
                action = ACTION_RECOVER_APP
                putExtra(EXTRA_REASON, reason)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
        }

        private fun immutableFlag(): Int {
            return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        }
    }
}
