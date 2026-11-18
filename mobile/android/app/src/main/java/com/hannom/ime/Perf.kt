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

/**
 * TẠM THỜI: ghi các khung hình chậm (> 32 ms) của cửa sổ bàn phím, tách từng bước (xử lý chạm, dàn trang, vẽ…), để
 * tìm chỗ lag không nằm trong các đoạn đã đo bằng [perf]. Gỡ khi đo xong.
 */
internal fun watchFrames(window: android.view.Window?) {
    if (window == null || android.os.Build.VERSION.SDK_INT < 24) return
    val handler = android.os.Handler(android.os.HandlerThread("frames").apply { start() }.looper)
    window.addOnFrameMetricsAvailableListener({ _, m, _ ->
        fun ms(id: Int) = m.getMetric(id) / 1e6
        val total = ms(android.view.FrameMetrics.TOTAL_DURATION)
        if (total > 32) {
            Log.d(
                "HanNomPerf", String.format(
                    "frame %6.1f ms: input %5.1f anim %5.1f layout %6.1f draw %5.1f sync %5.1f gpu-cmd %5.1f delay %5.1f",
                    total, ms(android.view.FrameMetrics.INPUT_HANDLING_DURATION),
                    ms(android.view.FrameMetrics.ANIMATION_DURATION), ms(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION),
                    ms(android.view.FrameMetrics.DRAW_DURATION), ms(android.view.FrameMetrics.SYNC_DURATION),
                    ms(android.view.FrameMetrics.COMMAND_ISSUE_DURATION), ms(android.view.FrameMetrics.UNKNOWN_DELAY_DURATION),
                ),
            )
        }
    }, handler)
}
