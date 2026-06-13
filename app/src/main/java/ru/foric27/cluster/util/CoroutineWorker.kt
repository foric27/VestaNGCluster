package ru.foric27.cluster.util

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Утилита для запуска фоновых корутин с приоритетом потока.
 *
 * Заменяет паттерн `Thread(...).apply { start() }` на корутинный подход.
 */
internal object CoroutineWorker {

    /**
     * Создаёт [CoroutineScope] для сервиса с [SupervisorJob].
     *
     * Все дочерние корутины живут, пока scope не отменён.
     */
    fun createServiceScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Запускает фоновую корутину с указанным приоритетом.
     *
     * @param scope родительский [CoroutineScope]
     * @param name имя потока (для логирования)
     * @param threadPriority приоритет Android-потока (см. [Process])
     * @param block тело корутины
     * @return [Job] для управления жизненным циклом
     */
    fun launchWorker(
        scope: CoroutineScope,
        name: String,
        threadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(Dispatchers.Default) {
        applyThreadPriority(threadPriority, name)
        try {
            block()
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                Timber.e(t, "Worker '$name' crashed")
            }
        }
    }

    /**
     * Запускает периодическую корутину с заданным интервалом.
     *
     * @param scope родительский [CoroutineScope]
     * @param name имя потока
     * @param intervalMs интервал между итерациями (мс)
     * @param threadPriority приоритет Android-потока
     * @param block тело итерации (suspend)
     * @return [Job] для управления жизненным циклом
     */
    fun launchPeriodicWorker(
        scope: CoroutineScope,
        name: String,
        intervalMs: Long,
        threadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(Dispatchers.Default) {
        applyThreadPriority(threadPriority, name)
        while (isActive) {
            try {
                block()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Timber.e(t, "Periodic worker '$name' iteration crashed")
                }
            }
            if (!isActive) break
            delay(intervalMs)
        }
    }

    private fun applyThreadPriority(priority: Int, name: String) {
        try {
            Process.setThreadPriority(priority)
        } catch (t: Throwable) {
            Timber.w(t, "Не удалось установить приоритет $priority для потока $name")
        }
    }
}
