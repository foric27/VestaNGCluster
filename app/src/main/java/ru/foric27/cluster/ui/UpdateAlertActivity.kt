package ru.foric27.cluster.ui
import ru.foric27.cluster.R
import ru.foric27.cluster.update.*
import ru.foric27.cluster.ui.theme.ClusterTheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import timber.log.Timber

/**
 * Прозрачная Activity для показа диалога обнаружения нового обновления.
 * Запускается из сервиса/координатора с флагом FLAG_ACTIVITY_NEW_TASK.
 */
internal class UpdateAlertActivity : ComponentActivity() {

    private var finishReceiver: BroadcastReceiver? = null
    private val showDialog = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerFinishReceiver()

        val updateLocation = intent.getStringExtra(EXTRA_UPDATE_LOCATION).orEmpty()
        val searchPolicyName = intent.getStringExtra(EXTRA_SEARCH_POLICY)
            ?: UpdateFileLocator.SearchPolicy.USB_ONLY.name

        Timber.tag(TAG).i("UpdateAlertActivity создан: location=$updateLocation")

        setContent {
            ClusterTheme {
                if (showDialog.value) {
                    UpdateAlertDialog(
                        updateLocation = updateLocation,
                        onUpdate = {
                            showDialog.value = false
                            performUpdate(searchPolicyName)
                            finish()
                        },
                        onCancel = {
                            showDialog.value = false
                            finish()
                        },
                    )
                }
            }
        }
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

@Composable
private fun UpdateAlertDialog(
    updateLocation: String,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Обновление найдено") },
        text = { Text("Обнаружено обновление: $updateLocation") },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text("Обновить", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Отмена", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
