package com.hannom.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

/**
 * Bảng giữ ô của emoji hai người (🤝, 👫, 💏, 💑, 🤼...), theo Gboard (đo trên điện thoại):
 * - hàng 1: 5 màu da cho người / bàn tay bên trái, người kia vẽ xám;
 * - hàng 2: 5 màu da cho người bên phải, người kia vẽ xám;
 * - hàng 3: bản mặc định (vàng) và ô ghép xem trước.
 *
 * Chạm ô ở hàng 1, 2 chỉ chọn (tô nền ô) và cập nhật ô ghép; chạm ô ghép khi đã chọn đủ hai người thì chèn, chưa đủ
 * thì không có gì. Hai màu giống nhau ra bản một màu (🤝🏼). Mỗi lần mở bảng đều chưa chọn gì, Gboard không nhớ.
 *
 * [tones] là bảng giữ ô trong `emoji.txt`: hàng 0 là bản gốc rồi 5 bản đồng màu, hàng 1 + i là người trái màu i,
 * cột j là người phải màu j.
 */
class EmojiPair private constructor(private val tones: List<List<String>>) {
    private var left: Int? = null
    private var right: Int? = null

    val default get() = tones[0][0]

    private fun emoji(l: Int, r: Int) = tones[l + 1][r]

    /** Bề rộng, chiều cao bảng với ô [cellW] × [cellH]. */
    fun width(cellW: Float) = cellW * TONES
    fun height(cellH: Float) = cellH * 3

    /**
     * Chạm điểm ([x], [y]) tính từ góc bảng: chọn màu, hay trả emoji cần chèn (bản mặc định; ô ghép khi đã đủ hai
     * người). Trả null nếu chỉ chọn hoặc không có gì.
     */
    fun tap(x: Float, y: Float, cellW: Float, cellH: Float): String? {
        val row = (y / cellH).toInt()
        val col = (x / cellW).toInt().coerceIn(0, TONES - 1)
        when (row) {
            0 -> left = col
            1 -> right = col
            2 -> return if (x < width(cellW) / 2) default else {
                val l = left
                val r = right
                if (l != null && r != null) emoji(l, r) else null
            }
        }
        return null
    }

    /** Vẽ bảng (không kể nền) tại ([ox], [oy]). */
    fun draw(canvas: Canvas, ox: Float, oy: Float, cellW: Float, cellH: Float, theme: KeyboardTheme, paint: Paint) {
        val size = minOf(cellW, cellH) * 0.6f
        val pad = minOf(cellW, cellH) * 0.06f
        for (i in 0 until TONES) {
            for ((row, chosen) in listOf(left, right).withIndex()) {
                val cx = ox + cellW * (i + 0.5f)
                val cy = oy + cellH * (row + 0.5f)
                if (chosen == i) {
                    paint.color = theme.text and 0x00FFFFFF or 0x40000000
                    val r = RectF(ox + cellW * i + pad, oy + cellH * row + pad, ox + cellW * (i + 1) - pad, oy + cellH * (row + 1) - pad)
                    canvas.drawRoundRect(r, cellW * 0.2f, cellW * 0.2f, paint)
                }
                val bmp = if (row == 0) half(i, null, size) else half(null, i, size)
                canvas.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, null)
            }
        }
        val cy = oy + cellH * 2.5f
        val w = width(cellW)
        paint.color = theme.text
        paint.textSize = size
        val dy = -(paint.ascent() + paint.descent()) / 2
        canvas.drawText(default, ox + w / 4, cy + dy, paint)
        val l = left
        val r = right
        if (l != null && r != null) {
            canvas.drawText(emoji(l, r), ox + w * 3 / 4, cy + dy, paint)
        } else {
            val bmp = half(l, r, size)
            canvas.drawBitmap(bmp, ox + w * 3 / 4 - bmp.width / 2f, cy - bmp.height / 2f, null)
        }
    }

    /**
     * Ảnh emoji với người trái màu [l], người phải màu [r]; bên nào null thì vẽ xám. Unicode không có emoji "một người
     * xám" nên tự dựng, cả người (da, tóc, áo quần) như Gboard:
     * 1. vẽ các bản chỉ khác màu da của một người: điểm ảnh nào đổi theo là da / tóc của người đó;
     * 2. từ da / tóc của hai người loang dần qua các điểm ảnh liền khối (áo, quần, giày), điểm nào gần người nào hơn
     *    thì thuộc người đó. Phần tách rời (trái tim của 💏, 💑) không thuộc ai, giữ màu.
     */
    private fun half(l: Int?, r: Int?, size: Float): Bitmap {
        val key = "$default|$l|$r|${size.toInt()}"
        cache.get(key)?.let { return it }
        // Màu dựng người xám: khác màu người kia (bản cùng màu là emoji khác, hình khác). So với mọi màu còn lại để
        // bắt đủ da lẫn tóc (tóc chỉ đổi giữa vài màu da).
        val lb = l ?: BASE.first { it != r }
        val rb = r ?: BASE.first { it != lb }
        val base = render(emoji(lb, rb), size)
        val w = base.width
        val px = IntArray(w * base.height)
        base.getPixels(px, 0, w, 0, 0, w, base.height)
        // 0: chưa thuộc ai, LEFT / RIGHT: người trái / phải.
        val owner = ByteArray(px.size)
        val queue = IntArray(px.size)
        var tail = 0
        fun mark(alt: String, who: Byte) {
            val a = render(alt, size)
            val ap = IntArray(px.size)
            a.getPixels(ap, 0, w, 0, 0, w, a.height)
            for (k in px.indices) if (owner[k] == NONE && differ(px[k], ap[k])) {
                owner[k] = who
                queue[tail++] = k
            }
        }
        for (t in 0 until TONES) if (t != lb && t != rb) {
            mark(emoji(t, rb), LEFT)
            mark(emoji(lb, t), RIGHT)
        }
        // Loang theo chiều rộng từ cả hai người cùng lúc: mỗi điểm về người tới trước, tức người gần hơn.
        var head = 0
        while (head < tail) {
            val k = queue[head++]
            val x = k % w
            for (n in intArrayOf(k - w, k + w, if (x > 0) k - 1 else -1, if (x < w - 1) k + 1 else -1)) {
                if (n < 0 || n >= px.size || owner[n] != NONE || (px[n] ushr 24) < OPAQUE || pink(px[n])) continue
                owner[n] = owner[k]
                queue[tail++] = n
            }
        }
        // Chưa chọn ai: Gboard vẽ xám cả hình, kể cả trái tim.
        val all = l == null && r == null
        for (k in px.indices) if (all || (owner[k] == LEFT && l == null) || (owner[k] == RIGHT && r == null)) {
            val c = px[k]
            val lum = (0.3f * (c shr 16 and 0xFF) + 0.59f * (c shr 8 and 0xFF) + 0.11f * (c and 0xFF))
            val g = (140 + lum * 0.3f).toInt()
            px[k] = (c and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
        }
        base.setPixels(px, 0, w, 0, 0, w, base.height)
        cache.put(key, base)
        return base
    }

    private fun render(text: String, size: Float): Bitmap {
        val side = (size * 1.4f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = size
        }
        Canvas(bmp).drawText(text, side / 2f, side / 2f - (p.ascent() + p.descent()) / 2, p)
        return bmp
    }

    /** Màu hồng của trái tim (💏, 💑): không loang qua, tim giữ màu như Gboard. */
    private fun pink(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return r > 180 && b > 80 && r - g > 70
    }

    private fun differ(a: Int, b: Int): Boolean {
        var d = 0
        for (s in intArrayOf(24, 16, 8, 0)) d += kotlin.math.abs((a shr s and 0xFF) - (b shr s and 0xFF))
        return d > 48
    }

    companion object {
        private const val TONES = 5
        /** Màu da dựng người xám, theo thứ tự ưu tiên (màu giữa cho bóng xám vừa phải). */
        private val BASE = listOf(2, 1, 3)
        private const val NONE: Byte = 0
        private const val LEFT: Byte = 1
        private const val RIGHT: Byte = 2
        /** Điểm ảnh đủ đậm (alpha) mới tính là liền khối khi loang. */
        private const val OPAQUE = 40
        private val MODIFIERS = 0x1F3FB..0x1F3FF
        private val cache = LruCache<String, Bitmap>(64)

        /** Bảng của emoji hai người (bản có hai màu da khác nhau); emoji khác thì null. */
        fun of(tones: List<List<String>>?): EmojiPair? = if (isPair(tones)) EmojiPair(tones!!) else null

        fun isPair(tones: List<List<String>>?): Boolean {
            if (tones == null || tones.size != TONES + 1 || (1..TONES).any { tones[it].size != TONES }) return false
            return tones[1][1].codePoints().filter { it in MODIFIERS }.distinct().count() == 2L
        }
    }
}
