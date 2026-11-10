package com.hannom.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/** Bàn phím tự vẽ kiểu Gboard. Mọi hành động gửi qua [Listener]. */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener, private val overlay: OverlayView) : View(context) {

    interface Listener {
        /** [literal]: chèn đúng [text], không đổi thành dấu câu Hán ([Key.literal]). */
        fun onChar(text: String, literal: Boolean = false)
        /** Ký tự chọn bằng cách giữ phím (vd. ệ, số). */
        fun onAlternate(text: String)
        fun onDelete()
        fun onDeleteWord()
        fun onEnter()
        fun onSpace()
        fun onCursor(delta: Int)
        fun onModeTap()
        /** Giữ phím chế độ: bảng chọn bàn phím (như giữ phím 🌐 của Gboard). */
        fun onModeLongPress()
        fun onKeyFeedback(view: View)
        /** Nút ở cột bên của bàn phím một tay: về bàn phím đầy đủ ([OneHand.OFF]) hay dồn sang bên kia. */
        fun onOneHand(mode: OneHand)
        /** Nút chỉnh kích thước ở cột bên của bàn phím một tay. */
        fun onResizeKeyboard()
    }

    enum class Shift { OFF, ONCE, LOCK }

    /** [PHONEPAD]: ô nhập số ở màn hình ngang ([Layouts.phonepad]). */
    private enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD, PHONEPAD }

    var theme = KeyboardTheme.of(context)
    var modeLabel = "V"
    var spaceLabel = ""
    /** Icon phím Enter theo hành động của ô nhập (tìm, gửi, xong…). */
    var enterIcon = R.drawable.ic_return
    /** false: bàn phím Tiếng Anh, các trang và bảng giữ phím như Gboard tiếng Anh. */
    var vietnamese = true
        set(v) {
            if (field == v) return
            field = v
            setPage(page)
        }
    val style get() = when {
        quocNgu -> KeyboardStyle.VIETNAMESE
        !vietnamese -> KeyboardStyle.ENGLISH
        else -> KeyboardStyle.HAN_NOM
    }
    /** Bàn phím Tiếng Việt: bảng giữ phím như Gboard tiếng Việt (nguyên âm không có hàng `a A`, phụ âm không có bảng, `d` chỉ ra đ). */
    var quocNgu = false
        set(v) {
            if (field == v) return
            field = v
            // Mọi trang đều có bảng giữ phím riêng cho bàn phím Tiếng Việt.
            setPage(page)
        }
    /**
     * Cỡ chữ và icon trên phím tính theo chiều cao hàng chuẩn, không theo [rowHeight]: kéo bàn phím cao lên thì chỉ
     * phím cao thêm, chữ giữ cỡ (như Gboard); hàng thấp hơn chuẩn (xoay ngang) thì chữ nhỏ lại cho vừa phím.
     */
    private val textRow get() =
        if (landscape) minOf(rowHeight, landscapeDefaultRow) * InputRoot.LANDSCAPE_TEXT else minOf(rowHeight, context.dp(TEXT_ROW_DP))

    /** Màn hình ngang: chiều cao hàng chữ mặc định (px); kéo bàn phím cao hơn thì chữ giữ cỡ như màn hình dọc. */
    var landscapeDefaultRow = 0f

    /** Chiều cao một hàng của trang chữ (5 hàng). */
    var rowHeight = context.dp(50f)
        set(v) {
            field = v
            requestLayout()
        }
    var shift = Shift.OFF
        set(v) {
            val changed = field != v
            field = v
            shiftTapped = false
            invalidate()
            if (changed) onShiftChange(v)
        }
    /** Shift một lần do người dùng bấm, không phải tự viết hoa đầu câu. */
    private var shiftTapped = false
    /** Shift đổi (gợi ý từ viết hoa theo Shift / Caps Lock). */
    var onShiftChange: (Shift) -> Unit = {}
    /** Bàn phím viết tay: trang chữ chỉ còn hàng dưới cùng, phía trên để cho vùng viết. */
    var handwriting = false
        set(v) {
            if (field == v) return
            field = v
            if (page == Page.LETTERS) setPage(Page.LETTERS)
            onOneHandChange?.invoke()
        }
    /**
     * Bàn phím một tay (như Gboard): [InputRoot] thu hẹp cả thanh gợi ý lẫn bàn phím, dồn về một bên, bên kia là cột
     * nút ([OneHandSide]). Bàn phím viết tay và màn hình ngang không dùng ([effectiveOneHand]).
     */
    var oneHand = OneHand.OFF
        set(v) {
            if (field == v) return
            field = v
            onOneHandChange?.invoke()
        }
    val effectiveOneHand get() = if (handwriting || landscape) OneHand.OFF else oneHand
    /** [effectiveOneHand] có thể vừa đổi (đổi chế độ, viết tay, xoay màn hình): [InputRoot] xếp lại. */
    var onOneHandChange: (() -> Unit)? = null

    /** Màn hình ngang: không có bàn phím một tay (như Gboard), cả mục của nó trong bảng giữ phím phẩy. */
    var landscape = false
        set(v) {
            if (field == v) return
            field = v
            // Xoay dọc khi đang ở bàn phím số bốn cột: về bàn phím số thường.
            if (page == Page.PHONEPAD && !v) setPage(Page.NUMPAD)
            layoutKeys()
            requestLayout()
            invalidate()
            onOneHandChange?.invoke()
        }

    /**
     * Màn hình ngang: lề trái / phải (px) khi cửa sổ bàn phím nằm đè lên tai thỏ ([setSideInsets]). Cửa sổ đã né tai
     * thỏ thì bằng 0.
     */
    var insetLeft = 0f
        private set
    var insetRight = 0f
        private set

    fun setSideInsets(left: Float, right: Float) {
        if (insetLeft == left && insetRight == right) return
        insetLeft = left
        insetRight = right
        layoutKeys()
        invalidate()
    }

    /** Báo vùng viết tay có hiện hay không (chỉ hiện ở trang chữ của bàn phím viết tay). */
    var onPadVisible: ((Boolean) -> Unit)? = null

    private var page = Page.LETTERS
    /** Trang mà `?123` ở trang chữ mở ra: giữ nguyên trang số cho tới khi bấm `!?#` (như Gboard), cả khi ẩn bàn phím. */
    private var symbolsPage = Page.SYMBOLS
    private var rows = Layouts.letters()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roboto = context.robotoTypeface()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = roboto
    }
    private val icons = HashMap<Int, Drawable>()
    private val radius = context.dp(7f)
    private val gapX = context.dp(5f)
    private val gapY = context.dp(9f)
    /** Lề trên và dưới khối phím; màn hình ngang sát hơn để hàng phím đầu nằm đúng chỗ như Gboard. */
    private val padTop get() = context.dp(if (landscape) 0.5f else 2f)
    private val landscapeGapY = context.dp(InputRoot.LANDSCAPE_GAP_Y)
    private val rowGap get() = if (landscape) landscapeGapY else gapY
    /** Trang 5 hàng ở màn hình ngang: hàng số trên cùng thấp hơn hàng chữ (như Gboard). */
    private val shortNumberRow get() = landscape && rows.size == Layouts.MAX_ROWS
    /** Chiều cao hàng phím cách (cả khe). */
    private val bottomRow get() = if (landscape) rowHeight * InputRoot.LANDSCAPE_BOTTOM_ROW else rowHeight
    /** Chiều cao phần phía trên hàng phím cách ở trang 5 hàng. */
    private val rowsAbove get() = rowHeight * if (landscape) InputRoot.LANDSCAPE_NUMBER_ROW + 3 else Layouts.MAX_ROWS - 1f
    /** Chiều cao các hàng trên hàng phím cách ở trang đang hiện; trang ít hàng thì phím cao hơn. */
    private var pageRowHeight = rowHeight

    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Key? = null
    private var pointerId = -1
    private var downX = 0f
    private var lastShiftTap = 0L
    private var longPressed = false
    /** Đã giữ đủ lâu trên phím chữ không có bảng: kéo ngón ra khỏi phím thì huỷ lần bấm. */
    private var heldWithoutPopup = false
    private var repeating = false
    private var cursorAnchorX = 0f
    private var spaceDragging = false
    private var deleteWordArmed = false
    private var downY = 0f

    /** Cuộn cột phép tính của bàn phím số (px, 0 là đầu cột). */
    private var railScroll = 0f
    private var maxRailScroll = 0f
    private var railSection = 0f
    private var railKeys = emptyList<Key>()
    private var railDragging = false
    private var railAnchorY = 0f
    private var railAnchorScroll = 0f
    private var railVelocity: VelocityTracker? = null
    private val railScroller = OverScroller(context)
    private val viewConfig = ViewConfiguration.get(context)

    /** Độ đậm thanh cuộn của cột (0..1): chỉ hiện khi đang cuộn, rồi mờ đi. */
    private var railBarAlpha = 0f
    private val railBarFade = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 250
        addUpdateListener {
            railBarAlpha = it.animatedValue as Float
            invalidate()
        }
    }
    private val fadeRailBar = Runnable { railBarFade.start() }

    private val longPress = Runnable { onLongPress() }
    /**
     * Giữ phím xoá: xoá lặp, nhanh dần theo cấp số nhân (mỗi lần chờ ngắn hơn lần trước [REPEAT_SPEEDUP] lần), từ
     * [REPEAT_SLOW] ms tới nhanh nhất [REPEAT_FAST] ms một lần thì giữ nguyên.
     */
    private val repeatDelete = object : Runnable {
        var interval = REPEAT_SLOW

        override fun run() {
            if (!repeating) interval = REPEAT_SLOW
            repeating = true
            listener.onDelete()
            handler.postDelayed(this, interval.toLong())
            interval = (interval * REPEAT_SPEEDUP).coerceAtLeast(REPEAT_FAST)
        }
    }

    fun resetToLetters() {
        if (page != Page.LETTERS) setPage(Page.LETTERS)
    }

    /** Ô nhập số (số, điện thoại, ngày giờ): mở bàn phím số như Gboard. */
    fun showNumpad() {
        // Màn hình ngang: Gboard đổi sang bàn phím số bốn cột, không có cột phép tính.
        val target = if (landscape) Page.PHONEPAD else Page.NUMPAD
        if (page == target) return
        // Trang `?123` mở ra ở ô chữ không đổi theo.
        val kept = symbolsPage
        setPage(target)
        symbolsPage = kept
    }

    /** Trang số (có số to, không có bóng phím khi nhấn). */
    private val numberPage get() = page == Page.NUMPAD || page == Page.PHONEPAD

    /** Bỏ trạng thái đang nhấn (bàn phím bị ẩn giữa lúc nhấn thì không có ACTION_UP). */
    fun cancelTouch() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeatDelete)
        pressed = null
        pointerId = -1
        railDragging = false
        railVelocity?.recycle()
        railVelocity = null
        overlay.hideAll()
        invalidate()
    }

    private fun setPage(p: Page) {
        if (p != page) {
            railScroller.forceFinished(true)
            railBarFade.cancel()
            handler.removeCallbacks(fadeRailBar)
            railBarAlpha = 0f
        }
        if (p == Page.SYMBOLS || p == Page.NUMPAD) symbolsPage = p
        page = p
        rows = when (p) {
            Page.LETTERS -> if (handwriting) Layouts.handwriting() else Layouts.letters(style)
            Page.SYMBOLS -> Layouts.symbols(style)
            Page.SYMBOLS2 -> Layouts.symbols2(style)
            Page.NUMPAD -> Layouts.numpad(style)
            Page.PHONEPAD -> Layouts.phonepad()
        }
        layoutKeys()
        requestLayout()
        invalidate()
        onPadVisible?.invoke(handwriting && p == Page.LETTERS)
    }

    /** Chiều cao phần phía trên hàng phím cách (vùng viết tay). */
    val padHeight get() = (padTop + rowsAbove).toInt()

    /** Bước hàng của [Page.PHONEPAD] (đo trên Gboard: 40 dp khi hàng chữ 38.9 dp). */
    private val phonepadRow get() = rowHeight * PHONEPAD_ROW

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (page == Page.PHONEPAD) (phonepadRow * rows.size + padTop * 2).toInt()
        else (rowsAbove + bottomRow + padTop * 2).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    /** Nền chung của cột phép tính ở bàn phím số. */
    private val numpadRail = RectF()
    /** Vùng hiện phím của cột phép tính (lùi vào so với nền, phím cuộn bị cắt ở đây, như Gboard). */
    private val railViewport = RectF()
    private val railPad = context.dp(8f)
    private val railBar = RectF()
    private val pressedRail = RectF()

    /** Bàn phím số: ba hàng trên chia đều, cột phép tính cao bằng cả ba hàng, hiện [Layouts.RAIL_VISIBLE] phím. */
    private fun layoutNumpad() {
        val left = if (landscape) insetLeft else gapX / 2
        val unit = if (landscape) (width - insetLeft - insetRight) / 10f else (width - gapX) / 10f
        val gap = rowGap
        pageRowHeight = (height - padTop * 2 - bottomRow) / 3
        var top = padTop + gap / 2
        val railTop = top
        rows.forEachIndexed { r, row ->
            val last = r == rows.lastIndex
            val h = (if (last) bottomRow else pageRowHeight) - gap
            var x = if (last) left else left + unit * row.first().weight
            row.filterNot { it.rail }.forEach { k ->
                val w = unit * k.weight
                k.rect.set(x + gapX / 2, top, x + w - gapX / 2, top + h)
                x += w
            }
            top += if (last) bottomRow else pageRowHeight
        }
        railKeys = rows.flatten().filter { it.rail }
        val railBottom = railTop + 3 * pageRowHeight - gap
        numpadRail.set(left + gapX / 2, railTop, left + unit * railKeys.first().weight - gapX / 2, railBottom)
        railViewport.set(numpadRail.left, numpadRail.top + railPad, numpadRail.right, numpadRail.bottom - railPad)
        // Chưa đo xong (height = 0, như khi xoay ngang rồi mở ngay ô số) thì vùng cột âm:
        // giữ các giá trị không âm để coerceIn không ném lỗi; onSizeChanged sẽ xếp lại.
        railSection = (railViewport.height() / Layouts.RAIL_VISIBLE).coerceAtLeast(0f)
        maxRailScroll = (railSection * (railKeys.size - Layouts.RAIL_VISIBLE)).coerceAtLeast(0f)
        setRailScroll(railScroll)
    }

    /** Đặt vị trí cuộn của cột phép tính và xếp lại phím trong cột. */
    private fun setRailScroll(value: Float) {
        railScroll = value.coerceIn(0f, maxRailScroll)
        railKeys.forEachIndexed { i, k ->
            val top = railViewport.top + i * railSection - railScroll
            k.rect.set(railViewport.left, top, railViewport.right, top + railSection)
        }
        invalidate()
    }

    private fun showRailBar(hideAfterMs: Long) {
        railBarFade.cancel()
        railBarAlpha = 1f
        handler.removeCallbacks(fadeRailBar)
        handler.postDelayed(fadeRailBar, hideAfterMs)
        invalidate()
    }

    private fun startRailDrag(y: Float) {
        railDragging = true
        railAnchorY = y
        railAnchorScroll = railScroll
        handler.removeCallbacks(longPress)
    }

    override fun computeScroll() {
        if (railScroller.computeScrollOffset()) {
            setRailScroll(railScroller.currY.toFloat())
            showRailBar(RAIL_BAR_SCROLL_MS)
            postInvalidateOnAnimation()
        }
    }

    /** Bàn phím số màn hình ngang: các hàng cao bằng nhau, chia đều chiều cao view. */
    private fun layoutPhonepad() {
        val left = insetLeft
        val unit = (width - insetLeft - insetRight) / 10f
        val gap = rowGap
        pageRowHeight = (height - padTop * 2) / rows.size
        var top = padTop + gap / 2
        for (row in rows) {
            var x = left
            for (k in row) {
                val w = unit * k.weight
                k.rect.set(x + gapX / 2, top, x + w - gapX / 2, top + pageRowHeight - gap)
                x += w
            }
            top += pageRowHeight
        }
    }

    private fun layoutKeys() {
        if (page == Page.NUMPAD) return layoutNumpad()
        if (page == Page.PHONEPAD) return layoutPhonepad()
        // [left] là mép trái ô phím đầu hàng (phím lùi vào thêm gapX / 2).
        val left = if (landscape) insetLeft else gapX / 2
        val unit = if (landscape) (width - insetLeft - insetRight) / 10f else (width - gapX) / 10f
        val gap = rowGap
        // Hàng đầu và hàng phím cách đứng yên như trang chữ. Trang ít hàng thì các hàng trên
        // dàn đều: phím cao thêm chút, khe giữa các hàng rộng ra ~30% phím (như Gboard).
        // Chỉ một hàng (viết tay): hàng đó nằm ở đáy như hàng phím cách của trang chữ.
        val above = height - padTop * 2 - bottomRow
        pageRowHeight = if (rows.size > 1) above / (rows.size - 1) else above
        val keyHeight = maxOf(rowHeight - gap, pageRowHeight / 1.3f)
        var top = padTop + gap / 2 + if (rows.size > 1) 0f else pageRowHeight
        rows.forEachIndexed { r, row ->
            val pitch = when {
                r == rows.lastIndex -> bottomRow
                !shortNumberRow -> pageRowHeight
                r == 0 -> rowHeight * InputRoot.LANDSCAPE_NUMBER_ROW
                else -> rowHeight
            }
            val h = if (r == rows.lastIndex || shortNumberRow) pitch - gap else keyHeight
            var x = left
            row.forEach { k ->
                val w = unit * k.weight
                k.rect.set(x + gapX / 2, top, x + w - gapX / 2, top + h)
                x += w
            }
            top += pitch
        }
    }

    /**
     * Cột nút một tay ([OneHandSide]) đặt nút ngang hàng đầu, hàng giữa và hàng dưới cùng (như Gboard): tâm các hàng
     * đó theo toạ độ view này, và cỡ phím (để nút tròn vừa hàng).
     */
    fun sideAnchors(): Pair<List<Float>, Float> {
        if (rows.isEmpty()) return emptyList<Float>() to 0f
        val at = listOf(0, rows.size / 2, rows.lastIndex)
        return at.map { rows[it].first().rect.centerY() } to rowHeight - gapY
    }

    /**
     * Dấu mà phím số [k] đang đổi thành lúc bật Shift một lần; phím khác hoặc không đổi thì null. Bàn phím Hán Nôm chỉ
     * đổi khi người dùng bấm Shift, tự viết hoa đầu câu thì hàng số vẫn là số (Gboard 拼音 không tự viết hoa).
     */
    private fun shiftedDigit(k: Key): String? =
        if (shift == Shift.ONCE && page == Page.LETTERS && (style.gboard || shiftTapped)) Layouts.shiftedDigit(k.label, style) else null

    private fun label(k: Key): String = when (k.type) {
        KeyType.CHAR -> shiftedDigit(k) ?: if (shift != Shift.OFF) k.label.uppercase() else k.label
        KeyType.SHIFT, KeyType.DELETE, KeyType.ENTER -> ""
        KeyType.SPACE -> spaceLabel
        KeyType.MODE -> modeLabel
        KeyType.TO_SYMBOLS2 -> "=\\<"
        else -> k.label
    }

    private fun icon(k: Key): Int? = when (k.type) {
        KeyType.SHIFT -> when (shift) {
            Shift.OFF -> R.drawable.ic_shift
            Shift.ONCE -> R.drawable.ic_shift_on
            Shift.LOCK -> R.drawable.ic_caps_lock
        }
        KeyType.DELETE -> R.drawable.ic_backspace
        KeyType.ENTER -> enterIcon
        KeyType.SPACE -> if (numberPage) R.drawable.ic_space else null
        else -> null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        if (page == Page.NUMPAD) {
            keyPaint.color = theme.specialKey
            canvas.drawRoundRect(numpadRail, radius, radius, keyPaint)
        }
        for (row in rows) for (k in row) {
            if (k.type == KeyType.SPACER) continue
            if (k.rail) {
                // Phím cuộn khỏi khung thì bỏ, phím còn lại cắt theo khung (restore ở cuối vòng lặp).
                if (!RectF.intersects(k.rect, railViewport)) continue
                canvas.save()
                canvas.clipRect(railViewport)
            }
            // Phím đang có bóng tròn phía trên: mất nền, chữ mờ đi như Gboard.
            val lifted = k === pressed && k.type == KeyType.CHAR && !numberPage && !overlay.showingAlternates
            keyPaint.color = when {
                lifted -> theme.background
                k === pressed && !railDragging -> theme.pressedKey
                k.type == KeyType.ENTER -> theme.accent
                k.isSpecial -> theme.specialKey
                else -> theme.key
            }
            val r = if (k.isPill) k.rect.height() / 2 else radius
            if (!k.rail) {
                canvas.drawRoundRect(k.rect, r, r, keyPaint)
            } else if (k === pressed && !railDragging) {
                // Phím trong cột phép tính không có nền riêng, chỉ sáng lên khi nhấn.
                pressedRail.set(k.rect)
                pressedRail.inset(gapX / 2, gapX / 4)
                canvas.drawRoundRect(pressedRail, r, r, keyPaint)
            }

            val fg = if (k.type == KeyType.ENTER) theme.onAccent else theme.text
            if (k.type == KeyType.TO_NUMPAD) {
                // Phím `12/34`: hai dòng chữ đậm như Gboard.
                labelPaint.color = fg
                labelPaint.textSize = textRow * 0.3f
                labelPaint.isFakeBoldText = true
                canvas.drawText("12", k.rect.centerX(), k.rect.centerY() - labelPaint.textSize * 0.14f, labelPaint)
                canvas.drawText("34", k.rect.centerX(), k.rect.centerY() + labelPaint.textSize * 0.92f, labelPaint)
                labelPaint.isFakeBoldText = false
                continue
            }
            val res = icon(k)
            if (res != null) {
                val d = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }
                val half = (textRow * if (k.type == KeyType.SHIFT) 0.32f else 0.23f).toInt()
                val cx = k.rect.centerX().toInt()
                val cy = k.rect.centerY().toInt()
                d.setTint(fg)
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.draw(canvas)
                continue
            }

            val text = label(k)
            // Chữ Nôm ngoài BMP (phím 𢮿徠) không có trong Roboto.
            labelPaint.typeface = if (text.any { it.isHighSurrogate() }) context.hanNomTypeface() ?: roboto else roboto
            labelPaint.color = if (k.type == KeyType.SPACE || lifted) theme.hintText else fg
            labelPaint.textSize = when {
                // Số ở bàn phím số to hơn chữ trên phím thường, như Gboard.
                numberPage && k.type == KeyType.CHAR && !k.isSpecial -> textRow * 0.62f
                page == Page.PHONEPAD && k.type == KeyType.CHAR -> textRow * 0.48f
                k.type == KeyType.SPACE -> if (text.length == 1) textRow * 0.48f else textRow * 0.3f
                k.type == KeyType.TO_SYMBOLS2 || k.type == KeyType.SHIFT || (k.type == KeyType.TO_SYMBOLS && !k.isPill) -> textRow * 0.42f
                k.type == KeyType.MODE || k.type == KeyType.TO_LETTERS || text.length > 2 -> textRow * 0.32f
                // Hàng số ở màn hình ngang thấp hơn nên chữ nhỏ hơn hàng chữ một chút.
                shortNumberRow && row === rows[0] -> textRow * 0.48f * InputRoot.LANDSCAPE_NUMBER_TEXT
                else -> textRow * 0.48f
            }
            var y = k.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            k.hintIcon?.let { res ->
                // Icon nhỏ giữa phía trên, chữ dịch xuống nửa dưới (phím phẩy của Gboard).
                val d = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }
                val half = (textRow * 0.13f).toInt()
                val cx = k.rect.centerX().toInt()
                val cy = (k.rect.top + k.rect.height() * 0.3f).toInt()
                d.setTint(theme.hintText)
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.draw(canvas)
                // Dấu phẩy vốn nằm dưới đường chân chữ (đã ở nửa dưới phím): chỉ dịch chút ít, không thì lọt xuống
                // sát / dưới viền dưới của phím.
                y += k.rect.height() * 0.03f
            }
            labelPaint.isFakeBoldText = k.type == KeyType.MODE
            canvas.drawText(text, labelPaint.centerXFor(text, k.rect.centerX()), y, labelPaint)
            labelPaint.isFakeBoldText = false
            if (k.rail) canvas.restore()
        }
        if (page == Page.NUMPAD && railBarAlpha > 0f) drawRailBar(canvas)
    }

    /** Thanh cuộn sát mép phải cột phép tính (đo trên Gboard: dày 1.5dp, cách mép 2dp, dài theo tỉ lệ phím thấy được). */
    private fun drawRailBar(canvas: Canvas) {
        val length = railViewport.height() * Layouts.RAIL_VISIBLE / railKeys.size
        val top = railViewport.top + (railViewport.height() - length) * railScroll / maxRailScroll
        val right = railViewport.right - context.dp(2f)
        railBar.set(right - context.dp(1.5f), top, right, top + length)
        keyPaint.color = theme.text
        keyPaint.alpha = (RAIL_BAR_ALPHA * railBarAlpha).toInt()
        canvas.drawRect(railBar, keyPaint)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        // Bàn phím số có cột phép tính cao nhiều hàng: chọn phím gần ngón nhất theo cả hai chiều. Phím trong cột chỉ
        // tính phần còn thấy trong khung, để phím bị cuộn khỏi khung không che phím ở hàng dưới.
        if (page == Page.NUMPAD) {
            val inRail = numpadRail.contains(x, y)
            return rows.flatten().filter { if (it.rail) RectF.intersects(it.rect, railViewport) else !inRail }.minByOrNull { k ->
                val top = if (k.rail) maxOf(k.rect.top, railViewport.top) else k.rect.top
                val bottom = if (k.rail) minOf(k.rect.bottom, railViewport.bottom) else k.rect.bottom
                val dx = maxOf(k.rect.left - x, x - k.rect.right, 0f)
                val dy = maxOf(top - y, y - bottom, 0f)
                dx * dx + dy * dy
            }
        }
        // Màn hình ngang: các hàng cao khác nhau, ranh giới là giữa khe hai hàng.
        val r = if (landscape) rows.indexOfLast { y >= it.first().rect.top - rowGap / 2 }.coerceAtLeast(0)
        else ((y - padTop) / pageRowHeight).toInt().coerceIn(0, rows.size - 1)
        val row = rows[r].filter { it.type != KeyType.SPACER }
        return row.minByOrNull { k ->
            when {
                x < k.rect.left -> k.rect.left - x
                x > k.rect.right -> x - k.rect.right
                else -> 0f
            }
        }
    }

    private fun overlayRect(k: Key): RectF {
        val me = IntArray(2).also { getLocationInWindow(it) }
        val ov = IntArray(2).also { overlay.getLocationInWindow(it) }
        return RectF(k.rect).apply { offset((me[0] - ov[0]).toFloat(), (me[1] - ov[1]).toFloat()) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        railVelocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(e.getPointerId(0), e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Gõ nhanh bằng hai ngón: chốt phím đang giữ rồi theo ngón mới.
                release(commit = true)
                val i = e.actionIndex
                press(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pointerId) release(commit = true)
            MotionEvent.ACTION_UP -> release(commit = true)
            MotionEvent.ACTION_CANCEL -> release(commit = false)
        }
        return true
    }

    private fun press(id: Int, x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        pointerId = id
        pressed = k
        downX = x
        downY = y
        railDragging = false
        cursorAnchorX = x
        longPressed = false
        heldWithoutPopup = false
        repeating = false
        spaceDragging = false
        deleteWordArmed = false
        listener.onKeyFeedback(this)
        // Bàn phím số không có bóng phím (như Gboard), phím chỉ sáng lên khi nhấn.
        if (k.type == KeyType.CHAR && !numberPage) overlay.showPreview(overlayRect(k), label(k), more = popupOf(k) != null)
        when (k.type) {
            KeyType.DELETE -> handler.postDelayed(repeatDelete, 300)
            KeyType.CHAR -> handler.postDelayed(longPress, 350)
            KeyType.MODE -> handler.postDelayed(longPress, 400)
            KeyType.SHIFT -> {
                // Shift có tác dụng ngay khi chạm xuống, giữ đủ lâu thì khoá chữ hoa ([onLongPress]).
                perform(k)
                handler.postDelayed(longPress, SHIFT_LOCK_MS)
            }
            else -> {}
        }
        if (k.rail) {
            railVelocity = VelocityTracker.obtain()
            // Chạm lúc cột đang trôi chỉ dừng cột, không gõ phím.
            if (!railScroller.isFinished) {
                railScroller.forceFinished(true)
                startRailDrag(y)
            }
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val k = pressed ?: return
        if (overlay.showingAlternates) {
            val r = overlayRect(k)
            overlay.moveAlternate(x + r.left - k.rect.left, y + r.top - k.rect.top)
            return
        }
        if (k.rail) {
            if (!railDragging && abs(y - downY) > viewConfig.scaledTouchSlop) startRailDrag(y)
            if (railDragging) {
                setRailScroll(railAnchorScroll + railAnchorY - y)
                showRailBar(RAIL_BAR_SCROLL_MS)
            }
            return
        }
        when (k.type) {
            KeyType.SPACE -> {
                val step = context.dp(14f)
                if (!spaceDragging && abs(x - downX) > context.dp(12f)) {
                    spaceDragging = true
                    cursorAnchorX = x
                }
                if (spaceDragging) {
                    val n = ((x - cursorAnchorX) / step).toInt()
                    if (n != 0) {
                        listener.onCursor(n)
                        cursorAnchorX += n * step
                    }
                }
            }
            KeyType.DELETE -> if (!repeating && downX - x > k.rect.width()) {
                handler.removeCallbacks(repeatDelete)
                deleteWordArmed = true
            }
            KeyType.CHAR -> if (heldWithoutPopup) {
                val slop = viewConfig.scaledTouchSlop
                val r = k.rect
                if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) release(commit = false)
            }
            else -> {}
        }
    }

    private fun release(commit: Boolean) {
        val k = pressed ?: return
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeatDelete)
        if (railDragging && commit) flingRail()
        railVelocity?.recycle()
        railVelocity = null
        pressed = null
        pointerId = -1
        overlay.hidePreview()
        if (overlay.showingAlternates) {
            val choice = overlay.takeAlternate()
            if (commit && choice != null) {
                listener.onAlternate(choice)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
        } else if (commit && !longPressed && !repeating && !spaceDragging && !railDragging && k.type != KeyType.SHIFT) {
            if (deleteWordArmed) listener.onDeleteWord() else perform(k)
        }
        railDragging = false
        invalidate()
    }

    /** Nhấc ngón sau khi kéo cột phép tính: cột trôi tiếp theo đà tay. */
    private fun flingRail() {
        val tracker = railVelocity ?: return
        tracker.computeCurrentVelocity(1000, viewConfig.scaledMaximumFlingVelocity.toFloat())
        val velocity = -tracker.getYVelocity(pointerId)
        if (abs(velocity) < viewConfig.scaledMinimumFlingVelocity) return
        railScroller.fling(0, railScroll.toInt(), 0, velocity.toInt(), 0, 0, 0, maxRailScroll.toInt())
        postInvalidateOnAnimation()
    }

    /** Bảng khi giữ phím [k] lúc này (theo chế độ gõ và Shift); không có thì null. */
    private fun popupOf(k: Key): Popup? {
        if (k.type != KeyType.CHAR) return null
        shiftedDigit(k)?.let { return Layouts.shiftedDigitPopup(it, style) }
        var p = when {
            quocNgu && k.label in Layouts.VOWELS -> Layouts.quocNguVowels.getValue(k.label)
            quocNgu && k.label == "d" -> Layouts.STROKE_D
            // Gboard tiếng Việt: các phụ âm khác không có bảng.
            quocNgu && k.vietnameseOnly -> null
            k.vietnameseOnly && !vietnamese -> Layouts.englishLetters[k.label]
            else -> k.popup
        } ?: return null
        if (landscape && p.rows.any { Layouts.ONE_HAND in it }) {
            val chosen = p.at(p.row, p.col)
            val rows = p.rows.map { row -> row - Layouts.ONE_HAND }
            p = Popup(rows, p.row, rows[p.row].indexOf(chosen).coerceAtLeast(0))
        }
        // Shift: đổi hoa / thường (cặp `a A` thành `A a`).
        // `ß` viết hoa theo Unicode là "SS", nhưng Gboard hiện chữ `ẞ`.
        val swap = { s: String ->
            when {
                s == "ß" -> "ẞ"
                s == "ẞ" -> "ß"
                s.any { it.isLowerCase() } -> s.uppercase()
                else -> s.lowercase()
            }
        }
        return if (shift == Shift.OFF) p else Popup(p.rows.map { row -> row.map(swap) }, p.row, p.col)
    }

    private fun onLongPress() {
        val k = pressed ?: return
        if (k.type == KeyType.SHIFT) {
            longPressed = true
            shift = Shift.LOCK
            return
        }
        if (k.type == KeyType.MODE) {
            longPressed = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener.onModeLongPress()
            return
        }
        val p = popupOf(k)
        if (p == null) {
            heldWithoutPopup = true
            return
        }
        longPressed = true
        // Bảng giữ cỡ của phím chữ màn dọc: màn ngang phím rộng và thấp hơn nhưng bảng không đổi.
        val rect = overlayRect(k)
        val metrics = resources.displayMetrics
        val base = if (landscape) minOf(width, metrics.widthPixels, metrics.heightPixels) else width
        val letterWidth = (base - gapX) / 10f - gapX
        val letterHeight = if (landscape) context.dp(Settings(context).rowHeight.toFloat()) else rect.height()
        overlay.showAlternates(rect, p, letterWidth, letterHeight)
        invalidate()
    }

    private fun perform(k: Key) {
        when (k.type) {
            KeyType.CHAR -> {
                listener.onChar(label(k), literal = k.literal)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    now - lastShiftTap < 300 -> Shift.LOCK
                    shift == Shift.ONCE -> Shift.OFF
                    else -> Shift.ONCE
                }
                shiftTapped = shift == Shift.ONCE
                lastShiftTap = now
            }
            KeyType.DELETE -> listener.onDelete()
            KeyType.ENTER -> listener.onEnter()
            KeyType.SPACE -> listener.onSpace()
            KeyType.MODE -> listener.onModeTap()
            KeyType.TO_SYMBOLS -> setPage(if (page == Page.LETTERS) symbolsPage else Page.SYMBOLS)
            KeyType.TO_LETTERS -> setPage(Page.LETTERS)
            KeyType.TO_SYMBOLS2 -> setPage(Page.SYMBOLS2)
            KeyType.TO_NUMPAD -> setPage(Page.NUMPAD)
            KeyType.SPACER -> {}
        }
    }

    companion object {
        /** Chiều cao hàng (dp) mà cỡ chữ trên phím lấy làm chuẩn (mức "Vừa"). */
        const val TEXT_ROW_DP = 50f
        /** Giữ phím xoá: khoảng chờ giữa hai lần xoá lúc đầu và nhanh nhất (ms), mỗi lần nhân thêm hệ số. */
        private const val REPEAT_SLOW = 70f
        private const val REPEAT_FAST = 20f
        private const val REPEAT_SPEEDUP = 0.9f
        /** Giữ phím Shift bao lâu thì khoá chữ hoa (ms). */
        private const val SHIFT_LOCK_MS = 1500L
        /** Thanh cuộn của cột phép tính: thời gian còn hiện sau lần cuộn cuối (ms). */
        private const val RAIL_BAR_SCROLL_MS = 300L
        private const val RAIL_BAR_ALPHA = 128
        /** Bước hàng bàn phím số màn hình ngang so với hàng chữ (Gboard: 140 px / 38.9 dp ở 3.5x). */
        private const val PHONEPAD_ROW = 1.027f
    }
}
