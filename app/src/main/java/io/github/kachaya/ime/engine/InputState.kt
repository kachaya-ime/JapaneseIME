package io.github.kachaya.ime.engine

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import kotlin.math.max
import kotlin.math.min

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

    fun processDpadUp(context: InputEngine, isShifted: Boolean): Boolean
    fun processDpadDown(context: InputEngine, isShifted: Boolean): Boolean
    fun processDpadLeft(context: InputEngine, isShifted: Boolean): Boolean
    fun processDpadRight(context: InputEngine, isShifted: Boolean): Boolean
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

    fun processPickCandidate(context: InputEngine, index: Int): Boolean
    fun processSetCandidateIndex(context: InputEngine, index: Int)
    fun formatComposingText(context: InputEngine, builder: SpannableStringBuilder)
}

/**
 * 未確定入力状態（Composing）を管理するクラスです。
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
            InputAction.COMMIT -> {
                return processEnter(context)
            }
            InputAction.CONVERT -> {
                return processSpace(context, false)
            }
            InputAction.NEXT_CANDIDATE -> {
                if (context.suggestionList.isNullOrEmpty()) {
                    context.updateSuggestions()
                } else {
                    context.chooseAdjacentSuggestion(true)
                }
                return true
            }
            InputAction.PREV_CANDIDATE -> {
                if (context.suggestionList.isNullOrEmpty()) {
                    context.updateSuggestions()
                } else {
                    context.chooseAdjacentSuggestion(false)
                }
                return true
            }
            InputAction.TOGGLE_EXPAND_CANDIDATES -> {
                context.toggleExpandCandidates()
                return true
            }
            InputAction.CURSOR_UP -> {
                if (context.suggestionList.isNotEmpty()) {
                    context.chooseAdjacentSuggestion(false)
                }
                return true
            }
            InputAction.CURSOR_DOWN -> {
                if (context.suggestionList.isNotEmpty()) {
                    context.chooseAdjacentSuggestion(true)
                }
                return true
            }
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
        val selectedIdx = context.getCurrentCandidateIndex()
        if (selectedIdx in context.candidateList.indices) {
            context.pickComposingCandidate(selectedIdx)
            return true
        }
        finish(context)
        context.changeState(InputStateIdle.INSTANCE)
        return true
    }

    override fun processDpadUp(context: InputEngine, isShifted: Boolean): Boolean {
        if (context.suggestionList.isNotEmpty()) {
            context.chooseAdjacentSuggestion(false)
            return true
        }
        return true
    }

    override fun processDpadDown(context: InputEngine, isShifted: Boolean): Boolean {
        if (context.suggestionList.isNotEmpty()) {
            context.chooseAdjacentSuggestion(true)
            return true
        }
        return true
    }

    override fun processDpadLeft(context: InputEngine, isShifted: Boolean): Boolean {
        context.moveComposingCursor(-1)
        return true
    }

    override fun processDpadRight(context: InputEngine, isShifted: Boolean): Boolean {
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

    override fun processPickCandidate(context: InputEngine, index: Int): Boolean {
        context.pickComposingCandidate(index)
        context.updateComposingText()
        return true
    }

    override fun processSetCandidateIndex(context: InputEngine, index: Int) {
        if (context.suggestionList.isNotEmpty()) {
            if (index == -1 || index in context.suggestionList.indices) {
                context.mCurrentSuggestionIndex = index
                context.mService?.requestChooseCandidate(context.mCurrentSuggestionIndex)
                context.updateFloatingCandidates()
                context.updateComposingText()
            }
        }
    }

    override fun formatComposingText(context: InputEngine, builder: SpannableStringBuilder) {
        var cursor = context.getComposingCursor()
        val convertedComposing = context.convertText(context.composingBuffer.composingText)
        val composingStr = convertedComposing.toString()
        cursor = max(0, min(composingStr.length, cursor))

        val firstPart = composingStr.substring(0, cursor)
        val secondPart = composingStr.substring(cursor)
        val converterText = context.composingBuffer.romajiBuffer

        val isPartial = (cursor < composingStr.length)

        val symStart = builder.length
        builder.append(firstPart)
        builder.append(converterText)
        val targetEnd = builder.length

        val nonTargetStart = builder.length
        builder.append(secondPart)
        val nonTargetEnd = builder.length

        if (isPartial) {
            builder.setSpan(BackgroundColorSpan(context.mColorConverting), symStart, targetEnd, Spanned.SPAN_COMPOSING)
            if (nonTargetStart < nonTargetEnd) {
                builder.setSpan(BackgroundColorSpan(context.mColorComposing), nonTargetStart, nonTargetEnd, Spanned.SPAN_COMPOSING)
            }
        } else {
            builder.setSpan(BackgroundColorSpan(context.mColorComposing), symStart, builder.length, Spanned.SPAN_COMPOSING)
        }
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
            InputAction.COMMIT -> {
                return processEnter(context)
            }
            InputAction.CONVERT -> {
                return processSpace(context, false)
            }
            InputAction.NEXT_CANDIDATE -> {
                context.chooseNextCandidate()
                return true
            }
            InputAction.PREV_CANDIDATE -> {
                context.choosePrevCandidate()
                return true
            }
            InputAction.TOGGLE_EXPAND_CANDIDATES -> {
                context.toggleExpandCandidates()
                return true
            }
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

    override fun processDpadUp(context: InputEngine, isShifted: Boolean): Boolean {
        context.choosePrevCandidate()
        return true
    }

    override fun processDpadDown(context: InputEngine, isShifted: Boolean): Boolean {
        context.chooseNextCandidate()
        return true
    }

    override fun processDpadLeft(context: InputEngine, isShifted: Boolean): Boolean {
        if (isShifted && context.resizeFocusedSegment(-1)) {
            return true
        }
        if (!context.focusPrevSegment()) {
            context.choosePrevCandidate()
        }
        return true
    }

    override fun processDpadRight(context: InputEngine, isShifted: Boolean): Boolean {
        if (isShifted && context.resizeFocusedSegment(1)) {
            return true
        }
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

    override fun processPickCandidate(context: InputEngine, index: Int): Boolean {
        if (context.segmentManager.hasSegments()) {
            context.setCandidateIndex(index)
        } else {
            context.pickCandidate(index)
            context.updateComposingText()
        }
        return true
    }

    override fun processSetCandidateIndex(context: InputEngine, index: Int) {
        if (context.segmentManager.hasSegments()) {
            val active = context.segmentManager.getActiveSegment()
            if (active != null && index in active.candidates.indices) {
                active.selectedIndex = index
                context.mCurrentCandidateIndex = index
                context.mService?.requestChooseCandidate(context.mCurrentCandidateIndex)
                context.updateFloatingCandidates()
                context.updateComposingText()
            }
            return
        }
        if (context.candidateList.isNotEmpty()) {
            if (index in context.candidateList.indices) {
                context.mCurrentCandidateIndex = index
                context.mService?.requestChooseCandidate(context.mCurrentCandidateIndex)
                context.updateFloatingCandidates()
                context.updateComposingText()
            }
        }
    }

    override fun formatComposingText(context: InputEngine, builder: SpannableStringBuilder) {
        val targetStart = builder.length
        if (context.segmentManager.hasSegments()) {
            val segments = context.segmentManager.segments ?: emptyList()
            val focusedIdx = context.segmentManager.focusedSegmentIndex
            val activeSeg = context.segmentManager.getActiveSegment()
            val activeCand = activeSeg?.getSelectedCandidate()

            if (activeCand != null && activeCand.isZenz()) {
                val segStart = builder.length
                builder.append(context.convertText(activeCand.candidate ?: ""))
                val segEnd = builder.length
                builder.setSpan(BackgroundColorSpan(context.mColorConverting), segStart, segEnd, Spanned.SPAN_COMPOSING)
            } else {
                for (i in segments.indices) {
                    val seg = segments[i]
                    val segStart = builder.length
                    builder.append(context.convertText(seg.getSelectedSurface()))
                    val segEnd = builder.length
                    if (i == focusedIdx) {
                        builder.setSpan(BackgroundColorSpan(context.mColorConverting), segStart, segEnd, Spanned.SPAN_COMPOSING)
                    } else {
                        builder.setSpan(BackgroundColorSpan(context.mColorComposing), segStart, segEnd, Spanned.SPAN_COMPOSING)
                    }
                }
            }
        } else {
            val stateText = getComposingText(context)
            builder.append(stateText)
            val converterText = context.composingBuffer.romajiBuffer
            builder.append(converterText)
            val targetEnd = builder.length
            builder.setSpan(BackgroundColorSpan(context.mColorConverting), targetStart, targetEnd, Spanned.SPAN_COMPOSING)
        }

        val remainingText = context.composingBuffer.getRemainingComposingText()
        if (remainingText != null) {
            val remStart = builder.length
            builder.append(context.convertText(remainingText))
            val remEnd = builder.length
            builder.setSpan(BackgroundColorSpan(context.mColorComposing), remStart, remEnd, Spanned.SPAN_COMPOSING)
        }
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
            InputAction.COMMIT -> {
                return processEnter(context)
            }
            InputAction.CONVERT -> {
                return processSpace(context, false)
            }
            InputAction.NEXT_CANDIDATE -> {
                if (context.candidateList.isNotEmpty()) {
                    context.chooseNextCandidate()
                    return true
                }
            }
            InputAction.PREV_CANDIDATE -> {
                if (context.candidateList.isNotEmpty()) {
                    context.choosePrevCandidate()
                    return true
                }
            }
            InputAction.TOGGLE_EXPAND_CANDIDATES -> {
                context.toggleExpandCandidates()
                return true
            }
            InputAction.RECONVERT -> {
                val lastCand = context.lastCommittedCandidate
                if (!lastCand.isNullOrEmpty()) {
                    context.mService?.prepareReConversion(lastCand)
                    return true
                }
            }
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

    override fun processDpadUp(context: InputEngine, isShifted: Boolean): Boolean = !context.canMoveCursor()
    override fun processDpadDown(context: InputEngine, isShifted: Boolean): Boolean = !context.canMoveCursor()
    override fun processDpadLeft(context: InputEngine, isShifted: Boolean): Boolean = !context.canMoveCursor()
    override fun processDpadRight(context: InputEngine, isShifted: Boolean): Boolean = !context.canMoveCursor()

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

    override fun processPickCandidate(context: InputEngine, index: Int): Boolean {
        context.pickIdleCandidate(index)
        return true
    }

    override fun processSetCandidateIndex(context: InputEngine, index: Int) {}

    override fun formatComposingText(context: InputEngine, builder: SpannableStringBuilder) {
        val converterText = context.composingBuffer.romajiBuffer
        if (converterText.isNotEmpty()) {
            val bgStart = builder.length
            builder.append(converterText)
            val bgEnd = builder.length
            builder.setSpan(BackgroundColorSpan(context.mColorComposing), bgStart, bgEnd, Spanned.SPAN_COMPOSING)
        }
    }
}
