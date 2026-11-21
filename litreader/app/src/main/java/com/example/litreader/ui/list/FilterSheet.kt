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
 * 阅读状态筛选面板（重设计）：卡片式弹层 + 2×2 选项胶囊 + 底部双按钮。
 * 草稿与应用分离——面板内只改 draft，取消/遮罩关闭丢弃草稿；
 * 「应用筛选」经回调写回 VM 并回第一页；「重置」只重置面板内阅读状态。
 */
class FilterSheet : BottomSheetDialogFragment() {

    private data class Option(val value: Int, val label: String, val view: TextView)

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

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_sheet_top)
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }

        // 顶部拖拽条：细圆角横条，提示可下拉关闭
        val grabber = View(ctx).apply {
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_filter_row)
        }
        root.addView(grabber, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        root.addView(TextView(ctx).apply {
            text = getString(R.string.filter_sheet_title)
            textSize = 17f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.inkPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        // 2×2 选项胶囊：描边底 → 选中 soft 填充（isSelected 驱动），✓ 前缀不只靠颜色
        val candidates = listOf(
            -1 to getString(R.string.filter_all),
            0 to getString(R.string.filter_unread),
            1 to getString(R.string.read_opened),
            2 to getString(R.string.filter_finished)
        )
        options = candidates.chunked(2).flatMap { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val created = pair.map { (value, label) ->
                val tv = TextView(ctx, null, 0, R.style.LitChip).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        Haptics.tick(this)
                        draft = value
                        render()
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(10)
                })
                Option(value, label, tv)
            }
            root.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            created
        }

        // 底部操作：重置（仅面板内阅读状态）+ 应用筛选
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val reset = TextView(ctx).apply {
            text = getString(R.string.filter_reset)
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.inkSecondary))
            gravity = Gravity.CENTER
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
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.onPrimary))
            gravity = Gravity.CENTER
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_btn_accent)
            setOnClickListener {
                Haptics.tick(this)
                onApply?.invoke(draft)
                dismiss()
            }
        }
        actions.addView(reset, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        actions.addView(apply, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        render()
        return root
    }

    /** 选中行 ✓ + 填充底，未选中为描边行——状态不依赖单一颜色。 */
    private fun render() {
        val mark = getString(R.string.filter_selected_mark)
        options.forEach { o ->
            val selected = o.value == draft
            o.view.text = if (selected) "$mark ${o.label}" else o.label
            o.view.isSelected = selected
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
