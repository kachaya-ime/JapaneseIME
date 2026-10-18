package io.github.kachaya.ime.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import androidx.preference.PreferenceManager

/**
 * すべてのソフトウェアキーボードビューの基底抽象クラスです。
 */
abstract class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /**
     * キーボードからの入力を受け取るためのリスナーインターフェースです。
     */
    fun interface OnKeyActionListener {
        fun onKey(config: KeyConfig)
    }

    /**
     * キーボードの修飾キー（Shift, Ctrl 等）の状態を保持するデータクラスです。
     */
    class KeyboardState(
        @JvmField val shifted: Boolean,
        @JvmField val shiftLocked: Boolean,
        @JvmField val control: Boolean,
        @JvmField val symbol: Boolean,
        @JvmField val symbolLocked: Boolean,
        @JvmField val modeText: String?,
        @JvmField val modeIconResId: Int
    )

    @JvmField
    var mListener: OnKeyActionListener? = null

    protected var mHapticEnabled: Boolean = false

    open fun setOnKeyActionListener(listener: OnKeyActionListener?) {
        mListener = listener
    }

    open fun setRowHeight(height: Int) {
        // サブクラスで必要に応じて実装
    }

    open fun readPrefs() {
        mHapticEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("haptic_feedback", true)
    }

    abstract fun updateState(state: KeyboardState)

    protected open fun performHapticFeedback(v: View) {
        if (mHapticEnabled) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
