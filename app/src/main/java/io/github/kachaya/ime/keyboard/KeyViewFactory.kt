package io.github.kachaya.ime.keyboard

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.widget.Button
import androidx.core.content.ContextCompat
import io.github.kachaya.ime.InputService
import io.github.kachaya.ime.R

/**
 * キーボードの各キー（Button）を生成し、共通のスタイルを適用するためのファクトリクラスです。
 */
object KeyViewFactory {

    /**
     * 指定された KeyConfig に基づいて、スタイル適用済みの Button を生成します。
     *
     * @param context コンテキスト
     * @param config  キーの構成情報
     * @return スタイルが適用された Button インスタンス
     */
    fun createKeyButton(context: Context, config: KeyConfig): Button {
        val themedContext = InputService.getThemedContext(context)
        val isFunctional = config.code != KeyConfig.CODE_NONE
        val style = if (isFunctional) R.style.FunctionalKeyButton else R.style.CharacterButton
        val bgRes = if (isFunctional) R.drawable.bg_function_button_selector else R.drawable.bg_character_button_selector

        val b = Button(ContextThemeWrapper(themedContext, style), null, 0)
        b.tag = config
        b.isAllCaps = false
        b.setPadding(0, 0, 0, 0)
        b.setBackgroundResource(bgRes)

        updateKeyButton(b, config)
        return b
    }

    /**
     * キーボタンの構成（ラベル、フォントサイズ、機能キー用背景・テキスト色）を最新の KeyConfig に更新します。
     *
     * @param b 対象の Button
     * @param config 設定するキーの構成情報
     */
    fun updateKeyButton(b: Button?, config: KeyConfig?) {
        if (b == null) return
        if (config == null) {
            updateKeyLabel(b, "")
            b.tag = null
            return
        }

        b.tag = config
        updateKeyLabel(b, config.label)

        val isFunctional = config.code != KeyConfig.CODE_NONE
        val bgRes = if (isFunctional) R.drawable.bg_function_button_selector else R.drawable.bg_character_button_selector
        b.setBackgroundResource(bgRes)

        val themedContext = InputService.getThemedContext(b.context)
        val textColorRes = if (isFunctional) R.color.function_button_fg else R.color.character_button_fg
        b.setTextColor(ContextCompat.getColor(themedContext, textColorRes))
    }

    /**
     * キーボタンのラベルテキストを設定し、文字数に応じて適切なフォントサイズを適用します。
     *
     * @param b 対象の Button
     * @param label 設定するラベル文字列
     */
    fun updateKeyLabel(b: Button?, label: String?) {
        if (b == null) return
        b.text = label
        if (label != null && label.length >= 2) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        } else {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
    }
}
