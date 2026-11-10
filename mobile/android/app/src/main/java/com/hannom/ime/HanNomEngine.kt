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
 * Chỉ dùng một từ điển: lần đầu chép từ từ điển mặc định, sau đó người dùng sửa thẳng vào nó
 * và gộp thêm tệp khác vào. Học thói quen là tăng độ phổ biến ngay trong từ điển (`chữ;âm;số`).
 * Từ điển lưu trong thư mục riêng của ứng dụng.
 */
class HanNomEngine private constructor(private val context: Context) {
    private val dictFile = File(context.filesDir, "dictionary.txt")
    private val handle: Long
    /** Độ phổ biến đã đổi (chọn gợi ý) mà chưa lưu. */
    private var dirty = false

    init {
        migrateLegacy()
        val dict = if (dictFile.exists()) dictFile.readOrEmpty() else builtinText()
        handle = NativeCore.create(dict)
        mergeBuiltinAfterUpdate()
    }

    /**
     * Sau mỗi lần cài / cập nhật ứng dụng, tự gộp từ điển mặc định (có thể đã có chữ mới) vào từ điển của
     * người dùng: thêm cặp còn thiếu, giữ nguyên chỗ đã sửa và độ phổ biến đã học.
     */
    private fun mergeBuiltinAfterUpdate() {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val prefs = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
        if (prefs.getLong(MERGED_BUILTIN, 0L) == installed) return
        // Chưa có từ điển riêng thì đang dùng thẳng từ điển mặc định, không cần gộp.
        if (dictFile.exists()) import(listOf(builtinText()))
        prefs.edit().putLong(MERGED_BUILTIN, installed).apply()
    }

    /**
     * Từ điển lưu theo định dạng cũ `chữ;âm1,âm2` (không có độ phổ biến) không đọc được nữa: đổi tên thành
     * `dictionary-old.txt` để dùng từ điển mặc định mới; bỏ tệp học thói quen cũ (`learned.txt`).
     */
    private fun migrateLegacy() {
        File(context.filesDir, "learned.txt").delete()
        if (!dictFile.exists()) return
        val lines = dictFile.readOrEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.any() && lines.none { line -> line.count { it == ';' } == 2 }) {
            dictFile.renameTo(File(context.filesDir, "dictionary-old.txt"))
        }
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
        candidateRecords(NativeCore.candidates(handle, keys, limit, quocNgu))

    /**
     * Thu hẹp âm tiết đầu từng cấp (cột trái của bảng gợi ý): phụ âm đầu → vần → phụ âm cuối → cả âm có
     * thanh. [picked]: các lựa chọn đã chọn (cấp, giá trị) theo thứ tự. Trả cấp kế tiếp và các lựa chọn đó,
     * `null` khi hết cấp.
     */
    @Synchronized fun narrow(keys: String, picked: List<Pair<Int, String>>): Pair<Int, List<String>>? {
        val raw = NativeCore.narrow(handle, keys, encodePicked(picked))
        if (raw.isEmpty()) return null
        val f = raw.split(NativeCore.US)
        return (f[0].toIntOrNull() ?: return null) to f.drop(1)
    }

    /** Các gợi ý cho [keys] có âm tiết đầu khớp các lựa chọn [picked] của [narrow]. */
    @Synchronized fun candidatesNarrowed(keys: String, picked: List<Pair<Int, String>>, limit: Int = 300): List<Candidate> =
        candidateRecords(NativeCore.candidatesNarrowed(handle, keys, encodePicked(picked), limit))

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
        records(NativeCore.candidatesAfter(handle, before, keys, limit)).mapNotNull { f ->
            if (f.size >= 5) Candidate(f[0], f[1], f[2].toIntOrNull() ?: 0, abbreviated = true, phrase = f[3] to f[4]) else null
        }

    @Synchronized fun select(candidate: Candidate) {
        val (text, reading) = candidate.phrase ?: (candidate.text to candidate.reading)
        if (NativeCore.select(handle, text, reading)) dirty = true
    }

    /** Lưu độ phổ biến đã đổi (gọi khi đóng bàn phím, không lưu sau mỗi lần chọn). */
    fun saveLearned() {
        if (dirty) save()
    }

    /** Xoá dữ liệu học: độ phổ biến về như từ điển mặc định, cặp tự thêm về 0. */
    @Synchronized fun clearLearned() {
        NativeCore.clearLearned(handle, builtinText())
        save()
    }

    /** Từ điển, mỗi cặp chữ – âm một dòng, xếp a → z; [query] khớp chữ, âm đọc hoặc `U+56FA`. */
    @Synchronized fun rows(query: String = ""): List<DictRow> =
        records(NativeCore.dictRows(handle, query)).mapNotNull { f ->
            if (f.size == 3) DictRow(f[0], f[1], f[2].toIntOrNull() ?: 0) else null
        }

    /**
     * Thêm các cặp chữ [text] – từng âm trong [readings] (`cố,có`) khi [old] là `null`, hoặc sửa cặp [old]
     * thành [text] – [readings] (một âm); độ phổ biến [freq]. Trả `null` nếu được, ngược lại là lỗi.
     */
    @Synchronized fun put(old: DictRow?, text: String, readings: String, freq: Int): String? {
        val error = NativeCore.dictPut(handle, old?.text.orEmpty(), old?.reading.orEmpty(), text, readings, freq)
        if (error.isNotEmpty()) return error
        save()
        return null
    }

    @Synchronized fun remove(row: DictRow) {
        NativeCore.dictRemove(handle, row.text, row.reading)
        save()
    }

    /** Xem trước khi gộp các tệp: mỗi tệp một báo cáo, mục đã có ở tệp trước tính là trùng. */
    @Synchronized fun previewImport(contents: List<String>): List<ImportReport> =
        reports(NativeCore.dictImport(handle, join(contents), false))

    @Synchronized fun import(contents: List<String>): List<ImportReport> =
        reports(NativeCore.dictImport(handle, join(contents), true)).also { r -> if (r.any { it.added > 0 }) save() }

    /** Thay toàn bộ từ điển bằng các tệp [contents] (gộp lần lượt vào một từ điển rỗng). */
    @Synchronized fun replaceWith(contents: List<String>): List<ImportReport> {
        NativeCore.dictClear(handle)
        return reports(NativeCore.dictImport(handle, join(contents), true)).also { save() }
    }

    /** Gộp lại từ điển mặc định: thêm các mục mặc định còn thiếu, giữ nguyên chỗ đã sửa / thêm. */
    fun mergeBuiltin(): ImportReport = import(listOf(builtinText())).first()

    /** Thay toàn bộ từ điển bằng từ điển mặc định; mất mọi chỗ đã sửa / thêm. */
    @Synchronized fun resetToBuiltin() {
        NativeCore.dictLoad(handle, builtinText())
        save()
    }

    @Synchronized fun exportText(): String = NativeCore.dictText(handle)

    @Synchronized fun clear() {
        NativeCore.dictClear(handle)
        save()
    }

    private fun builtinText(): String = context.assets.open("dict/builtin.txt").bufferedReader().use { it.readText() }

    @Synchronized private fun save() {
        dictFile.writeAtomic(NativeCore.dictText(handle))
        dirty = false
    }

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
