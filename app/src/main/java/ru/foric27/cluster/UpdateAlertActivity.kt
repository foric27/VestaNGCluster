package ru.foric27.cluster

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import timber.log.Timber

/**
 * Прозрачная Activity для показа диалога обнаружения нового обновления.
 * Запускается из сервиса/координатора с флагом FLAG_ACTIVITY_NEW_TASK.
 */
internal class UpdateAlertActivity : Activity() {

    private var finishReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerFinishReceiver()

        val updateLocation = intent.getStringExtra(EXTRA_UPDATE_LOCATION).orEmpty()
        val searchPolicyName = intent.getStringExtra(EXTRA_SEARCH_POLICY)
            ?: UpdateFileLocator.SearchPolicy.USB_ONLY.name

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_alert_title))
            .setMessage(getString(R.string.update_alert_message_fmt, updateLocation))
            .setPositiveButton(getString(R.string.update_alert_update)) { _, _ ->
                performUpdate(searchPolicyName)
                finish()
            }
            .setNegativeButton(getString(R.string.update_alert_cancel)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        finishReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        finishReceiver = null
        super.onDestroy()
    }

    private fun registerFinishReceiver() {
        finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_FINISH_UPDATE_ALERT) {
                    Timber.tag(TAG).i("Получен broadcast завершения UpdateAlertActivity")
                    finish()
                }
            }
        }
        val filter = IntentFilter(ACTION_FINISH_UPDATE_ALERT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishReceiver, filter)
        }
    }

    private fun performUpdate(searchPolicyName: String) {
        val policy = try {
            UpdateFileLocator.SearchPolicy.valueOf(searchPolicyName)
        } catch (_: IllegalArgumentException) {
            UpdateFileLocator.SearchPolicy.USB_ONLY
        }
        Thread {
            try {
                val result = UpdateServerManager.prepareAndStartServer(applicationContext, policy)
                if (result.success) {
                    val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
                    Timber.tag(TAG).i("FTP обновлён после подтверждения пользователя: $address")
                } else {
                    Timber.tag(TAG).w("Не удалось обновить FTP после подтверждения: ${result.message}")
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Ошибка при обновлении FTP из диалога")
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val ACTION_FINISH_UPDATE_ALERT = "ru.foric27.cluster.action.FINISH_UPDATE_ALERT"
        private const val TAG = "UpdateAlertActivity"
        private const val EXTRA_UPDATE_LOCATION = "update_location"
        private const val EXTRA_SEARCH_POLICY = "search_policy"

        fun createIntent(
            context: Context,
            updateLocation: String,
            searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.USB_ONLY,
        ): Intent {
            return Intent(context, UpdateAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(EXTRA_UPDATE_LOCATION, updateLocation)
                putExtra(EXTRA_SEARCH_POLICY, searchPolicy.name)
            }
        }

        fun dismiss(context: Context) {
            context.sendBroadcast(Intent(ACTION_FINISH_UPDATE_ALERT).setPackage(context.packageName))
        }
    }
}
