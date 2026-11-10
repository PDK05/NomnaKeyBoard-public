package com.hannom.ime

import android.graphics.RectF

enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, MODE, TO_SYMBOLS, TO_LETTERS, TO_SYMBOLS2, TO_NUMPAD, SPACER }

/** Kiểu trang phím theo chế độ gõ: Tiếng Việt và Tiếng Anh chép từ Gboard ([gboard]), Hán Nôm có bố cục riêng. */
enum class KeyboardStyle {
    HAN_NOM, VIETNAMESE, ENGLISH;

    val gboard get() = this != HAN_NOM

    /**
     * Chữ giao diện theo bàn phím đang dùng: Tiếng Việt [vi], 㗂越 (漢喃) [nom], English [en]. Bàn phím thêm sau này
     * chưa có chữ riêng thì mặc nhiên tiếng Anh.
     */
    fun <T> say(vi: T, nom: T, en: T): T = when (this) {
        VIETNAMESE -> vi
        HAN_NOM -> nom
        else -> en
    }
}

/**
 * Bảng ký tự khi giữ phím (như Gboard): [rows] từ trên xuống, ô rỗng ("") để trống; ô ([row], [col]) được
 * chọn sẵn và nằm ngay trên phím.
 */
class Popup(val rows: List<List<String>>, val row: Int, val col: Int) {
    val columns get() = rows.maxOf { it.size }

    fun at(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c).orEmpty()
}

class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val weight: Float = 1f,
    /** Ký tự hiện khi giữ phím, một hàng (mục đầu chọn sẵn); bảng nhiều hàng thì dùng [grid]. */
    alternates: List<String> = emptyList(),
    grid: Popup? = null,
    /** Bảng chỉ có ở chế độ tiếng Việt (chữ có dấu, đ). */
    val vietnameseOnly: Boolean = false,
    /** Phím ký tự tô màu phím chức năng (dấu phẩy, dấu chấm ở hàng dưới cùng). */
    val functional: Boolean = false,
    /** Phím bo tròn hẳn hai đầu, như ?123 và Enter của Gboard. */
    val isPill: Boolean = type == KeyType.ENTER || type == KeyType.TO_SYMBOLS || type == KeyType.TO_LETTERS,
    /** Icon nhỏ phía trên chữ của phím, chữ dịch xuống (vd. mặt cười trên phím phẩy: giữ để mở emoji). */
    val hintIcon: Int? = null,
    /** Phím trong cột phép tính của bàn phím số: chung một nền, xếp dọc ([KeyboardView] tự đặt chỗ). */
    val rail: Boolean = false,
    /** Chèn đúng nhãn, không đổi thành dấu câu Hán (các phím tính toán của bàn phím số). */
    val literal: Boolean = rail,
) {
    val popup: Popup? = grid ?: alternates.takeIf { it.isNotEmpty() }?.let { Popup(listOf(it), 0, 0) }
    val rect = RectF()
    val isSpecial get() = functional || (type != KeyType.CHAR && type != KeyType.SPACE)
}

/** Bố cục phím kiểu Gboard (có hàng số); mỗi hàng có tổng trọng số 10. */
object Layouts {
    /** Số hàng của trang chữ; mọi trang cao bằng nhau, trang ít hàng thì phím cao hơn. */
    const val MAX_ROWS = 5

    /** Các phím nguyên âm có bảng chọn chữ có dấu. */
    val VOWELS = setOf("a", "e", "i", "o", "u", "y")

    /** Bảng giữ phím `d` của bàn phím Quốc ngữ. */
    val STROKE_D = Popup(listOf(listOf("đ")), 0, 0)

    /**
     * Bảng giữ phím của chữ, xếp như Gboard tiếng Việt. Mọi chữ đều có chính nó (chọn sẵn, ngay trên phím) và
     * chữ hoa bên phải, riêng chữ sát mép phải (`o`, `p`, `l`) chữ hoa bên trái; nguyên âm có thêm các chữ có
     * dấu ở các hàng trên, `d` có thêm đ, Đ. Bật d/z (phím `d` là đ, `z` là d) thì giữ vẫn ra đúng chữ d, z.
     */
    private val letterPopups = mapOf(
        "a" to Popup(
            listOf(
                listOf("ẫ", "ậ", "", "", ""),
                listOf("ặ", "â", "ầ", "ấ", "ẩ"),
                listOf("ă", "ằ", "ắ", "ẳ", "ẵ"),
                listOf("à", "á", "ả", "ã", "ạ"),
                listOf("a", "A", "", "", ""),
            ),
            4, 0,
        ),
        "e" to Popup(
            listOf(
                listOf("ệ", "ể", "ễ", ""),
                listOf("ề", "ẹ", "ê", "ế"),
                listOf("ẻ", "è", "é", "ẽ"),
                listOf("", "e", "E", ""),
            ),
            3, 1,
        ),
        "i" to Popup(listOf(listOf("ị", "ĩ", "ỉ", "ì", "í"), listOf("", "", "", "i", "I")), 1, 3),
        "o" to Popup(
            listOf(
                listOf("", "", "", "ợ", "ỡ"),
                listOf("ở", "ớ", "ờ", "ơ", "ộ"),
                listOf("ỗ", "ổ", "ố", "ồ", "ô"),
                listOf("ọ", "õ", "ỏ", "ó", "ò"),
                listOf("", "", "", "O", "o"),
            ),
            4, 4,
        ),
        "u" to Popup(
            listOf(
                listOf("ự", "ử", "ữ", ""),
                listOf("ừ", "ụ", "ư", "ứ"),
                listOf("ủ", "ù", "ú", "ũ"),
                listOf("", "u", "U", ""),
            ),
            3, 1,
        ),
        "y" to Popup(listOf(listOf("ỵ", "ỷ", "ỳ", "ý", "ỹ"), listOf("", "", "y", "Y", "")), 1, 2),
        "d" to Popup(listOf(listOf("d", "D", "đ", "Đ")), 0, 0),
    ).let { vowels ->
        "qwertyuiopasdfghjklzxcvbnm".associate { ch ->
            val c = ch.toString()
            c to (vowels[c] ?: if (c == "l" || c == "p") Popup(listOf(listOf(c.uppercase(), c)), 0, 1)
            else Popup(listOf(listOf(c, c.uppercase())), 0, 0))
        }
    }

    /**
     * Bảng giữ nguyên âm của bàn phím Tiếng Việt (đo trên Gboard tiếng Việt): như [letterPopups] nhưng không có hàng
     * chữ không dấu / chữ hoa dưới cùng, ô chọn sẵn là chữ dấu huyền ngay trên phím.
     */
    val quocNguVowels = VOWELS.associateWith { v ->
        letterPopups.getValue(v).let { p -> Popup(p.rows.dropLast(1), p.rows.size - 2, p.col) }
    }

    /** Hàng số của bàn phím Tiếng Việt và Tiếng Anh khi bật Shift một lần (như Gboard); khoá chữ hoa thì vẫn là số. */
    private val shiftedDigits = "1234567890".zip("!@#$%^&*()").associate { (d, s) -> d.toString() to s.toString() }

    /** Hàng số của bàn phím Hán Nôm khi người dùng bấm Shift một lần, đo trên Gboard 拼音. */
    private val hanNomShiftedDigits = listOf("!", "@", "#", "¥", "%", "…", "&", "*", "(", ")")
        .withIndex().associate { (i, s) -> "1234567890"[i].toString() to s }

    /** Dấu thay phím số [digit] lúc bật Shift một lần; không đổi thì null. */
    fun shiftedDigit(digit: String, style: KeyboardStyle): String? =
        (if (style.gboard) shiftedDigits else hanNomShiftedDigits)[digit]

    private fun single(s: String) = Popup(listOf(listOf(s)), 0, 0)

    private fun oneRow(col: Int, vararg items: String) = Popup(listOf(items.toList()), 0, col)

    /** Bảng giữ hàng số lúc đang đổi thành dấu ([hanNomShiftedDigits]), đo trên Gboard 拼音; `…` không có bảng. */
    private val hanNomShiftPopups = mapOf(
        "!" to oneRow(0, "!", "¡"),
        "@" to single("＠"),
        "#" to single("＃"),
        "¥" to oneRow(0, "$", "＄"),
        "%" to single("％"),
        "&" to single("＆"),
        "*" to single("＊"),
        "(" to oneRow(4, "【", "［", "{", "<", "("),
        ")" to oneRow(4, "】", "］", "}", ">", ")"),
    )

    /** Bảng giữ phím chữ của bàn phím Tiếng Anh, đo trên Gboard tiếng Anh; chữ không có ở đây thì không có bảng. */
    val englishLetters = mapOf(
        "e" to Popup(listOf(listOf("ê", "é", "è", "ë", "ē")), 0, 1),
        "u" to Popup(listOf(listOf("ū", "ü", "ú", "û", "ù")), 0, 2),
        "i" to Popup(listOf(listOf("ì", "ī", "ï", "í", "î")), 0, 3),
        "o" to Popup(listOf(listOf("õ", "ō", "ø", "œ"), listOf("ò", "ö", "ô", "ó")), 1, 3),
        "a" to Popup(listOf(listOf("æ", "ã", "å", "ā"), listOf("à", "á", "â", "ä")), 1, 0),
        "s" to Popup(listOf(listOf("ß")), 0, 0),
        "c" to Popup(listOf(listOf("ç")), 0, 0),
        "n" to Popup(listOf(listOf("ñ")), 0, 0),
    )

    /**
     * Bảng giữ phím số, như Gboard: số mũ (chọn sẵn, ngay trên phím) và phân số; số không có phân số thì bảng
     * chỉ có số mũ.
     */
    private val digitPopups = mapOf(
        "1" to Popup(listOf(listOf("⅙", "⅐", "⅛", "⅑", "⅒"), listOf("¹", "½", "⅓", "¼", "⅕")), 1, 0),
        "2" to Popup(listOf(listOf("²", "⅔", "⅖")), 0, 0),
        "3" to Popup(listOf(listOf("⅗", "³", "¾", "⅜")), 0, 1),
        "4" to Popup(listOf(listOf("⁴", "⅘")), 0, 0),
        "5" to Popup(listOf(listOf("⅝", "⁵", "⅚")), 0, 1),
        "6" to Popup(listOf(listOf("⁶")), 0, 0),
        "7" to Popup(listOf(listOf("⁷", "⅞")), 0, 0),
        "8" to Popup(listOf(listOf("⁸")), 0, 0),
        "9" to Popup(listOf(listOf("⁹")), 0, 0),
        "0" to Popup(listOf(listOf("∅", "ⁿ", "⁰")), 0, 2),
    )

    private fun letter(c: String) = Key(c, grid = letterPopups[c], vietnameseOnly = true)

    private fun digits() = "1234567890".map { Key(it.toString(), grid = digitPopups[it.toString()]) }

    /** Bảng giữ phím ở trang ?123 của bàn phím Tiếng Việt và Tiếng Anh, chép từ Gboard; phím không có ở đây thì không có bảng. */
    private val symbolPopups = mapOf(
        "#" to Popup(listOf(listOf("№")), 0, 0),
        "₫" to Popup(listOf(listOf("₹", "¥", "₱", ""), listOf("€", "$", "¢", "£")), 1, 1),
        "$" to Popup(listOf(listOf("₹", "¥", "₱"), listOf("€", "¢", "£")), 1, 1),
        "-" to Popup(listOf(listOf("—", "_", "–", "·")), 0, 1),
        "+" to Popup(listOf(listOf("±")), 0, 0),
        "(" to Popup(listOf(listOf("[", "<", "{")), 0, 1),
        ")" to Popup(listOf(listOf("]", "}", ">")), 0, 2),
        "*" to Popup(listOf(listOf("★", "†", "‡")), 0, 1),
        "\"" to Popup(listOf(listOf("„", "“", "”", "«", "»")), 0, 1),
        "'" to Popup(listOf(listOf("‚", "‘", "’", "‹", "›")), 0, 2),
        "!" to Popup(listOf(listOf("¡")), 0, 0),
        "?" to Popup(listOf(listOf("¿", "‽")), 0, 0),
    )

    private fun keysOf(s: String, popups: Map<String, Popup>) = s.split(" ").map { Key(it, grid = popups[it]) }

    /** Bảng giữ phím ở trang ?123 của bàn phím Hán Nôm, đo trên Gboard 拼音. */
    private val hanNomSymbolPopups = mapOf(
        "#" to single("№"),
        "$" to symbolPopups.getValue("$"),
        "-" to symbolPopups.getValue("-"),
        "+" to single("±"),
        "(" to oneRow(3, "【", "［", "{", "(", "<"),
        ")" to oneRow(4, "】", "］", "}", ">", ")"),
        "/" to single("／"),
        "“" to oneRow(0, "＂", "《"),
        "”" to oneRow(0, "„", "》"),
        ":" to single(":"),
        ";" to single(";"),
        "!" to oneRow(0, "!", "¡"),
        "?" to oneRow(0, "?", "¿"),
    )

    /** Phím về trang chữ ở các trang ký tự của bàn phím Hán Nôm (chỗ phím `返回` của Gboard 拼音). */
    private const val HAN_NOM_BACK = "𢮿徠"

    /** Mục trong bảng giữ phím phẩy mở bảng emoji (như Gboard), không chèn chữ. */
    const val EMOJI = "😊"
    /** Mục trong bảng giữ phím phẩy bật / tắt bàn phím một tay, không chèn chữ. */
    const val ONE_HAND = ""
    /** Mục trong bảng giữ phím phẩy mở cài đặt, không chèn chữ. */
    const val SETTINGS = ""

    /** Icon vẽ thay chữ cho các mục không chèn chữ trong bảng giữ phím. */
    val ACTION_ICONS = mapOf(EMOJI to R.drawable.ic_emoji, ONE_HAND to R.drawable.ic_one_hand, SETTINGS to R.drawable.ic_settings)

    /** Giữ phím phẩy như Gboard: một tay, emoji (chọn sẵn, ngay trên phím), cài đặt. */
    private val comma = Key(
        ",", grid = Popup(listOf(listOf(ONE_HAND, EMOJI, SETTINGS)), 0, 1), functional = true, hintIcon = R.drawable.ic_emoji,
    )
    /** Phím chấm ở trang chữ của bàn phím Hán Nôm, chép từ Gboard 拼音. */
    private val period = Key(
        "。",
        grid = Popup(
            listOf(
                listOf("^_^", ":)", ":(", ":D", ":P", "&", "$", "^"),
                listOf("%", "_", ";", ":", "、", "!", "……", "?"),
            ),
            1, 6,
        ),
        functional = true,
    )

    /** Phím chấm ở trang chữ của bàn phím Tiếng Việt và Tiếng Anh (như Gboard): dấu phẩy chọn sẵn, ngay trên phím. */
    private val letterPeriod = Key(
        ".",
        grid = Popup(
            listOf(listOf("&", "%", "+", "\"", "-", ":", "'", "@"), listOf(";", "/", "(", ")", "#", "!", ",", "?")),
            1, 6,
        ),
        functional = true,
    )

    /** Phẩy và chấm ở trang ?123 của bàn phím Tiếng Việt và Tiếng Anh (như Gboard): phẩy không có emoji, cài đặt chọn sẵn. */
    private val symbolComma = Key(",", grid = Popup(listOf(listOf(SETTINGS, ONE_HAND)), 0, 0), functional = true)
    private val symbolPeriod = Key(".", alternates = listOf("…"), functional = true)

    /** Phím `12/34` (ở vị trí phím chế độ) của trang ?123 và `=\<` trên bàn phím Tiếng Việt và Tiếng Anh: mở bàn phím số. */
    private val toNumpad = Key("", KeyType.TO_NUMPAD)

    private fun bottomRow(switchKey: Key, left: Key = comma, right: Key = period, mode: Key = Key("", KeyType.MODE)) = listOf(
        switchKey,
        left,
        mode,
        Key("", KeyType.SPACE, weight = 4f),
        right,
        Key("", KeyType.ENTER, weight = 1.5f),
    )

    /** Trang chữ; kiểu Gboard ([KeyboardStyle.gboard]) thì phím chấm có bảng dấu câu như Gboard. */
    fun letters(style: KeyboardStyle = KeyboardStyle.HAN_NOM): List<List<Key>> = listOf(
        digits(),
        "qwertyuiop".map { letter(it.toString()) },
        listOf(Key("", KeyType.SPACER, 0.5f)) + "asdfghjkl".map { letter(it.toString()) } +
            Key("", KeyType.SPACER, 0.5f),
        listOf(Key("", KeyType.SHIFT, 1.5f)) + "zxcvbnm".map { letter(it.toString()) } +
            Key("", KeyType.DELETE, 1.5f),
        if (style.gboard) bottomRow(Key("?123", KeyType.TO_SYMBOLS, 1.5f), right = letterPeriod)
        else bottomRow(Key("?123", KeyType.TO_SYMBOLS, 1.5f)),
    )

    /** Trang ?123 của bàn phím Hán Nôm, chép từ Gboard 拼音. */
    private fun hanNomSymbols(): List<List<Key>> = listOf(
        digits(),
        keysOf("@ # $ _ & - + ( ) /", hanNomSymbolPopups),
        listOf(Key("=\\<", KeyType.TO_SYMBOLS2, 1.5f)) + keysOf("、 “ ” : ; ! ?", hanNomSymbolPopups) + Key("", KeyType.DELETE, 1.5f),
        bottomRow(Key(HAN_NOM_BACK, KeyType.TO_LETTERS, 1.5f), left = symbolComma, right = symbolPeriod, mode = toNumpad),
    )

    /** Trang ?123, chép từ Gboard theo từng bàn phím; tiếng Anh dùng `$` thay `₫`. */
    fun symbols(style: KeyboardStyle): List<List<Key>> = if (style == KeyboardStyle.HAN_NOM) hanNomSymbols() else listOf(
        digits(),
        keysOf("@ # ${if (style == KeyboardStyle.ENGLISH) "$" else "₫"} _ & - + ( ) /", symbolPopups),
        listOf(Key("=\\<", KeyType.TO_SYMBOLS2, 1.5f)) + keysOf("* \" ' : ; ! ?", symbolPopups) + Key("", KeyType.DELETE, 1.5f),
        bottomRow(Key("ABC", KeyType.TO_LETTERS, 1.5f), left = symbolComma, right = symbolPeriod, mode = toNumpad),
    )

    /** Số phím thấy được cùng lúc trong cột phép tính; các phím còn lại phải cuộn mới thấy. */
    const val RAIL_VISIBLE = 4

    /**
     * Bàn phím số (phím `12/34`), chép từ Gboard. Ba hàng trên gồm cột phép tính
     * ([Key.rail], sáu phím chung một nền, cuộn dọc, hiện [RAIL_VISIBLE] phím; thứ tự trong hàng không quan trọng,
     * [KeyboardView] xếp theo thứ tự xuất hiện), ba cột số và cột phải.
     */
    fun numpad(style: KeyboardStyle): List<List<Key>> {
        val side = 1.5f
        val wide = 7f / 3f
        val narrow = 4f / 3f
        fun digit(s: String) = Key(s, weight = wide)
        fun op(s: String) = Key(s, weight = side, functional = true, rail = true)
        return listOf(
            listOf(op("+"), digit("1"), digit("2"), digit("3"), Key("%", weight = side, functional = true)),
            listOf(op("-"), digit("4"), digit("5"), digit("6"), Key("", KeyType.SPACE, weight = side, functional = true)),
            listOf(op("*"), op("/"), op("("), op(")"), digit("7"), digit("8"), digit("9"), Key("", KeyType.DELETE, side)),
            listOf(
                Key(if (style.gboard) "ABC" else HAN_NOM_BACK, KeyType.TO_LETTERS, side),
                symbolComma,
                Key("!?#", KeyType.TO_SYMBOLS, narrow, isPill = false),
                digit("0"),
                Key("=", weight = narrow, functional = true),
                Key(".", alternates = listOf("…"), functional = true, literal = true),
                Key("", KeyType.ENTER, side),
            ),
        )
    }

    /**
     * Ô nhập số ở màn hình ngang: bốn cột bằng nhau, phím tròn, không có cột phép tính hay phím về trang chữ
     * (đo trên Gboard, Google Sheets nút 123). Cột phải là phím chức năng.
     */
    fun phonepad(): List<List<Key>> {
        val w = 2.5f
        fun digit(s: String) = Key(s, weight = w, isPill = true, literal = true)
        fun side(k: KeyType, s: String = "") = Key(s, k, w, functional = true, isPill = true, literal = true)
        return listOf(
            listOf(digit("1"), digit("2"), digit("3"), side(KeyType.CHAR, "-")),
            listOf(digit("4"), digit("5"), digit("6"), side(KeyType.SPACE)),
            listOf(digit("7"), digit("8"), digit("9"), side(KeyType.DELETE)),
            listOf(digit(","), digit("0"), digit("."), Key("", KeyType.ENTER, w)),
        )
    }

    /** Viết tay: chỉ hàng dưới cùng (thêm phím xoá), phía trên là vùng viết ([HandwritingView]). */
    fun handwriting(): List<List<Key>> = listOf(
        listOf(
            Key("?123", KeyType.TO_SYMBOLS, 1.5f),
            comma,
            Key("", KeyType.MODE),
            Key("", KeyType.SPACE, weight = 3f),
            period,
            Key("", KeyType.DELETE),
            Key("", KeyType.ENTER, weight = 1.5f),
        ),
    )

    /** Bảng giữ phím ở trang `=\<` của bàn phím Tiếng Việt và Tiếng Anh, chép từ Gboard. */
    private val symbols2Popups = mapOf(
        "•" to Popup(listOf(listOf("♣", "♠", "♪", "♥", "♦")), 0, 2),
        "π" to Popup(listOf(listOf("Ω", "Π", "μ")), 0, 1),
        "§" to Popup(listOf(listOf("¶")), 0, 0),
        "$" to Popup(listOf(listOf("₹", "¥", "₱"), listOf("€", "¢", "£")), 1, 1),
        "^" to Popup(listOf(listOf("←", "↑", "↓", "→")), 0, 1),
        "°" to Popup(listOf(listOf("′", "″")), 0, 0),
        "=" to Popup(listOf(listOf("∞", "≠", "≈")), 0, 1),
        "{" to Popup(listOf(listOf("(")), 0, 0),
        "}" to Popup(listOf(listOf(")")), 0, 0),
        "%" to Popup(listOf(listOf("‰", "℅")), 0, 0),
        "<" to Popup(listOf(listOf("«", "≤", "‹", "⟨")), 0, 1),
        ">" to Popup(listOf(listOf("⟩", "»", "≥", "›")), 0, 2),
    )

    /** Bảng giữ phím `=\<` của bàn phím Hán Nôm, đo trên Gboard 拼音 (dùng lại các bảng giống bản tiếng Việt). */
    private val hanNomSymbols2Popups = symbols2Popups + mapOf(
        "~" to single("～"),
        "*" to oneRow(2, "★", "‡", "†"),
        "「" to single("『"),
        "」" to single("』"),
        "‘" to oneRow(0, "'", "〈"),
        "’" to oneRow(0, "‚", "〉"),
        "[" to single("["),
        "]" to single("]"),
        "<" to Popup(listOf(listOf("⟨", "«", "〈"), listOf("‹", "《", "≤")), 1, 1),
        ">" to Popup(listOf(listOf("⟩", "»", "〉"), listOf("›", "》", "≥")), 1, 1),
    )

    /** Bảng giữ phím của dấu [s] mà hàng số đang đổi thành lúc bật Shift một lần. */
    fun shiftedDigitPopup(s: String, style: KeyboardStyle): Popup? =
        if (style.gboard) symbolPopups[s] ?: symbols2Popups[s] else hanNomShiftPopups[s]

    private fun gboardSymbols2(style: KeyboardStyle): List<List<Key>> {
        fun keys(s: String) = s.split(" ").map { Key(it, grid = symbols2Popups[it]) }
        return listOf(
            keys("~ ` | • √ π ÷ × § ∆"),
            keys(if (style == KeyboardStyle.ENGLISH) "£ ¢ € ¥ ^ ° = { } \\" else "£ € $ ¢ ^ ° = { } \\"),
            listOf(Key("?123", KeyType.TO_SYMBOLS, 1.5f, isPill = false)) + keys("% © ® ™ ✓ [ ]") +
                Key("", KeyType.DELETE, 1.5f),
            bottomRow(
                Key("ABC", KeyType.TO_LETTERS, 1.5f),
                left = Key("<", grid = symbols2Popups["<"], functional = true),
                right = Key(">", grid = symbols2Popups[">"], functional = true),
                mode = toNumpad,
            ),
        )
    }

    /** Trang ký hiệu thứ hai, chép từ Gboard theo từng bàn phím (Hán Nôm theo Gboard 拼音). */
    fun symbols2(style: KeyboardStyle): List<List<Key>> = if (style.gboard) gboardSymbols2(style) else listOf(
        keysOf("~ ` | • √ π ÷ × * §", hanNomSymbols2Popups),
        keysOf("£ ¢ € ¥ ^ ° = 「 」 \\", hanNomSymbols2Popups),
        listOf(Key("?123", KeyType.TO_SYMBOLS, 1.5f, isPill = false)) + keysOf("% ‘ ’ ™ ✓ [ ]", hanNomSymbols2Popups) +
            Key("", KeyType.DELETE, 1.5f),
        bottomRow(
            Key(HAN_NOM_BACK, KeyType.TO_LETTERS, 1.5f),
            left = Key("<", grid = hanNomSymbols2Popups["<"], functional = true),
            right = Key(">", grid = hanNomSymbols2Popups[">"], functional = true),
            mode = toNumpad,
        ),
    )
}
