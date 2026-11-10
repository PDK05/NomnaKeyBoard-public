package com.hannom.ime

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Bảng màu trang cài đặt, lấy mẫu từ "Cài đặt Gboard" (sáng / tối theo hệ thống). */
class SettingsColors(
    val background: Int,
    /** Nền thanh tiêu đề khi đã cuộn. */
    val bar: Int,
    val text: Int,
    val icon: Int,
    val summary: Int,
    /** Tiêu đề nhóm, công tắc. */
    val accent: Int,
    val ripple: Int,
    /** Nút nổi, ô chọn bố cục đã chọn (xanh đậm của Gboard) và chữ trên đó. */
    val primary: Int,
    val onPrimary: Int,
    val light: Boolean,
) {
    companion object {
        fun of(context: Context): SettingsColors {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                SettingsColors(
                    background = Color.parseColor("#1B1E29"),
                    bar = Color.parseColor("#30333E"),
                    text = Color.parseColor("#E3E2EA"),
                    icon = Color.parseColor("#C6C6D2"),
                    summary = Color.parseColor("#C6C6D2"),
                    accent = Color.parseColor("#B1C5FF"),
                    ripple = Color.parseColor("#33FFFFFF"),
                    primary = Color.parseColor("#A8C7FA"),
                    onPrimary = Color.parseColor("#062E6F"),
                    light = false,
                )
            } else {
                SettingsColors(
                    background = Color.parseColor("#EDEDF7"),
                    bar = Color.parseColor("#E2E1F1"),
                    text = Color.parseColor("#1B1C21"),
                    icon = Color.parseColor("#2A2E34"),
                    summary = Color.parseColor("#45464F"),
                    accent = Color.parseColor("#3F5A92"),
                    ripple = Color.parseColor("#1F000000"),
                    primary = Color.parseColor("#0B57D0"),
                    onPrimary = Color.WHITE,
                    light = true,
                )
            }
        }
    }
}

/**
 * ScrollView coi cả padding là vùng nhìn thấy: nội dung ngắn hơn khung thì không nhận vuốt bắt đầu
 * trên một dòng, và không cuộn ô đang gõ (con trỏ, Enter) ra khỏi chỗ bàn phím che. Lớp này tính
 * vùng nhìn thấy trừ padding, chừa thêm [topMargin] cho thanh tiêu đề và [bottomMargin] cho dải
 * khung gõ trong suốt của bàn phím.
 */
private class PaddedScrollView(context: Context, val topMargin: Int, val bottomMargin: Int) : ScrollView(context) {
    private val scrollRange get() = maxOf(0, (getChildAt(0)?.height ?: 0) - (height - paddingTop - paddingBottom))

    override fun canScrollVertically(direction: Int) = if (direction < 0) scrollY > 0 else scrollY < scrollRange

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val top = scrollY + paddingTop + topMargin
        val bottom = scrollY + height - paddingBottom - bottomMargin
        return when {
            rect.bottom > bottom && rect.top > top -> minOf(rect.bottom - bottom, rect.top - top)
            rect.top < top -> rect.top - top
            else -> 0
        }
    }
}

/** Nút icon ở góc phải thanh tiêu đề (bút chì "Sửa", thùng rác…). */
/** Extra: trang mở từ bàn phím (nút cài đặt, từ điển), ở cửa sổ (task) riêng. */
const val EXTRA_FROM_KEYBOARD = "fromKeyboard"

/**
 * Trang đầu của cửa sổ mở từ bàn phím: đóng thì bỏ luôn cửa sổ (khỏi để lại ô trống trong danh sách gần đây), về
 * đúng ứng dụng đang gõ. Trả `true` nếu đã đóng như vậy.
 */
fun Activity.finishKeyboardTask(): Boolean {
    if (!isTaskRoot || !intent.getBooleanExtra(EXTRA_FROM_KEYBOARD, false)) return false
    finishAndRemoveTask()
    return true
}

class BarAction(val icon: Int, val description: String, val onClick: () -> Unit)

/** Nút nổi góc dưới (như "+ Thêm bàn phím" của Gboard). */
class Fab(val label: String, val icon: Int?, val onClick: () -> Unit)

/**
 * Trang cài đặt kiểu Gboard: tiêu đề lớn ở trên, cuộn qua thì thu thành thanh tiêu đề có nút quay lại.
 * [build] thêm các dòng vào cột nội dung. [back]: có nút quay lại (trang chính mở từ biểu tượng ứng dụng thì không).
 */
fun Activity.settingsPage(
    title: String,
    action: BarAction? = null,
    fab: Fab? = null,
    back: Boolean = true,
    build: LinearLayout.(SettingsColors) -> Unit,
) {
    val colors = SettingsColors.of(this)
    val barHeight = dpi(64)
    val bigTitle = TextView(this).apply {
        text = title
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        setPadding(dpi(24), dpi(40), dpi(24), dpi(24))
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(context), LinearLayout.LayoutParams(1, barHeight))
        addView(bigTitle)
        build(colors)
        // Chừa chỗ cho nút nổi để dòng cuối không bị che.
        if (fab != null) addView(View(context), LinearLayout.LayoutParams(1, dpi(96)))
    }
    val scroll = PaddedScrollView(this, topMargin = barHeight, bottomMargin = dpi(40)).apply {
        isVerticalScrollBarEnabled = true
        clipToPadding = false
        addView(column)
    }

    val smallTitle = TextView(this).apply {
        text = title
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
    }
    val backButton = ImageView(this).apply {
        setImageResource(R.drawable.ic_arrow_back)
        setColorFilter(colors.text)
        contentDescription = "Quay lại"
        scaleType = ImageView.ScaleType.CENTER
        background = iconRipple(colors)
        setOnClickListener { finish() }
    }
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.background)
        // Thanh vẫn cao như khi có nút (khỏi đổi chỗ tiêu đề nhỏ).
        minimumHeight = dpi(56)
        if (back) addView(backButton, LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { marginStart = dpi(4) })
        addView(smallTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            // Không có nút quay lại thì tiêu đề nhỏ thẳng hàng tiêu đề lớn.
            marginStart = if (back) dpi(12) else dpi(24)
            marginEnd = dpi(16)
        })
        if (action != null) {
            addView(ImageView(context).apply {
                setImageResource(action.icon)
                setColorFilter(colors.text)
                contentDescription = action.description
                scaleType = ImageView.ScaleType.CENTER
                background = iconRipple(colors)
                setOnClickListener { action.onClick() }
            }, LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { marginEnd = dpi(4) })
        }
    }
    val fabView = fab?.let { fabButton(colors, it) }

    // Tiêu đề lớn khuất sau thanh thì hiện tiêu đề nhỏ và tô nền thanh (như Gboard).
    scroll.setOnScrollChangeListener { _, _, y, _, _ ->
        val collapsed = y > bigTitle.bottom - barHeight - dpi(24)
        smallTitle.animate().alpha(if (collapsed) 1f else 0f).setDuration(150).start()
        bar.setBackgroundColor(if (y > 0) colors.bar else colors.background)
    }

    val root = FrameLayout(this).apply {
        setBackgroundColor(colors.background)
        addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (fabView != null) {
            addView(fabView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(56), Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dpi(24), 0, 0, dpi(16))
            })
        }
    }
    // Android 15+ vẽ tràn viền: thanh tiêu đề chừa thanh trạng thái, nội dung chừa thanh điều hướng
    // và bàn phím (hệ thống không tự đẩy nội dung lên nữa).
    root.setOnApplyWindowInsetsListener { _, insets ->
        val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
        // Chỉ đặt khi đổi, để không gây layout lại liên tục.
        if (bar.paddingTop != top) {
            bar.setPadding(0, top, 0, 0)
            bar.minimumHeight = top + barHeight
        }
        if (scroll.paddingTop != top || scroll.paddingBottom != bottom) {
            scroll.setPadding(0, top, 0, bottom)
            fabView?.let { f ->
                (f.layoutParams as FrameLayout.LayoutParams).setMargins(dpi(24), 0, 0, bottom + dpi(16))
                f.requestLayout()
            }
            // Bàn phím vừa hiện: cuộn ô đang gõ lên trên bàn phím.
            scroll.post { scroll.findFocus()?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height)) } }
        }
        insets
    }
    setContentView(root)
    styleSystemBars(colors)
}

/** Biểu tượng thanh trạng thái / điều hướng tối trên nền sáng (gọi sau setContentView). */
fun Activity.styleSystemBars(colors: SettingsColors) {
    @Suppress("DEPRECATION")
    window.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    window.navigationBarColor = colors.background
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (colors.light) mask else 0, mask)
    } else if (colors.light) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
}

/** Gợn khi chạm nút icon trên thanh tiêu đề: tròn 20dp quanh icon (không đặt thì gợn lan quá nút, bị cắt mép). */
fun Context.iconRipple(colors: SettingsColors) = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, null).apply {
    if (android.os.Build.VERSION.SDK_INT >= 23) radius = dpi(20)
}

private fun View.rippleBackground(colors: SettingsColors) {
    background = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, ColorDrawable(Color.WHITE))
    isClickable = true
    isFocusable = true
}

private fun ViewGroup.textBlock(colors: SettingsColors, title: String, summary: String?): Pair<LinearLayout, TextView> {
    val summaryView = TextView(context).apply {
        text = summary.orEmpty()
        setTextColor(colors.summary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    val block = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        })
        addView(summaryView)
    }
    return block to summaryView
}

/**
 * Một dòng kiểu Gboard: icon bên trái, tiêu đề, dòng phụ. Trả về ô dòng phụ để cập nhật sau.
 * Không có icon thì chữ thụt như trang con của Gboard.
 */
fun LinearLayout.item(
    colors: SettingsColors,
    title: String,
    summary: String? = null,
    icon: Int? = null,
    onClick: () -> Unit,
): TextView {
    val (block, summaryView) = textBlock(colors, title, summary)
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(76)
        setPadding(context.dpi(24), context.dpi(14), context.dpi(24), context.dpi(14))
        rippleBackground(colors)
        setOnClickListener { onClick() }
        if (icon != null) {
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(colors.icon)
            }, LinearLayout.LayoutParams(context.dpi(26), context.dpi(26)).apply { marginEnd = context.dpi(30) })
        }
        addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return summaryView
}

/** Dòng có công tắc bên phải (như "Lựa chọn ưu tiên" của Gboard); chạm cả dòng cũng bật / tắt. */
@Suppress("DEPRECATION")
fun LinearLayout.switchItem(
    colors: SettingsColors,
    title: String,
    summary: String? = null,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val (block, _) = textBlock(colors, title, summary)
    val toggle = Switch(context).apply {
        isChecked = value
        thumbDrawable = switchThumb(colors)
        trackDrawable = switchTrack(colors)
        showText = false
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(76)
        setPadding(context.dpi(24), context.dpi(14), context.dpi(20), context.dpi(14))
        rippleBackground(colors)
        setOnClickListener { toggle.toggle() }
        addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dpi(16) })
        addView(toggle)
    }
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

private val CHECKED = intArrayOf(android.R.attr.state_checked)

/**
 * Nút tròn của công tắc kiểu Material 3 (như Gboard): bật thì to, tắt thì nhỏ.
 * Viền trong suốt dày hơn làm nút trông nhỏ hơn mà công tắc không đổi kích thước.
 */
private fun View.switchThumb(colors: SettingsColors): StateListDrawable {
    val size = context.dpi(28)
    fun knob(color: Int, inset: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(size, size)
        setColor(color)
        setStroke(inset, Color.TRANSPARENT)
    }
    return StateListDrawable().apply {
        addState(CHECKED, knob(if (colors.light) Color.WHITE else Color.parseColor("#14306A"), context.dpi(4)))
        addState(intArrayOf(), knob(colors.summary, context.dpi(8)))
    }
}

/** Rãnh công tắc: bật thì tô màu nhấn, tắt thì nền thanh có viền. */
private fun View.switchTrack(colors: SettingsColors): StateListDrawable {
    fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = context.dp(16f)
        setColor(fill)
        setStroke(context.dpi(2), stroke)
    }
    return StateListDrawable().apply {
        addState(CHECKED, pill(colors.accent, colors.accent))
        addState(intArrayOf(), pill(colors.bar, colors.summary))
    }
}

/** Tiêu đề nhóm nhỏ, màu nhấn (vd. "Phím tắt", "Bố cục" của Gboard). */
fun LinearLayout.section(colors: SettingsColors, text: String) = addView(TextView(context).apply {
    this.text = text
    setTextColor(colors.accent)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setPadding(context.dpi(24), context.dpi(24), context.dpi(24), context.dpi(8))
})

/** Đoạn chữ giải thích. */
fun LinearLayout.paragraph(colors: SettingsColors, text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(colors.summary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setLineSpacing(0f, 1.2f)
    setPadding(context.dpi(24), context.dpi(8), context.dpi(24), context.dpi(8))
}.also { addView(it) }

/** Nút nổi bo tròn, màu xanh đậm: icon rồi chữ. */
private fun Context.fabButton(colors: SettingsColors, fab: Fab): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dpi(20), 0, dpi(24), 0)
    background = RippleDrawable(
        ColorStateList.valueOf(colors.ripple),
        GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor(colors.primary)
        },
        null,
    )
    elevation = dp(3f)
    isClickable = true
    setOnClickListener { fab.onClick() }
    if (fab.icon != null) {
        addView(ImageView(context).apply {
            setImageResource(fab.icon)
            setColorFilter(colors.onPrimary)
        }, LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { marginEnd = dpi(12) })
    }
    addView(TextView(context).apply {
        text = fab.label
        setTextColor(colors.onPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    })
}

/** Một bố cục trong dải chọn bố cục: ảnh xem trước và nhãn của ô chọn. */
class LayoutOption(val preview: View, val label: String, val enabled: Boolean = true)

/**
 * Dải chọn bố cục của một ngôn ngữ (như Gboard): ảnh xem trước cuộn ngang, dưới mỗi ảnh một ô chọn
 * bo tròn; đã chọn thì tô xanh có dấu ✓, chưa chọn thì viền có vòng tròn. Chọn được nhiều ô.
 * [onToggle] trả `false` thì giữ nguyên (vd. bỏ chọn bàn phím cuối cùng).
 */
fun LinearLayout.layoutChooser(
    colors: SettingsColors,
    options: List<LayoutOption>,
    selected: Set<Int>,
    onToggle: (index: Int, checked: Boolean) -> Boolean,
) {
    val chosen = selected.toMutableSet()
    val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(context.dpi(20), context.dpi(24), context.dpi(20), context.dpi(24))
    }
    options.forEachIndexed { i, option ->
        val pill = TextView(context)
        fun paint() {
            val on = i in chosen
            pill.text = (if (on) "✓  " else "◯  ") + option.label
            pill.setTextColor(if (on) colors.onPrimary else colors.text)
            pill.background = GradientDrawable().apply {
                cornerRadius = context.dp(24f)
                setColor(if (on) colors.primary else Color.TRANSPARENT)
                if (!on) setStroke(context.dpi(1), colors.summary)
            }
        }
        pill.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dpi(16), 0, context.dpi(16), 0)
            alpha = if (option.enabled) 1f else 0.4f
            paint()
            setOnClickListener {
                if (!option.enabled) return@setOnClickListener
                val on = i !in chosen
                if (onToggle(i, on)) {
                    if (on) chosen.add(i) else chosen.remove(i)
                    paint()
                }
            }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(option.preview, LinearLayout.LayoutParams(context.dpi(184), context.dpi(130)))
            addView(pill, LinearLayout.LayoutParams(context.dpi(184), context.dpi(44)).apply { topMargin = context.dpi(12) })
        }
        strip.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (i > 0) marginStart = context.dpi(12)
        })
    }
    addView(HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(colors.bar)
        addView(strip)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/**
 * Danh sách sửa được (trang sửa "Ngôn ngữ" của Gboard): ô đánh dấu bên trái để xoá, tay nắm bên phải
 * kéo để đổi thứ tự. Thả ra thì dòng chuyển chỗ ngay trong danh sách (không dựng lại trang) và [onMove]
 * báo thứ tự mới; [checked] là vị trí các dòng đang đánh dấu, tự cập nhật theo thứ tự mới.
 */
fun LinearLayout.reorderList(
    colors: SettingsColors,
    rows: List<Pair<String, String>>,
    checked: MutableSet<Int>,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val moved = { from: Int, to: Int ->
        // Vị trí các dòng đã đánh dấu theo thứ tự mới.
        val order = (0 until list.childCount).toMutableList().apply { add(to, removeAt(from)) }
        val marks = checked.toSet()
        checked.clear()
        order.forEachIndexed { now, before -> if (before in marks) checked.add(now) }
        onMove(from, to)
    }
    rows.forEachIndexed { i, (title, summary) ->
        val (block, _) = list.textBlock(colors, title, summary)
        lateinit var row: LinearLayout
        val box = CheckBox(context).apply {
            buttonTintList = ColorStateList.valueOf(colors.accent)
            isChecked = i in checked
            setOnCheckedChangeListener { _, v ->
                val at = list.indexOfChild(row)
                if (v) checked.add(at) else checked.remove(at)
            }
        }
        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            setColorFilter(colors.icon)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Kéo để đổi thứ tự"
        }
        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpi(76)
            setPadding(context.dpi(12), context.dpi(14), context.dpi(8), context.dpi(14))
            background = RippleDrawable(ColorStateList.valueOf(colors.ripple), ColorDrawable(colors.background), null)
            setOnClickListener { box.toggle() }
            addView(box, LinearLayout.LayoutParams(context.dpi(48), context.dpi(48)).apply { marginEnd = context.dpi(8) })
            addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(handle, LinearLayout.LayoutParams(context.dpi(48), context.dpi(48)))
        }
        handle.setOnTouchListener(DragHandler(list, row, moved))
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/**
 * Kéo một dòng theo ngón tay; các dòng nó đi qua trượt vào chỗ trống. Thả ra thì dòng trượt vào ô mới
 * và chuyển chỗ ngay trong [list] (các dòng cao bằng nhau), rồi báo (vị trí cũ, vị trí mới).
 */
private class DragHandler(
    private val list: LinearLayout,
    private val row: View,
    private val onMove: (Int, Int) -> Unit,
) : View.OnTouchListener {
    private var startY = 0f
    private var from = -1
    private var target = -1
    /** Độ dịch đích của từng dòng khác, để chỉ chạy hiệu ứng khi đổi. */
    private val shifts = HashMap<View, Float>()

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        val h = row.height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.rawY
                from = list.indexOfChild(row)
                target = from
                shifts.clear()
                row.animate().cancel()
                row.elevation = v.context.dp(6f)
                list.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (from < 0) return true
                val dy = (e.rawY - startY).coerceIn(-from * h, (list.childCount - 1 - from) * h)
                row.translationY = dy
                target = (from + Math.round(dy / h)).coerceIn(0, list.childCount - 1)
                for (j in 0 until list.childCount) {
                    if (j == from) continue
                    val other = list.getChildAt(j)
                    val shift = when (j) {
                        in (from + 1)..target -> -h
                        in target until from -> h
                        else -> 0f
                    }
                    if (shifts[other] != shift) {
                        shifts[other] = shift
                        other.animate().translationY(shift).setDuration(SLIDE_MS).start()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (from < 0) return true
                val (start, to) = from to target
                from = -1
                list.parent?.requestDisallowInterceptTouchEvent(false)
                for (j in 0 until list.childCount) list.getChildAt(j).apply {
                    animate().cancel()
                    if (this !== row) translationY = 0f
                }
                // Các dòng khác đã nằm đúng chỗ mới; dòng kéo trượt từ chỗ ngón tay thả vào ô của nó.
                val dropped = row.translationY - (to - start) * h
                if (to != start) {
                    list.removeView(row)
                    list.addView(row, to)
                }
                row.translationY = dropped
                row.animate().translationY(0f).setDuration(SLIDE_MS).withEndAction { row.elevation = 0f }.start()
                if (to != start) onMove(start, to)
            }
        }
        return true
    }

    private companion object {
        const val SLIDE_MS = 150L
    }
}
