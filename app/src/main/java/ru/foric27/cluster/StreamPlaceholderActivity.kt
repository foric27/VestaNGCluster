package ru.foric27.cluster

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle

/**
 * Прозрачная заглушка для VirtualDisplay, когда целевой поток недоступен.
 */
internal class StreamPlaceholderActivity : Activity() {

    private var dismissReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.activity_stream_placeholder)
        registerDismissReceiver()

        val blackBottomPx = RuntimeConfig.Video.BLACK_BOTTOM_PX
        if (blackBottomPx > 0) {
            findViewById<android.widget.FrameLayout>(R.id.stream_placeholder_root)
                .setPadding(0, 0, 0, blackBottomPx)
        }
    }

    private fun registerDismissReceiver() {
        dismissReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_DISMISS_STREAM_PLACEHOLDER) {
                    finish()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                dismissReceiver,
                IntentFilter(ACTION_DISMISS_STREAM_PLACEHOLDER),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, IntentFilter(ACTION_DISMISS_STREAM_PLACEHOLDER))
        }
    }

    override fun onDestroy() {
        dismissReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver уже разрегистрирован
            }
        }
        dismissReceiver = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS_STREAM_PLACEHOLDER = "ru.foric27.cluster.action.DISMISS_STREAM_PLACEHOLDER"
    }
}
