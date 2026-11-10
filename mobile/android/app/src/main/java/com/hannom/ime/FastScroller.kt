package com.hannom.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.AbsListView
import android.widget.ListView

/**
 * Thanh cuộn nhanh kiểu Gboard đặt chồng lên [list]: cuộn thì hiện tay nắm ở mép phải, nắm kéo để lướt cả
 * danh sách theo ngón tay (chạm vào rãnh thì nhảy tới đó), kèm bong bóng [label] của dòng đang tới.
 * Đứng yên một lúc thì ẩn. Chạm ngoài mép phải (hoặc khi đang ẩn) thì để danh sách nhận.
 */
class FastScroller(
    context: Context,
    private val list: ListView,
    colors: SettingsColors,
    private val label: (position: Int) -> String?,
) : View(context) {
    private val thumbWidth = context.dp(6f)
    private val dragWidth = context.dp(8f)
    private val thumbHeight = context.dp(56f)
    private val edge = context.dp(6f)
    private val touchWidth = context.dp(48f)
    private val bubbleHeight = context.dp(56f)

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.summary
        alpha = 48
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.onPrimary
        typeface = context.robotoTypeface()
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
    }

    /** Vị trí tay nắm trên rãnh, 0 (đầu) → 1 (cuối). */
    private var fraction = 0f
    private var dragging = false
    private var grab = 0f
    /** Độ hiện của tay nắm 0 → 1. */
    private var shown = 0f
    private var fade: ValueAnimator? = null
    private val hide = Runnable { fadeTo(0f) }
    private var lastFirst = -1
    private var lastTop = 0

    init {
        list.isVerticalScrollBarEnabled = false
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                val top = list.getChildAt(0)?.top ?: 0
                // onScroll cũng chạy khi vẽ lại danh sách: chỉ hiện khi thật sự dịch chuyển.
                if (first == lastFirst && top == lastTop) return
                lastFirst = first
                lastTop = top
                if (!dragging) fraction = currentFraction()
                if (scrollable()) {
                    fadeTo(1f)
                    if (!dragging) {
                        removeCallbacks(hide)
                        postDelayed(hide, HIDE_DELAY)
                    }
                } else {
                    fadeTo(0f)
                }
                invalidate()
            }
        })
    }

    private fun scrollable() = list.canScrollVertically(1) || list.canScrollVertically(-1)

    private val trackTop get() = list.paddingTop + edge
    private val trackBottom get() = height - list.paddingBottom - edge
    private val travel get() = (trackBottom - trackTop - thumbHeight).coerceAtLeast(1f)

    /** Số dòng cuộn được tính theo chiều cao dòng đầu (các dòng gần như cao bằng nhau). */
    private fun rowRange(): Pair<Int, Float>? {
        val h = list.getChildAt(0)?.height?.takeIf { it > 0 } ?: return null
        val visible = (list.height - list.paddingTop - list.paddingBottom).toFloat() / h
        val range = list.count - visible
        return if (range > 0) h to range else null
    }

    private fun currentFraction(): Float {
        val (h, range) = rowRange() ?: return 0f
        val top = list.getChildAt(0)?.top ?: 0
        return ((list.firstVisiblePosition + (list.paddingTop - top).toFloat() / h) / range).coerceIn(0f, 1f)
    }

    private fun scrollTo(f: Float) {
        val (h, range) = rowRange() ?: return
        val target = f * range
        val position = target.toInt().coerceIn(0, list.count - 1)
        list.setSelectionFromTop(position, list.paddingTop - ((target - position) * h).toInt())
    }

    private fun fadeTo(target: Float) {
        if (shown == target && fade?.isRunning != true) return
        fade?.cancel()
        fade = ValueAnimator.ofFloat(shown, target).apply {
            duration = 200
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0f) return
        val width = if (dragging) dragWidth else thumbWidth
        val right = this.width - edge
        val top = trackTop + travel * fraction
        if (dragging) {
            canvas.drawRoundRect(right - width, trackTop, right, trackBottom, width / 2, width / 2, trackPaint)
        }
        thumbPaint.alpha = (255 * shown).toInt()
        canvas.drawRoundRect(right - width, top, right, top + thumbHeight, width / 2, width / 2, thumbPaint)

        if (!dragging) return
        val text = label(list.firstVisiblePosition) ?: return
        val bubbleWidth = maxOf(bubbleHeight, textPaint.measureText(text) + context.dp(32f))
        val bubbleRight = right - width - context.dp(16f)
        val centerY = (top + thumbHeight / 2).coerceIn(trackTop + bubbleHeight / 2, trackBottom - bubbleHeight / 2)
        canvas.drawRoundRect(
            bubbleRight - bubbleWidth, centerY - bubbleHeight / 2, bubbleRight, centerY + bubbleHeight / 2,
            bubbleHeight / 2, bubbleHeight / 2, bubblePaint,
        )
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, bubbleRight - bubbleWidth / 2, baseline, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shown < 0.5f || e.x < width - touchWidth || !scrollable()) return false
                dragging = true
                removeCallbacks(hide)
                fadeTo(1f)
                list.smoothScrollBy(0, 0) // dừng cú vuốt đang trôi
                parent?.requestDisallowInterceptTouchEvent(true)
                val thumbTop = trackTop + travel * fraction
                // Nắm trúng tay nắm thì giữ chỗ nắm; chạm vào rãnh thì nhảy tay nắm tới đó.
                grab = if (e.y in thumbTop..(thumbTop + thumbHeight)) e.y - thumbTop else thumbHeight / 2
                drag(e.y)
            }
            MotionEvent.ACTION_MOVE -> drag(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                postDelayed(hide, HIDE_DELAY)
                invalidate()
            }
        }
        return true
    }

    private fun drag(y: Float) {
        fraction = ((y - grab - trackTop) / travel).coerceIn(0f, 1f)
        scrollTo(fraction)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hide)
        fade?.cancel()
    }

    private companion object {
        const val HIDE_DELAY = 1500L
    }
}
