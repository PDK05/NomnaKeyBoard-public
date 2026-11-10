package com.hannom.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Lớp vẽ trên cùng của bàn phím: bóng xem trước phím và bảng ký tự khi giữ phím.
 * Cao bằng cả [InputRoot], kể cả phần trong suốt phía trên bàn phím, nên bóng và bảng không bị cắt. Không nhận chạm.
 */
class OverlayView(context: Context) : View(context) {
    var theme = KeyboardTheme.of(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = context.robotoTypeface()
    }
    private val radius = context.dp(8f)
    /** Icon của các mục không chèn chữ ([Layouts.ACTION_ICONS]). */
    private val actionIcons = Layouts.ACTION_ICONS.mapValues { context.getDrawable(it.value)!!.mutate() }

    /** Khoảng đệm giữa mép bảng ký tự và các ô, cùng tỉ lệ với cỡ ô như Gboard (ô ở đây nhỏ hơn ô của Gboard). */
    private val altPad = context.dp(8f)

    private var previewRect: RectF? = null
    private var previewLabel = ""
    private var previewMore = false

    private var popup: Popup? = null
    private val altPanel = RectF()
    private var cellW = 0f
    private var cellH = 0f
    /** Khoảng đệm giữa mép bảng và các ô đang dùng, theo hai chiều. */
    private var padX = 0f
    private var padY = 0f
    private var selRow = 0
    private var selCol = 0

    val showingAlternates get() = popup != null

    /** [key] tính theo toạ độ của overlay. [more]: phím có bảng khi giữ, hiện dấu … dưới chữ như Gboard. */
    fun showPreview(key: RectF, label: String, more: Boolean = false) {
        // Bóng tròn như Gboard (đo trên máy: đường kính 1.76 × rộng phím, 1.42 × cao phím; phím rộng ở màn ngang lấy
        // theo chiều cao), cách phím 6dp, phím sát mép thì bóng dịch vào trong.
        val d = bubbleDiameter(key)
        val left = (key.centerX() - d / 2).coerceAtMost(width - d).coerceAtLeast(0f)
        val top = (key.top - d - context.dp(6f)).coerceAtLeast(0f)
        previewRect = RectF(left, top, left + d, top + d)
        previewLabel = label
        previewMore = more
        invalidate()
    }

    private fun bubbleDiameter(key: RectF) = minOf(key.width() * 1.76f, key.height() * 1.42f)

    fun hidePreview() {
        previewRect = null
        invalidate()
    }

    /**
     * Bảng ký tự khi giữ phím [key]: ô chọn sẵn của [p] nằm ngay trên phím (bảng chạm mép thì dịch vào trong).
     * Bảng vươn lên trên bàn phím, đè lên ứng dụng như Gboard. Phía trên phím không đủ chỗ (phím hàng trên cùng như
     * `o`, màn hình ngang) thì bảng giữ nguyên cỡ ô và dời xuống đè lên phím đang giữ; chỉ khi dời tới đáy phím vẫn
     * không vừa thì các hàng mới thấp lại.
     */
    fun showAlternates(key: RectF, p: Popup, letterWidth: Float, letterHeight: Float) {
        hidePreview()
        popup = p
        // Mọi trang đều dùng cỡ của phím chữ, để phím rộng ở trang ?123 không kéo dài bảng.
        cellW = letterWidth.coerceAtLeast(context.dp(36f))
        val gap = context.dp(6f)
        // Chỗ trống phía trên phím, kể cả phần cửa sổ phía trên overlay khi được vẽ tràn ra (toàn màn hình khi xoay
        // ngang: bảng đè lên ô nhập của hệ thống, giữ cỡ ô như màn dọc thay vì ép thấp lại).
        val room = key.top + roomAbove()
        cellH = minOf(letterHeight * 0.9f, (room + key.height() - 2 * altPad) / p.rows.size)
        // Bảng một hàng: cao bằng đường kính bóng phím, hai đầu tròn theo bóng; một ô thì thành đúng hình tròn của bóng.
        val oneRow = p.rows.size == 1
        val height = if (oneRow) minOf(letterWidth * 1.76f, letterHeight * 1.42f, room - gap) else cellH * p.rows.size + 2 * altPad
        if (oneRow) cellH = minOf(cellH, height)
        val width = maxOf(cellW * p.columns + 2 * altPad, if (oneRow) height else 0f)
        padX = (width - cellW * p.columns) / 2
        padY = (height - cellH * p.rows.size) / 2
        val left = (key.centerX() - (p.col + 0.5f) * cellW - padX).coerceAtMost(this.width - width).coerceAtLeast(0f)
        // Không lên quá đỉnh vùng vẽ được (đỉnh overlay, hay đỉnh cửa sổ khi vẽ tràn).
        val top = maxOf(key.top - room, key.top - gap - height)
        altPanel.set(left, top, left + width, top + height)
        selRow = p.row
        selCol = p.col
        invalidate()
    }

    /**
     * Ngón tay ở ([x], [y]) (toạ độ overlay): chọn ô dưới ngón, ngoài bảng thì ô gần nhất; ô trống thì giữ ô cũ.
     * Kéo lên trên hoặc sang ngang quá mép bảng [CANCEL_DISTANCE_DP] thì đóng hẳn bảng, kéo lại cũng không mở lại;
     * kéo xuống dưới thì không huỷ (đo trên Gboard). Khác Gboard: thả tay sau đó không chèn gì, còn Gboard coi như
     * ngón đang nhấn phím bên dưới nên vẫn gõ ra phím đó.
     */
    fun moveAlternate(x: Float, y: Float) {
        val p = popup ?: return
        val reach = context.dp(CANCEL_DISTANCE_DP)
        if (y < altPanel.top - reach || x < altPanel.left - reach || x > altPanel.right + reach) {
            hideAll()
            return
        }
        val c = ((x - altPanel.left - padX) / cellW).toInt().coerceIn(0, p.columns - 1)
        val r = ((y - altPanel.top - padY) / cellH).toInt().coerceIn(0, p.rows.size - 1)
        if ((r != selRow || c != selCol) && p.at(r, c).isNotEmpty()) {
            selRow = r
            selCol = c
            invalidate()
        }
    }

    /**
     * Phần cửa sổ phía trên overlay mà overlay vẽ tràn lên được (px): chỉ khi các view cha không cắt ([InputRoot]
     * bỏ cắt ở chế độ toàn màn hình), không thì 0.
     */
    private fun roomAbove(): Float {
        var p = parent
        while (p is android.view.ViewGroup) {
            if (p.clipChildren) return 0f
            p = p.parent
        }
        val loc = IntArray(2).also { getLocationInWindow(it) }
        return loc[1].coerceAtLeast(0).toFloat()
    }

    fun takeAlternate(): String? {
        val choice = popup?.at(selRow, selCol)
        popup = null
        invalidate()
        return choice?.takeIf { it.isNotEmpty() }
    }

    fun hideAll() {
        previewRect = null
        popup = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        previewRect?.let { r ->
            fill.color = theme.bubble
            fill.setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0x40000000)
            canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
            fill.clearShadowLayer()
            textPaint.color = theme.text
            textPaint.textSize = r.height() * 0.55f
            canvas.drawText(previewLabel, textPaint.centerXFor(previewLabel, r.centerX()), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            if (previewMore) {
                textPaint.textSize = r.height() * 0.29f
                canvas.drawText("…", r.centerX(), r.centerY() + r.height() * 0.315f, textPaint)
            }
        }
        popup?.let { p ->
            fill.color = theme.popup
            fill.setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0x40000000)
            val panelRadius = if (p.rows.size == 1) altPanel.height() / 2 else context.dp(20f)
            canvas.drawRoundRect(altPanel, panelRadius, panelRadius, fill)
            fill.clearShadowLayer()
            val itemSize = minOf(cellW, cellH) * 0.64f
            for (r in p.rows.indices) for (c in 0 until p.columns) {
                val s = p.at(r, c)
                if (s.isEmpty()) continue
                // Mục nhiều ký tự (`^_^`) thu nhỏ cho vừa ô.
                textPaint.textSize = itemSize
                val fit = cellW * 0.86f / textPaint.measureText(s)
                if (fit < 1f) textPaint.textSize = itemSize * fit
                val cx = altPanel.left + padX + (c + 0.5f) * cellW
                val cy = altPanel.top + padY + (r + 0.5f) * cellH
                val selected = r == selRow && c == selCol
                if (selected) {
                    // Ô đang chọn: nền tròn màu nhấn, như Gboard.
                    fill.color = theme.accent
                    canvas.drawCircle(cx, cy, minOf(cellW, cellH) * 0.46f, fill)
                }
                textPaint.color = if (selected) theme.onAccent else theme.text
                val icon = actionIcons[s]
                if (icon != null) {
                    // Mục emoji, một tay, cài đặt vẽ bằng icon (như Gboard), không phải chữ.
                    val half = (minOf(cellW, cellH) * 0.3f).toInt()
                    icon.setTint(textPaint.color)
                    icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                    icon.draw(canvas)
                    continue
                }
                canvas.drawText(s, textPaint.centerXFor(s, cx), cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
        }
    }

    private companion object {
        /** Khoảng cách từ mép bảng ký tự mà kéo quá đó thì huỷ (dp). */
        const val CANCEL_DISTANCE_DP = 64f
    }
}
