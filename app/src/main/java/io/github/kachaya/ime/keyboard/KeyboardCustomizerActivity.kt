package io.github.kachaya.ime.keyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.tabs.TabLayout
import io.github.kachaya.ime.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * キーボード配列のカスタマイズ（ドラッグ＆ドロップ編集）を行うためのアクティビティです。
 */
class KeyboardCustomizerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREF_KEY = "pref_key"
        const val EXTRA_TITLE = "title"

        private val DEFAULT_LAYOUTS: MutableMap<String, String?> = HashMap()
    }

    private var mTargetPrefKey: String? = null

    private fun loadDefaultLayouts() {
        DEFAULT_LAYOUTS.clear()
        DEFAULT_LAYOUTS["custom_qwerty_layout_normal"] = LayoutManager.DefaultLayouts.get(this, "custom_qwerty_layout_normal")
        DEFAULT_LAYOUTS["custom_qwerty_layout_shift"] = LayoutManager.DefaultLayouts.get(this, "custom_qwerty_layout_shift")
        DEFAULT_LAYOUTS["custom_qwerty_layout_symbol"] = LayoutManager.DefaultLayouts.get(this, "custom_qwerty_layout_symbol")
        DEFAULT_LAYOUTS["custom_tablet_layout_normal"] = LayoutManager.DefaultLayouts.get(this, "custom_tablet_layout_normal")
        DEFAULT_LAYOUTS["custom_tablet_layout_shift"] = LayoutManager.DefaultLayouts.get(this, "custom_tablet_layout_shift")
        DEFAULT_LAYOUTS["combined_symbols"] = LayoutManager.DefaultLayouts.get(this, "combined_symbols")
    }

    private lateinit var mPreviewContainer: LinearLayout
    private lateinit var mDeleteZone: View
    private lateinit var mPaletteSpecial: FlexboxLayout
    private lateinit var mPaletteAlpha: FlexboxLayout
    private lateinit var mPaletteOther: FlexboxLayout
    private lateinit var mPaletteTabs: TabLayout

    private val mUsedChars: MutableSet<String> = HashSet()

    private enum class EditMode { NORMAL, SHIFT, SYMBOL }

    private var mEditMode: EditMode = EditMode.NORMAL

    private val mLayoutBuffers: MutableMap<EditMode, String?> = HashMap()
    private var mHapticEnabled: Boolean = false
    private lateinit var mInsertionIndicator: View

    private val mBackupLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> backupLayout(uri) }

    private val mRestoreLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> restoreLayout(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadDefaultLayouts()
        setContentView(R.layout.activity_keyboard_customizer)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val intent = intent
        mTargetPrefKey = intent.getStringExtra(EXTRA_PREF_KEY) ?: "custom_qwerty_layout"

        val title = intent.getStringExtra(EXTRA_TITLE)
        if (title != null) {
            setTitle(title)
        }

        mPreviewContainer = findViewById(R.id.keyboard_preview_container)
        mDeleteZone = findViewById(R.id.delete_zone)
        mPaletteSpecial = findViewById(R.id.key_palette_special)
        mPaletteAlpha = findViewById(R.id.key_palette_alpha)
        mPaletteOther = findViewById(R.id.key_palette_other)
        mPaletteTabs = findViewById(R.id.palette_tabs)

        mPaletteTabs.addTab(mPaletteTabs.newTab().setText("機能"))
        mPaletteTabs.addTab(mPaletteTabs.newTab().setText("英字"))
        mPaletteTabs.addTab(mPaletteTabs.newTab().setText("数字・記号"))

        mPaletteTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updatePaletteVisibility(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val modeSelector = findViewById<RadioGroup>(R.id.mode_selector)
        if (LayoutManager.isSymbolBar(mTargetPrefKey)) {
            modeSelector.visibility = View.GONE
        } else if (!LayoutManager.hasSymbolMode(mTargetPrefKey)) {
            findViewById<View>(R.id.radio_symbol).visibility = View.GONE
        }

        modeSelector.setOnCheckedChangeListener { _, checkedId ->
            mLayoutBuffers[mEditMode] = serializeLayout()

            when (checkedId) {
                R.id.radio_normal -> mEditMode = EditMode.NORMAL
                R.id.radio_shift -> mEditMode = EditMode.SHIFT
                R.id.radio_symbol -> mEditMode = EditMode.SYMBOL
            }

            renderKeyboard(mLayoutBuffers[mEditMode])
            updateUsedChars()
            setupPalette()
        }

        mHapticEnabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("haptic_feedback", true)

        mInsertionIndicator = View(this).apply {
            setBackgroundColor(0x6633B5E5.toInt())
        }

        mDeleteZone.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DROP) {
                val draggedView = event.localState as? View
                if (draggedView?.parent != null) {
                    val parent = draggedView.parent as ViewGroup
                    parent.removeView(draggedView)
                    refreshAllViews()
                }
                true
            } else {
                true
            }
        }

        loadLayout()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.keyboard_customizer_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                saveLayout()
                true
            }
            R.id.action_backup -> {
                val fileName = if ("combined_symbols" == mTargetPrefKey) {
                    "ime_symbol_layout.json"
                } else if ("custom_tablet_layout" == mTargetPrefKey) {
                    "ime_tablet_layout.json"
                } else {
                    "ime_qwerty_layout.json"
                }
                mBackupLauncher.launch(fileName)
                true
            }
            R.id.action_restore -> {
                mRestoreLauncher.launch(arrayOf("application/json", "text/plain"))
                true
            }
            R.id.action_reset -> {
                resetToDefault()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshAllViews() {
        val current = serializeLayout()
        if ("combined_symbols" == mTargetPrefKey) {
            mLayoutBuffers[EditMode.NORMAL] = current
            renderKeyboard(current)
        } else {
            mLayoutBuffers[mEditMode] = current
            renderKeyboard(current)
        }
        updateUsedChars()
        setupPalette()
    }

    private fun updateUsedChars() {
        mUsedChars.clear()
        for (layoutStr in mLayoutBuffers.values) {
            if (layoutStr == null) continue
            val layout = KeyConfig.layoutFromAnyString(layoutStr)
            for (row in layout) {
                for (cfg in row) {
                    if (cfg.label.isNotEmpty()) {
                        mUsedChars.add(cfg.label)
                    }
                }
            }
        }
    }

    private fun loadLayout() {
        mPreviewContainer.removeAllViews()

        if ("combined_symbols" == mTargetPrefKey) {
            val layoutStr = LayoutManager.loadLayout(this, "custom_symbols_layout", DEFAULT_LAYOUTS["combined_symbols"] ?: "")
            mLayoutBuffers[EditMode.NORMAL] = layoutStr
            renderKeyboard(layoutStr)
        } else {
            loadIndependentLayout(EditMode.NORMAL, "_normal")
            loadIndependentLayout(EditMode.SHIFT, "_shift")
            loadIndependentLayout(EditMode.SYMBOL, "_symbol")

            renderKeyboard(mLayoutBuffers[mEditMode])
        }
        updateUsedChars()
        setupPalette()
    }

    private fun loadIndependentLayout(mode: EditMode, suffix: String) {
        val key = mTargetPrefKey + suffix
        val defaultVal = DEFAULT_LAYOUTS[key] ?: ""
        val saved = LayoutManager.loadLayout(this, key, defaultVal)
        mLayoutBuffers[mode] = saved
    }

    private fun renderKeyboard(layoutStr: String?) {
        if (layoutStr == null) {
            return
        }
        renderKeyboard(KeyConfig.layoutFromAnyString(layoutStr))
    }

    private fun renderKeyboard(layout: Array<Array<KeyConfig>>?) {
        mPreviewContainer.removeAllViews()
        var currentLayout = layout
        if (currentLayout.isNullOrEmpty()) {
            currentLayout = arrayOf(emptyArray())
        }

        for (rowConfigs in currentLayout) {
            val rowLayout = createRowLayout()
            if (rowConfigs.isEmpty()) {
                val tv = TextView(this).apply {
                    text = "ここへキーをドラッグして追加"
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    tag = "placeholder"
                }
                rowLayout.addView(tv)
            } else {
                for (config in rowConfigs) {
                    rowLayout.addView(createKeyView(config, true))
                }
            }
            mPreviewContainer.addView(rowLayout)
        }
    }

    private fun createRowLayout(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimension(R.dimen.button_height).toInt()
        )
        row.layoutParams = lp
        row.setOnDragListener(RowDragListener())
        return row
    }

    private fun createKeyView(config: KeyConfig, isInKeyboard: Boolean): View {
        val view: View
        val density = resources.displayMetrics.density

        var paletteItemWidth = 0
        val paletteMargin = 0
        if (!isInKeyboard) {
            val screenWidth = resources.displayMetrics.widthPixels
            val totalPadding = (24 * density).toInt()
            val scrollbarWidth = (4 * density).toInt()
            val availableWidth = screenWidth - totalPadding - scrollbarWidth
            paletteItemWidth = availableWidth / 10
        }

        if (config.code == KeyConfig.CODE_GAP || "GAP" == config.label) {
            val tv = TextView(this).apply {
                text = "Gap"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)

                if (isInKeyboard) {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, config.weight)
                    setBackgroundColor(Color.LTGRAY)
                    setTextColor(Color.GRAY)
                    alpha = 0.6f
                } else {
                    val lp = FlexboxLayout.LayoutParams(paletteItemWidth, resources.getDimension(R.dimen.button_height).toInt())
                    lp.setMargins(paletteMargin, paletteMargin, paletteMargin, paletteMargin)
                    layoutParams = lp
                    setBackgroundColor(Color.DKGRAY)
                    setTextColor(Color.WHITE)
                }
            }
            view = tv
        } else {
            val b = KeyViewFactory.createKeyButton(this, config)

            if (isInKeyboard) {
                b.minWidth = 0
                b.minimumWidth = 0
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, config.weight)
                b.layoutParams = lp
            } else {
                b.alpha = 1.0f
                if (!mUsedChars.contains(config.label)) {
                    b.setTextColor(Color.GRAY)
                }

                val lp = FlexboxLayout.LayoutParams(paletteItemWidth, resources.getDimension(R.dimen.button_height).toInt())
                lp.setMargins(paletteMargin, paletteMargin, paletteMargin, paletteMargin)
                b.layoutParams = lp
            }
            view = b
        }

        view.tag = config

        view.setOnClickListener { v ->
            if (mHapticEnabled) {
                v.isHapticFeedbackEnabled = true
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (isInKeyboard || config.code == KeyConfig.CODE_GAP || "GAP" == config.label) {
                showKeyEditDialog(config)
            }
        }

        view.setOnLongClickListener { v ->
            if (mHapticEnabled) {
                v.isHapticFeedbackEnabled = true
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            val item = ClipData.Item(config.toString())
            val dragData = ClipData(config.toString(), arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), item)
            val shadow = View.DragShadowBuilder(view)
            v.startDragAndDrop(dragData, shadow, v, 0)
            if (isInKeyboard) {
                v.visibility = View.INVISIBLE
            }
            true
        }

        return view
    }

    private fun showKeyEditDialog(config: KeyConfig) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val isGap = config.code == KeyConfig.CODE_GAP || "GAP" == config.label
        val isSymbolBar = "combined_symbols" == mTargetPrefKey

        var configProvider: KeyConfigProvider? = null

        if (!isGap && !isSymbolBar) {
            configProvider = createFunctionalField(layout, config)
        }

        val editWeight = createField(layout, "ウェイト (1.0標準)", config.weight.toString())
        editWeight.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        val finalProvider = configProvider

        AlertDialog.Builder(this)
            .setTitle(if (isGap) "GAPの調整" else "キーの編集")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                try {
                    if (!isGap && finalProvider != null) {
                        finalProvider.applyTo(config)
                    }
                    val weight = editWeight.text.toString().toFloat()
                    if (weight > 0) {
                        config.weight = weight
                    }

                    refreshAllViews()
                } catch (_: NumberFormatException) {
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun interface KeyConfigProvider {
        fun applyTo(config: KeyConfig)
    }

    private fun createFunctionalField(container: LinearLayout, config: KeyConfig): KeyConfigProvider {
        val tv = TextView(this).apply {
            text = "入力文字 / 機能"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 16, 0, 0)
        }
        container.addView(tv)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val spinner = Spinner(this)
        val funcLabels = arrayOf("(文字入力)", "Shift", "Enter", "Backspace", "Space", "@!?", "Ctrl", "Tab", "Esc", "左移動", "右移動", "上移動", "下移動", "abc", "あ/A")
        val funcCodes = intArrayOf(KeyConfig.CODE_NONE, KeyConfig.CODE_SHIFT, KeyConfig.CODE_ENTER, KeyConfig.CODE_BACKSPACE, KeyConfig.CODE_SPACE, KeyConfig.CODE_SYM, KeyConfig.CODE_CTRL, KeyConfig.CODE_TAB, KeyConfig.CODE_ESC, KeyConfig.CODE_LEFT, KeyConfig.CODE_RIGHT, KeyConfig.CODE_UP, KeyConfig.CODE_DOWN, KeyConfig.CODE_ABC, KeyConfig.CODE_MODE)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, funcLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val etLabel = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            hint = "表示ラベル"
            setText(config.label)
        }

        var selected = 0
        if (config.code != KeyConfig.CODE_NONE) {
            for (i in 1..<funcCodes.size) {
                if (funcCodes[i] == config.code) {
                    selected = i
                    break
                }
            }
        }
        spinner.setSelection(selected)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isInitial = true

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isInitial) {
                    isInitial = false
                    return
                }
                val code = funcCodes[position]
                if (code != KeyConfig.CODE_NONE) {
                    val defaultLabel = KeyConfig.getDefaultLabel(code)
                    if (defaultLabel.isNotEmpty()) {
                        etLabel.setText(defaultLabel)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        row.addView(spinner)
        row.addView(etLabel)
        container.addView(row)

        return KeyConfigProvider { cfg ->
            val pos = spinner.selectedItemPosition
            cfg.code = funcCodes[pos]
            cfg.label = etLabel.text.toString()
        }
    }

    private fun createField(container: LinearLayout, label: String, value: String): EditText {
        val tv = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        container.addView(tv)
        val et = EditText(this).apply {
            setText(value)
        }
        container.addView(et)
        return et
    }

    private fun updatePaletteVisibility(position: Int) {
        when (position) {
            0 -> {
                mPaletteSpecial.visibility = View.VISIBLE
                mPaletteAlpha.visibility = View.GONE
                mPaletteOther.visibility = View.GONE
            }
            1 -> {
                mPaletteSpecial.visibility = View.GONE
                mPaletteAlpha.visibility = View.VISIBLE
                mPaletteOther.visibility = View.GONE
            }
            2 -> {
                mPaletteSpecial.visibility = View.GONE
                mPaletteAlpha.visibility = View.GONE
                mPaletteOther.visibility = View.VISIBLE
            }
        }
    }

    private fun setupPalette() {
        mPaletteSpecial.removeAllViews()
        mPaletteAlpha.removeAllViews()
        mPaletteOther.removeAllViews()

        if (!LayoutManager.isSymbolBar(mTargetPrefKey)) {
            mPaletteTabs.visibility = View.VISIBLE
            val specialKeys = LayoutManager.getSpecialKeysPalette(mTargetPrefKey)
            for (config in specialKeys) {
                mPaletteSpecial.addView(createKeyView(config, false))
            }
            fillPaletteDummies(mPaletteSpecial, specialKeys.size)

            val alphaKeys = LayoutManager.getAlphaKeysPalette()
            for (config in alphaKeys) {
                mPaletteAlpha.addView(createKeyView(config, false))
            }
            fillPaletteDummies(mPaletteAlpha, alphaKeys.size)

            val symbolKeys = LayoutManager.getSymbolKeysPalette(mTargetPrefKey)
            for (config in symbolKeys) {
                mPaletteOther.addView(createKeyView(config, false))
            }
            fillPaletteDummies(mPaletteOther, symbolKeys.size)

            updatePaletteVisibility(mPaletteTabs.selectedTabPosition)
        } else {
            mPaletteTabs.visibility = View.GONE
            mPaletteSpecial.visibility = View.GONE
            mPaletteAlpha.visibility = View.GONE
            mPaletteOther.visibility = View.VISIBLE

            val symbolBarKeys = LayoutManager.getSymbolKeysPalette(mTargetPrefKey)
            for (config in symbolBarKeys) {
                mPaletteOther.addView(createKeyView(config, false))
            }
            fillPaletteDummies(mPaletteOther, symbolBarKeys.size)
        }
    }

    private fun fillPaletteDummies(palette: FlexboxLayout, currentCount: Int) {
        val remainder = currentCount % 10
        if (remainder == 0) {
            return
        }
        val dummiesNeeded = 10 - remainder

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val totalPadding = (24 * density).toInt()
        val scrollbarWidth = (4 * density).toInt()
        val availableWidth = screenWidth - totalPadding - scrollbarWidth
        val paletteItemWidth = availableWidth / 10

        for (i in 0..<dummiesNeeded) {
            val dummy = View(this)
            val lp = FlexboxLayout.LayoutParams(
                paletteItemWidth, resources.getDimension(R.dimen.button_height).toInt()
            )
            dummy.layoutParams = lp
            dummy.visibility = View.INVISIBLE
            palette.addView(dummy)
        }
    }

    private fun serializeLayout(): String {
        val layout = serializeLayoutToConfigs()
        val arr = Array(layout.size) { i -> layout[i].toTypedArray() }
        return KeyConfig.layoutToJsonString(arr)
    }

    private fun saveLayout() {
        if (LayoutManager.isSymbolBar(mTargetPrefKey)) {
            LayoutManager.saveLayout(this, "custom_symbols_layout", serializeLayout())
        } else {
            mLayoutBuffers[mEditMode] = serializeLayout()

            LayoutManager.saveLayout(this, "${mTargetPrefKey}_normal", mLayoutBuffers[EditMode.NORMAL] ?: "")
            LayoutManager.saveLayout(this, "${mTargetPrefKey}_shift", mLayoutBuffers[EditMode.SHIFT] ?: "")
            if (LayoutManager.hasSymbolMode(mTargetPrefKey)) {
                LayoutManager.saveLayout(this, "${mTargetPrefKey}_symbol", mLayoutBuffers[EditMode.SYMBOL] ?: "")
            }
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putLong(LayoutManager.PREF_LAYOUT_UPDATED, System.currentTimeMillis())
            .apply()

        Toast.makeText(this, "配置を保存しました", Toast.LENGTH_SHORT).show()
    }

    private fun serializeLayoutToConfigs(): List<List<KeyConfig>> {
        val layout = ArrayList<List<KeyConfig>>()
        for (idx in 0..<mPreviewContainer.childCount) {
            val child = mPreviewContainer.getChildAt(idx)
            if (child is LinearLayout) {
                val rowConfigs = ArrayList<KeyConfig>()
                for (j in 0..<child.childCount) {
                    val v = child.getChildAt(j)
                    if (v == mInsertionIndicator || "placeholder" == v.tag) continue
                    val config = v.tag as? KeyConfig
                    if (config != null) {
                        rowConfigs.add(config)
                    }
                }
                layout.add(rowConfigs)
            }
        }
        return layout
    }

    private fun resetToDefault() {
        AlertDialog.Builder(this)
            .setTitle("レイアウトの初期化")
            .setMessage("このキーボードレイアウトの配置を初期状態に戻しますか？")
            .setPositiveButton("初期化") { _, _ ->
                LayoutManager.clearLayout(this, mTargetPrefKey)

                if (LayoutManager.isSymbolBar(mTargetPrefKey)) {
                    val defaultSym = DEFAULT_LAYOUTS["combined_symbols"]
                    mLayoutBuffers[EditMode.NORMAL] = defaultSym
                    renderKeyboard(defaultSym)
                } else {
                    mLayoutBuffers[EditMode.NORMAL] = DEFAULT_LAYOUTS["${mTargetPrefKey}_normal"]
                    mLayoutBuffers[EditMode.SHIFT] = DEFAULT_LAYOUTS["${mTargetPrefKey}_shift"]
                    if (LayoutManager.hasSymbolMode(mTargetPrefKey)) {
                        mLayoutBuffers[EditMode.SYMBOL] = DEFAULT_LAYOUTS["${mTargetPrefKey}_symbol"]
                    }
                    renderKeyboard(mLayoutBuffers[mEditMode])
                }
                updateUsedChars()
                setupPalette()

                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putLong(LayoutManager.PREF_LAYOUT_UPDATED, System.currentTimeMillis())
                    .apply()

                Toast.makeText(this, "レイアウトを初期化しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun backupLayout(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri, "wt").use { os ->
                if (os == null) return
                val layouts = LinkedHashMap<String, Array<Array<KeyConfig>>>()
                if (LayoutManager.isSymbolBar(mTargetPrefKey)) {
                    layouts["custom_symbols_layout"] = KeyConfig.layoutFromAnyString(serializeLayout())
                } else {
                    mLayoutBuffers[mEditMode] = serializeLayout()
                    layouts["${mTargetPrefKey}_normal"] = KeyConfig.layoutFromAnyString(mLayoutBuffers[EditMode.NORMAL])
                    layouts["${mTargetPrefKey}_shift"] = KeyConfig.layoutFromAnyString(mLayoutBuffers[EditMode.SHIFT])
                    if (LayoutManager.hasSymbolMode(mTargetPrefKey)) {
                        layouts["${mTargetPrefKey}_symbol"] = KeyConfig.layoutFromAnyString(mLayoutBuffers[EditMode.SYMBOL])
                    }
                }
                val jsonString = KeyConfig.backupToJsonString(layouts)
                os.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                Toast.makeText(this, "配置をバックアップしました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "バックアップに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreLayout(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.openInputStream(uri).use { isStream ->
                if (isStream == null) return
                BufferedReader(InputStreamReader(isStream, StandardCharsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }

                    val json = JSONObject(sb.toString())
                    if ("combined_symbols" == mTargetPrefKey) {
                        if (json.has("custom_symbols_layout")) {
                            mLayoutBuffers[EditMode.NORMAL] = json.getJSONArray("custom_symbols_layout").toString()
                        }
                    } else {
                        val suffixes = arrayOf("_normal", "_shift", "_symbol")
                        val modes = arrayOf(EditMode.NORMAL, EditMode.SHIFT, EditMode.SYMBOL)
                        for (i in suffixes.indices) {
                            val key = mTargetPrefKey + suffixes[i]
                            if (json.has(key)) {
                                mLayoutBuffers[modes[i]] = json.getJSONArray(key).toString()
                            }
                        }
                    }
                    renderKeyboard(mLayoutBuffers[mEditMode])
                    updateUsedChars()
                    setupPalette()
                    Toast.makeText(this, "配置を復元しました（保存ボタンで確定してください）", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "復元に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private inner class RowDragListener : View.OnDragListener {
        override fun onDrag(v: View, event: DragEvent): Boolean {
            val owner = v as ViewGroup
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val dragged = event.localState as? View
                    if (dragged != null) {
                        val w = dragged.width
                        if (w > 0) {
                            mInsertionIndicator.layoutParams = LinearLayout.LayoutParams(
                                w, resources.getDimension(R.dimen.button_height).toInt()
                            )
                        }
                    }
                    return true
                }

                DragEvent.ACTION_DRAG_LOCATION -> {
                    val index = calculateDropIndex(owner, event.x)
                    val placeholderView = owner.findViewWithTag<View>("placeholder")
                    placeholderView?.visibility = View.GONE
                    if (mInsertionIndicator.parent != owner || owner.indexOfChild(mInsertionIndicator) != index) {
                        (mInsertionIndicator.parent as? ViewGroup)?.removeView(mInsertionIndicator)
                        owner.addView(mInsertionIndicator, index)
                    }
                }

                DragEvent.ACTION_DRAG_EXITED -> {
                    if (mInsertionIndicator.parent == owner) {
                        owner.removeView(mInsertionIndicator)
                    }
                    val pView = owner.findViewWithTag<View>("placeholder")
                    pView?.visibility = View.VISIBLE
                }

                DragEvent.ACTION_DROP -> {
                    (mInsertionIndicator.parent as? ViewGroup)?.removeView(mInsertionIndicator)
                    val draggedView = event.localState as? View
                    val clipItem = event.clipData?.getItemAt(0)
                    val keyData = clipItem?.text?.toString() ?: ""
                    val config = KeyConfig.fromString(keyData)

                    var isFromLayout = false
                    if (draggedView?.parent is ViewGroup) {
                        val p = draggedView.parent as ViewGroup
                        if (p.parent == mPreviewContainer) {
                            isFromLayout = true
                        }
                    }

                    if (isFromLayout) {
                        val oldParent = draggedView?.parent as? ViewGroup
                        oldParent?.removeView(draggedView)
                    }

                    val newKey = createKeyView(config, true)
                    val dropIndex = calculateDropIndex(owner, event.x)
                    owner.addView(newKey, dropIndex)
                    newKey.visibility = View.VISIBLE

                    refreshAllViews()
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    if (mInsertionIndicator.parent == owner) {
                        owner.removeView(mInsertionIndicator)
                    }
                    val endPlaceholder = owner.findViewWithTag<View>("placeholder")
                    endPlaceholder?.visibility = View.VISIBLE
                    val localState = event.localState
                    if (localState is View) {
                        localState.visibility = View.VISIBLE
                    }
                }
            }
            return true
        }

        private fun calculateDropIndex(container: ViewGroup, x: Float): Int {
            var totalWeight = 0f
            val childCount = container.childCount
            val configs = ArrayList<KeyConfig>()
            for (i in 0..<childCount) {
                val child = container.getChildAt(i)
                if (child == mInsertionIndicator || "placeholder" == child.tag) {
                    continue
                }
                val cfg = child.tag as? KeyConfig
                if (cfg != null) {
                    configs.add(cfg)
                    totalWeight += cfg.weight
                }
            }

            if (totalWeight <= 0) {
                return 0
            }

            val containerWidth = container.width.toFloat()
            if (containerWidth <= 0) {
                return 0
            }

            val indicatorWidth = mInsertionIndicator.layoutParams.width
            var availableWidth = containerWidth
            if (mInsertionIndicator.parent == container) {
                availableWidth -= indicatorWidth
            }

            var accumulatedWeight = 0f
            for (i in configs.indices) {
                val w = configs[i].weight
                val thresholdX = ((accumulatedWeight + w / 2f) / totalWeight) * availableWidth
                if (x < thresholdX) {
                    return i
                }
                accumulatedWeight += w
            }
            return configs.size
        }
    }
}
