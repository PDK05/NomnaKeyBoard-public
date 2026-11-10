package com.hannom.ime

import android.view.View
import android.view.ViewGroup
import android.widget.Button

/** Android 15+ vẽ tràn viền: chừa chỗ cho thanh trạng thái / điều hướng. */
fun fitInsets(view: View) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        insets
    }
}

fun ViewGroup.button(text: String, onClick: () -> Unit) = Button(context).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}.also { addView(it) }
