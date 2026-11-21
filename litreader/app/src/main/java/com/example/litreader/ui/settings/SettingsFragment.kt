package com.example.litreader.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.litreader.R
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.databinding.FragmentSettingsBinding
import com.example.litreader.ui.list.KeywordSheet
import com.example.litreader.util.KeywordBlock
import com.example.litreader.util.UiTips
import com.example.litreader.util.UpdateManager
import java.io.File

/**
 * 设置页（底部导航第 4 个页签）：承接原列表页「更多」菜单的设置类入口——
 * 关键词屏蔽（按分区管理）、检查更新、崩溃日志、版本展示。
 * 刷新不在此处：列表页只走下拉刷新。
 */
class SettingsFragment : Fragment() {
    private lateinit var bind: FragmentSettingsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bind = FragmentSettingsBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind.tvVersion.text = getString(
            R.string.current_version, com.example.litreader.util.Constants.VERSION_NAME
        )
        bind.rowKeywords.setOnClickListener { pickSection() }
        bind.rowUpdate.setOnClickListener {
            UpdateManager.checkAndPromptUpdate(requireActivity(), lifecycleScope, silent = false)
        }
        bind.rowCrash.setOnClickListener { showCrashLogs() }
        renderKeywordsSub()
    }

    override fun onResume() {
        super.onResume()
        // 关键词可能在弹层里改过；从其他页签切回时刷新计数
        renderKeywordsSub()
    }

    /** 屏蔽词总数 = 各分区之和（KeywordBlock 按分区各存一份）。 */
    private fun blockedTotal(): Int = SourceRegistry.sources.sumOf { src ->
        KeywordBlock.list(
            requireContext().getSharedPreferences("catalog", android.content.Context.MODE_PRIVATE),
            src.id
        ).size
    }

    private fun renderKeywordsSub() {
        val n = blockedTotal()
        bind.tvKeywordsSub.text = getString(
            if (n > 0) R.string.settings_keywords_sub_n else R.string.settings_keywords_sub_none, n
        )
    }

    /** 关键词按分区各存一份：先选分区再进对应弹层；关闭后刷新计数。 */
    private fun pickSection() {
        val sources = SourceRegistry.sources
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_pick_section)
            .setItems(sources.map { it.name }.toTypedArray()) { _, which ->
                KeywordSheet.newInstance(sources[which].id) { renderKeywordsSub() }
                    .show(parentFragmentManager, "keywords")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 本地崩溃日志列表（App 未捕获异常落盘，最多 5 份）。 */
    private fun showCrashLogs() {
        val files = requireContext().getExternalFilesDir("crash")
            ?.listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (files.isEmpty()) {
            UiTips.snack(bind.root, getString(R.string.crash_logs_empty))
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.crash_logs)
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> showCrashContent(files[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCrashContent(file: File) {
        val content = runCatching { file.readText() }.getOrDefault("读取失败")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(requireContext())
        val tv = TextView(requireContext()).apply {
            text = content
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(tv)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share_image) { _, _ ->
                startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                        },
                        getString(R.string.share_image)
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
