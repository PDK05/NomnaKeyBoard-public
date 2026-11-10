package com.hannom.ime

/** Hàm JNI của lõi Rust (`android-ffi/src/lib.rs`). Chỉ gọi từ luồng chính. */
object NativeCore {
    init {
        System.loadLibrary("hannom_jni")
    }

    /** Ngăn cách bản ghi / trường trong chuỗi trả về. */
    const val RS = '\u001E'
    const val US = '\u001F'

    @JvmStatic external fun create(dict: String): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun setOptions(handle: Long, toneStyle: Int, dz: Boolean, learning: Boolean, telex: Boolean)
    @JvmStatic external fun compose(keys: String, toneStyle: Int, hanNom: Boolean, dz: Boolean): String
    @JvmStatic external fun composeJoined(keys: String, toneStyle: Int, dz: Boolean): String
    @JvmStatic external fun display(keys: String, dz: Boolean): String
    @JvmStatic external fun displayTelex(keys: String, toneStyle: Int): String
    @JvmStatic external fun normalize(keys: String, dz: Boolean): String
    @JvmStatic external fun decompose(word: String, hanNom: Boolean, dz: Boolean): String
    @JvmStatic external fun backspace(keys: String, toneStyle: Int): String
    @JvmStatic external fun backspaceTelex(keys: String, toneStyle: Int): String
    @JvmStatic external fun candidates(handle: Long, keys: String, limit: Int, quocNgu: Boolean): String
    @JvmStatic external fun narrow(handle: Long, keys: String, picked: String): String
    @JvmStatic external fun candidatesNarrowed(handle: Long, keys: String, picked: String, limit: Int): String
    @JvmStatic external fun candidatesAfter(handle: Long, before: String, keys: String, limit: Int): String
    @JvmStatic external fun select(handle: Long, text: String, reading: String): Boolean
    @JvmStatic external fun clearLearned(handle: Long, defaults: String)
    @JvmStatic external fun dictText(handle: Long): String
    @JvmStatic external fun dictLoad(handle: Long, content: String)
    @JvmStatic external fun dictImport(handle: Long, contents: String, apply: Boolean): String
    @JvmStatic external fun dictClear(handle: Long)
    @JvmStatic external fun dictRows(handle: Long, query: String): String
    @JvmStatic external fun dictPut(handle: Long, oldText: String, oldReading: String, text: String, readings: String, freq: Int): String
    @JvmStatic external fun dictRemove(handle: Long, text: String, reading: String)
}
