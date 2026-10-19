package io.github.kachaya.ime.engine

import android.view.KeyEvent

/**
 * 「入力モード」の振る舞いを定義するインターフェースです。
 *
 * 入力モード（ひらがな、カタカナ、英数、全角英数）に応じた文字変換のロジック、
 * モード固有のアイコン表示、およびツールチップテキストの提供を Strategy パターンで管理します。
 */
interface InputMode {
    /**
     * 指定されたキーコードを現在のモードの規則に従って処理します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param code 入力されたキーの Unicode コードポイント
     */
    fun processKey(context: InputEngine, code: Int)

    /**
     * キー入力アクションを、現在のモードに従って処理します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param action  割り当てられている InputAction
     * @return イベントを消費した場合は true
     */
    fun processInputAction(context: InputEngine, action: InputAction): Boolean

    /**
     * 与えられたテキストを、現在のモードの性質に合わせて最終変換（半角化、全角化等）します。
     *
     * @param text 変換対象の文字列
     * @return 変換後の文字列
     */
    fun convertText(text: CharSequence): CharSequence

    /**
     * かなモード（ひらがなとカタカナ）を交互に切り替える際の、遷移先となるモードを取得します。
     *
     * @param context 変換エンジンのコンテキスト
     * @return 遷移先の InputMode インスタンス（存在しない場合は null）
     */
    fun getToggledKanaMode(context: InputEngine): InputMode?
}

/**
 * 全角ひらがなモードを管理するクラスです。
 */
enum class InputModeFullHiragana : InputMode {
    INSTANCE;

    override fun processKey(context: InputEngine, code: Int) {
        context.processRomaji(code)
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
        return text
    }

    override fun getToggledKanaMode(context: InputEngine): InputMode {
        return InputModeFullKatakana.INSTANCE
    }
}

/**
 * 全角カタカナモードを管理するクラスです。
 */
enum class InputModeFullKatakana : InputMode {
    INSTANCE;

    override fun processKey(context: InputEngine, code: Int) {
        context.processRomaji(code)
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
        return RomajiConverter.toWideKatakana(text)
    }

    override fun getToggledKanaMode(context: InputEngine): InputMode {
        return if (context.useJisx0201Kana()) {
            InputModeHalfKatakana.INSTANCE
        } else {
            InputModeFullHiragana.INSTANCE
        }
    }
}

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

/**
 * 半角カタカナモード（JISX0201）を管理するクラスです。
 */
enum class InputModeHalfKatakana : InputMode {
    INSTANCE;

    override fun processKey(context: InputEngine, code: Int) {
        context.processRomaji(code)
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
        return RomajiConverter.toHalfKatakana(text)
    }

    override fun getToggledKanaMode(context: InputEngine): InputMode {
        return InputModeFullHiragana.INSTANCE
    }
}

/**
 * 半角英数モード（ASCII）を管理するクラスです。
 */
enum class InputModeHalfLatin : InputMode {
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
        return text
    }

    override fun getToggledKanaMode(context: InputEngine): InputMode? {
        return null
    }
}
