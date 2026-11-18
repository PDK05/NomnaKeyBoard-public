package com.hannom.ime

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Phần chọn giao diện của trang "Giao diện", chép Gboard (đo trên điện thoại 2026-11-18): ba mục "Giao diện của tôi",
 * "Mặc định", "Màu". Không có giao diện từ ảnh riêng (ô "+") và "Màu tự động". Chạm một ô thì hiện bảng xem trước
 * phía dưới với công tắc "Đường viền phím" và nút "Hủy" / "Áp dụng".
 *
 * Kích thước đo trên Gboard (màn hình 354 dp): lưới 3 cột, lề hai bên 24 dp, khe giữa các ô 9 dp; ô 96 × 72 dp có vành
 * 3 dp; trong ô một thanh 37,5 × 7,7 dp (phím cách) và chấm tròn 12 dp (màu nhấn) ở góc dưới phải.
 */
class ThemePicker(private val activity: Activity, private val ui: SettingsColors) {
    private val settings = Settings(activity)
    private val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    /** "Màu" đã mở rộng (Gboard: mở rồi thì nút mũi tên biến mất). */
    private var colorsExpanded = false

    fun attach(parent: LinearLayout) {
        parent.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        build()
    }

    private fun build() {
        container.removeAllViews()
        val currentId = settings.themeId
        val currentBorders = settings.keyBorders

        // Giao diện của tôi: chỉ các giao diện đã áp dụng từ mục "Màu" (đang dùng có ✓); chưa có thì ẩn cả mục.
        val mine = settings.myThemes.toMutableList()
        if (ThemeSpec.isColor(currentId) && (currentId to currentBorders) !in mine) mine.add(0, currentId to currentBorders)
        if (mine.isNotEmpty()) {
            title(activity.tr("Giao diện của tôi", "My themes", "交面𧵑𫇈"), first = true)
            grid(mine.map { (id, borders) ->
                tile(ThemeSpec.find(id), borders, selected = id == currentId && borders == currentBorders, caption = null, mine = true)
            })
        }

        // Giao diện mặc định đang dùng thì ✓ ở ngay ô của nó.
        title(activity.tr("Mặc định", "Default", "默定"), first = mine.isEmpty())
        val captions = listOf(
            activity.tr("Tự động thay đổi theo hệ thống", "System Auto", "自動𠊝𢷮蹺系統"),
            activity.tr("Mặc định", "Default", "默定"),
            activity.tr("Giao diện tối mặc định", "Default Dark", "交面𣋁默定"),
        )
        grid(ThemeSpec.DEFAULTS.mapIndexed { i, spec ->
            tile(spec, true, selected = spec.id == currentId, caption = captions[i], previewBorders = true)
        })

        val colorTiles = if (colorsExpanded) ThemeSpec.COLORS else ThemeSpec.COLORS.take(9)
        title(activity.tr("Màu", "Colors", "𬜝"), expandable = !colorsExpanded) {
            colorsExpanded = true
            build()
        }
        // Ô màu vẽ kiểu không viền (như Gboard) nhưng bảng xem trước mở ra với "Đường viền phím" đang bật.
        grid(colorTiles.map { tile(it, false, selected = false, caption = null, previewBorders = true) })
    }

    private fun title(text: String, first: Boolean = false, expandable: Boolean = false, onExpand: (() -> Unit)? = null) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Gboard: tiêu đề mục đầu sát tiêu đề trang; các mục sau cách hàng ô trên chừng 33 dp.
            setPadding(activity.dpi(24), activity.dpi(if (first) 2 else 33), activity.dpi(32), activity.dpi(16))
        }
        row.addView(TextView(activity).apply {
            this.text = text
            setTextColor(ui.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (expandable && onExpand != null) {
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_expand)
                setColorFilter(ui.text)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = activity.dpi(1)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ui.bar)
                }
                contentDescription = activity.tr("Mở rộng", "Expand", "𨷑𢌊")
                setOnClickListener { onExpand() }
            }, LinearLayout.LayoutParams(activity.dpi(24), activity.dpi(24)))
        }
        container.addView(row)
    }

    /** Lưới 3 cột; ô cuối hàng thiếu thì để trống bên phải như Gboard. */
    private fun grid(tiles: List<View>) {
        val side = activity.dpi(24)
        val gap = activity.dpi(9)
        tiles.chunked(3).forEachIndexed { r, chunk ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(side, if (r == 0) 0 else gap, side, 0)
            }
            for (i in 0 until 3) {
                val v = chunk.getOrNull(i) ?: View(activity)
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
            container.addView(row)
        }
    }

    private fun tile(
        spec: ThemeSpec,
        borders: Boolean,
        selected: Boolean,
        caption: String?,
        previewBorders: Boolean = borders,
        mine: Boolean = false,
    ): View {
        val swatch = ThemeSwatch(activity, ui, spec, borders, selected)
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (caption != null) {
                addView(TextView(activity).apply {
                    text = caption
                    setTextColor(ui.summary)
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    // Cỡ chữ so theo Gboard trên cùng máy (chữ dưới ô nhỏ hơn tiêu đề mục); chữ dài ("Tự động thay đổi
                    // theo hệ thống") tự nhỏ lại cho vừa ô như Gboard.
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                        val tv = v as TextView
                        val avail = (r - l).toFloat()
                        if (avail > 0 && tv.paint.measureText(caption) > avail) {
                            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.textSize * avail / tv.paint.measureText(caption) * 0.98f)
                        }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(24)).apply {
                    topMargin = -activity.dpi(3)
                })
            }
        }
        swatch.setOnClickListener { preview(spec, previewBorders, mine) }
        return cell
    }

    /**
     * Bảng xem trước phía dưới (Gboard): bàn phím mẫu, công tắc "Đường viền phím", nút "Hủy" / "Áp dụng". Mở từ
     * "Giao diện của tôi" ([mine]) thì có thêm nút thùng rác góc trên phải để xoá giao diện đó khỏi danh sách.
     */
    private fun preview(spec: ThemeSpec, initialBorders: Boolean, mine: Boolean) {
        val a = activity
        var borders = if (!mine && spec.id == settings.themeId) settings.keyBorders else initialBorders
        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val keyboard = settings.keyboard
        val layout = if (keyboard.layout == Layout.HANDWRITING) Layout.QWERTY else keyboard.layout
        val previewBox = FrameLayout(a)
        fun showPreview() {
            previewBox.removeAllViews()
            previewBox.addView(KeyboardPreview(a, keyboard.mode, layout, spec.resolve(a).build(borders), header = true))
        }
        showPreview()

        val sheetColor = if (ui.light) Color.parseColor("#F2F3FF") else Color.parseColor("#262A35")
        val sheet = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            // Có nút thùng rác thì bàn phím mẫu lùi xuống (Gboard: tâm nút cách mép trên 48 dp, bàn phím ở 84 dp).
            setPadding(a.dpi(24), a.dpi(if (mine) 24 else 36), a.dpi(24), a.dpi(28))
            background = GradientDrawable().apply {
                val r = a.dp(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(sheetColor)
            }
        }
        if (mine) {
            sheet.addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_delete_outline)
                setColorFilter(ui.primary)
                scaleType = ImageView.ScaleType.CENTER
                background = a.iconRipple(ui)
                contentDescription = a.tr("Xóa", "Delete", "𫨮")
                setOnClickListener {
                    confirmDelete {
                        settings.removeMyTheme(spec.id, initialBorders)
                        dialog.dismiss()
                        build()
                    }
                }
            }, LinearLayout.LayoutParams(a.dpi(48), a.dpi(48)).apply {
                gravity = Gravity.END
                bottomMargin = a.dpi(12)
            })
        }
        val width = a.resources.displayMetrics.widthPixels - a.dpi(48)
        sheet.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (width * 0.79f).toInt()))

        @Suppress("DEPRECATION")
        val toggle = Switch(a).apply {
            isChecked = borders
            thumbDrawable = switchThumb(ui)
            trackDrawable = switchTrack(ui)
            showText = false
            setOnCheckedChangeListener { _, v ->
                borders = v
                showPreview()
            }
        }
        sheet.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggle.toggle() }
            addView(TextView(a).apply {
                text = a.tr("Đường viền phím", "Key borders", "塘𧜘柉")
                setTextColor(ui.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(64)).apply { topMargin = a.dpi(16) })

        fun button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(a).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(a.dpi(24), 0, a.dpi(24), 0)
            minWidth = a.dpi(88)
            background = GradientDrawable().apply {
                cornerRadius = a.dp(20f)
                if (filled) {
                    setColor(if (ui.light) Color.parseColor("#E2DFFF") else Color.parseColor("#3E4759"))
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(a.dpi(1), if (ui.light) Color.parseColor("#737685") else ui.summary)
                }
            }
            setTextColor(when {
                !filled -> ui.primary
                ui.light -> Color.parseColor("#444465")
                else -> Color.parseColor("#DAE2F9")
            })
            setOnClickListener { onClick() }
        }
        sheet.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(a.tr("Hủy", "Cancel", "毀"), false) { dialog.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(40)))
            addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button(a.tr("Áp dụng", "Apply", "壓用"), true) {
                settings.applyTheme(spec.id, borders)
                dialog.dismiss()
                build()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(40)).apply { marginStart = a.dpi(16) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = a.dpi(24)
        })

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            // Bảng nối liền xuống dưới thanh điều hướng.
            navigationBarColor = sheetColor
        }
        dialog.show()
    }

    /**
     * Hỏi lại trước khi xoá (Gboard, đo trên điện thoại 2026-11-18): thẻ trắng bo tròn, icon bảng màu xanh ở trên, câu
     * hỏi chữ lớn ở giữa, hai nút nền nhạt "Không" / "Có".
     */
    private fun confirmDelete(onYes: () -> Unit) {
        val a = activity
        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun button(text: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(a).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(fg)
            background = GradientDrawable().apply {
                cornerRadius = a.dp(20f)
                setColor(bg)
            }
            setOnClickListener { onClick() }
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dpi(24), a.dpi(20), a.dpi(24), a.dpi(18))
            background = GradientDrawable().apply {
                cornerRadius = a.dp(28f)
                setColor(if (ui.light) Color.WHITE else Color.parseColor("#2B2F3A"))
            }
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_palette)
                setColorFilter(ui.primary)
            }, LinearLayout.LayoutParams(a.dpi(24), a.dpi(24)))
            addView(TextView(a).apply {
                text = a.tr("Bạn có chắc chắn muốn xóa chủ đề không?", "Are you sure you want to delete the theme?", "伴𣎏𢡠鎮㦖𫨮主提空？")
                gravity = Gravity.CENTER
                setTextColor(ui.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = a.dpi(14)
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(a.tr("Không", "No", "空"), if (ui.light) Color.parseColor("#DFE2F2") else Color.parseColor("#3A3F4C"), ui.text) {
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(a.dpi(91), a.dpi(40)))
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                addView(button(a.tr("Có", "Yes", "𣎏"), if (ui.light) Color.parseColor("#DAE1FF") else Color.parseColor("#3E4759"), ui.primary) {
                    dialog.dismiss()
                    onYes()
                }, LinearLayout.LayoutParams(a.dpi(88), a.dpi(40)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = a.dpi(22)
            })
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(a.dpi(288), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}

/** Ô mẫu một giao diện: nền bàn phím, thanh phím cách, chấm màu nhấn; giao diện đang dùng có vành xanh và dấu ✓. */
private class ThemeSwatch(
    context: Context,
    private val colors: SettingsColors,
    spec: ThemeSpec,
    borders: Boolean,
    private val selected: Boolean,
) : View(context) {
    private val split = spec.isSystem
    private val theme = (if (split) ThemeSpec.LIGHT else spec).build(borders)
    private val dark = if (split) ThemeSpec.DARK.build(borders) else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val check = context.getDrawable(R.drawable.ic_check)!!.mutate()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 72 / 96)
    }

    private fun barColor(t: KeyboardTheme) = if (t.borderless) ThemeSpec.blend(t.spaceBar, t.background) else t.key

    override fun onDraw(canvas: Canvas) {
        val ring = context.dp(3f)
        val w = width.toFloat()
        val h = height.toFloat()
        // Vành: xanh khi đang dùng.
        paint.color = if (selected) colors.primary else colors.bar
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, context.dp(20f), context.dp(20f), paint)
        rect.set(ring, ring, w - ring, h - ring)
        val r = context.dp(17f)
        paint.color = theme.background
        canvas.drawRoundRect(rect, r, r, paint)

        // Thanh (phím cách) và chấm (màu nhấn), đo từ ô Gboard.
        val dot = context.dp(12f)
        val dotRight = rect.right - context.dp(4.6f)
        val cy = rect.bottom - context.dp(9f) - context.dp(3.85f)
        val barRight = dotRight - dot - context.dp(9f)
        val barLeft = barRight - context.dp(37.5f)
        val barHalf = context.dp(3.85f)
        paint.color = barColor(theme)
        val bar = RectF(barLeft, cy - barHalf, barRight, cy + barHalf)
        canvas.drawRoundRect(bar, context.dp(2f), context.dp(2f), paint)

        if (dark != null) {
            // "Tự động thay đổi theo hệ thống": nửa phải là giao diện tối.
            canvas.save()
            canvas.clipRect(rect.centerX(), 0f, w, h)
            paint.color = dark.background
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = barColor(dark)
            canvas.drawRoundRect(bar, context.dp(2f), context.dp(2f), paint)
            canvas.restore()
        }
        paint.color = (dark ?: theme).accent
        canvas.drawCircle(dotRight - dot / 2, cy, dot / 2, paint)

        if (selected) {
            val cr = context.dp(16f)
            paint.color = colors.primary
            canvas.drawCircle(w / 2, h / 2, cr, paint)
            val half = context.dpi(11)
            check.setTint(colors.onPrimary)
            check.setBounds((w / 2).toInt() - half, (h / 2).toInt() - half, (w / 2).toInt() + half, (h / 2).toInt() + half)
            check.draw(canvas)
        }
    }
}
