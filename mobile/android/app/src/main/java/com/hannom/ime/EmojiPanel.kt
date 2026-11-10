package com.hannom.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bảng emoji thay chỗ thanh gợi ý và bàn phím, kiểu Gboard: hàng trên là nút quay lại rồi một dải cuộn ngang gồm
 * nút tìm ([EmojiSearchView]) và các nhóm, giữa là
 * emoji cuộn liền theo nhóm (cuộn tới nhóm nào thì nhóm đó sáng), hàng dưới là ABC, emoji, biểu tượng chữ
 * `:-)` và phím xoá. Dữ liệu ở `assets/emoji.txt`; emoji máy không vẽ được thì bỏ.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onEmoji(text: String)
        fun onEmojiDelete()
        fun onEmojiClose()
        /** Bấm nút tìm: mở ô tìm emoji trên bàn phím. */
        fun onEmojiSearch()
        /** Nút quay lại của ô tìm: về bảng emoji. */
        fun onEmojiSearchBack()
    }

    var theme = KeyboardTheme.of(context)
    private val settings = Settings(context)
    private val handler = Handler(Looper.getMainLooper())

    private val back = ImageView(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Quay lại bàn phím"
        setOnClickListener { listener.onEmojiClose() }
    }
    private val tabs = Tabs(context) { scrollToGroup(it) }
    private val grid = Grid(context, ::pick, ::pickTone, ::shown, ::tonesOf)
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(grid)
        setOnScrollChangeListener { _, _, y, _, oldY ->
            // Đang cuộn tới nhóm vừa bấm: vòng tô đã trượt thẳng tới đó, không chạy qua các nhóm ở giữa.
            if (!emoticons && android.os.SystemClock.uptimeMillis() >= ignoreScrollUntil) {
                val g = grid.groupAt(y)
                if (g != tabs.current) {
                    tabs.select(g)
                    revealTab(g)
                }
            }
            if (y != oldY) grid.closeTones()
            onScrolled(y, oldY)
        }
    }
    private val abc = label("ABC") { listener.onEmojiClose() }
    private val emojiTab = icon(R.drawable.ic_emoji, "Emoji") { showEmoji() }
    private val emoticonTab = label(":-)") { showEmoticons() }
    private val delete = icon(R.drawable.ic_backspace, "Xoá") {}.apply { setOnTouchListener(DeleteRepeat()) }

    private var emoticons = false

    private val landscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val searchIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_search)
        scaleType = ImageView.ScaleType.CENTER
    }
    /** Bàn phím đang dùng: nhãn nút tìm theo nó. */
    var style = KeyboardStyle.VIETNAMESE
        set(v) {
            field = v
            searchLabel.text = context.hanNomText(v.say(SEARCH_VI, SEARCH_NOM, SEARCH_EN))
            grid.style = v
            // Tên nhóm `:-)` chưa có chữ Hán Nôm thì tạm hiện tiếng Việt (PRD F6).
            for ((label, names) in emoticonLabels.zip(EMOTICON_NAMES)) {
                val (vi, nom, en) = names
                label.text = context.hanNomText(v.say(vi, nom.ifEmpty { vi }, en))
            }
        }
    private val searchLabel = TextView(context).apply {
        text = SEARCH_VI
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        typeface = context.robotoTypeface()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }
    /** Nút tìm emoji (viên trắng có kính lúp), như Gboard. */
    private val searchButton = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Tìm biểu tượng cảm xúc"
        addView(searchIcon, LayoutParams(context.dpi(24), LayoutParams.MATCH_PARENT).apply { leftMargin = context.dpi(4) })
        addView(searchLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { leftMargin = context.dpi(9) })
        setOnClickListener { listener.onEmojiSearch() }
    }
    /** Bề rộng (px) nút tìm, khoảng sau nó, mỗi nút nhóm ([sizeStrip]). */
    private var searchW = context.dpi(SEARCH_WIDTH)
    private var searchGap = context.dpi(SEARCH_GAP)
    private var tabW = context.dpi(TAB_WIDTH)
    /** Khoảng trống đầu dải giữ chỗ cho nút tìm. */
    private val searchSpacer = View(context)
    /**
     * Màn hình ngang: lề trái / phải (px) khi cửa sổ bàn phím đè lên tai thỏ, bằng lề phím chữ
     * ([KeyboardView.insetLeft], [KeyboardView.insetRight]). Bàn phím đặt trước khi mở bảng.
     */
    private var insetLeft = 0
    private var insetRight = 0

    fun setSideInsets(left: Int, right: Int) {
        insetLeft = left
        insetRight = right
    }
    /**
     * Dải cuộn ngang ở hàng trên, như Gboard: nút tìm rồi các nhóm, cắt thẳng ở hai mép (không mờ). Kéo dải thì nút
     * tìm thu dần thành viên kính lúp rồi trôi khuất qua mép trái ([placeSearch]).
     */
    private val stripScroll = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Bọc trong LinearLayout: HorizontalScrollView đo con với bề rộng không giới hạn, View trơn sẽ ra rộng 0.
        // Khoảng trống đầu hàng giữ chỗ cho nút tìm (nút tìm nằm đè lên, tự đổi chỗ và bề rộng khi cuộn).
        val row = LinearLayout(context).apply {
            addView(searchSpacer, LayoutParams(searchW + searchGap, 1))
            addView(tabs, LayoutParams(tabW * TAB_ICONS.size, LayoutParams.MATCH_PARENT))
            // Chừa chút cuối hàng để cuộn hết thì nút tìm đã khuất hẳn.
            setPadding(0, 0, context.dpi(8), 0)
        }
        addView(android.widget.FrameLayout(context).apply {
            addView(row, android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            addView(searchButton, android.widget.FrameLayout.LayoutParams(
                searchW, context.dpi(34), Gravity.CENTER_VERTICAL or Gravity.START,
            ))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnScrollChangeListener { _, x, _, _, _ -> placeSearch(x) }
        addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) post { sizeStrip() } }
    }

    /**
     * Bề rộng nút tìm, khoảng sau nó và mỗi nút nhóm. Màn hình dọc cố định (đo trên Gboard AVD 411 dp). Màn hình ngang
     * (đo trên điện thoại): nút tìm chiếm [LANDSCAPE_SEARCH_SHARE] dải, các nút nhóm chia đều phần còn lại sao cho vừa
     * [LANDSCAPE_TABS] nút (Gboard: nút tìm 179 dp, cách 9 dp, mỗi nhóm 61 dp trên dải 619 dp).
     */
    private fun sizeStrip() {
        val stripW = stripScroll.width
        if (landscape && stripW > 0) {
            searchW = (stripW * LANDSCAPE_SEARCH_SHARE).toInt()
            searchGap = context.dpi(LANDSCAPE_SEARCH_GAP)
            tabW = (stripW - searchW - searchGap) / LANDSCAPE_TABS
        } else {
            searchW = context.dpi(SEARCH_WIDTH)
            searchGap = context.dpi(SEARCH_GAP)
            tabW = context.dpi(TAB_WIDTH)
        }
        val spacer = searchSpacer.layoutParams
        val tabsLp = tabs.layoutParams
        if (spacer.width == searchW + searchGap && tabsLp.width == tabW * TAB_ICONS.size) return
        spacer.width = searchW + searchGap
        tabsLp.width = tabW * TAB_ICONS.size
        searchButton.layoutParams.width = -1 // để placeSearch đặt lại
        placeSearch(stripScroll.scrollX)
        searchSpacer.requestLayout()
        if (!emoticons) revealTab(tabs.current)
    }
    /** Trang `:-)`: nút "gần đây" (icon đồng hồ trong vòng tròn khi chọn), như nút gần đây của trang emoji. */
    private val emoticonRecent = ImageView(context).apply {
        setImageResource(R.drawable.ic_emoji_recent)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = "Gần đây"
        setOnClickListener {
            showEmoticonPage(0)
            revealEmoticonTab(this)
        }
    }
    /** Trang `:-)`: tên các nhóm (theo [EMOTICON_NAMES]), chạm để mở nhóm. */
    private val emoticonLabels = EMOTICON_NAMES.mapIndexed { i, names ->
        TextView(context).apply {
            text = names.first
            gravity = Gravity.CENTER
            isSingleLine = true
            typeface = context.robotoTypeface()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(context.dpi(8), 0, context.dpi(8), 0)
            setOnClickListener {
                showEmoticonPage(i + 1)
                revealEmoticonTab(this)
            }
        }
    }
    /**
     * Hàng trên của trang `:-)`, đo trên Gboard: nút gần đây rồi tên các nhóm, cuộn ngang, không có nút tìm. Nhóm đang
     * mở có viên nền màu nhấn nhạt cao 33 dp, chữ màu nhấn; chạm nhóm bị cắt ở mép thì dải cuộn cho nó hiện đủ
     * ([revealEmoticonTab]).
     */
    private val emoticonStrip = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        visibility = View.GONE
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(emoticonRecent, LayoutParams(context.dpi(34), context.dpi(34)))
            for (label in emoticonLabels) {
                addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, context.dpi(33)).apply {
                    leftMargin = context.dpi(2)
                    rightMargin = context.dpi(2)
                })
            }
            setPadding(0, 0, context.dpi(8), 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val topRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(back, LayoutParams(context.dpi(EmojiSearchView.BACK_SIZE), context.dpi(EmojiSearchView.BACK_SIZE)))
        addView(stripScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(emoticonStrip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }
    /** Trang `:-)` đang mở: 0 là gần đây, 1… là nhóm thứ tự trong [EMOTICON_NAMES]. */
    private var emoticonPage = 0
    /** Chiều cao bảng ở trang emoji (bàn phím đặt khi mở); trang `:-)` cao hơn [EMOTICON_EXTRA]. */
    private var baseHeight = 0

    /**
     * Như Gboard: cuộn lưới làm sáng một nhóm mà icon của nó bị khuất hay bị cắt ở mép dải thì dải tự cuộn mượt cho
     * nó hiện đủ. Sang phải thì cuộn ít nhất tới chỗ nút tìm vừa khuất hẳn (icon nhóm dồn về mép trái; đo trên Gboard:
     * chọn "Du lịch" thì dải nhảy đúng một nút tìm + khoảng cách).
     */
    private fun revealTab(i: Int) {
        val start = searchW + searchGap
        val l = start + i * tabW
        val r = l + tabW
        val x = stripScroll.scrollX
        val target = when {
            r > x + stripScroll.width -> maxOf(start, r - stripScroll.width)
            l < x -> l
            else -> return
        }
        stripScroll.smoothScrollTo(target, 0)
    }

    /**
     * Trang `:-)`, như Gboard (đo trên điện thoại): chạm một nhóm bị cắt ở mép thì dải cuộn vừa đủ cho nó hiện trọn,
     * cắt bên trái thì viên dồn sát mép trái dải, cắt bên phải thì sát mép phải.
     */
    private fun revealEmoticonTab(v: View) {
        val x = emoticonStrip.scrollX
        val target = when {
            v.left < x -> v.left
            v.right > x + emoticonStrip.width -> v.right - emoticonStrip.width
            else -> return
        }
        emoticonStrip.smoothScrollTo(target, 0)
    }

    /**
     * Đặt nút tìm khi dải cuộn tới [x] (đo trên Gboard): mép phải nút đi cùng dải, mép trái đứng yên ở mép dải nên
     * nút thu dần thành viên tròn kính lúp; thu hết rồi thì cả viên trôi theo dải, khuất qua mép trái.
     */
    private fun placeSearch(x: Int) {
        val lp = searchButton.layoutParams as android.widget.FrameLayout.LayoutParams
        val h = lp.height
        val full = searchW
        val left = minOf(x, full - h)
        val w = full - left
        // Chữ mờ hẳn khi nút mới thu được nửa đường.
        searchLabel.alpha = ((w - h).toFloat() / (full - h) * 2 - 1).coerceIn(0f, 1f)
        if (lp.leftMargin == left && lp.width == w) return
        lp.leftMargin = left
        lp.width = w
        searchButton.requestLayout()
    }
    private val bottomRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in listOf(abc, emojiTab, emoticonTab, delete)) addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }
    private val bottomHeight = context.dpi(48)
    private var bottomShown = true
    private var lastScrollAt = 0L
    /** Cuộn do bấm nút nhóm thì không thu hàng dưới, tới thời điểm này. */
    private var ignoreScrollUntil = 0L

    /** Phần dưới hàng trên: lưới và hàng dưới. Màn hình ngang thì lề hai bên (tai thỏ) chỉ là nền, vuốt không cuộn. */
    private val body = android.widget.FrameLayout(context)

    init {
        orientation = VERTICAL
        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(44)))
        // Hàng dưới nổi đè lên lưới và trượt xuống bằng translationY: khung cuộn không đổi cỡ nên không giật, không
        // bị ScrollView kẹp lại vị trí cuộn ở cuối danh sách. Lưới có đệm dưới bằng hàng dưới để hàng cuối không bị che.
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, bottomHeight)
        body.addView(scroll, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(bottomRow, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, bottomHeight, Gravity.BOTTOM))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        grid.bottomInset = bottomHeight
    }

    /**
     * Như Gboard: vuốt nhanh xuống cuối danh sách thì hàng dưới thu lại, vuốt nhanh ngược lại hoặc về đầu thì hiện ra.
     * Trang `:-)` thì hàng dưới luôn hiện.
     */
    private fun onScrolled(y: Int, oldY: Int) {
        if (emoticons) return
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = (now - lastScrollAt).coerceAtLeast(1)
        lastScrollAt = now
        if (y <= 0) return showBottom(true)
        if (now < ignoreScrollUntil) return
        val speed = (y - oldY).toFloat() / elapsed
        val fast = context.dp(FAST_SCROLL)
        if (speed > fast) showBottom(false) else if (speed < -fast) showBottom(true)
    }

    private fun showBottom(show: Boolean, animate: Boolean = true) {
        val target = if (show) 0f else bottomHeight.toFloat()
        if (show == bottomShown && (animate || bottomRow.translationY == target)) return
        bottomShown = show
        grid.bottomInset = if (show) bottomHeight else 0
        bottomRow.animate().cancel()
        if (animate) bottomRow.animate().translationY(target).setDuration(150).start()
        else bottomRow.translationY = target
    }

    /**
     * Màn hình ngang (đo trên Gboard điện thoại): cả hàng trên, lưới lẫn hàng dưới lùi vào mỗi bên lề phím chữ của
     * bên đó ([insetLeft], [insetRight]: tránh tai thỏ, bên có thanh điều hướng thì 0) + [LANDSCAPE_EDGE] dp; nền bảng
     * vẫn trải hết bề ngang.
     */
    private fun styleForOrientation() {
        // Hàng trên cao 44 dp cả hai chiều (người dùng muốn ngang thoáng như dọc, không thấp như Gboard ngang).
        topRow.layoutParams.height = context.dpi(44)
        val edgeL = if (landscape) insetLeft + context.dpi(LANDSCAPE_EDGE) else 0
        val edgeR = if (landscape) insetRight + context.dpi(LANDSCAPE_EDGE) else 0
        topRow.setPadding(edgeL, 0, edgeR, 0)
        // Lề là lề ngoài của khung cuộn (không phải đệm trong): vuốt ở lề không cuộn lưới.
        (scroll.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            leftMargin = edgeL
            rightMargin = edgeR
        }
        scroll.requestLayout()
        (bottomRow.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            leftMargin = edgeL
            rightMargin = edgeR
        }
        bottomRow.requestLayout()
        // Nút quay lại là hình tròn 34 dp (đo trên Gboard), cách mép 8 dp; nút tìm cách nó đúng bằng khoảng đó.
        val gap = context.dpi(8)
        (back.layoutParams as LayoutParams).leftMargin = gap
        (stripScroll.layoutParams as LayoutParams).leftMargin = gap
        (emoticonStrip.layoutParams as LayoutParams).leftMargin = gap
        back.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(theme.key)
        }
        searchButton.layoutParams.height = context.dpi(34)
        sizeStrip()
        placeSearch(stripScroll.scrollX)
        topRow.requestLayout()
    }

    private fun label(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { onClick() }
    }

    private fun icon(res: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        setOnClickListener { onClick() }
    }

    fun applyTheme() {
        setBackgroundColor(theme.background)
        // Như Gboard: giao diện sáng thì vùng emoji nền trắng (màu phím), nút quay lại trong ô tròn trắng.
        val light = android.graphics.Color.luminance(theme.background) > 0.5f
        scroll.setBackgroundColor(if (light) theme.key else theme.background)
        body.setBackgroundColor(if (light) theme.key else theme.background)
        bottomRow.setBackgroundColor(theme.background)
        styleForOrientation()
        back.setColorFilter(theme.text)
        searchButton.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = context.dp(17f)
            setColor(theme.key)
        }
        searchIcon.setColorFilter(theme.text)
        searchLabel.setTextColor(theme.hintText)
        abc.setTextColor(theme.text)
        delete.setColorFilter(theme.text)
        tabs.theme = theme
        grid.theme = theme
        markBottom()
        markEmoticonTabs()
    }

    /** Tô nút trang `:-)` đang mở: viên / vòng nền màu nhấn nhạt, chữ / icon màu nhấn (như Gboard). */
    private fun markEmoticonTabs() {
        val fill = theme.accent and 0x00FFFFFF or 0x40000000
        emoticonRecent.background = if (emoticonPage == 0) android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
        } else null
        emoticonRecent.setColorFilter(if (emoticonPage == 0) theme.accent else theme.text)
        for ((i, label) in emoticonLabels.withIndex()) {
            val on = emoticonPage == i + 1
            label.setTextColor(if (on) theme.accent else theme.text)
            label.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = context.dp(17f)
                setColor(fill)
            } else null
        }
    }

    /** Nhãn nút về bàn phím: bàn phím Hán Nôm là `𢮿徠`, còn lại `ABC`. */
    fun setBackLabel(hanNom: Boolean) {
        abc.text = context.hanNomText(if (hanNom) "𢮿徠" else "ABC")
    }

    /** Mở bảng (luôn ở trang emoji, cuộn lên đầu). Bàn phím đặt chiều cao trang emoji trước khi gọi. */
    fun open() {
        baseHeight = layoutParams.height
        styleForOrientation()
        showBottom(true, animate = false)
        showEmoji()
        scroll.scrollTo(0, 0)
        stripScroll.scrollTo(0, 0)
        placeSearch(0)
        tabs.select(0, animate = false)
    }

    /** Kết quả tìm [query] đem hiện (theo màu da, giới tính chọn lần trước); chưa gõ gì thì là các emoji gần đây. */
    fun searchResults(query: String): List<String> =
        if (query.isBlank()) settings.recentEmoji else EmojiIndex.search(context, query).map { shown(it, false) }

    /** Bảng giữ ô của một kết quả tìm (một bản bất kỳ của emoji); null nếu không có bản khác. */
    fun searchTones(item: String) = tonesOf(item)

    /** Chọn một kết quả tìm: chèn và ghi vào gần đây như chọn trong bảng. */
    fun pickSearchResult(text: String) {
        emoticons = false
        insert(text)
    }

    private fun showEmoji() {
        val wasEmoticons = emoticons
        emoticons = false
        stripScroll.visibility = View.VISIBLE
        emoticonStrip.visibility = View.GONE
        setPanelHeight(baseHeight)
        grid.landscape = landscape
        grid.emoticons = false
        grid.setGroups(emojiGroups(), columns = if (landscape) 8 else portraitColumns())
        if (wasEmoticons) {
            scroll.scrollTo(0, 0)
            showBottom(true)
        }
        markBottom()
    }

    /** Đổi chiều cao bảng (trang `:-)` cao hơn trang emoji, mở rộng lên trên như Gboard). */
    private fun setPanelHeight(h: Int) {
        val lp = layoutParams ?: return
        if (h <= 0 || lp.height == h) return
        lp.height = h
        requestLayout()
    }

    /**
     * Số cột màn hình dọc theo bề rộng, như Gboard (đo: máy 354 dp ra 8 cột, AVD 411 dp ra 9 cột, tức mỗi cột chừng
     * 41–44 dp): mỗi cột ít nhất [MIN_CELL] dp.
     */
    private fun portraitColumns() = (resources.configuration.screenWidthDp / MIN_CELL).coerceAtLeast(6)

    /**
     * Trang biểu tượng chữ `:-)`, như Gboard: cao hơn trang emoji [EMOTICON_EXTRA] dp (màn hình dọc), mở nhóm gần
     * đây (chưa dùng gì thì nhóm đầu, "Cổ điển").
     */
    private fun showEmoticons() {
        if (emoticons) return
        emoticons = true
        stripScroll.visibility = View.GONE
        emoticonStrip.visibility = View.VISIBLE
        emoticonStrip.scrollTo(0, 0)
        setPanelHeight(baseHeight + if (landscape) 0 else context.dpi(EMOTICON_EXTRA))
        grid.landscape = landscape
        grid.emoticons = true
        showEmoticonPage(if (settings.recentEmoticons.isEmpty()) 1 else 0)
        markBottom()
    }

    /** Mở trang [page] của `:-)` (0 gần đây, 1… các nhóm): mỗi nhóm một trang riêng, lưới 4 cột, không tiêu đề. */
    private fun showEmoticonPage(page: Int) {
        emoticonPage = page
        val items = if (page == 0) settings.recentEmoticons else emoticonData(context)[page - 1]
        grid.setGroups(listOf(Group("", items)), columns = EMOTICON_COLUMNS)
        scroll.scrollTo(0, 0)
        showBottom(true)
        markEmoticonTabs()
    }

    /** Nút trang đang mở ở hàng dưới: viên đặc màu nhấn, icon / chữ màu trên nền nhấn (như Gboard). */
    private fun markBottom() {
        fun mark(v: View, on: Boolean) {
            v.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = context.dp(20f)
                setColor(theme.accent)
            }.let { android.graphics.drawable.InsetDrawable(it, context.dpi(10), context.dpi(4), context.dpi(10), context.dpi(4)) } else null
        }
        mark(emojiTab, !emoticons)
        mark(emoticonTab, emoticons)
        emojiTab.setColorFilter(if (emoticons) theme.text else theme.onAccent)
        emoticonTab.setTextColor(if (emoticons) theme.onAccent else theme.text)
    }

    /** Các nhóm emoji; nhóm "gần đây" đứng đầu khi đã dùng emoji. */
    private fun emojiGroups(): List<Group> {
        val recent = settings.recentEmoji
        return (if (recent.isEmpty()) emptyList() else listOf(Group(RECENT, recent))) + data(context).groups
    }

    private fun scrollToGroup(tab: Int) {
        val title = if (tab == 0) RECENT else TAB_GROUPS[tab - 1]
        val y = grid.groupTop(title) ?: 0
        ignoreScrollUntil = android.os.SystemClock.uptimeMillis() + 400
        scroll.smoothScrollTo(0, y)
        tabs.select(tab)
        revealTab(tab)
    }

    /** Chạm ô [item]: chèn đúng emoji ô đang hiện. */
    private fun pick(item: String, recent: Boolean) = insert(if (emoticons) item else shown(item, recent))

    /**
     * Chọn [variant] trong bảng giữ ô: chèn, và lấy giới tính + màu da của nó làm lựa chọn chung cho mọi ô
     * (trừ các ô gần đây, giữ đúng bản đã dùng).
     */
    private fun pickTone(variant: String, recent: Boolean) {
        val rows = tonesOf(variant)
        // Không đổi lựa chọn chung khi chọn trong ô gần đây (cũng như trong khung tìm emoji, xem [pickSearchResult]),
        // hay khi là emoji hai người (Gboard: ô vẫn vàng, bản đã chọn chỉ vào gần đây).
        if (rows != null && !recent && !EmojiPair.isPair(rows)) {
            val r = rows.indexOfFirst { variant in it }
            settings.emojiTone = rows[r].indexOf(variant)
            // Hàng một bản (chỉ có màu da) không nói gì về giới tính: giữ giới tính cũ.
            if (rows.size > 1) settings.emojiGender = genderOf(rows[r][0])
        }
        grid.invalidate()
        insert(variant)
    }

    /** Chèn [text] và ghi vào gần đây (emoji hay biểu tượng chữ tuỳ trang); trang đang xem không đổi ngay. */
    private fun insert(text: String) {
        listener.onEmoji(text)
        if (emoticons) {
            settings.recentEmoticons = (listOf(text) + settings.recentEmoticons.filter { it != text }).take(MAX_RECENT)
        } else {
            settings.recentEmoji = (listOf(text) + settings.recentEmoji.filter { it != text }).take(MAX_RECENT)
        }
    }

    /**
     * Emoji hiện trong ô [item]. Ô gần đây: đúng bản đã dùng. Ô khác: bản theo giới tính và màu da chọn lần
     * trước (ô không có giới tính đó thì bản trung tính; không có màu da thì bản gốc). Emoji hai người luôn bản gốc.
     */
    private fun shown(item: String, recent: Boolean): String {
        if (recent) return item
        val rows = tonesOf(item) ?: return item
        if (EmojiPair.isPair(rows)) return item
        val gender = settings.emojiGender
        val row = rows.firstOrNull { it[0].codePoints().noneMatch { c -> c == RIGHT } && genderOf(it[0]) == gender } ?: rows[0]
        return row.getOrNull(settings.emojiTone) ?: row[0]
    }

    /**
     * Bảng giữ ô chứa [item] (gốc hay một bản của nó): mỗi hàng một bản (giới tính, hướng), trong hàng là bản
     * đó rồi 5 màu da. Emoji không có bản nào khác thì null.
     */
    private fun tonesOf(item: String): List<List<String>>? = data(context).let { d -> d.tones[d.baseOf[item] ?: item] }

    /** Giữ phím xoá thì xoá liên tục, như bàn phím. */
    private inner class DeleteRepeat : OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() {
                listener.onEmojiDelete()
                handler.postDelayed(this, 50)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    listener.onEmojiDelete()
                    handler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            return true
        }
    }

    class Group(val title: String, val items: List<String>)

    /**
     * Hàng nhóm như Gboard: icon Material đơn sắc màu chữ (18 dp); nhóm đang xem có nền tròn 34 dp và icon màu nhấn.
     */
    private class Tabs(context: Context, private val onTab: (Int) -> Unit) : View(context) {
        var theme = KeyboardTheme.of(context)
            set(value) { field = value; invalidate() }
        private var selected = 0
        /** Nhóm đang chọn. */
        val current get() = selected
        /** Vị trí (theo số thứ tự nút) của vòng tô, trượt dần tới [selected]. */
        private var pos = 0f
        private var anim: android.animation.ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icons = TAB_ICONS.map { context.getDrawable(it)!!.mutate() }
        private val iconSize = context.dpi(18)
        private val circle = context.dp(17f)

        fun select(i: Int, animate: Boolean = true) {
            if (i == selected && (animate || pos == i.toFloat())) return
            selected = i
            anim?.cancel()
            // Đang không hiện hay vừa mở bảng: nhảy thẳng, khỏi trượt.
            if (!animate || !isShown) {
                pos = i.toFloat()
                invalidate()
                return
            }
            anim = android.animation.ValueAnimator.ofFloat(pos, i.toFloat()).setDuration(200).apply {
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat() / TAB_ICONS.size
            val cy = height / 2f
            // Vòng tô nhóm đang chọn (cuộn tới mép dải thì bị cắt thẳng như Gboard).
            paint.color = theme.accent and 0x00FFFFFF or 0x40000000
            canvas.drawCircle(w * (pos + 0.5f), cy, minOf(circle, w / 2, cy), paint)
            for ((i, icon) in icons.withIndex()) {
                val cx = w * (i + 0.5f)
                // Màu icon chuyển dần theo vòng tô đang trượt qua.
                val f = (1f - kotlin.math.abs(i - pos)).coerceIn(0f, 1f)
                icon.setTint(android.animation.ArgbEvaluator().evaluate(f, theme.text, theme.accent) as Int)
                val l = (cx - iconSize / 2f).toInt()
                val t = (cy - iconSize / 2f).toInt()
                icon.setBounds(l, t, l + iconSize, t + iconSize)
                icon.draw(canvas)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                onTab((e.x / (width.toFloat() / TAB_ICONS.size)).toInt().coerceIn(0, TAB_ICONS.lastIndex))
            }
            return true
        }
    }

    /**
     * Lưới emoji của mọi nhóm, cao bằng cả danh sách (nằm trong ScrollView); chỉ vẽ các hàng đang thấy.
     * Mỗi nhóm có dòng tiêu đề.
     */
    private class Grid(
        context: Context,
        /** Chạm ô: emoji của ô, ô thuộc nhóm gần đây không. */
        private val onPick: (item: String, recent: Boolean) -> Unit,
        /** Chọn một bản trong bảng giữ ô: bản đó, bảng mở từ ô gần đây không. */
        private val onPickTone: (variant: String, recent: Boolean) -> Unit,
        /** Emoji vẽ trong ô (ô gần đây: đúng bản đã dùng; ô khác: theo giới tính, màu da chọn lần trước). */
        private val shown: (item: String, recent: Boolean) -> String,
        /** Bảng giữ ô (hàng: giới tính / hướng, cột: màu da); null nếu không có. */
        private val tonesOf: (String) -> List<List<String>>?,
    ) : View(context) {
        var theme = KeyboardTheme.of(context)
            set(v) {
                field = v
                invalidate()
            }
        private var groups: List<Group> = emptyList()
        private var columns = 8
        /** Màn hình ngang: hàng thấp và emoji cỡ cố định, đo trên Gboard (8 cột). */
        var landscape = false
        /** Phần dưới khung cuộn đang bị hàng dưới che (bảng giữ ô không mở xuống đó). */
        var bottomInset = 0
        /** Trang `:-)`: không hàng tiêu đề, hàng cao [EMOTICON_ROW] dp, chữ [EMOTICON_SIZE] sp thu nhỏ cho vừa ô. */
        var emoticons = false
        /** Các hàng đã dàn: tiêu đề (`items` rỗng) hoặc một hàng ô, kèm toạ độ trên. */
        private class Row(val top: Int, val height: Int, val title: String?, val items: List<String>, val group: Int)
        private var rows: List<Row> = emptyList()
        /** Hàng tiêu đề nhóm, 32 dp cả hai chiều (ngang giống dọc). */
        private val headerHeight get() = context.dpi(32)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val robotoTypeface = context.robotoTypeface()
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = context.dp(13f)
            typeface = robotoTypeface
        }
        /** Bàn phím đang dùng: tiêu đề nhóm theo nó ([groupTitle]). */
        var style = KeyboardStyle.VIETNAMESE
            set(v) {
                field = v
                invalidate()
            }
        private val clip = Rect()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private val handler = Handler(Looper.getMainLooper())
        private val mark = android.graphics.Path()

        /**
         * Bảng giữ ô đang mở cho ô [popupItem]: các bản, khung bảng và bản đang tô. Giữ ô thì mở, thả tay bảng vẫn
         * mở; chạm vào một bản thì chọn, chạm ra ngoài hay cuộn thì đóng (như Gboard).
         */
        private var popupItem: String? = null
        /** Ngón tay đang chạm trong bảng giữ ô. */
        private var touchInPopup = false
        /** Cử chỉ chỉ để đóng bảng (chạm ra ngoài) hoặc vừa mở bảng: thả tay không chọn gì. */
        private var swallow = false
        private var popupTones: List<List<String>> = emptyList()
        private val popupRect = android.graphics.RectF()
        private var popupCell = 0f
        private var popupCellH = 0f
        private var popupRow = 0
        private var popupCol = 0
        /** Bảng đang mở là của emoji hai người: chọn riêng từng người ([EmojiPair]). */
        private var pair: EmojiPair? = null
        /** Bảng đang mở là của một ô gần đây. */
        private var popupRecent = false

        private val longPress = Runnable { openTones() }

        fun setGroups(groups: List<Group>, columns: Int) {
            this.groups = groups
            this.columns = columns
            if (width > 0) layoutRows(width)
            requestLayout()
            invalidate()
        }

        private fun layoutRows(w: Int) {
            val cell = when {
                emoticons -> context.dpi(if (landscape) EMOTICON_ROW_LANDSCAPE else EMOTICON_ROW)
                landscape -> context.dpi(LANDSCAPE_ROW)
                else -> (w / columns.toFloat() * 0.95f).toInt()
            }
            var y = 0
            val out = ArrayList<Row>()
            for ((g, group) in groups.withIndex()) {
                if (!emoticons) {
                    out.add(Row(y, headerHeight, group.title, emptyList(), g))
                    y += headerHeight
                }
                for (chunk in group.items.chunked(columns)) {
                    out.add(Row(y, cell, null, chunk, g))
                    y += cell
                }
            }
            rows = out
        }

        private fun isRecent(row: Row) = groups.getOrNull(row.group)?.title == RECENT

        /** Toạ độ trên của nhóm có tiêu đề [title]. */
        fun groupTop(title: String): Int? = rows.firstOrNull { it.title == title }?.top

        /**
         * Nhóm (theo hàng nhóm của [Tabs]) đang sáng khi cuộn tới [y]. Như Gboard: cuộn hết hàng áp chót của một nhóm
         * (hàng cuối của nó chạm mép trên) là đã chuyển sang nhóm sau.
         */
        fun groupAt(y: Int): Int {
            var g = 0
            for (next in 1 until groups.size) {
                val lastRow = rows.lastOrNull { it.group == next - 1 && it.title == null }
                    ?: rows.firstOrNull { it.group == next } ?: break
                if (lastRow.top <= y) g = next else break
            }
            val title = groups.getOrNull(g)?.title ?: return 0
            return if (title == RECENT) 0 else TAB_GROUPS.indexOf(title) + 1
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            layoutRows(w)
            val h = rows.lastOrNull()?.let { it.top + it.height + context.dpi(8) } ?: 0
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.getClipBounds(clip)
            val cellW = width / columns.toFloat()
            for (row in rows) {
                if (row.top + row.height < clip.top || row.top > clip.bottom) continue
                if (row.title != null) {
                    titlePaint.color = theme.hintText
                    val shown = groupTitle(row.title, style)
                    // Tiêu đề Hán Nôm (toàn chữ Hán) vẽ bằng phông Hán Nôm; ":-)" và chữ Latin vẫn Roboto.
                    titlePaint.typeface = if (shown.any { it.isSurrogate() || it.code >= 0x2E80 }) {
                        context.hanNomTypeface() ?: robotoTypeface
                    } else {
                        robotoTypeface
                    }
                    canvas.drawText(shown, context.dp(12f), row.top + row.height * 0.7f, titlePaint)
                    continue
                }
                paint.color = theme.text
                if (emoticons) {
                    // Biểu tượng chữ: Roboto 18 sp như Gboard, chuỗi dài thu nhỏ cho vừa ô (chừa 8 dp mỗi bên).
                    paint.typeface = robotoTypeface
                    val full = context.sp(EMOTICON_SIZE)
                    val room = cellW - context.dp(16f)
                    for ((i, item) in row.items.withIndex()) {
                        paint.textSize = full
                        val w = paint.measureText(item)
                        if (w > room) paint.textSize = full * room / w
                        val cy = row.top + row.height / 2f - (paint.ascent() + paint.descent()) / 2
                        canvas.drawText(item, cellW * (i + 0.5f), cy, paint)
                    }
                    continue
                }
                paint.typeface = null
                paint.textSize = when {
                    landscape -> context.dp(LANDSCAPE_SIZE)
                    else -> cellW * 0.62f
                }
                val cy = row.top + row.height / 2f - (paint.ascent() + paint.descent()) / 2
                val recent = isRecent(row)
                for ((i, item) in row.items.withIndex()) {
                    canvas.drawText(shown(item, recent), cellW * (i + 0.5f), cy, paint)
                    // Có bản màu da: tam giác nhỏ ở góc dưới phải ô, như Gboard.
                    if (tonesOf(item) != null) {
                        val r = cellW * (i + 1) - context.dp(5f)
                        val b = row.top + row.height - context.dp(5f)
                        val s = context.dp(5f)
                        mark.reset()
                        mark.moveTo(r, b)
                        mark.lineTo(r - s, b)
                        mark.lineTo(r, b - s)
                        mark.close()
                        paint.color = theme.hintText
                        canvas.drawPath(mark, paint)
                        paint.color = theme.text
                    }
                }
            }
            drawTones(canvas)
        }

        private fun drawTones(canvas: Canvas) {
            if (popupItem == null) return
            paint.color = theme.popup
            paint.setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0x40000000)
            val radius = if (popupTones.size == 1) popupRect.height() / 2 else context.dp(16f)
            canvas.drawRoundRect(popupRect, radius, radius, paint)
            paint.clearShadowLayer()
            pair?.let {
                it.draw(canvas, popupRect.left, popupRect.top, popupCell, popupCellH, theme, paint)
                return
            }
            paint.textSize = minOf(popupCell, popupCellH) * 0.6f
            for ((r, line) in popupTones.withIndex()) for ((c, tone) in line.withIndex()) {
                val cx = popupRect.left + popupCell * (c + 0.5f)
                val cy = popupRect.top + popupCellH * (r + 0.5f)
                if (r == popupRow && c == popupCol) {
                    paint.color = theme.accent and 0x00FFFFFF or 0x40000000
                    canvas.drawCircle(cx, cy, minOf(popupCell, popupCellH) * 0.46f, paint)
                }
                paint.color = theme.text
                canvas.drawText(tone, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }

        /** Ô dưới điểm ([x], [y]): emoji của ô và hàng chứa nó. */
        private fun cellAt(x: Float, y: Float): Pair<String, Row>? {
            val row = rows.firstOrNull { y >= it.top && y < it.top + it.height && it.title == null } ?: return null
            val item = row.items.getOrNull((x / (width / columns.toFloat())).toInt()) ?: return null
            return item to row
        }

        /**
         * Giữ ô có bản khác: mở bảng ngay trên ô, không đủ chỗ thì dưới ô, chọn sẵn bản đang hiện. Bảng không bao giờ
         * che ô đang giữ: cả hai phía đều không đủ thì các hàng thấp lại cho vừa phía rộng hơn.
         */
        private fun openTones() {
            val (item, row) = cellAt(downX, downY) ?: return
            val tones = tonesOf(item) ?: return
            popupItem = item
            popupTones = tones
            pair = EmojiPair.of(tones)
            popupRecent = isRecent(row)
            val current = shown(item, isRecent(row))
            popupRow = tones.indexOfFirst { current in it }.coerceAtLeast(0)
            popupCol = tones[popupRow].indexOf(current).coerceAtLeast(0)
            popupCell = minOf(width / columns.toFloat() * 1.1f, row.height * 1.2f)
            popupCellH = row.height * 0.95f
            val lines = if (pair != null) 3 else tones.size
            val w = pair?.width(popupCell) ?: (popupCell * tones.maxOf { it.size })
            val cellX = (downX / (width / columns.toFloat())).toInt() * (width / columns.toFloat())
            val left = (cellX + width / columns.toFloat() / 2 - w / 2).coerceIn(0f, maxOf(0f, width - w))
            val view = parent as? View
            val visibleTop = view?.scrollY ?: 0
            val visibleBottom = visibleTop + (view?.height ?: height) - bottomInset
            val gap = context.dp(4f)
            val above = row.top - gap - visibleTop
            val below = visibleBottom - row.top - row.height - gap
            popupCellH = minOf(popupCellH, maxOf(above, below) / lines)
            val h = popupCellH * lines
            val top = if (h <= above) row.top - h - gap else row.top + row.height + gap
            popupRect.set(left, top, left + w, top + h)
            swallow = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        fun closeTones() {
            if (popupItem == null) return
            popupItem = null
            pair = null
            invalidate()
        }

        /** Tô bản dưới ngón tay ([x], [y]) trong bảng giữ ô. */
        private fun highlight(x: Float, y: Float) {
            if (pair != null) return
            val r = ((y - popupRect.top) / popupCellH).toInt().coerceIn(0, popupTones.lastIndex)
            val c = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, popupTones[r].lastIndex)
            if (r != popupRow || c != popupCol) {
                popupRow = r
                popupCol = c
                invalidate()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    if (popupItem != null) {
                        // Bảng đang mở: chạm trong bảng là chọn, chạm ngoài là đóng.
                        touchInPopup = popupRect.contains(e.x, e.y)
                        if (touchInPopup) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            highlight(e.x, e.y)
                        } else {
                            closeTones()
                            swallow = true
                        }
                        return true
                    }
                    swallow = false
                    cellAt(e.x, e.y)?.let { (item, _) -> if (tonesOf(item) != null) handler.postDelayed(longPress, 400) }
                }
                MotionEvent.ACTION_MOVE -> when {
                    touchInPopup -> if (popupRect.contains(e.x, e.y)) highlight(e.x, e.y)
                    kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop -> handler.removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        touchInPopup -> {
                            touchInPopup = false
                            val p = pair
                            if (p != null) {
                                if (popupRect.contains(e.x, e.y)) {
                                    val chosen = p.tap(e.x - popupRect.left, e.y - popupRect.top, popupCell, popupCellH)
                                    if (chosen != null) {
                                        closeTones()
                                        onPickTone(chosen, popupRecent)
                                    } else {
                                        invalidate()
                                    }
                                }
                            } else if (popupRect.contains(e.x, e.y)) {
                                val tone = popupTones[popupRow][popupCol]
                                closeTones()
                                onPickTone(tone, popupRecent)
                            }
                        }
                        swallow -> swallow = false
                        kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop ->
                            cellAt(e.x, e.y)?.let { (item, row) -> onPick(item, isRecent(row)) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    touchInPopup = false
                    swallow = false
                }
            }
            return true
        }
    }

    companion object {
        private const val RECENT = "Biểu tượng cảm xúc gần đây"

        /**
         * Tên Hán Nôm, tiếng Anh của tiêu đề nhóm (khoá là tên tiếng Việt trong `emoji.txt`), theo bảng F6 của
         * PRD-mobile.md.
         */
        private val GROUP_NAMES = mapOf(
            RECENT to ("表象感觸近低" to "Recent emoji"),
            "Mặt cười và biểu tượng cảm xúc" to ("𩈘唭表象感觸" to "Smileys & emotions"),
            "Người" to ("𠊛" to "People"),
            "Động vật và thiên nhiên" to ("動物吧天然" to "Animals & nature"),
            "Thực phẩm và đồ uống" to ("食品吧𣘊㕵" to "Food & drink"),
            "Du lịch và địa danh" to ("遊歷地名" to "Travel & places"),
            "Hoạt động và sự kiện" to ("活動吧事件" to "Activities"),
            "Đồ vật" to ("𣘊物" to "Objects"),
            "Biểu tượng" to ("表象" to "Symbols"),
            "Cờ" to ("旗" to "Flags"),
        )

        /** Tiêu đề nhóm [title] (tên tiếng Việt) đem hiện theo bàn phím [style]. */
        fun groupTitle(title: String, style: KeyboardStyle): String {
            val (nom, en) = GROUP_NAMES[title] ?: return title
            return style.say(title, nom, en)
        }
        private const val MAX_RECENT = 32
        /** Tốc độ cuộn (dp/ms) từ đó hàng dưới thu lại hay hiện ra; kéo chậm thì hàng dưới đứng yên. */
        private const val FAST_SCROLL = 0.5f
        /** Màn hình ngang, đo trên Gboard: chiều cao hàng emoji (dp) và cỡ chữ emoji (dp, hình vẽ rộng chừng 36 dp). */
        private const val LANDSCAPE_ROW = 51
        private const val LANDSCAPE_SIZE = 32f
        // Hàng trên, đo trên Gboard (AVD 411 dp): nút tìm 109 × 34 dp, mỗi nhóm 35 dp.
        private const val SEARCH_WIDTH = 109
        /** Khoảng giữa nút tìm và nút nhóm đầu tiên (dp). */
        private const val SEARCH_GAP = 10
        private const val TAB_WIDTH = 35
        /** Bề rộng nhỏ nhất (dp) một cột emoji ở màn hình dọc ([portraitColumns]). */
        private const val MIN_CELL = 42
        // Trang biểu tượng chữ `:-)`, đo trên Gboard (máy 354 dp): 4 cột, hàng cao 59 dp, chữ 18 sp, bảng cao hơn
        // trang emoji 136 dp ở màn hình dọc.
        private const val EMOTICON_COLUMNS = 4
        private const val EMOTICON_ROW = 59
        private const val EMOTICON_SIZE = 18f
        private const val EMOTICON_EXTRA = 136
        // Màn hình ngang, đo trên Gboard điện thoại (560 dpi, lề tai thỏ 37 dp): mọi thứ lùi vào hai bên lề tai thỏ thêm
        // 3 dp; dải nhóm: nút tìm 29% bề rộng dải, cách 9 dp, vừa đúng 7 nút nhóm; trang `:-)` hàng cao 53 dp.
        private const val LANDSCAPE_EDGE = 3
        private const val LANDSCAPE_SEARCH_SHARE = 0.29f
        private const val LANDSCAPE_SEARCH_GAP = 9
        private const val LANDSCAPE_TABS = 7
        private const val EMOTICON_ROW_LANDSCAPE = 53
        /** Nhãn "Tìm kiếm" (nút tìm, chữ mờ trong ô tìm) theo bàn phím: Tiếng Việt, Hán Nôm, Tiếng Anh. */
        const val SEARCH_VI = "Tìm kiếm"
        const val SEARCH_NOM = "尋檢"
        const val SEARCH_EN = "Search"

        /** Nhóm của từng nút trên hàng nhóm (sau nút "gần đây"), đúng tên trong `emoji.txt`. */
        private val TAB_GROUPS = listOf(
            "Mặt cười và biểu tượng cảm xúc", "Người", "Động vật và thiên nhiên", "Thực phẩm và đồ uống",
            "Du lịch và địa danh", "Hoạt động và sự kiện", "Đồ vật", "Biểu tượng", "Cờ",
        )
        /** Icon Material đơn sắc của Gboard cho từng nhóm (gần đây, rồi theo [TAB_GROUPS]). */
        private val TAB_ICONS = listOf(
            R.drawable.ic_emoji_recent, R.drawable.ic_emoji_smileys, R.drawable.ic_emoji_people,
            R.drawable.ic_emoji_nature, R.drawable.ic_emoji_food, R.drawable.ic_emoji_travel,
            R.drawable.ic_emoji_activities, R.drawable.ic_emoji_objects, R.drawable.ic_emoji_symbols,
            R.drawable.ic_emoji_flags,
        )

        /**
         * Tên các nhóm trang `:-)` theo thứ tự Gboard (Tiếng Việt, Hán Nôm, Tiếng Anh; bảng F6 của PRD-mobile.md).
         * Tên tiếng Việt cũng là tên nhóm trong `emoticons.txt`. Hán Nôm do người dùng điền; còn trống thì hiện tiếng Việt.
         */
        val EMOTICON_NAMES = listOf(
            Triple("Cổ điển", "古典", "Classic"),
            Triple("Cười", "唭", "Smiling"),
            Triple("Tình yêu", "情𢞅", "Love"),
            Triple("Ôm", "揞", "Hugging"),
            Triple("Gập cánh tay", "岋𦑃𢬣", "Flexing"),
            Triple("Động vật", "動物", "Animals"),
            Triple("Ngạc nhiên", "愕然", "Surprise"),
            Triple("Nhảy múa", "𬦮𢷵", "Dancing"),
            Triple("Nhún vai", "撋𦠘", "Shruggie"),
            Triple("Lật bàn", "搮盤", "Table flip"),
            Triple("Ánh nhìn không đồng ý", "映𥆾空同意", "Look of disapproval"),
            Triple("Khóc", "哭", "Crying"),
            Triple("Lo lắng", "𢥈𢠯", "Nervous"),
            Triple("Con trỏ", "𡥵𢸫", "Pointers"),
        )

        @Volatile private var emoticonCache: List<List<String>>? = null

        /**
         * Biểu tượng của từng nhóm trang `:-)`, theo thứ tự [EMOTICON_NAMES]. Đọc `assets/emoticons.txt` (tạo bằng
         * `tools/emoji/gen_emoticons_gboard.py`): mỗi nhóm một khối cách nhau dòng trống, dòng đầu là tên nhóm.
         */
        fun emoticonData(context: Context): List<List<String>> = emoticonCache ?: synchronized(this) {
            emoticonCache ?: run {
                val text = context.assets.open("emoticons.txt").bufferedReader().use { it.readText() }
                val blocks = text.split("\n\n").map { it.split('\n').filter { line -> line.isNotEmpty() } }
                    .filter { it.isNotEmpty() }.associate { it[0] to it.drop(1) }
                EMOTICON_NAMES.map { blocks[it.first].orEmpty() }.also { emoticonCache = it }
            }
        }

        /** Các nhóm emoji (mỗi ô một emoji gốc) và bảng giữ ô của emoji gốc (hàng: giới tính / hướng, cột: màu da). */
        class Data(val groups: List<Group>, val tones: Map<String, List<List<String>>>) {
            /** Mỗi bản trong bảng giữ ô → emoji gốc của ô (cho ô gần đây, lưu đúng bản đã dùng). */
            val baseOf: Map<String, String> = tones.entries.flatMap { (base, rows) -> rows.flatten().map { it to base } }.toMap()
        }

        /** Mũi tên quay phải (`🚶‍➡️`): hàng chỉ hướng, không tính khi áp giới tính chung. */
        private const val RIGHT = 0x27A1
        private val MALE = intArrayOf(0x2642, 0x1F468, 0x1F466, 0x1F474, 0x1F934, 0x1FAC3, 0x1F385)
        private val FEMALE = intArrayOf(0x2640, 0x1F469, 0x1F467, 0x1F475, 0x1F478, 0x1F930, 0x1F936)

        /** Giới tính của một bản: 1 nam (♂, 👨 👦 👴 🤴 🫃 🎅), 2 nữ (♀, 👩 👧 👵 👸 🤰 🤶), 0 trung tính. */
        fun genderOf(emoji: String): Int {
            val cps = emoji.codePoints().toArray()
            return when {
                cps.any { it in MALE } -> 1
                cps.any { it in FEMALE } -> 2
                else -> 0
            }
        }

        @Volatile private var cached: Data? = null

        /**
         * Đọc `assets/emoji.txt` một lần (tạo bằng `tools/emoji/gen_emoji.py`): mỗi mục một ô; ô có bản khác thì
         * các hàng cách nhau `|`, trong hàng là bản đó rồi 5 màu da cách nhau dấu phẩy. Bỏ emoji máy không vẽ được.
         */
        fun data(context: Context): Data = cached ?: synchronized(this) {
            cached ?: run {
                val paint = Paint()
                val groups = ArrayList<Group>()
                val tones = HashMap<String, List<List<String>>>()
                var title: String? = null
                context.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                    for (line in lines) when {
                        line.startsWith("#") || line.isBlank() -> {}
                        line.startsWith("=") -> title = line.substring(1)
                        else -> title?.let { t ->
                            val items = ArrayList<String>()
                            for (token in line.split(' ')) {
                                val rows = token.split('|')
                                    .map { r -> r.split(',').filter { it.isNotEmpty() && paint.hasGlyph(it) } }
                                    .filter { it.isNotEmpty() }
                                val base = rows.firstOrNull()?.first()?.takeIf { it == token.substringBefore('|').substringBefore(',') }
                                    ?: continue
                                items.add(base)
                                if (rows.size > 1 || rows[0].size > 1) tones[base] = rows
                            }
                            groups.add(Group(t, items))
                        }
                    }
                }
                Data(groups, tones).also { cached = it }
            }
        }
    }
}
