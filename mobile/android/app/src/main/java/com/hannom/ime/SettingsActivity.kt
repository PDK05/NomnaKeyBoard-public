package com.hannom.ime

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.SplashScreenView

/**
 * Ứng dụng chủ, bố cục theo "Cài đặt Gboard": trang chính là danh sách mục có icon,
 * mỗi mục mở một trang con (cùng activity, chọn bằng [EXTRA_PAGE]).
 */
class SettingsActivity : Activity() {
    private enum class Page {
        MAIN, SETUP, LANGUAGES, LANGUAGES_EDIT, ADD_KEYBOARD, LANGUAGE,
        PREFERENCES, APPEARANCE, SUGGESTIONS, PRIVACY, ABOUT,
    }

    private lateinit var settings: Settings
    /** [AppText.version] lúc dựng trang. */
    private var textVersion = 0
    private lateinit var page: Page
    /** Dòng phụ cần cập nhật khi quay lại trang (trạng thái bàn phím, ngôn ngữ). */
    private var status: TextView? = null
    private var languagesSummary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padForCutout()
        // Từ nút cài đặt của bàn phím khi đang ở trong ứng dụng: không chạy màn khởi động (icon đỏ).
        if (Build.VERSION.SDK_INT >= 31 && !intent.getBooleanExtra(EXTRA_NO_SPLASH, false)) {
            splashScreen.setOnExitAnimationListener(::playSplash)
        }
        settings = Settings(this)
        textVersion = AppText.version
        page = runCatching { Page.valueOf(intent.getStringExtra(EXTRA_PAGE)!!) }.getOrDefault(Page.MAIN)
        when (page) {
            Page.MAIN -> mainPage()
            Page.SETUP -> setupPage()
            // Vẽ trong onResume: quay lại từ trang con thì danh sách đã đổi.
            Page.LANGUAGES -> {}
            Page.LANGUAGES_EDIT -> languagesEditPage()
            Page.ADD_KEYBOARD -> addKeyboardPage()
            Page.LANGUAGE -> languagePage(
                runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE)!!) }.getOrDefault(Mode.QUOC_NGU),
                adding = intent.getBooleanExtra(EXTRA_ADDING, false),
            )
            Page.PREFERENCES -> preferencesPage()
            Page.APPEARANCE -> appearancePage()
            Page.SUGGESTIONS -> suggestionsPage()
            Page.PRIVACY -> privacyPage()
            Page.ABOUT -> aboutPage()
        }
    }

    /**
     * Màn hình chờ (Android 12+): hệ thống chỉ vẽ nền trắng (icon trong suốt, xem values-v31/themes.xml).
     * Khi app sẵn sàng, icon đỏ nổi lên, lặn xuống một chút, đứng yên một lúc rồi cả màn hình mờ dần.
     */
    @TargetApi(31)
    private fun playSplash(splash: SplashScreenView) {
        val dp = resources.displayMetrics.density
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.splash_icon)
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
        }
        val side = (288 * dp).toInt()
        splash.addView(icon, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(400).setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                icon.animate().translationY(10 * dp).scaleX(0.95f).scaleY(0.95f)
                    .setDuration(300).setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        splash.animate().alpha(0f)
                            .setStartDelay(500).setDuration(350).setInterpolator(DecelerateInterpolator())
                            .withEndAction { splash.remove() }
                    }
            }
    }

    override fun finish() {
        if (!finishKeyboardTask()) super.finish()
    }

    override fun onResume() {
        super.onResume()
        // Đã đổi ngôn ngữ ứng dụng ở trang khác: dựng lại trang này bằng ngôn ngữ mới.
        if (textVersion != AppText.version) {
            recreate()
            return
        }
        if (page == Page.LANGUAGES) languagesPage()
        status?.text = keyboardStatus()
        languagesSummary?.text = settings.keyboards.map { it.mode.titleIn(this) }.distinct().joinToString(", ")
    }

    private fun open(p: Page) = startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p.name))

    private fun openLanguage(mode: Mode, adding: Boolean) {
        val intent = Intent(this, SettingsActivity::class.java)
            .putExtra(EXTRA_PAGE, Page.LANGUAGE.name)
            .putExtra(EXTRA_MODE, mode.name)
            .putExtra(EXTRA_ADDING, adding)
        @Suppress("DEPRECATION")
        if (adding) startActivityForResult(intent, REQUEST_ADD) else startActivity(intent)
    }

    // ---- Các trang ----

    /** Mở từ biểu tượng ứng dụng (không phải từ nút cài đặt của bàn phím): trang chính không có gì để quay lại. */
    private val fromLauncher get() = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

    private fun mainPage() = settingsPage(tr("Bàn phím Hán Nôm", "Bàn phím Hán Nôm", "盤柉漢喃"), back = !fromLauncher) { c ->
        status = item(c, tr("Bật bàn phím", "Enable keyboard", "弼盤柉"), keyboardStatus(), R.drawable.ic_keyboard) { open(Page.SETUP) }
        languagesSummary = item(c, tr("Ngôn ngữ", "Languages", "言語"), null, R.drawable.ic_language) { open(Page.LANGUAGES) }
        item(c, tr("Lựa chọn ưu tiên", "Preferences", "攄𫾗優先"), null, R.drawable.ic_tune) { open(Page.PREFERENCES) }
        item(c, tr("Giao diện", "Theme", "交面"), null, R.drawable.ic_palette) { open(Page.APPEARANCE) }
        item(c, tr("Chỉnh sửa và đề xuất", "Corrections & suggestions", "整𢯢吧提出"), null, R.drawable.ic_spellcheck) { open(Page.SUGGESTIONS) }
        item(c, tr("Từ điển", "Dictionary", "辭典"), null, R.drawable.ic_menu_book) {
            startActivity(Intent(this@SettingsActivity, DictionaryActivity::class.java))
        }
        item(c, tr("Quyền riêng tư", "Privacy", "權𥢆私"), null, R.drawable.ic_shield) { open(Page.PRIVACY) }
        item(c, tr("Giới thiệu", "About", "介紹"), null, R.drawable.ic_info) { open(Page.ABOUT) }
    }

    private fun setupPage() = settingsPage(tr("Bật bàn phím", "Enable keyboard", "弼盤柉")) { c ->
        status = paragraph(c, keyboardStatus())
        item(c, tr("1. Bật Bàn phím Hán Nôm", "1. Enable Bàn phím Hán Nôm", "1. 弼盤柉漢喃"), tr("Trong danh sách bàn phím của hệ thống", "In the system keyboard list", "𥪝名冊盤柉𧵑系統")) {
            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        item(c, tr("2. Chọn Bàn phím Hán Nôm", "2. Select Bàn phím Hán Nôm", "2. 𫾗盤柉𧵑漢喃"), tr("Làm bàn phím đang dùng", "As the current keyboard", "爫盤柉當用")) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        section(c, tr("Gõ thử", "Try it", "𢱗試"))
        addView(EditText(context).apply {
            hint = hanNomText(tr("Gõ thử ở đây", "Type here to try", "𢱗試於低"))
            setTextColor(c.text)
            setHintTextColor(c.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            useHanNomFont()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dpi(20)
            marginEnd = dpi(20)
        })
    }

    /** Danh sách bàn phím đã thêm (như "Ngôn ngữ" của Gboard): mỗi dòng một ngôn ngữ và bố cục. */
    private fun languagesPage() = settingsPage(
        tr("Ngôn ngữ", "Languages", "言語"),
        action = BarAction(R.drawable.ic_edit, tr("Sửa", "Edit")) { open(Page.LANGUAGES_EDIT) },
        fab = Fab(tr("Thêm bàn phím", "Add keyboard", "添盤柉"), R.drawable.ic_add) { open(Page.ADD_KEYBOARD) },
    ) { c ->
        // Ngôn ngữ ứng dụng (chữ trong ứng dụng cài đặt), đầu trang như người dùng muốn 2026-11-18.
        val langs = listOf(AppLang.SYSTEM, AppLang.VI, AppLang.NOM, AppLang.EN)
        // Mỗi ngôn ngữ ghi bằng chính ngôn ngữ đó (như danh sách ngôn ngữ của Android); "theo hệ thống" theo ngôn ngữ đang dùng.
        fun langName(l: AppLang) = when (l) {
            AppLang.SYSTEM -> tr("Mặc định của hệ thống", "System default", "默定𧵑系統")
            // Bản Hán Nôm: 㗂越, 㗂英 (người dùng đặt 2026-11-18).
            AppLang.VI -> tr("Tiếng Việt", "Tiếng Việt", "㗂越")
            // Người dùng đặt 2026-11-18: "Tiếng Việt (Hán Nôm)", viết bằng Hán Nôm là 㗂越 (漢喃) (như trang Từ điển).
            AppLang.NOM -> tr("Tiếng Việt (Hán Nôm)", "Vietnamese (Han Nom)", "㗂越 (漢喃)")
            AppLang.EN -> tr("English", "English", "㗂英")
        }
        item(c, tr("Ngôn ngữ ứng dụng", "App language", "言語應用"), langName(AppText.chosen(this@SettingsActivity))) {
            choose(tr("Ngôn ngữ ứng dụng", "App language", "言語應用"), langs.map(::langName), langs.indexOf(AppText.chosen(this@SettingsActivity))) {
                AppText.set(this@SettingsActivity, langs[it])
                recreate()
            }
        }
        section(c, tr("Ngôn ngữ và bố cục bàn phím", "Keyboard languages & layouts", "言語吧佈局"))
        for (k in settings.keyboards) {
            item(c, k.mode.titleIn(this@SettingsActivity), k.layout.titleIn(k.mode, this@SettingsActivity)) { openLanguage(k.mode, adding = false) }
        }
        paragraph(c, tr("Chạm phím chế độ (bên trái phím cách) để chuyển lần lượt các bàn phím theo thứ tự trên.", "Tap the mode key (left of the space bar) to cycle through the keyboards in the order above.", "𫼜柉制度（邊𢁑柉隔）抵轉𨁮𦀎各盤柉蹺次序𨑗"))
    }

    /** Sửa danh sách: đánh dấu rồi xoá, kéo tay nắm để đổi thứ tự. */
    private fun languagesEditPage() {
        val checked = mutableSetOf<Int>()
        fun build() {
            val list = settings.keyboards
            settingsPage(tr("Ngôn ngữ", "Languages", "言語"), action = BarAction(R.drawable.ic_delete, tr("Xoá", "Delete")) {
                // Đọc lại danh sách: kéo đổi thứ tự lưu ngay mà không dựng lại trang.
                val current = settings.keyboards
                when {
                    checked.isEmpty() -> toast(tr("Đánh dấu bàn phím cần xoá", "Select the keyboards to delete", "打𨁪盤柉勤𫨮"))
                    checked.size == current.size -> toast(tr("Phải giữ lại ít nhất một bàn phím", "Keep at least one keyboard", "𬆃貯徠𠃣一𠬠盤柉"))
                    else -> {
                        settings.keyboards = current.filterIndexed { i, _ -> i !in checked }
                        checked.clear()
                        build()
                    }
                }
            }) { c ->
                section(c, tr("Ngôn ngữ và bố cục bàn phím", "Keyboard languages & layouts", "言語吧佈局"))
                // Dòng đã tự chuyển chỗ trong danh sách: chỉ lưu thứ tự mới, không dựng lại trang (đỡ giật).
                reorderList(c, list.map { it.mode.titleIn(this@SettingsActivity) to it.layout.titleIn(it.mode, this@SettingsActivity) }, checked) { from, to ->
                    settings.keyboards = settings.keyboards.toMutableList().apply { add(to, removeAt(from)) }
                }
            }
        }
        build()
    }

    /** Chọn ngôn ngữ để thêm; trang ngôn ngữ mở ra để chọn bố cục. */
    private fun addKeyboardPage() = settingsPage(tr("Thêm bàn phím", "Add keyboard", "添盤柉")) { c ->
        section(c, tr("Tất cả ngôn ngữ", "All languages", "畢𪥘󠄁言語"))
        for (m in Mode.entries) {
            item(c, m.titleIn(this@SettingsActivity), Layout.entries.filter { it.available(m) }.joinToString(", ") { it.titleIn(m, this@SettingsActivity) }) { openLanguage(m, adding = true) }
        }
    }

    /**
     * Trang một ngôn ngữ: dải chọn bố cục (chọn được nhiều), rồi cài đặt riêng của ngôn ngữ.
     * Mở từ danh sách thì chọn / bỏ chọn áp dụng ngay; mở từ "Thêm bàn phím" thì bấm "Xong" mới thêm.
     */
    private fun languagePage(mode: Mode, adding: Boolean) {
        val layouts = Layout.entries.filter { it.available(mode) }
        val enabled = settings.keyboards.filter { it.mode == mode }.map { it.layout }
        val chosen = (if (adding && enabled.isEmpty()) listOf(layouts.first()) else enabled).toMutableSet()
        fun save() {
            val others = settings.keyboards
            // Giữ chỗ của bố cục đã có, bố cục mới thêm vào cuối.
            val kept = others.filter { it.mode != mode || it.layout in chosen }
            settings.keyboards = kept + layouts.filter { it in chosen && Keyboard(mode, it) !in kept }.map { Keyboard(mode, it) }
        }
        val done = if (adding) Fab(tr("Xong", "Done", "歱"), R.drawable.ic_check) {
            save()
            setResult(RESULT_OK)
            finish()
        } else null
        settingsPage(mode.titleIn(this), fab = done) { c ->
            val previews = layouts.associateWith { KeyboardPreview(context, mode, it) }
            val options = layouts.map { LayoutOption(previews.getValue(it), it.titleIn(mode, this@SettingsActivity)) }
            layoutChooser(c, options, layouts.indices.filter { layouts[it] in chosen }.toSet()) { i, on ->
                val layout = layouts[i]
                val rest = settings.keyboards.filterNot { it.mode == mode && it.layout == layout }
                if (!on && !adding && rest.isEmpty()) {
                    toast(tr("Phải giữ lại ít nhất một bàn phím", "Keep at least one keyboard", "𬆃貯徠𠃣一𠬠盤柉"))
                    return@layoutChooser false
                }
                if (!on && adding && chosen.size == 1) {
                    toast(tr("Chọn ít nhất một bố cục", "Choose at least one layout", "𫾗𠃣一𠬠佈局"))
                    return@layoutChooser false
                }
                if (on) chosen.add(layout) else chosen.remove(layout)
                if (!adding) save()
                true
            }
            languageSettings(c, mode)
        }
    }

    /**
     * Kiểu đặt dấu chỉ có tác dụng khi gõ Telex (chữ hiện ra có dấu); dùng chung cho QWERTY (Telex) của
     * Tiếng Việt (Latinh) và Hán Nôm.
     */
    private fun LinearLayout.toneStyleItem(c: SettingsColors) {
        val styles = listOf(tr("Kiểu cũ: hòa, thủy", "Old style: hòa, thủy", "矯𫇰"), tr("Kiểu mới: hoà, thuỷ", "New style: hoà, thuỷ", "矯𬔫"))
        lateinit var styleSummary: TextView
        styleSummary = item(c, tr("Kiểu đặt dấu", "Tone mark placement", "矯撻𨁪"), styles[settings.toneStyle]) {
            choose(tr("Kiểu đặt dấu", "Tone mark placement", "矯撻𨁪"), styles, settings.toneStyle) {
                settings.toneStyle = it
                styleSummary.text = styles[it]
            }
        }
        paragraph(c, tr("Chỉ áp dụng cho bố cục QWERTY (Telex), dùng chung cho Tiếng Việt (Latinh) và 㗂越 (漢喃).", "Applies only to the QWERTY (Telex) layout, shared by Vietnamese (Latin) and Vietnamese (Han Nom).", "只壓用賙佈局，用終賙㗂越（Latin）吧㗂越 (漢喃)."))
    }

    /** Cài đặt và hướng dẫn gõ riêng của từng ngôn ngữ, dưới dải chọn bố cục. */
    private fun LinearLayout.languageSettings(c: SettingsColors, mode: Mode) {
        when (mode) {
            Mode.QUOC_NGU -> {
                section(c, tr("Cài đặt QWERTY (Telex)", "QWERTY (Telex) settings", "掑撻QWERTY (Telex)"))
                paragraph(c, tr("Telex tự do như Unikey: dấu gõ ở đâu trong chữ cũng được (toots → tốt, vieetj → việt).", "Free Telex like Unikey: marks can be typed anywhere in the word (toots → tốt, vieetj → việt)."))
                toneStyleItem(c)
            }
            Mode.HAN_NOM -> {
                section(c, tr("Quy tắc gõ QWERTY (Telex)", "QWERTY (Telex) typing rules", "規則𢱗QWERTY (Telex)"))
                paragraph(c, tr(
                    "Gõ âm đọc bằng Telex như Unikey rồi chọn chữ Hán / Nôm trên thanh gợi ý: aa â, ee ê, oo ô, " +
                        "aw ă, ow ơ, uw ư, dd đ; dấu thanh s f r x j gõ ở đâu trong chữ cũng được (hasn → hán, toots → tốt).",
                    "Type the reading in Telex like Unikey, then pick the Hán / Nôm character on the suggestion bar: aa â, " +
                        "ee ê, oo ô, aw ă, ow ơ, uw ư, dd đ; tone keys s f r x j can go anywhere in the word (hasn → hán, " +
                        "toots → tốt).",
                ))
                paragraph(c, tr("• Gõ không dấu cũng được (tot ra cả tốt, tột…); dấu đã gõ dùng để lọc gợi ý.", "• Marks are optional (tot gives tốt, tột…); typed marks filter the suggestions."))
                paragraph(c, tr(
                    "• Các chữ phải cách nhau bằng phím cách (quoocs vieetj → 國越), gõ liền không tự tách; " +
                        "chữ cuối gõ dở cũng được. Cách một lần là tách chữ, lần nữa để chọn gợi ý đầu; Enter đưa ra " +
                        "đúng chữ Quốc ngữ trong khung gõ.",
                    "• Separate words with the space bar (quoocs vieetj → 國越); words typed together are not split. " +
                        "The last word may be unfinished. One space separates words, another picks the first " +
                        "suggestion; Enter inserts the Quốc ngữ text in the composing box as typed.",
                ))
                section(c, tr("Cài đặt QWERTY (Telex)", "QWERTY (Telex) settings", "掑撻QWERTY (Telex)"))
                toneStyleItem(c)
            }
            Mode.ENGLISH -> {
                section(c, tr("Cài đặt QWERTY", "QWERTY settings"))
                paragraph(c, tr("Gõ thẳng chữ Latinh, không ghép dấu tiếng Việt.", "Types Latin letters directly, without Vietnamese marks."))
            }
        }
    }

    /**
     * "Lựa chọn ưu tiên", chép thứ tự và nhãn từ Gboard (đo trên điện thoại 2026-11-18): Phím, Giao diện, Bố cục, Gõ
     * phím. Các mục Gboard có mà người dùng chưa muốn (phím emoji, phím đổi ngôn ngữ, phím phẩy, dải gợi ý, một tay,
     * phím tắt, cửa sổ bật lên, thời gian giữ, biểu tượng ứng dụng) không có ở đây; mục "Tự động viết hoa" cũng bỏ khỏi
     * trang này (người dùng muốn 2026-11-18). Mục "Bố cục" (chiều cao, khoảng trống dưới bàn phím) cũng bỏ: chỉnh
     * ngay trên bàn phím bằng nút Kích thước (người dùng muốn 2026-11-18).
     */
    private fun preferencesPage() = settingsPage(tr("Lựa chọn ưu tiên", "Preferences", "攄𫾗優先")) { c ->
        section(c, tr("Phím", "Keys", "柉"))
        lateinit var passwordRow: android.widget.Switch
        switchItem(c, tr("Hàng số", "Number row", "行數"), tr("Luôn hiển thị trên các bố cục QWERTY, Telex", "Always show on QWERTY, Telex layouts", "㫻顯示𨑗各佈局QWERTY，Telex"), settings.numberRow) {
            settings.numberRow = it
            setRowEnabled(rowOf(passwordRow), !it)
        }
        passwordRow = switchItem(
            c, tr("Hàng số cho mật khẩu", "Number row for passwords", "行數賙密口"), tr("Luôn hiển thị trên QWERTY, Telex để nhập mật khẩu", "Always show on QWERTY, Telex when entering passwords", "㫻顯示𨑗QWERTY，Telex抵入密口"), settings.numberRowPassword,
        ) { settings.numberRowPassword = it }
        setRowEnabled(rowOf(passwordRow), !settings.numberRow)

        section(c, tr("Giao diện", "Theme", "交面"))
        val fonts = listOf(tr("Phông chữ mặc định của bàn phím", "Keyboard default font", "風𡨸默定𧵑盤柉"), tr("Phông chữ mặc định của hệ thống", "System default font", "風𡨸默定𧵑系統"))
        lateinit var fontSummary: TextView
        fontSummary = item(c, tr("Phông chữ", "Font", "風𡨸"), fonts[if (settings.systemFont) 1 else 0]) {
            choose(tr("Phông chữ", "Font", "風𡨸"), fonts, if (settings.systemFont) 1 else 0) {
                settings.systemFont = it == 1
                fontSummary.text = fonts[it]
            }
        }
        val sizes = Settings.FONT_SIZES
        fun sizeText(i: Int) = if (sizes[i] == Settings.DEFAULT) tr("Giống như thiết bị", "Same as device", "𠏳如設備") else "${sizes[i]}%"
        fun tt(sp: Float) = TextView(this@SettingsActivity).apply {
            text = "Tt"
            gravity = Gravity.CENTER
            setTextColor(c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val sizeIndex = sizes.indexOf(settings.fontSize).coerceAtLeast(0)
        sliderBlock(
            c, tr("Cỡ chữ", "Font size", "𢤫𡨸"), sizeText(sizeIndex), null, tt(16f), tt(28f), sizes.lastIndex, sizeIndex,
            sizes.indexOf(Settings.DEFAULT), tr("Sử dụng cỡ chữ của thiết bị", "Use device font size", "使用𢤫𡨸𧵑設備"), ::sizeText,
            // Bong bóng lúc kéo như Gboard: số phần trăm (nấc mặc định là 100%).
            labelOf = { i -> if (sizes[i] == Settings.DEFAULT) "100%" else "${sizes[i]}%" },
        ) { settings.fontSize = sizes[it] }

        val emoji = listOf("😂", "😘", "😀", "❤️", "😭", "😎", "🔥", "🎉", "👍")
        val emojiPreview = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        fun showEmoji(step: Int) {
            emojiPreview.removeAllViews()
            val n = Settings.EMOJI_COLUMNS[step]
            val cell = (resources.displayMetrics.widthPixels - dpi(48)) / n
            emoji.take(n).forEach { e ->
                emojiPreview.addView(TextView(this@SettingsActivity).apply {
                    text = e
                    gravity = Gravity.CENTER
                    // Màu chữ mặc định có độ trong suốt, làm emoji màu bị mờ.
                    setTextColor(android.graphics.Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, cell * 0.62f * Settings.EMOJI_SCALE[step])
                    // Vùng emoji cao cố định, emoji căn giữa: đổi nấc thì thanh kéo và nút bên dưới đứng yên. Gboard (đo
                    // 2026-11-18, nấc 9 và nấc 5): tâm hàng emoji luôn cách tâm thanh kéo 64 dp = 64 / 2 + 8 (đệm) + 24.
                }, LinearLayout.LayoutParams(0, dpi(64), 1f))
            }
        }
        showEmoji(settings.emojiSize)
        // Mặt cười nét viền một màu ở hai đầu thanh kéo, như Gboard.
        fun face(padDp: Int) = ImageView(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_emoji)
            setColorFilter(c.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpi(padDp), dpi(padDp), dpi(padDp), dpi(padDp))
        }
        sliderBlock(
            c, tr("Kích thước biểu tượng cảm xúc", "Emoji size", "戟𡱩表象感觸"), tr("Đặt kích thước và số lượng biểu tượng cảm xúc hiển thị trong một hàng", "Set the size and number of emoji shown in a row", "撻戟𡱩吧數量表象感觸顯示𥪝𠬠行"),
            emojiPreview, face(14), face(7), Settings.EMOJI_COLUMNS.lastIndex, settings.emojiSize,
            Settings.DEFAULT_EMOJI_SIZE, tr("Dùng kích thước mặc định", "Use default", "用戟𡱩默定"), null,
            // Bong bóng lúc kéo như Gboard: số emoji một hàng, nấc to hơn cùng số cột có dấu + ("8+").
            labelOf = { i ->
                val n = Settings.EMOJI_COLUMNS[i]
                if (i > 0 && Settings.EMOJI_COLUMNS[i - 1] == n) "$n+" else "$n"
            },
        ) {
            settings.emojiSize = it
            showEmoji(it)
        }

        section(c, tr("Gõ phím", "Key tap", "𢱗柉"))
        lateinit var volumeSummary: TextView
        fun volumeText() = if (settings.volume == Settings.DEFAULT) tr("Mặc định của hệ thống", "System default", "默定𧵑系統") else "${settings.volume}%"
        switchItem(c, tr("Âm thanh", "Sound", "音聲"), tr("Phát khi nhấn phím", "Play when tapping keys", "發欺扨柉"), settings.sound) {
            settings.sound = it
            setRowEnabled(rowOf(volumeSummary), it)
        }
        volumeSummary = item(c, tr("Âm lượng", "Volume", "音量"), volumeText()) {
            sliderDialog(tr("Âm lượng", "Volume", "音量"), 100, settings.volume.coerceAtLeast(0), settings.volume, { if (it < 0) tr("Mặc định của hệ thống", "System default", "默定𧵑系統") else "$it%" }, { v ->
                if (v >= 0) keyClick(this@SettingsActivity, v)
            }) {
                settings.volume = it
                volumeSummary.text = volumeText()
            }
        }
        setRowEnabled(rowOf(volumeSummary), settings.sound)
        lateinit var vibrationSummary: TextView
        fun msText(ms: Int) = if (ms <= 0) tr("Mặc định của hệ thống", "System default", "默定𧵑系統") else tr("$ms mili giây", "$ms ms", "${ms}mili𣆰")
        switchItem(c, tr("Rung khi thao tác với bàn phím", "Keyboard vibration", "搈朞操作唄盤柉"), null, settings.vibrate) {
            settings.vibrate = it
            setRowEnabled(rowOf(vibrationSummary), it)
        }
        vibrationSummary = item(c, tr("Cường độ rung khi nhấn phím", "Vibration strength on keypress", "強度搈朞扨柉"), msText(settings.vibrationMs)) {
            val ms = settings.vibrationMs
            sliderDialog(tr("Cường độ rung khi nhấn phím", "Vibration strength on keypress", "強度搈朞扨柉"), Settings.MAX_VIBRATION_MS, ms.coerceAtLeast(0), ms, ::msText, { v ->
                keyVibrate(this@SettingsActivity, window.decorView, if (v <= 0) Settings.DEFAULT else v)
            }) {
                val v = if (it <= 0) Settings.DEFAULT else it
                settings.vibrationMs = v
                vibrationSummary.text = msText(v)
            }
        }
        setRowEnabled(rowOf(vibrationSummary), settings.vibrate)
    }

    private fun appearancePage() = settingsPage(tr("Giao diện", "Theme", "交面")) { c ->
        ThemePicker(this@SettingsActivity, c).attach(this)
    }

    private fun suggestionsPage() = settingsPage(tr("Chỉnh sửa và đề xuất", "Corrections & suggestions", "整𢯢吧提出")) { c ->
        section(c, tr("Gợi ý", "Suggestions", "㨳意"))
        switchItem(c, tr("Học thói quen chọn chữ", "Learn word choices", "學𢟔慣"), tr("Chữ hay chọn được đưa lên trước", "Words you pick often move up", "𡨸咍𫾗得迻𬨠𠓀"), settings.learning) { settings.learning = it }
        item(c, tr("Xoá dữ liệu học", "Delete learned data", "𫨮與料學"), tr("Xoá toàn bộ thói quen chọn chữ đã học", "Delete all learned word choices", "𫨮全部𢟔慣𡨸㐌學")) {
            confirm(tr("Xoá toàn bộ dữ liệu học thói quen?", "Delete all learned data?", "𫨮全部與料學𢟔慣？")) {
                HanNomEngine.get(this@SettingsActivity).clearLearned()
                toast(tr("Đã xoá dữ liệu học", "Learned data deleted", "㐌𫨮與料學"))
            }
        }
        if (!Handwriting.available(this@SettingsActivity)) return@settingsPage
        val hw = Handwriting.get(this@SettingsActivity)
        val none = tr("Chưa có. Khi gợi ý đầu sai, chọn chữ đúng ở các gợi ý sau là bàn phím tự nhớ", "None yet. When the first suggestion is wrong, pick the right character from the others and the keyboard remembers it", "𣗓𣎏。欺㨳意頭差，𫾗𡨸倲於各㨳意𢖖󠄁𪜀盤柉自𢖵")
        section(c, tr("Viết tay", "Handwriting", "𢪏𢬣"))
        val learned = item(c, tr("Chữ viết tay đã dạy", "Taught handwriting", "𡨸𢪏𢬣㐌𠰺"), "…") {}
        hw.learnedCount { learned.text = if (it == 0) none else tr("$it mẫu", "$it samples", "${it}母") }
        item(c, tr("Xuất mẫu viết tay", "Export handwriting samples", "出母𢪏𢬣"), tr("Tệp văn bản gồm nét viết và chữ đúng, dùng để cải thiện bộ nhận dạng", "A text file of strokes and correct characters, used to improve the recognizer", "集文本𠁟涅𢪏吧𡨸倲，用抵改善部認樣")) { pickHandwritingExport() }
        item(c, tr("Xoá chữ viết tay đã dạy", "Delete taught handwriting", "𫨮𡨸𢪏𢬣"), null) {
            confirm(tr("Xoá toàn bộ chữ viết tay đã dạy?", "Delete all taught handwriting?", "𫨮全部𡨸𢪏𢬣㐌𠰺")) {
                hw.clearLearned()
                learned.text = none
                toast(tr("Đã xoá chữ viết tay đã dạy", "Taught handwriting deleted", "㐌𫨮全部𡨸𢪏𢬣㐌𠰺"))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickHandwritingExport() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "mau-viet-tay.txt")
        }, REQUEST_EXPORT_HANDWRITING)
    }

    private fun privacyPage() = settingsPage(tr("Quyền riêng tư", "Privacy", "權𥢆私")) { c ->
        paragraph(c, tr(
            "Bàn phím không kết nối mạng và không gửi đi bất cứ thứ gì bạn gõ. " +
                "Ô mật khẩu luôn gõ tiếng Anh và không học thói quen.",
            "The keyboard never connects to the network and never sends anything you type. " +
                "Password fields always use English and nothing is learned from them.",
            "盤柉空結綏𦁸吧空寄𠫾不據次咦伴𢱗。嗚密口㫻𢱗㗂英吧空學𢟔慣。",
        ))
        paragraph(c, tr("Từ điển và dữ liệu học chỉ lưu trong máy, ở thư mục riêng của ứng dụng.", "Dictionaries and learned data stay on this device, in the app's private folder.", "辭典吧與料學只留𥪝𣛠，於書目𥢆𧵑應用。"))
    }

    private fun aboutPage() = settingsPage(tr("Giới thiệu", "About", "介紹")) { c ->
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        item(c, tr("Phiên bản hiện tại", "Current version", "番版現在"), version) {}
        item(c, tr("Phông chữ đi kèm", "Bundled font", "風𡨸𠫾兼"), "MiSans L3") {}
    }

    // ---- Tiện ích ----

    private fun keyboardStatus(): String {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        return when {
            current -> tr("Bàn phím Hán Nôm đang là bàn phím mặc định", "Bàn phím Hán Nôm is the default keyboard", "盤柉漢喃當𪜀盤柉默定")
            enabled -> tr("Đã bật. Bước tiếp: chọn Bàn phím Hán Nôm làm bàn phím đang dùng", "Enabled. Next: select Bàn phím Hán Nôm as the current keyboard", "㐌弼。𨀈接：𫾗盤柉漢喃爫盤柉當用")
            else -> tr("Chưa bật. Làm lần lượt 2 bước", "Not enabled. Follow the 2 steps", "𣗓弼。爫𨁮𦀎2𨀈")
        }
    }

    /**
     * Hộp thoại thanh kéo như "Âm lượng", "Cường độ rung khi nhấn phím" của Gboard: số đang chọn ở trên, thanh kéo 0 →
     * [max], nút Mặc định (về [Settings.DEFAULT]), Huỷ, OK. [preview] chạy mỗi lần kéo (phát tiếng / rung thử).
     */
    private fun sliderDialog(
        title: String,
        max: Int,
        start: Int,
        current: Int,
        label: (Int) -> String,
        preview: (Int) -> Unit,
        onOk: (Int) -> Unit,
    ) {
        val c = SettingsColors.of(this)
        var value = current
        val text = TextView(this).apply {
            this.text = label(value)
            gravity = Gravity.CENTER
            setTextColor(c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        lateinit var slider: SettingsSlider
        slider = settingsSlider(c, max, start) {
            value = it
            text.text = label(it)
            preview(it)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), dpi(16), dpi(24), 0)
            addView(text)
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(8) })
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(body)
            .setNeutralButton(tr("Mặc định", "Default", "默定"), null)
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .setPositiveButton(tr("OK", "OK", "同意")) { _, _ -> onOk(value) }
            .show()
        // Mặc định: đưa về mức của hệ thống, chưa lưu tới khi bấm OK (như Gboard).
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            value = Settings.DEFAULT
            slider.progress = 0
            text.text = label(value)
        }
    }

    private fun choose(title: String, options: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                onChoose(which)
                dialog.dismiss()
            }
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .show()
    }

    @Deprecated("Activity result API cũ, đủ dùng khi không có AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Đã thêm bàn phím: quay thẳng về danh sách.
        if (requestCode == REQUEST_ADD) {
            if (resultCode == RESULT_OK) finish()
            return
        }
        val uri = data?.data
        if (requestCode == REQUEST_EXPORT_HANDWRITING) {
            if (resultCode != RESULT_OK || uri == null) return
            Handwriting.get(this).exportLearned { text ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.onSuccess { toast(tr("Đã xuất mẫu viết tay", "Handwriting samples exported", "㐌出母𢪏𢬣")) }.onFailure { toast(tr("Không ghi được tệp", "Couldn't write the file", "空𥱬得集")) }
            }
            return
        }
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton(tr("Đồng ý", "OK", "同意")) { _, _ -> onYes() }
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, hanNomText(text), Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_ADD = 2
        private const val REQUEST_EXPORT_HANDWRITING = 3
        /** Mở từ nút cài đặt của bàn phím khi đang ở trong ứng dụng: bỏ màn khởi động. */
        const val EXTRA_NO_SPLASH = "com.hannom.ime.NO_SPLASH"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ADDING = "adding"
    }
}
