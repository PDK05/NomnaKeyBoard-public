package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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

/** Một nút trên thanh công cụ khi chưa gõ gì; có [icon] thì hiện icon thay chữ. [id]: tiện ích kéo thả được. */
class Tool(val label: String, val icon: Int? = null, val id: ToolId? = null, val action: () -> Unit)

/**
 * Thanh gợi ý: dải cuộn ngang các gợi ý (kiểu Sogou) và nút ⌄ mở bảng lưới.
 * Khi chưa gõ gì thì hiện hàng công cụ kiểu Gboard (dàn đều, nút cuối trong nền tròn).
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (Candidate) -> Unit, private val onExpand: (Boolean) -> Unit) :
    LinearLayout(context) {

    var theme = KeyboardTheme.of(context)

    private val strip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // Các ô chữ Hán chia đều bề rộng thanh; chỉ cuộn khi chữ Latin dài quá.
        isFillViewport = true
        addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    /** Icon căn giữa ô; mở bảng thì lật dọc quanh tâm (⌄ thành ⌃), không xê dịch. */
    private val expandButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_expand)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Mở bảng gợi ý"
        setOnClickListener { expanded = !expanded }
    }

    private val toolRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    var expanded = false
        set(v) {
            field = v
            expandButton.scaleY = if (v) -1f else 1f
            expandButton.contentDescription = if (v) "Đóng bảng gợi ý" else "Mở bảng gợi ý"
            onExpand(v)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(expandButton, LayoutParams(context.dpi(44), LayoutParams.MATCH_PARENT))
        addView(toolRow, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun showStrip(show: Boolean) {
        scroller.visibility = if (show) View.VISIBLE else View.GONE
        expandButton.visibility = if (show) View.INVISIBLE else View.GONE
        toolRow.visibility = if (show) View.GONE else View.VISIBLE
    }

    fun applyTheme() {
        resetToolCells()
        setBackgroundColor(theme.background)
        expandButton.setColorFilter(theme.text)
        // Vạch ngăn mảnh giữa các ô gợi ý, cách viền trên dưới.
        val line = GradientDrawable().apply {
            setColor(theme.hintText)
            alpha = 90
            setSize(context.dpi(1).coerceAtLeast(1), 0)
        }
        strip.dividerDrawable = android.graphics.drawable.InsetDrawable(line, 0, context.dpi(13), 0, context.dpi(13))
        strip.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        wordStrip.dividerDrawable = strip.dividerDrawable
        wordStrip.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    private companion object {
        /** Chỉ xét chừng này gợi ý đầu khi xếp thanh (danh sách có thể tới vài trăm). */
        const val MAX_SCAN = 40
        /** Tổng số chữ Hán tối đa trên thanh khi có cụm 3–6 chữ. */
        const val BUDGET = 11
        /** Cỡ chữ gợi ý khi thanh đủ chỗ (sp). */
        const val MAX_SP = 21f
        /** Cỡ chữ nhỏ nhất (sp) khi thu nhỏ nhiều gợi ý cho cùng vừa thanh. */
        const val MIN_SP = 15f
        /** Gợi ý đứng một mình thu nhỏ tới cỡ vừa đúng chừng này chữ; dài hơn thì giữ cỡ đó, thanh kéo ngang. */
        const val LONE_MAX_CHARS = 21
        /** Bề rộng ô nút đầu và nút góc của thanh công cụ (dp). */
        const val TOOL_EDGE = 52
        /** Bề rộng một nút trong khung nút ghim khi sửa (dp). */
        const val TOOL_CELL = 46
        /** Số ô gợi ý từ (Quốc ngữ / tiếng Anh) và cỡ chữ của chúng (sp). */
        const val WORD_SLOTS = 3
        const val WORD_SP = 17f
    }

    /** Cột trái của bảng gợi ý có lựa chọn thu hẹp cho chuỗi phím đang gõ không. */
    var canNarrow: () -> Boolean = { false }

    /** Vị trí (trong danh sách gợi ý) các gợi ý đang hiện trên thanh. */
    private var shown: Set<Int> = emptySet()

    /** Gợi ý chưa có trên thanh, cho bảng lưới (⌄). */
    fun hidden(items: List<Candidate>) = items.filterIndexed { i, _ -> i !in shown }

    /**
     * Xếp gợi ý lên thanh theo khuôn [fits] (số ô và cỡ các ô theo cụm chữ Hán dài nhất): duyệt theo
     * thứ tự xếp hạng, gợi ý nào thêm vào mà vẫn vừa khuôn thì lấy, không thì để dành cho bảng lưới.
     * Gợi ý đầu luôn có mặt. Chữ Latin tính theo bề rộng thật ([slotLength]).
     */
    fun showCandidates(items: List<Candidate>, emptyAction: Tool?) {
        showStrip(true)
        strip.removeAllViews()
        scroller.scrollTo(0, 0)
        val picked = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        for ((i, c) in items.withIndex().take(MAX_SCAN)) {
            val len = slotLength(c.text)
            lengths.add(len)
            if (picked.isEmpty() || fits(lengths)) picked.add(i) else lengths.removeAt(lengths.lastIndex)
        }
        // Tràn thanh thì thu nhỏ đều chữ cho vừa, tới MIN_SP. Nhỏ cỡ đó vẫn không vừa thì gợi ý đầu đứng
        // một mình (các gợi ý khác xem ở bảng ⌄) và thu nhỏ tiếp cho vừa, tới cỡ vừa đúng LONE_MAX_CHARS chữ;
        // dài hơn thì giữ cỡ đó, thanh kéo ngang.
        val room = availableWidth()
        // Ít gợi ý thì không giãn ra cả thanh: các ô vẫn rộng như khi thanh đủ ngăn theo khuôn [fits] (chữ đơn
        // 6 ngăn, từ kép 5 ngăn, cụm dài hơn 11 chữ), xếp từ ngăn 1 bên trái, phần còn lại để trống.
        fun weight(i: Int) = maxOf(slotLength(items[i].text), 2).toFloat()
        fun capacity(): Float {
            val han = picked.map { slotLength(items[it].text) }
            if (han.isEmpty()) return 0f
            return when (han.max()) {
                1 -> 6 * 2f
                2 -> 5 * 2f
                else -> BUDGET.toFloat()
            }
        }
        var size = fitSize(picked.map { items[it].text }, room)
        if (size < MIN_SP && picked.size > 1) {
            picked.subList(1, picked.size).clear()
            size = fitSize(picked.map { items[it].text }, room)
        }
        var fit = true
        if (picked.size == 1) {
            val text = items[picked[0]].text
            if (text.codePointCount(0, text.length) > LONE_MAX_CHARS) {
                size = fitSize(listOf(text.substring(0, text.offsetByCodePoints(0, LONE_MAX_CHARS))), room)
                fit = false
            }
        } else {
            size = size.coerceAtLeast(MIN_SP)
        }
        shown = picked.toSet()
        picked.forEachIndexed { n, i ->
            strip.addView(chip(items[i].text, highlight = n == 0, slot = true, fit = fit, sizeSp = size, weight = weight(i)) { onPick(items[i]) })
        }
        val weights = picked.map(::weight)
        strip.weightSum = if (!fit || weights.isEmpty()) 0f else maxOf(capacity(), weights.sum())
        if (items.isEmpty() && emptyAction != null) strip.addView(chip(emptyAction.label, small = true) { emptyAction.action() })
        // Thanh đã đủ mọi gợi ý vẫn mở được bảng khi cột trái còn thu hẹp được (gõ `deer` mà muốn rể: chọn r).
        val more = items.size > picked.size || (items.isNotEmpty() && canNarrow())
        expandButton.visibility = if (more) View.VISIBLE else View.INVISIBLE
        if (!more && expanded) expanded = false
    }

    /**
     * Gợi ý từ của bàn phím Quốc ngữ / tiếng Anh, nằm giữa nút ⊞ và nút góc của thanh công cụ (vẫn bấm được),
     * không có bảng gợi ý (⌄): đoạn giữa chia [WORD_SLOTS] ngăn đều nhau, các ô xếp từ trái qua như thanh gợi ý Hán
     * Nôm, có vạch ngăn giữa các ô, từ đầu tô màu nhấn. Ô nào chiếm mấy ngăn xem [wordSlots].
     */
    private fun fillWords(items: List<Candidate>) {
        wordStrip.removeAllViews()
        val slots = wordSlots(items)
        shown = slots.map { items.indexOf(it.first) }.toSet()
        wordStrip.weightSum = 0f
        slots.forEachIndexed { n, (c, w) ->
            wordStrip.addView(chip(c.text, highlight = n == 0, sizeSp = WORD_SP) { onPick(c) }.apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(w, LayoutParams.MATCH_PARENT)
            })
        }
    }

    /**
     * Các từ lên thanh, kèm bề rộng ô (px): từ vừa một ngăn thì rộng đúng một ngăn, dài hơn (exchanging) thì rộng vừa
     * chữ (không làm tròn lên số ngăn, khỏi trông như bị lùi vào). Duyệt theo thứ tự xếp hạng, từ nào còn đủ chỗ thì
     * lấy; từ đầu luôn có mặt (dài quá thì cắt bớt).
     */
    private fun wordSlots(items: List<Candidate>): List<Pair<Candidate, Int>> {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        // Trừ chỗ các vạch ngăn (mỗi vạch 1 px).
        val room = w - 2 * context.dpi(TOOL_EDGE) - (WORD_SLOTS - 1)
        val slot = room / WORD_SLOTS
        measurePaint.textSize = context.sp(WORD_SP)
        measurePaint.typeface = context.robotoTypeface()
        val pad = 2f * context.dpi(12)
        val out = ArrayList<Pair<Candidate, Int>>()
        var used = 0
        for (c in items) {
            if (room - used < slot) break
            val need = kotlin.math.ceil(measurePaint.measureText(c.text) + pad).toInt().coerceAtLeast(slot)
            when {
                out.isEmpty() -> need.coerceAtMost(room)
                used + need <= room -> need
                else -> null
            }?.let {
                out.add(c to it)
                used += it
            }
        }
        return out
    }

    /**
     * Các gợi ý có số chữ Hán [lengths] có vừa thanh không (tổng thường không quá [BUDGET] chữ):
     * - dài nhất 1 chữ: 6 ô; 2 chữ: 5 ô;
     * - 3–4 chữ: 4 ô, tổng ≤ 11 (vd. 4+4+2+1, 3+3+3+2);
     * - 5–6 chữ: 3 ô, tổng ≤ 11;
     * - 7 chữ: 3 ô, kèm một cụm ≤ 3 chữ và một chữ đơn;
     * - 8–10 chữ: 2 ô, kèm một cụm ≤ 3 chữ;
     * - 11 chữ: 2 ô, kèm một chữ đơn;
     * - trên 11 chữ: chỉ mình nó.
     * Chỉ một cụm dài (≥ 7 chữ). Chữ Latin tính theo [slotLength].
     */
    private fun fits(lengths: List<Int>): Boolean {
        val s = lengths.sortedDescending()
        val rest = s.drop(1)
        val slots = s.size
        return when {
            s[0] > 11 -> s.size == 1
            s[0] == 11 -> s.size <= 2 && rest.all { it <= 1 }
            s[0] >= 8 -> s.size <= 2 && rest.all { it <= 3 }
            s[0] == 7 -> s.size <= 3 && rest.getOrElse(0) { 0 } <= 3 && rest.getOrElse(1) { 0 } <= 1
            s[0] >= 5 -> slots <= 3 && s.sum() <= BUDGET
            s[0] >= 3 -> slots <= 4 && s.sum() <= BUDGET
            s[0] == 2 -> slots <= 5
            else -> slots <= 6
        }
    }

    /** Số chữ Hán (CJK) trong [text]; chữ Latin không tính. */
    private fun hanLength(text: String) = text.codePoints().filter { it >= 0x2E80 }.count().toInt()

    /**
     * Số ngăn [text] chiếm trên thanh, tính theo bề rộng một chữ Hán: chữ Hán thì bằng số chữ; chữ Latin (lựa chọn giữ
     * Quốc ngữ `đéo mẹ`) thì đo bề rộng thật, để ô Latin rộng không lấn mất chỗ của các ô chữ Hán.
     */
    private fun slotLength(text: String): Int {
        val han = hanLength(text)
        if (han > 0) return han
        measurePaint.textSize = context.sp(MAX_SP)
        measurePaint.typeface = context.robotoTypeface()
        val extraPad = 2f * (slotPadding(text) - context.dpi(4))
        return kotlin.math.ceil((measurePaint.measureText(text) + extraPad) / context.sp(MAX_SP)).toInt().coerceAtLeast(1)
    }

    // ---- Thanh công cụ: nút đầu (⊞ / ✕ / ✓), khung các nút ghim, nút góc phải trong nền tròn ----

    private val leadButton = toolButton()
    private val cornerButton = toolButton()
    /** Khung các nút ghim; sửa thì có viền màu nhấn, co vừa các nút và nằm giữa. */
    private val capsule = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val middle = FrameLayout(context)
    /** Gợi ý từ (Quốc ngữ / tiếng Anh), thay chỗ khung nút ghim ở giữa thanh công cụ. */
    private val wordStrip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    /** Ô của từng nút ghim, dùng lại để các nút trượt mượt khi kéo thả. */
    private val toolCells = HashMap<ToolId, FrameLayout>()
    private var toolsEditing = false

    /** Màn hình ngang: nút góc thu nhỏ nền tròn, nút ⊞ to hơn (đo trên Gboard). */
    var landscapeBar = false

    /** Giữ một nút trên thanh khi đang sửa (bắt đầu kéo). */
    var onToolDrag: ((ToolId) -> Unit)? = null

    init {
        toolRow.addView(leadButton.parent as View, LayoutParams(context.dpi(TOOL_EDGE), LayoutParams.MATCH_PARENT))
        middle.addView(capsule, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        middle.addView(wordStrip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        toolRow.addView(middle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        toolRow.addView(cornerButton.parent as View, LayoutParams(context.dpi(TOOL_EDGE), LayoutParams.MATCH_PARENT))
    }

    /** Nút icon 40dp nằm giữa một ô. */
    private fun toolButton(): ImageView {
        val icon = ImageView(context).apply {
            val pad = context.dpi(8)
            setPadding(pad, pad, pad, pad)
        }
        FrameLayout(context).addView(icon, FrameLayout.LayoutParams(context.dpi(40), context.dpi(40), Gravity.CENTER))
        return icon
    }

    val showingTools get() = toolRow.visibility == View.VISIBLE

    /**
     * Hàng công cụ: [lead] (⊞, ✕ khi bảng tiện ích mở, ✓ khi đang sửa), các nút ghim [row] dàn đều, nút góc [corner].
     * [editing]: khung nút ghim và nút góc có viền màu nhấn, giữ nút để kéo. Nút [hidden] (đang kéo) để trống chỗ;
     * [animate]: các nút trượt tới chỗ mới. Có [words] (gợi ý từ) thì chúng thay chỗ các nút ghim.
     */
    fun showTools(
        lead: Tool, row: List<Tool>, corner: Tool?, editing: Boolean, hidden: ToolId? = null, animate: Boolean = false,
        words: List<Candidate> = emptyList(),
    ) {
        if (expanded) expanded = false
        showStrip(false)
        toolsEditing = editing
        val wordMode = words.isNotEmpty() && !editing
        capsule.visibility = if (wordMode) View.GONE else View.VISIBLE
        wordStrip.visibility = if (wordMode) View.VISIBLE else View.GONE
        if (wordMode) fillWords(words) else wordStrip.removeAllViews()
        leadButton.setImageResource(lead.icon ?: 0)
        leadButton.setColorFilter(theme.text)
        leadButton.contentDescription = lead.label
        leadButton.background = null
        // Ngang: icon ⊞ vẽ 28dp (bốn ô rộng chừng 19dp như Gboard).
        val leadPad = context.dpi(if (landscapeBar && lead.icon == R.drawable.ic_apps) 6 else 8)
        leadButton.setPadding(leadPad, leadPad, leadPad, leadPad)
        leadButton.setOnClickListener { lead.action() }

        if (animate) TransitionManager.beginDelayedTransition(capsule, ChangeBounds().setDuration(160))
        capsule.removeAllViews()
        capsule.layoutParams = (capsule.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (editing) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT
            height = if (editing) context.dpi(40) else FrameLayout.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        capsule.background = if (editing) GradientDrawable().apply {
            cornerRadius = context.dp(20f)
            setStroke(context.dpi(2), theme.accent)
        } else null
        capsule.setPadding(if (editing) context.dpi(4) else 0, 0, if (editing) context.dpi(4) else 0, 0)
        // Khung trống (chưa ghim nút nào) vẫn rộng bằng một nút, để thấy chỗ thả.
        capsule.minimumWidth = if (editing) context.dpi(TOOL_CELL + 8) else 0
        for (t in row) {
            val id = t.id ?: continue
            val cell = toolCells.getOrPut(id) {
                val icon = toolButton().apply {
                    setImageResource(id.icon)
                    setColorFilter(theme.text)
                    contentDescription = id.label
                }
                icon.parent as FrameLayout
            }
            (cell.parent as? ViewGroup)?.removeView(cell)
            val icon = cell.getChildAt(0)
            icon.setOnClickListener { t.action() }
            bindDrag(icon, id, editing)
            cell.visibility = if (id == hidden) View.INVISIBLE else View.VISIBLE
            capsule.addView(cell, if (editing) LayoutParams(context.dpi(TOOL_CELL), LayoutParams.MATCH_PARENT) else LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        cornerButton.visibility = if (corner == null && !editing) View.INVISIBLE else View.VISIBLE
        cornerButton.setImageDrawable(if (corner == null || corner.id == hidden) null else context.getDrawable(corner.icon ?: 0))
        cornerButton.setColorFilter(theme.text)
        cornerButton.contentDescription = corner?.label
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.key)
            if (editing) setStroke(context.dpi(2), theme.accent)
        }
        // Màn hình ngang thanh chỉ cao 40dp: nền thu về 34dp như Gboard cho khỏi chạm mép, vùng bấm vẫn 40dp.
        val inset = if (landscapeBar) context.dpi(3) else 0
        val pad = context.dpi(if (landscapeBar) 9 else 8)
        cornerButton.background = android.graphics.drawable.InsetDrawable(oval, inset)
        // Sau khi đặt nền: InsetDrawable ghi đè padding của view.
        cornerButton.setPadding(pad, pad, pad, pad)
        cornerButton.setOnClickListener { corner?.action?.invoke() }
        bindDrag(cornerButton, corner?.id, editing)
    }

    private fun bindDrag(v: View, id: ToolId?, editing: Boolean) {
        if (!editing || id == null) {
            v.setOnLongClickListener(null)
            v.isLongClickable = false
            return
        }
        v.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onToolDrag?.invoke(id)
            true
        }
    }

    /** Màu đổi thì dựng lại các nút ghim. */
    private fun resetToolCells() = toolCells.clear()

    /** Đoạn giữa thanh (giữa nút đầu và nút góc), theo toạ độ cửa sổ: thả vào đây là ghim. */
    fun middleBounds(): android.graphics.RectF = windowRect(middle)

    /** Nút góc theo toạ độ cửa sổ (nới ra cả ô). */
    fun cornerBounds(): android.graphics.RectF = windowRect(cornerButton.parent as View)

    /**
     * Chỗ chèn trong khung nút ghim ứng với [x] (toạ độ cửa sổ) khi khung đã có [count] nút (không kể nút đang kéo):
     * khung thêm một ô thì rộng `(count + 1)` ô, nằm giữa đoạn giữa thanh.
     */
    fun rowIndexAt(x: Float, count: Int): Int {
        val r = middleBounds()
        val cell = context.dp(TOOL_CELL.toFloat())
        val left = r.centerX() - (count + 1) * cell / 2f
        return ((x - left) / cell).toInt().coerceIn(0, count)
    }

    private fun windowRect(v: View): android.graphics.RectF {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return android.graphics.RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    /** Bề rộng dành cho các ô gợi ý (trừ nút ⌄). */
    private fun availableWidth(): Float {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        return (w - context.dpi(44)).toFloat()
    }

    /** Lề trái + phải của một ô gợi ý: chữ Hán sát nhau hơn, chữ Latin cần thoáng cho khỏi dính. */
    private fun slotPadding(text: String) = context.dpi(if (hanLength(text) > 0) 4 else 12)

    /**
     * Cỡ chữ (sp, tối đa MAX_SP) để các gợi ý [texts] cùng vừa bề rộng [room]; có thể nhỏ hơn MIN_SP,
     * nơi gọi tự chặn. Ô nào hẹp hơn bề rộng tối thiểu (44dp) thì tính bằng bề rộng tối thiểu.
     */
    private fun fitSize(texts: List<String>, room: Float): Float {
        measurePaint.textSize = context.sp(MAX_SP)
        measurePaint.typeface = context.hanNomTypeface()
        val min = context.dp(44f)
        val text = texts.map { measurePaint.measureText(it) }
        val pad = texts.map { 2f * slotPadding(it) }
        // Chừa chút dư cho sai số đo (phông dự phòng, khoảng cách chữ).
        val usable = room * 0.97f
        var scale = 1f
        // Lặp vài lần: ô nào chạm bề rộng tối thiểu thì thôi co, phần còn lại co thêm.
        repeat(3) {
            val fixed = text.indices.filter { text[it] * scale + pad[it] <= min }
            val fixedWidth = fixed.size * min
            val flexText = text.indices.filter { it !in fixed }.sumOf { text[it].toDouble() }.toFloat()
            val flexPad = text.indices.filter { it !in fixed }.sumOf { pad[it].toDouble() }.toFloat()
            scale = if (flexText <= 0f) 1f else ((usable - fixedWidth - flexPad) / flexText).coerceIn(0f, 1f)
        }
        return MAX_SP * scale
    }

    private val measurePaint = android.graphics.Paint()

    /**
     * [slot]: một ô gợi ý cỡ chữ [sizeSp], một dòng. Vừa thanh ([fit]) thì ô chữ Hán rộng theo số chữ;
     * không vừa thì rộng vừa chữ, thanh kéo ngang. Chữ Latin rộng vừa đủ.
     */
    private fun chip(
        text: String,
        highlight: Boolean = false,
        small: Boolean = false,
        slot: Boolean = false,
        fit: Boolean = true,
        sizeSp: Float = MAX_SP,
        weight: Float = 0f,
        onClick: () -> Unit,
    ) =
        TextView(context).apply {
            this.text = if (small) text else context.hanNomText(text)
            gravity = Gravity.CENTER
            setTextColor(if (highlight) theme.accent else theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 15f else sizeSp)
            minWidth = context.dpi(44)
            setPadding(context.dpi(12), 0, context.dpi(12), 0)
            if (small) {
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(16f)
                    setColor(theme.specialKey)
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, context.dpi(32)).apply {
                    marginStart = context.dpi(6)
                }
                setOnClickListener { onClick() }
                return@apply
            }
            // Một dòng, không xuống dòng: chữ thiếu chỗ thì cắt ở cuối chứ không đẩy dòng đầu ra khỏi ô.
            isSingleLine = true
            val pad = if (slot) slotPadding(text) else context.dpi(12)
            setPadding(pad, 0, pad, 0)
            layoutParams = if (slot && fit && weight > 0f) {
                // Rộng theo số ngăn (cụm dài không bị ép bằng ô chữ đơn); chữ đơn, từ kép tối thiểu như nhau.
                LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
            } else {
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            }
            setOnClickListener { onClick() }
        }
}

/**
 * Bảng gợi ý thả xuống (nút ⌄), thay chỗ bàn phím, kiểu Gboard pinyin:
 * - cột trái: thu hẹp âm tiết đầu từng cấp theo âm đọc trong từ điển (lõi `Engine::narrow`), lưới chỉ còn chữ
 *   một âm tiết để chọn xong chữ của âm đầu rồi mới tới phím sau (như pinyin): phụ âm đầu →
 *   nguyên âm đầu → vần → phụ âm cuối → cả âm có thanh; cấp chỉ có một lựa chọn tự bỏ qua
 *   (`chnc` hỏi vần ngay; chọn âu → châu, chầu, chấu…). Lưới chỉ còn gợi ý khớp các lựa chọn; mục "‹" ở
 *   đầu cột bỏ lựa chọn cuối. Mỗi mục cao bằng một hàng lưới; vạch dọc ngăn cột với lưới. Dưới cùng là
 *   nút 𡨸單 chỉ xem gợi ý một chữ;
 * - giữa: lưới gợi ý theo hàng, vạch mảnh ngăn các ô, cuộn dọc; gợi ý kế không vừa hàng thì ô cuối giãn
 *   ra cho kín hàng (hàng cuối thì không);
 * - cột phải: lật trang lên / xuống, xoá, Enter.
 */
@SuppressLint("ViewConstructor")
class CandidateGrid(context: Context, private val onPick: (Candidate) -> Unit) : LinearLayout(context) {
    var theme = KeyboardTheme.of(context)
    /**
     * Lõi, cho chuỗi phím đang gõ: cấp thu hẹp kế tiếp sau các lựa chọn (cấp, giá trị) và các lựa chọn của
     * nó (`null` khi hết cấp); và mọi gợi ý khớp các lựa chọn, không bị giới hạn số gợi ý như danh sách đưa
     * vào [show] (`c` có hàng nghìn gợi ý, `cực` nằm cuối).
     */
    var narrow: (List<Pair<Int, String>>) -> Pair<Int, List<String>>? = { null }
    var narrowed: (List<Pair<Int, String>>) -> List<Candidate> = { emptyList() }
    var onDelete: () -> Unit = {}
    var onEnter: () -> Unit = {}

    private val filterColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val filterScroll = ScrollView(context).apply {
        // Thanh cuộn mảnh bên phải (như Gboard) cho biết còn lựa chọn bên dưới.
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
        addView(filterColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    /** Nút 𡨸單 (chữ đơn, như 单字 của Gboard): chữ Nôm, vạch ngắn bên dưới; bật thì tô màu nhấn. */
    private val singleLabel = TextView(context).apply {
        text = context.hanNomText("𡨸單")
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        contentDescription = "Chữ đơn"
    }
    private val singleLine = View(context)
    private val singleButton = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(singleLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(singleLine, LayoutParams(context.dpi(20), context.dpi(2)).apply { topMargin = context.dpi(2) })
        setOnClickListener {
            single = !single
            render()
        }
    }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val pageScroll = object : ScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            loadMoreIfNeeded()
            updatePaging()
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val upButton = sideButton(text = "▲") { pageScroll.smoothScrollBy(0, -pageHeight()) }
    private val downButton = sideButton(text = "▼") {
        loadMoreIfNeeded()
        pageScroll.smoothScrollBy(0, pageHeight())
    }
    /** Đang thu hẹp ở cột trái thì xoá là lùi một bước thu hẹp; hết bước mới xoá chữ / phím như thường. */
    private val deleteButton = sideButton(icon = R.drawable.ic_backspace) { if (picked.isNotEmpty()) pick(null) else onDelete() }
    private val enterButton = sideButton(icon = R.drawable.ic_return, accent = true) { onEnter() }

    private var all: List<Candidate> = emptyList()
    private var hidden: List<Candidate> = emptyList()
    private var keys = ""
    /** Mỗi lần chọn chữ trong lưới: chuỗi phím lúc đó và các bước thu hẹp đã chọn, để xoá chữ thì trở lại. */
    private val history = ArrayList<Pair<String, List<Pair<Int, String>>>>()
    /** Các lựa chọn thu hẹp đã chọn (cấp, giá trị); rỗng là xem tất cả. */
    private val picked = ArrayList<Pair<Int, String>>()
    /** Cấp kế tiếp và các lựa chọn của nó. */
    private var level: Pair<Int, List<String>>? = null
    private var single = false
    private var rowHeight = context.dpi(52)
    private val columnLine = View(context)
    private val leftColumn = LinearLayout(context).apply { orientation = VERTICAL }

    /** Có cột trái (thu hẹp, 𡨸單) không; viết tay thì không, lưới chiếm cả bề ngang (như Gboard). */
    var filters = true
        set(v) {
            field = v
            leftColumn.visibility = if (v) View.VISIBLE else View.GONE
            columnLine.visibility = leftColumn.visibility
        }

    init {
        orientation = HORIZONTAL
        addView(leftColumn.apply {
            addView(filterScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(singleButton, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        }, LayoutParams(context.dpi(64), LayoutParams.MATCH_PARENT))
        addView(columnLine, LayoutParams(context.dpi(1).coerceAtLeast(1), LayoutParams.MATCH_PARENT).apply {
            setMargins(0, context.dpi(8), 0, context.dpi(8))
        })
        addView(pageScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dpi(6), context.dpi(6), context.dpi(8), context.dpi(6))
            listOf(upButton, downButton, deleteButton, enterButton).forEach {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, context.dpi(4), 0, context.dpi(4)) })
            }
        }, LayoutParams(context.dpi(72), LayoutParams.MATCH_PARENT))
    }

    /** [all]: mọi gợi ý; [hidden]: các gợi ý chưa có trên thanh (bảng hiện các gợi ý này khi không lọc). */
    fun show(all: List<Candidate>, hidden: List<Candidate>, keys: String) {
        if (keys != this.keys) {
            picked.clear()
            single = false
            // Xoá chữ vừa chọn trong lưới (chuỗi phím trở lại như trước khi chọn): trở lại đúng các bước thu
            // hẹp lúc chọn nó, không phải thu hẹp lại từ đầu.
            val back = history.lastOrNull()
            if (back != null && back.first == keys) {
                history.removeAt(history.lastIndex)
                picked.addAll(back.second)
            } else if (keys.isEmpty()) {
                history.clear()
            }
        }
        this.all = all
        this.hidden = hidden
        this.keys = keys
        level = narrow(picked)
        render()
    }

    private fun pick(choice: Pair<Int, String>?) {
        if (choice == null) picked.removeAt(picked.lastIndex) else picked.add(choice)
        level = narrow(picked)
        render()
    }

    /** Chiều cao bảng (bằng bàn phím): bốn hàng gợi ý mỗi trang. */
    fun setHeight(height: Int) {
        val h = (height / 4).coerceAtLeast(context.dpi(40))
        if (h != rowHeight) {
            rowHeight = h
            singleButton.layoutParams.height = h
            singleButton.requestLayout()
            render()
        }
    }

    private fun pageHeight() = (pageScroll.height / rowHeight).coerceAtLeast(1) * rowHeight

    private fun updatePaging() {
        upButton.alpha = if (pageScroll.canScrollVertically(-1)) 1f else 0.4f
        downButton.alpha = if (pageScroll.canScrollVertically(1) || next < items.size) 1f else 0.4f
    }

    private fun render() {
        setBackgroundColor(theme.background)
        columnLine.background = GradientDrawable().apply {
            setColor(theme.hintText)
            alpha = 90
        }
        renderFilters()
        val singleColor = if (single) theme.accent else theme.text
        singleLabel.setTextColor(singleColor)
        singleLine.background = GradientDrawable().apply {
            cornerRadius = context.dp(1f)
            setColor(singleColor)
        }

        val source = when {
            picked.isNotEmpty() -> narrowed(picked)
            single -> all
            // Thanh đã hiện hết (mở bảng chỉ để thu hẹp): lưới để trống, không lặp lại gợi ý trên thanh. Riêng khi
            // cột trái cũng không có gì để chọn thì vẫn hiện, khỏi ra bảng trắng.
            level == null -> hidden.ifEmpty { all }
            else -> hidden
        }
        items = source.filter { !single || hanLength(it.text) <= 1 }
        rows.removeAllViews()
        pageScroll.scrollTo(0, 0)
        next = 0
        lastRow = null
        appendRows(FIRST_ROWS)
        pageScroll.post { updatePaging() }
    }

    /** Gợi ý của lưới đang hiện; chỉ dựng ô tới [next], cuộn gần cuối thì dựng thêm (vài trăm ô một lúc thì giật). */
    private var items: List<Candidate> = emptyList()
    private var next = 0
    /** Hàng cuối đang xếp dở và số ngăn đã dùng. */
    private var lastRow: LinearLayout? = null
    private var used = 0

    /** Dựng thêm tối đa [count] hàng lưới. */
    private fun appendRows(count: Int) {
        var added = 0
        while (next < items.size) {
            val c = items[next]
            val units = units(c.text)
            var row = lastRow
            if (row != null && used + units > UNITS) {
                // Hàng vừa xong còn thừa ngăn: ô cuối giãn ra cho kín hàng.
                row.getChildAt(row.childCount - 1)?.let { last ->
                    (last.layoutParams as LayoutParams).weight += (UNITS - used).toFloat()
                    last.requestLayout()
                }
                row = null
                lastRow = null
            }
            if (row == null) {
                if (added == count) break
                row = newRow()
                rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
                lastRow = row
                used = 0
                added++
            }
            row.addView(cell(c), LayoutParams(0, LayoutParams.MATCH_PARENT, units.toFloat()))
            used += units
            next++
        }
    }

    /** Cuộn còn chưa tới hai trang là hết phần đã dựng thì dựng thêm. */
    private fun loadMoreIfNeeded() {
        if (next < items.size && pageScroll.scrollY + 3 * pageScroll.height >= rows.height) appendRows(MORE_ROWS)
    }

    private fun renderFilters() {
        filterColumn.removeAllViews()
        filterScroll.scrollTo(0, 0)
        fun item(label: String, color: Int, size: Float, onClick: (() -> Unit)?) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            setPadding(context.dpi(4), 0, context.dpi(4), 0)
            onClick?.let { setOnClickListener { it() } }
        }
        // Đã chọn: "‹ châu…", chọn tới cả âm có thanh thì "‹ chấu" (màu nhấn, có vạch bên phải như Gboard),
        // chạm để bỏ lựa chọn cuối.
        if (picked.isNotEmpty()) {
            filterColumn.addView(item("‹ " + pickedLabel(), theme.accent, 15f) { pick(null) }.apply {
                // Âm dài (nghiêng…) thu nhỏ chữ cho vừa cột thay vì bị cắt.
                ellipsize = null
                setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP)
                background = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(theme.accent) }))
                    .apply { setLayerInset(0, context.dpi(60), context.dpi(14), 0, context.dpi(14)) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        }
        val (part, options) = level ?: return
        for (option in options) {
            // Chỉ phần đang hỏi (qu → ô → ô → c), không ghép cả cụm; phụ âm đầu / cuối rỗng hiện "–".
            filterColumn.addView(item(option.ifEmpty { "–" }, theme.text, 17f) { pick(part to option) }.apply {
                contentDescription = "${PART_NAMES.getOrElse(part) { "" }} ${option.ifEmpty { "không có" }}"
            }, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        }
    }

    /**
     * Phần âm tiết đã chọn, ghép liền như chữ viết: phụ âm đầu + vần (hay nguyên âm đầu) + phụ âm cuối, chưa
     * tới thanh thì thêm "…"; đã chọn cả âm có thanh thì là âm đó (c, ư, c → "cưc…"; rồi cực → "cực").
     */
    private fun pickedLabel(): String {
        val by = picked.toMap()
        by[PART_SYLLABLE]?.let { return it }
        val rhyme = by[PART_NUCLEUS] ?: by[PART_VOWEL].orEmpty()
        return by[PART_INITIAL].orEmpty() + rhyme + by[PART_FINAL].orEmpty() + "…"
    }

    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        weightSum = UNITS.toFloat()
        // Vạch ngăn mảnh giữa các ô, cách viền trên dưới (như thanh gợi ý).
        dividerDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply {
                setColor(theme.hintText)
                alpha = 90
                setSize(context.dpi(1).coerceAtLeast(1), 0)
            },
            0, context.dpi(16), 0, context.dpi(16),
        )
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    private fun cell(c: Candidate) = TextView(context).apply {
        text = context.hanNomText(c.text)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (hanLength(c.text) > 0) 22f else 18f)
        setPadding(context.dpi(4), 0, context.dpi(4), 0)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(theme.pressedKey), null, android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE),
        )
        setOnClickListener {
            history.add(keys to picked.toList())
            onPick(c)
        }
    }

    /** Số ô (trên [UNITS] ô một hàng) của một gợi ý: chữ đơn 1, từ kép 2, cụm 3–4 chữ 3, dài hơn cả hàng. */
    private fun units(text: String): Int {
        val han = hanLength(text)
        return when {
            han == 0 -> ((text.length + 3) / 4).coerceIn(1, UNITS)
            han == 1 -> 1
            han == 2 -> 2
            han <= 4 -> 3
            else -> UNITS
        }
    }

    private fun hanLength(text: String) = text.codePoints().filter { it >= 0x2E80 }.count().toInt()

    /** Nút cột phải: nền phím chức năng bo góc; Enter nền màu nhấn bo tròn. */
    private fun sideButton(text: String? = null, icon: Int? = null, accent: Boolean = false, onClick: () -> Unit): View {
        val view: View = if (icon != null) ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
        } else TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        view.setOnClickListener { onClick() }
        view.tag = accent
        return view
    }

    /** Tô lại các nút cột phải theo [theme] (gọi khi đổi giao diện). */
    fun applyTheme() {
        listOf(upButton, downButton, deleteButton, enterButton).forEach { v ->
            val accent = v.tag == true
            v.background = GradientDrawable().apply {
                cornerRadius = context.dp(if (accent) 28f else 10f)
                setColor(if (accent) theme.accent else theme.specialKey)
            }
            val fg = if (accent) theme.onAccent else theme.text
            (v as? ImageView)?.setColorFilter(fg)
            (v as? TextView)?.setTextColor(fg)
        }
        render()
    }

    private companion object {
        /** Số ô một hàng (chữ đơn). */
        const val UNITS = 6
        /** Số hàng dựng sẵn khi mở / lọc lưới (vài trang, mỗi trang bốn hàng), và mỗi lần cuộn gần hết. */
        const val FIRST_ROWS = 12
        const val MORE_ROWS = 12
        /** Tên từng cấp thu hẹp, theo thứ tự `Part` của lõi. */
        val PART_NAMES = listOf("âm đầu", "nguyên âm", "vần", "âm cuối", "thanh")
        const val PART_INITIAL = 0
        const val PART_VOWEL = 1
        const val PART_NUCLEUS = 2
        const val PART_FINAL = 3
        const val PART_SYLLABLE = 4
    }
}

/**
 * Gốc giao diện bàn phím: khung gõ + thanh gợi ý + (bàn phím | bảng lưới), overlay phủ lên trên.
 *
 * Khung gõ (kiểu khung pinyin) nằm trên một dải trong suốt ở trên cùng; [HanNomIME.onComputeInsets]
 * báo dải này không che và không nhận chạm, nên ứng dụng bên dưới vẫn thấy và bấm được.
 */
class InputRoot(
    context: Context,
    listener: KeyboardView.Listener,
    padListener: HandwritingView.Listener,
    onPick: (Candidate) -> Unit,
    onExpand: (Boolean) -> Unit,
    emojiListener: EmojiPanel.Listener,
    private val toolsListener: ToolsPanel.Listener,
    clipListener: ClipboardPanel.Listener,
    editListener: TextEditPanel.Listener,
) : FrameLayout(context) {
    val overlay = OverlayView(context)
    val keyboard = KeyboardView(context, listener, overlay)
    val bar = CandidateBar(context, onPick, onExpand)
    val grid = CandidateGrid(context, onPick)
    /** Bảng emoji, thay chỗ cả thanh gợi ý và bàn phím khi mở. */
    val emoji = EmojiPanel(context, emojiListener)
    /** Tìm emoji: nằm trên thanh gợi ý, bàn phím thường hiện bên dưới để gõ chữ tìm. */
    val emojiSearch = EmojiSearchView(context, { emojiListener.onEmojiSearchBack() }, emoji::pickSearchResult, emoji::searchResults,
        emoji::searchTones)
    /** Bảng tiện ích (nút ⊞), thay chỗ bàn phím; thanh công cụ vẫn ở trên. */
    val tools = ToolsPanel(context, { toolsListener.onToolUse(it) }, { editTools(true) }, ::startToolDrag)
    /** Bảng nhớ tạm, thay chỗ thanh gợi ý và bàn phím như bảng emoji. */
    val clipboard = ClipboardPanel(context, clipListener)
    /** Bảng sửa văn bản, thay chỗ bàn phím như bảng tiện ích. */
    val textEdit = TextEditPanel(context, editListener)
    /** Vùng viết tay, phủ lên phần trên hàng phím cách của [keyboard]. */
    val pad = HandwritingView(context, padListener)
    private var padActive = false
    private val body = FrameLayout(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /**
     * Thanh gợi ý và bàn phím (cả các bảng thay chỗ bàn phím): bàn phím một tay thì cả khối thu hẹp, dồn về một bên
     * ([panelBounds]), bên kia là cột nút [side] (như Gboard).
     */
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panelRow = FrameLayout(context)
    private val side = OneHandSide(context, listener).apply { visibility = View.GONE }
    private val composeRow = FrameLayout(context)
    private val composing = ComposingView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // Một dòng; dài quá bề rộng thì ẩn phần đầu, luôn thấy chữ đang gõ ở cuối.
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.START
        setPadding(context.dpi(12), 0, context.dpi(12), 0)
        visibility = View.INVISIBLE
    }
    private val backgroundPaint = Paint()

    /** Chế độ "Kích thước" (như Resize của Gboard), phủ thanh gợi ý và bàn phím. */
    private val resize = ResizeOverlay(context, object : ResizeOverlay.Listener {
        override fun onResizeStart() {
            editRow = resizeRow
            editGap = resizeGap
            editWidth = oneHandWidth
            editShift = oneHandShift
            dragging = true
        }

        override fun onResizeTop(dy: Float): Boolean {
            // Kéo cạnh trên lên dy px: cả trang (MAX_ROWS hàng) cao thêm chừng đó.
            // Cao nhất: theo chiều cao hàng phím, và đỉnh khung không lên quá phần [MAX_SHARE] màn hình.
            val want = editRow - dy / context.dp(1f) / pageRows
            val maxRow = (editRow + (maxGap(editRow) - editGap) / pageRows)
                .coerceIn(editRow.coerceAtMost(Settings.MAX_ROW_HEIGHT.toFloat()), Settings.MAX_ROW_HEIGHT.toFloat())
            editRow = want.coerceIn(minRow, maxRow)
            showFrame()
            return editRow != want
        }

        override fun onResizeBottom(dy: Float): Boolean {
            // Cạnh dưới lên `up` dp: khoảng trống rộng thêm chừng đó, cả trang thấp đi chừng đó (cạnh trên đứng yên).
            // Cạnh trên đứng yên nên chỉ bị chặn khi hàng phím thấp / cao hết mức (khoảng trống không có trần riêng).
            val rows = pageRows
            val want = -dy / context.dp(1f)
            val up = want
                .coerceIn(-editGap, Settings.MAX_BOTTOM_GAP - editGap)
                .coerceIn((editRow - Settings.MAX_ROW_HEIGHT) * rows, (editRow - minRow) * rows)
            editGap += up
            editRow -= up / rows
            showFrame()
            return up != want
        }

        override fun onResizeLeft(dx: Float): Boolean {
            val (l, r) = panelBounds(width, editWidth, editShift)
            return setEditEdges(l + dx, r.toFloat(), keepRight = true)
        }

        override fun onResizeRight(dx: Float): Boolean {
            val (l, r) = panelBounds(width, editWidth, editShift)
            return setEditEdges(l.toFloat(), r + dx)
        }

        override fun onResizeMove(dx: Float, dy: Float): Pair<Boolean, Boolean> {
            val want = editGap - dy / context.dp(1f)
            editGap = want.coerceIn(0f, maxOf(maxGap(editRow), editGap.coerceAtMost(want)))
            var stopX = false
            if (keyboard.effectiveOneHand != OneHand.OFF && dx != 0f) {
                // Một tay: dời ngang, giữ bề rộng, tới mép màn hình (cột nút sẽ sang bên rộng hơn).
                val (l, r) = panelBounds(width, editWidth, editShift)
                val d = dx.coerceIn(-l.toFloat(), (width - r).toFloat())
                setEditEdges(l + d, r + d)
                stopX = d != dx
            }
            showFrame()
            return stopX to (editGap != want)
        }

        override fun onResizeEnd() {
            // Thả tay: bàn phím đổi theo khung.
            dragging = false
            resizeRow = editRow
            resizeGap = editGap
            oneHandWidth = editWidth
            oneHandShift = editShift
            settleOneHand()
            setRowHeight(context.dp(resizeRow))
            requestApplyInsets()
            relayoutPanel()
        }

        override fun onResizeReset() {
            resizeRow = (if (landscape) landscapeDefaultRow else Settings.ROW_HEIGHTS[1]).toFloat()
            resizeGap = 0f
            oneHandWidth = Settings.DEFAULT_ONE_HAND_WIDTH
            oneHandShift = 0f
            setRowHeight(context.dp(resizeRow))
            requestApplyInsets()
            relayoutPanel()
        }

        override fun onResizeDone() {
            val settings = Settings(context)
            val row = kotlin.math.round(resizeRow).toInt()
            val gap = kotlin.math.round(resizeGap).toInt()
            if (landscape) {
                settings.landscapeRowHeight = row
                settings.landscapeBottomGap = gap
            } else {
                val level = Settings.ROW_HEIGHTS.indexOf(row)
                // Trùng một mức có sẵn thì lưu là mức đó (cài đặt hiện Thấp / Vừa / Cao), không thì là tuỳ chỉnh.
                if (level >= 0) settings.height = level else settings.rowHeight = row
                settings.bottomGap = gap
            }
            settings.oneHandWidth = oneHandWidth
            settings.oneHandShift = kotlin.math.round(oneHandShift).toInt()
            if (keyboard.oneHand != OneHand.OFF) settings.oneHand = keyboard.oneHand
            showResize(false)
        }
    })

    /** Bàn phím một tay: bề rộng so với màn hình và khoảng cách (dp) từ mép phía dồn về; đang chỉnh thì chưa lưu. */
    private var oneHandWidth = Settings(context).oneHandWidth
    private var oneHandShift = Settings(context).oneHandShift.toFloat()

    /**
     * Một tay: bề rộng lớn nhất (px) khối bàn phím trên bề rộng [w]. Kéo cạnh chỉ thu hẹp được, không rộng hơn mặc
     * định (như Gboard); dời bằng ✥ thì đi đâu cũng được trong bề ngang màn hình.
     */
    private fun maxPanelWidth(w: Int): Float = w * Settings.DEFAULT_ONE_HAND_WIDTH

    /**
     * Khoảng ngang (px) của thanh gợi ý + bàn phím trên bề rộng [w]: cả bề rộng, hay phần dồn về một bên khi một tay
     * (bề rộng [widthRatio] so với màn hình, cách mép phía dồn về [shiftDp]).
     */
    private fun panelBounds(w: Int, widthRatio: Float = oneHandWidth, shiftDp: Float = oneHandShift): Pair<Int, Int> {
        val mode = keyboard.effectiveOneHand
        if (mode == OneHand.OFF || w <= 0) return 0 to w
        val pw = (w * widthRatio).toInt().coerceIn((w * Settings.MIN_ONE_HAND_WIDTH).toInt(), maxPanelWidth(w).toInt())
        val shift = context.dp(shiftDp).toInt().coerceIn(0, w - pw)
        return if (mode == OneHand.LEFT) shift to shift + pw else w - shift - pw to w - shift
    }

    /**
     * Một tay: bên "dồn về" luôn là bên gần khối bàn phím hơn, cột nút ở bên rộng hơn (như Gboard). Dời qua giữa thì
     * đổi bên, khối bàn phím giữ chỗ (khoảng cách tính lại từ mép bên kia).
     */
    private fun settleOneHand() {
        val mode = keyboard.effectiveOneHand
        val w = width
        if (mode == OneHand.OFF || w <= 0) return
        val (l, r) = panelBounds(w)
        val flip = if (mode == OneHand.LEFT) l > w - r else w - r > l
        if (!flip) return
        oneHandShift = (if (mode == OneHand.LEFT) w - r else l) / context.dp(1f)
        keyboard.oneHand = if (mode == OneHand.LEFT) OneHand.RIGHT else OneHand.LEFT
    }

    /**
     * Đang kéo: đặt khoảng ngang khung một tay là [l]..[r] (px), giữ trong giới hạn; đổi ra bề rộng và khoảng cách.
     * [keepRight]: đang kéo cạnh trái, cạnh phải đứng yên (hẹp quá thì chặn cạnh trái chứ không đẩy cạnh phải).
     * Trả về `true` nếu bị chặn ở giới hạn.
     */
    private fun setEditEdges(l: Float, r: Float, keepRight: Boolean = false): Boolean {
        val w = width
        if (w <= 0 || keyboard.effectiveOneHand == OneHand.OFF) return false
        val minW = w * Settings.MIN_ONE_HAND_WIDTH
        val maxW = maxPanelWidth(w)
        val leftMin = if (keepRight) maxOf(0f, r - maxW) else 0f
        val leftMax = if (keepRight) r - minW else w - minW
        val left = l.coerceIn(leftMin, leftMax.coerceAtLeast(leftMin))
        val right = r.coerceIn(left + minW, minOf(w.toFloat(), left + maxW).coerceAtLeast(left + minW))
        editWidth = (right - left) / w
        val shift = if (keyboard.effectiveOneHand == OneHand.LEFT) left else w - right
        editShift = shift / context.dp(1f)
        showFrame()
        return kotlin.math.abs(left - l) > 0.5f || kotlin.math.abs(right - r) > 0.5f
    }

    /**
     * Khoảng trống dưới đáy lớn nhất (dp) khi hàng phím cao [row] dp: đỉnh khung (thanh gợi ý) không lên quá phần
     * [MAX_SHARE] (ngang: [LANDSCAPE_MAX_SHARE]) chiều cao view tính từ đáy (lúc chỉnh kích thước view cao gần hết
     * màn hình), như Gboard.
     */
    private fun maxGap(row: Float): Float {
        val dp = context.dp(1f)
        val panelH = panel.height + (row - resizeRow) * pageRows * dp
        val system = paddingBottom - resizeGap * dp
        val share = if (landscape) LANDSCAPE_MAX_SHARE else MAX_SHARE
        return ((height * share - system - panelH) / dp).coerceIn(0f, Settings.MAX_BOTTOM_GAP.toFloat())
    }

    /** Chiều cao cả trang phím tính theo số hàng chữ. */
    private val pageRows get() = if (landscape) LANDSCAPE_PAGE_ROWS else Layouts.MAX_ROWS.toFloat()
    private val minRow get() = (if (landscape) Settings.MIN_LANDSCAPE_ROW_HEIGHT else Settings.MIN_ROW_HEIGHT).toFloat()

    /** Đang kéo: số đo mới (hàng phím, khoảng trống dưới đáy, bề rộng / khoảng cách một tay), thả tay mới áp. */
    private var dragging = false
    private var editRow = 0f
    private var editGap = 0f
    private var editWidth = 0f
    private var editShift = 0f

    /**
     * Đặt khung của [resize]: đang kéo thì khung xem trước theo số đo mới (bàn phím đứng yên), không thì đúng khối bàn
     * phím. Toạ độ của [InputRoot] (cũng là của [resize], phủ cả view).
     */
    private fun showFrame() {
        val dp = context.dp(1f)
        val top = panelTop.toFloat()
        val bottom = top + panel.height
        val frame = if (dragging) {
            // Khoảng trống dưới đáy rộng thêm thì cạnh dưới lên chừng đó; hàng phím cao thêm thì cả trang cao thêm.
            val (l, r) = panelBounds(width, editWidth, editShift)
            val b = bottom - (editGap - resizeGap) * dp
            val t = b - panel.height - (editRow - resizeRow) * pageRows * dp
            RectF(l.toFloat(), t, r.toFloat(), b)
        } else {
            val (l, r) = panelBounds(width)
            RectF(l.toFloat(), top, r.toFloat(), bottom)
        }
        resize.setFrame(frame, top, bottom, sides = keyboard.effectiveOneHand != OneHand.OFF)
    }

    /**
     * Khối bàn phím đổi chỗ / bề rộng: đánh dấu cả các view con cần đo lại (requestLayout của gốc không làm vậy, các
     * view con giữ kết quả đo cũ vì kích thước gốc truyền xuống không đổi).
     */
    private fun relayoutPanel() {
        panel.requestLayout()
        side.requestLayout()
        composing.requestLayout()
    }
    /** Đang chỉnh trong chế độ "Kích thước": chiều cao hàng phím và khoảng trống dưới đáy (dp), chưa lưu. */
    private var resizeRow = 0f
    private var resizeGap = 0f

    val resizeShown get() = resize.visibility == View.VISIBLE

    /** Mở / đóng chế độ "Kích thước". Đóng mà chưa bấm "Xong" thì trả lại kích thước đã lưu. */
    fun showResize(show: Boolean) {
        if (show == resizeShown) return
        val settings = Settings(context)
        if (show) {
            showEmoji(false)
            showEmojiSearch(false)
            if (bar.expanded) bar.expanded = false
            resizeRow = savedRow(settings).toFloat()
            resizeGap = savedGap(settings).toFloat()
        } else {
            setRowHeight(context.dp(savedRow(settings).toFloat()))
        }
        loadOneHand(settings)
        if (show) resize.style = keyboard.style
        resize.visibility = if (show) View.VISIBLE else View.GONE
        requestApplyInsets()
    }

    /** Lấy lại bề rộng, vị trí và bên dồn về của bàn phím một tay đã lưu (bỏ phần đang chỉnh dở). */
    private fun loadOneHand(settings: Settings = Settings(context)) {
        if (keyboard.oneHand != OneHand.OFF && settings.oneHand != OneHand.OFF) keyboard.oneHand = settings.oneHand
        oneHandWidth = settings.oneHandWidth
        oneHandShift = settings.oneHandShift.toFloat()
        relayoutPanel()
    }

    /**
     * Phần trong suốt thêm ở trên cùng (px) để cả view cao bằng chỗ tối đa được cấp. Bảng ký tự khi giữ phím nhờ vậy
     * vươn được lên trên bàn phím mà không che phím đang giữ. Lúc kéo "Kích thước" cửa sổ bàn phím giữ nguyên cỡ; không
     * thì mỗi bước kéo cửa sổ đổi cỡ trễ hơn nội dung. Ứng dụng vẫn co giãn và nhận chạm theo [panelTop] báo qua insets.
     */
    private var headroom = 0
    /**
     * Bàn phím đang ở chế độ toàn màn hình (xoay ngang): ô nhập của hệ thống nằm phía trên trong cùng cửa sổ, nên không
     * chiếm thêm [headroom], chừa chỗ cho ô nhập.
     */
    var fullscreen = false
        set(v) {
            if (field == v) return
            field = v
            fitComposeRow()
            requestLayout()
            invalidate()
        }

    /**
     * Toàn màn hình: dải khung gõ không chiếm chỗ (cao 0), để ô nhập của hệ thống phía trên đủ cao; khung gõ (canh đáy
     * dải) tràn lên trên, nổi đè lên mép dưới ô nhập ([unclipAncestors]). Bình thường dải cao [COMPOSE_HEIGHT], trong suốt.
     */
    private fun fitComposeRow() {
        val h = if (!fullscreen) context.dpi(COMPOSE_HEIGHT) else 0
        val lp = composeRow.layoutParams ?: return
        if (lp.height == h) return
        lp.height = h
        composeRow.layoutParams = lp
    }

    /**
     * Toàn màn hình: cho khung gõ vẽ tràn lên trên view này (đè lên ô nhập của hệ thống) bằng cách bỏ cắt ở dải khung
     * gõ, cột, view này và các view cha trong cửa sổ bàn phím. Vùng ô nhập được vẽ trước nên khung gõ nằm trên.
     */
    private fun unclipAncestors() {
        // Từ dải khung gõ đi lên tới gốc cửa sổ (qua cột, view này, khung của hệ thống).
        var p: android.view.ViewParent? = composeRow
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
        }
    }
    /** [headroom] lúc vẽ nền lần trước. */
    private var drawnSpace = 0

    /** Khoảng từ đỉnh view tới thanh gợi ý: dải trong suốt chứa khung gõ (và [headroom]). */
    val panelTop get() = headroom + composeRow.height

    /**
     * Cao đúng bằng thanh gợi ý + bàn phím (+ phần chừa cho thanh điều hướng);
     * overlay phủ theo, không kéo giãn cả cửa sổ.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Một tay: khối bàn phím thu hẹp, dồn về một bên; cột nút chiếm phần còn lại phía bên kia, khung gõ theo khối.
        val mode = keyboard.effectiveOneHand
        val (l, r) = panelBounds(w)
        (panel.layoutParams as LayoutParams).apply {
            width = r - l
            leftMargin = l
        }
        // Đang chỉnh "Kích thước": tạm ẩn cột nút (vẫn giữ chỗ, khối bàn phím không xê dịch).
        val sideShown = when {
            mode == OneHand.OFF -> View.GONE
            resizeShown -> View.INVISIBLE
            else -> View.VISIBLE
        }
        if (side.visibility != sideShown) side.visibility = sideShown
        side.side = mode
        (side.layoutParams as LayoutParams).apply {
            leftMargin = if (mode == OneHand.RIGHT) 0 else r
            width = if (mode == OneHand.RIGHT) l else w - r
        }
        (composing.layoutParams as LayoutParams).leftMargin = l
        // Màn hình ngang: thanh công cụ thẳng hàng với phím (có lề khi cửa sổ đè lên tai thỏ).
        (bar.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = if (landscape) keyboard.insetLeft.toInt() else 0
            rightMargin = if (landscape) keyboard.insetRight.toInt() else 0
        }
        column.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        // FrameLayout chỉ đo lại con MATCH_PARENT khi có từ hai con như vậy: tự đo cột nút cao bằng khối bàn phím.
        if (side.visibility != View.GONE) {
            side.measure(
                MeasureSpec.makeMeasureSpec(side.layoutParams.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(panel.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
        val h = column.measuredHeight
        // Ô nổi khi kéo tiện ích: cỡ cố định đặt lúc tạo.
        toolDrag?.view?.let { v ->
            v.measure(MeasureSpec.makeMeasureSpec(v.layoutParams.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(v.layoutParams.height, MeasureSpec.EXACTLY))
        }
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        headroom = if (!fullscreen && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            (maxH - h - paddingBottom).coerceAtLeast(0)
        } else {
            0
        }
        val total = headroom + h + paddingBottom
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        // Khung "Kích thước" phủ cả view: lúc kéo khung tràn được lên trên bàn phím hay xuống dưới đáy.
        resize.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, total)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (fullscreen) unclipAncestors()
        // Nội dung dời xuống dưới phần trong suốt thêm ở trên. Các lớp phủ cả view thì không, ô nổi khi kéo tiện ích
        // cũng không (vị trí của nó là translation theo toạ độ của view này).
        if (headroom > 0) {
            for (i in 0 until childCount) {
                getChildAt(i).takeIf { it !== resize && it !== overlay && it !== toolDrag?.view }?.offsetTopAndBottom(headroom)
            }
        }
        if (resizeShown) showFrame()
        // Đang tìm emoji, khung tìm nằm giữa dải khung gõ và thanh gợi ý: dời khung gõ xuống, vẫn ngay trên thanh gợi ý
        // như bình thường (vẽ đè lên mép dưới khung tìm).
        val drop = if (emojiSearchShown) emojiSearch.height.toFloat() else 0f
        if (composeRow.translationY != drop) composeRow.translationY = drop
        // Cỡ view không đổi nên không tự vẽ lại: vẽ lại nền (từ [panelTop]), không thì phần dưới đáy còn hình phím cũ.
        if (headroom != drawnSpace) {
            drawnSpace = headroom
            invalidate()
        }
        // Nút một tay ngang hàng phím: đổi toạ độ hàng phím sang toạ độ cột nút (cùng gốc với khối bàn phím).
        if (side.visibility == View.VISIBLE) {
            val (ys, size) = keyboard.sideAnchors()
            val dy = (body.top + keyboard.top).toFloat()
            side.anchors = ys.map { it + dy } to size
        }
    }

    init {
        // Android 15+ vẽ tràn viền: chừa chỗ để nút ẩn bàn phím / đổi bàn phím của hệ thống không đè lên phím.
        // Điều hướng cử chỉ chỉ báo ~24dp nhưng nút của hệ thống cần ~48dp, nên chừa ít nhất 48dp (như Gboard).
        // Một số máy (HyperOS, One UI, ColorOS khi dùng cử chỉ toàn màn hình) báo thanh điều hướng 0 cho cửa sổ bàn
        // phím dù vẫn vẽ tràn xuống dưới nó: lấy số lớn nhất trong các vùng hệ thống ở đáy, cả thế vẫn 0 mà máy có
        // thanh điều hướng thì dùng chiều cao chuẩn của nó.
        setOnApplyWindowInsetsListener { v, insets ->
            val nav = if (android.os.Build.VERSION.SDK_INT >= 30) {
                listOf(
                    android.view.WindowInsets.Type.navigationBars(),
                    android.view.WindowInsets.Type.tappableElement(),
                    android.view.WindowInsets.Type.mandatorySystemGestures(),
                ).maxOf { insets.getInsets(it).bottom }
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            val reported = if (nav == 0 && android.os.Build.VERSION.SDK_INT >= 35 && hasNavigationBar()) navigationBarHeight() else nav
            val system = when {
                // Màn hình ngang, thanh điều hướng nằm bên cạnh: đáy chỉ chừa khoảng trống như Gboard.
                landscape && nav == 0 -> context.dpi(LANDSCAPE_BOTTOM)
                reported > 0 -> maxOf(reported, context.dpi(48))
                else -> 0
            }
            val gap = if (resizeShown) kotlin.math.round(resizeGap).toInt() else savedGap(Settings(context))
            val bottom = system + context.dpi(gap)
            if (v.paddingBottom != bottom) v.setPadding(0, 0, 0, bottom)
            val cut = if (landscape && android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
            cutoutLeft = cut?.safeInsetLeft ?: 0
            cutoutRight = cut?.safeInsetRight ?: 0
            applySideInsets()
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySideInsets() }
    }

    /** Phần tai thỏ (px) cửa sổ bàn phím đè lên ở mép trái / phải (màn hình ngang). */
    private var cutoutLeft = 0
    private var cutoutRight = 0

    /**
     * Lề trái / phải của phím, thanh công cụ và bảng emoji ở màn hình ngang. Thanh điều hướng nằm ở một cạnh (cửa sổ bàn
     * phím không chạm mép màn hình bên đó) thì bên đó không lùi, chỉ bên tai thỏ lùi. Không có thanh điều hướng ở cạnh
     * (dùng cử chỉ) thì chừa đều hai bên bằng phần tai thỏ lớn hơn.
     */
    private fun applySideInsets() {
        var left = cutoutLeft
        var right = cutoutRight
        if (landscape && (left > 0 || right > 0) && width > 0) {
            val at = IntArray(2).also { getLocationOnScreen(it) }
            val screenW = if (android.os.Build.VERSION.SDK_INT >= 30) {
                context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.width()
            } else {
                @Suppress("DEPRECATION")
                android.util.DisplayMetrics().also {
                    context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it)
                }.widthPixels
            }
            val navLeft = at[0] > 0
            val navRight = at[0] + width < screenW
            if (!navLeft && !navRight) {
                left = maxOf(left, right)
                right = left
            } else {
                if (navLeft) left = 0
                if (navRight) right = 0
            }
        }
        if (keyboard.insetLeft != left.toFloat() || keyboard.insetRight != right.toFloat()) {
            keyboard.setSideInsets(left.toFloat(), right.toFloat())
            post { requestLayout() }
        }
    }

    init {
        setWillNotDraw(false)
        // Khung "Kích thước" kéo xuống được cả phần đệm dưới đáy (khoảng trống, thanh điều hướng): đừng cắt ở đó.
        clipToPadding = false
        composeRow.addView(composing, LayoutParams(LayoutParams.WRAP_CONTENT, context.dpi(COMPOSE_HEIGHT), Gravity.BOTTOM or Gravity.START))
        column.addView(composeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(COMPOSE_HEIGHT)))
        // Dải khung gõ vẽ trên khối bàn phím (khi dời xuống lúc tìm emoji, nó đè lên khung tìm); không đổ bóng.
        composeRow.translationZ = 1f
        composeRow.outlineProvider = null
        panel.addView(emojiSearch, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emojiSearch.visibility = View.GONE
        panel.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(48)))
        body.addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, keyboard.padHeight, Gravity.TOP))
        body.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(textEdit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tools.visibility = View.GONE
        textEdit.visibility = View.GONE
        clipboard.visibility = View.GONE
        grid.visibility = View.GONE
        pad.visibility = View.GONE
        emoji.visibility = View.GONE
        keyboard.onPadVisible = { show ->
            padActive = show
            pad.visibility = if (show && grid.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }
        panel.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelRow.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelRow.addView(side, LayoutParams(0, LayoutParams.MATCH_PARENT))
        column.addView(panelRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Đổi bàn phím một tay (bật, đổi bên, viết tay, xoay màn hình): xếp lại khối bàn phím và cột nút.
        keyboard.onOneHandChange = { relayoutPanel() }
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(resize, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resize.visibility = View.GONE
    }

    fun applyTheme(theme: KeyboardTheme) {
        keyboard.theme = theme
        pad.theme = theme
        pad.invalidate()
        overlay.theme = theme
        bar.theme = theme
        grid.theme = theme
        grid.applyTheme()
        emoji.theme = theme
        emoji.applyTheme()
        emojiSearch.theme = theme
        emojiSearch.applyTheme()
        resize.theme = theme
        side.theme = theme
        tools.theme = theme
        tools.applyTheme()
        clipboard.theme = theme
        textEdit.theme = theme
        textEdit.applyTheme()
        bar.applyTheme()
        backgroundPaint.color = theme.background
        composing.setTextColor(theme.text)
        composing.accent = theme.accent
        composing.background = GradientDrawable().apply {
            val r = context.dp(10f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(theme.composeBox)
        }
        keyboard.invalidate()
        invalidate()
    }

    /** Nền bàn phím, trừ dải khung gõ ở trên (để trong suốt); toàn màn hình thì nền đặc cả dải. */
    override fun onDraw(canvas: Canvas) {
        val top = if (fullscreen) 0f else panelTop.toFloat()
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), backgroundPaint)
    }

    /** Chữ đang hiện trong khung gõ. */
    val composingText: CharSequence get() = composing.text

    /** Ô khung gõ đang hiện (toạ độ trong cửa sổ), để nhận chạm; `null` nếu khung đang ẩn. */
    fun composeBox(): android.graphics.Rect? {
        if (composing.visibility != View.VISIBLE || composing.width == 0) return null
        val loc = IntArray(2).also { composing.getLocationInWindow(it) }
        return android.graphics.Rect(loc[0], loc[1], loc[0] + composing.width, loc[1] + composing.height)
    }

    /** Chạm vào khung gõ, kèm vị trí ký tự gần chỗ chạm nhất (để dời con trỏ). */
    var onComposeTap: ((Int) -> Unit)?
        get() = composing.onTap
        set(v) { composing.onTap = v }

    /** Chữ trong khung gõ, [chosen] ký tự đầu là chữ đã chọn dở (tô màu nhấn); rỗng thì ẩn khung. */
    fun setComposing(text: String, chosen: Int = 0, caret: Int = text.length) {
        // Có thể có chữ Hán đã chọn dở (như pinyin), cần phông Hán Nôm.
        composing.show(text, chosen, caret)
        composing.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        // Đang tìm emoji mà khung gõ hiện: chỉ con trỏ khung gõ nhấp nháy, con trỏ ô tìm đứng yên.
        emojiSearch.field.steadyCaret = text.isNotEmpty()
        fitComposeRow()
    }

    fun showGrid(show: Boolean) {
        grid.layoutParams.height = keyboard.height.coerceAtLeast(1)
        grid.setHeight(keyboard.height)
        grid.visibility = if (show) View.VISIBLE else View.GONE
        keyboard.visibility = if (show) View.INVISIBLE else View.VISIBLE
        pad.visibility = if (padActive && !show) View.VISIBLE else View.GONE
        pad.keepInk(show)
    }

    /** Máy có thanh điều hướng (3 nút hay vạch cử chỉ) theo cấu hình của hệ thống. */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun hasNavigationBar(): Boolean {
        val id = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        return id == 0 || resources.getBoolean(id)
    }

    /** Chiều cao chuẩn của thanh điều hướng (px), dự phòng khi hệ thống báo 0. */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else context.dpi(48)
    }

    /** Khoảng trống dưới bàn phím người dùng chỉnh vừa đổi: tính lại phần chừa dưới đáy. */
    fun updateBottomGap() = requestApplyInsets()

    val emojiShown get() = emoji.visibility == View.VISIBLE

    val toolsShown get() = tools.visibility == View.VISIBLE
    val clipboardShown get() = clipboard.visibility == View.VISIBLE
    val textEditShown get() = textEdit.visibility == View.VISIBLE

    /** Chỗ các tiện ích (thanh công cụ + bảng tiện ích); bộ gõ đặt từ cài đặt, kéo thả xong thì báo lại. */
    var toolLayout = ToolLayout.DEFAULT

    /** Mở / đóng bảng tiện ích: cao đúng bằng bàn phím, thanh công cụ vẫn hiện (nút ⊞ thành ✕). */
    fun showTools(show: Boolean) {
        if (show) {
            showEmoji(false)
            showEmojiSearch(false)
            showClipboard(false, false, emptyList())
            showTextEdit(false)
            tools.layoutParams.height = keyboard.height
            tools.style = keyboard.style
            tools.show(shownLayout(toolLayout), editing = false)
        } else {
            cancelToolDrag()
        }
        tools.visibility = if (show) View.VISIBLE else View.GONE
        keyboard.visibility = if (show || emojiShown || clipboardShown || textEditShown) View.INVISIBLE else View.VISIBLE
        pad.visibility = if (padActive && !show && !textEditShown) View.VISIBLE else View.GONE
        if (bar.showingTools) showToolbar()
    }

    /**
     * Mở / đóng bảng sửa văn bản: cao đúng bằng bàn phím, thanh công cụ vẫn hiện (nút ⊞ thành ✕). [selected]: ô nhập
     * đang có vùng chọn; [hasText]: ô nhập có chữ; [canPaste]: có chữ để dán (xem [TextEditPanel]).
     */
    fun showTextEdit(show: Boolean, selected: Boolean = false, hasText: Boolean = false, canPaste: Boolean = false) {
        if (show == textEditShown) return
        if (show) {
            showEmoji(false)
            showEmojiSearch(false)
            showClipboard(false, false, emptyList())
            if (toolsShown) showTools(false)
            textEdit.layoutParams.height = keyboard.height
            textEdit.style = keyboard.style
            textEdit.open(selected, hasText, canPaste)
        }
        textEdit.visibility = if (show) View.VISIBLE else View.GONE
        keyboard.visibility = if (show) View.INVISIBLE else View.VISIBLE
        pad.visibility = if (padActive && !show) View.VISIBLE else View.GONE
        if (bar.showingTools) showToolbar()
    }

    /** Chế độ sửa của bảng tiện ích (nút ✎; nút ✓ trên thanh để thoát). */
    private fun editTools(on: Boolean) {
        if (!toolsShown) return
        tools.show(shownLayout(toolLayout), on)
        showToolbar()
    }

    /** Gợi ý từ (Quốc ngữ / tiếng Anh) đang hiện giữa thanh công cụ. */
    private var words: List<Candidate> = emptyList()

    /** Thanh công cụ kèm gợi ý từ [items] ở giữa (thay các nút ghim); rỗng thì chỉ thanh công cụ. */
    fun showWords(items: List<Candidate>) {
        words = items
        showToolbar()
    }

    /** Bố cục đem hiện: màn hình ngang không có bàn phím một tay (như Gboard) nên ẩn nút đó; bố cục đã lưu vẫn giữ nó. */
    private fun shownLayout(l: ToolLayout) = if (!landscape) l else l.copy(
        row = l.row - ToolId.ONE_HAND,
        corner = l.corner.takeIf { it != ToolId.ONE_HAND },
        grid = l.grid - ToolId.ONE_HAND,
    )

    /** Đổi vị trí thả [i] trong danh sách đang hiện [shown] thành vị trí trong danh sách đã lưu [full]. */
    private fun fullIndex(full: List<ToolId>, shown: List<ToolId>, i: Int) = if (i >= shown.size) full.size else full.indexOf(shown[i])

    /** Hàng công cụ trên thanh gợi ý (khi chưa gõ gì), theo [toolLayout] (đang kéo thì theo chỗ thả dự kiến). */
    fun showToolbar(animate: Boolean = false) {
        val editing = toolsShown && tools.editing
        val layout = shownLayout(toolDrag?.preview ?: toolLayout)
        val lead = when {
            editing -> Tool("Xong", R.drawable.ic_check) { editTools(false) }
            toolsShown -> Tool("Đóng", R.drawable.ic_close) { showTools(false) }
            textEditShown -> Tool("Đóng", R.drawable.ic_close) { showTextEdit(false) }
            else -> Tool("Tiện ích", R.drawable.ic_apps) { showTools(true) }
        }
        fun tool(id: ToolId) = Tool(id.label(keyboard.style), id.icon, id) { if (!(toolsShown && tools.editing)) toolsListener.onToolUse(id) }
        // Bảng tiện ích / sửa văn bản đang mở thì thanh là của bảng đó, không hiện gợi ý từ.
        val shownWords = if (toolsShown || textEditShown) emptyList() else words
        bar.showTools(lead, layout.row.map(::tool), layout.corner?.let(::tool), editing, toolDrag?.id, animate, shownWords)
    }

    // ---- Kéo thả tiện ích (chế độ sửa): giữ một ô / nút rồi kéo, các ô khác dạt ra nhường chỗ ----

    /** Đang kéo [id]: [base] là bố cục khi đã nhấc nó ra, [slot] là chỗ thả dự kiến (null: thả thì về chỗ cũ). */
    private inner class ToolDrag(val id: ToolId, val from: ToolLayout, val view: View) {
        val base = from.without(id)
        var slot: ToolSlot? = from.slotOf(id)
        val preview get() = slot?.let { base.with(id, it) } ?: base
    }

    private var toolDrag: ToolDrag? = null
    /** Chỗ chạm cuối (toạ độ của view này), để đặt ô nổi khi bắt đầu kéo bằng cú giữ. */
    private var touchX = 0f
    private var touchY = 0f

    init {
        bar.onToolDrag = ::startToolDrag
    }

    private fun startToolDrag(id: ToolId) {
        if (toolDrag != null || !toolsShown) return
        // Giữ một ô khi chưa sửa thì vào chế độ sửa luôn.
        if (!tools.editing) editTools(true)
        val view = tools.makeFloating(id)
        val size = context.dpi(48)
        addView(view, LayoutParams(size, size))
        toolDrag = ToolDrag(id, toolLayout, view)
        moveToolDrag(touchX, touchY)
        renderToolDrag(animate = false)
    }

    private fun moveToolDrag(x: Float, y: Float) {
        val d = toolDrag ?: return
        d.view.translationX = x - d.view.layoutParams.width / 2f
        d.view.translationY = y - d.view.layoutParams.height / 2f
        val slot = toolSlotAt(x, y, d)
        if (slot != d.slot) {
            d.slot = slot
            renderToolDrag(animate = true)
        }
    }

    /** Chỗ thả ứng với điểm ([x], [y] theo toạ độ view này). */
    private fun toolSlotAt(x: Float, y: Float, d: ToolDrag): ToolSlot? {
        val at = IntArray(2).also { getLocationInWindow(it) }
        val wx = x + at[0]
        val wy = y + at[1]
        if (bar.cornerBounds().contains(wx, wy)) return ToolSlot.Corner
        if (bar.middleBounds().contains(wx, wy)) {
            val row = shownLayout(d.base).row
            return if (d.base.row.size >= ToolLayout.MAX_ROW) null else ToolSlot.Row(fullIndex(d.base.row, row, bar.rowIndexAt(wx, row.size)))
        }
        val panel = IntArray(2).also { tools.getLocationInWindow(it) }
        if (wy >= panel[1] && wy < panel[1] + tools.height) {
            val grid = shownLayout(d.base).grid
            return ToolSlot.Grid(fullIndex(d.base.grid, grid, tools.gridIndexAt(wx, wy).coerceAtMost(grid.size)))
        }
        return null
    }

    private fun renderToolDrag(animate: Boolean) {
        val d = toolDrag
        tools.show(shownLayout(d?.preview ?: toolLayout), editing = true, hidden = d?.id, animate = animate)
        showToolbar(animate)
    }

    /** Thả: lưu bố cục mới ([drop] false thì trả về như cũ). */
    private fun endToolDrag(drop: Boolean) {
        val d = toolDrag ?: return
        toolDrag = null
        removeView(d.view)
        val next = if (drop) d.slot?.let { d.base.with(d.id, it) } ?: d.from else d.from
        toolLayout = next
        if (toolsShown) renderToolDrag(animate = true)
        if (next != d.from) toolsListener.onToolsChanged(next)
    }

    private fun cancelToolDrag() = endToolDrag(drop = false)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchX = ev.x
        touchY = ev.y
        return super.dispatchTouchEvent(ev)
    }

    /** Đang kéo thì view này nhận hết các chạm sau (ô / nút vừa giữ nhận ACTION_CANCEL). */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = toolDrag != null || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (toolDrag == null) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> moveToolDrag(ev.x, ev.y)
            MotionEvent.ACTION_UP -> endToolDrag(drop = true)
            MotionEvent.ACTION_CANCEL -> cancelToolDrag()
        }
        return true
    }

    /** Mở / đóng bảng nhớ tạm (thay chỗ thanh gợi ý và bàn phím); mở thì dựng theo [on] và [clips]. */
    fun showClipboard(show: Boolean, on: Boolean, clips: List<Clip>) {
        if (show) {
            showEmoji(false)
            showEmojiSearch(false)
            tools.visibility = View.GONE
            textEdit.visibility = View.GONE
            clipboard.layoutParams.height = keyboard.height + bar.height
            clipboard.style = keyboard.style
            clipboard.show(on, clips)
        }
        if (!show && !clipboardShown) return
        clipboard.visibility = if (show) View.VISIBLE else View.GONE
        bar.visibility = if (show) View.GONE else View.VISIBLE
        keyboard.visibility = if (show) View.INVISIBLE else View.VISIBLE
        pad.visibility = if (padActive && !show) View.VISIBLE else View.GONE
    }

    /**
     * Mở / đóng bảng emoji: thay chỗ thanh gợi ý + bàn phím, cao đúng bằng chừng đó (đo trên Gboard: đỉnh bảng emoji
     * trùng đỉnh thanh công cụ của bàn phím chữ, không mở rộng lên trên).
     */
    fun showEmoji(show: Boolean) {
        if (show == emojiShown) return
        if (show) {
            showEmojiSearch(false)
            emoji.layoutParams.height = keyboard.height + bar.height
            emoji.style = keyboard.style
            emoji.setSideInsets(
                if (landscape) keyboard.insetLeft.toInt() else 0,
                if (landscape) keyboard.insetRight.toInt() else 0,
            )
            emoji.open()
            grid.visibility = View.GONE
            tools.visibility = View.GONE
            clipboard.visibility = View.GONE
            textEdit.visibility = View.GONE
        }
        emoji.visibility = if (show) View.VISIBLE else View.GONE
        bar.visibility = if (show) View.GONE else View.VISIBLE
        keyboard.visibility = if (show) View.INVISIBLE else View.VISIBLE
        pad.visibility = if (padActive && !show) View.VISIBLE else View.GONE
    }

    val emojiSearchShown get() = emojiSearch.visibility == View.VISIBLE
    /** Ô tìm emoji sắp đóng (phím còn vào ô tìm): bộ gõ bỏ chữ đang gõ dở. */
    var onEmojiSearchClosing: (() -> Unit)? = null
    /** Ô tìm emoji vừa đóng (bộ gõ trả phím về ô nhập của ứng dụng). */
    var onEmojiSearchClosed: (() -> Unit)? = null

    /** Mở / đóng ô tìm emoji: thay bảng emoji, nằm trên thanh gợi ý, bàn phím thường hiện lại bên dưới (như Gboard). */
    fun showEmojiSearch(show: Boolean) {
        if (show == emojiSearchShown) return
        if (show) {
            showEmoji(false)
            grid.visibility = View.GONE
            tools.visibility = View.GONE
            clipboard.visibility = View.GONE
            textEdit.visibility = View.GONE
            bar.visibility = View.VISIBLE
            keyboard.visibility = View.VISIBLE
            pad.visibility = if (padActive) View.VISIBLE else View.GONE
            emojiSearch.style = keyboard.style
            emojiSearch.open()
        } else {
            onEmojiSearchClosing?.invoke()
            emojiSearch.close()
        }
        emojiSearch.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) onEmojiSearchClosed?.invoke()
    }

    /**
     * Màn hình ngang: hàng chữ mặc định [defaultRow] dp (như Gboard, chừng 9,3% chiều cao màn hình), thanh công cụ thấp
     * hơn, bảng emoji không cao thêm. Chiều cao và khoảng trống chỉnh ở màn hình ngang lưu riêng với màn hình dọc.
     */
    fun setLandscape(landscape: Boolean, defaultRow: Int) {
        this.landscape = landscape
        landscapeDefaultRow = defaultRow
        keyboard.landscapeDefaultRow = context.dp(defaultRow.toFloat())
        keyboard.landscape = landscape
        bar.layoutParams.height = context.dpi(if (landscape) 40 else 48)
        bar.landscapeBar = landscape
        bar.requestLayout()
    }

    private var landscape = false
    private var landscapeDefaultRow = 0

    /** Chiều cao hàng phím (dp) và khoảng trống dưới đáy (dp) đã lưu của hướng màn hình đang dùng. */
    private fun savedRow(s: Settings) = if (landscape) s.landscapeRowHeight.takeIf { it > 0 } ?: landscapeDefaultRow else s.rowHeight
    private fun savedGap(s: Settings) = if (landscape) s.landscapeBottomGap else s.bottomGap

    fun applySavedRowHeight() = setRowHeight(context.dp(savedRow(Settings(context)).toFloat()))

    /** Chiều cao một hàng phím (px). Vùng viết tay cao theo. */
    fun setRowHeight(h: Float) {
        keyboard.rowHeight = h
        pad.layoutParams.height = keyboard.padHeight
        pad.requestLayout()
    }

    companion object {
        /** Chiều cao dải khung gõ trong suốt ở trên cùng (dp). */
        const val COMPOSE_HEIGHT = 32
        /** Chỉnh kích thước: bàn phím cùng khoảng trống dưới đáy chiếm tối đa chừng này chiều cao màn hình. */
        const val MAX_SHARE = 0.6f
        /** Như [MAX_SHARE] cho màn hình ngang (đo trên Gboard: đỉnh khung lên tới chừng 22% màn hình tính từ trên). */
        const val LANDSCAPE_MAX_SHARE = 0.835f
        // Tỉ lệ màn hình ngang, đo trên Gboard (máy 792 × 354 dp). Các số hàng tính theo bước hàng chữ.
        /** Chiều cao hàng chữ (cả khe) so với chiều cao màn hình. */
        const val LANDSCAPE_ROW = 0.11f
        /** Khe giữa các hàng phím (dp). */
        const val LANDSCAPE_GAP_Y = 3.45f
        /**
         * Khoảng trống dưới đáy khi thanh điều hướng nằm bên cạnh (dp). Cộng với lề trong của KeyboardView (chừng 2,2 dp)
         * thì dưới hàng phím cách còn 6,2 dp, Gboard cỡ mặc định đo được 6 dp.
         */
        const val LANDSCAPE_BOTTOM = 4
        const val LANDSCAPE_NUMBER_ROW = 0.836f
        const val LANDSCAPE_BOTTOM_ROW = 0.953f
        /** Chiều cao cả trang 5 hàng, tính theo số hàng chữ. */
        const val LANDSCAPE_PAGE_ROWS = 3 + LANDSCAPE_NUMBER_ROW + LANDSCAPE_BOTTOM_ROW
        /** Chiều cao chuẩn để tính cỡ chữ, icon trên phím, so với hàng chữ. */
        const val LANDSCAPE_TEXT = 1.13f
        /** Cỡ chữ hàng số so với hàng chữ. */
        const val LANDSCAPE_NUMBER_TEXT = 0.88f
    }
}

/**
 * Khung gõ (kiểu khung pinyin): phần đầu đã chọn dở tô màu nhấn, cuối khung có con trỏ nháy chỉ chỗ phím gõ tiếp
 * sẽ vào.
 */
@SuppressLint("ViewConstructor")
class ComposingView(context: Context) : TextView(context) {
    var accent = 0
        set(v) {
            field = v
            cursorPaint.color = v
        }
    /** Chạm vào khung gõ: vị trí ký tự gần chỗ chạm nhất. */
    var onTap: ((Int) -> Unit)? = null
    private val cursorPaint = Paint().apply { strokeWidth = context.dp(2f) }
    private var cursorOn = true
    /** Con trỏ đứng trước ký tự thứ này của chữ đang hiện. */
    private var caret = 0
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    /** Chữ [text], trong đó [chosen] ký tự đầu là chữ đã chọn dở; con trỏ ở trước ký tự thứ [caret]. */
    fun show(text: String, chosen: Int, caret: Int = text.length) {
        this.caret = caret.coerceIn(0, text.length)
        val shown = SpannableString(context.hanNomText(text))
        val end = chosen.coerceIn(0, text.length)
        if (end > 0) {
            shown.setSpan(ForegroundColorSpan(accent), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            shown.setSpan(BackgroundColorSpan(accent and 0xFFFFFF or (0x2E shl 24)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        this.text = shown
        // Gõ thêm thì con trỏ hiện ngay, đếm nhịp nháy lại từ đầu (như ô nhập của hệ thống).
        cursorOn = true
        removeCallbacks(blink)
        if (text.isNotEmpty()) postDelayed(blink, BLINK_MS)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tap = onTap ?: return super.onTouchEvent(event)
        if (text.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val l = layout ?: return true
            tap(l.getOffsetForHorizontal(0, event.x - totalPaddingLeft + scrollX))
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (!cursorOn || text.isEmpty()) return
        // totalPadding gồm cả khoảng căn giữa theo chiều dọc (gravity), extendedPadding thì không.
        val x = totalPaddingLeft + l.getPrimaryHorizontal(caret.coerceAtMost(l.text.length)) +
            if (caret >= text.length) context.dp(1.5f) else 0f
        val top = totalPaddingTop + l.getLineTop(0).toFloat()
        val bottom = totalPaddingTop + l.getLineBottom(0).toFloat()
        canvas.drawLine(x, top, x, bottom, cursorPaint)
    }

    companion object {
        /** Nhịp nháy của con trỏ (ms), như ô nhập của Android. */
        const val BLINK_MS = 500L
    }
}
