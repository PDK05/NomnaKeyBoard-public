package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Vùng viết tay một bảng, viết một hay nhiều chữ liền một dòng (trái sang phải). Mỗi lần nhấc bút gửi
 * lại toàn bộ nét cho [Listener.onStrokes] để nhận dạng lại cả dòng (nên viết thêm nét sau vẫn được:
 * 目 rồi thêm 氵 → 泪). Nét tự ẩn sau [VISIBLE_MS] (mờ dần trong [FADE_MS] cuối); mỗi lần chạm tay vào bảng
 * thì mọi nét đếm lại từ đầu (giữ nguyên độ mờ lúc chạm, không đậm lại), tính từ lúc nhấc tay (đang chạm thì không nét nào ẩn); nét đã ẩn vẫn được nhận dạng. Chữ nào đã ẩn hết nét thì [Listener.onFaded] chốt
 * nó (gợi ý đầu) và các nét đó bị bỏ khỏi bảng, phần còn lại nhận dạng lại.
 */
@SuppressLint("ViewConstructor")
class HandwritingView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** Nét của chữ đang viết, toạ độ trong bảng; [cell] là cạnh ngắn của bảng. */
        fun onStrokes(strokes: List<FloatArray>, cell: Float)
        /**
         * Các nét [hidden] (chỉ số trong [cellStrokes]) đã ẩn. Trả về các nét đã chốt thành chữ, bảng bỏ
         * chúng đi; rỗng thì chưa chốt được (vd. chưa nhận dạng xong), bảng hỏi lại sau [RETRY_MS].
         */
        fun onFaded(hidden: Set<Int>): Set<Int>
    }

    var theme = KeyboardTheme.of(context)

    /** Mỗi nét là dãy x0, y0, x1, y1… trong toạ độ của view. */
    private val strokes = ArrayList<FloatArray>()
    /** Lúc vẽ xong từng nét ([SystemClock.uptimeMillis]), song song với [strokes]. */
    private val drawnAt = ArrayList<Long>()
    /** Độ đậm từng nét lúc bắt đầu đếm (1 với nét mới; chạm tay lúc nét đang mờ thì giữ độ mờ đó). */
    private val level = ArrayList<Float>()
    private var current: FloatArrayBuilder? = null
    /** Giữ nguyên nét, không ẩn (đang xem bảng gợi ý). */
    private var kept = false
    /** Lúc bắt đầu chạm tay ([SystemClock.uptimeMillis]); đang chạm thì đồng hồ ẩn nét đứng ở đây. */
    private var touchStart: Long? = null

    /** Đồng hồ tính thời gian ẩn nét: đứng yên trong lúc chạm tay. */
    private fun clock() = touchStart ?: SystemClock.uptimeMillis()

    /** Đếm lại từ đầu thời gian hiện của mọi nét, tính từ lúc [at]. */
    private fun restartAll(at: Long) = drawnAt.indices.forEach {
        level[it] = inkAlpha(it, clock())
        drawnAt[it] = at
    }

    /** Nhấc tay: mọi nét đếm lại [VISIBLE_MS] từ bây giờ. */
    private fun endTouch() {
        if (touchStart == null) return
        touchStart = null
        // Độ mờ đã giữ lúc chạm tay; chỉ đếm lại thời gian.
        val now = SystemClock.uptimeMillis()
        drawnAt.indices.forEach { drawnAt[it] = now }
    }

    private companion object {
        /** Nét hiện chừng này rồi ẩn. */
        const val VISIBLE_MS = 4000L
        /** Mờ dần trong chừng này cuối thời gian hiện: bằng [VISIBLE_MS] nên nhấc tay là bắt đầu mờ. */
        const val FADE_MS = 4000L
        /** Có nét đã ẩn mà chưa chốt được thì hỏi lại sau chừng này. */
        const val RETRY_MS = 150L
    }

    /** Phần còn lại (0..1) của nét bắt đầu đếm lúc [at]: 1 tới khi bắt đầu mờ, về 0 trong [FADE_MS] cuối. */
    private fun remaining(at: Long, now: Long): Float = ((at + VISIBLE_MS - now).toFloat() / FADE_MS).coerceIn(0f, 1f)

    /** Độ đậm (0..1) của nét thứ [i]. */
    private fun inkAlpha(i: Int, now: Long): Float = if (kept) 1f else level[i] * remaining(drawnAt[i], now)

    /** Hẹn vẽ lại khi cần: mỗi khung hình lúc có nét đang mờ, không thì đúng lúc nét kế bắt đầu mờ. */
    private val tick = Runnable { invalidate() }

    private fun schedule(now: Long) {
        removeCallbacks(tick)
        if (kept || touchStart != null || drawnAt.isEmpty()) return
        val hidden = drawnAt.indices.filter { inkAlpha(it, now) <= 0f }.toSet()
        if (hidden.isNotEmpty()) {
            val done = listener.onFaded(hidden)
            if (done.isNotEmpty()) {
                remove(done)
                return
            }
        }
        val next = drawnAt.map { it + VISIBLE_MS - FADE_MS - now }.filter { it >= 0 }.minOrNull()
        when {
            drawnAt.indices.any { i -> remaining(drawnAt[i], now).let { it > 0f && it < 1f } && level[i] > 0f } -> postInvalidateOnAnimation()
            next != null -> postDelayed(tick, next)
            hidden.isNotEmpty() -> postDelayed(tick, RETRY_MS)
        }
    }

    /** Bỏ các nét [done] đã chốt thành chữ; còn nét thì nhận dạng lại phần còn lại. */
    private fun remove(done: Set<Int>) {
        for (i in done.filter { it in strokes.indices }.sortedDescending()) {
            strokes.removeAt(i)
            drawnAt.removeAt(i)
            level.removeAt(i)
        }
        invalidate()
        if (strokes.isNotEmpty()) listener.onStrokes(cellStrokes(), cellSize)
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = context.dp(4f)
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(6f)), 0f)
    }
    private val roboto = context.robotoTypeface()
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = roboto
    }
    /** Bàn phím đang dùng: chữ gợi ý "Viết chữ vào đây" theo ngôn ngữ của nó. */
    var style = KeyboardStyle.HAN_NOM
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }
    private val path = Path()

    val hasInk get() = strokes.isNotEmpty()
    val strokeCount get() = strokes.size

    fun clear() {
        strokes.clear()
        drawnAt.clear()
        level.clear()
        current = null
        touchStart = null
        removeCallbacks(tick)
        invalidate()
    }

    /** [keep]: hiện lại và giữ nguyên mọi nét (vd. đang xem bảng gợi ý); thôi giữ thì nét hiện thêm [VISIBLE_MS]. */
    fun keepInk(keep: Boolean) {
        if (kept == keep) return
        kept = keep
        if (!keep) {
            val now = SystemClock.uptimeMillis()
            drawnAt.indices.forEach { drawnAt[it] = now }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    /** Nét hiện tại (để nhận dạng). */
    fun cellStrokes(): List<FloatArray> = strokes.toList()

    /** Chiều cao bảng viết (cỡ một chữ khi viết cả dòng). */
    val cellSize get() = minOf(width, height).toFloat()

    /** Ngón đang vẽ (ngón chạm đầu tiên); các ngón chạm thêm bị bỏ qua, không nối nét sang chỗ khác. */
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    /** Xong nét đang vẽ tại ([x], [y]) rồi gửi nhận dạng. */
    private fun finishStroke(x: Float, y: Float) {
        val b = current ?: return
        b.add(x, y)
        strokes.add(b.toArray())
        drawnAt.add(SystemClock.uptimeMillis())
        level.add(1f)
        current = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
        listener.onStrokes(cellStrokes(), cellSize)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStart = SystemClock.uptimeMillis().also(::restartAll)
                removeCallbacks(tick)
                pointerId = e.getPointerId(0)
                current = FloatArrayBuilder().apply { add(e.x, e.y) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val b = current ?: return true
                val i = e.findPointerIndex(pointerId)
                if (i < 0) return true
                for (h in 0 until e.historySize) b.add(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                b.add(e.getX(i), e.getY(i))
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Nhấc ngón đang vẽ khi ngón khác còn chạm: xong nét (vẫn đang chạm nên nét chưa đếm giờ ẩn).
                val i = e.actionIndex
                if (e.getPointerId(i) == pointerId) finishStroke(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP -> {
                endTouch()
                if (e.getPointerId(e.actionIndex) == pointerId) finishStroke(e.getX(e.actionIndex), e.getY(e.actionIndex))
                else invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                endTouch()
                current = null
                pointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        // Khung bảng viết (nét đứt) và gợi ý khi chưa viết.
        guide.color = theme.specialKey
        val inset = context.dp(6f)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, context.dp(10f), context.dp(10f), guide)
        if (strokes.isEmpty() && current == null) {
            hint.color = theme.hintText
            hint.textSize = context.dp(15f)
            val text = style.say("Viết chữ vào đây!", "𢪏𡨸𠓨低！", "Write here!")
            // Chữ Hán Nôm ngoài BMP (𢪏, 𡨸) cần phông Hán Nôm đi kèm.
            hint.typeface = if (text.any { it.isHighSurrogate() }) context.hanNomTypeface() ?: roboto else roboto
            canvas.drawText(text, width / 2f, height / 2f, hint)
        }
        val now = clock()
        for ((i, s) in strokes.withIndex()) {
            val a = inkAlpha(i, now)
            if (a <= 0f) continue
            ink.color = theme.text
            ink.alpha = (a * 255).toInt()
            drawStroke(canvas, s, s.size)
        }
        ink.color = theme.text
        current?.let { drawStroke(canvas, it.data, it.size) }
        // Gọi listener (có thể xoá nét) sau khi vẽ xong khung này.
        post { schedule(clock()) }
    }

    private fun drawStroke(canvas: Canvas, s: FloatArray, n: Int) {
        if (n < 2) return
        if (n == 2) {
            canvas.drawPoint(s[0], s[1], ink)
            return
        }
        path.reset()
        path.moveTo(s[0], s[1])
        // Nối trung điểm bằng đường cong bậc hai cho nét mượt.
        var i = 2
        while (i + 2 < n) {
            path.quadTo(s[i], s[i + 1], (s[i] + s[i + 2]) / 2, (s[i + 1] + s[i + 3]) / 2)
            i += 2
        }
        path.lineTo(s[n - 2], s[n - 1])
        canvas.drawPath(path, ink)
    }

    private class FloatArrayBuilder {
        var data = FloatArray(64)
        var size = 0

        fun add(x: Float, y: Float) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }

        fun toArray() = data.copyOf(size)
    }
}
