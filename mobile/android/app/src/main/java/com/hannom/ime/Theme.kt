package com.hannom.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.widget.TextView
import java.io.File

/** Bảng màu kiểu Gboard, theo chế độ sáng / tối của hệ thống. */
class KeyboardTheme(
    val background: Int,
    val key: Int,
    val specialKey: Int,
    val pressedKey: Int,
    val text: Int,
    val hintText: Int,
    val accent: Int,
    val onAccent: Int,
    val bubble: Int,
    /** Nền bảng giữ phím (ký tự thay thế) và bảng màu da emoji: tối hơn nền bàn phím một chút. */
    val popup: Int,
    /** Nền khung gõ (kiểu khung pinyin) phía trên thanh gợi ý: nhạt hơn nền bàn phím. */
    val composeBox: Int,
    /** Nền thanh gợi ý / thanh công cụ (giao diện màu không viền phím: đậm hơn nền bàn phím như Gboard). */
    val header: Int = background,
    /** Không viền phím: phím chữ và phím chức năng không có nền, chỉ phím cách và `?123` có nền [spaceBar]. */
    val borderless: Boolean = false,
    /** Nền phím cách và `?123` khi [borderless] (màu trong mờ phủ lên nền bàn phím). */
    val spaceBar: Int = key,
    /**
     * Chữ gợi ý đầu trên thanh gợi ý: màu nhấn ở giao diện mặc định; giao diện "Màu" dùng màu chữ như Gboard
     * (`color_state_label_candidate_selected`), không tô màu nhấn (người dùng muốn 2026-11-18).
     */
    val candidate: Int = accent,
) {
    companion object {
        fun of(context: Context): KeyboardTheme {
            val settings = Settings(context)
            return ThemeSpec.find(settings.themeId).resolve(context).build(settings.keyBorders)
        }

        /** Mặc định (sáng) và giao diện tối mặc định, lấy mẫu từ Gboard. */
        internal val LIGHT = KeyboardTheme(
            background = Color.parseColor("#EDF0F4"),
            key = Color.WHITE,
            specialKey = Color.parseColor("#D3D8E0"),
            pressedKey = Color.parseColor("#BFC4CC"),
            text = Color.parseColor("#202124"),
            hintText = Color.parseColor("#5F6368"),
            accent = Color.parseColor("#3B7BEA"),
            onAccent = Color.WHITE,
            bubble = Color.WHITE,
            popup = Color.parseColor("#E1E4E9"),
            composeBox = Color.WHITE,
            spaceBar = 0x19000000,
        )
        /**
         * Gboard "Default Dark" = bảng Google Material 3 tối (`style_sheet_material3_dark` / `dynamic_color_dark`, mã màu
         * đọc trong APK Gboard 18.5 ngày 2026-11-18): nền surface_container, phím chữ surface_bright, phím chức năng
         * surface_variant, chữ on_surface, màu nhấn primary, chữ trên phím nhấn on_primary. Các màu còn lại chưa đo.
         */
        internal val DARK = KeyboardTheme(
            background = Color.parseColor("#1E1F20"),
            key = Color.parseColor("#37393B"),
            specialKey = Color.parseColor("#444746"),
            pressedKey = Color.parseColor("#62656E"),
            text = Color.parseColor("#E3E3E3"),
            hintText = Color.parseColor("#9AA0A6"),
            accent = Color.parseColor("#A8C7FA"),
            onAccent = Color.parseColor("#062E6F"),
            bubble = Color.parseColor("#5A5D66"),
            popup = Color.parseColor("#22252D"),
            composeBox = Color.parseColor("#3C3F48"),
            spaceBar = 0x29FFFFFF,
        )
    }

    /** Bản không viền phím (hoặc giữ nguyên nếu [borders]). */
    fun withBorders(borders: Boolean): KeyboardTheme = if (borders) this else KeyboardTheme(
        background, key, specialKey, pressedKey, text, hintText, accent, onAccent, bubble, popup, composeBox,
        header = header, borderless = true, spaceBar = spaceBar, candidate = candidate,
    )
}

/**
 * Một giao diện trong trang "Giao diện" (chép Gboard: mục "Mặc định" và "Màu", trừ "Màu tự động"). Màu của các
 * giao diện "Màu" lấy đúng từ style sheet trong APK Gboard (`assets/theme/style_sheet_*.binarypb`, đọc 2026-11-18):
 * [primary] nền bàn phím, [secondary] nền thanh trên cùng khi không viền phím, [key]/[keyDark] phím chữ / phím chức
 * năng khi có viền, [spaceBar] phím cách khi không viền.
 */
class ThemeSpec(
    val id: String,
    val primary: Int,
    val secondary: Int,
    val key: Int,
    val keyPressed: Int,
    val keyDark: Int,
    val accent: Int,
    val label: Int,
    val spaceBar: Int,
    /** Nền phím Enter khi có viền, nếu khác [accent] (Holo trắng, Material). */
    val borderAccent: Int = accent,
    /** Nền bàn phím khi có viền, nếu khác [primary] (Google xanh sáng / tối). */
    val borderPrimary: Int = primary,
    /** Mặc định / tối mặc định: dùng bảng màu dựng sẵn. */
    private val base: KeyboardTheme? = null,
) {
    /** Tự động theo hệ thống: chọn sáng / tối lúc dùng. */
    val isSystem get() = id == SYSTEM

    fun resolve(context: Context): ThemeSpec = if (!isSystem) this else {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) DARK else LIGHT
    }

    fun build(borders: Boolean): KeyboardTheme {
        base?.let { return it.withBorders(borders) }
        // Chữ phụ (số nhỏ góc phím, chữ phím cách): chữ chính mờ đi trên nền (Gboard color_label_secondary: 80%).
        val hint = blend(label and 0xFFFFFF or (0xCC shl 24), if (borders) borderPrimary else primary)
        val bg = if (borders) borderPrimary else primary
        return KeyboardTheme(
            background = bg,
            key = key,
            specialKey = keyDark,
            pressedKey = keyPressed,
            text = label,
            hintText = hint,
            accent = if (borders) borderAccent else accent,
            onAccent = Color.WHITE,
            bubble = key,
            popup = secondary,
            composeBox = key,
            header = if (borders) bg else secondary,
            borderless = !borders,
            spaceBar = spaceBar,
            candidate = label,
        )
    }

    companion object {
        const val SYSTEM = "system"
        const val LIGHT_ID = "light"
        const val DARK_ID = "dark"

        private fun c(hex: String) = Color.parseColor(hex)
        private const val WHITE_BAR = 0x73FFFFFF
        private val WHITE_LABEL = Color.WHITE
        private val BLACK_LABEL = c("#333333")

        private fun color(id: String, primary: String, secondary: String, accent: String, key: String, keyPressed: String,
                          keyDark: String, label: Int = WHITE_LABEL, spaceBar: Int = WHITE_BAR, borderAccent: String? = null,
                          borderPrimary: String? = null) =
            ThemeSpec("color_$id", c(primary), c(secondary), c(key), c(keyPressed), c(keyDark), c(accent), label, spaceBar,
                borderAccent?.let(::c) ?: c(accent), borderPrimary?.let(::c) ?: c(primary))

        private fun fromBase(id: String, t: KeyboardTheme) =
            ThemeSpec(id, t.background, t.background, t.key, t.pressedKey, t.specialKey, t.accent, t.text, t.spaceBar, base = t)

        val LIGHT = fromBase(LIGHT_ID, KeyboardTheme.LIGHT)
        val DARK = fromBase(DARK_ID, KeyboardTheme.DARK)
        /** Ô "Tự động thay đổi theo hệ thống" (vẽ nửa sáng nửa tối). */
        val SYSTEM_SPEC = fromBase(SYSTEM, KeyboardTheme.LIGHT)

        /** Mục "Mặc định", theo thứ tự Gboard (bỏ "Màu tự động"). */
        val DEFAULTS = listOf(SYSTEM_SPEC, LIGHT, DARK)

        /** Mục "Màu": 18 ô theo thứ tự Gboard (đo trên điện thoại 2026-11-18). */
        val COLORS = listOf(
            color("material_light", "#ECEFF1", "#E4E7E9", "#4DB6AC", "#FBFBFC", "#F3F5F6", "#DFE2E4",
                label = c("#061D28"), spaceBar = 0x3337474F, borderAccent = "#5CB5AB"),
            color("material_dark", "#263238", "#21272B", "#80CBC4", "#404A50", "#596367", "#313C42",
                label = 0xCCFFFFFF.toInt(), spaceBar = 0x1AFFFFFF, borderAccent = "#6EACA8"),
            color("google_blue_light", "#F8F9FA", "#F1F3F4", "#1A73E8", "#FFFFFF", "#E6E6E6", "#CCCED5",
                label = Color.BLACK, spaceBar = 0x19000000, borderPrimary = "#E8EAED"),
            color("google_blue_dark", "#363B40", "#292E33", "#5E97F6", "#484C4F", "#5A5E60", "#373C41",
                spaceBar = 0x29FFFFFF, borderPrimary = "#292E33"),
            color("red", "#C62828", "#B71C1C", "#F44336", "#D04E4E", "#EEC1C1", "#CA3838"),
            color("green", "#43A047", "#388E3C", "#2D7D32", "#64B168", "#C9E3CA", "#51A755"),
            color("teal", "#00695C", "#006064", "#4DB6AC", "#2E8479", "#B6D4D0", "#147468"),
            color("blue", "#1565C0", "#0D47A1", "#2196F3", "#3F80CB", "#BCD2EC", "#2771C4"),
            color("cyan", "#80DEEA", "#00BCD4", "#00ACC1", "#ACE9F1", "#E2F7FA", "#99E4EE", label = BLACK_LABEL),
            color("deep_purple", "#4527A0", "#311B92", "#9575CD", "#664DB1", "#C9C1E3", "#5337A7"),
            color("pink", "#AD1457", "#870D4E", "#F50057", "#BB3E75", "#E7BBCE", "#B32664"),
            color("light_pink", "#F8BBD0", "#F48FB1", "#E36087", "#FAD2E0", "#FDEFF4", "#F9C8D9", label = BLACK_LABEL),
            color("sand", "#DACDC5", "#CDBCB1", "#FF7276", "#F0EBE7", "#C9BDB6", "#E7DFDA", label = c("#1E1E1E")),
            color("brown", "#4E342E", "#3E2723", "#8D6E63", "#6D5853", "#CCC4C3", "#5B433E"),
            color("blue_grey", "#37474F", "#263238", "#78909C", "#5B686E", "#C5CACC", "#46555C"),
            color("black", "#000000", "#373737", "#80CBC4", "#2E2E2E", "#B6B6B6", "#141414"),
            color("holo_blue", "#161616", "#272727", "#003D5C", "#5C5C5C", "#006699", "#282828"),
            color("holo_white", "#161616", "#272727", "#282828", "#5C5C5C", "#BCBCBC", "#282828"),
        )

        /** Giao diện thuộc mục "Màu" (chỉ những giao diện này vào "Giao diện của tôi"). */
        fun isColor(id: String) = COLORS.any { it.id == id }

        fun find(id: String?): ThemeSpec = (DEFAULTS + COLORS).firstOrNull { it.id == id } ?: SYSTEM_SPEC

        /** Màu [top] (có độ trong) phủ lên nền đặc [bottom]. */
        fun blend(top: Int, bottom: Int): Int {
            val a = Color.alpha(top) / 255f
            fun ch(t: Int, b: Int) = (t * a + b * (1 - a)).toInt()
            return Color.rgb(
                ch(Color.red(top), Color.red(bottom)),
                ch(Color.green(top), Color.green(bottom)),
                ch(Color.blue(top), Color.blue(bottom)),
            )
        }
    }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()

fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * Phông hiển thị chữ Hán Nôm ngoài BMP (vd. 𡨸), theo "Phông chữ" trong Lựa chọn ưu tiên: phông mặc định của bàn
 * phím là phông đi kèm `assets/fonts/MiSans L3.ttf`; chọn phông mặc định của hệ thống (hoặc thiếu phông đi kèm) thì
 * `null`, nơi gọi dùng phông hệ thống.
 */
fun Context.hanNomTypeface(): Typeface? = FontCache.get(this)

/**
 * Roboto đóng gói kèm app, dùng cho mọi thành phần trừ chữ Hán Nôm (máy nhiều hãng đổi phông mặc định). Chỗ dùng
 * TextView thì đã có từ theme (`android:fontFamily`), hàm này cho các chỗ tự vẽ chữ bằng Paint.
 */
fun Context.robotoTypeface(): Typeface =
    if (Settings.systemFont(this)) Typeface.DEFAULT else resources.getFont(R.font.roboto)

/**
 * Toạ độ x để vẽ [text] (paint căn giữa) có tâm ở [cx]. Dấu câu Hán và ký tự toàn chiều rộng (`。、「」＠`) có nét chỉ ở
 * một góc ô chữ, nên căn theo nét thật như Gboard; chữ khác giữ cách căn thường.
 */
fun android.graphics.Paint.centerXFor(text: String, cx: Float): Float {
    val c = text.singleOrNull() ?: return cx
    if (c !in '　'..'〿' && c !in '＀'..'￯') return cx
    val ink = android.graphics.Rect().also { getTextBounds(text, 0, 1, it) }
    return cx + measureText(text) / 2 - (ink.left + ink.right) / 2f
}

/** Đổi phông cho một đoạn chữ. */
private class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }
}

/**
 * Gắn phông Hán Nôm cho các chữ ngoài BMP (CJK mở rộng B trở đi), chữ còn lại giữ phông của ô chữ (Roboto).
 * Không đặt cả ô chữ sang phông Hán Nôm vì MiSans L3 có dấu cách rộng kiểu chữ Hán.
 */
fun applyHanNomFont(text: Spannable, typeface: Typeface) {
    text.getSpans(0, text.length, FontSpan::class.java).forEach(text::removeSpan)
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        if (cp < 0x20000) {
            i += Character.charCount(cp)
            continue
        }
        var end = i
        while (end < text.length && Character.codePointAt(text, end) >= 0x20000) {
            end += Character.charCount(Character.codePointAt(text, end))
        }
        text.setSpan(FontSpan(typeface), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        i = end
    }
}

/** Bản có gắn phông Hán Nôm của [text], để đặt vào TextView. */
fun Context.hanNomText(text: CharSequence): CharSequence {
    val typeface = hanNomTypeface() ?: return text
    return SpannableString(text).also { applyHanNomFont(it, typeface) }
}

/**
 * TextView một dòng, chữ dài quá thì tự cắt ở cuối bằng "…" phông Latin (Roboto). Dấu "…" mà TextView tự chèn
 * (`ellipsize`) lấy phông của chữ Hán bị cắt nên trông như "。。。" (người dùng muốn 2026-11-15: dùng "…").
 */
class EllipsisTextView(context: Context) : TextView(context) {
    /** Chữ đầy đủ (trước khi cắt). */
    private var source: CharSequence = ""
    /** Đang đặt chữ đã cắt (không phải chữ mới từ bên ngoài). */
    private var fitting = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!fitting) source = text ?: ""
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val full = source
        val avail = (measuredWidth - totalPaddingLeft - totalPaddingRight).toFloat()
        // Chỉ cắt chữ một dòng (nhiều dòng thì để TextView xuống dòng như thường).
        val fits = maxLines != 1 || avail <= 0f || android.text.Layout.getDesiredWidth(full, paint) <= avail
        val shown: CharSequence = if (fits) full else {
            fun cut(n: Int): CharSequence = android.text.SpannableStringBuilder(full, 0, n).append(
                SpannableString(ELLIPSIS).apply { setSpan(FontSpan(Typeface.DEFAULT), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) },
            )
            // Phần đầu dài nhất còn vừa (không cắt giữa cặp surrogate).
            var lo = 0
            var hi = full.length
            while (lo < hi) {
                val m = (lo + hi + 1) / 2
                if (android.text.Layout.getDesiredWidth(cut(m), paint) <= avail) lo = m else hi = m - 1
            }
            if (lo in 1 until full.length && Character.isLowSurrogate(full[lo])) lo--
            cut(lo)
        }
        // Chỉ đặt lại khi khác chữ đang hiện, kẻo đo lại mãi.
        if (shown.toString() != text.toString()) {
            fitting = true
            super.setText(shown, BufferType.SPANNABLE)
            fitting = false
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private companion object {
        const val ELLIPSIS = "…"
    }
}

/** Cho ô nhập: gắn lại phông Hán Nôm mỗi khi chữ thay đổi. */
fun TextView.useHanNomFont() {
    val typeface = context.hanNomTypeface() ?: return
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) = applyHanNomFont(s, typeface)
    })
}

private object FontCache {
    private const val BUNDLED_NAME = "MiSans L3.ttf"
    private const val BUNDLED = "fonts/$BUNDLED_NAME"
    /** Chữ Nôm mà [BUNDLED] thiếu (CJK mở rộng G–J…), cắt từ Jigmo bằng `tools/fonts/extra.py`. */
    private const val EXTRA = "fonts/hannom-extra.ttf"
    /** Tệp phông riêng của bản cũ (đã bỏ tính năng chọn phông Hán Nôm riêng). */
    private const val OLD_CUSTOM = "hannom_font.ttf"
    private var loaded = false
    private var typeface: Typeface? = null

    fun get(context: Context): Typeface? {
        if (Settings.systemFont(context)) return null
        if (!loaded) {
            loaded = true
            File(context.filesDir, OLD_CUSTOM).delete()
            typeface = runCatching { load(context) }.getOrNull()
        }
        return typeface
    }

    /** [BUNDLED]; từ Android 10 thêm [EXTRA] làm phông dự phòng để chữ phông chính thiếu không thành ô trắng. */
    private fun load(context: Context): Typeface? {
        val bundled = context.assets.list("fonts").orEmpty()
        if (BUNDLED_NAME !in bundled) return null
        if (android.os.Build.VERSION.SDK_INT >= 29 && "hannom-extra.ttf" in bundled) {
            runCatching {
                return Typeface.CustomFallbackBuilder(FontFamily.Builder(Font.Builder(context.assets, BUNDLED).build()).build())
                    .addCustomFallback(FontFamily.Builder(Font.Builder(context.assets, EXTRA).build()).build())
                    .setSystemFallback("sans-serif")
                    .build()
            }
        }
        return Typeface.createFromAsset(context.assets, BUNDLED)
    }
}
