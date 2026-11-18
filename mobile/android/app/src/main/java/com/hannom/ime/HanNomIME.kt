package com.hannom.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Bàn phím Hán Nôm.
 *
 * Chữ đang gõ được giữ trong [keys] (chuỗi phím Telex). Quốc ngữ ghi thẳng vào ô nhập, không gạch chân
 * (xem [typed]); Hán Nôm hiện trong khung gõ riêng. Ở chế độ Hán Nôm, dấu thanh chưa đặt lên chữ mà hiện ký hiệu ở cuối (`tots` → `totˊ`); chữ cuối
 * chưa gõ thanh thì phím cách hiện ¯: ấn là gán thanh ngang (lưu thành ' ' trong [keys]), chưa chọn chữ;
 * âm tiết đã có thanh thì phím cách chọn gợi ý đầu.
 * - Quốc ngữ: Telex tự do như Unikey, dấu đặt thẳng lên chữ; phím cách là sang từ mới. Gợi ý từ trên thanh
 *   (từ điển `vi`), không có bảng gợi ý.
 * - Hán Nôm: gợi ý chữ Hán/Nôm, chọn từng phần như Sogou; gõ không dấu hoặc thêm dấu ở cuối
 *   (`tots`, `totqs`), dấu đã gõ dùng để lọc.
 * - Tiếng Anh: gõ thẳng; gợi ý từ theo từ trước con trỏ (từ điển `en`, nếu có).
 * - Viết tay (bàn phím Hán Nôm kiểu viết tay): mỗi lần nhấc bút nhận dạng lại cả chữ, gợi ý hiện trên
 *   thanh gợi ý; chạm gợi ý, phím cách, dấu câu hoặc viết sang ô kia thì chốt chữ. Chọn gợi ý không
 *   phải đầu tiên thì bộ nhận dạng nhớ nét đó là chữ đã chọn.
 */
class HanNomIME : InputMethodService(), KeyboardView.Listener, HandwritingView.Listener, EmojiPanel.Listener,
    ToolsPanel.Listener, ClipboardPanel.Listener, TextEditPanel.Listener {
    private lateinit var engine: HanNomEngine
    private lateinit var settings: Settings
    private var root: InputRoot? = null
    private var recognizer: Handwriting? = null

    private val keys = StringBuilder()
    /**
     * Hán Nôm: các chữ đã chọn cho phần đầu chuỗi phím (như pinyin: `hstt` chọn 函 rồi 數…), còn nằm trong
     * khung gõ trước [keys], chưa vào ô nhập.
     */
    private val chosen = ArrayList<Chosen>()
    private val chosenText get() = chosen.withIndex().joinToString("") { (i, it) ->
        if (i > 0 && it.candidate.literal && chosen[i - 1].candidate.literal) " ${it.candidate.text}" else it.candidate.text
    }

    /**
     * Ghép chữ đã chọn dở với [next] ngay sau nó: chữ Latin (giữ nguyên Quốc ngữ, [latin]) liền nhau thì cách một
     * khoảng, cạnh chữ Hán thì viết liền (`搭telex伶㰷`, `telex nhanh hơn`).
     */
    private fun afterChosen(next: String, latin: Boolean): String =
        if (latin && next.isNotEmpty() && chosen.lastOrNull()?.candidate?.literal == true) " $next" else next

    /**
     * Một chữ đã chọn dở: đoạn phím nó đã thay ([keys]) để xoá thì trả lại, và phím còn lại ngay sau khi chọn
     * ([rest]): gõ thêm rồi ấn xoá thì xoá phím vừa gõ trước, về đúng [rest] mới gỡ chữ này.
     */
    private class Chosen(val candidate: Candidate, val keys: String, val rest: String)

    /** Một hành động của người dùng trong khung gõ Hán Nôm, để phím xoá hoàn tác theo thứ tự ngược lại ([acts]). */
    private enum class Act { KEY, LOCK, CHOOSE }

    /**
     * Các hành động đã làm (gõ phím, chốt âm, chọn chữ), cũ trước: phím xoá hoàn tác hành động sau cùng: bỏ chốt âm vừa
     * chốt, gỡ chữ vừa chọn (các âm đã chốt trở lại như lúc chọn), xoá phím vừa gõ (người dùng muốn 2026-11-15).
     */
    private val acts = ArrayList<Act>()
    private var mode = Mode.QUOC_NGU
    private var layout = Layout.QWERTY
    /** Ô mật khẩu: luôn gõ tiếng Anh, không học. */
    private var secure = false
    private var candidates: List<Candidate> = emptyList()
        set(v) {
            field = v
            // Gán từ chỗ khác (gợi ý từ, viết tay, xoá): không còn là gợi ý Hán Nôm của trạng thái nào.
            candidatesFor = null
        }
    /** Dòng viết tay vừa nhận dạng, để dạy lại đúng chữ khi người dùng chọn gợi ý khác gợi ý đầu. */
    private var written: HandwrittenLine? = null
    /** Số nét [written] đã nhận dạng (khác số nét trên bảng thì kết quả chưa theo kịp). */
    private var writtenStrokes = 0
    /** Gợi ý đầu của chữ viết tay đang nằm trong ô nhập dưới dạng chữ đang soạn. */
    private var writtenShown = false

    private val effectiveMode get() = if (secure) Mode.ENGLISH else mode
    private val handwriting get() = !secure && layout == Layout.HANDWRITING
    /** Quốc ngữ: chữ đang gõ ghi thẳng vào ô nhập, không qua chữ đang soạn (gạch chân). */
    private val direct get() = effectiveMode == Mode.QUOC_NGU
    /** Quốc ngữ: đúng đoạn chữ đang gõ đã ghi trước con trỏ; gõ thêm phím thì thay đoạn này. */
    private var typed = ""
    // Lấy lại mỗi lần: sửa từ điển trong cài đặt thì bản nạp lại thay bản cũ ([WordDict.reload]).
    private val viWords get() = WordDict.get(this, WordDict.VI)
    private val enWords get() = WordDict.get(this, WordDict.EN)
    /** Bàn phím Hán Nôm kiểu Telex: [keys] là phím Telex gõ thẳng (như Unikey), không chuẩn hoá kiểu Hán Nôm riêng. */
    private val telex get() = effectiveMode == Mode.HAN_NOM && layout == Layout.TELEX

    private companion object {
        /** Số chữ đầu trên thanh gợi ý được tính trước lúc chọn ([prefetchPicks]). */
        const val PREFETCH_PICKS = 4
        /** Số chữ nhớ dạng hiển thị ([shownWord]); đầy thì xoá hết. */
        const val SHOWN_WORDS = 4096
        /** Ô tìm emoji: một dòng chữ, phím Enter là nút tìm (như Gboard). */
        val SEARCH_INFO = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        /**
         * Chuỗi phím đang gõ dài tối đa (chống treo). Gboard 拼音 không giới hạn (đo trên máy người dùng 2026-11-15:
         * 1000 phím vẫn ghép cả câu); lõi ghép câu Telex tới 1000 phím (`MAX_SENTENCE_KEYS_TELEX`, cửa sổ ghép câu).
         */
        const val MAX_KEYS = 1000
        /** Viết tay nhiều chữ: số gợi ý "cả bảng là một chữ" đưa lên ngay sau gợi ý đầu. */
        const val WHOLE_FIRST = 3
        /** Đọc chừng này ký tự trước con trỏ để lấy từ đang gõ và các từ trước nó. */
        const val MAX_WORD = 64
        /** Cỡ chữ ô nhập toàn màn hình: đo trên Gboard (Realme, 2026-11-10), chữ `đ` cao 50px = 0.676 lần bản 28sp. */
        const val EXTRACT_TEXT_SP = 19f
        /** Mở bảng: chờ lưới dựng xong tối đa chừng này (ms) rồi mới đổi bàn phím sang bảng ([onExpand]). */
        const val GRID_READY_MS = 300L
        /** Chuỗi phím Hán Nôm dài tới chừng này thì tra gợi ý ngay (vài ms); dài hơn thì tra ở luồng nền. */
        const val SYNC_KEYS = 24
        /** Số lần chọn tối đa khi chốt cả khung gõ bằng chữ Hán Nôm ([commitHanNom]). */
        const val MAX_COMMIT_PICKS = 64
        /** Số trạng thái giữ gợi ý đã tra ([lookupCache]). */
        const val LOOKUP_CACHE = 400
        /** Dừng tay chừng này (ms) mới tính trước cho bảng thả ([prefetch]). */
        const val PREFETCH_IDLE_MS = 300L
        /** Dấu thanh tiếng Việt (dạng tách NFD): huyền, sắc, ngã, hỏi, nặng. */
        val TONE_MARKS = Regex("[̣̀́̃̉]")
    }

    private val hanNomPunctuation = mapOf(
        "," to "，", "." to "。", "。" to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；", "(" to "（", ")" to "）", "[" to "［", "]" to "］",
    )

    override fun onCreate() {
        // Phải đặt trước super.onCreate(), sau đó InputMethodService đã tạo cửa sổ theo theme.
        setTheme(R.style.ImeTheme)
        super.onCreate()
        coverCutout()
        engine = HanNomEngine.get(this)
        settings = Settings(this)
        clips = ClipStore(this)
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        WordDict.preload(this)
    }

    override fun onDestroy() {
        // Hệ thống chuyển sang bộ gõ khác bằng đường khác (thanh thông báo, cài đặt).
        ClipAddActivity.keyboardLeft()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        lookupThread.shutdownNow()
        super.onDestroy()
    }

    /**
     * Cửa sổ bàn phím phủ cả vùng camera / tai thỏ như Gboard (xoay ngang: ô nhập toàn màn hình và nền bàn phím kín
     * tới mép máy, không để hở ứng dụng phía sau); các phím vẫn lùi vào tránh camera ([KeyboardView.insetLeft], [KeyboardView.insetRight]).
     * Máy không có camera trên màn hình thì không đổi gì.
     */
    private fun coverCutout() {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val w = window?.window ?: return
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** TẠM THỜI ([watchFrames]). */
    private var framesWatched = false

    override fun onCreateInputView(): View =
        InputRoot(this, this, this, ::pick, ::onExpand, this, this, this, this).also {
            root = it
            if (!framesWatched) {
                framesWatched = true
                watchFrames(window?.window)
            }
            // Hệ thống có thể đã chọn chế độ toàn màn hình trước khi tạo view (xoay ngang).
            it.fullscreen = isFullscreenMode
            it.grid.narrow = { picked -> if (keys.isEmpty()) null else engine.narrow(keys.toString(), picked, chosenText) }
            it.grid.narrowed = { picked ->
                val k = keys.toString()
                withPointedLatin(engine.candidatesNarrowed(k, picked, chosenText), k, picked)
            }
            // Bảng gọi lõi ở luồng nền: chụp chuỗi phím và chữ đã chọn dở ngay lúc dựng (không đọc [keys] từ luồng nền).
            it.grid.core = {
                val k = keys.toString()
                val b = chosenText
                CandidateGrid.Core(
                    narrow = { picked -> if (k.isEmpty()) null else engine.narrow(k, picked, b) },
                    narrowed = { picked -> withPointedLatin(engine.candidatesNarrowed(k, picked, b), k, picked) },
                )
            }
            it.grid.onCoreNeeded = {
                prefetchGen++
                // Bảng cần lõi ngay: việc tính trước chưa bắt đầu thì thôi, đợi lần dừng tay sau ([prefetch]).
                mainHandler.removeCallbacks(prefetchTask)
                prefetchJobs.clear()
            }
            it.grid.syllableCount = { if (keys.isEmpty()) 0 else engine.syllableChunks(keys.toString()).size }
            // Chốt xong âm cuối: không chèn sẵn dấu tách; người dùng tự ấn cách rồi gõ âm tiếp (người dùng muốn
            // 2026-11-15, thay cho chèn sẵn của 2026-11-14 làm lần cách đầu đã chọn chữ).
            it.grid.onLocked = {
                acts.add(Act.LOCK)
                if (it.grid.lockedAll) refresh()
                prefetchLock(it)
            }
            it.grid.chunksOf = { k -> if (k.isEmpty()) emptyList() else engine.syllableChunks(k) }
            it.grid.typedChunk = { i ->
                val k = keys.toString()
                val raw = engine.syllableChunks(k).getOrNull(i)?.let { k.substring(it).replace("'", "") }.orEmpty()
                shownChunk(raw)
            }
            // Âm đầu theo các đoạn âm của lõi, không theo dấu cách của khung gõ: gõ tắt `tt` (t + t) không có dấu cách nên
            // cột trái từng có mục chọn nhanh "tt" (người dùng báo 2026-11-18).
            it.grid.typedSyllable = typed@{
                if (keys.isEmpty()) return@typed null
                val k = keys.toString()
                val first = engine.syllableChunks(k).firstOrNull() ?: return@typed null
                shownChunk(k.substring(first).replace("'", "")).lowercase().ifEmpty { null }
            }
            // Gần như chữ nào cũng có chữ khác dấu để thu hẹp tới: luôn mở được bảng (khỏi tính cột trái mỗi phím).
            it.bar.canNarrow = { !handwriting }
            // Gợi ý từ tiếp theo (chưa gõ gì) viết hoa theo Shift / Caps Lock: bấm Shift thì đổi ngay.
            it.keyboard.onShiftChange = { if (direct && !handwriting && keys.isEmpty()) refresh() }
            it.onComposeTap = ::onComposeTap
            it.grid.onDelete = ::onDelete
            it.grid.onNarrowing = narrowing@{ texts, latin ->
                // Bảng đã thu lên (chốt bằng phím cách ngoài bàn phím): thanh do [showLookup] lo.
                if (!it.bar.expanded) return@narrowing
                var shown = if (texts == null) candidates else candidates.filter { c -> c.text !in texts }
                // Chữ Latin theo lựa chọn ở cột trái thay chữ Latin cũ ở ô 2 (gõ `thooi`, chọn t: `t` thay `thôi`).
                if (latin != null) {
                    val rest = shown.filter { c -> !(c.literal && !c.panelFirst) && c.text != latin.text }
                    shown = rest.take(1) + latin + rest.drop(1)
                }
                it.bar.showCandidates(shown, null, narrowing = texts != null)
            }
            it.grid.onBar = singles@{ singles ->
                if (!it.bar.expanded) return@singles singles
                it.bar.showCandidates(singles, null, narrowing = true)
                it.bar.hidden(singles)
            }
            it.grid.onEnter = ::onEnter
            // Dấu câu ở cột trái: chốt chữ đang chọn rồi chèn dấu (đã là dấu toàn khổ, không đổi nữa), thu bảng lên.
            it.grid.onPunctuation = { p ->
                onChar(p, literal = true)
                if (it.bar.expanded && keys.isEmpty()) it.bar.expanded = false
            }
            it.grid.onPicking = { root?.let(::showComposing) }
            it.grid.onLocks = { labels ->
                lockLabels = labels
                root?.let(::showComposing)
            }
            it.onEmojiSearchClosing = ::dropTyping
            it.onEmojiSearchClosed = {
                appConsumesInput(false)
                updateKeyboardLabels()
                updateShift()
                refresh()
            }
        }

    /**
     * Đang tìm emoji: phím gõ vào ô tìm thay cho ô nhập của ứng dụng (mọi chỗ trong bộ gõ đều lấy ô nhập qua hàm
     * này). Chèn emoji vào ứng dụng thì dùng [appConnection].
     */
    override fun getCurrentInputConnection(): InputConnection? =
        root?.emojiSearch?.takeIf { it.active }?.connection ?: super.getCurrentInputConnection()

    /** Ô nhập của ứng dụng, cả khi đang tìm emoji. */
    private val appConnection: InputConnection? get() = super.getCurrentInputConnection()

    /**
     * Báo ô nhập của ứng dụng rằng bàn phím đang tự dùng phím gõ (đang tìm emoji): ô nhập ẩn con trỏ, như Gboard
     * (Android 12+; ô nhập tự vẽ có thể bỏ qua).
     */
    private fun appConsumesInput(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 31) appConnection?.setImeConsumesInput(on)
    }

    /** Bỏ chữ đang gõ dở (không đưa vào ô nhập). */
    private fun dropTyping() {
        clearHandwriting()
        keys.clear()
        typed = ""
        chosen.clear()
        acts.clear()
        candidates = emptyList()
    }

    /** Dải khung gõ trên cùng không che ứng dụng và không nhận chạm. */
    // Chế độ toàn màn hình (xoay ngang trên điện thoại: ô nhập riêng của hệ thống phía trên + nút Xong) để hệ thống
    // quyết định như Gboard (đo trên máy: Messenger xoay ngang hiện "Nhắn tin" + XONG). Ứng dụng đặt
    // IME_FLAG_NO_EXTRACT_UI / NO_FULLSCREEN thì không dùng.

    override fun onUpdateExtractingViews(ei: EditorInfo) {
        super.onUpdateExtractingViews(ei)
        applyExtractHint()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        blinkExtractCursor()
    }

    /**
     * Con trỏ của ô nhập toàn màn hình nháy ngay khi bàn phím hiện. Ô này không bao giờ nhận tiêu điểm nên Android chỉ
     * bắt đầu nháy khi chữ / vùng chọn đổi; tắt bật lại con trỏ là cách công khai để bắt đầu lại nhịp nháy.
     */
    private fun blinkExtractCursor() {
        if (!isFullscreenMode) return
        val edit = window?.window?.decorView?.findViewById<android.widget.EditText>(android.R.id.inputExtractEditText) ?: return
        edit.post {
            edit.isCursorVisible = false
            edit.isCursorVisible = true
        }
    }

    /**
     * Chữ gợi ý của ô nhập toàn màn hình: đúng chữ gợi ý của ô đang gõ trong ứng dụng (vd. "Gõ thử ở đây"), như người
     * dùng muốn 2026-11-18; ô không có chữ gợi ý thì "Gõ ở đây" theo ngôn ngữ bàn phím đang dùng.
     */
    private fun extractHint(): CharSequence {
        val app = currentInputEditorInfo?.hintText?.takeIf { it.isNotBlank() }
        return hanNomText(app ?: effectiveMode.say("Gõ ở đây", "𢱗於低", "Typing here"))
    }

    /**
     * Ô nhập toàn màn hình (xoay ngang) tự dựng như Gboard, không dùng kiểu của hãng máy (Realme: ô bo tròn, nút
     * xanh). Đo trên Gboard (Realme, 3.5x): nền #FAF8FF; ô chữ cỡ 21sp canh trên (lề trên 10dp, chữ cách mép 8dp), gạch
     * chân 2dp #0054D6 lùi 4dp hai bên, cách đáy 6dp; nút 80 × 36dp nền #D6D7D7, chữ in hoa 14sp #181B25, có bóng,
     * cách ô chữ 15dp, cách mép phải 12dp, giữa theo chiều dọc. Hệ thống tìm các view theo id chuẩn.
     */
    override fun onCreateExtractTextView(): View {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1F1F23.toInt() else 0xFFFAF8FF.toInt()
        val text = if (night) 0xFFE3E3E8.toInt() else 0xFF1B1B1F.toInt()
        val hint = if (night) 0xFF8E8E96.toInt() else 0xFFA4A4AC.toInt()
        val line = if (night) 0xFF8AB4F8.toInt() else 0xFF0054D6.toInt()
        val button = if (night) 0xFF3C3D40.toInt() else 0xFFD6D7D7.toInt()
        val buttonText = if (night) 0xFFE3E3E8.toInt() else 0xFF181B25.toInt()
        // Các nền giữ lại để so: máy Realme thay nền / lề của các view này bằng kiểu của hãng sau khi bàn phím dựng.
        val areaBg = android.graphics.drawable.ColorDrawable(bg)
        val rootBg = android.graphics.drawable.ColorDrawable(bg)
        // Gạch chân 2dp, lùi 4dp hai bên và cách đáy 6dp (như nền ô nhập Material).
        val editBg = android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(line))).apply {
                setLayerGravity(0, android.view.Gravity.BOTTOM or android.view.Gravity.FILL_HORIZONTAL)
                setLayerHeight(0, dpi(2))
            },
            dpi(4), 0, dpi(4), dpi(6),
        )
        val buttonBg = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33000000),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(button)
                cornerRadius = dp(2f)
            },
            null,
        )
        val edit = android.inputmethodservice.ExtractEditText(this).apply {
            id = android.R.id.inputExtractEditText
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, EXTRACT_TEXT_SP)
            setTextColor(text)
            setHintTextColor(hint)
            typeface = robotoTypeface()
            background = editBg
            setPadding(dpi(8), dpi(10), dpi(8), dpi(10))
            if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(line)
                setSize(dpi(2), 0)
            }
        }
        val action = android.widget.Button(this).apply {
            id = android.R.id.inputExtractAction
            setTextColor(buttonText)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = true
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(robotoTypeface(), 500, false) else robotoTypeface()
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dpi(8), 0, dpi(8), 0)
            stateListAnimator = null
            elevation = dp(2f)
            background = buttonBg
        }
        val accessories = android.widget.FrameLayout(this).apply {
            id = android.R.id.inputExtractAccessories
            addView(action, android.widget.FrameLayout.LayoutParams(dpi(80), dpi(36), android.view.Gravity.CENTER))
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = rootBg
            clipToPadding = false
            addView(edit, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(accessories, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = dpi(15)
                marginEnd = dpi(12)
            })
        }
        // Trước mỗi lần vẽ: hệ thống đặt lại chữ gợi ý của ứng dụng, máy Realme đổi nền (ô trắng bo tròn, nút xanh), lề
        // khung ngoài và cỡ nút. Thấy khác thì đặt lại như Gboard rồi bỏ lần vẽ này (vẽ lại ngay với kiểu đúng).
        root.viewTreeObserver.addOnPreDrawListener {
            var changed = false
            fun fix(ok: Boolean, apply: () -> Unit) {
                if (!ok) {
                    apply()
                    changed = true
                }
            }
            (root.parent as? android.view.ViewGroup)?.let { area ->
                fix(area.background === areaBg) { area.background = areaBg }
                fix(area.paddingLeft == 0 && area.paddingTop == 0 && area.paddingRight == 0 && area.paddingBottom == 0) {
                    area.setPadding(0, 0, 0, 0)
                }
            }
            fix(root.background === rootBg) { root.background = rootBg }
            fix(root.paddingLeft == 0 && root.paddingTop == 0 && root.paddingRight == 0 && root.paddingBottom == 0) {
                root.setPadding(0, 0, 0, 0)
            }
            fix(edit.background === editBg) { edit.background = editBg }
            fix(edit.paddingLeft == dpi(8) && edit.paddingTop == dpi(10)) { edit.setPadding(dpi(8), dpi(10), dpi(8), dpi(10)) }
            fix(edit.currentHintTextColor == hint) { edit.setHintTextColor(hint) }
            // Hệ thống đặt lại cỡ chữ của ứng dụng / hãng máy sau khi dựng ô.
            val size = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, EXTRACT_TEXT_SP, resources.displayMetrics)
            fix(kotlin.math.abs(edit.textSize - size) < 0.5f) { edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, EXTRACT_TEXT_SP) }
            val want = extractHint()
            fix(edit.hint?.toString() == want.toString()) { edit.hint = want }
            fix(action.background === buttonBg) { action.background = buttonBg }
            fix(action.currentTextColor == buttonText) { action.setTextColor(buttonText) }
            val lp = action.layoutParams
            fix(lp != null && lp.width == dpi(80) && lp.height == dpi(36)) {
                action.layoutParams = android.widget.FrameLayout.LayoutParams(dpi(80), dpi(36), android.view.Gravity.CENTER)
            }
            !changed
        }
        return root
    }

    /** Ô nhập toàn màn hình (xoay ngang): chữ gợi ý và tên nút "Xong" theo ngôn ngữ bàn phím đang dùng. */
    private fun applyExtractHint() {
        val decor = window?.window?.decorView ?: return
        decor.findViewById<android.widget.EditText>(android.R.id.inputExtractEditText)?.hint = extractHint()
        // Nút "Done" của hệ thống (ứng dụng không đặt tên riêng): theo ngôn ngữ bàn phím.
        val action = decor.findViewById<android.widget.TextView>(android.R.id.inputExtractAction) ?: return
        val done = getTextForImeAction(EditorInfo.IME_ACTION_DONE)?.toString()
        // Chữ của hệ thống theo ngôn ngữ máy (máy tiếng Việt là "Xong"): bàn phím tiếng Anh ghi thẳng "Done".
        val label = effectiveMode.say("Xong", "歱", "Done")
        if (action.text.toString() in setOf(done, "Xong", "歱", "Done")) action.text = hanNomText(label)
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        root?.fullscreen = isFullscreenMode
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        // Toàn màn hình: cửa sổ bàn phím phủ cả màn hình, ô nhập phía trên cũng phải nhận chạm: giữ như hệ thống.
        if (isFullscreenMode) return
        val r = root ?: return
        if (!r.isShown) return
        val loc = IntArray(2).also { r.getLocationInWindow(it) }
        val top = loc[1] + r.panelTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        // Đang chỉnh kích thước: khung kéo tràn được lên trên bàn phím, nhận chạm cả view.
        outInsets.touchableRegion.set(0, if (r.resizeShown) loc[1] else top, r.width, loc[1] + r.height)
        // Khung gõ nằm trên dải trong suốt: nhận chạm riêng ô khung gõ (dời con trỏ), phần còn lại vẫn của ứng dụng.
        r.composeBox()?.let { outInsets.touchableRegion.op(it, android.graphics.Region.Op.UNION) }
        // Bảng giữ emoji tràn lên trên bàn phím (như Gboard): phần đó cũng nhận chạm.
        r.tonesBox()?.let { outInsets.touchableRegion.op(it, android.graphics.Region.Op.UNION) }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Vừa đóng hộp thoại "Thêm mục mới": hệ thống có thể đã ẩn bàn phím, mở lại để về bảng nhớ tạm.
        if (ClipAddActivity.closedRecently() && android.os.Build.VERSION.SDK_INT >= 28) requestShowSelf(0)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        root?.fullscreen = isFullscreenMode
        blinkExtractCursor()
        engine.apply(settings)
        settings.keyboard.let {
            mode = it.mode
            layout = it.layout
        }
        secure = isPassword(info)
        keys.clear()
        typed = ""
        chosen.clear()
        acts.clear()
        candidates = emptyList()
        written = null
        writtenShown = false
        recognizer?.cancel()
        root?.let { r ->
            r.applyTheme(KeyboardTheme.of(this))
            // Lựa chọn ưu tiên: hàng số (ô mật khẩu có thể vẫn hiện), phông và cỡ chữ trên phím.
            r.keyboard.numberRow = settings.numberRow || (secure && settings.numberRowPassword)
            r.keyboard.labelScale = settings.labelScale(this)
            r.keyboard.refreshFont()
            val config = resources.configuration
            val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // Ngang: mặc định hàng chữ theo tỉ lệ chiều cao màn hình (đo trên Gboard), không cao hơn khi dọc.
            r.setLandscape(landscape, kotlin.math.round(config.screenHeightDp * InputRoot.LANDSCAPE_ROW).toInt().coerceAtMost(settings.rowHeight))
            r.applySavedRowHeight()
            r.pad.clear()
            // Ô nhập số (cả khi ứng dụng đổi kiểu ô đang mở, như nút 123 của Google Sheets): bàn phím số.
            if (isNumeric(info)) r.keyboard.showNumpad() else r.keyboard.resetToLetters()
            r.keyboard.cancelTouch()
            r.showGrid(false)
            r.showEmoji(false)
            r.showEmojiSearch(false)
            r.showResize(false)
            r.toolLayout = settings.tools
            r.showTools(false)
            r.showTextEdit(false)
            r.showClipboard(false, false, emptyList())
            r.updateBottomGap()
        }
        updateKeyboardLabels(info)
        updateShift()
        refresh()
        // Vừa đóng hộp thoại "Thêm mục mới": trở lại bảng nhớ tạm.
        if (ClipAddActivity.justClosed()) openClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        root?.keyboard?.cancelTouch()
        // Chữ viết tay đang soạn trong ô nhập: chốt gợi ý đầu.
        commitHandwriting()
        clearHandwriting()
        commitComposing()
        // Đang tìm emoji thì chữ trên vừa vào ô tìm; đóng ô tìm để lần sau phím về ô nhập của ứng dụng.
        root?.showEmojiSearch(false)
        engine.saveLearned()
        WordDict.saveAll()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        root?.let {
            if (it.textEditShown) {
                it.textEdit.hasSelection = newSelStart != newSelEnd
                // Sửa chữ (xoá, cắt, dán) cũng báo đổi vùng chọn: ô nhập vừa trống / vừa có chữ thì bật tắt các nút.
                it.textEdit.hasText = newSelStart != newSelEnd || fieldHasText()
            }
        }
        // Khung gõ Hán Nôm đang hiện mà người dùng chạm / giữ trong ô nhập làm con trỏ dời chỗ (vùng chọn khác lúc mở khung
        // gõ): dừng gõ. Như Gboard gõ pinyin (đo trên máy người dùng, Tin nhắn, 2026-11-15): ô trống thì chạm / giữ / vuốt
        // không dời con trỏ, chữ đang gõ giữ nguyên; ô đã có chữ, chạm làm con trỏ dời thì bỏ hết chữ đang gõ, cả chữ đã
        // chọn dở.
        val anchor = composeAnchor
        if (appSelectionUiDismissed && keys.isNotEmpty() && effectiveMode == Mode.HAN_NOM && anchor != null &&
            (newSelStart != anchor.first || newSelEnd != anchor.second)
        ) {
            stopComposing()
            return
        }
        // Quốc ngữ gõ thẳng vào ô nhập: con trỏ nhảy ra khỏi chữ đang gõ (chạm chỗ khác) thì giữ nguyên chữ,
        // gõ tiếp là từ mới. Hán Nôm gõ trong khung gõ riêng nên không bị ảnh hưởng.
        if (keys.isNotEmpty() && direct && (newSelStart != newSelEnd || !typedIntact())) {
            keys.clear()
            typed = ""
            refresh()
        } else if (keys.isEmpty() && (effectiveMode == Mode.ENGLISH || direct) && !handwriting) {
            // Gõ thẳng (cả phím xoá là phím thật): gợi ý theo chữ trước con trỏ sau mỗi lần đổi, ngay sau dấu
            // cách là từ tiếp theo.
            refresh()
        }
    }

    /** Ghi [text] thay cho đoạn chữ đang gõ [typed] trước con trỏ (chỉ sửa phần khác nhau). */
    private fun showTyped(text: String) {
        if (text == typed) return
        val ic = currentInputConnection
        if (ic != null) {
            val same = typed.commonPrefixWith(text).length
            ic.beginBatchEdit()
            if (typed.length > same) ic.deleteSurroundingText(typed.length - same, 0)
            if (text.length > same) ic.commitText(text.substring(same), 1)
            ic.endBatchEdit()
        }
        typed = text
    }

    /** Đoạn [typed] vẫn nằm ngay trước con trỏ (chưa chạm chỗ khác, ứng dụng chưa sửa chữ). */
    private fun typedIntact(): Boolean {
        if (typed.isEmpty()) return true
        val ic = currentInputConnection ?: return false
        return ic.getTextBeforeCursor(typed.length, 0)?.toString() == typed
    }

    /** Các từ ngay trước [before] (tối đa 2, gần nhất ở cuối), để gợi ý từ nhiều chữ nối tiếp; gặp dấu câu thì dừng. */
    private fun contextWords(before: String): List<String> {
        if (before.isEmpty() || !before.last().isWhitespace()) return emptyList()
        return before.trimEnd().split(Regex("\\s+")).takeLast(2).takeLastWhile { w -> w.isNotEmpty() && w.all { it.isLetter() } }
    }

    /** Từ tiếng Anh ngay trước con trỏ (chữ cái và `'`). */
    private fun englishWordBefore(): String {
        val before = currentInputConnection?.getTextBeforeCursor(MAX_WORD, 0)?.toString().orEmpty()
        return before.takeLastWhile { it.isLetter() || it == '\'' }.trimStart('\'')
    }

    /** Các từ ngay trước đoạn [skip] ký tự cuối trước con trỏ (xem [contextWords]). */
    private fun wordsBefore(skip: Int): List<String> {
        val before = currentInputConnection?.getTextBeforeCursor(skip + MAX_WORD, 0)?.toString().orEmpty()
        return contextWords(before.dropLast(skip))
    }

    /**
     * Gợi ý từ: Quốc ngữ cho chữ đang gõ, tiếng Anh cho từ trước con trỏ; chưa gõ gì (ngay sau dấu cách) thì
     * gợi ý từ tiếp theo.
     */
    private fun wordCandidates(): List<Candidate> {
        val ic = currentInputConnection ?: return emptyList()
        if (direct) {
            if (viWords.isEmpty) return emptyList()
            // Viết hoa theo bàn phím, không theo từ điển (Việt Nam, Hà Nội): Caps Lock thì cả từ, Shift (hay đã gõ
            // chữ đầu hoa) thì chữ cái đầu, không thì chữ thường hết.
            val shift = root?.keyboard?.shift
            val caps = when {
                shift == KeyboardView.Shift.LOCK -> WordDict.Caps.ALL
                if (typed.isEmpty()) shift == KeyboardView.Shift.ONCE else typed.first().isUpperCase() -> WordDict.Caps.FIRST
                else -> WordDict.Caps.NONE
            }
            if (typed.isEmpty()) {
                if (secure || !ic.getSelectedText(0).isNullOrEmpty()) return emptyList()
                return viWords.predict(wordsBefore(0), caps = caps).map { Candidate(it, "", 0) }
            }
            return viWords.suggest(typed, wordsBefore(typed.length), caps = caps).map { Candidate(it, "", 0) }
        }
        if (secure || enWords.isEmpty || !ic.getSelectedText(0).isNullOrEmpty()) return emptyList()
        val word = englishWordBefore()
        if (word.isEmpty()) return enWords.predict(wordsBefore(0)).map { Candidate(it, "", 0) }
        return enWords.suggest(word, wordsBefore(word.length), keepTyped = true).map { Candidate(it, "", 0) }
    }

    /** Chọn gợi ý từ: thay từ đang gõ bằng từ đó, thêm dấu cách. */
    private fun pickWord(c: Candidate) {
        val ic = currentInputConnection ?: return
        if (direct) {
            val prev = wordsBefore(typed.length).lastOrNull()
            showTyped(c.text + " ")
            viWords.learn(c.text, prev)
        } else {
            val word = englishWordBefore()
            val prev = wordsBefore(word.length).lastOrNull()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(c.text + " ", 1)
            ic.endBatchEdit()
            enWords.learn(c.text, prev)
        }
        keys.clear()
        typed = ""
        refresh()
        updateShift()
    }

    private fun isPassword(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isNumeric(info: EditorInfo): Boolean = when (info.inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    private fun updateKeyboardLabels(info: EditorInfo? = if (root?.emojiSearch?.active == true) SEARCH_INFO else currentInputEditorInfo) {
        ClipAddActivity.languageChanged(mode)
        applyExtractHint()
        val kb = root?.keyboard ?: return
        val m = effectiveMode
        kb.handwriting = handwriting
        if (handwriting && recognizer == null) recognizer = Handwriting.get(this)
        kb.modeLabel = m.label
        kb.spaceLabel = m.spaceLabel
        root?.emoji?.setBackLabel(m == Mode.HAN_NOM)
        kb.vietnamese = m != Mode.ENGLISH
        kb.quocNgu = m == Mode.QUOC_NGU
        root?.pad?.style = kb.style
        kb.oneHand = settings.oneHand
        engine.telex = telex
        // Cùng điều kiện với onEnter: ô nhiều dòng thì Enter xuống dòng.
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        kb.enterIcon = if (multiLine) R.drawable.ic_return else when (info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_go
            else -> R.drawable.ic_return
        }
        // Đổi bàn phím khi đang tìm emoji: tiêu đề và chữ mờ của ô tìm đổi theo.
        root?.emojiSearch?.takeIf { it.active }?.style = kb.style
        kb.invalidate()
    }

    // ---- Phần chữ đang gõ ----

    /** Chữ có dấu của chuỗi phím đang gõ, dùng khi chốt chữ. */
    private fun composed(): String {
        // Hán Nôm: dấu tách `'` chốt ra thành dấu cách.
        val separator = if (effectiveMode == Mode.HAN_NOM) " " else "'"
        return keys.split('\'').joinToString(separator) { composeWord(it) }
    }

    private fun composeWord(w: String): String =
        if (telex) engine.displayTelex(w, settings.toneStyle) else engine.compose(w, settings.toneStyle)

    /**
     * Gợi ý Hán Nôm cho chuỗi phím [k] sau các chữ đã chọn dở [before]; [telex]: bàn phím kiểu Telex. Chỉ đọc các
     * tham số (không đọc trạng thái bộ gõ) để chạy được ở luồng nền.
     */
    private fun lookup(k: String, before: String, telex: Boolean): List<Candidate> {
        val key = lookupKey(k, before, telex)
        synchronized(lookupCache) { lookupCache[key] }?.let { return it }
        val own = engine.candidates(k, quocNgu = false)
        val list = if (before.isEmpty()) own else {
            // Đã chọn dở (函 của `hstt`): cụm bắt đầu bằng chữ đã chọn lên trước (數 của 函數).
            val follow = engine.after(before, k)
            follow + own.filterNot { c -> follow.any { it.text == c.text && it.consumed == c.consumed } }
        }
        // Xếp theo độ phổ biến, không ưu tiên cụm dài; chỉ câu tự ghép giữ ô 1 (người dùng muốn 2026-11-19; trước đó cụm
        // dài nhất lên đầu). Đang chọn tiếp sau chữ đã chọn dở thì các cụm nối tiếp chữ đó vẫn đứng trước.
        val ordered = if (before.isEmpty() && k.isNotEmpty()) byFrequency(list) else list
        // Chữ Latin thêm sau khi xếp, để luôn ở vị trí thứ 2 ([withQuocNgu]).
        val out = if (telex) withQuocNgu(ordered, k) else ordered
        synchronized(lookupCache) { lookupCache[key] = out }
        return out
    }

    /**
     * [list] xếp theo độ phổ biến (cao trước, bằng nhau giữ thứ tự của lõi), kể cả cụm phủ hết phần đã gõ (中 dùng nhiều
     * hơn thì đứng trước 中國); chỉ câu tự ghép (lõi đặt ở đầu khi không cụm nào phủ hết) giữ ô 1. Người dùng muốn
     * 2026-11-19.
     */
    private fun byFrequency(list: List<Candidate>): List<Candidate> = when {
        list.size < 2 -> list
        list[0].joined -> listOf(list[0]) + list.drop(1).sortedByDescending { it.freq }
        else -> list.sortedByDescending { it.freq }
    }

    private fun lookupKey(k: String, before: String, telex: Boolean) = "${if (telex) 'T' else 'H'}$before\u0000$k"

    /**
     * Gợi ý đã tra trong lần gõ này, theo (kiểu gõ, chữ đã chọn dở, chuỗi phím): xoá phím thì chuỗi phím trở lại đúng
     * các trạng thái vừa gõ qua, lấy lại ngay khỏi tra lõi (cụm test kinh điển 0 → 30: xoá mỗi phím 23 ms, chậm nhất
     * 120 ms trên máy tính). Bỏ hết khi khung gõ đóng hay chọn xong (học chữ đổi thứ tự gợi ý); giữ tối đa
     * [LOOKUP_CACHE] trạng thái gần nhất.
     */
    private val lookupCache = object : LinkedHashMap<String, List<Candidate>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Candidate>>?) = size > LOOKUP_CACHE
    }

    /** Gợi ý đã tra sẵn cho trạng thái hiện tại ([lookupCache]), hay `null`. */
    private fun cachedLookup(): List<Candidate>? =
        synchronized(lookupCache) { lookupCache[lookupKey(keys.toString(), chosenText, telex)] }

    private fun clearLookupCache() = synchronized(lookupCache) { lookupCache.clear() }

    /**
     * Bàn phím Hán Nôm kiểu Telex: chữ Quốc ngữ của từng đoạn phím giữa hai lần cách ([k] tách ở `'`), đúng như đã
     * ghép dấu khi gõ (`ẽ` rồi gõ tiếp `changing` vẫn là ẽchanging).
     */
    private fun telexWords(k: String): List<String> = k.split('\'').map(::shownWord)

    /**
     * Chữ Telex [seg] (không có dấu tách) như khung gõ hiện, nhớ lại: khung gõ dựng lại sau mỗi phím đổi cả chuỗi, mà
     * gõ thêm ở cuối thì các chữ trước không đổi (đo trên điện thoại, ~1000 phím: gọi lõi cho từng chữ mất ~100 ms mỗi
     * phím trên luồng giao diện).
     */
    private fun shownWord(seg: String): String {
        val key = "${settings.toneStyle}\u0000$seg"
        shownWords[key]?.let { return it }
        if (shownWords.size >= SHOWN_WORDS) shownWords.clear()
        return engine.displayTelex(seg, settings.toneStyle).replace(" ", "").also { shownWords[key] = it }
    }

    /** Gọi cả từ luồng tra gợi ý nền. */
    private val shownWords = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ---- Tra gợi ý Hán Nôm ở luồng nền ----
    //
    // Chuỗi phím dài (tới MAX_KEYS) tra mất cả trăm ms trên điện thoại: tra ngay thì gõ nhanh bị khựng. Chuỗi ngắn
    // vẫn tra ngay; dài hơn SYNC_KEYS thì khung gõ cập nhật ngay, gợi ý tra ở luồng nền, chỉ lấy kết quả của lần gõ
    // cuối (các lần tra cũ chưa chạy thì bỏ). Phím cách, dấu câu, mở bảng gợi ý cần gợi ý đúng thì tra ngay
    // ([freshCandidates]); chạm gợi ý cũ trong lúc đang tra lại thì bỏ qua.

    /** Trạng thái Hán Nôm mà [candidates] ứng với (xem [hanNomState]); `null`: không phải gợi ý Hán Nôm. */
    private var candidatesFor: String? = null
    /** Các chữ đã chọn dở và chuỗi phím đang gõ. */
    private val hanNomState get() = chosenText + "\u0000" + keys
    /** Lần tra mới nhất; lần tra ở luồng nền có số khác thì kết quả đã cũ. */
    @Volatile private var lookupGen = 0
    private val lookupThread by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Gợi ý Hán Nôm khớp trạng thái hiện tại: [candidates] đang cũ thì tra lại ngay (bỏ lần tra ở luồng nền). */
    private fun freshCandidates(): List<Candidate> {
        if (effectiveMode == Mode.HAN_NOM && !handwriting && keys.isNotEmpty() && candidatesFor != hanNomState) {
            lookupGen++
            candidates = lookup(keys.toString(), chosenText, telex)
            candidatesFor = hanNomState
            root?.let(::showLookup)
        }
        return candidates
    }

    private fun requestLookup() {
        val gen = ++lookupGen
        val k = keys.toString()
        val before = chosenText
        val state = hanNomState
        val t = telex
        // Đang chốt âm ở cột trái (bảng đã thu): các chữ khớp các âm đã chốt cho thanh gợi ý cũng tra ở luồng nền, cùng
        // lần tra này (mỗi phím mất cả trăm ms trên điện thoại, tra ngay thì gõ bị khựng).
        val r = root
        val query = if (r != null && !r.bar.expanded) {
            r.grid.syncKeys(k)
            r.grid.selectionQuery()
        } else null
        val singles = r?.grid?.singleOn == true
        // Bắt đầu tra sau khi giao diện dựng xong lần này (bảng gợi ý gọi lõi ngay trên luồng giao diện, lõi chỉ một
        // việc một lúc): tra trước thì giao diện phải chờ (đo trên điện thoại, chọn 空 khi đã chốt 17 âm: chờ ~45 ms).
        mainHandler.post {
            if (gen != lookupGen) return@post
            lookupThread.execute {
                if (gen != lookupGen) return@execute
                val list = runCatching { lookup(k, before, t) }.getOrNull() ?: return@execute
                val chosen = query?.let { q -> runCatching { engine.candidatesNarrowed(k, q, before) }.getOrNull() }
                    ?.let { if (singles) r?.grid?.singlesOf(it) ?: it else it }
                mainHandler.post {
                    if (gen != lookupGen || state != hanNomState || effectiveMode != Mode.HAN_NOM || handwriting) return@post
                    candidates = list
                    candidatesFor = state
                    selectionCache = if (query != null && chosen != null) Triple(state, query, chosen) else null
                    root?.let(::showLookup)
                }
            }
        }
    }

    /** Các chữ khớp các âm đã chốt ([CandidateGrid.selection]) đã tra ở luồng nền: (trạng thái, lựa chọn, kết quả). */
    private var selectionCache: Triple<String, List<Pair<Int, String>>, List<Candidate>>? = null

    /** Khung gõ đang hiện thêm dấu cách cuối chuỗi chưa gõ (xem [showLookup]). */
    private var trailingSpace = false

    /** Nhãn các âm đã chốt ở cột trái ([CandidateGrid.onLocks]): khung gõ hiện chúng thay cho phím của các âm đó. */
    private var lockLabels: List<String> = emptyList()

    /** Khung gõ và thanh gợi ý Hán Nôm theo [candidates] (khung gõ tách chữ theo gợi ý đầu). */
    private fun showLookup(r: InputRoot) = perf("showLookup", keys.toString()) { showLookupTimed(r) }

    private fun showLookupTimed(r: InputRoot) {
        // Bảng đã thu lên mà đang chọn dở ở cột trái: vẫn giữ (phím đổi thì bảng tự bỏ), khung gõ vẫn hiện các âm đã
        // chốt, thanh gợi ý hiện các chữ khớp phần đang chọn (người dùng muốn 2026-11-14).
        val open = r.bar.expanded
        // Cả khi bảng mở: các âm đã chốt phải theo chuỗi phím mới trước khi dựng khung gõ. Bảng dựng lại ở luồng nền
        // (CandidateGrid.render), chờ nó thì khung gõ hiện vài khung hình các âm chốt cũ cho phím mới: chọn 中 hay xoá
        // thì chữ dài ra, "…" chớp (người dùng báo 2026-11-16).
        r.grid.syncKeys(keys.toString())
        // Chỉ khi bảng thả mở mới là chế độ chọn âm (nền vàng, phím cách chốt âm; người dùng muốn 2026-11-15); thu bảng
        // lên thì các âm đã chốt vẫn hiện xanh và chữ chọn ra vẫn khớp chúng.
        lockLabels = if (open || r.grid.selecting) r.grid.lockLabels else emptyList()
        showComposing(r)
        r.keyboard.spaceLabel = effectiveMode.spaceLabel
        r.keyboard.invalidate()
        val bc = perf("  barCandidates") { barCandidates(r) }
        // Bảng mở mà lưới đang giữ các chữ khớp phần chọn dở ([CandidateGrid.narrowing]): thanh do lưới lo
        // ([CandidateGrid.onNarrowing], gọi lại khi dựng xong). Hiện đủ gợi ý ở đây rồi lưới bỏ bớt thì thanh chớp mỗi
        // lần chốt / bỏ chốt âm (người dùng báo 2026-11-16).
        // Bảng mở: thanh do lưới vẽ ([CandidateGrid.onNarrowing], một lần khi dựng xong), không vẽ ở đây; vẽ cả hai thì thanh
        // nháy giữa đủ gợi ý và gợi ý đã bỏ các chữ của lưới mỗi lần mở / đóng bảng, chốt / bỏ chốt âm (người dùng báo
        // 2026-11-16). Lưới lấy các gợi ý không lên thanh theo cách xếp thanh cho đủ gợi ý ([CandidateBar.hiddenFor]).
        if (!open) perf("  bar.show") { r.bar.showCandidates(bc, null, narrowing = r.grid.selecting) }
        if (open) perf("  grid.show") { r.grid.update(candidates, r.bar.hiddenFor(candidates), keys.toString()) }
        if (open && r.grid.hasLocks) prefetchPicks(r, bc)
        if (open) prefetchLock(r)
    }

    /**
     * Đã chốt âm, bảng đang mở: tính trước ở luồng nền phần lõi của cột trái ([HanNomEngine.narrow]) cho lúc chọn từng
     * chữ đầu trên thanh gợi ý. Lõi nhớ kết quả, nên chạm chọn chữ khỏi chờ trên luồng giao diện (đo trên điện thoại với
     * cụm test kinh điển: chọn 空 khựng ~100 ms, phần lớn là bước này).
     */
    private fun prefetchPicks(r: InputRoot, bar: List<Candidate>) {
        val k = keys.toString()
        val before = chosenText
        val gen = lookupGen
        val jobs = bar.asSequence().filter { !it.literal && it.consumed in 1 until k.length }.take(PREFETCH_PICKS).mapNotNull { c ->
            val rest = k.drop(c.consumed).trimStart('\'', ' ')
            if (rest.isEmpty()) null else Triple(rest, r.grid.queryAfter(rest), before + c.text)
        }.toList()
        prefetch(gen, jobs)
    }

    /** Lần dựng bảng mới nhất bắt đầu gọi lõi ([CandidateGrid.onCoreNeeded]); các việc tính trước cũ hơn thì bỏ. */
    @Volatile private var prefetchGen = 0

    /** Bảng đang mở: tính trước phần lõi của cột trái cho lần chốt âm kế tiếp (phím cách của bảng), như [prefetchPicks]. */
    private fun prefetchLock(r: InputRoot) {
        if (!r.bar.expanded || keys.isEmpty()) return
        val query = r.grid.queryAfterLock() ?: return
        prefetch(lookupGen, listOf(Triple(keys.toString(), query, chosenText)))
    }

    /**
     * Gọi [HanNomEngine.narrow] ở luồng nền cho các trạng thái ([jobs]: phím, lựa chọn, chữ trước) để lõi nhớ sẵn. Xếp
     * sau lượt tra gợi ý đang chờ ([requestLookup] cũng bắt đầu sau lần dựng giao diện này), kẻo thanh gợi ý chậm.
     */
    private fun prefetch(gen: Int, jobs: List<Triple<String, List<Pair<Int, String>>, String>>) {
        if (jobs.isEmpty()) return
        if (gen != prefetchJobsGen) {
            prefetchJobs.clear()
            prefetchJobsGen = gen
        }
        prefetchJobs.addAll(jobs)
        // Chỉ tính trước khi người dùng đã dừng tay [PREFETCH_IDLE_MS]: lõi làm một việc một lúc, việc tính trước đang
        // chạy thì bảng phải chờ nó xong (đo trên điện thoại 2026-11-16, xoá âm liên tục khi đã chốt ~40 âm: bảng tự
        // tính 2–8 ms mà chờ tới 400 ms sau các lần tính trước 33–128 ms, toàn việc thừa vì phím kế tiếp đã tới).
        mainHandler.removeCallbacks(prefetchTask)
        mainHandler.postDelayed(prefetchTask, PREFETCH_IDLE_MS)
    }

    /** Các việc tính trước đang đợi người dùng dừng tay ([prefetch]), và lần tra ([lookupGen]) của chúng. */
    private val prefetchJobs = ArrayList<Triple<String, List<Pair<Int, String>>, String>>()
    private var prefetchJobsGen = 0

    private val prefetchTask = Runnable {
        val gen = prefetchJobsGen
        val pg = prefetchGen
        val jobs = prefetchJobs.toList()
        prefetchJobs.clear()
        lookupThread.execute {
            for ((k, query, b) in jobs) {
                // Bảng vừa cần lõi (chạm phím cách / xoá của bảng): thôi tính trước, kẻo bảng phải chờ.
                if (gen != lookupGen || pg != prefetchGen) return@execute
                runCatching { engine.narrow(k, query, b) }
            }
        }
    }

    /** Gợi ý cho thanh khi bảng đã thu lên: đang chọn dở ở cột trái thì các chữ khớp phần đó trước. */
    private fun barCandidates(r: InputRoot): List<Candidate> {
        // Bật 𡨸單 thì thanh vẫn chỉ chữ đơn của âm đầu khi bảng đã thu lên (người dùng muốn 2026-11-17).
        // Bảng mở mà đã chốt âm: thanh do lưới vẽ (cụm khớp các âm đã chốt, [CandidateGrid.onBar]), không phải
        // [candidates]; chọn đúng gợi ý đầu đang hiện (chạm dấu câu ở cột trái khi đã chốt hết âm thì ra cụm đầu, người
        // dùng báo 2026-11-17).
        if (r.bar.expanded && r.grid.hasLocks && !handwriting) {
            val first = r.bar.firstShown
            // Chỉ khi lưới (và thanh nó vẽ) đã dựng cho đúng chuỗi phím này, kẻo chọn gợi ý cũ.
            if (first != null && r.grid.renderedKeys == keys.toString()) return listOf(first) + candidates
        }
        if (r.bar.expanded || !r.grid.filtersBar || handwriting) return candidates
        val cached = selectionCache?.takeIf { (state, query, _) -> state == hanNomState && query == r.grid.selectionQuery() }
        // Đang tra ở luồng nền (phím vừa đổi): giữ các chữ cũ tới khi có kết quả, không tra ngay ở đây.
        val pending = cached == null && candidatesFor != hanNomState
        val chosen = cached?.third ?: if (pending) selectionCache?.third.orEmpty() else r.grid.selection()
        return if (chosen.isEmpty()) candidates else chosen
    }

    /** Khung gõ Hán Nôm: chữ đã chọn dở, các âm đã chốt ở cột trái ("ph… ", tô xanh, [lockLabels]), rồi phím còn lại. */
    private fun showComposing(r: InputRoot) {
        // Phần chưa chọn hiện bằng Quốc ngữ: sau chữ Latin đã chọn thì cách ra như khi chốt nguyên văn.
        val done = chosenText
        // Telex: dấu cách đỏ ở cuối chuỗi, con trỏ không bao giờ ra sau nó (`tô |␣`). Dấu cách đỏ chỉ hiện khi đã ấn cách một lần ở cuối chuỗi (dấu tách `'` cuối), cả lúc gõ thường lẫn lúc chọn
        // âm; không hiện sẵn (người dùng muốn 2026-11-15).
        trailingSpace = telex && !handwriting && keys.isNotEmpty() && keys.last() == '\''
        val end = if (trailingSpace) " " else ""
        if (lockLabels.isNotEmpty()) {
            // Đã chốt âm: các âm đó hiện bằng nhãn, cách nhau bằng dấu cách thường, chỉ tô xanh; con trỏ vẫn dời được
            // như thường ([shownStops]; người dùng muốn 2026-11-14).
            val head = lockedHead()
            var body = hanNomComposing(afterLocked(keys.toString(), lockLabels.size))
            var target = targetLength(r, body, lockLabels.size)
            var caret = snapCaret()
            val picking = if (target > 0) r.grid.pickingLabel else ""
            if (picking.isNotEmpty()) {
                // Âm tô vàng theo phần đang chọn dở ở cột trái (`thôi` → `t…`), đổi ngay mỗi lần chọn.
                body = picking + body.substring(target)
                if (caret > head.length) caret = if (caret >= head.length + target) caret + picking.length - target else head.length + picking.length
                target = picking.length
            }
            // Số đỏ đếm riêng từng loại (người dùng muốn 2026-11-15): số lần xoá nữa còn hoàn tác cùng loại với hành động
            // sau cùng ([undoSteps]); không ghi được hành động thì theo thứ tự cố định của [onDelete].
            val steps = undoSteps() ?: if (chosen.isNotEmpty() && r.grid.lockedAll) chosen.size else lockLabels.size
            r.setComposing(done + head + body + end, chosen = done.length, caret = done.length + caret,
                locked = head.length, target = target, steps = steps)
            return
        }
        var composed = hanNomComposing()
        var target = targetLength(r, composed, 0)
        var caret = snapCaret()
        val picking = if (target > 0) r.grid.pickingLabel else ""
        if (picking.isNotEmpty()) {
            // Âm tô vàng theo phần đang chọn dở ở cột trái (`thôi` → `t…` → `tôi…`), đổi ngay mỗi lần chọn.
            composed = picking + composed.substring(target)
            if (caret > 0) caret = if (caret >= target) caret + picking.length - target else picking.length
            target = picking.length
        }
        val body = afterChosen(composed, latin = true)
        // Con trỏ không vào phần đã chọn: xa nhất là ngay sau chữ đã chọn, trước dấu cách sau chữ Latin (`tôi| như`),
        // như chữ Hán (`空|một`; người dùng muốn 2026-11-16).
        val at = if (caret == 0) done.length else done.length + body.length - composed.length + caret
        r.setComposing(done + body + end, chosen = done.length, caret = at, locked = body.length - composed.length,
            target = target, steps = undoSteps() ?: chosen.size)
    }

    /**
     * Số đỏ của khung kính: các hành động liền nhau cùng loại ở cuối [acts] (chốt âm hay chọn chữ), tức số lần xoá nữa còn
     * hoàn tác loại đó; 0 khi hành động sau cùng là gõ phím; `null` khi không có hành động ghi lại.
     */
    private fun undoSteps(): Int? {
        val top = acts.lastOrNull() ?: return null
        if (top == Act.KEY) return 0
        val run = acts.takeLastWhile { it == top }.size
        return run.coerceAtMost(if (top == Act.LOCK) lockLabels.size else chosen.size)
    }

    /**
     * Bảng gợi ý thả xuống đang mở (hay đang chọn dở): số ký tự đầu của [body] (khung gõ phần sau các âm đã chốt) là âm
     * thứ [n] mà cột trái đang trỏ tới, để khung gõ tô vàng (người dùng muốn 2026-11-14); 0 nếu không tô.
     */
    private fun targetLength(r: InputRoot, body: String, n: Int): Int {
        if (!r.bar.expanded || handwriting || keys.isEmpty()) return 0
        val k = keys.toString()
        val range = engine.syllableChunks(k).getOrNull(n) ?: return 0
        val chunk = hanNomComposing(k.substring(range)).trim()
        return if (chunk.isNotEmpty() && body.startsWith(chunk)) chunk.length else 0
    }

    /**
     * Nhãn các âm đã chốt ([lockLabels]) cách nhau bằng dấu cách; sau âm chốt cuối chỉ có dấu cách khi còn phím gõ
     * sau nó hay đã ấn cách (dấu tách `'` cuối chuỗi), không hiện sẵn (người dùng muốn 2026-11-15).
     */
    private fun lockedHead(): String {
        val k = keys.toString()
        val more = k.endsWith('\'') || lockLabels.size < engine.syllableChunks(k).size
        return lockLabels.joinToString(" ") + if (more) " " else ""
    }

    /** Phím sau [n] âm đầu của [k], theo các đoạn âm của lõi ([HanNomEngine.syllableChunks]: `twcmn` → tw, c, m, n). */
    private fun afterLocked(k: String, n: Int): String {
        val chunks = engine.syllableChunks(k)
        return if (n < chunks.size) k.substring(chunks[n].first) else ""
    }

    /**
     * Bàn phím Hán Nôm kiểu Telex: thêm lựa chọn giữ nguyên chữ Quốc ngữ đang gõ ([k] như khung gõ hiện; người dùng muốn
     * 2026-11-18: mọi chữ gõ ra đều có chữ Latin ở vị trí thứ 2):
     * - chữ Latin của chữ đang được trỏ tới (chưa chốt âm: chữ đầu, `không` của `khoong mootj hai`) ở vị trí thứ 2, ngay
     *   sau gợi ý đầu; chọn nó thì chữ đó nằm lại trong khung gõ như chữ đã chọn dở, chọn tiếp chữ Hán cho phần sau;
     * - gõ nhiều chữ (đã ấn cách) thì cả cụm Latin (`không một hai`) không lên thanh, đứng ô đầu của bảng thả
     *   ([Candidate.panelFirst]).
     */
    private fun withQuocNgu(list: List<Candidate>, k: String): List<Candidate> {
        val out = list.toMutableList()
        val words = telexWords(k).map { it.trim() }
        val count = words.count { it.isNotEmpty() }
        val head = words[0]
        if (head.isEmpty()) return out
        // Chỉ một chữ: chọn là lấy cả chuỗi phím (cả dấu cách đã ấn sau nó).
        addLiteral(out, head, if (count == 1) k.length else k.substringBefore('\'').length, at = 1)
        if (count > 1) addLiteral(out, words.filter { it.isNotEmpty() }.joinToString(" "), k.length, panelFirst = true)
        return out
    }

    /**
     * Đoạn phím một âm [raw] (không có dấu tách) như khung gõ hiện: có nguyên âm thì ghép Telex (`mieenf` → miền); toàn
     * phụ âm (gõ tắt) giữ phím, trừ `dd` là đ (người dùng báo 2026-11-17: `‹ dd`, `中央dd`).
     */
    private fun shownChunk(raw: String): String =
        if (raw.any { it in "aeiouy" }) hanNomComposing(raw).trim()
        else raw.replace("dd", "đ").replace("DD", "Đ").replace("Dd", "Đ").replace("dD", "đ")

    /**
     * Gợi ý của bảng thả (đã chốt âm / đang chọn ở cột trái / bật 𡨸單): thêm chữ Latin của âm đang được trỏ tới ở vị trí
     * thứ 2 (người dùng muốn 2026-11-18), viết liền sau chữ Hán của các âm đã chốt (`勢時…目標lag`):
     * - chưa chọn gì ở cột trái cho âm đó: cả chữ đã gõ (đoạn phím tới lần cách sau);
     * - đã chọn ở cột trái: theo các lựa chọn đó ([adjustedLatin]: đổi `trung` sang ch là `chung`, chọn thanh là `chúng`).
     * Còn nhiều chữ phía sau thì cả cụm Latin đứng ô đầu của bảng thả ([Candidate.panelFirst]).
     */
    private fun withPointedLatin(list: List<Candidate>, k: String, picked: List<Pair<Int, String>>): List<Candidate> {
        if (!telex || k.isEmpty()) return list
        val lockAt = picked.indices.filter { picked[it].first == CandidateGrid.LOCK_LEVEL }
        val chunks = engine.syllableChunks(k)
        if (chunks.isEmpty()) return list
        // Âm đang trỏ tới: âm đầu chưa chốt; chốt hết rồi thì âm chốt sau cùng, theo lựa chọn lúc chốt nó (người dùng báo
        // 2026-11-18: gõ `thooi`, chốt âm thì chữ Latin `thôi` phải ở ô 2 của thanh, không nằm cuối lưới).
        val n = minOf(lockAt.size, chunks.size - 1)
        // Chỉ lựa chọn của âm đã chốt mới đổi chữ Latin: chạm t ở cột trái thì ô 2 vẫn là `thôi`, chốt âm ở `t…` rồi mới
        // thành `t` (người dùng muốn 2026-11-18).
        val current = if (n < lockAt.size) {
            picked.subList(if (n == 0) 0 else lockAt[n - 1] + 1, lockAt[n])
        } else {
            emptyList()
        }
        fun endOf(i: Int): Int {
            var e = chunks[i].last + 1
            while (e < k.length && k[e] == '\'') e++
            return e
        }
        // Chữ của các âm đã chốt: n chữ đầu của gợi ý tốt nhất phủ hết các âm đó (mỗi chữ một âm).
        val head = if (n == 0) Candidate("", "", 0) else list.firstNotNullOfOrNull { c ->
            val chars = c.text.codePoints().toArray()
            val words = c.reading.split(' ')
            if (c.literal || c.consumed < endOf(n - 1) || chars.size != words.size || words.size < n) null
            else Candidate(String(chars, 0, n), words.take(n).joinToString(" "), endOf(n - 1))
        } ?: return list
        val start = chunks[n].first
        val wordEnd = k.indexOf('\'', start).let { if (it < 0) k.length else it }
        val (latin, consumed) = if (current.isEmpty()) {
            var e = wordEnd
            while (e < k.length && k[e] == '\'') e++
            shownChunk(k.substring(start, wordEnd)) to e
        } else {
            adjustedLatin(shownChunk(k.substring(chunks[n]).replace("'", "")), current) to endOf(n)
        }
        if (latin.isEmpty()) return list
        val out = list.toMutableList()
        addLiteral(out, head.text + latin, consumed, at = 1, reading = "${head.reading} $latin".trim())
        val rest = telexWords(k.substring(start)).map { it.trim() }.filter { it.isNotEmpty() }
        if (rest.size > 1) {
            val phrase = rest.joinToString(" ")
            addLiteral(out, head.text + phrase, k.length, reading = "${head.reading} $phrase".trim(), panelFirst = true)
        }
        return out
    }

    /**
     * Chữ Latin của âm [typed] (như khung gõ hiện) sau các lựa chọn [picks] ở cột trái: chọn cả âm (tới thanh) thì đúng
     * âm đó; chọn chưa hết âm thì chỉ là các phần đã chọn, thay cho cả âm đã gõ (người dùng muốn 2026-11-18: gõ `thooi`,
     * chọn t rồi dừng ở `t…` thì chữ Latin là `t`, không phải `tôi`; thay quy tắc cũ `trung` chọn ch → `chung`).
     */
    private fun adjustedLatin(typed: String, picks: List<Pair<Int, String>>): String {
        fun pick(part: Int) = picks.lastOrNull { it.first == part }?.second
        val result = pick(CandidateGrid.PART_SYLLABLE)
            ?: (pick(CandidateGrid.PART_INITIAL).orEmpty() +
                (pick(CandidateGrid.PART_NUCLEUS) ?: pick(CandidateGrid.PART_VOWEL)).orEmpty() +
                pick(CandidateGrid.PART_FINAL).orEmpty())
        return if (typed.firstOrNull()?.isUpperCase() == true) result.replaceFirstChar { it.uppercaseChar() } else result
    }

    /**
     * Thêm lựa chọn giữ nguyên Quốc ngữ [text] cho [consumed] phím đầu vào [out]: ở vị trí [at] (vị trí thứ 2 là 1), hay
     * cuối danh sách khi [panelFirst] (cả cụm Latin, ô đầu bảng thả). Đã có chữ y hệt thì thôi.
     */
    private fun addLiteral(
        out: MutableList<Candidate>, text: String, consumed: Int, at: Int = out.size, reading: String = text,
        panelFirst: Boolean = false,
    ) {
        if (text.isEmpty() || out.any { it.text == text }) return
        out.add(at.coerceAtMost(out.size), Candidate(text, reading, consumed, literal = true, panelFirst = panelFirst))
    }

    private fun refresh() {
        if (!caretMovable) tail = 0
        val r = root ?: return
        if (handwriting) {
            r.setComposing("")
            // Gợi ý đầu hiện sẵn trong ô nhập (chữ đang soạn, như Gboard); chọn chữ khác thì thay vào.
            val top = candidates.firstOrNull()?.text.orEmpty()
            if (top.isNotEmpty() || writtenShown) currentInputConnection?.setComposingText(top, 1)
            writtenShown = top.isNotEmpty()
            if (candidates.isEmpty()) r.showWords(emptyList()) else r.bar.showCandidates(candidates, null)
            if (r.bar.expanded) r.grid.show(candidates, r.bar.hiddenFor(candidates), keys.toString())
            return
        }
        if (keys.isEmpty()) {
            if (appSelectionUiDismissed) {
                appSelectionUiDismissed = false
                composeAnchor = null
                // Khung gõ đóng: con trỏ của ô nhập hiện lại nếu bản cũ đã ẩn (đang tìm emoji thì ô tìm vẫn giữ việc này).
                if (r.emojiSearch.active != true) appConsumesInput(false)
            }
            if (direct) showTyped("")
            // Hết phím (xoá hết, hay đã chọn xong): phần chọn dở ở cột trái kết thúc hẳn, gõ lại `twc` không được hiện
            // lại các âm đã chốt cũ (người dùng muốn 2026-11-14).
            r.grid.syncKeys("")
            lockLabels = emptyList()
            clearLookupCache()
            r.setComposing("")
            // Cài đặt có thể vừa đổi trong ứng dụng khi bàn phím đang mở.
            engine.apply(settings)
            updateKeyboardLabels()
            candidates = if (effectiveMode == Mode.ENGLISH || direct) wordCandidates() else emptyList()
            r.showWords(candidates)
            return
        }
        if (direct) {
            r.setComposing("")
            showTyped(composed())
            candidates = wordCandidates()
            r.showWords(candidates)
            return
        }
        // Hán Nôm: chữ đang gõ chỉ hiện trong khung gõ, chọn xong mới vào ô nhập (như pinyin).
        if (!appSelectionUiDismissed) {
            dismissAppSelectionUi()
            appSelectionUiDismissed = true
        }
        if (candidatesFor != hanNomState) {
            // Đang chốt âm (bảng đã thu): thanh gợi ý cần tra các chữ khớp các âm đã chốt, cả trăm ms: luôn tra ở luồng nền.
            val r = root
            val selecting = r != null && !r.bar.expanded && r.grid.selecting
            val cached = if (selecting) null else perf("candidates (nhớ)", keys.toString()) { cachedLookup() }
            if (cached != null) {
                // Đã tra lúc gõ qua trạng thái này (vừa xoá phím): hiện ngay.
                lookupGen++
                candidates = cached
                candidatesFor = hanNomState
            } else if (keys.length <= SYNC_KEYS && !selecting) {
                lookupGen++
                candidates = lookup(keys.toString(), chosenText, telex)
                candidatesFor = hanNomState
            } else {
                requestLookup()
            }
        }
        // Đang tra ở luồng nền: khung gõ hiện ngay, thanh gợi ý giữ gợi ý cũ tới khi có kết quả.
        showLookup(r)
    }

    /** Đã ẩn giọt nước con trỏ / hộp "Dán" của ứng dụng cho lần gõ Hán Nôm này (xem [dismissAppSelectionUi]). */
    private var appSelectionUiDismissed = false
    /**
     * Vùng chọn (đầu, cuối) của ô nhập lúc khung gõ mở: khi khung gõ đang hiện, bàn phím không sửa gì trong ô nhập (chọn
     * hết mới đưa chữ vào), nên vùng chọn đổi khác chỗ này là người dùng chạm / giữ trong ô nhập. `null`: không đọc được.
     */
    private var composeAnchor: Pair<Int, Int>? = null

    /**
     * Người dùng chạm / giữ trong ô nhập làm con trỏ dời chỗ khi khung gõ đang hiện: bỏ hết chữ đang gõ, cả chữ Hán đã
     * chọn dở, không đưa gì vào ô nhập (như Gboard gõ pinyin, đo trên máy người dùng 2026-11-15). Vùng chọn / con trỏ
     * mới của người dùng giữ nguyên.
     */
    private fun stopComposing() {
        dropTyping()
        tail = 0
        refresh()
    }

    /**
     * Giọt nước con trỏ và hộp "Dán / Tự động điền" của ô nhập là cửa sổ nổi của ứng dụng, hệ thống xếp trên cửa sổ bàn
     * phím nên đè lên khung gõ (đo trên Realme, Tin nhắn). Ô nhập tự ẩn chúng khi chữ trong ô đổi, nhưng chữ Hán Nôm
     * đang gõ chỉ nằm trong khung gõ: chèn rồi xoá một chữ tạm trong một lần sửa để ô nhập ẩn chúng, chữ trong ô
     * không đổi. Ô nhập đang có vùng chọn thì không làm (chữ tạm sẽ thay mất đoạn đang chọn).
     */
    private fun dismissAppSelectionUi() {
        composeAnchor = null
        if (root?.emojiSearch?.active == true) return
        val ic = appConnection ?: return
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val anchor = et?.let { it.startOffset + it.selectionStart to it.startOffset + it.selectionEnd }
        composeAnchor = anchor
        // Không ẩn con trỏ của ô nhập (setImeConsumesInput) khi khung gõ đang hiện: ô nhập khi đó tắt cả con trỏ chạm giữ,
        // giữ trong ô ra vùng chọn thay vì con trỏ. Gboard gõ pinyin cũng để con trỏ ô nhập hiện (đo trên máy người dùng,
        // Tin nhắn, 2026-11-15).
        if (anchor == null || anchor.first != anchor.second) return
        ic.beginBatchEdit()
        ic.setComposingText(" ", 1)
        ic.setComposingText("", 1)
        ic.finishComposingText()
        ic.endBatchEdit()
    }

    /** Khung gõ Hán Nôm: không tự cách chữ, chỉ cách ở chỗ người dùng ấn phím cách (lưu là dấu tách `'`). */
    private fun hanNomComposing(k: String = keys.toString()): String = telexWords(k).joinToString(" ")

    // ---- Con trỏ trong khung gõ Hán Nôm ----
    //
    // Con trỏ là một vị trí trong chuỗi phím ([caretAt]); gõ phím chèn vào đó, phím xoá xoá phím trước nó. Chỉ dừng ở
    // chỗ mà phím trước con trỏ hiện đúng phần đầu khung gõ ([caretStops]): `hown` (hơn) dừng ở |hơn, h|ơn, hơ|n,
    // hơn|, không dừng giữa `o` và `w` (đang ghép thành ơ).

    /** Số phím sau con trỏ (0: con trỏ ở cuối). Đếm từ cuối nên chọn phần đầu (bỏ phím đầu) không làm dời con trỏ. */
    private var tail = 0
    private val caretAt get() = keys.length - tail.coerceIn(0, keys.length)

    /** Đang có khung gõ Hán Nôm để dời con trỏ. */
    private val caretMovable get() = effectiveMode == Mode.HAN_NOM && !direct && !handwriting && keys.isNotEmpty()

    /** Các chỗ con trỏ dừng được, tăng dần: (số phím trước con trỏ, vị trí trong khung gõ của [hanNomComposing]). */
    private fun caretStops(k: String = keys.toString()): List<Pair<Int, Int>> {
        val full = hanNomComposing(k)
        // Vị trí trong khung gõ → số phím (lấy số lớn nhất: phím dấu cuối chữ thuộc về chữ trước con trỏ).
        val stops = java.util.TreeMap<Int, Int>()
        fun add(p: Int, shown: String) {
            if (full.startsWith(shown)) stops[shown.length] = p
        }
        if (telex) {
            // Từng đoạn giữa hai dấu tách: chỉ ghép lại đoạn đang xét (khỏi ghép lại cả chuỗi ở mỗi vị trí).
            var start = 0
            var before = ""
            for (seg in k.split('\'')) {
                val head = if (start == 0) "" else "$before "
                if (full.startsWith(head)) for ((d, j) in segmentStops(seg)) stops[head.length + d] = start + j
                before = head + shownWord(seg)
                start += seg.length + 1
            }
        } else {
            for (p in 0..k.length) add(p, hanNomComposing(k.substring(0, p)))
        }
        stops[full.length] = k.length
        return stops.map { (shown, p) -> p to shown }
    }

    /**
     * Chỗ dừng trong một đoạn Telex [seg] (giữa hai dấu tách): vị trí trong chữ đang hiện → số phím. Phím trước con trỏ
     * hiện đúng phần đầu chữ, hay đúng nếu bỏ dấu thanh (`nguw|owif` là ngư|ời: dấu thanh gõ cuối nằm trên chữ sau
     * con trỏ), để con trỏ dừng được ở từng ký tự.
     */
    private fun segmentStops(seg: String): Map<Int, Int> {
        val shown = ::shownWord
        val whole = shown(seg)
        val bare = untoned(whole)
        val exact = HashMap<Int, Int>()
        val loose = HashMap<Int, Int>()
        for (j in 0..seg.length) {
            val p = shown(seg.substring(0, j))
            when {
                whole.startsWith(p) -> exact[p.length] = j
                bare.startsWith(untoned(p)) -> loose[p.length] = j
            }
        }
        return loose + exact
    }

    private fun untoned(s: String): String = java.text.Normalizer.normalize(
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(TONE_MARKS, ""),
        java.text.Normalizer.Form.NFC,
    )

    /**
     * Trước khi dời con trỏ: đoạn nào gõ theo thứ tự làm con trỏ không dừng được ở mọi ký tự (`nguoiwf`: w gõ sau
     * đổi cả u lẫn o) thì đổi sang chuỗi phím chuẩn hiện y hệt (`nguwowif`). Chữ hiện không đổi, con trỏ giữ chỗ.
     */
    private fun canonicalizeForCaret() {
        if (!telex) return
        val k = keys.toString()
        if (chosen.lastOrNull()?.rest == k) return
        val next = k.split('\'').joinToString("'") { seg ->
            if (seg.isEmpty() || seg.any { !it.isLetter() }) return@joinToString seg
            val shown = engine.displayTelex(seg, settings.toneStyle)
            if (shown.contains(' ') || segmentStops(seg).size > shown.length) return@joinToString seg
            val canon = engine.decompose(shown)
            val better = canon != seg && engine.displayTelex(canon, settings.toneStyle) == shown &&
                segmentStops(canon).size > segmentStops(seg).size
            if (better) canon else seg
        }
        if (next == k) return
        val at = caretStops().lastOrNull { it.first <= caretAt }?.second ?: 0
        keys.clear()
        keys.append(next)
        val stops = caretStops()
        tail = keys.length - (stops.lastOrNull { it.second <= at } ?: stops.first()).first
    }

    /**
     * Phần khung gõ sau chữ đã chọn dở và các chỗ con trỏ dừng được trong nó: (số phím trước con trỏ, vị trí hiện). Đã
     * chốt âm ở cột trái thì nhãn các âm đó ([lockLabels]) thay cho phím của chúng; con trỏ chỉ dừng ở phần gõ sau
     * các âm đã chốt, xa nhất là ngay sau âm chốt cuối (người dùng muốn 2026-11-14).
     */
    /** Phần khung gõ của [shownStops] (không tính các chỗ dừng). */
    private fun shownBody(): String {
        if (lockLabels.isEmpty()) return hanNomComposing()
        val k = keys.toString()
        val chunks = engine.syllableChunks(k)
        val n = lockLabels.size
        val bodyStart = if (n < chunks.size) chunks[n].first else k.length
        return lockedHead() + hanNomComposing(k.substring(bodyStart))
    }

    private fun shownStops(): Pair<String, List<Pair<Int, Int>>> {
        if (lockLabels.isEmpty()) return hanNomComposing() to caretStops()
        val k = keys.toString()
        val chunks = engine.syllableChunks(k)
        val n = lockLabels.size
        val head = StringBuilder()
        val stops = ArrayList<Pair<Int, Int>>()
        // Con trỏ không vào giữa âm đã chốt: chỉ dừng ngay trước và ngay sau mỗi âm (sau âm thì xoá được cả âm,
        // [deleteLockedBefore]).
        // Phần đã chốt (chữ xanh) con trỏ không vào được: xa nhất là ngay sau âm chốt cuối, ở đó xoá được cả âm đó
        // ([deleteLockedBefore]; người dùng muốn 2026-11-14). Chỗ trống giữa hai dấu tách (âm chốt cuối vừa bị xoá)
        // cũng là chỗ dừng, để gõ âm khác vào.
        head.append(lockedHead())
        val lastEnd = chunks.getOrNull(n - 1)?.let { it.last + 1 } ?: 0
        val bodyStart = if (n < chunks.size) chunks[n].first else k.length
        // Có dấu tách sau âm chốt cuối (`tôi |`): con trỏ cũng dừng được ngay sau âm đó, trước dấu cách (`tôi| `), để
        // xoá âm đó hay các âm trước (người dùng muốn 2026-11-15). Không có dấu tách (gõ liền, `twcmn`) thì chỗ ngay
        // sau âm chốt cuối cũng hiện ở `tôi|`, không ra sau dấu cách: vuốt tới đó là hết (người dùng muốn 2026-11-16).
        if (n > 0 && head.endsWith(' ')) stops.add(lastEnd to head.length - 1)
        val gap = k.substring(lastEnd.coerceAtMost(bodyStart), bodyStart)
        if (gap.contains("''")) stops.add(lastEnd + gap.indexOf("''") + 1 to head.length)
        val bodyKeys = k.substring(bodyStart)
        for ((p, s) in caretStops(bodyKeys)) stops.add(bodyStart + p to head.length + s)
        // Sau âm này và trước âm kế là cùng một chỗ trong chuỗi phím (không có dấu tách giữa): giữ một chỗ dừng.
        return head.toString() + hanNomComposing(bodyKeys) to stops.distinctBy { it.first }
    }

    /**
     * Phím xoá khi con trỏ ngay sau một âm đã chốt ([shownStops]): bỏ cả âm đó (phím của nó), con trỏ ở chỗ cũ để gõ âm
     * khác vào, các âm đã chốt khác giữ nguyên ([CandidateGrid.syncKeys]); như gỡ chữ Hán Nôm đã chọn (người dùng muốn
     * 2026-11-14). Trả `true` nếu đã xoá.
     */
    private fun deleteLockedBefore(): Boolean {
        if (lockLabels.isEmpty() || tail <= 0) return false
        val k = keys.toString()
        val chunks = engine.syllableChunks(k)
        val at = caretAt
        val i = (0 until minOf(lockLabels.size, chunks.size)).lastOrNull { i ->
            val end = chunks[i].last + 1
            val next = chunks.getOrNull(i + 1)?.first ?: k.length
            end <= at && at <= next
        } ?: return false
        // Chỗ trống giữa hai dấu tách (`tw'|'mieenf`): âm gõ vào không dính sang âm trước hay sau; con trỏ đứng giữa
        // ([shownStops] có chỗ dừng ở đó).
        val pre = k.substring(0, chunks[i].first).trimEnd('\'')
        val post = k.substring(at).trimStart('\'')
        // Xoá hết các âm đã chốt, phía trước không còn gì: không để chỗ trống (dấu cách) trước âm còn lại (` miền`;
        // người dùng báo 2026-11-16).
        if (pre.isEmpty() && lockLabels.size <= 1) {
            keys.clear()
            keys.append(post)
            tail = post.length
            return true
        }
        val lead = if (pre.isEmpty()) "" else "'"
        val next = pre + lead + "'" + post
        keys.clear()
        keys.append(next)
        tail = next.length - (pre.length + lead.length)
        return true
    }

    /** Đưa con trỏ về chỗ dừng gần nhất phía sau (vừa gõ giữa một chữ thì con trỏ sang cuối chữ); trả vị trí hiện. */
    private fun snapCaret(): Int {
        // Con trỏ ở cuối: chỉ cần độ dài khung gõ, khỏi tính mọi chỗ dừng (mỗi chữ một lần gọi lõi cho từng phần đầu).
        if (tail <= 0) return shownBody().length
        val stops = shownStops().second
        val stop = stops.firstOrNull { it.first >= caretAt } ?: stops.last()
        tail = keys.length - stop.first
        return stop.second
    }

    /** Chạm vào khung gõ tại ký tự thứ [offset] (tính cả chữ đã chọn dở ở đầu): dời con trỏ tới chỗ dừng gần nhất. */
    private fun onComposeTap(offset: Int) {
        if (!caretMovable) return
        val r = root ?: return
        canonicalizeForCaret()
        val (body, stops) = shownStops()
        val start = r.composingText.length - body.length - if (trailingSpace) 1 else 0
        val at = offset - start
        val stop = stops.minByOrNull { kotlin.math.abs(it.second - at) } ?: return
        tail = keys.length - stop.first
        showLookup(r)
    }

    /** Vuốt phím cách khi đang gõ Hán Nôm: dời con trỏ [delta] chỗ dừng. */
    private fun moveCaret(delta: Int) {
        val r = root ?: return
        canonicalizeForCaret()
        val stops = shownStops().second
        val i = stops.indexOfLast { it.first <= caretAt }.coerceAtLeast(0)
        tail = keys.length - stops[(i + delta).coerceIn(0, stops.lastIndex)].first
        showLookup(r)
    }

    /**
     * Telex, con trỏ giữa một chữ: sửa thẳng trên chữ đang hiện như sửa văn bản, không chèn / xoá phím (xoá `e` của
     * `telex` gõ bằng `telexx` ra telx, không phải phím `telxx`). [edit] nhận chữ của đoạn đang sửa (giữa hai dấu tách)
     * và vị trí con trỏ trong nó, trả chữ mới (dấu cách là tách chữ) và vị trí con trỏ mới, hay `null` nếu không sửa
     * kiểu này. Trả `true` nếu đã sửa.
     */
    private fun editInWord(edit: (String, Int) -> Pair<String, Int>?): Boolean {
        if (!telex || tail <= 0 || !caretMovable) return false
        val k = keys.toString()
        val at = caretAt
        if (at == 0 || k[at - 1] == '\'' || k[at] == '\'') return false
        val start = k.lastIndexOf('\'', at - 1) + 1
        val end = k.indexOf('\'', at).let { if (it < 0) k.length else it }
        val shown = { s: String -> engine.displayTelex(s, settings.toneStyle).replace(" ", "") }
        val (text, caret) = edit(shown(k.substring(start, end)), shown(k.substring(start, at)).length) ?: return false
        val word = text.split(' ').joinToString("'") { telexKeysFor(it) }
        val target = hanNomComposing(k.substring(0, start)).length + caret
        keys.clear()
        keys.append(k.substring(0, start)).append(word).append(k.substring(end))
        tail = 0
        if (keys.isEmpty()) return true
        val stops = caretStops()
        val stop = stops.lastOrNull { it.second == target } ?: stops.firstOrNull { it.second >= target } ?: stops.last()
        tail = keys.length - stop.first
        return true
    }

    /**
     * Telex, con trỏ giữa một chữ tiếng Việt (không phải chữ thô): chèn phím chữ cái [c] vào đúng chỗ trong chuỗi phím
     * vẫn ra một chữ thì chèn phím như gõ thường, để phím dấu vẫn ghép dấu (`nha|nh` gõ `s` → nhánh).
     */
    private fun keyInsertKeepsWord(c: Char): Boolean {
        if (!telex || tail <= 0 || !caretMovable || !c.isLetter()) return false
        val k = keys.toString()
        val at = caretAt
        val start = k.lastIndexOf('\'', at - 1) + 1
        val end = k.indexOf('\'', at).let { if (it < 0) k.length else it }
        val seg = k.substring(start, end)
        val shown = engine.displayTelex(seg, settings.toneStyle).replace(" ", "")
        if (telexKeysFor(shown).startsWith('')) return false
        val next = k.substring(start, at) + c + k.substring(at, end)
        return !engine.displayTelex(next, settings.toneStyle).contains(' ') && !next.startsWith('')
    }

    /** Phím Telex hiện đúng chữ [text]; không có thì chữ thô (`RAW` của lõi + chữ, gõ tiếp không ghép dấu). */
    private fun telexKeysFor(text: String): String {
        if (text.isEmpty()) return ""
        val k = engine.decompose(text)
        return if (engine.displayTelex(k, settings.toneStyle).replace(" ", "") == text) k else "$text"
    }

    /** Chèn [text] vào chuỗi phím ở con trỏ. */
    private fun insertKeys(text: String) {
        root?.hideLoupe()
        keys.insert(caretAt, text)
        acts.add(Act.KEY)
    }

    private fun useTool(id: ToolId) {
        showToolsPanel(false)
        if (id != ToolId.TEXT_EDIT) root?.showTextEdit(false)
        when (id) {
            ToolId.CLIPBOARD -> openClipboard()
            ToolId.EMOJI -> openEmoji()
            ToolId.DICTIONARY -> openDictionary()
            ToolId.RESIZE -> root?.showResize(true)
            ToolId.ONE_HAND -> onOneHand(if (settings.oneHand == OneHand.OFF) OneHand.LEFT else OneHand.OFF)
            ToolId.TEXT_EDIT -> openTextEdit()
            ToolId.LANGUAGE -> onModeTap()
            ToolId.SETTINGS -> openSettings()
        }
    }

    /**
     * Nút cài đặt. Đang gõ ngay trong ứng dụng này (vd. ô "Gõ thử"): về trang chính của ứng dụng trong chính cửa sổ đang
     * mở (xoá các trang đang mở, như mở từ biểu tượng ứng dụng), không mở cửa sổ mới, không chạy lại màn khởi động.
     */
    private fun openSettings() {
        if (currentInputEditorInfo?.packageName != packageName) {
            openActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val intent = Intent(this, SettingsActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(SettingsActivity.EXTRA_NO_SPLASH, true)
        // Android 13+: màn chờ của hệ thống chỉ là nền trơn, không hiện icon.
        val options = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.app.ActivityOptions.makeBasic()
                .setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
                .toBundle()
        } else null
        startActivity(intent, options)
        requestHideSelf(0)
    }

    // ---- Bảng sửa văn bản ----

    private fun openTextEdit() {
        val r = root ?: return
        commitHandwriting()
        commitComposing()
        selAnchor = -1
        val selected = !currentInputConnection?.getSelectedText(0).isNullOrEmpty()
        r.showTextEdit(true, selected, selected || fieldHasText(), clipHasText())
    }

    /** Ô nhập có chữ (trước hay sau con trỏ): bảng sửa văn bản tắt hết nút khi trống. */
    private fun fieldHasText(): Boolean {
        val ic = currentInputConnection ?: return false
        return !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    /** Bảng nhớ của máy có chữ (đã sao chép / cắt): "Dán" của bảng sửa văn bản mới bật. */
    private fun clipHasText(): Boolean = runCatching {
        val clip = clipboardManager.primaryClip ?: return@runCatching false
        clip.itemCount > 0 && !clip.getItemAt(0).coerceToText(this).isNullOrEmpty()
    }.getOrDefault(false)

    override fun onEditClose() {
        root?.showTextEdit(false)
    }

    override fun onEditAction(action: EditAction, selecting: Boolean) {
        val ic = currentInputConnection ?: return
        when (action) {
            EditAction.UP -> moveCursor(KeyEvent.KEYCODE_DPAD_UP, selecting)
            EditAction.DOWN -> moveCursor(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            EditAction.LEFT -> moveCursor(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            EditAction.RIGHT -> moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            EditAction.HOME -> moveCursor(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            EditAction.END -> moveCursor(KeyEvent.KEYCODE_MOVE_END, selecting)
            EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
            EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            EditAction.DELETE -> onDelete()
            EditAction.DESELECT -> {
                val end = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.let { it.startOffset + it.selectionEnd }
                if (end != null) ic.setSelection(end, end)
            }
        }
    }

    /**
     * Chỗ bắt đầu chọn (vị trí trong ô nhập) khi bảng sửa văn bản bắt đầu kéo vùng chọn; -1: chưa có. Ô nhập trên
     * trang web báo vùng chọn đã sắp (đầu nhỏ trước), không cho biết đầu nào đang di chuyển: cần biết để nhận ra con
     * trỏ đã chạm mép.
     */
    private var selAnchor = -1

    /**
     * Mũi tên, về đầu / cuối dòng: gửi phím thật để ô nhập tự di chuyển (▲ ▼ giữ đúng cột theo cách ô nhập xuống
     * dòng, vùng chọn giữ đúng đầu). Trừ chỗ phím thật làm ô nhập chuyển chỗ nhập sang ô khác (đóng bảng): ô một dòng
     * thì ▲ / ▼ thành về đầu / cuối (như Gboard); đầu di chuyển đã chạm mép (▲ ◀ ở đầu, ▼ ▶ ở cuối) thì bỏ qua.
     */
    private fun moveCursor(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val text = et?.text
        if (et == null || text == null || et.selectionStart < 0) return sendEditKey(code, shift)
        val s = et.selectionStart
        val e = et.selectionEnd
        val lo = minOf(s, e)
        val hi = maxOf(s, e)
        val remembered = selAnchor - et.startOffset
        // Đầu cố định: chỗ bắt đầu chọn đã nhớ nếu còn khớp, không thì theo ô nhập báo (ô nhập thường báo đúng thứ tự).
        val anchor = if (lo != hi && (remembered == lo || remembered == hi)) remembered else s
        val focus = if (lo == hi) lo else if (anchor == lo) hi else lo
        selAnchor = if (!shift) -1 else et.startOffset + if (lo == hi) lo else anchor
        val multiLine = ((currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
            text.contains('\n')
        val key = when {
            multiLine -> code
            code == KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_MOVE_HOME
            code == KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_MOVE_END
            else -> code
        }
        // Không chọn mà đang có vùng chọn: phím thật bỏ chọn, không lo chạm mép.
        if (shift || lo == hi) {
            val atEdge = when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> focus == 0
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> focus == text.length
                else -> false
            }
            if (atEdge) return
        }
        sendEditKey(key, shift)
    }

    /** Phím di chuyển con trỏ; [shift]: kéo vùng chọn theo (như giữ Shift trên bàn phím thật). */
    private fun sendEditKey(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val t = android.os.SystemClock.uptimeMillis()
        val device = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
        // Ô nhập bỏ qua cờ Shift trên riêng phím mũi tên: nhấn giữ phím Shift thật quanh nó (như Gboard).
        if (shift) ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta, device, 0, flags))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta, device, 0, flags))
        ic.sendKeyEvent(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta, device, 0, flags))
        if (shift) ic.sendKeyEvent(KeyEvent(t, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, device, 0, flags))
    }

    // ---- Bảng tiện ích (⊞) ----

    private fun showToolsPanel(show: Boolean) {
        val r = root ?: return
        if (show == r.toolsShown) return
        r.showTools(show)
    }

    override fun onToolUse(tool: ToolId) = useTool(tool)

    override fun onToolsChanged(layout: ToolLayout) {
        settings.tools = layout
    }

    // ---- Bàn phím: bảng chọn (giữ phím chế độ) ----

    override fun onModeLongPress() = showKeyboardChooser()

    override fun onOneHand(mode: OneHand) {
        settings.oneHand = mode
        root?.keyboard?.oneHand = mode
    }

    override fun onResizeKeyboard() = useTool(ToolId.RESIZE)

    /**
     * Bảng "Thay đổi bàn phím" như Gboard: các bàn phím của ứng dụng (đang dùng có ✓), các bộ gõ khác đã bật trong
     * máy, và nút mở cài đặt ngôn ngữ.
     */
    private fun showKeyboardChooser() {
        val r = root ?: return
        /** Chữ của bảng theo bàn phím đang dùng. */
        val style = r.keyboard.style
        val current = settings.keyboard
        val rows = settings.keyboards.map { kb ->
            KeyboardDialog.Row(kb.mode.title, kb.layout.title(kb.mode), kb == current) {
                if (secure) return@Row
                commitHandwriting()
                commitComposing()
                settings.keyboard = kb
                mode = kb.mode
                layout = kb.layout
                updateKeyboardLabels()
                refresh()
            }
        }.toMutableList()
        val imm = getSystemService(InputMethodManager::class.java)
        // Bộ gõ khác: mỗi ngôn ngữ một dòng, tên bộ gõ ở trên (Gboard / English (US)) như bảng chọn của hệ thống,
        // để bộ gõ nhiều ngôn ngữ vẫn biết dòng nào thuộc bộ gõ nào.
        for (info in imm.enabledInputMethodList.filter { it.packageName != packageName }) {
            val label = info.loadLabel(packageManager)
            val subtypes = imm.getEnabledInputMethodSubtypeList(info, true)
                .map { it to it.getDisplayName(this, info.packageName, info.serviceInfo.applicationInfo).toString() }
                .filter { it.second.isNotBlank() }
            if (subtypes.isEmpty()) {
                rows.add(KeyboardDialog.Row(label, null) { switchTo(imm, info.id, null) })
            } else {
                // Android không cho biết tên kiểu gõ của bộ gõ khác: cùng ngôn ngữ nhiều kiểu thì đánh số để phân biệt.
                val total = subtypes.groupingBy { it.second }.eachCount()
                val seen = HashMap<String, Int>()
                for ((subtype, name) in subtypes) {
                    val n = (seen[name] ?: 0) + 1
                    seen[name] = n
                    val shown = if (total.getValue(name) > 1) "$name · ${style.say("kiểu $n", "矯 $n", "style $n")}" else name
                    rows.add(KeyboardDialog.Row(label, shown) { switchTo(imm, info.id, subtype) })
                }
            }
        }
        val title = style.say("Thay đổi bàn phím", "𠊝𢷮盤柉", "Change keyboard")
        KeyboardDialog.show(r, KeyboardTheme.of(this), title, rows, style.say("Cài đặt ngôn ngữ", "掑撻言語", "Language settings") to {
            openActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "LANGUAGES"))
        })
    }

    /** Chuyển sang bộ gõ khác [id] (và ngôn ngữ [subtype] của nó, nếu có). */
    private fun switchTo(imm: InputMethodManager, id: String, subtype: android.view.inputmethod.InputMethodSubtype?) {
        ClipAddActivity.keyboardLeft()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (subtype != null) switchInputMethod(id, subtype) else switchInputMethod(id)
        } else {
            val token = window.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            if (subtype != null) imm.setInputMethodAndSubtype(token, id, subtype) else imm.setInputMethod(token, id)
        }
    }

    // ---- Bảng nhớ tạm ----

    private lateinit var clips: ClipStore
    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        captureClip(fresh = true)
        // Vừa sao chép / cắt: "Dán" của bảng sửa văn bản bật lên.
        root?.let { if (it.textEditShown) it.textEdit.canPaste = clipHasText() }
    }

    /**
     * Chép chữ đang trong bảng nhớ của máy vào bảng nhớ tạm (khi đã bật; bỏ mật khẩu, chữ nhạy cảm).
     * [fresh]: người dùng vừa sao chép; không thì mục đã xoá không được chép lại.
     */
    private fun captureClip(fresh: Boolean = false) {
        if (!settings.clipboard) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        val sensitive = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        if (sensitive || clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        // Lúc sao chép theo hệ thống: chữ nằm trong bộ nhớ của máy quá 1 tiếng thì không thêm lại.
        if (text.isNotBlank()) clips.add(text, fresh, clip.description?.timestamp ?: 0L)
        if (root?.clipboardShown == true) root?.clipboard?.show(true, clips.all())
    }

    private fun openClipboard() {
        val r = root ?: return
        commitHandwriting()
        commitComposing()
        captureClip()
        r.showClipboard(true, settings.clipboard, if (settings.clipboard) clips.all() else emptyList())
    }

    override fun onClipPaste(clip: Clip) {
        currentInputConnection?.commitText(clip.text, 1)
    }

    override fun onClipsPin(clips: List<Clip>, pinned: Boolean) {
        this.clips.setPinned(clips, pinned)
        root?.clipboard?.show(true, this.clips.all())
    }

    override fun onClipsDelete(clips: List<Clip>) {
        this.clips.remove(clips)
        root?.clipboard?.show(true, this.clips.all())
    }

    override fun onClipsRestore(clips: List<Clip>) {
        this.clips.restore(clips)
        root?.clipboard?.show(true, this.clips.all())
    }

    override fun onClipEnabled(on: Boolean) {
        settings.clipboard = on
        if (!on) clips.clearRecent() else captureClip()
        root?.clipboard?.show(on, if (on) clips.all() else emptyList())
    }

    /** Không ẩn bàn phím như [openActivity]: hộp thoại cần nó để gõ ngay. */
    override fun onClipAdd() {
        startActivity(
            Intent(this, ClipAddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_FROM_KEYBOARD, true),
        )
    }

    override fun onClipEdit(clip: Clip) {
        startActivity(
            Intent(this, ClipAddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_FROM_KEYBOARD, true)
                .putExtra(ClipAddActivity.EXTRA_EDIT, clip.text),
        )
    }

    override fun onClipClose() {
        root?.showClipboard(false, false, emptyList())
        updateShift()
    }

    private fun commitComposing() {
        if (keys.isEmpty() && chosen.isEmpty()) return
        // Quốc ngữ: chữ đã nằm sẵn trong ô nhập, chỉ cần bỏ trạng thái đang gõ.
        // Hán Nôm kiểu Telex: đúng chữ trong khung gõ (không tự tách âm tiết: realme không thành "re a l me").
        if (direct) typed = ""
        else currentInputConnection?.commitText(takeChosen(if (telex) hanNomComposing() else composed(), latin = true), 1)
        keys.clear()
        refresh()
    }

    /**
     * Lấy các chữ đã chọn trong khung gõ để chốt (học các chữ đó), ghép với [next] (xem [afterChosen]), rồi bỏ khỏi
     * khung gõ.
     */
    private fun takeChosen(next: String, latin: Boolean): String {
        val text = chosenText + afterChosen(next, latin)
        if (!secure) chosen.forEach { if (!it.candidate.literal) engine.select(it.candidate) }
        clearLookupCache()
        chosen.clear()
        acts.clear()
        return text
    }

    private fun pick(c: Candidate) {
        val ic = currentInputConnection ?: return
        // Đã chọn gợi ý: phần chọn dở ở cột trái kết thúc (phím còn lại là phần sau, không còn âm đã chốt). Nhớ các âm
        // đã chốt lúc chọn, để xoá chữ vừa chọn thì trở lại đúng như trước (chọn ở thanh gợi ý, phím cách, lưới).
        root?.grid?.remember()
        if (handwriting || effectiveMode != Mode.HAN_NOM) root?.grid?.dropSelection()
        if (handwriting) {
            ic.commitText(c.text, 1)
            writtenShown = false
            val pad = root?.pad
            if (candidates.indexOf(c) > 0 && pad != null && pad.hasInk) learnWritten(c.text, pad)
            clearHandwriting()
            root?.let { if (it.bar.expanded) it.bar.expanded = false }
            refresh()
            return
        }
        if (effectiveMode != Mode.HAN_NOM) {
            pickWord(c)
            return
        }
        // Chạm gợi ý cũ trong lúc đang tra lại cho phím vừa gõ (luồng nền): bỏ qua, gợi ý mới sắp hiện.
        if (keys.isNotEmpty() && candidatesFor != hanNomState) return
        val rest = keys.drop(c.consumed).trimStart('\'', ' ').toString()
        // Chọn hết thì bỏ phần chọn dở. Còn phím phía sau thì các âm đã chốt sau chữ vừa chọn vẫn giữ (chọn 中 cho
        // `t` của `twc` thì còn ư… cục; người dùng muốn 2026-11-15): cột trái tự dời chúng theo phím còn lại
        // ([CandidateGrid.syncKeys]), bỏ các âm của phần đã chọn.
        if (rest.isEmpty()) root?.grid?.dropSelection()
        if (effectiveMode == Mode.HAN_NOM && rest.isNotEmpty()) {
            // Chọn phần đầu: chữ nằm lại trong khung gõ, chọn tiếp phần còn lại; chọn hết mới vào ô nhập.
            chosen.add(Chosen(c, keys.substring(0, keys.length - rest.length), rest))
            acts.add(Act.CHOOSE)
        } else {
            ic.commitText(takeChosen(c.text, c.literal), 1)
            if (!secure && !c.literal) engine.select(c)
        }
        keys.clear()
        keys.append(rest)
        if (keys.isEmpty() && effectiveMode == Mode.QUOC_NGU) ic.commitText(" ", 1)
        root?.let { if (keys.isEmpty() && it.bar.expanded) it.bar.expanded = false }
        refresh()
        updateShift()
    }

    private fun onExpand(show: Boolean) {
        val r = root ?: return
        r.grid.filters = !handwriting
        if (show) {
            // Bàn phím còn hiện tới khi lưới dựng xong, rồi mới đổi sang bảng (không thấy bảng lấp dần; người dùng báo
            // 2026-11-17). Dự phòng: quá [GRID_READY_MS] mà chưa xong thì vẫn mở.
            r.prepareGrid()
            val open = { if (r.bar.expanded) r.showGrid(true) }
            r.grid.onReady = open
            mainHandler.postDelayed({ if (r.grid.onReady === open) { r.grid.onReady = null; open() } }, GRID_READY_MS)
            r.grid.show(freshCandidates(), r.bar.hiddenFor(candidates), keys.toString(), opening = true)
            prefetchLock(r)
        } else {
            r.grid.onReady = null
            // Thu bảng lên lúc đang chọn dở: phần chọn dở vẫn giữ, thanh hiện các chữ khớp nó ([barCandidates]); không
            // chọn dở thì thanh hiện lại đủ gợi ý.
            if (r.grid.narrowing || r.grid.selecting) r.bar.showCandidates(barCandidates(r), null, narrowing = r.grid.selecting)
        }
        // Các âm đã chốt còn hiện khi bảng thu lên; nền vàng thì chỉ khi bảng mở (2026-11-15).
        val labels = if (show || r.grid.selecting) r.grid.lockLabels else emptyList()
        if (labels != lockLabels || (!direct && !handwriting && keys.isNotEmpty())) {
            lockLabels = labels
            showComposing(r)
        }
        if (!show) r.showGrid(false)
    }

    // ---- Viết tay ----

    override fun onStrokes(strokes: List<FloatArray>, cell: Float) {
        val count = strokes.size
        recognizer?.recognizeLine(strokes, cell) { line ->
            written = line
            writtenStrokes = count
            candidates = lineCandidates(line).map { Candidate(it, "", 0) }
            refresh()
        }
    }

    /**
     * Gợi ý cho dòng viết tay: gợi ý đầu của từng chữ ghép lại (人 + 从 → 人从), rồi vài gợi ý khi coi cả bảng
     * là một chữ (viết 𠊛 hơi xa nhau mà bị tách thành 人㝵), rồi cách tách có số chữ khác, rồi lần lượt thay một
     * chữ bằng gợi ý thứ 2, 3… của nó.
     *
     * Nhận là một chữ thì ngay sau gợi ý đầu là cách tách ra nhiều chữ (𠊛 → 人㝵, phòng viết hai chữ sát nhau
     * bị nhận là một), rồi các dị thể và các gợi ý khác.
     */
    private fun lineCandidates(line: HandwrittenLine): List<String> {
        val chars = line.chars
        if (chars.size <= 1) {
            val list = withVariants(chars.firstOrNull()?.chars.orEmpty())
            if (line.split.isEmpty() || list.isEmpty()) return list
            return (list.take(1) + line.split + splitParts(line.split) + list.drop(1)).distinct()
        }
        val top = chars.map { it.chars[0] }
        val out = LinkedHashSet<String>()
        out.add(top.joinToString(""))
        out.addAll(line.whole.take(WHOLE_FIRST))
        if (line.split.isNotEmpty()) out.add(line.split)
        // Từng chữ đơn (人, 㝵): muốn chỉ lấy một phần đã viết.
        out.addAll(top)
        for (rank in 1 until 10) {
            for ((k, c) in chars.withIndex()) {
                val alt = c.chars.getOrNull(rank) ?: continue
                out.add(top.mapIndexed { i, t -> if (i == k) alt else t }.joinToString(""))
            }
        }
        out.addAll(line.whole.drop(WHOLE_FIRST))
        return out.toList()
    }

    /**
     * Nhóm dị thể (`assets/variants.txt`, tạo bằng `tools/handwriting/variants.py`): chữ → cả nhóm của nó.
     * Mô hình hay nhầm giữa các dị thể chỉ khác một nét (囯 / 国), nhất là chữ ít mẫu.
     */
    private val variants: Map<String, List<String>> by lazy {
        runCatching {
            assets.open("variants.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.flatMap { line ->
                    val group = line.codePoints().toArray().map { String(Character.toChars(it)) }
                    group.map { it to group }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /** Các chữ của một chuỗi (人㝵 → 人, 㝵), tính cả chữ ngoài BMP. */
    private fun splitParts(text: String): List<String> = text.codePoints().toArray().map { String(Character.toChars(it)) }

    /** Gợi ý một chữ viết tay: ngay sau gợi ý đầu là các dị thể của nó (国 → 囯 國), rồi các gợi ý khác. */
    private fun withVariants(list: List<String>): List<String> {
        val top = list.firstOrNull() ?: return list
        return (listOf(top) + variants[top].orEmpty() + list.drop(1)).distinct()
    }

    /**
     * Người dùng chọn [text] thay cho gợi ý đầu: nhớ nét là chữ đó. Viết nhiều chữ thì chỉ dạy các chữ bị
     * đoán sai, mỗi chữ với đúng nét của nó; chọn gợi ý "cả bảng một chữ" thì dạy cả bảng.
     */
    private fun learnWritten(text: String, pad: HandwritingView) {
        val r = recognizer ?: return
        val strokes = pad.cellStrokes()
        val chars = written?.chars.orEmpty()
        val picked = text.codePoints().toArray().map { String(Character.toChars(it)) }
        // Chọn cách tách khác (人㝵 thay cho 𠊛) hay một phần của nó (人): nét không ứng với chữ đó, không dạy.
        val split = written?.split.orEmpty()
        if (split.isNotEmpty() && (text == split || text in splitParts(split))) return
        if (chars.size <= 1 || written?.whole?.contains(text) == true) {
            r.learn(strokes, pad.cellSize, text)
            return
        }
        if (picked.size != chars.size) return
        for ((k, c) in chars.withIndex()) {
            if (picked[k] != c.chars[0]) r.learn(c.strokes.mapNotNull { strokes.getOrNull(it) }, pad.cellSize, picked[k])
        }
    }

    /**
     * Nét đã ẩn: các chữ (từ trái sang) đã ẩn hết nét được chốt ngay bằng gợi ý đầu của từng chữ, dù đang
     * viết tiếp chữ khác; bảng bỏ nét của chúng rồi nhận dạng lại phần còn lại. Chưa nhận dạng xong nét mới
     * nhất thì chờ.
     */
    override fun onFaded(hidden: Set<Int>): Set<Int> {
        val pad = root?.pad ?: return emptySet()
        val line = written ?: return emptySet()
        if (writtenStrokes != pad.strokeCount) return emptySet()
        val done = line.chars.takeWhile { c -> c.strokes.all { it in hidden } }
        if (done.isEmpty()) {
            // Không nhận ra chữ nào (thiếu mô hình): ẩn hết thì bỏ nét.
            return if (line.chars.isEmpty() && hidden.size == pad.strokeCount) hidden else emptySet()
        }
        currentInputConnection?.commitText(done.joinToString("") { it.chars[0] }, 1)
        writtenShown = false
        recognizer?.cancel()
        candidates = emptyList()
        written = null
        root?.let { if (it.bar.expanded) it.bar.expanded = false }
        refresh()
        return done.flatMap { it.strokes }.toSet()
    }

    private fun clearHandwriting() {
        root?.pad?.clear()
        recognizer?.cancel()
        candidates = emptyList()
        written = null
        writtenStrokes = 0
    }

    /** Đang có chữ viết dở: chốt gợi ý đầu (chưa nhận dạng xong thì bỏ). Trả về false nếu không có gì. */
    private fun commitHandwriting(): Boolean {
        if (!handwriting || root?.pad?.hasInk != true) return false
        val top = candidates.firstOrNull()
        if (top != null) pick(top) else {
            clearHandwriting()
            refresh()
        }
        return true
    }

    // ---- KeyboardView.Listener ----

    override fun onChar(text: String, literal: Boolean) = perf("onChar", "$text keys=${keys.length}") { onCharTimed(text, literal) }

    private fun onCharTimed(text: String, literal: Boolean) {
        commitHandwriting()
        val c = text.singleOrNull()
        val composable = effectiveMode != Mode.ENGLISH && c != null &&
            (c in 'a'..'z' || c in 'A'..'Z' || (c == '\'' && keys.isNotEmpty() && effectiveMode == Mode.HAN_NOM))
        if (composable) {
            // Dấu tách chữ không gõ liền hai lần.
            if (c == '\'' && (caretAt == 0 || keys[caretAt - 1] == '\'' || keys.getOrNull(caretAt) == '\'')) return
            // Đã chốt hết các âm mà gõ chữ liền sau âm chốt cuối (chưa ấn cách): chữ đó mở âm mới, không dính vào âm
            // vừa chốt (`cục` + `a` không thành `ca`). Dấu tách chỉ thêm lúc này, không hiện sẵn (2026-11-15).
            val g = root?.grid
            if (c != '\'' && telex && effectiveMode == Mode.HAN_NOM && !handwriting && tail == 0 && g != null &&
                g.lockedAll && keys.isNotEmpty() && keys.last() != '\'') keys.append('\'')
            typeKeys(text)
            return
        }
        val punctuation = if (effectiveMode == Mode.HAN_NOM && !literal) hanNomPunctuation[text] else null
        inOneEdit {
            // Đang gõ Hán Nôm mà ấn phím ký tự (dấu câu, số, ký hiệu): khung gõ ra chữ Hán Nôm rồi mới thêm ký tự (như
            // Sogou), không ra chữ Latin (người dùng muốn 2026-11-15).
            if (effectiveMode == Mode.HAN_NOM && !direct && !handwriting) commitHanNom()
            commitComposing()
            currentInputConnection?.commitText(punctuation ?: text, 1)
        }
        updateShift()
    }

    /**
     * Chốt cả khung gõ bằng chữ Hán Nôm: chọn lần lượt gợi ý đầu như phím cách chọn ([onSpace]: đang chốt âm thì là chữ
     * khớp các âm đã chốt) tới hết phím; gợi ý đầu chỉ phủ phần đầu thì chọn tiếp phần sau.
     */
    private fun commitHanNom() {
        var guard = 0
        while (keys.isNotEmpty() && guard++ < MAX_COMMIT_PICKS) {
            val all = freshCandidates()
            val shown = root?.let(::barCandidates) ?: all
            val first = shown.firstOrNull() ?: all.firstOrNull() ?: break
            val before = keys.length
            pick(first)
            if (keys.length >= before) break
        }
    }

    /**
     * Nhiều lần sửa ô nhập liền nhau (chốt chữ đang gõ rồi chèn thêm) gộp thành một: ô nhập chỉ báo thay đổi một lần.
     * Báo từng lần thì ô nhập toàn màn hình (xoay ngang) nhận hai bản cập nhật cùng một đoạn và chèn trùng chữ
     * (giữ `a` chọn à sau `m` hiện màà, ô thật chỉ có mà).
     */
    private inline fun inOneEdit(block: () -> Unit) {
        val ic = currentInputConnection
        ic?.beginBatchEdit()
        try {
            block()
        } finally {
            ic?.endBatchEdit()
        }
    }

    override fun onAlternate(text: String) {
        when (text) {
            Layouts.EMOJI -> return openEmoji()
            // Bật thì phím luôn dồn về bên trái trước (nút ở cột bên dồn sang phải), đang bật thì về bàn phím đầy đủ.
            Layouts.ONE_HAND -> return onOneHand(if (settings.oneHand == OneHand.OFF) OneHand.LEFT else OneHand.OFF)
            Layouts.SETTINGS -> return useTool(ToolId.SETTINGS)
        }
        // Chọn trong bảng giữ phím là gõ thô: chốt chữ đang gõ rồi chèn đúng ký tự đã chọn (bấm `:` ra dạng Hán, giữ ra Latin).
        inOneEdit {
            if (effectiveMode == Mode.HAN_NOM && !direct && !handwriting) commitHanNom()
            commitComposing()
            currentInputConnection?.commitText(text, 1)
        }
        updateShift()
    }

    // ---- Bảng emoji ----

    /** Mở bảng emoji; chữ đang gõ chốt trước (như chọn ký tự trong bảng giữ phím). */
    private fun openEmoji() {
        val r = root ?: return
        commitHandwriting()
        commitComposing()
        if (r.bar.expanded) r.bar.expanded = false
        r.showEmoji(true)
    }

    override fun onEmoji(text: String) {
        appConnection?.commitText(text, 1)
    }

    /** Mở ô tìm emoji: bàn phím chữ hiện lại, phím Enter thành nút tìm. */
    override fun onEmojiSearch() {
        val r = root ?: return
        dropTyping()
        r.showEmojiSearch(true)
        appConsumesInput(true)
        r.keyboard.resetToLetters()
        updateKeyboardLabels()
        updateShift()
        refresh()
    }

    override fun onEmojiSearchBack() {
        root?.showEmoji(true)
    }

    override fun onEmojiDelete() = onDelete()

    override fun onEmojiClose() {
        root?.showEmoji(false)
        updateShift()
    }

    /** Thêm phím vào chữ đang gõ. */
    private fun typeKeys(text: String) {
        // Chống treo: chuỗi phím đang gõ dài tối đa MAX_KEYS, quá thì bỏ phím (rung báo).
        if (keys.length + text.length > MAX_KEYS) {
            root?.keyboard?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        // Telex, con trỏ giữa chữ: chữ cái chèn thẳng vào chữ đang hiện, dấu tách cắt chữ làm hai.
        val c = text.singleOrNull()
        // Con trỏ ở đầu một chữ (`|mất`, vd. vừa xoá hết các âm đã chốt trước nó): chữ cái gõ vào không ghép được với
        // chữ đó thì thành chữ mới đứng trước (`chu|' mất` → chuỗi mất), không dính thành chữ thô `chumất` (người dùng
        // báo 2026-11-16).
        if (c != null && c.isLetter() && telex && tail > 0 && caretMovable) {
            val at = caretAt
            if ((at == 0 || keys[at - 1] == '\'') && keys[at] != '\'' && !keyInsertKeepsWord(c)) {
                insertKeys("$c'")
                tail += 1
                refresh()
                return
            }
        }
        val inWord = c != null && !keyInsertKeepsWord(c) && editInWord { d, at ->
            when {
                c == '\'' -> d.substring(0, at) + " " + d.substring(at) to at + 1
                c.isLetter() -> d.substring(0, at) + c + d.substring(at) to at + 1
                else -> null
            }
        }
        if (!inWord) insertKeys(text)
        refresh()
    }

    override fun onDelete() {
        // Khung kính chỉ đếm ngược (số đỏ) khi người dùng đang xoá lùi.
        root?.markDeleting()
        // Viết tay: phím xoá không xoá nét mà xoá chữ trước con trỏ; chữ đang viết dở được chốt trước,
        // nên xoá ngay chữ cuối vừa viết.
        commitHandwriting()
        // Thứ tự cố định, khi không còn hành động ghi lại ([acts], xem dưới).
        // Phía sau chỉ còn các âm đã chốt (chưa gõ thêm phím tự do), con trỏ ở cuối; người dùng muốn 2026-11-15:
        // 1. đã ấn cách sau âm chốt cuối: xoá dấu cách đó trước;
        // 2. còn chữ Hán đã chọn ở đầu khung gõ: gỡ từng chữ, chữ chọn sau cùng trước (trở lại thành phím); các âm
        //    đã chốt giữ nguyên như đang thấy;
        // 3. hết chữ đã chọn: bỏ chốt từng âm từ phải sang (thành chữ đen), hết âm đã chốt mới xoá phím; cả trong bảng
        //    thả lẫn khi bảng đã thu (người dùng muốn 2026-11-15, thay cho xoá cả âm chốt cuối khi bảng đã thu).
        val g = root?.grid
        // Hoàn tác hành động sau cùng ([acts]; người dùng muốn 2026-11-15, thay cho thứ tự cố định ở dưới): chốt âm thì
        // bỏ chốt (thành chữ đen), chọn chữ thì gỡ chữ (lưới trở lại các âm đã chốt lúc chọn, [CandidateGrid.syncKeys]),
        // gõ phím thì xoá phím như thường. Dấu cách vừa ấn sau âm chốt cuối vẫn xoá trước. Không ghi được hành động
        // (vd. bắt đầu gõ trước bản này) thì theo thứ tự cố định ở dưới.
        var keyUndo = false
        if (!direct && !handwriting && effectiveMode == Mode.HAN_NOM && tail == 0 && keys.isNotEmpty() && g != null &&
            g.lockedAll && keys.last() == '\'') {
            // Dấu cách sau âm chốt cuối: xoá ở nhánh dưới; nó là phím vừa gõ thì bỏ khỏi [acts].
            if (acts.lastOrNull() == Act.KEY) acts.removeAt(acts.lastIndex)
        } else if (!direct && !handwriting && effectiveMode == Mode.HAN_NOM && tail == 0 && keys.isNotEmpty() && g != null) {
            // Hành động đã bị bỏ bằng cách khác (‹ ở cột trái, sửa giữa chuỗi…) thì bỏ qua.
            while (acts.isNotEmpty()) {
                val stale = when (acts.last()) {
                    Act.KEY -> false
                    Act.LOCK -> !g.hasLocks
                    Act.CHOOSE -> chosen.isEmpty()
                }
                if (!stale) break
                acts.removeAt(acts.lastIndex)
            }
            when (acts.lastOrNull()) {
                Act.LOCK -> {
                    acts.removeAt(acts.lastIndex)
                    g.syncKeys(keys.toString())
                    g.unlockLastPlain()
                    refresh()
                    return
                }
                Act.CHOOSE -> {
                    acts.removeAt(acts.lastIndex)
                    val last = chosen.removeAt(chosen.lastIndex)
                    keys.insert(0, last.keys)
                    refresh()
                    return
                }
                // Phím vừa gõ: xoá như thường (bỏ qua hai nhánh âm chốt dưới).
                Act.KEY -> {
                    acts.removeAt(acts.lastIndex)
                    keyUndo = true
                }
                null -> {}
            }
        }
        if (!keyUndo &&!direct && !handwriting && effectiveMode == Mode.HAN_NOM && tail == 0 && keys.isNotEmpty() && g != null &&
            g.hasLocks && !(g.lockedAll && (keys.last() == '\'' || chosen.isNotEmpty()))) {
            g.syncKeys(keys.toString())
            g.unlockLastPlain()
            refresh()
            return
        }
        if (!keyUndo && !direct && !handwriting && effectiveMode == Mode.HAN_NOM && tail == 0 && keys.isNotEmpty() && g != null && g.lockedAll) {
            val last = chosen.lastOrNull()
            when {
                keys.last() == '\'' -> keys.deleteCharAt(keys.length - 1)
                last != null -> {
                    chosen.removeAt(chosen.lastIndex)
                    g.forgetHistory()
                    keys.insert(0, last.keys)
                }
                else -> {
                    g.syncKeys(keys.toString())
                    g.unlockLastPlain()
                }
            }
            refresh()
            return
        }
        // Con trỏ ngay sau một âm đã chốt: xoá cả âm đó để gõ âm khác vào.
        if (caretMovable && deleteLockedBefore()) {
            // Xoá hết các âm (chỉ còn dấu tách): đóng khung gõ, không kẹt ở `'`.
            dropIfNoLetters()
            refresh()
            return
        }
        val last = chosen.lastOrNull()
        if (tail > 0 && caretMovable) {
            // Con trỏ giữa khung gõ: xoá phím trước con trỏ, phần sau giữ nguyên.
            val at = caretAt
            if (at == 0) {
                // Ở đầu: gỡ chữ chọn dở cuối như khi chọn nhầm. Con trỏ nhảy về sau chữ đã chọn trước đó (để xoá
                // tiếp được), hết chữ đã chọn thì về cuối chuỗi.
                if (last == null) return
                chosen.removeAt(chosen.lastIndex)
                keys.insert(0, last.keys)
                tail = if (chosen.isEmpty()) 0 else keys.length
            } else {
                // Xoá ký tự thường: ẩn khung kính; gần tới ký tự đầu thì nó hiện lại, đếm ngược (2026-11-19).
                root?.hideLoupe()
                root?.markPlainDeleting()
                if (!editInWord { d, i -> if (i == 0) null else d.removeRange(i - 1, i) to i - 1 }) {
                    val start = keys.lastIndexOf('\'', at - 1) + 1
                    if (telex && start > 0 && engine.displayTelex(keys.substring(start, at), settings.toneStyle).isEmpty()) {
                        // Đầu một chữ (sau dấu tách, hay sau dấu chữ thô): xoá dấu tách, nối với chữ trước.
                        keys.deleteCharAt(start - 1)
                    } else {
                        val before = keys.substring(0, at)
                        val after = keys.substring(at)
                        keys.clear()
                        keys.append(if (telex) engine.backspaceTelex(before, settings.toneStyle) else before.dropLast(1))
                        keys.append(after)
                    }
                    // Xoá hết chữ mới gõ ở đầu khung gõ (`c|' mất`): bỏ cả dấu tách, không để dấu cách trước chữ sau.
                    // Còn âm đã chốt thì giữ (chỗ trống để gõ âm khác vào, [deleteLockedBefore]).
                    if (telex && lockLabels.isEmpty() && caretAt == 0 && keys.startsWith("'")) {
                        keys.deleteCharAt(0)
                        tail = keys.length
                    }
                }
            }
            dropIfNoLetters()
            refresh()
            return
        }
        if (last != null && keys.length <= last.rest.length) {
            // Chọn nhầm (chưa gõ thêm gì): bỏ chữ vừa chọn, trả lại đoạn phím của nó để chọn lại.
            chosen.removeAt(chosen.lastIndex)
            keys.insert(0, last.keys)
            refresh()
            return
        }
        if (keys.isNotEmpty()) {
            root?.hideLoupe()
            root?.markPlainDeleting()
            val next = when {
                effectiveMode == Mode.QUOC_NGU -> engine.backspace(keys.toString(), settings.toneStyle)
                telex -> engine.backspaceTelex(keys.toString(), settings.toneStyle)
                else -> keys.dropLast(1).toString()
            }
            keys.clear()
            keys.append(next)
            dropIfNoLetters()
            if (keys.isEmpty() && !direct) currentInputConnection?.commitText("", 1)
            refresh()
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty() && reopenWord(ic)) return
        if (selected.isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) else ic.commitText("", 1)
        updateShift()
    }

    /**
     * Quốc ngữ (Telex): xoá dấu cách ngay sau một chữ (đã ấn cách, hay chọn gợi ý tự thêm cách) thì mở lại chữ đó để gõ
     * tiếp dấu (`tô`, cách, xoá, `s` → tố; người dùng báo 2026-11-17: không quay lại gõ dấu được). Chữ đổi ngược ra phím
     * Telex ([telexKeysOf]) mà dựng lại không đúng chữ đó thì thôi, xoá như thường.
     */
    private fun reopenWord(ic: InputConnection): Boolean {
        if (!direct || handwriting || keys.isNotEmpty()) return false
        val before = ic.getTextBeforeCursor(MAX_WORD + 1, 0)?.toString() ?: return false
        if (!before.endsWith(' ') || before.endsWith("  ")) return false
        val body = before.dropLast(1)
        val word = body.takeLastWhile { it.isLetter() }
        if (word.isEmpty() || (word.length < body.length && !body[body.length - word.length - 1].isWhitespace())) return false
        val k = telexKeysOf(word) ?: return false
        if (composeWord(k) != word) return false
        ic.deleteSurroundingText(1, 0)
        keys.clear()
        keys.append(k)
        typed = word
        refresh()
        return true
    }

    /**
     * Phím Telex của chữ Quốc ngữ [word] (`tốn` → `toons`): dấu mũ gõ đôi chữ cái, dấu trăng / móc là `w`, đ là `dd`, thanh
     * gõ cuối chữ. `null` nếu có ký tự không đổi được.
     */
    private fun telexKeysOf(word: String): String? {
        val out = StringBuilder()
        var tone: Char? = null
        for (ch in word) {
            if (ch == 'đ' || ch == 'Đ') {
                out.append(if (ch == 'Đ') "DD" else "dd")
                continue
            }
            val parts = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)
            val base = parts[0]
            if (base !in 'a'..'z' && base !in 'A'..'Z') return null
            out.append(base)
            for (m in parts.drop(1)) {
                when (m) {
                    '̂' -> out.append(base)
                    '̆', '̛' -> out.append(if (base.isUpperCase()) 'W' else 'w')
                    '́' -> tone = 's'
                    '̀' -> tone = 'f'
                    '̉' -> tone = 'r'
                    '̃' -> tone = 'x'
                    '̣' -> tone = 'j'
                    else -> return null
                }
            }
        }
        tone?.let(out::append)
        return out.toString()
    }

    /**
     * Khung gõ Hán Nôm xoá tới không còn chữ nào (chỉ còn dấu tách, không có chữ đã chọn dở): bỏ hết, thoát chế độ
     * nhập (khung gõ đóng, như chưa gõ gì).
     */
    private fun dropIfNoLetters() {
        if (direct || chosen.isNotEmpty() || keys.isEmpty() || keys.any { it != '\'' }) return
        keys.clear()
        tail = 0
        currentInputConnection?.commitText("", 1)
    }

    override fun onDeleteWord() {
        commitHandwriting()
        if (keys.isNotEmpty() || chosen.isNotEmpty()) {
            keys.clear()
            chosen.clear()
            acts.clear()
            currentInputConnection?.commitText("", 1)
            refresh()
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val start = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
        ic.deleteSurroundingText(before.length - start, 0)
        updateShift()
    }

    override fun onEnter() {
        if (commitHandwriting()) return
        if (keys.isNotEmpty() && telex) {
            // Telex: Enter đưa ra đúng chữ trong khung gõ (Quốc ngữ đã ghép dấu, cách chữ chỗ đã ấn cách), các chữ
            // đã chọn dở ra trước; muốn chữ Hán thì cách hai lần.
            currentInputConnection?.commitText(takeChosen(hanNomComposing().trimEnd(), latin = true), 1)
            keys.clear()
            refresh()
            return
        }
        if (keys.isNotEmpty()) {
            commitComposing()
            // Quốc ngữ: chữ đã nằm trong ô nhập nên Enter làm luôn việc của Enter (xuống dòng / gửi).
            if (!direct) return
        }
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateShift()
    }

    override fun onSpace() {
        if (commitHandwriting()) return
        if (keys.isEmpty()) {
            if (effectiveMode == Mode.ENGLISH && !secure && !enWords.isEmpty) {
                val word = englishWordBefore()
                enWords.learn(word, wordsBefore(word.length).lastOrNull())
            }
            currentInputConnection?.commitText(" ", 1)
            updateShift()
            return
        }
        if (direct) {
            // Quốc ngữ: phím cách chỉ sang từ mới, không tự chọn gợi ý.
            viWords.learn(typed, wordsBefore(typed.length).lastOrNull())
            commitComposing()
            currentInputConnection?.commitText(" ", 1)
            updateShift()
            return
        }
        // Đang chọn dở ở cột trái (bảng mở hay đã thu): phím cách chốt âm đang chọn như phím cách của bảng, kể cả âm
        // cuối, để gõ tiếp các âm sau (người dùng muốn 2026-11-14).
        val r = root
        // Chốt xong thì phím cách này cũng là dấu cách tách âm (chèn dấu tách), để gõ tiếp thành âm mới; cách lần nữa
        // mới chọn chữ (người dùng muốn 2026-11-15).
        if (effectiveMode == Mode.HAN_NOM && !handwriting && tail == 0 && r != null && r.bar.expanded && r.grid.lockFromKeyboard()) {
            if (keys.isNotEmpty() && keys.last() != '\'') keys.append('\'')
            refresh()
            return
        }
        // Telex: cách một lần là tách chữ (dấu tách `'`, khung gõ hiện dấu cách); cách lần nữa mới chọn gợi ý đầu.
        // Chỉ chốt khi con trỏ ở cuối cụm và ngay trước nó đã là dấu cách (cách hai lần ở cuối). Con trỏ đã dời về trước
        // (kể cả ngay trước dấu cách cuối cụm, hay cạnh dấu cách giữa cụm) thì phím cách luôn chèn dấu cách ở con trỏ.
        // Con trỏ ở đầu cụm thì không chèn (dấu cách ở đầu không tách gì).
        if (telex && effectiveMode == Mode.HAN_NOM && (caretAt < keys.length || keys.lastOrNull() != '\'')) {
            if (caretAt > 0) typeKeys("'")
            return
        }
        when (effectiveMode) {
            // Chọn gợi ý đầu đang hiện trên thanh: đang chốt âm ở cột trái thì là chữ khớp các âm đã chốt (中央局沔南),
            // không phải gợi ý đầu của cả chuỗi phím khi chưa chốt (相應).
            Mode.HAN_NOM -> {
                val all = freshCandidates()
                val shown = root?.let(::barCandidates) ?: all
                val first = shown.firstOrNull() ?: all.firstOrNull()
                if (first != null) pick(first) else commitComposing()
            }
            else -> {
                // Gõ tắt chỉ chọn khi chạm vào gợi ý, để "an" + cách không thành một cụm khác.
                val first = candidates.firstOrNull()
                if (first != null && first.consumed == keys.length && !first.abbreviated) {
                    pick(first)
                } else {
                    commitComposing()
                    currentInputConnection?.commitText(" ", 1)
                }
            }
        }
        updateShift()
    }

    override fun onCursor(delta: Int) {
        // Đang gõ Hán Nôm: vuốt phím cách dời con trỏ trong khung gõ, không chốt chữ.
        if (caretMovable) return moveCaret(delta)
        commitHandwriting()
        commitComposing()
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(code) }
    }

    override fun onModeTap() {
        if (secure) return
        commitHandwriting()
        commitComposing()
        settings.nextKeyboard().let {
            mode = it.mode
            layout = it.layout
        }
        updateKeyboardLabels()
        refresh()
    }

    override fun onKeyFeedback(view: View) {
        if (settings.vibrate) keyVibrate(this, view, settings.vibrationMs)
        if (settings.sound) keyClick(this, settings.volume)
    }

    // ---- Tiện ích ----

    private fun updateShift() {
        val kb = root?.keyboard ?: return
        if (kb.shift == KeyboardView.Shift.LOCK) return
        val info = currentInputEditorInfo ?: return
        val caps = settings.autoCap && keys.isEmpty() &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        kb.shift = if (caps) KeyboardView.Shift.ONCE else KeyboardView.Shift.OFF
    }

    private fun openDictionary() {
        commitComposing()
        openActivity(Intent(this, DictionaryActivity::class.java))
    }

    /**
     * Mở trang của ứng dụng từ bàn phím, ở cửa sổ (task) riêng: không chen vào cửa sổ Nôm Na đang chạy nền (mở từ
     * biểu tượng), để quay lại ở trang đầu là về đúng ứng dụng đang gõ (xem [EXTRA_FROM_KEYBOARD]).
     *
     * Mỗi trang chỉ một cửa sổ như vậy (tài liệu riêng, nhận ra theo [Intent.getData]): mở lại thì dùng lại cửa sổ
     * cũ, mở trang mới từ đầu, không đẻ thêm cửa sổ trong danh sách gần đây (ra bằng nút Home thì cửa sổ còn lại).
     */
    private fun openActivity(intent: Intent) {
        intent.data = android.net.Uri.parse("nomna-keyboard:${intent.component?.className}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(EXTRA_FROM_KEYBOARD, true)
        startActivity(intent)
        requestHideSelf(0)
    }
}
