package ru.foric27.cluster

import android.os.Process
import timber.log.Timber

/**
 * Защита процесса от OOM killer через root.
 *
 * Устанавливает oom_score_adj в -1000, чтобы система не убивала
 * сервис при нехватке памяти — критично для китайских head unit.
 */
internal class OomScoreAdjuster(private val rootShell: NetworkRootShell) {

    fun protectProcess() {
        if (!rootShell.isAvailable()) {
            Timber.tag(TAG).i("ROOT недоступен — пропускаю OOM-защиту")
            return
        }
        try {
            val pid = Process.myPid()
            val result = rootShell.execScript(listOf("echo -1000 > /proc/$pid/oom_score_adj"))
            if (result.isSuccess) {
                Timber.tag(TAG).i("OOM score установлен в -1000 для PID=$pid")
            } else {
                Timber.tag(TAG).w("Не удалось установить oom_score_adj: ${result.exceptionOrNull()?.message}")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Ошибка при установке oom_score_adj")
        }
    }

    private companion object {
        private const val TAG = "OomScoreAdjuster"
    }
}
