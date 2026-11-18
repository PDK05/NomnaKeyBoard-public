package com.hannom.ime

import android.app.Activity
import android.os.Build
import android.view.View

/**
 * Bảng của bàn phím (clipboard, công cụ…) ở màn hình ngang: cửa sổ Gboard phủ cả tai thỏ và lùi nội dung vào bằng tai
 * thỏ ở cả hai bên. Cửa sổ của ta né tai thỏ thì bên đó đã lùi sẵn, chỉ thêm ở bên kia. Trả về (thêm trái, thêm phải)
 * theo px; màn hình dọc hay không có tai thỏ thì (0, 0).
 */
fun View.cutoutSides(): Pair<Int, Int> {
    val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (!landscape || Build.VERSION.SDK_INT < 29 || !isAttachedToWindow) return 0 to 0
    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    @Suppress("DEPRECATION") val display = wm.defaultDisplay
    val cut = display.cutout ?: return 0 to 0
    val size = maxOf(cut.safeInsetLeft, cut.safeInsetRight)
    if (size == 0) return 0 to 0
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    val real = android.graphics.Point()
    @Suppress("DEPRECATION") display.getRealSize(real)
    return if (cut.safeInsetLeft > 0) maxOf(0, size - loc[0]) to size
    else size to maxOf(0, size - (real.x - loc[0] - width))
}

/**
 * Máy có tai thỏ / camera đục lỗ ở cạnh (thường gặp khi xoay ngang): nội dung activity lùi vào mỗi bên đúng phần bị che,
 * để chữ và nút không nằm dưới camera. Bên không có tai thỏ thì không lùi. Gọi trong onCreate.
 */
fun Activity.padForCutout() {
    if (Build.VERSION.SDK_INT < 28) return
    val content = window.decorView.findViewById<View>(android.R.id.content) ?: return
    content.setOnApplyWindowInsetsListener { v, insets ->
        val cut = insets.displayCutout
        val left = cut?.safeInsetLeft ?: 0
        val right = cut?.safeInsetRight ?: 0
        if (v.paddingLeft != left || v.paddingRight != right) v.setPadding(left, v.paddingTop, right, v.paddingBottom)
        // Dải lề cùng màu nền trang (không lộ nền trắng của cửa sổ).
        val page = (v as? android.view.ViewGroup)?.getChildAt(0)?.background
        if (page is android.graphics.drawable.ColorDrawable) v.setBackgroundColor(page.color)
        insets
    }
    content.requestApplyInsets()
}
