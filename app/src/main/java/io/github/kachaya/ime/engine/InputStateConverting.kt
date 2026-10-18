package io.github.kachaya.ime.engine

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
