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
) {
    companion object {
        fun of(context: Context): KeyboardTheme {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                // Lấy mẫu từ Gboard giao diện tối mặc định.
                KeyboardTheme(
                    background = Color.parseColor("#292C35"),
                    key = Color.parseColor("#474A53"),
                    specialKey = Color.parseColor("#373A43"),
                    pressedKey = Color.parseColor("#62656E"),
                    text = Color.parseColor("#E8EAED"),
                    hintText = Color.parseColor("#9AA0A6"),
                    accent = Color.parseColor("#5D95EF"),
                    onAccent = Color.WHITE,
                    bubble = Color.parseColor("#5A5D66"),
                    popup = Color.parseColor("#22252D"),
                    composeBox = Color.parseColor("#3C3F48"),
                )
            } else {
                KeyboardTheme(
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
                )
            }
        }
    }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()

fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * Phông hiển thị chữ Hán Nôm ngoài BMP (vd. 𡨸): phông người dùng chọn, nếu không thì phông
 * đi kèm `assets/fonts/hannom.ttf` (MiSans L3). `null` nếu không có phông nào.
 */
fun Context.hanNomTypeface(): Typeface? = FontCache.get(this)

/**
 * Roboto đóng gói kèm app, dùng cho mọi thành phần trừ chữ Hán Nôm (máy nhiều hãng đổi phông mặc định). Chỗ dùng
 * TextView thì đã có từ theme (`android:fontFamily`), hàm này cho các chỗ tự vẽ chữ bằng Paint.
 */
fun Context.robotoTypeface(): Typeface = resources.getFont(R.font.roboto)

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
    private const val BUNDLED = "fonts/hannom.ttf"
    /** Chữ Nôm mà [BUNDLED] thiếu (CJK mở rộng G–J…), cắt từ Jigmo bằng `tools/fonts/extra.py`. */
    private const val EXTRA = "fonts/hannom-extra.ttf"
    private var key: Long? = null
    private var typeface: Typeface? = null

    fun get(context: Context): Typeface? {
        val custom = Settings(context).fontFile
        val k = if (custom.exists()) custom.lastModified() else 0L
        if (k != key) {
            typeface = runCatching { load(context, custom.takeIf { it.exists() }) }.getOrNull()
            key = k
        }
        return typeface
    }

    /**
     * Phông chính (phông riêng người dùng chọn, không thì [BUNDLED]); từ Android 10 thêm [EXTRA] làm phông
     * dự phòng để chữ phông chính thiếu không thành ô trắng.
     */
    private fun load(context: Context, custom: File?): Typeface? {
        val bundled = context.assets.list("fonts").orEmpty()
        if (custom == null && "hannom.ttf" !in bundled) return null
        if (android.os.Build.VERSION.SDK_INT >= 29 && "hannom-extra.ttf" in bundled) {
            runCatching {
                val main = if (custom != null) Font.Builder(custom).build() else Font.Builder(context.assets, BUNDLED).build()
                return Typeface.CustomFallbackBuilder(FontFamily.Builder(main).build())
                    .addCustomFallback(FontFamily.Builder(Font.Builder(context.assets, EXTRA).build()).build())
                    .setSystemFallback("sans-serif")
                    .build()
            }
        }
        return if (custom != null) Typeface.createFromFile(custom) else Typeface.createFromAsset(context.assets, BUNDLED)
    }
}
