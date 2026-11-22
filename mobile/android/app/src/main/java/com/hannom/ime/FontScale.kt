package com.hannom.ime

/**
 * Phóng cỡ chữ phi tuyến như Android 14 (FontScaleConverterFactory của AOSP): chữ càng to càng phóng ít. Gboard dùng
 * cách này cho "Cỡ chữ" (đo trên điện thoại 2026-11-22: chữ phím 24 sp ở nấc 200% chỉ lên 36 sp, `?123` 16 sp lên
 * ~28 sp). Chép bảng vào đây để mọi máy (minSdk 26) cho cùng kết quả.
 */
object FontScale {
    private val FROM_SP = floatArrayOf(8f, 10f, 12f, 14f, 18f, 20f, 24f, 30f, 100f)
    private val SCALES = floatArrayOf(1.05f, 1.1f, 1.15f, 1.2f, 1.3f, 1.5f, 1.8f, 2f)
    private val TO_DP = arrayOf(
        floatArrayOf(8.4f, 10.5f, 12.6f, 14.8f, 18.6f, 20.6f, 24.4f, 30f, 100f),
        floatArrayOf(8.8f, 11f, 13.2f, 15.6f, 19.2f, 21.2f, 24.8f, 30f, 100f),
        floatArrayOf(9.2f, 11.5f, 13.8f, 16.4f, 19.8f, 21.8f, 25.2f, 30f, 100f),
        floatArrayOf(9.6f, 12f, 14.4f, 17.2f, 20.4f, 22.4f, 25.6f, 30f, 100f),
        floatArrayOf(10.4f, 13f, 15.6f, 18.8f, 21.6f, 23.6f, 26.4f, 30f, 100f),
        floatArrayOf(12f, 15f, 18f, 22f, 24f, 26f, 28f, 30f, 100f),
        floatArrayOf(14.4f, 18f, 21.6f, 24.4f, 27.6f, 30.8f, 32.8f, 34.8f, 100f),
        floatArrayOf(16f, 20f, 24f, 26f, 30f, 34f, 36f, 38f, 100f),
    )

    /** Cỡ [sp] sau khi phóng theo hệ số [scale] (1 là 100%); dưới 1,04 hoặc trên 2 thì phóng thẳng như Android. */
    fun apply(scale: Float, sp: Float): Float {
        if (scale < 1.04f || scale > 2f) return sp * scale
        val hi = SCALES.indexOfFirst { it >= scale - 1e-4f }
        if (hi <= 0) return if (hi == 0 && kotlin.math.abs(SCALES[0] - scale) < 1e-4f) lookup(TO_DP[0], sp) else sp * scale
        if (kotlin.math.abs(SCALES[hi] - scale) < 1e-4f) return lookup(TO_DP[hi], sp)
        val t = (scale - SCALES[hi - 1]) / (SCALES[hi] - SCALES[hi - 1])
        val a = lookup(TO_DP[hi - 1], sp)
        return a + (lookup(TO_DP[hi], sp) - a) * t
    }

    /**
     * Icon trên phím to theo "Cỡ chữ" khác chữ: đo Gboard trên điện thoại 2026-11-22, bề rộng icon so với nấc 100% ở
     * các nấc [ICON_LEVELS]. Shift, xoá, enter giữ cỡ tới 150% rồi mới to lên; shift to nhanh hơn xoá / enter.
     */
    private val ICON_LEVELS = floatArrayOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)
    val SHIFT_ICON = floatArrayOf(0.85f, 1f, 1.03f, 1.03f, 1.03f, 1.21f, 1.33f)
    val KEY_ICON = floatArrayOf(0.85f, 1f, 1f, 1f, 1f, 1.15f, 1.2f)
    /** Nhãn chữ nhỏ kiểu icon: `12/34`, `=\<` (Gboard: 150% vẫn như 100%, 180% ×1,15, 200% ×1,25). */
    val SMALL_LABEL = floatArrayOf(0.85f, 1f, 1f, 1f, 1f, 1.15f, 1.25f)
    /** Icon thanh công cụ (micro, bảng nhớ tạm, con trỏ, bánh răng…; trung bình các icon). Nút ⊞ to ít hơn: [LEAD_ICON]. */
    val TOOL_ICON = floatArrayOf(0.85f, 1f, 1.07f, 1.14f, 1.24f, 1.44f, 1.53f)
    val LEAD_ICON = floatArrayOf(0.85f, 1f, 1f, 1.03f, 1.03f, 1.21f, 1.21f)
    /**
     * Icon trong ô của bảng tiện ích (nút ⊞): đo Gboard 2026-11-22 (bảng màu, hoàn tác). Ô giữ cao 48 dp, tâm icon và
     * chỗ bắt đầu chữ đứng yên; chữ trong ô to như chữ 14 sp theo bảng phóng ([TILE_TEXT_BASE]).
     */
    val TILE_ICON = floatArrayOf(0.85f, 1f, 1.01f, 1.03f, 1.05f, 1.22f, 1.33f)
    /** Chữ trong ô bảng tiện ích (16 sp ở 100%) to theo tỉ lệ của chữ cỡ này: đo Gboard 200% ×1,86 = 26/14. */
    const val TILE_TEXT_BASE = 14f

    /** Hệ số icon theo [table] ở cỡ chữ [scale]; giữa hai nấc thì nội suy (cỡ chữ thiết bị), ngoài thì lấy nấc biên. */
    fun icon(table: FloatArray, scale: Float): Float {
        if (scale <= ICON_LEVELS[0]) return table[0] * scale / ICON_LEVELS[0]
        if (scale >= ICON_LEVELS.last()) return table.last()
        val i = ICON_LEVELS.indexOfFirst { it >= scale }
        val t = (scale - ICON_LEVELS[i - 1]) / (ICON_LEVELS[i] - ICON_LEVELS[i - 1])
        return table[i - 1] + (table[i] - table[i - 1]) * t
    }

    private fun lookup(to: FloatArray, sp: Float): Float {
        if (sp <= FROM_SP[0]) return sp * to[0] / FROM_SP[0]
        if (sp >= FROM_SP.last()) return sp * to.last() / FROM_SP.last()
        val i = FROM_SP.indexOfFirst { it >= sp }
        val t = (sp - FROM_SP[i - 1]) / (FROM_SP[i] - FROM_SP[i - 1])
        return to[i - 1] + (to[i] - to[i - 1]) * t
    }
}
