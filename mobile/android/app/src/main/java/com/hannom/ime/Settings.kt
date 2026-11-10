package com.hannom.ime

import android.content.Context
import java.io.File

/** Ngôn ngữ bàn phím: nhãn phím chế độ, nhãn phím cách, tên trong cài đặt. */
enum class Mode(val label: String, val spaceLabel: String, val title: String) {
    QUOC_NGU("V", "Tiếng Việt", "Tiếng Việt (Latinh)"),
    HAN_NOM("喃", "㗂越 (漢喃)", "㗂越 (漢喃)"),
    ENGLISH("EN", "English", "English (US)");

    /** Chữ giao diện theo ngôn ngữ này; ngôn ngữ thêm sau này chưa có chữ riêng thì mặc nhiên tiếng Anh [en]. */
    fun <T> say(vi: T, nom: T, en: T): T = when (this) {
        QUOC_NGU -> vi
        HAN_NOM -> nom
        else -> en
    }
}

/** Kiểu bàn phím của một ngôn ngữ. */
enum class Layout {
    QWERTY,
    /** Hán Nôm gõ âm đọc bằng Telex tự do như Unikey (aa → â, dấu gõ đâu cũng được), cho người mới bắt đầu. */
    TELEX,
    HANDWRITING;

    fun title(mode: Mode) = when (this) {
        QWERTY -> if (mode == Mode.ENGLISH) "QWERTY" else "QWERTY (Telex)"
        TELEX -> "QWERTY (Telex)"
        HANDWRITING -> "Viết tay"
    }

    /** Hán Nôm gõ bằng [TELEX] hoặc viết tay (mô hình chỉ nhận chữ Hán Nôm); ngôn ngữ khác dùng [QWERTY]. */
    fun available(mode: Mode) = (this == QWERTY) != (mode == Mode.HAN_NOM)
}

/** Bàn phím một tay: [LEFT] / [RIGHT] là bên phím dồn về, bên kia là cột nút (như Gboard). */
enum class OneHand { OFF, LEFT, RIGHT }

/** Một bàn phím trong danh sách "Ngôn ngữ": ngôn ngữ và kiểu bàn phím (như Gboard). */
data class Keyboard(val mode: Mode, val layout: Layout) {
    val id get() = "${mode.name}:${layout.name}"

    companion object {
        val DEFAULT = Mode.entries.map { Keyboard(it, if (it == Mode.HAN_NOM) Layout.TELEX else Layout.QWERTY) }

        fun parse(id: String): Keyboard? = runCatching {
            val (m, l) = id.split(':')
            val mode = Mode.valueOf(m)
            // Bản cũ có Hán Nôm QWERTY (kiểu gõ đặc biệt), nay đã bỏ: chuyển sang Telex.
            val layout = Layout.valueOf(l).let { if (mode == Mode.HAN_NOM && it == Layout.QWERTY) Layout.TELEX else it }
            Keyboard(mode, layout)
        }.getOrNull()
    }
}

/** Cài đặt người dùng; giá trị hỏng hoặc thiếu thì dùng mặc định. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir

    /** Các bàn phím đã thêm, theo thứ tự phím chế độ chuyển qua; luôn có ít nhất một. */
    var keyboards: List<Keyboard>
        get() = prefs.getString("keyboards", null)?.split(',')?.mapNotNull(Keyboard::parse)
            ?.filter { it.layout.available(it.mode) }?.distinct()?.takeIf { it.isNotEmpty() } ?: Keyboard.DEFAULT
        set(v) = prefs.edit().putString("keyboards", v.joinToString(",") { it.id }).apply()

    /** Bàn phím đang dùng; đã bị xoá khỏi danh sách thì là bàn phím đầu tiên. */
    var keyboard: Keyboard
        get() {
            val list = keyboards
            // Bản cũ chỉ lưu ngôn ngữ ("mode").
            val saved = prefs.getString("keyboard", null)?.let(Keyboard::parse)
                ?: prefs.getString("mode", null)?.let { Keyboard.parse("$it:${Layout.QWERTY.name}") }
            return saved?.takeIf { it in list } ?: list.first()
        }
        set(v) = prefs.edit().putString("keyboard", v.id).apply()

    val mode: Mode get() = keyboard.mode

    /** Chuyển sang bàn phím kế tiếp trong danh sách (phím chế độ). */
    fun nextKeyboard(): Keyboard {
        val list = keyboards
        return list[(list.indexOf(keyboard) + 1) % list.size].also { keyboard = it }
    }

    /** 0: kiểu cũ (hòa), 1: kiểu mới (hoà). */
    var toneStyle: Int
        get() = prefs.getInt("tone_style", 0).coerceIn(0, 1)
        set(v) = prefs.edit().putInt("tone_style", v).apply()

    var learning: Boolean
        get() = prefs.getBoolean("learning", true)
        set(v) = prefs.edit().putBoolean("learning", v).apply()

    /** Emoji dùng gần đây, mới nhất trước ([EmojiPanel]). */
    var recentEmoji: List<String>
        get() = prefs.getString("recent_emoji", null)?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = prefs.edit().putString("recent_emoji", v.joinToString(" ")).apply()

    /** Biểu tượng chữ (trang `:-)`) dùng gần đây, mới nhất trước; cách nhau dòng mới vì có thể chứa dấu cách. */
    var recentEmoticons: List<String>
        get() = prefs.getString("recent_emoticons", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = prefs.edit().putString("recent_emoticons", v.joinToString("\n")).apply()

    /** Giới tính chọn lần trước trong bảng giữ ô emoji, áp cho mọi ô: 0 trung tính, 1 nam, 2 nữ. */
    var emojiGender: Int
        get() = prefs.getInt("emoji_gender", 0).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("emoji_gender", v).apply()

    /** Màu da chọn lần trước, áp cho mọi ô: 0 vàng mặc định, 1..5 sáng → tối. */
    var emojiTone: Int
        get() = prefs.getInt("emoji_tone", 0).coerceIn(0, 5)
        set(v) = prefs.edit().putInt("emoji_tone", v).apply()

    var autoCap: Boolean
        get() = prefs.getBoolean("auto_cap", true)
        set(v) = prefs.edit().putBoolean("auto_cap", v).apply()

    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", false)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    /** Thanh công cụ và bảng tiện ích (nút ⊞): nút ghim, nút góc phải, thứ tự các ô còn lại; kéo thả để đổi. */
    var tools: ToolLayout
        get() {
            fun ids(key: String) = prefs.getString(key, null)?.split(',')?.mapNotNull { runCatching { ToolId.valueOf(it) }.getOrNull() }
            val row = ids("tool_row")
            if (row == null) {
                // Bản cũ chỉ lưu một danh sách nút ghim ("toolbar"): Cài đặt (hay nút cuối) thành nút góc.
                val old = ids("toolbar") ?: return ToolLayout.DEFAULT
                val corner = if (ToolId.SETTINGS in old) ToolId.SETTINGS else old.lastOrNull()
                return ToolLayout.of(old - setOfNotNull(corner), corner, emptyList())
            }
            val corner = prefs.getString("tool_corner", null)?.let { runCatching { ToolId.valueOf(it) }.getOrNull() }
            return ToolLayout.of(row, corner, ids("tool_grid").orEmpty())
        }
        set(v) = prefs.edit()
            .putString("tool_row", v.row.joinToString(",") { it.name })
            .putString("tool_corner", v.corner?.name ?: "")
            .putString("tool_grid", v.grid.joinToString(",") { it.name })
            .apply()

    /** Bảng nhớ tạm: giữ các mục sao chép gần nhất (như Gboard, mặc định tắt). */
    var clipboard: Boolean
        get() = prefs.getBoolean("clipboard", false)
        set(v) = prefs.edit().putBoolean("clipboard", v).apply()

    /** Bàn phím một tay (giữ phím phẩy, như Gboard): tắt, hay phím dồn về bên trái / phải. */
    var oneHand: OneHand
        get() = runCatching { OneHand.valueOf(prefs.getString("one_hand", null)!!) }.getOrDefault(OneHand.OFF)
        set(v) = prefs.edit().putString("one_hand", v.name).remove("one_hand_side").apply()

    /** Bàn phím một tay: bề rộng so với màn hình (chỉnh trong khung "Kích thước"). */
    var oneHandWidth: Float
        get() = prefs.getFloat("one_hand_width", DEFAULT_ONE_HAND_WIDTH).coerceIn(MIN_ONE_HAND_WIDTH, 1f)
        set(v) = prefs.edit().putFloat("one_hand_width", v).apply()

    /** Bàn phím một tay: khoảng cách (dp) từ mép màn hình phía bàn phím dồn về (kéo ✥ sang ngang). */
    var oneHandShift: Int
        get() = prefs.getInt("one_hand_shift", 0).coerceAtLeast(0)
        set(v) = prefs.edit().putInt("one_hand_shift", v).apply()

    /** Khoảng trống thêm dưới bàn phím (dp), cho máy bị thanh điều hướng che hàng phím dưới cùng. */
    var bottomGap: Int
        get() = prefs.getInt("bottom_gap", 0).coerceIn(0, MAX_BOTTOM_GAP)
        set(v) = prefs.edit().putInt("bottom_gap", v).apply()

    /** Mức chiều cao chọn trong cài đặt: 0 thấp, 1 vừa, 2 cao; kéo khung "Kích thước" thì thành chiều cao tuỳ chỉnh. */
    var height: Int
        get() = prefs.getInt("height", 1).coerceIn(0, 2)
        set(v) = prefs.edit().putInt("height", v).remove("row_height").apply()

    /** Chiều cao một hàng phím (dp): theo [height], hoặc giá trị kéo trong chế độ "Kích thước". */
    var rowHeight: Int
        get() = prefs.getInt("row_height", ROW_HEIGHTS[height]).coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT)
        set(v) = prefs.edit().putInt("row_height", v).apply()

    /** Chiều cao đã kéo tay, không trùng mức nào trong [ROW_HEIGHTS]. */
    val customHeight: Boolean get() = prefs.contains("row_height") && rowHeight != ROW_HEIGHTS[height]

    /** Màn hình ngang: chiều cao hàng chữ (dp) đã kéo trong "Kích thước", tách khỏi màn hình dọc; 0 là chưa chỉnh. */
    var landscapeRowHeight: Int
        get() = prefs.getInt("landscape_row_height", 0).coerceIn(0, MAX_ROW_HEIGHT)
        set(v) = prefs.edit().putInt("landscape_row_height", v).apply()

    /** Màn hình ngang: khoảng trống thêm dưới bàn phím (dp), tách khỏi [bottomGap] của màn hình dọc. */
    var landscapeBottomGap: Int
        get() = prefs.getInt("landscape_bottom_gap", 0).coerceIn(0, MAX_BOTTOM_GAP)
        set(v) = prefs.edit().putInt("landscape_bottom_gap", v).apply()

    /** Phông Hán Nôm người dùng chọn, chép vào thư mục ứng dụng. */
    val fontFile: File get() = File(filesDir, "hannom_font.ttf")

    companion object {
        /**
         * Khoảng trống dưới bàn phím tối đa (dp), chỉ để chặn giá trị hỏng; lúc kéo thì giới hạn thật là đỉnh bàn phím
         * không lên quá [InputRoot.MAX_SHARE] màn hình.
         */
        const val MAX_BOTTOM_GAP = 480
        /** Chiều cao hàng phím (dp) của các mức thấp, vừa, cao. */
        val ROW_HEIGHTS = intArrayOf(44, 50, 56)
        const val MIN_ROW_HEIGHT = 36
        const val MIN_LANDSCAPE_ROW_HEIGHT = 24
        const val MAX_ROW_HEIGHT = 72
        /** Bề rộng bàn phím một tay so với màn hình: mặc định (Gboard chừng 87%) và nhỏ nhất. */
        const val DEFAULT_ONE_HAND_WIDTH = 0.87f
        const val MIN_ONE_HAND_WIDTH = 0.55f
    }
}
