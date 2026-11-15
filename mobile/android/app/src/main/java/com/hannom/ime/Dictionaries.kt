package com.hannom.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Ngôn ngữ có từ điển riêng: Tiếng Việt (gợi ý từ của bàn phím Quốc ngữ), Hán Nôm (lõi gõ), English. [code] là
 * tên thư mục `files/dicts/<code>/` và tên tài sản từ điển mặc định của [WordDict] (`vi`, `en`).
 */
enum class Lang(val code: String, val title: String) {
    VI("vi", "Tiếng Việt"),
    NOM("nom", "Hán Nôm"),
    EN("en", "English");

    /** Tên từ điển theo chính ngôn ngữ đó (bảng của người dùng 2026-11-14). */
    val defaultName get() = when (this) {
        VI -> "Từ điển mặc định"
        NOM -> "辭典默定"
        EN -> "Default dictionary"
    }

    fun personalName(n: Int) = when (this) {
        VI -> "Từ điển cá nhân $n"
        NOM -> "辭典個人 $n"
        EN -> "Personal dictionary $n"
    }

    companion object {
        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code }
    }
}

/** Một từ điển của một ngôn ngữ: từ điển mặc định ([isDefault], không xoá được) hay từ điển cá nhân. */
data class DictInfo(val lang: Lang, val id: String, val name: String, val enabled: Boolean) {
    val isDefault get() = id == DEFAULT_ID

    companion object {
        const val DEFAULT_ID = "default"
    }
}

/**
 * Danh sách từ điển của từng ngôn ngữ (tên, bật / tắt) và tệp của chúng (`files/dicts/<ngôn ngữ>/<id>.txt`).
 * Mỗi ngôn ngữ luôn có từ điển mặc định đứng đầu; các từ điển đang bật được gộp lại khi gõ (mặc định trước, rồi
 * từ điển cá nhân theo thứ tự tạo), mục trùng thì lấy ở từ điển trước, không sửa tệp nào.
 */
object Dictionaries {
    private const val PREFS = "dictionaries"

    fun dir(context: Context, lang: Lang) = File(context.filesDir, "dicts/${lang.code}").apply { mkdirs() }

    fun file(context: Context, lang: Lang, id: String) = File(dir(context, lang), "$id.txt")

    @Synchronized fun list(context: Context, lang: Lang): List<DictInfo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(lang.code, null)
        val out = ArrayList<DictInfo>()
        if (raw != null) runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val name = if (id == DictInfo.DEFAULT_ID) lang.defaultName else o.optString("name", id)
                out.add(DictInfo(lang, id, name, o.optBoolean("on", true)))
            }
        }
        if (out.none { it.isDefault }) out.add(0, DictInfo(lang, DictInfo.DEFAULT_ID, lang.defaultName, true))
        return out
    }

    /** Đã có danh sách lưu cho [lang] chưa (chưa thì là lần đầu, xem các bước chuyển dữ liệu cũ). */
    fun initialized(context: Context, lang: Lang) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(lang.code)

    @Synchronized fun save(context: Context, lang: Lang, dicts: List<DictInfo>) {
        val arr = JSONArray()
        dicts.forEach { d -> arr.put(JSONObject().put("id", d.id).put("name", d.name).put("on", d.enabled)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(lang.code, arr.toString()).apply()
    }

    fun enabled(context: Context, lang: Lang) = list(context, lang).filter { it.enabled }

    /** Tên gợi ý cho từ điển cá nhân mới: số nhỏ nhất chưa dùng. */
    fun nextPersonalName(context: Context, lang: Lang): String {
        val names = list(context, lang).map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map(lang::personalName).first { it !in names }
    }

    /** Thêm từ điển cá nhân rỗng (bật sẵn), cuối danh sách. */
    @Synchronized fun add(context: Context, lang: Lang, name: String): DictInfo {
        val dicts = list(context, lang)
        val used = dicts.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "p$it" }.first { it !in used && !file(context, lang, it).exists() }
        file(context, lang, id).writeText("")
        val info = DictInfo(lang, id, name.trim().ifEmpty { nextPersonalName(context, lang) }, true)
        save(context, lang, dicts + info)
        changed(context, lang)
        return info
    }

    @Synchronized fun update(context: Context, info: DictInfo) {
        save(context, info.lang, list(context, info.lang).map { if (it.id == info.id) info else it })
        changed(context, info.lang)
    }

    /** Xoá các từ điển cá nhân [ids] (từ điển mặc định thì bỏ qua) cùng tệp của chúng. */
    @Synchronized fun delete(context: Context, lang: Lang, ids: Collection<String>) {
        val gone = ids.filter { it != DictInfo.DEFAULT_ID }.toSet()
        if (gone.isEmpty()) return
        if (lang == Lang.NOM) HanNomEngine.get(context).forget(gone)
        gone.forEach { file(context, lang, it).delete() }
        save(context, lang, list(context, lang).filter { it.id !in gone })
        changed(context, lang)
    }

    /** Từ điển của [lang] đã đổi: bàn phím gộp lại khi cần. */
    fun changed(context: Context, lang: Lang) {
        if (lang == Lang.NOM) HanNomEngine.get(context).invalidate() else WordDict.reload(context, lang.code)
    }

    /** Các mục của một từ điển, cho màn hình quản lý. */
    fun store(context: Context, info: DictInfo): DictStore =
        if (info.lang == Lang.NOM) HanNomEngine.get(context).store(info.id) else WordStore(context, info)
}

/** Thao tác trên một từ điển cho màn hình quản lý (chung cho Hán Nôm và từ điển từ Quốc ngữ / tiếng Anh). */
interface DictStore {
    /** Hán Nôm có âm đọc (cột chữ – âm), từ điển từ chỉ có từ và độ phổ biến. */
    val hasReading: Boolean
    fun count(): Int
    fun rows(query: String = ""): List<DictRow>
    /** Thêm ([old] `null`) hay sửa một mục; trả lỗi hoặc `null` nếu được. */
    fun put(old: DictRow?, text: String, readings: String, freq: Int): String?
    fun remove(rows: Collection<DictRow>)
    fun previewImport(contents: List<String>): List<ImportReport>
    fun import(contents: List<String>): List<ImportReport>
    fun replaceWith(contents: List<String>): List<ImportReport>
    fun exportText(): String
    fun clear()
    /** Từ điển mặc định: gộp lại / thay bằng bản đóng gói sẵn. */
    fun mergeBuiltin(): ImportReport
    fun resetToBuiltin()
}

/**
 * Từ điển từ (Quốc ngữ, tiếng Anh) dạng tệp `từ;độ phổ biến`. Từ điển mặc định chưa sửa thì đọc thẳng tài sản đóng
 * gói (`assets/dict/<vi|en>.txt`), sửa lần đầu mới chép ra tệp.
 */
class WordStore(private val context: Context, private val info: DictInfo) : DictStore {
    private val file = Dictionaries.file(context, info.lang, info.id)
    private val words: LinkedHashMap<String, Int> = read()

    override val hasReading = false

    private fun read(): LinkedHashMap<String, Int> =
        if (file.exists()) parse(runCatching { file.readText() }.getOrDefault(""))
        else if (info.isDefault) parse(builtin(context, info.lang)) else LinkedHashMap()

    private fun save() {
        file.writeText(words.entries.joinToString("") { "${it.key};${it.value}\n" })
        Dictionaries.changed(context, info.lang)
    }

    override fun count() = words.size

    override fun rows(query: String): List<DictRow> {
        val q = WordDict.fold(query.trim())
        return words.entries.asSequence()
            .filter { q.isEmpty() || WordDict.fold(it.key).contains(q) }
            .map { DictRow(it.key, "", it.value) }
            .sortedWith(compareBy<DictRow> { WordDict.fold(it.text) }.thenBy { it.text })
            .toList()
    }

    override fun put(old: DictRow?, text: String, readings: String, freq: Int): String? {
        val word = clean(text)
        if (word.isEmpty()) return "Chưa nhập từ"
        if (word != old?.text && word in words) return "Đã có $word trong từ điển"
        if (old == null) {
            words[word] = freq
        } else {
            // Giữ chỗ cũ trong thứ tự tệp.
            val copy = LinkedHashMap<String, Int>()
            words.forEach { (k, v) -> if (k == old.text) copy[word] = freq else copy[k] = v }
            words.clear()
            words.putAll(copy)
        }
        save()
        return null
    }

    override fun remove(rows: Collection<DictRow>) {
        rows.forEach { words.remove(it.text) }
        save()
    }

    private fun plan(contents: List<String>): Pair<List<ImportReport>, LinkedHashMap<String, Int>> {
        val fresh = LinkedHashMap<String, Int>()
        val reports = contents.map { content ->
            var added = 0
            var duplicates = 0
            val errors = ArrayList<String>()
            content.lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(';', '\t', ',')
                val word = clean(parts[0])
                val f = parts.getOrNull(1)?.trim()
                val freq = if (f.isNullOrEmpty()) 0 else f.toIntOrNull()
                when {
                    word.isEmpty() || freq == null || freq < 0 -> errors.add("Dòng ${i + 1}: $line")
                    word in words || word in fresh -> duplicates++
                    else -> {
                        fresh[word] = freq
                        added++
                    }
                }
            }
            ImportReport(added, duplicates, errors)
        }
        return reports to fresh
    }

    override fun previewImport(contents: List<String>) = plan(contents).first

    override fun import(contents: List<String>): List<ImportReport> {
        val (reports, fresh) = plan(contents)
        if (fresh.isNotEmpty()) {
            words.putAll(fresh)
            save()
        }
        return reports
    }

    override fun replaceWith(contents: List<String>): List<ImportReport> {
        words.clear()
        return import(contents).also { save() }
    }

    override fun exportText() = words.entries.joinToString("") { "${it.key};${it.value}\n" }

    override fun clear() {
        words.clear()
        save()
    }

    override fun mergeBuiltin(): ImportReport = import(listOf(builtin(context, info.lang))).first()

    override fun resetToBuiltin() {
        words.clear()
        words.putAll(parse(builtin(context, info.lang)))
        save()
    }

    companion object {
        fun builtin(context: Context, lang: Lang): String =
            runCatching { context.assets.open("dict/${lang.code}.txt").bufferedReader().use { it.readText() } }.getOrDefault("")

        /** Từ viết liền một dấu cách giữa các chữ, NFC. */
        fun clean(raw: String): String =
            Normalizer.normalize(raw.trim().split(Regex("\\s+")).joinToString(" "), Normalizer.Form.NFC)

        /** `từ;số` mỗi dòng (cũng nhận tab hay dấu phẩy); dòng `#` bỏ qua, từ trùng lấy lần đầu. */
        fun parse(text: String): LinkedHashMap<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(';', '\t', ',')
                val word = clean(parts[0])
                if (word.isEmpty() || word in out) continue
                out[word] = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            }
            return out
        }

        /** Các từ của mọi từ điển đang bật của [lang], gộp lại: từ trùng lấy ở từ điển trước. */
        fun merged(context: Context, lang: Lang): LinkedHashMap<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for (d in Dictionaries.enabled(context, lang)) {
                val f = Dictionaries.file(context, lang, d.id)
                val words = if (f.exists()) parse(runCatching { f.readText() }.getOrDefault(""))
                else if (d.isDefault) parse(builtin(context, lang)) else continue
                words.forEach { (w, n) -> if (w !in out) out[w] = n }
            }
            return out
        }
    }
}
