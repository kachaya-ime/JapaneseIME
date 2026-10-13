package io.github.kachaya.ime.engine;

import android.view.KeyEvent;

/**
 * 半角英数モード（ASCII）を管理するクラスです。
 * <p>
 * キー入力を受け取り、半角のアルファベットや記号として直接入力（確定）を行います。
 * InputMode インターフェースにおける標準的な直接入力の振る舞いを実装しています。
 * </p>
 */
public enum InputModeHalfLatin implements InputMode {
    /** シングルトンインスタンス。 */
    INSTANCE;

    /**
     * キー入力を直接コミットします。
     *
     * @param context 変換エンジンのコンテキスト
     * @param code    入力されたキーの Unicode コードポイント
     */
    @Override
    public void processKey(InputEngine context, int code) {
        String text = new String(Character.toChars(code));
        context.commitTextInternal(convertText(text), 1);
    }

    /**
     * Ctrlキー入力を処理します。割り当てられた CtrlAction に応じて各種アクションを実行します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param action  割り当てられている InputAction
     * @return イベントを消費した場合は true
     */
    @Override
    public boolean processInputAction(InputEngine context, InputAction action) {
        // 半角英数モードでは常にカーソル移動を処理する。
        // （バッファがある場合にこれを阻止するかどうかは、State 側の責務とする）
        switch (action) {
            case KANA_KEY:
                context.handleKanaKey();
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
                if (context.hasComposing()) {
                    context.handleEnter();
                }
                context.sendShiftEnter();
                return true;
            case CURSOR_UP:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
                return true;
            case CURSOR_DOWN:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            case CURSOR_LEFT:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            case CURSOR_RIGHT:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            case FORWARD_DELETE:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL);
                return true;
            case DELETE_CHAR:
                if (!context.handleBackspace()) {
                    context.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                }
                return true;
            case LINE_START:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME);
                return true;
            case LINE_END:
                context.sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END);
                return true;
            case KILL_LINE:
                return context.handleKillLine();
            case KILL_LINE_BACKWARD:
                return context.handleKillLineBackward();
            default:
                break;
        }
        return false;
    }

    /**
     * 与えられたテキストを変換せずにそのまま返します（半角英数）。
     *
     * @param text 変換対象の CharSequence
     * @return そのままの CharSequence
     */
    @Override
    public CharSequence convertText(CharSequence text) {
        return text;
    }

    /**
     * 半角英数モードはトグル対象のかなモードを持ちません。
     *
     * @param context 変換エンジンのコンテキスト
     * @return null
     */
    @Override
    public InputMode getToggledKanaMode(InputEngine context) {
        return null;
    }
}
