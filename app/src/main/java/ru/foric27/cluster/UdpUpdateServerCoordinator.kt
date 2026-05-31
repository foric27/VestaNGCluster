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

    @Volatile private var lastFtpRetryReport: String? = null
    @Volatile private var lastFtpFailureReport: String? = null
    @Volatile private var ftpRetryScheduled = false
    @Volatile private var ftpOperationInProgress = false
    @Volatile private var lastKnownUpdateSha256: String? = null
    @Volatile private var lastAlertShownTime: Long = 0L

    private val ftpRetryRunnable = Runnable { performFtpRetry() }

    fun startOrRefreshUpdateServer() {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Timber.tag(TAG).i("Пропускаю запуск FTP обновления: нет разрешения MANAGE_EXTERNAL_STORAGE")
            return
        }
        val result = runFtpOperation("startup") {
            UpdateServerManager.prepareAndStartServer(
                context,
                UpdateFileLocator.SearchPolicy.USB_ONLY,
            )
        } ?: return
        if (result.success) {
            Timber.tag(TAG).i("FTP обновление активно: ${result.boundAddress?.host}:${result.boundAddress?.port}")
            lastFtpFailureReport = null
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        reportFtpFailureOnce(
            report = "startup:${result.message}",
            logMessage = "FTP обновление не запущено: ${result.message}",
            warningMessage = context.getString(R.string.service_notification_ftp_message_fmt, result.message),
        )
        if (result.retrySuggested) {
            scheduleFtpRetry("startup")
        } else {
            cancelFtpRetry()
        }
    }

    fun restartUpdateServer(searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.USB_ONLY) {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Timber.tag(TAG).i("Пропускаю перезапуск FTP обновления: нет разрешения MANAGE_EXTERNAL_STORAGE")
            return
        }
        val result = runFtpOperation("restart") {
            UpdateServerManager.replaceAndStartServer(context, searchPolicy)
        } ?: return
        if (result.success) {
            val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
            Timber.tag(TAG).i("FTP перезапущен: $address")
            lastFtpFailureReport = null
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        reportFtpFailureOnce(
            report = "restart:${result.message}",
            logMessage = "FTP после перезапуска не поднят: ${result.message}",
            warningMessage = context.getString(R.string.service_notification_ftp_message_fmt, result.message),
        )
        if (result.retrySuggested) {
            scheduleFtpRetry("restart")
        } else {
            cancelFtpRetry()
        }
    }

    fun refreshUsbUpdateServer() {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Timber.tag(TAG).i("Пропускаю USB-обновление FTP: нет разрешения MANAGE_EXTERNAL_STORAGE")
            return
        }
        val result = runFtpOperation("usb_refresh") {
            UpdateServerManager.replaceAndStartServer(
                context,
                UpdateFileLocator.SearchPolicy.USB_ONLY,
            )
        } ?: return
        if (result.success) {
            val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
            Timber.tag(TAG).i("FTP обновлён по USB-aware пути: $address")
            lastFtpFailureReport = null
            cancelFtpRetry()
            checkAndShowUpdateAlert(result)
            return
        }

        reportFtpFailureOnce(
            report = "usb_refresh:${result.message}",
            logMessage = "USB-aware обновление FTP не запустило сервер: ${result.message}",
            warningMessage = context.getString(R.string.service_notification_ftp_message_fmt, result.message),
        )
        if (result.retrySuggested) {
            scheduleFtpRetry("usb_refresh")
        } else {
            cancelFtpRetry()
        }
    }

    fun refreshAfterUsbRemoved() {
        val result = runFtpOperation("usb_removed") {
            UpdateServerManager.replaceAndStartServer(
                context = context,
                searchPolicy = UpdateFileLocator.SearchPolicy.USB_ONLY,
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
        lastKnownUpdateSha256 = null
        lastAlertShownTime = 0L
        UpdateAlertActivity.dismiss(context)
        cancelFtpRetry()
    }

    fun stop() {
        cancelFtpRetry()
    }

    fun cancelFtpRetry() {
        ftpRetryScheduled = false
        mainHandler.removeCallbacks(ftpRetryRunnable)
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
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            Timber.tag(TAG).i("Отменяю повторный запуск FTP: нет разрешения MANAGE_EXTERNAL_STORAGE")
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
            lastFtpFailureReport = null
        } else if (result.retrySuggested) {
            scheduleFtpRetry("ftp_retry")
        } else {
            cancelFtpRetry()
        }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Ошибка повторного запуска FTP")
                scheduleFtpRetry("ftp_retry_exception")
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
                UpdateFileLocator.SearchPolicy.USB_ONLY,
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

    private fun reportFtpFailureOnce(
        report: String,
        logMessage: String,
        warningMessage: String,
    ) {
        if (report == lastFtpFailureReport) return
        lastFtpFailureReport = report
        Timber.tag(TAG).w(logMessage)
        publishWarning(warningMessage)
    }

    private companion object {
        private const val TAG = "UdpUpdateServerCoord"
        private const val ALERT_THROTTLE_MS = 5 * 60 * 1000L // 5 минут
    }
}
