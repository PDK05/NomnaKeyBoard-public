package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Các tiện ích của bàn phím: ghim lên thanh công cụ hay mở từ bảng tiện ích (nút ⊞), như Gboard. */
enum class ToolId(val label: String, val icon: Int) {
    CLIPBOARD("Bảng nhớ tạm", R.drawable.ic_clipboard),
    EMOJI("Emoji", R.drawable.ic_emoji),
    DICTIONARY("Từ điển", R.drawable.ic_dictionary),
    RESIZE("Kích thước", R.drawable.ic_resize),
    ONE_HAND("Một tay", R.drawable.ic_one_hand),
    TEXT_EDIT("Chỉnh sửa văn bản", R.drawable.ic_text_edit),
    SETTINGS("Cài đặt", R.drawable.ic_settings);

    /** Tên theo bàn phím đang dùng; bàn phím chưa có tên riêng cho tiện ích thì mặc nhiên tên tiếng Anh. */
    fun label(style: KeyboardStyle): String {
        val en = when (this) {
            CLIPBOARD -> "Clipboard"
            EMOJI -> "Emoji"
            RESIZE -> "Resize"
            ONE_HAND -> "One-handed"
            DICTIONARY -> "Dictionary"
            TEXT_EDIT -> "Text editing"
            SETTINGS -> "Settings"
        }
        val nom = HAN_NOM_LABELS[this] ?: en
        val vi = when (this) {
            EMOJI -> "Biểu tượng cảm xúc"
            RESIZE -> "Đổi kích thước"
            else -> label
        }
        return style.say(vi, nom, en)
    }

    companion object {
        /** Tên Hán Nôm của tiện ích; tiện ích chưa có tên Hán Nôm thì dùng tên tiếng Anh. */
        private val HAN_NOM_LABELS = mapOf(
            CLIPBOARD to "榜𢖵暫",
            EMOJI to "表象感觸",
            RESIZE to "𢷮戟𡱩",
            ONE_HAND to "𠬠𢬣",
            DICTIONARY to "辭典",
            TEXT_EDIT to "整𢯢文本",
            SETTINGS to "掑撻",
        )
    }
}

/**
 * Chỗ của các tiện ích: [row] ghim trên thanh công cụ (sau nút ⊞), [corner] ở góc phải thanh (nền tròn, mặc định
 * Cài đặt), [grid] các ô còn lại trong bảng tiện ích theo thứ tự. Mỗi tiện ích ở đúng một chỗ.
 */
data class ToolLayout(val row: List<ToolId>, val corner: ToolId?, val grid: List<ToolId>) {
    fun without(id: ToolId) = ToolLayout(row - id, corner.takeIf { it != id }, grid - id)

    /** Đặt [id] (đã bỏ khỏi bố cục) vào [slot]; thả vào góc thì nút góc cũ về đầu lưới. */
    fun with(id: ToolId, slot: ToolSlot): ToolLayout = when (slot) {
        is ToolSlot.Row -> copy(row = row.toMutableList().apply { add(slot.index.coerceIn(0, size), id) })
        is ToolSlot.Corner -> ToolLayout(row, id, listOfNotNull(corner) + grid)
        is ToolSlot.Grid -> copy(grid = grid.toMutableList().apply { add(slot.index.coerceIn(0, size), id) })
    }

    /** Chỗ hiện tại của [id]. */
    fun slotOf(id: ToolId): ToolSlot? = when (id) {
        corner -> ToolSlot.Corner
        in row -> ToolSlot.Row(row.indexOf(id))
        in grid -> ToolSlot.Grid(grid.indexOf(id))
        else -> null
    }

    companion object {
        /** Số nút ghim tối đa trong khung (chưa kể nút ⊞ và nút góc). */
        const val MAX_ROW = 5
        val DEFAULT = of(listOf(ToolId.CLIPBOARD, ToolId.EMOJI, ToolId.DICTIONARY), ToolId.SETTINGS, emptyList())

        /** Chuẩn hoá: bỏ trùng, khung quá chỗ thì dư ra lưới, tiện ích chưa có chỗ thêm vào cuối lưới. */
        fun of(row: List<ToolId>, corner: ToolId?, grid: List<ToolId>): ToolLayout {
            val r = row.distinct().filter { it != corner }.take(MAX_ROW)
            val used = r.toSet() + setOfNotNull(corner)
            return ToolLayout(r, corner, (grid + ToolId.entries).distinct().filter { it !in used })
        }
    }
}

/** Chỗ thả khi kéo một tiện ích. */
sealed interface ToolSlot {
    data class Row(val index: Int) : ToolSlot
    data object Corner : ToolSlot
    data class Grid(val index: Int) : ToolSlot
}

/**
 * Bảng tiện ích (nút ⊞ của Gboard), thay chỗ bàn phím: lưới các tiện ích chưa ghim, mỗi ô bo tròn
 * (icon + tên), chia trang vuốt ngang, chấm trang và nút ✎ ở dưới. Chạm để dùng. Nút ✎ (hay giữ một ô) vào chế độ
 * sửa: "Giữ và kéo để tuỳ chỉnh", lưới trong khung viền màu nhấn, cuộn dọc; kéo thả do [InputRoot] lo.
 */
@SuppressLint("ViewConstructor")
class ToolsPanel(
    context: Context,
    private val onUse: (ToolId) -> Unit,
    private val onEdit: () -> Unit,
    private val onDrag: (ToolId) -> Unit,
) : LinearLayout(context) {
    /** Bên ngoài (bộ gõ) nghe: dùng một tiện ích, bố cục vừa kéo thả xong. */
    interface Listener {
        fun onToolUse(tool: ToolId)
        fun onToolsChanged(layout: ToolLayout)
    }

    var theme = KeyboardTheme.of(context)
    var editing = false
        private set
    private val tiles = HashMap<ToolId, View>()
    /** Bàn phím đang dùng: tên các ô theo ngôn ngữ của nó ([ToolId.label]). Đổi thì dựng lại ô. */
    var style = KeyboardStyle.VIETNAMESE
        set(v) {
            if (field == v) return
            field = v
            tiles.clear()
            title.text = titleText()
        }

    private fun titleText() = context.hanNomText(style.say("Giữ và kéo để tuỳ chỉnh", "𡨺吧𢫃抵隨整", "Hold and drag to customize"))

    private val title = TextView(context).apply {
        text = titleText()
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, context.dpi(10), 0, context.dpi(10))
    }
    private val pager = Pager(context) { updateDots() }
    private val editGrid = TileGrid(context, minRows = 0)
    private val editFrame = FrameLayout(context).apply {
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(editGrid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private val dots = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val editButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_edit)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Tuỳ chỉnh"
        setOnClickListener { onEdit() }
    }
    private val footer = FrameLayout(context).apply {
        addView(dots, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(editButton, FrameLayout.LayoutParams(context.dpi(64), context.dpi(34), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = context.dpi(16)
        })
    }

    init {
        orientation = VERTICAL
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(editFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = context.dpi(12)
            marginEnd = context.dpi(12)
            bottomMargin = context.dpi(8)
        })
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(FOOTER)))
    }

    /** Màu đổi thì dựng lại các ô. */
    fun applyTheme() {
        tiles.clear()
        setBackgroundColor(theme.background)
        title.setTextColor(theme.text)
        editButton.setColorFilter(theme.text)
        editButton.background = GradientDrawable().apply {
            cornerRadius = context.dp(17f)
            setStroke(context.dpi(1).coerceAtLeast(1), theme.hintText)
        }
        editFrame.background = GradientDrawable().apply {
            cornerRadius = context.dp(24f)
            setStroke(context.dpi(2), theme.accent)
        }
    }

    /**
     * Dựng bảng theo [layout]: thường thì các trang lưới, [editing] thì lưới sửa. Ô [hidden] (đang kéo) để trống
     * chỗ; [animate]: các ô trượt tới chỗ mới.
     */
    fun show(layout: ToolLayout, editing: Boolean, hidden: ToolId? = null, animate: Boolean = false) {
        this.editing = editing
        if (tiles.isEmpty()) applyTheme()
        title.visibility = if (editing) View.VISIBLE else View.GONE
        pager.visibility = if (editing) View.GONE else View.VISIBLE
        editFrame.visibility = if (editing) View.VISIBLE else View.GONE
        footer.visibility = if (editing) View.GONE else View.VISIBLE
        if (animate) TransitionManager.beginDelayedTransition(editGrid, ChangeBounds().setDuration(160))
        editGrid.removeAllViews()
        pager.clear()
        if (editing) {
            for (id in layout.grid) editGrid.addView(tile(id, hidden))
        } else {
            val rows = rowsPerPage()
            for (page in layout.grid.chunked(rows * TileGrid.columnsFor(context))) {
                pager.addPage(TileGrid(context, minRows = rows).apply { page.forEach { addView(tile(it, hidden)) } })
            }
            updateDots()
        }
    }

    /** Số hàng một trang: tối đa [MAX_ROWS], ít hơn khi bảng thấp (màn hình ngang) để ô không bị cắt. */
    private fun rowsPerPage(): Int {
        val panel = layoutParams?.height ?: 0
        if (panel <= 0) return MAX_ROWS
        val gap = context.dpi(TileGrid.V_GAP)
        val room = panel - context.dpi(FOOTER) - 2 * context.dpi(TileGrid.PADDING)
        return ((room + gap) / (context.dpi(TileGrid.HEIGHT) + gap)).coerceIn(1, MAX_ROWS)
    }

    /** Ô của [id] (dùng lại để các ô trượt mượt khi kéo thả), đã gỡ khỏi chỗ cũ. */
    private fun tile(id: ToolId, hidden: ToolId?): View {
        val v = tiles.getOrPut(id) { makeTile(id) }
        (v.parent as? ViewGroup)?.removeView(v)
        v.visibility = if (id == hidden) View.INVISIBLE else View.VISIBLE
        return v
    }

    /** Nút nổi theo ngón tay khi kéo: chỉ icon trong nền tròn. */
    fun makeFloating(id: ToolId): View = ImageView(context).apply {
        setImageResource(id.icon)
        setColorFilter(theme.text)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.pressedKey)
        }
        elevation = context.dp(8f)
        contentDescription = id.label(style)
    }

    /** Ô bo tròn: icon + tên một dòng. */
    private fun makeTile(id: ToolId): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpi(14), 0, context.dpi(12), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(TileGrid.HEIGHT / 2f)
            setColor(theme.composeBox)
        }
        addView(ImageView(context).apply {
            setImageResource(id.icon)
            setColorFilter(theme.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(context.dpi(22), context.dpi(22)))
        addView(TextView(context).apply {
            // Tên Hán Nôm có chữ ngoài BMP (𢷮, 𡱩, 𠬠, 𢬣...): dùng phông Hán Nôm đi kèm.
            text = context.hanNomText(id.label(style))
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpi(12) })
        contentDescription = id.label(style)
        setOnClickListener { if (!editing) onUse(id) }
        setOnLongClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onDrag(id)
            true
        }
    }

    /** Vị trí chèn trong lưới sửa ứng với điểm ([x], [y] theo toạ độ cửa sổ). */
    fun gridIndexAt(x: Float, y: Float): Int {
        val at = IntArray(2).also { editGrid.getLocationInWindow(it) }
        return editGrid.cellAt(x - at[0], y - at[1])
    }

    private fun updateDots() {
        dots.removeAllViews()
        if (pager.pages < 2) return
        repeat(pager.pages) { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == pager.page) theme.text else theme.hintText)
                }
                alpha = if (i == pager.page) 1f else 0.5f
            }, LayoutParams(context.dpi(6), context.dpi(6)).apply {
                marginStart = context.dpi(3)
                marginEnd = context.dpi(3)
            })
        }
    }

    companion object {
        /** Số hàng nhiều nhất một trang (như Gboard). */
        private const val MAX_ROWS = 3
        /** Chiều cao dải chấm trang và nút ✎ (dp). */
        private const val FOOTER = 48
    }
}

/** Lưới các ô cao bằng nhau, số cột theo [columnsFor]; ít nhất [minRows] hàng (trang lưới không co lại khi ít ô). */
class TileGrid(context: Context, private val minRows: Int) : ViewGroup(context) {
    private val tileHeight = context.dpi(HEIGHT)
    private val hGap = context.dpi(H_GAP)
    private val vGap = context.dpi(V_GAP)
    private val columns get() = columnsFor(context)

    init {
        val pad = context.dpi(PADDING)
        setPadding(pad, pad, pad, pad)
    }

    private fun cellWidth(w: Int) = (w - paddingLeft - paddingRight - (columns - 1) * hGap) / columns

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = cellWidth(w)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY))
        }
        val rows = maxOf(minRows, (childCount + columns - 1) / columns)
        val h = paddingTop + paddingBottom + rows * tileHeight + (rows - 1).coerceAtLeast(0) * vGap
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth(width)
        val n = columns
        for (i in 0 until childCount) {
            val x = paddingLeft + (i % n) * (cw + hGap)
            val y = paddingTop + (i / n) * (tileHeight + vGap)
            getChildAt(i).layout(x, y, x + cw, y + tileHeight)
        }
    }

    /** Ô ứng với điểm ([x], [y] theo toạ độ lưới); có thể quá số ô, nơi gọi tự chặn. */
    fun cellAt(x: Float, y: Float): Int {
        val n = columns
        val col = ((x - paddingLeft + hGap / 2f) / (cellWidth(width) + hGap)).toInt().coerceIn(0, n - 1)
        val row = ((y - paddingTop + vGap / 2f) / (tileHeight + vGap)).toInt().coerceAtLeast(0)
        return row * n + col
    }

    companion object {
        /** Chiều cao một ô (dp). */
        const val HEIGHT = 44
        /** Khoảng cách giữa các cột, giữa các hàng và lề lưới (dp). */
        const val H_GAP = 12
        const val V_GAP = 10
        const val PADDING = 12

        /** Màn hình dọc 2 cột; màn hình ngang giữ ô gần bằng bề rộng lúc dọc nên có nhiều cột hơn. */
        fun columnsFor(context: Context): Int {
            val m = context.resources.displayMetrics
            val pad = context.dpi(PADDING)
            val gap = context.dpi(H_GAP)
            val cell = (minOf(m.widthPixels, m.heightPixels) - 2 * pad - gap) / 2
            return ((m.widthPixels - 2 * pad + gap) / (cell + gap)).coerceAtLeast(2)
        }
    }
}

/** Các trang xếp ngang, vuốt thì bật về trang gần nhất (hay trang kế khi vuốt đủ xa). */
private class Pager(context: Context, private val onPage: () -> Unit) : HorizontalScrollView(context) {
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    var page = 0
        private set
    val pages get() = strip.childCount
    private var downScroll = 0

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun clear() {
        strip.removeAllViews()
        page = 0
        scrollTo(0, 0)
    }

    fun addPage(v: View) = strip.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until strip.childCount) strip.getChildAt(i).layoutParams.width = w
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downScroll = scrollX
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val moved = scrollX - downScroll
            val step = when {
                moved > width / 5 -> 1
                moved < -width / 5 -> -1
                else -> 0
            }
            page = (downScroll / width.coerceAtLeast(1) + step).coerceIn(0, (pages - 1).coerceAtLeast(0))
            smoothScrollTo(page * width, 0)
            onPage()
        }
        return handled
    }

    /** Không trôi theo đà: luôn bật về đúng một trang. */
    override fun fling(velocityX: Int) {}
}
