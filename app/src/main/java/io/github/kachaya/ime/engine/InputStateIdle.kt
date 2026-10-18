package io.github.kachaya.ime.engine

import android.view.KeyEvent

/**
 * 待機・確定モード（Idle / 通常の直接入力状態）を管理するクラスです。
 */
enum class InputStateIdle : InputState {
    INSTANCE;

    override fun isTransient(): Boolean = false
    override fun isConverting(): Boolean = false

    override fun processKey(context: InputEngine, code: Int): Boolean = false
    override fun processSpace(context: InputEngine, isShifted: Boolean): Boolean = false

    override fun processInputAction(context: InputEngine, action: InputAction): Boolean {
        when (action) {
            InputAction.KANA_KEY -> {
                context.handleKanaKey()
                return true
            }
            InputAction.CANCEL -> return context.handleCancel()
            InputAction.TOGGLE_KANA -> {
                context.toggleKana()
                return true
            }
            InputAction.TOGGLE_EN_JP -> {
                context.toggleEnglishJapanese()
                return true
            }
            InputAction.FORWARD_DELETE -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)
                return true
            }
            InputAction.DELETE_CHAR -> {
                if (!context.handleBackspace()) {
                    context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                return true
            }
            InputAction.LINE_START -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
                return true
            }
            InputAction.LINE_END -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
                return true
            }
            InputAction.KILL_LINE -> return context.handleKillLine()
            InputAction.KILL_LINE_BACKWARD -> return context.handleKillLineBackward()
            InputAction.LAUNCH_SETTINGS -> {
                context.launchSettings()
                return true
            }
            InputAction.OPEN_EMOJI -> {
                context.openEmojiPicker()
                return true
            }
            InputAction.INSERT_NEWLINE -> {
                context.sendShiftEnter()
                return true
            }
            else -> {}
        }
        return false
    }

    override fun processTab(context: InputEngine, isShifted: Boolean): Boolean = false
    override fun processEnter(context: InputEngine): Boolean = false

    override fun processDpadUp(context: InputEngine): Boolean = !context.canMoveCursor()
    override fun processDpadDown(context: InputEngine): Boolean = !context.canMoveCursor()
    override fun processDpadLeft(context: InputEngine): Boolean = !context.canMoveCursor()
    override fun processDpadRight(context: InputEngine): Boolean = !context.canMoveCursor()

    override fun handleBackKey(context: InputEngine): Boolean = false

    override fun onEnterState(context: InputEngine) {
        context.clearBuffers()
        context.updateIdlePredictions()
    }

    override fun onExitState(context: InputEngine) {}

    override fun processText(context: InputEngine, text: String?, initial: Char, isUpper: Boolean) {
        if (text != null) {
            context.changeState(InputStateComposing.INSTANCE)
            InputStateComposing.INSTANCE.processText(context, text, initial, false)
        }
    }

    override fun onFinishRomaji(context: InputEngine) {}

    override fun processBackspace(context: InputEngine): Boolean = false
    override fun beforeBackspace(context: InputEngine) {}
    override fun afterBackspace(context: InputEngine) {}
    override fun handleCancel(context: InputEngine): Boolean = false

    override fun finish(context: InputEngine): Boolean = false

    override fun toggleKana(context: InputEngine) {
        val nextMode = context.getToggledKanaMode()
        if (nextMode != null) {
            context.changeMode(nextMode, false)
        }
    }

    override fun getComposingText(context: InputEngine): CharSequence? = null
}
