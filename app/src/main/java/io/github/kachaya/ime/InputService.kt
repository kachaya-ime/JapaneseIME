package io.github.kachaya.ime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import io.github.kachaya.ime.SettingsActivity.PrefKeys
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.engine.InputEngine
import io.github.kachaya.ime.engine.InputModeFullHiragana
import io.github.kachaya.ime.engine.InputModeFullKatakana
import io.github.kachaya.ime.engine.InputModeFullLatin
import io.github.kachaya.ime.engine.InputModeHalfKatakana
import io.github.kachaya.ime.engine.InputModeHalfLatin


/**
 * 日本語入力メソッドのメインサービス実装です。
 */
class InputService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val mHideHandler = Handler(Looper.getMainLooper())
    private val mForwardedCtrlKeys = HashSet<Int>()

    private var mEngine: InputEngine? = null
    private var mDictionary: Dictionary? = null
    private var mInputView: InputView? = null
    private var mPrefs: SharedPreferences? = null

    private var mAutoAsciiMode = false

    private var mTooltipDuration = 1000
    private var mTooltipPosition = "top"
    private var mStartupMode = "japanese"

    private var mIsInputTypeNull = false
    var isCursorInvisible = false
        private set
    private var mShowStatusBarIcon = true

    private var isEnterUsed = false
    private var mHasComposingText = false

    private var mCursorHorizontal = 0f
    var cursorTop = 0f
        private set
    var cursorBottom = 0f
        private set

    private var mNeedsTooltipUpdate = false
    private var mTooltipPopup: PopupWindow? = null
    private var mHelpPopup: PopupWindow? = null

    private val mHideRunnable = Runnable {
        mTooltipPopup?.dismiss()
    }
    private val mShowTooltipRunnable = Runnable {
        requestUIUpdate()
    }

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(javaClass.simpleName, msg)
        }
    }

    private fun logW(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.w(javaClass.simpleName, msg)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mEngine?.apply {
            updateColors()
            requestUIUpdate()
        }
        mInputView = null
        setInputView(onCreateInputView())
        mTooltipPopup?.let {
            if (it.isShowing) {
                it.dismiss()
            }
            mTooltipPopup = null
        }
    }

    override fun onCreate() {
        logI("onCreate()")
        super.onCreate()

        setupDefaultPreferences(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        mPrefs = prefs
        val dict = Dictionary(this)
        mDictionary = dict
        mEngine = InputEngine(this, dict)

        readPrefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onInitializeInterface() {
        logI("onInitializeInterface()")
        super.onInitializeInterface()
    }

    override fun onCreateInputView(): View {
        logI("onCreateInputView()")
        var view = mInputView
        if (view == null) {
            view = InputView(this, this, getThemedContext(this))
            mInputView = view
        } else {
            val parent = view.parent as? ViewGroup
            parent?.removeView(view)
        }
        return view
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        logI("onStartInput: Cursor Start=${attribute.initialSelStart}, Cursor End=${attribute.initialSelEnd}")
        logI("onStartInput: restarting=$restarting")
        super.onStartInput(attribute, restarting)

        readPrefs()

        resetCursorState()
        mNeedsTooltipUpdate = false

        val ic = currentInputConnection
        if (ic != null) {
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
        } else {
            logW("InputConnection is null")
        }

        mIsInputTypeNull = (attribute.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL

        val engine = mEngine
        if (!restarting && engine != null) {
            mHasComposingText = false
            engine.resetOnStartInput()

            when (attribute.inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_DATETIME,
                InputType.TYPE_CLASS_PHONE -> engine.toASCIIMode()
                InputType.TYPE_CLASS_TEXT -> {
                    when (attribute.inputType and InputType.TYPE_MASK_VARIATION) {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_FILTER -> engine.toASCIIMode()
                        InputType.TYPE_TEXT_VARIATION_URI -> {
                            if (mAutoAsciiMode || "english" == mStartupMode) {
                                engine.toASCIIMode()
                            } else {
                                engine.changeMode(InputModeFullHiragana.INSTANCE, false)
                            }
                        }
                        else -> {
                            if ("english" == mStartupMode) {
                                engine.toASCIIMode()
                            } else {
                                engine.changeMode(InputModeFullHiragana.INSTANCE, false)
                            }
                        }
                    }
                }
                InputType.TYPE_NULL -> {}
                else -> {
                    if ("english" == mStartupMode) {
                        engine.toASCIIMode()
                    } else {
                        engine.changeMode(InputModeFullHiragana.INSTANCE, false)
                    }
                }
            }
        } else if (restarting) {
            logI("onStartInput: restarting session, preserving state.")
        }

        updateStatusIcon()
        updateInputViewShown()

        if (!mIsInputTypeNull) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                requestShowSelf(0)
            } else {
                showWindow(true)
            }
        } else {
            requestHideSelf(0)
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        logI("onStartInputView: restarting=$restarting")
        super.onStartInputView(editorInfo, restarting)
        val view = mInputView
        if (view != null) {
            view.readPrefs()
            view.doStartInputView(editorInfo, restarting)
            if (restarting) {
                requestUIUpdate()
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        logI("onFinishInputView: finishingInput=$finishingInput")
        dismissStatusUI()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        logI("onFinishInput()")
        super.onFinishInput()
        dismissStatusUI()
        hideCandidatesView()
        requestHideSelf(0)
    }

    override fun onWindowHidden() {
        logI("onWindowHidden()")
        super.onWindowHidden()
        dismissStatusUI()
    }

    override fun onWindowShown() {
        logI("onWindowShown()")
        super.onWindowShown()
        readPrefs()

        val ic = currentInputConnection
        ic?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)

        mHideHandler.postDelayed(mShowTooltipRunnable, 1000)
    }

    override fun onDestroy() {
        logI("onDestroy()")
        mPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        mDictionary?.close()
        mEngine?.zenzEngine?.release()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (cursorAnchorInfo == null) {
            return
        }

        val flags = cursorAnchorInfo.insertionMarkerFlags
        val wasInvisible = isCursorInvisible
        isCursorInvisible = (flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION) != 0 &&
                (flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION) == 0

        var horizontal = cursorAnchorInfo.insertionMarkerHorizontal
        var top = cursorAnchorInfo.insertionMarkerTop
        var bottom = cursorAnchorInfo.insertionMarkerBottom

        if (horizontal.isNaN() || top.isNaN() || bottom.isNaN()) {
            val selStart = cursorAnchorInfo.selectionStart
            val compStart = cursorAnchorInfo.composingTextStart
            val charIndex = if (compStart >= 0) compStart else selStart
            if (charIndex >= 0) {
                val bounds = cursorAnchorInfo.getCharacterBounds(charIndex)
                if (bounds != null) {
                    horizontal = bounds.left
                    top = bounds.top
                    bottom = bounds.bottom
                }
            }
        }

        val matrix = cursorAnchorInfo.matrix
        if (matrix != null && !horizontal.isNaN() && !top.isNaN() && !bottom.isNaN()) {
            val points = floatArrayOf(horizontal, top, horizontal, bottom)
            matrix.mapPoints(points)
            if (!points[0].isNaN() && !points[1].isNaN() && !points[3].isNaN()) {
                mCursorHorizontal = points[0]
                cursorTop = points[1]
                cursorBottom = points[3]
            }
        }

        if (wasInvisible != isCursorInvisible) {
            updateStatusIcon()
        }

        updateTooltipPosition()
        mInputView?.updateCandidatePopupPosition()

        performTooltipUpdate()
    }

    private fun resetCursorState() {
        mCursorHorizontal = 0f
        cursorTop = 0f
        cursorBottom = 0f
        isCursorInvisible = false
    }

    private fun updateTooltipPosition() {
        val popup = mTooltipPopup
        if (popup == null || !popup.isShowing) {
            return
        }
        if (isCursorInvisible) {
            popup.dismiss()
            return
        }

        val contentView = popup.contentView ?: return
        val coords = calculateTooltipScreenCoordinates(contentView)
        showPopupAtScreenLocation(popup, coords[0], coords[1])
    }

    private fun setTooltipViewText(popupContentView: View?, text: String) {
        if (popupContentView == null) return
        val tv = popupContentView.findViewById<TextView>(R.id.tooltip_text)
        if (tv != null) {
            tv.text = text
        } else if (popupContentView is TextView) {
            popupContentView.text = text
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun getStatusBarHeight(): Int {
        val view = mInputView
        if (view != null) {
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets != null) {
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                if (statusBars.top > 0) {
                    return statusBars.top
                }
            }
        }
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        return 0
    }

    private fun calculateTooltipScreenCoordinates(contentView: View): IntArray {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val density = resources.displayMetrics.density
        val popupWidth = contentView.measuredWidth
        val popupHeight = contentView.measuredHeight
        val margin = (2 * density).toInt()

        var keyboardTop = screenHeight
        val view = mInputView
        if (view != null && view.isShown) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            if (loc[1] > 0) {
                keyboardTop = loc[1]
            }
        }

        if ((cursorTop == 0f && cursorBottom == 0f) || cursorTop.isNaN() || cursorBottom.isNaN()) {
            val statusBarHeight = getStatusBarHeight()
            val targetX = Math.max(0, (screenWidth - popupWidth) / 2)
            val targetY = Math.max(statusBarHeight, keyboardTop - popupHeight - margin)
            return intArrayOf(targetX, targetY)
        }

        var targetX = mCursorHorizontal.toInt() - (popupWidth / 2)
        targetX = Math.max(0, Math.min(targetX, screenWidth - popupWidth))

        val caretTop = Math.min(cursorTop, cursorBottom)
        val caretBottom = Math.max(cursorTop, cursorBottom)

        val topY = caretTop.toInt() - popupHeight - margin
        val bottomY = caretBottom.toInt() + margin
        var targetY: Int

        if ("top" == mTooltipPosition) {
            targetY = if (topY < 0 && (bottomY + popupHeight) <= keyboardTop) {
                bottomY
            } else {
                topY
            }
        } else {
            targetY = if ((bottomY + popupHeight) > keyboardTop && topY >= 0) {
                topY
            } else {
                bottomY
            }
        }

        val statusBarHeight = getStatusBarHeight()
        val maxAllowedY = Math.max(statusBarHeight, keyboardTop - popupHeight)
        targetY = Math.max(statusBarHeight, Math.min(targetY, maxAllowedY))

        return intArrayOf(targetX, targetY)
    }

    private fun getAnchorView(): View? {
        val view = mInputView
        if (view != null && view.windowToken != null) {
            return view.rootView
        }
        try {
            val dialog: Dialog? = window
            val win = dialog?.window
            if (win != null) {
                val decor = win.decorView
                if (decor.windowToken != null) {
                    return decor
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun showPopupAtScreenLocation(popup: PopupWindow, screenX: Int, screenY: Int) {
        val anchor = getAnchorView() ?: return
        if (anchor.windowToken == null) return

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)

        val x = screenX - loc[0]
        val y = screenY - loc[1]

        try {
            if (popup.isShowing) {
                popup.update(x, y, -1, -1)
            } else {
                popup.isClippingEnabled = false
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) {
            logI("Failed to show/update popup: ${e.message}")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        logI("onSharedPreferenceChanged: key=$key")
        if ("keyboard_theme" == key) {
            mInputView = null
            setInputView(onCreateInputView())
        }
        if ("keyboard_type" == key) {
            updateInputViewShown()
        }
        readPrefs()
    }

    private fun isInputDisabled(): Boolean {
        return mIsInputTypeNull || currentInputConnection == null
    }

    private fun readPrefs() {
        val prefs = mPrefs ?: PreferenceManager.getDefaultSharedPreferences(this)
        mTooltipDuration = prefs.getInt(PrefKeys.TOOLTIP_DURATION_SEC, 1) * 1000
        mTooltipPosition = prefs.getString(PrefKeys.TOOLTIP_POSITION, "top") ?: "top"
        mAutoAsciiMode = prefs.getBoolean(PrefKeys.AUTO_ASCII_MODE, false)
        mStartupMode = prefs.getString(PrefKeys.STARTUP_MODE, "japanese") ?: "japanese"
        mShowStatusBarIcon = prefs.getBoolean(PrefKeys.SHOW_STATUS_BAR_ICON, true)

        updateStatusIcon()

        mDictionary?.reloadLearningDictionary()
        mEngine?.readPrefs()
        mInputView?.readPrefs()
    }

    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown(): Boolean {
        return !mIsInputTypeNull
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        logI("onShowInputRequested: flags=$flags, configChange=$configChange")
        return !mIsInputTypeNull
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isInputDisabled()) {
            return super.onKeyUp(keyCode, event)
        }

        val engine = mEngine
        if (engine != null && engine.ignoresKeyEvent()) {
            return super.onKeyUp(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (event.isCtrlPressed || (event.metaState and KeyEvent.META_CTRL_ON) != 0) {
                    return super.onKeyUp(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (isEnterUsed) {
                    isEnterUsed = false
                    return true
                }
            }
        }

        if (event.isCtrlPressed && mForwardedCtrlKeys.contains(keyCode)) {
            mForwardedCtrlKeys.remove(keyCode)
            val ic = currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(event)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, count: Int, event: KeyEvent): Boolean {
        logI("onKeyMultiple: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode) ?: keyCode}), count=$count")
        return super.onKeyMultiple(keyCode, count, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logI("onKeyDown: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode) ?: keyCode}), event=$event")
        if (isInputDisabled()) {
            return super.onKeyDown(keyCode, event)
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_RED) {
            logI("Emoji key matched: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode) ?: keyCode})")
            toggleEmojiPicker()
            return true
        }

        val engine = mEngine

        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || keyCode == KeyEvent.KEYCODE_ZENKAKU_HANKAKU) {
            logI("Toggle En/Jp key matched: keyCode=$keyCode")
            if (engine != null) {
                engine.toggleEnglishJapanese()
                return true
            }
        }

        if (event.isCtrlPressed || (event.metaState and KeyEvent.META_CTRL_ON) != 0) {
            if (engine != null && engine.processShortcutKey(keyCode)) {
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (engine == null || !engine.handleBackspace()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                return super.onKeyDown(keyCode, event)
            }
            val ic = currentInputConnection
            if (ic != null) {
                mForwardedCtrlKeys.add(keyCode)
                ic.sendKeyEvent(event)
                return true
            }
        }

        if (!event.isCtrlPressed && ((event.metaState and KeyEvent.META_SHIFT_ON) != 0 || event.isShiftPressed)) {
            if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_DEL) {
                if (engine != null && engine.processShortcutKey(keyCode, true)) {
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_TAB) {
            val isShifted = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
            if (engine != null && engine.processTab(isShifted)) {
                return true
            }
        }

        if (engine != null && engine.ignoresKeyEvent()) {
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                if (engine != null && engine.handleCancel()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (engine != null && engine.handleBackKey()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DEL -> {
                if (engine != null && engine.handleBackspace()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (engine != null && engine.handleEnter()) {
                    isEnterUsed = true
                    return true
                }
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (engine != null && engine.handleSpace(event.isShiftPressed)) {
                    return true
                }
                processKey(' '.code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (engine != null && engine.handleDpadUp(event.isShiftPressed)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (engine != null && engine.handleDpadDown(event.isShiftPressed)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (engine != null && engine.handleDpadLeft(event.isShiftPressed)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (engine != null && engine.handleDpadRight(event.isShiftPressed)) {
                    return true
                }
            }
            else -> {
                if (translateKeyDown(event)) {
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun translateKeyDown(event: KeyEvent): Boolean {
        val meta = event.metaState
        val c = event.getUnicodeChar(meta)

        val ic = currentInputConnection
        if (c == 0 || ic == null) {
            return false
        }

        val ch = c.toChar()
        if (!Character.isLetter(c) && !Character.isDigit(c) && ch != '.' && ch != ',' && ch != '/' && ch != '>' && ch != '<' && ch != '?' && ch != '-' && ch != '~' && ch != '[' && ch != ']') {
            val engine = mEngine
            if (engine != null && engine.hasComposing()) {
                engine.flushRomaji()
            }
            commitText(c.toChar().toString(), 1)
            return true
        }

        processKey(c)
        return true
    }

    fun processKey(code: Int) {
        if (isInputDisabled()) {
            return
        }
        if (code == ' '.code) {
            handleSpace()
            return
        }
        mEngine?.processKey(code)
    }

    fun handleSpace(isShifted: Boolean = false) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (isShifted && engine != null && engine.processShortcutKey(KeyEvent.KEYCODE_SPACE, true)) {
            return
        }
        if (engine == null || !engine.handleSpace(isShifted)) {
            sendKeyChar(' ')
        }
    }

    fun handleBackspace() {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleBackspace()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    fun handleEnter() {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleEnter()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun toggleEnglishJapanese() {
        mEngine?.toggleEnglishJapanese()
    }

    fun handleCtrlKey(keyCode: Int) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.processShortcutKey(keyCode)) {
            val ic = currentInputConnection
            if (ic != null) {
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(
                    KeyEvent(
                        now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                    )
                )
                ic.sendKeyEvent(
                    KeyEvent(
                        now, now, KeyEvent.ACTION_UP, keyCode, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                    )
                )
            }
        }
    }

    fun handleTab(isShifted: Boolean) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.processTab(isShifted)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
    }

    fun handleEscape() {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleCancel()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
        }
    }

    fun handleDpadUp(isShifted: Boolean = false) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleDpadUp(isShifted)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
        }
    }

    fun handleDpadDown(isShifted: Boolean = false) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleDpadDown(isShifted)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    fun handleDpadLeft(isShifted: Boolean = false) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleDpadLeft(isShifted)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
        }
    }

    fun handleDpadRight(isShifted: Boolean = false) {
        if (isInputDisabled()) {
            return
        }
        val engine = mEngine
        if (engine == null || !engine.handleDpadRight(isShifted)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    fun hasHardwareKeyboardConnected(): Boolean {
        val config = resources.configuration
        if (config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.keyboard != Configuration.KEYBOARD_UNDEFINED &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        ) {
            return true
        }

        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id)
            if (device != null && !device.isVirtual) {
                if ((device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                    && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                ) {
                    return true
                }
            }
        }
        return false
    }

    fun isFloatingCandidateEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isPhysical = hasHardwareKeyboardConnected()
        val type = prefs.getString(PrefKeys.KEYBOARD_TYPE, PrefKeys.KEYBOARD_TYPE_QWERTY)
        val key = "floating_candidate_" + (if (isPhysical) "physical_" else "") + type

        val defaultVal = isPhysical || PrefKeys.KEYBOARD_TYPE_STROKE == type
                || PrefKeys.KEYBOARD_TYPE_SYMBOLS == type
                || PrefKeys.KEYBOARD_TYPE_NONE == type

        return prefs.getBoolean(key, defaultVal)
    }

    fun setCandidates(list: List<String>?, selectFirst: Boolean = true) {
        val view = mInputView
        if (view != null) {
            if (list != null && !isFloatingCandidateEnabled()) {
                view.setCandidates(list, selectFirst)
                view.showCandidatesView()
            } else {
                view.hideCandidatesView()
            }
        }
    }

    fun openEmojiPicker() {
        logI("openEmojiPicker called")
        setCandidatesViewShown(true)
        mInputView?.showEmojiPicker()
    }

    fun toggleEmojiPicker() {
        logI("toggleEmojiPicker called")
        setCandidatesViewShown(true)
        val view = mInputView
        if (view != null) {
            if (view.isEmojiPickerShowing()) {
                view.hideEmojiPicker()
            } else {
                view.showEmojiPicker()
            }
        }
    }

    fun setCandidateObjects(candidates: List<Candidate>?, selectFirst: Boolean = true) {
        val view = mInputView
        if (view != null) {
            if (candidates != null && !isFloatingCandidateEnabled()) {
                view.setCandidateObjects(candidates, selectFirst)
                view.showCandidatesView()
            } else {
                view.hideCandidatesView()
            }
        }
    }

    fun hideCandidatesView() {
        mInputView?.hideCandidatesView()
    }

    fun requestChooseCandidate(index: Int) {
        mInputView?.selectCandidate(index)
    }

    fun pickCandidateViewManually(index: Int) {
        mEngine?.pickCandidateViewManually(index)
    }

    fun getTextBeforeCursor(maxChars: Int): String {
        val ic = currentInputConnection ?: return ""
        val cs = ic.getTextBeforeCursor(maxChars, 0)
        return cs?.toString() ?: ""
    }

    fun prepareReConversion(candidate: String): Boolean {
        val ic = currentInputConnection ?: return false
        val textBeforeCursor = ic.getTextBeforeCursor(candidate.length, 0)?.toString() ?: ""
        if (candidate == textBeforeCursor) {
            ic.deleteSurroundingText(candidate.length, 0)
            return true
        }
        return false
    }

    fun commitText(text: CharSequence, newCursorPosition: Int) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.commitText(text, newCursorPosition)
            mHasComposingText = false
        }
    }

    fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        val ic = currentInputConnection
        if (ic == null) {
            mHasComposingText = false
            return
        }
        if (!mHasComposingText && text.isEmpty()) {
            return
        }
        if (text.isEmpty()) {
            mHasComposingText = false
            ic.setComposingText("", newCursorPosition)
            ic.finishComposingText()
            return
        }
        mHasComposingText = true
        ic.setComposingText(text, newCursorPosition)
    }

    fun requestUIUpdate() {
        updateStatusIcon()
        mInputView?.updateKeys(getCurrentTooltipText(), getCurrentIconResId())
        mNeedsTooltipUpdate = true
        performTooltipUpdate()
    }

    private fun updateStatusIcon() {
        if (isInputDisabled() || !mShowStatusBarIcon) {
            hideStatusIcon()
            return
        }
        val iconRes = getCurrentIconResId()
        if (iconRes != 0) {
            showStatusIcon(iconRes)
        } else {
            hideStatusIcon()
        }
    }

    private fun getCurrentIconResId(): Int {
        val engine = mEngine ?: return 0
        return when (engine.mode) {
            InputModeFullHiragana.INSTANCE -> R.drawable.ic_mode_full_hiragana
            InputModeFullKatakana.INSTANCE -> R.drawable.ic_mode_full_katakana
            InputModeFullLatin.INSTANCE -> R.drawable.ic_mode_full_latin
            InputModeHalfKatakana.INSTANCE -> R.drawable.ic_mode_half_katakana
            else -> 0
        }
    }

    private fun getCurrentTooltipText(): String {
        val engine = mEngine ?: return ""
        return when (engine.mode) {
            InputModeFullHiragana.INSTANCE -> "あ"
            InputModeFullKatakana.INSTANCE -> "ア"
            InputModeFullLatin.INSTANCE -> "全英"
            InputModeHalfKatakana.INSTANCE -> "ｶﾅ"
            InputModeHalfLatin.INSTANCE -> "英"
            else -> ""
        }
    }

    fun dismissStatusUI() {
        mNeedsTooltipUpdate = false
        hideStatusIcon()
        mHideHandler.removeCallbacks(mHideRunnable)
        mHideHandler.removeCallbacks(mShowTooltipRunnable)
        mTooltipPopup?.dismiss()
        mInputView?.hideFloatingCandidates()
        mHelpPopup?.dismiss()
    }

    private fun performTooltipUpdate() {
        if (!mNeedsTooltipUpdate || isInputDisabled()) {
            return
        }
        if (isCursorInvisible) {
            return
        }

        mNeedsTooltipUpdate = false

        if (mTooltipDuration > 0) {
            val text = getCurrentTooltipText()
            if (text.isNotEmpty()) {
                showTooltipNow(text)
            }
        }
    }

    fun hideFloatingCandidates() {
        mInputView?.hideFloatingCandidates()
    }

    fun showCandidateFlexboxPopup(header: String?, items: List<String>?, selectedIdx: Int) {
        mTooltipPopup?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        mInputView?.showCandidateFlexboxPopup(header, items, selectedIdx)
    }

    fun hideFullWidthCandidatePopup() {
        mInputView?.hideFullWidthCandidatePopup()
    }

    fun getCurrentCandidateIndex(): Int {
        return mEngine?.getCurrentCandidateIndex() ?: 0
    }

    fun setCandidateIndex(index: Int) {
        mEngine?.setCandidateIndex(index)
    }

    private fun showTooltipNow(text: String) {
        hideFullWidthCandidatePopup()
        mHideHandler.removeCallbacks(mHideRunnable)

        val popup = mTooltipPopup
        if (popup != null && popup.isShowing) {
            setTooltipViewText(popup.contentView, text)
            updateTooltipPosition()
        } else {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.tooltip_view, null)
            setTooltipViewText(view, text)

            val newPopup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                isFocusable = false
                animationStyle = 0
            }
            mTooltipPopup = newPopup

            val coords = calculateTooltipScreenCoordinates(view)
            showPopupAtScreenLocation(newPopup, coords[0], coords[1])
        }
        mHideHandler.postDelayed(mHideRunnable, mTooltipDuration.toLong())
    }

    fun showStrokeHelp() {
        val help = mHelpPopup
        if (help != null && help.isShowing) {
            return
        }

        val inflater = LayoutInflater.from(this)
        val helpView = inflater.inflate(R.layout.stroke_help, null)
        val screenWidth = resources.displayMetrics.widthPixels

        helpView.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val helpHeight = helpView.measuredHeight

        val anchor = getAnchorView()
        if (anchor != null) {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)

            val newHelp = PopupWindow(helpView, screenWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                isFocusable = true
                isOutsideTouchable = true
                isTouchable = true
                setBackgroundDrawable(ColorDrawable(0))
                animationStyle = 0
            }
            mHelpPopup = newHelp

            val flipper = helpView as ViewFlipper
            flipper.setOnClickListener { flipper.showNext() }

            val targetX = loc[0]
            var targetY = loc[1] - helpHeight

            val statusBarHeight = getStatusBarHeight()
            if (targetY < statusBarHeight) {
                targetY = statusBarHeight
            }
            showPopupAtScreenLocation(newHelp, targetX, targetY)
        }
    }

    class InputDeviceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
        }
    }

    companion object {
        fun setupDefaultPreferences(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            if (!prefs.contains(PrefKeys.KEYBOARD_TYPE)) {
                val isPhysical = (context as? InputService)?.hasHardwareKeyboardConnected() ?: false
                val defaultType = if (isPhysical) PrefKeys.KEYBOARD_TYPE_SYMBOLS else PrefKeys.KEYBOARD_TYPE_QWERTY
                prefs.edit().putString(PrefKeys.KEYBOARD_TYPE, defaultType).apply()
            }

            PreferenceManager.setDefaultValues(context, R.xml.root_preferences, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_input_engine, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_main, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_qwerty, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_tablet, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_stroke, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_symbols, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_keyboard_none, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_display_ui, false)
            PreferenceManager.setDefaultValues(context,R.xml.pref_dictionary, false)
            PreferenceManager.setDefaultValues(context, R.xml.pref_backup_reset, false)
        }

        fun getThemedContext(context: Context): Context {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val themeMode = prefs.getString(PrefKeys.KEYBOARD_THEME, "auto")

            if ("auto" == themeMode) {
                return context
            }

            val config = Configuration(context.resources.configuration)
            if ("dark" == themeMode) {
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            } else if ("light" == themeMode) {
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
            return context.createConfigurationContext(config)
        }
    }
}
