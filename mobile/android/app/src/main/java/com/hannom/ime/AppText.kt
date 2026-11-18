package com.hannom.ime

import android.content.Context
import java.util.Locale

/** "Ngôn ngữ ứng dụng" (đầu trang Ngôn ngữ): ngôn ngữ chữ trong ứng dụng cài đặt, không phải của bàn phím. */
enum class AppLang(val id: String) {
    /** Theo ngôn ngữ của máy: tiếng Việt nếu máy dùng tiếng Việt, không thì tiếng Anh. */
    SYSTEM("system"),
    VI("vi"),
    NOM("nom"),
    EN("en");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

object AppText {
    /** Tăng mỗi lần đổi ngôn ngữ ứng dụng: trang đã mở thấy khác số lúc dựng thì dựng lại. */
    @Volatile var version = 0
        private set

    /** Ngôn ngữ đang dùng (đã đổi [AppLang.SYSTEM] ra ngôn ngữ cụ thể). */
    fun lang(context: Context): AppLang {
        val chosen = AppLang.of(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null),
        )
        if (chosen != AppLang.SYSTEM) return chosen
        return if (Locale.getDefault().language == "vi") AppLang.VI else AppLang.EN
    }

    fun set(context: Context, lang: AppLang) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, lang.id).apply()
        version++
    }

    fun chosen(context: Context): AppLang =
        AppLang.of(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null))

    private const val KEY = "app_lang"
}

/**
 * Chữ giao diện ứng dụng theo ngôn ngữ ứng dụng. Chữ Hán Nôm do người dùng quyết định (danh sách ở
 * `app-labels.md`); chuỗi chưa có chữ Hán Nôm ([nom] = `null`) thì hiện tiếng Việt.
 */
fun Context.tr(vi: String, en: String, nom: String? = null): String = when (AppText.lang(this)) {
    AppLang.EN -> en
    AppLang.NOM -> nom ?: vi
    else -> vi
}
