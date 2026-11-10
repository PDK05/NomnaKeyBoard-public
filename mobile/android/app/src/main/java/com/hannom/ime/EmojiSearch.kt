package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tìm emoji, như Gboard (đo trên Gboard của AVD): hàng trên là nút quay lại (về bảng emoji) và tiêu đề; dưới là khung
 * trắng bo góc gồm các hàng kết quả cuộn ngang và ô tìm (kính lúp, chữ, nút xoá). Bàn phím thường nằm ngay dưới,
 * gõ vào ô tìm qua [connection] (bộ gõ trả nó thay cho ô nhập của ứng dụng khi [active]).
 */
@SuppressLint("ViewConstructor")
class EmojiSearchView(
    context: Context,
    /** Nút quay lại: về bảng emoji. */
    private val onBack: () -> Unit,
    /** Chạm một kết quả (đúng emoji đang hiện). */
    private val onPick: (String) -> Unit,
    /** Kết quả cho chữ đang tìm: các emoji đem hiện (đã theo màu da, giới tính chọn lần trước). */
    private val results: (String) -> List<String>,
    /** Bảng giữ ô của một emoji (hàng: giới tính / hướng, cột: màu da); null nếu không có bản khác. */
    private val tonesOf: (String) -> List<List<String>>?,
) : LinearLayout(context) {
    var theme = KeyboardTheme.of(context)
    /** Đang tìm: phím gõ vào ô tìm. */
    var active = false
        private set

    private val back = ImageView(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Quay lại bảng biểu tượng cảm xúc"
        setOnClickListener { onBack() }
    }
    /** Bàn phím đang dùng: tiêu đề và chữ mờ trong ô tìm theo nó. */
    var style = KeyboardStyle.VIETNAMESE
        set(v) {
            field = v
            title.text = context.hanNomText(v.say("Tìm biểu tượng cảm xúc", "尋表象感觸", "Search emoji"))
            this.field.hint = context.hanNomText(v.say(EmojiPanel.SEARCH_VI, EmojiPanel.SEARCH_NOM, EmojiPanel.SEARCH_EN))
        }
    private val title = TextView(context).apply {
        text = "Tìm biểu tượng cảm xúc"
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = context.robotoTypeface()
    }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    // Chọn một bản trong bảng giữ ô: chỉ gõ ra đúng bản đó (như chạm một kết quả); không đổi màu da chung, không
    // dựng lại danh sách kết quả.
    private val grid = ResultGrid(context, { onPick(it) }, tonesOf) { onPick(it) }
    private val scroll = PagedScroll(context) { grid.pageWidth to grid.pageCount }.apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Màn hình ngang có lề trái: kết quả cuộn qua lề tới sát mép khung.
        clipToPadding = false
        addView(grid)
        // Cuộn kết quả thì đóng bảng chọn bản.
        setOnScrollChangeListener { _, x, _, oldX, _ -> if (x != oldX) grid.closeTones() }
    }
    private val divider = View(context)
    private val searchIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_search)
        scaleType = ImageView.ScaleType.CENTER
    }
    val field = SearchField(context)
    private val clear = ImageView(context).apply {
        setImageResource(R.drawable.ic_cancel)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Xoá chữ tìm"
        visibility = View.INVISIBLE
        setOnClickListener { field.editableText.clear() }
    }
    private val card = LinearLayout(context).apply { orientation = VERTICAL }
    private var landscape = false
    /**
     * Khung chứa [card]. Màn hình ngang (đo trên Gboard điện thoại): khung tìm rộng [LANDSCAPE_CARD_SHARE] bề ngang,
     * nằm giữa, nút quay lại ở ngay bên trái nó (cách [LANDSCAPE_BACK_GAP] dp), thẳng mép trên.
     */
    private val body = object : android.widget.FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (landscape) {
                val total = MeasureSpec.getSize(widthMeasureSpec)
                val w = (total * LANDSCAPE_CARD_SHARE).toInt()
                (card.layoutParams as android.widget.FrameLayout.LayoutParams).width = w
                (back.layoutParams as? android.widget.FrameLayout.LayoutParams)?.leftMargin =
                    maxOf(0, (total - w) / 2 - context.dpi(LANDSCAPE_BACK_GAP) - context.dpi(BACK_SIZE))
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Ô nhập thay cho ô của ứng dụng khi đang tìm. */
    val connection get() = field.connection

    init {
        orientation = VERTICAL
        header.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = context.dpi(10) })
        val fieldRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchIcon, LayoutParams(context.dpi(48), LayoutParams.MATCH_PARENT))
            addView(field, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(clear, LayoutParams(context.dpi(48), LayoutParams.MATCH_PARENT))
        }
        card.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(1).coerceAtLeast(1)))
        card.addView(fieldRow, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(FIELD_HEIGHT)))
        card.clipToOutline = true
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(HEADER_HEIGHT)))
        body.addView(card, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                clear.visibility = if (s.isEmpty()) View.INVISIBLE else View.VISIBLE
                update()
            }
        })
    }

    /**
     * Màn hình dọc: hàng trên có nút quay lại và tiêu đề, khung tìm trải ngang. Màn hình ngang (đo trên Gboard điện
     * thoại): không tiêu đề; khung tìm ở giữa, cách đỉnh [LANDSCAPE_TOP] dp, nút quay lại bên trái nó; một hàng kết quả.
     */
    private fun styleForOrientation() {
        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        header.visibility = if (landscape) View.GONE else View.VISIBLE
        (back.parent as? android.view.ViewGroup)?.removeView(back)
        val size = context.dpi(BACK_SIZE)
        val cardLp = card.layoutParams as android.widget.FrameLayout.LayoutParams
        if (landscape) {
            body.addView(back, android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
            cardLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            cardLp.setMargins(0, 0, 0, 0)
            body.setPadding(0, context.dpi(LANDSCAPE_TOP), 0, 0)
            scroll.setPadding(context.dpi(LANDSCAPE_GRID_LEFT), 0, 0, 0)
            (field.parent as View).layoutParams.height = context.dpi(LANDSCAPE_FIELD_HEIGHT)
        } else {
            // Nút quay lại là hình tròn 34 dp cách mép 8 dp (đo trên Gboard).
            header.addView(back, 0, LayoutParams(size, size).apply { leftMargin = context.dpi(8) })
            cardLp.width = LayoutParams.MATCH_PARENT
            cardLp.gravity = Gravity.NO_GRAVITY
            cardLp.setMargins(context.dpi(8), 0, context.dpi(8), context.dpi(6))
            body.setPadding(0, 0, 0, 0)
            scroll.setPadding(0, 0, 0, 0)
            (field.parent as View).layoutParams.height = context.dpi(FIELD_HEIGHT)
        }
        back.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.key)
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        grid.landscape = landscape
        requestLayout()
    }

    fun applyTheme() {
        setBackgroundColor(theme.background)
        styleForOrientation()
        back.setColorFilter(theme.text)
        title.setTextColor(theme.text)
        card.background = GradientDrawable().apply {
            cornerRadius = context.dp(16f)
            setColor(theme.key)
        }
        divider.setBackgroundColor(theme.hintText and 0x00FFFFFF or 0x50000000)
        searchIcon.setColorFilter(theme.text)
        clear.setColorFilter(theme.text)
        field.setTextColor(theme.text)
        field.setHintTextColor(theme.hintText)
        field.caretColor = theme.accent
        grid.theme = theme
        grid.invalidate()
    }

    /** Mở: ô tìm trống, phím gõ vào ô tìm. */
    fun open() {
        styleForOrientation()
        field.editableText.clear()
        active = true
        field.startBlink()
        update()
    }

    fun close() {
        active = false
        field.stopBlink()
        grid.closeTones()
    }

    /** Hiện lại kết quả theo chữ đang tìm, cuộn về đầu. */
    fun update() {
        grid.closeTones()
        grid.items = results(field.text.toString())
        scroll.scrollTo(0, 0)
    }

    /**
     * Cuộn ngang theo trang, như Gboard (đo trên máy): vuốt quá một đoạn ngắn là sang hẳn trang bên cạnh, thả tay thì
     * dừng thẳng mép trang; vuốt nhanh cũng chỉ sang một trang.
     */
    @SuppressLint("ViewConstructor")
    private class PagedScroll(context: Context, private val pages: () -> Pair<Int, Int>) : HorizontalScrollView(context) {
        private var startX = 0
        private var flung = false
        private val threshold = context.dp(PAGE_THRESHOLD)

        /** Trang chứa mép trái đang thấy lúc bắt đầu chạm, theo [startX]. */
        private fun pageAt(x: Int, w: Int) = if (w <= 0) 0 else (x + w / 2) / w

        private fun snap(direction: Int) {
            val (w, count) = pages()
            if (w <= 0) return
            val from = pageAt(startX, w)
            val page = when {
                direction > 0 -> from + 1
                direction < 0 -> from - 1
                else -> from
            }.coerceIn(0, count - 1)
            smoothScrollTo(page * w, 0)
        }

        /** Hướng vuốt theo quãng đã kéo: quá [threshold] thì sang trang đó. */
        private fun dragDirection(): Int {
            val d = scrollX - startX
            return when {
                d > threshold -> 1
                d < -threshold -> -1
                else -> 0
            }
        }

        override fun fling(velocityX: Int) {
            flung = true
            snap(if (velocityX > 0) 1 else -1)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                flung = false
                startX = scrollX
            }
            val handled = super.onTouchEvent(ev)
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
                snap(dragDirection())
            }
            return handled
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                flung = false
                startX = scrollX
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    /**
     * Các hàng kết quả, cuộn ngang theo trang: mỗi trang vừa số cột trọn vẹn của khung, trong trang các ô xếp theo hàng. Ô có bản khác (màu da, giới
     * tính) có tam giác nhỏ ở góc; giữ ô thì mở bảng chọn ngay trên ô, như trong bảng emoji: thả tay bảng vẫn mở,
     * chạm một bản thì chọn, chạm ra ngoài hay cuộn thì đóng.
     */
    private class ResultGrid(
        context: Context,
        private val onPick: (String) -> Unit,
        private val tonesOf: (String) -> List<List<String>>?,
        private val onPickTone: (String) -> Unit,
    ) : View(context) {
        var theme = KeyboardTheme.of(context)
        /**
         * Màn hình ngang (đo trên Gboard điện thoại): một hàng kết quả cao [LANDSCAPE_ROW] dp, ô rộng [LANDSCAPE_CELL]
         * dp, emoji nhỏ hơn cho vừa hàng (hình vẽ cao chừng 31 dp).
         */
        var landscape = false
            set(v) {
                if (field == v) return
                field = v
                rows = if (v) 1 else 2
                cell = context.dp(if (v) LANDSCAPE_CELL else CELL)
                rowHeight = context.dp(if (v) LANDSCAPE_ROW else ROW)
                paint.textSize = context.dp(if (v) LANDSCAPE_EMOJI_SIZE else EMOJI_SIZE)
                requestLayout()
                invalidate()
            }
        private var rows = 2
        var items: List<String> = emptyList()
            set(v) {
                field = v
                requestLayout()
                invalidate()
            }
        private var cell = context.dp(CELL)
        private var rowHeight = context.dp(ROW)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = context.dp(EMOJI_SIZE)
        }
        /** Số cột một trang: số ô trọn vẹn vừa khung (Gboard: 7, cột thứ 8 ló ra một phần). */
        private var pageCols = 1
        private val perPage get() = pageCols * rows
        /** Bề rộng một trang (px): mỗi lần vuốt sang đúng chừng này. */
        val pageWidth get() = (pageCols * cell).toInt()
        val pageCount get() = maxOf(1, (items.size + perPage - 1) / perPage)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // HorizontalScrollView đo con với UNSPECIFIED nhưng vẫn kèm bề rộng của nó.
            val parentW = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (parent as? View)?.width ?: 0
            pageCols = maxOf(1, (parentW / cell).toInt())
            // Trang cuối dù ít ô vẫn kéo được về sát mép trái (như Gboard): chừa thêm một khung sau nó.
            val w = (pageCount - 1) * pageWidth + parentW
            setMeasuredDimension(maxOf(parentW, w), (rows * rowHeight).toInt())
        }

        /** Cột (tính từ đầu, qua mọi trang) và hàng của ô thứ [i]: trong mỗi trang các ô xếp theo hàng. */
        private fun colOf(i: Int) = (i / perPage) * pageCols + i % perPage % pageCols
        private fun rowOf(i: Int) = i % perPage / pageCols

        private val mark = android.graphics.Path()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val longPress = Runnable { openTones() }
        /** Bảng chọn bản đang mở (cửa sổ nổi, vươn được lên trên khung kết quả). */
        private var popup: android.widget.PopupWindow? = null
        private var bubble: ToneBubble? = null
        /** Vừa giữ ô mở bảng: thả tay không chọn ô; ngón tay còn đè thì kéo sang bảng để tô bản. */
        private var holding = false
        /** Lúc chạm ra ngoài làm đóng bảng: cú chạm đó không chọn ô bên dưới. */
        private var dismissedAt = 0L

        override fun onDraw(canvas: Canvas) {
            paint.color = theme.text
            val dy = -(paint.ascent() + paint.descent()) / 2
            for ((i, item) in items.withIndex()) {
                val r = rowOf(i)
                val c = colOf(i)
                canvas.drawText(item, cell * (c + 0.5f), rowHeight * (r + 0.5f) + dy, paint)
                // Có bản màu da: tam giác nhỏ ở góc dưới phải ô, như Gboard.
                if (tonesOf(item) != null) {
                    val right = cell * (c + 1) - context.dp(5f)
                    val bottom = rowHeight * (r + 1) - context.dp(5f)
                    val s = context.dp(5f)
                    mark.reset()
                    mark.moveTo(right, bottom)
                    mark.lineTo(right - s, bottom)
                    mark.lineTo(right, bottom - s)
                    mark.close()
                    paint.color = theme.hintText
                    canvas.drawPath(mark, paint)
                    paint.color = theme.text
                }
            }
        }

        /** Chỉ số ô dưới điểm ([x], [y]), hay -1. */
        private fun indexAt(x: Float, y: Float): Int {
            val col = (x / cell).toInt()
            val r = (y / rowHeight).toInt()
            if (x < 0 || y < 0 || r >= rows) return -1
            return ((col / pageCols) * perPage + r * pageCols + col % pageCols).takeIf { it < items.size } ?: -1
        }

        private fun openTones() {
            val i = indexAt(downX, downY).takeIf { it >= 0 } ?: return
            val item = items[i]
            val tones = tonesOf(item) ?: return
            val b = ToneBubble(context, tones, item, theme, rowHeight) { closeTones(); onPickTone(it) }
            val w = b.measuredW
            val h = b.measuredH
            val at = IntArray(2).also { getLocationInWindow(it) }
            val cellLeft = at[0] + colOf(i) * cell
            val cellTop = at[1] + rowOf(i) * rowHeight
            val screenW = resources.displayMetrics.widthPixels
            val x = (cellLeft + cell / 2 - w / 2).toInt().coerceIn(0, maxOf(0, screenW - w))
            // Ngay trên ô; không đủ chỗ phía trên (đỉnh cửa sổ) thì dưới ô.
            val above = (cellTop - h - context.dp(4f)).toInt()
            val y = if (above >= 0) above else (cellTop + rowHeight + context.dp(4f)).toInt()
            popup = android.widget.PopupWindow(b, w, h, false).apply {
                isOutsideTouchable = true
                isClippingEnabled = false
                elevation = context.dp(3f)
                setTouchInterceptor { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        dismissedAt = android.os.SystemClock.uptimeMillis()
                        closeTones()
                        true
                    } else {
                        false
                    }
                }
                showAtLocation(this@ResultGrid, Gravity.NO_GRAVITY, x, y)
            }
            bubble = b
            holding = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        fun closeTones() {
            handler.removeCallbacks(longPress)
            popup?.dismiss()
            popup = null
            bubble = null
            holding = false
        }

        /** Điểm ([x], [y]) của view này theo toạ độ trong bảng chọn. */
        private fun toBubble(x: Float, y: Float): Pair<Float, Float>? {
            val b = bubble ?: return null
            if (!b.isAttachedToWindow) return null
            val me = IntArray(2).also { getLocationOnScreen(it) }
            val it = IntArray(2).also { b.getLocationOnScreen(it) }
            return (x + me[0] - it[0]) to (y + me[1] - it[1])
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    if (popup != null) closeTones()
                    val i = indexAt(e.x, e.y)
                    if (i >= 0 && tonesOf(items[i]) != null) handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> if (holding) {
                    toBubble(e.x, e.y)?.let { (bx, by) -> bubble?.highlight(bx, by) }
                } else if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) {
                    handler.removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        holding -> {
                            // Thả tay trên một bản thì chọn bản đó; thả chỗ khác thì bảng vẫn mở.
                            holding = false
                            val p = toBubble(e.x, e.y)
                            val b = bubble
                            if (p != null && b != null && b.contains(p.first, p.second)) b.release(p.first, p.second)
                        }
                        kotlin.math.abs(e.downTime - dismissedAt) < 300 -> {}
                        kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop ->
                            indexAt(e.x, e.y).takeIf { it >= 0 }?.let { onPick(items[it]) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    holding = false
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            closeTones()
            super.onDetachedFromWindow()
        }
    }

    /**
     * Bảng chọn bản của một emoji (cùng kiểu bảng giữ ô trong [EmojiPanel]): mỗi hàng một bản giới tính / hướng, trong
     * hàng là bản đó rồi 5 màu da; bản [current] tô sẵn.
     */
    @SuppressLint("ViewConstructor")
    private class ToneBubble(
        context: Context,
        private val tones: List<List<String>>,
        current: String,
        private val theme: KeyboardTheme,
        rowHeight: Float,
        private val onPick: (String) -> Unit,
    ) : View(context) {
        private val cellW = minOf(context.dp(CELL) * 1.1f, rowHeight * 1.2f)
        private val cellH = rowHeight * 0.95f
        private val pad = context.dp(4f)
        /** Emoji hai người: chọn riêng từng người ([EmojiPair]). */
        private val pair = EmojiPair.of(tones)
        val measuredW = ((pair?.width(cellW) ?: (cellW * tones.maxOf { it.size })) + pad * 2).toInt()
        val measuredH = ((pair?.height(cellH) ?: (cellH * tones.size)) + pad * 2).toInt()
        private var row = tones.indexOfFirst { current in it }.coerceAtLeast(0)
        private var col = tones[row].indexOf(current).coerceAtLeast(0)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = minOf(cellW, cellH) * 0.6f
        }
        private val rect = android.graphics.RectF()

        fun contains(x: Float, y: Float) = x >= 0 && y >= 0 && x < width && y < height

        fun highlight(x: Float, y: Float) {
            if (!contains(x, y) || pair != null) return
            val r = ((y - pad) / cellH).toInt().coerceIn(0, tones.lastIndex)
            val c = ((x - pad) / cellW).toInt().coerceIn(0, tones[r].lastIndex)
            if (r != row || c != col) {
                row = r
                col = c
                invalidate()
            }
        }

        fun pickHighlighted() = onPick(tones[row][col])

        /** Thả tay ở ([x], [y]): bảng hai người thì như chạm ô đó; bảng thường thì chọn bản đang tô. */
        fun release(x: Float, y: Float) {
            val p = pair ?: return pickHighlighted()
            p.tap(x - pad, y - pad, cellW, cellH)?.let(onPick) ?: invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = theme.popup
            val radius = if (tones.size == 1) height / 2f else context.dp(16f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            pair?.let {
                it.draw(canvas, pad, pad, cellW, cellH, theme, paint)
                return
            }
            val dy = -(paint.ascent() + paint.descent()) / 2
            for ((r, line) in tones.withIndex()) for ((c, tone) in line.withIndex()) {
                val cx = pad + cellW * (c + 0.5f)
                val cy = pad + cellH * (r + 0.5f)
                if (r == row && c == col) {
                    paint.color = theme.accent and 0x00FFFFFF or 0x40000000
                    canvas.drawCircle(cx, cy, minOf(cellW, cellH) * 0.46f, paint)
                }
                paint.color = theme.text
                canvas.drawText(tone, cx, cy + dy, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> highlight(e.x, e.y)
                MotionEvent.ACTION_UP -> if (contains(e.x, e.y)) release(e.x, e.y)
            }
            return true
        }
    }

    companion object {
        // Đo trên Gboard (AVD 411 dp): hàng trên 53 dp, ô kết quả 52 × 53 dp, emoji chừng 34 dp, ô tìm 37 dp.
        const val HEADER_HEIGHT = 53
        // Màn hình ngang, đo trên Gboard điện thoại (560 dpi): khung tìm rộng 61,3% bề ngang (458 / 748 dp), cách đỉnh
        // 9 dp; nút quay lại cách khung 10 dp; hàng kết quả 40 dp, ô 61 dp, ô đầu cách mép khung 4 dp, emoji hình vẽ
        // cao 31 dp; ô tìm 36 dp.
        const val LANDSCAPE_CARD_SHARE = 0.613f
        const val LANDSCAPE_TOP = 9
        const val LANDSCAPE_BACK_GAP = 10
        const val LANDSCAPE_ROW = 40f
        const val LANDSCAPE_CELL = 61f
        const val LANDSCAPE_GRID_LEFT = 4
        const val LANDSCAPE_EMOJI_SIZE = 28f
        const val LANDSCAPE_FIELD_HEIGHT = 36
        const val FIELD_HEIGHT = 37
        const val CELL = 52f
        const val ROW = 53f
        const val EMOJI_SIZE = 32f
        /** Giữ ô chừng này (ms) thì mở bảng chọn bản, như bảng emoji. */
        const val LONG_PRESS_MS = 400L
        /** Nút quay lại: hình tròn đường kính chừng này (dp). */
        const val BACK_SIZE = 34
        /** Kéo quá chừng này (dp) rồi thả là sang trang bên cạnh (Gboard: kéo chậm 114 dp đã sang trang). */
        const val PAGE_THRESHOLD = 24f
    }
}

/**
 * Ô tìm: chữ nằm trong chính TextView này (không cần tiêu điểm của cửa sổ); con trỏ tự vẽ và nháy như ô nhập.
 * [connection] là ô nhập bộ gõ dùng khi đang tìm.
 */
@SuppressLint("ViewConstructor")
class SearchField(context: Context) : TextView(context) {
    var caretColor = 0
        set(v) {
            field = v
            caretPaint.color = v
        }
    private val caretPaint = Paint().apply { strokeWidth = context.dp(2f) }
    private var caretOn = true
    private var blinking = false
    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, ComposingView.BLINK_MS)
        }
    }

    init {
        setText("", BufferType.EDITABLE)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        // Ô trống: chữ mờ "Tìm kiếm", như Gboard.
        hint = "Tìm kiếm"
        setHorizontallyScrolling(true)
        // Gõ chữ Hán Nôm vào ô tìm: chữ ngoài BMP cần phông Hán Nôm.
        useHanNomFont()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                // Gõ thêm thì con trỏ hiện ngay, đếm nhịp nháy lại; chữ dài thì cuộn cho thấy con trỏ.
                caretOn = true
                removeCallbacks(blink)
                if (blinking && !steadyCaret) postDelayed(blink, ComposingView.BLINK_MS)
                post { bringPointIntoView(caret) }
                invalidate()
            }
        })
    }

    private val caret get() = Selection.getSelectionEnd(editableText).let { if (it < 0) editableText.length else it }

    fun startBlink() {
        blinking = true
        caretOn = true
        removeCallbacks(blink)
        if (!steadyCaret) postDelayed(blink, ComposingView.BLINK_MS)
        invalidate()
    }

    fun stopBlink() {
        blinking = false
        removeCallbacks(blink)
    }

    /**
     * Đang gõ dở (khung gõ của bộ gõ hiện, con trỏ ở đó nhấp nháy): con trỏ ô tìm vẫn hiện nhưng đứng yên, để chỉ một
     * chỗ nhấp nháy.
     */
    var steadyCaret = false
        set(v) {
            if (field == v) return
            field = v
            removeCallbacks(blink)
            caretOn = true
            if (!v && blinking) postDelayed(blink, ComposingView.BLINK_MS)
            invalidate()
        }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (!blinking || !caretOn) return
        val at = caret.coerceAtMost(l.text.length)
        val x = totalPaddingLeft + l.getPrimaryHorizontal(at) + context.dp(1f)
        val top = totalPaddingTop + l.getLineTop(0).toFloat()
        val bottom = totalPaddingTop + l.getLineBottom(0).toFloat()
        canvas.drawLine(x, top, x, bottom, caretPaint)
    }

    /** Ô nhập trên chữ của ô tìm. Phím thật (xoá, mũi tên) tự xử lý vì không có cửa sổ nào nhận chúng. */
    val connection = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = editableText

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val e = editableText
            val start = Selection.getSelectionStart(e).coerceAtLeast(0)
            val end = Selection.getSelectionEnd(e).coerceAtLeast(0)
            val (a, b) = minOf(start, end) to maxOf(start, end)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    finishComposingText()
                    if (a != b) e.delete(a, b) else if (a > 0) e.delete(Character.offsetByCodePoints(e, a, -1), a)
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (b > 0) Selection.setSelection(e, Character.offsetByCodePoints(e, b, -1))
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (b < e.length) Selection.setSelection(e, Character.offsetByCodePoints(e, b, 1))
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_UP -> Selection.setSelection(e, 0)
                KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DPAD_DOWN -> Selection.setSelection(e, e.length)
                KeyEvent.KEYCODE_ENTER -> {}
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) commitText(String(Character.toChars(c)), 1)
                }
            }
            invalidate()
            return true
        }

        /** Phím tìm (Enter) không làm gì: kết quả đã hiện theo từng chữ gõ. */
        override fun performEditorAction(actionCode: Int) = true

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val e = editableText
            return ExtractedText().apply {
                text = e.toString()
                startOffset = 0
                selectionStart = Selection.getSelectionStart(e).coerceAtLeast(0)
                selectionEnd = Selection.getSelectionEnd(e).coerceAtLeast(0)
            }
        }
    }
}

/**
 * Tìm emoji theo tên và từ khoá (tiếng Việt, tiếng Anh) trong `assets/emoji_search.txt`; chữ Hán Nôm gõ vào thì tìm
 * theo mọi âm đọc của nó trong từ điển.
 */
object EmojiIndex {
    /** Tên và từ khoá tách thành chữ, kèm bản không dấu ([fold]) và dạng chuẩn để so âm đọc ([canon]) tính sẵn. */
    private class Entry(val emoji: String, val name: List<String>, val words: List<String>) {
        val nameFold = name.map(::fold)
        val wordsFold = words.map(::fold)
        val nameCanon = name.map(::canon)
        val wordsCanon = words.map(::canon)
    }

    @Volatile private var cached: List<Entry>? = null
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{Mn}+")

    /** Bỏ dấu, chữ thường, `đ` → `d`: gõ không dấu vẫn tìm được. */
    fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase().replace('đ', 'd'), java.text.Normalizer.Form.NFD).replace(MARKS, "")

    private fun words(s: String) = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    /**
     * Đọc một lần (tạo bằng `tools/emoji/gen_emoji_search.py`): mỗi dòng emoji, tab, tên, tab, từ khoá cách nhau `|`.
     * Chỉ giữ emoji có trong bảng (máy vẽ được).
     */
    private fun load(context: Context): List<Entry> = cached ?: synchronized(this) {
        cached ?: run {
            val shown = EmojiPanel.data(context).groups.flatMap { it.items }.toHashSet()
            val out = ArrayList<Entry>()
            context.assets.open("emoji_search.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size < 3 || parts[0] !in shown) continue
                    out.add(Entry(parts[0], words(parts[1]), words(parts[2]).distinct()))
                }
            }
            out.also { cached = it }
        }
    }

    /**
     * Một chữ của chữ tìm: chữ Quốc ngữ / Latin ([raw], bản bỏ dấu [fold]), hay một chữ Hán Nôm với mọi âm đọc của
     * nó trong từ điển ([readings], dạng [canon]).
     */
    private class Token(val raw: String, val fold: String, val readings: Set<String>?) {
        val han get() = readings != null
    }

    private fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    /** Tách chữ tìm: chữ Latin theo khoảng trắng / dấu câu, chữ Hán Nôm mỗi chữ một ([readingsOf]). */
    private fun tokens(context: Context, query: String): List<Token> {
        val out = ArrayList<Token>()
        for (w in words(query)) {
            val latin = StringBuilder()
            fun flush() {
                if (latin.isNotEmpty()) out.add(Token(latin.toString(), fold(latin.toString()), null))
                latin.clear()
            }
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                if (isHan(cp)) {
                    flush()
                    out.add(Token(String(Character.toChars(cp)), "", readingsOf(context)[cp] ?: emptySet()))
                } else {
                    latin.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            flush()
        }
        return out
    }

    /**
     * Dạng chuẩn của một âm tiết để so âm đọc với tên emoji: dấu thanh để cuối (khóa = khoá, thủy = thuỷ), `y` cuối sau
     * phụ âm thành `i` (kỹ = kĩ, mỳ = mì).
     */
    fun canon(syllable: String): String {
        val d = java.text.Normalizer.normalize(syllable.lowercase(), java.text.Normalizer.Form.NFD)
        val tone = d.filter { it in TONES }
        var base = java.text.Normalizer.normalize(d.filter { it !in TONES }, java.text.Normalizer.Form.NFC)
        if (base.length >= 2 && base.last() == 'y' && base[base.length - 2] !in VOWELS) base = base.dropLast(1) + "i"
        return base + tone
    }

    private const val TONES = "̣̀́̃̉"
    private const val VOWELS = "aăâeêioôơuưy"

    /** Âm đọc của từng chữ Hán Nôm (dạng [canon]), theo từ điển đang dùng; đọc lại khi từ điển đổi. */
    @Volatile private var readings: Pair<Long, Map<Int, Set<String>>>? = null

    /**
     * Mọi âm đọc của từng chữ một chữ trong từ điển của người dùng (`dictionary.txt`, gồm cả chữ tự thêm), chưa có thì
     * từ điển mặc định. Dòng: `chữ;âm đọc;độ phổ biến`.
     */
    private fun readingsOf(context: Context): Map<Int, Set<String>> {
        val file = java.io.File(context.filesDir, "dictionary.txt")
        val key = if (file.exists()) file.lastModified() else -1L
        readings?.takeIf { it.first == key }?.let { return it.second }
        synchronized(this) {
            readings?.takeIf { it.first == key }?.let { return it.second }
            val map = HashMap<Int, MutableSet<String>>()
            val reader = if (file.exists()) file.bufferedReader() else context.assets.open("dict/builtin.txt").bufferedReader()
            reader.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val parts = line.split(';')
                    if (parts.size < 2) continue
                    val chars = parts[0]
                    if (chars.isEmpty() || Character.charCount(chars.codePointAt(0)) != chars.length) continue
                    for (r in parts[1].split(',')) {
                        val s = r.trim()
                        if (s.isNotEmpty() && ' ' !in s) map.getOrPut(chars.codePointAt(0)) { HashSet() }.add(canon(s))
                    }
                }
            }
            readings = key to map
            return map
        }
    }

    /**
     * Emoji khớp [query]: mỗi chữ Latin của [query] phải là đầu một chữ trong tên hay từ khoá (gõ không dấu thì so
     * không dấu, có dấu thì so đúng dấu); mỗi chữ Hán Nôm khớp khi tên hay từ khoá có một chữ trùng một âm đọc của nó
     * (𩈘唭 tìm như "mặt cười", "mạt khi"…). Xếp trước: khớp đúng chữ đã gõ; trong đó tên bắt đầu bằng cả cụm tìm,
     * rồi tên chứa mọi chữ tìm; còn lại theo thứ tự trong bảng.
     */
    fun search(context: Context, query: String): List<String> {
        val q = tokens(context, query)
        if (q.isEmpty()) return emptyList()
        val phrase = q.takeIf { t -> t.none { it.han } }?.joinToString(" ") { it.fold }
        fun hit(t: Token, raw: List<String>, folded: List<String>, canon: List<String>) = when {
            t.han -> canon.any { it in t.readings!! }
            t.raw != t.fold -> raw.any { it.startsWith(t.raw) }
            else -> folded.any { it.startsWith(t.fold) }
        }
        val scored = ArrayList<Pair<Int, String>>()
        for (e in load(context)) {
            if (!q.all { hit(it, e.name, e.nameFold, e.nameCanon) || hit(it, e.words, e.wordsFold, e.wordsCanon) }) continue
            // Khớp đúng chữ đã gõ (cả dấu: "tay" là tay, không phải tây) xếp trước khớp khi bỏ dấu.
            val exact = q.all { t -> t.han || e.name.any { it.startsWith(t.raw) } || e.words.any { it.startsWith(t.raw) } }
            val score = (if (exact) 0 else 3) + when {
                phrase != null && e.nameFold.joinToString(" ").startsWith(phrase) -> 0
                q.all { hit(it, e.name, e.nameFold, e.nameCanon) } -> 1
                else -> 2
            }
            scored.add(score to e.emoji)
        }
        // Gboard đặt một số emoji ở hai nhóm (❤️ ở mặt cười và biểu tượng): kết quả chỉ hiện một lần.
        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }
}
