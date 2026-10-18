package io.github.kachaya.ime.keyboard

import android.content.Context
import android.util.AttributeSet

/**
 * Tablet 配列のソフトウェアキーボードを表示・管理するビューです。
 */
class TabletKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseLayoutKeyboardView(context, attrs, "custom_tablet_layout")
