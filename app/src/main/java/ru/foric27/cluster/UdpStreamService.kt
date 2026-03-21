package ru.foric27.cluster

import android.app.AlarmManager
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val restartScheduled = AtomicBoolean(false)
    private val internalUpdatePollScheduled = AtomicBoolean(false)
    private val ftpRetryScheduled = AtomicBoolean(false)
    private val internalUpdatePollRunnable = Runnable { performInternalUpdatePoll() }
    private val ftpRetryRunnable = Runnable { performFtpRetry() }
    private val restartRunnable = Runnable { attemptRestart(pendingRestartReason) }
    private val wakeRecoveryVerifyRunnable = Runnable { verifyWakeRecovery() }

    private var restartBackoffMs = RuntimeConfig.Service.RESTART_BACKOFF_START_MS
    private var lastRestartRequestMs = 0L
    private var noLinkNotified = false

    @Volatile private var pendingRestartReason: String? = null
    @Volatile private var streamActive = false
    @Volatile private var startInProgress = false
    @Volatile private var rootWarningShown = false
    @Volatile private var lastInternalUpdatePollReport: String? = null
    @Volatile private var lastFtpRetryReport: String? = null

    private var encoder: VideoEncoder? = null
    private var sender: UdpSender? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiverRegistered = false
    private var displayStateReceiver: BroadcastReceiver? = null
    private var displayStateReceiverRegistered = false
    private var usbMediaReceiver: BroadcastReceiver? = null
    private var usbMediaReceiverRegistered = false
    @Volatile private var screenSleepStartedAtMs = 0L
    @Volatile private var lastWakeRecoveryAtMs = 0L
    @Volatile private var pendingWakeAction: String? = null
    @Volatile private var wakeRecoveryStage: Int = 0
    private var statusThread: Thread? = null
    private val statusStop = AtomicBoolean(false)
    private var syncHandler: SyncHandler? = null
    private var statusSocket: java.net.DatagramSocket? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var statusReceiverRegistered = false
    private val statusPacketsSent = java.util.concurrent.atomic.AtomicLong(0)
    private val statusBytesSent = java.util.concurrent.atomic.AtomicLong(0)
    private val statusSendErrors = java.util.concurrent.atomic.AtomicLong(0)
    private var statsThread: Thread? = null
    private val statsStop = AtomicBoolean(false)
    private var watchdogThread: Thread? = null
    private val watchdogStop = AtomicBoolean(false)
    @Volatile private var routeFailureStreak = 0
    private var boundNetwork: Network? = null
    @Volatile private var lastSeenEthNetwork: Network? = null
    private var linkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeRootIface: String? = null
    private var streamWakeLock: PowerManager.WakeLock? = null

    private var host: String? = null
    private var port: Int = 0
    private var lastCfg: StreamConfig? = null
    private lateinit var updateCoordinator: UdpUpdateServerCoordinator
    private lateinit var wakeRecoveryController: UdpWakeRecoveryController

    /**
     * Готовит сервисный runtime: wake lock, coordinators, receivers и канал
     * foreground-уведомления.
     */
    override fun onCreate() {
        super.onCreate()
        RuntimeConfig.init(applicationContext)
        restartBackoffMs = RuntimeConfig.Service.RESTART_BACKOFF_START_MS
        sServiceRunning = true
        sStreamActive = false
        initWakeLock()
        initCollaborators()
        ensureNotificationChannel()
        cancelServiceRecoveryAlarm()
        wakeRecoveryController.register()
        registerDisplayStateReceiver()
        registerUsbMediaReceiver()
    }

    /**
     * Главная точка входа сервиса. Разруливает команды refresh/restart и
     * запускает подготовку сети перед стартом видеопайплайна.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            if (action == ACTION_REFRESH_FTP_NOW) {
                startDetachedWorker("RefreshFtpNow") {
                    try {
                        startOrRefreshUpdateServer()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Ошибка немедленного обновления FTP", t)
                    }
                }
                return START_STICKY
            }

            val forceRestart = action == ACTION_RESTART_SERVICE_NOW
            val safeIntent = intent ?: createStartIntent(this).also {
                Log.w(TAG, "onStartCommand(): получен null intent, использую fixedConfig(context)")
            }
            val cfg = readConfig(safeIntent)
            val targetHost = cfg.ip.trim()
            val targetPort = cfg.port
            if (targetHost.isEmpty() || targetPort !in 1..65535) {
                startInProgress = false
                Log.e(TAG, "Ошибка запуска: некорректные host/port")
                scheduleServiceRecovery("invalid_config", SERVICE_RECOVERY_DELAY_MS)
                return START_STICKY
            }

            synchronized(serviceLock) {
                if (!forceRestart && lastCfg == cfg && (streamActive || startInProgress || sender != null)) {
                    Log.i(TAG, "Игнорирую повторный startCommand: стрим уже активен или запускается")
                    updateNotification(getNotificationStateText())
                    return START_STICKY
                }

                stopInternalKeepService()
                cancelPendingRestart()
                unbindProcessNetworkBestEffort()

                lastCfg = cfg
                host = targetHost
                port = targetPort
                startInProgress = true
            }

            ensureNotificationChannel()
            startForegroundCompat(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                buildNotification(),
            )

            startDetachedWorker("StartupWorker") {
                try {
                    startOrRefreshUpdateServer()
                    scheduleInternalUpdatePoll()
                    val networkPrep = prepareNetwork(cfg)
                    mainHandler.post {
                        if (lastCfg != cfg || !startInProgress) {
                            return@post
                        }
                        if (networkPrep.rootRequired) {
                            startInProgress = false
                            notifyRootRequiredOnce()
                            return@post
                        }
                        activeRootIface = if (cfg.useRootNet && networkPrep.ifacePresent) {
                            networkPrep.ifaceName
                        } else {
                            null
                        }
                        startPipelineAsync(
                            cfg = cfg,
                            hostValue = targetHost,
                            bindIp = networkPrep.bindIp,
                            network = networkPrep.network,
                            launchComponent = cfg.launchComponent,
                            restartLog = false,
                        )
                    }
                } catch (t: Throwable) {
                    mainHandler.post {
                        startInProgress = false
                        Log.e(TAG, "Ошибка подготовки сети", t)
                        scheduleRestart("network_prep", t)
                    }
                }
            }
            return START_STICKY
        } catch (se: SecurityException) {
            startInProgress = false
            Log.e(TAG, "Ошибка запуска: SecurityException", se)
            scheduleServiceRecovery("security_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        } catch (t: Throwable) {
            startInProgress = false
            Log.e(TAG, "Ошибка запуска стрима", t)
            scheduleServiceRecovery("startup_exception", SERVICE_RECOVERY_DELAY_MS)
            return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Задача приложения удалена из recents; планирую восстановление сервиса")
        scheduleServiceRecovery(
            reason = "task_removed",
            delayMs = TASK_REMOVED_RECOVERY_DELAY_MS,
            userReason = getString(R.string.app_recovery_restart_reason_task_removed),
            launchUi = true,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sStreamActive = false
        sServiceRunning = false
        scheduleServiceRecovery(
            reason = "service_destroyed",
            delayMs = SERVICE_RECOVERY_DELAY_MS,
            userReason = getString(R.string.app_recovery_restart_reason_service_destroyed),
        )
        wakeRecoveryController.unregister()
        unregisterDisplayStateReceiver()
        unregisterUsbMediaReceiver()
        stopInternalFull()
        releaseStreamWakeLock()
        super.onDestroy()
    }

    override fun requestRestart() {
        scheduleRestart("udp_error", null)
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
        updateCoordinator = UdpUpdateServerCoordinator(
            context = applicationContext,
            mainHandler = mainHandler,
            isServiceRunning = { sServiceRunning },
            startDetachedWorker = ::startDetachedWorker,
            publishWarning = { AppWarningCenter.publish(it) },
        )
        wakeRecoveryController = UdpWakeRecoveryController(
            context = this,
            mainHandler = mainHandler,
            registerLocalReceiver = ::registerLocalReceiver,
            unregisterReceiverBestEffort = ::unregisterReceiverBestEffort,
            snapshotProvider = ::buildWakeRecoverySnapshot,
            logPipelineSnapshot = ::logPipelineSnapshot,
            forceOutputFrame = { reason -> encoder?.forceOutputFrame(reason) ?: Unit },
            relaunchTargetActivity = { reason -> encoder?.relaunchTargetActivityIfNeeded(reason) ?: Unit },
            requestImmediateRecovery = ::requestImmediateRecovery,
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
            wakeLock.acquire()
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
        val currentSender = sender
        val snapshot = currentSender?.snapshot()
        val recentVideoTraffic = snapshot?.lastSendElapsedRealtimeMs?.let { lastSendMs ->
            lastSendMs > 0L && (SystemClock.elapsedRealtime() - lastSendMs) <= ROUTE_RECENT_SEND_GRACE_MS
        } == true
        val displayId = VdspState.getDisplayId()
        return UdpWakeRecoverySnapshot(
            streamHealthy = streamActive && !startInProgress && currentSender != null && recentVideoTraffic && displayId >= 0,
            requiresFullRecovery = !streamActive || startInProgress || currentSender == null || displayId < 0,
        )
    }

    private fun onEthernetNetworkLost(network: Network) {
        if (boundNetwork == network || streamActive || startInProgress || sender != null) {
            Log.w(TAG, "Ethernet Network потерян: $network")
            requestImmediateRecovery(
                reason = "eth_network_lost",
                minBackoffMs = NETWORK_LOST_RESTART_BACKOFF_MS,
                userMessage = "Ethernet-связь потеряна. Перезапуск стрима…",
            )
        }
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
                        reason = "cluster_display_removed",
                        minBackoffMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
                        userMessage = "Cluster display исчез. Перезапускаю трансляцию…",
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

    private fun scheduleRestart(reason: String, cause: Throwable?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartRequestMs < RESTART_REQUEST_DEBOUNCE_MS) {
            return
        }
        lastRestartRequestMs = now

        if (!restartScheduled.compareAndSet(false, true)) {
            return
        }

        pendingRestartReason = reason
        val delayMs = restartBackoffMs
        Log.w(TAG, "Перезапуск запланирован через ${delayMs}мс, reason=$reason${cause?.let { " cause=$it" } ?: ""}")
        notifyNoLinkOnce(getString(R.string.service_notification_no_link_restart_fmt, delayMs / 1000))

        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun cancelPendingRestart() {
        restartScheduled.set(false)
        pendingRestartReason = null
        mainHandler.removeCallbacks(restartRunnable)
    }

    private fun scheduleInternalUpdatePoll() {
        updateCoordinator.scheduleInternalUpdatePoll()
    }

    private fun cancelInternalUpdatePoll() {
        updateCoordinator.cancelInternalUpdatePoll()
    }

    private fun scheduleFtpRetry(reason: String) {
        if (ftpRetryScheduled.getAndSet(true)) {
            return
        }
        Log.i(TAG, "Повторный запуск FTP запланирован через ${RuntimeConfig.UpdateFtp.RETRY_DELAY_MS}мс, reason=$reason")
        mainHandler.removeCallbacks(ftpRetryRunnable)
        mainHandler.postDelayed(ftpRetryRunnable, RuntimeConfig.UpdateFtp.RETRY_DELAY_MS)
    }

    private fun cancelFtpRetry() {
        updateCoordinator.cancelFtpRetry()
    }

    private fun performFtpRetry() {
        if (!sServiceRunning) {
            ftpRetryScheduled.set(false)
            return
        }
        ftpRetryScheduled.set(false)

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
                        AppWarningCenter.publish(getString(R.string.service_notification_ftp_restarted_fmt, address))
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
        if (!sServiceRunning) {
            internalUpdatePollScheduled.set(false)
            return
        }
        internalUpdatePollScheduled.set(false)

        startDetachedWorker("UpdatePollWorker") {
            try {
                val result = UpdateServerManager.pollAvailableStorage(applicationContext)
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
                if (sServiceRunning) {
                    scheduleInternalUpdatePoll()
                }
            }
        }
    }

    private fun attemptRestart(reason: String?) {
        restartScheduled.set(false)
        pendingRestartReason = null

        if (reason == "net_available" && (streamActive || startInProgress || sender != null)) {
            Log.i(TAG, "Пропускаю отложенный restart(net_available): стрим уже активен")
            return
        }

        stopInternalKeepService()
        RootNetUtil.clearCaches()
        unbindProcessNetworkBestEffort()

        startDetachedWorker("RestartWorker") {
            try {
                val cfg = lastCfg ?: StreamConfig.fixedConfig(this@UdpStreamService)
                val targetHost = host ?: cfg.ip
                val networkPrep = prepareNetwork(cfg)

                if (networkPrep.rootRequired) {
                    mainHandler.post {
                        startInProgress = false
                        notifyRootRequiredOnce()
                    }
                    return@startDetachedWorker
                }

                if (cfg.useRootNet && !networkPrep.ifacePresent) {
                    mainHandler.post {
                        startInProgress = false
                        restartBackoffMs = maxOf(
                            IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            minOf(RESTART_BACKOFF_MAX_MS, restartBackoffMs * 2),
                        )
                        val ifaceName = RootNetUtil.getSelectedIfaceName(force = true) ?: RuntimeConfig.Root.IFACE
                        Log.w(TAG, "$ifaceName отсутствует на устройстве; повторю позже. backoff=${restartBackoffMs}ms")
                        notifyNoLinkOnce(getString(R.string.service_notification_iface_missing_fmt, ifaceName, restartBackoffMs / 1000))
                        scheduleRestart(RuntimeConfig.Root.MISSING_REASON, null)
                    }
                    return@startDetachedWorker
                }

                mainHandler.post {
                    startPipelineAsync(
                        cfg = cfg,
                        hostValue = targetHost,
                        bindIp = networkPrep.bindIp,
                        network = networkPrep.network,
                        launchComponent = cfg.launchComponent,
                        restartLog = true,
                    )
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    startInProgress = false
                    restartBackoffMs = minOf(RESTART_BACKOFF_MAX_MS, restartBackoffMs * 2)
                    Log.w(TAG, "Попытка рестарта завершилась ошибкой; повторю позже. backoff=${restartBackoffMs}ms", t)
                    notifyNoLinkOnce(getString(R.string.service_notification_no_connection_retry_fmt, restartBackoffMs / 1000))
                    scheduleRestart("retry", t)
                }
            }
        }
    }

    private fun notifyNoLinkOnce(msg: String) {
        updateNotification(msg)
        AppWarningCenter.publish(msg)
        if (noLinkNotified) return
        noLinkNotified = true
    }

    private fun notifyRootRequiredOnce() {
        val msg = getString(R.string.msg_root_required)
        updateNotification(msg)
        AppWarningCenter.publish(msg)
        if (rootWarningShown) return
        rootWarningShown = true
    }

    private fun prepareNetwork(cfg: StreamConfig): NetworkPreparation {
        RootNetUtil.logSelectedIface(TAG, "Подготовка сети")
        if (!cfg.useRootNet) {
            activeRootIface = null
            ensureEthCallbackRegistered()
            val network = lastSeenEthNetwork ?: findEthernetNetwork()
            val bindIp = cfg.bindIp
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { it == RuntimeConfig.Network.BIND_IP }
            if (network != null) {
                bindProcessToNetworkBestEffort(network)
            } else {
                Log.i(TAG, "Root-сеть отключена; ethernet Network не найден, продолжаю без привязки к интерфейсу")
            }
            if (bindIp == null && !cfg.bindIp.isNullOrBlank()) {
                Log.i(TAG, "Root-сеть отключена; статический bindIp ${cfg.bindIp} пропускаю")
            }
            return NetworkPreparation(
                bindIp = bindIp,
                network = network,
                ifacePresent = network != null,
                rootRequired = false,
                ifaceName = null,
            )
        }

        ensureEthCallbackRegistered()

        val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: DEF_USB_LOCAL_CIDR
        val gateway = cfg.gateway?.takeIf { it.isNotBlank() } ?: DEF_USB_GATEWAY
        val applyResult = RootNetUtil.applyStaticIfaceNetwork(localCidr, gateway)
        if (applyResult.rootRequired) {
            Log.w(TAG, "Нет доступа к su/root — сетевой root-режим недоступен")
            return NetworkPreparation(
                bindIp = null,
                network = null,
                ifacePresent = true,
                rootRequired = true,
                ifaceName = applyResult.iface,
            )
        }

        val defaultBindIp = ipFromCidr(localCidr)
        var bindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: defaultBindIp
        if (!applyResult.ok && (cfg.bindIp.isNullOrBlank() || cfg.bindIp == defaultBindIp)) {
            bindIp = null
        }

        val probeState = RootNetUtil.getIfaceProbeState()
        if (probeState.rootRequired) {
            Log.w(TAG, "Нет доступа к su/root — проверка ${probeState.iface} недоступна")
            return NetworkPreparation(
                bindIp = null,
                network = null,
                ifacePresent = true,
                rootRequired = true,
                ifaceName = probeState.iface,
            )
        }

        val ifacePresent = probeState.exists
        if (!applyResult.ok) {
            if (ifacePresent) {
                Log.w(TAG, "Не удалось применить статический IP для ${applyResult.iface} (продолжаю): ${applyResult.details}")
            } else {
                Log.i(TAG, "${probeState.iface} отсутствует — статическая настройка сети пропущена")
            }
        }

        var network: Network? = null
        if (ifacePresent) {
            network = lastSeenEthNetwork ?: findEthernetNetwork()
            if (network != null) {
                bindProcessToNetworkBestEffort(network)
            } else {
                Log.i(TAG, "ConnectivityManager не дал ethernet Network; продолжаю по bindIp и route")
            }
        } else {
            Log.i(TAG, "${probeState.iface} отсутствует на устройстве — биндинг к ethernet Network пропущен")
        }

        return NetworkPreparation(
            bindIp = bindIp,
            network = network,
            ifacePresent = ifacePresent,
            rootRequired = false,
            ifaceName = probeState.iface,
        )
    }

    private fun startOrRefreshUpdateServer() {
        val result = UpdateServerManager.prepareAndStartServer(
            applicationContext,
            UpdateFileLocator.SearchPolicy.USB_FIRST,
        )
        if (result.success) {
            Log.i(TAG, "FTP обновление активно: ${result.boundAddress?.host}:${result.boundAddress?.port}")
            return
        }

        Log.w(TAG, "FTP обновление не запущено: ${result.message}")
        AppWarningCenter.publish(getString(R.string.service_notification_ftp_message_fmt, result.message))
        scheduleFtpRetry("startup")
    }

    private fun stopInternalKeepService() {
        stopConnectivityWatchdog()
        stopTransportStatsLogging()
        stopStatusSyncBestEffort()
        cancelFtpRetry()
        pendingWakeAction = null
        wakeRecoveryStage = 0
        mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
        routeFailureStreak = 0
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

    private fun stopInternalFull() {
        cancelPendingRestart()
        stopInternalKeepService()
        UpdateServerManager.stopServer()
        RootNetUtil.clearCaches()
        unbindProcessNetworkBestEffort()
        unregisterEthCallbackBestEffort()
        PersistentVirtualDisplay.detachSurface()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> handleWakeEvent(intent.action.orEmpty())
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        try {
            registerLocalReceiver(receiver, filter)
            screenStateReceiver = receiver
            screenStateReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать receiver экранных событий", t)
            screenStateReceiver = null
            screenStateReceiverRegistered = false
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) return
        unregisterReceiverBestEffort(screenStateReceiver, "экранных событий")
        screenStateReceiver = null
        screenStateReceiverRegistered = false
        screenSleepStartedAtMs = 0L
        pendingWakeAction = null
        wakeRecoveryStage = 0
        mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
    }

    private fun handleScreenOff() {
        if (!isVideoStreamModeSelected()) return
        screenSleepStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Экран выключен; отслеживаю восстановление после выхода из сна")
    }

    private fun handleWakeEvent(action: String) {
        if (!isVideoStreamModeSelected()) return
        val sleptAt = screenSleepStartedAtMs
        if (sleptAt == 0L) return

        val now = SystemClock.elapsedRealtime()
        if ((now - lastWakeRecoveryAtMs) < WAKE_RECOVERY_DEBOUNCE_MS) {
            return
        }
        lastWakeRecoveryAtMs = now
        screenSleepStartedAtMs = 0L

        val sleptMs = now - sleptAt
        Log.i(TAG, "Устройство вышло из сна: action=$action, slept=${sleptMs}ms")

        pendingWakeAction = action
        wakeRecoveryStage = 0
        mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
        mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_VERIFY_DELAY_MS)
    }

    private fun verifyWakeRecovery() {
        val action = pendingWakeAction ?: return
        val currentSender = sender
        val snapshot = currentSender?.snapshot()
        val recentVideoTraffic = snapshot?.lastSendElapsedRealtimeMs?.let { lastSendMs ->
            lastSendMs > 0L && (SystemClock.elapsedRealtime() - lastSendMs) <= ROUTE_RECENT_SEND_GRACE_MS
        } == true
        val displayId = VdspState.getDisplayId()

        if (streamActive && !startInProgress && currentSender != null && recentVideoTraffic && displayId >= 0) {
            Log.i(TAG, "После выхода из сна поток уже активен; лишний relaunch не нужен")
            pendingWakeAction = null
            wakeRecoveryStage = 0
            return
        }

        if (!streamActive || startInProgress || currentSender == null || displayId < 0) {
            logPipelineSnapshot("Wake recovery требует полного восстановления")
            pendingWakeAction = null
            wakeRecoveryStage = 0
            requestImmediateRecovery(
                reason = "wake_recovery",
                minBackoffMs = RESTART_BACKOFF_START_MS,
                userMessage = getString(R.string.service_notification_wake_recovery),
            )
            return
        }

        if (wakeRecoveryStage == 0) {
            wakeRecoveryStage = 1
            encoder?.forceOutputFrame("wake:$action")
            mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
            mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_FORCE_FRAME_SETTLE_DELAY_MS)
            return
        }

        if (wakeRecoveryStage == 1) {
            wakeRecoveryStage = 2
            encoder?.relaunchTargetActivityIfNeeded("wake:$action")
            mainHandler.removeCallbacks(wakeRecoveryVerifyRunnable)
            mainHandler.postDelayed(wakeRecoveryVerifyRunnable, WAKE_RELAUNCH_SETTLE_DELAY_MS)
            return
        }

        pendingWakeAction = null
        wakeRecoveryStage = 0
        requestImmediateRecovery(
            reason = "wake_recovery",
            minBackoffMs = RESTART_BACKOFF_START_MS,
            userMessage = getString(R.string.service_notification_wake_recovery_failed),
        )
    }

    private fun startStatusSyncBestEffort(network: Network?, bindIp: String?, hostValue: String) {
        try {
            stopStatusSyncBestEffort()
            syncHandler = SyncHandler(applicationContext, STATUS_KEY_SYNC_INTERVAL)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED -> syncHandler?.setTimeChanged()
                        Intent.ACTION_LOCALE_CHANGED -> syncHandler?.setLangChanged()
                    }
                }
            }
            statusReceiver = receiver

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            }
            registerLocalReceiver(receiver, filter)
            statusReceiverRegistered = true

            statusPacketsSent.set(0)
            statusBytesSent.set(0)
            statusSendErrors.set(0)
            statusStop.set(false)

            val socket = if (!bindIp.isNullOrBlank()) {
                try {
                    DatagramSocket(InetSocketAddress(InetAddress.getByName(bindIp), 0)).also {
                        Log.i(TAG, "StatusSocket привязан к локальному IP $bindIp")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Не удалось привязать statusSocket к $bindIp, использую bind 0.0.0.0", t)
                    DatagramSocket()
                }
            } else {
                DatagramSocket()
            }
            statusSocket = socket
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (t: Throwable) {
                    Log.w(TAG, "Не удалось привязать statusSocket к Network (продолжаю без привязки): $t")
                }
            }

            val destinationHost = InetAddress.getByName(hostValue)
            val destinationPort = STATUS_PORT

            statusThread = launchWorker("StatusSync", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                var consecutiveErrors = 0
                while (!statusStop.get()) {
                    try {
                        val payload = syncHandler?.sync()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        val packet = DatagramPacket(payload, payload.size, destinationHost, destinationPort)
                        socket.send(packet)
                        statusPacketsSent.incrementAndGet()
                        statusBytesSent.addAndGet(payload.size.toLong())
                        consecutiveErrors = 0
                    } catch (t: Throwable) {
                        statusSendErrors.incrementAndGet()
                        consecutiveErrors++
                        if (consecutiveErrors == 1 || consecutiveErrors % STATUS_ERROR_LOG_EVERY == 0) {
                            Log.w(TAG, "Ошибка отправки status sync (ошибок подряд=$consecutiveErrors)", t)
                        }
                    }

                    try {
                        Thread.sleep(STATUS_PERIOD_MS.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            Log.i(TAG, "Status sync запущен (dst=$hostValue:$destinationPort)")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запустить status sync", t)
            stopStatusSyncBestEffort()
        }
    }

    private fun stopStatusSyncBestEffort() {
        statusStop.set(true)
        interruptThreadQuietly(statusThread, "status sync")
        joinThreadQuietly(statusThread, "status sync")
        statusThread = null

        try {
            statusSocket?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось закрыть statusSocket", t)
        }
        statusSocket = null

        if (statusReceiverRegistered) {
            unregisterReceiverBestEffort(statusReceiver, "status sync")
            statusReceiverRegistered = false
        }
        statusReceiver = null
        syncHandler = null
    }

    private fun startTransportStatsLogging() {
        stopTransportStatsLogging()
        statsStop.set(false)
        statsThread = launchWorker("UdpStats", Process.THREAD_PRIORITY_DEFAULT) {
            var prevVideoFrames = 0L
            var prevVideoPackets = 0L
            var prevVideoBytes = 0L
            var prevProbePackets = 0L
            var prevProbeBytes = 0L
            var prevStatusPackets = 0L
            var prevStatusBytes = 0L
            var prevErrors = 0L

            while (!statsStop.get()) {
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (statsStop.get()) break

                val snapshot = sender?.snapshot() ?: continue
                val statusPackets = statusPacketsSent.get()
                val statusBytes = statusBytesSent.get()
                val totalErrors = snapshot.sendErrors + statusSendErrors.get()

                val deltaFrames = snapshot.videoFramesSent - prevVideoFrames
                val deltaPackets = snapshot.videoPacketsSent - prevVideoPackets
                val deltaBytes = snapshot.videoBytesSent - prevVideoBytes
                val deltaProbePackets = snapshot.probePacketsSent - prevProbePackets
                val deltaProbeBytes = snapshot.probeBytesSent - prevProbeBytes
                val deltaStatusPackets = statusPackets - prevStatusPackets
                val deltaStatusBytes = statusBytes - prevStatusBytes
                val deltaErrors = totalErrors - prevErrors

                prevVideoFrames = snapshot.videoFramesSent
                prevVideoPackets = snapshot.videoPacketsSent
                prevVideoBytes = snapshot.videoBytesSent
                prevProbePackets = snapshot.probePacketsSent
                prevProbeBytes = snapshot.probeBytesSent
                prevStatusPackets = statusPackets
                prevStatusBytes = statusBytes
                prevErrors = totalErrors

                val videoMbps = deltaBytes * 8.0 / 2_000_000.0
                val statusKbps = deltaStatusBytes * 8.0 / 2_000.0
                val lastSendAgo = if (snapshot.lastSendElapsedRealtimeMs > 0L) {
                    SystemClock.elapsedRealtime() - snapshot.lastSendElapsedRealtimeMs
                } else {
                    -1L
                }

                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "UDP stats | dst=%s:%d | video: +%d frames, +%d packets, +%d bytes, %.3f Mbps | probes: +%d packets, +%d bytes | status: +%d packets, +%d bytes, %.2f kbps | errors:+%d | lastSendAgo=%dms",
                        snapshot.host,
                        snapshot.port,
                        deltaFrames,
                        deltaPackets,
                        deltaBytes,
                        videoMbps,
                        deltaProbePackets,
                        deltaProbeBytes,
                        deltaStatusPackets,
                        deltaStatusBytes,
                        statusKbps,
                        deltaErrors,
                        lastSendAgo,
                    ),
                )
            }
        }
    }

    private fun startConnectivityWatchdog() {
        stopConnectivityWatchdog()
        watchdogStop.set(false)
        watchdogThread = launchWorker("ConnectivityWatchdog", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            while (!watchdogStop.get()) {
                try {
                    Thread.sleep(CONNECTIVITY_WATCHDOG_PERIOD_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (watchdogStop.get()) break

                val cfg = lastCfg ?: continue
                val currentSender = sender ?: continue
                val snapshot = currentSender.snapshot()
                val nowMs = SystemClock.elapsedRealtime()
                val targetHost = host?.takeIf { it.isNotBlank() } ?: cfg.ip
                val localCidr = cfg.localCidr?.takeIf { it.isNotBlank() } ?: DEF_USB_LOCAL_CIDR
                val expectedBindIp = cfg.bindIp?.takeIf { it.isNotBlank() } ?: ipFromCidr(localCidr)
                val lastSendMs = snapshot.lastSendElapsedRealtimeMs
                val recentVideoTraffic = lastSendMs > 0L && (nowMs - lastSendMs) <= ROUTE_RECENT_SEND_GRACE_MS

                if (cfg.useRootNet) {
                    val activeProbeState = RootNetUtil.getIfaceProbeState(force = false)
                    if (!activeProbeState.rootRequired && activeProbeState.exists && !activeProbeState.linkUp) {
                        Log.w(TAG, "Watchdog: link ${activeProbeState.iface} down во время активного стрима")
                        requestImmediateRecovery(
                            reason = "root_iface_link_down",
                            minBackoffMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            userMessage = "Сетевой линк ${activeProbeState.iface} потерян. Перезапускаю стрим…",
                        )
                        continue
                    }
                    if (!activeProbeState.rootRequired && !activeProbeState.exists) {
                        Log.w(TAG, "Watchdog: ${activeProbeState.iface} пропал во время активного стрима")
                        requestImmediateRecovery(
                            reason = RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                            minBackoffMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            userMessage = "${activeProbeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                        )
                        continue
                    }

                    val pinnedIface = activeRootIface?.takeIf { it.isNotBlank() }
                    val selectedIface = RootNetUtil.getSelectedIfaceName(force = false)
                    if (
                        !activeProbeState.rootRequired &&
                        !pinnedIface.isNullOrBlank() &&
                        !selectedIface.equals(pinnedIface, ignoreCase = true)
                    ) {
                        Log.w(TAG, "Watchdog: активный root-интерфейс $pinnedIface сменился на ${selectedIface ?: "none"}")
                        requestImmediateRecovery(
                            reason = "root_iface_changed",
                            minBackoffMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            userMessage = "Сетевой интерфейс $pinnedIface отключён или сменился. Перезапускаю стрим…",
                        )
                        continue
                    }

                    if (recentVideoTraffic) {
                        if (routeFailureStreak != 0) {
                            Log.i(TAG, "Watchdog: есть свежая видеопередача, сбрасываю route-failure streak")
                        }
                        routeFailureStreak = 0
                        continue
                    }

                    val probeState = RootNetUtil.getIfaceProbeState(force = false)
                    if (!probeState.rootRequired && probeState.exists && !probeState.linkUp) {
                        Log.w(TAG, "Watchdog: link ${probeState.iface} down во время активного стрима")
                        requestImmediateRecovery(
                            reason = "root_iface_link_down",
                            minBackoffMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            userMessage = "Сетевой линк ${probeState.iface} потерян. Перезапускаю стрим…",
                        )
                        continue
                    }
                    if (!probeState.rootRequired && !probeState.exists) {
                        Log.w(TAG, "Watchdog: ${probeState.iface} пропал во время активного стрима")
                        requestImmediateRecovery(
                            reason = RuntimeConfig.Root.MISSING_RUNTIME_REASON,
                            minBackoffMs = IFACE_MISSING_RESTART_BACKOFF_MIN_MS,
                            userMessage = "${probeState.iface} пропал. Ожидаю восстановление и перезапускаю стрим…",
                        )
                        continue
                    }

                    if (!expectedBindIp.isNullOrBlank()) {
                        val routeReady = RootNetUtil.canRouteTo(targetHost, expectedBindIp, forceProbe = false)
                        if (routeReady) {
                            routeFailureStreak = 0
                        } else {
                            val probeOk = try {
                                currentSender.probe()
                            } catch (t: Throwable) {
                                Log.w(TAG, "Watchdog: исключение при UDP probe во время route-check", t)
                                false
                            }
                            if (probeOk) {
                                if (routeFailureStreak != 0) {
                                    Log.i(TAG, "Watchdog: route-check для ${probeState.iface} не совпал, но probe жив; сбрасываю streak")
                                }
                                routeFailureStreak = 0
                            } else {
                                routeFailureStreak += 1
                                Log.w(TAG, "Watchdog: маршрут до $targetHost через ${probeState.iface} недоступен и нет живой передачи (streak=$routeFailureStreak)")
                                if (routeFailureStreak >= ROUTE_FAILURES_BEFORE_RESTART) {
                                    routeFailureStreak = 0
                                    requestImmediateRecovery(
                                        reason = "route_lost_runtime",
                                        minBackoffMs = NO_ROUTE_RESTART_BACKOFF_MIN_MS,
                                        userMessage = "Маршрут через ${probeState.iface} потерян. Перезапуск стрима…",
                                    )
                                    continue
                                }
                            }
                        }
                    }
                }
                // Для режима Dynamic FPS отсутствие новых видеокадров само по себе не считается ошибкой:
                // при статичной картинке на VirtualDisplay кодек может долго не отдавать выходные буферы.
                // Восстановление здесь выполняется только по проверке маршрута, probe и явным ошибкам сокета или энкодера.
            }
        }
    }

    private fun stopConnectivityWatchdog() {
        watchdogStop.set(true)
        interruptThreadQuietly(watchdogThread, "watchdog")
        joinThreadQuietly(watchdogThread, "watchdog")
        watchdogThread = null
    }

    private fun requestImmediateRecovery(
        reason: String,
        minBackoffMs: Long,
        userMessage: String,
    ) {
        mainHandler.post {
            restartBackoffMs = maxOf(minBackoffMs, restartBackoffMs)
            logPipelineSnapshot("Немедленное восстановление, reason=$reason")
            Log.w(TAG, "Немедленное восстановление стрима, reason=$reason, backoff=${restartBackoffMs}ms")
            notifyNoLinkOnce(userMessage)
            cancelPendingRestart()
            lastRestartRequestMs = 0L
            stopInternalKeepService()
            RootNetUtil.clearCaches()
            unbindProcessNetworkBestEffort()
            scheduleRestart(reason, null)
        }
    }

    private fun stopTransportStatsLogging() {
        statsStop.set(true)
        interruptThreadQuietly(statsThread, "статистики")
        joinThreadQuietly(statsThread, "статистики")
        statsThread = null
    }

    private fun startPipelineAsync(
        cfg: StreamConfig,
        hostValue: String,
        bindIp: String?,
        network: Network?,
        launchComponent: String?,
        restartLog: Boolean,
    ) {
        val localSender = try {
            UdpSender(hostValue, port, bindIp, network)
        } catch (t: Throwable) {
            startInProgress = false
            Log.e(TAG, "Не удалось создать UdpSender", t)
            scheduleRestart("udp_sender_init", t)
            return
        }
        sender = localSender
        startInProgress = true

        launchWorker("UdpProbe", Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            val udpReady = waitForUdpReady(
                sender = localSender,
                cfg = cfg,
                dstHost = hostValue,
                bindIp = bindIp,
                timeoutMs = ROUTE_WAIT_TIMEOUT_MS,
            )

            if (!udpReady) {
                closeSenderQuietly(localSender)
                mainHandler.post {
                    if (sender === localSender) {
                        sender = null
                    }
                    startInProgress = false
                    restartBackoffMs = maxOf(
                        NO_ROUTE_RESTART_BACKOFF_MIN_MS,
                        minOf(RESTART_BACKOFF_MAX_MS, restartBackoffMs * 2),
                    )
                    logPipelineSnapshot("Маршрут не готов для $hostValue")
                    Log.w(TAG, "Маршрут до $hostValue не готов; повторю позже. backoff=${restartBackoffMs}ms")
                    notifyNoLinkOnce(getString(R.string.service_notification_no_route_fmt, hostValue, restartBackoffMs / 1000))
                    scheduleRestart("net_wait", null)
                }
                return@launchWorker
            }

            mainHandler.post {
                if (sender !== localSender) {
                    closeSenderQuietly(localSender)
                    startInProgress = false
                    return@post
                }

                if (!isVideoStreamModeSelected()) {
                    try {
                        closeSenderQuietly(localSender)
                        if (sender === localSender) {
                            sender = null
                        }
                        stopTransportStatsLogging()
                        stopConnectivityWatchdog()
                        startStatusSyncBestEffort(network, bindIp, hostValue)
                        streamActive = false
                        sStreamActive = false
                        startInProgress = false
                        restartBackoffMs = RESTART_BACKOFF_START_MS
                        noLinkNotified = false
                        releaseStreamWakeLock()
                        cancelPendingRestart()
                        cancelServiceRecoveryAlarm()
                        updateNotification(getNotificationStateText())
                        Log.i(TAG, "Активен режим бортового компьютера: видеотрансляция отключена")
                    } catch (t: Throwable) {
                        startInProgress = false
                        Log.e(TAG, "Ошибка запуска status-only режима бортового компьютера", t)
                        scheduleRestart("trip_mode_start", t)
                    }
                    return@post
                }

                try {
                    encoder = VideoEncoder(
                        context = this@UdpStreamService,
                        streamConfig = cfg,
                        preferredLaunchComponent = launchComponent,
                        udpSender = localSender,
                        restartCallback = this@UdpStreamService,
                    )
                    encoder?.start()
                    startStatusSyncBestEffort(network, bindIp, hostValue)
                    startTransportStatsLogging()
                    startConnectivityWatchdog()
                    streamActive = true
                    sStreamActive = true
                    startInProgress = false
                    restartBackoffMs = 500L
                    noLinkNotified = false
                    acquireStreamWakeLock()
                    cancelPendingRestart()
                    cancelServiceRecoveryAlarm()
                    updateNotification(getNotificationStateText())
                    if (AppWarningCenter.contains(getString(R.string.msg_root_required))) {
                        notifyRootRequiredOnce()
                    }
                    if (restartLog) {
                        Log.i(TAG, "Перезапуск выполнен успешно")
                    } else {
                        Log.i(TAG, "Стрим успешно запущен: $hostValue:$port")
                    }
                } catch (t: Throwable) {
                    startInProgress = false
                    streamActive = false
                    sStreamActive = false
                    Log.e(TAG, "Ошибка запуска кодера", t)
                    closeSenderQuietly(localSender)
                    if (sender === localSender) {
                        sender = null
                    }
                    try {
                        encoder?.stop()
                    } catch (stopError: Throwable) {
                        Log.w(TAG, "Не удалось остановить частично запущенный VideoEncoder", stopError)
                    }
                    encoder = null
                    scheduleRestart("enc_start", t)
                }
            }
        }
    }

    private fun waitForUdpReady(
        sender: UdpSender,
        cfg: StreamConfig,
        dstHost: String,
        bindIp: String?,
        timeoutMs: Long,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var lastLoggedWaitMs = 0L

        while (true) {
            if (sender.isClosed()) return false

            val probeOk = try {
                sender.probe()
            } catch (t: Throwable) {
                Log.w(TAG, "Исключение при проверке UDP", t)
                false
            }

            val ready = probeOk
            if (ready) {
                return true
            }

            val waitedMs = SystemClock.elapsedRealtime() - startedAt
            if (waitedMs >= timeoutMs) {
                return false
            }

            if (waitedMs - lastLoggedWaitMs >= 1_000L) {
                lastLoggedWaitMs = waitedMs
                Log.i(TAG, "Ожидание готовности UDP: dst=$dstHost probeOk=$probeOk waited=${waitedMs}ms")
            }

            try {
                Thread.sleep(ROUTE_WAIT_STEP_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    private fun ensureEthCallbackRegistered() {
        if (linkCallback != null) return

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastSeenEthNetwork = network
                if (UpdateServerManager.getServerState().retrySuggested) {
                    scheduleFtpRetry("net_available")
                }
                if (streamActive || startInProgress || sender != null) return
                scheduleRestart("net_available", null)
            }

            override fun onLost(network: Network) {
                if (lastSeenEthNetwork == network) {
                    lastSeenEthNetwork = null
                }
                if (boundNetwork == network || streamActive || startInProgress || sender != null) {
                    Log.w(TAG, "Ethernet Network потерян: $network")
                    requestImmediateRecovery(
                        reason = "eth_network_lost",
                        minBackoffMs = NETWORK_LOST_RESTART_BACKOFF_MS,
                        userMessage = "Ethernet-связь потеряна. Перезапуск стрима…",
                    )
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            linkCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать NetworkCallback", t)
            linkCallback = null
        }
    }

    private fun unregisterEthCallbackBestEffort() {
        val callback = linkCallback ?: return
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять NetworkCallback", t)
        } finally {
            linkCallback = null
            lastSeenEthNetwork = null
        }
    }

    private fun findEthernetNetwork(): Network? {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                lastSeenEthNetwork = network
                return network
            }
        }
        return null
    }

    private fun bindProcessToNetworkBestEffort(network: Network) {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                connectivityManager.bindProcessToNetwork(network)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(network)
            }
            boundNetwork = network
            Log.i(TAG, "Процесс привязан к Network=$network")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось привязать процесс к Network (продолжаю без привязки): $t")
        }
    }

    private fun unbindProcessNetworkBestEffort() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                connectivityManager.bindProcessToNetwork(null)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять привязку процесса к Network", t)
        }
        boundNetwork = null
    }

    private fun ipFromCidr(cidr: String): String? {
        return try {
            cidr.substringBefore('/').trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось извлечь IP из CIDR: $cidr", t)
            null
        }
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
        ).also { it.start() }
    }

    private fun startDetachedWorker(name: String, block: () -> Unit) {
        launchWorker(name, block = block)
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
                "sendErrors=${senderSnapshot?.sendErrors ?: 0}, lastSendAgo=${lastSendAgoMs}ms, routeFailureStreak=$routeFailureStreak",
        )
    }

    private fun scheduleServiceRecovery(
        reason: String,
        delayMs: Long,
        userReason: String = reason,
        launchUi: Boolean = false,
    ) {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = buildServiceRecoveryPendingIntent(
                reason = userReason,
                launchUi = launchUi,
            )
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.w(TAG, "Запланировано восстановление сервиса через ${delayMs}мс, reason=$reason, launchUi=$launchUi")
            updateNotification(getString(R.string.service_notification_service_recovery_fmt, delayMs / 1000))
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запланировать восстановление сервиса, reason=$reason, launchUi=$launchUi", t)
            try {
                startServiceCompat(applicationContext)
            } catch (restartError: Throwable) {
                Log.e(TAG, "Fallback-запуск сервиса тоже не удался", restartError)
            }
        }
    }

    private fun cancelServiceRecoveryAlarm() {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildServiceRecoveryPendingIntent(reason = "", launchUi = false))
            alarmManager.cancel(buildServiceRecoveryPendingIntent(reason = "", launchUi = true))
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять pending alarm восстановления", t)
        }
    }

    private fun buildServiceRecoveryPendingIntent(reason: String, launchUi: Boolean): PendingIntent {
        return AppRecoveryReceiver.createPendingIntent(
            context = this,
            requestCode = SERVICE_RECOVERY_REQUEST_CODE,
            reason = reason,
            launchUi = launchUi,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getNotificationStateText())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun getNotificationStateText(): String {
        return if (streamActive) {
            getString(R.string.service_notification_stream_running)
        } else {
            getString(R.string.service_notification_stream_stopped)
        }
    }

    private fun updateNotification(@Suppress("UNUSED_PARAMETER") text: String) {
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
        return if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    }

    private data class NetworkPreparation(
        val bindIp: String?,
        val network: Network?,
        val ifacePresent: Boolean,
        val rootRequired: Boolean,
        val ifaceName: String?,
    )

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
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restartServiceCompat(context: Context) {
            val intent = createStartIntent(context).apply {
                action = ACTION_RESTART_SERVICE_NOW
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshFtpCompat(context: Context) {
            val intent = Intent(context, UdpStreamService::class.java).apply {
                action = ACTION_REFRESH_FTP_NOW
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val ACTION_RESTART_SERVICE_NOW = "ru.foric27.cluster.action.RESTART_SERVICE_NOW"
        private const val ACTION_REFRESH_FTP_NOW = "ru.foric27.cluster.action.REFRESH_FTP_NOW"

        private const val TAG = "UdpStreamService"

        private val CHANNEL_ID: String
            get() = RuntimeConfig.Service.NOTIFICATION_CHANNEL_ID

        private val NOTIF_ID: Int
            get() = RuntimeConfig.Service.NOTIFICATION_ID

        private val RESTART_BACKOFF_MAX_MS: Long
            get() = RuntimeConfig.Service.RESTART_BACKOFF_MAX_MS

        private val SERVICE_RECOVERY_DELAY_MS: Long
            get() = RuntimeConfig.Service.SERVICE_RECOVERY_DELAY_MS

        private val TASK_REMOVED_RECOVERY_DELAY_MS: Long
            get() = RuntimeConfig.Service.TASK_REMOVED_RECOVERY_DELAY_MS

        private val SERVICE_RECOVERY_REQUEST_CODE: Int
            get() = RuntimeConfig.Service.SERVICE_RECOVERY_REQUEST_CODE

        private val RESTART_REQUEST_DEBOUNCE_MS: Long
            get() = RuntimeConfig.Service.RESTART_REQUEST_DEBOUNCE_MS

        private val RESTART_BACKOFF_START_MS: Long
            get() = RuntimeConfig.Service.RESTART_BACKOFF_START_MS

        private val ROUTE_WAIT_TIMEOUT_MS: Long
            get() = RuntimeConfig.Service.ROUTE_WAIT_TIMEOUT_MS

        private val ROUTE_WAIT_STEP_MS: Long
            get() = RuntimeConfig.Service.ROUTE_WAIT_STEP_MS

        private val NO_ROUTE_RESTART_BACKOFF_MIN_MS: Long
            get() = RuntimeConfig.Service.NO_ROUTE_RESTART_BACKOFF_MIN_MS

        private val IFACE_MISSING_RESTART_BACKOFF_MIN_MS: Long
            get() = RuntimeConfig.Service.IFACE_MISSING_RESTART_BACKOFF_MIN_MS

        private val NETWORK_LOST_RESTART_BACKOFF_MS: Long
            get() = RuntimeConfig.Service.NETWORK_LOST_RESTART_BACKOFF_MS

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

        private const val WAKE_RECOVERY_DEBOUNCE_MS = 2_000L
        private const val WAKE_VERIFY_DELAY_MS = 1_500L
        private const val WAKE_FORCE_FRAME_SETTLE_DELAY_MS = 1_200L
        private const val WAKE_RELAUNCH_SETTLE_DELAY_MS = 2_500L
    }
}
