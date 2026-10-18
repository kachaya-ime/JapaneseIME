package io.github.kachaya.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import io.github.kachaya.ime.R

/**
 * 記号や特定の機能キーを 1 行（バー状）で表示・管理するビューです。
 */
class SymbolKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    private var mSymbolsPrimary: List<KeyConfig> = ArrayList()
    private var mSymbolsSecondary: List<KeyConfig> = ArrayList()
    private var mMaxButtonCount: Int = 0
    private var mIsAlternativeSymbols: Boolean = false

    private val mSymbolButtons: MutableList<Button> = ArrayList()
    private val mToggleKeyboardButton: ImageButton

    init {
        orientation = HORIZONTAL

        val style = R.style.FunctionButton
        mToggleKeyboardButton = ImageButton(ContextThemeWrapper(context, style), null, 0)
        mToggleKeyboardButton.setOnClickListener { v -> onClickToggleKeyboardButton(v) }
        mToggleKeyboardButton.setImageResource(R.drawable.ic_keyboard_swap)

        readPrefs()
        buildKeyboard()
    }

    override fun readPrefs() {
        super.readPrefs()

        val defaultSymbols = LayoutManager.DefaultLayouts.get(context, "custom_symbols_layout")
        val symLayoutStr = LayoutManager.loadLayout(context, "custom_symbols_layout", defaultSymbols ?: "")
        val symLayout = KeyConfig.layoutFromAnyString(symLayoutStr)

        mSymbolsPrimary = if (symLayout.isNotEmpty()) symLayout[0].toList() else ArrayList()
        mSymbolsSecondary = if (symLayout.size > 1) symLayout[1].toList() else ArrayList()
        mMaxButtonCount = Math.max(mSymbolsPrimary.size, mSymbolsSecondary.size)

        if (mSymbolButtons.isNotEmpty()) {
            buildKeyboard()
        }
    }

    override fun updateState(state: KeyboardState) {
        // 記号バーは修飾キーの状態に依存しない
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildKeyboard() {
        removeAllViews()
        mSymbolButtons.clear()

        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f)

        for (i in 0..<mMaxButtonCount) {
            val b = KeyViewFactory.createKeyButton(context, KeyConfig(""))
            b.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    performHapticFeedback(v)
                }
                false
            }
            b.setOnClickListener { v ->
                val config = v.tag as? KeyConfig
                if (config != null) {
                    mListener?.onKey(config)
                }
            }
            addView(b, lp)
            mSymbolButtons.add(b)
        }

        val parent = mToggleKeyboardButton.parent
        if (parent is ViewGroup) {
            parent.removeView(mToggleKeyboardButton)
        }
        addView(mToggleKeyboardButton, lp)

        updateSymbolButtons()
    }

    private fun updateSymbolButtons() {
        val currentSet = if (mIsAlternativeSymbols) mSymbolsSecondary else mSymbolsPrimary
        for (i in mSymbolButtons.indices) {
            val b = mSymbolButtons[i]
            if (i < currentSet.size) {
                val config = currentSet[i]
                KeyViewFactory.updateKeyButton(b, config)
                b.isEnabled = true
                b.isClickable = true
            } else {
                KeyViewFactory.updateKeyButton(b, null)
                b.isEnabled = false
                b.isClickable = false
            }
        }
    }

    private fun onClickToggleKeyboardButton(v: View) {
        performHapticFeedback(v)
        mIsAlternativeSymbols = !mIsAlternativeSymbols;
        updateSymbolButtons()
    }
}
