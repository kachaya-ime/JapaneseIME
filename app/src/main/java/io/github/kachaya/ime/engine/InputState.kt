package io.github.kachaya.ime.engine

import android.view.KeyEvent

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

/**
 * 未確定入力状態（Composing / ▽）を管理するクラスです。
 */
enum class InputStateComposing : InputState {
    INSTANCE;

    override fun isTransient(): Boolean = true
    override fun isConverting(): Boolean = false

    override fun processKey(context: InputEngine, code: Int): Boolean = false

    override fun processInputAction(context: InputEngine, action: InputAction): Boolean {
        when (action) {
            InputAction.TOGGLE_KANA -> {
                context.toggleKana()
                return true
            }
            InputAction.DELETE_CHAR -> return context.handleBackspace()
            InputAction.KILL_LINE_BACKWARD -> return context.killComposingToLineStart()
            InputAction.COMPLETION -> {
                if (context.suggestionList.isNullOrEmpty()) {
                    context.updateSuggestions()
                } else {
                    context.chooseAdjacentSuggestion(true)
                }
                return true
            }
            InputAction.KANA_KEY -> {
                context.handleKanaKey()
                return true
            }
            InputAction.CANCEL -> return context.handleCancel()
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
                context.handleEnter()
                context.sendShiftEnter()
                return true
            }
            InputAction.CURSOR_LEFT -> {
                context.moveComposingCursor(-1)
                return true
            }
            InputAction.CURSOR_RIGHT -> {
                context.moveComposingCursor(1)
                return true
            }
            InputAction.LINE_START -> {
                context.setComposingCursor(0)
                return true
            }
            InputAction.LINE_END -> {
                context.setComposingCursor(context.getComposingText().length)
                return true
            }
            InputAction.FORWARD_DELETE -> return context.deleteComposingCharAfterCursor()
            InputAction.KILL_LINE -> return context.killComposingToLineEnd()
            InputAction.CURSOR_UP,
            InputAction.CURSOR_DOWN -> return true
            else -> {}
        }
        return false
    }

    override fun processTab(context: InputEngine, isShifted: Boolean): Boolean {
        if (context.suggestionList.isNullOrEmpty()) {
            context.updateSuggestions()
        } else {
            context.chooseAdjacentSuggestion(!isShifted)
        }
        return true
    }

    override fun processEnter(context: InputEngine): Boolean {
        finish(context)
        context.changeState(InputStateIdle.INSTANCE)
        return true
    }

    override fun processDpadUp(context: InputEngine): Boolean = true
    override fun processDpadDown(context: InputEngine): Boolean = true

    override fun processDpadLeft(context: InputEngine): Boolean {
        context.moveComposingCursor(-1)
        return true
    }

    override fun processDpadRight(context: InputEngine): Boolean {
        context.moveComposingCursor(1)
        return true
    }

    override fun handleBackKey(context: InputEngine): Boolean = handleCancel(context)

    override fun onEnterState(context: InputEngine) {
        context.clearCandidates()
        context.resetComposingCursor()
    }

    override fun onExitState(context: InputEngine) {}

    override fun processSpace(context: InputEngine, isShifted: Boolean): Boolean {
        context.conversionStart()
        return true
    }

    override fun processText(context: InputEngine, text: String?, initial: Char, isUpper: Boolean) {
        if (text != null) {
            context.insertComposingText(text)
        }
    }

    override fun onFinishRomaji(context: InputEngine) {}

    override fun processBackspace(context: InputEngine): Boolean = context.deleteComposingCharBeforeCursor()

    override fun beforeBackspace(context: InputEngine) {}

    override fun afterBackspace(context: InputEngine) {
        val composingText = context.getComposingText()
        val len = composingText.length
        val hasComposing = context.hasComposing()

        if (len == 0 && !hasComposing) {
            context.lastCommittedCandidate = null
            context.changeState(InputStateIdle.INSTANCE)
        }
    }

    override fun handleCancel(context: InputEngine): Boolean {
        context.lastCommittedCandidate = null
        context.changeState(InputStateIdle.INSTANCE)
        return true
    }

    override fun finish(context: InputEngine): Boolean {
        val composingText = context.getComposingText()
        val len = composingText.length
        if (len > 0) {
            val text = context.convertText(composingText)
            context.commitTextInternal(text, 1)
        }
        context.lastCommittedCandidate = null
        return true
    }

    override fun toggleKana(context: InputEngine) {
        val composingText = context.getComposingText()
        val len = composingText.length
        if (len > 0) {
            val toggledMode = context.getToggledKanaMode()
            if (toggledMode != null) {
                val text = toggledMode.convertText(composingText)
                context.commitTextInternal(text, 1)
            }
        }
        context.lastCommittedCandidate = null
        context.changeState(InputStateIdle.INSTANCE)
    }

    override fun getComposingText(context: InputEngine): CharSequence {
        val composingText = context.getComposingText()
        return context.convertText(composingText)
    }
}

/**
 * 漢字変換中・候補選択状態（Converting / ▼）を管理するクラスです。
 */
enum class InputStateConverting : InputState {
    INSTANCE;

    override fun isTransient(): Boolean = true
    override fun isConverting(): Boolean = true

    override fun processKey(context: InputEngine, code: Int): Boolean {
        context.pickCurrentCandidate()
        context.processKey(code)
        return true
    }

    override fun processSpace(context: InputEngine, isShifted: Boolean): Boolean {
        if (isShifted) {
            context.choosePrevCandidate()
        } else {
            context.chooseNextCandidate()
        }
        return true
    }

    override fun processInputAction(context: InputEngine, action: InputAction): Boolean {
        when (action) {
            InputAction.CURSOR_DOWN -> {
                context.chooseNextCandidate()
                return true
            }
            InputAction.CURSOR_UP -> {
                context.choosePrevCandidate()
                return true
            }
            InputAction.CURSOR_RIGHT -> {
                if (!context.focusNextSegment()) {
                    context.chooseNextCandidate()
                }
                return true
            }
            InputAction.CURSOR_LEFT -> {
                if (!context.focusPrevSegment()) {
                    context.choosePrevCandidate()
                }
                return true
            }
            InputAction.KANA_KEY -> {
                context.handleKanaKey()
                return true
            }
            InputAction.CANCEL -> return handleCancel(context)
            InputAction.TOGGLE_KANA -> {
                context.toggleKana()
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
                context.handleEnter()
                context.sendShiftEnter()
                return true
            }
            InputAction.DELETE_CHAR -> {
                context.handleBackspace()
                return true
            }
            InputAction.FORWARD_DELETE,
            InputAction.LINE_START,
            InputAction.LINE_END,
            InputAction.KILL_LINE -> return true
            else -> {}
        }
        return false
    }

    override fun processTab(context: InputEngine, isShifted: Boolean): Boolean {
        if (isShifted) {
            context.choosePrevCandidate()
        } else {
            context.chooseNextCandidate()
        }
        return true
    }

    override fun processEnter(context: InputEngine): Boolean {
        context.pickCurrentCandidate()
        return true
    }

    override fun processDpadUp(context: InputEngine): Boolean {
        context.choosePrevCandidate()
        return true
    }

    override fun processDpadDown(context: InputEngine): Boolean {
        context.chooseNextCandidate()
        return true
    }

    override fun processDpadLeft(context: InputEngine): Boolean {
        if (!context.focusPrevSegment()) {
            context.choosePrevCandidate()
        }
        return true
    }

    override fun processDpadRight(context: InputEngine): Boolean {
        if (!context.focusNextSegment()) {
            context.chooseNextCandidate()
        }
        return true
    }

    override fun handleBackKey(context: InputEngine): Boolean = handleCancel(context)

    override fun onEnterState(context: InputEngine) {}
    override fun onExitState(context: InputEngine) {}

    override fun processText(context: InputEngine, text: String?, initial: Char, isUpper: Boolean) {
        context.pickCurrentCandidate()
        InputStateIdle.INSTANCE.processText(context, text, initial, isUpper)
    }

    override fun onFinishRomaji(context: InputEngine) {}

    override fun processBackspace(context: InputEngine): Boolean = false

    override fun beforeBackspace(context: InputEngine) {
        context.restoreRemainingComposingText()
    }

    override fun afterBackspace(context: InputEngine) {
        val composingText = context.getComposingText()
        if (composingText.length == 0) {
            context.changeState(InputStateIdle.INSTANCE)
        } else {
            context.changeState(InputStateComposing.INSTANCE)
        }
    }

    override fun handleCancel(context: InputEngine): Boolean {
        context.changeState(InputStateComposing.INSTANCE)
        return true
    }

    override fun finish(context: InputEngine): Boolean {
        context.pickCurrentCandidate()
        return true
    }

    override fun toggleKana(context: InputEngine) {
        context.pickCurrentCandidate()
        InputStateIdle.INSTANCE.toggleKana(context)
    }

    override fun getComposingText(context: InputEngine): CharSequence {
        val multiSeg = context.getMultiSegmentText()
        if (multiSeg != null) {
            return context.convertText(multiSeg)
        }
        val candidate = context.getCurrentCandidate()
        return context.convertText(candidate)
    }
}

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
