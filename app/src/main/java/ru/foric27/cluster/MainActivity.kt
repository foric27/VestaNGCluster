package ru.foric27.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.foric27.cluster.AppSettings.UiStreamMode
import ru.foric27.cluster.databinding.ActivityMainBinding
import java.util.ArrayDeque

/**
 * Минимальный экран управления трансляцией навигатора на комбинацию приборов.
 */
class MainActivity : AppCompatActivity() {

    private data class InlineNotice(
        val text: String,
        val isError: Boolean,
    )

    private lateinit var binding: ActivityMainBinding
    private val inlineNotices = ArrayDeque<InlineNotice>()
    private var vdspReceiverRegistered = false
    private var manageStorageSettingsOpened = false
    private var hadAllFilesAccess = false
    private var versionTapCount = 0
    private var lastVersionTapAt = 0L
    private var notificationsPermissionPending = false
    private var backgroundLaunchHandled = false

    private val warningListener = object : AppWarningCenter.WarningListener {
        override fun onWarningPublished(message: String) {
            runOnUiThread {
                showInlineNotice(message, isError = true)
            }
        }
    }

    private val vdspReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (VdspState.ACTION_VDSP_READY != intent.action) return
            refreshScreenState()
            showInlineNotice(getString(R.string.msg_vdsp_ready), isError = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeConfig.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindNoticePanel()
        bindModeSelector()
        bindFooterInfo()
        refreshScreenState()
        renderNoticePanel()

        requestNotificationsPermissionIfNeeded()
        handleAllFilesAccessState()
        ensureStreamingRunning()
        tryMoveTaskToBackIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        AppWarningCenter.registerListener(warningListener)
        registerVdspReceiverSafely()
        refreshScreenState()
    }

    override fun onResume() {
        super.onResume()
        handleAllFilesAccessState()
        refreshScreenState()
        tryMoveTaskToBackIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        backgroundLaunchHandled = false
    }

    override fun onStop() {
        AppWarningCenter.unregisterListener(warningListener)
        unregisterVdspReceiverSafely()
        super.onStop()
    }

    private fun bindNoticePanel() {
        binding.noticeDismissBtn.setOnClickListener {
            inlineNotices.clear()
            renderNoticePanel()
        }
    }

    private fun bindModeSelector() {
        val selected = AppSettings.getSelectedMode(this)
        binding.modeRadioGroup.setOnCheckedChangeListener(null)
        binding.modeRadioGroup.check(modeToRadioId(selected))

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = radioIdToMode(checkedId) ?: return@setOnCheckedChangeListener
            val result = AppSettings.applySelectedMode(this, mode)
            refreshScreenState(refreshFtp = false)

            if (result.ok) {
                showInlineNotice(getString(R.string.stream_mode_apply_ok_fmt, modeLabel(mode)), isError = false)
            } else {
                showInlineNotice(getString(R.string.stream_mode_apply_fail_fmt, modeLabel(mode)), isError = true)
                if (result.savedLocally) {
                    showInlineNotice(result.details, isError = false)
                }
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
            Log.w(TAG, "Не удалось открыть экран разработчика", error)
            showInlineNotice(getString(R.string.main_open_developer_failed), isError = true)
        }
    }

    private fun openDeveloperTelegram() {
        val telegramUri = Uri.parse(getString(R.string.developer_telegram_link))
        val launchIntent = Intent(Intent.ACTION_VIEW, telegramUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching {
            startActivity(launchIntent)
        }.onFailure { error ->
            Log.w(TAG, "Не удалось открыть ссылку Telegram разработчика", error)
            showInlineNotice(getString(R.string.main_open_telegram_failed), isError = true)
        }
    }

    private fun ensureStreamingRunning() {
        if (UdpStreamService.isServiceRunning()) {
            refreshScreenState()
            return
        }

        startStreamingService()
        refreshScreenState()
    }

    private fun startStreamingService() {
        UdpStreamService.startServiceCompat(this)
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
            showPendingWarnings()
        }
    }

    private fun renderDynamicState() {
        val displayId = VdspState.getDisplayId()
        val running = UdpStreamService.isServiceRunning()
        val streaming = UdpStreamService.isStreamActive()

        binding.statusHeadline.text = when {
            streaming -> getString(R.string.status_running_headline)
            running -> getString(R.string.status_starting_headline)
            else -> getString(R.string.status_recovering_headline)
        }

        binding.statusSubline.text = when {
            streaming && displayId >= 0 -> getString(R.string.status_running_subline, displayId)
            streaming -> getString(R.string.status_running_subline_no_display)
            running -> getString(R.string.status_starting_subline)
            else -> getString(R.string.status_recovering_subline)
        }
    }

    private fun renderFtpState() {
        val state = UpdateServerManager.getServerState()
        binding.ftpStatusText.text = buildString {
            append(
                if (state.status == UpdateServerManager.Status.RUNNING) {
                    getString(R.string.ftp_state_running)
                } else {
                    getString(R.string.ftp_state_inactive)
                },
            )
            append("\n")
            append(
                state.detectedLocation?.let { location ->
                    getString(R.string.ftp_file_found_fmt, location)
                } ?: getString(R.string.ftp_file_not_found),
            )
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

    private fun showPendingWarnings() {
        AppWarningCenter.consumeAll().forEach { showInlineNotice(it, isError = true) }
    }

    private fun showInlineNotice(msg: String, isError: Boolean) {
        val normalized = msg.trim()
        if (normalized.isEmpty()) return

        val existing = inlineNotices.firstOrNull { it.text == normalized }
        if (existing != null && (!isError || existing.isError)) {
            renderNoticePanel()
            return
        }

        inlineNotices.removeAll { it.text == normalized }
        inlineNotices.addFirst(InlineNotice(normalized, isError))
        while (inlineNotices.size > MAX_INLINE_NOTICES) {
            inlineNotices.removeLast()
        }
        renderNoticePanel()
    }

    private fun renderNoticePanel() {
        if (inlineNotices.isEmpty()) {
            binding.noticePanel.visibility = View.GONE
            binding.noticeText.text = ""
            return
        }

        val hasErrors = inlineNotices.any { it.isError }
        binding.noticePanel.visibility = View.VISIBLE
        binding.noticeTitle.text = if (hasErrors) {
            getString(R.string.inline_notice_title_warning)
        } else {
            getString(R.string.inline_notice_title_info)
        }
        binding.noticeText.text = inlineNotices.joinToString("\n\n") {
            getString(R.string.inline_notice_item_fmt, it.text)
        }
    }

    private fun registerVdspReceiverSafely() {
        if (vdspReceiverRegistered) return
        try {
            val filter = IntentFilter(VdspState.ACTION_VDSP_READY)
            ContextCompat.registerReceiver(this, vdspReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            vdspReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось зарегистрировать VDSP receiver", t)
        }
    }

    private fun unregisterVdspReceiverSafely() {
        if (!vdspReceiverRegistered) return
        try {
            unregisterReceiver(vdspReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось снять VDSP receiver", t)
        } finally {
            vdspReceiverRegistered = false
        }
    }

    private fun handleAllFilesAccessState() {
        val hasAccess = StorageAccessManager.isAllFilesAccessGranted()
        val accessJustGranted = hasAccess && !hadAllFilesAccess
        hadAllFilesAccess = hasAccess

        if (!hasAccess) {
            showInlineNotice(StorageAccessManager.buildMissingAccessMessage(this), isError = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !manageStorageSettingsOpened) {
                manageStorageSettingsOpened = true
                openManageAllFilesAccessSettings()
            }
            renderFtpState()
            return
        }

        if (accessJustGranted) {
            showInlineNotice(getString(R.string.main_all_files_access_granted), isError = false)
            UdpStreamService.startServiceCompat(this)
            UpdateServerManager.restartServer()
            renderFtpState()
        }
    }

    private fun openManageAllFilesAccessSettings() {
        runCatching {
            startActivity(StorageAccessManager.buildManageAllFilesAccessIntent(this))
        }.recoverCatching {
            startActivity(StorageAccessManager.buildManageAllFilesAccessFallbackIntent())
        }.onFailure { error ->
            Log.w(TAG, "Не удалось открыть настройки доступа ко всем файлам", error)
            showInlineNotice(getString(R.string.main_open_files_settings_failed), isError = true)
        }
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationsPermissionPending = true
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS_CODE) return
        notificationsPermissionPending = false
        tryMoveTaskToBackIfNeeded()
    }

    private fun tryMoveTaskToBackIfNeeded() {
        if (backgroundLaunchHandled) return
        if (intent.getBooleanExtra(EXTRA_KEEP_IN_FOREGROUND, false)) return
        if (notificationsPermissionPending) return
        if (!StorageAccessManager.isAllFilesAccessGranted()) return

        backgroundLaunchHandled = true
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            moveTaskToBack(true)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_NOTIFICATIONS_CODE = 10
        private const val MAX_INLINE_NOTICES = 6
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
