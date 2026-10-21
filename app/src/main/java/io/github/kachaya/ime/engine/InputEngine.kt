package io.github.kachaya.ime.engine

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
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
import io.github.kachaya.ime.dictionary.CandidateLearner
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.viterbi.ViterbiDecoder
import io.github.kachaya.ime.viterbi.ViterbiEngine
import io.github.kachaya.ime.zenz.ZenzEngine
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 入力・変換ロジックを統括するエンジンクラスです。
 */
class InputEngine(
    val context: Context?,
    val mDictionary: Dictionary?,
    bridge: InputEngineUiBridge? = null
) {
    val uiBridge: InputEngineUiBridge? = bridge ?: if (context is InputService) InputServiceUiBridge(context) else null

    val mService: InputService?
        get() = if (context is InputService) context else null

    val suggestionProvider: SuggestionProvider = SuggestionProvider()
    val candidateLearner: CandidateLearner? = if (mDictionary != null) CandidateLearner(mDictionary) else null

    val composingBuffer: ComposingBuffer = ComposingBuffer()

    fun getComposing(): CharSequence {
        return composingBuffer.romajiBuffer
    }

    var mColorComposing: Int = 0
    var mColorConverting: Int = 0

    private val mFullWidthSeparatorMap: MutableMap<String, String> = HashMap()

    var mode: InputMode = InputModeFullHiragana.INSTANCE
        private set

    var state: InputState = InputStateIdle.INSTANCE
        private set

    var candidateList: List<Candidate> = emptyList()
        private set

    var mCurrentCandidateIndex: Int = -1

    var suggestionList: List<String> = emptyList()
        private set

    var mCurrentSuggestionIndex: Int = -1

    companion object {
        const val PREF_KEY_VITERBI_ENABLED = "viterbi_enabled"
        const val PREF_KEY_VITERBI_NBEST = "viterbi_nbest"
        const val DEFAULT_VITERBI_NBEST = 10
    }

    private var mDisplayState: Boolean = true
    private var mUseJisx0201Kana: Boolean = false
    var mEnableLearning: Boolean = true
    private var mViterbiNBest: Int = DEFAULT_VITERBI_NBEST

    var lastCommittedCandidate: String? = null

    private var mShortcutMap: Map<Int, InputAction>? = null

    val viterbiEngine: ViterbiEngine = ViterbiEngine(mDictionary, mViterbiNBest)
    val zenzEngine: ZenzEngine? = if (context != null) ZenzEngine(context) else null

    val mViterbiDecoder: ViterbiDecoder?
        get() = viterbiEngine.viterbiDecoder

    val segmentManager: SegmentManager = SegmentManager()

    fun isZenzActive(): Boolean {
        return zenzEngine != null && zenzEngine.isEnabled
    }

    init {
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

        viterbiEngine.isEnabled = prefs.getBoolean(PREF_KEY_VITERBI_ENABLED, true)
        val nbestStr = prefs.getString(PREF_KEY_VITERBI_NBEST, DEFAULT_VITERBI_NBEST.toString()) ?: DEFAULT_VITERBI_NBEST.toString()
        val nbest = try {
            nbestStr.toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            DEFAULT_VITERBI_NBEST
        }
        mViterbiNBest = nbest
        viterbiEngine.updateNBest(mViterbiNBest)

        mShortcutMap = KeyShortcutManager.loadMappings(prefs)

        zenzEngine?.reloadPreferences(prefs)

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
        if (state.processDpadLeft(this, isShifted)) {
            updateComposingText()
            return true
        }
        return false
    }

    fun handleDpadRight(isShifted: Boolean): Boolean {
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
        uiBridge?.sendDownUpKeyEvents(keyCode)
    }

    fun sendShiftEnter() {
        uiBridge?.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    fun processDigitOrSymbol(code: Int) {
        flushRomaji()
        val isUpper = code in 'A'.code..'Z'.code
        val ch = String(Character.toChars(code))
        val converted = mode.convertText(ch).toString()
        commitRomajiText(converted, converted.firstOrNull() ?: ch[0], isUpper)
    }

    fun processDirectText(text: CharSequence) {
        if (text.isEmpty()) return
        flushRomaji()
        val converted = mode.convertText(text)
        if (state.isConverting()) {
            pickCurrentCandidate()
        }
        if (state.isTransient()) {
            state.processText(this, converted.toString(), converted.firstOrNull() ?: ' ', false)
        } else {
            commitTextInternal(converted, 1)
            lastCommittedCandidate = converted.toString()
            updateIdlePredictions()
        }
        updateComposingText()
    }

    fun processRomaji(code: Int) {
        if (!Character.isLetter(code)) {
            processDigitOrSymbol(code)
            return
        }

        var currentCode = code
        val isUpper = currentCode in 'A'.code..'Z'.code
        if (isUpper) {
            currentCode = Character.toLowerCase(currentCode)
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
        val bridge = uiBridge ?: return false
        return bridge.deleteSurroundingText(0, 2000)
    }

    fun handleKillLineBackward(): Boolean {
        val bridge = uiBridge ?: return false
        val textBefore = bridge.getTextBeforeCursor(2000)
        if (textBefore.isEmpty()) {
            return false
        }
        var newlineIdx = -1
        for (i in textBefore.length - 1 downTo 0) {
            if (textBefore[i] == '\n' || textBefore[i] == '\r') {
                newlineIdx = i
                break
            }
        }
        val len = when {
            newlineIdx == textBefore.length - 1 -> 1
            newlineIdx >= 0 -> textBefore.length - 1 - newlineIdx
            else -> textBefore.length
        }
        return bridge.deleteSurroundingText(len, 0)
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

    fun toggleExpandCandidates() {
        uiBridge?.toggleExpandCandidates()
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
        uiBridge?.commitText(text, newCursorPosition)
        if (isPunctuation(text.toString())) {
            lastCommittedCandidate = null
        }
    }

    fun resetOnStartInput() {
        reset()
        if (state.isTransient()) {
            changeState(InputStateIdle.INSTANCE)
        }
        uiBridge?.requestUIUpdate()
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
            nextIndex = -1
        } else if (nextIndex < -1) {
            nextIndex = listSize - 1
        }

        mCurrentSuggestionIndex = nextIndex
        uiBridge?.requestChooseCandidate(mCurrentSuggestionIndex)
        updateFloatingCandidates()
    }

    fun chooseNextCandidate() {
        if (segmentManager.hasSegments()) {
            val active = segmentManager.getActiveSegment()
            if (active == null || active.candidates.isEmpty()) return
            val listSize = active.candidates.size
            var nextIndex = active.selectedIndex + 1

            if (nextIndex > listSize - 1) {
                nextIndex = 0
            }

            active.selectedIndex = nextIndex
            mCurrentCandidateIndex = nextIndex
            uiBridge?.requestChooseCandidate(mCurrentCandidateIndex)
            updateFloatingCandidates()
            updateComposingText()
            return
        }

        if (candidateList.isEmpty()) {
            return
        }
        val nextIndex = mCurrentCandidateIndex + 1

        val listSize = candidateList.size
        if (nextIndex > listSize - 1) {
            mCurrentCandidateIndex = 0
            uiBridge?.requestChooseCandidate(mCurrentCandidateIndex)
            updateFloatingCandidates()
            updateComposingText()
            return
        }
        mCurrentCandidateIndex = nextIndex
        uiBridge?.requestChooseCandidate(mCurrentCandidateIndex)
        updateFloatingCandidates()
        updateComposingText()
    }

    fun choosePrevCandidate() {
        if (segmentManager.hasSegments()) {
            val active = segmentManager.getActiveSegment()
            if (active == null || active.candidates.isEmpty()) return
            val listSize = active.candidates.size
            var nextIndex = active.selectedIndex - 1

            if (nextIndex < 0) {
                nextIndex = listSize - 1
            }

            active.selectedIndex = nextIndex
            mCurrentCandidateIndex = nextIndex
            uiBridge?.requestChooseCandidate(mCurrentCandidateIndex)
            updateFloatingCandidates()
            updateComposingText()
            return
        }

        if (candidateList.isEmpty()) {
            return
        }
        val nextIndex = mCurrentCandidateIndex - 1

        if (nextIndex < 0) {
            changeState(InputStateComposing.INSTANCE)
            updateSuggestions()
            updateComposingText()

            mCurrentCandidateIndex = -1
            mCurrentSuggestionIndex = -1
            return
        }
        mCurrentCandidateIndex = nextIndex
        uiBridge?.requestChooseCandidate(mCurrentCandidateIndex)
        updateFloatingCandidates()
        updateComposingText()
    }

    fun pickCandidateViewManually(index: Int) {
        state.processPickCandidate(this, index)
    }

    fun pickIdleCandidate(index: Int) {
        if (index in candidateList.indices) {
            val c = candidateList[index]
            val candStr = c.candidate ?: ""
            commitTextInternal(candStr, 1)

            val key = composingBuffer.getSearchQuery()
            val raw = c.rawCandidate
            if (candidateLearner != null && mEnableLearning && raw != null && !c.isZenz()) {
                lastCommittedCandidate = candidateLearner.learnCandidateEntries(c, key.ifEmpty { candStr }, raw, lastCommittedCandidate)
            } else {
                lastCommittedCandidate = candStr
            }

            updateIdlePredictions()
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

        val raw = c.rawCandidate
        if (candidateLearner != null && mEnableLearning && !c.isZenz() && raw != null) {
            val key = composingBuffer.getSearchQuery()
            lastCommittedCandidate = candidateLearner.learnCandidateEntries(c, key, raw, lastCommittedCandidate)
        } else {
            lastCommittedCandidate = finalCandidate.toString()
        }

        clearBuffers()
        changeState(InputStateIdle.INSTANCE)
    }

    fun updateIdlePredictions() {
        if (state != InputStateIdle.INSTANCE) return
        var lastCand = lastCommittedCandidate
        if (lastCand.isNullOrBlank() || isPunctuation(lastCand)) {
            lastCand = "__BOS__"
        }
        val dict = mDictionary
        if (dict == null) {
            clearCandidates()
            return
        }
        val predictedSurfaces = dict.getPredictedSurfaceWords(lastCand, 10)
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
        mCurrentCandidateIndex = -1
        mCurrentSuggestionIndex = -1
        val sugStrList = ArrayList<String>()
        for (c in cands) {
            sugStrList.add(c.candidate ?: "")
        }
        suggestionList = sugStrList
        if (!composingBuffer.hasComposing()) {
            uiBridge?.hideCandidatesView()
            uiBridge?.hideFloatingCandidates()
        } else {
            uiBridge?.setCandidateObjects(candidateList, false)
            updateFloatingCandidates()
        }
    }

    fun hasComposing(): Boolean = composingBuffer.hasComposing()

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
        state.formatComposingText(this, ct)

        val totalLen = ct.length
        if (totalLen != 0) {
            val underline = UnderlineSpan()
            ct.setSpan(underline, 0, totalLen, Spanned.SPAN_COMPOSING)
        }

        mService?.setComposingText(ct, 1)
        updateFloatingCandidates()
    }

    fun conversionStart() {
        suggestionProvider.cancelPending()
        zenzEngine?.cancelPendingRequests()

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
        return viterbiEngine.convertSync(segmentReading, preferredSurface)
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
            mViterbiDecoder
        )
        if (res) {
            syncActiveSegment()
        }
        return res
    }

    private fun conversionStartInternal(lastCandidate: Boolean): Boolean {
        val query = composingBuffer.getSearchQuery()

        val segments = ArrayList<Segment>()
        if (viterbiEngine.isEnabled && mViterbiDecoder != null) {
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
        Log.d("InputEngine", "[Viterbi Candidates Set to CandidateList (Converting)] activeSegmentReading=\"${segmentManager.getActiveSegment()?.reading}\", activeCandidateCount=${candidateList.size}, candidates=${candidateList.map { it.candidate }}")

        if (isZenzActive() && !hasUnconvertedAlphabet(query)) {
            val leftContext = getCleanLeftContext(20)
            Log.i("InputEngine", "==> [Zenz Request Sent (Converting)] query=\"$query\", leftContext=\"$leftContext\"")

            zenzEngine?.convertAsync(query, leftContext, object : ZenzEngine.ConversionCallback {
                override fun onCandidateAvailable(zenzCandidate: Candidate) {
                    val candStr = zenzCandidate.candidate ?: ""
                    Log.i("InputEngine", "<== [Zenz Response Received (Converting)] candidate=\"$candStr\" (type=${zenzCandidate.type}, query=\"$query\")")
                    if (state.isConverting() && candStr.isNotEmpty()) {
                        val activeSeg = segmentManager.getActiveSegment()
                        if (activeSeg != null) {
                            var exists = false
                            for (c in activeSeg.candidates) {
                                if (c.candidate == candStr) {
                                    exists = true
                                    break
                                }
                            }
                            if (!exists) {
                                val newCands = activeSeg.candidates.toMutableList()
                                newCands.add(zenzCandidate)
                                activeSeg.candidates = newCands
                                Log.d("InputEngine", "[Zenz Candidate Added to CandidateList (Converting)] candidate=\"$candStr\", newCandidateCount=${newCands.size}, candidates=${newCands.map { it.candidate }}")
                                syncActiveSegment()
                            } else {
                                Log.d("InputEngine", "[Zenz Candidate Already Exists in CandidateList (Converting)] candidate=\"$candStr\"")
                            }
                        }
                    }
                }

                override fun onConversionComplete() {
                    Log.i("InputEngine", "<== [Zenz Request Completed (Converting)] query=\"$query\"")
                }
            })
        } else {
            if (!isZenzActive()) {
                Log.d("InputEngine", "[Zenz Request Skipped (Converting)] Zenz engine is not active. (query=\"$query\")")
            } else if (hasUnconvertedAlphabet(query)) {
                Log.d("InputEngine", "[Zenz Request Skipped (Converting)] Query has unconverted alphabet. (query=\"$query\")")
            }
        }

        return true
    }

    fun getCurrentCandidateIndex(): Int {
        return if (state.isConverting() && candidateList.isNotEmpty()) {
            mCurrentCandidateIndex
        } else if (suggestionList.isNotEmpty()) {
            mCurrentSuggestionIndex
        } else {
            -1
        }
    }

    fun setCandidateIndex(index: Int) {
        state.processSetCandidateIndex(this, index)
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
        val bridge = uiBridge ?: return

        if (!bridge.isFloatingCandidateEnabled()) {
            bridge.hideFloatingCandidates()
            return
        }

        if (state.isConverting() && candidateList.isNotEmpty()) {
            val items = getAllCandidateItems(candidateList)
            bridge.showCandidateFlexboxPopup(null, items, mCurrentCandidateIndex)
        } else if (composingBuffer.hasComposing() && suggestionList.isNotEmpty()) {
            val items = getAllSuggestionItems(suggestionList)
            bridge.showCandidateFlexboxPopup(null, items, mCurrentSuggestionIndex)
        } else {
            bridge.hideFloatingCandidates()
        }
    }

    private fun updateCandidates() {
        uiBridge?.setCandidateObjects(candidateList, true)
        updateFloatingCandidates()
    }

    fun updateSuggestions() {
        if (!composingBuffer.hasComposing() || state.isConverting()) {
            suggestionProvider.cancelPending()
            if (!state.isConverting()) {
                suggestionList = emptyList()
                candidateList = emptyList()
                uiBridge?.hideCandidatesView()
                uiBridge?.hideFloatingCandidates()
            }
            return
        }

        val query = composingBuffer.getComposingTextString()
        val romaji = composingBuffer.getRomajiString()
        val displayQuery = if (query.isNotEmpty()) query else romaji

        if (displayQuery.isNotEmpty()) {
            val initialCands = ArrayList<Candidate>()
            initialCands.add(Candidate(displayQuery, displayQuery, "Zenz AI"))

            val wideK = RomajiConverter.toWideKatakana(displayQuery)
            val halfK = RomajiConverter.toHalfKatakana(displayQuery)
            if (!wideK.isNullEmpty() && wideK != displayQuery) {
                initialCands.add(Candidate(wideK, wideK, "Zenz AI"))
            }
            if (!halfK.isNullEmpty() && halfK != displayQuery) {
                initialCands.add(Candidate(halfK, halfK, "Zenz AI"))
            }

            candidateList = initialCands
            val initialSugList = ArrayList<String>()
            for (c in initialCands) {
                initialSugList.add(c.candidate ?: "")
            }
            suggestionList = initialSugList
            mCurrentCandidateIndex = -1
            mCurrentSuggestionIndex = -1

            uiBridge?.setCandidateObjects(candidateList, false)
            updateFloatingCandidates()
        }

        suggestionProvider.requestSuggestions(
            query,
            mViterbiDecoder,
            mDictionary,
            lastCommittedCandidate
        ) { requestId, currentQuery, merged ->
            if (requestId == suggestionProvider.suggestionRequestId.get() && composingBuffer.hasComposing() && !state.isConverting()) {
                candidateList = merged.ifEmpty {
                    val fallbackQuery = composingBuffer.getComposingTextString().ifEmpty { composingBuffer.getRomajiString() }
                    listOf(Candidate(fallbackQuery, fallbackQuery, "Zenz AI"))
                }
                mCurrentCandidateIndex = -1
                mCurrentSuggestionIndex = -1

                val sugStrList = ArrayList<String>()
                for (c in candidateList) {
                    sugStrList.add(c.candidate ?: "")
                }
                suggestionList = sugStrList

                uiBridge?.setCandidateObjects(candidateList, false)
                updateFloatingCandidates()
                Log.d("InputEngine", "[Viterbi/Dict Suggest Candidates Set] query=\"$currentQuery\", count=${candidateList.size}, candidates=${candidateList.map { it.candidate }}")

                if (isZenzActive() && !hasUnconvertedAlphabet(currentQuery)) {
                    val leftContext = getCleanLeftContext(20)
                    Log.i("InputEngine", "==> [Zenz Live Suggest Request Sent] query=\"$currentQuery\", leftContext=\"$leftContext\"")

                    zenzEngine?.convertAsync(currentQuery, leftContext, object : ZenzEngine.ConversionCallback {
                        override fun onCandidateAvailable(zenzCandidate: Candidate) {
                            val candStr = zenzCandidate.candidate ?: ""
                            Log.i("InputEngine", "<== [Zenz Live Suggest Response Received] candidate=\"$candStr\" (type=${zenzCandidate.type}, query=\"$currentQuery\")")
                            if (requestId == suggestionProvider.suggestionRequestId.get() && composingBuffer.getComposingTextString() == currentQuery && !state.isConverting() && candStr.isNotEmpty()) {
                                var exists = false
                                for (c in candidateList) {
                                    if (c.candidate == candStr) {
                                        exists = true
                                        break
                                    }
                                }
                                if (!exists) {
                                    val updatedList = ArrayList(candidateList)
                                    updatedList.add(zenzCandidate)
                                    candidateList = updatedList

                                    val sugStrList = ArrayList<String>()
                                    for (c in updatedList) {
                                        sugStrList.add(c.candidate ?: "")
                                    }
                                    suggestionList = sugStrList

                                    Log.d("InputEngine", "[Zenz Live Suggest Candidate Added] candidate=\"$candStr\", newCandidateCount=${updatedList.size}, candidates=${updatedList.map { it.candidate }}")

                                    uiBridge?.setCandidateObjects(candidateList, false)
                                    updateFloatingCandidates()
                                } else {
                                    Log.d("InputEngine", "[Zenz Live Suggest Candidate Already Exists] candidate=\"$candStr\"")
                                }
                            }
                        }

                        override fun onConversionComplete() {
                            Log.i("InputEngine", "<== [Zenz Live Suggest Request Completed] query=\"$currentQuery\"")
                        }
                    })
                }
            }
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



    fun getCurrentCandidate(): String {
        if (candidateList.isEmpty()) {
            return ""
        }
        val c = candidateList[mCurrentCandidateIndex]
        return c.candidate ?: ""
    }

    fun getCleanLeftContext(maxLen: Int): String {
        val engine = zenzEngine
        if (engine == null || !engine.isUseContext) {
            return ""
        }
        val bridge = uiBridge ?: return ""
        val fullLeftContext = ContextExtractor.getCleanLeftContext(bridge, maxLen)
        if (fullLeftContext.isEmpty()) {
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

            if (candidateLearner != null && mEnableLearning) {
                val lastSurf = candidateLearner.learnSegments(segments, lastCommittedCandidate)
                if (lastSurf != null) {
                    lastCommittedCandidate = lastSurf
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
        if (candidateLearner != null && mEnableLearning && !c.isZenz() && raw != null) {
            logI("Learning: key=$key, cand=$raw")
            lastCommittedCandidate = candidateLearner.learnCandidateEntries(c, key, raw, lastCommittedCandidate)
        } else {
            logI("Skip learning: enable=$mEnableLearning, suggestion/prediction/zenz=${c.isSuggestion() || c.isPrediction() || c.isZenz()}, raw=${raw != null}")
            lastCommittedCandidate = finalCandidate.toString()
        }

        if (composingBuffer.getRemainingComposingText() != null) {
            composingBuffer.restoreRemainingComposingText()
            changeState(InputStateComposing.INSTANCE)
        } else {
            changeState(InputStateIdle.INSTANCE)
        }
    }

    private fun isPunctuation(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val trimmed = text.trim()
        val lastChar = trimmed.last()
        return lastChar == '。' || lastChar == '．' || lastChar == '！' || lastChar == '？' || lastChar == '.' || lastChar == '!' || lastChar == '?'
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

    fun cancelPendingRequests() {
        viterbiEngine.cancelPending()
        zenzEngine?.cancelPending()
    }

    fun release() {
        viterbiEngine.release()
        zenzEngine?.release()
    }

    fun reset() {
        cancelPendingRequests()
        lastCommittedCandidate = null
        clearBuffers()
    }

    fun clearBuffers() {
        cancelPendingRequests()
        composingBuffer.clearAll()
        clearCandidates()
    }

    fun clearCandidates() {
        suggestionProvider.cancelPending()
        segmentManager.clear()
        candidateList = emptyList()
        suggestionList = emptyList()
        mCurrentCandidateIndex = -1
        mCurrentSuggestionIndex = -1
        uiBridge?.hideCandidatesView()
        uiBridge?.hideFloatingCandidates()
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
        uiBridge?.requestUIUpdate()
    }

    private fun String?.isNullEmpty(): Boolean = this.isNullOrEmpty()
    private fun CharSequence?.isNullEmpty(): Boolean = this.isNullOrEmpty()
}
