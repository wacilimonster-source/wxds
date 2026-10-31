package com.example.litreader.ui.list

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.litreader.R
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.util.Haptics
import com.example.litreader.util.KeywordBlock
import com.example.litreader.util.UiTips
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 关键词屏蔽底部弹层：按分区（文学/贴图）管理屏蔽词，标题命中即不在列表展示。
 * 词条轻点删除，输入框+添加新增；每次增删回调 onChanged 让列表即时重过滤。
 */
class KeywordSheet : BottomSheetDialogFragment() {

    private var sourceId: String = ""
    private var onChanged: (() -> Unit)? = null

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var chipRow: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = arguments?.getString(ARG_SOURCE) ?: SourceRegistry.first().id
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        prefs = ctx.getSharedPreferences("catalog", Context.MODE_PRIVATE)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        // 标题行：关键词屏蔽 · 分区名
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(ctx).apply {
            text = getString(R.string.keyword_block)
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.inkPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val section = TextView(ctx).apply {
            text = (SourceRegistry.get(sourceId) ?: SourceRegistry.first()).name
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.inkSecondary))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(section, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 说明：命中规则
        root.addView(TextView(ctx).apply {
            text = getString(R.string.keyword_block_hint)
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.ink3))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // 屏蔽词词条（横向滚动，轻点删除）
        chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        emptyHint = TextView(ctx).apply {
            text = getString(R.string.keyword_none)
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.ink3))
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(emptyHint)
                addView(chipRow)
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        // 输入行：关键词 + 添加
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(ctx).apply {
            hint = getString(R.string.keyword_input_hint)
            textSize = 13f
            setSingleLine(true)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_search_field_off)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val add = TextView(ctx).apply {
            text = getString(R.string.keyword_add)
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.onPrimary))
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_btn_accent)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(9), dp(22), dp(9))
            setOnClickListener { addKeyword() }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        root.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        renderChips()
        return root
    }

    private fun current(): List<String> = KeywordBlock.list(prefs, sourceId)

    private fun renderChips() {
        val ctx = context ?: return
        val kws = current()
        chipRow.removeAllViews()
        emptyHint.visibility = if (kws.isEmpty()) View.VISIBLE else View.GONE
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        kws.forEach { kw ->
            val tv = TextView(ctx, null, 0, R.style.LitChip).apply {
                text = getString(R.string.keyword_chip, kw)
                setOnClickListener {
                    Haptics.key(this)
                    val next = current() - kw
                    KeywordBlock.save(prefs, sourceId, next)
                    renderChips()
                    onChanged?.invoke()
                }
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            chipRow.addView(tv)
        }
    }

    private fun addKeyword() {
        val kw = input.text.toString().trim()
        val ctx = context ?: return
        when {
            kw.isEmpty() -> UiTips.snack(requireView(), getString(R.string.keyword_empty))
            kw in current() -> UiTips.snack(requireView(), getString(R.string.keyword_exists))
            else -> {
                Haptics.tick(input)
                KeywordBlock.save(prefs, sourceId, current() + kw)
                input.setText("")
                renderChips()
                onChanged?.invoke()
            }
        }
    }

    companion object {
        private const val ARG_SOURCE = "sourceId"

        fun newInstance(sourceId: String, onChanged: () -> Unit): KeywordSheet =
            KeywordSheet().apply {
                arguments = Bundle().apply { putString(ARG_SOURCE, sourceId) }
                this.onChanged = onChanged
            }
    }
}
