package ru.foric27.cluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Главный foreground-сервис cluster-проекта.
 *
 * Сервис оркестрирует жизненный цикл стрима, root-сети, FTP-обновлений, wake
 * recovery, watchdog и публикацию состояния в уведомлении. Это одна из самых
 * чувствительных точек проекта, поэтому здесь важнее предсказуемость и
 * наблюдаемость, чем красивая абстрактная архитектура.
 */
class UdpStreamService : Service(), VideoEncoder.RestartCallback {

    private val serviceLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val networkRootShell = NetworkRootShell()

    @Volatile private var streamActive = false
    @Volatile private var startInProgress = false
    @Volatile private var restartInProgress = false

    private var encoder: VideoEncoder? = null
    @Volatile private var sender: UdpSender? = null
    private var displayStateReceiver: BroadcastReceiver? = null
    private var displayStateReceiverRegistered = false
    private var usbMediaReceiver: BroadcastReceiver? = null
    private var usbMediaReceiverRegistered = false
    @Volatile private var activeRootIface: String? = null
    private var streamWakeLock: PowerManager.WakeLock? = null

    private var targetHost: String? = null
    private var targetPort: Int = 0
    private var lastCfg: StreamConfig? = null
    private var lastBindIp: String? = null
    private lateinit var updateCoordinator: UdpUpdateServerCoordinator
    private lateinit var wakeRecoveryController: UdpWakeRecoveryController
    private lateinit var serviceAlerts: UdpServiceAlerts
    private lateinit var recoveryScheduler: UdpServiceRecoveryScheduler
    private lateinit var restartController: UdpServiceRestartController
    private lateinit var statusSyncCoordinator: UdpStatusSyncCoordinator
    private lateinit var transportStatsCoordinator: UdpTransportStatsCoordinator
    private lateinit var connectivityWatchdogCoordinator: UdpConnectivityWatchdogCoordinator
    private lateinit var pipelineStartCoordinator: UdpPipelineStartCoordinator
    private lateinit var startupProbeCoordinator: UdpStartupProbeCoordinator
    private lateinit var startupFlowCoordinator: UdpStartupFlowCoordinator
    private lateinit var networkPreparationCoordinator: UdpNetworkPreparationCoordinator

    /**
     * Готовит сервисный runtime: wake lock, coordinators, receivers и канал
     * foreground-уведомления.
     */
    override fun onCreate() {
        super.onCreate()
        RuntimeConfig.init(applicationContext)
        RootNetUtil.attachNetworkRootShell(networkRootShell)
        if (!networkRootShell.isAvailable()) {
            Timber.tag(TAG).e("ROOT НЕ ДОСТУПЕН: сервис запущен без root-прав, сетевые функции и видеотрансляция будут недоступны")
        }
        serviceRunning = true
        streamActiveState = false
        initWakeLock()
        initCollaborators()
        ensureNotificationChannel()
        recoveryScheduler.cancel()
        wakeRecoveryController.register()
        registerDisplayStateReceiver()
        registerUsbMediaReceiver()
        scheduleStartupUpdateServerRefresh()
    }

    /**
     * Главная точка входа сервиса. Разруливает команды refresh/restart и
     * запускает подготовку сети перед стартом видеопайплайна.
     */
    @Volatile private var streamStoppedForSleep = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForegroundCompat(FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, buildNotification())
        try {
            when (intent?.action) {
                ACTION_STOP_STREAM -> return handleStopStreamAction()
                ACTION_START_STREAM -> return handleStartStreamAction()
                ACTION_REFRESH_FTP_NOW -> return handleRefreshFtpAction()
                ACTION_REFRESH_USB_FTP_NOW -> return handleRefreshUsbFtpAction()
                ACTION_REFRESH_USB_REMOVED_FTP_NOW -> return handleRefreshUsbRemovedFtpAction()
                ACTION_RESTART_PIPELINE_NOW -> {
                    val cfg = intent?.let { readConfig(it) } ?: lastCfg ?: StreamConfig.fixedConfig(this, getCurrentStreamMode())
                    return handleRestartPipelineAction(cfg)
                }
            }

            return handleStartupCommand(
                intent = intent,
                forceRestart = intent?.action == ACTION_RESTART_SERVICE_NOW,
            )
        } catch (se: SecurityException) {
            startInProgress = false
            Timber.tag(TAG).e(se, "Ошибка запуска: SecurityException")
            recoveryScheduler.schedule("security_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        } catch (t: Throwable) {
            startInProgress = false
            Timber.tag(TAG).e(t, "Ошибка запуска стрима")
            recoveryScheduler.schedule("startup_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.tag(TAG).w("Задача приложения удалена из recents; планирую восстановление сервиса")
        recoveryScheduler.schedule(
            reason = "task_removed",
            delayMs = TASK_REMOVED_RECOVERY_DELAY_MS,
            userReason = getString(R.string.app_recovery_restart_reason_task_removed),
            launchUi = false,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        streamActiveState = false
        serviceRunning = false
        synchronized(serviceLock) {
            restartInProgress = false
        }
        recoveryScheduler.schedule(
            reason = "service_destroyed",
            delayMs = SERVICE_RECOVERY_DELAY_MS,
            userReason = getString(R.string.app_recovery_restart_reason_service_destroyed),
        )
        wakeRecoveryController.unregister()
        unregisterDisplayStateReceiver()
        unregisterUsbMediaReceiver()
        stopInternalFull()
        networkRootShell.close()
        updateStreamWakeLock(held = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun requestRestart() {
        restartController.schedule("udp_error", null)
    }

    private fun readConfig(intent: Intent): StreamConfig {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra(EXTRA_CONFIG, StreamConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
        } ?: StreamConfig.fixedConfig(this, getCurrentStreamMode())
    }

    private fun initCollaborators() {
        initRecoveryCoordinators()
        initUpdateCoordinator()
        initNetworkCoordinators()
        initVideoCoordinators()
    }

    private fun initRecoveryCoordinators() {
        restartController = UdpServiceRestartController(
            scope = serviceScope,
            tag = TAG,
            onAttemptRestart = ::attemptRestart,
            notifyRestartScheduled = { delayMs ->
                serviceAlerts.notifyNoLinkOnce(
                    getString(R.string.service_notification_no_link_restart_fmt, delayMs / 1000),
                )
            },
        )
        serviceAlerts = UdpServiceAlerts(
            context = applicationContext,
            tag = TAG,
            updateNotification = ::updateNotification,
        )
        recoveryScheduler = UdpServiceRecoveryScheduler(
            context = this,
            tag = TAG,
            requestCode = SERVICE_RECOVERY_REQUEST_CODE,
            onScheduled = { delaySeconds ->
                updateNotification(getString(R.string.service_notification_service_recovery_fmt, delaySeconds))
            },
            onFallbackStart = {
                startServiceCompat(applicationContext)
            },
        )
        wakeRecoveryController = UdpWakeRecoveryController(
            context = this,
            scope = serviceScope,
            startDetachedWorker = ::startDetachedWorker,
            postToMain = { block -> mainHandler.post { block() } },
            registerLocalReceiver = ::registerLocalReceiver,
            unregisterReceiverBestEffort = ::unregisterReceiverBestEffort,
            snapshotProvider = ::buildWakeRecoverySnapshot,
            logPipelineSnapshot = ::logPipelineSnapshot,
            forceOutputFrame = { reason -> encoder?.forceOutputFrame(reason) ?: Unit },
            relaunchTargetActivity = { reason -> encoder?.relaunchTargetActivityIfNeeded(reason) ?: Unit },
            requestImmediateRecovery = ::requestImmediateRecovery,
            stopStream = ::stopStreamForSleep,
            startStream = ::resumeStreamAfterSleep,
        )
    }

    private fun initUpdateCoordinator() {
        updateCoordinator = UdpUpdateServerCoordinator(
            context = applicationContext,
            mainHandler = mainHandler,
            isServiceRunning = { serviceRunning },
            startDetachedWorker = ::startDetachedWorker,
            publishWarning = { AppWarningCenter.publish(it) },
        )
    }

    private fun initNetworkCoordinators() {
        statusSyncCoordinator = UdpStatusSyncCoordinator(
            context = applicationContext,
            tag = TAG,
            statusKeySyncInterval = STATUS_KEY_SYNC_INTERVAL,
            statusPort = STATUS_PORT,
            statusPeriodMs = STATUS_PERIOD_MS,
            statusErrorLogEvery = STATUS_ERROR_LOG_EVERY,
            registerLocalReceiver = ::registerLocalReceiver,
            unregisterReceiverBestEffort = ::unregisterReceiverBestEffort,
            launchWorker = ::launchWorker,
            interruptThreadQuietly = ::interruptThreadQuietly,
            joinThreadQuietly = ::joinThreadQuietly,
        )
        transportStatsCoordinator = UdpTransportStatsCoordinator(
            tag = TAG,
            senderSnapshotProvider = { sender?.snapshot() },
            statusSnapshotProvider = statusSyncCoordinator::snapshot,
            launchWorker = ::launchWorker,
            interruptThreadQuietly = ::interruptThreadQuietly,
            joinThreadQuietly = ::joinThreadQuietly,
        )
        connectivityWatchdogCoordinator = UdpConnectivityWatchdogCoordinator(
            tag = TAG,
            launchWorker = ::launchWorker,
            interruptThreadQuietly = ::interruptThreadQuietly,
            joinThreadQuietly = ::joinThreadQuietly,
            configProvider = { lastCfg },
            senderProvider = { sender },
            hostProvider = { targetHost },
            activeRootIfaceProvider = { activeRootIface },
            ipFromCidr = ::ipFromCidr,
            logRouteVerdict = serviceAlerts::logRouteVerdict,
            requestImmediateRecovery = ::requestImmediateRecovery,
            routeRecentSendGraceMs = ROUTE_RECENT_SEND_GRACE_MS,
            connectivityWatchdogPeriodMs = CONNECTIVITY_WATCHDOG_PERIOD_MS,
            ifaceMissingRestartBackoffMinMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
            noRouteRestartBackoffMinMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
            routeFailuresBeforeRestart = ROUTE_FAILURES_BEFORE_RESTART,
            defaultUsbLocalCidr = DEF_USB_LOCAL_CIDR,
        )
        networkPreparationCoordinator = UdpNetworkPreparationCoordinator(
            tag = TAG,
            defaultUsbLocalCidr = DEF_USB_LOCAL_CIDR,
            defaultUsbGateway = DEF_USB_GATEWAY,
            ipFromCidr = ::ipFromCidr,
            logRouteVerdict = serviceAlerts::logRouteVerdict,
        )
    }

    private fun initVideoCoordinators() {
        pipelineStartCoordinator = UdpPipelineStartCoordinator(
            tag = TAG,
            createVideoEncoder = { cfg, launchComponent, localSender ->
                VideoEncoder(
                    context = this,
                    streamConfig = cfg,
                    preferredLaunchComponent = launchComponent,
                    udpSender = localSender,
                    restartCallback = this,
                )
            },
            closeSenderQuietly = ::closeSenderQuietly,
            isCurrentSender = { localSender -> sender === localSender },
            clearSenderIfCurrent = { localSender ->
                if (sender === localSender) {
                    sender = null
                }
            },
            assignEncoder = { encoder = it },
            startStatusSync = statusSyncCoordinator::start,
            stopTransportStats = transportStatsCoordinator::stop,
            startTransportStats = transportStatsCoordinator::start,
            stopConnectivityWatchdog = connectivityWatchdogCoordinator::stop,
            startConnectivityWatchdog = connectivityWatchdogCoordinator::start,
            setStreamActive = {
                streamActive = it
                streamActiveState = it
            },
            setStartInProgress = { startInProgress = it },
            resetRestartBackoff = restartController::resetBackoff,
            setRestartBackoff = restartController::setBackoff,
            resetNoLinkWarning = serviceAlerts::resetNoLinkWarning,
            replayRootWarningIfPresent = serviceAlerts::replayRootWarningIfPresent,
            acquireStreamWakeLock = { updateStreamWakeLock(held = true) },
            releaseStreamWakeLock = { updateStreamWakeLock(held = false) },
            cancelPendingRestart = restartController::cancel,
            cancelRecovery = recoveryScheduler::cancel,
            updateNotification = { updateNotification(getNotificationStateText()) },
            scheduleRestart = restartController::schedule,
            stopEncoderQuietly = {
                runCatching { encoder?.stop() }
                    .onFailure { stopError ->
                        Timber.tag(TAG).w(stopError, "Не удалось остановить частично запущенный VideoEncoder")
                    }
            },
        )
        startupProbeCoordinator = UdpStartupProbeCoordinator(
            tag = TAG,
            routeWaitStepMs = ROUTE_WAIT_STEP_MS,
            closeSenderQuietly = ::closeSenderQuietly,
            clearSenderIfCurrent = { localSender ->
                if (sender === localSender) {
                    sender = null
                }
            },
            setStartInProgress = { startInProgress = it },
            increaseRestartBackoff = restartController::increaseBackoff,
            logPipelineSnapshot = ::logPipelineSnapshot,
            notifyNoRoute = { targetHostValue, backoffMs ->
                serviceAlerts.notifyNoLinkOnce(
                    getString(R.string.service_notification_no_route_fmt, targetHostValue, backoffMs / 1000),
                )
            },
            scheduleRestart = restartController::schedule,
        )
        startupFlowCoordinator = UdpStartupFlowCoordinator(
            tag = TAG,
            createSender = ::UdpSender,
            assignSender = { sender = it },
            setStartInProgress = { startInProgress = it },
            scheduleRestart = restartController::schedule,
            launchUdpProbe = { block ->
                launchWorker("UdpProbe", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                    block()
                }
            },
            postToMain = { block -> mainHandler.post { block() } },
            waitForUdpReady = startupProbeCoordinator::waitForUdpReady,
            handleRouteNotReady = startupProbeCoordinator::handleRouteNotReady,
        )
    }

    private fun initWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        streamWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream").apply {
            setReferenceCounted(false)
        }
    }

    private fun updateStreamWakeLock(held: Boolean) {
        val wakeLock = streamWakeLock ?: return
        if (held == wakeLock.isHeld) return
        runCatching {
            if (held) {
                wakeLock.acquire(STREAM_WAKE_LOCK_TIMEOUT_MS)
            } else {
                wakeLock.release()
            }
        }.onSuccess {
            val action = if (held) "Удерживаю" else "Освобождаю"
            Timber.tag(TAG).i("$action PARTIAL_WAKE_LOCK стрима")
        }.onFailure { t ->
            val action = if (held) "захватить" else "освободить"
            Timber.tag(TAG).w(t, "Не удалось $action PARTIAL_WAKE_LOCK")
        }
    }

    private fun buildWakeRecoverySnapshot(): UdpWakeRecoverySnapshot {
        if (!isVideoStreamModeSelected()) {
            return UdpWakeRecoverySnapshot(
                streamHealthy = true,
                requiresFullRecovery = false,
            )
        }
        val cfg = lastCfg ?: StreamConfig.fixedConfig(applicationContext, getCurrentStreamMode())
        val currentSender = sender
        val senderSnapshot = currentSender?.snapshot()
        val recentVideoTraffic = senderSnapshot?.lastSendElapsedRealtimeMs?.let { lastSendMs ->
            lastSendMs > 0L && (SystemClock.elapsedRealtime() - lastSendMs) <= ROUTE_RECENT_SEND_GRACE_MS
        } == true
        val displayId = VdspState.getDisplayId()
        val targetHostValue = targetHost?.takeIf { it.isNotBlank() } ?: cfg.ip
        val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: cfg.localCidr?.let(::ipFromCidr)
        val routeCheck = expectedBindIp?.takeIf { it.isNotBlank() }?.let {
            RootNetUtil.checkRouteTo(targetHostValue, it, forceProbe = true)
        }
        routeCheck?.let { serviceAlerts.logRouteVerdict("wake snapshot", it) }
        val runtimeSnapshot = RuntimeHealthSnapshot(
            streamActive = streamActive,
            startInProgress = startInProgress,
            senderReady = currentSender != null,
            displayReady = displayId >= 0,
            recentVideoTraffic = recentVideoTraffic,
            routeReady = routeCheck?.ok ?: true,

        )
        Timber.tag(TAG).i(
            "Wake snapshot: ${ConnectivityHealth.describeWakeSnapshot(runtimeSnapshot)} | " +
                ConnectivityHealth.describeWakeDecision(runtimeSnapshot),
        )
        return UdpWakeRecoverySnapshot(
            streamHealthy = ConnectivityHealth.isWakeStreamHealthy(runtimeSnapshot),
            requiresFullRecovery = ConnectivityHealth.requiresWakeFullRecovery(runtimeSnapshot),
        )
    }

    private fun registerDisplayStateReceiver() {
        registerManagedReceiver(
            isRegistered = { displayStateReceiverRegistered },
            setReceiver = { displayStateReceiver = it },
            setRegistered = { displayStateReceiverRegistered = it },
            createReceiver = {
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != VdspState.ACTION_VDSP_STATE_CHANGED) return
                        val state = intent.getStringExtra(VdspState.EXTRA_STATE).orEmpty()
                        val displayId = intent.getIntExtra(VdspState.EXTRA_DISPLAY_ID, -1)
                        Timber.tag(TAG).i("Состояние cluster display изменилось: state=$state, displayId=$displayId")
                        if (state == VdspState.DisplayState.REMOVED.wireValue && streamActive && !startInProgress) {
                            requestImmediateRecovery(
                                "display_removed",
                                NO_ROUTE_RESTART_BACKOFF_MIN_MS,
                                getString(R.string.service_notification_display_removed_restart),
                            )
                        }
                    }
                }
            },
            register = { receiver ->
                registerLocalReceiver(receiver, IntentFilter(VdspState.ACTION_VDSP_STATE_CHANGED))
            },
            failureMessage = "Не удалось зарегистрировать receiver состояния cluster display",
        )
    }

    private fun unregisterDisplayStateReceiver() {
        if (!displayStateReceiverRegistered) return
        unregisterReceiverBestEffort(displayStateReceiver, "cluster display state")
        displayStateReceiver = null
        displayStateReceiverRegistered = false
    }

    private fun registerUsbMediaReceiver() {
        registerManagedReceiver(
            isRegistered = { usbMediaReceiverRegistered },
            setReceiver = { usbMediaReceiver = it },
            setRegistered = { usbMediaReceiverRegistered = it },
            createReceiver = {
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val action = intent.action.orEmpty()
                        val path = intent.data?.path.orEmpty()
                        when (action) {
                            Intent.ACTION_MEDIA_MOUNTED -> {
                                if (!UsbStoragePathMatcher.isUsbStoragePath(path)) {
                                    Timber.tag(TAG).i("Пропускаю runtime mount не-USB носителя: $path")
                                    return
                                }
                                startDetachedWorker("UsbMountedRefresh") {
                                    updateCoordinator.refreshUsbUpdateServer()
                                    Timber.tag(TAG).i("USB вставлен во время работы приложения: $path, FTP обновлён")
                                }
                            }

                            Intent.ACTION_MEDIA_REMOVED,
                            Intent.ACTION_MEDIA_UNMOUNTED,
                            Intent.ACTION_MEDIA_EJECT -> {
                                if (path.isNotBlank() && !UsbStoragePathMatcher.isUsbStoragePath(path)) {
                                    Timber.tag(TAG).i("Пропускаю runtime remove не-USB носителя: $path")
                                    return
                                }
                                startDetachedWorker("UsbRemovedRefresh") {
                                    updateCoordinator.refreshAfterUsbRemoved()
                                    Timber.tag(TAG).i("USB извлечён во время работы приложения: $path, FTP обновлён")
                                }
                            }
                        }
                    }
                }
            },
            register = { receiver ->
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_MEDIA_MOUNTED)
                    addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                    addAction(Intent.ACTION_MEDIA_REMOVED)
                    addAction(Intent.ACTION_MEDIA_EJECT)
                    addDataScheme("file")
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(receiver, filter)
                }
            },
            failureMessage = "Не удалось зарегистрировать USB media receiver",
        )
    }

    private fun unregisterUsbMediaReceiver() {
        if (!usbMediaReceiverRegistered) return
        unregisterReceiverBestEffort(usbMediaReceiver, "usb media")
        usbMediaReceiver = null
        usbMediaReceiverRegistered = false
    }

    private fun attemptRestart(reason: String?) {
        if (!serviceRunning) {
            Timber.tag(TAG).w("attemptRestart отменён: сервис уже уничтожен")
            return
        }
        if (reason == "net_available" && (streamActive || startInProgress || sender != null)) {
            Timber.tag(TAG).i("Пропускаю отложенный restart(net_available): стрим уже активен")
            return
        }
        synchronized(serviceLock) {
            if (restartInProgress) {
                Timber.tag(TAG).i("Пропускаю attemptRestart: рестарт уже выполняется")
                return
            }
            restartInProgress = true
        }

        stopInternalKeepService()
        RootNetUtil.clearCaches()

        startDetachedWorker("RestartWorker") {
            try {
                val cfg = lastCfg ?: StreamConfig.fixedConfig(this@UdpStreamService, getCurrentStreamMode())
                val requestedTargetHost = targetHost ?: cfg.ip
                val networkPrep = networkPreparationCoordinator.prepare(cfg)

                if (networkPrep.rootRequired) {
                    mainHandler.post {
                        if (!serviceRunning) return@post
                        startInProgress = false
                        serviceAlerts.notifyRootRequiredOnce()
                    }
                    return@startDetachedWorker
                }

                if (!networkPrep.ifacePresent) {
                    mainHandler.post {
                        if (!serviceRunning) return@post
                        handleMissingInterface(scheduleRestart = true)
                    }
                    return@startDetachedWorker
                }

                mainHandler.post {
                    if (!serviceRunning) return@post
                    lastBindIp = networkPrep.bindIp
                    startPipelineAsync(
                        cfg = cfg,
                        targetHostValue = requestedTargetHost,
                        bindIp = networkPrep.bindIp,
                        launchComponent = cfg.launchComponent,
                        restartLog = true,
                    )
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    if (!serviceRunning) return@post
                    startInProgress = false
                    val backoffMs = restartController.increaseBackoff()
                    Timber.tag(TAG).w(t, "Попытка рестарта завершилась ошибкой; повторю позже. backoff=${backoffMs}ms")
                    serviceAlerts.notifyNoLinkOnce(getString(R.string.service_notification_no_connection_retry_fmt, backoffMs / 1000))
                    restartController.schedule("retry", t)
                }
            } finally {
                synchronized(serviceLock) {
                    restartInProgress = false
                }
            }
        }
    }

    private fun startOrRefreshUpdateServer() {
        updateCoordinator.startOrRefreshUpdateServer()
    }

    private fun scheduleStartupUpdateServerRefresh() {
        mainHandler.postDelayed(
            {
                if (!serviceRunning) return@postDelayed
                startDetachedWorker("StartupFtpRefresh") {
                    runCatching {
                        Timber.tag(TAG).i("Проверяю FTP обновлений после создания сервиса")
                        startOrRefreshUpdateServer()
                    }.onFailure { t ->
                        Timber.tag(TAG).e(t, "Ошибка отложенного запуска FTP обновлений")
                    }
                }
            },
            FTP_STARTUP_REFRESH_DELAY_MS,
        )
    }

    private fun stopInternalKeepService() {
        synchronized(serviceLock) {
            streamActive = false
            streamActiveState = false
            startInProgress = false
            connectivityWatchdogCoordinator.stop()
            transportStatsCoordinator.stop()
            statusSyncCoordinator.stop()
            updateCoordinator.stop()
            connectivityWatchdogCoordinator.resetRouteFailureStreak()
            activeRootIface = null
            updateStreamWakeLock(held = false)

            runCatching { encoder?.stop() }
                .onFailure { t -> Timber.tag(TAG).w(t, "Не удалось остановить VideoEncoder") }
            encoder = null

            runCatching { sender?.close() }
                .onFailure { t -> Timber.tag(TAG).w(t, "Не удалось закрыть UdpSender") }
            sender = null

            notifyMediaCoverFinish()
        }
    }

    private fun stopInternalKeepPipelineOnly() {
        synchronized(serviceLock) {
            streamActive = false
            streamActiveState = false
            startInProgress = false
            updateStreamWakeLock(held = false)

            runCatching { encoder?.stop() }
                .onFailure { t -> Timber.tag(TAG).w(t, "Не удалось остановить VideoEncoder") }
            encoder = null

            runCatching { sender?.close() }
                .onFailure { t -> Timber.tag(TAG).w(t, "Не удалось закрыть UdpSender") }
            sender = null

            notifyMediaCoverFinish()
        }
    }

    private fun notifyMediaCoverFinish() {
        try {
            sendBroadcast(
                Intent(MediaCoverActivity.ACTION_FINISH_MEDIA_COVER).setPackage(packageName)
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось отправить broadcast завершения MediaCoverActivity")
        }
    }

    private fun stopInternalFull() {
        restartController.cancel()
        stopInternalKeepService()
        UpdateServerManager.stopServer()
        RootNetUtil.clearCaches()
        PersistentVirtualDisplay.releaseAll()
    }

    private fun requestImmediateRecovery(
        reason: String,
        minBackoffMs: Long,
        userMessage: String,
    ) {
        startDetachedWorker("ImmediateRecovery") {
            if (!serviceRunning) return@startDetachedWorker
            restartController.prepareImmediateRecovery(
                reason = reason,
                minBackoffMs = minBackoffMs,
                logPipelineSnapshot = ::logPipelineSnapshot,
                userMessage = userMessage,
                notifyUser = serviceAlerts::notifyNoLinkOnce,
                beforeSchedule = {
                    stopInternalKeepService()
                    RootNetUtil.clearCaches()
                },
            )
        }
    }

    private fun startPipelineAsync(
        cfg: StreamConfig,
        targetHostValue: String,
        bindIp: String?,
        launchComponent: String?,
        restartLog: Boolean,
    ) {
        startupFlowCoordinator.start(
            hostValue = targetHostValue,
            port = targetPort,
            bindIp = bindIp,
            routeWaitTimeoutMs = ROUTE_WAIT_TIMEOUT_MS,
            noRouteRestartBackoffMinMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
            onReadyPipeline = { localSender ->
                pipelineStartCoordinator.startReadyPipeline(
                    cfg = cfg,
                    hostValue = targetHostValue,
                    port = targetPort,
                    bindIp = bindIp,
                    launchComponent = launchComponent,
                    restartLog = restartLog,
                    localSender = localSender,
                    isVideoModeSelected = isVideoStreamModeSelected(),
                )
            },
        )
    }

    private fun ipFromCidr(cidr: String): String? {
        return try {
            cidr.substringBefore('/').trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось извлечь IP из CIDR: $cidr")
            null
        }
    }

    private fun isVideoStreamModeSelected(): Boolean {
        return try {
            AppSettings.getSelectedClusterMode(applicationContext).isVideoStreamMode
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось определить cluster mode, оставляю видеотрансляцию включённой")
            true
        }
    }

    private fun getCurrentStreamMode(): AppSettings.UiStreamMode {
        return try {
            AppSettings.getSelectedMode(applicationContext)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось определить stream mode, использую NAV")
            AppSettings.UiStreamMode.NAV
        }
    }

    private fun launchWorker(
        name: String,
        threadPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
        block: () -> Unit,
    ): Thread {
        return Thread(
            {
                applyThreadPriority(threadPriority, name)
                block()
            },
            name,
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startDetachedWorker(name: String, block: () -> Unit) {
        serviceScope.launch(Dispatchers.Default) {
            block()
        }
    }

    private fun applyThreadPriority(threadPriority: Int, name: String) {
        try {
            Process.setThreadPriority(threadPriority)
            if (threadPriority != Process.THREAD_PRIORITY_DEFAULT) {
                Timber.tag(TAG).i("Приоритет потока повышен: $name -> $threadPriority")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось изменить приоритет потока $name")
        }
    }

    private fun registerLocalReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerManagedReceiver(
        isRegistered: () -> Boolean,
        setReceiver: (BroadcastReceiver?) -> Unit,
        setRegistered: (Boolean) -> Unit,
        createReceiver: () -> BroadcastReceiver,
        register: (BroadcastReceiver) -> Unit,
        failureMessage: String,
    ) {
        if (isRegistered()) return
        val receiver = createReceiver()
        runCatching { register(receiver) }
            .onSuccess {
                setReceiver(receiver)
                setRegistered(true)
            }
            .onFailure { t ->
                Timber.tag(TAG).w(t, failureMessage)
                setReceiver(null)
                setRegistered(false)
            }
    }

    private fun unregisterReceiverBestEffort(receiver: BroadcastReceiver?, label: String) {
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось снять receiver $label")
        }
    }

    private fun interruptThreadQuietly(thread: Thread?, label: String) {
        try {
            thread?.interrupt()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось прервать поток $label")
        }
    }

    private fun joinThreadQuietly(thread: Thread?, label: String) {
        try {
            thread?.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось дождаться завершения потока $label")
        }
    }

    private fun logPipelineSnapshot(prefix: String) {
        val senderSnapshot = sender?.snapshot()
        val displayId = VdspState.getDisplayId()
        val lastSendAgoMs = senderSnapshot?.lastSendElapsedRealtimeMs?.takeIf { it > 0L }?.let {
            SystemClock.elapsedRealtime() - it
        } ?: -1L
        Timber.tag(TAG).i(
            "Снимок сервиса | $prefix | streamActive=$streamActive, startInProgress=$startInProgress, displayId=$displayId, " +
                "sender=${senderSnapshot != null}, host=${senderSnapshot?.host ?: targetHost ?: "unknown"}, " +
                "videoFrames=${senderSnapshot?.videoFramesSent ?: 0}, videoPackets=${senderSnapshot?.videoPacketsSent ?: 0}, " +
                "sendErrors=${senderSnapshot?.sendErrors ?: 0}, lastSendAgo=${lastSendAgoMs}ms, routeFailureStreak=${connectivityWatchdogCoordinator.currentRouteFailureStreak()}",
        )
    }

    private fun ensureNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(
            CHANNEL_ID,
            RuntimeConfig.Service.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).also(notificationManager::createNotificationChannel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            MainActivity.createLaunchIntent(this, keepInForeground = true),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getNotificationStateText())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
        addNotificationActions(builder)
        return builder.build()
    }

    private fun addNotificationActions(builder: NotificationCompat.Builder) {
        builder.addAction(
            0,
            getString(R.string.service_notification_action_restart),
            buildServicePendingIntent(ACTION_RESTART_SERVICE_NOW, 1),
        )
    }

    private fun buildServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, UdpStreamService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
    }

    private fun getNotificationStateText(): String {
        return when {
            streamActive && streamStoppedForSleep -> getString(R.string.service_notification_wake_started)
            streamActive -> getString(R.string.service_notification_stream_running)
            streamStoppedForSleep -> getString(R.string.service_notification_sleep_stopped)
            else -> getString(R.string.service_notification_stream_stopped)
        }
    }

    private fun updateNotification(text: String = getNotificationStateText()) {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось обновить foreground-уведомление")
        }
    }

    private fun startForegroundCompat(type: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun closeSenderQuietly(localSender: UdpSender) {
        try {
            localSender.close()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось закрыть локальный UdpSender")
        }
    }

    private fun pendingIntentImmutableFlag(): Int {
        return PendingIntent.FLAG_IMMUTABLE
    }

    private fun handleStopStreamAction(): Int {
        synchronized(serviceLock) {
            if (streamActive) {
                streamStoppedForSleep = true
                stopInternalKeepService()
                PersistentVirtualDisplay.releaseAll()
                updateNotification(getString(R.string.service_notification_sleep_stopped))
            }
        }
        return START_STICKY
    }

    private fun handleStartStreamAction(): Int {
        synchronized(serviceLock) {
            streamStoppedForSleep = false
            if (!streamActive && !startInProgress) {
                attemptRestart("user_start_from_notification")
            } else {
                updateNotification(getNotificationStateText())
            }
        }
        return START_STICKY
    }

    private fun handleRestartPipelineAction(cfg: StreamConfig): Int {
        synchronized(serviceLock) {
            val bindIp = lastBindIp
            val host = targetHost ?: cfg.ip
            val port = targetPort.takeIf { it in 1..65535 } ?: cfg.port
            if (bindIp == null || host.isNullOrEmpty()) {
                Timber.tag(TAG).w("Невозможен pipeline-restart: bindIp=$bindIp, host=$host — выполняю полный рестарт")
                attemptRestart("mode_change_fallback")
                return START_STICKY
            }
            stopInternalKeepPipelineOnly()
            lastCfg = cfg
            targetHost = host
            targetPort = port
            startInProgress = true
            mainHandler.post {
                if (!serviceRunning || !startInProgress) return@post
                startPipelineAsync(
                    cfg = cfg,
                    targetHostValue = host,
                    bindIp = bindIp,
                    launchComponent = cfg.launchComponent,
                    restartLog = false,
                )
            }
            Timber.tag(TAG).i("Перезапущен только pipeline (режим изменён) без сетевой подготовки")
        }
        return START_STICKY
    }

    private fun handleRefreshFtpAction(): Int {
        launchUpdateRefreshWorker("RefreshFtpNow", "Ошибка немедленного обновления FTP") {
            startOrRefreshUpdateServer()
        }
        return START_STICKY
    }

    private fun handleRefreshUsbFtpAction(): Int {
        launchUpdateRefreshWorker("RefreshUsbFtpNow", "Ошибка USB-aware обновления FTP") {
            updateCoordinator.refreshUsbUpdateServer()
        }
        return START_STICKY
    }

    private fun handleRefreshUsbRemovedFtpAction(): Int {
        launchUpdateRefreshWorker("RefreshUsbRemovedFtpNow", "Ошибка обновления FTP после извлечения USB") {
            updateCoordinator.refreshAfterUsbRemoved()
        }
        return START_STICKY
    }

    private fun handleStartupCommand(intent: Intent?, forceRestart: Boolean): Int {
        val safeIntent = intent ?: createStartIntent(this).also {
            Timber.tag(TAG).w("onStartCommand(): получен null intent, использую fixedConfig(context)")
        }
        val cfg = readConfig(safeIntent)
        val requestedTargetHost = cfg.ip?.trim().takeIf { !it.isNullOrEmpty() }
        val requestedTargetPort = cfg.port.takeIf { it in 1..65535 }
        if (requestedTargetHost == null || requestedTargetPort == null) {
            startInProgress = false
            Timber.tag(TAG).e("Ошибка запуска: некорректные host/port")
            recoveryScheduler.schedule("invalid_config", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        }

        synchronized(serviceLock) {
            if (!forceRestart && lastCfg == cfg && (streamActive || startInProgress || sender != null)) {
                Timber.tag(TAG).i("Игнорирую повторный startCommand: стрим уже активен или запускается")
                launchUpdateRefreshWorker("DuplicateStartFtpRefresh", "Ошибка обновления FTP при повторном startCommand") {
                    startOrRefreshUpdateServer()
                }
                updateNotification(getNotificationStateText())
                return START_STICKY
            }

            stopInternalKeepService()
            restartController.cancel()

            lastCfg = cfg
            targetHost = requestedTargetHost
            targetPort = requestedTargetPort
            startInProgress = true
        }

        startDetachedWorker("StartupWorker") {
            try {
                val networkPrep = networkPreparationCoordinator.prepare(cfg)
                if (networkPrep.rootRequired) {
                    mainHandler.post {
                        if (!serviceRunning) return@post
                        startInProgress = false
                        serviceAlerts.notifyRootRequiredOnce()
                    }
                    return@startDetachedWorker
                }
                if (forceRestart) {
                    updateCoordinator.restartUpdateServer(UpdateFileLocator.SearchPolicy.USB_FIRST)
                } else {
                    startOrRefreshUpdateServer()
                }
                updateCoordinator.scheduleInternalUpdatePoll()
                if (!networkPrep.ifacePresent) {
                    handleMissingInterface(scheduleRestart = false)
                    return@startDetachedWorker
                }

                mainHandler.post {
                    if (!serviceRunning || lastCfg != cfg || !startInProgress) {
                        return@post
                    }
                    activeRootIface = networkPrep.ifaceName
                    lastBindIp = networkPrep.bindIp
                    startPipelineAsync(
                        cfg = cfg,
                        targetHostValue = requestedTargetHost,
                        bindIp = networkPrep.bindIp,
                        launchComponent = cfg.launchComponent,
                        restartLog = false,
                    )
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    if (!serviceRunning) return@post
                    startInProgress = false
                    Timber.tag(TAG).e(t, "Ошибка подготовки сети")
                    restartController.schedule("network_prep", t)
                }
            }
        }
        return START_STICKY
    }

    private fun launchUpdateRefreshWorker(name: String, errorMessage: String, action: () -> Unit) {
        startDetachedWorker(name) {
            runCatching { action() }
                .onFailure { t -> Timber.tag(TAG).e(t, errorMessage) }
        }
    }

    private fun stopStreamForSleep() {
        synchronized(serviceLock) {
            if (streamActive) {
                streamStoppedForSleep = true
                stopInternalKeepService()
                PersistentVirtualDisplay.releaseAll()
                Timber.tag(TAG).i("Экран выключен — стрим и VirtualDisplay освобождены для экономии заряда")
                updateNotification(getString(R.string.service_notification_sleep_stopped))
            }
        }
    }

    private fun resumeStreamAfterSleep() {
        synchronized(serviceLock) {
            streamStoppedForSleep = false
            if (!streamActive && !startInProgress) {
                attemptRestart("wake_recovery")
            } else {
                updateNotification(getString(R.string.service_notification_wake_started))
            }
        }
    }

    private fun handleMissingInterface(scheduleRestart: Boolean) {
        startInProgress = false
        val ifaceName = RootNetUtil.getSelectedIfaceName(force = true) ?: RuntimeConfig.Root.IFACE
        if (!RootNetUtil.wasSelectedIfaceEverPresent) {
            val logMessage = if (scheduleRestart) {
                "$ifaceName отсутствует на устройстве; повторный запуск отменён — интерфейс никогда не поднимался"
            } else {
                "$ifaceName отсутствует; запуск стрима отменён — интерфейс никогда не поднимался"
            }
            Timber.tag(TAG).i(logMessage)
            serviceAlerts.notifyNoLinkOnce(getString(R.string.service_notification_iface_missing_fmt, ifaceName, 0))
            return
        }

        val retryDelaySeconds = if (scheduleRestart) {
            val backoffMs = restartController.increaseBackoff(IFACE_MISSING_RESTART_BACKOFF_MIN_MS)
            Timber.tag(TAG).w("$ifaceName отсутствует на устройстве; повторю позже. backoff=${backoffMs}ms")
            serviceAlerts.notifyNoLinkOnce(getString(R.string.service_notification_iface_missing_fmt, ifaceName, backoffMs / 1000))
            restartController.schedule(RuntimeConfig.Root.MISSING_REASON, null)
            backoffMs / 1000
        } else {
            Timber.tag(TAG).w("$ifaceName отсутствует; запуск отложен до появления интерфейса")
            serviceAlerts.notifyNoLinkOnce(getString(R.string.service_notification_iface_missing_fmt, ifaceName, 0))
            0L
        }
    }

    companion object {
        const val EXTRA_CONFIG: String = "config"

        @Volatile private var serviceRunning: Boolean = false
        @Volatile private var streamActiveState: Boolean = false

        fun isServiceRunning(): Boolean = serviceRunning
        fun isStreamActive(): Boolean = streamActiveState

        fun createStartIntent(context: Context): Intent {
            val mode = try {
                AppSettings.getSelectedMode(context)
            } catch (t: Throwable) {
                AppSettings.UiStreamMode.NAV
            }
            return Intent(context, UdpStreamService::class.java).apply {
                putExtra(EXTRA_CONFIG, StreamConfig.fixedConfig(context, mode))
            }
        }

        fun startServiceCompat(context: Context) {
            val intent = createStartIntent(context)
            context.startForegroundService(intent)
        }

        fun restartServiceCompat(context: Context) {
            val intent = createStartIntent(context).apply {
                action = ACTION_RESTART_SERVICE_NOW
            }
            context.startForegroundService(intent)
        }

        fun restartPipelineCompat(context: Context) {
            val intent = createStartIntent(context).apply {
                action = ACTION_RESTART_PIPELINE_NOW
            }
            context.startForegroundService(intent)
        }

        fun refreshFtpCompat(context: Context) {
            val intent = Intent(context, UdpStreamService::class.java).apply {
                action = ACTION_REFRESH_FTP_NOW
            }
            context.startForegroundService(intent)
        }

        fun refreshUsbFtpCompat(context: Context) {
            val intent = Intent(context, UdpStreamService::class.java).apply {
                action = ACTION_REFRESH_USB_FTP_NOW
            }
            context.startForegroundService(intent)
        }

        fun refreshUsbRemovedFtpCompat(context: Context) {
            val intent = Intent(context, UdpStreamService::class.java).apply {
                action = ACTION_REFRESH_USB_REMOVED_FTP_NOW
            }
            context.startForegroundService(intent)
        }

        private const val ACTION_RESTART_SERVICE_NOW = "ru.foric27.cluster.action.RESTART_SERVICE_NOW"
        private const val ACTION_RESTART_PIPELINE_NOW = "ru.foric27.cluster.action.RESTART_PIPELINE_NOW"
        private const val ACTION_REFRESH_FTP_NOW = "ru.foric27.cluster.action.REFRESH_FTP_NOW"
        private const val ACTION_REFRESH_USB_FTP_NOW = "ru.foric27.cluster.action.REFRESH_USB_FTP_NOW"
        private const val ACTION_REFRESH_USB_REMOVED_FTP_NOW = "ru.foric27.cluster.action.REFRESH_USB_REMOVED_FTP_NOW"
        private const val ACTION_STOP_STREAM = "ru.foric27.cluster.action.STOP_STREAM"
        private const val ACTION_START_STREAM = "ru.foric27.cluster.action.START_STREAM"

        private const val TAG = "UdpStreamService"
        private const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
        private const val FTP_STARTUP_REFRESH_DELAY_MS = 1_500L
        private const val STREAM_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private val CHANNEL_ID: String
            get() = RuntimeConfig.Service.NOTIFICATION_CHANNEL_ID

        private val NOTIF_ID: Int
            get() = RuntimeConfig.Service.NOTIFICATION_ID

        private val SERVICE_RECOVERY_DELAY_MS: Long
            get() = RuntimeConfig.Service.SERVICE_RECOVERY_DELAY_MS

        private val TASK_REMOVED_RECOVERY_DELAY_MS: Long
            get() = RuntimeConfig.Service.TASK_REMOVED_RECOVERY_DELAY_MS

        private val SERVICE_RECOVERY_REQUEST_CODE: Int
            get() = RuntimeConfig.Service.SERVICE_RECOVERY_REQUEST_CODE

        private val ROUTE_WAIT_TIMEOUT_MS: Long
            get() = RuntimeConfig.Service.ROUTE_WAIT_TIMEOUT_MS

        private val ROUTE_WAIT_STEP_MS: Long
            get() = RuntimeConfig.Service.ROUTE_WAIT_STEP_MS

        private val NO_ROUTE_RESTART_BACKOFF_MIN_MS: Long
            get() = RuntimeConfig.Service.NO_ROUTE_RESTART_BACKOFF_MIN_MS

        private val IFACE_MISSING_RESTART_BACKOFF_MIN_MS: Long
            get() = RuntimeConfig.Service.IFACE_MISSING_RESTART_BACKOFF_MIN_MS

        private val CONNECTIVITY_WATCHDOG_PERIOD_MS: Long
            get() = RuntimeConfig.Service.CONNECTIVITY_WATCHDOG_PERIOD_MS

        private val ROUTE_RECENT_SEND_GRACE_MS: Long
            get() = RuntimeConfig.Service.ROUTE_RECENT_SEND_GRACE_MS

        private val ROUTE_FAILURES_BEFORE_RESTART: Int
            get() = RuntimeConfig.Service.ROUTE_FAILURES_BEFORE_RESTART

        private val THREAD_JOIN_TIMEOUT_MS: Long
            get() = RuntimeConfig.Service.THREAD_JOIN_TIMEOUT_MS

        private val STATUS_PORT: Int
            get() = RuntimeConfig.Network.STATUS_PORT

        private val STATUS_PERIOD_MS: Int
            get() = RuntimeConfig.Service.STATUS_PERIOD_MS

        private val STATUS_KEY_SYNC_INTERVAL: Int
            get() = RuntimeConfig.Service.STATUS_KEY_SYNC_INTERVAL

        private val STATUS_ERROR_LOG_EVERY: Int
            get() = RuntimeConfig.Service.STATUS_ERROR_LOG_EVERY

        private val DEF_USB_LOCAL_CIDR: String
            get() = RuntimeConfig.Network.LOCAL_CIDR

        private val DEF_USB_GATEWAY: String
            get() = RuntimeConfig.Network.GATEWAY
    }
}
