package com.hannom.ime

import android.annotation.SuppressLint
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
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Khung chung của các trang từ điển (kiểu Gboard): thanh tiêu đề ← tiêu đề (dòng phụ) · tìm · thêm · ⋮, danh sách
 * mỗi mục một dòng (chữ to; dòng phụ; dòng nhỏ; bên phải là độ phổ biến hay công tắc). Giữ một dòng để chọn, chạm
 * thêm để chọn tiếp; mục Xoá trong ⋮ đổi theo lúc đó: có mục đã chọn thì xoá các mục đó, không thì xoá tất cả (như
 * mục Xếp A → Z / Z → A).
 */
abstract class DictPage<T> : Activity() {
    /** [AppText.version] lúc dựng trang. */
    private val textVersion = AppText.version
    protected lateinit var colors: SettingsColors
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var titleBlock: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    protected lateinit var search: EditText
    private lateinit var searchButton: View
    private var addButton: View? = null
    private lateinit var clearButton: View
    /** Nút đầu thanh: ← quay lại, hay ✕ bỏ chọn khi đang có mục được chọn. */
    private lateinit var backButton: ImageView
    protected lateinit var list: ListView
    private val adapter = RowAdapter()
    protected var shown: List<T> = emptyList()
    protected var descending = false
    /** Khoá ([keyOf]) các mục đang chọn (giữ để chọn). */
    protected val selected = LinkedHashSet<String>()
    /** Phần bị che ở bốn cạnh (trái, trên, phải, dưới; px): thanh hệ thống, bàn phím, tai thỏ. */
    private var insetSides = IntArray(4)
    private lateinit var listFrame: FrameLayout

    protected abstract fun pageTitle(): CharSequence
    protected abstract val searchHint: String
    /** Mọi mục khớp [query], xếp A → Z. */
    protected abstract fun load(query: String): List<T>
    protected abstract fun keyOf(item: T): String
    protected abstract fun bind(holder: RowHolder, item: T)
    protected abstract fun open(item: T)
    /** Dòng phụ dưới tiêu đề khi không tìm, vd. "12 mục · A → Z". */
    protected abstract fun summary(count: Int): String
    /** Mục riêng của trang trong ⋮ (trước Xếp và Xoá). */
    protected open fun extraActions(): List<Pair<String, () -> Unit>> = emptyList()
    /** Nhãn của mục Xoá: [count] mục đã chọn, hay 0 là xoá tất cả. */
    protected abstract fun deleteLabel(count: Int): String
    /** Xoá [items] ([all]: không chọn gì, là xoá tất cả); hỏi lại trước khi xoá. */
    protected abstract fun delete(items: List<T>, all: Boolean)
    /** Nút + (thêm); `null` thì không có. */
    protected open val onAdd: (() -> Unit)? = null
    /** Tính mỗi lần đọc: lúc dựng activity chưa có Context để đọc ngôn ngữ ứng dụng. */
    protected open val addDescription get() = tr("Thêm", "Add")
    protected open fun selectable(item: T) = true
    /** ⋮ có mục Xoá không. */
    protected open val deletable = true
    /** Mục luôn nằm trên đầu danh sách (từ điển mặc định). */
    protected open fun pinned(item: T) = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cửa sổ vẽ cả vào vùng tai thỏ (nền thanh tiêu đề tràn qua); chữ, nút lùi vào theo insets bên dưới.
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        colors = SettingsColors.of(this)

        titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            titleView = TextView(context).apply {
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleView)
            subtitle = TextView(context).apply {
                setTextColor(colors.summary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                // Không đủ chỗ (chữ hệ thống to, màn hẹp) thì xuống dòng, không cắt cụt: một dòng thì "A → Z" bị cắt ở
                // mép các nút tìm / thêm (người dùng báo 2026-11-18).
                maxLines = 2
            }
            addView(subtitle)
        }
        search = EditText(this).apply {
            hint = searchHint
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
        clearButton = barIcon(R.drawable.ic_close, tr("Xoá ô tìm", "Clear search")) { search.setText("") }.apply { visibility = View.GONE }
        searchButton = barIcon(R.drawable.ic_search, tr("Tìm", "Search")) { openSearch() }
        addButton = onAdd?.let { add -> barIcon(R.drawable.ic_add, addDescription) { add() } }
        lateinit var more: View
        more = barIcon(R.drawable.ic_more_vert, tr("Tuỳ chọn khác", "More options")) { showMenu(more) }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.bar)
            minimumHeight = dpi(64)
            backButton = barIcon(R.drawable.ic_arrow_back, tr("Quay lại", "Back")) {
                if (selected.isNotEmpty() && search.visibility != View.VISIBLE) clearSelection() else goBack()
            }
            addView(backButton, LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { marginStart = dpi(4) })
            addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(12) })
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(4) })
            listOfNotNull(clearButton, searchButton, addButton, more).forEach { addView(it, LinearLayout.LayoutParams(dpi(48), dpi(48))) }
            setPadding(0, 0, dpi(4), 0)
        }

        list = ListView(this).apply {
            this.adapter = this@DictPage.adapter
            divider = ColorDrawable(if (colors.light) Color.parseColor("#C6C6D0") else Color.parseColor("#3B3E4A"))
            dividerHeight = maxOf(1, dpi(1) / 2)
            selector = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, ColorDrawable(Color.WHITE))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ ->
                val item = shown.getOrNull(pos) ?: return@setOnItemClickListener
                if (selected.isNotEmpty()) toggle(item) else open(item)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { toggle(it) }
                true
            }
        }
        val empty = TextView(this).apply {
            // 𣎏 nằm ngoài BMP: cần phông Hán Nôm đi kèm.
            text = hanNomText(tr("Không có mục nào", "No entries", "空𣎏目芾"))
            setTextColor(colors.summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        list.emptyView = empty
        listFrame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            scrollBubble()?.let { bubble ->
                addView(FastScroller(this@DictPage, list, colors) { pos -> shown.getOrNull(pos)?.let(bubble) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
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
        // Vẽ tràn viền: thanh tiêu đề chừa thanh trạng thái, danh sách chừa thanh điều hướng và bàn phím. Màn hình ngang:
        // chừa cả tai thỏ và thanh điều hướng ở hai bên (nền thanh tiêu đề vẫn tràn qua, chữ / nút lùi vào); trước đây
        // nút ⋮ nằm dưới thanh điều hướng, dải tai thỏ lệch màu thanh tiêu đề (người dùng báo 2026-11-18).
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sides = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                val cut = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                @Suppress("DEPRECATION")
                intArrayOf(
                    maxOf(insets.systemWindowInsetLeft, cut?.safeInsetLeft ?: 0), insets.systemWindowInsetTop,
                    maxOf(insets.systemWindowInsetRight, cut?.safeInsetRight ?: 0), insets.systemWindowInsetBottom,
                )
            }
            if (!sides.contentEquals(insetSides)) {
                insetSides = sides
                val (left, top, right, bottom) = sides
                bar.setPadding(left, top, right + dpi(4), 0)
                listFrame.setPadding(left, 0, right, 0)
                list.setPadding(0, 0, 0, bottom)
            }
            insets
        }
        setContentView(root)
        styleSystemBars(colors)
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.background
    }

    override fun onResume() {
        super.onResume()
        // Đã đổi ngôn ngữ ứng dụng: dựng lại trang bằng ngôn ngữ mới.
        if (textVersion != AppText.version) {
            recreate()
            return
        }
        reload()
    }

    /** Bong bóng khi kéo thanh cuộn nhanh; `null` thì không có thanh cuộn nhanh (danh sách ngắn). */
    protected open fun scrollBubble(): ((T) -> String?)? = null

    private fun barIcon(icon: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(colors.text)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = iconRipple(colors)
        setOnClickListener { onClick() }
    }

    private fun toggle(item: T) {
        if (!selectable(item)) {
            onUnselectable(item)
            return
        }
        val key = keyOf(item)
        if (!selected.remove(key)) selected.add(key)
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    protected open fun onUnselectable(item: T) {}

    protected fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun goBack() {
        when {
            // Đóng ô tìm vẫn giữ các mục đã chọn; lùi thêm lần nữa mới bỏ chọn.
            search.visibility == View.VISIBLE -> closeSearch()
            selected.isNotEmpty() -> clearSelection()
            else -> finish()
        }
    }

    @Deprecated("Activity cũ, không có AndroidX")
    override fun onBackPressed() {
        if (selected.isNotEmpty() || search.visibility == View.VISIBLE) goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun openSearch() {
        titleBlock.visibility = View.GONE
        searchButton.visibility = View.GONE
        addButton?.visibility = View.GONE
        search.visibility = View.VISIBLE
        updateTitle()
        showKeyboard(search)
    }

    private fun closeSearch() {
        hideKeyboard(search)
        search.setText("")
        search.visibility = View.GONE
        titleBlock.visibility = View.VISIBLE
        searchButton.visibility = View.VISIBLE
        addButton?.visibility = View.VISIBLE
        updateTitle()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        // Mục đã chọn có thể không nằm trong kết quả tìm đang hiện: lấy từ cả danh sách.
        val chosen = if (selected.isEmpty()) emptyList() else load("").filter { keyOf(it) in selected }
        val actions = extraActions() + listOf(
            (if (descending) tr("Xếp A → Z", "Sort A → Z", "疊A → Z") else tr("Xếp Z → A", "Sort Z → A", "疊Z → A")) to {
                descending = !descending
                reload()
                list.setSelection(0)
            },
        ) + listOfNotNull(
            if (deletable) deleteLabel(chosen.size) to { delete(chosen, chosen.isEmpty()) } else null,
            if (selected.isNotEmpty()) tr("Bỏ chọn tất cả", "Deselect all", "𠬃𫾗畢𪥘󠄁") to { clearSelection() } else null,
        )
        actions.forEachIndexed { i, (label, _) -> menu.menu.add(0, i, i, hanNomText(label)) }
        menu.setOnMenuItemClickListener { actions[it.itemId].second(); true }
        menu.show()
    }

    protected fun reload() {
        val query = if (::search.isInitialized) search.text.toString() else ""
        val rows = load(query)
        // Mục ghim ([pinned]) luôn đứng đầu, xếp A → Z hay Z → A cũng vậy.
        val (top, rest) = rows.partition(::pinned)
        shown = top + if (descending) rest.asReversed() else rest
        // Lựa chọn giữ qua các lần tìm (chọn ở kết quả này, tìm tiếp chọn thêm); chỉ bỏ mục không còn trong từ điển.
        if (query.isBlank()) selected.retainAll(shown.mapTo(HashSet(), ::keyOf))
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun updateTitle() {
        titleView.text = if (selected.isNotEmpty()) tr("Đã chọn ${selected.size}", "${selected.size} selected", "㐌𫾗${selected.size}") else hanNomText(pageTitle())
        // Đang tìm thì tiêu đề bị ô tìm che: số mục đã chọn hiện ở chữ gợi ý của ô tìm.
        // Có mục đã chọn (không đang tìm): nút đầu thanh thành ✕ bỏ chọn.
        val cancel = selected.isNotEmpty() && search.visibility != View.VISIBLE
        backButton.setImageResource(if (cancel) R.drawable.ic_close else R.drawable.ic_arrow_back)
        backButton.contentDescription = if (cancel) tr("Bỏ chọn", "Deselect") else tr("Quay lại", "Back")
        search.hint = hanNomText(if (selected.isNotEmpty()) tr("Đã chọn ${selected.size} · $searchHint", "${selected.size} selected · $searchHint", "㐌𫾗${selected.size}·${searchHint}") else searchHint)
        val query = search.text.toString()
        subtitle.text = when {
            query.isNotBlank() -> tr("${number(shown.size)} kết quả", "${number(shown.size)} results", "${number(shown.size)}結果")
            // Dấu cách không ngắt dòng: "A → Z" không bị tách sang hai dòng khi dòng phụ dài (người dùng báo 2026-11-19).
            else -> summary(shown.size) + " · " + if (descending) "Z → A" else "A → Z"
        }
    }

    protected fun number(n: Int): String = String.format(Locale("vi"), "%,d", n)

    /** Hỏi lại rồi làm [action]. */
    protected fun confirm(message: String, button: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(hanNomText(message))
            .setPositiveButton(button) { _, _ -> action() }
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .show()
    }

    protected fun toast(text: String) = Toast.makeText(this, hanNomText(text), Toast.LENGTH_SHORT).show()

    protected fun showKeyboard(view: View) {
        view.requestFocus()
        view.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    /**
     * Hiện hộp thoại có ô nhập: giữa phần màn hình phía trên bàn phím, bàn phím hiện thì tự đẩy lên cho khỏi bị đè (phần
     * ô nhập trong khung cuộn [scrollable] nên co được khi chật). Bàn phím đang mở sẵn (ô tìm) thì hộp thoại vẽ một khung
     * hình ở chỗ chưa tính bàn phím rồi mới nhảy lên: bảng sửa nháy (người dùng báo 2026-11-18). Khi đó hộp thoại trong
     * suốt tới lúc đã đặt đúng chỗ phía trên bàn phím mới hiện ra.
     */
    protected fun showAboveKeyboard(dialog: AlertDialog) {
        val w = dialog.window ?: return dialog.show()
        w.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        val imeOpen = Build.VERSION.SDK_INT >= 30 &&
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (!imeOpen) return dialog.show()
        val decor = w.decorView
        decor.alpha = 0f
        var shown = false
        val reveal = Runnable {
            if (!shown) {
                shown = true
                decor.alpha = 1f
            }
        }
        decor.setOnApplyWindowInsetsListener { v, insets ->
            // Đã nhận chỗ của bàn phím: hiện ở khung hình sau, khi đã dàn lại theo chỗ đó.
            if (Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())) v.postOnAnimation { v.postOnAnimation(reveal) }
            v.onApplyWindowInsets(insets)
        }
        // Phòng khi không nhận được chỗ bàn phím (bàn phím tắt giữa chừng): vẫn hiện.
        decor.postDelayed(reveal, 400)
        dialog.show()
    }

    /** [content] trong khung cuộn, để hộp thoại co lại vừa phần trên bàn phím ([pinAboveKeyboard]). */
    protected fun scrollable(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    /**
     * Hộp thoại nhập tên (từ điển mới, đổi tên). Tên trùng một từ điển khác ([taken], không phân biệt hoa thường) thì
     * báo ngay dưới ô nhập và giữ hộp thoại mở (người dùng muốn 2026-11-18).
     */
    protected fun askName(title: String, initial: String, taken: (String) -> Boolean, onDone: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setSingleLine()
            useHanNomFont()
            setSelection(text.length)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(colors.accent, colors.summary),
            )
        }
        val error = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#D93025"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
            addView(field)
            addView(error)
        }
        fun check(name: String): Boolean {
            val dup = name.isNotEmpty() && taken(name)
            error.text = hanNomText(tr("Đã có từ điển tên $name", "A dictionary named $name already exists", "㐌𣎏辭典𠸜${name}"))
            error.visibility = if (dup) View.VISIBLE else View.GONE
            return !dup
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable) {
                check(s.toString().trim())
            }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(tr("Lưu", "Save", "留"), null)
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .create()
        // Bấm Lưu khi tên trùng: không đóng hộp thoại.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isEmpty() || !check(name)) return@setOnClickListener
                dialog.dismiss()
                onDone(name)
            }
        }
        dialog.window?.setDimAmount(0.2f)
        showAboveKeyboard(dialog)
        field.requestFocus()
    }

    /** Dòng kiểu Gboard: chữ to, dòng phụ, dòng nhỏ; bên phải là chữ nhỏ (độ phổ biến) hoặc công tắc. */
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: createRow()
            val holder = view.tag as RowHolder
            val item = shown[position]
            holder.toggle.setOnCheckedChangeListener(null)
            bind(holder, item)
            listOf(holder.reading, holder.codes, holder.side).forEach { it.visibility = if (it.text.isNullOrEmpty()) View.GONE else View.VISIBLE }
            val chosen = keyOf(item) in selected
            holder.check.visibility = if (chosen) View.VISIBLE else View.GONE
            view.setBackgroundColor(if (chosen) colors.ripple else Color.TRANSPARENT)
            return view
        }

        @SuppressLint("UseSwitchCompatOrMaterialCode")
        private fun createRow(): View {
            fun label(size: Float, color: Int, single: Boolean = true) = TextView(this@DictPage).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setTextColor(color)
                if (single) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            val toggle = Switch(this@DictPage).apply {
                thumbDrawable = switchThumb(colors)
                trackDrawable = switchTrack(colors)
                showText = false
                // Không lấy focus, để chạm cả dòng vẫn mở được mục.
                isFocusable = false
                visibility = View.GONE
            }
            val check = ImageView(this@DictPage).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(colors.accent)
                visibility = View.GONE
            }
            // Mã Unicode một dòng riêng dưới âm đọc, dài (từ nhiều chữ) thì xuống dòng chứ không cắt.
            val holder = RowHolder(
                label(24f, colors.text, single = false),
                label(16f, colors.text, single = false),
                label(13f, colors.summary, single = false),
                label(13f, colors.summary),
                toggle,
                check,
            )
            return LinearLayout(this@DictPage).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(76)
                // Chừa mép phải cho thanh cuộn nhanh.
                setPadding(dpi(24), dpi(10), dpi(32), dpi(10))
                addView(check, LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { marginEnd = dpi(16) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(holder.text)
                    addView(holder.reading)
                    addView(holder.codes)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(holder.side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(16) })
                addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(16) })
                tag = holder
            }
        }
    }

    class RowHolder(
        val text: TextView,
        val reading: TextView,
        val codes: TextView,
        /** Chữ nhỏ bên phải (độ phổ biến). */
        val side: TextView,
        /** Công tắc bên phải (bật / tắt từ điển), thay chỗ độ phổ biến. */
        val toggle: Switch,
        val check: ImageView,
    )

    companion object {
        const val EXTRA_LANG = "lang"
        const val EXTRA_DICT = "dict"
    }
}

/**
 * Trang đầu của Từ điển: các ngôn ngữ (Tiếng Việt, Hán Nôm, English), chạm để xem các từ điển của ngôn ngữ đó.
 * Không có nút thêm hay nhập / xuất tệp (các việc đó ở từng từ điển). Cũng nhận tệp .txt được chia sẻ hoặc mở từ
 * ứng dụng khác: hỏi nhập vào từ điển Hán Nôm nào.
 */
class DictionaryActivity : DictPage<Lang>() {
    override fun pageTitle() = tr("Từ điển", "Dictionary", "辭典")
    override val searchHint get() = tr("Tìm ngôn ngữ", "Search languages", "尋言語")

    override fun finish() {
        if (!finishKeyboardTask()) super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HanNomEngine.get(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun load(query: String): List<Lang> {
        val q = WordDict.fold(query.trim())
        return Lang.entries.filter { q.isEmpty() || WordDict.fold(it.titleIn(this)).contains(q) }.sortedBy { WordDict.fold(it.titleIn(this)) }
    }

    override fun keyOf(item: Lang) = item.code

    override fun bind(holder: RowHolder, item: Lang) {
        val dicts = Dictionaries.list(this, item)
        holder.text.text = item.titleIn(this)
        holder.reading.text = ""
        holder.codes.text = tr("${dicts.size} từ điển · ${dicts.count { it.enabled }} đang bật", "${dicts.size} dictionaries · ${dicts.count { it.enabled }} on", "${dicts.size}辭典·${dicts.count { it.enabled }}當弼")
        holder.side.text = ""
        holder.toggle.visibility = View.GONE
    }

    override fun open(item: Lang) {
        startActivity(Intent(this, DictListActivity::class.java).putExtra(EXTRA_LANG, item.code))
    }

    override fun summary(count: Int) = tr("$count ngôn ngữ", "$count languages", "${count}言語")

    /**
     * Trang danh sách ngôn ngữ không có giữ để chọn, ⋮ cũng không có mục xoá từ điển cá nhân (người dùng muốn
     * 2026-11-18); xoá ở trang từ điển của từng ngôn ngữ.
     */
    override fun selectable(item: Lang) = false
    override val deletable = false

    override fun deleteLabel(count: Int) = tr("Xoá tất cả từ điển cá nhân", "Delete all personal dictionaries", "𫨮畢𪥘󠄁辭典個人")

    override fun delete(items: List<Lang>, all: Boolean) {
        val langs = if (all) Lang.entries else items
        val personal = langs.sumOf { l -> Dictionaries.list(this, l).count { !it.isDefault } }
        if (personal == 0) return toast(tr("Không có từ điển cá nhân nào", "No personal dictionaries", "空𣎏辭典個人芾"))
        confirm(
            tr(
                "Xoá $personal từ điển cá nhân của ${langs.joinToString(", ") { it.titleIn(this) }}? Từ điển mặc định vẫn " +
                    "giữ. Nên xuất tệp để lưu trước.",
                "Delete $personal personal dictionaries of ${langs.joinToString(", ") { it.titleIn(this) }}? The default " +
                    "dictionaries are kept. Export first to keep a copy.",
                "𫨮${personal}辭典個人𧵑${langs.joinToString(", ") { it.titleIn(this) }}？辭典默定吻𡨺。𢧚出集抵留𠓀。",
            ),
            tr("Xoá", "Delete", "𫨮"),
        ) {
            langs.forEach { l -> Dictionaries.delete(this, l, Dictionaries.list(this, l).filter { !it.isDefault }.map { it.id }) }
            clearSelection()
            reload()
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        @Suppress("DEPRECATION")
        val shared: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (shared.isEmpty()) return
        val files = readFiles(shared)
        if (files.isEmpty()) return
        // Tệp chia sẻ là từ điển Hán Nôm (`chữ;âm;số`): chọn từ điển nhận, hay tạo từ điển cá nhân mới.
        val dicts = Dictionaries.list(this, Lang.NOM)
        val labels = dicts.map { hanNomText(it.name) } + tr("Từ điển cá nhân mới", "New personal dictionary", "辭典個人𬔫")
        AlertDialog.Builder(this)
            .setTitle(tr("Nhập vào từ điển Hán Nôm nào?", "Import into which Han Nom dictionary?"))
            .setItems(labels.toTypedArray()) { _, which ->
                val target = dicts.getOrNull(which) ?: Dictionaries.add(this, Lang.NOM, Dictionaries.nextPersonalName(this, Lang.NOM))
                previewImport(Dictionaries.store(this, target), files) { reload() }
            }
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .show()
    }
}

/** Các từ điển của một ngôn ngữ: công tắc bật / tắt (bật nhiều thì bàn phím tự gộp), + thêm từ điển cá nhân. */
class DictListActivity : DictPage<DictInfo>() {
    private val lang by lazy { Lang.of(intent.getStringExtra(EXTRA_LANG)) ?: Lang.NOM }
    /**
     * Số mục của từ điển [item]: nhớ theo tệp (kích thước, lần sửa), tệp không đổi thì không đếm lại. Trước đây mỗi lần
     * bật / tắt đều đếm lại mọi từ điển, đọc lại cả tệp tiếng Anh hơn trăm nghìn từ trên luồng giao diện: gạt công tắc
     * "siêu lag" (người dùng báo 2026-11-18).
     */
    private fun countOf(item: DictInfo): Int? {
        val f = Dictionaries.file(this, lang, item.id)
        val key = "${lang.code}/${item.id}/" + if (f.exists()) "${f.length()}:${f.lastModified()}" else "builtin"
        synchronized(COUNTS) {
            COUNTS[key]?.let { return it }
            if (!counting.add(key)) return null
        }
        // Chưa đếm: đếm ở luồng nền (tiếng Anh ~1,4 giây trên máy ảo, đo 2026-11-18), đếm xong thì vẽ lại trang.
        val app = applicationContext
        Thread {
            val n = runCatching { Dictionaries.store(app, item).count() }.getOrNull()
            synchronized(COUNTS) {
                counting.remove(key)
                if (n != null) COUNTS[key] = n
            }
            if (n != null) runOnUiThread { if (!isDestroyed) reload() }
        }.start()
        return null
    }

    private companion object {
        /** Số mục đã đếm ([countOf]), giữ qua các lần mở trang. */
        val COUNTS = HashMap<String, Int>()
        /** Các tệp đang đếm ở luồng nền. */
        val counting = HashSet<String>()
    }

    override fun pageTitle() = lang.titleIn(this)
    override val searchHint get() = tr("Tìm từ điển", "Search dictionaries", "尋辭典")
    override val addDescription get() = tr("Thêm từ điển", "Add dictionary")

    override val onAdd: () -> Unit = {
        askName(
            tr("Từ điển mới", "New dictionary", "辭典𬔫"),
            Dictionaries.nextPersonalName(this, lang),
            { name -> Dictionaries.list(this, lang).any { it.name.equals(name, ignoreCase = true) } },
        ) { name ->
            val info = Dictionaries.add(this, lang, name)
            reload()
            openDict(info)
        }
    }

    override fun load(query: String): List<DictInfo> {
        val q = WordDict.fold(query.trim())
        return Dictionaries.list(this, lang)
            .filter { q.isEmpty() || WordDict.fold(it.name).contains(q) }
            .sortedWith(compareBy<DictInfo> { WordDict.fold(it.name) }.thenBy { it.name })
    }

    override fun keyOf(item: DictInfo) = item.id

    override fun bind(holder: RowHolder, item: DictInfo) {
        holder.text.text = hanNomText(item.name)
        holder.reading.text = ""
        holder.codes.text = countOf(item)?.let { tr("${number(it)} mục", "${number(it)} entries", "${number(it)}目") } ?: tr("Đang đếm số mục…", "Counting entries…", "當點數目")
        holder.side.text = ""
        holder.toggle.visibility = View.VISIBLE
        holder.toggle.isChecked = item.enabled
        holder.toggle.setOnCheckedChangeListener { _, on ->
            Dictionaries.update(this, item.copy(enabled = on))
            reload()
        }
    }

    override fun open(item: DictInfo) = openDict(item)

    private fun openDict(item: DictInfo) {
        startActivity(Intent(this, DictWordsActivity::class.java).putExtra(EXTRA_LANG, lang.code).putExtra(EXTRA_DICT, item.id))
    }

    override fun summary(count: Int): String {
        val on = shown.count { it.enabled }
        return tr("$count từ điển · $on đang bật", "$count dictionaries · $on on", "${count}辭典·${on}當弼")
    }

    override fun selectable(item: DictInfo) = !item.isDefault

    override fun pinned(item: DictInfo) = item.isDefault

    override fun onUnselectable(item: DictInfo) = toast(tr("Không xoá được ${item.name}", "Can't delete ${item.name}", "空𫨮得${item.name}"))

    override fun deleteLabel(count: Int) = if (count > 0) tr("Xoá $count từ điển đã chọn", "Delete $count selected dictionaries", "𫨮${count}辭典㐌𫾗") else tr("Xoá tất cả từ điển cá nhân", "Delete all personal dictionaries", "𫨮畢𪥘󠄁辭典個人")

    override fun delete(items: List<DictInfo>, all: Boolean) {
        val gone = (if (all) Dictionaries.list(this, lang) else items).filter { !it.isDefault }
        if (gone.isEmpty()) return toast(tr("Không có từ điển cá nhân nào", "No personal dictionaries", "空𣎏辭典個人芾"))
        confirm(
            tr(
                "Xoá ${gone.joinToString(", ") { it.name }}? ${lang.defaultName} vẫn giữ. Nên xuất tệp để lưu trước.",
                "Delete ${gone.joinToString(", ") { it.name }}? ${lang.defaultName} is kept. Export first to keep a copy.",
                "𫨮${gone.joinToString(", ") { it.name }}？${lang.defaultName}吻𡨺。𢧚出集抵留𠓀。",
            ),
            tr("Xoá", "Delete", "𫨮"),
        ) {
            Dictionaries.delete(this, lang, gone.map { it.id })
            clearSelection()
            reload()
        }
    }
}

/**
 * Các mục của một từ điển. Hán Nôm: mỗi cặp chữ – âm một dòng (chữ to, âm đọc bên dưới, mã Unicode, bên phải là độ
 * phổ biến), xếp a → z như tệp lưu, thanh cuộn nhanh ở mép phải. Tiếng Việt / English: từ và độ phổ biến. Chạm một
 * dòng để sửa. ⋮: nhập tệp (gộp hay thay toàn bộ), xuất tệp, nhập lại bản mặc định (chỉ từ điển mặc định), đổi tên
 * (từ điển cá nhân), xếp, xoá.
 */
class DictWordsActivity : DictPage<DictRow>() {
    private val lang by lazy { Lang.of(intent.getStringExtra(EXTRA_LANG)) ?: Lang.NOM }
    private val dictId by lazy { intent.getStringExtra(EXTRA_DICT) ?: DictInfo.DEFAULT_ID }
    private val info get() = Dictionaries.list(this, lang).firstOrNull { it.id == dictId } ?: Dictionaries.list(this, lang)[0]
    private val store by lazy { Dictionaries.store(this, info) }

    override fun pageTitle(): CharSequence = info.name
    override val searchHint get() = if (lang == Lang.NOM) tr("Tìm chữ, âm đọc hoặc mã U", "Search character, reading or Unicode", "尋𡨸，音讀或碼U") else tr("Tìm từ", "Search words", "尋詞")
    override val onAdd: () -> Unit = { openEditor(null) }

    override fun load(query: String) = store.rows(query)

    override fun keyOf(item: DictRow) = "${item.text};${item.reading}"

    override fun scrollBubble(): (DictRow) -> String? =
        if (lang == Lang.NOM) { row -> row.reading.substringBefore(' ') } else { row -> row.text.take(2) }

    override fun bind(holder: RowHolder, item: DictRow) {
        holder.text.text = hanNomText(item.text)
        holder.reading.text = item.reading
        holder.codes.text = if (store.hasReading) item.codes else ""
        holder.side.text = tr("phổ biến ${item.freq}", "frequency ${item.freq}", "譜遍${item.freq}")
        holder.toggle.visibility = View.GONE
    }

    override fun open(item: DictRow) = openEditor(item)

    override fun summary(count: Int) = tr("${number(count)} mục", "${number(count)} entries", "${number(count)}目")

    override fun extraActions(): List<Pair<String, () -> Unit>> = listOfNotNull(
        tr("Nhập tệp", "Import file", "入集") to ::pickImport,
        tr("Xuất tệp", "Export file", "出集") to ::pickExport,
        if (info.isDefault) tr("Nhập lại bản mặc định", "Restore default", "入徠版默定") to ::builtinDialog else null,
        if (!info.isDefault) tr("Đổi tên", "Rename", "𢷮𠸜") to {
            val current = info
            askName(
                tr("Đổi tên", "Rename", "𢷮𠸜"),
                current.name,
                { name -> Dictionaries.list(this, lang).any { it.id != current.id && it.name.equals(name, ignoreCase = true) } },
            ) { name ->
                Dictionaries.update(this, info.copy(name = name))
                reload()
            }
        } else null,
        tr("Định dạng tệp", "File format", "定樣集") to ::formatHelp,
    )

    override fun deleteLabel(count: Int) = if (count > 0) tr("Xoá $count mục đã chọn", "Delete $count selected entries", "𫨮${count}目㐌𫾗") else tr("Xoá hết", "Delete all", "𫨮歇")

    override fun delete(items: List<DictRow>, all: Boolean) {
        if (all) {
            confirm(tr("Xoá toàn bộ ${info.name}? Nên xuất tệp để lưu trước.", "Delete everything in ${info.name}? Export first to keep a copy.", "𫨮全部${info.name}？𢧚出集抵留𠓀"), tr("Xoá hết", "Delete all", "𫨮歇")) {
                store.clear()
                reload()
            }
        } else {
            confirm(tr("Xoá ${items.size} mục đã chọn?", "Delete ${items.size} selected entries?", "𫨮${items.size}目㐌𫾗"), tr("Xoá", "Delete", "𫨮")) {
                store.remove(items)
                clearSelection()
                reload()
            }
        }
    }

    private fun formatHelp() {
        val message = if (store.hasReading) {
            tr(
                "Tệp nhập / xuất là văn bản UTF-8, mỗi dòng: chữ;âm đọc;độ phổ biến\n\n" +
                    "固;cố;3\n\nMỗi âm một dòng. Độ phổ biến tăng mỗi khi chọn chữ khi gõ.\n\n" +
                    "Từ ngoại lai (không có chữ Hán Nôm) được bỏ trống âm đọc, gõ chính từ đó là ra:\n\nalo;;0",
                "Import / export files are UTF-8 text, one line each: character;reading;frequency\n\n" +
                    "固;cố;3\n\nOne reading per line. Frequency goes up each time you pick the character while typing.\n\n" +
                    "Loanwords (no Hán Nôm characters) may leave the reading empty; type the word itself to get it:\n\nalo;;0",
                "集 入／出 𪜀文本UTF-8，每𣳔：𡨸；音讀；度譜遍\n\n固;cố;3\n\n每音𠬠𣳔。度譜遍增每欺𫾗𡨸欺𢱗。",
            )
        } else {
            tr(
                "Tệp nhập / xuất là văn bản UTF-8, mỗi dòng: từ;độ phổ biến\n\nchữ nôm;120\nhello;35",
                "Import / export files are UTF-8 text, one line each: word;frequency\n\nchữ nôm;120\nhello;35",
                "集 入／出 𪜀文本UTF-8，每𣳔：詞；度譜遍\n\nchữ nôm;120\nhello;35",
            )
        }
        AlertDialog.Builder(this).setTitle(tr("Định dạng tệp", "File format", "定樣集")).setMessage(hanNomText(message)).setPositiveButton(tr("OK", "OK", "同意"), null).show()
    }

    /**
     * Hộp thoại thêm (`row == null`; Hán Nôm: chữ với một hoặc nhiều âm cách nhau bằng dấu phẩy) hoặc sửa một dòng.
     * Mục đã có thì báo lỗi ngay trong hộp thoại. Khi sửa có thêm nút Xoá.
     */
    private fun openEditor(row: DictRow?) {
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
        val nom = store.hasReading
        val textHint = when {
            !nom -> if (row == null) tr("Nhập từ", "Enter word", "入詞") else tr("Từ", "Word", "詞")
            row == null -> tr("Nhập chữ hoặc cụm từ vd. 喃", "Enter a character or phrase, e.g. 喃", "入𡨸或椹詞，譬喻：喃")
            else -> tr("Chữ", "Character", "𡨸")
        }
        val textField = field(textHint, 24f).apply {
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
        val readingField = field(if (row == null) tr("vd. nôm, nam", "e.g. nôm, nam", "譬喻：nôm，nam") else tr("Âm đọc", "Reading", "音讀"), 18f).apply {
            setText(row?.reading.orEmpty())
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
            if (nom) {
                addView(caption(tr("Âm đọc:", "Reading:", "音讀")))
                addView(readingField)
            }
            addView(caption(if (nom) tr("Độ phổ biến (tăng mỗi khi chọn chữ khi gõ):", "Frequency (goes up each time you pick it while typing):", "度譜遍（增每欺𫾗𡨸欺𢱗）") else tr("Độ phổ biến:", "Frequency:", "度譜遍")))
            addView(freqField)
            addView(error)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(hanNomText(if (row == null) tr("Thêm vào ${info.name}", "Add to ${info.name}", "添𠓨${info.name}") else tr("Sửa mục", "Edit entry", "𢯢目")))
            .setView(scrollable(form))
            .setPositiveButton(tr("Lưu", "Save", "留"), null)
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
        if (row != null) {
            builder.setNeutralButton(tr("Xoá", "Delete", "𫨮")) { _, _ ->
                store.remove(listOf(row))
                reload()
                toast(tr("Đã xoá ${row.text} ${row.reading}", "Deleted ${row.text} ${row.reading}", "㐌𫨮${row.text} ${row.reading}").trim())
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val freq = freqField.text.toString().trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }
                if (freq == null || freq < 0) {
                    error.text = hanNomText(tr("Độ phổ biến phải là số nguyên không âm", "Frequency must be a non-negative whole number", "度譜遍𬆃𪜀數原空陰"))
                    return@setOnClickListener
                }
                // Ô bỏ trống: nói theo ô của hộp thoại, không theo định dạng tệp của lõi ("thiếu âm đọc sau dấu ;" — người
                // dùng thấy không đúng trải nghiệm, 2026-11-18).
                val blank = when {
                    textField.text.isBlank() -> if (nom) tr("Chưa nhập chữ", "No character entered", "𣗓入𡨸") else tr("Chưa nhập từ", "No word entered", "𣗓入詞")
                    // Không có chữ Hán Nôm nào (chữ Latinh, ký hiệu…) thì được để trống âm đọc (người dùng 2026-11-23).
                    nom && readingField.text.isBlank() && textField.text.codePoints().anyMatch {
                        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
                    } -> tr("Chưa nhập âm đọc", "No reading entered", "𣗓入音讀")
                    else -> null
                }
                if (blank != null) {
                    error.text = blank
                    (if (textField.text.isBlank()) textField else readingField).requestFocus()
                    return@setOnClickListener
                }
                val message = store.put(row, textField.text.toString(), readingField.text.toString(), freq)
                if (message == null) {
                    dialog.dismiss()
                    reload()
                } else {
                    error.text = hanNomText(dictMessage(message))
                }
            }
        }
        // Lớp phủ mặc định làm tối hẳn màn hình phía sau: chỉ phủ nhẹ.
        dialog.window?.setDimAmount(0.2f)
        showAboveKeyboard(dialog)
        val focus = if (row == null || !nom) textField else readingField
        focus.requestFocus()
        focus.setSelection(focus.text.length)
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
            putExtra(Intent.EXTRA_TITLE, "${lang.code}-${info.name.replace(' ', '-')}.txt")
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
                val files = readFiles(uris)
                if (files.isNotEmpty()) previewImport(store, files) { reload() }
            }
            REQUEST_EXPORT -> data.data?.let { uri ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.exportText().toByteArray()) }
                }.onSuccess { toast(tr("Đã xuất ${info.name}", "Exported ${info.name}", "㐌出${info.name}")) }.onFailure { toast(tr("Không ghi được tệp", "Couldn't write the file", "空𥱬得集")) }
            }
        }
    }

    /** Nhập lại bản mặc định đóng gói sẵn: gộp vào (giữ chỗ đã sửa) hoặc thay toàn bộ. */
    private fun builtinDialog() {
        AlertDialog.Builder(this)
            .setTitle(tr("Nhập lại bản mặc định", "Restore default", "入徠版默定"))
            .setMessage(
                tr(
                    "Gộp: thêm lại các mục mặc định còn thiếu, giữ nguyên chỗ đã sửa và thêm.\n\n" +
                        "Thay toàn bộ: từ điển trở về như mặc định, mất mọi chỗ đã sửa và thêm. Nên xuất tệp để lưu trước.",
                    "Merge: add back missing default entries, keeping your edits and additions.\n\n" +
                        "Replace all: the dictionary goes back to the default, losing all edits and additions. " +
                        "Export first to keep a copy.",
                    "𠄦：添徠各目默定𫲳少，𡨺原𡊲㐌𢯢吧添。\n\n𠊝全部：辭典𠭤𧗱如默定，𬇮每𡊲㐌𢯢吧添。𢧚出集抵留𠓀。",
                )
            )
            .setPositiveButton(tr("Gộp", "Merge", "𠄦")) { _, _ ->
                toast(store.mergeBuiltin().added.let { tr("Đã thêm $it mục", "Added $it entries", "㐌添${it}目") })
                reload()
            }
            .setNeutralButton(tr("Thay toàn bộ", "Replace all", "𠊝全部")) { _, _ ->
                store.resetToBuiltin()
                toast(tr("Đã khôi phục bản mặc định", "Default restored", "㐌恢復版默定"))
                reload()
            }
            .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
            .show()
    }

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2
    }
}

/** Đọc các tệp văn bản (tên, nội dung UTF-8); tệp không đọc được thì báo và bỏ qua. */
private fun Activity.readFiles(uris: List<Uri>): List<Pair<String, String>> {
    val files = uris.mapNotNull { uri ->
        runCatching {
            displayName(uri) to contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }
    if (files.size < uris.size) Toast.makeText(this, tr("Không đọc được ${uris.size - files.size} tệp", "Couldn't read ${uris.size - files.size} files", "空讀得${uris.size - files.size}集"), Toast.LENGTH_SHORT).show()
    return files
}

private fun Activity.displayName(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

/**
 * Hiện bản xem trước từng tệp (số mục mới / trùng / lỗi), rồi chọn gộp vào từ điển [store] hoặc thay toàn bộ bằng
 * các tệp này.
 */
private fun Activity.previewImport(store: DictStore, files: List<Pair<String, String>>, done: () -> Unit) {
    val contents = files.map { it.second }
    val reports = store.previewImport(contents)
    val message = buildString {
        files.zip(reports).forEachIndexed { i, (file, report) ->
            if (i > 0) append("\n\n")
            if (files.size > 1) append(file.first).append(":\n")
            append(tr("Sẽ thêm ${report.added} mục, bỏ qua ${report.duplicates} mục trùng.", "Will add ${report.added} entries, skip ${report.duplicates} duplicates.", "𫪣添${report.added}目，𠬃過${report.duplicates}目重"))
            if (report.errors.isNotEmpty()) {
                append(tr("\n${report.errors.size} dòng lỗi (sẽ bỏ qua):\n", "\n${report.errors.size} bad lines (will be skipped):\n", "\n${report.errors.size}𣳔𥓹（𫪣𠬃過）：\n"))
                report.errors.take(50).forEach { append("• ").append(dictMessage(it)).append('\n') }
                if (report.errors.size > 50) append("…")
            }
        }
    }
    val toast = { text: String -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    AlertDialog.Builder(this)
        .setTitle(if (files.size > 1) tr("Nhập ${files.size} tệp", "Import ${files.size} files", "入${files.size}集") else tr("Nhập tệp", "Import file", "入集"))
        .setMessage(
            hanNomText(
                "$message\n\n" + tr(
                    "Gộp: thêm các mục mới vào từ điển này, mục đã có giữ nguyên độ phổ biến.\n" +
                        "Thay toàn bộ: từ điển chỉ còn các mục của tệp nhập. Nên xuất tệp để lưu trước.",
                    "Merge: add the new entries to this dictionary; existing entries keep their frequency.\n" +
                        "Replace all: the dictionary keeps only the imported entries. Export first to keep a copy.",
                    "𠄦：添各目𬔫𠓨辭典呢，目㐌𣎏𡨺原度譜遍。\n𠊝全部：辭典只𫲳各目𧵑集入。𢧚出集抵留𠓀。",
                )
            )
        )
        .setPositiveButton(tr("Gộp", "Merge", "𠄦")) { _, _ ->
            toast(store.import(contents).sumOf { it.added }.let { tr("Đã thêm $it mục", "Added $it entries", "㐌添${it}目") })
            done()
        }
        .setNeutralButton(tr("Thay toàn bộ", "Replace all", "𠊝全部")) { _, _ ->
            toast(store.replaceWith(contents).sumOf { it.added }.let { tr("Từ điển giờ có $it mục", "The dictionary now has $it entries", "辭典𣇞𣎏${it}目") })
            done()
        }
        .setNegativeButton(tr("Huỷ", "Cancel", "毀"), null)
        .show()
}
