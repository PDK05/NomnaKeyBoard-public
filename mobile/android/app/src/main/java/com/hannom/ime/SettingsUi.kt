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
        text = context.hanNomText(title)
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
        text = context.hanNomText(title)
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
    }
    val backButton = ImageView(this).apply {
        setImageResource(R.drawable.ic_arrow_back)
        setColorFilter(colors.text)
        contentDescription = tr("Quay lại", "Back")
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
        text = context.hanNomText(summary.orEmpty())
        setTextColor(colors.summary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    val block = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = context.hanNomText(title)
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

/**
 * Bật / tắt cả một dòng cài đặt (dòng tắt thì mờ đi, như mục phụ thuộc của Gboard). [row]: dòng của [item] (cha của
 * khối chữ chứa ô dòng phụ) hay của [switchItem] (cha của công tắc), xem [rowOf].
 */
fun setRowEnabled(row: View, enabled: Boolean) {
    fun apply(v: View) {
        v.isEnabled = enabled
        if (v is ViewGroup) for (i in 0 until v.childCount) apply(v.getChildAt(i))
    }
    apply(row)
    row.alpha = if (enabled) 1f else 0.38f
}

/** Dòng chứa ô dòng phụ của [item] / công tắc của [switchItem]. */
fun rowOf(summaryOrSwitch: View): View =
    (if (summaryOrSwitch is Switch) summaryOrSwitch.parent else summaryOrSwitch.parent.parent) as View

/**
 * Thanh kéo kiểu Material 3 như Gboard (đo trên điện thoại 2026-11-18, màn hình 354 dp): rãnh dày 16 dp bo tròn, phần đã
 * kéo màu nhấn, phần còn lại màu nhạt; nút kéo là vạch đứng 4 × 44 dp, cách hai phần rãnh 4 dp; ít nấc thì mỗi nấc một
 * chấm 4 dp (trên phần đã kéo chấm màu nhạt, phần còn lại chấm màu nhấn). Nấc đầu / cuối cách hai đầu rãnh 8 dp.
 */
class SettingsSlider(
    context: Context,
    private val colors: SettingsColors,
    max: Int,
    value: Int,
    /** Chữ trong bong bóng hiện trên nút kéo lúc đang kéo (Gboard: "9", "8", "8+"…, "100%"); `null` thì không có. */
    private val labelOf: ((Int) -> String)? = null,
    private val onChange: (Int) -> Unit,
) : View(context) {
    var max = max.coerceAtLeast(1)
    /** Nấc đang chọn (0..[max]); đặt từ code thì không gọi [onChange]. */
    var progress = value.coerceIn(0, this.max)
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val path = android.graphics.Path()
    private val active = colors.primary
    private val inactive = if (colors.light) Color.parseColor("#E2DFFF") else Color.parseColor("#3B4252")
    /** Đang kéo: hiện bong bóng giá trị. */
    private var dragging = false
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = context.sp(14f)
        color = Color.parseColor("#E0E2EF")
    }
    /** Có vẽ chấm nấc không (thanh liên tục 0–100 như âm lượng thì không). */
    private val stops get() = max <= 20

    private fun d(v: Float) = context.dp(v)
    private val inset get() = d(8f)
    private fun xOf(v: Float) = inset + (width - 2 * inset) * v / max

    /** Khung bo tròn: đầu ngoài bo tròn hẳn, đầu sát nút kéo bo 2 dp. */
    private fun track(c: android.graphics.Canvas, l: Float, r: Float, outerLeft: Boolean, color: Int) {
        if (r - l <= 0f) return
        val cy = height / 2f
        val h = d(8f)
        val big = h
        val small = d(2f)
        val (rl, rr) = if (outerLeft) big to small else small to big
        rect.set(l, cy - h, r, cy + h)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(rl, rl, rr, rr, rr, rr, rl, rl), android.graphics.Path.Direction.CW)
        paint.color = color
        c.drawPath(path, paint)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val cy = height / 2f
        val hx = xOf(progress.toFloat())
        val gap = d(4f) + d(2f)
        track(c, 0f, hx - gap, outerLeft = true, color = active)
        track(c, hx + gap, width.toFloat(), outerLeft = false, color = inactive)
        if (stops) {
            for (i in 0..max) {
                if (i == progress) continue
                paint.color = if (i < progress) inactive else active
                c.drawCircle(xOf(i.toFloat()), cy, d(2f), paint)
            }
        }
        paint.color = active
        // Nút kéo cao 44 dp; đang giữ thì ngắn lại còn 38 dp như Gboard (đo 2026-11-18).
        val half = d(if (dragging) 19f else 22f)
        rect.set(hx - d(2f), cy - half, hx + d(2f), cy + half)
        c.drawRoundRect(rect, d(2f), d(2f), paint)
    }

    /**
     * Bong bóng giá trị lúc kéo, như Gboard (đo 2026-11-18): tròn 32 dp màu tối, một chữ thì tròn, dài ("100%") thì thành
     * viên thuốc; cách đỉnh nút kéo 7 dp; đè lên tiêu đề và hàng emoji phía trên. Vẽ trên lớp phủ của cửa sổ: vẽ tràn
     * ra ngoài view thì Android không vẽ lại phần tràn, bong bóng không hiện (lỗi bản trước).
     */
    private val bubble = object : android.graphics.drawable.Drawable() {
        var label = ""
        override fun draw(c: android.graphics.Canvas) {
            val r = android.graphics.RectF(bounds)
            paint.color = Color.parseColor("#10131C")
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            c.drawText(label, r.centerX(), r.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    private var bubbleHost: ViewGroup? = null
    private val loc = IntArray(2)
    private val rootLoc = IntArray(2)

    /** Đặt bong bóng trên nút kéo (đang giữ: nút cao 38 dp); không kéo thì gỡ bong bóng. */
    private fun placeBubble() {
        val hx = xOf(progress.toFloat())
        val top = height / 2f - d(19f)
        val label = labelOf?.takeIf { dragging }?.invoke(progress)
        if (label == null) {
            bubbleHost?.overlay?.remove(bubble)
            bubbleHost = null
            return
        }
        val host = bubbleHost ?: (rootView as? ViewGroup)?.also { it.overlay.add(bubble); bubbleHost = it } ?: return
        getLocationInWindow(loc)
        host.getLocationInWindow(rootLoc)
        val x = loc[0] - rootLoc[0] + hx
        val bottom = loc[1] - rootLoc[1] + top - d(7f)
        val h = d(32f)
        // Gboard (đo 2026-11-18): một ký tự ("8") là hình tròn 32 dp; viên "100%" rộng ≈ 59 dp = chữ + 13 dp mỗi bên.
        val w = if (label.length <= 1) h else maxOf(h, labelPaint.measureText(label) + d(26f))
        bubble.label = label
        bubble.setBounds((x - w / 2).toInt(), (bottom - h).toInt(), (x + w / 2).toInt(), bottom.toInt())
        bubble.invalidateSelf()
    }

    override fun onDetachedFromWindow() {
        bubbleHost?.overlay?.remove(bubble)
        bubbleHost = null
        super.onDetachedFromWindow()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!dragging) {
                    dragging = true
                    invalidate()
                }
                val v = Math.round((e.x - inset) / (width - 2 * inset) * max).coerceIn(0, max)
                if (v != progress) {
                    progress = v
                    onChange(v)
                }
                placeBubble()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                invalidate()
                placeBubble()
            }
        }
        return true
    }
}

fun Context.settingsSlider(
    colors: SettingsColors,
    max: Int,
    value: Int,
    labelOf: ((Int) -> String)? = null,
    onChange: (Int) -> Unit,
) = SettingsSlider(this, colors, max, value, labelOf, onChange)

/**
 * Khối có thanh kéo ngay trên trang (như "Cỡ chữ", "Kích thước biểu tượng cảm xúc" của Gboard): tiêu đề, dòng phụ,
 * [preview] (nếu có), thanh kéo giữa hai hình nhỏ / to, nút viền đặt lại mặc định (mờ khi đang ở mặc định).
 * Trả về hàm cập nhật dòng phụ và nút theo nấc đang chọn.
 */
fun LinearLayout.sliderBlock(
    colors: SettingsColors,
    title: String,
    summary: String?,
    preview: View?,
    small: View,
    large: View,
    max: Int,
    value: Int,
    defaultValue: Int,
    resetLabel: String,
    summaryOf: ((Int) -> String)?,
    labelOf: ((Int) -> String)? = null,
    onChange: (Int) -> Unit,
) {
    val (block, summaryView) = textBlock(colors, title, summary)
    block.setPadding(context.dpi(24), context.dpi(20), context.dpi(24), 0)
    addView(block, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (preview != null) {
        addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dpi(24), context.dpi(16), context.dpi(24), 0)
        })
    }
    lateinit var reset: TextView
    lateinit var slider: SettingsSlider
    fun show(v: Int) {
        summaryOf?.let { summaryView.text = it(v); summaryView.visibility = View.VISIBLE }
        reset.isEnabled = v != defaultValue
        reset.alpha = if (v != defaultValue) 1f else 0.38f
    }
    slider = context.settingsSlider(colors, max, value, labelOf) {
        onChange(it)
        show(it)
    }
    // Bong bóng giá trị vẽ trên lớp phủ của cửa sổ (xem SettingsSlider.placeBubble), không cần tràn khỏi view.
    clipChildren = false
    val sliderRow = LinearLayout(context).apply {
        clipChildren = false
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Gboard (354 dp): hình nhỏ giữa ở 48 dp, rãnh 86–276 dp, hình to giữa ở 310 dp.
        setPadding(context.dpi(24), context.dpi(8), context.dpi(20), context.dpi(8))
        addView(small, LinearLayout.LayoutParams(context.dpi(48), context.dpi(48)))
        addView(slider, LinearLayout.LayoutParams(0, context.dpi(48), 1f).apply {
            marginStart = context.dpi(14)
            marginEnd = context.dpi(10)
        })
        addView(large, LinearLayout.LayoutParams(context.dpi(48), context.dpi(48)))
    }
    addView(sliderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    reset = TextView(context).apply {
        text = resetLabel
        gravity = Gravity.CENTER
        setTextColor(colors.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(28f)
            setStroke(context.dpi(1), colors.summary)
        }
        setOnClickListener {
            slider.progress = defaultValue
            onChange(defaultValue)
            show(defaultValue)
        }
    }
    addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(56)).apply {
        setMargins(context.dpi(16), context.dpi(4), context.dpi(16), context.dpi(16))
    })
    show(value)
}

/** Dòng có công tắc bên phải (như "Lựa chọn ưu tiên" của Gboard); chạm cả dòng cũng bật / tắt. Trả về công tắc. */
@Suppress("DEPRECATION")
fun LinearLayout.switchItem(
    colors: SettingsColors,
    title: String,
    summary: String? = null,
    value: Boolean,
    onChange: (Boolean) -> Unit,
): Switch {
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
    return toggle
}

private val CHECKED = intArrayOf(android.R.attr.state_checked)

/**
 * Nút tròn của công tắc kiểu Material 3 (như Gboard): bật thì to, tắt thì nhỏ.
 * Viền trong suốt dày hơn làm nút trông nhỏ hơn mà công tắc không đổi kích thước.
 */
internal fun View.switchThumb(colors: SettingsColors): StateListDrawable {
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
internal fun View.switchTrack(colors: SettingsColors): StateListDrawable {
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
    this.text = context.hanNomText(text)
    setTextColor(colors.accent)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setPadding(context.dpi(24), context.dpi(24), context.dpi(24), context.dpi(8))
})

/** Đoạn chữ giải thích. */
fun LinearLayout.paragraph(colors: SettingsColors, text: String): TextView = TextView(context).apply {
    this.text = context.hanNomText(text)
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
        text = context.hanNomText(fab.label)
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
            // Nhãn ẩn cho TalkBack, không hiện trên màn hình: không cần chữ Nôm (người dùng 2026-11-22), Hán Nôm thì đọc tiếng Việt.
            contentDescription = context.tr("Kéo để đổi thứ tự", "Drag to reorder")
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
