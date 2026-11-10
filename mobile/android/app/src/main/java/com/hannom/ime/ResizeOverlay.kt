package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Chế độ "Kích thước" như Resize của Gboard: phủ mờ lên thanh gợi ý và bàn phím, khung bo tròn viền màu nhấn bao phần
 * bàn phím ([setFrame]). Cạnh trên và cạnh dưới kéo được để co giãn (cạnh kia đứng yên): kéo cạnh dưới lên thì bàn
 * phím thấp đi và chừa khoảng trống dưới đáy (cho máy bị thanh điều hướng che). Bàn phím một tay có thêm cạnh trái,
 * cạnh phải và bốn góc (đổi bề rộng). Nút ✥ dời cả bàn phím (một tay thì cả sang ngang), giữ cỡ.
 *
 * Như Gboard, lúc kéo chỉ khung di chuyển (tràn được lên trên ứng dụng hay xuống dưới đáy), bàn phím đứng yên; thả tay
 * thì [InputRoot] mới đổi bàn phím theo khung. Vì vậy view này phủ cả [InputRoot], chỉ làm mờ vùng bàn phím và vùng
 * trong khung. Chỉ tô đỏ khi kéo chạm giới hạn: kéo cạnh / góc thì đỏ cả khung; kéo ✥ thì
 * đỏ cạnh phía đang dời tới. Kéo báo độ dời theo px qua [Listener], "Đặt lại" và "Xong"
 * cũng vậy; [InputRoot] tính khung mới, đổi kích thước và lưu.
 */
@SuppressLint("ViewConstructor")
class ResizeOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    /** Các hàm kéo trả về `true` nếu bị chặn ở giới hạn (đổi ít hơn độ dời được báo). */
    interface Listener {
        /** Bắt đầu kéo (cạnh hay ✥): các hàm kéo sau đó chỉ dời khung, tới [onResizeEnd] mới đổi bàn phím. */
        fun onResizeStart()
        /** Kéo cạnh trên [dy] px (âm là lên: bàn phím cao thêm, cạnh dưới đứng yên). */
        fun onResizeTop(dy: Float): Boolean
        /** Kéo cạnh dưới [dy] px (âm là lên: bàn phím thấp đi, khoảng trống dưới đáy rộng thêm, cạnh trên đứng yên). */
        fun onResizeBottom(dy: Float): Boolean
        /** Bàn phím một tay: kéo cạnh trái / phải [dx] px (âm là sang trái), cạnh kia đứng yên. */
        fun onResizeLeft(dx: Float): Boolean
        fun onResizeRight(dx: Float): Boolean
        /**
         * Kéo nút ✥: dời cả bàn phím [dy] px (âm là lên), một tay thì cả [dx] px sang ngang; giữ cỡ. Trả về chiều ngang /
         * chiều dọc có bị chặn ở giới hạn không.
         */
        fun onResizeMove(dx: Float, dy: Float): Pair<Boolean, Boolean>
        /** Thả tay: đổi bàn phím theo khung. */
        fun onResizeEnd()
        fun onResizeReset()
        fun onResizeDone()
    }

    private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    var theme = KeyboardTheme.of(context)
        set(v) {
            field = v
            styleButtons()
            invalidate()
        }

    /** Khung (px, toạ độ view này); [sides]: có cạnh trái / phải và góc kéo được (một tay). */
    private val box = RectF()
    private var sides = false
    /** Vùng bàn phím hiện tại theo chiều dọc (làm mờ cả bề ngang, cả cột nút một tay). */
    private var dimTop = 0f
    private var dimBottom = 0f

    /** [InputRoot] đặt khung: theo bàn phím sau mỗi lần xếp, hay khung xem trước lúc đang kéo. */
    fun setFrame(frame: RectF, dimTop: Float, dimBottom: Float, sides: Boolean) {
        if (frame == box && dimTop == this.dimTop && dimBottom == this.dimBottom && sides == this.sides) return
        val relayout = sides != this.sides || vertical(frame.width()) != vertical(box.width())
        box.set(frame)
        this.dimTop = dimTop
        this.dimBottom = dimBottom
        this.sides = sides
        // Đổi cách xếp ba nút thì đo lại; không thì chỉ dời hàng nút theo khung.
        if (relayout) requestLayout() else placeRow()
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val handleZone = context.dp(36f)
    /** Cạnh đang kéo (góc là hai cạnh). */
    private var active: Set<Edge> = emptySet()
    /** Cạnh đang chạm giới hạn trong lần kéo này (tô đỏ). */
    private val limited = HashSet<Edge>()
    private var lastX = 0f
    private var lastY = 0f
    /** Đang kéo (đã báo [Listener.onResizeStart], chưa báo [Listener.onResizeEnd]). */
    private var dragging = false

    // Biểu tượng vẽ (như Gboard), không dùng ký tự ⟲ ✓ ✥ của phông: nét đều, cân với chữ.
    private val resetLabel = label("Đặt lại")
    private val doneLabel = label("Xong")
    private val reset = button(R.drawable.ic_reset, resetLabel) { listener.onResizeReset() }
    private val move = button(R.drawable.ic_move, null) {}.apply {
        setOnTouchListener { _, e -> moveDrag(e) }
    }
    private val done = button(R.drawable.ic_check, doneLabel) { listener.onResizeDone() }

    /** Bàn phím đang dùng: chữ trên nút "Đặt lại" / "Xong" theo ngôn ngữ của nó. */
    var style = KeyboardStyle.VIETNAMESE
        set(v) {
            field = v
            resetLabel.text = context.hanNomText(v.say("Đặt lại", "撻徠", "Reset"))
            doneLabel.text = context.hanNomText(v.say("Xong", "歱", "Done"))
        }
    /** Ba nút; khung hẹp (một tay) thì xếp dọc như Gboard. */
    private val row = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        val h = context.dpi(MOVE.toInt())
        addView(reset, LinearLayout.LayoutParams(context.dpi(BUTTON.toInt()), h))
        addView(move, LinearLayout.LayoutParams(h, h))
        addView(done, LinearLayout.LayoutParams(context.dpi(BUTTON.toInt()), h))
    }

    init {
        setWillNotDraw(false)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        styleButtons()
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
    }

    /** Nút bo tròn: biểu tượng [icon] và chữ [label] (nếu có) nằm giữa nút. */
    private fun button(icon: Int, label: TextView?, onClick: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(context.dpi(22), context.dpi(22)))
        if (label != null) {
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dpi(10)
            })
        }
        setOnClickListener { onClick() }
    }

    private fun styleButtons() {
        for (label in listOf(resetLabel, doneLabel)) label.setTextColor(theme.text)
        for (b in listOf(reset, move, done)) {
            (b.getChildAt(0) as ImageView).setColorFilter(theme.text)
            b.background = GradientDrawable().apply {
                cornerRadius = context.dp(22f)
                setColor(if (b === move) theme.specialKey else theme.accent and 0x00FFFFFF or 0x30000000)
            }
        }
    }

    /** Một tay xếp dọc như Gboard; không thì hàng ngang, trừ khi khung hẹp hơn chừng 120 + 44 + 120 dp và lề. */
    private fun vertical(frameWidth: Float) = sides || frameWidth < context.dp(BUTTON * 2 + MOVE + 3 * GAP + 24f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val vertical = vertical(box.width())
        row.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val gap = context.dpi(GAP.toInt())
        (move.layoutParams as LinearLayout.LayoutParams).apply {
            if (vertical) setMargins(0, gap, 0, gap) else setMargins(gap, 0, gap, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, r: Int, bottom: Int) {
        super.onLayout(changed, left, top, r, bottom)
        placeRow()
    }

    /** Ba nút nằm giữa khung (không phải giữa cả bề ngang khi một tay). */
    private fun placeRow() {
        val cx = box.centerX().toInt()
        val cy = box.centerY().toInt()
        row.layout(cx - row.measuredWidth / 2, cy - row.measuredHeight / 2, cx + row.measuredWidth / 2, cy + row.measuredHeight / 2)
    }

    override fun onDraw(canvas: Canvas) {
        // Phủ mờ cả thanh gợi ý và bàn phím (như Gboard làm nhạt phím khi chỉnh kích thước), cả cột nút một tay; khung
        // tràn ra ngoài bàn phím thì phủ thêm phần trong khung.
        paint.style = Paint.Style.FILL
        paint.color = theme.background and 0x00FFFFFF or 0xB0000000.toInt()
        canvas.drawRect(0f, dimTop, width.toFloat(), dimBottom, paint)
        val stroke = context.dp(2f)
        val r = context.dp(24f)
        frame.set(box.left + stroke, box.top + stroke, box.right - stroke, box.bottom - stroke)
        canvas.save()
        canvas.clipOutRect(0f, dimTop, width.toFloat(), dimBottom)
        canvas.drawRoundRect(frame, r, r, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        // Kéo cạnh / góc chạm giới hạn thì tô đỏ cả khung (kéo ✥ thì chỉ cạnh phía dời tới).
        val whole = active.isNotEmpty() && limited.any { it in active }
        paint.color = if (whole) ACTIVE else theme.accent
        canvas.drawRoundRect(frame, r, r, paint)
        // Cạnh chạm giới hạn: tô đỏ đoạn thẳng của cạnh đó.
        paint.color = ACTIVE
        for (e in limited) {
            when (e) {
                Edge.TOP -> canvas.drawLine(frame.left + r, frame.top, frame.right - r, frame.top, paint)
                Edge.BOTTOM -> canvas.drawLine(frame.left + r, frame.bottom, frame.right - r, frame.bottom, paint)
                Edge.LEFT -> canvas.drawLine(frame.left, frame.top + r, frame.left, frame.bottom - r, paint)
                Edge.RIGHT -> canvas.drawLine(frame.right, frame.top + r, frame.right, frame.bottom - r, paint)
            }
        }
        // Tay nắm: vạch giữa cạnh trên, dưới (một tay thêm cạnh trái, phải) và cung ở bốn góc (một tay).
        paint.style = Paint.Style.FILL
        val half = context.dp(20f)
        val thick = context.dp(2.5f)
        val inset = context.dp(14f)
        val cx = frame.centerX()
        val cy = frame.centerY()
        fun bar(e: Edge, l: Float, t: Float, rr: Float, b: Float) {
            paint.color = if (whole || e in limited) ACTIVE else theme.accent
            canvas.drawRoundRect(l, t, rr, b, thick, thick, paint)
        }
        bar(Edge.TOP, cx - half, box.top + inset - thick, cx + half, box.top + inset + thick)
        bar(Edge.BOTTOM, cx - half, box.bottom - inset - thick, cx + half, box.bottom - inset + thick)
        if (!sides) return
        bar(Edge.LEFT, frame.left + inset - thick, cy - half, frame.left + inset + thick, cy + half)
        bar(Edge.RIGHT, frame.right - inset - thick, cy - half, frame.right - inset + thick, cy + half)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(4f)
        paint.strokeCap = Paint.Cap.ROUND
        val arc = context.dp(18f)
        val corners = listOf(
            RectF(frame.left + inset, frame.top + inset, frame.left + inset + 2 * arc, frame.top + inset + 2 * arc) to 180f,
            RectF(frame.right - inset - 2 * arc, frame.top + inset, frame.right - inset, frame.top + inset + 2 * arc) to 270f,
            RectF(frame.right - inset - 2 * arc, frame.bottom - inset - 2 * arc, frame.right - inset, frame.bottom - inset) to 0f,
            RectF(frame.left + inset, frame.bottom - inset - 2 * arc, frame.left + inset + 2 * arc, frame.bottom - inset) to 90f,
        )
        for ((oval, start) in corners) {
            paint.color = if (whole) ACTIVE else theme.accent
            canvas.drawArc(oval, start, 90f, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun startDrag(e: MotionEvent) {
        lastX = e.rawX
        lastY = e.rawY
        clearLimits()
        dragging = true
        listener.onResizeStart()
    }

    private fun endDrag() {
        clearLimits()
        if (!dragging) return
        dragging = false
        listener.onResizeEnd()
    }

    /** Kéo nút ✥: báo độ dời theo toạ độ màn hình (khung dời theo, nút cũng vậy). */
    private fun moveDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startDrag(e)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastX
                val dy = e.rawY - lastY
                lastX = e.rawX
                lastY = e.rawY
                val (stopX, stopY) = listener.onResizeMove(dx, dy)
                // Chạm giới hạn: tô đỏ cạnh phía đang dời tới. Không dời theo chiều nào thì giữ nguyên chiều đó.
                if (dx != 0f) mark(if (dx < 0) Edge.LEFT else Edge.RIGHT, if (dx < 0) Edge.RIGHT else Edge.LEFT, stopX)
                if (dy != 0f) mark(if (dy < 0) Edge.TOP else Edge.BOTTOM, if (dy < 0) Edge.BOTTOM else Edge.TOP, stopY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    /** Đặt [edge] chạm giới hạn hay không ([stop]); dời theo hướng ngược lại thì cạnh [opposite] hết chạm. */
    private fun mark(edge: Edge, opposite: Edge? = null, stop: Boolean) {
        val changed = (opposite != null && limited.remove(opposite)) or
            (if (stop) limited.add(edge) else limited.remove(edge))
        if (changed) invalidate()
    }

    private fun clearLimits() {
        if (limited.isEmpty()) return
        limited.clear()
        invalidate()
    }

    /** Cạnh (góc là hai cạnh) gần chỗ chạm ([x], [y]) trong khoảng [handleZone], cả trong lẫn ngoài khung. */
    private fun edgesAt(x: Float, y: Float): Set<Edge> {
        val out = HashSet<Edge>()
        val inX = x > box.left - handleZone && x < box.right + handleZone
        val inY = y > box.top - handleZone && y < box.bottom + handleZone
        if (inX && abs(y - box.top) < handleZone) out.add(Edge.TOP)
        if (inX && abs(y - box.bottom) < handleZone) out.add(Edge.BOTTOM)
        if (sides && inY && abs(x - box.left) < handleZone) out.add(Edge.LEFT)
        if (sides && inY && abs(x - box.right) < handleZone) out.add(Edge.RIGHT)
        return out
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = edgesAt(e.x, e.y)
                if (active.isNotEmpty()) startDrag(e)
            }
            MotionEvent.ACTION_MOVE -> if (active.isNotEmpty()) {
                val dx = e.rawX - lastX
                val dy = e.rawY - lastY
                lastX = e.rawX
                lastY = e.rawY
                // Cạnh không dời trong lần này thì giữ nguyên trạng thái chạm giới hạn.
                fun drag(edge: Edge, d: Float, resize: (Float) -> Boolean) {
                    if (edge in active && d != 0f) mark(edge, stop = resize(d))
                }
                drag(Edge.TOP, dy, listener::onResizeTop)
                drag(Edge.BOTTOM, dy, listener::onResizeBottom)
                drag(Edge.LEFT, dx, listener::onResizeLeft)
                drag(Edge.RIGHT, dx, listener::onResizeRight)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = emptySet()
                endDrag()
                invalidate()
            }
        }
        // Chạm chỗ khác (trong hay ngoài khung): nuốt luôn, không để phím phía dưới nhận.
        return true
    }

    private companion object {
        /** Cỡ nút (dp): "Đặt lại" / "Xong" rộng [BUTTON], nút ✥ vuông [MOVE], cách nhau [GAP]. */
        const val BUTTON = 120f
        const val MOVE = 44f
        const val GAP = 12f
        /** Màu cạnh / góc đang kéo (như Gboard). */
        val ACTIVE = Color.parseColor("#E5484D")
    }
}
