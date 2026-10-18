package io.github.kachaya.ime.engine

import android.view.KeyEvent

/**
 * 全角英数モード（JISX0208 Latin）を管理するクラスです。
 */
enum class InputModeFullLatin : InputMode {
    INSTANCE;

    override fun processKey(context: InputEngine, code: Int) {
        val text = String(Character.toChars(code))
        context.commitTextInternal(convertText(text), 1)
    }

    override fun processInputAction(context: InputEngine, action: InputAction): Boolean {
        when (action) {
            InputAction.KANA_KEY -> {
                context.handleKanaKey()
                return true
            }
            InputAction.TOGGLE_EN_JP -> {
                context.toggleEnglishJapanese()
                return true
            }
            InputAction.LAUNCH_SETTINGS -> {
                context.launchSettings()
                return true
            }
            InputAction.OPEN_EMOJI -> {
                context.openEmojiPicker()
                return true
            }
            InputAction.INSERT_NEWLINE -> {
                if (context.hasComposing()) {
                    context.handleEnter()
                }
                context.sendShiftEnter()
                return true
            }
            InputAction.CURSOR_UP -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            InputAction.CURSOR_DOWN -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
                return true
            }
            InputAction.CURSOR_LEFT -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                return true
            }
            InputAction.CURSOR_RIGHT -> {
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
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
            else -> {}
        }
        return false
    }

    override fun convertText(text: CharSequence): CharSequence {
        return RomajiConverter.toWideLatin(text) ?: text
    }

    override fun getToggledKanaMode(context: InputEngine): InputMode? {
        return null
    }
}
