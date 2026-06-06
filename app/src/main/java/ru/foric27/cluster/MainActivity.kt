package ru.foric27.cluster

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import timber.log.Timber
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.foric27.cluster.AppSettings.UiStreamMode
import ru.foric27.cluster.AppSettings.UpdateChannel
import ru.foric27.cluster.databinding.ActivityMainBinding

/**
 * Главный экран приложения.
 *
 * Activity намеренно остается тонкой: она связывает lifecycle, рендерит текущее
 * состояние сервиса и делегирует preflight доступа и журнал предупреждений
 * отдельным helper-классам.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var noticeLog: MainNoticeLog
    private lateinit var accessPreflight: MainAccessPreflight
    private var vdspReceiverRegistered = false
    private var versionTapCount = 0
    private var lastVersionTapAt = 0L
    private var backgroundLaunchHandled = false
    private var updateBusy = false
    private var pendingRemoteUpdate: AppUpdateManager.RemoteRelease? = null
    private var pendingDownloadedUpdate: AppUpdateManager.DownloadedUpdate? = null
    private var updateQueryJob: Job? = null
    private var updateDownloadJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        accessPreflight.handleNotificationsPermissionResult(granted)
        tryMoveTaskToBackIfNeeded()
    }

    private val readStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        accessPreflight.handleReadStoragePermissionResult(granted)
        tryMoveTaskToBackIfNeeded()
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        accessPreflight.handleSettingsActivityResult()
        tryMoveTaskToBackIfNeeded()
    }

    private val vdspReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                VdspState.ACTION_VDSP_READY -> {
                    refreshScreenState()
                    noticeLog.show(getString(R.string.msg_vdsp_ready), isError = false)
                }

                VdspState.ACTION_VDSP_STATE_CHANGED -> {
                    refreshScreenState()
                }

                UpdateServerManager.ACTION_UPDATE_SERVER_STATE_CHANGED -> {
                    renderFtpState()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                display?.displayId
            } else {
                windowManager.defaultDisplay.displayId
            }
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId != android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).i("MainActivity запущена на display=$currentDisplayId — перенаправляю на MediaCoverActivity")
            val intent = Intent(this, MediaCoverActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        RuntimeConfig.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noticeLog = MainNoticeLog(this, binding)
        accessPreflight = MainAccessPreflight(
            activity = this,
            showNotice = noticeLog::show,
            onAllFilesAccessGranted = {
                UdpStreamService.startServiceCompat(this)
                renderFtpState()
            },
            requestNotificationsPermission = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            requestReadStoragePermission = {
                readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            launchSettingsIntent = ::launchSettingsIntent,
        )

        noticeLog.bindClearAction()
        bindModeSelector()
        bindActions()
        bindFooterInfo()
        renderAppUpdateIdle()
        refreshScreenState()
        noticeLog.render()

        accessPreflight.run()
        checkRootAndNotify()
        ensureStreamingRunning()
        refreshAppUpdateState()
        tryMoveTaskToBackIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        AppWarningCenter.registerListener(noticeLog.warningListener)
        registerVdspReceiverSafely()
        refreshScreenState()
    }

    override fun onResume() {
        super.onResume()
        accessPreflight.run()
        refreshScreenState()
        if (!updateBusy) {
            refreshAppUpdateState(silent = true)
        }
        tryMoveTaskToBackIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        backgroundLaunchHandled = false
    }

    override fun onStop() {
        AppWarningCenter.unregisterListener(noticeLog.warningListener)
        unregisterVdspReceiverSafely()
        super.onStop()
    }

    override fun onDestroy() {
        updateQueryJob?.cancel()
        updateDownloadJob?.cancel()
        super.onDestroy()
    }

    private fun bindModeSelector() {
        val selected = AppSettings.getSelectedMode(this)
        binding.modeRadioGroup.setOnCheckedChangeListener(null)
        binding.modeRadioGroup.check(modeToRadioId(selected))

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = radioIdToMode(checkedId) ?: return@setOnCheckedChangeListener
            val result = AppSettings.applySelectedMode(this, mode)
            if (result.ok || result.savedLocally) {
                UdpStreamService.restartPipelineCompat(this)
            }
            refreshScreenState(refreshFtp = false)

            if (result.ok) {
                noticeLog.show(getString(R.string.stream_mode_apply_ok_fmt, modeLabel(mode)), isError = false)
            } else {
                noticeLog.show(getString(R.string.stream_mode_apply_fail_fmt, modeLabel(mode)), isError = true)
                if (result.savedLocally) {
                    noticeLog.show(result.details, isError = false)
                }
            }
        }
    }

    private fun bindActions() {
        binding.restartStreamBtn.setOnClickListener {
            UdpStreamService.restartServiceCompat(this)
            noticeLog.show(getString(R.string.main_restart_stream_requested), isError = false)
            refreshScreenState(refreshFtp = false, consumeWarnings = false)
        }
        binding.appUpdateCheckBtn.setOnClickListener {
            refreshAppUpdateState(force = true)
        }
        binding.appUpdateInstallBtn.setOnClickListener {
            val downloaded = pendingDownloadedUpdate
            if (downloaded != null) {
                requestInstallUpdate(downloaded)
            } else {
                val release = pendingRemoteUpdate ?: return@setOnClickListener
                downloadAppUpdate(release)
            }
        }
    }

    private fun bindFooterInfo() {
        binding.versionText.text = getString(R.string.app_version_fmt, BuildConfig.VERSION_NAME)
        binding.versionText.setOnClickListener { handleVersionTap() }
        binding.developerTelegramText.setOnClickListener { openDeveloperTelegram() }
    }

    private fun handleVersionTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVersionTapAt > VERSION_TAP_TIMEOUT_MS) {
            versionTapCount = 0
        }
        lastVersionTapAt = now
        versionTapCount += 1

        if (versionTapCount >= DEVELOPER_TAP_COUNT) {
            versionTapCount = 0
            openDeveloperScreen()
        }
    }

    private fun openDeveloperScreen() {
        runCatching {
            startActivity(Intent(this, DeveloperActivity::class.java))
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть экран разработчика")
            noticeLog.show(getString(R.string.main_open_developer_failed), isError = true)
        }
    }

    private fun openDeveloperTelegram() {
        val telegramUri = getString(R.string.developer_telegram_link).toUri()
        val launchIntent = Intent(Intent.ACTION_VIEW, telegramUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching {
            startActivity(launchIntent)
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Не удалось открыть ссылку Telegram разработчика")
            noticeLog.show(getString(R.string.main_open_telegram_failed), isError = true)
        }
    }

    private fun ensureStreamingRunning() {
        if (UdpStreamService.isServiceRunning()) {
            refreshScreenState()
            return
        }

        UdpStreamService.startServiceCompat(this)
        refreshScreenState()
    }

    private fun refreshScreenState(
        refreshFtp: Boolean = true,
        consumeWarnings: Boolean = true,
    ) {
        renderDynamicState()
        if (refreshFtp) {
            renderFtpState()
        }
        if (consumeWarnings) {
            noticeLog.consumePendingWarnings()
        }
    }

    private fun renderDynamicState() {
        val displayId = VdspState.getDisplayId()
        val running = UdpStreamService.isServiceRunning()
        val streaming = UdpStreamService.isStreamActive()
        val selectedClusterMode = AppSettings.getSelectedClusterMode(this)

        if (!selectedClusterMode.isVideoStreamMode && running) {
            binding.statusHeadline.text = getString(R.string.status_trip_headline)
            binding.statusSubline.text = getString(R.string.status_trip_subline)
            return
        }

        binding.statusHeadline.text = when {
            streaming -> getString(R.string.status_running_headline)
            running -> getString(R.string.status_starting_headline)
            else -> getString(R.string.status_recovering_headline)
        }

        binding.statusSubline.text = when {
            streaming && displayId >= 0 -> getString(R.string.status_running_subline, displayId)
            streaming -> when (VdspState.getDisplayState()) {
                VdspState.DisplayState.REMOVED -> getString(R.string.status_running_subline_display_removed)
                VdspState.DisplayState.CHANGED -> getString(R.string.status_running_subline_display_changed)
                else -> getString(R.string.status_running_subline_no_display)
            }

            running -> getString(R.string.status_starting_subline)
            else -> getString(R.string.status_recovering_subline)
        }
    }

    private fun renderFtpState() {
        val state = UpdateServerManager.getServerState()
        val ftpRunning = state.status == UpdateServerManager.Status.RUNNING
        if (ftpRunning) clearStaleFtpWarnings()
        binding.ftpStatusText.text = buildString {
            append(
                if (ftpRunning) {
                    state.boundAddress?.let { address ->
                        getString(R.string.ftp_state_running_fmt, address.host, address.port)
                    } ?: getString(R.string.ftp_state_running)
                } else {
                    getString(R.string.ftp_state_inactive)
                },
            )
            append("\n")
            append(
                state.sourceFilePath?.let { path ->
                    getString(R.string.ftp_file_found_fmt, path)
                } ?: state.detectedLocation?.let { location ->
                    getString(R.string.ftp_file_source_fmt, location)
                } ?: getString(R.string.ftp_file_not_found),
            )
        }
    }

    private fun refreshAppUpdateState(silent: Boolean = false, force: Boolean = false) {
        if (updateBusy) return
        val channel = AppSettings.getSelectedUpdateChannel(this)
        updateBusy = true
        renderAppUpdateChecking(channel)
        updateQueryJob?.cancel()
        updateQueryJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateManager.queryUpdate(this@MainActivity, channel, force)
            }
            updateBusy = false
            applyAppUpdateQueryResult(result, silent)
        }
    }

    private fun applyAppUpdateQueryResult(result: AppUpdateManager.QueryResult, silent: Boolean) {
        when (result) {
            is AppUpdateManager.QueryResult.DownloadedReady -> {
                pendingRemoteUpdate = null
                pendingDownloadedUpdate = result.update
                renderAppUpdateDownloaded(result.update)
            }

            is AppUpdateManager.QueryResult.RemoteAvailable -> {
                pendingRemoteUpdate = result.release
                pendingDownloadedUpdate = null
                renderAppUpdateAvailable(result.release)
            }

            is AppUpdateManager.QueryResult.UpToDate -> {
                pendingRemoteUpdate = null
                pendingDownloadedUpdate = null
                renderAppUpdateUpToDate(result.message)
            }

            is AppUpdateManager.QueryResult.Error -> {
                if (silent) return
                pendingRemoteUpdate = null
                pendingDownloadedUpdate = null
                renderAppUpdateError(result.message)
                noticeLog.show(result.message, isError = true)
            }
        }
    }

    private fun downloadAppUpdate(release: AppUpdateManager.RemoteRelease) {
        if (updateBusy) return
        updateBusy = true
        binding.appUpdateStatusText.text = getString(
            R.string.app_update_downloading_fmt,
            release.versionName,
            release.versionCode,
        )
        binding.appUpdateCheckBtn.isEnabled = false
        binding.appUpdateInstallBtn.isEnabled = false
        updateDownloadJob?.cancel()
        updateDownloadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdateManager.downloadUpdate(this@MainActivity, release)
            }
            updateBusy = false
            when (result) {
                is AppUpdateManager.DownloadResult.Success -> {
                    pendingRemoteUpdate = null
                    pendingDownloadedUpdate = result.update
                    renderAppUpdateDownloaded(result.update)
                    noticeLog.show(getString(R.string.app_update_download_complete), isError = false)
                }

                is AppUpdateManager.DownloadResult.Error -> {
                    pendingDownloadedUpdate = null
                    pendingRemoteUpdate = release
                    renderAppUpdateError(result.message)
                    noticeLog.show(result.message, isError = true)
                }
            }
        }
    }

    private fun requestInstallUpdate(update: AppUpdateManager.DownloadedUpdate) {
        when (val result = AppUpdateManager.requestInstall(this, update)) {
            is AppUpdateManager.InstallResult.Started -> {
                noticeLog.show(getString(R.string.app_update_install_started), isError = false)
            }

            is AppUpdateManager.InstallResult.PermissionRequired -> {
                startActivity(result.intent)
                noticeLog.show(getString(R.string.app_update_unknown_sources_required), isError = false)
            }

            is AppUpdateManager.InstallResult.Error -> {
                renderAppUpdateError(result.message)
                noticeLog.show(result.message, isError = true)
            }
        }
    }

    private fun renderAppUpdateIdle() {
        binding.appUpdateStatusText.text = getString(R.string.app_update_idle)
        binding.appUpdateCheckBtn.isEnabled = true
        binding.appUpdateInstallBtn.isEnabled = false
        binding.appUpdateInstallBtn.text = getString(R.string.app_update_download_button)
    }

    private fun renderAppUpdateChecking(channel: UpdateChannel) {
        binding.appUpdateStatusText.text = getString(R.string.app_update_checking_fmt, updateChannelLabel(channel))
        binding.appUpdateCheckBtn.isEnabled = false
        binding.appUpdateInstallBtn.isEnabled = false
        binding.appUpdateInstallBtn.text = getString(R.string.app_update_download_button)
    }

    private fun renderAppUpdateAvailable(release: AppUpdateManager.RemoteRelease) {
        binding.appUpdateStatusText.text = getString(
            R.string.app_update_available_fmt,
            release.versionName,
            release.versionCode,
            updateChannelLabel(release.channel),
        )
        binding.appUpdateCheckBtn.isEnabled = true
        binding.appUpdateInstallBtn.isEnabled = true
        binding.appUpdateInstallBtn.text = getString(R.string.app_update_download_button)
    }

    private fun renderAppUpdateDownloaded(update: AppUpdateManager.DownloadedUpdate) {
        binding.appUpdateStatusText.text = getString(
            R.string.app_update_downloaded_fmt,
            update.versionName,
            update.versionCode,
        )
        binding.appUpdateCheckBtn.isEnabled = true
        binding.appUpdateInstallBtn.isEnabled = true
        binding.appUpdateInstallBtn.text = getString(R.string.app_update_install_button)
    }

    private fun renderAppUpdateUpToDate(message: String) {
        binding.appUpdateStatusText.text = message
        binding.appUpdateCheckBtn.isEnabled = true
        binding.appUpdateInstallBtn.isEnabled = false
        binding.appUpdateInstallBtn.text = getString(R.string.app_update_download_button)
    }

    private fun renderAppUpdateError(message: String) {
        binding.appUpdateStatusText.text = getString(R.string.app_update_error_fmt, message)
        binding.appUpdateCheckBtn.isEnabled = true
        binding.appUpdateInstallBtn.isEnabled = pendingDownloadedUpdate != null || pendingRemoteUpdate != null
        binding.appUpdateInstallBtn.text = if (pendingDownloadedUpdate != null) {
            getString(R.string.app_update_install_button)
        } else {
            getString(R.string.app_update_download_button)
        }
    }

    private fun updateChannelLabel(channel: UpdateChannel): String {
        return when (channel) {
            UpdateChannel.ROLLING -> getString(R.string.app_update_channel_rolling)
            UpdateChannel.STABLE -> getString(R.string.app_update_channel_stable)
        }
    }

    private fun clearStaleFtpWarnings() {
        val ftpPrefix = getString(R.string.service_notification_ftp_message_fmt, "")
        noticeLog.removeMatching { message ->
            message.startsWith(ftpPrefix) ||
                message.contains(getString(R.string.update_server_start_failed)) ||
                message.contains(getString(R.string.update_server_no_valid_pair))
        }
    }

    private fun modeLabel(mode: UiStreamMode): String {
        return when (mode) {
            UiStreamMode.NAV -> getString(R.string.stream_mode_nav)
            UiStreamMode.MED -> getString(R.string.stream_mode_med)
            UiStreamMode.ABS -> getString(R.string.stream_mode_abs)
        }
    }

    private fun modeToRadioId(mode: UiStreamMode): Int {
        return when (mode) {
            UiStreamMode.NAV -> binding.modeNavRadio.id
            UiStreamMode.MED -> binding.modeMedRadio.id
            UiStreamMode.ABS -> binding.modeAbsRadio.id
        }
    }

    private fun radioIdToMode(radioId: Int): UiStreamMode? {
        return when (radioId) {
            binding.modeNavRadio.id -> UiStreamMode.NAV
            binding.modeMedRadio.id -> UiStreamMode.MED
            binding.modeAbsRadio.id -> UiStreamMode.ABS
            else -> null
        }
    }

    private fun registerVdspReceiverSafely() {
        if (vdspReceiverRegistered) return
        try {
            val filter = IntentFilter(VdspState.ACTION_VDSP_READY).apply {
                addAction(VdspState.ACTION_VDSP_STATE_CHANGED)
                addAction(UpdateServerManager.ACTION_UPDATE_SERVER_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(this, vdspReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            vdspReceiverRegistered = true
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось зарегистрировать VDSP receiver")
        }
    }

    private fun unregisterVdspReceiverSafely() {
        if (!vdspReceiverRegistered) return
        try {
            unregisterReceiver(vdspReceiver)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось снять VDSP receiver")
        } finally {
            vdspReceiverRegistered = false
        }
    }

    private fun launchSettingsIntent(
        primary: Intent,
        fallback: Intent?,
        onFailure: (Throwable) -> Unit,
    ) {
        launchWithFallback(settingsLauncher, primary, fallback, onFailure)
    }

    private fun launchWithFallback(
        launcher: ActivityResultLauncher<Intent>,
        primary: Intent,
        fallback: Intent?,
        onFailure: (Throwable) -> Unit,
    ) {
        runCatching {
            launcher.launch(primary)
        }.recoverCatching { error ->
            fallback?.let { launcher.launch(it) } ?: throw error
        }.onFailure(onFailure)
    }

    private fun checkRootAndNotify() {
        val shell = NetworkRootShell()
        val rootAvailable = try {
            shell.isAvailable()
        } catch (_: Throwable) {
            false
        } finally {
            shell.close()
        }
        if (!rootAvailable) {
            val rootMessage = getString(R.string.msg_root_required)
            val shouldShowWarning = !AppWarningCenter.contains(rootMessage)
            if (shouldShowWarning) {
                Timber.tag(TAG).w("ROOT НЕ ДОСТУПЕН: приложение запущено без root-прав")
                Toast.makeText(this, R.string.msg_root_missing_toast, Toast.LENGTH_LONG).show()
            }
            AppWarningCenter.publish(rootMessage)
        }
    }

    private fun tryMoveTaskToBackIfNeeded() {
        if (backgroundLaunchHandled) return
        if (intent.getBooleanExtra(EXTRA_KEEP_IN_FOREGROUND, false)) return
        if (!AppSettings.isCollapseOnLaunchEnabled(this)) return
        if (!accessPreflight.isReadyToBackground()) return

        backgroundLaunchHandled = true
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            moveTaskToBack(true)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEVELOPER_TAP_COUNT = 7
        private const val VERSION_TAP_TIMEOUT_MS = 1_500L
        const val EXTRA_KEEP_IN_FOREGROUND = "ru.foric27.cluster.extra.KEEP_IN_FOREGROUND"

        fun createLaunchIntent(context: Context, keepInForeground: Boolean): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_KEEP_IN_FOREGROUND, keepInForeground)
                setPackage(context.packageName)
            }
        }
    }
}
