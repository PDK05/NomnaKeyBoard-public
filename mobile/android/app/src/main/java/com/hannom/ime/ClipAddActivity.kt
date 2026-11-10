package com.hannom.ime

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hộp thoại "Thêm mục mới" / "Chỉnh sửa" ([EXTRA_EDIT]) của bảng nhớ tạm (như Gboard): tiêu đề theo ngôn ngữ bàn phím đang dùng, ô nhập, Huỷ và Lưu.
 * Ô nhập là ô thật nên chính bàn phím gõ vào được. Đóng xong bàn phím trở lại bảng nhớ tạm ([justClosed]).
 */
class ClipAddActivity : Activity() {
    private lateinit var titleView: TextView
    private lateinit var cancelView: TextView
    private lateinit var saveView: TextView
    private lateinit var noticeView: TextView
    /** Chữ của mục đang sửa ("Chỉnh sửa" trong menu của thẻ); `null` là thêm mục mới. */
    private var editing: String? = null

    /** Đặt chữ theo ngôn ngữ bàn phím [mode]; bàn phím gọi lại khi người dùng chuyển ngôn ngữ lúc hộp thoại đang mở. */
    private fun applyLanguage(mode: Mode) {
        val texts = mode.say(
            listOf(if (editing != null) "Chỉnh sửa" else "Thêm mục mới", "Huỷ", "Lưu", "Vui lòng nhập văn bản rồi mới lưu"),
            listOf(if (editing != null) "整𢯢" else "添目㵋", "毀", "留", "𢝙𢚸入文本耒㵋留"),
            listOf(if (editing != null) "Edit" else "Add a new item", "Cancel", "Save", "Please input some text before saving"),
        )
        listOf(titleView, cancelView, saveView, noticeView).zip(texts).forEach { (view, text) -> view.text = hanNomText(text) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padForCutout()
        editing = intent.getStringExtra(EXTRA_EDIT)
        val colors = SettingsColors.of(this)
        // Làm tối phía sau thẻ như Gboard.
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(DIM)

        val field = EditText(this).apply {
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 3
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            useHanNomFont()
        }
        fun button(onClick: () -> Unit) = TextView(this).apply {
            setTextColor(colors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val pad = dpi(16)
            setPadding(pad, dpi(10), pad, dpi(10))
            setOnClickListener { onClick() }
        }
        // Thông báo vẽ ngay trong cửa sổ: thông báo của hệ thống nằm dưới bàn phím và không có phông Hán Nôm.
        val notice = TextView(this).apply {
            setTextColor(colors.background)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpi(16), dpi(7), dpi(16), dpi(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(colors.text)
            }
            visibility = View.GONE
        }
        val hideNotice = Runnable { notice.visibility = View.GONE }
        noticeView = notice
        val cancel = button { finish() }
        cancelView = cancel
        val save = button {
            val text = field.text.toString()
            if (text.isNotBlank()) {
                val store = ClipStore(this@ClipAddActivity)
                editing?.let { store.edit(it, text) } ?: store.add(text, fresh = true)
                finish()
            } else {
                notice.visibility = View.VISIBLE
                notice.removeCallbacks(hideNotice)
                notice.postDelayed(hideNotice, NOTICE_MS)
            }
        }
        saveView = save
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                if (landscape) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (landscape) setPadding(dpi(20), dpi(16), dpi(12), dpi(6)) else setPadding(dpi(24), dpi(24), dpi(20), dpi(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(28f)
                setColor(colors.background)
            }
            isClickable = true
            titleView = TextView(this@ClipAddActivity).apply {
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, 0, 0, dpi(4))
            }
            addView(titleView)
            // Đo trên Gboard. Ngang: ô nhập và hai nút chung một hàng. Dọc: ô nhập một hàng, hai nút hàng dưới.
            if (landscape) {
                addView(LinearLayout(this@ClipAddActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(cancel)
                    addView(save)
                })
            } else {
                addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpi(9)
                })
                addView(LinearLayout(this@ClipAddActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(cancel)
                    addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dpi(28)
                        marginEnd = dpi(4)
                    })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpi(25)
                })
            }
        }
        val side = if (landscape) (resources.displayMetrics.widthPixels * SIDE).toInt() else dpi(PORTRAIT_SIDE)
        setContentView(FrameLayout(this).apply {
            // Ngang thì chỗ phía trên bàn phím rất ít: thẻ được phép nằm đè thanh trạng thái để ô nhập không bị che.
            fitsSystemWindows = !landscape
            setOnClickListener { finish() }
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    if (landscape) Gravity.TOP else Gravity.BOTTOM,
                ).apply {
                    if (landscape) setMargins(side, dpi(10), side, 0) else setMargins(side, 0, side, dpi(PORTRAIT_BOTTOM))
                },
            )
            addView(
                notice,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    (if (landscape) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL,
                ).apply { if (landscape) topMargin = dpi(4) else bottomMargin = dpi(1) },
            )
        })
        applyLanguage(Settings(this).keyboard.mode)
        current = this
        // Sửa mục: điền sẵn chữ, con trỏ ở cuối (như Gboard).
        editing?.let {
            field.setText(it)
            field.setSelection(it.length)
        }
        field.requestFocus()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun finish() {
        closedAt = SystemClock.uptimeMillis()
        if (!finishKeyboardTask()) super.finish()
    }

    companion object {
        /** Chữ của mục cần sửa: có thì hộp thoại là "Chỉnh sửa" thay cho "Thêm mục mới". */
        const val EXTRA_EDIT = "com.hannom.ime.CLIP_EDIT"
        /** Lề hai bên hộp thoại so với bề ngang màn hình (đo trên Gboard). */
        private const val SIDE = 0.085f
        /** Độ tối của phần phía sau thẻ (đo trên Gboard). */
        private const val DIM = 0.6f
        /** Thời gian hiện thông báo khi lưu mà ô nhập trống (ms). */
        private const val NOTICE_MS = 2500L
        /** Màn hình dọc: lề hai bên và khoảng cách từ đáy thẻ tới bàn phím (dp, đo trên Gboard). */
        private const val PORTRAIT_SIDE = 25
        private const val PORTRAIT_BOTTOM = 39
        /** Trong chừng này ms sau khi đóng, lần bàn phím mở lại được coi là quay về từ hộp thoại. */
        private const val RETURN_MS = 3000L
        private var closedAt = 0L
        /** Hộp thoại đang mở, để bàn phím báo khi đổi ngôn ngữ. */
        private var current: ClipAddActivity? = null

        /**
         * Chuyển sang bộ gõ của ứng dụng khác: đóng hộp thoại đang mở (như Gboard, hộp thoại đi cùng bàn phím) và
         * không trở lại bảng nhớ tạm.
         */
        fun keyboardLeft() {
            current?.finish()
            closedAt = 0L
        }

        /** Bàn phím vừa chuyển sang ngôn ngữ [mode]: đổi chữ của hộp thoại đang mở (nếu có). */
        fun languageChanged(mode: Mode) {
            current?.applyLanguage(mode)
        }

        /** Hộp thoại vừa đóng xong không. */
        fun closedRecently() = closedAt != 0L && SystemClock.uptimeMillis() - closedAt < RETURN_MS

        /** Như [closedRecently] nhưng chỉ trả `true` một lần. */
        fun justClosed(): Boolean {
            val recent = closedRecently()
            closedAt = 0L
            return recent
        }
    }
}
