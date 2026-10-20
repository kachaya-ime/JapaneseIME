package io.github.kachaya.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.flexbox.FlexboxLayout
import io.github.kachaya.ime.SettingsActivity.PrefKeys
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.keyboard.EmojiKeyboardView
import io.github.kachaya.ime.keyboard.KeyConfig
import io.github.kachaya.ime.keyboard.KeyboardView
import io.github.kachaya.ime.keyboard.KeyboardView.KeyboardState
import io.github.kachaya.ime.keyboard.LayoutManager
import io.github.kachaya.ime.keyboard.LayoutManager.DefaultLayouts
import io.github.kachaya.ime.keyboard.QwertyKeyboardView
import io.github.kachaya.ime.keyboard.StrokeKeyboardView
import io.github.kachaya.ime.keyboard.SymbolKeyboardView
import io.github.kachaya.ime.keyboard.TabletKeyboardView

/**
 * 日本語入力のビュー（キーボード UI）を管理するクラスです。
 */
class InputView @JvmOverloads constructor(
    context: Context,
    private val mInputService: InputService? = if (context is InputService) context else null,
    themedContext: Context = InputService.getThemedContext(context)
) : LinearLayout(themedContext) {

    private val mCandidatesLayout: LinearLayout
    private val mCandidateBarLayout: View?
    private val mCandidatesView: HorizontalScrollView
    private val mKeyboardLayout: LinearLayout
    private val mCandidateExpandedScroll: ScrollView?
    private val mCandidateExpandedFlexbox: FlexboxLayout?
    private val mBtnExpandCandidates: Button?
    private var mIsExpanded = false

    private var mCurrentKeyboardView: KeyboardView? = null

    private var mHapticEnabled = false

    private var mAdjustedButtonHeight = 0
    private var mAdjustedCandidateHeight = 0
    private var mAdjustedCandidateTextSize = 0f

    private var mKeyboardType: String? = null

    private var mStrokeAlign = "right"
    private var mStrokeWidthScale = 1.0f
    private var mTabletAlign = "right"
    private var mTabletWidthScale = 1.0f

    private var mIsShifted = false
    private var mIsShiftLocked = false
    private var mIsControl = false
    private var mIsSymbol = false
    private var mIsSymbolLocked = false
    private var mModeText = ""
    private var mModeIconResId = 0

    private var mCandidateButton: Array<Button?>? = null
    private var mDefaultCandidateTextColorStateList: ColorStateList? = null

    private var mLastEditorInfo: EditorInfo? = null

    private var mLayoutUpdatedAt: Long = 0
    private var mRowCount = 4

    private var mCandidatePopup: PopupWindow? = null
    private var mLastPopupY: Int = -1
    private var mLastPopupHeight: Int = -1

    init {
        val layout = LayoutInflater.from(themedContext).inflate(R.layout.input, this)
        mCandidateBarLayout = layout.findViewById(R.id.candidate_bar_layout)
        mCandidatesView = layout.findViewById(R.id.candidate_view)
        mCandidatesLayout = layout.findViewById(R.id.candidates_layout)
        mKeyboardLayout = layout.findViewById(R.id.keyboard_layout)
        mCandidateExpandedScroll = layout.findViewById(R.id.candidate_expanded_scroll)
        mCandidateExpandedFlexbox = layout.findViewById(R.id.candidate_expanded_flexbox)
        mBtnExpandCandidates = layout.findViewById(R.id.btn_expand_candidates)
        mBtnExpandCandidates?.setOnClickListener { toggleExpanded() }

        hideCandidatesView()

        readPrefs()

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, v.paddingTop, systemBars.right, systemBars.bottom)
            readPrefs()
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (PrefKeys.KEYBOARD_TYPE_NONE == mKeyboardType) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
            return
        }
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val expectedKeyboardHeight: Int = if (PrefKeys.KEYBOARD_TYPE_STROKE == mKeyboardType) {
            val baseSize = Math.min(screenWidth, screenHeight)
            (baseSize * mStrokeWidthScale).toInt() / 2
        } else if ("emoji" == mKeyboardType) {
            mAdjustedButtonHeight * 5
        } else {
            mAdjustedButtonHeight * mRowCount
        }
        val candidateHeight = if (mCandidateBarLayout != null && mCandidateBarLayout.visibility != GONE) {
            if (mCandidateBarLayout.height > 0) mCandidateBarLayout.height else mAdjustedCandidateHeight
        } else 0
        val maxHeight = Math.max(screenHeight / 2, candidateHeight + expectedKeyboardHeight) + paddingBottom

        var heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var heightSize = MeasureSpec.getSize(heightMeasureSpec)

        if (heightMode == MeasureSpec.EXACTLY) {
            heightSize = Math.min(heightSize, maxHeight)
        } else if (heightMode == MeasureSpec.AT_MOST) {
            heightSize = Math.min(heightSize, maxHeight)
        } else {
            heightSize = maxHeight
            heightMode = MeasureSpec.AT_MOST
        }

        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(heightSize, heightMode))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readPrefs()
    }

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(javaClass.simpleName, msg)
        }
    }

    fun readPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val haptic = prefs.getBoolean(PrefKeys.HAPTIC_FEEDBACK, true)

        val config = context.resources.configuration
        val hasHardwareKeyboard = (config.keyboard != Configuration.KEYBOARD_NOKEYS &&
                config.keyboard != Configuration.KEYBOARD_UNDEFINED &&
                config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES)

        val isPhysical = mInputService?.hasHardwareKeyboardConnected() ?: hasHardwareKeyboard
        val defaultType = if (isPhysical) PrefKeys.KEYBOARD_TYPE_SYMBOLS else PrefKeys.KEYBOARD_TYPE_QWERTY
        val type = prefs.getString(PrefKeys.KEYBOARD_TYPE, defaultType)
        mStrokeAlign = prefs.getString(PrefKeys.STROKE_ALIGN, "right") ?: "right"
        mTabletAlign = prefs.getString(PrefKeys.TABLET_ALIGN, "right") ?: "right"
        var strokeWidthScale = 1.0f
        try {
            strokeWidthScale = (prefs.getString(PrefKeys.STROKE_WIDTH_SCALE, "1.0") ?: "1.0").toFloat()
        } catch (_: Exception) {
        }
        mStrokeWidthScale = strokeWidthScale

        var tabletWidthScale = 1.0f
        try {
            tabletWidthScale = (prefs.getString(PrefKeys.TABLET_WIDTH_SCALE, "1.0") ?: "1.0").toFloat()
        } catch (_: Exception) {
        }
        mTabletWidthScale = tabletWidthScale

        var heightScale = 1.0f
        try {
            val heightPrefKey = "keyboard_height_scale_$type"
            var heightVal = prefs.getString(heightPrefKey, null)
            if (heightVal == null) {
                heightVal = prefs.getString(PrefKeys.KEYBOARD_HEIGHT_SCALE, "1.0")
            }
            heightScale = (heightVal ?: "1.0").toFloat()
        } catch (_: Exception) {
        }
        var candidateHeightScale = 1.0f
        try {
            candidateHeightScale = (prefs.getString(PrefKeys.CANDIDATE_HEIGHT_SCALE, "1.0") ?: "1.0").toFloat()
        } catch (_: Exception) {
        }
        var candidateFontSizeSp = 24.0f
        try {
            candidateFontSizeSp = (prefs.getString(PrefKeys.CANDIDATE_FONT_SIZE, "24") ?: "24").toFloat()
        } catch (_: Exception) {
        }
        val updatedAt = prefs.getLong(LayoutManager.PREF_LAYOUT_UPDATED, 0)

        var changed = (mKeyboardType == null) || (
                type != mKeyboardType ||
                        updatedAt != mLayoutUpdatedAt ||
                        mStrokeWidthScale != strokeWidthScale ||
                        mTabletWidthScale != tabletWidthScale ||
                        mStrokeAlign != prefs.getString("stroke_align", "right") ||
                        mTabletAlign != prefs.getString("tablet_align", "right")
                )
        mStrokeAlign = prefs.getString("stroke_align", "right") ?: "right"
        mTabletAlign = prefs.getString("tablet_align", "right") ?: "right"
        mStrokeWidthScale = strokeWidthScale
        mTabletWidthScale = tabletWidthScale

        mHapticEnabled = haptic
        mKeyboardType = type
        mLayoutUpdatedAt = updatedAt

        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val defaultButtonHeight = (resources.getDimension(R.dimen.button_height) * heightScale).toInt()

        if (PrefKeys.KEYBOARD_TYPE_STROKE == type || PrefKeys.KEYBOARD_TYPE_NONE == type) {
            mRowCount = 0
        } else if (PrefKeys.KEYBOARD_TYPE_SYMBOLS == type) {
            mRowCount = 1
        } else if ("emoji" == type) {
            mRowCount = 5
        } else {
            val baseKey = if (PrefKeys.KEYBOARD_TYPE_TABLET == type) "custom_tablet_layout" else "custom_qwerty_layout"
            val layoutStr = LayoutManager.loadLayout(context, "${baseKey}_normal", DefaultLayouts.get(context, "${baseKey}_normal") ?: "")
            val layout = KeyConfig.layoutFromAnyString(layoutStr)
            mRowCount = Math.max(1, layout.size)
        }

        val minButtonHeight = if (PrefKeys.KEYBOARD_TYPE_STROKE == type || PrefKeys.KEYBOARD_TYPE_NONE == type) 0 else (32 * density).toInt()

        val availableHeight = Math.max(0, screenHeight - paddingBottom)
        val totalRows = if (PrefKeys.KEYBOARD_TYPE_STROKE == type) {
            5
        } else if (PrefKeys.KEYBOARD_TYPE_NONE == type) {
            1
        } else {
            mRowCount + 1
        }
        val maxAllowedHeight = (availableHeight / 2) / totalRows
        val newAdjustedButtonHeight = Math.max(minButtonHeight, Math.min(defaultButtonHeight, maxAllowedHeight))

        val newAdjustedCandidateTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            candidateFontSizeSp,
            resources.displayMetrics
        )
        val newAdjustedCandidateHeight = (resources.getDimension(R.dimen.candidate_height) * (candidateFontSizeSp / 24.0f) * candidateHeightScale).toInt()

        if (mAdjustedButtonHeight != newAdjustedButtonHeight ||
            mAdjustedCandidateHeight != newAdjustedCandidateHeight ||
            mAdjustedCandidateTextSize != newAdjustedCandidateTextSize
        ) {
            mAdjustedButtonHeight = newAdjustedButtonHeight
            mAdjustedCandidateHeight = newAdjustedCandidateHeight
            mAdjustedCandidateTextSize = newAdjustedCandidateTextSize
            changed = (mKeyboardType != null)
        }

        mBtnExpandCandidates?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mAdjustedCandidateTextSize)
            if (mAdjustedCandidateHeight > 0) {
                minHeight = mAdjustedCandidateHeight
            }
        }
        if (mAdjustedCandidateHeight > 0) {
            mCandidateBarLayout?.minimumHeight = mAdjustedCandidateHeight
        }

        val currView = mCurrentKeyboardView
        if (currView != null) {
            val rowHeight = if (currView is EmojiKeyboardView && mAdjustedCandidateHeight > 0) {
                mAdjustedCandidateHeight
            } else {
                mAdjustedButtonHeight
            }
            currView.setRowHeight(rowHeight)
            currView.readPrefs()
        }

        if (mCandidateButton.isNullOrEmpty()) {
            hideCandidatesView()
        }

        val lastInfo = mLastEditorInfo
        if (changed && lastInfo != null) {
            doStartInputView(lastInfo, true)
        }
    }

    fun doStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        logI("doStartInputView: editorInfo=$editorInfo, restarting=$restarting")
        mLastEditorInfo = editorInfo

        mKeyboardLayout.removeAllViews()
        mCurrentKeyboardView = null

        var height = LayoutParams.WRAP_CONTENT

        if (PrefKeys.KEYBOARD_TYPE_NONE == mKeyboardType) {
            setBackgroundColor(Color.TRANSPARENT)
            mCandidateBarLayout?.visibility = GONE
            mCandidateExpandedScroll?.visibility = GONE
            mKeyboardLayout.visibility = GONE
            height = 1
        } else {
            background = null
            mCandidateExpandedScroll?.visibility = GONE
            mKeyboardLayout.visibility = VISIBLE

            when (mKeyboardType) {
                PrefKeys.KEYBOARD_TYPE_TABLET -> {
                    val tv = TabletKeyboardView(context)
                    tv.setRowHeight(mAdjustedButtonHeight)
                    tv.setOnKeyActionListener { config -> onClickKey(config) }
                    mCurrentKeyboardView = tv
                }
                PrefKeys.KEYBOARD_TYPE_QWERTY -> {
                    val qv = QwertyKeyboardView(context)
                    qv.setRowHeight(mAdjustedButtonHeight)
                    qv.setOnKeyActionListener { config -> onClickKey(config) }
                    mCurrentKeyboardView = qv
                }
                PrefKeys.KEYBOARD_TYPE_STROKE -> {
                    val sv = StrokeKeyboardView(context)
                    sv.setOnKeyActionListener { config -> onClickKey(config) }
                    sv.setOnHelpListener { mInputService?.showStrokeHelp() }
                    mCurrentKeyboardView = sv
                    val baseSize = Math.min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    height = (baseSize * mStrokeWidthScale).toInt() / 2
                }
                "emoji" -> {
                    val ev = EmojiKeyboardView(context)
                    ev.setOnKeyActionListener { config -> onClickKey(config) }
                    ev.setRowHeight(if (mAdjustedCandidateHeight > 0) mAdjustedCandidateHeight else mAdjustedButtonHeight)
                    mCurrentKeyboardView = ev
                    height = mAdjustedButtonHeight * 5
                }
                "symbols", null -> {
                    val syv = SymbolKeyboardView(context)
                    syv.setOnKeyActionListener { config -> onClickKey(config) }
                    mCurrentKeyboardView = syv
                    height = mAdjustedButtonHeight
                }
                else -> {
                    val syv = SymbolKeyboardView(context)
                    syv.setOnKeyActionListener { config -> onClickKey(config) }
                    mCurrentKeyboardView = syv
                    height = mAdjustedButtonHeight
                }
            }
        }

        val currView = mCurrentKeyboardView
        if (currView != null) {
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, height)
            if (currView is StrokeKeyboardView) {
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val baseSize = Math.min(screenWidth, screenHeight)
                val targetWidth = (baseSize * mStrokeWidthScale).toInt()
                lp.width = targetWidth
                lp.height = targetWidth / 2

                var gravity = Gravity.END
                if ("left" == mStrokeAlign) {
                    gravity = Gravity.START
                }
                lp.gravity = gravity
            } else if (currView is TabletKeyboardView) {
                val screenWidth = resources.displayMetrics.widthPixels
                val targetWidth = (screenWidth * mTabletWidthScale).toInt()
                lp.width = if (mTabletWidthScale >= 1.0f) LayoutParams.MATCH_PARENT else targetWidth

                var gravity = Gravity.END
                if ("left" == mTabletAlign) {
                    gravity = Gravity.START
                }
                lp.gravity = gravity
            }
            mKeyboardLayout.addView(currView, lp)
        }
        updateKeys()
        if (!restarting) {
            hideCandidatesView()
        } else {
            mInputService?.requestUIUpdate()
        }
    }

    fun showEmojiPicker() {
        mKeyboardLayout.removeAllViews()
        val ev = EmojiKeyboardView(context)
        ev.setOnKeyActionListener { config -> onClickKey(config) }
        ev.setOnCloseListener { hideEmojiPicker() }
        ev.setRowHeight(if (mAdjustedCandidateHeight > 0) mAdjustedCandidateHeight else mAdjustedButtonHeight)
        mCurrentKeyboardView = ev
        val height = mAdjustedButtonHeight * 5
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, height)
        mKeyboardLayout.addView(ev, lp)
        mKeyboardLayout.visibility = VISIBLE
    }

    fun hideEmojiPicker() {
        val lastInfo = mLastEditorInfo
        if (lastInfo != null) {
            doStartInputView(lastInfo, true)
        }
    }

    fun isEmojiPickerShowing(): Boolean {
        return mCurrentKeyboardView is EmojiKeyboardView
    }

    fun updateKeys(modeText: String, modeIconResId: Int) {
        mModeText = modeText
        mModeIconResId = modeIconResId
        mCurrentKeyboardView?.updateState(
            KeyboardState(mIsShifted, mIsShiftLocked, mIsControl, mIsSymbol, mIsSymbolLocked, mModeText, mModeIconResId)
        )
    }

    @Deprecated("Use updateKeys(modeText, modeIconResId) instead.")
    fun updateKeys(modeText: String) {
        updateKeys(modeText, mModeIconResId)
    }

    fun updateKeys() {
        updateKeys(mModeText, mModeIconResId)
    }

    private fun resetModifiers() {
        var changed = false
        if (!mIsShiftLocked && mIsShifted) {
            mIsShifted = false
            changed = true
        }
        if (!mIsSymbolLocked && mIsSymbol) {
            mIsSymbol = false
            changed = true
        }
        if (mIsControl) {
            mIsControl = false
            changed = true
        }
        if (changed) {
            updateKeys()
        }
    }

    private fun onClickKey(config: KeyConfig) {
        logI("onClickKey: label=${config.label}, code=${config.code}, shift=$mIsShifted")
        if (config.code != KeyConfig.CODE_NONE) {
            when (config.code) {
                KeyConfig.CODE_SHIFT -> {
                    if (mIsShiftLocked) {
                        mIsShiftLocked = false
                        mIsShifted = false
                    } else if (mIsShifted) {
                        mIsShiftLocked = true
                    } else {
                        mIsShifted = true
                    }
                    mIsControl = false
                    mIsSymbol = false
                    mIsSymbolLocked = false
                    updateKeys()
                }
                KeyConfig.CODE_CTRL -> {
                    mIsControl = !mIsControl
                    mIsShifted = mIsShiftLocked && !mIsControl
                    mIsSymbol = false
                    mIsSymbolLocked = false
                    updateKeys()
                }
                KeyConfig.CODE_SYM -> {
                    if (mIsSymbolLocked) {
                        mIsSymbolLocked = false
                        mIsSymbol = false
                    } else if (mIsSymbol) {
                        mIsSymbolLocked = true
                    } else {
                        mIsSymbol = true
                    }
                    mIsShifted = mIsShiftLocked && !mIsSymbol
                    mIsControl = false
                    updateKeys()
                }
                KeyConfig.CODE_ABC -> {
                    mIsSymbol = false
                    mIsSymbolLocked = false
                    mIsControl = false
                    mIsShifted = mIsShiftLocked
                    updateKeys()
                }
                KeyConfig.CODE_MODE -> {
                    mInputService?.toggleEnglishJapanese()
                    resetModifiers()
                }
                KeyConfig.CODE_TAB -> {
                    if (mIsControl) {
                        mInputService?.handleCtrlKey(KeyEvent.KEYCODE_TAB)
                    } else {
                        mInputService?.handleTab(mIsShifted)
                    }
                    resetModifiers()
                }
                KeyConfig.CODE_ESC -> {
                    mInputService?.handleEscape()
                    resetModifiers()
                }
                KeyConfig.CODE_LEFT -> {
                    mInputService?.handleDpadLeft(mIsShifted)
                    resetModifiers()
                }
                KeyConfig.CODE_RIGHT -> {
                    mInputService?.handleDpadRight(mIsShifted)
                    resetModifiers()
                }
                KeyConfig.CODE_UP -> {
                    mInputService?.handleDpadUp(mIsShifted)
                    resetModifiers()
                }
                KeyConfig.CODE_DOWN -> {
                    mInputService?.handleDpadDown(mIsShifted)
                    resetModifiers()
                }
                KeyConfig.CODE_BACKSPACE -> {
                    if (mIsControl) {
                        mInputService?.handleCtrlKey(KeyEvent.KEYCODE_DEL)
                    } else {
                        mInputService?.handleBackspace()
                    }
                    resetModifiers()
                }
                KeyConfig.CODE_ENTER -> {
                    if (mIsControl) {
                        mInputService?.handleCtrlKey(KeyEvent.KEYCODE_ENTER)
                    } else {
                        mInputService?.handleEnter()
                    }
                    resetModifiers()
                }
                KeyConfig.CODE_SPACE -> {
                    if (mIsControl) {
                        mInputService?.handleCtrlKey(KeyEvent.KEYCODE_SPACE)
                    } else {
                        mInputService?.processKey(' '.code)
                    }
                    resetModifiers()
                }
            }
        } else {
            val key = config.label
            if (key.isNotEmpty()) {
                if (mCurrentKeyboardView is EmojiKeyboardView) {
                    mInputService?.commitText(key, 1)
                    resetModifiers()
                } else if (key.length == 1) {
                    if (mIsControl) {
                        val keyCode = getKeyCode(key[0])
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            mInputService?.handleCtrlKey(keyCode)
                        }
                        mIsControl = false
                        mIsShifted = mIsShiftLocked
                        updateKeys()
                    } else {
                        var c = key[0]
                        if (mIsShifted) {
                            c = c.uppercaseChar()
                        }
                        mInputService?.processKey(c.code)
                        resetModifiers()
                    }
                } else {
                    mInputService?.commitText(key, 1)
                    resetModifiers()
                }
            }
        }
    }

    private fun getKeyCode(c: Char): Int {
        val lower = Character.toLowerCase(c)
        if (lower in 'a'..'z') {
            return KeyEvent.KEYCODE_A + (lower - 'a')
        }
        return when (lower) {
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '/' -> KeyEvent.KEYCODE_SLASH
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun onClickCandidateButton(v: View) {
        if (mHapticEnabled) {
            v.isHapticFeedbackEnabled = true
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        val index = v.tag as Int
        mInputService?.pickCandidateViewManually(index)
    }

    private fun toggleExpanded() {
        mIsExpanded = !mIsExpanded
        updateExpansionState()
    }

    private fun updateExpansionState() {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val expectedKeyboardHeight: Int = if ("stroke" == mKeyboardType) {
            val baseSize = Math.min(screenWidth, screenHeight)
            (baseSize * mStrokeWidthScale).toInt() / 2
        } else {
            mAdjustedButtonHeight * mRowCount
        }

        if (mIsExpanded) {
            mKeyboardLayout.visibility = GONE
            val scroll = mCandidateExpandedScroll
            if (scroll != null) {
                val lp = scroll.layoutParams
                lp.height = expectedKeyboardHeight
                scroll.layoutParams = lp
                scroll.visibility = VISIBLE

                scroll.post {
                    val buttons = mCandidateButton
                    val flexbox = mCandidateExpandedFlexbox
                    if (buttons != null && flexbox != null) {
                        var selectedIdx = 0
                        for (i in buttons.indices) {
                            val b = buttons[i]
                            if (b != null && b.isSelected) {
                                selectedIdx = i
                                break
                            }
                        }
                        val selectedChip = flexbox.getChildAt(selectedIdx)
                        if (selectedChip != null) {
                            val chipTop = selectedChip.top + flexbox.top
                            val density = resources.displayMetrics.density
                            scroll.scrollTo(0, Math.max(0, chipTop - (8 * density).toInt()))
                        }
                    }
                }
            }
            mBtnExpandCandidates?.text = "▲"
        } else {
            mKeyboardLayout.visibility = if (PrefKeys.KEYBOARD_TYPE_NONE == mKeyboardType) GONE else VISIBLE
            mCandidateExpandedScroll?.visibility = GONE
            mBtnExpandCandidates?.text = "▼"
        }
        requestLayout()
    }

    private fun clearCandidates() {
        hideExcessCandidateButtons(mCandidatesLayout, 0)
        mCandidateExpandedFlexbox?.let { hideExcessCandidateButtons(it, 0) }
        mCandidateButton = null
    }

    private fun bindCandidateButton(container: ViewGroup, index: Int, text: CharSequence, isUserDict: Boolean): Button {
        val b: Button
        if (index < container.childCount) {
            b = container.getChildAt(index) as Button
        } else {
            b = LayoutInflater.from(context).inflate(R.layout.candidate_inputview_chip_item, container, false) as Button
            if (mDefaultCandidateTextColorStateList == null) {
                mDefaultCandidateTextColorStateList = b.textColors
            }
            container.addView(b)
        }

        b.visibility = VISIBLE
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, mAdjustedCandidateTextSize)
        if (mAdjustedCandidateHeight > 0) {
            b.minHeight = mAdjustedCandidateHeight
        }
        b.text = text
        if (isUserDict) {
            b.setTextColor(ContextCompat.getColor(context, R.color.candidate_user_dict_fg))
        } else {
            mDefaultCandidateTextColorStateList?.let { b.setTextColor(it) }
        }
        b.setOnClickListener { v -> onClickCandidateButton(v) }
        b.tag = index
        b.isSelected = false
        return b
    }

    private fun hideExcessCandidateButtons(container: ViewGroup, targetCount: Int) {
        for (i in targetCount until container.childCount) {
            container.getChildAt(i).visibility = GONE
        }
    }

    fun setCandidates(candidates: List<String>?, selectFirst: Boolean = true) {
        if (candidates.isNullOrEmpty()) {
            clearCandidates()
            return
        }

        val buttons = arrayOfNulls<Button>(candidates.size)

        for (i in candidates.indices) {
            val text = candidates[i]

            val b = bindCandidateButton(mCandidatesLayout, i, text, false)
            buttons[i] = b

            val flexbox = mCandidateExpandedFlexbox
            if (flexbox != null) {
                bindCandidateButton(flexbox, i, text, false)
            }
        }

        hideExcessCandidateButtons(mCandidatesLayout, candidates.size)
        val flexbox = mCandidateExpandedFlexbox
        if (flexbox != null) {
            hideExcessCandidateButtons(flexbox, candidates.size)
        }

        mCandidateButton = buttons

        if (selectFirst) {
            selectCandidate(0)
        } else {
            selectCandidate(-1)
        }
    }

    fun setCandidateObjects(candidates: List<Candidate>?, selectFirst: Boolean = true) {
        if (candidates.isNullOrEmpty()) {
            clearCandidates()
            return
        }

        val filtered = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (c in candidates) {
            val label = c.candidate ?: ""
            if (seen.add(label)) {
                filtered.add(c)
            }
        }

        val buttons = arrayOfNulls<Button>(filtered.size)

        for (i in filtered.indices) {
            val c = filtered[i]
            val formatted = c.getFormattedText(c.candidate ?: "")
            val isLearning = c.isLearningWord()

            val b = bindCandidateButton(mCandidatesLayout, i, formatted, isLearning)
            buttons[i] = b

            val flexbox = mCandidateExpandedFlexbox
            if (flexbox != null) {
                bindCandidateButton(flexbox, i, formatted, isLearning)
            }
        }

        hideExcessCandidateButtons(mCandidatesLayout, filtered.size)
        val flexbox = mCandidateExpandedFlexbox
        if (flexbox != null) {
            hideExcessCandidateButtons(flexbox, filtered.size)
        }

        mCandidateButton = buttons

        if (selectFirst) {
            selectCandidate(0)
        } else {
            selectCandidate(-1)
        }
    }

    fun selectCandidate(index: Int) {
        val buttons = mCandidateButton ?: return
        if (index < 0) {
            for (button in buttons) {
                button?.isSelected = false
            }
            return
        }
        if (index >= buttons.size) {
            return
        }
        mCandidatesView.post {
            for (button in buttons) {
                button?.isSelected = false
            }
            val selectedButton = buttons[index] ?: return@post
            selectedButton.isSelected = true

            val buttonLeft = selectedButton.left
            val buttonWidth = selectedButton.width
            val viewWidth = mCandidatesView.width
            val scrollToX = buttonLeft + (buttonWidth / 2) - (viewWidth / 2)
            mCandidatesView.scrollTo(scrollToX, 0)

            val flexbox = mCandidateExpandedFlexbox
            val scroll = mCandidateExpandedScroll
            if (flexbox != null && scroll != null) {
                for (i in 0..<flexbox.childCount) {
                    val child = flexbox.getChildAt(i)
                    child.isSelected = (i == index)
                }
                val selectedChip = flexbox.getChildAt(index)
                if (selectedChip != null) {
                    val density = resources.displayMetrics.density
                    val padding = (8 * density).toInt()
                    val chipTop = selectedChip.top + flexbox.top
                    val chipBottom = chipTop + selectedChip.height

                    val scrollY = scroll.scrollY
                    val viewHeight = scroll.height

                    if (viewHeight > 0) {
                        if (chipTop < scrollY + padding) {
                            scroll.smoothScrollTo(0, Math.max(0, chipTop - padding))
                        } else if (chipBottom > scrollY + viewHeight - padding) {
                            scroll.smoothScrollTo(0, chipBottom - viewHeight + padding)
                        }
                    }
                }
            }
        }
    }

    fun showCandidatesView() {
        if (mInputService != null && mInputService.isFloatingCandidateEnabled()) {
            mCandidateBarLayout?.visibility = GONE
            mCandidatesView.visibility = GONE
            mBtnExpandCandidates?.visibility = GONE
            mCandidateExpandedScroll?.visibility = GONE
            mKeyboardLayout.visibility = if (PrefKeys.KEYBOARD_TYPE_NONE == mKeyboardType) GONE else VISIBLE
            return
        }
        mCandidateBarLayout?.visibility = VISIBLE
        mCandidatesView.visibility = VISIBLE
        mBtnExpandCandidates?.visibility = VISIBLE
        updateExpansionState()
    }

    fun hideCandidatesView() {
        mIsExpanded = false
        val showBarWhenEmpty = "none" != mKeyboardType
                && (mInputService == null || !mInputService.isFloatingCandidateEnabled())

        val barVisibility = if (showBarWhenEmpty) VISIBLE else GONE
        mCandidateBarLayout?.visibility = barVisibility
        mCandidatesView.visibility = barVisibility
        mBtnExpandCandidates?.visibility = GONE
        mCandidateExpandedScroll?.visibility = GONE

        mKeyboardLayout.visibility = if (PrefKeys.KEYBOARD_TYPE_NONE == mKeyboardType) GONE else VISIBLE
        updateExpansionState()
    }

    fun hideFloatingCandidates() {
        hideFullWidthCandidatePopup()
    }

    fun hideFullWidthCandidatePopup() {
        val popup = mCandidatePopup
        if (popup != null && popup.isShowing) {
            popup.dismiss()
        }
        mLastPopupY = -1
        mLastPopupHeight = -1
    }

    fun updateCandidatePopupPosition() {
        val popup = mCandidatePopup
        if (popup == null || !popup.isShowing) {
            return
        }
        if (mInputService != null && mInputService.isCursorInvisible) {
            hideFullWidthCandidatePopup()
            return
        }

        val contentView = popup.contentView ?: return
        val coordsAndSize = calculateCandidatePopupScreenCoordinates(contentView)
        val targetX = coordsAndSize[0]
        val targetY = coordsAndSize[1]
        val height = coordsAndSize[2]

        if (targetY != mLastPopupY || height != mLastPopupHeight) {
            mLastPopupY = targetY
            mLastPopupHeight = height
            showPopupAtScreenLocationWithSize(
                popup, targetX, targetY,
                resources.displayMetrics.widthPixels, height
            )
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun getStatusBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(this)
        if (insets != null) {
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            if (statusBars.top > 0) {
                return statusBars.top
            }
        }
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

    private fun calculateTopMaxHeight(contentView: View): Int {
        val header = contentView.findViewById<View>(R.id.candidate_header)
        val headerHeight = if (header != null && header.visibility == VISIBLE) header.measuredHeight else 0

        val flexbox = contentView.findViewById<FlexboxLayout>(R.id.candidate_flexbox)
        var rowHeight = 0
        if (flexbox != null && flexbox.childCount > 0) {
            for (i in 0..<flexbox.childCount) {
                val child = flexbox.getChildAt(i)
                val h = child.measuredHeight
                if (h > rowHeight) {
                    rowHeight = h
                }
            }
            if (rowHeight == 0) {
                rowHeight = (40 * resources.displayMetrics.density).toInt()
            }
            val firstChild = flexbox.getChildAt(0)
            if (firstChild != null && firstChild.layoutParams is MarginLayoutParams) {
                val lp = firstChild.layoutParams as MarginLayoutParams
                rowHeight += lp.topMargin + lp.bottomMargin
            } else {
                rowHeight += (6 * resources.displayMetrics.density).toInt()
            }
        } else {
            rowHeight = (44 * resources.displayMetrics.density).toInt()
        }

        return headerHeight + (4 * rowHeight) + (16 * resources.displayMetrics.density).toInt()
    }

    private fun calculateCandidatePopupScreenCoordinates(contentView: View): IntArray {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val margin = (2 * density).toInt()

        var keyboardTop = screenHeight
        if (isShown) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            if (loc[1] > 0) {
                keyboardTop = loc[1]
            }
        }

        val cursorTop = mInputService?.cursorTop ?: 0f
        val cursorBottom = mInputService?.cursorBottom ?: 0f

        if ((cursorTop == 0f && cursorBottom == 0f) || java.lang.Float.isNaN(cursorTop) || java.lang.Float.isNaN(cursorBottom)) {
            contentView.measure(
                MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.EXACTLY),
                MeasureSpec.UNSPECIFIED
            )
            val measuredContentHeight = contentView.measuredHeight
            val statusBarHeight = getStatusBarHeight()
            val maxAllowedHeight = Math.max((40 * density).toInt(), keyboardTop - statusBarHeight - margin)
            val finalPopupHeight = Math.min(measuredContentHeight, maxAllowedHeight)

            val targetX = 0
            val targetY = Math.max(statusBarHeight, keyboardTop - finalPopupHeight - margin)
            return intArrayOf(targetX, targetY, finalPopupHeight)
        }

        val caretTop = Math.min(cursorTop, cursorBottom)
        val caretBottom = Math.max(cursorTop, cursorBottom)

        val statusBarHeight = getStatusBarHeight()
        val spaceTop = Math.max(0, caretTop.toInt() - statusBarHeight - margin)
        val spaceBottom = Math.max(0, keyboardTop - caretBottom.toInt() - margin)

        val useTop = (spaceTop >= spaceBottom)
        val chosenSpace = if (useTop) spaceTop else spaceBottom

        contentView.measure(
            MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.EXACTLY),
            MeasureSpec.UNSPECIFIED
        )
        val measuredContentHeight = contentView.measuredHeight

        val allowedMaxHeight = if (useTop) {
            val max4LinesHeight = calculateTopMaxHeight(contentView)
            Math.min(chosenSpace, max4LinesHeight)
        } else {
            Math.max((40 * density).toInt(), chosenSpace)
        }

        val finalPopupHeight = Math.min(measuredContentHeight, allowedMaxHeight)

        val targetX = 0
        var targetY = if (useTop) {
            caretTop.toInt() - finalPopupHeight - margin
        } else {
            caretBottom.toInt() + margin
        }

        targetY = Math.max(statusBarHeight, Math.min(targetY, Math.max(statusBarHeight, keyboardTop - finalPopupHeight)))

        return intArrayOf(targetX, targetY, finalPopupHeight)
    }

    private fun showPopupAtScreenLocationWithSize(popup: PopupWindow, screenX: Int, screenY: Int, width: Int, height: Int) {
        var anchor: View? = rootView
        if (anchor == null) anchor = this
        if (anchor.windowToken == null) return

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)

        val x = screenX - loc[0]
        val y = screenY - loc[1]

        try {
            if (popup.isShowing) {
                popup.update(x, y, width, height)
            } else {
                popup.isClippingEnabled = false
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        } catch (_: Exception) {
        }
    }

    fun showCandidateFlexboxPopup(header: String?, items: List<String>?, selectedIdx: Int) {
        val screenWidth = resources.displayMetrics.widthPixels

        if (mCandidatePopup == null) {
            val inflater = LayoutInflater.from(context)
            val popupView = inflater.inflate(R.layout.candidate_floating, null)
            mCandidatePopup = PopupWindow(popupView, screenWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                isFocusable = false
                animationStyle = 0
            }
        }

        val popup = mCandidatePopup!!
        val finalPopupView = popup.contentView!!
        val headerView = finalPopupView.findViewById<TextView>(R.id.candidate_header)
        val flexbox = finalPopupView.findViewById<FlexboxLayout>(R.id.candidate_flexbox)

        if (headerView != null) {
            if (!header.isNullOrEmpty()) {
                headerView.visibility = VISIBLE
                headerView.text = header
            } else {
                headerView.visibility = GONE
            }
        }

        if (flexbox != null && !items.isNullOrEmpty()) {
            val count = items.size
            for (i in items.indices) {
                val chip: TextView
                if (i < flexbox.childCount) {
                    chip = flexbox.getChildAt(i) as TextView
                } else {
                    val inflater = LayoutInflater.from(context)
                    chip = inflater.inflate(R.layout.candidate_floating_chip_item, flexbox, false) as TextView
                    flexbox.addView(chip)
                }
                chip.visibility = VISIBLE
                chip.text = items[i]
                val index = i
                chip.setOnClickListener {
                    mInputService?.pickCandidateViewManually(index)
                }
                chip.isSelected = (i == selectedIdx)
            }

            for (i in count until flexbox.childCount) {
                flexbox.getChildAt(i).visibility = GONE
            }

            scrollToSelectedChip(finalPopupView, flexbox, selectedIdx)
        }

        val coordsAndSize = calculateCandidatePopupScreenCoordinates(finalPopupView)
        mLastPopupY = coordsAndSize[1]
        mLastPopupHeight = coordsAndSize[2]
        showPopupAtScreenLocationWithSize(popup, coordsAndSize[0], coordsAndSize[1], screenWidth, coordsAndSize[2])
    }

    private fun scrollToSelectedChip(popupView: View, flexbox: FlexboxLayout, selectedIdx: Int) {
        if (selectedIdx < 0 || selectedIdx >= flexbox.childCount) {
            return
        }
        val scroll = popupView.findViewById<ScrollView>(R.id.candidate_scroll)
        val selectedChip = flexbox.getChildAt(selectedIdx)
        if (scroll != null && selectedChip != null) {
            val density = resources.displayMetrics.density
            val padding = (8 * density).toInt()

            val chipTop = selectedChip.top + flexbox.top
            val chipBottom = chipTop + selectedChip.height

            val scrollY = scroll.scrollY
            val viewHeight = scroll.height

            if (viewHeight <= 0) {
                scroll.post { scrollToSelectedChip(popupView, flexbox, selectedIdx) }
                return
            }

            if (chipTop - padding < scrollY) {
                scroll.smoothScrollTo(0, Math.max(0, chipTop - padding))
            } else if (chipBottom + padding > (scrollY + viewHeight)) {
                scroll.smoothScrollTo(0, chipBottom + padding - viewHeight)
            }
        }
    }
}
