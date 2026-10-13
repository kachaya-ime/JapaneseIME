package io.github.kachaya.ime.engine;

import android.view.KeyEvent;

/**
 * 全角カタカナモードを管理するクラスです。
 * <p>
 * ローマ字入力を受け取り、全角のカタカナへと変換を行います。
 * 確定済みのテキストや辞書からの候補を全角カタカナへ変換する振る舞いを持ちます。
 * </p>
 */
public enum InputModeFullKatakana implements InputMode {
    /** シングルトンインスタンス。 */
    INSTANCE;

    /**
     * キー入力をローマ字かな変換エンジンへ渡します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param code    入力されたキーの Unicode コードポイント
     */
    @Override
    public void processKey(InputEngine context, int code) {
        context.processRomaji(code);
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
     * 与えられたテキストを全角カタカナへ変換します。
     *
     * @param text 変換対象のテキスト
     * @return 全角カタカナへ変換された CharSequence
     */
    @Override
    public CharSequence convertText(CharSequence text) {
        return RomajiConverter.toWideKatakana(text);
    }

    /**
     * カタカナモードからのトグル先を取得します。
     * 半角カタカナの使用設定が有効な場合は半角カタカナへ、無効な場合はひらがなへ遷移します。
     *
     * @param context 変換エンジンのコンテキスト
     * @return 遷移先の {@link InputMode}
     */
    @Override
    public InputMode getToggledKanaMode(InputEngine context) {
        if (context.useJisx0201Kana()) {
            return InputModeHalfKatakana.INSTANCE;
        } else {
            return InputModeFullHiragana.INSTANCE;
        }
    }
}
