package io.github.kachaya.ime.keyboard

import android.content.Context
import android.util.AttributeSet

/**
 * QWERTY 配列のソフトウェアキーボードを表示・管理するビューです。
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseLayoutKeyboardView(context, attrs, "custom_qwerty_layout")
