package io.github.kachaya.ime.engine;

/**
 * 入力バッファ（ローマ字バッファ、未確定文字列バッファ、カーソル位置等）を一括管理するクラスです。
 */
public class ComposingBuffer {

    private final StringBuilder mRomajiBuffer = new StringBuilder();
    private final StringBuilder mComposingText = new StringBuilder();
    private int mComposingCursor = 0;
    private boolean mIsCursorMovedInComposing = false;
    private String mRemainingComposingText = null;

    // --- ローマ字バッファ操作 ---

    public StringBuilder getRomajiBuffer() {
        return mRomajiBuffer;
    }

    public String getRomajiString() {
        return mRomajiBuffer.toString();
    }

    public boolean hasRomaji() {
        return mRomajiBuffer.length() > 0;
    }

    public void appendRomaji(char c) {
        mRomajiBuffer.append(c);
    }

    public void appendRomaji(String text) {
        mRomajiBuffer.append(text);
    }

    public void clearRomaji() {
        mRomajiBuffer.setLength(0);
    }

    public boolean deleteLastRomajiChar() {
        if (mRomajiBuffer.length() > 0) {
            mRomajiBuffer.deleteCharAt(mRomajiBuffer.length() - 1);
            return true;
        }
        return false;
    }

    public String flushRomaji() {
        String flushed = RomajiConverter.flushRomaji(mRomajiBuffer);
        mRomajiBuffer.setLength(0);
        return flushed;
    }

    // --- 未確定文字列バッファ操作 ---

    public StringBuilder getComposingText() {
        return mComposingText;
    }

    public String getComposingTextString() {
        return mComposingText.toString();
    }

    public boolean hasComposingText() {
        return mComposingText.length() > 0;
    }

    public void setComposingText(String text) {
        mComposingText.setLength(0);
        if (text != null) {
            mComposingText.append(text);
        }
        resetComposingCursor();
    }

    public void clearComposingText() {
        mComposingText.setLength(0);
        resetComposingCursor();
    }

    public int getComposingCursor() {
        if (!mIsCursorMovedInComposing) {
            return mComposingText.length();
        }
        return Math.max(0, Math.min(mComposingText.length(), mComposingCursor));
    }

    public void setComposingCursor(int cursor) {
        mComposingCursor = Math.max(0, Math.min(mComposingText.length(), cursor));
        mIsCursorMovedInComposing = (mComposingCursor < mComposingText.length());
    }

    public void moveComposingCursor(int delta) {
        setComposingCursor(getComposingCursor() + delta);
    }

    public void resetComposingCursor() {
        mComposingCursor = mComposingText.length();
        mIsCursorMovedInComposing = false;
    }

    public boolean isCursorMovedInComposing() {
        return mIsCursorMovedInComposing;
    }

    public void setCursorMovedInComposing(boolean moved) {
        mIsCursorMovedInComposing = moved;
    }

    public void insertComposingText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        mComposingText.append(text);
        resetComposingCursor();
    }

    public boolean deleteComposingCharBeforeCursor() {
        if (mComposingText.length() > 0) {
            mComposingText.deleteCharAt(mComposingText.length() - 1);
            resetComposingCursor();
            return true;
        }
        return false;
    }

    public boolean deleteComposingCharAfterCursor() {
        return false;
    }

    public boolean killComposingToLineEnd() {
        return false;
    }

    public boolean killComposingToLineStart() {
        if (mComposingText.length() > 0) {
            clearComposingText();
            return true;
        }
        return false;
    }

    // --- 残余未確定文字列 (mRemainingComposingText) 操作 ---

    public String getRemainingComposingText() {
        return mRemainingComposingText;
    }

    public void setRemainingComposingText(String remaining) {
        mRemainingComposingText = remaining;
    }

    public void restoreRemainingComposingText() {
        if (mRemainingComposingText != null) {
            int currentLen = mComposingText.length();
            mComposingText.append(mRemainingComposingText);
            mRemainingComposingText = null;
            mComposingCursor = currentLen;
            mIsCursorMovedInComposing = true;
        }
    }

    public void splitComposingAtCursorIfNeeded() {
        if (mIsCursorMovedInComposing) {
            int pos = getComposingCursor();
            int len = mComposingText.length();
            if (pos < len && pos > 0) {
                String target = mComposingText.substring(0, pos);
                String remaining = mComposingText.substring(pos);
                mComposingText.setLength(0);
                mComposingText.append(target);
                mRemainingComposingText = remaining;
                resetComposingCursor();
            } else {
                mRemainingComposingText = null;
            }
        } else {
            mRemainingComposingText = null;
        }
    }

    // --- 複合・補助操作 ---

    public boolean hasComposing() {
        return hasComposingText() || hasRomaji();
    }

    public String getSearchQuery() {
        return mComposingText.toString();
    }

    public void clearAll() {
        mRomajiBuffer.setLength(0);
        mComposingText.setLength(0);
        resetComposingCursor();
        mRemainingComposingText = null;
    }
}
