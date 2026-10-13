package io.github.kachaya.ime.engine;

import android.view.KeyEvent;

/**
 * 漢字変換中・候補選択状態（Converting / ▼）を管理するクラスです。
 * <p>
 * 提示された変換候補リスト（{@link Candidate}）の中から、ユーザーが目的の単語を選択・確定するフェーズです。
 * </p>
 */
public enum InputStateConverting implements InputState {
    /** シングルトンインスタンス。 */
    INSTANCE;

    // --- 状態フラグ ---

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public boolean isConverting() {
        return true;
    }

    // --- キー入力処理 ---

    @Override
    public boolean processKey(InputEngine context, int code) {
        // その他のキー入力は現在の候補を確定してから処理
        context.pickCurrentCandidate();
        context.processKey(code);
        return true;
    }

    @Override
    public boolean processSpace(InputEngine context, boolean isShifted) {
        if (isShifted) {
            context.choosePrevCandidate();
        } else {
            context.chooseNextCandidate();
        }
        return true;
    }

    @Override
    public boolean processInputAction(InputEngine context, InputAction action) {
        switch (action) {
            case CURSOR_DOWN:
                context.chooseNextCandidate();
                return true;
            case CURSOR_UP:
                context.choosePrevCandidate();
                return true;
            case CURSOR_RIGHT:
                if (!context.focusNextSegment()) {
                    context.chooseNextCandidate();
                }
                return true;
            case CURSOR_LEFT:
                if (!context.focusPrevSegment()) {
                    context.choosePrevCandidate();
                }
                return true;
            case KANA_KEY:
                context.handleKanaKey();
                return true;
            case CANCEL:
                return handleCancel(context);
            case TOGGLE_KANA:
                context.toggleKana();
                return true;
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
            case DELETE_CHAR:
                context.handleBackspace();
                return true;
            case FORWARD_DELETE:
            case LINE_START:
            case LINE_END:
            case KILL_LINE:
                return true;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean processTab(InputEngine context, boolean isShifted) {
        if (isShifted) {
            context.choosePrevCandidate();
        } else {
            context.chooseNextCandidate();
        }
        return true;
    }

    @Override
    public boolean processEnter(InputEngine context) {
        context.pickCurrentCandidate();
        return true;
    }

    @Override
    public boolean processDpadUp(InputEngine context) {
        context.choosePrevCandidate();
        return true;
    }

    @Override
    public boolean processDpadDown(InputEngine context) {
        context.chooseNextCandidate();
        return true;
    }

    @Override
    public boolean processDpadLeft(InputEngine context) {
        if (!context.focusPrevSegment()) {
            context.choosePrevCandidate();
        }
        return true;
    }

    @Override
    public boolean processDpadRight(InputEngine context) {
        if (!context.focusNextSegment()) {
            context.chooseNextCandidate();
        }
        return true;
    }

    @Override
    public boolean handleBackKey(InputEngine context) {
        return handleCancel(context);
    }

    // --- ライフサイクル ---

    @Override
    public void onEnterState(InputEngine context) {
    }

    @Override
    public void onExitState(InputEngine context) {
    }

    @Override
    public void processText(InputEngine context, String text, char initial, boolean isUpper) {
        context.pickCurrentCandidate();
        InputStateIdle.INSTANCE.processText(context, text, initial, isUpper);
    }

    @Override
    public void onFinishRomaji(InputEngine context) {
    }

    // --- 削除・キャンセル ---

    @Override
    public boolean processBackspace(InputEngine context) {
        return false;
    }

    @Override
    public void beforeBackspace(InputEngine context) {
        context.restoreRemainingComposingText();
    }

    @Override
    public void afterBackspace(InputEngine context) {
        StringBuilder composingText = context.getComposingText();
        if (composingText.length() == 0) {
            context.changeState(InputStateIdle.INSTANCE);
        } else {
            context.changeState(InputStateComposing.INSTANCE);
        }
    }

    @Override
    public boolean handleCancel(InputEngine context) {
        context.changeState(InputStateComposing.INSTANCE);
        return true;
    }

    // --- 確定・モード切替 ---

    @Override
    public boolean finish(InputEngine context) {
        context.pickCurrentCandidate();
        return true;
    }

    @Override
    public void toggleKana(InputEngine context) {
        context.pickCurrentCandidate();
        InputStateIdle.INSTANCE.toggleKana(context);
    }

    @Override
    public CharSequence getComposingText(InputEngine context) {
        String multiSeg = context.getMultiSegmentText();
        if (multiSeg != null) {
            return context.convertText(multiSeg);
        }
        String candidate = context.getCurrentCandidate();
        return context.convertText(candidate);
    }
}
