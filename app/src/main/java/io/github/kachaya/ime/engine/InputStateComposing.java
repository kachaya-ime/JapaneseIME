package io.github.kachaya.ime.engine;

import android.view.KeyEvent;

/**
 * 未確定入力状態（Composing / ▽）を管理するクラスです。
 * <p>
 * ユーザーが入力中の未確定文字列（読み）をバッファに蓄積します。
 * スペースキーにより漢字変換（{@link InputStateConverting}）を開始し、
 * Tab キーまたは Ctrl-I により辞書からの補完候補（Suggestions）を表示します。
 * </p>
 */
public enum InputStateComposing implements InputState {
    /** シングルトンインスタンス。 */
    INSTANCE;

    // --- 状態フラグ ---

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public boolean isConverting() {
        return false;
    }

    // --- キー入力処理 ---

    @Override
    public boolean processKey(InputEngine context, int code) {
        return false;
    }

    @Override
    public boolean processInputAction(InputEngine context, InputAction action) {
        switch (action) {
            case TOGGLE_KANA:
                context.toggleKana();
                return true;
            case DELETE_CHAR:
                return context.handleBackspace();
            case KILL_LINE_BACKWARD:
                return context.killComposingToLineStart();
            case COMPLETION:
                if (context.getSuggestionList() == null || context.getSuggestionList().isEmpty()) {
                    context.updateSuggestions();
                } else {
                    context.chooseAdjacentSuggestion(true);
                }
                return true;
            case KANA_KEY:
                context.handleKanaKey();
                return true;
            case CANCEL:
                return context.handleCancel();
            case TOGGLE_EN_JP:
                context.toggleEnglishJapanese();
                return true;
            case LAUNCH_SETTINGS:
                context.launchSettings();
                return true;
            case OPEN_EMOJI:
                context.openEmojiPicker();
                return true;
            case INSERT_NEWLINE:
                context.handleEnter();
                context.sendShiftEnter();
                return true;

            case CURSOR_LEFT:
                context.moveComposingCursor(-1);
                return true;
            case CURSOR_RIGHT:
                context.moveComposingCursor(1);
                return true;
            case LINE_START:
                context.setComposingCursor(0);
                return true;
            case LINE_END:
                context.setComposingCursor(context.getComposingText().length());
                return true;
            case FORWARD_DELETE:
                return context.deleteComposingCharAfterCursor();
            case KILL_LINE:
                return context.killComposingToLineEnd();
            case CURSOR_UP:
            case CURSOR_DOWN:
                return true;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean processTab(InputEngine context, boolean isShifted) {
        if (context.getSuggestionList() == null || context.getSuggestionList().isEmpty()) {
            context.updateSuggestions();
        } else {
            context.chooseAdjacentSuggestion(!isShifted);
        }
        return true;
    }

    @Override
    public boolean processEnter(InputEngine context) {
        finish(context);
        context.changeState(InputStateIdle.INSTANCE);
        return true;
    }

    @Override
    public boolean processDpadUp(InputEngine context) {
        return true;
    }

    @Override
    public boolean processDpadDown(InputEngine context) {
        return true;
    }

    @Override
    public boolean processDpadLeft(InputEngine context) {
        context.moveComposingCursor(-1);
        return true;
    }

    @Override
    public boolean processDpadRight(InputEngine context) {
        context.moveComposingCursor(1);
        return true;
    }

    @Override
    public boolean handleBackKey(InputEngine context) {
        return handleCancel(context);
    }

    // --- ライフサイクル ---

    @Override
    public void onEnterState(InputEngine context) {
        context.clearCandidates();
        context.resetComposingCursor();
    }

    @Override
    public void onExitState(InputEngine context) {
    }

    // --- テキスト入力処理 ---

    @Override
    public boolean processSpace(InputEngine context, boolean isShifted) {
        context.conversionStart();
        return true;
    }

    @Override
    public void processText(InputEngine context, String text, char initial, boolean isUpper) {
        if (text != null) {
            context.insertComposingText(text);
        }
    }

    @Override
    public void onFinishRomaji(InputEngine context) {
    }

    // --- 削除・キャンセル ---

    @Override
    public boolean processBackspace(InputEngine context) {
        return context.deleteComposingCharBeforeCursor();
    }

    @Override
    public void beforeBackspace(InputEngine context) {
    }

    @Override
    public void afterBackspace(InputEngine context) {
        StringBuilder composingText = context.getComposingText();
        int len = composingText.length();
        boolean hasComposing = context.hasComposing();

        if (len == 0 && !hasComposing) {
            context.setLastCommittedCandidate(null);
            context.changeState(InputStateIdle.INSTANCE);
        }
    }

    @Override
    public boolean handleCancel(InputEngine context) {
        context.setLastCommittedCandidate(null);
        context.changeState(InputStateIdle.INSTANCE);
        return true;
    }

    // --- 確定・モード切替 ---

    @Override
    public boolean finish(InputEngine context) {
        StringBuilder composingText = context.getComposingText();
        int len = composingText.length();
        if (len > 0) {
            CharSequence text = context.convertText(composingText);
            context.commitTextInternal(text, 1);
        }
        context.setLastCommittedCandidate(null);
        return true;
    }

    @Override
    public void toggleKana(InputEngine context) {
        StringBuilder composingText = context.getComposingText();
        int len = composingText.length();
        if (len > 0) {
            InputMode toggledMode = context.getToggledKanaMode();
            CharSequence text = toggledMode.convertText(composingText);
            context.commitTextInternal(text, 1);
        }
        context.setLastCommittedCandidate(null);
        context.changeState(InputStateIdle.INSTANCE);
    }

    @Override
    public CharSequence getComposingText(InputEngine context) {
        StringBuilder composingText = context.getComposingText();
        return context.convertText(composingText);
    }
}
