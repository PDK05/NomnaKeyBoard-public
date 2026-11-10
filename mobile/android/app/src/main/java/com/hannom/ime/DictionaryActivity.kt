package com.hannom.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Từ điển (kiểu Gboard). Chỉ dùng một từ điển: lần đầu là bản sao từ điển mặc định. Mỗi cặp chữ – âm
 * một dòng (chữ to, âm đọc bên dưới, bên phải là mã Unicode và độ phổ biến), xếp a → z như tệp lưu; thanh
 * cuộn nhanh ở mép phải để kéo lướt cả từ điển. Chạm một dòng mở trang sửa (thùng rác để xoá, quay lại là lưu).
 * Nhập nhiều tệp `chữ;âm;độ phổ biến` một lượt: gộp vào từ điển hoặc thay toàn bộ; dòng đúng cú pháp được
 * nhận, dòng lỗi bị bỏ qua. Có thể nhập lại từ điển mặc định.
 * Cũng nhận tệp .txt được chia sẻ hoặc mở từ ứng dụng khác.
 */
class DictionaryActivity : Activity() {
    private lateinit var engine: HanNomEngine
    private lateinit var colors: SettingsColors
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var titleBlock: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private lateinit var searchButton: View
    private lateinit var addButton: View
    private lateinit var clearButton: View
    private lateinit var list: ListView
    private val adapter = RowAdapter()
    private var shown: List<DictRow> = emptyList()
    private var descending = false
    private var insetTop = 0
    private var insetBottom = 0

    override fun finish() {
        if (!finishKeyboardTask()) super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padForCutout()
        engine = HanNomEngine.get(this)
        colors = SettingsColors.of(this)

        // Thanh tiêu đề: ← Từ điển (số mục) · tìm · thêm · ⋮. Bấm tìm thì tiêu đề thành ô tìm kiếm.
        titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Từ điển"
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                isSingleLine = true
            })
            subtitle = TextView(context).apply {
                setTextColor(colors.summary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isSingleLine = true
            }
            addView(subtitle)
        }
        search = EditText(this).apply {
            hint = "Tìm chữ, âm đọc hoặc mã U"
            setTextColor(colors.text)
            setHintTextColor(colors.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = null
            setSingleLine()
            useHanNomFont()
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    reload()
                }
            })
        }
        clearButton = barIcon(R.drawable.ic_close, "Xoá ô tìm") { search.setText("") }.apply { visibility = View.GONE }
        searchButton = barIcon(R.drawable.ic_search, "Tìm") { openSearch() }
        addButton = barIcon(R.drawable.ic_add, "Thêm") { openEditor(null, null) }
        lateinit var more: View
        more = barIcon(R.drawable.ic_more_vert, "Tuỳ chọn khác") { showMenu(more) }
        bar = barRow { if (search.visibility == View.VISIBLE) closeSearch() else finish() }.apply {
            addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(12) })
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(4) })
            listOf(clearButton, searchButton, addButton, more).forEach { addView(it, LinearLayout.LayoutParams(dpi(48), dpi(48))) }
            setPadding(0, 0, dpi(4), 0)
        }

        list = ListView(this).apply {
            this.adapter = this@DictionaryActivity.adapter
            divider = ColorDrawable(dividerColor())
            dividerHeight = maxOf(1, dpi(1) / 2)
            selector = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, ColorDrawable(Color.WHITE))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { openEditor(it, null) } }
        }
        val empty = TextView(this).apply {
            text = "Không có mục nào"
            setTextColor(colors.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        list.emptyView = empty
        // Bong bóng khi kéo thanh cuộn: âm tiết đầu của dòng đang tới.
        val scroller = FastScroller(this, list, colors) { pos -> shown.getOrNull(pos)?.reading?.substringBefore(' ') }
        val listFrame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(colors.background)
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // Vẽ tràn viền: thanh tiêu đề chừa thanh trạng thái, danh sách / trang sửa chừa thanh điều hướng và bàn phím.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            if (top != insetTop || bottom != insetBottom) {
                insetTop = top
                insetBottom = bottom
                applyInsets()
            }
            insets
        }
        setContentView(root)
        styleSystemBars(colors)
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.background

        reload()
        handleIntent(intent)
    }

    private fun applyInsets() {
        bar.setPadding(0, insetTop, dpi(4), 0)
        list.setPadding(0, 0, 0, insetBottom)
    }

    private fun dividerColor() = if (colors.light) Color.parseColor("#C6C6D0") else Color.parseColor("#3B3E4A")

    /** Thanh tiêu đề (nền xám như Gboard) có nút quay lại ở đầu. */
    private fun barRow(onBack: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.bar)
        minimumHeight = dpi(64)
        addView(barIcon(R.drawable.ic_arrow_back, "Quay lại", onBack), LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { marginStart = dpi(4) })
    }

    private fun barIcon(icon: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(colors.text)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = iconRipple(colors)
        setOnClickListener { onClick() }
    }

    private fun openSearch() {
        titleBlock.visibility = View.GONE
        searchButton.visibility = View.GONE
        addButton.visibility = View.GONE
        search.visibility = View.VISIBLE
        showKeyboard(search)
    }

    private fun closeSearch() {
        hideKeyboard(search)
        search.setText("")
        search.visibility = View.GONE
        titleBlock.visibility = View.VISIBLE
        searchButton.visibility = View.VISIBLE
        addButton.visibility = View.VISIBLE
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        val actions = listOf(
            "Nhập tệp" to ::pickImport,
            "Xuất tệp" to ::pickExport,
            (if (descending) "Xếp A → Z" else "Xếp Z → A") to {
                descending = !descending
                reload()
                list.setSelection(0)
            },
            "Từ điển mặc định" to ::builtinDialog,
            "Định dạng tệp" to ::formatHelp,
            "Xoá hết" to ::clearAll,
        )
        actions.forEachIndexed { i, (label, _) -> menu.menu.add(0, i, i, label) }
        menu.setOnMenuItemClickListener { actions[it.itemId].second(); true }
        menu.show()
    }

    @Deprecated("Activity cũ, không có AndroidX")
    override fun onBackPressed() {
        when {
            search.visibility == View.VISIBLE -> closeSearch()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_READING)?.let { openEditor(null, it) }
        @Suppress("DEPRECATION")
        val shared: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (shared.isNotEmpty()) previewImport(shared)
    }

    private fun reload() {
        // Lõi đã xếp a → z theo âm đọc (bảng chữ cái tiếng Việt, rồi thanh), đúng như tệp lưu.
        val query = search.text.toString()
        val rows = engine.rows(query)
        shown = if (descending) rows.asReversed() else rows
        adapter.notifyDataSetChanged()
        val count = String.format(Locale("vi"), "%,d", shown.size)
        subtitle.text = "$count mục · " + if (descending) "Z → A" else "A → Z"
        if (query.isNotBlank()) subtitle.text = "$count kết quả"
    }

    private fun formatHelp() {
        AlertDialog.Builder(this)
            .setTitle("Định dạng tệp")
            .setMessage(
                "Tệp nhập / xuất là văn bản UTF-8, mỗi dòng: chữ;âm đọc;độ phổ biến\n\n" +
                    "固;cố;3\n越南;vn;0  (mã gõ tắt)\n\n" +
                    "Mỗi âm một dòng. Độ phổ biến tăng mỗi khi chọn chữ khi gõ."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Dòng kiểu Gboard: chữ to, âm đọc bên dưới; mã Unicode và độ phổ biến nhỏ bên phải. */
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: createRow()
            val holder = view.tag as RowHolder
            val row = shown[position]
            holder.text.text = hanNomText(row.text)
            holder.reading.text = row.reading
            holder.codes.text = row.codes
            holder.freq.text = "phổ biến ${row.freq}"
            return view
        }

        private fun createRow(): View {
            fun label(size: Float, color: Int, single: Boolean = true) = TextView(this@DictionaryActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(color)
                if (single) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            // Mã Unicode một dòng riêng dưới âm đọc, dài (từ nhiều chữ) thì xuống dòng chứ không cắt.
            val holder = RowHolder(
                label(24f, colors.text, single = false),
                label(16f, colors.text, single = false),
                label(13f, colors.summary, single = false),
                label(13f, colors.summary),
            )
            return LinearLayout(this@DictionaryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(76)
                // Chừa mép phải cho thanh cuộn nhanh.
                setPadding(dpi(24), dpi(10), dpi(32), dpi(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(holder.text)
                    addView(holder.reading)
                    addView(holder.codes)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(holder.freq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(16) })
                tag = holder
            }
        }
    }

    private class RowHolder(val text: TextView, val reading: TextView, val codes: TextView, val freq: TextView)

    /**
     * Hộp thoại thêm (`row == null`: chữ với một hoặc nhiều âm cách nhau bằng dấu phẩy) hoặc sửa một dòng
     * (chữ, âm đọc, độ phổ biến). Cặp đã có thì báo lỗi ngay trong hộp thoại. Khi sửa có thêm nút Xoá.
     */
    private fun openEditor(row: DictRow?, prefillReading: String?) {
        fun caption(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(colors.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpi(4), dpi(16), dpi(4), 0)
        }
        fun field(hint: String, size: Float) = EditText(this).apply {
            this.hint = hint
            setTextColor(colors.text)
            setHintTextColor(colors.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setSingleLine()
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(colors.accent, colors.summary),
            )
        }
        val textField = field(if (row == null) "Nhập chữ hoặc cụm từ vd. 喃" else "Chữ", 24f).apply {
            useHanNomFont()
            setText(row?.text.orEmpty())
            // Gợi ý dài hơn ô ở cỡ 24sp: thu nhỏ riêng chữ gợi ý cho vừa ô, không bị cắt đuôi.
            val full = hint.toString()
            hint = hanNomText(full)
            var applied = 1f
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val room = v.width - v.paddingLeft - v.paddingRight
                if (room <= 0) return@addOnLayoutChangeListener
                val scale = (room / paint.measureText(full) * 0.97f).coerceAtMost(1f)
                if (scale == applied) return@addOnLayoutChangeListener
                applied = scale
                v.post {
                    hint = hanNomText(SpannableString(full).apply {
                        if (scale < 1f) setSpan(RelativeSizeSpan(scale), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    })
                }
            }
        }
        val readingField = field(if (row == null) "vd. nôm, nam" else "Âm đọc", 18f).apply {
            setText(row?.reading ?: prefillReading.orEmpty())
        }
        val freqField = field("0", 18f).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row?.freq?.toString().orEmpty())
        }
        val error = TextView(this).apply {
            setTextColor(if (colors.light) Color.parseColor("#B3261E") else Color.parseColor("#F2B8B5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpi(4), dpi(8), dpi(4), 0)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
            addView(textField)
            addView(caption("Âm đọc:"))
            addView(readingField)
            addView(caption("Độ phổ biến (tăng mỗi khi chọn chữ khi gõ):"))
            addView(freqField)
            addView(error)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (row == null) "Thêm vào từ điển" else "Sửa mục")
            .setView(form)
            .setPositiveButton("Lưu", null)
            .setNegativeButton("Huỷ", null)
        if (row != null) {
            builder.setNeutralButton("Xoá") { _, _ ->
                engine.remove(row)
                reload()
                toast("Đã xoá ${row.text} ${row.reading}")
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val freq = freqField.text.toString().trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }
                if (freq == null || freq < 0) {
                    error.text = "Độ phổ biến phải là số nguyên không âm"
                    return@setOnClickListener
                }
                val message = engine.put(row, textField.text.toString(), readingField.text.toString(), freq)
                if (message == null) {
                    dialog.dismiss()
                    reload()
                } else {
                    error.text = message
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // Lớp phủ mặc định làm tối hẳn màn hình phía sau: chỉ phủ nhẹ.
        dialog.window?.setDimAmount(0.2f)
        dialog.show()
        val focus = if (row == null) textField else readingField
        focus.requestFocus()
        focus.setSelection(focus.text.length)
    }

    private fun showKeyboard(view: View) {
        view.requestFocus()
        view.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    @Suppress("DEPRECATION")
    private fun pickImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_IMPORT)
    }

    @Suppress("DEPRECATION")
    private fun pickExport() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "nomna-dict.txt")
        }, REQUEST_EXPORT)
    }

    @Deprecated("Activity result API cũ, đủ dùng khi không có AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_IMPORT -> {
                // Chọn nhiều tệp thì nằm trong clipData, một tệp thì ở data.
                val clip = data.clipData
                val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(data.data)
                if (uris.isNotEmpty()) previewImport(uris)
            }
            REQUEST_EXPORT -> data.data?.let { uri ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(engine.exportText().toByteArray()) }
                }.onSuccess { toast("Đã xuất từ điển") }.onFailure { toast("Không ghi được tệp") }
            }
        }
    }

    /**
     * Đọc các tệp, hiện bản xem trước từng tệp (số mục mới / trùng / lỗi), rồi chọn gộp vào từ điển đang
     * dùng hoặc thay toàn bộ bằng các tệp này.
     */
    private fun previewImport(uris: List<Uri>) {
        val files = uris.mapNotNull { uri ->
            runCatching {
                displayName(uri) to contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        if (files.size < uris.size) toast("Không đọc được ${uris.size - files.size} tệp")
        if (files.isEmpty()) return
        val contents = files.map { it.second }
        val reports = engine.previewImport(contents)
        val message = buildString {
            files.zip(reports).forEachIndexed { i, (file, report) ->
                if (i > 0) append("\n\n")
                if (files.size > 1) append(file.first).append(":\n")
                append("Sẽ thêm ${report.added} mục, bỏ qua ${report.duplicates} mục trùng.")
                if (report.errors.isNotEmpty()) {
                    append("\n${report.errors.size} dòng lỗi (sẽ bỏ qua):\n")
                    report.errors.take(50).forEach { append("• ").append(it).append('\n') }
                    if (report.errors.size > 50) append("…")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (files.size > 1) "Nhập ${files.size} tệp" else "Nhập từ điển")
            .setMessage(
                "$message\n\nGộp: thêm các mục mới vào từ điển đang dùng, mục đã có giữ nguyên độ phổ biến.\n" +
                    "Thay toàn bộ: từ điển chỉ còn các mục của tệp nhập. Nên xuất tệp để lưu trước."
            )
            .setPositiveButton("Gộp") { _, _ ->
                val done = engine.import(contents)
                toast("Đã thêm ${done.sumOf { it.added }} mục")
                reload()
            }
            .setNeutralButton("Thay toàn bộ") { _, _ ->
                val done = engine.replaceWith(contents)
                toast("Từ điển giờ có ${done.sumOf { it.added }} mục")
                reload()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Nhập lại từ điển mặc định: gộp vào (giữ chỗ đã sửa) hoặc thay toàn bộ. */
    private fun builtinDialog() {
        AlertDialog.Builder(this)
            .setTitle("Nhập lại từ điển mặc định")
            .setMessage(
                "Gộp: thêm lại các mục mặc định còn thiếu, giữ nguyên chỗ đã sửa và thêm.\n\n" +
                    "Thay toàn bộ: từ điển trở về như mặc định, mất mọi chỗ đã sửa và thêm. Nên xuất tệp để lưu trước."
            )
            .setPositiveButton("Gộp") { _, _ ->
                toast("Đã thêm ${engine.mergeBuiltin().added} mục")
                reload()
            }
            .setNeutralButton("Thay toàn bộ") { _, _ ->
                engine.resetToBuiltin()
                toast("Đã khôi phục từ điển mặc định")
                reload()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setMessage("Xoá toàn bộ từ điển? Bàn phím sẽ không còn gợi ý chữ Hán Nôm cho tới khi nhập tệp " +
                "hoặc nhập lại từ điển mặc định. Nên xuất tệp để lưu trước.")
            .setPositiveButton("Xoá hết") { _, _ ->
                engine.clear()
                reload()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_READING = "reading"
        private const val REQUEST_IMPORT = 1
        private const val REQUEST_EXPORT = 2
    }
}
