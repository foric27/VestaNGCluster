package ru.foric27.cluster

import android.app.Activity
import android.os.Bundle

/**
 * Тёмная заглушка для VirtualDisplay, когда целевой поток недоступен.
 */
internal class StreamPlaceholderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_placeholder)

        val blackBottomPx = RuntimeConfig.Video.BLACK_BOTTOM_PX
        if (blackBottomPx > 0) {
            findViewById<android.widget.FrameLayout>(R.id.stream_placeholder_root)
                .setPadding(0, 0, 0, blackBottomPx)
        }
    }
}
