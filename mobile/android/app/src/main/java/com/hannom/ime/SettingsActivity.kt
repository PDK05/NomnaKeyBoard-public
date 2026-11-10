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
    private lateinit var page: Page
    /** Dòng phụ cần cập nhật khi quay lại trang (trạng thái bàn phím, phông, ngôn ngữ). */
    private var status: TextView? = null
    private var fontSummary: TextView? = null
    private var languagesSummary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padForCutout()
        // Từ nút cài đặt của bàn phím khi đang ở trong ứng dụng: không chạy màn khởi động (icon đỏ).
        if (Build.VERSION.SDK_INT >= 31 && !intent.getBooleanExtra(EXTRA_NO_SPLASH, false)) {
            splashScreen.setOnExitAnimationListener(::playSplash)
        }
        settings = Settings(this)
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
        if (page == Page.LANGUAGES) languagesPage()
        status?.text = keyboardStatus()
        fontSummary?.text = if (settings.fontFile.exists()) "Phông riêng bạn đã chọn" else "MiSans L3 (đi kèm)"
        languagesSummary?.text = settings.keyboards.map { it.mode.title }.distinct().joinToString(", ")
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

    private fun mainPage() = settingsPage("Bàn phím Hán Nôm", back = !fromLauncher) { c ->
        status = item(c, "Bật bàn phím", keyboardStatus(), R.drawable.ic_keyboard) { open(Page.SETUP) }
        languagesSummary = item(c, "Ngôn ngữ", null, R.drawable.ic_language) { open(Page.LANGUAGES) }
        item(c, "Lựa chọn ưu tiên", null, R.drawable.ic_tune) { open(Page.PREFERENCES) }
        item(c, "Giao diện", null, R.drawable.ic_palette) { open(Page.APPEARANCE) }
        item(c, "Chỉnh sửa và đề xuất", null, R.drawable.ic_spellcheck) { open(Page.SUGGESTIONS) }
        item(c, "Từ điển", null, R.drawable.ic_menu_book) {
            startActivity(Intent(this@SettingsActivity, DictionaryActivity::class.java))
        }
        item(c, "Quyền riêng tư", null, R.drawable.ic_shield) { open(Page.PRIVACY) }
        item(c, "Giới thiệu", null, R.drawable.ic_info) { open(Page.ABOUT) }
    }

    private fun setupPage() = settingsPage("Bật bàn phím") { c ->
        status = paragraph(c, keyboardStatus())
        item(c, "1. Bật Bàn phím Hán Nôm", "Trong danh sách bàn phím của hệ thống") {
            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        item(c, "2. Chọn Bàn phím Hán Nôm", "Làm bàn phím đang dùng") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        section(c, "Gõ thử")
        addView(EditText(context).apply {
            hint = "Gõ thử ở đây"
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
        "Ngôn ngữ",
        action = BarAction(R.drawable.ic_edit, "Sửa") { open(Page.LANGUAGES_EDIT) },
        fab = Fab("Thêm bàn phím", R.drawable.ic_add) { open(Page.ADD_KEYBOARD) },
    ) { c ->
        section(c, "Ngôn ngữ và bố cục bàn phím")
        for (k in settings.keyboards) {
            item(c, k.mode.title, k.layout.title(k.mode)) { openLanguage(k.mode, adding = false) }
        }
        paragraph(c, "Chạm phím chế độ (bên trái phím cách) để chuyển lần lượt các bàn phím theo thứ tự trên.")
    }

    /** Sửa danh sách: đánh dấu rồi xoá, kéo tay nắm để đổi thứ tự. */
    private fun languagesEditPage() {
        val checked = mutableSetOf<Int>()
        fun build() {
            val list = settings.keyboards
            settingsPage("Ngôn ngữ", action = BarAction(R.drawable.ic_delete, "Xoá") {
                // Đọc lại danh sách: kéo đổi thứ tự lưu ngay mà không dựng lại trang.
                val current = settings.keyboards
                when {
                    checked.isEmpty() -> toast("Đánh dấu bàn phím cần xoá")
                    checked.size == current.size -> toast("Phải giữ lại ít nhất một bàn phím")
                    else -> {
                        settings.keyboards = current.filterIndexed { i, _ -> i !in checked }
                        checked.clear()
                        build()
                    }
                }
            }) { c ->
                section(c, "Ngôn ngữ và bố cục bàn phím")
                // Dòng đã tự chuyển chỗ trong danh sách: chỉ lưu thứ tự mới, không dựng lại trang (đỡ giật).
                reorderList(c, list.map { it.mode.title to it.layout.title(it.mode) }, checked) { from, to ->
                    settings.keyboards = settings.keyboards.toMutableList().apply { add(to, removeAt(from)) }
                }
            }
        }
        build()
    }

    /** Chọn ngôn ngữ để thêm; trang ngôn ngữ mở ra để chọn bố cục. */
    private fun addKeyboardPage() = settingsPage("Thêm bàn phím") { c ->
        section(c, "Tất cả ngôn ngữ")
        for (m in Mode.entries) {
            item(c, m.title, Layout.entries.filter { it.available(m) }.joinToString(", ") { it.title(m) }) { openLanguage(m, adding = true) }
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
        val done = if (adding) Fab("Xong", R.drawable.ic_check) {
            save()
            setResult(RESULT_OK)
            finish()
        } else null
        settingsPage(mode.title, fab = done) { c ->
            val previews = layouts.associateWith { KeyboardPreview(context, mode, it) }
            val options = layouts.map { LayoutOption(previews.getValue(it), it.title(mode)) }
            layoutChooser(c, options, layouts.indices.filter { layouts[it] in chosen }.toSet()) { i, on ->
                val layout = layouts[i]
                val rest = settings.keyboards.filterNot { it.mode == mode && it.layout == layout }
                if (!on && !adding && rest.isEmpty()) {
                    toast("Phải giữ lại ít nhất một bàn phím")
                    return@layoutChooser false
                }
                if (!on && adding && chosen.size == 1) {
                    toast("Chọn ít nhất một bố cục")
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
        val styles = listOf("Kiểu cũ: hòa, thủy", "Kiểu mới: hoà, thuỷ")
        lateinit var styleSummary: TextView
        styleSummary = item(c, "Kiểu đặt dấu", styles[settings.toneStyle]) {
            choose("Kiểu đặt dấu", styles, settings.toneStyle) {
                settings.toneStyle = it
                styleSummary.text = styles[it]
            }
        }
        paragraph(c, "Chỉ áp dụng cho bố cục QWERTY (Telex), dùng chung cho Tiếng Việt (Latinh) và 㗂越 (漢喃).")
    }

    /** Cài đặt và hướng dẫn gõ riêng của từng ngôn ngữ, dưới dải chọn bố cục. */
    private fun LinearLayout.languageSettings(c: SettingsColors, mode: Mode) {
        when (mode) {
            Mode.QUOC_NGU -> {
                section(c, "Cài đặt QWERTY (Telex)")
                paragraph(c, "Telex tự do như Unikey: dấu gõ ở đâu trong chữ cũng được (toots → tốt, vieetj → việt).")
                toneStyleItem(c)
            }
            Mode.HAN_NOM -> {
                section(c, "Quy tắc gõ QWERTY (Telex)")
                paragraph(c, "Gõ âm đọc bằng Telex như Unikey rồi chọn chữ Hán / Nôm trên thanh gợi ý: aa â, ee ê, oo ô, " +
                    "aw ă, ow ơ, uw ư, dd đ; dấu thanh s f r x j gõ ở đâu trong chữ cũng được (hasn → hán, toots → tốt).")
                paragraph(c, "• Gõ không dấu cũng được (tot ra cả tốt, tột…); dấu đã gõ dùng để lọc gợi ý.")
                paragraph(c, "• Các chữ phải cách nhau bằng phím cách (quoocs vieetj → 國越), gõ liền không tự tách; " +
                    "chữ cuối gõ dở cũng được. Cách một lần là tách chữ, lần nữa để chọn gợi ý đầu; Enter đưa ra " +
                    "đúng chữ Quốc ngữ trong khung gõ.")
                section(c, "Cài đặt QWERTY (Telex)")
                toneStyleItem(c)
            }
            Mode.ENGLISH -> {
                section(c, "Cài đặt QWERTY")
                paragraph(c, "Gõ thẳng chữ Latinh, không ghép dấu tiếng Việt.")
            }
        }
    }

    private fun preferencesPage() = settingsPage("Lựa chọn ưu tiên") { c ->
        section(c, "Phím")
        switchItem(c, "Tự động viết hoa", "Viết hoa chữ đầu tiên của mỗi câu", settings.autoCap) { settings.autoCap = it }

        section(c, "Gõ phím")
        switchItem(c, "Âm thanh", "Phát khi nhấn phím", settings.sound) { settings.sound = it }
        switchItem(c, "Rung khi thao tác với bàn phím", null, settings.vibrate) { settings.vibrate = it }

        section(c, "Bố cục")
        val heights = listOf("Thấp", "Vừa", "Cao")
        lateinit var heightSummary: TextView
        heightSummary = item(c, "Chiều cao bàn phím", if (settings.customHeight) "Tuỳ chỉnh" else heights[settings.height]) {
            choose("Chiều cao bàn phím", heights, settings.height) {
                settings.height = it
                heightSummary.text = heights[it]
            }
        }
        val gaps = listOf(0, 8, 16, 24, 32, 48, 64)
        fun gapText(dp: Int) = if (dp == 0) "Không" else "$dp dp"
        lateinit var gapSummary: TextView
        gapSummary = item(c, "Khoảng trống dưới bàn phím", gapText(settings.bottomGap)) {
            val current = gaps.indexOf(settings.bottomGap).let { if (it < 0) gaps.indexOfFirst { g -> g >= settings.bottomGap } else it }
            choose("Khoảng trống dưới bàn phím", gaps.map(::gapText), current.coerceAtLeast(0)) {
                settings.bottomGap = gaps[it]
                gapSummary.text = gapText(gaps[it])
            }
        }
        paragraph(c, "Thanh điều hướng che mất hàng phím dưới cùng thì tăng khoảng trống này. Cũng chỉnh được ngay " +
            "trên bàn phím: nút Kích thước trên thanh công cụ, kéo cạnh trên để đổi chiều cao, kéo cạnh dưới hay " +
            "nút ✥ để đẩy bàn phím lên.")
    }

    private fun appearancePage() = settingsPage("Giao diện") { c ->
        paragraph(c, "Bàn phím sáng / tối theo chế độ của hệ thống.")
        section(c, "Chữ Hán Nôm")
        fontSummary = item(c, "Phông Hán Nôm", null) { pickFont() }
        item(c, "Dùng lại phông đi kèm", null) {
            settings.fontFile.delete()
            toast("Đã dùng lại phông đi kèm")
            onResume()
        }
        paragraph(c, "Phông chỉ áp dụng trong bàn phím và ứng dụng này. Ứng dụng khác dùng phông của máy, " +
            "nên chữ Nôm hiếm có thể vẫn hiện thành ô vuông ở đó.")
    }

    private fun suggestionsPage() = settingsPage("Chỉnh sửa và đề xuất") { c ->
        section(c, "Gợi ý")
        switchItem(c, "Học thói quen chọn chữ", "Chữ hay chọn được đưa lên trước", settings.learning) { settings.learning = it }
        item(c, "Xoá dữ liệu học", "Xoá toàn bộ thói quen chọn chữ đã học") {
            confirm("Xoá toàn bộ dữ liệu học thói quen?") {
                HanNomEngine.get(this@SettingsActivity).clearLearned()
                toast("Đã xoá dữ liệu học")
            }
        }
        if (!Handwriting.available(this@SettingsActivity)) return@settingsPage
        val hw = Handwriting.get(this@SettingsActivity)
        val none = "Chưa có. Khi gợi ý đầu sai, chọn chữ đúng ở các gợi ý sau là bàn phím tự nhớ"
        section(c, "Viết tay")
        val learned = item(c, "Chữ viết tay đã dạy", "…") {}
        hw.learnedCount { learned.text = if (it == 0) none else "$it mẫu" }
        item(c, "Xuất mẫu viết tay", "Tệp văn bản gồm nét viết và chữ đúng, dùng để cải thiện bộ nhận dạng") { pickHandwritingExport() }
        item(c, "Xoá chữ viết tay đã dạy", null) {
            confirm("Xoá toàn bộ chữ viết tay đã dạy?") {
                hw.clearLearned()
                learned.text = none
                toast("Đã xoá chữ viết tay đã dạy")
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

    private fun privacyPage() = settingsPage("Quyền riêng tư") { c ->
        paragraph(c, "Bàn phím không kết nối mạng và không gửi đi bất cứ thứ gì bạn gõ. " +
            "Ô mật khẩu luôn gõ tiếng Anh và không học thói quen.")
        paragraph(c, "Từ điển và dữ liệu học chỉ lưu trong máy, ở thư mục riêng của ứng dụng.")
    }

    private fun aboutPage() = settingsPage("Giới thiệu") { c ->
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        item(c, "Phiên bản hiện tại", version) {}
        item(c, "Phông chữ đi kèm", "MiSans L3") {}
    }

    // ---- Tiện ích ----

    private fun keyboardStatus(): String {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        return when {
            current -> "Bàn phím Hán Nôm đang là bàn phím mặc định"
            enabled -> "Đã bật. Bước tiếp: chọn Bàn phím Hán Nôm làm bàn phím đang dùng"
            else -> "Chưa bật. Làm lần lượt 2 bước"
        }
    }

    private fun choose(title: String, options: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                onChoose(which)
                dialog.dismiss()
            }
            .setNegativeButton("Huỷ", null)
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
                }.onSuccess { toast("Đã xuất mẫu viết tay") }.onFailure { toast("Không ghi được tệp") }
            }
            return
        }
        if (requestCode != REQUEST_FONT || resultCode != RESULT_OK || uri == null) return
        runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                settings.fontFile.outputStream().use { input.copyTo(it) }
            }
            android.graphics.Typeface.createFromFile(settings.fontFile)
        }.onSuccess {
            toast("Đã đặt phông Hán Nôm")
        }.onFailure {
            settings.fontFile.delete()
            toast("Không đọc được tệp phông")
        }
        onResume()
    }

    @Suppress("DEPRECATION")
    private fun pickFont() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_FONT)
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton("Đồng ý") { _, _ -> onYes() }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_FONT = 1
        private const val REQUEST_ADD = 2
        private const val REQUEST_EXPORT_HANDWRITING = 3
        /** Mở từ nút cài đặt của bàn phím khi đang ở trong ứng dụng: bỏ màn khởi động. */
        const val EXTRA_NO_SPLASH = "com.hannom.ime.NO_SPLASH"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ADDING = "adding"
    }
}
