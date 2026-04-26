package ru.foric27.cluster

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ru.foric27.cluster.databinding.ActivityMainBinding
import java.util.ArrayDeque

internal class MainNoticeLog(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    private data class NoticeEntry(
        val text: String,
        val isError: Boolean,
    )

    private val entries = ArrayDeque<NoticeEntry>()

    val warningListener = object : AppWarningCenter.WarningListener {
        override fun onWarningPublished(message: String) {
            activity.runOnUiThread {
                show(message, isError = true)
            }
        }
    }

    fun bindClearAction() {
        binding.noticeClearBtn.setOnClickListener {
            clear()
        }
    }

    fun consumePendingWarnings() {
        AppWarningCenter.consumeAll().forEach { show(it, isError = true) }
    }

    fun show(message: String, isError: Boolean) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return

        val existing = entries.firstOrNull { it.text == normalized }
        if (existing != null && (!isError || existing.isError)) {
            render()
            return
        }

        entries.removeAll { it.text == normalized }
        entries.addFirst(NoticeEntry(normalized, isError))
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
        render()
    }

    fun clear() {
        entries.clear()
        AppWarningCenter.clear()
        render()
    }

    fun removeMatching(predicate: (String) -> Boolean) {
        entries.removeAll { predicate(it.text) }
        AppWarningCenter.removeMatching(predicate)
        render()
    }

    fun render() {
        if (entries.isEmpty()) {
            binding.noticePanel.visibility = View.GONE
            binding.noticeText.text = ""
            return
        }

        val hasErrors = entries.any { it.isError }
        binding.noticePanel.visibility = View.VISIBLE
        binding.noticeTitle.text = if (hasErrors) {
            activity.getString(R.string.inline_notice_title_warning)
        } else {
            activity.getString(R.string.inline_notice_title_info)
        }
        binding.noticeText.text = entries.joinToString("\n\n") {
            activity.getString(R.string.inline_notice_item_fmt, it.text)
        }
    }

    private companion object {
        private const val MAX_ENTRIES = 120
    }
}
