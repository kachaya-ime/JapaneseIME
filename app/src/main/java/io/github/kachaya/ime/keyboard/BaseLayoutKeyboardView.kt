package io.github.kachaya.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import io.github.kachaya.ime.R

/**
 * カスタムレイアウト（QWERTY、Tablet 等）を持つソフトウェアキーボードビューの共通基底クラスです。
 */
abstract class BaseLayoutKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    protected var mBaseKey: String
) : KeyboardView(context, attrs) {

    protected var mNormalLayout: Array<Array<KeyConfig>>? = null
    protected var mShiftLayout: Array<Array<KeyConfig>>? = null
    protected var mSymbolLayout: Array<Array<KeyConfig>>? = null
    protected var mCurrentLayout: Array<Array<KeyConfig>>? = null

    protected var mRowHeight: Int = 0
    protected var mIsShifted: Boolean = false
    protected var mIsShiftLocked: Boolean = false
    protected var mIsControl: Boolean = false
    protected var mIsSymbol: Boolean = false

    protected val mRepeatHandler: Handler = Handler(Looper.getMainLooper())
    protected var mRepeatRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        readPrefs()
    }

    override fun setRowHeight(height: Int) {
        if (mRowHeight != height) {
            mRowHeight = height
            buildKeyboard()
        }
    }

    override fun readPrefs() {
        super.readPrefs()

        if (mRowHeight <= 0) {
            mRowHeight = resources.getDimension(R.dimen.button_height).toInt()
        }
        mNormalLayout = loadIndependentLayout(mBaseKey, "_normal", LayoutManager.DefaultLayouts.get(context, mBaseKey + "_normal"))
        mShiftLayout = loadIndependentLayout(mBaseKey, "_shift", LayoutManager.DefaultLayouts.get(context, mBaseKey + "_shift"))
        mSymbolLayout = loadIndependentLayout(mBaseKey, "_symbol", LayoutManager.DefaultLayouts.get(context, mBaseKey + "_symbol"))

        updateLayout()
    }

    protected open fun loadIndependentLayout(baseKey: String, suffix: String, defaultLayout: String?): Array<Array<KeyConfig>> {
        val key = baseKey + suffix
        val layoutStr = LayoutManager.loadLayout(context, key, defaultLayout ?: "")
        return KeyConfig.layoutFromAnyString(layoutStr)
    }

    override fun updateState(state: KeyboardState) {
        val layoutChanged = (mIsShifted != state.shifted || mIsShiftLocked != state.shiftLocked
                || mIsControl != state.control || mIsSymbol != state.symbol)
        mIsShifted = state.shifted
        mIsShiftLocked = state.shiftLocked
        mIsControl = state.control
        mIsSymbol = state.symbol

        if (layoutChanged) {
            updateLayout()
        }
    }

    protected open fun updateLayout() {
        val symbol = mSymbolLayout
        val nextLayout = if (mIsSymbol && !symbol.isNullOrEmpty()) {
            symbol
        } else if (mIsShifted) {
            mShiftLayout
        } else {
            mNormalLayout
        }

        if (mCurrentLayout != nextLayout) {
            mCurrentLayout = nextLayout
            buildKeyboard()
        } else {
            updateKeyStates()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    protected open fun buildKeyboard() {
        removeAllViews()

        val current = mCurrentLayout ?: return

        for (rowConfig in current) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, mRowHeight))

            for (config in rowConfig) {
                if (config.code == KeyConfig.CODE_GAP || "GAP" == config.label) {
                    val gapView = View(context)
                    row.addView(gapView, LayoutParams(0, LayoutParams.MATCH_PARENT, config.weight))
                    continue
                }

                val b = KeyViewFactory.createKeyButton(context, config)
                if (config.isRepeatable()) {
                    setupRepeatKey(b, config)
                } else {
                    b.setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            performHapticFeedback(v)
                        }
                        false
                    }
                    b.setOnClickListener { v ->
                        val tag = v.tag
                        if (tag is KeyConfig) {
                            mListener?.onKey(tag)
                        }
                    }
                }
                row.addView(b, LayoutParams(0, LayoutParams.MATCH_PARENT, config.weight))
            }
        }
        updateKeyStates()
    }

    protected open fun updateKeyStates() {
        for (i in 0..<childCount) {
            val v = getChildAt(i)
            if (v is LinearLayout) {
                for (j in 0..<v.childCount) {
                    val bv = v.getChildAt(j)
                    if (bv is Button) {
                        val config = bv.tag as? KeyConfig ?: continue
                        when (config.code) {
                            KeyConfig.CODE_SHIFT -> bv.isSelected = mIsShifted
                            KeyConfig.CODE_CTRL -> bv.isSelected = mIsControl
                            KeyConfig.CODE_SYM -> bv.isSelected = mIsSymbol
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    protected open fun setupRepeatKey(b: Button, config: KeyConfig) {
        b.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    performHapticFeedback(v)
                    mRepeatRunnable?.let { mRepeatHandler.removeCallbacks(it) }
                    val runnable = object : Runnable {
                        override fun run() {
                            mListener?.onKey(config)
                            mRepeatHandler.postDelayed(this, 50)
                        }
                    }
                    mRepeatRunnable = runnable
                    mListener?.onKey(config)
                    mRepeatHandler.postDelayed(runnable, 500)
                    v.isPressed = true
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    mRepeatRunnable?.let {
                        mRepeatHandler.removeCallbacks(it)
                        mRepeatRunnable = null
                    }
                    v.isPressed = false
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mRepeatRunnable?.let {
                        mRepeatHandler.removeCallbacks(it)
                        mRepeatRunnable = null
                    }
                    v.isPressed = false
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
}
