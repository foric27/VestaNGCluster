package ru.foric27.cluster

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.Constraints
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback: периодическая проверка, что сервис жив.
 *
 * Запускается каждые 15 минут. Если UdpStreamService не запущен —
 * запускает его через startServiceCompat().
 */
class ServiceKeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!UdpStreamService.isServiceRunning()) {
            Timber.tag(TAG).w("WorkManager: сервис не запущен, запускаю recovery")
            try {
                UdpStreamService.startServiceCompat(ctx)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "WorkManager: не удалось запустить сервис")
            }
        } else {
            Timber.tag(TAG).i("WorkManager: сервис жив")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val WORK_NAME = "cluster_keepalive"

        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<ServiceKeepAliveWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(false)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Timber.tag(TAG).i("WorkManager keep-alive запланирован каждые 15 мин")
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Не удалось запланировать WorkManager keep-alive")
            }
        }
    }
}
