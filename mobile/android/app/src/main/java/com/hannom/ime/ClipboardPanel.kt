package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import kotlin.math.abs

/** Một mục trong bảng nhớ tạm: chữ, lúc sao chép (ms), đã ghim chưa. */
data class Clip(val text: String, val time: Long, val pinned: Boolean)

/**
 * Bảng nhớ tạm (như Gboard): giữ [MAX_RECENT] mục chưa ghim gần nhất, mục chưa ghim tự xoá sau [RECENT_MS] (1 tiếng)
 * kể từ lúc sao chép; mục đã ghim giữ mãi. Lưu trong `clipboard.txt` của ứng dụng (mỗi dòng: ghim, lúc sao chép, chữ mã
 * Base64), chỉ trên máy.
 * Mục người dùng đã xoá không quay lại dù chữ còn trong bộ nhớ của máy: `clipboard_deleted.txt` giữ mã băm (không giữ
 * chữ) và lúc xoá; quá 1 tiếng thì bỏ (chữ cũ hơn 1 tiếng trong bộ nhớ của máy đã không được thêm nữa). Sao chép lại thật
 * sự thì mới lưu.
 */
class ClipStore(context: Context) {
    private val file = File(context.filesDir, "clipboard.txt")
    private val deletedFile = File(context.filesDir, "clipboard_deleted.txt")

    /** Các mục: đã ghim trước, rồi mới nhất trước. Mục chưa ghim quá 1 tiếng bị bỏ (và xoá khỏi tệp). */
    fun all(): List<Clip> {
        val clips = runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                Clip(String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8), parts[1].toLong(), parts[0] == "1")
            }
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val live = clips.filter { it.pinned || now - it.time < RECENT_MS }
        if (live.size != clips.size) save(live)
        return live.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    }

    /**
     * Thêm chữ trong bộ nhớ của máy (trùng thì đưa lên đầu, giữ cờ ghim); chỉ giữ [MAX_RECENT] mục chưa ghim.
     * [fresh]: người dùng vừa sao chép; không thì chữ chỉ còn nằm đó từ trước, mục đã xoá thì bỏ qua.
     * [copiedAt]: lúc sao chép theo hệ thống (0 nếu không biết); chữ đã quá 1 tiếng thì không thêm.
     */
    fun add(text: String, fresh: Boolean, copiedAt: Long = 0) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val time = if (fresh || copiedAt <= 0 || copiedAt > now) now else copiedAt
        if (now - time >= RECENT_MS) return
        val hash = hashOf(text)
        val deleted = deletedHashes()
        if (hash in deleted) {
            if (!fresh) return
            saveDeleted(deleted - hash)
        }
        val old = all()
        // Chữ trùng mục đang có mà không phải vừa sao chép: giữ nguyên (không làm mới giờ, không đưa lên đầu).
        if (!fresh && old.any { it.text == text }) return
        val pinned = old.any { it.text == text && it.pinned }
        val rest = old.filter { it.text != text }
        val recent = rest.filter { !it.pinned }.take(MAX_RECENT - 1)
        save(listOf(Clip(text, time, pinned)) + rest.filter { it.pinned } + recent)
    }

    /** Sửa chữ của mục [old] thành [text], giữ chỗ và cờ ghim; mục khác trùng chữ mới thì gộp vào mục này. */
    fun edit(old: String, text: String) {
        if (text.isBlank() || text == old) return
        val clips = all()
        val target = clips.firstOrNull { it.text == old } ?: return add(text, fresh = true)
        val merged = target.copy(text = text, pinned = target.pinned || clips.any { it.text == text && it.pinned })
        save(clips.filter { it.text != text }.map { if (it === target) merged else it })
    }

    /** Bỏ ghim thì mục về "Gần đây" và tính lại 1 tiếng từ lúc bỏ ghim (không biến mất ngay vì đã ghim lâu). */
    fun setPinned(targets: List<Clip>, pinned: Boolean) {
        val texts = targets.map { it.text }.toSet()
        val now = System.currentTimeMillis()
        save(all().map {
            when {
                it.text !in texts || it.pinned == pinned -> it
                pinned -> it.copy(pinned = true)
                else -> it.copy(pinned = false, time = now)
            }
        })
    }

    fun remove(targets: List<Clip>) {
        val texts = targets.map { it.text }.toSet()
        save(all().filter { it.text !in texts })
        saveDeleted(deletedHashes() + texts.map(::hashOf))
    }

    /** Đưa lại các mục vừa xoá (hoàn tác); mục trùng chữ đã có thì giữ mục đang có. */
    fun restore(targets: List<Clip>) {
        saveDeleted(deletedHashes() - targets.map { hashOf(it.text) }.toSet())
        val now = all()
        save(now + targets.filter { t -> now.none { it.text == t.text } })
    }

    /** Tắt bảng nhớ tạm: bỏ các mục chưa ghim. */
    fun clearRecent() = save(all().filter { it.pinned })

    private fun save(clips: List<Clip>) {
        runCatching {
            file.writeText(clips.joinToString("") { c ->
                "${if (c.pinned) 1 else 0}\t${c.time}\t${Base64.encodeToString(c.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}\n"
            })
        }
    }

    private fun hashOf(text: String): String = Base64.encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP,
    )

    /** Mã băm các mục đã xoá trong 1 tiếng qua (mỗi dòng: mã băm, lúc xoá; dòng cũ không có giờ thì bỏ). */
    private fun deletedHashes(): List<String> = runCatching {
        val now = System.currentTimeMillis()
        deletedFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].takeIf { it.isNotEmpty() && now - at < RECENT_MS }?.let { it to at }
        }.also { deletedAt = it.toMap() }.map { it.first }
    }.getOrDefault(emptyList())

    /** Lúc xoá của các mã băm vừa đọc (giữ nguyên khi ghi lại). */
    private var deletedAt: Map<String, Long> = emptyMap()

    private fun saveDeleted(hashes: List<String>) {
        val now = System.currentTimeMillis()
        runCatching {
            deletedFile.writeText(hashes.distinct().takeLast(MAX_DELETED).joinToString("") { "$it\t${deletedAt[it] ?: now}\n" })
        }
    }

    companion object {
        const val MAX_RECENT = 20
        /** Mục chưa ghim tự xoá sau 1 tiếng kể từ lúc sao chép (như Gboard). */
        const val RECENT_MS = 60 * 60 * 1000L
        /** Số mã băm mục đã xoá giữ lại. */
        private const val MAX_DELETED = 100
    }
}

/**
 * Bảng nhớ tạm thay chỗ thanh gợi ý và bàn phím (như Gboard). Hàng trên: nút quay lại, tiêu đề, công tắc bật / tắt,
 * nút ✎ chọn nhiều mục. Tắt thì có thẻ giải thích và nút bật; bật thì các thẻ chữ gần đây rồi đã ghim, hai cột
 * xếp so le theo chiều cao chữ. Chạm thẻ để dán, gạt ngang để xoá (có hoàn tác), giữ để hiện menu ghim / chọn /
 * xoá. Chế độ chọn: chạm để chọn nhiều thẻ rồi ghim hay xoá cùng lúc.
 */
@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode", "ClickableViewAccessibility")
class ClipboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onClipPaste(clip: Clip)
        fun onClipsPin(clips: List<Clip>, pinned: Boolean)
        fun onClipsDelete(clips: List<Clip>)
        fun onClipsRestore(clips: List<Clip>)
        fun onClipEnabled(on: Boolean)
        fun onClipAdd()
        fun onClipEdit(clip: Clip)
        fun onClipClose()
    }

    var theme = KeyboardTheme.of(context)
    private val back = headerButton(R.drawable.ic_arrow_back, "Quay lại bàn phím") {
        if (selecting) setSelecting(false) else listener.onClipClose()
    }
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(context.dpi(12), 0, 0, 0)
    }
    private val toggle = Switch(context).apply {
        setOnCheckedChangeListener { _, on -> if (!binding) listener.onClipEnabled(on) }
    }
    private val editButton = headerButton(R.drawable.ic_edit, "Chọn mục") { setSelecting(true) }
    private val pinButton = headerButton(R.drawable.ic_pin, "Ghim") {
        val chosen = selectedClips()
        if (chosen.isNotEmpty()) {
            // Có mục chưa ghim thì ghim cả; tất cả đã ghim thì bỏ ghim.
            listener.onClipsPin(chosen, chosen.any { !it.pinned })
            setSelecting(false)
        }
    }
    private val deleteButton = headerButton(R.drawable.ic_delete, "Xoá") {
        val chosen = selectedClips()
        if (chosen.isNotEmpty()) {
            setSelecting(false)
            delete(chosen)
        }
    }
    private val addButton = headerButton(R.drawable.ic_add, "Thêm mục mới") { listener.onClipAdd() }
    /** Lề ngang nằm ở chính thẻ ([cardMargin]); lề của khối đặt trong [applyEdges]. */
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { addView(content) }
    private val undoText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
    }
    private val undoAction = TextView(context).apply {
        text = "Hoàn tác"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(context.dpi(16), 0, context.dpi(8), 0)
        setOnClickListener {
            val clips = undoClips
            hideUndo()
            if (clips.isNotEmpty()) listener.onClipsRestore(clips)
        }
    }
    private val undoBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpi(20), 0, context.dpi(12), 0)
        addView(undoText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(undoAction, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }
    private var undoClips: List<Clip> = emptyList()
    private val hideUndoLater = Runnable { hideUndo() }
    private var binding = false
    private var on = false
    private var clips: List<Clip> = emptyList()
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var menu: PopupWindow? = null
    /** Bàn phím đang dùng: tên mục "Gần đây" / "Đã ghim" theo ngôn ngữ của nó. */
    var style = KeyboardStyle.VIETNAMESE

    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        setPadding(context.dpi(4), 0, context.dpi(8), 0)
        addView(back, LayoutParams(context.dpi(48), context.dpi(48)))
        addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = context.dpi(4)
        })
        for (b in listOf(editButton, deleteButton, pinButton, addButton)) addView(b, LayoutParams(context.dpi(48), context.dpi(48)))
    }
    private val landscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** Lề thêm hai bên (px) ở màn hình ngang, xem [cutoutSides]. */
    private var extra = 0 to 0
    /** Nửa khe giữa hai cột thẻ (px). */
    private val cardMargin get() = context.dp(if (landscape) CARD_MARGIN_LANDSCAPE else CARD_MARGIN).toInt()

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(56)))
        addView(FrameLayout(context).apply {
            addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(undoBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(48), Gravity.BOTTOM).apply {
                setMargins(context.dpi(12), 0, context.dpi(12), context.dpi(10))
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Màn hình ngang: cửa sổ Gboard phủ cả tai thỏ và lùi nội dung vào bằng tai thỏ ở cả hai bên. Cửa sổ của ta né tai
     * thỏ thì bên đó đã lùi sẵn, chỉ thêm ở bên kia. Trả về (thêm trái, thêm phải) theo px.
     */
    private fun cutoutSides(): Pair<Int, Int> {
        if (!landscape || android.os.Build.VERSION.SDK_INT < 29 || !isAttachedToWindow) return 0 to 0
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION") val display = wm.defaultDisplay
        val cut = display.cutout ?: return 0 to 0
        val size = maxOf(cut.safeInsetLeft, cut.safeInsetRight)
        if (size == 0) return 0 to 0
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val real = android.graphics.Point()
        @Suppress("DEPRECATION") display.getRealSize(real)
        return if (cut.safeInsetLeft > 0) maxOf(0, size - loc[0]) to size
        else size to maxOf(0, size - (real.x - loc[0] - width))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val sides = cutoutSides()
        if (sides != extra) {
            extra = sides
            post { applyEdges() }
        }
    }

    /**
     * Lề đầu bảng và lưới (đo trên Gboard). Dọc: thẻ cách mép ~6.5dp. Ngang: thẻ cách mép 10dp (cộng [extra]), đầu bảng
     * cao 38dp, nút tròn đầu bảng thẳng mép với lưới.
     */
    private fun applyEdges() {
        val edge = context.dp(if (landscape) GRID_EDGE_LANDSCAPE else GRID_EDGE).toInt()
        val (extraL, extraR) = if (landscape) extra else 0 to 0
        content.setPadding(edge - cardMargin + extraL, 0, edge - cardMargin + extraR, context.dpi(64))
        // Nút tròn đầu bảng thẳng mép với lưới (nền tròn lùi vào 7dp so với nút); tiêu đề cách nút tròn 14dp.
        val inset = context.dpi(7)
        header.setPadding(maxOf(0, edge - inset) + extraL, 0, maxOf(0, edge - inset) + extraR, 0)
        title.setPadding(context.dpi(14) - inset, 0, 0, 0)
        val h = context.dpi(if (landscape) HEADER_LANDSCAPE else HEADER)
        if (header.layoutParams.height != h) header.layoutParams = header.layoutParams.apply { height = h }
    }

    /** Dựng lại theo trạng thái bật / tắt [on] và các mục [clips]. */
    fun show(on: Boolean, clips: List<Clip>) {
        this.on = on
        this.clips = clips
        selected.retainAll(clips.map { it.text }.toSet())
        if (!on) selecting = false
        if (visibility != View.VISIBLE || !on) hideUndo()
        render()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            menu?.dismiss()
            selecting = false
            selected.clear()
        }
    }

    private fun render() {
        if (!landscape) extra = 0 to 0
        applyEdges()
        setBackgroundColor(theme.background)
        title.setTextColor(theme.text)
        // Như Gboard: nền tròn 34dp cho quay lại, bút chì và dấu +; thùng rác và ghim không có nền.
        for (b in listOf(back, editButton, addButton)) {
            b.setColorFilter(theme.text)
            b.background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.key)
            }, context.dpi(7))
        }
        for (b in listOf(pinButton, deleteButton)) b.setColorFilter(theme.text)
        undoBar.background = GradientDrawable().apply {
            cornerRadius = context.dp(12f)
            setColor(theme.text)
        }
        undoText.setTextColor(theme.background)
        undoAction.setTextColor(theme.accent)

        binding = true
        toggle.isChecked = on
        binding = false
        back.setImageResource(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back)
        back.contentDescription = if (selecting) "Thôi chọn" else "Quay lại bàn phím"
        // Chỉ chữ ngoài BMP (𢖵, 𫾗) dùng phông Hán Nôm; dấu cách và chữ số giữ Roboto (dấu cách MiSans rộng).
        title.text = context.hanNomText(when {
            !selecting -> style.say("Bảng nhớ tạm", "榜𢖵暫", "Clipboard")
            selected.isEmpty() -> style.say("Chọn mục", "𫾗目", "Select items")
            // Hán Nôm: 㐌 (đã, như 㐌針) + 𫾗 (chọn, như 𫾗目), số thường sau một dấu cách.
            else -> style.say("Đã chọn ${selected.size}", "㐌𫾗 ${selected.size}", "${selected.size} selected")
        })
        toggle.visibility = if (selecting) View.GONE else View.VISIBLE
        editButton.visibility = if (!selecting && on) View.VISIBLE else View.GONE
        for (b in listOf(pinButton, deleteButton)) {
            b.visibility = if (selecting) View.VISIBLE else View.GONE
            b.alpha = if (selected.isEmpty()) 0.4f else 1f
        }
        addButton.visibility = if (selecting) View.VISIBLE else View.GONE

        content.removeAllViews()
        if (!on) {
            content.addView(offCard())
            return
        }
        val recent = clips.filter { !it.pinned }
        val pinned = clips.filter { it.pinned }
        // Như Gboard: trống hẳn thì hiện hình bảng trống; phần nào không có mục thì không hiện tên phần đó.
        if (recent.isEmpty() && pinned.isEmpty()) {
            content.addView(emptyState())
            return
        }
        if (recent.isNotEmpty()) section(style.say("Gần đây", "近低", "Recent"), recent)
        if (pinned.isNotEmpty()) section(style.say("Đã ghim", "㐌針", "Pinned"), pinned)
    }

    private fun section(name: String, clips: List<Clip>) {
        content.addView(TextView(context).apply {
            text = context.hanNomText(name)
            setTextColor(theme.hintText)
            // Đo trên Gboard: chữ nhỏ hơn chữ thẻ, nằm sát thẻ phía dưới (chân chữ cách thẻ ~7dp, đầu chữ cách thẻ
            // phía trên ~12dp); dòng cao cố định (hai phần cách nhau ~29dp), không đổi theo phông.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.BOTTOM
            setPadding(context.dpi(8), 0, 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(if (landscape) SECTION_LANDSCAPE else SECTION)))
        if (clips.isEmpty()) return
        // Các cột so le (như Gboard; dọc 2 cột, ngang 3 cột): mỗi thẻ vào cột đang thấp nhất, chiều cao ước theo số dòng chữ.
        // Không cắt ở mép cột: thẻ đang gạt ngang vẽ được đè lên cột bên cạnh.
        val columns = List(if (landscape) 3 else 2) { LinearLayout(context).apply { orientation = VERTICAL; clipChildren = false } }
        val heights = IntArray(columns.size)
        for (clip in clips) {
            val col = heights.indices.minByOrNull { heights[it] } ?: 0
            heights[col] += estimatedLines(clip.text) + 1
            columns[col].addView(card(clip), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = cardMargin
                setMargins(m, context.dpi(4), m, context.dpi(4))
            })
        }
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            for (c in columns) addView(c, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun estimatedLines(text: String): Int =
        text.lines().take(MAX_LINES).sumOf { (it.length / 18) + 1 }.coerceIn(1, MAX_LINES)

    private fun card(clip: Clip): View {
        val chosen = clip.text in selected
        val text = TextView(context).apply {
            text = context.hanNomText(clip.text)
            maxLines = MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(theme.text)
            // Đo trên Gboard: mỗi dòng 21dp, ô từ 41dp (1 dòng) đến 104dp (MAX_LINES dòng).
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (android.os.Build.VERSION.SDK_INT >= 28) lineHeight = context.dpi(21)
            val pad = context.dpi(12)
            val padY = context.dpi(10)
            setPadding(pad, padY, if (selecting) context.dpi(36) else pad, padY)
            minHeight = context.dpi(41)
        }
        val card = FrameLayout(context).apply {
            addView(text, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (selecting) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(if (chosen) theme.onAccent else Color.TRANSPARENT)
                val p = context.dpi(3)
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (chosen) setColor(theme.accent) else setStroke(context.dpi(2), theme.hintText)
                }
            }, FrameLayout.LayoutParams(context.dpi(22), context.dpi(22), Gravity.TOP or Gravity.END).apply {
                setMargins(0, context.dpi(10), context.dpi(10), 0)
            })
            contentDescription = clip.text
        }
        paintCard(card, pressed = false, chosen = chosen)
        card.setOnTouchListener(CardTouch(clip))
        return card
    }

    private fun paintCard(card: View, pressed: Boolean, chosen: Boolean) {
        card.background = GradientDrawable().apply {
            cornerRadius = context.dp(16f)
            setColor(if (pressed) theme.pressedKey else theme.key)
            if (chosen) setStroke(context.dpi(2), theme.accent)
        }
    }

    /** Chạm: dán (chế độ chọn thì chọn / bỏ chọn); gạt ngang quá ~1/3 thẻ hay vuốt nhanh: xoá; giữ: menu. */
    private inner class CardTouch(private val clip: Clip) : OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val flingSpeed = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private var moved = false
        private var longPressed = false
        private var tracker: VelocityTracker? = null
        private var view: View? = null
        private val longPress = Runnable {
            val v = view ?: return@Runnable
            longPressed = true
            paintCard(v, pressed = false, chosen = clip.text in selected)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (selecting) toggleSelected(clip) else showMenu(v, clip)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view = v
                    downX = e.rawX
                    downY = e.rawY
                    swiping = false
                    moved = false
                    longPressed = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    paintCard(v, pressed = true, chosen = clip.text in selected)
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!swiping && !moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        v.removeCallbacks(longPress)
                        paintCard(v, pressed = false, chosen = clip.text in selected)
                        if (!selecting && !longPressed && abs(dx) > abs(dy)) {
                            swiping = true
                            v.parent.requestDisallowInterceptTouchEvent(true)
                            // Cột của thẻ đang gạt nổi lên trên cột bên cạnh (cột không có nền nên không đổ bóng).
                            (v.parent as? View)?.translationZ = context.dp(1f)
                        }
                    }
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = 1f - (abs(dx) / v.width).coerceAtMost(1f) * 0.7f
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    paintCard(v, pressed = false, chosen = clip.text in selected)
                    // Thả tracker trước khi dán / render: render gỡ thẻ ra khỏi cây view và
                    // bắn ACTION_CANCEL lồng vào đây, recycle hai lần sẽ crash.
                    val t = tracker
                    tracker = null
                    t?.addMovement(e)
                    t?.computeCurrentVelocity(1000)
                    val vx = t?.xVelocity ?: 0f
                    t?.recycle()
                    val dx = e.rawX - downX
                    when {
                        swiping -> {
                            val fling = abs(vx) > flingSpeed && vx * dx > 0
                            if (abs(dx) > v.width / 3f || fling) swipeAway(v, dx) else settle(v)
                        }
                        !moved && !longPressed -> if (selecting) toggleSelected(clip) else listener.onClipPaste(clip)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    paintCard(v, pressed = false, chosen = clip.text in selected)
                    if (swiping) settle(v)
                    tracker?.recycle()
                    tracker = null
                }
            }
            return true
        }

        private fun settle(v: View) = v.animate().translationX(0f).alpha(1f).setDuration(150)
            .withEndAction { (v.parent as? View)?.translationZ = 0f }.start()

        private fun swipeAway(v: View, dx: Float) {
            val to = if (dx < 0) -v.width.toFloat() else v.width.toFloat()
            v.animate().translationX(to).alpha(0f).setDuration(150).withEndAction { delete(listOf(clip)) }.start()
        }
    }

    private fun toggleSelected(clip: Clip) {
        if (!selected.remove(clip.text)) selected.add(clip.text)
        render()
    }

    private fun setSelecting(on: Boolean) {
        menu?.dismiss()
        selecting = on
        selected.clear()
        render()
    }

    private fun selectedClips() = clips.filter { it.text in selected }

    /** Xoá rồi hiện thanh hoàn tác vài giây. */
    private fun delete(targets: List<Clip>) {
        listener.onClipsDelete(targets)
        undoClips = targets
        val n = targets.size
        undoText.text = context.hanNomText(style.say("Đã xoá $n mục", "㐌𫨮 $n 目", if (n == 1) "1 item deleted" else "$n items deleted"))
        undoAction.text = context.hanNomText(style.say("Hoàn tác", "還作", "Undo"))
        undoBar.visibility = View.VISIBLE
        removeCallbacks(hideUndoLater)
        postDelayed(hideUndoLater, UNDO_MS)
    }

    private fun hideUndo() {
        removeCallbacks(hideUndoLater)
        undoClips = emptyList()
        undoBar.visibility = View.GONE
    }

    /**
     * Giữ thẻ (đo trên Gboard): cả bảng tối đi, bản sao của thẻ hiện ở trên cùng bảng, menu bên cạnh (Dán, Ghim / Bỏ ghim,
     * Chỉnh sửa, Xoá). Thẻ ở nửa trái thì thẻ trái, menu phải; thẻ ở nửa phải thì lật lại. Chạm ra ngoài để đóng.
     */
    private fun showMenu(anchor: View, clip: Clip) {
        menu?.dismiss()
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val panelLoc = IntArray(2)
        getLocationInWindow(panelLoc)
        val itemLeft = loc[0] - panelLoc[0]
        val itemRight = itemLeft + anchor.width
        // Mục ở cột cuối thì lật: menu bên trái, thẻ bên phải.
        val column = anchor.parent as? View
        val row = column?.parent as? ViewGroup
        val right = row != null && row.indexOfChild(column) == row.childCount - 1

        val overlay = FrameLayout(context).apply {
            setBackgroundColor((theme.background and 0x00FFFFFF) or (SCRIM_ALPHA shl 24))
        }
        val popup = PopupWindow(overlay, width, height, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
        }
        // Chạm vào nền tối chỉ đóng menu, không dán thẻ nằm bên dưới.
        overlay.setOnClickListener { popup.dismiss() }

        // Thẻ chép thẳng mép với mục đang giữ; menu bên cạnh, không vượt quá giới hạn phải (khối dịch sang trái nếu
        // cần). Đo trên Gboard: dọc thẻ rộng hết phần còn lại, ngang thẻ 268dp, menu 148dp.
        val menuW = context.dpi(if (landscape) MENU_WIDTH_LANDSCAPE else MENU_WIDTH)
        val gap = context.dpi(MENU_GAP)
        val top = context.dpi(if (landscape) MENU_TOP_LANDSCAPE else MENU_TOP)
        val gridRight = width - content.paddingRight - cardMargin
        val menuLimit = if (landscape) gridRight - context.dpi(MENU_EDGE) else width - context.dpi(MENU_EDGE)
        val cardW = if (landscape) context.dpi(CARD_WIDTH_LANDSCAPE) else menuLimit - menuW - gap - context.dpi(CARD_EDGE)
        val cardLeft: Int
        val menuLeft: Int
        if (right) {
            cardLeft = itemRight - cardW
            menuLeft = cardLeft - gap - menuW
        } else {
            cardLeft = minOf(itemLeft, menuLimit - menuW - gap - cardW)
            menuLeft = cardLeft + cardW + gap
        }

        val copy = card(clip).apply {
            setOnTouchListener(null)
            setOnClickListener { popup.dismiss() }
            (this as FrameLayout).getChildAt(0).setPadding(context.dpi(COPY_PAD_X), context.dpi(COPY_PAD_Y), context.dpi(COPY_PAD_X), context.dpi(COPY_PAD_Y))
        }
        overlay.addView(copy, FrameLayout.LayoutParams(cardW, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = top
            leftMargin = cardLeft
        })

        // Các dòng cách nhau 1dp (lộ nền tối), cả khối bo tròn bốn góc.
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, context.dp(16f))
            }
        }
        overlay.addView(list, FrameLayout.LayoutParams(menuW, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = top
            leftMargin = menuLeft
        })
        fun row(icon: Int, label: String, action: () -> Unit) = list.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.key)
            setPadding(context.dpi(14), 0, context.dpi(8), 0)
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(theme.text)
            }, LayoutParams(context.dpi(24), context.dpi(24)))
            addView(TextView(context).apply {
                // Tên Hán Nôm có chữ ngoài BMP (𬖭, 𢯢, 𫨮): dùng phông Hán Nôm đi kèm.
                text = context.hanNomText(label)
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                setPadding(context.dpi(12), 0, 0, 0)
            })
            setOnClickListener {
                popup.dismiss()
                action()
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(MENU_ROW)).apply {
            if (list.childCount > 0) topMargin = context.dpi(1)
        })
        // Tên theo bàn phím.
        fun say(vi: String, nom: String, en: String) = style.say(vi, nom, en)
        row(R.drawable.ic_clipboard, say("Dán", "𬖭", "Paste")) { listener.onClipPaste(clip) }
        row(R.drawable.ic_pin, if (clip.pinned) say("Bỏ ghim", "𠬃針", "Unpin") else say("Ghim", "針", "Pin")) {
            listener.onClipsPin(listOf(clip), !clip.pinned)
        }
        // Màn hình ngang Gboard bỏ Chỉnh sửa (bảng thấp, chỉ vừa ba dòng).
        if (!landscape) row(R.drawable.ic_edit, say("Chỉnh sửa", "整𢯢", "Edit")) { listener.onClipEdit(clip) }
        row(R.drawable.ic_delete, say("Xoá", "𫨮", "Delete")) { delete(listOf(clip)) }
        popup.setOnDismissListener { if (menu === popup) menu = null }
        menu = popup
        // Phủ đúng lên bảng: thả xuống từ đáy bảng rồi kéo lên bằng cả chiều cao.
        popup.showAsDropDown(this, 0, -height)
    }

    private fun headerButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        setOnClickListener { onClick() }
    }

    /**
     * Bảng nhớ tạm đang bật nhưng chưa có mục nào (đo trên Gboard): hình bảng kẹp giấy trống cách đỉnh bảng 80dp,
     * dưới là tiêu đề và dòng chú thích, căn giữa; chữ theo bàn phím đang dùng.
     */
    private fun emptyState() = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val headerH = context.dpi(if (landscape) HEADER_LANDSCAPE else HEADER)
        setPadding(0, maxOf(0, context.dpi(EMPTY_TOP) - headerH), 0, 0)
        addView(EmptyClipboardIcon(context, theme), LayoutParams(context.dpi(56), context.dpi(73)))
        addView(TextView(context).apply {
            text = context.hanNomText(style.say("Bảng nhớ tạm của bạn trống", "榜𢖵𧵑伴𥧪", "Your clipboard is empty"))
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(14) })
        addView(TextView(context).apply {
            text = context.hanNomText(style.say(
                "Khi bạn sao chép một đoạn văn bản, thì đoạn văn bản đó sẽ hiển thị ở đây.",
                "欺伴抄劄𠬠段文本，時段文本𪦆𠱊顯示於低。",
                "Once you copy a piece of text, it will show up here.",
            ))
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(4) })
    }

    /** Hình bảng kẹp giấy trống của Gboard (56 × 73dp): khung xám, lòng sáng, kẹp trên đỉnh, ○ ◇ × bên trong. */
    private class EmptyClipboardIcon(context: Context, private val theme: KeyboardTheme) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val u = width / 56f
            fun mix(a: Int, b: Int, t: Float) = android.graphics.Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
            )
            val frame = mix(theme.background, theme.hintText, 0.12f)
            val clip = mix(theme.background, theme.hintText, 0.3f)
            val inner = mix(theme.background, theme.key, 0.8f)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = frame
            r.set(0f, 7.5f * u, 56f * u, 73f * u)
            canvas.drawRoundRect(r, 3f * u, 3f * u, paint)
            paint.color = inner
            r.set(6f * u, 13.5f * u, 50f * u, 67f * u)
            canvas.drawRect(r, paint)
            paint.color = clip
            r.set(20f * u, 3f * u, 36f * u, 14.5f * u)
            canvas.drawRoundRect(r, 2f * u, 2f * u, paint)
            canvas.drawCircle(28f * u, 3f * u, 3f * u, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.5f * u
            paint.color = frame
            canvas.drawCircle(16f * u, 41f * u, 4.5f * u, paint)
            canvas.save()
            canvas.rotate(20f, 34f * u, 29f * u)
            r.set(29.5f * u, 24.5f * u, 38.5f * u, 33.5f * u)
            canvas.drawRect(r, paint)
            canvas.restore()
            val x = 34f * u
            val y = 54f * u
            val d = 4f * u
            canvas.drawLine(x - d, y - d, x + d, y + d, paint)
            canvas.drawLine(x - d, y + d, x + d, y - d, paint)
        }
    }

    private fun offCard() = LinearLayout(context).apply {
        orientation = VERTICAL
        val pad = context.dpi(18)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(24f)
            setColor(theme.key)
        }
        addView(TextView(context).apply {
            text = context.hanNomText(style.say(
                "Sao chép nhiều mục, dán nhanh cùng một lúc",
                "抄劄𡗉目，𬖭伶共𠬠𣅶",
                "Copy multiple items, paste them quickly all at once",
            ))
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        addView(TextView(context).apply {
            text = context.hanNomText(style.say(
                "Bảng nhớ tạm của Nôm Na giúp bạn sao chép văn bản để dán nhanh các mục này cùng một lúc " +
                    "(thời gian lưu giữ trong khay nhớ là 1 tiếng)",
                "榜𢖵暫𧵑喃哪𠢞伴抄劄文本抵𬖭伶各目迡共𠬠𣅶（時間留貯𥪝𥳐𢖵𪜀 1 㗂）",
                "Nôm Na's clipboard lets you copy text and quickly paste these items all at once " +
                    "(items stay in the clipboard for 1 hour)",
            ))
            setTextColor(theme.hintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, context.dpi(8), 0, context.dpi(14))
        })
        addView(TextView(context).apply {
            text = context.hanNomText(style.say("Bật bảng nhớ tạm", "弼榜𢖵暫", "Turn on clipboard"))
            setTextColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val p = context.dpi(12)
            setPadding(context.dpi(20), p, context.dpi(20), p)
            background = GradientDrawable().apply {
                cornerRadius = context.dp(24f)
                setColor(theme.accent and 0x00FFFFFF or 0x30000000)
            }
            setOnClickListener { listener.onClipEnabled(true) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
    }

    companion object {
        const val MAX_LINES = 4
        const val UNDO_MS = 4000L
        // Menu giữ thẻ, đo trên Gboard (dp).
        /** Độ đục lớp nền tối (màu nền bàn phím, ~95%). */
        const val SCRIM_ALPHA = 0xF2
        const val MENU_TOP = 32
        const val MENU_TOP_LANDSCAPE = 27
        const val MENU_WIDTH = 133
        const val MENU_WIDTH_LANDSCAPE = 148
        const val CARD_WIDTH_LANDSCAPE = 268
        const val MENU_ROW = 54
        /** Menu cách mép bảng. */
        const val MENU_EDGE = 10
        /** Khe giữa thẻ và menu. */
        const val MENU_GAP = 12
        /** Thẻ chép trong menu cách mép bảng. */
        const val CARD_EDGE = 6
        /** Thẻ trong lưới cách mép bảng, và nửa khe giữa hai cột (dp). */
        const val GRID_EDGE = 6.5f
        const val CARD_MARGIN = 4.5f
        /** Màn hình ngang (Gboard): thẻ cách mép 10dp, hai cột cách nhau 7dp. */
        const val GRID_EDGE_LANDSCAPE = 10f
        const val CARD_MARGIN_LANDSCAPE = 3.5f
        /** Chiều cao hàng đầu bảng (dp). */
        const val HEADER = 44
        const val HEADER_LANDSCAPE = 38
        /** Dòng tên mục ("Gần đây"…) (dp). */
        const val SECTION = 21
        /** Hình bảng trống cách đỉnh bảng (dp). */
        const val EMPTY_TOP = 80
        const val SECTION_LANDSCAPE = 19
        /** Thẻ chép trong menu có lề chữ rộng hơn thẻ trong lưới (dp). */
        const val COPY_PAD_X = 16
        const val COPY_PAD_Y = 15
    }
}
