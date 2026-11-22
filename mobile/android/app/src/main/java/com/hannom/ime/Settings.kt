package com.hannom.ime

import android.content.Context

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

    /** Tên trong ứng dụng cài đặt, theo ngôn ngữ ứng dụng ([AppText]). */
    fun titleIn(context: Context): String = when (this) {
        // Bản Hán Nôm: tiếng Việt 㗂越, tiếng Anh 㗂英 (người dùng đặt 2026-11-18).
        QUOC_NGU -> context.tr(title, "Vietnamese (Latin)", "㗂越 (Latin)")
        HAN_NOM -> title
        ENGLISH -> context.tr(title, title, "㗂英 (US)")
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

    /** [title] theo ngôn ngữ ứng dụng ([AppText]). */
    fun titleIn(mode: Mode, context: Context): String =
        if (this == HANDWRITING) context.tr("Viết tay", "Handwriting", "𢪏𢬣") else title(mode)

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

    /** Âm lượng tiếng gõ phím (%, 0–100); [DEFAULT] là mức của hệ thống. */
    var volume: Int
        get() = prefs.getInt("volume", DEFAULT).coerceIn(DEFAULT, 100)
        set(v) = prefs.edit().putInt("volume", v).apply()

    /** Cường độ rung khi nhấn phím: thời gian rung (ms, 1–100) như Gboard; [DEFAULT] là mặc định của hệ thống. */
    var vibrationMs: Int
        get() = prefs.getInt("vibration_ms", DEFAULT).coerceIn(DEFAULT, MAX_VIBRATION_MS)
        set(v) = prefs.edit().putInt("vibration_ms", v).apply()

    /** Trang chữ có hàng số (như Gboard, mặc định bật). */
    var numberRow: Boolean
        get() = prefs.getBoolean("number_row", true)
        set(v) = prefs.edit().putBoolean("number_row", v).apply()

    /** Tắt [numberRow] mà ô đang gõ là ô mật khẩu thì vẫn hiện hàng số. */
    var numberRowPassword: Boolean
        get() = prefs.getBoolean("number_row_password", false)
        set(v) = prefs.edit().putBoolean("number_row_password", v).apply()

    /**
     * "Phông chữ" (như Gboard): phông mặc định của hệ thống thay cho phông đi kèm của bàn phím, cho cả chữ Latin
     * (thay Roboto) và chữ Hán Nôm (thay MiSans L3).
     */
    var systemFont: Boolean
        get() = prefs.getBoolean("system_font", false)
        set(v) = prefs.edit().putBoolean("system_font", v).apply()

    /** Cỡ chữ trên phím (%), một trong [FONT_SIZES]; [DEFAULT] là "Giống như thiết bị" (theo cỡ chữ hệ thống). */
    var fontSize: Int
        get() = prefs.getInt("font_size", DEFAULT).let { if (it in FONT_SIZES) it else DEFAULT }
        set(v) = prefs.edit().putInt("font_size", v).apply()

    /** Kích thước emoji: nấc 0–6 của [EMOJI_COLUMNS], mặc định [DEFAULT_EMOJI_SIZE]. */
    var emojiSize: Int
        get() = prefs.getInt("emoji_size", DEFAULT_EMOJI_SIZE).coerceIn(0, EMOJI_COLUMNS.lastIndex)
        set(v) = prefs.edit().putInt("emoji_size", v).apply()

    /** Hệ số cỡ chữ trên phím theo [fontSize]: "Giống như thiết bị" lấy cỡ chữ của hệ thống. */
    fun labelScale(context: Context): Float =
        if (fontSize == DEFAULT) context.resources.configuration.fontScale else fontSize / 100f

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
        get() = prefs.getInt("one_hand_shift", DEFAULT_ONE_HAND_SHIFT).coerceAtLeast(0)
        set(v) = prefs.edit().putInt("one_hand_shift", v).apply()

    /**
     * Bàn phím một tay (màn hình dọc): chiều cao hàng phím (dp) và khoảng trống dưới đáy (dp) đã kéo trong "Kích thước",
     * nhớ riêng với bàn phím đầy đủ (người dùng muốn 2026-11-18): bấm phóng to thì bàn phím đầy đủ trở lại đúng cỡ và
     * chỗ cũ. Chưa chỉnh lần nào thì theo bàn phím đầy đủ, thấp hơn như Gboard: bước hàng chữ một tay mặc định ~148,75
     * px so với 161 px của bàn phím đầy đủ (đo 2026-11-22), tức 42,5 dp khi bàn phím đầy đủ ở mức "Vừa". Lưu số lẻ (dp):
     * làm tròn tới 1 dp là lệch Gboard 1–2 px mỗi hàng. Bản cũ lưu số nguyên ở "one_hand_row_height" vẫn đọc được.
     */
    var oneHandRowHeight: Float
        get() = (
            if (prefs.contains("one_hand_row")) prefs.getFloat("one_hand_row", 0f)
            else if (prefs.contains("one_hand_row_height")) prefs.getInt("one_hand_row_height", 0).toFloat()
            else rowHeight * ONE_HAND_ROW_SHARE
            ).coerceIn(MIN_ROW_HEIGHT.toFloat(), MAX_ROW_HEIGHT.toFloat())
        set(v) = prefs.edit().putFloat("one_hand_row", v).remove("one_hand_row_height").apply()
    var oneHandBottomGap: Int
        get() = prefs.getInt("one_hand_bottom_gap", bottomGap).coerceIn(0, MAX_BOTTOM_GAP)
        set(v) = prefs.edit().putInt("one_hand_bottom_gap", v).apply()

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

    /** Giao diện bàn phím đang dùng ([ThemeSpec.id]); mặc định tự động sáng / tối theo hệ thống. */
    var themeId: String
        get() = prefs.getString("theme", null) ?: ThemeSpec.SYSTEM
        set(v) = prefs.edit().putString("theme", v).apply()

    /** "Đường viền phím" của giao diện đang dùng. */
    var keyBorders: Boolean
        get() = prefs.getBoolean("key_borders", true)
        set(v) = prefs.edit().putBoolean("key_borders", v).apply()

    /**
     * "Giao diện của tôi": chỉ các giao diện đã áp dụng từ mục "Màu" (mới nhất trước; người dùng quyết định
     * 2026-11-18), mỗi mục `id:1` (có viền) hoặc `id:0`. Không có giao diện ảnh riêng.
     */
    var myThemes: List<Pair<String, Boolean>>
        get() = prefs.getString("my_themes", null)?.split(',')?.mapNotNull {
            val (id, b) = it.split(':').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            (id to (b == "1")).takeIf { ThemeSpec.isColor(id) }
        }.orEmpty()
        set(v) = prefs.edit().putString("my_themes", v.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" }).apply()

    /** Áp dụng giao diện; giao diện từ mục "Màu" thì đưa lên đầu "Giao diện của tôi". */
    fun applyTheme(id: String, borders: Boolean) {
        themeId = id
        keyBorders = borders
        if (ThemeSpec.isColor(id)) myThemes = listOf(id to borders) + myThemes.filter { it != id to borders }
    }

    /** Xoá một mục khỏi "Giao diện của tôi"; đang dùng chính nó thì trở về giao diện tự động theo hệ thống. */
    fun removeMyTheme(id: String, borders: Boolean) {
        myThemes = myThemes.filter { it != id to borders }
        if (themeId == id && keyBorders == borders) {
            themeId = ThemeSpec.SYSTEM
            keyBorders = true
        }
    }

    companion object {
        /**
         * Khoảng trống dưới bàn phím tối đa (dp), chỉ để chặn giá trị hỏng; lúc kéo thì giới hạn thật là đỉnh bàn phím
         * không lên quá [InputRoot.MAX_SHARE] màn hình.
         */
        const val MAX_BOTTOM_GAP = 480
        /**
         * Chiều cao hàng phím (dp) của các mức thấp, vừa, cao. "Vừa" bằng Gboard cỡ mặc định (đo trên điện thoại
         * 2026-11-18: bước hàng chữ 46 dp; trước là 50).
         */
        val ROW_HEIGHTS = intArrayOf(40, 46, 52)
        /** Hàng chữ màn hình dọc thấp / cao nhất (dp): Gboard 111 / 261 px = 31,7 / 74,6 dp (đo 2026-11-22; trước 36 / 72). */
        const val MIN_ROW_HEIGHT = 32
        const val MIN_LANDSCAPE_ROW_HEIGHT = 25
        const val MAX_ROW_HEIGHT = 75
        /**
         * Bề rộng bàn phím một tay so với màn hình: mặc định = lớn nhất (Gboard: khung 1075 / 1240 px, đo 2026-11-22;
         * trước 0,87) và nhỏ nhất (Gboard 75%: khung 930 / 1240 px, đo 2026-11-18).
         */
        const val DEFAULT_ONE_HAND_WIDTH = 0.867f
        const val MIN_ONE_HAND_WIDTH = 0.75f
        /**
         * Bàn phím một tay mặc định: hàng chữ [DEFAULT_ONE_HAND_ROW] dp (= [ONE_HAND_ROW_SHARE] × hàng "Vừa"), cách mép
         * màn hình [DEFAULT_ONE_HAND_SHIFT] dp (Gboard: khung cách mép 14 px, kéo ✥ thì sát mép được). Đo 2026-11-22.
         */
        const val ONE_HAND_ROW_SHARE = 42.5f / 46f
        const val DEFAULT_ONE_HAND_ROW = 42.5f
        const val DEFAULT_ONE_HAND_SHIFT = 4

        /** Giá trị "mặc định của hệ thống" cho [volume], [vibrationMs], [fontSize]. */
        const val DEFAULT = -1
        const val MAX_VIBRATION_MS = 100
        /**
         * Các nấc "Cỡ chữ" của Gboard (đo trên điện thoại 2026-11-18): 85%, giống như thiết bị, 115–200%.
         */
        val FONT_SIZES = intArrayOf(85, DEFAULT, 115, 130, 150, 180, 200)
        /**
         * Số emoji một hàng (màn hình dọc) theo nấc "Kích thước biểu tượng cảm xúc" của Gboard (đo trên điện thoại
         * 2026-11-18, màn hình 354 dp; mặc định nấc 1 = 8 emoji).
         */
        val EMOJI_COLUMNS = intArrayOf(9, 8, 8, 7, 7, 6, 5)
        /**
         * Hệ số cỡ emoji (so với cỡ thường của số cột đó) theo nấc: nấc "8+" và "7+" giữ số cột nhưng emoji to hơn một
         * chút. Đo trên Gboard (điện thoại 2026-11-18): 😂 rộng 121 → 128 px ở 8 / 8+ cột, 137 → 150 px ở 7 / 7+ cột.
         */
        val EMOJI_SCALE = floatArrayOf(1f, 1f, 128f / 121f, 1f, 150f / 137f, 1f, 1f)
        const val DEFAULT_EMOJI_SIZE = 1

        /** Đọc nhanh [systemFont] không cần dựng [Settings] (dùng khi vẽ chữ). */
        fun systemFont(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("system_font", false)
    }
}

/**
 * Rung khi nhấn phím: [ms] là [Settings.DEFAULT] thì rung kiểu gõ phím của hệ thống, không thì rung đúng chừng đó mili
 * giây ("Cường độ rung khi nhấn phím" của Gboard).
 */
fun keyVibrate(context: Context, view: android.view.View?, ms: Int) {
    if (ms == Settings.DEFAULT || ms <= 0) {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        return
    }
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    } ?: return
    vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
}

/** Tiếng gõ phím: [volume] (%) là [Settings.DEFAULT] thì theo âm lượng hệ thống. */
fun keyClick(context: Context, volume: Int) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    if (volume == Settings.DEFAULT) audio.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
    else audio.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, volume / 100f)
}
