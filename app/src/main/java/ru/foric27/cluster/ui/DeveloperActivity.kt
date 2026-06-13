package ru.foric27.cluster.ui
import ru.foric27.cluster.BuildConfig
import ru.foric27.cluster.R
import ru.foric27.cluster.config.*
import ru.foric27.cluster.network.*
import ru.foric27.cluster.service.*
import ru.foric27.cluster.ui.theme.*
import ru.foric27.cluster.util.*

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timber.log.Timber

/**
 * Скрытый экран разработчика для runtime-настроек.
 *
 * Все изменения применяются сразу после завершения редактирования поля.
 */
class DeveloperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeConfig.init(applicationContext)

        setContent {
            ClusterTheme {
                DeveloperScreen(
                    onBack = ::finish,
                )
            }
        }
    }

    /**
     * Сбрасывает все runtime-настройки к значениям по умолчанию.
     *
     * @return сообщение об ошибке или null при успехе
     */
    internal fun performResetAll(): String? {
        val result = RuntimeConfig.resetToDefaults(this)
        if (!result.ok) return result.message
        RuntimeConfig.init(applicationContext)
        RootNetUtil.clearCaches()
        UdpStreamService.restartServiceCompat(this)
        UdpStreamService.refreshFtpCompat(this)
        return null
    }

    /**
     * Экспортирует logcat в файл и возвращает результат.
     *
     * @return результат экспорта с файлом и URI
     */
    internal fun performExportLogcat(): Result<LogcatExporter.Result> = runCatching { LogcatExporter.export(this) }

    /**
     * Очищает системный logcat и локальные файлы логов.
     *
     * @return результат очистки
     */
    internal fun performClearLogcat(): Result<LogcatExporter.ClearResult> = runCatching { LogcatExporter.clear(this) }

    /**
     * Открывает системное меню share для переданного экспортированного лога.
     *
     * @param exported результат экспорта logcat
     */
    internal fun shareLogcat(exported: LogcatExporter.Result) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.developer_logcat_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.developer_logcat_share_title)))
    }

    /**
     * Удаляет все постоянные файлы логов из cacheDir/logs.
     *
     * @return количество удалённых файлов
     */
    internal fun clearPersistentLogFiles(): Int {
        val logDir = java.io.File(cacheDir, "logs")
        if (!logDir.exists()) return 0
        return logDir.listFiles { file -> file.isFile }?.count { it.delete() } ?: 0
    }
}

@Composable
private fun DeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as DeveloperActivity
    var statusText by remember { mutableStateOf("") }
    var updateChannel by remember { mutableStateOf(AppSettings.getSelectedUpdateChannel(context)) }
    var collapseOnLaunch by remember { mutableStateOf(AppSettings.isCollapseOnLaunchEnabled(context)) }
    var fieldValues by remember { mutableStateOf(loadAllFieldValues()) }
    var exportEnabled by remember { mutableStateOf(true) }
    var clearEnabled by remember { mutableStateOf(true) }

    val cardTopMargin = dimensionResource(R.dimen.developer_card_top_margin)
    val cardFirstTopMargin = dimensionResource(R.dimen.developer_card_first_top_margin)
    val titleSize = dimensionResource(R.dimen.developer_title_size)
    val cardTextSize = dimensionResource(R.dimen.developer_card_text_size)
    val captionSize = dimensionResource(R.dimen.screen_caption_size)
    val contentPadding = dimensionResource(R.dimen.screen_content_padding)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(dimensionResource(R.dimen.developer_card_padding)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< ", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.developer_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = dimensionResource(R.dimen.developer_title_size).value.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val sections = remember(fieldValues) {
            val map = linkedMapOf<Int, MutableList<RuntimeConfig.FieldSpec>>()
            RuntimeConfig.getFieldSpecs().forEach { spec ->
                map.getOrPut(spec.sectionResId) { mutableListOf() }.add(spec)
            }
            map.entries.toList()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dimensionResource(R.dimen.developer_card_padding)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.developer_card_top_margin)),
        ) {
            item {
                Text(
                    text = stringResource(R.string.app_version_fmt, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = dimensionResource(R.dimen.screen_body_size).value.sp,
                )
            }

            item {
                UpdateChannelCard(
                    selected = updateChannel,
                    onSelect = { channel ->
                        if (AppSettings.saveSelectedUpdateChannel(context, channel)) {
                            updateChannel = channel
                            statusText = context.getString(
                                R.string.developer_update_channel_saved_fmt,
                                channelLabel(context, channel),
                            )
                        } else {
                            statusText = context.getString(R.string.developer_update_channel_save_failed)
                        }
                    },
                )
            }

            item {
                CollapseOnLaunchCard(
                    checked = collapseOnLaunch,
                    onCheckedChange = { checked ->
                        if (AppSettings.saveCollapseOnLaunchEnabled(context, checked)) {
                            collapseOnLaunch = checked
                            statusText = context.getString(
                                R.string.developer_collapse_on_launch_saved_fmt,
                                if (checked) context.getString(R.string.developer_collapse_on_launch_state_enabled)
                                else context.getString(R.string.developer_collapse_on_launch_state_disabled),
                            )
                        } else {
                            statusText = context.getString(R.string.developer_collapse_on_launch_save_failed)
                        }
                    },
                )
            }

            items(sections, key = { it.key }) { entry ->
                RuntimeSectionCard(
                    sectionTitle = context.getString(entry.key),
                    fields = entry.value,
                    fieldValues = fieldValues,
                    onFieldChanged = { spec, rawValue ->
                        val result = RuntimeConfig.saveField(context, spec, rawValue)
                        if (result.ok) {
                            RuntimeConfig.init(context)
                            fieldValues = loadAllFieldValues()
                            handleAppliedChange(context, spec) { statusText = it }
                        } else {
                            statusText = result.message ?: ""
                        }
                        result.ok
                    },
                    onBooleanChanged = { spec, checked ->
                        val result = RuntimeConfig.saveField(context, spec, checked.toString())
                        if (result.ok) {
                            RuntimeConfig.init(context)
                            fieldValues = loadAllFieldValues()
                            handleAppliedChange(context, spec) { statusText = it }
                        } else {
                            statusText = result.message ?: ""
                        }
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.screen_block_spacing)),
                ) {
                    Button(
                        onClick = {
                            val error = activity.performResetAll()
                            if (error == null) {
                                fieldValues = loadAllFieldValues()
                                statusText = context.getString(R.string.developer_reset_success)
                            } else {
                                statusText = error
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.developer_reset_all), color = MaterialTheme.colorScheme.onSurface, fontSize = captionSize.value.sp)
                    }

                    Button(
                        onClick = {
                            exportEnabled = false
                            statusText = context.getString(R.string.developer_logcat_export_in_progress)
                            Thread {
                                val result = activity.performExportLogcat()
                                (context as ComponentActivity).runOnUiThread {
                                    exportEnabled = true
                                    result.onSuccess { exported ->
                                        statusText = context.getString(R.string.developer_logcat_export_ok_fmt, exported.file.name)
                                        activity.shareLogcat(exported)
                                    }.onFailure { error ->
                                        val msg = error.message ?: error.javaClass.simpleName
                                        statusText = context.getString(R.string.developer_logcat_export_error_fmt, msg)
                                        Toast.makeText(context, R.string.developer_logcat_export_error_short, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        },
                        enabled = exportEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.developer_export_logcat), color = MaterialTheme.colorScheme.onSurface, fontSize = captionSize.value.sp)
                    }

                    Button(
                        onClick = {
                            clearEnabled = false
                            statusText = context.getString(R.string.developer_logcat_clear_in_progress)
                            Thread {
                                val result = activity.performClearLogcat()
                                (context as ComponentActivity).runOnUiThread {
                                    clearEnabled = true
                                    result.onSuccess { cleared ->
                                        val memoryCleared = InMemoryLogBuffer.size()
                                        InMemoryLogBuffer.clear()
                                        val persistentCleared = activity.clearPersistentLogFiles()
                                        val totalCleared = cleared.deletedFiles + persistentCleared
                                        val msgRes = if (cleared.systemCleared) R.string.developer_logcat_clear_ok_fmt
                                            else R.string.developer_logcat_clear_partial_fmt
                                        statusText = context.getString(msgRes, totalCleared) +
                                            " (RAM: $memoryCleared, disk: ${cleared.deletedFiles + persistentCleared})"
                                    }.onFailure { error ->
                                        val msg = error.message ?: error.javaClass.simpleName
                                        statusText = context.getString(R.string.developer_logcat_clear_error_fmt, msg)
                                        Toast.makeText(context, R.string.developer_logcat_clear_error_short, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        },
                        enabled = clearEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.developer_clear_logcat), color = MaterialTheme.colorScheme.onSurface, fontSize = captionSize.value.sp)
                    }
                }
            }

            if (statusText.isNotEmpty()) {
                item {
                    Text(
                        text = statusText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = captionSize.value.sp,
                        modifier = Modifier.padding(bottom = contentPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateChannelCard(
    selected: AppSettings.UpdateChannel,
    onSelect: (AppSettings.UpdateChannel) -> Unit,
) {
    val cardPadding = dimensionResource(R.dimen.developer_card_padding)
    val cardTextSize = dimensionResource(R.dimen.developer_card_text_size)
    val bodySize = dimensionResource(R.dimen.screen_body_size)
    val modeSpacing = dimensionResource(R.dimen.screen_mode_spacing)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(cardPadding)) {
            Text(
                text = stringResource(R.string.developer_update_channel_title),
                color = TextPrimary,
                fontSize = cardTextSize.value.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(modeSpacing))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected == AppSettings.UpdateChannel.ROLLING,
                    onClick = { onSelect(AppSettings.UpdateChannel.ROLLING) },
                )
                Text(stringResource(R.string.app_update_channel_rolling), color = TextPrimary, fontSize = bodySize.value.sp)
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = selected == AppSettings.UpdateChannel.STABLE,
                    onClick = { onSelect(AppSettings.UpdateChannel.STABLE) },
                )
                Text(stringResource(R.string.app_update_channel_stable), color = TextPrimary, fontSize = bodySize.value.sp)
            }
        }
    }
}

@Composable
private fun CollapseOnLaunchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cardPadding = dimensionResource(R.dimen.developer_card_padding)
    val cardTextSize = dimensionResource(R.dimen.developer_card_text_size)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.developer_collapse_on_launch_title),
                color = TextPrimary,
                fontSize = cardTextSize.value.sp,
                modifier = Modifier.weight(1f),
            )
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun RuntimeSectionCard(
    sectionTitle: String,
    fields: List<RuntimeConfig.FieldSpec>,
    fieldValues: Map<String, String>,
    onFieldChanged: (RuntimeConfig.FieldSpec, String) -> Boolean,
    onBooleanChanged: (RuntimeConfig.FieldSpec, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val cardPadding = dimensionResource(R.dimen.developer_card_padding)
    val cardTextSize = dimensionResource(R.dimen.developer_card_text_size)
    val valueTopMargin = dimensionResource(R.dimen.developer_value_top_margin)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(cardPadding)) {
            Text(
                text = sectionTitle,
                color = TextPrimary,
                fontSize = cardTextSize.value.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(valueTopMargin))
            fields.forEach { spec ->
                val value = fieldValues[spec.key] ?: ""
                val title = fieldTitle(context, spec)
                if (spec.type == RuntimeConfig.ValueType.BOOLEAN) {
                    BooleanField(
                        title = title,
                        value = value,
                        onChanged = { onBooleanChanged(spec, it) },
                    )
                } else {
                    TextInputField(
                        title = title,
                        value = value,
                        keyboardType = when (spec.type) {
                            RuntimeConfig.ValueType.INT, RuntimeConfig.ValueType.LONG -> KeyboardType.Number
                            else -> KeyboardType.Text
                        },
                        onDone = { raw -> onFieldChanged(spec, raw) },
                    )
                }
                Spacer(Modifier.height(valueTopMargin))
            }
        }
    }
}

@Composable
private fun BooleanField(
    title: String,
    value: String,
    onChanged: (Boolean) -> Unit,
) {
    val valuePadding = dimensionResource(R.dimen.developer_value_padding)
    val valuePaddingTop = dimensionResource(R.dimen.developer_value_padding_top)
    val valueTextSize = dimensionResource(R.dimen.developer_value_text_size)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = valuePadding, vertical = valuePaddingTop),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = valueTextSize.value.sp,
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                checked = value.toBoolean(),
                onCheckedChange = onChanged,
            )
        }
    }
}

@Composable
private fun TextInputField(
    title: String,
    value: String,
    keyboardType: KeyboardType,
    onDone: (String) -> Boolean,
) {
    var text by remember(value) { mutableStateOf(value) }
    var isError by remember { mutableStateOf(false) }
    val valueTextSize = dimensionResource(R.dimen.developer_value_text_size)

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; isError = false },
        label = { Text(title, fontSize = valueTextSize.value.sp) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (text.trim() != value.trim()) {
                    if (!onDone(text)) isError = true
                }
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun loadAllFieldValues(): Map<String, String> {
    return RuntimeConfig.getFieldSpecs().associate { spec ->
        spec.key to RuntimeConfig.getFieldValue(spec)
    }
}

private fun fieldTitle(context: android.content.Context, spec: RuntimeConfig.FieldSpec): String =
    RuntimeConfig.getFieldTitle(context, spec)

private fun handleAppliedChange(
    context: android.content.Context,
    spec: RuntimeConfig.FieldSpec,
    setStatus: (String) -> Unit,
) {
    val title = RuntimeConfig.getFieldTitle(context, spec)
    val isLoggingVerbose = spec.key == RuntimeConfig.Keys.LOGGING_VERBOSE_ENABLED
    val isFtp = RuntimeConfig.isFtpOnlyField(spec)

    when {
        isLoggingVerbose -> {
            setStatus(context.getString(R.string.developer_applied_local_fmt, title))
        }
        isFtp -> {
            UdpStreamService.refreshFtpCompat(context)
            setStatus(context.getString(R.string.developer_applied_ftp_fmt, title))
        }
        else -> {
            RootNetUtil.clearCaches()
            UdpStreamService.restartServiceCompat(context)
            setStatus(context.getString(R.string.developer_applied_stream_fmt, title))
        }
    }
}

private fun channelLabel(context: android.content.Context, channel: AppSettings.UpdateChannel): String {
    return when (channel) {
        AppSettings.UpdateChannel.ROLLING -> context.getString(R.string.app_update_channel_rolling)
        AppSettings.UpdateChannel.STABLE -> context.getString(R.string.app_update_channel_stable)
    }
}
