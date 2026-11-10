package com.hannom.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** Hàm JNI của bộ nhận dạng viết tay (`android-ffi/src/lib.rs`). */
object NativeHandwriting {
    init {
        System.loadLibrary("hannom_jni")
    }

    @JvmStatic external fun create(model: ByteArray): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun recognize(handle: Long, memory: Long, xy: FloatArray, canvas: Float, limit: Int): String
    @JvmStatic external fun recognizeLine(handle: Long, memory: Long, xy: FloatArray, canvas: Float, limit: Int): String
    @JvmStatic external fun memoryLoad(handle: Long, text: String): Long
    @JvmStatic external fun memoryFree(memory: Long)
    @JvmStatic external fun memoryAdd(handle: Long, memory: Long, xy: FloatArray, canvas: Float, text: String): Boolean
    @JvmStatic external fun memoryText(handle: Long, memory: Long): String
    @JvmStatic external fun memorySize(memory: Long): Int
    @JvmStatic external fun memoryClear(memory: Long)
}

/** Một chữ trong dòng viết tay: chỉ số các nét của nó và các chữ khả dĩ nhất. */
class HandwrittenChar(val strokes: List<Int>, val chars: List<String>)

/**
 * Dòng viết tay: các chữ từ trái sang phải, gợi ý khi coi cả dòng là một chữ ([whole], có khi từ hai chữ), và
 * cách tách có số chữ khác ([split]: viết 𠊛 nhận là một chữ thì là 人㝵, và ngược lại), rỗng nếu không có.
 */
class HandwrittenLine(val chars: List<HandwrittenChar>, val whole: List<String>, val split: String = "")

/**
 * Nhận dạng chữ viết tay bằng mô hình `assets/models/hw.bin`, chạy ở luồng nền.
 * Kết quả cũ (nét đã thay đổi trong lúc chờ) bị bỏ, chỉ kết quả mới nhất được trả về luồng chính.
 *
 * Chữ người dùng chọn khi gợi ý đầu sai lưu ở [LEARNED] và được ưu tiên khi viết lại giống vậy.
 * Mọi lệnh gọi JNI chạy trên [worker].
 */
class Handwriting private constructor(context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val file = File(context.filesDir, LEARNED)
    @Volatile private var handle = 0L
    private var memory = 0L
    private var generation = 0

    init {
        val app = context.applicationContext
        worker.execute {
            handle = runCatching { NativeHandwriting.create(app.assets.open(MODEL).use { it.readBytes() }) }.getOrDefault(0L)
            if (handle != 0L) {
                val saved = runCatching { file.readText() }.getOrDefault("")
                memory = NativeHandwriting.memoryLoad(handle, saved)
            }
        }
    }

    /** Gửi chữ khả dĩ nhất trước cho [onResult] trên luồng chính. */
    fun recognize(strokes: List<FloatArray>, cell: Float, limit: Int = 40, onResult: (List<String>) -> Unit) {
        val gen = ++generation
        val xy = pack(strokes)
        worker.execute {
            val h = handle
            val raw = if (h == 0L) "" else NativeHandwriting.recognize(h, memory, xy, cell, limit)
            val chars = raw.split('\n').filter { it.isNotEmpty() }
            main.post { if (gen == generation) onResult(chars) }
        }
    }

    /**
     * Nhận dạng nhiều chữ viết liền một dòng (trái sang phải), gửi kết quả cho [onResult] trên luồng chính.
     * [cell] là chiều cao vùng viết.
     */
    fun recognizeLine(strokes: List<FloatArray>, cell: Float, limit: Int = 20, onResult: (HandwrittenLine) -> Unit) {
        val gen = ++generation
        val xy = pack(strokes)
        worker.execute {
            val h = handle
            val raw = if (h == 0L) "" else NativeHandwriting.recognizeLine(h, memory, xy, cell, limit)
            val segments = ArrayList<HandwrittenChar>()
            var whole = emptyList<String>()
            var split = ""
            for (record in raw.split(NativeCore.RS).filter { it.isNotEmpty() }) {
                val fields = record.split(NativeCore.US)
                val chars = fields.drop(1).filter { it.isNotEmpty() }
                when (fields[0]) {
                    "*" -> whole = chars
                    "~" -> split = chars.firstOrNull().orEmpty()
                    else -> segments.add(HandwrittenChar(fields[0].split(',').map { it.toInt() }, chars))
                }
            }
            val line = HandwrittenLine(segments.filter { it.chars.isNotEmpty() }, whole, split)
            main.post { if (gen == generation) onResult(line) }
        }
    }

    /** Bỏ mọi kết quả đang chờ (vd. đã chọn chữ hoặc xoá hết nét). */
    fun cancel() {
        generation++
    }

    /** Ghi nhớ nét [strokes] là chữ [text]. */
    fun learn(strokes: List<FloatArray>, cell: Float, text: String) {
        val xy = pack(strokes)
        worker.execute {
            if (memory != 0L && NativeHandwriting.memoryAdd(handle, memory, xy, cell, text)) save()
        }
    }

    /** Số mẫu đã dạy, gửi về luồng chính. */
    fun learnedCount(onResult: (Int) -> Unit) {
        worker.execute {
            val n = if (memory == 0L) 0 else NativeHandwriting.memorySize(memory)
            main.post { onResult(n) }
        }
    }

    /** Nội dung tệp mẫu (để xuất ra, dùng huấn luyện lại mô hình), gửi về luồng chính. */
    fun exportLearned(onResult: (String) -> Unit) {
        worker.execute {
            val text = if (memory == 0L) "" else NativeHandwriting.memoryText(handle, memory)
            main.post { onResult(text) }
        }
    }

    fun clearLearned() {
        worker.execute {
            if (memory != 0L) {
                NativeHandwriting.memoryClear(memory)
                save()
            }
        }
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        runCatching {
            tmp.writeText(NativeHandwriting.memoryText(handle, memory))
            tmp.renameTo(file)
        }
    }

    private fun pack(strokes: List<FloatArray>): FloatArray {
        val out = FloatArray(strokes.sumOf { it.size + 2 })
        var i = 0
        for (s in strokes) {
            s.copyInto(out, i)
            i += s.size
            out[i++] = Float.NaN
            out[i++] = Float.NaN
        }
        return out
    }

    companion object {
        const val MODEL = "models/hw.bin"
        const val LEARNED = "handwriting-learned.txt"

        @Volatile private var instance: Handwriting? = null

        /** Ứng dụng có đóng gói mô hình viết tay không. */
        fun available(context: Context): Boolean =
            context.assets.list("models")?.contains("hw.bin") == true

        fun get(context: Context): Handwriting =
            instance ?: synchronized(this) {
                instance ?: Handwriting(context).also { instance = it }
            }
    }
}
