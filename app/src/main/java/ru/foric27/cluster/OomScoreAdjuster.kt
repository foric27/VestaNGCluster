package ru.foric27.cluster

import android.os.Process
import com.topjohnwu.superuser.Shell
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
            // Обходим NetworkRootShell для записи в /proc — это не network-команда,
            // и NetworkRootShell блокирует shell-перенаправление (>)
            val result = Shell.cmd("echo -1000 > /proc/$pid/oom_score_adj").exec()
            if (result.isSuccess) {
                Timber.tag(TAG).i("OOM score установлен в -1000 для PID=$pid")
            } else {
                Timber.tag(TAG).w("Не удалось установить oom_score_adj: код=${result.code}")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Ошибка при установке oom_score_adj")
        }
    }

    private companion object {
        private const val TAG = "OomScoreAdjuster"
    }
}
