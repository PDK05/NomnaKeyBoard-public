package com.hannom.ime

import android.app.Activity
import android.os.Build
import android.view.View

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
