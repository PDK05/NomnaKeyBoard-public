package com.hannom.ime

import android.content.Context
import java.io.File

/**
 * [abbreviated]: khớp nhờ gõ tắt chữ cái đầu (`vn` → 越南).
 * [breaks]: vị trí phím giữa các âm tiết (`chunom` → 𡨸喃: `[3]`), để khung gõ hiện tách chữ.
 * [joined]: lõi ghép từ nhiều mục (`quocviet` → 國越), không phải một mục có sẵn trong từ điển.
 */
data class Candidate(
    val text: String,
    val reading: String,
    val consumed: Int,
    val abbreviated: Boolean = false,
    val breaks: List<Int> = emptyList(),
    val joined: Boolean = false,
    /** Gợi ý tiếp theo chữ đã chọn (xem [HanNomEngine.after]): cả cụm trong từ điển, học cụm này khi chốt. */
    val phrase: Pair<String, String>? = null,
    /** Chữ Quốc ngữ đúng như khung gõ (bàn phím Hán Nôm kiểu Telex), không phải chữ trong từ điển: chọn thì không học. */
    val literal: Boolean = false,
)

/** Một cặp chữ – âm trong từ điển và độ phổ biến của nó. */
data class DictRow(val text: String, val reading: String, val freq: Int) {
    /** Mã Unicode từng ký tự (`固` → `U+56FA`). */
    val codes: String get() = text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
}

data class ImportReport(val added: Int, val duplicates: Int, val errors: List<String>)

/**
 * Lõi gõ dùng chung cho bàn phím và ứng dụng cài đặt (cùng tiến trình).
 * Hán Nôm có nhiều từ điển ([Dictionaries], `files/dicts/nom/`): từ điển mặc định (lần đầu là bản đóng gói sẵn) và
 * các từ điển cá nhân. Khi gõ, lõi dùng bản gộp các từ điển đang bật: mặc định trước, mục trùng ở từ điển sau bỏ qua,
 * không sửa tệp nào. Học thói quen là tăng độ phổ biến (`chữ;âm;số`) ở mọi từ điển đang bật có cặp đó.
 */
class HanNomEngine private constructor(private val context: Context) {
    /** Bản gộp các từ điển đang bật, để gõ. */
    private val handle: Long
    /** Từng từ điển (theo id), nạp khi cần (sửa, đếm, ghi độ phổ biến đã học). */
    private val parts = HashMap<String, Long>()
    /** Danh sách / nội dung từ điển đã đổi: gộp lại trước lần gõ kế tiếp. */
    private var stale = false
    /** Các cặp đã chọn khi gõ mà chưa ghi vào từng từ điển. */
    private val pending = ArrayList<Pair<String, String>>()

    init {
        migrateLegacy()
        migrateToDictionaries()
        handle = NativeCore.create("")
        mergeBuiltinAfterUpdate()
        rebuild()
    }

    /**
     * Sau mỗi lần cài / cập nhật ứng dụng, tự gộp từ điển mặc định đóng gói sẵn (có thể đã có chữ mới) vào từ điển
     * mặc định của người dùng: thêm cặp còn thiếu, giữ nguyên chỗ đã sửa và độ phổ biến đã học.
     */
    private fun mergeBuiltinAfterUpdate() {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val prefs = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
        if (prefs.getLong(MERGED_BUILTIN, 0L) == installed) return
        // Chưa có tệp riêng thì đang dùng thẳng bản đóng gói, không cần gộp.
        if (fileOf(DictInfo.DEFAULT_ID).exists()) {
            val h = part(DictInfo.DEFAULT_ID)
            if (report(NativeCore.dictImport(h, builtinText(), true)).added > 0) save(DictInfo.DEFAULT_ID)
        }
        prefs.edit().putLong(MERGED_BUILTIN, installed).apply()
    }

    /**
     * Từ điển lưu theo định dạng cũ `chữ;âm1,âm2` (không có độ phổ biến) không đọc được nữa: đổi tên thành
     * `dictionary-old.txt` để dùng từ điển mặc định mới; bỏ tệp học thói quen cũ (`learned.txt`).
     */
    private fun migrateLegacy() {
        File(context.filesDir, "learned.txt").delete()
        val dictFile = File(context.filesDir, LEGACY_FILE)
        if (!dictFile.exists()) return
        val lines = dictFile.readOrEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.any() && lines.none { line -> line.count { it == ';' } == 2 }) {
            dictFile.renameTo(File(context.filesDir, "dictionary-old.txt"))
        }
    }

    /**
     * Bản trước chỉ có một từ điển (`dictionary.txt`, bản sao từ điển mặc định người dùng sửa thẳng vào). Chia ra
     * (người dùng chọn 2026-11-14): cặp có trong bản đóng gói thành từ điển mặc định (giữ độ phổ biến), cặp người
     * dùng tự thêm / sửa thành 辭典個人 1. Tệp cũ đổi tên thành `dictionary-before-split.txt` để còn bản lưu.
     */
    private fun migrateToDictionaries() {
        if (Dictionaries.initialized(context, Lang.NOM)) return
        val old = File(context.filesDir, LEGACY_FILE)
        val dicts = arrayListOf(DictInfo(Lang.NOM, DictInfo.DEFAULT_ID, Lang.NOM.defaultName, true))
        if (old.exists()) {
            fun canonical(text: String): List<String> {
                val h = NativeCore.create(text)
                return NativeCore.dictText(h).also { NativeCore.destroy(h) }.lines().filter { it.isNotBlank() }
            }
            val builtin = canonical(builtinText()).mapTo(HashSet()) { it.substringBeforeLast(';') }
            val (kept, added) = canonical(old.readOrEmpty()).partition { it.substringBeforeLast(';') in builtin }
            fileOf(DictInfo.DEFAULT_ID).writeAtomic(kept.joinToString("") { it + "\n" })
            if (added.isNotEmpty()) {
                fileOf("p1").writeAtomic(added.joinToString("") { it + "\n" })
                dicts.add(DictInfo(Lang.NOM, "p1", Lang.NOM.personalName(1), true))
            }
            old.renameTo(File(context.filesDir, "dictionary-before-split.txt"))
        }
        Dictionaries.save(context, Lang.NOM, dicts)
    }

    private var toneStyle = 0
    private var learning = true

    /** Bàn phím Hán Nôm kiểu Telex ([Layout.TELEX]) đang dùng: gợi ý theo Telex tự do. */
    var telex = false
        set(v) {
            field = v
            applyOptions()
        }

    fun apply(settings: Settings) {
        toneStyle = settings.toneStyle
        learning = settings.learning
        applyOptions()
    }

    @Synchronized private fun applyOptions() = NativeCore.setOptions(handle, toneStyle, false, learning, telex)

    /** Khung gõ bàn phím Hán Nôm kiểu Telex: các chữ đã ghép dấu, cách nhau (`chuwxnoom` → chữ nôm). */
    fun displayTelex(keys: String, toneStyle: Int): String = NativeCore.displayTelex(keys, toneStyle)

    /** Telex tự do như Unikey. */
    fun compose(keys: String, toneStyle: Int): String = NativeCore.compose(keys, toneStyle, false, false)

    fun decompose(word: String): String = NativeCore.decompose(word, false, false)

    fun backspace(keys: String, toneStyle: Int): String = NativeCore.backspace(keys, toneStyle)

    /** Xoá lùi bàn phím Hán Nôm kiểu Telex: phím dấu đã thay dấu cũ thì xoá hẳn dấu (`asf` → `a`). */
    fun backspaceTelex(keys: String, toneStyle: Int): String = NativeCore.backspaceTelex(keys, toneStyle)

    /** Gợi ý Hán Nôm, hoặc chữ Quốc ngữ có dấu khi [quocNgu]. */
    @Synchronized fun candidates(keys: String, quocNgu: Boolean, limit: Int = 300): List<Candidate> =
        fresh().let { _ -> perf("candidates", keys) { candidateRecords(NativeCore.candidates(handle, keys, limit, quocNgu)) } }

    /**
     * Thu hẹp âm tiết đầu từng cấp (cột trái của bảng gợi ý): phụ âm đầu → vần → phụ âm cuối → cả âm có
     * thanh. [picked]: các lựa chọn đã chọn (cấp, giá trị) theo thứ tự. Trả cấp kế tiếp và các lựa chọn đó,
     * `null` khi hết cấp.
     */
    @Synchronized fun narrow(keys: String, picked: List<Pair<Int, String>>, before: String = ""): Pair<Int, List<String>>? {
        fresh()
        val raw = perf("narrow", "$keys $picked") { NativeCore.narrow(handle, keys, encodePicked(picked), before) }
        if (raw.isEmpty()) return null
        val f = raw.split(NativeCore.US)
        return (f[0].toIntOrNull() ?: return null) to f.drop(1)
    }

    /**
     * Các đoạn âm của [keys] (vị trí ký tự, cuối không tính), cho chốt âm ở cột trái: `twcmn` → tw, c, m, n.
     */
    fun syllableChunks(keys: String): List<IntRange> {
        val key = "$telex|$keys"
        synchronized(chunkCache) { chunkCache[key] }?.let { return it }
        // Không khoá lõi: luồng giao diện gọi hàm này mỗi lần vẽ khung gõ, không phải chờ lõi tra gợi ý ở luồng nền.
        val raw = perf("syllableChunks", keys) { NativeCore.syllableChunks(telex, false, keys) }
        val out = if (raw.isEmpty()) emptyList() else raw.split(NativeCore.RS).mapNotNull { r ->
            val (s, e) = r.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            s until e
        }
        synchronized(chunkCache) { chunkCache[key] = out }
        return out
    }

    /**
     * Kết quả [syllableChunks] gần đây: mỗi lần chọn chữ / chốt âm, bộ gõ hỏi cùng một chuỗi phím hàng chục lần (đo trên
     * điện thoại với cụm test kinh điển: 90 lần, 158 ms khi chọn một chữ). Chỉ phụ thuộc chuỗi phím và kiểu gõ.
     */
    private val chunkCache = object : LinkedHashMap<String, List<IntRange>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<IntRange>>?) = size > 32
    }

    /**
     * Các gợi ý cho [keys] có âm tiết đầu khớp các lựa chọn [picked] của [narrow]. [before]: chữ đã chọn dở, để khi đã
     * chốt âm thì chữ nối tiếp nó thành cụm (政 + tr… → 治) đứng đầu.
     */
    @Synchronized fun candidatesNarrowed(keys: String, picked: List<Pair<Int, String>>, before: String = "", limit: Int = 300): List<Candidate> {
        fresh()
        return perf("candidatesNarrowed", "$keys $picked") { candidateRecords(NativeCore.candidatesNarrowed(handle, keys, encodePicked(picked), before, limit)) }
    }

    private fun encodePicked(picked: List<Pair<Int, String>>) =
        picked.joinToString(NativeCore.RS.toString()) { (level, value) -> "$level${NativeCore.US}$value" }

    private fun candidateRecords(raw: String): List<Candidate> =
        records(raw).mapNotNull { f ->
            if (f.size >= 3) {
                val breaks = f.getOrNull(4).orEmpty().split(',').mapNotNull { it.toIntOrNull() }
                Candidate(f[0], f[1], f[2].toIntOrNull() ?: 0, f.getOrNull(3) == "1", breaks, f.getOrNull(5) == "1")
            } else null
        }

    /**
     * Gợi ý tiếp theo các chữ đã chọn [before] (chọn từng phần như pinyin): phần còn lại của các cụm bắt đầu
     * bằng chữ đã chọn, khớp phần đầu [keys] (`hstt` chọn 函 thì `stt` gợi ý 數 của 函數).
     */
    @Synchronized fun after(before: String, keys: String, limit: Int = 100): List<Candidate> =
        fresh().let { _ -> perf("after", "$before+$keys") { records(NativeCore.candidatesAfter(handle, before, keys, limit)) }.mapNotNull { f ->
            if (f.size >= 5) Candidate(f[0], f[1], f[2].toIntOrNull() ?: 0, abbreviated = true, phrase = f[3] to f[4]) else null
        } }

    @Synchronized fun select(candidate: Candidate) {
        val (text, reading) = candidate.phrase ?: (candidate.text to candidate.reading)
        if (NativeCore.select(handle, text, reading)) pending.add(text to reading)
    }

    /**
     * Ghi độ phổ biến đã học vào từng từ điển đang bật có cặp đó (gọi khi đóng bàn phím, không lưu sau mỗi lần
     * chọn).
     */
    @Synchronized fun saveLearned() {
        if (pending.isEmpty()) return
        for (d in Dictionaries.enabled(context, Lang.NOM)) {
            val h = part(d.id)
            var changed = false
            for ((text, reading) in pending) changed = NativeCore.select(h, text, reading) || changed
            if (changed) save(d.id)
        }
        pending.clear()
    }

    /**
     * Xoá dữ liệu học của từ điển mặc định: độ phổ biến về như bản đóng gói sẵn, cặp tự thêm về 0. Từ điển cá nhân
     * là tệp của người dùng, không đụng tới.
     */
    @Synchronized fun clearLearned() {
        pending.clear()
        NativeCore.clearLearned(part(DictInfo.DEFAULT_ID), builtinText())
        save(DictInfo.DEFAULT_ID)
        stale = true
    }

    /** Danh sách hay nội dung từ điển đã đổi: lần gõ kế tiếp gộp lại. */
    @Synchronized fun invalidate() {
        stale = true
    }

    /** Bỏ các từ điển [ids] khỏi bộ nhớ (sắp xoá). */
    @Synchronized fun forget(ids: Collection<String>) {
        ids.forEach { id -> parts.remove(id)?.let(NativeCore::destroy) }
        stale = true
    }

    /** Gộp lại nếu từ điển đã đổi (gọi trước khi tra gợi ý; trong khoá). */
    private fun fresh() {
        if (!stale) return
        saveLearned()
        rebuild()
    }

    /** Gộp các từ điển đang bật vào bản để gõ: mặc định trước, mục trùng ở từ điển sau bỏ qua. */
    private fun rebuild() {
        stale = false
        val on = Dictionaries.enabled(context, Lang.NOM)
        if (on.isEmpty()) {
            NativeCore.dictClear(handle)
            return
        }
        NativeCore.dictLoad(handle, textOf(on[0].id))
        if (on.size > 1) NativeCore.dictImport(handle, join(on.drop(1).map { textOf(it.id) }), true)
    }

    private fun fileOf(id: String) = Dictionaries.file(context, Lang.NOM, id)

    /** Nội dung từ điển [id]: bản đang nạp nếu có, không thì tệp (từ điển mặc định chưa có tệp là bản đóng gói). */
    private fun textOf(id: String): String =
        parts[id]?.let(NativeCore::dictText)
            ?: fileOf(id).takeIf { it.exists() }?.readOrEmpty()
            ?: if (id == DictInfo.DEFAULT_ID) builtinText() else ""

    /** Từ điển [id], nạp khi cần. */
    private fun part(id: String): Long = parts.getOrPut(id) {
        NativeCore.create(textOf(id)).also { NativeCore.setOptions(it, 0, false, true, false) }
    }

    private fun save(id: String) {
        parts[id]?.let { fileOf(id).writeAtomic(NativeCore.dictText(it)) }
    }

    /** Thao tác trên từ điển [id] cho màn hình quản lý; sửa xong thì lưu tệp và gộp lại khi gõ. */
    fun store(id: String): DictStore = object : DictStore {
        override val hasReading = true

        private fun <T> edit(block: (Long) -> T): T = synchronized(this@HanNomEngine) {
            block(part(id)).also {
                save(id)
                stale = true
            }
        }

        override fun count() = synchronized(this@HanNomEngine) { NativeCore.dictLen(part(id)) }

        /** Mỗi cặp chữ – âm một dòng, xếp a → z; [query] khớp chữ, âm đọc hoặc `U+56FA`. */
        override fun rows(query: String) = synchronized(this@HanNomEngine) {
            records(NativeCore.dictRows(part(id), query)).mapNotNull { f ->
                if (f.size == 3) DictRow(f[0], f[1], f[2].toIntOrNull() ?: 0) else null
            }
        }

        /**
         * Thêm các cặp chữ [text] – từng âm trong [readings] (`cố,có`) khi [old] là `null`, hoặc sửa cặp [old]
         * thành [text] – [readings] (một âm); độ phổ biến [freq].
         */
        override fun put(old: DictRow?, text: String, readings: String, freq: Int): String? = synchronized(this@HanNomEngine) {
            val h = part(id)
            val error = NativeCore.dictPut(h, old?.text.orEmpty(), old?.reading.orEmpty(), text, readings, freq)
            if (error.isNotEmpty()) return error
            save(id)
            stale = true
            null
        }

        override fun remove(rows: Collection<DictRow>) {
            if (rows.isEmpty()) return
            edit { h ->
                if (rows.size == 1) {
                    rows.first().let { NativeCore.dictRemove(h, it.text, it.reading) }
                } else {
                    // Xoá nhiều mục một lượt: lọc tệp rồi nạp lại (mỗi lần xoá lẻ lõi dựng lại chỉ mục).
                    val gone = rows.mapTo(HashSet()) { "${it.text};${it.reading}" }
                    val kept = NativeCore.dictText(h).lineSequence().filter { it.isNotBlank() && it.substringBeforeLast(';') !in gone }
                    NativeCore.dictLoad(h, kept.joinToString("\n"))
                }
            }
        }

        /** Xem trước khi gộp các tệp: mỗi tệp một báo cáo, mục đã có ở tệp trước tính là trùng. */
        override fun previewImport(contents: List<String>) = synchronized(this@HanNomEngine) {
            reports(NativeCore.dictImport(part(id), join(contents), false))
        }

        override fun import(contents: List<String>) = edit { h -> reports(NativeCore.dictImport(h, join(contents), true)) }

        /** Thay toàn bộ từ điển bằng các tệp [contents] (gộp lần lượt vào một từ điển rỗng). */
        override fun replaceWith(contents: List<String>) = edit { h ->
            NativeCore.dictClear(h)
            reports(NativeCore.dictImport(h, join(contents), true))
        }

        override fun exportText() = synchronized(this@HanNomEngine) { NativeCore.dictText(part(id)) }

        override fun clear() = edit { h -> NativeCore.dictClear(h) }

        /** Gộp lại bản đóng gói: thêm các mục mặc định còn thiếu, giữ nguyên chỗ đã sửa / thêm. */
        override fun mergeBuiltin() = import(listOf(builtinText())).first()

        /** Thay toàn bộ bằng bản đóng gói; mất mọi chỗ đã sửa / thêm. */
        override fun resetToBuiltin() = edit { h -> NativeCore.dictLoad(h, builtinText()) }
    }

    private fun builtinText(): String = context.assets.open("dict/builtin.txt").bufferedReader().use { it.readText() }

    /** Nội dung các tệp nối bằng `RS`; ký tự này không có nghĩa trong tệp văn bản nên bỏ đi. */
    private fun join(contents: List<String>) =
        contents.joinToString(NativeCore.RS.toString()) { it.replace(NativeCore.RS.toString(), "") }

    private fun reports(raw: String): List<ImportReport> = raw.split(NativeCore.RS).map(::report)

    private fun report(raw: String): ImportReport {
        val f = raw.split(NativeCore.US)
        return ImportReport(f.getOrNull(0)?.toIntOrNull() ?: 0, f.getOrNull(1)?.toIntOrNull() ?: 0, f.drop(2))
    }

    private fun records(raw: String): List<List<String>> =
        if (raw.isEmpty()) emptyList() else raw.split(NativeCore.RS).map { it.split(NativeCore.US) }

    companion object {
        /** Từ điển duy nhất của bản trước khi chia theo ngôn ngữ (xem [migrateToDictionaries]). */
        private const val LEGACY_FILE = "dictionary.txt"

        /** Thời điểm cài / cập nhật ứng dụng lần cuối đã gộp từ điển mặc định. */
        private const val MERGED_BUILTIN = "merged_builtin_at"

        @Volatile private var instance: HanNomEngine? = null

        fun get(context: Context): HanNomEngine =
            instance ?: synchronized(this) {
                instance ?: HanNomEngine(context.applicationContext).also { instance = it }
            }
    }
}

private fun File.readOrEmpty(): String = if (exists()) runCatching { readText() }.getOrDefault("") else ""

private fun File.writeAtomic(content: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(this)) {
        writeText(content)
        tmp.delete()
    }
}
