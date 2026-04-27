package ru.foric27.cluster

import android.content.Context
import android.os.Handler
import timber.log.Timber

internal class UdpUpdateServerCoordinator(
    private val context: Context,
    private val mainHandler: Handler,
    private val isServiceRunning: () -> Boolean,
    private val startDetachedWorker: (name: String, block: () -> Unit) -> Unit,
    private val publishWarning: (String) -> Unit,
) {

    @Volatile private var lastInternalUpdatePollReport: String? = null
    @Volatile private var lastFtpRetryReport: String? = null
    @Volatile private var internalUpdatePollScheduled = false
    @Volatile private var ftpRetryScheduled = false
    @Volatile private var ftpOperationInProgress = false
    @Volatile private var lastKnownUpdateSha256: String? = null
    @Volatile private var lastAlertShownTime: Long = 0L

    private val internalUpdatePollRunnable = Runnable { performInternalUpdatePoll() }
    private val ftpRetryRunnable = Runnable { performFtpRetry() }

    fun startOrRefreshUpdateServer() {
        val result = runFtpOperation("startup") {
            UpdateServerManager.prepareAndStartServer(
                context,
                UpdateFileLocator.SearchPolicy.INTERNAL_ONLY,
            )
        } ?: return
        if (result.success) {
            Timber.tag(TAG).i("FTP обновление активно: ${result.boundAddress?.host}:${result.boundAddress?.port}")
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        Timber.tag(TAG).w("FTP обновление не запущено: ${result.message}")
        publishWarning(context.getString(R.string.service_notification_ftp_message_fmt, result.message))
        scheduleFtpRetry("startup")
    }

    fun restartUpdateServer(searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.INTERNAL_ONLY) {
        val result = runFtpOperation("restart") {
            UpdateServerManager.replaceAndStartServer(context, searchPolicy)
        } ?: return
        if (result.success) {
            val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
            Timber.tag(TAG).i("FTP перезапущен: $address")
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        Timber.tag(TAG).w("FTP после перезапуска не поднят: ${result.message}")
        publishWarning(context.getString(R.string.service_notification_ftp_message_fmt, result.message))
        scheduleFtpRetry("restart")
    }

    fun refreshUsbUpdateServer() {
        val result = runFtpOperation("usb_refresh") {
            UpdateServerManager.replaceAndStartServer(
                context,
                UpdateFileLocator.SearchPolicy.USB_FIRST,
            )
        } ?: return
        if (result.success) {
            val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
            Timber.tag(TAG).i("FTP обновлён по USB-aware пути: $address")
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        Timber.tag(TAG).w("USB-aware обновление FTP не запустило сервер: ${result.message}")
        publishWarning(context.getString(R.string.service_notification_ftp_message_fmt, result.message))
        scheduleFtpRetry("usb_refresh")
    }

    fun refreshAfterUsbRemoved() {
        val result = runFtpOperation("usb_removed") {
            UpdateServerManager.replaceAndStartServer(
                context = context,
                searchPolicy = UpdateFileLocator.SearchPolicy.INTERNAL_ONLY,
                clearDetection = true,
            )
        } ?: return
        if (result.success) {
            val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
            Timber.tag(TAG).i("FTP обновлён после извлечения USB: $address")
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        Timber.tag(TAG).i("После извлечения USB валидный update не найден: ${result.message}")
        cancelFtpRetry()
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
        Timber.tag(TAG).i("Повторный запуск FTP запланирован через ${RuntimeConfig.UpdateFtp.RETRY_DELAY_MS}мс, reason=$reason")
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

                val result = runFtpOperation("retry") { UpdateServerManager.restartServer() }
                    ?: return@startDetachedWorker
                val report = (if (result.success) "ok:" else "fail:") + result.message
                if (report != lastFtpRetryReport) {
                    lastFtpRetryReport = report
                    if (result.success) {
                        val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
                        Timber.tag(TAG).i("FTP успешно поднят повторно: $address")
                        publishWarning(context.getString(R.string.service_notification_ftp_restarted_fmt, address))
                    } else {
                        Timber.tag(TAG).w("Повторный запуск FTP не удался: ${result.message}")
                    }
                }

                if (result.success) {
                    cancelFtpRetry()
                } else {
                    scheduleFtpRetry("ftp_retry")
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Ошибка повторного запуска FTP")
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
                val result = runFtpOperation("poll") { UpdateServerManager.pollAvailableStorage(context) }
                    ?: return@startDetachedWorker
                val report = (if (result.success) "ok:" else "fail:") + result.message
                if (report != lastInternalUpdatePollReport) {
                    lastInternalUpdatePollReport = report
                    if (!result.success) {
                        Timber.tag(TAG).w("Периодический опрос обновления: ${result.message}")
                    }
                }
                if (result.success) {
                    cancelFtpRetry()
                    checkAndShowUpdateAlert(result)
                } else {
                    scheduleFtpRetry("internal_poll")
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Ошибка периодического опроса обновления")
            } finally {
                if (isServiceRunning()) {
                    scheduleInternalUpdatePoll()
                }
            }
        }
    }

    private fun checkAndShowUpdateAlert(result: UpdateServerManager.Result) {
        val fileInfo = result.fileInfo ?: return
        val currentSha256 = fileInfo.sha256
        if (currentSha256 == lastKnownUpdateSha256) return

        val now = System.currentTimeMillis()
        if (now - lastAlertShownTime < ALERT_THROTTLE_MS) {
            Timber.tag(TAG).i("Новое обновление обнаружено, но диалог недавно показывался; пропускаю")
            return
        }

        val location = result.sourceFilePath ?: result.detectedLocation ?: fileInfo.path
        Timber.tag(TAG).i("Новое обновление обнаружено: $location, показываю диалог")
        try {
            val intent = UpdateAlertActivity.createIntent(
                context,
                location,
                UpdateFileLocator.SearchPolicy.USB_FIRST,
            )
            context.startActivity(intent)
            lastKnownUpdateSha256 = currentSha256
            lastAlertShownTime = now
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось показать диалог обновления")
        }
    }

    private inline fun runFtpOperation(
        reason: String,
        operation: () -> UpdateServerManager.Result,
    ): UpdateServerManager.Result? {
        synchronized(this) {
            if (ftpOperationInProgress) {
                Timber.tag(TAG).i("Пропускаю FTP $reason: предыдущая операция ещё выполняется")
                return null
            }
            ftpOperationInProgress = true
        }
        return try {
            operation()
        } finally {
            synchronized(this) {
                ftpOperationInProgress = false
            }
        }
    }

    private companion object {
        private const val TAG = "UdpUpdateServerCoord"
        private const val ALERT_THROTTLE_MS = 5 * 60 * 1000L // 5 минут
    }
}
