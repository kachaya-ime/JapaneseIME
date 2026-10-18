package io.github.kachaya.ime.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 入力バッファ（ローマ字バッファ、未確定文字列バッファ、カーソル位置等）を一括管理するクラスです。
 */
class ComposingBuffer {

    val romajiBuffer: StringBuilder = StringBuilder()

    val composingText: StringBuilder = StringBuilder()

    private var mComposingCursor = 0
    private var mIsCursorMovedInComposing = false
    private var mRemainingComposingText: String? = null

    // --- ローマ字バッファ操作 ---

    fun getRomajiString(): String = romajiBuffer.toString()

    fun hasRomaji(): Boolean = romajiBuffer.isNotEmpty()

    fun appendRomaji(c: Char) {
        romajiBuffer.append(c)
    }

    fun appendRomaji(text: String?) {
        if (text != null) {
            romajiBuffer.append(text)
        }
    }

    fun clearRomaji() {
        romajiBuffer.setLength(0)
    }

    fun deleteLastRomajiChar(): Boolean {
        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteCharAt(romajiBuffer.length - 1)
            return true
        }
        return false
    }

    fun flushRomaji(): String {
        val flushed = RomajiConverter.flushRomaji(romajiBuffer)
        romajiBuffer.setLength(0)
        return flushed
    }

    // --- 未確定文字列バッファ操作 ---

    fun getComposingTextString(): String = composingText.toString()

    fun hasComposingText(): Boolean = composingText.isNotEmpty()

    fun setComposingText(text: String?) {
        composingText.setLength(0)
        if (text != null) {
            composingText.append(text)
        }
        resetComposingCursor()
    }

    fun clearComposingText() {
        composingText.setLength(0)
        resetComposingCursor()
    }

    fun getComposingCursor(): Int {
        if (!mIsCursorMovedInComposing) {
            return composingText.length
        }
        return max(0, min(composingText.length, mComposingCursor))
    }

    fun setComposingCursor(cursor: Int) {
        mComposingCursor = max(0, min(composingText.length, cursor))
        mIsCursorMovedInComposing = (mComposingCursor < composingText.length)
    }

    fun moveComposingCursor(delta: Int) {
        setComposingCursor(getComposingCursor() + delta)
    }

    fun resetComposingCursor() {
        mComposingCursor = composingText.length
        mIsCursorMovedInComposing = false
    }

    fun isCursorMovedInComposing(): Boolean = mIsCursorMovedInComposing

    fun setCursorMovedInComposing(moved: Boolean) {
        mIsCursorMovedInComposing = moved
    }

    fun insertComposingText(text: String?) {
        if (text.isNullOrEmpty()) {
            return
        }
        composingText.append(text)
        resetComposingCursor()
    }

    fun deleteComposingCharBeforeCursor(): Boolean {
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            resetComposingCursor()
            return true
        }
        return false
    }

    fun deleteComposingCharAfterCursor(): Boolean = false

    fun killComposingToLineEnd(): Boolean = false

    fun killComposingToLineStart(): Boolean {
        if (composingText.isNotEmpty()) {
            clearComposingText()
            return true
        }
        return false
    }

    // --- 残余未確定文字列 (mRemainingComposingText) 操作 ---

    fun getRemainingComposingText(): String? = mRemainingComposingText

    fun setRemainingComposingText(remaining: String?) {
        mRemainingComposingText = remaining
    }

    fun restoreRemainingComposingText() {
        val remaining = mRemainingComposingText
        if (remaining != null) {
            val currentLen = composingText.length
            composingText.append(remaining)
            mRemainingComposingText = null
            mComposingCursor = currentLen
            mIsCursorMovedInComposing = true
        }
    }

    fun splitComposingAtCursorIfNeeded() {
        if (mIsCursorMovedInComposing) {
            val pos = getComposingCursor()
            val len = composingText.length
            if (pos in 1..<len) {
                val target = composingText.substring(0, pos)
                val remaining = composingText.substring(pos)
                composingText.setLength(0)
                composingText.append(target)
                mRemainingComposingText = remaining
                resetComposingCursor()
            } else {
                mRemainingComposingText = null
            }
        } else {
            mRemainingComposingText = null
        }
    }

    // --- 複合・補助操作 ---

    fun hasComposing(): Boolean = hasComposingText() || hasRomaji()

    fun getSearchQuery(): String = composingText.toString()

    fun clearAll() {
        romajiBuffer.setLength(0)
        composingText.setLength(0)
        resetComposingCursor()
        mRemainingComposingText = null
    }
}
