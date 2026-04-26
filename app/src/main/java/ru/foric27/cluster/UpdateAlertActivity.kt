package ru.foric27.cluster

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.app.AlertDialog

/**
 * Прозрачная Activity для показа диалога обнаружения нового обновления.
 * Запускается из сервиса/координатора с флагом FLAG_ACTIVITY_NEW_TASK.
 */
internal class UpdateAlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val updateLocation = intent.getStringExtra(EXTRA_UPDATE_LOCATION).orEmpty()
        val searchPolicyName = intent.getStringExtra(EXTRA_SEARCH_POLICY)
            ?: UpdateFileLocator.SearchPolicy.USB_FIRST.name

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
            .setOnDismissListener { finish() }
            .setCancelable(false)
            .show()
    }

    private fun performUpdate(searchPolicyName: String) {
        val policy = try {
            UpdateFileLocator.SearchPolicy.valueOf(searchPolicyName)
        } catch (_: IllegalArgumentException) {
            UpdateFileLocator.SearchPolicy.USB_FIRST
        }
        Thread {
            try {
                val result = UpdateServerManager.prepareAndStartServer(applicationContext, policy)
                if (result.success) {
                    val address = result.boundAddress?.let { "${it.host}:${it.port}" } ?: "без адреса"
                    Log.i(TAG, "FTP обновлён после подтверждения пользователя: $address")
                } else {
                    Log.w(TAG, "Не удалось обновить FTP после подтверждения: ${result.message}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка при обновлении FTP из диалога", t)
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "UpdateAlertActivity"
        private const val EXTRA_UPDATE_LOCATION = "update_location"
        private const val EXTRA_SEARCH_POLICY = "search_policy"

        fun createIntent(
            context: Context,
            updateLocation: String,
            searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.USB_FIRST,
        ): Intent {
            return Intent(context, UpdateAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(EXTRA_UPDATE_LOCATION, updateLocation)
                putExtra(EXTRA_SEARCH_POLICY, searchPolicy.name)
            }
        }
    }
}
