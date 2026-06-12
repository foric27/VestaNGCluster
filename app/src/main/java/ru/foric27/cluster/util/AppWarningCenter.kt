package ru.foric27.cluster.util

import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Единый центр пользовательских предупреждений.
 *
 * Сообщения складируются в очередь, чтобы их можно было показать после открытия UI,
 * и одновременно рассылаются активным слушателям, если окно приложения уже открыто.
 */
internal object AppWarningCenter {

    private const val TAG = "AppWarningCenter"
    private const val MAX_QUEUE_SIZE = 128

    interface WarningListener {
        fun onWarningPublished(message: String)
    }

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private val listeners = LinkedHashSet<WeakReference<WarningListener>>()

    /**
     * Публикует предупреждение в очередь и рассылает активным слушателям.
     *
     * @param message текст предупреждения
     */
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
            listeners.removeAll { it.get() == null }
            listenersSnapshot = listeners.mapNotNull { it.get() }.toList()
        }

        listenersSnapshot.forEach {
            try {
                it.onWarningPublished(normalized)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Слушатель предупреждений завершился ошибкой")
            }
        }
    }

    /**
     * Регистрирует слушателя предупреждений.
     *
     * @param listener слушатель для получения уведомлений
     */
    fun registerListener(listener: WarningListener) {
        synchronized(lock) {
            listeners.add(WeakReference(listener))
        }
    }

    /**
     * Удаляет слушателя предупреждений.
     *
     * @param listener слушатель для удаления
     */
    fun unregisterListener(listener: WarningListener) {
        synchronized(lock) {
            listeners.removeAll { it.get() === listener || it.get() == null }
        }
    }

    fun contains(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false
        return synchronized(lock) { queue.contains(normalized) }
    }

    /**
     * Извлекает и очищает все накопленные предупреждения.
     *
     * @return список извлечённых сообщений
     */
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

    /**
     * Удаляет предупреждения, соответствующие предикату.
     *
     * @param predicate условие удаления
     */
    fun removeMatching(predicate: (String) -> Boolean) {
        synchronized(lock) {
            queue.removeAll(predicate)
        }
    }
}
