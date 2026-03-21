package ru.foric27.cluster

import android.content.Context
import android.os.Handler
import android.util.Log

internal class UdpUpdateServerCoordinator(
    private val context: Context,
    private val mainHandler: Handler,
    private val isServiceRunning: () -> Boolean,
    private val startDetachedWorker: (name: String, block: () -> Unit) -> Unit,
    private val publishWarning: (String) -> Unit,
) {

    private var lastInternalUpdatePollReport: String? = null
    private var lastFtpRetryReport: String? = null
    private var internalUpdatePollScheduled = false
    private var ftpRetryScheduled = false

    private val internalUpdatePollRunnable = Runnable { performInternalUpdatePoll() }
    private val ftpRetryRunnable = Runnable { performFtpRetry() }

    fun startOrRefreshUpdateServer() {
        val result = UpdateServerManager.prepareAndStartServer(
            context,
            UpdateFileLocator.SearchPolicy.INTERNAL_ONLY,
        )
        if (result.success) {
            Log.i(TAG, "FTP обновление активно: ${result.boundAddress?.host}:${result.boundAddress?.port}")
            return
        }

        Log.w(TAG, "FTP обновление не запущено: ${result.message}")
        publishWarning(context.getString(R.string.service_notification_ftp_message_fmt, result.message))
        scheduleFtpRetry("startup")
    }

    fun scheduleInternalUpdatePoll() {
        if (internalUpdatePollScheduled) {
            mainHandler.removeCallbacks(internalUpdatePollRunnable)
        }
        internalUpdatePollScheduled = true
        mainHandler.postDelayed(
            internalUpdatePollRunnable,
            RuntimeConfig.UpdateFtp.INTERNAL_POLL_PERIOD_MS,
        )
    }

    fun cancelInternalUpdatePoll() {
        internalUpdatePollScheduled = false
        mainHandler.removeCallbacks(internalUpdatePollRunnable)
    }

    fun cancelFtpRetry() {
        ftpRetryScheduled = false
        mainHandler.removeCallbacks(ftpRetryRunnable)
    }

    fun stop() {
        cancelInternalUpdatePoll()
        cancelFtpRetry()
    }

    private fun scheduleFtpRetry(reason: String) {
        if (ftpRetryScheduled) return
        ftpRetryScheduled = true
        Log.i(TAG, "Повторный запуск FTP запланирован через ${RuntimeConfig.UpdateFtp.RETRY_DELAY_MS}мс, reason=$reason")
        mainHandler.removeCallbacks(ftpRetryRunnable)
        mainHandler.postDelayed(ftpRetryRunnable, RuntimeConfig.UpdateFtp.RETRY_DELAY_MS)
    }

    private fun performFtpRetry() {
        if (!isServiceRunning()) {
            ftpRetryScheduled = false
            return
        }
        ftpRetryScheduled = false

        startDetachedWorker("FtpRetryWorker") {
            try {
                val state = UpdateServerManager.getServerState()
                if (state.status == UpdateServerManager.Status.RUNNING) {
                    cancelFtpRetry()
                    return@startDetachedWorker
                }

                val result = UpdateServerManager.restartServer()
                val report = (if (result.success) "ok:" else "fail:") + result.message
                if (report != lastFtpRetryReport) {
                    lastFtpRetryReport = report
                    if (result.success) {
                        val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
                        Log.i(TAG, "FTP успешно поднят повторно: $address")
                        publishWarning(context.getString(R.string.service_notification_ftp_restarted_fmt, address))
                    } else {
                        Log.w(TAG, "Повторный запуск FTP не удался: ${result.message}")
                    }
                }

                if (result.success) {
                    cancelFtpRetry()
                } else {
                    scheduleFtpRetry("ftp_retry")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка повторного запуска FTP", t)
                scheduleFtpRetry("ftp_retry_exception")
            }
        }
    }

    private fun performInternalUpdatePoll() {
        if (!isServiceRunning()) {
            internalUpdatePollScheduled = false
            return
        }
        internalUpdatePollScheduled = false

        startDetachedWorker("UpdatePollWorker") {
            try {
                val result = UpdateServerManager.pollAvailableStorage(context)
                val report = (if (result.success) "ok:" else "fail:") + result.message
                if (report != lastInternalUpdatePollReport) {
                    lastInternalUpdatePollReport = report
                    if (!result.success) {
                        Log.w(TAG, "Периодический опрос обновления: ${result.message}")
                    }
                }
                if (result.success) {
                    cancelFtpRetry()
                } else {
                    scheduleFtpRetry("internal_poll")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка периодического опроса обновления", t)
            } finally {
                if (isServiceRunning()) {
                    scheduleInternalUpdatePoll()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "UdpUpdateServerCoord"
    }
}
