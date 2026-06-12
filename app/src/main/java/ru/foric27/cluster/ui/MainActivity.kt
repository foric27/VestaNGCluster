package ru.foric27.cluster.ui
import ru.foric27.cluster.BuildConfig
import ru.foric27.cluster.R
import ru.foric27.cluster.config.*
import ru.foric27.cluster.network.*
import ru.foric27.cluster.service.*
import ru.foric27.cluster.ui.theme.*
import ru.foric27.cluster.update.*
import ru.foric27.cluster.util.*

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.foric27.cluster.config.AppSettings.UiStreamMode
import ru.foric27.cluster.config.AppSettings.UpdateChannel
import timber.log.Timber

/**
 * Главная Activity приложения.
 *
 * Управляет UI, потоковым режимом, обновлениями, уведомлениями и
 * проверкой root-доступа. Является точкой входа для пользователя.
 */
class MainActivity : ComponentActivity() {

    private lateinit var noticeLog: MainNoticeLog
    private lateinit var accessPreflight: MainAccessPreflight
    private var vdspReceiverRegistered = false
    private var versionTapCount = 0
    private var lastVersionTapAt = 0L
    private var lastModeChangeAt = 0L
    private var backgroundLaunchHandled = false
    private var updateBusy = false
    private var pendingRemoteUpdate: AppUpdateManager.RemoteRelease? = null
    private var pendingDownloadedUpdate: AppUpdateManager.DownloadedUpdate? = null
    private var updateQueryJob: Job? = null
    private var updateDownloadJob: Job? = null

    private val _screenState = mutableStateOf(ScreenState())
    private val _ftpState = mutableStateOf("")
    private val _appUpdateState = mutableStateOf(AppUpdateState())
    private val _noticeUpdateTrigger = mutableStateOf(0)

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
                VdspState.ACTION_VDSP_STATE_CHANGED -> refreshScreenState()
                UpdateServerManager.ACTION_UPDATE_SERVER_STATE_CHANGED -> renderFtpState()
            }
        }
    }

    /**
     * Инициализирует UI, проверяет разрешения, запускает сервис и проверяет обновления.
     *
     * @param savedInstanceState сохранённое состояние
     */
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentDisplayId = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId
            else windowManager.defaultDisplay.displayId
        } catch (_: Throwable) { null } ?: 0
        if (currentDisplayId != android.view.Display.DEFAULT_DISPLAY) {
            Timber.tag(TAG).i("MainActivity запущена на display=$currentDisplayId — перенаправляю на MediaCoverActivity")
            startActivity(Intent(this, MediaCoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        RuntimeConfig.init(applicationContext)
        noticeLog = MainNoticeLog()
        noticeLog.setChangeListener { _noticeUpdateTrigger.value++ }

        accessPreflight = MainAccessPreflight(
            activity = this,
            showNotice = noticeLog::show,
            onAllFilesAccessGranted = {
                UdpStreamService.startServiceCompat(this)
                renderFtpState()
            },
            requestNotificationsPermission = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            requestReadStoragePermission = { readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) },
            launchSettingsIntent = ::launchSettingsIntent,
        )

        setContent {
            ClusterTheme {
                MainScreen(
                    screenState = _screenState.value,
                    ftpState = _ftpState.value,
                    appUpdateState = _appUpdateState.value,
                    noticeTrigger = _noticeUpdateTrigger.value,
                    noticeLog = noticeLog,
                    onModeSelected = ::onModeSelected,
                    onRestartStream = ::onRestartStream,
                    onCheckUpdate = { refreshAppUpdateState(force = true) },
                    onInstallUpdate = { onInstallUpdate() },
                    onVersionTap = ::handleVersionTap,
                    onTelegramTap = ::openDeveloperTelegram,
                    onClearNotice = { noticeLog.clear() },
                )
            }
        }

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
        if (!updateBusy) refreshAppUpdateState(silent = true)
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

    /**
     * Применяет выбранный режим потоковой передачи и перезапускает pipeline.
     *
     * @param mode целевой режим (NAV, MED, ABS)
     */
    private fun onModeSelected(mode: UiStreamMode) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastModeChangeAt < 500) return
        lastModeChangeAt = now
        val currentMode = AppSettings.getSelectedMode(this)
        if (currentMode == mode) {
            refreshScreenState(refreshFtp = false)
            noticeLog.show(getString(R.string.stream_mode_apply_ok_fmt, modeLabel(mode)), isError = false)
            return
        }
        val result = AppSettings.applySelectedMode(this, mode)
        if (result.ok || result.savedLocally) UdpStreamService.restartPipelineCompat(this)
        refreshScreenState(refreshFtp = false)
        if (result.ok) noticeLog.show(getString(R.string.stream_mode_apply_ok_fmt, modeLabel(mode)), isError = false)
        else {
            noticeLog.show(getString(R.string.stream_mode_apply_fail_fmt, modeLabel(mode)), isError = true)
            if (result.savedLocally) noticeLog.show(result.details, isError = false)
        }
    }

    /**
     * Перезапускает сервис потоковой передачи.
     */
    private fun onRestartStream() {
        UdpStreamService.restartServiceCompat(this)
        noticeLog.show(getString(R.string.main_restart_stream_requested), isError = false)
        refreshScreenState(refreshFtp = false, consumeWarnings = false)
    }

    private fun onInstallUpdate() {
        val downloaded = pendingDownloadedUpdate
        if (downloaded != null) {
            requestInstallUpdate(downloaded)
        } else {
            val release = pendingRemoteUpdate ?: return
            downloadAppUpdate(release)
        }
    }

    private fun handleVersionTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVersionTapAt > VERSION_TAP_TIMEOUT_MS) versionTapCount = 0
        lastVersionTapAt = now
        versionTapCount++
        if (versionTapCount >= DEVELOPER_TAP_COUNT) {
            versionTapCount = 0
            openDeveloperScreen()
        }
    }

    private fun openDeveloperScreen() {
        runCatching { startActivity(Intent(this, DeveloperActivity::class.java)) }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось открыть экран разработчика") }
    }

    private fun openDeveloperTelegram() {
        val telegramUri = getString(R.string.developer_telegram_link).toUri()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, telegramUri).addCategory(Intent.CATEGORY_BROWSABLE))
        }.onFailure { Timber.tag(TAG).w(it, "Не удалось открыть ссылку Telegram") }
    }

    private fun ensureStreamingRunning() {
        if (!UdpStreamService.isServiceRunning()) UdpStreamService.startServiceCompat(this)
        refreshScreenState()
    }

    private fun refreshScreenState(refreshFtp: Boolean = true, consumeWarnings: Boolean = true) {
        renderDynamicState()
        if (refreshFtp) renderFtpState()
        if (consumeWarnings) noticeLog.consumePendingWarnings()
    }

    private fun renderDynamicState() {
        val displayId = VdspState.getDisplayId()
        val running = UdpStreamService.isServiceRunning()
        val streaming = UdpStreamService.isStreamActive()
        val selectedClusterMode = AppSettings.getSelectedClusterMode(this)

        if (!selectedClusterMode.isVideoStreamMode && running) {
            _screenState.value = ScreenState(
                headline = getString(R.string.status_trip_headline),
                subline = getString(R.string.status_trip_subline),
                selectedMode = AppSettings.getSelectedMode(this),
            )
            return
        }

        val headline = when {
            streaming -> getString(R.string.status_running_headline)
            running -> getString(R.string.status_starting_headline)
            else -> getString(R.string.status_recovering_headline)
        }
        val subline = when {
            streaming && displayId >= 0 -> getString(R.string.status_running_subline, displayId)
            streaming -> when (VdspState.getDisplayState()) {
                VdspState.DisplayState.REMOVED -> getString(R.string.status_running_subline_display_removed)
                VdspState.DisplayState.CHANGED -> getString(R.string.status_running_subline_display_changed)
                else -> getString(R.string.status_running_subline_no_display)
            }
            running -> getString(R.string.status_starting_subline)
            else -> getString(R.string.status_recovering_subline)
        }
        _screenState.value = ScreenState(headline = headline, subline = subline, selectedMode = AppSettings.getSelectedMode(this))
    }

    private fun renderFtpState() {
        val state = UpdateServerManager.getServerState()
        val ftpRunning = state.status == UpdateServerManager.Status.RUNNING
        if (ftpRunning) clearStaleFtpWarnings()
        _ftpState.value = buildString {
            append(if (ftpRunning) {
                state.boundAddress?.let { getString(R.string.ftp_state_running_fmt, it.host, it.port) } ?: getString(R.string.ftp_state_running)
            } else getString(R.string.ftp_state_inactive))
            append("\n")
            append(state.sourceFilePath?.let { getString(R.string.ftp_file_found_fmt, it) }
                ?: state.detectedLocation?.let { getString(R.string.ftp_file_source_fmt, it) }
                ?: getString(R.string.ftp_file_not_found))
        }
    }

    private fun renderAppUpdateChecking(channel: UpdateChannel) {
        _appUpdateState.value = AppUpdateState(
            statusText = getString(R.string.app_update_checking_fmt, updateChannelLabel(channel)),
        )
    }

    /**
     * Проверяет наличие обновления приложения и обновляет UI.
     *
     * @param silent тихая проверка без уведомлений об ошибках
     * @param force принудительная проверка, игнорируя кэш
     */
    private fun refreshAppUpdateState(silent: Boolean = false, force: Boolean = false) {
        if (updateBusy) return
        val channel = AppSettings.getSelectedUpdateChannel(this)
        updateBusy = true
        renderAppUpdateChecking(channel)
        updateQueryJob?.cancel()
        updateQueryJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateManager.queryUpdate(this@MainActivity, channel, force) }
            updateBusy = false
            applyAppUpdateQueryResult(result, silent)
        }
    }

    private fun applyAppUpdateQueryResult(result: AppUpdateManager.QueryResult, silent: Boolean) {
        when (result) {
            is AppUpdateManager.QueryResult.DownloadedReady -> {
                pendingRemoteUpdate = null; pendingDownloadedUpdate = result.update
                _appUpdateState.value = AppUpdateState(
                    statusText = getString(R.string.app_update_downloaded_fmt, result.update.versionName, result.update.versionCode),
                    checkEnabled = true, installEnabled = true, installButtonText = getString(R.string.app_update_install_button),
                )
            }
            is AppUpdateManager.QueryResult.RemoteAvailable -> {
                pendingRemoteUpdate = result.release; pendingDownloadedUpdate = null
                _appUpdateState.value = AppUpdateState(
                    statusText = getString(R.string.app_update_available_fmt, result.release.versionName, result.release.versionCode, updateChannelLabel(result.release.channel)),
                    checkEnabled = true, installEnabled = true, installButtonText = getString(R.string.app_update_download_button),
                )
            }
            is AppUpdateManager.QueryResult.UpToDate -> {
                pendingRemoteUpdate = null; pendingDownloadedUpdate = null
                _appUpdateState.value = AppUpdateState(statusText = result.message, checkEnabled = true)
            }
            is AppUpdateManager.QueryResult.Error -> {
                if (silent) { Timber.tag(TAG).w("Фоновая проверка обновлений: %s", result.message); return }
                pendingRemoteUpdate = null; pendingDownloadedUpdate = null
                _appUpdateState.value = AppUpdateState(statusText = getString(R.string.app_update_error_fmt, result.message), checkEnabled = true)
                noticeLog.show(result.message, isError = true)
            }
        }
    }

    /**
     * Загружает обновление APK из GitHub Releases.
     *
     * @param release информация о релизе для загрузки
     */
    private fun downloadAppUpdate(release: AppUpdateManager.RemoteRelease) {
        if (updateBusy) return
        updateBusy = true
        _appUpdateState.value = AppUpdateState(statusText = getString(R.string.app_update_downloading_fmt, release.versionName, release.versionCode))
        updateDownloadJob?.cancel()
        updateDownloadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateManager.downloadUpdate(this@MainActivity, release) }
            updateBusy = false
            when (result) {
                is AppUpdateManager.DownloadResult.Success -> {
                    pendingRemoteUpdate = null; pendingDownloadedUpdate = result.update
                    _appUpdateState.value = AppUpdateState(
                        statusText = getString(R.string.app_update_downloaded_fmt, result.update.versionName, result.update.versionCode),
                        checkEnabled = true, installEnabled = true, installButtonText = getString(R.string.app_update_install_button),
                    )
                    noticeLog.show(getString(R.string.app_update_download_complete), isError = false)
                }
                is AppUpdateManager.DownloadResult.Error -> {
                    pendingDownloadedUpdate = null; pendingRemoteUpdate = release
                    _appUpdateState.value = AppUpdateState(statusText = getString(R.string.app_update_error_fmt, result.message), checkEnabled = true)
                    noticeLog.show(result.message, isError = true)
                }
            }
        }
    }

    /**
     * Запускает установку загруженного обновления.
     *
     * @param update загруженное обновление
     */
    private fun requestInstallUpdate(update: AppUpdateManager.DownloadedUpdate) {
        when (val result = AppUpdateManager.requestInstall(this, update)) {
            is AppUpdateManager.InstallResult.Started -> noticeLog.show(getString(R.string.app_update_install_started), isError = false)
            is AppUpdateManager.InstallResult.PermissionRequired -> {
                startActivity(result.intent)
                noticeLog.show(getString(R.string.app_update_unknown_sources_required), isError = false)
            }
            is AppUpdateManager.InstallResult.Error -> {
                _appUpdateState.value = _appUpdateState.value.copy(statusText = getString(R.string.app_update_error_fmt, result.message))
                noticeLog.show(result.message, isError = true)
            }
        }
    }

    private fun updateChannelLabel(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.ROLLING -> getString(R.string.app_update_channel_rolling)
        UpdateChannel.STABLE -> getString(R.string.app_update_channel_stable)
    }

    private fun modeLabel(mode: UiStreamMode): String = when (mode) {
        UiStreamMode.NAV -> getString(R.string.stream_mode_nav)
        UiStreamMode.MED -> getString(R.string.stream_mode_med)
        UiStreamMode.ABS -> getString(R.string.stream_mode_abs)
    }

    private fun clearStaleFtpWarnings() {
        val ftpPrefix = getString(R.string.service_notification_ftp_message_fmt, "")
        noticeLog.removeMatching { message ->
            message.startsWith(ftpPrefix) ||
                message.contains(getString(R.string.update_server_start_failed)) ||
                message.contains(getString(R.string.update_server_no_valid_pair))
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
        } catch (t: Throwable) { Timber.tag(TAG).w(t, "Не удалось зарегистрировать VDSP receiver") }
    }

    private fun unregisterVdspReceiverSafely() {
        if (!vdspReceiverRegistered) return
        try { unregisterReceiver(vdspReceiver) }
        catch (t: Throwable) { Timber.tag(TAG).w(t, "Не удалось снять VDSP receiver") }
        finally { vdspReceiverRegistered = false }
    }

    private fun launchSettingsIntent(primary: Intent, fallback: Intent?, onFailure: (Throwable) -> Unit) {
        runCatching { settingsLauncher.launch(primary) }
            .recoverCatching { error -> fallback?.let { settingsLauncher.launch(it) } ?: throw error }
            .onFailure(onFailure)
    }

    /**
     * Проверяет доступность root и публикует предупреждение, если root недоступен.
     */
    private fun checkRootAndNotify() {
        val shell = NetworkRootShell()
        val rootAvailable = try { shell.isAvailable() } catch (_: Throwable) { false } finally { shell.close() }
        if (!rootAvailable) {
            val rootMessage = getString(R.string.msg_root_required)
            if (!AppWarningCenter.contains(rootMessage)) {
                Timber.tag(TAG).w("ROOT НЕ ДОСТУПЕН")
                Toast.makeText(this, R.string.msg_root_missing_toast, Toast.LENGTH_LONG).show()
            }
            AppWarningCenter.publish(rootMessage)
        }
    }

    /**
     * Сворачивает задачу в background после завершения preflight, если включена настройка.
     */
    private fun tryMoveTaskToBackIfNeeded() {
        if (backgroundLaunchHandled) return
        if (intent.getBooleanExtra(EXTRA_KEEP_IN_FOREGROUND, false)) return
        if (!AppSettings.isCollapseOnLaunchEnabled(this)) return
        if (!accessPreflight.isReadyToBackground()) return
        backgroundLaunchHandled = true
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            moveTaskToBack(true)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEVELOPER_TAP_COUNT = 7
        private const val VERSION_TAP_TIMEOUT_MS = 1_500L
        const val EXTRA_KEEP_IN_FOREGROUND = "ru.foric27.cluster.extra.KEEP_IN_FOREGROUND"
    }
}

private data class ScreenState(
    val headline: String = "",
    val subline: String = "",
    val selectedMode: UiStreamMode = UiStreamMode.NAV,
)

private data class AppUpdateState(
    val statusText: String = "",
    val checkEnabled: Boolean = true,
    val installEnabled: Boolean = false,
    val installButtonText: String = "",
)

@Composable
private fun MainScreen(
    screenState: ScreenState,
    ftpState: String,
    appUpdateState: AppUpdateState,
    noticeTrigger: Int,
    noticeLog: MainNoticeLog,
    onModeSelected: (UiStreamMode) -> Unit,
    onRestartStream: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    onTelegramTap: () -> Unit,
    onClearNotice: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 600

    if (isWide) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TitleSection()
                Spacer(Modifier.height(16.dp))
                NoticePanel(noticeTrigger, noticeLog, onClearNotice)
                Spacer(Modifier.height(16.dp))
                StatusSection(screenState)
                Spacer(Modifier.height(16.dp))
                ModeSelector(screenState.selectedMode, onModeSelected)
                Spacer(Modifier.height(16.dp))
                RestartButton(onRestartStream)
            }
            Column(modifier = Modifier.weight(1f)) {
                FtpStatusCard(ftpState)
                Spacer(Modifier.height(16.dp))
                AppUpdateCard(appUpdateState, onCheckUpdate, onInstallUpdate)
                Spacer(Modifier.weight(1f))
                Footer(onVersionTap, onTelegramTap)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TitleSection()
            NoticePanel(noticeTrigger, noticeLog, onClearNotice)
            StatusSection(screenState)
            ModeSelector(screenState.selectedMode, onModeSelected)
            RestartButton(onRestartStream)
            FtpStatusCard(ftpState)
            AppUpdateCard(appUpdateState, onCheckUpdate, onInstallUpdate)
            Footer(onVersionTap, onTelegramTap)
        }
    }
}

@Composable
private fun TitleSection() {
    Text(
        text = stringResource(R.string.app_name),
        color = Primary,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun NoticePanel(trigger: Int, noticeLog: MainNoticeLog, onClear: () -> Unit) {
    if (noticeLog.isEmpty()) return
    val hasErrors = noticeLog.hasErrors()
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hasErrors) stringResource(R.string.inline_notice_title_warning) else stringResource(R.string.inline_notice_title_info),
                    color = Warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.inline_notice_clear), color = Warning, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                Text(text = noticeLog.renderText(), color = TextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatusSection(state: ScreenState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = state.headline, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text = state.subline, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModeSelector(selected: UiStreamMode, onSelect: (UiStreamMode) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stream_mode_title),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            UiStreamMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = selected == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            UiStreamMode.NAV -> stringResource(R.string.stream_mode_nav)
                            UiStreamMode.MED -> stringResource(R.string.stream_mode_med)
                            UiStreamMode.ABS -> stringResource(R.string.stream_mode_abs)
                        },
                        color = TextPrimary,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestartButton(onRestart: () -> Unit) {
    Button(
        onClick = onRestart,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
    ) {
        Text(stringResource(R.string.main_restart_stream_button), color = OnPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun FtpStatusCard(ftpState: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ftp_title),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = ftpState, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.app_update_title),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = state.statusText, color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = state.checkEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.app_update_check_button), fontSize = 12.sp)
                }
                Button(
                    onClick = onInstall,
                    enabled = state.installEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    Text(state.installButtonText.ifEmpty { stringResource(R.string.app_update_download_button) }, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Footer(onVersionTap: () -> Unit, onTelegramTap: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_version_fmt, BuildConfig.VERSION_NAME),
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.clickable { onVersionTap() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.developer_telegram),
            color = Secondary,
            fontSize = 12.sp,
            modifier = Modifier.clickable { onTelegramTap() },
        )
    }
}
