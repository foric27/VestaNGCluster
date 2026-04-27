package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import timber.log.Timber
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ru.foric27.cluster.AppSettings.UiStreamMode
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG) {
            val sigResult = SignatureVerifier.verify(this)
            if (!sigResult.valid) {
                Timber.tag(TAG).e("Несовпадение подписи APK: expected=${sigResult.expectedSha256}, actual=${sigResult.actualSha256}")
                showSignatureMismatchDialog(sigResult.actualSha256, sigResult.expectedSha256)
                return
            }
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
        )

        noticeLog.bindClearAction()
        bindModeSelector()
        bindActions()
        bindFooterInfo()
        refreshScreenState()
        noticeLog.render()

        accessPreflight.run()
        ensureStreamingRunning()
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

    private fun bindModeSelector() {
        val selected = AppSettings.getSelectedMode(this)
        binding.modeRadioGroup.setOnCheckedChangeListener(null)
        binding.modeRadioGroup.check(modeToRadioId(selected))

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = radioIdToMode(checkedId) ?: return@setOnCheckedChangeListener
            val result = AppSettings.applySelectedMode(this, mode)
            if (result.ok || result.savedLocally) {
                UdpStreamService.restartServiceCompat(this)
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

    private fun showSignatureMismatchDialog(actual: String, expected: String) {
        val expectedFmt = expected.takeIf { it.isNotBlank() } ?: "not set"
        val actualFmt = actual.takeIf { it.isNotBlank() } ?: "unavailable"
        val message = getString(R.string.signature_mismatch_message_fmt, expectedFmt, actualFmt)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.signature_mismatch_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.signature_mismatch_close) { _, _ ->
                finish()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!accessPreflight.handlePermissionsResult(requestCode, grantResults)) return
        tryMoveTaskToBackIfNeeded()
    }

    private fun tryMoveTaskToBackIfNeeded() {
        if (backgroundLaunchHandled) return
        if (intent.getBooleanExtra(EXTRA_KEEP_IN_FOREGROUND, false)) return
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
            }
        }
    }
}
