package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Совместимость с внешним заводским контрактом запроса cluster focus.
 *
 * Заводское приложение публикует exported receiver на эти `action`, поэтому
 * IVI-окружение или системные скрипты могут ожидать такой же вход в нашем приложении.
 */
class ClusterFocusRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        RuntimeConfig.init(context.applicationContext)
        val action = intent?.action.orEmpty()

        when (action) {
            in REQUEST_FOCUS_ACTIONS -> handleRequestFocus(context, action)
            in ABANDON_FOCUS_ACTIONS -> handleAbandonFocus(action)
        }
    }

    private fun handleRequestFocus(context: Context, action: String) {
        Timber.tag(TAG).i("Получен внешний запрос cluster focus: action=$action")

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_KEEP_IN_FOREGROUND, false)
            setPackage(context.packageName)
        }
        try {
            context.startActivity(activityIntent)
            UdpStreamService.startServiceCompat(context)
            Timber.tag(TAG).i("MainActivity и сервис активированы по внешнему запросу cluster focus: action=$action")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось активировать приложение по внешнему запросу cluster focus: action=$action")
        }
    }

    private fun handleAbandonFocus(action: String) {
        Timber.tag(TAG).i("Получен внешний запрос abandon focus: action=$action")
    }

    private companion object {
        private const val TAG = "ClusterFocusReceiver"
        private val REQUEST_FOCUS_ACTIONS = setOf(
            "com.humaxdigital.cluster.action.CLUSTER_REQUEST_FOCUS",
            "ru.yandex.yandexnavi.action.CLUSTER_REQUEST_FOCUS",
            "yandex.auto.uma.action.CLUSTER_REQUEST_FOCUS",
            "com.humaxdigital.connectivity.action.CLUSTER_REQUEST_FOCUS",
        )
        private val ABANDON_FOCUS_ACTIONS = setOf(
            "com.humaxdigital.cluster.action.CLUSTER_ABANDON_FOCUS",
            "ru.yandex.yandexnavi.action.CLUSTER_ABANDON_FOCUS",
            "yandex.auto.uma.action.CLUSTER_ABANDON_FOCUS",
            "com.humaxdigital.connectivity.action.CLUSTER_ABANDON_FOCUS",
        )
    }
}
