package ru.foric27.cluster.ui
import ru.foric27.cluster.video.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
internal class ClusterBlackScreenActivity : ComponentActivity() {

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
        setContent { ClusterBlackScreen() }
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
            }
        }
    }

    companion object {
        private const val TAG = "ClusterBlackScreen"
        const val ACTION_FINISH_CLUSTER_BLACK_SCREEN = "ru.foric27.cluster.action.FINISH_CLUSTER_BLACK_SCREEN"
    }
}

@Composable
private fun ClusterBlackScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )
}
