package com.hannom.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
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
    private val viWords by lazy { WordDict.get(this, WordDict.VI) }
    private val enWords by lazy { WordDict.get(this, WordDict.EN) }
    /** Bàn phím Hán Nôm kiểu Telex: [keys] là phím Telex gõ thẳng (như Unikey), không chuẩn hoá kiểu Hán Nôm riêng. */
    private val telex get() = effectiveMode == Mode.HAN_NOM && layout == Layout.TELEX

    private companion object {
        /** Ô tìm emoji: một dòng chữ, phím Enter là nút tìm (như Gboard). */
        val SEARCH_INFO = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        /**
         * Chuỗi phím đang gõ dài tối đa. Lõi chỉ ghép cả câu tới 80 phím (Telex 120, xem `MAX_SENTENCE_KEYS`);
         * dài hơn thì vẫn tra nhanh (≈ 20 ms ở 200 phím), chọn dần từng phần như pinyin.
         */
        const val MAX_KEYS = 200
        /** Viết tay nhiều chữ: số gợi ý "cả bảng là một chữ" đưa lên ngay sau gợi ý đầu. */
        const val WHOLE_FIRST = 3
        /** Đọc chừng này ký tự trước con trỏ để lấy từ đang gõ và các từ trước nó. */
        const val MAX_WORD = 64
        /**
         * Cụm Quốc ngữ có độ phổ biến từ chừng này (từ điển `vi`: 1000·log10(1 + số lần trên một tỉ từ), 4000 ≈ 10 lần
         * trên một triệu từ) thì lựa chọn giữ nguyên Quốc ngữ lên thanh gợi ý.
         */
        const val COMMON_WORD = 4000
        /** Cỡ chữ ô nhập toàn màn hình: đo trên Gboard (Realme, 2026-11-10), chữ `đ` cao 50px = 0.676 lần bản 28sp. */
        const val EXTRACT_TEXT_SP = 19f
        /** Cụm ít gặp: lựa chọn giữ nguyên Quốc ngữ đứng ở vị trí này (thường đã sang bảng gợi ý ⌄). */
        const val QUOC_NGU_AT = 8
        /** Chuỗi phím Hán Nôm dài tới chừng này thì tra gợi ý ngay (vài ms); dài hơn thì tra ở luồng nền. */
        const val SYNC_KEYS = 24
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

    override fun onCreateInputView(): View =
        InputRoot(this, this, this, ::pick, ::onExpand, this, this, this, this).also {
            root = it
            // Hệ thống có thể đã chọn chế độ toàn màn hình trước khi tạo view (xoay ngang).
            it.fullscreen = isFullscreenMode
            it.grid.narrow = { picked -> if (keys.isEmpty()) null else engine.narrow(keys.toString(), picked) }
            it.grid.narrowed = { picked -> engine.candidatesNarrowed(keys.toString(), picked) }
            it.grid.typedSyllable = {
                if (keys.isEmpty()) null else hanNomComposing().trim().substringBefore(' ').lowercase().ifEmpty { null }
            }
            // Gần như chữ nào cũng có chữ khác dấu để thu hẹp tới: luôn mở được bảng (khỏi tính cột trái mỗi phím).
            it.bar.canNarrow = { !handwriting }
            // Gợi ý từ tiếp theo (chưa gõ gì) viết hoa theo Shift / Caps Lock: bấm Shift thì đổi ngay.
            it.keyboard.onShiftChange = { if (direct && !handwriting && keys.isEmpty()) refresh() }
            it.onComposeTap = ::onComposeTap
            it.grid.onDelete = ::onDelete
            it.grid.onEnter = ::onEnter
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

    /** Chữ gợi ý của ô nhập toàn màn hình theo ngôn ngữ bàn phím đang dùng (thay cho chữ gợi ý của ứng dụng). */
    private fun extractHint(): CharSequence = hanNomText(effectiveMode.say("Gõ ở đây", "𢱗於低", "Typing here"))

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
        candidates = emptyList()
        written = null
        writtenShown = false
        recognizer?.cancel()
        root?.let { r ->
            r.applyTheme(KeyboardTheme.of(this))
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
        // Khung gõ Hán Nôm đang hiện mà người dùng chạm / giữ trong ô nhập (vùng chọn khác lúc mở khung gõ): dừng gõ.
        val anchor = composeAnchor
        if (appSelectionUiDismissed && keys.isNotEmpty() && effectiveMode == Mode.HAN_NOM && anchor != null &&
            (newSelStart != anchor.first || newSelEnd != anchor.second)
        ) {
            stopComposing(anchor, newSelStart, newSelEnd)
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
        val own = engine.candidates(k, quocNgu = false)
        val list = if (before.isEmpty()) own else {
            // Đã chọn dở (函 của `hstt`): cụm bắt đầu bằng chữ đã chọn lên trước (數 của 函數).
            val follow = engine.after(before, k)
            follow + own.filterNot { c -> follow.any { it.text == c.text && it.consumed == c.consumed } }
        }
        return if (telex) withQuocNgu(list, k) else list
    }

    /**
     * Bàn phím Hán Nôm kiểu Telex: chữ Quốc ngữ của từng đoạn phím giữa hai lần cách ([k] tách ở `'`), đúng như đã
     * ghép dấu khi gõ (`ẽ` rồi gõ tiếp `changing` vẫn là ẽchanging).
     */
    private fun telexWords(k: String): List<String> =
        k.split('\'').map { engine.displayTelex(it, settings.toneStyle).replace(" ", "") }

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
        lookupThread.execute {
            if (gen != lookupGen) return@execute
            val list = runCatching { lookup(k, before, t) }.getOrNull() ?: return@execute
            mainHandler.post {
                if (gen != lookupGen || state != hanNomState || effectiveMode != Mode.HAN_NOM || handwriting) return@post
                candidates = list
                candidatesFor = state
                root?.let(::showLookup)
            }
        }
    }

    /** Khung gõ đang hiện thêm dấu cách cuối chuỗi chưa gõ (xem [showLookup]). */
    private var trailingSpace = false

    /** Khung gõ và thanh gợi ý Hán Nôm theo [candidates] (khung gõ tách chữ theo gợi ý đầu). */
    private fun showLookup(r: InputRoot) {
        // Phần chưa chọn hiện bằng Quốc ngữ: sau chữ Latin đã chọn thì cách ra như khi chốt nguyên văn.
        val done = chosenText
        val body = afterChosen(hanNomComposing(), latin = true)
        val caret = snapCaret()
        val at = done.length + body.length - hanNomComposing().length + caret
        // Telex: dấu cách cuối chuỗi (tô đỏ) luôn hiện sẵn sau cùng, con trỏ không bao giờ ra sau nó; ấn cách thì
        // dấu cách thường hiện ở con trỏ, trước dấu cách đỏ (`tô|␣` → `tô |␣`).
        trailingSpace = telex
        val text = done + body + if (trailingSpace) " " else ""
        r.setComposing(text, chosen = done.length, caret = at)
        r.keyboard.spaceLabel = effectiveMode.spaceLabel
        r.keyboard.invalidate()
        r.bar.showCandidates(candidates, null)
        if (r.bar.expanded) r.grid.show(candidates, r.bar.hidden(candidates), keys.toString())
    }

    /**
     * Bàn phím Hán Nôm kiểu Telex: thêm lựa chọn giữ nguyên chữ Quốc ngữ đang gõ ([k] như khung gõ hiện). Cụm phổ biến
     * (từ điển `vi`, hay đã học) đứng ngay sau gợi ý đầu, trên thanh; chưa có chữ Hán nào khớp trọn chuỗi phím (chữ
     * không phải tiếng Việt như `exchanging`, chỉ có gợi ý cho phần đầu) cũng vậy. Còn lại nằm sau, trong bảng gợi ý (⌄).
     *
     * Cụm nhiều chữ (đã ấn cách) có thêm lựa chọn giữ nguyên chữ đầu, xếp theo cùng cách: chọn nó thì chữ đó nằm lại
     * trong khung gõ như chữ đã chọn dở, chọn tiếp chữ Hán cho phần sau (`telex nhanh hơn` → telex rồi 𨀈欣).
     */
    private fun withQuocNgu(list: List<Candidate>, k: String): List<Candidate> {
        val out = list.toMutableList()
        val words = telexWords(k)
        val whole = list.firstOrNull()?.let { it.consumed >= k.trimEnd('\'').length } == true
        addLiteral(out, words.joinToString(" ").trim(), k.length, whole)
        val first = k.substringBefore('\'')
        val head = words[0].trim()
        if (head.isNotEmpty() && words.count { it.isNotBlank() } > 1) {
            addLiteral(out, head, first.length, list.any { it.consumed >= first.length })
        }
        return out
    }

    /** Thêm lựa chọn giữ nguyên Quốc ngữ [text] cho [consumed] phím đầu vào [out] (xem [withQuocNgu]). */
    private fun addLiteral(out: MutableList<Candidate>, text: String, consumed: Int, covered: Boolean) {
        if (text.isEmpty() || out.any { it.text == text }) return
        val common = (viWords.frequency(text.trim()) ?: -1) >= COMMON_WORD
        val at = if (common || !covered) 1 else QUOC_NGU_AT
        out.add(at.coerceAtMost(out.size), Candidate(text, text, consumed, literal = true))
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
            if (r.bar.expanded) r.grid.show(candidates, r.bar.hidden(candidates), keys.toString())
            return
        }
        if (keys.isEmpty()) {
            if (appSelectionUiDismissed) {
                appSelectionUiDismissed = false
                composeAnchor = null
                // Khung gõ đóng: con trỏ của ô nhập hiện lại (đang tìm emoji thì ô tìm vẫn giữ việc này).
                if (r.emojiSearch.active != true) appConsumesInput(false)
            }
            if (direct) showTyped("")
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
            if (keys.length <= SYNC_KEYS) {
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
        // Con trỏ của ô nhập cũng ẩn khi khung gõ đang hiện (khung gõ có con trỏ riêng), như lúc tìm emoji.
        appConsumesInput(true)
        if (anchor == null || anchor.first != anchor.second) return
        ic.beginBatchEdit()
        ic.setComposingText(" ", 1)
        ic.setComposingText("", 1)
        ic.finishComposingText()
        ic.endBatchEdit()
    }

    /**
     * Người dùng chạm / giữ trong ô nhập khi khung gõ đang hiện: dừng gõ ngay. Chỉ các chữ đã chọn dở trong khung gõ vào
     * đúng chỗ đang gõ trước đó [anchor]; phần Quốc ngữ chưa chọn bỏ đi, không đưa chữ Latin vào ô nhập. Vùng chọn /
     * con trỏ mới của người dùng giữ nguyên (dời theo chữ vừa thêm), không thay mất đoạn người dùng vừa chọn.
     */
    private fun stopComposing(anchor: Pair<Int, Int>, newStart: Int, newEnd: Int) {
        val ic = appConnection ?: return
        val text = takeChosen("", latin = false)
        keys.clear()
        tail = 0
        if (text.isNotEmpty()) {
            val at = anchor.second
            val shift = { p: Int -> if (p >= at) p + text.length else p }
            ic.beginBatchEdit()
            ic.setSelection(at, at)
            ic.commitText(text, 1)
            ic.setSelection(shift(newStart), shift(newEnd))
            ic.endBatchEdit()
        }
        refresh()
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
    private fun caretStops(): List<Pair<Int, Int>> {
        val k = keys.toString()
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
                before = head + engine.displayTelex(seg, settings.toneStyle).replace(" ", "")
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
        val shown = { s: String -> engine.displayTelex(s, settings.toneStyle).replace(" ", "") }
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

    /** Đưa con trỏ về chỗ dừng gần nhất phía sau (vừa gõ giữa một chữ thì con trỏ sang cuối chữ); trả vị trí hiện. */
    private fun snapCaret(): Int {
        if (tail <= 0) return hanNomComposing().length
        val stops = caretStops()
        val stop = stops.firstOrNull { it.first >= caretAt } ?: stops.last()
        tail = keys.length - stop.first
        return stop.second
    }

    /** Chạm vào khung gõ tại ký tự thứ [offset] (tính cả chữ đã chọn dở ở đầu): dời con trỏ tới chỗ dừng gần nhất. */
    private fun onComposeTap(offset: Int) {
        if (!caretMovable) return
        val r = root ?: return
        canonicalizeForCaret()
        val body = hanNomComposing()
        val start = r.composingText.length - body.length - if (trailingSpace) 1 else 0
        val stops = caretStops()
        val at = offset - start
        val stop = stops.minByOrNull { kotlin.math.abs(it.second - at) } ?: return
        tail = keys.length - stop.first
        showLookup(r)
    }

    /** Vuốt phím cách khi đang gõ Hán Nôm: dời con trỏ [delta] chỗ dừng. */
    private fun moveCaret(delta: Int) {
        val r = root ?: return
        canonicalizeForCaret()
        val stops = caretStops()
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
        keys.insert(caretAt, text)
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
        chosen.clear()
        return text
    }

    private fun pick(c: Candidate) {
        val ic = currentInputConnection ?: return
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
        if (effectiveMode == Mode.HAN_NOM && rest.isNotEmpty()) {
            // Chọn phần đầu: chữ nằm lại trong khung gõ, chọn tiếp phần còn lại; chọn hết mới vào ô nhập.
            chosen.add(Chosen(c, keys.substring(0, keys.length - rest.length), rest))
        } else {
            // Cả cụm của lượt gõ này (chọn từng phần `tphn` → 城, 舖, 河內, hay cụm ghép tự động): học để lần sau
            // gõ đúng chuỗi phím ấy cụm đứng đầu.
            val parts = chosen.map { it.candidate } + c
            val whole = if (!secure && parts.none { it.literal } && (parts.size > 1 || c.joined)) {
                Triple(
                    chosen.joinToString("") { it.keys } + keys.substring(0, c.consumed.coerceAtMost(keys.length)),
                    parts.joinToString("") { it.text },
                    parts.joinToString(" ") { it.reading },
                )
            } else null
            ic.commitText(takeChosen(c.text, c.literal), 1)
            if (!secure && !c.literal) engine.select(c)
            whole?.let { (k, text, reading) -> engine.learn(k, text, reading) }
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
        if (show) r.grid.show(freshCandidates(), r.bar.hidden(candidates), keys.toString())
        r.showGrid(show)
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

    override fun onChar(text: String, literal: Boolean) {
        commitHandwriting()
        val c = text.singleOrNull()
        val composable = effectiveMode != Mode.ENGLISH && c != null &&
            (c in 'a'..'z' || c in 'A'..'Z' || (c == '\'' && keys.isNotEmpty() && effectiveMode == Mode.HAN_NOM))
        if (composable) {
            // Dấu tách chữ không gõ liền hai lần.
            if (c == '\'' && (caretAt == 0 || keys[caretAt - 1] == '\'' || keys.getOrNull(caretAt) == '\'')) return
            typeKeys(text)
            return
        }
        val punctuation = if (effectiveMode == Mode.HAN_NOM && !literal) hanNomPunctuation[text] else null
        inOneEdit {
            if (punctuation != null && keys.isNotEmpty() && freshCandidates().isNotEmpty()) {
                // Dấu câu chốt gợi ý đầu rồi mới thêm dấu câu (như Sogou).
                pick(candidates.first())
            }
            commitComposing()
            currentInputConnection?.commitText(punctuation ?: text, 1)
        }
        updateShift()
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
        // Viết tay: phím xoá không xoá nét mà xoá chữ trước con trỏ; chữ đang viết dở được chốt trước,
        // nên xoá ngay chữ cuối vừa viết.
        commitHandwriting()
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
            } else if (!editInWord { d, i -> if (i == 0) null else d.removeRange(i - 1, i) to i - 1 }) {
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
        if (selected.isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) else ic.commitText("", 1)
        updateShift()
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
        // Telex: cách một lần là tách chữ (dấu tách `'`, khung gõ hiện dấu cách); cách lần nữa mới chọn gợi ý đầu.
        // Chỉ chốt khi con trỏ ở cuối cụm và ngay trước nó đã là dấu cách (cách hai lần ở cuối). Con trỏ đã dời về trước
        // (kể cả ngay trước dấu cách cuối cụm, hay cạnh dấu cách giữa cụm) thì phím cách luôn chèn dấu cách ở con trỏ.
        // Con trỏ ở đầu cụm thì không chèn (dấu cách ở đầu không tách gì).
        if (telex && effectiveMode == Mode.HAN_NOM && (caretAt < keys.length || keys.lastOrNull() != '\'')) {
            if (caretAt > 0) typeKeys("'")
            return
        }
        when (effectiveMode) {
            Mode.HAN_NOM -> if (freshCandidates().isNotEmpty()) pick(candidates.first()) else commitComposing()
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
        if (settings.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (settings.sound) (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEY_CLICK)
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
