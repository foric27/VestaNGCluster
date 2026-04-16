package ru.foric27.cluster

import android.util.Log
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Единый центр пользовательских предупреждений.
 *
 * Сообщения складируются в очередь, чтобы их можно было показать после открытия UI,
 * и одновременно рассылаются активным слушателям, если окно приложения уже открыто.
 */
object AppWarningCenter {

    private const val TAG = "AppWarningCenter"
    private const val MAX_QUEUE_SIZE = 128

    interface WarningListener {
        fun onWarningPublished(message: String)
    }

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private val listeners = LinkedHashSet<WarningListener>()

    fun publish(message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return

        val listenersSnapshot: List<WarningListener>
        synchronized(lock) {
            if (queue.contains(normalized)) return
            while (queue.size >= MAX_QUEUE_SIZE) {
                queue.removeFirst()
            }
            queue.addLast(normalized)
            listenersSnapshot = listeners.toList()
        }

        listenersSnapshot.forEach {
            try {
                it.onWarningPublished(normalized)
            } catch (t: Throwable) {
                Log.w(TAG, "Слушатель предупреждений завершился ошибкой", t)
            }
        }
    }

    fun registerListener(listener: WarningListener) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: WarningListener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun contains(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false
        return synchronized(lock) { queue.contains(normalized) }
    }

    fun consumeAll(): List<String> {
        synchronized(lock) {
            if (queue.isEmpty()) return emptyList()
            val out = ArrayList<String>(queue.size)
            while (queue.isNotEmpty()) {
                out += queue.removeFirst()
            }
            return out
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }
}
