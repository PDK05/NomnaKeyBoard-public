package com.hannom.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Ảnh thu nhỏ của một bàn phím cho dải chọn bố cục (như các ảnh trong cài đặt ngôn ngữ của Gboard):
 * vẽ từ chính bố cục phím [Layouts.letters], màu theo [KeyboardTheme]. Viết tay thì là vùng viết
 * trống phía trên hàng phím dưới cùng.
 */
class KeyboardPreview(
    context: Context,
    private val mode: Mode,
    private val layout: Layout,
    private val theme: KeyboardTheme = KeyboardTheme.of(context),
    /** Có dải thanh công cụ phía trên (xem trước giao diện, như Gboard). */
    private val header: Boolean = false,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = context.robotoTypeface()
    }
    private val rect = RectF()
    private val icons = HashMap<Int, Drawable>()

    override fun onDraw(canvas: Canvas) {
        val radius = context.dp(10f)
        fill.color = theme.background
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)

        val pad = context.dp(4f)
        val gap = context.dp(1.5f)
        val rows = Layouts.letters()
        val rowHeight = (height - 2 * pad) / (rows.size + if (header) 1 else 0)
        var top0 = pad
        if (header) {
            // Thanh công cụ: nền [KeyboardTheme.header], icon bảng tiện ích bên trái.
            fill.color = theme.header
            rect.set(0f, 0f, width.toFloat(), pad + rowHeight)
            canvas.save()
            canvas.clipRect(rect)
            rect.bottom += radius
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.restore()
            val d = icons.getOrPut(R.drawable.ic_apps) { context.getDrawable(R.drawable.ic_apps)!!.mutate() }
            val half = (rowHeight * 0.25f).toInt()
            val cx = (pad + rowHeight * 0.6f).toInt()
            val cy = ((pad + rowHeight) / 2).toInt()
            d.setTint(theme.text)
            d.setBounds(cx - half, cy - half, cx + half, cy + half)
            d.draw(canvas)
            top0 += rowHeight
        }
        // Viết tay: chỉ giữ hàng dưới cùng, phần trên để trống làm chỗ viết.
        val drawn = if (layout == Layout.HANDWRITING) listOf(rows.lastIndex to Layouts.handwriting().last()) else rows.withIndex().map { it.index to it.value }
        for ((r, row) in drawn) {
            val top = top0 + r * rowHeight
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = pad
            val unit = (width - 2 * pad) / total
            for (key in row) {
                val w = key.weight * unit
                if (key.type != KeyType.SPACER) {
                    rect.set(x + gap, top + gap, x + w - gap, top + rowHeight - gap)
                    val barKey = key.type == KeyType.SPACE ||
                        (key.isPill && (key.type == KeyType.TO_SYMBOLS || key.type == KeyType.TO_LETTERS))
                    fill.color = when {
                        key.type == KeyType.ENTER -> theme.accent
                        theme.borderless -> if (barKey) theme.spaceBar else 0
                        key.isSpecial -> theme.specialKey
                        else -> theme.key
                    }
                    val r2 = if (key.isPill || (theme.borderless && key.type == KeyType.SPACE)) rect.height() / 2
                        else context.dp(if (header) 6f else 3f)
                    if (fill.color != 0) canvas.drawRoundRect(rect, r2, r2, fill)
                    val fg = if (key.type == KeyType.ENTER) theme.onAccent else theme.text
                    val icon = when (key.type) {
                        KeyType.SHIFT -> R.drawable.ic_shift
                        KeyType.DELETE -> R.drawable.ic_backspace
                        KeyType.ENTER -> R.drawable.ic_return
                        else -> null
                    }
                    if (icon != null) {
                        val d = icons.getOrPut(icon) { context.getDrawable(icon)!!.mutate() }
                        val half = (rowHeight * if (key.type == KeyType.SHIFT) 0.3f else 0.22f).toInt()
                        val cx = rect.centerX().toInt()
                        val cy = rect.centerY().toInt()
                        d.setTint(fg)
                        d.setBounds(cx - half, cy - half, cx + half, cy + half)
                        d.draw(canvas)
                    }
                    val text = when (key.type) {
                        KeyType.CHAR, KeyType.TO_SYMBOLS -> key.label
                        KeyType.MODE -> mode.label
                        KeyType.SPACE -> mode.spaceLabel
                        else -> ""
                    }
                    if (text.isNotEmpty()) {
                        label.color = if (key.type == KeyType.SPACE) theme.hintText else fg
                        label.textSize = rowHeight * if (key.type == KeyType.CHAR) 0.45f else 0.3f
                        label.isFakeBoldText = key.type == KeyType.MODE
                        canvas.drawText(text, rect.centerX(), rect.centerY() - (label.ascent() + label.descent()) / 2, label)
                    }
                }
                x += w
            }
        }
    }
}
