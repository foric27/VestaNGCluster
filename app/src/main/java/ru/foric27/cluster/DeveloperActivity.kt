package ru.foric27.cluster

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ru.foric27.cluster.databinding.ActivityDeveloperBinding

/**
 * Скрытый экран разработчика для runtime-настроек.
 *
 * Все изменения применяются сразу после завершения редактирования поля. Экран
 * не хранит собственную копию конфигурации, а работает поверх [RuntimeConfig].
 */
class DeveloperActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeveloperBinding
    private val runtimeInputs = LinkedHashMap<RuntimeConfig.FieldSpec, TextInputEditText>()
    private val runtimeCheckboxes = LinkedHashMap<RuntimeConfig.FieldSpec, MaterialCheckBox>()
    private var suppressCheckboxEvents = false

    private enum class ApplyTarget {
        STREAM,
        FTP,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeConfig.init(applicationContext)
        binding = ActivityDeveloperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.resetAllBtn.setOnClickListener { resetAllSettings() }

        renderVersionInfo()
        buildDynamicSettingsEditor()
        renderAllValues()
    }

    private fun renderVersionInfo() {
        binding.versionInfoText.text = getString(R.string.app_version_fmt, BuildConfig.VERSION_NAME)
    }

    private fun renderAllValues() {
        suppressCheckboxEvents = true
        try {
            RuntimeConfig.getFieldSpecs().forEach { spec ->
                when (spec.type) {
                    RuntimeConfig.ValueType.BOOLEAN -> runtimeCheckboxes[spec]?.isChecked = RuntimeConfig.getFieldValue(spec).toBoolean()
                    else -> runtimeInputs[spec]?.setText(RuntimeConfig.getFieldValue(spec))
                }
            }
        } finally {
            suppressCheckboxEvents = false
        }
    }

    /**
     * Строит экран настроек из описаний полей RuntimeConfig, чтобы UI и набор
     * доступных параметров не расходились между собой.
     */
    private fun buildDynamicSettingsEditor() {
        binding.settingsContainer.removeAllViews()
        runtimeInputs.clear()
        runtimeCheckboxes.clear()

        val sections = linkedMapOf<Int, MutableList<RuntimeConfig.FieldSpec>>()
        RuntimeConfig.getFieldSpecs().forEach { spec ->
            sections.getOrPut(spec.sectionResId) { mutableListOf() }.add(spec)
        }

        val sectionList = sections.entries.toList()
        sectionList.forEachIndexed { index, entry ->
            binding.settingsContainer.addView(
                createSectionCard(entry.key, entry.value),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = resources.getDimensionPixelSize(R.dimen.developer_card_top_margin)
                },
            )
        }
    }

    private fun createSectionCard(sectionTitleResId: Int, fields: List<RuntimeConfig.FieldSpec>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = resources.getDimension(R.dimen.developer_card_radius)
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_dark_2))
            strokeColor = getColor(R.color.surface_line)
            strokeWidth = resources.getDimensionPixelSize(R.dimen.developer_card_stroke)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val cardPad = resources.getDimensionPixelSize(R.dimen.developer_card_padding)
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }

        val titleView = TextView(this).apply {
            text = getString(sectionTitleResId)
            setTextColor(getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.developer_card_text_size)
            setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(titleView)

        fields.forEachIndexed { index, spec ->
            val fieldView = createFieldView(spec)
            container.addView(
                fieldView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = if (index == 0) {
                        resources.getDimensionPixelSize(R.dimen.developer_card_first_top_margin)
                    } else {
                        resources.getDimensionPixelSize(R.dimen.developer_value_top_margin)
                    }
                },
            )
        }

        card.addView(container)
        return card
    }

    private fun createFieldView(spec: RuntimeConfig.FieldSpec): View {
        return if (spec.type == RuntimeConfig.ValueType.BOOLEAN) {
            createCheckboxField(spec)
        } else {
            createTextInputField(spec)
        }
    }

    private fun createTextInputField(spec: RuntimeConfig.FieldSpec): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            hint = RuntimeConfig.getFieldTitle(this@DeveloperActivity, spec)
            helperText = helperTextFor(spec)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(getColor(R.color.surface_dark_3))
        }

        val input = TextInputEditText(this).apply {
            inputType = inputTypeFor(spec.type)
            maxLines = 1
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        runtimeInputs[spec] = input
        bindImmediateApply(layout, input, spec)
        return layout
    }

    private fun createCheckboxField(spec: RuntimeConfig.FieldSpec): MaterialCardView {
        val rowCard = MaterialCardView(this).apply {
            radius = resources.getDimension(R.dimen.developer_value_radius)
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_dark_3))
            strokeColor = getColor(R.color.surface_line)
            strokeWidth = resources.getDimensionPixelSize(R.dimen.developer_value_stroke)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = resources.getDimensionPixelSize(R.dimen.developer_value_padding)
            val padTop = resources.getDimensionPixelSize(R.dimen.developer_value_padding_top)
            setPadding(pad, padTop, pad, pad)
        }

        val titleView = TextView(this).apply {
            text = RuntimeConfig.getFieldTitle(this@DeveloperActivity, spec)
            setTextColor(getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.developer_value_text_size)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val checkbox = MaterialCheckBox(this).apply {
            isChecked = RuntimeConfig.getFieldValue(spec).toBoolean()
            setUseMaterialThemeColors(true)
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (suppressCheckboxEvents) return@setOnCheckedChangeListener
                applyBooleanChange(spec, isChecked)
            }
        }

        row.addView(titleView)
        row.addView(checkbox)
        rowCard.addView(row)
        runtimeCheckboxes[spec] = checkbox
        return rowCard
    }

    /**
     * Применяет изменения сразу по потере фокуса или по действию клавиатуры.
     */
    private fun bindImmediateApply(
        layout: TextInputLayout,
        input: TextInputEditText,
        spec: RuntimeConfig.FieldSpec,
    ) {
        var appliedValue = RuntimeConfig.getFieldValue(spec)

        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = input.text?.toString().orEmpty()
                if (raw.trim() != appliedValue.trim()) {
                    applySpecChange(spec, raw, layout)?.let { appliedValue = it }
                }
            }
        }

        input.setOnEditorActionListener { _, actionId, event ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (!isSubmit) {
                return@setOnEditorActionListener false
            }
            val raw = input.text?.toString().orEmpty()
            applySpecChange(spec, raw, layout)?.let { appliedValue = it }
            true
        }
    }

    private fun applyBooleanChange(spec: RuntimeConfig.FieldSpec, checked: Boolean) {
        val result = RuntimeConfig.saveField(this, spec, checked.toString())
        if (!result.ok) {
            binding.developerStatusText.text = result.message
            suppressCheckboxEvents = true
            try {
                runtimeCheckboxes[spec]?.isChecked = RuntimeConfig.getFieldValue(spec).toBoolean()
            } finally {
                suppressCheckboxEvents = false
            }
            return
        }

        RuntimeConfig.init(applicationContext)
        handleAppliedChange(spec)
    }

    private fun applySpecChange(
        spec: RuntimeConfig.FieldSpec,
        rawValue: String,
        layout: TextInputLayout,
    ): String? {
        val result = RuntimeConfig.saveField(this, spec, rawValue)
        if (!result.ok) {
            layout.error = result.message
            binding.developerStatusText.text = result.message
            runtimeInputs[spec]?.requestFocus()
            return null
        }

        layout.error = null
        RuntimeConfig.init(applicationContext)
        val appliedValue = RuntimeConfig.getFieldValue(spec)
        runtimeInputs[spec]?.setText(appliedValue)

        handleAppliedChange(spec)
        return appliedValue
    }

    /**
     * Перезапускает только ту часть приложения, которая зависит от изменённого
     * поля, чтобы не дёргать весь пайплайн без необходимости.
     */
    private fun handleAppliedChange(spec: RuntimeConfig.FieldSpec) {
        val title = RuntimeConfig.getFieldTitle(this, spec)
        when (applyTargetFor(spec)) {
            ApplyTarget.FTP -> {
                UdpStreamService.refreshFtpCompat(this)
                binding.developerStatusText.text = getString(R.string.developer_applied_ftp_fmt, title)
            }

            ApplyTarget.STREAM -> {
                RootNetUtil.clearCaches()
                UdpStreamService.restartServiceCompat(this)
                binding.developerStatusText.text = getString(R.string.developer_applied_stream_fmt, title)
            }
        }
    }

    private fun applyTargetFor(spec: RuntimeConfig.FieldSpec): ApplyTarget {
        return if (RuntimeConfig.isFtpOnlyField(spec)) {
            ApplyTarget.FTP
        } else {
            ApplyTarget.STREAM
        }
    }

    private fun helperTextFor(spec: RuntimeConfig.FieldSpec): String? {
        return if (spec.type == RuntimeConfig.ValueType.BOOLEAN) {
            getString(R.string.developer_bool_hint)
        } else {
            null
        }
    }

    private fun inputTypeFor(type: RuntimeConfig.ValueType): Int {
        return when (type) {
            RuntimeConfig.ValueType.INT -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            RuntimeConfig.ValueType.LONG -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            RuntimeConfig.ValueType.BOOLEAN -> InputType.TYPE_CLASS_TEXT
            RuntimeConfig.ValueType.STRING -> InputType.TYPE_CLASS_TEXT
        }
    }

    private fun resetAllSettings() {
        val resetResult = RuntimeConfig.resetToDefaults(this)
        if (!resetResult.ok) {
            binding.developerStatusText.text = resetResult.message
            return
        }

        RuntimeConfig.init(applicationContext)
        RootNetUtil.clearCaches()
        renderAllValues()
        restartRuntimeDrivenServices()
        binding.developerStatusText.text = getString(R.string.developer_reset_success)
    }

    /**
     * Используется после полного сброса настроек, когда безопаснее обновить и
     * стрим, и FTP-сценарий одним штатным путём.
     */
    private fun restartRuntimeDrivenServices() {
        UdpStreamService.restartServiceCompat(this)
        UdpStreamService.refreshFtpCompat(this)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
