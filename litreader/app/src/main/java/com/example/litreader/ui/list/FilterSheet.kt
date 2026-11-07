package com.example.litreader.ui.list

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.litreader.R
import com.example.litreader.util.Haptics
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 阅读状态筛选面板：草稿与应用分离——
 * 面板内只改 draft，取消/遮罩关闭丢弃草稿；「应用筛选」经回调写回 VM 并回第一页。
 * 「重置」只重置面板内阅读状态（不重置标签）。
 */
class FilterSheet : BottomSheetDialogFragment() {

    private data class Option(val value: Int, val label: String, val row: TextView)

    private var applied: Int = -1
    private var draft: Int = -1
    private var onApply: ((Int) -> Unit)? = null
    private lateinit var options: List<Option>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applied = arguments?.getInt(ARG_APPLIED) ?: -1
        draft = applied
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val colorInk = androidx.core.content.ContextCompat.getColor(ctx, R.color.inkPrimary)
        val colorOn = androidx.core.content.ContextCompat.getColor(ctx, R.color.onPrimary)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        root.addView(TextView(ctx).apply {
            text = getString(R.string.filter_sheet_title)
            textSize = 15f
            setTextColor(colorInk)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        // 候选行：选中项带 ✓ 与填充底（非仅颜色区分），触摸高 48dp
        val candidates = listOf(
            -1 to getString(R.string.filter_all),
            0 to getString(R.string.filter_unread),
            1 to getString(R.string.read_opened),
            2 to getString(R.string.filter_finished)
        )
        options = candidates.map { (value, label) ->
            val row = TextView(ctx).apply {
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setOnClickListener {
                    Haptics.tick(this)
                    draft = value
                    render()
                }
            }
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
            Option(value, label, row)
        }
        render()

        // 底部操作：重置（仅面板内阅读状态）+ 应用筛选
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val reset = TextView(ctx).apply {
            text = getString(R.string.filter_reset)
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_filter_row)
            setOnClickListener {
                Haptics.tick(this)
                draft = -1
                render()
            }
        }
        val apply = TextView(ctx).apply {
            text = getString(R.string.filter_apply)
            textSize = 14f
            setTextColor(colorOn)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_btn_accent)
            setOnClickListener {
                Haptics.tick(this)
                onApply?.invoke(draft)
                dismiss()
            }
        }
        actions.addView(reset, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        actions.addView(apply, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        return root
    }

    /** 选中行 ✓ + 填充底，未选中为描边行——状态不依赖单一颜色。 */
    private fun render() {
        val mark = getString(R.string.filter_selected_mark)
        options.forEach { o ->
            val selected = o.value == draft
            o.row.text = if (selected) "$mark ${o.label}" else o.label
            o.row.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(), if (selected) R.color.accent else R.color.inkSecondary
                )
            )
            o.row.background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), if (selected) R.drawable.bg_chip_on else R.drawable.bg_filter_row
            )
        }
    }

    companion object {
        private const val ARG_APPLIED = "applied"

        fun newInstance(applied: Int, onApply: (Int) -> Unit): FilterSheet =
            FilterSheet().apply {
                arguments = Bundle().apply { putInt(ARG_APPLIED, applied) }
                this.onApply = onApply
            }
    }
}
