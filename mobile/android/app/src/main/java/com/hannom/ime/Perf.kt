package com.hannom.ime

import android.util.Log

/** TẠM THỜI: đo thời gian để tìm chỗ lag (`adb logcat -s HanNomPerf`). Gỡ khi đo xong. */
internal inline fun <T> perf(name: String, detail: String = "", f: () -> T): T {
    val t = System.nanoTime()
    val r = f()
    val ms = (System.nanoTime() - t) / 1e6
    val main = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    Log.d("HanNomPerf", String.format("%-18s %7.1f ms %s %s", name, ms, if (main) "UI " else "nền", detail))
    return r
}
