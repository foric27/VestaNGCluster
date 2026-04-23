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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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

    private var host: String? = null
    private var port: Int = 0
    private var lastCfg: StreamConfig? = null
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
        sServiceRunning = true
        sStreamActive = false
        initWakeLock()
        initCollaborators()
        ensureNotificationChannel()
        recoveryScheduler.cancel()
        wakeRecoveryController.register()
        registerDisplayStateReceiver()
        registerUsbMediaReceiver()
    }

    /**
     * Главная точка входа сервиса. Разруливает команды refresh/restart и
     * запускает подготовку сети перед стартом видеопайплайна.
     */
    @Volatile private var sleepStopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForegroundCompat(FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, buildNotification())
        try {
            val action = intent?.action
            when (action) {
                ACTION_STOP_STREAM -> {
                    synchronized(serviceLock) {
                        if (streamActive) {
                            sleepStopped = true
                            stopInternalKeepService()
                            updateNotification(getString(R.string.service_notification_sleep_stopped))
                        }
                    }
                    return START_STICKY
                }
                ACTION_START_STREAM -> {
                    synchronized(serviceLock) {
                        sleepStopped = false
                        if (!streamActive && !startInProgress) {
                            attemptRestart("user_start_from_notification")
                        } else {
                            updateNotification(getNotificationStateText())
                        }
                    }
                    return START_STICKY
                }
                ACTION_REFRESH_FTP_NOW -> {
                    startDetachedWorker("RefreshFtpNow") {
                        try {
                            startOrRefreshUpdateServer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Ошибка немедленного обновления FTP", t)
                        }
                    }
                    return START_STICKY
                }
            }

            val forceRestart = action == ACTION_RESTART_SERVICE_NOW
            val safeIntent = intent ?: createStartIntent(this).also {
                Log.w(TAG, "onStartCommand(): получен null intent, использую fixedConfig(context)")
            }
            val cfg = readConfig(safeIntent)
            val targetHost = cfg.ip?.trim().orEmpty()
            val targetPort = cfg.port
            if (targetHost.isEmpty() || targetPort !in 1..65535) {
                startInProgress = false
                Log.e(TAG, "Ошибка запуска: некорректные host/port")
                recoveryScheduler.schedule("invalid_config", SERVICE_RECOVERY_DELAY_MS)
                return START_STICKY
            }

            synchronized(serviceLock) {
                if (!forceRestart && lastCfg == cfg && (streamActive || startInProgress || sender != null)) {
                    Log.i(TAG, "Игнорирую повторный startCommand: стрим уже активен или запускается")
                    updateNotification(getNotificationStateText())
                    return START_STICKY
                }

                stopInternalKeepService()
                restartController.cancel()

                lastCfg = cfg
                host = targetHost
                port = targetPort
                startInProgress = true
            }

            startDetachedWorker("StartupWorker") {
                try {
                    startOrRefreshUpdateServer()
                    updateCoordinator.scheduleInternalUpdatePoll()
                    val networkPrep = networkPreparationCoordinator.prepare(cfg)
                    mainHandler.post {
                        if (!sServiceRunning || lastCfg != cfg || !startInProgress) {
                            return@post
                        }
                        if (networkPrep.rootRequired) {
                            startInProgress = false
                            serviceAlerts.notifyRootRequiredOnce()
                            return@post
                        }
                        activeRootIface = if (networkPrep.ifacePresent) {
                            networkPrep.ifaceName
                        } else {
                            null
                        }
                        startPipelineAsync(
                            cfg = cfg,
                            hostValue = targetHost,
                            bindIp = networkPrep.bindIp,
                            launchComponent = cfg.launchComponent,
                            restartLog = false,
                        )
                    }
                } catch (t: Throwable) {
                    mainHandler.post {
                        if (!sServiceRunning) return@post
                        startInProgress = false
                        Log.e(TAG, "Ошибка подготовки сети", t)
                        restartController.schedule("network_prep", t)
                    }
                }
            }
            return START_STICKY
        } catch (se: SecurityException) {
            startInProgress = false
            Log.e(TAG, "Ошибка запуска: SecurityException", se)
            recoveryScheduler.schedule("security_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        } catch (t: Throwable) {
            startInProgress = false
            Log.e(TAG, "Ошибка запуска стрима", t)
            recoveryScheduler.schedule("startup_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Задача приложения удалена из recents; планирую восстановление сервиса")
        recoveryScheduler.schedule(
            reason = "task_removed",
            delayMs = TASK_REMOVED_RECOVERY_DELAY_MS,
            userReason = getString(R.string.app_recovery_restart_reason_task_removed),
            launchUi = false,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sStreamActive = false
        sServiceRunning = false
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
        releaseStreamWakeLock()
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
        } ?: StreamConfig.fixedConfig(this)
    }

    private fun initCollaborators() {
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
        updateCoordinator = UdpUpdateServerCoordinator(
            context = applicationContext,
            mainHandler = mainHandler,
            isServiceRunning = { sServiceRunning },
            startDetachedWorker = ::startDetachedWorker,
            publishWarning = { AppWarningCenter.publish(it) },
        )
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
            hostProvider = { host },
            activeRootIfaceProvider = { activeRootIface },
            ipFromCidr = ::ipFromCidr,
            logRouteVerdict = serviceAlerts::logRouteVerdict,
            evaluatePeerReachability = ::evaluatePeerReachability,
            requestImmediateRecovery = ::requestImmediateRecovery,
            routeRecentSendGraceMs = ROUTE_RECENT_SEND_GRACE_MS,
            connectivityWatchdogPeriodMs = CONNECTIVITY_WATCHDOG_PERIOD_MS,
            ifaceMissingRestartBackoffMinMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
            noRouteRestartBackoffMinMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
            routeFailuresBeforeRestart = ROUTE_FAILURES_BEFORE_RESTART,
            defaultUsbLocalCidr = DEF_USB_LOCAL_CIDR,
        )
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
                sStreamActive = it
            },
            setStartInProgress = { startInProgress = it },
            resetRestartBackoff = restartController::resetBackoff,
            setRestartBackoff = restartController::setBackoff,
            resetNoLinkWarning = serviceAlerts::resetNoLinkWarning,
            replayRootWarningIfPresent = serviceAlerts::replayRootWarningIfPresent,
            acquireStreamWakeLock = ::acquireStreamWakeLock,
            releaseStreamWakeLock = ::releaseStreamWakeLock,
            cancelPendingRestart = restartController::cancel,
            cancelRecovery = recoveryScheduler::cancel,
            updateNotification = { updateNotification(getNotificationStateText()) },
            scheduleRestart = restartController::schedule,
            stopEncoderQuietly = {
                try {
                    encoder?.stop()
                } catch (stopError: Throwable) {
                    Log.w(TAG, "Не удалось остановить частично запущенный VideoEncoder", stopError)
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
            notifyNoRoute = { hostValue, backoffMs ->
                serviceAlerts.notifyNoLinkOnce(
                    getString(R.string.service_notification_no_route_fmt, hostValue, backoffMs / 1000),
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
        networkPreparationCoordinator = UdpNetworkPreparationCoordinator(
            tag = TAG,
            defaultUsbLocalCidr = DEF_USB_LOCAL_CIDR,
            defaultUsbGateway = DEF_USB_GATEWAY,
            ipFromCidr = ::ipFromCidr,
            logRouteVerdict = serviceAlerts::logRouteVerdict,
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
            stopStream = {
                synchronized(serviceLock) {
                    if (streamActive) {
                        sleepStopped = true
                        stopInternalKeepService()
                        updateNotification(getString(R.string.service_notification_sleep_stopped))
                    }
                }
            },
            startStream = {
                synchronized(serviceLock) {
                    sleepStopped = false
                    if (!streamActive && !startInProgress) {
                        attemptRestart("wake_recovery")
                    } else {
                        updateNotification(getString(R.string.service_notification_wake_started))
                    }
                }
            },
        )
    }

    private fun initWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        streamWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream").apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireStreamWakeLock() {
        val wakeLock = streamWakeLock ?: return
        if (wakeLock.isHeld) return
        try {
            wakeLock.acquire(STREAM_WAKE_LOCK_TIMEOUT_MS)
            Log.i(TAG, "Удерживаю PARTIAL_WAKE_LOCK для активного стрима")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось захватить PARTIAL_WAKE_LOCK", t)
        }
    }

    private fun releaseStreamWakeLock() {
        val wakeLock = streamWakeLock ?: return
        if (!wakeLock.isHeld) return
        try {
            wakeLock.release()
            Log.i(TAG, "Освобождаю PARTIAL_WAKE_LOCK стрима")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить PARTIAL_WAKE_LOCK", t)
        }
    }

    private fun buildWakeRecoverySnapshot(): UdpWakeRecoverySnapshot {
        if (!isVideoStreamModeSelected()) {
            return UdpWakeRecoverySnapshot(
                streamHealthy = true,
                requiresFullRecovery = false,
            )
        }
        val cfg = lastCfg ?: StreamConfig.fixedConfig(applicationContext)
        val currentSender = sender
        val senderSnapshot = currentSender?.snapshot()
        val recentVideoTraffic = senderSnapshot?.lastSendElapsedRealtimeMs?.let { lastSendMs ->
            lastSendMs > 0L && (SystemClock.elapsedRealtime() - lastSendMs) <= ROUTE_RECENT_SEND_GRACE_MS
        } == true
        val displayId = VdspState.getDisplayId()
        val targetHost = host?.takeIf { it.isNotBlank() } ?: cfg.ip
        val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: cfg.localCidr?.let(::ipFromCidr)
        val routeCheck = if (!expectedBindIp.isNullOrBlank()) {
            RootNetUtil.checkRouteTo(targetHost, expectedBindIp, forceProbe = true)
        } else {
            null
        }
        routeCheck?.let { serviceAlerts.logRouteVerdict("wake snapshot", it) }
        val runtimeSnapshot = RuntimeHealthSnapshot(
            streamActive = streamActive,
            startInProgress = startInProgress,
            senderReady = currentSender != null,
            displayReady = displayId >= 0,
            recentVideoTraffic = recentVideoTraffic,
            routeReady = routeCheck?.ok ?: true,
            peerCheck = evaluatePeerReachability(targetHost, force = true),
        )
        Log.i(
            TAG,
            "Wake snapshot: ${ConnectivityHealth.describeWakeSnapshot(runtimeSnapshot)} | " +
                ConnectivityHealth.describeWakeDecision(runtimeSnapshot),
        )
        return UdpWakeRecoverySnapshot(
            streamHealthy = ConnectivityHealth.isWakeStreamHealthy(runtimeSnapshot),
            requiresFullRecovery = ConnectivityHealth.requiresWakeFullRecovery(runtimeSnapshot),
        )
    }

    private fun registerDisplayStateReceiver() {
        if (displayStateReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != VdspState.ACTION_VDSP_STATE_CHANGED) return
                val state = intent.getStringExtra(VdspState.EXTRA_STATE).orEmpty()
                val displayId = intent.getIntExtra(VdspState.EXTRA_DISPLAY_ID, -1)
                Log.i(TAG, "Состояние cluster display изменилось: state=$state, displayId=$displayId")
                if (state == VdspState.DisplayState.REMOVED.wireValue && streamActive && !startInProgress) {
                    requestImmediateRecovery(
                        "display_removed",
                        NO_ROUTE_RESTART_BACKOFF_MIN_MS,
                        getString(R.string.service_notification_display_removed_restart),
                    )
                }
            }
        }
        try {
            registerLocalReceiver(receiver, IntentFilter(VdspState.ACTION_VDSP_STATE_CHANGED))
            displayStateReceiver = receiver
            displayStateReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать receiver состояния cluster display", t)
        }
    }

    private fun unregisterDisplayStateReceiver() {
        if (!displayStateReceiverRegistered) return
        unregisterReceiverBestEffort(displayStateReceiver, "cluster display state")
        displayStateReceiver = null
        displayStateReceiverRegistered = false
    }

    private fun registerUsbMediaReceiver() {
        if (usbMediaReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action.orEmpty()
                val path = intent.data?.path.orEmpty()
                when (action) {
                    Intent.ACTION_MEDIA_MOUNTED -> {
                        if (!UsbStoragePathMatcher.isUsbStoragePath(path)) {
                            Log.i(TAG, "Пропускаю runtime mount не-USB носителя: $path")
                            return
                        }
                        startDetachedWorker("UsbMountedRefresh") {
                            val result = UpdateServerManager.handleUsbInserted(applicationContext)
                            Log.i(TAG, "USB вставлен во время работы приложения: $path, результат FTP: ${result.message}")
                        }
                    }
                    Intent.ACTION_MEDIA_REMOVED,
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_EJECT -> {
                        if (path.isNotBlank() && !UsbStoragePathMatcher.isUsbStoragePath(path)) {
                            Log.i(TAG, "Пропускаю runtime remove не-USB носителя: $path")
                            return
                        }
                        startDetachedWorker("UsbRemovedRefresh") {
                            val result = UpdateServerManager.handleUsbRemoved(applicationContext)
                            Log.i(TAG, "USB извлечён во время работы приложения: $path, результат FTP: ${result.message}")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            usbMediaReceiver = receiver
            usbMediaReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать USB media receiver", t)
            usbMediaReceiver = null
            usbMediaReceiverRegistered = false
        }
    }

    private fun unregisterUsbMediaReceiver() {
        if (!usbMediaReceiverRegistered) return
        unregisterReceiverBestEffort(usbMediaReceiver, "usb media")
        usbMediaReceiver = null
        usbMediaReceiverRegistered = false
    }

    private fun attemptRestart(reason: String?) {
        if (!sServiceRunning) {
            Log.w(TAG, "attemptRestart отменён: сервис уже уничтожен")
            return
        }
        if (reason == "net_available" && (streamActive || startInProgress || sender != null)) {
            Log.i(TAG, "Пропускаю отложенный restart(net_available): стрим уже активен")
            return
        }
        synchronized(serviceLock) {
            if (restartInProgress) {
                Log.i(TAG, "Пропускаю attemptRestart: рестарт уже выполняется")
                return
            }
            restartInProgress = true
        }

        stopInternalKeepService()
        RootNetUtil.clearCaches()

        startDetachedWorker("RestartWorker") {
            try {
                val cfg = lastCfg ?: StreamConfig.fixedConfig(this@UdpStreamService)
                val targetHost = host ?: cfg.ip
                val networkPrep = networkPreparationCoordinator.prepare(cfg)

                if (networkPrep.rootRequired) {
                    mainHandler.post {
                        if (!sServiceRunning) return@post
                        startInProgress = false
                        serviceAlerts.notifyRootRequiredOnce()
                    }
                    return@startDetachedWorker
                }

                if (!networkPrep.ifacePresent) {
                    mainHandler.post {
                        if (!sServiceRunning) return@post
                        startInProgress = false
                        val backoffMs = restartController.increaseBackoff(IFACE_MISSING_RESTART_BACKOFF_MIN_MS)
                        val ifaceName = RootNetUtil.getSelectedIfaceName(force = true) ?: RuntimeConfig.Root.IFACE
                        Log.w(TAG, "$ifaceName отсутствует на устройстве; повторю позже. backoff=${backoffMs}ms")
                        serviceAlerts.notifyNoLinkOnce(getString(R.string.service_notification_iface_missing_fmt, ifaceName, backoffMs / 1000))
                        restartController.schedule(RuntimeConfig.Root.MISSING_REASON, null)
                    }
                    return@startDetachedWorker
                }

                mainHandler.post {
                    if (!sServiceRunning) return@post
                    startPipelineAsync(
                        cfg = cfg,
                        hostValue = targetHost,
                        bindIp = networkPrep.bindIp,
                        launchComponent = cfg.launchComponent,
                        restartLog = true,
                    )
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    if (!sServiceRunning) return@post
                    startInProgress = false
                    val backoffMs = restartController.increaseBackoff()
                    Log.w(TAG, "Попытка рестарта завершилась ошибкой; повторю позже. backoff=${backoffMs}ms", t)
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

    private fun stopInternalKeepService() {
        synchronized(serviceLock) {
            connectivityWatchdogCoordinator.stop()
            transportStatsCoordinator.stop()
            statusSyncCoordinator.stop()
            updateCoordinator.stop()
            connectivityWatchdogCoordinator.resetRouteFailureStreak()
            activeRootIface = null
            releaseStreamWakeLock()

            try {
                encoder?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось остановить VideoEncoder", t)
            }
            encoder = null

            streamActive = false
            sStreamActive = false
            startInProgress = false

            try {
                sender?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось закрыть UdpSender", t)
            }
            sender = null
        }
    }

    private fun stopInternalFull() {
        restartController.cancel()
        stopInternalKeepService()
        UpdateServerManager.stopServer()
        RootNetUtil.clearCaches()
        PersistentVirtualDisplay.detachSurface()
    }
    private fun requestImmediateRecovery(
        reason: String,
        minBackoffMs: Long,
        userMessage: String,
    ) {
        startDetachedWorker("ImmediateRecovery") {
            if (!sServiceRunning) return@startDetachedWorker
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
        hostValue: String,
        bindIp: String?,
        launchComponent: String?,
        restartLog: Boolean,
    ) {
        startupFlowCoordinator.start(
            hostValue = hostValue,
            port = port,
            bindIp = bindIp,
            routeWaitTimeoutMs = ROUTE_WAIT_TIMEOUT_MS,
            noRouteRestartBackoffMinMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
            onReadyPipeline = { localSender ->
                pipelineStartCoordinator.startReadyPipeline(
                    cfg = cfg,
                    hostValue = hostValue,
                    port = port,
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
            Log.w(TAG, "Не удалось извлечь IP из CIDR: $cidr", t)
            null
        }
    }

    private fun evaluatePeerReachability(targetHost: String, force: Boolean): PeerCheckResult {
        // Ping через root удалён; проверка доступности выполняется только через UDP-probe
        return PeerCheckResult(attempted = false, ok = false)
    }

    private fun isVideoStreamModeSelected(): Boolean {
        return try {
            AppSettings.getSelectedClusterMode(applicationContext).isVideoStreamMode
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось определить cluster mode, оставляю видеотрансляцию включённой", t)
            true
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
                Log.i(TAG, "Приоритет потока повышен: $name -> $threadPriority")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось изменить приоритет потока $name", t)
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

    private fun unregisterReceiverBestEffort(receiver: BroadcastReceiver?, label: String) {
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять receiver $label", t)
        }
    }

    private fun interruptThreadQuietly(thread: Thread?, label: String) {
        try {
            thread?.interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось прервать поток $label", t)
        }
    }

    private fun joinThreadQuietly(thread: Thread?, label: String) {
        try {
            thread?.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось дождаться завершения потока $label", t)
        }
    }

    private fun logPipelineSnapshot(prefix: String) {
        val senderSnapshot = sender?.snapshot()
        val displayId = VdspState.getDisplayId()
        val lastSendAgoMs = senderSnapshot?.lastSendElapsedRealtimeMs?.takeIf { it > 0L }?.let {
            SystemClock.elapsedRealtime() - it
        } ?: -1L
        Log.i(
            TAG,
            "Снимок сервиса | $prefix | streamActive=$streamActive, startInProgress=$startInProgress, displayId=$displayId, " +
                "sender=${senderSnapshot != null}, host=${senderSnapshot?.host ?: host ?: "unknown"}, " +
                "videoFrames=${senderSnapshot?.videoFramesSent ?: 0}, videoPackets=${senderSnapshot?.videoPacketsSent ?: 0}, " +
                "sendErrors=${senderSnapshot?.sendErrors ?: 0}, lastSendAgo=${lastSendAgoMs}ms, routeFailureStreak=${connectivityWatchdogCoordinator.currentRouteFailureStreak()}",
        )
    }

    private fun ensureNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, RuntimeConfig.Service.NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
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
            streamActive && sleepStopped -> getString(R.string.service_notification_wake_started)
            streamActive -> getString(R.string.service_notification_stream_running)
            sleepStopped -> getString(R.string.service_notification_sleep_stopped)
            else -> getString(R.string.service_notification_stream_stopped)
        }
    }

    private fun updateNotification(text: String = getNotificationStateText()) {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось обновить foreground-уведомление", t)
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
            Log.w(TAG, "Не удалось закрыть локальный UdpSender", t)
        }
    }

    private fun pendingIntentImmutableFlag(): Int {
        return PendingIntent.FLAG_IMMUTABLE
    }

    companion object {
        const val EXTRA_CONFIG: String = "config"

        @Volatile private var sServiceRunning: Boolean = false
        @Volatile private var sStreamActive: Boolean = false

        fun isServiceRunning(): Boolean = sServiceRunning
        fun isStreamActive(): Boolean = sStreamActive

        fun createStartIntent(context: Context): Intent {
            return Intent(context, UdpStreamService::class.java).apply {
                putExtra(EXTRA_CONFIG, StreamConfig.fixedConfig(context))
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

        fun refreshFtpCompat(context: Context) {
            val intent = Intent(context, UdpStreamService::class.java).apply {
                action = ACTION_REFRESH_FTP_NOW
            }
            context.startForegroundService(intent)
        }

        private const val ACTION_RESTART_SERVICE_NOW = "ru.foric27.cluster.action.RESTART_SERVICE_NOW"
        private const val ACTION_REFRESH_FTP_NOW = "ru.foric27.cluster.action.REFRESH_FTP_NOW"
        private const val ACTION_STOP_STREAM = "ru.foric27.cluster.action.STOP_STREAM"
        private const val ACTION_START_STREAM = "ru.foric27.cluster.action.START_STREAM"

        private const val TAG = "UdpStreamService"
        private const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
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
