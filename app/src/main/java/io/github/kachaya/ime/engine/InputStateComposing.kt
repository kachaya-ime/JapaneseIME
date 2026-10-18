package io.github.kachaya.ime.engine

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
