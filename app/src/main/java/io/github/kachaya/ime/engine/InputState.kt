package io.github.kachaya.ime.engine

/**
 * 「入力状態」の振る舞いを定義するインターフェースです。
 *
 * 直接入力（Direct）、見出し語入力中（▽）、変換中（▼）などの状態遷移と、
 * それぞれの状態におけるキーイベントの解釈を Strategy パターンで管理します。
 */
interface InputState {
    fun isTransient(): Boolean
    fun isConverting(): Boolean

    fun processKey(context: InputEngine, code: Int): Boolean
    fun processSpace(context: InputEngine, isShifted: Boolean): Boolean
    fun processInputAction(context: InputEngine, action: InputAction): Boolean
    fun processTab(context: InputEngine, isShifted: Boolean): Boolean
    fun processEnter(context: InputEngine): Boolean

    fun processDpadUp(context: InputEngine): Boolean
    fun processDpadDown(context: InputEngine): Boolean
    fun processDpadLeft(context: InputEngine): Boolean
    fun processDpadRight(context: InputEngine): Boolean
    fun handleBackKey(context: InputEngine): Boolean

    fun onEnterState(context: InputEngine)
    fun onExitState(context: InputEngine)

    fun processText(context: InputEngine, text: String?, initial: Char, isUpper: Boolean)
    fun onFinishRomaji(context: InputEngine)

    fun processBackspace(context: InputEngine): Boolean
    fun beforeBackspace(context: InputEngine)
    fun afterBackspace(context: InputEngine)
    fun handleCancel(context: InputEngine): Boolean

    fun finish(context: InputEngine): Boolean
    fun toggleKana(context: InputEngine)
    fun getComposingText(context: InputEngine): CharSequence?
}
