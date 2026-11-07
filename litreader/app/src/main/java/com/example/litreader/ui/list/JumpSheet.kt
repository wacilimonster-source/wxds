package com.example.litreader.ui.list

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.litreader.R
import com.example.litreader.util.Haptics
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 跳页底部弹层：页码宫格直达 + 输入框兜底。
 * 宫格窗口为当前页 ±12，两端固定展示第 1 页与最后一页，断档处显示 …。
 * 输入校验：仅接受 1…N 的整数，错误显示在字段下且面板保持打开；
 * 总页数未知时禁用提交（显示「总页数获取中」）。
 */
class JumpSheet : BottomSheetDialogFragment() {

    private var total: Int = 1
    private var totalKnown: Boolean = true
    private var current: Int = 1
    private var onJump: ((Int) -> Unit)? = null

    private lateinit var input: EditText
    private lateinit var inputError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        total = arguments?.getInt(ARG_TOTAL)?.coerceAtLeast(1) ?: 1
        totalKnown = arguments?.getBoolean(ARG_KNOWN) ?: true
        current = arguments?.getInt(ARG_CURRENT)?.coerceIn(1, total) ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val colorSubtle = androidx.core.content.ContextCompat.getColor(ctx, R.color.inkSecondary)
        val colorDanger = androidx.core.content.ContextCompat.getColor(ctx, R.color.danger)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        // 标题行：跳转到页 · 共 N 页 / 总页数获取中
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(ctx).apply {
            text = getString(R.string.jump_sheet_title)
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.inkPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val totalLabel = TextView(ctx).apply {
            text = if (totalKnown) getString(R.string.jump_total, total)
            else getString(R.string.jump_unknown_total)
            textSize = 12f
            setTextColor(colorSubtle)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(totalLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (totalKnown) {
            // 页码宫格（6 列）
            val pages = windowPages(current, total)
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            var row: LinearLayout? = null
            pages.forEachIndexed { idx, p ->
                if (idx % 6 == 0) {
                    row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (idx == 0) 0 else dp(6)
                    })
                }
                val cell = TextView(ctx).apply {
                    text = p.label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    if (p.page in 1..total) {
                        background = androidx.core.content.ContextCompat.getDrawable(
                            ctx, if (p.page == current) R.drawable.bg_chip_on else R.drawable.bg_chip_off
                        )
                        setTextColor(
                            androidx.core.content.ContextCompat.getColor(
                                ctx, if (p.page == current) R.color.accentDeep else R.color.inkSecondary
                            )
                        )
                        setOnClickListener {
                            Haptics.tick(this)
                            onJump?.invoke(p.page)
                            dismiss()
                        }
                    } else {
                        setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.ink3))
                        background = null
                        isClickable = false
                    }
                }
                val lp = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
                row?.addView(cell, lp)
            }
            val scroll = ScrollView(ctx).apply { addView(grid) }
            root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }.also { it.height = dp(216) })
        }

        // 输入兜底：输入页码 + 跳转（总页数未知时禁用提交）
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(
                if (totalKnown) R.string.jump_input_hint else R.string.jump_input_unknown
            )
            textSize = 13f
            setSingleLine(true)
            isEnabled = totalKnown
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_search_field_off)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val go = TextView(ctx).apply {
            text = getString(R.string.jump_go)
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.onPrimary))
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_btn_accent)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(22), dp(9), dp(22), dp(9))
            isEnabled = totalKnown
            alpha = if (totalKnown) 1f else 0.45f
            setOnClickListener {
                if (!totalKnown) return@setOnClickListener
                // 整数校验：拒绝 0/负数/越界；错误显示在字段下，面板保持打开
                val text = input.text.toString().trim()
                val n = text.toIntOrNull()
                when {
                    text.isEmpty() || n == null -> showError(getString(R.string.jump_error_int))
                    n < 1 || n > total -> showError(getString(R.string.jump_error_range, total))
                    else -> {
                        Haptics.tick(this)
                        onJump?.invoke(n)
                        dismiss()
                    }
                }
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        root.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        inputError = TextView(ctx).apply {
            textSize = 12f
            setTextColor(colorDanger)
            visibility = View.GONE
        }
        root.addView(inputError, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return root
    }

    private fun showError(msg: String) {
        inputError.text = msg
        inputError.visibility = View.VISIBLE
    }

    /** 窗口页序列：1 … (current-12..current+12) … N，重复的端点合并，负数页被过滤。 */
    private data class Cell(val page: Int, val label: String)

    private fun windowPages(cur: Int, total: Int): List<Cell> {
        val WINDOW = 12
        val seq = sortedSetOf(1, total)
        (cur - WINDOW..cur + WINDOW).filter { it in 1..total }.forEach { seq.add(it) }
        val cells = ArrayList<Cell>()
        var prev = 0
        for (p in seq) {
            if (p - prev > 1) cells.add(Cell(0, "…"))
            cells.add(Cell(p, p.toString()))
            prev = p
        }
        return cells
    }

    companion object {
        private const val ARG_TOTAL = "total"
        private const val ARG_CURRENT = "current"
        private const val ARG_KNOWN = "known"

        /** totalKnown=false：总页数未取得，宫格不展示、提交禁用。 */
        fun newInstance(total: Int, current: Int, totalKnown: Boolean = true, onJump: (Int) -> Unit): JumpSheet =
            JumpSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TOTAL, total)
                    putInt(ARG_CURRENT, current)
                    putBoolean(ARG_KNOWN, totalKnown)
                }
                this.onJump = onJump
            }
    }
}
