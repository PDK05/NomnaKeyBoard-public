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
        setBackgroundColor(theme.header)
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
        /** Thanh gợi ý màn hình ngang: số ô đều nhau, như Gboard (người dùng muốn 2026-11-17). */
        const val LANDSCAPE_CELLS = 8
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

    /** Gợi ý đầu đang hiện trên thanh (lần [showCandidates] gần nhất), `null` nếu thanh trống. */
    var firstShown: Candidate? = null
        private set

    /** Gợi ý chưa có trên thanh, cho bảng lưới (⌄). */
    fun hidden(items: List<Candidate>) = items.filterIndexed { i, _ -> i !in shown }

    /** Như [hidden] nhưng tính theo cách xếp thanh cho chính [items] ([layout]), không cần thanh đang hiện chúng. */
    fun hiddenFor(items: List<Candidate>): List<Candidate> {
        val on = layout(items).first.toSet()
        return items.filterIndexed { i, _ -> i !in on }
    }

    /** Cách xếp [items] lên thanh: vị trí các gợi ý lên thanh, cỡ chữ, và có vừa thanh không ([showCandidates]). */
    private fun layout(items: List<Candidate>): Triple<List<Int>, Float, Boolean> {
        if (landscapeBar && items.isNotEmpty() && cellUnits(items[0].text) <= LANDSCAPE_CELLS) {
            // Màn hình ngang như Gboard (đo trên điện thoại 2026-11-17, gõ zhong): thanh chia [LANDSCAPE_CELLS] ô đều
            // nhau, gợi ý vừa một ô (chữ đơn, từ kép, chữ Latin ngắn như `trung`) chiếm một ô, dài hơn thì nhiều ô.
            val picked = ArrayList<Int>()
            var used = 0
            for ((i, c) in items.withIndex().take(MAX_SCAN)) {
                if (c.panelFirst && i > 0) continue
                val u = cellUnits(c.text)
                if (used + u > LANDSCAPE_CELLS) continue
                picked.add(i)
                used += u
                if (used == LANDSCAPE_CELLS) break
            }
            return Triple(picked, MAX_SP, true)
        }
        val picked = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        for ((i, c) in items.withIndex().take(MAX_SCAN)) {
            // Cả cụm Latin nhiều chữ chỉ ở ô đầu bảng thả ([Candidate.panelFirst]).
            if (c.panelFirst && i > 0) continue
            val len = slotLength(c.text)
            lengths.add(len)
            if (picked.isEmpty() || fits(lengths)) picked.add(i) else lengths.removeAt(lengths.lastIndex)
        }
        // Tràn thanh thì thu nhỏ đều chữ cho vừa, tới MIN_SP. Nhỏ cỡ đó vẫn không vừa thì gợi ý đầu đứng
        // một mình (các gợi ý khác xem ở bảng ⌄) và thu nhỏ tiếp cho vừa, tới cỡ vừa đúng LONE_MAX_CHARS chữ;
        // dài hơn thì giữ cỡ đó, thanh kéo ngang.
        val room = availableWidth()
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
        return Triple(picked, size, fit)
    }

    /**
     * Xếp gợi ý lên thanh theo khuôn [fits] (số ô và cỡ các ô theo cụm chữ Hán dài nhất): duyệt theo
     * thứ tự xếp hạng, gợi ý nào thêm vào mà vẫn vừa khuôn thì lấy, không thì để dành cho bảng lưới.
     * Gợi ý đầu luôn có mặt. Chữ Latin tính theo bề rộng thật ([slotLength]).
     */
    fun showCandidates(items: List<Candidate>, emptyAction: Tool?, narrowing: Boolean = false) {
        showStrip(true)
        strip.removeAllViews()
        scroller.scrollTo(0, 0)
        val (picked, size, fit) = layout(items)
        // Ít gợi ý thì không giãn ra cả thanh: các ô vẫn rộng như khi thanh đủ ngăn theo khuôn [fits] (chữ đơn
        // 6 ngăn, từ kép 5 ngăn, cụm dài hơn 11 chữ), xếp từ ngăn 1 bên trái, phần còn lại để trống.
        val cells = landscapeBar && fit && picked.isNotEmpty() && cellUnits(items[picked[0]].text) <= LANDSCAPE_CELLS
        fun weight(i: Int) = if (cells) cellUnits(items[i].text).toFloat() else maxOf(slotLength(items[i].text), 2).toFloat()
        fun capacity(): Float {
            if (cells) return LANDSCAPE_CELLS.toFloat()
            val han = picked.map { slotLength(items[it].text) }
            if (han.isEmpty()) return 0f
            return when (han.max()) {
                1 -> 6 * 2f
                2 -> 5 * 2f
                else -> BUDGET.toFloat()
            }
        }
        shown = picked.toSet()
        firstShown = picked.firstOrNull()?.let(items::get)
        picked.forEachIndexed { n, i ->
            strip.addView(chip(items[i].text, highlight = n == 0, slot = true, fit = fit, sizeSp = size, weight = weight(i)) { onPick(items[i]) })
        }
        val weights = picked.map(::weight)
        strip.weightSum = if (!fit || weights.isEmpty()) 0f else maxOf(capacity(), weights.sum())
        if (items.isEmpty() && emptyAction != null) strip.addView(chip(emptyAction.label, small = true) { emptyAction.action() })
        // Thanh đã đủ mọi gợi ý vẫn mở được bảng khi cột trái còn thu hẹp được (gõ `deer` mà muốn rể: chọn r).
        val more = items.size > picked.size || (items.isNotEmpty() && canNarrow()) || narrowing
        expandButton.visibility = if (more) View.VISIBLE else View.INVISIBLE
        // Đang thu hẹp ở bảng ([CandidateGrid.onNarrowing]) thì thanh có thể trống mà bảng vẫn mở.
        if (!more && expanded && !narrowing) expanded = false
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
        measurePaint.textSize = context.sp(MAX_SP)
        if (han > 0) {
            // Chữ Hán kèm chữ Latin (`及庄諸m`, giữ nguyên Latin của âm sau các âm đã chốt): tính cả phần Latin, kẻo ô
            // hẹp quá, chữ Latin bị lấp.
            val latin = text.filter { it.code < 0x2E80 && !Character.isSurrogate(it) }
            if (latin.isEmpty()) return han
            measurePaint.typeface = context.robotoTypeface()
            return han + kotlin.math.ceil(measurePaint.measureText(latin) / context.sp(MAX_SP)).toInt().coerceAtLeast(1)
        }
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
        // Icon ⊞ vẽ 28dp (bốn ô rộng chừng 18–19dp như Gboard, đo trên điện thoại 2026-11-18: 64 px ở 3,5 px/dp); các
        // nút khác 24dp như Gboard.
        val leadPad = context.dpi(if (lead.icon == R.drawable.ic_apps) 6 else 8)
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
        // Nền tròn 34dp, icon 20dp như Gboard (đo trên điện thoại 2026-11-18: vòng 118 px, bánh răng 59 px ở 3,5 px/dp);
        // vùng bấm vẫn 40dp.
        val inset = context.dpi(3)
        val pad = context.dpi(10)
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

    /** Số ô (trên [LANDSCAPE_CELLS] ô đều nhau của thanh ngang) [text] cần ở cỡ [MAX_SP], kể cả lề. */
    private fun cellUnits(text: String): Int {
        val cell = availableWidth() / LANDSCAPE_CELLS
        measurePaint.textSize = context.sp(MAX_SP)
        measurePaint.typeface = if (hanLength(text) > 0) context.hanNomTypeface() else context.robotoTypeface()
        val need = measurePaint.measureText(text) + 2f * slotPadding(text)
        return kotlin.math.ceil(need / cell).toInt().coerceAtLeast(1)
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
        EllipsisTextView(context).apply {
            this.text = if (small) text else context.hanNomText(text)
            gravity = Gravity.CENTER
            setTextColor(if (highlight) theme.candidate else theme.text)
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

    /** [narrow] / [narrowed] cho đúng chuỗi phím lúc lấy (trên luồng giao diện), gọi được từ luồng nền. */
    class Core(
        val narrow: (List<Pair<Int, String>>) -> Pair<Int, List<String>>?,
        val narrowed: (List<Pair<Int, String>>) -> List<Candidate>,
    )

    /**
     * Lấy [Core] cho chuỗi phím đang gõ. Bảng gọi lõi ở luồng nền ([render]) rồi mới dựng: gọi trên luồng giao diện thì
     * mỗi lần chạm phím cách / xoá của bảng phải chờ lõi (đo trên điện thoại với cụm test kinh điển: 40–265 ms, phần
     * lớn là chờ các việc tính trước của bộ gõ; Gboard cũng không để giao diện chờ bộ giải mã).
     */
    var core: () -> Core = { Core(narrow, narrowed) }

    /** Bảng sắp gọi lõi ([render]): bộ gõ bỏ các việc tính trước còn chờ, để lõi rảnh cho bảng. */
    var onCoreNeeded: () -> Unit = {}
    /** Âm đủ (có thanh, chữ thường) của chữ đầu đang gõ, như khung gõ hiện (`tooi` → tôi); `null` nếu chưa có. */
    var typedSyllable: () -> String? = { null }
    /** Số âm của chuỗi phím đang gõ (đoạn âm của lõi), để biết còn âm sau âm đang chọn không ([canLock]). */
    var syllableCount: () -> Int = { 0 }
    /** Các đoạn âm của một chuỗi phím (lõi), để biết gõ tiếp có giữ nguyên các âm đã chốt không ([syncKeys]). */
    var chunksOf: (String) -> List<IntRange> = { emptyList() }
    /** Phím của đoạn âm thứ i (đoạn âm của lõi: `twcmn` → t, w, c, m, n), cho nhãn âm chốt chưa chọn gì. */
    var typedChunk: (Int) -> String = { "" }
    var onDelete: () -> Unit = {}
    var onEnter: () -> Unit = {}
    /** Chạm một dấu câu ở cột trái ([PUNCTUATION]): như ấn phím dấu câu. */
    var onPunctuation: (String) -> Unit = {}
    /**
     * Đang thu hẹp ở cột trái: các chữ (của [narrowed]) mà lưới hiện, để thanh gợi ý phía trên bỏ chúng đi và đôn
     * các gợi ý khác lên (không lặp giữa thanh và lưới); `null` khi thôi thu hẹp, thanh trở lại như cũ. Kèm chữ Latin
     * theo lựa chọn ở cột trái ([pointedLatin]): nó thay chữ Latin cũ ở ô 2 của thanh, không xuống lưới.
     */
    var onNarrowing: (Set<String>?, Candidate?) -> Unit = { _, _ -> }
    /** Chữ Latin theo lựa chọn ở cột trái của lần [render] gần nhất (xem [onNarrowing]). */
    private var narrowLatin: Candidate? = null

    /**
     * Chữ Latin của âm đang được trỏ tới (HanNomIME.withPointedLatin): luôn ở ô 2 của thanh gợi ý, thay chữ Latin cũ,
     * kể cả khi mới chọn một phần âm hay đã chốt âm (người dùng muốn 2026-11-18). Cả cụm Latin ([Candidate.panelFirst])
     * không tính.
     */
    private fun pointedLatin(list: List<Candidate>) = list.firstOrNull { it.literal && !it.panelFirst }
    /**
     * Thanh gợi ý hiện các mục đầu của danh sách còn vừa thanh; trả các mục còn lại cho lưới (không lặp). Dùng khi bật
     * 𡨸單 và khi mở bảng / đã chốt âm.
     */
    var onBar: (List<Candidate>) -> List<Candidate> = { it }
    /** Thanh gợi ý đang bỏ bớt các chữ của lưới (xem [onNarrowing]). */
    var narrowing = false
        private set

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
            whenReady {
                single = !single
                // Chỉ lọc lưới, cột trái không đổi: giữ chỗ đang cuộn, không nhảy lên đầu.
                keepFilterScroll = true
                render()
            }
        }
    }
    /**
     * Phím cách (như phím cách của bàn phím số), một phím cố định ở góc dưới bên trái lưới, nằm trên [LOCK_CELLS] ô để
     * trống của hàng cuối mỗi trang ([reservesLock]); lưới nhảy từng trang, không trượt, nên không chữ nào nằm dưới nó
     * (người dùng muốn 2026-11-14). Chốt tương đối âm đang chọn ở cột trái theo đúng các lựa chọn lúc đó ("ph…",
     * "phá…", chưa chọn gì thì theo phím đã gõ), rồi cột chuyển ngay sang âm tiếp theo.
     */
    private val lockButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_space)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Chốt âm"
        setOnClickListener { whenReady(::lock) }
        // Chốt ngay khi chạm xuống (như phím của bàn phím), không chờ nhả tay: bảng dựng lại giữa lúc chạm và nhả (dời
        // phím chốt, dựng lại các hàng) thì "click" bị huỷ, chạm dồn dập mất ~17% lần (đo 2026-11-16, 50/60).
        setOnTouchListener { v, e ->
            // Không xét isEnabled: nó theo lần dựng gần nhất, có thể đã cũ khi đang hoãn dựng; lock() tự kiểm tra.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                v.isPressed = true
                lockNow()
            } else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                v.isPressed = false
            }
            true
        }
    }
    /** Các âm đã chốt ([lockButton]), mỗi âm là các lựa chọn lúc chốt; [picked] là của âm kế tiếp. */
    private val locks = ArrayList<List<Pair<Int, String>>>()
    /** Các âm đã chốt đổi: nhãn từng âm ("ph…", "phát"), để khung gõ hiện thay cho phím của các âm đó. */
    var onLocks: (List<String>) -> Unit = {}
    private var shownLocks: List<String> = emptyList()
    /** Nhãn các âm đang chốt (như [onLocks] báo lần cuối). */
    val lockLabels: List<String> get() = locks.indices.map(::lockedLabel)

    /**
     * Nhãn phần đã chọn dở (chưa chốt) của âm đang trỏ tới, như đầu cột trái (`t…`, `tôi…`, `tối`); rỗng khi chưa chọn
     * gì. Khung gõ hiện nó thay cho âm đã gõ ở chỗ tô vàng (người dùng muốn 2026-11-18).
     */
    val pickingLabel: String get() = if (picked.isEmpty()) "" else pickedLabel(picked, locks.size)
    /** [pickingLabel] vừa đổi (sau mỗi lần chọn / bỏ chọn ở cột trái). */
    var onPicking: () -> Unit = {}
    private var shownPicking = ""
    /**
     * Các hàng lưới. Các lớp bọc ngoài bàn phím (LinearLayout có weight lồng nhau) đo lại lưới ~16 lần mỗi khung hình
     * (đo trên máy ảo 2026-11-17: 12 hàng, mỗi lần 1–5 ms; lưới 300 chữ thì mở bảng khựng 65–200 ms mỗi khung hình).
     * Hàng lưới chỉ đổi khi dựng ([rowsChanged]), nên cùng kích thước đo thì trả lại kết quả lần trước, không đo lại các ô.
     */
    private val rows = object : LinearLayout(context) {
        /** Kết quả đo theo từng cặp kích thước được hỏi (các lớp ngoài hỏi vài kích thước khác nhau mỗi khung hình). */
        private val measured = HashMap<Long, Long>()
        /** Kích thước đo thật gần nhất: các ô đang mang kết quả đo của nó (onLayout dùng). */
        private var realSpec = -1L

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (rowsChanged) {
                measured.clear()
                rowsChanged = false
            }
            val spec = (widthMeasureSpec.toLong() shl 32) or (heightMeasureSpec.toLong() and 0xffffffffL)
            val known = measured[spec]
            if (known != null) {
                setMeasuredDimension((known shr 32).toInt(), known.toInt())
                return
            }
            perf("  grid.measure", "$childCount hàng") { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
            realSpec = spec
            measured[spec] = (measuredWidth.toLong() shl 32) or (measuredHeight.toLong() and 0xffffffffL)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // Các ô phải mang kết quả đo đúng bề ngang được xếp: lần đo cuối là kích thước khác (lấy từ bộ nhớ) thì đo
            // thật lại cho đúng bề ngang đó trước khi xếp.
            val w = MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY)
            if (realSpec ushr 32 != w.toLong() and 0xffffffffL) {
                val h = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                perf("  grid.measure", "$childCount hàng (xếp)") { super.onMeasure(w, h) }
                realSpec = (w.toLong() shl 32) or (h.toLong() and 0xffffffffL)
                setMeasuredDimension(r - l, b - t)
            }
            super.onLayout(changed, l, t, r, b)
        }
    }.apply { orientation = VERTICAL }

    /** Các hàng lưới vừa đổi (thêm / bỏ hàng, ô, chiều cao chừa): lần đo tới phải đo lại thật ([rows]). */
    private var rowsChanged = true
    private val pageScroll = object : ScrollView(context) {
        /** Lần nhấc tay này đã vuốt nhanh ([fling]). */
        private var flung = false
        /** Ngón tay đang giữ trên lưới: chưa nhảy về trang. */
        private var touching = false
        /** Lưới đứng yên mà lệch mốc trang (kéo dở, cuộn quán tính…): tự nhảy về trang gần nhất. */
        private val settle = Runnable {
            val page = pageHeight()
            if (!touching && scrollY % page != 0) smoothScrollTo(0, (scrollY + page / 2) / page * page)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            loadMoreIfNeeded()
            updatePaging()
            removeCallbacks(settle)
            postDelayed(settle, SETTLE_MS)
        }

        // Cuộn bằng tay cũng dừng đúng mốc trang, để hàng có chỗ trống luôn nằm dưới phím chốt âm ([reservesLock]):
        // vuốt nhanh thì sang trang kế, kéo chậm thì về trang gần nhất.
        override fun fling(velocityY: Int) {
            flung = true
            val page = pageHeight()
            val base = scrollY / page * page
            smoothScrollTo(0, if (velocityY > 0) base + page else if (scrollY % page == 0) base - page else base)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                flung = false
                touching = true
                scrollTarget = -1
            }
            val handled = super.onTouchEvent(ev)
            val up = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
            if (up) {
                touching = false
                removeCallbacks(settle)
                postDelayed(settle, SETTLE_MS)
            }
            if (up && !flung) {
                val page = pageHeight()
                smoothScrollTo(0, (scrollY + page / 2) / page * page)
            }
            return handled
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    // Cuộn từ chỗ đang đứng như trước (tính từ trang đích của lần ấn trước thì người dùng không chấp nhận, 2026-11-16).
    private val upButton = sideButton(text = "▲") { pageTo(pageScroll.scrollY - pageHeight()) }
    private val downButton = sideButton(text = "▼") {
        loadMoreIfNeeded()
        pageTo(pageScroll.scrollY + pageHeight())
    }
    /** Đang thu hẹp ở cột trái thì xoá là lùi một bước thu hẹp; hết bước mới xoá chữ / phím như thường. */
    // Giữ nút: lần lặp nào phải xếp hàng chờ bảng tra lõi thì bỏ qua (không dồn lại, nhả tay là thôi xoá).
    private val deleteButton = sideButton(icon = R.drawable.ic_backspace) { deleteNow(hold = false) }
        .also { repeatOnHold(it) { deleteNow(hold = true) } }

    /**
     * Nút xoá của bảng: đang chọn dở âm hiện tại thì lùi một bước chọn; không thì như phím xoá của bàn phím (xoá dấu
     * cách, gỡ chữ Hán đã chọn, rồi xoá cả âm đã chốt), không mở lại âm đã chốt (không hiện nền vàng; người dùng muốn
     * 2026-11-15). Muốn chọn lại âm đã chốt thì dùng nút "‹".
     * Như phím chốt âm ([lockNow]): bỏ chốt / xoá phím ngay, không chờ lần dựng đang tra dở; lưới
     * chỉ dựng lại khi dừng tay ([render]). Xếp hàng chờ ([whenReady]) thì mỗi lần xoá chờ một lần dựng (~0,2 giây trên
     * điện thoại): chạm 60 lần bỏ chốt trong 3,6 giây, 4 giây sau vẫn còn xoá (đo 2026-11-17). Đang lùi bước chọn ở cột
     * trái thì vẫn chờ (cần các lựa chọn tự chọn của lần dựng).
     */
    private fun deleteNow(hold: Boolean) {
        if (picked.isNotEmpty()) {
            if (!hold || !pending) pick(null)
            return
        }
        if (pending && waiting.isNotEmpty()) {
            if (!hold) whenReady { onDelete() }
        } else {
            onDelete()
        }
    }

    /**
     * Giữ nút để lặp lại như phím xoá của bàn phím (người dùng muốn 2026-11-15): sau [HOLD_DELAY_MS] ms thì lặp, nhanh
     * dần từ [HOLD_SLOW_MS] tới [HOLD_FAST_MS] ms một lần; chạm nhanh rồi nhả thì làm một lần.
     * Lần xoá kế tiếp là cả một âm đã chốt / chữ đã chọn ([wholeSyllable]): đều [HOLD_SYLLABLE_MS] ms một lần, không
     * nhanh dần. Từ khi xoá nhận ngay lúc chạm ([deleteNow]), giữ xoá bỏ chốt tới 20 ms một âm, người dùng thấy "hơi nhanh
     * quá" (2026-11-17); trước đó bảng kìm lại ~0,2 giây một âm.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(v: View, action: () -> Unit) {
        var repeating = false
        val tick = object : Runnable {
            var interval = HOLD_SLOW_MS
            override fun run() {
                if (!repeating) interval = HOLD_SLOW_MS
                repeating = true
                action()
                if (wholeSyllable()) {
                    // Hết âm chốt thì xoá phím nhanh dần lại từ đầu.
                    interval = HOLD_SLOW_MS
                    v.postDelayed(this, HOLD_SYLLABLE_MS)
                    return
                }
                v.postDelayed(this, interval.toLong())
                interval = (interval * HOLD_SPEEDUP).coerceAtLeast(HOLD_FAST_MS)
            }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeating = false
                    view.isPressed = true
                    view.postDelayed(tick, HOLD_DELAY_MS)
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(tick)
                    view.isPressed = false
                    if (!repeating) view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(tick)
                    view.isPressed = false
                }
            }
            true
        }
    }
    private val enterButton = sideButton(icon = R.drawable.ic_return, accent = true) { onEnter() }

    private var all: List<Candidate> = emptyList()
    private var hidden: List<Candidate> = emptyList()
    private var keys = ""
    /** Trạng thái thu hẹp lúc chọn một chữ trong lưới: chuỗi phím, các lựa chọn, cấp tự chọn, các âm đã chốt. */
    private class Back(val keys: String, val picked: List<Pair<Int, String>>, val auto: Set<Int>, val locks: List<List<Pair<Int, String>>>)
    /** Mỗi lần chọn chữ trong lưới: trạng thái thu hẹp lúc đó ([Back]), để xoá chữ thì trở lại. */
    private val history = ArrayList<Back>()
    /** Các lựa chọn thu hẹp đã chọn (cấp, giá trị); rỗng là xem tất cả. */
    private val picked = ArrayList<Pair<Int, String>>()
    /** Các cấp trong [picked] do bảng tự chọn (cấp chỉ có một lựa chọn, xem [advance]), không phải người dùng chạm. */
    private val autoPicked = HashSet<Int>()
    /** Cấp kế tiếp và các lựa chọn của nó. */
    private var level: Pair<Int, List<String>>? = null
    /** Nút 𡨸單 là công tắc: bật thì giữ khi gõ tiếp, xoá, chọn chữ… tới khi người dùng tắt, hay khung gõ (pinyin) tắt. */
    private var single = false
    /** Lần dựng cột trái kế tiếp giữ nguyên chỗ đang cuộn (xem [pick]). */
    private var keepFilterScroll = false
    private var rowHeight = context.dpi(52)
    private val columnLine = View(context)
    private val leftColumn = LinearLayout(context).apply { orientation = VERTICAL }

    /** Có cột trái (thu hẹp, 𡨸單) không; viết tay thì không, lưới chiếm cả bề ngang (như Gboard). */
    var filters = true
        set(v) {
            field = v
            leftColumn.visibility = if (v) View.VISIBLE else View.GONE
            columnLine.visibility = leftColumn.visibility
            lockButton.visibility = leftColumn.visibility
        }

    init {
        orientation = HORIZONTAL
        addView(leftColumn.apply {
            addView(filterScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(singleButton, LayoutParams(LayoutParams.MATCH_PARENT, singleHeight()))
        }, LayoutParams(context.dpi(64), LayoutParams.MATCH_PARENT))
        addView(columnLine, LayoutParams(context.dpi(1).coerceAtLeast(1), LayoutParams.MATCH_PARENT).apply {
            setMargins(0, context.dpi(8), 0, context.dpi(8))
        })
        // Phím chốt âm cố định ở góc dưới bên trái của lưới, ngang hàng nút 𡨸單, để cột trái dành hết chỗ cho các âm
        // ([lockButton]; người dùng muốn 2026-11-14).
        addView(FrameLayout(context).apply {
            addView(pageScroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(lockButton, lockParams())
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dpi(6), context.dpi(6), context.dpi(8), context.dpi(6))
            listOf(upButton, downButton, deleteButton, enterButton).forEach {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, context.dpi(4), 0, context.dpi(4)) })
            }
        }, LayoutParams(context.dpi(72), LayoutParams.MATCH_PARENT))
    }

    /** Khung gõ (pinyin) đã tắt: tắt nút 𡨸單. */
    fun endComposing() {
        single = false
    }

    /** [all]: mọi gợi ý; [hidden]: các gợi ý chưa có trên thanh (bảng hiện các gợi ý này khi không lọc). */
    /** Gọi một lần khi lần dựng lưới kế tiếp xong (mở bảng: lúc đó mới đổi bàn phím sang bảng). */
    var onReady: (() -> Unit)? = null

    fun show(all: List<Candidate>, hidden: List<Candidate>, keys: String, opening: Boolean = false) {
        syncKeys(keys)
        this.all = all
        this.hidden = hidden
        // Vừa mở bảng mà lưới / cột trái còn giữ kết quả của chuỗi phím khác (gõ âm mới rồi mở bảng): xoá ngay, kẻo hiện
        // âm cũ trong lúc lõi tính lại ở luồng nền (người dùng báo 2026-11-17: "nháy load âm cũ"). Đang mở thì không
        // (giữ xoá trong bảng sẽ chớp trắng).
        if (opening && keys != renderedKeys) {
            fillGen++
            rows.removeAllViews()
            rowsChanged = true
            filterColumn.removeAllViews()
            items = emptyList()
            next = 0
            lastRow = null
        }
        render()
    }

    /**
     * Như [show] khi gợi ý vừa tra lại: lưới đang hiện các chữ khớp phần chọn dở / các âm đã chốt ([render], không lấy
     * từ [all]) mà chuỗi phím không đổi thì chỉ nhận gợi ý mới, khỏi dựng lại (đo trên điện thoại: mỗi lần ~85 ms).
     */
    fun update(all: List<Candidate>, hidden: List<Candidate>, keys: String) {
        // So với chuỗi phím của lần dựng gần nhất ([renderedKeys]), không phải [keys]: bộ gõ có thể đã [syncKeys] trước.
        // Đang dựng dở cho đúng chuỗi phím này (vừa chốt / bỏ chốt âm): lần dựng đó đọc [all] / [hidden] lúc xong, khỏi
        // dựng lại từ đầu (dựng hai lần thì thanh gợi ý chớp; người dùng báo 2026-11-16).
        if (pending && keys == this.keys) {
            this.all = all
            this.hidden = hidden
            return
        }
        // Bật 𡨸單: lưới và thanh chỉ là chữ đơn tra riêng, không theo gợi ý chung; khỏi dựng lại (mở bảng thì dựng hai lần,
        // cách ~170 ms; đo 2026-11-17).
        // Đã chốt âm: thanh và lưới theo các chữ khớp các âm đã chốt, cũng không theo gợi ý chung.
        if (keys == renderedKeys && renderedSelection && (single || locks.isNotEmpty())) {
            this.all = all
            this.hidden = hidden
            return
        }
        if (keys == renderedKeys && renderedSelection && selecting) {
            this.all = all
            this.hidden = hidden
            // Thanh gợi ý vừa hiện lại đủ gợi ý mới: bỏ lại các chữ lưới đang giữ (như [render]).
            onNarrowing(items.mapTo(HashSet()) { it.text }, narrowLatin)
            return
        }
        show(all, hidden, keys)
    }

    /** Lần [render] gần nhất hiện các chữ khớp phần chọn dở (không phụ thuộc [all] / [hidden]). */
    private var renderedSelection = false
    /** Chuỗi phím của lần [render] gần nhất (đang tra dở thì chưa tính). */
    var renderedKeys: String? = null
        private set

    /**
     * Sắp chọn một gợi ý (ở lưới, thanh gợi ý hay phím cách): nhớ trạng thái thu hẹp lúc đó ([Back]), để xoá chữ vừa
     * chọn thì trở lại các âm đã chốt. Lưới đã nhớ khi chạm ô thì không nhớ lại lần nữa.
     */
    fun remember() {
        val b = Back(keys, picked.toList(), autoPicked.toSet(), locks.toList())
        val last = history.lastOrNull()
        if (last != null && last.keys == b.keys && last.picked == b.picked && last.locks == b.locks) return
        history.add(b)
    }

    /**
     * Bỏ các trạng thái đã nhớ lúc chọn chữ ([remember]): gỡ chữ đã chọn bằng phím xoá thì các âm đã chốt giữ như
     * đang thấy, không trở lại lúc chọn chữ đó (người dùng muốn 2026-11-15).
     */
    fun forgetHistory() {
        history.clear()
    }

    /** Đã chọn một gợi ý (chuỗi phím còn lại là phần sau): bỏ phần chọn dở, lần đổi phím tới không giữ âm đã chốt. */
    fun dropSelection() {
        dropPending()
        picked.clear()
        autoPicked.clear()
        locks.clear()
    }

    /**
     * Phím cách ngoài bàn phím khi đang chọn dở mà âm đang chọn là âm cuối: chốt nó như phím cách của bảng; còn âm phía
     * sau thì không chốt (phím cách gõ như thường, để còn dời con trỏ lên sửa; người dùng muốn 2026-11-14). `false` nếu
     * không chốt.
     */
    fun lockFromKeyboard(): Boolean {
        if (!selecting || !canLock() || syllableCount() != locks.size + 1) return false
        lockNow()
        return true
    }

    /** Đang chọn dở ở cột trái (đã chọn phần âm hay đã chốt âm); thu bảng lên vẫn giữ (người dùng muốn 2026-11-14). */
    val selecting: Boolean get() = picked.isNotEmpty() || locks.isNotEmpty()

    /** Các gợi ý khớp phần đang chọn dở ([selecting]), cho thanh gợi ý khi bảng đã thu lên. */
    fun selection(): List<Candidate> = selectionQuery()?.let { q ->
        if (single) firstSingles(Core(narrow, narrowed), q) else narrowed(q)
    } ?: emptyList()

    /**
     * Lựa chọn gửi cho lõi của [selection] (để tra ở luồng nền); `null` khi không chọn dở gì và không bật 𡨸單. Bật 𡨸單
     * thì thanh gợi ý vẫn chỉ hiện chữ đơn của âm đầu khi bảng đã thu lên (người dùng muốn 2026-11-17); kết quả lọc bằng
     * [singlesOf].
     */
    fun selectionQuery(): List<Pair<Int, String>>? = when {
        single -> singleQuery(locks, picked, if (selecting) null else typedSyllable())
        selecting -> query(picked)
        else -> null
    }

    /** Nút 𡨸單 đang bật. */
    val singleOn: Boolean get() = single

    /** Thanh gợi ý khi bảng thu lên có lọc theo bảng không ([selection]): đang chọn dở hay bật 𡨸單. */
    val filtersBar: Boolean get() = selecting || single

    /**
     * Chuỗi phím đổi (kể cả khi bảng đang thu): bỏ phần chọn dở, trừ khi xoá về đúng lúc chọn một chữ trong lưới thì
     * trở lại các bước lúc đó. Không dựng lại bảng.
     */
    fun syncKeys(keys: String) = syncKeys(keys, live = true)

    /** [live]: đổi thật (bỏ lần dựng đang tra dở); `false` khi chỉ thử để tính lựa chọn ([queryAfter]). */
    private fun syncKeys(keys: String, live: Boolean) {
        if (keys != this.keys) {
            if (live) dropPending()
            // Gõ tiếp, xoá hay sửa một âm giữa chừng: các âm đã chốt mà đoạn phím không đổi vẫn giữ (khớp phần đầu và
            // phần đuôi giống nhau của hai chuỗi đoạn âm). Âm bị sửa nằm giữa các âm đã chốt thì chốt theo phím mới gõ
            // (bỏ âm đã chốt rồi gõ âm khác vào, như gỡ chữ Hán Nôm đã chọn; người dùng muốn 2026-11-14). Lựa chọn của âm
            // đang chọn giữ nếu đoạn của nó không đổi.
            val before = chunksOf(this.keys).map { this.keys.substring(it) }
            val after = chunksOf(keys).map { keys.substring(it) }
            var p = 0
            while (p < before.size && p < after.size && before[p] == after[p]) p++
            var s = 0
            while (s < before.size - p && s < after.size - p && before[before.size - 1 - s] == after[after.size - 1 - s]) s++
            // Đoạn cũ thứ o ở đâu trong chuỗi mới (null: đoạn đã đổi).
            fun moved(o: Int): Int? = when {
                o < p -> o
                o >= before.size - s -> o - before.size + after.size
                else -> null
            }
            val target = if (locks.size < before.size) moved(locks.size) else null
            val last = if (keys.isEmpty()) -1 else locks.indices.filter { it < before.size }.mapNotNull(::moved).maxOrNull() ?: -1
            val kept = (0..last).map { j -> locks.indices.firstOrNull { moved(it) == j }?.let { locks[it] } ?: emptyList() }
            if (keys.isEmpty() || target == null || target != kept.size) {
                picked.clear()
                autoPicked.clear()
            }
            locks.clear()
            locks.addAll(kept)
            // Xoá chữ vừa chọn trong lưới (chuỗi phím trở lại như trước khi chọn): trở lại đúng các bước thu
            // hẹp lúc chọn nó, không phải thu hẹp lại từ đầu.
            val back = history.lastOrNull()
            if (back != null && back.keys == keys) {
                history.removeAt(history.lastIndex)
                picked.clear()
                autoPicked.clear()
                locks.clear()
                picked.addAll(back.picked)
                autoPicked.addAll(back.auto)
                locks.addAll(back.locks)
            } else if (keys.isEmpty()) {
                history.clear()
            }
        }
        this.keys = keys
    }

    /**
     * Cấp kế tiếp sau các lựa chọn. Đã bắt đầu thu hẹp mà cấp kế chỉ có một lựa chọn (d → ư thì vần chỉ còn ươ, rồi
     * phụ âm cuối chỉ còn ng) thì tự chọn luôn, khỏi chạm một mục không bớt được chữ nào (người dùng duyệt
     * 2026-11-14); nút "‹" vẫn ghi đủ phần đã chọn.
     */
    private fun advance(
        core: Core, locks: List<List<Pair<Int, String>>>, picked: MutableList<Pair<Int, String>>, autoPicked: MutableSet<Int>,
    ): Pair<Int, List<String>>? {
        var next = core.narrow(queryOf(locks, picked))
        while (picked.isNotEmpty() && next != null && next.first != PART_SYLLABLE && next.second.size == 1) {
            if (picked.any { it.first == next!!.first }) break
            picked.add(next.first to next.second[0])
            autoPicked.add(next.first)
            next = core.narrow(queryOf(locks, picked))
        }
        return next
    }

    /**
     * Lựa chọn gửi cho lõi ([narrow]) nếu chuỗi phím đổi thành [newKeys] (như sau khi chọn một chữ: [syncKeys]), không
     * đổi gì ở bảng. Để tính trước ở luồng nền (lõi nhớ kết quả), chạm chọn chữ khỏi chờ.
     */
    fun queryAfter(newKeys: String): List<Pair<Int, String>> {
        val k = keys
        val p = picked.toList()
        val a = autoPicked.toSet()
        val l = locks.toList()
        val h = history.toList()
        syncKeys(newKeys, live = false)
        val q = query(picked)
        keys = k
        picked.clear(); picked.addAll(p)
        autoPicked.clear(); autoPicked.addAll(a)
        locks.clear(); locks.addAll(l)
        history.clear(); history.addAll(h)
        return q
    }

    /** Lựa chọn gửi cho lõi ([narrow]) sau khi chốt âm đang chọn ([lock]); `null` nếu không chốt được. Để tính trước. */
    fun queryAfterLock(): List<Pair<Int, String>>? = if (canLock()) query(picked) + (LOCK_LEVEL to "") else null

    /** Lựa chọn gửi cho lõi: các âm đã chốt (mỗi âm kết thúc bằng mốc [LOCK_LEVEL]) rồi lựa chọn [p] của âm đang chọn. */
    private fun query(p: List<Pair<Int, String>>): List<Pair<Int, String>> = queryOf(locks, p)

    private fun queryOf(locks: List<List<Pair<Int, String>>>, p: List<Pair<Int, String>>): List<Pair<Int, String>> =
        locks.flatMap { it + (LOCK_LEVEL to "") } + p

    /** Phím chốt âm ([lockButton]): giữ các lựa chọn của âm đang chọn, cột chuyển sang âm tiếp theo. */
    /** Còn âm đang chọn thì chốt được, kể cả âm cuối: chốt xong thì gõ tiếp các âm sau (người dùng muốn 2026-11-14). */
    private fun canLock() = keys.isNotEmpty() && syllableCount() > locks.size

    /**
     * Phím chốt âm: chốt ngay, không chờ lần dựng đang tra dở (lần dựng sau tính cho mọi âm đã chốt). Xếp hàng chờ
     * ([whenReady]) thì chạm dồn dập mỗi lần chốt phải chờ một lần dựng (~0,2 giây khi đã chốt 40–60 âm): 60 lần chạm
     * xong 2 giây sau mới chốt hết (đo 2026-11-16). Đang dựng dở thì lựa chọn tự chọn của âm này chưa có: âm chốt theo
     * phím đã gõ, như khi chưa chọn gì ở cột trái.
     */
    private fun lockNow() {
        if (pending && waiting.isNotEmpty()) whenReady(::lock) else lock()
    }

    private fun lock() {
        if (!canLock()) return
        locks.add(picked.toList())
        picked.clear()
        autoPicked.clear()
        keepFilterScroll = false
        render()
        onLocked()
    }

    /** Vừa chốt một âm (phím cách của bảng hay của bàn phím). */
    var onLocked: () -> Unit = {}

    /** Đã chốt hết các âm đang gõ (chưa gõ âm nào sau đó). */
    val lockedAll: Boolean get() = locks.isNotEmpty() && syllableCount() <= locks.size

    /** Còn âm đã chốt. */
    val hasLocks: Boolean get() = locks.isNotEmpty()

    /** Lần xoá kế tiếp gỡ cả một chữ đã chọn ([history]) hay bỏ chốt cả một âm, không phải xoá một phím. */
    private fun wholeSyllable() = locks.isNotEmpty() || history.isNotEmpty()

    /**
     * Phím xoá trong bảng: bỏ chốt âm chốt cuối, âm đó thành chữ thường (đen), không mở lại các lựa chọn lúc chốt
     * (người dùng muốn 2026-11-15). `false` nếu không còn âm đã chốt.
     */
    fun unlockLastPlain(): Boolean {
        if (locks.isEmpty()) return false
        dropPending()
        locks.removeAt(locks.lastIndex)
        picked.clear()
        autoPicked.clear()
        keepFilterScroll = false
        render()
        return true
    }

    /** Bỏ chốt âm vừa chốt, mở lại nó với đúng các lựa chọn lúc chốt (như xoá dấu cách vừa ấn). */
    fun unlockLast(): Boolean {
        if (locks.isEmpty()) return false
        dropPending()
        picked.clear()
        autoPicked.clear()
        picked.addAll(locks.removeAt(locks.lastIndex))
        keepFilterScroll = false
        render()
        return true
    }

    /** Chạm ở cột trái: bảng còn đang tra lõi cho lần trước thì làm sau khi tra xong ([whenReady]). */
    private fun pick(choice: Pair<Int, String>?) = whenReady { pickNow(choice) }

    private fun pickNow(choice: Pair<Int, String>?) {
        when {
            // Chưa chọn gì ở âm đang chọn: lùi là mở lại âm vừa chốt, với đúng các lựa chọn lúc chốt.
            choice == null && picked.isEmpty() && locks.isNotEmpty() -> picked.addAll(locks.removeAt(locks.lastIndex))
            // Lùi một bước người dùng đã chọn, bỏ luôn các bước tự chọn sau nó.
            choice == null -> {
                while (picked.isNotEmpty() && autoPicked.remove(picked.last().first)) picked.removeAt(picked.lastIndex)
                if (picked.isNotEmpty()) picked.removeAt(picked.lastIndex)
            }
            // Đã chọn cả âm: chạm thanh khác của nó thì đổi, không chồng thêm lựa chọn.
            choice.first == PART_SYLLABLE && picked.lastOrNull()?.first == PART_SYLLABLE -> picked[picked.lastIndex] = choice
            else -> picked.add(choice)
        }
        // Chọn thanh: cột vẫn là các thanh của âm đó, giữ chỗ đang cuộn tới (vuốt xuống chọn tối không bị nhảy lên).
        keepFilterScroll = choice?.first == PART_SYLLABLE
        render { onColumnTap() }
    }

    /** Vừa chạm một mục ở cột trái (khung gõ hiện khung kính nếu âm đang chọn bị "…" che). */
    var onColumnTap: () -> Unit = {}

    /** Chiều cao bảng (bằng bàn phím): bốn hàng gợi ý mỗi trang. */
    fun setHeight(height: Int) {
        val h = (height / 4).coerceAtLeast(context.dpi(40))
        if (h != rowHeight) {
            rowHeight = h
            singleButton.layoutParams.height = singleHeight()
            singleButton.requestLayout()
            lockButton.layoutParams = lockParams()
            render()
        }
    }

    private fun pageHeight(): Int = (pageScroll.height / rowHeight).coerceAtLeast(1) * rowHeight

    /**
     * Cột trái cao bằng bảng (4 hàng lưới): nút 𡨸單 một hàng như bản 0.1.6, còn 3 hàng cho các âm, mỗi âm 0,8 hàng
     * cho dễ bấm, thấy được khoảng 4 âm cùng lúc (người dùng muốn thấy đủ 3–4 âm, 2026-11-14). Phím chốt âm nằm ở
     * góc lưới ([lockParams]), không chiếm chỗ của cột.
     */
    private fun singleHeight() = rowHeight

    /** Chiều cao phím chốt âm. */
    private fun lockHeight() = (rowHeight * 0.7f).toInt()

    /** Phím chốt âm ở góc dưới bên trái lưới: trên [LOCK_CELLS] ô để trống của hàng cuối trang, giữa theo chiều cao hàng. */
    private fun lockParams() = FrameLayout.LayoutParams(
        gridWidth() * LOCK_CELLS / cols.coerceAtLeast(1) - context.dpi(12), lockHeight(), Gravity.BOTTOM or Gravity.START,
    ).apply {
        setMargins(context.dpi(6), 0, 0, (rowHeight - lockHeight()) / 2 + (pageScroll.height - pageHeight()).coerceAtLeast(0))
    }

    /** Phím chốt âm theo số ô lưới và chiều cao lưới (xoay màn hình, đổi cỡ bàn phím). */
    private fun updateLockParams() {
        val want = lockParams()
        val now = lockButton.layoutParams as? FrameLayout.LayoutParams
        if (now == null || now.width != want.width || now.height != want.height || now.bottomMargin != want.bottomMargin) {
            lockButton.layoutParams = want
        }
    }

    /** Chiều cao mỗi âm ở cột trái ([singleHeight]). */
    private fun filterRowHeight() = (rowHeight * 0.8f).toInt()

    /** Lần dựng lưới để dựng dần các trang sau; dựng lưới mới thì việc dựng dần của lần cũ thôi. */
    private var fillGen = 0

    /** Chỗ cuộn mà ▲▼ vừa nhắm tới (đang cuộn tới đó), -1 nếu không: mũi tên mờ theo trang đích, không chờ cuộn xong. */
    private var scrollTarget = -1

    /** Cuộn tới [y] (▲▼), mũi tên đổi ngay theo trang đích. */
    private fun pageTo(y: Int) {
        val target = y.coerceIn(0, maxScroll())
        scrollTarget = target
        pageScroll.smoothScrollTo(0, target)
        updatePaging()
    }

    /** Chỗ cuộn xa nhất, tính theo số hàng đã dựng (đúng cả khi vừa thêm hàng mà chưa đo lại). */
    private fun maxScroll() = (rows.childCount * rowHeight + rows.paddingBottom - pageScroll.height).coerceAtLeast(0)

    private fun updatePaging() {
        if (pageScroll.scrollY == scrollTarget) scrollTarget = -1
        val y = if (scrollTarget >= 0) scrollTarget else pageScroll.scrollY
        upButton.alpha = if (y > 0) 1f else 0.4f
        downButton.alpha = if (y < maxScroll() || next < items.size) 1f else 0.4f
    }

    /** Kết quả gọi lõi cho một lần dựng ([render]), tính ở luồng nền. */
    private class Computed(
        val picked: List<Pair<Int, String>>,
        val auto: Set<Int>,
        val level: Pair<Int, List<String>>?,
        val chosen: List<Candidate>?,
        val quick: String?,
        val firstSingles: List<Candidate>,
    )

    /** Lần dựng mới nhất; lần tra ở luồng nền có số khác thì đã cũ. */
    @Volatile private var renderGen = 0
    /** Đang tra lõi cho lần dựng mới nhất (lưới còn hiện kết quả lần trước). */
    private var pending = false
    /** Các chạm (cột trái, phím cách, xoá…) đến lúc đang tra: làm theo thứ tự sau khi dựng xong. */
    private val waiting = ArrayDeque<() -> Unit>()
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Làm [action] ngay, hay sau khi lần dựng đang tra dở xong (các lựa chọn tự chọn của nó đã vào [picked]). */
    private fun whenReady(action: () -> Unit) {
        if (pending) waiting.add(action) else action()
    }

    /** Trạng thái thu hẹp vừa bị đổi trực tiếp: bỏ lần dựng đang tra dở (kết quả của nó đã cũ). */
    private fun dropPending() {
        if (!pending) return
        renderGen++
        pending = false
        drain()
    }

    private fun drain() {
        while (!pending && waiting.isNotEmpty()) waiting.removeFirst()()
    }

    /**
     * Dựng lại bảng: gọi lõi ở luồng nền ([core], [compute]) rồi mới dựng trên luồng giao diện; [after] chạy sau khi
     * dựng. Chạm dồn dập thì chỉ dựng lần mới nhất.
     */
    private fun render(after: (() -> Unit)? = null) {
        // Thao tác dồn dập (giữ phím xoá, ấn cách liên tục): khung gõ đổi ngay theo từng phím, còn lưới chỉ dựng lại khi
        // người dùng dừng tay [RAPID_MS], cho trạng thái cuối; dựng mọi trạng thái giữa chừng thì lưới luôn đuổi theo sau
        // (đo trên điện thoại 2026-11-16: giữ xoá khi bật 𡨸單, mỗi lần dựng 10–150 ms mà phím lặp mỗi ~50 ms).
        val now = android.os.SystemClock.uptimeMillis()
        val rapid = now - lastRenderCall < RAPID_MS
        lastRenderCall = now
        after?.let(deferredAfter::add)
        main.removeCallbacks(deferredRender)
        if (rapid) {
            renderGen++
            pending = true
            onCoreNeeded()
            main.postDelayed(deferredRender, RAPID_MS)
            return
        }
        renderNow()
    }

    /** Lần gọi [render] gần nhất (ms, uptime). */
    private var lastRenderCall = 0L
    /** Các việc chờ lần dựng (đang hoãn vì thao tác dồn dập) xong. */
    private val deferredAfter = ArrayList<() -> Unit>()
    private val deferredRender = Runnable { renderNow() }

    private fun renderNow() {
        val afters = deferredAfter.toList()
        deferredAfter.clear()
        val after: (() -> Unit)? = if (afters.isEmpty()) null else ({ afters.forEach { it() } })
        val gen = ++renderGen
        pending = true
        val c = core()
        val p = ArrayList(picked)
        val a = HashSet(autoPicked)
        val l = locks.toList()
        val one = single
        val k = keys
        renderedKeys = null
        val typed = if (p.isEmpty() && l.isEmpty()) typedSyllable() else null
        // Âm đầu đã chốt theo phím đã gõ: chữ của đoạn đó (đủ âm có nguyên âm), lấy trên luồng giao diện.
        val firstTyped = if (l.isNotEmpty() && l[0].isEmpty()) typedChunk(0).lowercase().takeIf { s ->
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).any { it in "aeiouy" }
        } else null
        onCoreNeeded()
        worker.execute {
            if (gen != renderGen) return@execute
            val result = runCatching { perf("grid.compute", "${l.size} locks $p") { compute(c, l, p, a, one, typed, firstTyped) } }.getOrNull()
            main.post {
                if (gen != renderGen) return@post
                pending = false
                if (result != null) {
                    picked.clear()
                    picked.addAll(result.picked)
                    autoPicked.clear()
                    autoPicked.addAll(result.auto)
                    level = result.level
                    perf("grid.render", "$keys $picked locks=${locks.size}") { renderTimed(result) }
                    renderedKeys = k
                    after?.invoke()
                    onReady?.let {
                        onReady = null
                        it()
                    }
                }
                drain()
            }
        }
    }

    /** Phần gọi lõi của [render], chỉ dùng các bản sao trạng thái (chạy ở luồng nền). */
    private fun compute(
        core: Core, locks: List<List<Pair<Int, String>>>, picked: MutableList<Pair<Int, String>>, auto: MutableSet<Int>,
        single: Boolean, typed: String?, firstTyped: String?,
    ): Computed {
        val level = advance(core, locks, picked, auto)
        // Bật 𡨸單 khi đã chốt âm: chỉ chữ đơn của âm đầu (theo lựa chọn lúc chốt nó), như 单字 của Gboard chỉ hiện chữ
        // đơn của âm đầu dù gõ nhiều âm (đo trên điện thoại 2026-11-17, `gong chan dang`). Trước đây mỗi âm đã chốt một
        // nhóm: ~40 âm mất 1,1–1,5 giây, phải tính dần ở luồng nền.
        // Đã chốt âm mà từ điển không có cụm nào khớp: lưới như khi chưa thu hẹp (chọn dần từng chữ), không để trống.
        val chosen = if (single) firstSingles(core, singleQuery(locks, picked, typed, firstTyped))
        else if (picked.isNotEmpty() || locks.isNotEmpty()) {
            core.narrowed(queryOf(locks, picked)).takeIf { locks.isEmpty() || it.isNotEmpty() }
        } else null
        // Chọn nhanh đúng âm đã gõ ([renderFilters]). Âm đã gõ trùng nhãn một lựa chọn của cấp đang hỏi (gõ `a`: vần a)
        // thì giữ lựa chọn đó, không thay bằng chọn nhanh (người dùng muốn 2026-11-15: gõ a để ra anh).
        val quick = typed?.takeIf {
            picked.isEmpty() && locks.isEmpty() && level?.first != PART_SYLLABLE && it.isNotEmpty() &&
                level?.second?.contains(it) != true && core.narrowed(listOf(PART_SYLLABLE to it)).isNotEmpty()
        }
        // Chữ đơn của âm đầu, xếp cuối lưới khi mở bảng / đã chốt âm ([panelOrder]).
        val singles = if (!single && (locks.isNotEmpty() || picked.isEmpty())) firstSingles(core, singleQuery(locks, picked, typed, firstTyped)) else emptyList()
        return Computed(picked, auto, level, chosen, quick, singles)
    }

    private fun renderTimed(result: Computed) {
        val labels = locks.indices.map(::lockedLabel)
        if (labels != shownLocks) {
            shownLocks = labels
            onLocks(labels)
        }
        val picking = pickingLabel
        if (picking != shownPicking) {
            shownPicking = picking
            onPicking()
        }
        setBackgroundColor(theme.header)
        columnLine.background = GradientDrawable().apply {
            setColor(theme.hintText)
            alpha = 90
        }
        perf("  renderFilters") { renderFilters(result.quick) }
        // Phím chốt âm: như phím cách của bàn phím số (nền phím chức năng, biểu tượng phím cách).
        lockButton.background = GradientDrawable().apply {
            cornerRadius = context.dp(10f)
            setColor(theme.specialKey)
        }
        lockButton.setColorFilter(theme.text)
        // Hết âm phía sau: phím chốt mờ đi, không bấm được.
        val lockable = canLock()
        lockButton.isEnabled = lockable
        lockButton.alpha = if (lockable) 1f else 0.35f
        val singleColor = if (single) theme.accent else theme.text
        singleLabel.setTextColor(singleColor)
        singleLine.background = GradientDrawable().apply {
            cornerRadius = context.dp(1f)
            setColor(singleColor)
        }

        // Đang thu hẹp: lưới hiện đủ chữ khớp lựa chọn, thanh bỏ các chữ đó (gõ bương, chọn bương thì mọi chữ
        // bương xuống lưới, thanh còn bường, bướng…; người dùng muốn 2026-11-14).
        // Các chữ khớp phần đã chọn / chữ đơn của âm đầu đã tra ở luồng nền ([compute]).
        val chosen = result.chosen
        renderedSelection = chosen != null
        val source = chosen ?: all
        cols = gridColumns()
        updateLockParams()
        // Bảng mở thì thanh chỉ vẽ lại ở đây (một lần, khi bảng dựng xong), kể cả khi lưới không giữ chữ nào: vẽ thanh
        // ở chỗ khác rồi lưới bỏ bớt thì thanh nháy giữa gợi ý dài và ngắn (người dùng báo 2026-11-16).
        if (single) {
            // Bật 𡨸單 (như 单字 của Gboard, đo 2026-11-17): thanh và lưới là một danh sách chữ đơn, thanh lấy các chữ
            // đầu, lưới nối tiếp từ chữ kế, không lặp; thanh không còn cụm dài.
            val singles = source.filter { hanLength(it.text) <= 1 }
            narrowing = true
            items = onBar(singles)
        } else if (chosen != null && locks.isEmpty()) {
            // Đang chọn ở cột trái (chưa chốt âm): lưới hiện đủ chữ khớp lựa chọn, thanh bỏ các chữ đó (không lặp giữa
            // thanh và lưới).
            // Cụm dài quá [PANEL_MAX_HAN] chữ không xuống lưới (như [panelOrder]).
            val latin = pointedLatin(source)
            narrowLatin = latin
            items = panelFirstOf(source.filter { it !== latin && hanLength(it.text) <= PANEL_MAX_HAN && !it.joined })
            narrowing = true
            onNarrowing(items.mapTo(HashSet()) { it.text }, latin)
        } else {
            // Mở bảng / đã chốt âm: thanh như khi gõ ([byFrequency]: ô 1 gợi ý phủ hết, còn lại theo độ phổ biến; 2026-11-19);
            // lưới (người dùng muốn 2026-11-17, như Gboard) xếp từ dài
            // xuống ngắn, mỗi độ dài ít cụm ([panelOrder]), rồi chữ đơn của âm đầu. Lưới ít mục nên dựng nhanh.
            // Cụm lõi tự ghép (không có trong từ điển): thanh chỉ giữ cụm đầu tiên (câu tốt nhất), các biến thể khác một
            // chữ (共産党越南, 共産讜越南…) bỏ đi (người dùng muốn 2026-11-17).
            var firstJoined = true
            val ordered = byFrequency(chosen ?: all).filter { c ->
                !c.joined || firstJoined.also { firstJoined = false }
            }
            // Chữ Latin của âm đang trỏ tới giữ ô 2 của thanh dù cụm dài nhất được đôn lên đầu ([pointedLatin]).
            val latin = pointedLatin(ordered)
            val barList = if (latin == null) ordered else ordered.filter { it !== latin }.let { it.take(1) + latin + it.drop(1) }
            narrowing = true
            val rest = onBar(barList)
            val restTexts = rest.mapTo(HashSet()) { it.text }
            onBarTexts = barList.filter { it.text !in restTexts }.mapTo(HashSet()) { it.text }
            // Chữ đơn của âm đầu nối cuối lưới: bỏ chữ Latin của nó (đã có chữ Latin theo các âm đã chốt ở ô 2 của thanh;
            // trước đây `tôi` lẻ loi ở cuối lưới, người dùng báo 2026-11-18).
            items = panelOrder(rest, if (latin == null) result.firstSingles else result.firstSingles.filter { !it.literal })
        }
        rows.removeAllViews()
        rowsChanged = true
        pageScroll.scrollTo(0, 0)
        scrollTarget = -1
        next = 0
        lastRow = null
        perf("  appendRows", "${items.size} mục") { appendRows(FIRST_ROWS) }
        // Trang đầu hiện ngay; các trang sau dựng hết một lượt ngay sau đó, khi màn hình đã vẽ xong trang đầu (đỡ khựng
        // khi chạm), để ▲▼ không phải chờ dựng (người dùng thấy ▼ "chờ load", 2026-11-17). Quá [ALL_ROWS] hàng thì phần
        // sau vẫn dựng khi cuộn gần tới.
        // Dựng mỗi khung hình một trang (dựng 315 mục một lần khựng 40–210 ms trên máy ảo, đo 2026-11-17), lần dựng lưới
        // mới thì thôi.
        val token = ++fillGen
        pageScroll.postDelayed(object : Runnable {
            override fun run() {
                if (token != fillGen || next >= items.size || rows.childCount >= ALL_ROWS) return
                perf("  appendRows (trang)") { appendRows(rowsPerPage()) }
                updatePaging()
                pageScroll.postOnAnimation(this)
            }
        }, MORE_DELAY_MS)
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
        rowsChanged = true
        var added = 0
        while (next < items.size) {
            val c = items[next]
            val units = units(label(c))
            var row = lastRow
            if (row != null && used + units > cols) {
                // Hàng vừa xong còn thừa ngăn: chia đều cho mọi ô trong hàng, không dồn vào ô cuối (試 dài cả hàng).
                // Chỗ trống dưới phím chốt âm ([LOCK_SPACER]) giữ nguyên 2 ô.
                val cells = (0 until row.childCount).map(row::getChildAt).filter { it.tag != LOCK_SPACER }
                val extra = if (cells.isEmpty()) 0f else (cols - used).toFloat() / cells.size
                for (cell in cells) {
                    (cell.layoutParams as LayoutParams).weight += extra
                    cell.requestLayout()
                }
                row = null
                lastRow = null
            }
            if (row == null) {
                if (added == count) break
                row = addGridRow()
                lastRow = row
                used = if (row.childCount > 0) LOCK_CELLS else 0
                added++
                // Cụm dài không vừa phần còn lại của hàng có chỗ trống: sang hàng sau (hàng này chỉ có chỗ trống).
                if (used + units > cols) {
                    row = null
                    lastRow = null
                    continue
                }
            }
            row.addView(cell(c), LayoutParams(0, LayoutParams.MATCH_PARENT, units.toFloat()))
            used += units
            next++
        }
        padToPage()
    }

    /** Số hàng lưới mỗi trang (▲▼ cuộn từng trang). */
    private fun rowsPerPage() = if (pageScroll.height > 0) (pageHeight() / rowHeight).coerceAtLeast(1) else 4

    /** Hàng thứ [index] của lưới (hàng cuối mỗi trang) chừa 2 ô đầu cho phím chốt âm. */
    private fun reservesLock(index: Int) = filters && rowsPerPage() > 1 && index % rowsPerPage() == rowsPerPage() - 1

    /** Thêm một hàng lưới; hàng cuối mỗi trang ([reservesLock]) để trống 2 ô đầu, dưới phím chốt âm (người dùng muốn 2026-11-14). */
    private fun addGridRow(): LinearLayout {
        val row = newRow()
        val reserved = reservesLock(rows.childCount)
        rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        if (reserved) row.addView(View(context).apply { tag = LOCK_SPACER }, LayoutParams(0, LayoutParams.MATCH_PARENT, LOCK_CELLS.toFloat()))
        return row
    }

    /**
     * Lưới cao tròn số trang, để trang cuối cũng cuộn tới đúng mốc trang. Đã xếp hết gợi ý thì thêm hàng trống cho đủ
     * trang cuối, để trang nào cũng có phím chốt âm ở góc (ít gợi ý, kể cả lưới trống); chưa xếp hết thì chừa chỗ.
     */
    private fun padToPage() {
        val per = rowsPerPage()
        if (next >= items.size && filters && per > 1) {
            while (rows.childCount == 0 || rows.childCount % per != 0) addGridRow()
            lastRow = null
        }
        val bottom = ((per - rows.childCount % per) % per) * rowHeight
        if (rows.paddingBottom != bottom) {
            rows.setPadding(0, 0, 0, bottom)
            rowsChanged = true
        }
    }

    /** Cuộn còn chưa tới hai trang là hết phần đã dựng thì dựng thêm. */
    private fun loadMoreIfNeeded() {
        if (next < items.size && pageScroll.scrollY + 3 * pageScroll.height >= rows.height) appendRows(MORE_ROWS)
    }

    /** [quickSyllable]: mục chọn nhanh đúng âm đã gõ, đã kiểm ở luồng nền ([compute]); `null` nếu không có. */
    private fun renderFilters(quickSyllable: String?) {
        val keepY = filterScroll.scrollY.takeIf { keepFilterScroll }
        keepFilterScroll = false
        filterColumn.removeAllViews()
        if (keepY == null) filterScroll.scrollTo(0, 0) else filterScroll.post { filterScroll.scrollTo(0, keepY) }
        fun item(label: String, color: Int, size: Float, onClick: (() -> Unit)?) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            // Một dòng, âm dài (ngưỡng, nghiêng) thu nhỏ chữ cho vừa cột. Không dùng isSingleLine: nó bật cuộn ngang,
            // TextView coi bề ngang vô hạn nên không thu nhỏ, chữ bị cắt ("ngưỡnc").
            maxLines = 1
            setHorizontallyScrolling(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setAutoSizeTextTypeUniformWithConfiguration(9, size.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(color)
            setPadding(context.dpi(4), 0, context.dpi(4), 0)
            onClick?.let { setOnClickListener { it() } }
        }
        // Đã chọn: "‹ châu…", chọn tới cả âm có thanh thì "‹ chấu" (màu nhấn, có vạch bên phải như Gboard),
        // chạm để bỏ lựa chọn cuối.
        // Chưa chọn gì ở âm đang chọn mà đã chốt âm trước: ghi âm đang chọn như đã gõ ("‹ n" sau khi chốt miền; người
        // dùng muốn 2026-11-15, thay cho ghi âm vừa chốt của 2026-11-14); chạm để lùi (mở lại âm vừa chốt).
        // Đã chốt hết các âm: cột chỉ là dấu câu như Gboard, không có mục "‹" (người dùng muốn 2026-11-17); bỏ chốt bằng phím
        // xoá.
        val allLocked = locks.isNotEmpty() && locks.size >= syllableCount()
        if ((picked.isNotEmpty() || locks.isNotEmpty()) && !allLocked) {
            val label = pickedLabel(picked, locks.size).ifEmpty {
                when {
                    locks.isEmpty() -> ""
                    locks.size < syllableCount() -> typedChunk(locks.size)
                    else -> lockedLabel(locks.lastIndex)
                }
            }
            filterColumn.addView(item("‹ $label".trimEnd(), theme.accent, 15f) { pick(null) }.apply {
                background = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(theme.accent) }))
                    .apply { setLayerInset(0, context.dpi(60), context.dpi(14), 0, context.dpi(14)) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, filterRowHeight()))
        }
        // Đã chốt hết các âm (chốt âm cuối, phía sau hết âm): không hỏi tiếp, cột là dấu câu cuộn được như Gboard (người
        // dùng muốn 2026-11-17; danh sách đo trên Gboard 拼音). Chạm dấu thì như ấn phím dấu câu ([onPunctuation]). Chọn
        // từng cấp ở cột trái (chưa chốt) thì vẫn hỏi như cũ.
        if (allLocked) {
            for (p in PUNCTUATION) {
                filterColumn.addView(item(p, theme.text, 20f) { onPunctuation(p) }, LayoutParams(LayoutParams.MATCH_PARENT, filterRowHeight()))
            }
            return
        }
        // Chọn nhanh đúng âm đã gõ (gõ tôi thì có sẵn mục tôi), khỏi chọn từng cấp phụ âm, vần, thanh.
        val quick = quickSyllable
        if (quick != null) {
            filterColumn.addView(item(quick, theme.text, 17f) { pick(PART_SYLLABLE to quick) }.apply {
                contentDescription = "Âm $quick"
            }, LayoutParams(LayoutParams.MATCH_PARENT, filterRowHeight()))
        }
        val (part, options) = level ?: return
        // Đã chọn cả âm: các lựa chọn là thanh khác của chính âm đó, âm đang chọn tô màu nhấn.
        val current = picked.lastOrNull()?.takeIf { it.first == PART_SYLLABLE }?.second
        for (option in options) {
            // Âm không có phụ âm đầu (yêu): vần trùng nhãn với mục chọn nhanh ở trên thì khỏi hiện lại; chọn
            // nhanh vẫn đổi được thanh.
            if (option == quick) continue
            // Chỉ phần đang hỏi (qu → ô → ô → c), không ghép cả cụm; phụ âm đầu / cuối rỗng hiện "–".
            // Âm đang chọn sáng lên ngay tại chỗ trong cột (người dùng muốn 2026-11-14), nút "‹" phía trên
            // không ghi lại âm đó.
            val chosen = option == current
            filterColumn.addView(item(option.ifEmpty { "–" }, if (chosen) theme.accent else theme.text, 17f) { pick(part to option) }.apply {
                contentDescription = "${PART_NAMES.getOrElse(part) { "" }} ${option.ifEmpty { "không có" }}" +
                    if (chosen) " (đang chọn)" else ""
                if (chosen) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.InsetDrawable(
                        GradientDrawable().apply {
                            cornerRadius = context.dp(12f)
                            setColor(theme.accent)
                            alpha = 48
                        },
                        context.dpi(4), context.dpi(6), context.dpi(4), context.dpi(6),
                    )
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, filterRowHeight()))
        }
    }

    /**
     * Phần âm tiết đã chọn, ghép liền như chữ viết: phụ âm đầu + vần (hay nguyên âm đầu) + phụ âm cuối, chưa
     * tới thanh thì thêm "…"; đã chọn cả âm có thanh thì là âm đó (c, ư, c → "cưc…"; rồi cực → "cực").
     */
    private fun pickedLabel(choices: List<Pair<Int, String>>, syllable: Int): String {
        // Đã chọn tới cả âm có thanh: đúng âm đó (người dùng muốn 2026-11-17: chọn rõ dấu thì phải hiện đúng).
        choices.lastOrNull { it.first == PART_SYLLABLE }?.let { return it.second }
        val by = choices.toMap()
        if (by.isEmpty()) return ""
        val rhyme = by[PART_NUCLEUS] ?: by[PART_VOWEL].orEmpty()
        // Chưa chọn phụ âm đầu ở cột: phụ âm đã gõ của âm đó (gõ h, chọn vần oa → "hoa…", không "oa…"; 2026-11-17).
        val initial = by[PART_INITIAL] ?: typedInitial(syllable)
        return initial + rhyme + by[PART_FINAL].orEmpty() + "…"
    }

    /** Phụ âm đầu đã gõ của âm thứ [i] ([typedChunk]: `h`, `nh`, đ); không có thì rỗng. */
    private fun typedInitial(i: Int): String {
        val typed = typedChunk(i).lowercase()
        if (typed.startsWith("đ")) return "đ"
        // Dừng ở nguyên âm đầu tiên (kể cả có dấu: à, ơ… so theo chữ gốc), và ở `w` (ư).
        return typed.takeWhile { c ->
            c.isLetter() && java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0] !in "aeiouyw"
        }
    }

    /**
     * Nhãn âm đã chốt thứ [i]: cả âm có thanh nếu đã chọn tới đó, không thì phần đã chọn ("ph…"); chưa chọn gì thì theo
     * phím của đoạn đó ([typedChunk]: `w` là ư, như lõi hiểu; người dùng muốn 2026-11-14), "ư…", "t…".
     */
    private fun lockedLabel(i: Int): String {
        val choices = locks[i]
        return choices.lastOrNull { it.first == PART_SYLLABLE }?.second ?: pickedLabel(choices, i).ifEmpty {
            val typed = typedChunk(i).lowercase()
            when {
                typed == "w" -> "ư…"
                typed.isEmpty() -> "…"
                // Đoạn có nguyên âm: chữ đã gõ (miền), như khung gõ hiện.
                typed.all { it in 'a'..'z' || it == 'đ' } && typed.none { it in "aeiouy" } -> "$typed…"
                else -> typed
            }
        }
    }

    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        weightSum = cols.toFloat()
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

    private fun cell(c: Candidate) = EllipsisTextView(context).apply {
        text = context.hanNomText(label(c))
        gravity = Gravity.CENTER
        isSingleLine = true
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (hanLength(label(c)) > 0) 22f else 18f)
        setPadding(context.dpi(4), 0, context.dpi(4), 0)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(theme.pressedKey), null, android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE),
        )
        setOnClickListener {
            // Lưới đang hiện kết quả cũ (đang dựng lại cho phím / lựa chọn mới, kể cả lúc hoãn dựng vì thao tác dồn dập):
            // bỏ qua lần chạm, như Gboard. Chọn ô cũ thì chữ của chuỗi phím cũ áp lên chuỗi phím mới, nuốt sai phím (đo
            // 2026-11-16: chọn dồn dập 空 ở 𡨸單 thành 空空 + `ai'…`).
            if (pending || renderedKeys != keys) return@setOnClickListener
            history.add(Back(keys, picked.toList(), autoPicked.toSet(), locks.toList()))
            onPick(c)
        }
    }

    /**
     * Số ô một hàng lưới ([gridColumns]), tính lại mỗi lần dựng lưới ([render]): màn hình ngang nhiều ô hơn.
     */
    private var cols = UNITS

    /** Bề rộng lưới (px): đã đo thì lấy thật, chưa thì ước theo màn hình trừ cột trái và cột nút phải. */
    private fun gridWidth() = pageScroll.width.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels - context.dpi(64 + 1 + 72))

    /**
     * Số ô một hàng: mỗi ô khoảng [CELL_DP], ít nhất [UNITS] (màn hình dọc giữ như cũ). Gboard màn hình ngang 8 ô, mỗi ô
     * khoảng 72 dp, từ kép vừa một ô (đo trên Realme 560 dpi, 2026-11-14, lại 2026-11-17); lưới ngang của mình khoảng
     * 566 dp nên 8 ô.
     */
    private fun gridColumns() = maxOf(UNITS, (gridWidth() / context.dp(CELL_DP)).toInt())

    /**
     * Số ô (trên [cols] ô một hàng) của một gợi ý: chữ đơn và từ kép 1 ô (như Gboard), dài hơn thì khoảng 2 chữ một ô
     * (cụm 5 chữ 3 ô, không chiếm cả hàng); đo thấy không vừa thì thêm ô.
     */
    private fun units(text: String): Int {
        val han = hanLength(text)
        val base = when {
            han == 0 -> ((text.length + 3) / 4).coerceIn(1, cols)
            else -> ((han + 1) / 2).coerceIn(1, cols)
        }
        // Đo bề rộng thật (chữ Latin có dấu, chữ to…): ước theo số ký tự hay thiếu, ô bị cắt còn "u…".
        val unit = gridWidth() / cols.toFloat()
        if (unit <= 0f) return base
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, if (han > 0) 22f else 18f, resources.displayMetrics,
        )
        val need = android.text.Layout.getDesiredWidth(context.hanNomText(text), measurePaint) + context.dpi(12)
        return maxOf(base, kotlin.math.ceil(need / unit).toInt()).coerceAtMost(cols)
    }

    private val measurePaint by lazy { android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    /** Chữ hiện trong ô. */
    private fun label(c: Candidate): String = c.text

    /**
     * Bật 𡨸單 khi đã chốt âm: chữ đơn của âm đầu theo các lựa chọn lúc chốt nó ([want]), mỗi chữ một lần; kể cả gợi ý
     * giữ nguyên chữ Latin của âm đó (HanNomIME.withLockedLatin). Một lần tra lõi, như Gboard chỉ hiện âm đầu.
     */
    private fun firstSingles(core: Core, want: List<Pair<Int, String>>): List<Candidate> {
        val out = singlesOf(core.narrowed(want))
        // Âm có thanh mà từ điển không có chữ nào đúng âm đó: mọi chữ của âm đầu, khỏi ra bảng trắng.
        return if (out.isEmpty() && want.isNotEmpty() && want.all { it.first == PART_SYLLABLE }) singlesOf(core.narrowed(emptyList())) else out
    }

    /**
     * Thứ tự thanh gợi ý (người dùng muốn 2026-11-19; trước đó cụm dài nhất lên đầu): theo độ phổ biến, kể cả cụm phủ hết
     * phần đã gõ; chỉ câu tự ghép (lõi đặt ở đầu) giữ ô 1. Chữ Latin vẫn ở ô 2 ([pointedLatin]).
     */
    private fun byFrequency(list: List<Candidate>): List<Candidate> = when {
        list.size < 2 -> list
        list[0].joined -> listOf(list[0]) + list.drop(1).sortedByDescending { it.freq }
        else -> list.sortedByDescending { it.freq }
    }

    /**
     * Lưới khi mở bảng / đã chốt âm (người dùng muốn 2026-11-17): xếp từ dài xuống ngắn (8, 7… 2 chữ), mỗi độ dài chỉ
     * [PANEL_PER_LENGTH] cụm (cụm có trong từ điển xếp trước cụm tự ghép), chữ đơn và chữ Latin giữ hết; rồi chữ đơn của
     * âm đầu ([singles]) chưa có. Thanh gợi ý không giới hạn (lấy đầu danh sách đủ). Cùng độ dài thì giữ thứ tự xếp hạng.
     * Gõ đủ âm (không gõ tắt) thì không giới hạn gì, chỉ xếp dài trước.
     */
    /** [list] với cả cụm Latin nhiều chữ ([Candidate.panelFirst]) lên ô đầu, còn lại giữ thứ tự. */
    private fun panelFirstOf(list: List<Candidate>): List<Candidate> {
        val (first, rest) = list.partition { it.panelFirst }
        return first + rest
    }

    private fun panelOrder(rest: List<Candidate>, singles: List<Candidate>): List<Candidate> {
        val counts = HashMap<Int, Int>()
        // Cụm dài hơn số âm đã gõ (cụm đoán trước: gõ `t` ra 時間, 辭典…) không xuống lưới: chưa gõ dài thì lưới không có
        // cụm dài (người dùng báo 2026-11-17).
        val syllables = syllableCount()
        // Mọi âm đã gõ đều đủ (có nguyên âm, như `trung`): hiện hết các cụm dài hơn số âm đã gõ (từ đoán trước: 中國,
        // 中央…), không giới hạn (người dùng muốn 2026-11-17). Gõ tắt (`t`, `tr`) thì không có các cụm đó.
        // Cụm dài đúng tới số âm đã gõ thì vẫn mỗi độ dài [PANEL_PER_LENGTH] cụm: bỏ giới hạn này thì gõ `cộng sản đảng
        // việt nam` ra cả lưới cụm tự ghép 共産黨越蘚, 共産党越南… (người dùng báo 2026-11-17).
        val full = syllables > 0 && (0 until syllables).all { i ->
            java.text.Normalizer.normalize(typedChunk(i).lowercase(), java.text.Normalizer.Form.NFD).any { it in "aeiouy" }
        }
        val kept = rest.filter { c ->
            val n = hanLength(c.text)
            // Lưới không có cụm dài quá [PANEL_MAX_HAN] chữ (người dùng báo 2026-11-17: gõ cả câu thì lưới đầy câu 16
            // chữ bị cắt "…"); câu dài vẫn ở thanh gợi ý.
            if (n > PANEL_MAX_HAN) return@filter false
            // Cụm lõi tự ghép (không có trong từ điển) không xuống lưới (người dùng muốn 2026-11-17).
            if (c.joined) return@filter false
            if (n > syllables) return@filter full
            val max = if (n >= 2) PANEL_PER_LENGTH else Int.MAX_VALUE
            val used = counts.getOrDefault(n, 0)
            if (used >= max) false else {
                counts[n] = used + 1
                true
            }
        }.sortedByDescending { hanLength(it.text) }.let(::panelFirstOf)
        val seen = kept.mapTo(HashSet()) { it.text }
        onBarTexts?.let(seen::addAll)
        return kept + singles.filter { seen.add(it.text) }
    }

    /** Các chữ đang trên thanh gợi ý (lần [onBar] gần nhất), để lưới không lặp lại. */
    private var onBarTexts: Set<String>? = null

    /** Các chữ đơn (một âm, một chữ; hay giữ nguyên chữ Latin của âm đó) trong [list], mỗi chữ một lần. */
    fun singlesOf(list: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        return list.filter { c ->
            c.reading.split(' ').size == 1 && hanLength(c.text) == (if (c.literal) 0 else 1) && seen.add(c.text)
        }
    }

    /**
     * Lựa chọn gửi lõi cho chữ đơn của âm đầu khi bật 𡨸單 (như 单字 của Gboard chỉ hiện âm đầu dù gõ nhiều âm):
     * - đã chốt âm: theo lựa chọn lúc chốt âm đầu;
     * - đang chọn ở cột trái: theo các lựa chọn đó;
     * - âm đầu gõ có thanh (`trungs` → trúng): chỉ chữ của đúng âm đó;
     * - không thanh (`trung`): mọi chữ của âm đầu, không phân biệt thanh, dấu mũ / móc (trung, trúng, trùng, trưng…),
     *   chữ đúng âm đã gõ đứng trước (người dùng muốn 2026-11-17).
     */
    private fun singleQuery(
        locks: List<List<Pair<Int, String>>>, picked: List<Pair<Int, String>>, typed: String?, firstTyped: String? = null,
    ): List<Pair<Int, String>> = when {
        // Âm đầu chốt theo phím đã gõ (chưa chọn gì): đúng âm đã gõ, kể cả thanh (chốt là chốt đúng âm; 2026-11-17).
        locks.isNotEmpty() && locks[0].isEmpty() && firstTyped != null -> listOf(PART_SYLLABLE to firstTyped)
        locks.isNotEmpty() -> locks[0]
        picked.isNotEmpty() -> picked.toList()
        typed != null && hasTone(typed) -> listOf(PART_SYLLABLE to typed)
        else -> emptyList()
    }

    /** Âm có dấu thanh (sắc, huyền, hỏi, ngã, nặng); dấu mũ / móc / trăng không tính. */
    private fun hasTone(syllable: String) = java.text.Normalizer.normalize(syllable, java.text.Normalizer.Form.NFD)
        .any { it in "̣̀́̃̉" }

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

    companion object {
        /** Số ô một hàng tối thiểu (màn hình dọc). */
        const val UNITS = 6
        /** Số ô lưới phím chốt âm chiếm ở hàng cuối mỗi trang ([reservesLock]). */
        const val LOCK_CELLS = 2
        /** Lưới đứng yên chừng này ms mà lệch mốc trang thì tự nhảy về trang gần nhất. */
        const val SETTLE_MS = 120L
        /** Thẻ của chỗ trống dưới phím chốt âm trong hàng lưới. */
        const val LOCK_SPACER = "lock-spacer"
        /**
         * Bề rộng mỗi ô lưới mong muốn (dp), để màn hình ngang có nhiều ô hơn ([gridColumns]). 80 dp thì lưới ngang trên
         * điện thoại (~566 dp) chỉ 7 cột; Gboard 8 cột (người dùng muốn chia nhiều như Gboard, 2026-11-17).
         */
        const val CELL_DP = 70f
        /**
         * Số hàng dựng ngay khi mở / lọc lưới (một trang bốn hàng, đủ thấy), và mỗi lần dựng thêm: ngay sau đó
         * ([MORE_DELAY_MS]) hay khi cuộn gần hết.
         */
        const val FIRST_ROWS = 4
        /** Hai lần dựng lưới cách nhau dưới chừng này (ms) là thao tác dồn dập: hoãn dựng tới khi dừng tay ([render]). */
        const val RAPID_MS = 120L
        const val MORE_ROWS = 12
        /** Dấu câu ở cột trái khi đã chọn xong âm cuối, theo thứ tự cột trái của Gboard 拼音 (đo 2026-11-17). */
        val PUNCTUATION = listOf("？", "！", "……", "～", "：", "；", "@", "/", "\"", "%")
        /** Số hàng dựng một lượt sau trang đầu (~30 trang); danh sách dài hơn thì dựng thêm khi cuộn gần hết. */
        const val ALL_ROWS = 120
        /** Dựng thêm các trang sau chừng này ms sau trang đầu, để trang đầu kịp hiện. */
        const val MORE_DELAY_MS = 60L
        /** Giữ nút xoá của bảng ([repeatOnHold]): như phím xoá của bàn phím ([KeyboardView]). */
        const val HOLD_DELAY_MS = 300L
        const val HOLD_SLOW_MS = 70f
        const val HOLD_FAST_MS = 20f
        const val HOLD_SPEEDUP = 0.9f
        /** Giữ nút xoá khi mỗi lần xoá là cả một âm đã chốt / chữ đã chọn: ms một lần ([repeatOnHold]). */
        const val HOLD_SYLLABLE_MS = 200L
        /** Tên từng cấp thu hẹp, theo thứ tự `Part` của lõi. */
        val PART_NAMES = listOf("âm đầu", "nguyên âm", "vần", "âm cuối", "thanh")
        /** Số cụm mỗi độ dài (từ 2 chữ) trong lưới ([panelOrder]). */
        const val PANEL_PER_LENGTH = 2
        /** Cụm dài nhất (số chữ Hán) xuống lưới ([panelOrder]). */
        const val PANEL_MAX_HAN = 8
        const val PART_INITIAL = 0
        const val PART_VOWEL = 1
        const val PART_NUCLEUS = 2
        const val PART_FINAL = 3
        const val PART_SYLLABLE = 4
        /** Mốc chốt âm trong danh sách lựa chọn gửi cho lõi (`LOCK_LEVEL` ở android-ffi). */
        const val LOCK_LEVEL = 5
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
        // Một dòng; dài quá bề rộng thì [ComposingView.show] chỉ hiện đoạn có con trỏ (cuộn theo con trỏ).
        isSingleLine = true
        setPadding(context.dpi(12), 0, context.dpi(12), 0)
        visibility = View.INVISIBLE
    }
    private val backgroundPaint = Paint()

    init {
        // Chạm ở cột trái: khung gõ dựng lại xong mới xem âm đang chọn có bị che không.
        grid.onColumnTap = { composing.post { composing.peekTarget() } }
    }

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
                .coerceIn(editRow.coerceAtMost(maxRowLimit), maxRowLimit)
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
                .coerceIn((editRow - maxRowLimit) * rows, (editRow - minRow) * rows)
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
                // Đúng cỡ mặc định thì lưu 0 (theo mặc định), để mặc định đổi sau này (đo lại Gboard) vẫn có tác dụng.
                settings.landscapeRowHeight = if (row == landscapeDefaultRow) 0 else row
                settings.landscapeBottomGap = gap
            } else if (oneHandSize) {
                // Một tay: cỡ và chỗ nhớ riêng, bàn phím đầy đủ giữ nguyên.
                settings.oneHandRowHeight = row
                settings.oneHandBottomGap = gap
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
        // Một tay (dọc): đỉnh khung không lên quá ONE_HAND_MAX_FRAME_DP tính từ đáy bàn phím, như Gboard.
        if (oneHandPortrait) return (ONE_HAND_MAX_FRAME_DP - panelH / dp).coerceIn(0f, Settings.MAX_BOTTOM_GAP.toFloat())
        val system = paddingBottom - resizeGap * dp
        val share = if (landscape) LANDSCAPE_MAX_SHARE else MAX_SHARE
        return ((height * share - system - panelH) / dp).coerceIn(0f, Settings.MAX_BOTTOM_GAP.toFloat())
    }

    /** Chiều cao cả trang phím tính theo số hàng chữ. */
    private val pageRows get() = (if (landscape) LANDSCAPE_PAGE_ROWS else PORTRAIT_PAGE_ROWS) -
        if (keyboard.numberRow) 0f else if (landscape) LANDSCAPE_NUMBER_ROW else PORTRAIT_NUMBER_ROW
    private val minRow get() = when {
        oneHandPortrait -> rowForFrame(ONE_HAND_MIN_FRAME_DP)
        landscape -> Settings.MIN_LANDSCAPE_ROW_HEIGHT.toFloat()
        else -> Settings.MIN_ROW_HEIGHT.toFloat()
    }
    private val maxRowLimit get() =
        if (oneHandPortrait) rowForFrame(ONE_HAND_MAX_FRAME_DP) else Settings.MAX_ROW_HEIGHT.toFloat()
    private val oneHandPortrait get() = oneHandSize && !landscape

    /** Chiều cao hàng phím (dp) để cả khung (thanh trên + trang phím) cao [frameDp] dp. */
    private fun rowForFrame(frameDp: Float) = resizeRow + (frameDp - panel.height / context.dp(1f)) / pageRows

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
        // Màn hình ngang: khung nằm trong vùng phím, lùi khỏi tai thỏ như các phím (cạnh / góc kéo không nằm dưới camera).
        if (landscape) {
            frame.left = maxOf(frame.left, keyboard.insetLeft)
            frame.right = minOf(frame.right, width - keyboard.insetRight)
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
        // Màn hình ngang: khung gõ, thanh công cụ và bảng gợi ý thả xuống thẳng hàng với phím (có lề khi cửa sổ đè lên
        // tai thỏ).
        val insetL = if (landscape) keyboard.insetLeft.toInt() else 0
        val insetR = if (landscape) keyboard.insetRight.toInt() else 0
        (composing.layoutParams as LayoutParams).leftMargin = l + insetL
        (bar.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = insetL
            rightMargin = insetR
        }
        (grid.layoutParams as MarginLayoutParams).apply {
            leftMargin = insetL
            rightMargin = insetR
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
        panel.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(BAR_DP)))
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
        keyboard.onOneHandChange = {
            relayoutPanel()
            // Bật / tắt một tay: lấy lại cỡ và khoảng trống đã nhớ của chế độ đó.
            if (!resizeShown) {
                applySavedRowHeight()
                requestApplyInsets()
            }
        }
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(resize, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        resize.visibility = View.GONE
        // Bảng giữ emoji vẽ trên lớp phủ (đè hàng tìm / nhóm, tràn lên trên bàn phím như Gboard); mở / đóng thì dàn
        // lại để cửa sổ tính lại vùng nhận chạm ([HanNomIME.onComputeInsets]).
        overlay.extra = { canvas -> emoji.drawTones(canvas, overlay) }
        emoji.onTonesChanged = {
            overlay.invalidate()
            requestLayout()
        }
    }

    /** Đang chuyển các chạm của một cử chỉ cho bảng giữ emoji ([EmojiPanel.tonesTouch]). */
    private var tonesGesture = false

    /** Khung bảng giữ emoji trong cửa sổ, `null` nếu đóng. */
    fun tonesBox(): android.graphics.Rect? = emoji.tonesBoxInWindow()

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
        composing.loupeColor = theme.composeBox
        composing.loupeEdge = theme.hintText
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
    val composingText: CharSequence get() = composing.fullText

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
    /** [steps]: số chữ Hán đã chọn và âm đã chốt, mỗi cái một lần xoá (đếm ngược ở khung kính, [ComposingView.show]). */
    /** Gõ phím hay xoá ký tự thường: ẩn khung kính ([ComposingView.hideLoupe]). */
    fun hideLoupe() = composing.hideLoupe()

    /** Người dùng vừa ấn phím xoá: các lần dựng khung gõ ngay sau đó là xoá lùi ([ComposingView.deleting]). */
    fun markDeleting() {
        composing.deleting = true
        composing.post { composing.deleting = false }
    }

    /** Ấn xoá mà xoá một ký tự thường (chữ đen): khung kính đếm ngược khi gần tới ký tự đầu ([ComposingView.show]). */
    fun markPlainDeleting() {
        composing.plainDeleting = true
        composing.post { composing.plainDeleting = false }
    }

    fun setComposing(text: String, chosen: Int = 0, caret: Int = text.length, locked: Int = 0, target: Int = 0, steps: Int = 0) {
        // Bề rộng chữ tối đa: từ lề trái khung gõ tới mép phải, trừ lề trong và chỗ con trỏ ở cuối.
        val rowWidth = composeRow.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val left = (composing.layoutParams as? LayoutParams)?.leftMargin ?: 0
        composing.maxTextWidth = rowWidth - left - composing.paddingLeft - composing.paddingRight - context.dpi(4)
        // Có thể có chữ Hán đã chọn dở (như pinyin), cần phông Hán Nôm.
        composing.show(text, chosen, caret, locked, target, steps)
        composing.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        // Khung gõ tắt: hết lượt gõ, nút 𡨸單 cũng tắt.
        if (text.isEmpty()) grid.endComposing()
        // Đang tìm emoji mà khung gõ hiện: chỉ con trỏ khung gõ nhấp nháy, con trỏ ô tìm đứng yên.
        emojiSearch.field.steadyCaret = text.isNotEmpty()
        fitComposeRow()
    }

    /**
     * Sắp mở bảng: dựng bảng ẩn (đã đo, chưa vẽ) đè sau bàn phím; bàn phím vẫn hiện tới khi bảng dựng xong
     * ([showGrid]), như Gboard, không thấy bảng hiện kết quả cũ / trống rồi mới lấp (người dùng báo 2026-11-17: "lúc nào
     * mở bảng thả ra cũng bị thấy chớp load").
     */
    fun prepareGrid() {
        grid.layoutParams.height = keyboard.height.coerceAtLeast(1)
        grid.setHeight(keyboard.height)
        if (grid.visibility == View.GONE) grid.visibility = View.INVISIBLE
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
     * Mở / đóng bảng sửa văn bản: che cả thanh công cụ và bàn phím (cao bằng chừng đó, như bảng nhớ tạm), về bàn phím
     * bằng nút ← đầu bảng (người dùng muốn 2026-11-18; trước đây thanh công cụ vẫn hiện, nút ⊞ thành ✕). [selected]:
     * ô nhập đang có vùng chọn; [hasText]: ô nhập có chữ; [canPaste]: có chữ để dán (xem [TextEditPanel]).
     */
    fun showTextEdit(show: Boolean, selected: Boolean = false, hasText: Boolean = false, canPaste: Boolean = false) {
        if (show == textEditShown) return
        if (show) {
            showEmoji(false)
            showEmojiSearch(false)
            showClipboard(false, false, emptyList())
            if (toolsShown) showTools(false)
            textEdit.layoutParams.height = keyboard.height + bar.height
            textEdit.style = keyboard.style
            textEdit.open(selected, hasText, canPaste)
        }
        textEdit.visibility = if (show) View.VISIBLE else View.GONE
        bar.visibility = if (show) View.GONE else View.VISIBLE
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
        // Bảng giữ emoji đang mở: cả cử chỉ về bảng (chạm trong bảng là chọn, ngoài bảng là đóng), kể cả phần bảng đè
        // lên hàng tìm / nhóm hay tràn lên trên bàn phím.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) tonesGesture = emoji.tonesOpen
        if (tonesGesture) {
            emoji.tonesTouch(ev, this)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) tonesGesture = false
            return true
        }
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
        bar.layoutParams.height = context.dpi(if (landscape) 40 else BAR_DP)
        bar.landscapeBar = landscape
        bar.requestLayout()
    }

    private var landscape = false
    private var landscapeDefaultRow = 0

    /** Chiều cao hàng phím (dp) và khoảng trống dưới đáy (dp) đã lưu của hướng màn hình đang dùng. */
    private fun savedRow(s: Settings) = when {
        landscape -> s.landscapeRowHeight.takeIf { it > 0 } ?: landscapeDefaultRow
        oneHandSize -> s.oneHandRowHeight
        else -> s.rowHeight
    }
    private fun savedGap(s: Settings) = when {
        landscape -> s.landscapeBottomGap
        oneHandSize -> s.oneHandBottomGap
        else -> s.bottomGap
    }

    /** Đang dùng cỡ riêng của bàn phím một tay (màn hình dọc, không viết tay). */
    private val oneHandSize get() = keyboard.effectiveOneHand != OneHand.OFF

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
        /**
         * Bàn phím một tay, màn hình dọc: khung (thanh trên + phím) cao 224–358 dp, và đỉnh khung không lên quá 358 dp tính
         * từ đáy bàn phím (đo khung chỉnh kích thước một tay của Gboard trên điện thoại 2026-11-18).
         */
        const val ONE_HAND_MIN_FRAME_DP = 224f
        const val ONE_HAND_MAX_FRAME_DP = 358f
        /** Như [MAX_SHARE] cho màn hình ngang (đo trên Gboard: đỉnh khung lên tới chừng 22% màn hình tính từ trên). */
        const val LANDSCAPE_MAX_SHARE = 0.835f
        // Tỉ lệ màn hình ngang, đo trên Gboard (máy 792 × 354 dp). Các số hàng tính theo bước hàng chữ.
        /**
         * Chiều cao hàng chữ (cả khe) so với chiều cao màn hình. Đo lại 2026-11-17 trên Gboard cỡ mặc định: bước hàng chữ
         * 124 px = 35,4 dp trên 354 dp (trước đặt 0,11, cao hơn Gboard ~9%).
         */
        const val LANDSCAPE_ROW = 0.10f
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
        /**
         * Màn hình dọc, đo trên Gboard cỡ mặc định (điện thoại 3,5 px/dp, 2026-11-18): bước hàng chữ 161 px (46 dp, khe
         * 28 px), hàng số 150 px, hàng phím cách 172 px (phím cao 144 px).
         */
        const val PORTRAIT_NUMBER_ROW = 0.932f
        /** Thanh gợi ý / công cụ màn hình dọc (dp): mép trên bàn phím trùng Gboard (đo 2026-11-18; trước 48). */
        const val BAR_DP = 46
        const val PORTRAIT_BOTTOM_ROW = 1.068f
        const val PORTRAIT_PAGE_ROWS = 3 + PORTRAIT_NUMBER_ROW + PORTRAIT_BOTTOM_ROW
        /**
         * Chiều cao chuẩn để tính cỡ chữ, icon trên phím, so với hàng chữ. Hạ [LANDSCAPE_ROW] 0,11 → 0,10 mà cỡ chữ đã
         * khớp Gboard: số này tăng cùng tỉ lệ (1,13 × 1,1).
         */
        const val LANDSCAPE_TEXT = 1.243f
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
    /** Bề rộng chữ tối đa (px, không kể lề trong); 0 là không giới hạn. Đặt trước [show]. */
    var maxTextWidth = 0
    /** Cả chữ của khung gõ (chữ đang hiện có thể chỉ là một đoạn của nó, hai đầu là "…"). */
    var fullText = ""
        private set
    /** Nền và viền của khung kính (theo giao diện). */
    var loupeColor = 0
    var loupeEdge = 0
    private var loupe: android.widget.PopupWindow? = null
    /** Lần [show] trước: chữ, hết phần đã chọn, hết phần âm đã chốt (để tìm chỗ đổi). */
    private var lastText = ""
    private var lastChosen = 0
    private var lastLocked = 0
    /** Lần [show] trước: chữ đã tô, đoạn đang hiện, đoạn của âm cột trái đang trỏ tới (cho [peekTarget]). */
    private var lastFull: CharSequence = ""
    private var lastWindow = 0 to 0
    private var lastTarget = 0 to 0
    private var lastCaret = 0
    /**
     * Số chữ Hán đã chọn và âm đã chốt còn lại: mỗi cái một lần xoá nữa (gỡ chữ, bỏ chốt) mới tới chữ đầu tiên. Xoá lùi vượt
     * lằn "…" thì khung kính đếm ngược bằng số này, màu đỏ ở góc (người dùng muốn 2026-11-15).
     */
    private var steps = 0
    /** Bộ gõ đang xử lý phím xoá ([CandidateBar.markDeleting]). */
    var deleting = false
    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** Đoạn đang hiện bắt đầu ở ký tự này của [fullText]; [prefix] là số ký tự "…" thêm ở đầu. */
    private var windowStart = 0
    private var prefix = 0
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    /**
     * Chữ [text], trong đó [chosen] ký tự đầu là chữ đã chọn dở; con trỏ ở trước ký tự thứ [caret]. Dài quá
     * [maxTextWidth] thì chỉ hiện một đoạn có con trỏ (phần ẩn thay bằng "…"): con trỏ ở cuối thì hiện phần cuối, dời
     * con trỏ về chữ trước thì khung cuộn theo, nên vẫn tới được cả chữ đầu.
     */
    /**
     * [locked]: số ký tự ngay sau phần đã chọn là các âm đã chốt ở cột trái ("ph… "): chữ màu nhấn, không nền.
     * [target]: số ký tự ngay sau đó là âm cột trái đang trỏ tới: nền vàng.
     */
    fun show(text: String, chosen: Int, caret: Int = text.length, locked: Int = 0, target: Int = 0, steps: Int = 0) {
        this.steps = steps
        val c = caret.coerceIn(0, text.length)
        fullText = text
        // Dấu cách cuối hiện bằng dấu cách không ngắt: dấu cách thường ở cuối dòng không được tính vào bề rộng chữ
        // (TextView hẹp hơn một dấu cách), con trỏ sau nó bị cắt mất.
        val shownText = if (text.endsWith(' ')) text.dropLast(1) + ' ' else text
        val full = SpannableString(context.hanNomText(shownText))
        val end = chosen.coerceIn(0, text.length)
        if (end > 0) {
            full.setSpan(ForegroundColorSpan(accent), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            full.setSpan(BackgroundColorSpan(accent and 0xFFFFFF or (0x2E shl 24)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val lockEnd = (end + locked).coerceIn(end, text.length)
        if (lockEnd > end) full.setSpan(ForegroundColorSpan(accent), end, lockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val targetEnd = (lockEnd + target).coerceIn(lockEnd, text.length)
        if (targetEnd > lockEnd) full.setSpan(BackgroundColorSpan(TARGET), lockEnd, targetEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)        // Dấu cách cuối chuỗi (đã ấn cách một lần, ấn nữa là chọn gợi ý đầu): tô đỏ cho dễ thấy.
        if (text.length > end && shownText.endsWith(' ')) {
            full.setSpan(BackgroundColorSpan(TRAILING_SPACE), text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val (a, b) = window(full, c)
        // Chỗ đổi so với lần trước: ký tự đầu tiên khác nhau, không thì ranh giới phần đã chọn / đã chốt đã dời.
        val changed = when {
            lastText != text -> lastText.commonPrefixWith(text).length
            lastLocked != lockEnd -> minOf(lastLocked, lockEnd)
            lastChosen != end -> minOf(lastChosen, end)
            else -> null
        }
        // Chọn / gỡ chữ Hán, chốt / bỏ chốt âm.
        val selecting = end != lastChosen || lockEnd != lastLocked
        // Đang xoá lùi (gỡ chữ đã chọn, bỏ chốt âm, do phím xoá: [deleting]): chỉ lúc này mới có số đỏ đếm ngược. Không
        // đoán theo chữ: chọn chữ thì âm đã chốt thành một chữ Hán, ranh giới chốt cũng lùi lại.
        val backward = deleting && selecting
        // Vừa chọn thêm chữ Hán: khung kính ở chữ đó, không phải ô vàng của bảng thả (người dùng muốn 2026-11-15); giữ
        // tới khi gỡ chữ, chạm cột trái ([peekTarget]), gõ hay xoá ([hideLoupe]).
        if (end > lastChosen) showChosen = true else if (end < lastChosen) showChosen = false
        lastText = text
        lastChosen = end
        lastLocked = lockEnd
        lastFull = full
        lastWindow = a to b
        lastTarget = lockEnd to targetEnd
        lastCaret = c
        // Khung kính (người dùng muốn 2026-11-15): chỉ hiện cho chữ nằm trước "…" ở đầu khung gõ (bị che; [a] là đầu đoạn
        // đang thấy, nên màn ngang rộng hơn thì lằn "…" ở chỗ khác). Số đỏ đếm ngược (số lần xoá nữa tới chữ đầu) chỉ hiện
        // khi xoá lùi (gỡ chữ, bỏ chốt) vượt qua lằn "…". Bộ gõ ẩn khung kính khi gõ phím hay xoá ký tự thường
        // ([hideLoupe]); hết chữ hay chỗ đó lại thấy được thì ẩn.
        fun hidden(at: Int) = a > 0 && at < a
        // Mốc đếm ngược ở chỗ xoá: chữ dài không dấu cách thì 12, còn lại 5.
        val limit = aheadLimit(full, aheadAt(end, lockEnd))
        // Còn "…" mà chỉ còn ≤ mốc lần xoá nữa là hết chữ chọn / âm chốt ([steps]).
        fun ahead(now: Boolean) = now && a > 0 && steps in 1..limit
        // Mỗi lần xoá, khung gõ có thể dựng lại vài lần (tra gợi ý, cột trái báo âm đã chốt): lần sau không đổi gì thì giữ
        // số đang hiện, kẻo mất số đỏ. Chọn tới thì hết số; gõ phím thì [hideLoupe] đã xoá khung.
        val count = when {
            backward -> steps
            selecting -> 0
            else -> (loupeText?.count ?: 0).coerceAtMost(steps)
        }
        val shownBefore = loupeShows
        val hadTarget = lastHadTarget
        lastHadTarget = targetEnd > lockEnd
        when {
            text.isEmpty() -> hideLoupe()
            // Xoá chữ đen gõ thường (chưa chọn chữ, chưa chốt âm): còn ≤ mốc lần xoá nữa ([c] ký tự trước con trỏ) là tới
            // ký tự đầu tiên, mà phần đầu bị "…" che, thì khung kính ở chỗ xoá, đếm ngược (người dùng muốn 2026-11-19).
            plainDeleting && end == 0 && lockEnd == 0 -> {
                val at = (c - 1).coerceAtLeast(0)
                if (a > 0 && c <= aheadLimit(full, at)) showLoupe(full, at, c, c) else hideLoupe()
            }
            showChosen && end > 0 -> if (hidden(end - 1)) showLoupe(full, end - 1, c, count) else {
                dismissLoupe()
                loupeAt = -1
            }
            // Đang chọn âm / chữ: cột trái trỏ vào một âm (ô vàng), vừa chọn / gỡ chữ, chốt / bỏ chốt âm, hay đã có chữ chọn /
            // âm chốt (gõ thêm vẫn giữ). Chỉ hiện khi chỗ đó bị "…" che (người dùng muốn 2026-11-15):
            // 1. ô vàng (âm cột trái đang trỏ, sau các âm đã chốt) bị che: khung kính ở đó;
            // 2. đã chọn chữ Hán mà chữ chọn sau cùng bị che: khung kính ở chữ đó và âm kế tiếp, âm bảng gợi ý đang đưa
            //    chữ Hán cho (các âm đã chốt xanh mà chưa chọn chữ thì vẫn là âm đầu sau phần đã chọn);
            // 3. chưa chọn chữ nào mà đã chốt âm, cột trái không trỏ âm nào (đã chốt hết): bảng gợi ý đưa chữ Hán cho âm
            //    đầu tiên; nó bị che thì khung kính ở đó.
            targetEnd > lockEnd || selecting || steps > 0 -> when {
                // Xoá lùi mà còn hơn mốc lần xoá: không hiện khung kính (không có số đỏ 20, 60…).
                count > limit -> hideLoupe()
                targetEnd > lockEnd && hidden(lockEnd) -> showLoupe(full, lockEnd, c, count)
                end > 0 && hidden(end - 1) -> showLoupe(full, end - 1, c, count)
                end == 0 && targetEnd == lockEnd && lockEnd > 0 && hidden(0) -> showLoupe(full, 0, c, count)
                // 4. Xoá lùi khi có "…" mà chỉ còn ≤ [LOUPE_AHEAD] lần xoá: hiện sớm, ở chỗ vừa xoá, dù chỗ đó chưa bị che
                //    (phần bị che ngắn thì khung kính hiện quá muộn, không kịp thấy đếm ngược; người dùng muốn 2026-11-17).
                //    Khung gõ dựng lại sau đó (bảng dựng xong, không còn [deleting]) thì giữ, kẻo vừa hiện đã ẩn.
                ahead(backward || (loupe?.isShowing == true && count > 0)) -> showLoupe(full, aheadAt(end, lockEnd), c, count)
                else -> hideLoupe()
            }
            // Vừa hết ô vàng (thôi chọn âm ở cột trái), chưa chọn gì: ẩn theo.
            hadTarget -> hideLoupe()
            changed == null -> {}
            hidden(changed) -> showLoupe(full, changed, c)
        }
        // Khung kính vẫn hiện mà lần này không dựng lại: chép lại chữ mới (màu, ô vàng) cho khớp khung gõ; chỗ đó đã
        // thấy được (không còn sau "…") thì ẩn.
        if (loupeShows == shownBefore && loupe?.isShowing == true && loupeAt >= 0) {
            // Khung kính hiện sớm có số đỏ ([ahead]) thì giữ, dù chỗ đó chưa bị che.
            val early = ahead((loupeText?.count ?: 0) > 0)
            if (hidden(loupeAt) || early) showLoupe(full, loupeAt.coerceAtMost(text.length), c, loupeText?.count ?: 0) else hideLoupe()
        }
        windowStart = a
        prefix = if (a > 0) ELLIPSIS.length else 0
        this.caret = c - a + prefix
        this.text = piece(full, a, b)
        // Gõ thêm thì con trỏ hiện ngay, đếm nhịp nháy lại từ đầu (như ô nhập của hệ thống).
        cursorOn = true
        removeCallbacks(blink)
        if (text.isNotEmpty()) postDelayed(blink, BLINK_MS)
        invalidate()
    }

    /**
     * Chỗ khung kính hiện sớm khi xoá lùi ([LOUPE_AHEAD]): chữ Hán chọn sau cùng; chưa chọn chữ thì âm chốt sau cùng (khung
     * kính hiện nó và âm vừa bỏ chốt sau nó).
     */
    private fun aheadAt(end: Int, lockEnd: Int) = if (end > 0) end - 1 else (lockEnd - 1).coerceAtLeast(0)

    /** Mốc đếm ngược ở chỗ [at]: chữ dài không dấu cách (quá [LOUPE_RUN_CHARS] ký tự) thì [LOUPE_RUN_AHEAD], còn lại [LOUPE_AHEAD]. */
    private fun aheadLimit(full: CharSequence, at: Int): Int {
        val w = wordRanges(full).firstOrNull { at in it } ?: return LOUPE_AHEAD
        return if (w.last + 1 - w.first > LOUPE_RUN_CHARS) LOUPE_RUN_AHEAD else LOUPE_AHEAD
    }

    /** Bộ gõ đang xoá một ký tự thường (chữ đen; [CandidateBar.markPlainDeleting]). */
    var plainDeleting = false

    /** Đoạn [a, b) của [full], thêm "…" ở đầu / cuối nếu còn chữ bị ẩn bên đó. */
    private fun piece(full: CharSequence, a: Int, b: Int): CharSequence {
        if (a == 0 && b == full.length) return full
        return android.text.SpannableStringBuilder().apply {
            if (a > 0) append(ELLIPSIS)
            append(full, a, b)
            if (b < full.length) append(ELLIPSIS)
        }
    }

    /** Đoạn [a, b) của [full] vừa [maxTextWidth] và có con trỏ [c]: thường là phần cuối; con trỏ ở phần bị ẩn thì cuộn theo. */
    private fun window(full: CharSequence, c: Int): Pair<Int, Int> {
        val n = full.length
        val max = maxTextWidth.toFloat()
        fun fits(a: Int, b: Int) = android.text.Layout.getDesiredWidth(piece(full, a, b), paint) <= max
        if (max <= 0f || fits(0, n)) return 0 to n
        // Không cắt giữa cặp surrogate (chữ Hán ngoài BMP).
        fun low(i: Int) = i in 1 until n && Character.isLowSurrogate(full[i])
        // Phần cuối: đầu đoạn nhỏ nhất mà vẫn vừa.
        var lo = 0
        var hi = n
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (fits(m, n)) hi = m else lo = m + 1
        }
        var a = lo
        if (low(a)) a++
        if (c >= a) return a to n
        // Con trỏ ở phần đầu bị ẩn: hiện từ vài ký tự trước con trỏ, kéo về sau tới hết chỗ.
        a = (c - SCROLL_CONTEXT).coerceAtLeast(0)
        if (low(a)) a--
        lo = c
        hi = n
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (fits(a, m)) lo = m else hi = m - 1
        }
        var b = lo
        if (low(b) && b > c) b--
        return a to b
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        dismissLoupe()
        super.onDetachedFromWindow()
    }

    /**
     * Khung kính: một khung nhỏ trong mờ ngay trên khung gõ, chữ to hơn, hiện đoạn quanh chỗ [at] của [full] (vạch nhấn ở
     * đúng chỗ đó), không tự ẩn (người dùng muốn 2026-11-15): ẩn khi đổi ở chỗ đang thấy hay hết chữ; góc có số đỏ đếm ngược tới chữ đầu tiên ([steps]).
     */
    /** Các chữ của [full]: đoạn giữa các dấu cách; mỗi chữ Hán là một chữ. */
    private fun wordRanges(full: CharSequence): List<IntRange> {
        val n = full.length
        val words = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            val cp = Character.codePointAt(full, i)
            val len = Character.charCount(cp)
            when {
                cp == ' '.code || cp == 0xA0 -> i += len
                cp >= 0x2E80 -> { words.add(i until i + len); i += len }
                else -> {
                    var j = i
                    while (j < n) {
                        val c2 = Character.codePointAt(full, j)
                        if (c2 == ' '.code || c2 == 0xA0 || c2 >= 0x2E80) break
                        j += Character.charCount(c2)
                    }
                    words.add(i until j)
                    i = j
                }
            }
        }
        return words
    }

    /** Chữ trong khung kính: vẽ thêm con trỏ thật ([mark]) và số đỏ đếm ngược ([count]) ở góc trên bên phải. */
    private inner class LoupeText : TextView(context) {
        var mark = -1
        var count = 0

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (count >= 1) {
                countdownPaint.textSize = context.sp(10f)
                canvas.drawText(count.toString(), width - context.dp(COUNT_EDGE_DP), context.dp(12f), countdownPaint)
            }
            val l = layout ?: return
            if (mark < 0) return
            val x = totalPaddingLeft + l.getPrimaryHorizontal(mark.coerceAtMost(l.text.length))
            canvas.drawLine(x, totalPaddingTop + l.getLineTop(0).toFloat(), x, totalPaddingTop + l.getLineBottom(0).toFloat(), cursorPaint)
        }
    }

    private var loupeText: LoupeText? = null

    /** Chỗ khung kính đang phóng to (giữ khi khung gõ dựng lại mà không đổi gì). */
    private var loupeAt = -1

    /** Đếm số lần dựng khung kính (để [show] biết lần này đã dựng lại chưa). */
    private var loupeShows = 0
    /** Lần [show] trước có ô vàng (âm cột trái đang trỏ tới). */
    private var lastHadTarget = false

    private fun showLoupe(full: CharSequence, at: Int, caretAt: Int, countdown: Int = 0) {
        if (windowToken == null) return
        loupeShows++
        val n = full.length
        // Hai chữ quanh chỗ đổi (người dùng muốn 2026-11-15): chữ có chỗ đổi và chữ sau nó (hết chữ thì chữ trước nó).
        // Chữ là đoạn giữa các dấu cách; mỗi chữ Hán là một chữ.
        val words = wordRanges(full)
        if (words.isEmpty()) return
        loupeAt = at
        val wi = words.indexOfFirst { it.last >= at }.let { if (it < 0) words.lastIndex else it }
        val first = if (wi + 1 <= words.lastIndex) wi else (wi - 1).coerceAtLeast(0)
        val last = (first + 1).coerceAtMost(words.lastIndex)
        var s = words[first].first
        var e = words[last].last + 1
        // Chữ dài không có dấu cách để tách (tttt…, gõ loạn): hiện đúng [LOUPE_RUN_CHARS] ký tự quanh chỗ đổi, phần còn
        // lại là "…", kẻo khung kính dài quá màn hình (người dùng muốn 2026-11-19).
        val word = words[wi]
        if (word.last + 1 - word.first > LOUPE_RUN_CHARS) {
            s = (at - (LOUPE_RUN_CHARS - 1)).coerceIn(word.first, word.last + 1 - LOUPE_RUN_CHARS)
            e = s + LOUPE_RUN_CHARS
            if (s in 1 until n && Character.isLowSurrogate(full[s])) s--
            if (e in 1 until n && Character.isLowSurrogate(full[e])) e++
        }
        val head = if (s > 0) ELLIPSIS else ""
        val shown = android.text.SpannableStringBuilder().append(head).append(full, s, e).append(if (e < n) ELLIPSIS else "")
        // Dùng lại một khung (gõ phím nào cũng cập nhật, khỏi tạo cửa sổ mới cho đỡ chậm).
        val view = loupeText ?: LoupeText().apply {
            isSingleLine = true
            loupeText = this
        }
        view.apply {
            // Lề phải chừa đủ chỗ cho số đỏ (hai, ba chữ số không đè lên viền hay chữ).
            countdownPaint.textSize = context.sp(10f)
            val countW = if (countdown >= 1) countdownPaint.measureText(countdown.toString()) else 0f
            val pad = context.dpi(10)
            val right = if (countdown >= 1) kotlin.math.ceil(context.dp(COUNT_EDGE_DP + 4f) + countW).toInt() else pad
            setPadding(pad, context.dpi(4), maxOf(pad, right), context.dpi(4))
            typeface = this@ComposingView.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_PX, this@ComposingView.textSize * LOUPE_SCALE)
            setTextColor(this@ComposingView.currentTextColor)
            background = GradientDrawable().apply {
                cornerRadius = context.dp(12f)
                // Kính: nền trong mờ, viền mảnh.
                setColor(loupeColor and 0xFFFFFF or (0xE0 shl 24))
                setStroke(context.dpi(1), loupeEdge and 0xFFFFFF or (0x66 shl 24))
            }
            text = shown
            // Con trỏ thật (không phải chỗ đổi): chỉ vẽ khi nó nằm trong đoạn đang hiện.
            mark = if (caretAt in s..e) head.length + caretAt - s else -1
            count = countdown
            invalidate()
        }
        // Đổi chữ trong khung đang hiện: TextView có thể không tự đo lại (bề rộng cố định theo khung), đo lần trước còn
        // nhớ thì khung giữ cỡ và chữ cũ ("tr" khi đã gõ "trên"). Buộc đo lại.
        view.forceLayout()
        view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val w = view.measuredWidth
        val h = view.measuredHeight
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = loc[0].coerceIn(0, maxOf(0, screenW - w))
        val y = loc[1] - h - context.dpi(6)
        val popup = loupe
        if (popup != null && popup.isShowing) {
            popup.update(x, y, w, h)
            view.requestLayout()
        } else {
            loupe = android.widget.PopupWindow(view, w, h, false).apply {
                isTouchable = false
                isClippingEnabled = false
                elevation = context.dp(4f)
                showAtLocation(this@ComposingView, Gravity.NO_GRAVITY, x, y)
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Bàn phím ẩn: khung kính ẩn theo.
        if (visibility != View.VISIBLE) dismissLoupe()
    }

    /**
     * Vừa chạm ở cột trái: khung kính chuyển tới âm cột đang trỏ tới (nền vàng; chưa có thì chỗ sau các âm đã chốt)
     * (người dùng muốn 2026-11-15).
     */
    fun peekTarget() {
        showChosen = false
        // Chỉ khi âm đó bị "…" ở đầu che.
        val at = lastTarget.first
        if (lastText.isEmpty() || lastWindow.first == 0 || at >= lastWindow.first) return
        showLoupe(lastFull, at, lastCaret)
    }

    /** Ẩn khung kính (gõ phím, xoá ký tự thường). */
    fun hideLoupe() {
        dismissLoupe()
        loupeAt = -1
        showChosen = false
    }

    /** Khung kính đang dành cho chữ Hán vừa chọn (xem [show]). */
    private var showChosen = false

    private fun dismissLoupe() {
        loupe?.dismiss()
        loupe = null
        // Khung đã gỡ khỏi cửa sổ: lần sau tạo lại chữ trong khung.
        loupeText = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tap = onTap ?: return super.onTouchEvent(event)
        if (text.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Kéo ngang trên khung gõ: con trỏ chạy theo ngón tay ngay (không đợi nhấc tay).
                parent?.requestDisallowInterceptTouchEvent(true)
                lastDrag = -1
                lastScroll = 0L
                follow(event.x, tap)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> follow(event.x, tap)
        }
        return true
    }

    /** Vị trí con trỏ lần cuối khi kéo (tránh dời lại cùng chỗ). */
    private var lastDrag = -1
    /** Lúc cuộn khung gõ lần cuối khi ngón tay đứng trên "…" (cuộn từng nhịp, không vụt về đầu). */
    private var lastScroll = 0L

    private fun follow(x: Float, tap: (Int) -> Unit) {
        val l = layout ?: return
        // Vị trí trong chữ đang hiện → vị trí trong cả chữ (chạm vào "…" ở đầu thì về ký tự ngay trước đoạn đang hiện).
        val shown = l.getOffsetForHorizontal(0, x - totalPaddingLeft + scrollX)
        val at = (shown - prefix + windowStart).coerceIn(0, fullText.length)
        val onEllipsis = shown < prefix || (shown >= text.length && text.endsWith(ELLIPSIS) && at < fullText.length)
        if (onEllipsis) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastScroll < SCROLL_MS) return
            lastScroll = now
        } else if (at == lastDrag) {
            return
        }
        lastDrag = at
        tap(at)
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
        /** Khung kính: chữ to hơn khung gõ bao nhiêu lần. */
        const val LOUPE_SCALE = 1.1f
        /** Số đỏ của khung kính cách mép phải (dp). */
        const val COUNT_EDGE_DP = 6f
        /**
         * Xoá lùi: khung kính chỉ hiện khi còn chừng này lần xoá nữa là tới chữ đầu tiên (chữ Hán đã chọn, âm đã chốt,
         * chữ đen gõ thường), đếm 12 … 1; tới chữ đầu tiên thì không có số; xa hơn thì không hiện (người dùng 2026-11-19;
         * trước 5).
         */
        const val LOUPE_AHEAD = 12
        /** Như [LOUPE_AHEAD] cho chữ dài không có dấu cách để tách (tttt…, quá [LOUPE_RUN_CHARS] ký tự): đếm 12 … 1. */
        const val LOUPE_RUN_AHEAD = 12
        /** Chữ dài không có dấu cách để tách (tttt…): khung kính hiện đúng chừng này ký tự thay cho 2 chữ (2026-11-19). */
        const val LOUPE_RUN_CHARS = 13
        const val ELLIPSIS = "…"
        /** Cuộn khung gõ về trước: số ký tự vẫn hiện trước con trỏ. */
        const val SCROLL_CONTEXT = 2
        /** Giữ ngón tay trên "…" khi kéo: mỗi nhịp này cuộn khung gõ một chỗ (ms). */
        const val SCROLL_MS = 150L
        /** Nền dấu cách cuối chuỗi: đỏ hơi trong. */
        const val TRAILING_SPACE = 0xB3E53935.toInt()
        /** Nền âm mà cột trái của bảng gợi ý đang trỏ tới: vàng hơi trong. */
        const val TARGET = 0x99FFD54F.toInt()    }
}
