package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Совместимость с внешним заводским контрактом запроса cluster focus.
 *
 * Заводское приложение публикует exported receiver на эти `action`, поэтому
 * IVI-окружение или системные скрипты могут ожидать такой же вход в нашем приложении.
 */
class ClusterFocusRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        RuntimeConfig.init(context.applicationContext)

        when (intent?.action) {
            ACTION_REQUEST_FOCUS -> handleRequestFocus(context)
            ACTION_ABANDON_FOCUS -> handleAbandonFocus()
        }
    }

    private fun handleRequestFocus(context: Context) {
        Log.i(TAG, "Получен внешний запрос cluster focus")

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(activityIntent)
            Log.i(TAG, "MainActivity поднята по внешнему запросу cluster focus")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось поднять MainActivity по внешнему запросу cluster focus", t)
        }
    }

    private fun handleAbandonFocus() {
        Log.i(TAG, "Получен внешний запрос abandon focus; действие только задокументировано")
    }

    private companion object {
        private const val TAG = "ClusterFocusReceiver"
        private const val ACTION_REQUEST_FOCUS: String =
            "com.humaxdigital.cluster.action.CLUSTER_REQUEST_FOCUS"
        private const val ACTION_ABANDON_FOCUS: String =
            "com.humaxdigital.cluster.action.CLUSTER_ABANDON_FOCUS"
    }
}
