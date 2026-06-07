package ru.foric27.cluster

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import timber.log.Timber

/**
 * Кратковременный чёрный экран на cluster display в момент запуска навигатора.
 *
 * Используется в [VideoDisplayLauncher] между запросом на запуск навигатора и
 * фактическим появлением его UI. Благодаря `noHistory="true"` и `singleTask`
 * activity автоматически сворачивается, как только на дисплей выходит новая
 * активность (навигатор). Также может быть принудительно закрыта через
 * broadcast [ACTION_FINISH_CLUSTER_BLACK_SCREEN].
 */
internal class ClusterBlackScreenActivity : Activity() {

    private var finishReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentDisplayId = try {
            if (Build.VERSION.SDK_INT >= 30) {
                display?.displayId
            } else {
                windowManager.defaultDisplay.displayId
            }
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId == android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).w("ClusterBlackScreenActivity запущена на основном дисплее (display=%d) — завершаю", currentDisplayId)
            finish()
            return
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(R.layout.activity_cluster_black)
        registerFinishReceiver()
        Timber.tag(TAG).i("ClusterBlackScreenActivity создан на display=%d", currentDisplayId)
    }

    private fun registerFinishReceiver() {
        finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_FINISH_CLUSTER_BLACK_SCREEN) {
                    Timber.tag(TAG).i("Получен broadcast finish — закрываю чёрный экран")
                    finish()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_CLUSTER_BLACK_SCREEN), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_CLUSTER_BLACK_SCREEN))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver уже разрегистрирован
            }
        }
    }

    companion object {
        private const val TAG = "ClusterBlackScreen"
        const val ACTION_FINISH_CLUSTER_BLACK_SCREEN = "ru.foric27.cluster.action.FINISH_CLUSTER_BLACK_SCREEN"
    }
}
