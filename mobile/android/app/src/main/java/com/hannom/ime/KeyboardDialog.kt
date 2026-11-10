package com.hannom.ime

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hộp thoại nổi trên bàn phím (gắn vào cửa sổ bàn phím, như bảng "Change keyboard" của Gboard): thẻ bo tròn,
 * tiêu đề giữa, các dòng (dòng đang chọn có ✓ và nền viên màu nhấn nhạt), nút hành động ở góc dưới phải.
 */
object KeyboardDialog {
    class Row(val title: CharSequence, val subtitle: CharSequence? = null, val checked: Boolean = false, val onClick: () -> Unit)

    fun show(anchor: View, theme: KeyboardTheme, title: String?, rows: List<Row>, action: Pair<String, () -> Unit>? = null) {
        val token = anchor.windowToken ?: return
        val context = anchor.context
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dpi(20)
            setPadding(pad, pad, pad, context.dpi(12))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(28f)
                setColor(theme.key)
            }
        }
        if (title != null) {
            card.addView(TextView(context).apply {
                text = context.hanNomText(title)
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, context.dpi(4), 0, context.dpi(16))
            })
        }
        // Nhiều dòng thì phần danh sách cuộn được, tiêu đề và nút hành động vẫn đứng yên.
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (row in rows) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dpi(16), context.dpi(12), context.dpi(16), context.dpi(12))
                if (row.checked) background = GradientDrawable().apply {
                    cornerRadius = context.dp(32f)
                    setColor(theme.accent and 0x00FFFFFF or 0x30000000)
                }
                setOnClickListener {
                    dialog.dismiss()
                    row.onClick()
                }
            }
            line.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(theme.accent)
                visibility = if (row.checked) View.VISIBLE else View.INVISIBLE
            }, LinearLayout.LayoutParams(context.dpi(24), context.dpi(24)).apply { marginEnd = context.dpi(16) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // Bộ gõ khác bật nhiều ngôn ngữ thì tên rất dài: cắt còn hai dòng (như Gboard).
            texts.addView(TextView(context).apply {
                text = context.hanNomText(row.title.toString())
                setTextColor(if (row.checked) theme.accent else theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!row.subtitle.isNullOrEmpty()) texts.addView(TextView(context).apply {
                text = context.hanNomText(row.subtitle.toString())
                setTextColor(if (row.checked) theme.accent else theme.hintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val scroll = object : android.widget.ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Không cao quá 60% màn hình.
                val max = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(list)
        scroll.isVerticalScrollBarEnabled = false
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (action != null) {
            // Danh sách dài phải cuộn thì có vạch ngăn với nút hành động (như Gboard).
            val divider = View(context).apply {
                setBackgroundColor(theme.hintText)
                alpha = 0.35f
                visibility = View.INVISIBLE
            }
            card.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(1).coerceAtLeast(1)).apply {
                marginStart = -context.dpi(20)
                marginEnd = -context.dpi(20)
            })
            scroll.viewTreeObserver.addOnGlobalLayoutListener {
                divider.visibility = if (scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
            }
            card.addView(TextView(context).apply {
                text = context.hanNomText(action.first)
                setTextColor(theme.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(context.dpi(16), context.dpi(16), context.dpi(8), context.dpi(8))
                setOnClickListener {
                    dialog.dismiss()
                    action.second()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply {
                this.token = token
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            // Làm tối nền phía sau (cả bàn phím) như Gboard, để thẻ không lẫn vào các phím cùng màu.
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
            val width = minOf((context.resources.displayMetrics.widthPixels * 0.86f).toInt(), context.dpi(420))
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
