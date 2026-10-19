package io.github.kachaya.ime.engine

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import io.github.kachaya.ime.BuildConfig
import io.github.kachaya.ime.InputService
import io.github.kachaya.ime.R
import io.github.kachaya.ime.SettingsActivity
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.zenz.ZenzEngine
import kotlin.math.max
import kotlin.math.min

/**
 * 入力・変換ロジックを統括するエンジンクラスです。
 */
class InputEngine(val context: Context?, private val mDictionary: Dictionary?) {

    private val mService: InputService? = if (context is InputService) context else null

    val composingBuffer: ComposingBuffer = ComposingBuffer()

    fun getComposing(): CharSequence {
        return composingBuffer.romajiBuffer
    }

    private var mColorComposing: Int = 0
    private var mColorConverting: Int = 0

    private val mFullWidthSeparatorMap: MutableMap<String, String> = HashMap()

    var mode: InputMode = InputModeFullHiragana.INSTANCE
        private set

    var state: InputState = InputStateIdle.INSTANCE
        private set

    var candidateList: List<Candidate> = emptyList()
        private set

    private var mCurrentCandidateIndex: Int = 0

    var suggestionList: List<String> = emptyList()
        private set

    private var mCurrentSuggestionIndex: Int = 0

    private var mDisplayState: Boolean = true
    private var mUseJisx0201Kana: Boolean = false
    private var mEnableLearning: Boolean = true
    private var mShowAnnotation: Boolean = true

    var lastCommittedCandidate: String? = null

    private var mShortcutMap: Map<Int, InputAction>? = null
    private var mViterbiDecoder: ViterbiDecoder? = null

    val segmentManager: SegmentManager = SegmentManager()

    val zenzEngine: ZenzEngine? = if (context != null) ZenzEngine(context) else null

    fun isZenzActive(): Boolean {
        return zenzEngine != null && zenzEngine.isEnabled
    }

    fun isViterbiActive(): Boolean {
        return !isZenzActive()
    }

    init {
        mViterbiDecoder = ViterbiDecoder(mDictionary, 20)

        mFullWidthSeparatorMap["."] = "。"
        RomajiConverter.setSeparator(".", "。")
        mFullWidthSeparatorMap[","] = "、"
        RomajiConverter.setSeparator(",", "、")

        if (this.context != null) {
            readPrefs()
            updateColors()
        } else {
            mColorComposing = 0
            mColorConverting = 0
        }
    }

    fun updateColors() {
        if (context != null) {
            val res = context.resources
            mColorComposing = res.getColor(R.color.composing_composing, context.theme)
            mColorConverting = res.getColor(R.color.composing_converting, context.theme)
        }
    }

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(javaClass.simpleName, msg)
        }
    }

    fun readPrefs() {
        if (context == null) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val useKutenJp = prefs.getBoolean("use_kuten_jp", true)
        val kuten = if (useKutenJp) "。" else "．"
        mFullWidthSeparatorMap["."] = kuten
        RomajiConverter.setSeparator(".", kuten)

        val useToutenJp = prefs.getBoolean("use_touten_jp", true)
        val touten = if (useToutenJp) "、" else "，"
        mFullWidthSeparatorMap[","] = touten
        RomajiConverter.setSeparator(",", touten)

        mDisplayState = false
        mUseJisx0201Kana = prefs.getBoolean("use_jisx0201_kana", false)
        mEnableLearning = prefs.getBoolean("enable_learning", true)
        mShowAnnotation = prefs.getBoolean("show_annotation", true)

        mShortcutMap = KeyShortcutManager.loadMappings(prefs)

        zenzEngine?.reloadPreferences(prefs)

        logI("readPrefs: useKutenJp =$useKutenJp")
        logI("readPrefs: useToutenJp =$useToutenJp")
        logI("readPrefs: useJisx0201Kana =$mUseJisx0201Kana")
        logI("readPrefs: enableLearning =$mEnableLearning")
        logI("readPrefs: displayState =$mDisplayState")
    }

    fun isComposing(): Boolean = composingBuffer.hasComposing()

    fun canMoveCursor(): Boolean = !isComposing()

    fun ignoresKeyEvent(): Boolean = false

    fun processKey(code: Int) {
        val consumed = state.processKey(this, code)
        if (!consumed) {
            mode.processKey(this, code)
        }
        updateComposingText()
    }

    fun processShortcutKey(keyCode: Int, isShift: Boolean = false): Boolean {
        var key = keyCode
        if (isShift) {
            key = key or KeyShortcutManager.SHIFT_MASK
        } else if (KeyShortcutManager.isOsReserved(keyCode)) {
            return false
        }
        var action = mShortcutMap?.get(key)
        if (action == null) {
            action = KeyShortcutManager.getDefaultMappings()[key]
        }
        if (action == null || action == InputAction.NONE) {
            return false
        }
        if (state.processInputAction(this, action)) {
            updateComposingText()
            return true
        }
        if (mode.processInputAction(this, action)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun processTab(isShifted: Boolean): Boolean {
        if (state.processTab(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleSpace(isShifted: Boolean = false): Boolean {
        if (composingBuffer.hasRomaji()) {
            flushRomaji()
        }
        if (state.processSpace(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleDpadLeft(isShifted: Boolean): Boolean {
        if (state.isConverting()) {
            if (isShifted) {
                if (resizeFocusedSegment(-1)) {
                    return true
                }
            }
        }
        if (state.processDpadLeft(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleDpadRight(isShifted: Boolean): Boolean {
        if (state.isConverting()) {
            if (isShifted) {
                if (resizeFocusedSegment(1)) {
                    return true
                }
            }
        }
        if (state.processDpadRight(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleDpadUp(isShifted: Boolean): Boolean {
        if (state.processDpadUp(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleDpadDown(isShifted: Boolean): Boolean {
        if (state.processDpadDown(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun sendDownUpKeyEvents(keyCode: Int) {
        mService?.sendDownUpKeyEvents(keyCode)
    }

    fun sendShiftEnter() {
        val ic = mService?.currentInputConnection
        if (ic != null) {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON)
            ic.sendKeyEvent(down)
            ic.sendKeyEvent(up)
        }
    }

    fun processRomaji(code: Int) {
        var currentCode = code
        if (currentCode == ' '.code) {
            flushRomaji()
            commitRomajiText(" ", ' ', false)
            return
        }

        val isUpper = currentCode in 'A'.code..'Z'.code
        if (isUpper) {
            currentCode = Character.toLowerCase(currentCode)
        }

        if (Character.isDigit(currentCode)) {
            flushRomaji()
            commitRomajiText(currentCode.toChar().toString(), currentCode.toChar(), isUpper)
            return
        }

        composingBuffer.appendRomaji(currentCode.toChar())
        val res = RomajiConverter.convert(composingBuffer.romajiBuffer)
        if (res.convertedText.isNotEmpty()) {
            composingBuffer.clearRomaji()
            composingBuffer.appendRomaji(res.pendingRomaji)
            commitRomajiText(res.convertedText, res.convertedText[0], isUpper)
            if (res.pendingRomaji.isEmpty()) {
                onFinishRomaji()
            }
        }
    }

    fun flushRomaji(): Boolean {
        if (!composingBuffer.hasRomaji()) {
            return false
        }
        val flushed = composingBuffer.flushRomaji()
        if (flushed.isNotEmpty()) {
            commitRomajiText(flushed, flushed[0], false)
        }
        return true
    }

    fun commitRomajiText(text: String, initial: Char, isUpper: Boolean) {
        if (mode == InputModeHalfLatin.INSTANCE || mode == InputModeFullLatin.INSTANCE) {
            val committedText = mFullWidthSeparatorMap[text] ?: text
            state.processText(this, committedText, initial, isUpper)
            return
        }

        val committedText = mFullWidthSeparatorMap[text] ?: text
        state.processText(this, committedText, initial, isUpper)
    }

    fun onFinishRomaji() {
        state.onFinishRomaji(this)
    }

    fun handleKanaKey() {
        flushRomaji()
        state.finish(this)
        changeState(InputStateIdle.INSTANCE)
        if (mode != InputModeFullHiragana.INSTANCE) {
            changeMode(InputModeFullHiragana.INSTANCE, false)
        }
        updateComposingText()
    }

    fun handleBackKey(): Boolean {
        if (state.handleBackKey(this)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleEnter(): Boolean {
        if (state.processEnter(this)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleBackspace(): Boolean {
        val currentState = state
        currentState.beforeBackspace(this)

        if (composingBuffer.hasRomaji()) {
            composingBuffer.deleteLastRomajiChar()
            currentState.afterBackspace(this)
            updateComposingText()
            return true
        }

        if (currentState.processBackspace(this)) {
            currentState.afterBackspace(this)
            updateComposingText()
            return true
        }

        if (!currentState.isTransient()) {
            return false
        }
        currentState.afterBackspace(this)

        updateComposingText()
        return true
    }

    fun handleCancel(): Boolean {
        var result = false
        if (composingBuffer.hasRomaji()) {
            composingBuffer.clearRomaji()
            result = true
        }
        if (state.handleCancel(this)) {
            result = true
        }
        if (result) {
            updateComposingText()
        }
        lastCommittedCandidate = null
        return result
    }

    fun toASCIIMode() {
        flushRomaji()
        changeMode(InputModeHalfLatin.INSTANCE, true)
        updateComposingText()
    }

    fun toggleKana() {
        flushRomaji()
        state.toggleKana(this)
        updateComposingText()
    }

    fun toggleEnglishJapanese() {
        if (mode == InputModeHalfLatin.INSTANCE || mode == InputModeFullLatin.INSTANCE) {
            handleKanaKey()
        } else {
            flushRomaji()
            state.finish(this)
            changeState(InputStateIdle.INSTANCE)
            changeMode(InputModeHalfLatin.INSTANCE, true)
            updateComposingText()
        }
    }

    fun handleKillLine(): Boolean {
        val ic = mService?.currentInputConnection ?: return false
        val textAfter = ic.getTextAfterCursor(2000, 0)
        if (textAfter.isNullEmpty()) {
            return false
        }
        val textStr = textAfter?.toString() ?: ""
        val len: Int
        var newlineIdx = -1
        for (i in textStr.indices) {
            if (textStr[i] == '\n' || textStr[i] == '\r') {
                newlineIdx = i
                break
            }
        }
        len = when {
            newlineIdx == 0 -> 1
            newlineIdx > 0 -> newlineIdx
            else -> textStr.length
        }
        return ic.deleteSurroundingText(0, len)
    }

    fun handleKillLineBackward(): Boolean {
        val ic = mService?.currentInputConnection ?: return false
        val textBefore = ic.getTextBeforeCursor(2000, 0)
        if (textBefore.isNullEmpty()) {
            return false
        }
        val textStr = textBefore?.toString() ?: ""
        val len: Int
        var newlineIdx = -1
        for (i in textStr.length - 1 downTo 0) {
            if (textStr[i] == '\n' || textStr[i] == '\r') {
                newlineIdx = i
                break
            }
        }
        len = when {
            newlineIdx == textStr.length - 1 -> 1
            newlineIdx >= 0 -> textStr.length - 1 - newlineIdx
            else -> textStr.length
        }
        return ic.deleteSurroundingText(len, 0)
    }

    fun launchSettings() {
        val ctx = context ?: return
        try {
            val intent = Intent(ctx, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e("InputEngine", "Failed to launch SettingsActivity", e)
        }
    }

    fun openEmojiPicker() {
        if (context is InputService) {
            context.openEmojiPicker()
        }
    }

    fun getToggledKanaMode(): InputMode? {
        return mode.getToggledKanaMode(this)
    }

    fun convertText(text: CharSequence): CharSequence {
        return mode.convertText(text)
    }

    fun commitTextInternal(text: CharSequence, newCursorPosition: Int) {
        mService?.commitText(text, newCursorPosition)
    }

    fun resetOnStartInput() {
        reset()
        if (state.isTransient()) {
            changeState(InputStateIdle.INSTANCE)
        }
        mService?.requestUIUpdate()
        updateComposingText()
    }

    fun getCurrentSuggestion(): String? {
        if (suggestionList.isEmpty()) {
            return null
        }
        if (mCurrentSuggestionIndex !in suggestionList.indices) {
            return null
        }
        return suggestionList[mCurrentSuggestionIndex]
    }

    fun chooseAdjacentSuggestion(isForward: Boolean) {
        if (suggestionList.isEmpty()) {
            return
        }
        var nextIndex = mCurrentSuggestionIndex
        if (isForward) {
            nextIndex++
        } else {
            nextIndex--
        }

        val listSize = suggestionList.size
        if (nextIndex > listSize - 1) {
            nextIndex = 0
        } else if (nextIndex < 0) {
            nextIndex = listSize - 1
        }

        mCurrentSuggestionIndex = nextIndex
        mService?.requestChooseCandidate(mCurrentSuggestionIndex)
        updateFloatingCandidates()
    }

    fun chooseNextCandidate() {
        chooseCandidateInternal(true)
    }

    fun choosePrevCandidate() {
        chooseCandidateInternal(false)
    }

    private fun chooseCandidateInternal(isForward: Boolean) {
        if (segmentManager.hasSegments()) {
            val active = segmentManager.getActiveSegment()
            if (active == null || active.candidates.isEmpty()) return
            val listSize = active.candidates.size
            var nextIndex = active.selectedIndex + (if (isForward) 1 else -1)

            if (nextIndex > listSize - 1) {
                nextIndex = 0
            } else if (nextIndex < 0) {
                nextIndex = listSize - 1
            }

            active.selectedIndex = nextIndex
            mCurrentCandidateIndex = nextIndex
            mService?.requestChooseCandidate(mCurrentCandidateIndex)
            updateFloatingCandidates()
            updateComposingText()
            return
        }

        if (candidateList.isEmpty()) {
            return
        }
        val nextIndex = mCurrentCandidateIndex + (if (isForward) 1 else -1)

        val listSize = candidateList.size
        if (nextIndex > listSize - 1) {
            mCurrentCandidateIndex = 0
            mService?.requestChooseCandidate(mCurrentCandidateIndex)
            updateFloatingCandidates()
            updateComposingText()
            return
        } else if (nextIndex < 0) {
            changeState(InputStateComposing.INSTANCE)
            updateSuggestions()
            updateComposingText()

            mCurrentCandidateIndex = 0
            return
        }
        mCurrentCandidateIndex = nextIndex
        mService?.requestChooseCandidate(mCurrentCandidateIndex)
        updateFloatingCandidates()
        updateComposingText()
    }

    fun pickCandidateViewManually(index: Int) {
        if (state.isConverting()) {
            if (segmentManager.hasSegments()) {
                setCandidateIndex(index)
            } else {
                pickCandidate(index)
                updateComposingText()
            }
        } else if (state == InputStateComposing.INSTANCE) {
            pickComposingCandidate(index)
            updateComposingText()
        } else if (state == InputStateIdle.INSTANCE) {
            if (index in candidateList.indices) {
                val c = candidateList[index]
                val candStr = c.candidate ?: ""
                commitTextInternal(candStr, 1)

                val key = composingBuffer.getSearchQuery()
                val raw = c.rawCandidate
                if (mEnableLearning && raw != null && !c.isZenz()) {
                    val lastCand = lastCommittedCandidate
                    if (lastCand != null) {
                        mDictionary?.learnTransition(lastCand, candStr)
                    }
                    if (mDictionary != null) {
                        learnCandidateEntries(mDictionary, c, key.ifEmpty { candStr }, raw)
                    }
                }

                lastCommittedCandidate = candStr
                updateIdlePredictions()
            }
        }
    }

    fun pickComposingCandidate(index: Int) {
        if (index !in candidateList.indices) {
            return
        }

        val c = candidateList[index]
        val candidateStr = c.candidate ?: ""
        val finalCandidate = mode.convertText(candidateStr)

        commitTextInternal(finalCandidate, 1)

        if (mDictionary != null) {
            val key = composingBuffer.getSearchQuery()
            val raw = c.rawCandidate
            if (mEnableLearning && !c.isZenz() && raw != null) {
                val lastCand = lastCommittedCandidate
                if (lastCand != null) {
                    mDictionary.learnTransition(lastCand, finalCandidate.toString())
                }
                learnCandidateEntries(mDictionary, c, key, raw)
            }
        }

        lastCommittedCandidate = finalCandidate.toString()

        clearBuffers()
        changeState(InputStateIdle.INSTANCE)
    }

    fun updateIdlePredictions() {
        if (state != InputStateIdle.INSTANCE) return
        val lastCand = lastCommittedCandidate
        if (mDictionary == null || lastCand == null) {
            clearCandidates()
            return
        }
        val predictedSurfaces = mDictionary.getPredictedSurfaceWords(lastCand, 10)
        if (predictedSurfaces.isNullOrEmpty()) {
            clearCandidates()
            return
        }
        val cands = ArrayList<Candidate>()
        for (surf in predictedSurfaces) {
            val c = Candidate(surf, surf, "Zenz AI")
            c.type = Candidate.Type.PREDICTION
            cands.add(c)
        }
        candidateList = cands
        mCurrentCandidateIndex = 0
        updateCandidates()
    }

    fun hasComposing(): Boolean = composingBuffer.hasRomaji()

    fun getComposingText(): StringBuilder = composingBuffer.composingText

    fun getComposingCursor(): Int = composingBuffer.getComposingCursor()

    fun setComposingCursor(cursor: Int) {
        composingBuffer.setComposingCursor(cursor)
        updateUI()
    }

    fun moveComposingCursor(delta: Int) {
        composingBuffer.moveComposingCursor(delta)
        updateUI()
    }

    fun insertComposingText(text: String?) {
        composingBuffer.insertComposingText(text)
        if (state == InputStateComposing.INSTANCE) {
            updateSuggestions()
        }
        updateUI()
    }

    fun deleteComposingCharBeforeCursor(): Boolean {
        val res = composingBuffer.deleteComposingCharBeforeCursor()
        if (res) {
            if (state == InputStateComposing.INSTANCE) {
                updateSuggestions()
            }
            updateUI()
        }
        return res
    }

    fun deleteComposingCharAfterCursor(): Boolean {
        val res = composingBuffer.deleteComposingCharAfterCursor()
        if (res) {
            if (state == InputStateComposing.INSTANCE) {
                updateSuggestions()
            }
            updateUI()
        }
        return res
    }

    fun killComposingToLineEnd(): Boolean {
        val res = composingBuffer.killComposingToLineEnd()
        if (res) {
            if (state == InputStateComposing.INSTANCE) {
                updateSuggestions()
            }
            updateUI()
        }
        return res
    }

    fun killComposingToLineStart(): Boolean {
        val res = composingBuffer.killComposingToLineStart()
        if (res) {
            if (state == InputStateComposing.INSTANCE) {
                updateSuggestions()
            }
            updateUI()
        }
        return res
    }

    fun resetComposingCursor() {
        composingBuffer.resetComposingCursor()
    }

    fun restoreRemainingComposingText() {
        composingBuffer.restoreRemainingComposingText()
    }

    fun updateComposingText() {
        val ct = SpannableStringBuilder()

        if (state.isConverting()) {
            val targetStart = ct.length
            if (segmentManager.hasSegments()) {
                val segments = segmentManager.segments ?: emptyList()
                val focusedIdx = segmentManager.focusedSegmentIndex
                val activeSeg = segmentManager.getActiveSegment()
                val activeCand = activeSeg?.getSelectedCandidate()

                if (activeCand != null && activeCand.isZenz()) {
                    val segStart = ct.length
                    ct.append(convertText(activeCand.candidate ?: ""))
                    val segEnd = ct.length
                    ct.setSpan(BackgroundColorSpan(mColorConverting), segStart, segEnd, Spanned.SPAN_COMPOSING)
                } else {
                    for (i in segments.indices) {
                        val seg = segments[i]
                        val segStart = ct.length
                        ct.append(convertText(seg.getSelectedSurface()))
                        val segEnd = ct.length
                        if (i == focusedIdx) {
                            ct.setSpan(BackgroundColorSpan(mColorConverting), segStart, segEnd, Spanned.SPAN_COMPOSING)
                        } else {
                            ct.setSpan(BackgroundColorSpan(mColorComposing), segStart, segEnd, Spanned.SPAN_COMPOSING)
                        }
                    }
                }
            } else {
                val stateText = state.getComposingText(this)
                if (stateText != null) {
                    ct.append(stateText)
                }
                val converterText = composingBuffer.romajiBuffer
                ct.append(converterText)
                val targetEnd = ct.length
                ct.setSpan(BackgroundColorSpan(mColorConverting), targetStart, targetEnd, Spanned.SPAN_COMPOSING)
            }

            val remainingText = composingBuffer.getRemainingComposingText()
            if (remainingText != null) {
                val remStart = ct.length
                ct.append(convertText(remainingText))
                val remEnd = ct.length
                ct.setSpan(BackgroundColorSpan(mColorComposing), remStart, remEnd, Spanned.SPAN_COMPOSING)
            }
        } else if (state.isTransient()) {
            if (state == InputStateComposing.INSTANCE) {
                var cursor = getComposingCursor()
                val convertedComposing = convertText(composingBuffer.composingText)
                val composingStr = convertedComposing.toString()
                cursor = max(0, min(composingStr.length, cursor))

                val firstPart = composingStr.substring(0, cursor)
                val secondPart = composingStr.substring(cursor)
                val converterText = composingBuffer.romajiBuffer

                val isPartial = (cursor < composingStr.length)

                val symStart = ct.length
                ct.append(firstPart)
                ct.append(converterText)
                val targetEnd = ct.length

                val nonTargetStart = ct.length
                ct.append(secondPart)
                val nonTargetEnd = ct.length

                if (isPartial) {
                    ct.setSpan(BackgroundColorSpan(mColorConverting), symStart, targetEnd, Spanned.SPAN_COMPOSING)
                    if (nonTargetStart < nonTargetEnd) {
                        ct.setSpan(BackgroundColorSpan(mColorComposing), nonTargetStart, nonTargetEnd, Spanned.SPAN_COMPOSING)
                    }
                } else {
                    ct.setSpan(BackgroundColorSpan(mColorComposing), symStart, ct.length, Spanned.SPAN_COMPOSING)
                }
            } else {
                val bgStart = ct.length
                val stateText = state.getComposingText(this)
                if (stateText != null) {
                    ct.append(stateText)
                }
                val converterText = composingBuffer.romajiBuffer
                ct.append(converterText)
                val bgEnd = ct.length
                ct.setSpan(BackgroundColorSpan(mColorComposing), bgStart, bgEnd, Spanned.SPAN_COMPOSING)
            }
        } else {
            val converterText = composingBuffer.romajiBuffer
            if (converterText.isNotEmpty()) {
                val bgStart = ct.length
                ct.append(converterText)
                val bgEnd = ct.length
                ct.setSpan(BackgroundColorSpan(mColorComposing), bgStart, bgEnd, Spanned.SPAN_COMPOSING)
            }
        }

        val totalLen = ct.length
        if (totalLen != 0) {
            val underline = UnderlineSpan()
            ct.setSpan(underline, 0, totalLen, Spanned.SPAN_COMPOSING)
        }

        mService?.setComposingText(ct, 1)
        updateFloatingCandidates()
    }

    fun conversionStart() {
        composingBuffer.splitComposingAtCursorIfNeeded()

        val success = conversionStartInternal(false)
        if (!success) {
            val sb = StringBuilder(composingBuffer.composingText)
            commitTextInternal(mode.convertText(sb), 1)
            reset()
            changeState(InputStateIdle.INSTANCE)
        }
    }

    fun getSegmentCandidates(segmentReading: String, preferredSurface: String?): List<Candidate> {
        val rawCands = ArrayList<Candidate>()

        if (isViterbiActive() && mViterbiDecoder != null) {
            try {
                val viterbiCands = mViterbiDecoder?.decode(segmentReading, null)
                if (viterbiCands != null) {
                    rawCands.addAll(viterbiCands)
                }
            } catch (e: Exception) {
                Log.e("InputEngine", "Viterbi decode error for segment: $segmentReading", e)
            }
        }

        if (mDictionary != null) {
            val dictCands = mDictionary.findCandidates(segmentReading)
            if (dictCands != null) {
                rawCands.addAll(dictCands)
            }
        }

        val wideKatakana = RomajiConverter.toWideKatakana(segmentReading)
        val halfKatakana = RomajiConverter.toHalfKatakana(segmentReading)

        val seen = HashSet<String>()
        val convertedCands = ArrayList<Candidate>()
        val plainCands = ArrayList<Candidate>()

        if (!preferredSurface.isNullEmpty()) {
            val prefSurf = preferredSurface!!
            seen.add(prefSurf)
            var prefCand: Candidate? = null
            for (c in rawCands) {
                if (prefSurf == c.candidate) {
                    prefCand = c
                    break
                }
            }
            if (prefCand == null) {
                prefCand = Candidate(prefSurf, prefSurf, "Zenz AI")
            }
            if (isPlainReadingCandidate(segmentReading, prefSurf)) {
                plainCands.add(prefCand)
            } else {
                convertedCands.add(prefCand)
            }
        }

        for (c in rawCands) {
            val candStr = c.candidate
            if (!candStr.isNullEmpty()) {
                if (seen.add(getCandidateDisplayKey(c))) {
                    if (isPlainReadingCandidate(segmentReading, candStr)) {
                        plainCands.add(c)
                    } else {
                        convertedCands.add(c)
                    }
                }
            }
        }

        if (seen.add(segmentReading)) {
            plainCands.add(Candidate(segmentReading, segmentReading, "Zenz AI"))
        }
        if (seen.add(wideKatakana)) {
            plainCands.add(Candidate(wideKatakana, wideKatakana, "Zenz AI"))
        }
        if (seen.add(halfKatakana)) {
            plainCands.add(Candidate(halfKatakana, halfKatakana, "Zenz AI"))
        }

        val merged = ArrayList<Candidate>()
        merged.addAll(convertedCands)
        merged.addAll(plainCands)

        return merged
    }

    fun syncActiveSegment() {
        if (segmentManager.hasSegments()) {
            val active = segmentManager.getActiveSegment()
            if (active != null) {
                candidateList = active.candidates
                mCurrentCandidateIndex = active.selectedIndex
                updateCandidates()
                updateFloatingCandidates()
                updateComposingText()
            }
        }
    }

    fun focusNextSegment(): Boolean {
        if (segmentManager.focusNextSegment()) {
            syncActiveSegment()
            return true
        }
        return false
    }

    fun focusPrevSegment(): Boolean {
        if (segmentManager.focusPrevSegment()) {
            syncActiveSegment()
            return true
        }
        return false
    }

    fun getSegments(): List<Segment>? = segmentManager.segments

    fun getFocusedSegmentIndex(): Int = segmentManager.focusedSegmentIndex

    fun getMultiSegmentText(): String? = segmentManager.getMultiSegmentText()

    fun resizeFocusedSegment(delta: Int): Boolean {
        val res = segmentManager.resizeFocusedSegment(
            delta,
            composingBuffer.getSearchQuery(),
            { reading, preferredSurface -> getSegmentCandidates(reading, preferredSurface) },
            if (isViterbiActive()) mViterbiDecoder else null
        )
        if (res) {
            syncActiveSegment()
        }
        return res
    }

    private fun conversionStartInternal(lastCandidate: Boolean): Boolean {
        val query = composingBuffer.getSearchQuery()

        val segments = ArrayList<Segment>()
        if (isViterbiActive() && mViterbiDecoder != null) {
            try {
                val viterbiCands = mViterbiDecoder?.decode(query, null)
                if (!viterbiCands.isNullOrEmpty() && viterbiCands[0].viterbiPathNode != null) {
                    var curr: ViterbiDecoder.PathNode? = viterbiCands[0].viterbiPathNode
                    val nodes = ArrayList<ViterbiDecoder.PathNode>()
                    while (curr != null && curr.prev != null) {
                        if (!curr.reading.isNullOrEmpty()) {
                            nodes.add(0, curr)
                        }
                        curr = curr.prev
                    }

                    val sb = StringBuilder()
                    for (n in nodes) {
                        sb.append(n.reading)
                    }

                    if (sb.toString() == query && nodes.isNotEmpty()) {
                        for (n in nodes) {
                            val reading = n.reading ?: ""
                            val segCands = getSegmentCandidates(reading, n.surface)
                            segments.add(Segment(reading, segCands, 0))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("InputEngine", "Viterbi decode error", e)
            }
        }

        if (segments.isEmpty()) {
            val fullCands = getSegmentCandidates(query, null)
            if (fullCands.isEmpty()) {
                return false
            }
            val initialIdx = if (lastCandidate) fullCands.size - 1 else 0
            segments.add(Segment(query, fullCands, initialIdx))
        }

        segmentManager.segments = segments

        Log.d("InputEngine", "=== [CONVERSION SEGMENT SPLIT LOG] ===")
        Log.d("InputEngine", "Total Segments Count: ${segments.size}")
        for (i in segments.indices) {
            val seg = segments[i]
            Log.d("InputEngine", " Segment[$i]: reading=\"${seg.reading}\", selected=\"${seg.getSelectedSurface()}\", candidatesCount=${seg.candidates.size}")
        }
        Log.d("InputEngine", "======================================")

        changeState(InputStateConverting.INSTANCE)
        syncActiveSegment()

        if (isZenzActive() && !hasUnconvertedAlphabet(query)) {
            val leftContext = getCleanLeftContext(20)
            Log.i("InputEngine", "[Zenz Request] query=\"$query\", leftContext=\"$leftContext\"")

            zenzEngine?.convertAsync(query, leftContext, object : ZenzEngine.ConversionCallback {
                override fun onCandidateAvailable(zenzCandidate: Candidate) {
                    if (!state.isConverting()) return

                    val candStr = zenzCandidate.candidate ?: ""
                    Log.i("InputEngine", "[Zenz Received Candidate] candidate=\"$candStr\"")

                    val active = segmentManager.getActiveSegment()
                    if (active != null) {
                        val reading = active.reading ?: query
                        val currentCands = active.candidates

                        val zenzCands = ArrayList<Candidate>()
                        val otherConvertedCands = ArrayList<Candidate>()
                        val plainReadingCands = ArrayList<Candidate>()
                        val seen = HashSet<String>()

                        for (c in currentCands) {
                            val cStr = c.candidate
                            if (cStr != null && cStr.isNotEmpty()) {
                                if (seen.add(cStr)) {
                                    if (isPlainReadingCandidate(reading, cStr)) {
                                        plainReadingCands.add(c)
                                    } else if (c.isZenz()) {
                                        zenzCands.add(c)
                                    } else {
                                        otherConvertedCands.add(c)
                                    }
                                }
                            }
                        }

                        if (seen.add(candStr)) {
                            if (isPlainReadingCandidate(reading, candStr)) {
                                plainReadingCands.add(zenzCandidate)
                            } else {
                                zenzCands.add(zenzCandidate)
                            }
                        }

                        val newCands = ArrayList<Candidate>()
                        newCands.addAll(zenzCands)
                        newCands.addAll(otherConvertedCands)
                        newCands.addAll(plainReadingCands)

                        var updatedIndex = active.selectedIndex
                        if (updatedIndex > 0) {
                            updatedIndex++
                        } else {
                            updatedIndex = 0
                        }

                        candidateList = newCands
                        active.candidates = newCands
                        active.selectedIndex = updatedIndex
                        mCurrentCandidateIndex = updatedIndex

                        updateCandidates()
                        updateFloatingCandidates()
                        updateComposingText()
                    }
                }

                override fun onConversionComplete() {
                    Log.d("InputEngine", "Zenz streaming conversion completely finished.")
                }
            })
        }

        return true
    }

    fun getCurrentCandidateIndex(): Int {
        return if (state.isConverting() && candidateList.isNotEmpty()) {
            mCurrentCandidateIndex
        } else if (suggestionList.isNotEmpty()) {
            mCurrentSuggestionIndex
        } else {
            0
        }
    }

    fun setCandidateIndex(index: Int) {
        if (state.isConverting()) {
            if (segmentManager.hasSegments()) {
                val active = segmentManager.getActiveSegment()
                if (active != null && index in active.candidates.indices) {
                    active.selectedIndex = index
                    mCurrentCandidateIndex = index
                    mService?.requestChooseCandidate(mCurrentCandidateIndex)
                    updateFloatingCandidates()
                    updateComposingText()
                }
                return
            }
            if (candidateList.isNotEmpty()) {
                if (index in candidateList.indices) {
                    mCurrentCandidateIndex = index
                    mService?.requestChooseCandidate(mCurrentCandidateIndex)
                    updateFloatingCandidates()
                    updateComposingText()
                }
            }
        } else if (suggestionList.isNotEmpty()) {
            if (index in suggestionList.indices) {
                mCurrentSuggestionIndex = index
                mService?.requestChooseCandidate(mCurrentSuggestionIndex)
                updateFloatingCandidates()
                updateComposingText()
            }
        }
    }

    private fun getCandidateDisplayKey(c: Candidate): String {
        return c.candidate ?: ""
    }

    private fun getAllCandidateItems(list: List<Candidate>): List<String> {
        val items = ArrayList<String>()
        val seen = HashSet<String>()
        for (c in list) {
            val item = getCandidateDisplayKey(c)
            if (seen.add(item)) {
                items.add(item)
            }
        }
        return items
    }

    private fun getAllSuggestionItems(list: List<String>): List<String> {
        return ArrayList(list)
    }

    fun updateFloatingCandidates() {
        val service = mService ?: return

        if (!service.isFloatingCandidateEnabled()) {
            service.hideFloatingCandidates()
            return
        }

        if (state.isConverting() && candidateList.isNotEmpty()) {
            val items = getAllCandidateItems(candidateList)
            service.showCandidateFlexboxPopup(null, items, mCurrentCandidateIndex)
        } else if (suggestionList.isNotEmpty()) {
            val items = getAllSuggestionItems(suggestionList)
            service.showCandidateFlexboxPopup(null, items, mCurrentSuggestionIndex)
        } else {
            service.hideFloatingCandidates()
        }
    }

    private fun updateCandidates() {
        mService?.setCandidateObjects(candidateList)
        updateFloatingCandidates()
    }

    fun updateSuggestions() {
        if (!composingBuffer.hasComposingText()) {
            suggestionList = emptyList()
            candidateList = emptyList()
            mService?.hideCandidatesView()
            mService?.hideFloatingCandidates()
            return
        }

        val query = composingBuffer.getComposingTextString()
        val rawCandidates = ArrayList<Candidate>()

        if (isViterbiActive()) {
            if (mViterbiDecoder != null) {
                try {
                    val viterbiCands = mViterbiDecoder?.decode(query, null)
                    if (viterbiCands != null) {
                        rawCandidates.addAll(viterbiCands)
                    }
                } catch (e: Exception) {
                    Log.e("InputEngine", "Viterbi decode error", e)
                }
            }

            if (mDictionary != null) {
                val dictCands = mDictionary.findCandidates(query)
                if (dictCands != null) {
                    rawCandidates.addAll(dictCands)
                }

                val suggestionKeys = mDictionary.findSuggestions(query)
                if (suggestionKeys != null) {
                    for (key in suggestionKeys) {
                        if (!key.isNullOrEmpty() && key != query) {
                            val keyCand = Candidate(key, key, "Zenz AI").setType(Candidate.Type.SUGGESTION)
                            rawCandidates.add(keyCand)

                            val keyCands = mDictionary.findCandidates(key)
                            if (keyCands != null) {
                                for (c in keyCands) {
                                    val sugCand = Candidate(c.rawCandidate, c.candidate, "Zenz AI")
                                        .setType(Candidate.Type.SUGGESTION)
                                    sugCand.setEntry(c.entry)
                                    rawCandidates.add(sugCand)
                                }
                            }
                        }
                    }
                }
            }
        }

        val predictedSurfaces = HashSet<String>()
        val lastCand = lastCommittedCandidate
        if (mDictionary != null && lastCand != null) {
            val predicted = mDictionary.getPredictedSurfaceWords(lastCand, 50)
            if (predicted != null) {
                predictedSurfaces.addAll(predicted)
            }
        }

        val wideKatakana = RomajiConverter.toWideKatakana(query)
        val halfKatakana = RomajiConverter.toHalfKatakana(query)

        val seen = HashSet<String>()
        val predictionList = ArrayList<Candidate>()
        val otherList = ArrayList<Candidate>()
        val suggestionListCands = ArrayList<Candidate>()

        for (c in rawCandidates) {
            val candStr = c.candidate
            if (!candStr.isNullEmpty()) {
                val key = getCandidateDisplayKey(c)
                if (seen.add(key)) {
                    if (predictedSurfaces.contains(candStr)) {
                        if (c.type == Candidate.Type.SYSTEM) {
                            c.type = Candidate.Type.PREDICTION
                        }
                        predictionList.add(c)
                    } else if (c.isSuggestion()) {
                        suggestionListCands.add(c)
                    } else {
                        otherList.add(c)
                    }
                }
            }
        }

        val merged = ArrayList<Candidate>()
        merged.addAll(predictionList)
        merged.addAll(otherList)
        merged.addAll(suggestionListCands)

        if (seen.add(query)) {
            merged.add(Candidate(query, query, "Zenz AI"))
        }
        if (seen.add(wideKatakana)) {
            merged.add(Candidate(wideKatakana, wideKatakana, "Zenz AI"))
        }
        if (seen.add(halfKatakana)) {
            merged.add(Candidate(halfKatakana, halfKatakana, "Zenz AI"))
        }

        candidateList = merged
        mCurrentCandidateIndex = 0

        val sugStrList = ArrayList<String>()
        for (c in merged) {
            sugStrList.add(c.candidate ?: "")
        }
        suggestionList = sugStrList

        mService?.setCandidateObjects(candidateList)
        updateFloatingCandidates()

        if (isZenzActive() && !hasUnconvertedAlphabet(query)) {
            val currentQuery = query
            val leftContext = getCleanLeftContext(20)
            Log.i("InputEngine", "[Zenz Live Suggest Request] query=\"$currentQuery\", leftContext=\"$leftContext\"")

            zenzEngine?.convertAsync(currentQuery, leftContext, object : ZenzEngine.ConversionCallback {
                override fun onCandidateAvailable(zenzCandidate: Candidate) {
                    if (state != InputStateComposing.INSTANCE) return

                    val nowQuery = composingBuffer.getComposingTextString()
                    if (currentQuery != nowQuery || hasUnconvertedAlphabet(nowQuery)) {
                        return
                    }

                    val candStr = zenzCandidate.candidate ?: ""
                    Log.i("InputEngine", "[Zenz Live Suggest Received] candidate=\"$candStr\"")

                    val currentCands = ArrayList(candidateList)
                    val seen = HashSet<String>()

                    val zenzConvertedCands = ArrayList<Candidate>()
                    val plainReadingCands = ArrayList<Candidate>()

                    for (c in currentCands) {
                        val cStr = c.candidate
                        if (cStr != null && cStr.isNotEmpty()) {
                            if (seen.add(cStr)) {
                                if (isPlainReadingCandidate(nowQuery, cStr)) {
                                    plainReadingCands.add(c)
                                } else {
                                    zenzConvertedCands.add(c)
                                }
                            }
                        }
                    }

                    if (seen.add(candStr)) {
                        val isZenzPlain = isPlainReadingCandidate(nowQuery, candStr)
                        if (isZenzPlain) {
                            plainReadingCands.add(zenzCandidate)
                        } else {
                            zenzConvertedCands.add(zenzCandidate)
                        }
                    }

                    val newCands = ArrayList<Candidate>()
                    newCands.addAll(zenzConvertedCands)
                    newCands.addAll(plainReadingCands)

                    candidateList = newCands
                    val sugStrList = ArrayList<String>()
                    for (c in newCands) {
                        sugStrList.add(c.candidate ?: "")
                    }
                    suggestionList = sugStrList

                    mService?.setCandidateObjects(candidateList)
                    updateFloatingCandidates()
                }

                override fun onConversionComplete() {
                    Log.d("InputEngine", "Zenz live suggest conversion completely finished.")
                }
            })
        }
    }

    private fun hasUnconvertedAlphabet(query: String?): Boolean {
        if (composingBuffer.hasRomaji()) {
            return true
        }
        if (query != null) {
            for (i in query.indices) {
                val c = query[i]
                if (RomajiConverter.isAlphabet(c.code) || (c in 'Ａ'..'Ｚ') || (c in 'ａ'..'ｚ')) {
                    return true
                }
            }
        }
        return false
    }

    private fun isPlainReadingCandidate(query: String?, candStr: String?): Boolean {
        if (candStr.isNullEmpty() || query.isNullEmpty()) {
            return false
        }
        if (candStr == query) {
            return true
        }
        val wideK = RomajiConverter.toWideKatakana(query)
        if (candStr == wideK) {
            return true
        }
        val halfK = RomajiConverter.toHalfKatakana(query)
        return candStr == halfK
    }

    fun getCurrentCandidate(): String {
        if (candidateList.isEmpty()) {
            return ""
        }
        val c = candidateList[mCurrentCandidateIndex]
        return c.candidate ?: ""
    }

    fun getCleanLeftContext(maxLen: Int): String {
        if (zenzEngine == null || !zenzEngine.isUseContext) {
            return ""
        }
        val service = mService ?: return ""
        val fullLeftContext = service.getTextBeforeCursor(maxLen)
        if (fullLeftContext.isNullEmpty()) {
            return ""
        }

        val composingText = composingBuffer.getComposingTextString()
        val romajiBuffer = composingBuffer.getRomajiString()
        val displayComposing = convertText(composingText).toString() + romajiBuffer

        if (displayComposing.isNotEmpty() && fullLeftContext.endsWith(displayComposing)) {
            return fullLeftContext.substring(0, fullLeftContext.length - displayComposing.length)
        }

        if (composingText.isNotEmpty() && fullLeftContext.endsWith(composingText)) {
            return fullLeftContext.substring(0, fullLeftContext.length - composingText.length)
        }

        var candidateTrim = displayComposing
        while (candidateTrim.isNotEmpty()) {
            if (fullLeftContext.endsWith(candidateTrim)) {
                return fullLeftContext.substring(0, fullLeftContext.length - candidateTrim.length)
            }
            candidateTrim = candidateTrim.substring(0, candidateTrim.length - 1)
        }

        return fullLeftContext
    }

    fun pickCurrentCandidate() {
        pickCandidate(mCurrentCandidateIndex)
    }

    fun pickCandidate(index: Int) {
        if (!state.isConverting()) {
            return
        }

        if (segmentManager.hasSegments()) {
            val segments = segmentManager.segments ?: emptyList()
            val active = segmentManager.getActiveSegment()
            if (active != null && index in active.candidates.indices) {
                active.selectedIndex = index
            }

            val fullSurface = StringBuilder()
            val surfaces = ArrayList<String>()

            val activeSelected = active?.getSelectedCandidate()
            if (activeSelected != null && activeSelected.isZenz()) {
                val cand = activeSelected.candidate ?: ""
                fullSurface.append(cand)
                surfaces.add(cand)
            } else {
                for (seg in segments) {
                    val surf = seg.getSelectedSurface()
                    fullSurface.append(surf)
                    if (surf.isNotEmpty()) {
                        surfaces.add(surf)
                    }
                }
            }

            val finalCandidate = mode.convertText(fullSurface.toString())
            commitTextInternal(finalCandidate, 1)

            if (mDictionary != null) {
                if (mEnableLearning) {
                    for (seg in segments) {
                        val selected = seg.getSelectedCandidate()
                        val raw = selected?.rawCandidate
                        if (selected != null && !selected.isSuggestion() && !selected.isPrediction() && !selected.isZenz() && raw != null) {
                            logI("Learning segment: key=${seg.reading}, cand=$raw")
                            mDictionary.addEntry(seg.reading, raw, selected.getPosId(), selected.getCost())
                        }
                    }
                }

                if (surfaces.isNotEmpty()) {
                    if (mEnableLearning) {
                        val lastCand = lastCommittedCandidate
                        if (lastCand != null) {
                            mDictionary.learnTransition(lastCand, surfaces[0])
                        }
                        for (i in 0..<surfaces.size - 1) {
                            mDictionary.learnTransition(surfaces[i], surfaces[i + 1])
                        }
                    }
                    lastCommittedCandidate = surfaces[surfaces.size - 1]
                } else {
                    lastCommittedCandidate = finalCandidate.toString()
                }
            } else {
                if (segments.isNotEmpty()) {
                    val lastSurf = segments[segments.size - 1].getSelectedSurface()
                    lastCommittedCandidate = if (lastSurf.isNotEmpty()) lastSurf else finalCandidate.toString()
                } else {
                    lastCommittedCandidate = finalCandidate.toString()
                }
            }

            segmentManager.clear()

            if (composingBuffer.getRemainingComposingText() != null) {
                composingBuffer.restoreRemainingComposingText()
                changeState(InputStateComposing.INSTANCE)
            } else {
                changeState(InputStateIdle.INSTANCE)
            }
            return
        }

        if (index !in candidateList.indices) {
            if (candidateList.isEmpty()) {
                reset()
                changeState(InputStateIdle.INSTANCE)
            }
            return
        }

        val c = candidateList[index]
        val candidateStr = c.candidate ?: ""
        val finalCandidate = mode.convertText(candidateStr)
        commitTextInternal(finalCandidate, 1)

        val key = composingBuffer.getSearchQuery()

        val raw = c.rawCandidate
        if (mEnableLearning && !c.isZenz() && raw != null) {
            logI("Learning: key=$key, cand=$raw")
            val lastCand = lastCommittedCandidate
            if (lastCand != null) {
                mDictionary?.learnTransition(lastCand, finalCandidate.toString())
            }
            if (mDictionary != null) {
                learnCandidateEntries(mDictionary, c, key, raw)
            }
        } else {
            logI("Skip learning: enable=$mEnableLearning, suggestion/prediction/zenz=${c.isSuggestion() || c.isPrediction() || c.isZenz()}, raw=${raw != null}")
        }

        lastCommittedCandidate = finalCandidate.toString()

        if (composingBuffer.getRemainingComposingText() != null) {
            composingBuffer.restoreRemainingComposingText()
            changeState(InputStateComposing.INSTANCE)
        } else {
            changeState(InputStateIdle.INSTANCE)
        }
    }

    private fun learnCandidateEntries(dictionary: Dictionary, c: Candidate, defaultKey: String, defaultRaw: String) {
        val pathNode = c.viterbiPathNode
        if (pathNode != null) {
            var curr: ViterbiDecoder.PathNode? = pathNode
            val words = ArrayList<ViterbiDecoder.PathNode>()
            while (curr != null && curr.prev != null) {
                if (!curr.reading.isNullOrEmpty() && !curr.surface.isNullOrEmpty()) {
                    words.add(0, curr)
                }
                curr = curr.prev
            }
            if (words.isNotEmpty()) {
                for (w in words) {
                    dictionary.addEntry(w.reading!!, w.surface!!, w.posId, w.cost)
                }
                return
            }
        }
        dictionary.addEntry(defaultKey, defaultRaw, c.getPosId(), c.getCost())
    }

    fun pickCurrentSuggestion() {
        pickSuggestion(mCurrentSuggestionIndex)
    }

    fun pickSuggestion(index: Int) {
        if (index !in suggestionList.indices) {
            return
        }
        val s = suggestionList[index]

        composingBuffer.setComposingText(s)
        if (state == InputStateComposing.INSTANCE) {
            val lastIndex = s.length - 1
            val lastCode = s.codePointAt(lastIndex)
            if (RomajiConverter.isAlphabet(lastCode)) {
                composingBuffer.composingText.deleteCharAt(lastIndex)
                val upperCode = Character.toUpperCase(lastCode)
                processKey(upperCode)
            } else {
                conversionStart()
            }
        }
    }

    fun reset() {
        lastCommittedCandidate = null
        clearBuffers()
    }

    fun clearBuffers() {
        composingBuffer.clearAll()
        clearCandidates()
    }

    fun clearCandidates() {
        segmentManager.clear()
        candidateList = emptyList()
        suggestionList = emptyList()
        mCurrentCandidateIndex = 0
        mCurrentSuggestionIndex = 0
        mService?.hideCandidatesView()
        mService?.hideFloatingCandidates()
    }

    fun changeMode(mode: InputMode, finish: Boolean = false) {
        if (finish) {
            state.finish(this)
            changeState(InputStateIdle.INSTANCE)
        }
        this.mode = mode
        updateUI()
    }

    fun changeState(state: InputState, finish: Boolean = false) {
        if (finish) {
            this.state.finish(this)
        }
        this.state.onExitState(this)
        this.state = state
        this.state.onEnterState(this)
        updateUI()
    }

    fun useJisx0201Kana(): Boolean = mUseJisx0201Kana

    private fun updateUI() {
        mService?.requestUIUpdate()
    }

    private fun String?.isNullEmpty(): Boolean = this.isNullOrEmpty()
    private fun CharSequence?.isNullEmpty(): Boolean = this.isNullOrEmpty()
}
