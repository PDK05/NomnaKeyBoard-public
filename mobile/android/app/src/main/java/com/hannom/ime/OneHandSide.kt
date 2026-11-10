package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Cột nút bên cạnh bàn phím một tay (như Gboard): về bàn phím đầy đủ, dồn sang bên kia, chỉnh kích thước. Ba nút tròn
 * đặt ngang hàng đầu, hàng giữa và hàng dưới cùng của bàn phím ([anchors], toạ độ view này); chạm đâu trong cột thì
 * tính nút gần nhất theo chiều dọc.
 */
@SuppressLint("ViewConstructor")
class OneHandSide(context: Context, private val listener: KeyboardView.Listener) : View(context) {
    private enum class Button { FULL, SWITCH, RESIZE }

    var theme = KeyboardTheme.of(context)
        set(v) {
            field = v
            invalidate()
        }

    /** Bàn phím đang dồn về bên nào (mũi tên chỉ sang bên kia). */
    var side = OneHand.LEFT
        set(v) {
            field = v
            invalidate()
        }

    /** Tâm ba nút theo chiều dọc và cỡ nút tối đa (px), theo hàng phím của bàn phím. */
    var anchors: Pair<List<Float>, Float> = emptyList<Float>() to 0f
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = HashMap<Int, Drawable>()
    private val rects = Button.entries.associateWith { RectF() }
    private var pressed: Button? = null

    private fun layoutButtons() {
        val (ys, size) = anchors
        val r = minOf(width / 2f - context.dp(4f), size / 2, context.dp(22f))
        val cx = width / 2f
        Button.entries.forEachIndexed { i, b ->
            val cy = ys.getOrNull(i) ?: return@forEachIndexed
            rects.getValue(b).set(cx - r, cy - r, cx + r, cy + r)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (anchors.first.size < Button.entries.size || width <= 0) return
        layoutButtons()
        for ((b, r) in rects) {
            paint.color = if (b == pressed) theme.pressedKey else theme.specialKey
            canvas.drawOval(r, paint)
            val res = when (b) {
                Button.FULL -> R.drawable.ic_full_width
                // Mũi tên chỉ về phía sẽ dồn phím sang.
                Button.SWITCH -> if (side == OneHand.LEFT) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left
                Button.RESIZE -> R.drawable.ic_resize
            }
            val d = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }
            val half = (r.width() * 0.28f).toInt()
            d.setTint(theme.text)
            d.setBounds(r.centerX().toInt() - half, r.centerY().toInt() - half, r.centerX().toInt() + half, r.centerY().toInt() + half)
            d.draw(canvas)
        }
    }

    /** Nút gần chỗ chạm nhất theo chiều dọc (cả cột chia ba). */
    private fun at(y: Float): Button? = rects.minByOrNull { (_, r) -> abs(r.centerY() - y) }?.key

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = at(e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val b = pressed
                pressed = null
                invalidate()
                if (b != null) {
                    listener.onKeyFeedback(this)
                    when (b) {
                        Button.FULL -> listener.onOneHand(OneHand.OFF)
                        Button.SWITCH -> listener.onOneHand(if (side == OneHand.LEFT) OneHand.RIGHT else OneHand.LEFT)
                        Button.RESIZE -> listener.onResizeKeyboard()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }
}
