package com.hannom.ime

import android.content.Context
import java.io.File
import java.text.Normalizer

/**
 * Từ điển gợi ý từ cho bàn phím Quốc ngữ và tiếng Anh: `dict/vi-dict.txt`, `dict/en-dict.txt` của repo, khi build
 * chép vào `assets/dict/vi.txt`, `en.txt`. Mỗi dòng một từ (có thể nhiều chữ: `chữ nôm`), sau từ có thể có độ phổ biến, ngăn bằng `;`, tab hoặc `,`;
 * dòng `#` bỏ qua. Không có tệp thì không gợi ý.
 *
 * Tìm theo chữ không dấu (`chu` khớp chữ, chú, chủ…), từ gõ đúng dấu lên trước. Chọn gợi ý thì từ đó được học
 * (lưu riêng trong thư mục ứng dụng), lần sau lên trước.
 *
 * Từ tiếp theo: học các cặp từ người dùng gõ; gõ xong một từ thì gợi ý luôn từ hay gõ sau nó, đang gõ dở thì từ đó
 * lên đầu.
 */
class WordDict private constructor(private val context: Context, private val name: String) {
    /** Viết hoa gợi ý theo bàn phím: thường hết, chỉ chữ cái đầu (Shift), toàn bộ (Caps Lock). */
    enum class Caps {
        NONE, FIRST, ALL;

        fun apply(word: String): String = when (this) {
            NONE -> word.lowercase()
            FIRST -> word.lowercase().replaceFirstChar { it.uppercaseChar() }
            ALL -> word.uppercase()
        }
    }

    private class Entry(val word: String, val key: String, val freq: Int, val syllables: Int)

    /** Xếp theo [Entry.key] (chữ không dấu) để tìm tiền tố bằng chia đôi. */
    private val entries: Array<Entry>
    private val learnedFile = File(context.filesDir, "words-$name-learned.txt")
    private val learned = HashMap<String, Int>()
    /** Cặp từ đã gõ: `toneKey(từ trước)` + tab + từ sau (chữ thường) → số lần. */
    private val pairsFile = File(context.filesDir, "words-$name-pairs.txt")
    private val pairs = HashMap<String, Int>()
    private var dirty = false

    init {
        val text = runCatching { context.assets.open("dict/$name.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        val freqs = LinkedHashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(';', '\t', ',')
            val word = Normalizer.normalize(parts[0].trim().split(Regex("\\s+")).joinToString(" "), Normalizer.Form.NFC)
            if (word.isEmpty() || word in freqs) continue
            freqs[word] = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        }
        entries = freqs.map { (w, f) -> Entry(w, fold(w), f, w.count { it == ' ' } + 1) }.sortedBy { it.key }.toTypedArray()
        readCounts(learnedFile, learned)
        readCounts(pairsFile, pairs)
    }

    private fun readCounts(file: File, into: HashMap<String, Int>) {
        if (file.exists()) runCatching {
            file.readLines().forEach { line ->
                val i = line.lastIndexOf(';')
                if (i > 0) into[line.substring(0, i)] = line.substring(i + 1).toIntOrNull() ?: 0
            }
        }
    }

    val isEmpty get() = entries.isEmpty()

    /**
     * Gợi ý cho từ đang gõ [typed] (đã có dấu), tối đa [limit]. [before]: các từ ngay trước (gần nhất ở cuối);
     * từ nhiều chữ bắt đầu bằng đúng các từ đó thì gợi ý phần còn lại lên đầu (`chữ` + `n` → nôm).
     * Không gợi ý lại đúng từ đã gõ. [caps]: viết hoa theo bàn phím; `null` thì theo từ đã gõ và cách viết trong
     * từ điển.
     */
    fun suggest(typed: String, before: List<String>, limit: Int = 3, caps: Caps? = null, keepTyped: Boolean = false): List<String> {
        if (typed.isEmpty() || entries.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val out = LinkedHashMap<String, Long>()
        // Viết hoa theo từ đã gõ trước khi gộp: Love và love chỉ một ô.
        fun add(word: String, score: Long) {
            // [keepTyped]: từ có trong từ điển gõ đủ rồi vẫn giữ trên thanh, đứng đầu (exchanging không mất gợi ý).
            val same = word.equals(typed, ignoreCase = true)
            if (same && !keepTyped) return
            val shown = caps?.apply(word) ?: matchCase(word, typed)
            if (same) {
                out[shown] = Long.MAX_VALUE
                return
            }
            if ((out[shown] ?: Long.MIN_VALUE) < score) out[shown] = score
        }
        // Từ nhiều chữ nối tiếp các từ trước (thử 2 từ trước rồi 1 từ trước).
        for (n in minOf(2, before.size) downTo 1) {
            val head = before.takeLast(n).joinToString(" ") { it.lowercase() }
            scan("$head $lower") { e ->
                if (!e.word.startsWith("$head ")) return@scan
                val rest = e.word.substring(head.length + 1)
                add(rest, CONTEXT * n + rank(e, lower, e.syllables - n))
            }
        }
        val prev = before.lastOrNull()?.let(::toneKey)
        scan(lower) { e -> add(e.word, rank(e, lower, e.syllables, prev)) }
        // Cùng điểm thì từ ngắn trước.
        return out.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key.length })
            .take(limit).map { it.key }
    }

    /**
     * Gợi ý từ tiếp theo khi chưa gõ gì: các từ đã hay gõ sau từ gần nhất của [before], viết hoa theo từ trước nếu
     * nó viết toàn hoa ([caps]: viết hoa theo bàn phím).
     */
    fun predict(before: List<String>, limit: Int = 3, caps: Caps? = null): List<String> {
        val last = before.lastOrNull() ?: return emptyList()
        val prev = toneKey(last)
        val out = LinkedHashMap<String, Long>()
        fun add(word: String, score: Long) {
            val shown = caps?.apply(word)
                ?: if (last.length > 1 && last.all { !it.isLetter() || it.isUpperCase() }) word.uppercase() else word
            if ((out[shown] ?: Long.MIN_VALUE) < score) out[shown] = score
        }
        val head = "$prev\t"
        for ((k, n) in pairs) if (k.startsWith(head)) add(spelling(k.substring(head.length)), n.toLong())
        return out.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Dạng viết trong từ điển của từ chữ thường [lower] (`i` → `I`). */
    private fun spelling(lower: String): String {
        var found: String? = null
        scan(lower) { e -> if (found == null && e.word.equals(lower, ignoreCase = true)) found = e.word }
        return found ?: lower
    }

    /**
     * Điểm xếp hạng: đã học (từ, cặp với từ trước [prev]) > gõ đúng dấu > không có `'` (dog's) khi chưa gõ `'` >
     * ít chữ > phổ biến.
     */
    private fun rank(e: Entry, lower: String, syllables: Int, prev: String? = null): Long {
        val exact = e.word.startsWith(lower)
        val apostrophe = '\'' in e.word && '\'' !in lower
        val word = e.word.lowercase()
        val pair = prev?.let { pairs["$it\t$word"] } ?: 0
        return ((learned[word] ?: 0) + pair * 3).toLong() * LEARNED +
            (if (exact) EXACT else 0L) -
            (if (apostrophe) APOSTROPHE else 0L) -
            (syllables - 1).coerceAtLeast(0) * SYLLABLE +
            e.freq.coerceAtMost(SYLLABLE.toInt() - 1)
    }

    /** Duyệt các mục có chữ không dấu bắt đầu bằng chữ không dấu của [prefix]. */
    private inline fun scan(prefix: String, visit: (Entry) -> Unit) {
        val key = fold(prefix)
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].key < key) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < entries.size && i - lo < MAX_SCAN && entries[i].key.startsWith(key)) visit(entries[i++])
    }

    /** Chọn / gõ xong từ [word] sau từ [prev]: tăng số lần dùng từ và cặp nếu có trong từ điển. */
    fun learn(word: String, prev: String? = null) {
        val w = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC)
        if (entries.isEmpty() || find(w) == null) return
        learned[w] = (learned[w] ?: 0) + 1
        if (!prev.isNullOrEmpty() && find(Normalizer.normalize(prev.lowercase(), Normalizer.Form.NFC)) != null) {
            val k = "${toneKey(prev)}\t$w"
            pairs[k] = (pairs[k] ?: 0) + 1
            if (pairs.size > MAX_PAIRS) pairs.entries.removeAll { it.value <= 1 }
        }
        dirty = true
    }

    /** Độ phổ biến của từ [word] (đã học thì coi như rất phổ biến); không có trong từ điển thì `null`. */
    fun frequency(word: String): Int? {
        val w = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC)
        val e = find(w) ?: return null
        return if ((learned[w] ?: 0) > 0) Int.MAX_VALUE else e.freq
    }

    private fun find(word: String): Entry? {
        var found: Entry? = null
        scan(word) { e -> if (found == null && e.word.equals(word, ignoreCase = true)) found = e }
        return found
    }

    fun save() {
        if (!dirty) return
        runCatching {
            learnedFile.writeText(learned.entries.joinToString("\n") { "${it.key};${it.value}" })
            pairsFile.writeText(pairs.entries.joinToString("\n") { "${it.key};${it.value}" })
        }
        dirty = false
    }

    companion object {
        const val VI = "vi"
        const val EN = "en"
        private const val CONTEXT = 1L shl 50
        private const val LEARNED = 1L shl 30
        private const val EXACT = 1L shl 24
        private const val APOSTROPHE = 1L shl 20
        private const val SYLLABLE = 1L shl 16
        private const val MAX_PAIRS = 20000
        /** Tiền tố ngắn (`c`) khớp rất nhiều từ; chỉ xét chừng này mục. */
        private const val MAX_SCAN = 20000

        private val marks = Regex("\\p{Mn}+")
        private const val TONES = "̣̀́̃̉"

        /** Chữ thường không dấu: `Chữ` → `chu`, đ → d. */
        fun fold(s: String): String =
            marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "").replace('đ', 'd')

        /** Khoá bất kể kiểu bỏ dấu thanh (hoà = hòa, thuỷ = thủy): chữ thường, dấu thanh dời ra cuối mỗi chữ. */
        fun toneKey(s: String): String = s.lowercase().split(' ').joinToString(" ") { syl ->
            val d = Normalizer.normalize(syl, Normalizer.Form.NFD)
            val tone = d.firstOrNull { it in TONES }
            Normalizer.normalize(d.filter { it !in TONES }, Normalizer.Form.NFC) + (tone ?: "")
        }

        /** Viết hoa theo từ đã gõ: gõ `Chu` thì gợi ý `Chữ`, gõ toàn hoa thì gợi ý toàn hoa. */
        private fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        private val cache = HashMap<String, WordDict>()

        @Synchronized
        fun get(context: Context, name: String): WordDict =
            cache.getOrPut(name) { WordDict(context.applicationContext, name) }

        /** Nạp sẵn các từ điển ở luồng nền (tiếng Anh hơn trăm nghìn từ), lần gõ đầu khỏi khựng. */
        fun preload(context: Context) {
            val app = context.applicationContext
            Thread { listOf(VI, EN).forEach { get(app, it) } }.start()
        }

        /** Lưu số lần dùng đã học của các từ điển đã mở (gọi khi đóng bàn phím). */
        @Synchronized
        fun saveAll() = cache.values.forEach { it.save() }
    }
}
