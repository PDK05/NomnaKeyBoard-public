package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Các nút của bảng sửa văn bản. [DESELECT]: tắt "Chọn" khi đang có vùng chọn (bỏ chọn, con trỏ về cuối vùng). */
enum class EditAction { UP, DOWN, LEFT, RIGHT, HOME, END, SELECT_ALL, COPY, CUT, PASTE, DELETE, DESELECT }

/**
 * Bảng sửa văn bản (như Gboard), thay chỗ bàn phím, thanh công cụ vẫn ở trên. Bốn cột đều nhau: ◀ cao suốt ba
 * hàng, cột ▲ / "Chọn" / ▼, ▶ cao suốt ba hàng, cột phải "Chọn tất cả" / "Sao chép" / "Dán"; hàng dưới về đầu,
 * về cuối và xoá. Bật "Chọn" thì mũi tên kéo vùng chọn. Đang có vùng chọn (bộ gõ báo qua [hasSelection]) thì
 * "Chọn" tự sáng, "Chọn tất cả" thành "Cắt" và "Sao chép" dùng được; chưa có thì "Sao chép" mờ.
 * Ô nhập trống ([hasText]) thì mọi nút tắt (mờ), trừ "Dán" khi đã có chữ sao chép / cắt để dán ([canPaste]).
 * Giữ mũi tên hay phím xoá thì lặp.
 */
@SuppressLint("ViewConstructor")
class TextEditPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        /** [selecting]: đang bật "Chọn" (mũi tên, đầu / cuối dòng kéo vùng chọn). */
        fun onEditAction(action: EditAction, selecting: Boolean)
        fun onKeyFeedback(view: View)
    }

    var theme = KeyboardTheme.of(context)
    /** Đang bật "Chọn". */
    var selecting = false
        set(v) {
            field = v
            styleState()
        }
    /** Ô nhập đang có vùng chọn (khác rỗng). Mới có thì bật "Chọn"; vùng chọn mất thì giữ "Chọn" như người dùng đặt. */
    var hasSelection = false
        set(v) {
            if (field == v) return
            field = v
            if (v) selecting = true else styleState()
        }
    /** Ô nhập có chữ: không thì mọi nút tắt, trừ "Dán" ([canPaste]). */
    var hasText = false
        set(v) {
            if (field == v) return
            field = v
            styleState()
        }
    /** Đã có chữ sao chép / cắt (bảng nhớ của máy có chữ): "Dán" mới bật. */
    var canPaste = false
        set(v) {
            if (field == v) return
            field = v
            styleState()
        }
    private val repeater = Handler(Looper.getMainLooper())
    private val buttons = ArrayList<View>()
    private val select = text("Chọn") { v ->
        feedback(v)
        if (selecting && hasSelection) listener.onEditAction(EditAction.DESELECT, false)
        selecting = !selecting
    }
    /** "Chọn tất cả", thành "Cắt" khi đang có vùng chọn. */
    private val selectAll = text("Chọn tất cả") { v -> fire(v, if (hasSelection) EditAction.CUT else EditAction.SELECT_ALL) }
    private val copy = text("Sao chép") { v -> if (hasSelection) fire(v, EditAction.COPY) }
    private val paste = text("Dán") { v -> fire(v, EditAction.PASTE) }

    init {
        orientation = VERTICAL
        val pad = context.dpi(5)
        setPadding(pad, pad, pad, pad)
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            add(icon(EditAction.LEFT, R.drawable.ic_chevron_left), 1f)
            add(column(icon(EditAction.UP, R.drawable.ic_chevron_left, 90f), select, icon(EditAction.DOWN, R.drawable.ic_chevron_left, -90f)), 1f, margin = false)
            add(icon(EditAction.RIGHT, R.drawable.ic_chevron_right), 1f)
            add(column(selectAll, copy, paste), 1f, margin = false)
        }
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 3f))
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            add(icon(EditAction.HOME, R.drawable.ic_first_page), 1.5f)
            add(icon(EditAction.END, R.drawable.ic_last_page), 1.5f)
            add(icon(EditAction.DELETE, R.drawable.ic_backspace), 1f)
        }
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Màu đổi thì tô lại các nút. */
    fun applyTheme() {
        setBackgroundColor(theme.background)
        for (v in buttons) {
            v.background = keyBackground(theme.key)
            (v as? TextView)?.setTextColor(theme.text)
            ((v as? FrameLayout)?.getChildAt(0) as? ImageView)?.setColorFilter(theme.text)
        }
        styleState()
    }

    /** Mở bảng: "Chọn" theo vùng chọn hiện có của ô nhập; các nút bật / tắt theo [text] và [pasteable]. */
    fun open(selected: Boolean, text: Boolean, pasteable: Boolean) {
        hasSelection = selected
        selecting = selected
        hasText = text
        canPaste = pasteable
        applyTheme()
    }

    /** Bàn phím đang dùng: nhãn các nút theo ngôn ngữ của nó. */
    var style = KeyboardStyle.VIETNAMESE
        set(v) {
            if (field == v) return
            field = v
            styleState()
        }

    /** Nhãn theo [style]; chỉ chữ Hán Nôm ngoài BMP dùng phông Hán Nôm. */
    private fun say(vi: String, nom: String, en: String): CharSequence = context.hanNomText(style.say(vi, nom, en))

    private fun styleState() {
        // Ô nhập trống thì không có gì để chọn: "Chọn" cũng tắt.
        val on = selecting && hasText
        select.background = keyBackground(if (on) theme.accent else theme.key)
        select.setTextColor(if (on) theme.onAccent else theme.text)
        select.text = say("Chọn", "𫾗", "Select")
        selectAll.text = if (hasSelection) say("Cắt", "割", "Cut") else say("Chọn tất cả", "𫾗畢𪥘", "Select all")
        copy.text = say("Sao chép", "抄劄", "Copy")
        paste.text = say("Dán", "𬖭", "Paste")
        for (v in buttons) {
            val enabled = when (v) {
                paste -> canPaste
                copy -> hasText && hasSelection
                else -> hasText
            }
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    private fun keyBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = context.dp(10f)
        setColor(color)
    }

    /**
     * Thêm [v] chiếm [weight] phần chiều ngang; [margin]: chừa khe quanh nút (cột con tự chừa). Khe nằm trong ô bọc
     * chứ không phải lề, để bề rộng chia đúng theo [weight] (cột phải rộng bằng phím xoá).
     */
    private fun LinearLayout.add(v: View, weight: Float, margin: Boolean = true) {
        val cell = if (!margin) v else FrameLayout(context).apply {
            context.dpi(GAP).let { setPadding(it, it, it, it) }
            addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, weight))
    }

    /** Cột ba nút cao bằng nhau. */
    private fun column(vararg views: View) = LinearLayout(context).apply {
        orientation = VERTICAL
        for (v in views) addView(v, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            context.dpi(GAP).let { setMargins(it, it, it, it) }
        })
    }

    private fun text(label: String, onClick: (View) -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        buttons.add(this)
        setOnClickListener { onClick(it) }
    }

    private fun icon(action: EditAction, res: Int, rotation: Float = 0f) = FrameLayout(context).apply {
        addView(ImageView(context).apply {
            setImageResource(res)
            this.rotation = rotation
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentDescription = action.name
        buttons.add(this)
        repeatOnHold(this, action)
    }

    /** Ấn là chạy một lần, giữ thì lặp (mũi tên, xoá). */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(v: View, action: EditAction) {
        val tick = object : Runnable {
            override fun run() {
                fire(v, action)
                repeater.postDelayed(this, REPEAT_MS)
            }
        }
        v.setOnTouchListener { _, ev ->
            // Nút đang tắt (ô nhập trống): nuốt chạm, không làm gì.
            if (!v.isEnabled) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.alpha = 0.6f
                    fire(v, action)
                    repeater.postDelayed(tick, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    // Giữ xoá tới khi ô nhập trống thì nút vừa tắt: giữ độ mờ của nút tắt.
                    v.alpha = if (v.isEnabled) 1f else DISABLED_ALPHA
                    repeater.removeCallbacks(tick)
                }
            }
            true
        }
    }

    private fun fire(v: View, action: EditAction) {
        // Giữ để lặp mà nút vừa tắt (xoá hết chữ): thôi, không rung.
        if (!v.isEnabled) return
        feedback(v)
        listener.onEditAction(action, selecting)
        // Đã cắt / dán / xoá thì thôi chọn, như Gboard (sao chép vẫn giữ vùng chọn).
        if (action in DONE_SELECTING) selecting = false
    }

    private fun feedback(v: View) = listener.onKeyFeedback(v)

    override fun onDetachedFromWindow() {
        repeater.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) repeater.removeCallbacksAndMessages(null)
    }

    companion object {
        /** Nửa khe giữa hai nút (dp). */
        private const val GAP = 3
        /** Độ mờ của nút đang tắt. */
        private const val DISABLED_ALPHA = 0.4f
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_MS = 60L
        private val DONE_SELECTING = setOf(EditAction.CUT, EditAction.PASTE, EditAction.DELETE)
    }
}
