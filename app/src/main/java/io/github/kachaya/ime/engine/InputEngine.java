package io.github.kachaya.ime.engine;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.kachaya.ime.BuildConfig;
import io.github.kachaya.ime.InputService;
import io.github.kachaya.ime.R;
import io.github.kachaya.ime.SettingsActivity;
import io.github.kachaya.ime.dictionary.Candidate;
import io.github.kachaya.ime.dictionary.Dictionary;

/**
 * 入力・変換ロジックを統括するエンジンクラスです。
 * <p>
 * ユーザーのキー入力を受け取り、現在の状態（{@link InputState}）やモード（{@link InputMode}）に応じて、
 * ローマ字変換、辞書検索（漢字変換）、単語登録、補完（Suggestion）などのアクションを制御します。
 * 入力サービス（{@link InputService}）と辞書（{@link Dictionary}）の仲介役として動作します。
 * </p>
 */
public class InputEngine {

    /** 親となる入力サービスへの参照。 */
    private final InputService mService;
    /** 使用する辞書管理インスタンス。 */
    private final Dictionary mDictionary;
    /** 各種リソースアクセスのためのコンテキスト。 */
    private final Context mContext;
    /** 未確定の入力バッファ（ローマ字、見出し語、送り仮名等）の管理オブジェクト。 */
    private final ComposingBuffer mComposingBuffer = new ComposingBuffer();

    public ComposingBuffer getComposingBuffer() {
        return mComposingBuffer;
    }

    public CharSequence getComposing() {
        return mComposingBuffer.getRomajiBuffer();
    }

    /** ▽（見出し語入力中）の背景色。 */
    private int mColorComposing;
    /** ▼（変換中）の背景色。 */
    private int mColorConverting;
    /** 全角入力が優先される記号（。、など）のマッピングテーブル。 */
    private final Map<String, String> mFullWidthSeparatorMap;
    /** 現在の入力モード。初期値は全角ひらがな。 */
    private InputMode mMode = InputModeFullHiragana.INSTANCE;
    /** 現在の入力状態。初期値は確定（Direct）モード。 */
    private InputState mState = InputStateIdle.INSTANCE;
    /** 現在ユーザーに提示されている変換候補のリスト。 */
    private List<Candidate> mCandidateList = Collections.emptyList();
    /** 候補リスト内で現在選択されている項目のインデックス。 */
    private int mCurrentCandidateIndex;
    /** 現在提示されている補完のリスト。 */
    private List<String> mSuggestionList = Collections.emptyList();
    /** 補完リスト内で現在選択されている項目のインデックス。 */
    private int mCurrentSuggestionIndex;
    /** 状態表示（▼▽）の目印を表示するかどうか。 */
    private boolean mDisplayState = true;
    /** 半角カタカナを使用するかどうか。 */
    private boolean mUseJisx0201Kana = false;
    /** 設定により ユーザー辞書への学習機能を有効にするかどうか。 */
    private boolean mEnableLearning = true;
    /** 候補表示時の注釈（アノテーション）表示フラグ。 */
    private boolean mShowAnnotation = true;
    /** 直近に確定された候補文字列（予測変換の Bigram 学習用）。 */
    private String mLastCommittedCandidate = null;

    public void setLastCommittedCandidate(String candidate) {
        mLastCommittedCandidate = candidate;
    }
    /** ショートカットキーの割り当てマップ。 */
    private Map<Integer, InputAction> mShortcutMap = null;
    /** Viterbi格子探索デコーダー。 */
    private ViterbiDecoder mViterbiDecoder;

    /** 複数文節変換の管理オブジェクト。 */
    private final SegmentManager mSegmentManager = new SegmentManager();

    public SegmentManager getSegmentManager() {
        return mSegmentManager;
    }


    /**
     * 現在の入力モードを取得します。
     *
     * @return 現在の {@link InputMode}
     */
    public InputMode getMode() {
        return mMode;
    }

    /**
     * 現在の入力状態を取得します。
     *
     * @return 現在の {@link InputState}
     */
    public InputState getState() {
        return mState;
    }

    /**
     * コンテキストを取得します。
     *
     * @return コンテキスト
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * InputEngine を構築します。
     *
     * @param context    親サービス（リソースアクセス用）
     * @param dictionary 検索・学習に使用する辞書
     */
    public InputEngine(Context context, Dictionary dictionary) {
        mContext = context;
        mService = (context instanceof InputService) ? (InputService) context : null;
        mDictionary = dictionary;
        mViterbiDecoder = new ViterbiDecoder(mDictionary, 20);

        mFullWidthSeparatorMap = new HashMap<>();
        mFullWidthSeparatorMap.put(".", "。");
        RomajiConverter.setSeparator(".", "。");
        mFullWidthSeparatorMap.put(",", "、");
        RomajiConverter.setSeparator(",", "、");
        if (mContext != null) {
            readPrefs();
            updateColors();
        } else {
            mColorComposing = 0;
            mColorConverting = 0;
        }
    }

    /**
     * 現在のリソース／テーマ設定に基づいて、入力・変換中の背景色を更新します。
     */
    public void updateColors() {
        if (mContext != null) {
            Resources res = mContext.getResources();
            mColorComposing = res.getColor(R.color.composing_composing, mContext.getTheme());
            mColorConverting = res.getColor(R.color.composing_converting, mContext.getTheme());
        }
    }

    /**
     * デバッグビルド時のみログを出力します。
     *
     * @param msg ログメッセージ
     */
    private void logI(String msg) {
        if (BuildConfig.DEBUG) {
            Log.i(getClass().getSimpleName(), msg);
        }
    }

    /**
     * 共有設定（SharedPreferences）から最新の設定を読み込み、エンジンへ反映します。
     * <p>
     * 句読点の種類（。、 vs ．，）、状態表示シンボルの有無、
     * 半角カタカナの使用設定を更新します。
     * </p>
     */
    public void readPrefs() {
        if (mContext == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        // 句読点の設定を直接マップに反映
        boolean useKutenJp = prefs.getBoolean("use_kuten_jp", true);
        String kuten = useKutenJp ? "。" : "．";
        mFullWidthSeparatorMap.put(".", kuten);
        RomajiConverter.setSeparator(".", kuten);

        boolean useToutenJp = prefs.getBoolean("use_touten_jp", true);
        String touten = useToutenJp ? "、" : "，";
        mFullWidthSeparatorMap.put(",", touten);
        RomajiConverter.setSeparator(",", touten);

        // 各種フラグの更新
        mDisplayState = false;
        mUseJisx0201Kana = prefs.getBoolean("use_jisx0201_kana", false);
        mEnableLearning = prefs.getBoolean("enable_learning", true);
        mShowAnnotation = prefs.getBoolean("show_annotation", true);

        mShortcutMap = KeyShortcutManager.loadMappings(prefs);

        logI("readPrefs: useKutenJp =" + useKutenJp);
        logI("readPrefs: useToutenJp =" + useToutenJp);
        logI("readPrefs: useJisx0201Kana =" + mUseJisx0201Kana);
        logI("readPrefs: enableLearning =" + mEnableLearning);
        logI("readPrefs: displayState =" + mDisplayState);


    }

    /**
     * 漢字変換または Abbrev 変換に未確定の内容があるか判定します。
     *
     * @return 入力バッファに編集中の内容がある場合は true
     */
    public boolean isComposing() {
        return mComposingBuffer.hasComposing();
    }

    /**
     * システム（IME）による標準的なカーソル移動を許可すべきかを判定します。
     *
     * @return 移動可能な状態（バッファが空）なら true
     */
    public boolean canMoveCursor() {
        return !isComposing();
    }

    /**
     * 現在の入力モードに基づいて、システムへのキーイベント波及を無視すべきか判定します。
     * <p>
     * 以前は直接入力（Direct Input）モードで true を返していましたが、
     * 現在は全角英数変換や Ctrl-J 処理などのために、常に入力処理をハンドルします。
     * </p>
     *
     * @return 常に false (キーイベントをハンドルする)
     */
    public boolean ignoresKeyEvent() {
        return false;
    }

    /**
     * 現在の入力状態（State）およびモード（Mode）に基づいてキー入力を処理します。
     * <p>
     * 1. 現在の {@link InputState} に対してキー処理を試みます。<br>
     * 2. 状態側で処理されなかった場合、現在の {@link InputMode} に対してキー処理を委譲します。<br>
     * 処理完了後、エディタ上のインライン表示（Composing Text）を最新状態に更新します。
     * </p>
     *
     * @param code 入力された Unicode コードポイント
     */
    public void processKey(int code) {
        boolean consumed = mState.processKey(this, code);
        if (!consumed) {
            mMode.processKey(this, code);
        }
        updateComposingText();
    }

    /**
     * Ctrlキーと同時押しのキー入力を処理します。
     * <p>
     * 状態（State）およびモード（Mode）に処理を委譲します。
     * 共通のデフォルト処理（カーソル移動等）は行わず、各状態・モードの責務とします。
     * </p>
     *
     * @param keyCode KeyEventで定義されているキーコード
     * @return イベントを消費した場合は true
     */
    public boolean processShortcutKey(int keyCode) {
        return processShortcutKey(keyCode, false);
    }

    /**
     * ショートカットキー（Ctrl または Shift + 特殊キー）入力を処理します。
     *
     * @param keyCode KeyEventで定義されているキーコード
     * @param isShift Shift修飾キーが付加されているか
     * @return イベントを消費した場合は true
     */
    public boolean processShortcutKey(int keyCode, boolean isShift) {
        int key = keyCode;
        if (isShift) {
            key |= KeyShortcutManager.SHIFT_MASK;
        } else if (KeyShortcutManager.isSystemReserved(keyCode)) {
            return false;
        }
        InputAction action = (mShortcutMap != null) ? mShortcutMap.get(key) : null;
        if (action == null) {
            action = KeyShortcutManager.getDefaultMappings().get(key);
        }
        if (action == null || action == InputAction.NONE) {
            return false;
        }
        if (mState.processInputAction(this, action)) {
            updateComposingText();
            return true;
        }
        if (mMode.processInputAction(this, action)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    /**
     * Tabキー入力を処理します。
     *
     * @param isShifted Shiftキーが押されている（逆順選択）かどうか
     * @return イベントを消費した場合は true
     */
    public boolean processTab(boolean isShifted) {
        if (mState.processTab(this, isShifted)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    /**
     * スペースキー入力を処理します。
     *
     * @param isShifted Shiftキー押下状態かどうか
     * @return イベントを消費した場合は true
     */
    public boolean handleSpace(boolean isShifted) {
        if (mComposingBuffer.hasRomaji()) {
            flushRomaji();
        }
        if (mState.processSpace(this, isShifted)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    public boolean handleSpace() {
        return handleSpace(false);
    }

    public boolean handleDpadLeft(boolean isShifted) {
        if (mState.isConverting()) {
            if (isShifted) {
                if (resizeFocusedSegment(-1)) {
                    return true;
                }
            }
        }
        if (mState.processDpadLeft(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    public boolean handleDpadRight(boolean isShifted) {
        if (mState.isConverting()) {
            if (isShifted) {
                if (resizeFocusedSegment(1)) {
                    return true;
                }
            }
        }
        if (mState.processDpadRight(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    public boolean handleDpadUp() {
        if (mState.processDpadUp(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    public boolean handleDpadDown() {
        if (mState.processDpadDown(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    /**
     * システム（エディタ）に対してキーイベント送信をリクエストします。
     *
     * @param keyCode KeyEvent で定義されているキーコード
     */
    public void sendDownUpKeyEvents(int keyCode) {
        mService.sendDownUpKeyEvents(keyCode);
    }

    /**
     * Shift+Enter キーイベントをエディタに送信します（チャットアプリ等での送信なし改行用）。
     */
    public void sendShiftEnter() {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic != null) {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON);
            ic.sendKeyEvent(down);
            ic.sendKeyEvent(up);
        }
    }

    /**
     * キーコードをローマ字かな変換処理へ渡し、変換を継続します。
     *
     * @param code キーコード
     */
    public void processRomaji(int code) {
        if (code == ' ') {
            flushRomaji();
            commitRomajiText(" ", ' ', false);
            return;
        }

        boolean isUpper = (code >= 'A' && code <= 'Z');
        if (isUpper) {
            code = Character.toLowerCase(code);
        }

        if (Character.isDigit(code)) {
            flushRomaji();
            commitRomajiText(String.valueOf((char) code), (char) code, isUpper);
            return;
        }

        mComposingBuffer.appendRomaji((char) code);
        RomajiConverter.ConversionResult res = RomajiConverter.convert(mComposingBuffer.getRomajiBuffer());
        if (!res.convertedText.isEmpty()) {
            mComposingBuffer.clearRomaji();
            mComposingBuffer.appendRomaji(res.pendingRomaji);
            commitRomajiText(res.convertedText, res.convertedText.charAt(0), isUpper);
            if (res.pendingRomaji.isEmpty()) {
                onFinishRomaji();
            }
        }
    }

    /**
     * バッファに残っている未確定ローマ字を強制的に変換して確定させます。
     *
     * @return 文字が放出された場合は true
     */
    public boolean flushRomaji() {
        if (!mComposingBuffer.hasRomaji()) {
            return false;
        }
        String flushed = mComposingBuffer.flushRomaji();
        if (!flushed.isEmpty()) {
            commitRomajiText(flushed, flushed.charAt(0), false);
        }
        return true;
    }

    /**
     * ローマ字かな変換エンジンから確定テキスト（または記号）を受け取り、処理します。
     *
     * @param text    確定したかな文字列
     * @param initial 変換元となった入力の最初の 1 文字
     * @param isUpper シフトキーが押されていたかどうか
     */
    public void commitRomajiText(String text, char initial, boolean isUpper) {
        if (mMode == InputModeHalfLatin.INSTANCE || mMode == InputModeFullLatin.INSTANCE) {
            String committedText = mFullWidthSeparatorMap.getOrDefault(text, text);
            mState.processText(this, committedText, initial, isUpper);
            return;
        }

        String committedText = mFullWidthSeparatorMap.getOrDefault(text, text);
        mState.processText(this, committedText, initial, isUpper);
    }

    /**
     * 1 つの「かな」としての区切りが完了した際（Leafノード到達時）のコールバックです。
     */
    public void onFinishRomaji() {
        mState.onFinishRomaji(this);
    }

    /**
     * かなキー（Ctrl-J）入力を処理し、確定モード・ひらがなモードへ戻します。
     */
    public void handleKanaKey() {
        flushRomaji();
        mState.finish(this);
        changeState(InputStateIdle.INSTANCE);
        if (mMode != InputModeFullHiragana.INSTANCE) {
            changeMode(InputModeFullHiragana.INSTANCE, false);
        }
        updateComposingText();
    }

    /**
     * 戻る（Back）キー入力を処理します。
     *
     * @return イベントを消費した場合は true
     */
    public boolean handleBackKey() {
        if (mState.handleBackKey(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    /**
     * エンターキー入力を処理し、現在の入力を確定させます。
     *
     * @return 確定処理として消費した場合は true
     */
    public boolean handleEnter() {
        if (mState.processEnter(this)) {
            updateComposingText();
            return true;
        }
        return false;
    }

    /**
     * バックスペースキー入力を処理します。
     *
     * @return 何らかの削除処理を実行し、イベントを消費した場合は true
     */
    public boolean handleBackspace() {
        InputState state = mState;
        state.beforeBackspace(this);

        if (mComposingBuffer.hasRomaji()) {
            mComposingBuffer.deleteLastRomajiChar();
            state.afterBackspace(this);
            updateComposingText();
            return true;
        }

        if (state.processBackspace(this)) {
            state.afterBackspace(this);
            updateComposingText();
            return true;
        }

        if (!state.isTransient()) {
            return false;
        }
        state.afterBackspace(this);

        updateComposingText();
        return true;
    }

    /**
     * キャンセル操作（Ctrl-G 等）を処理します。
     *
     * @return 何らかのキャンセル処理が実行された場合は true
     */
    public boolean handleCancel() {
        boolean result = false;
        if (mComposingBuffer.hasRomaji()) {
            mComposingBuffer.clearRomaji();
            result = true;
        }
        if (mState.handleCancel(this)) {
            result = true;
        }
        if (result) {
            updateComposingText();
        }
        mLastCommittedCandidate = null;
        return result;
    }

    /**
     * 状態を強制的に英数（ASCII）モードへ切り替えます。
     */
    public void toASCIIMode() {
        flushRomaji();
        changeMode(InputModeHalfLatin.INSTANCE, true);
        updateComposingText();
    }

    /**
     * かな（ひらがな/カタカナ）を交互に切り替えます。
     */
    public void toggleKana() {
        flushRomaji();
        mState.toggleKana(this);
        updateComposingText();
    }

    /**
     * 英語モード（半角英数）と日本語モード（ひらがな）を交互に切り替えます。
     */
    public void toggleEnglishJapanese() {
        if (mMode == InputModeHalfLatin.INSTANCE || mMode == InputModeFullLatin.INSTANCE) {
            handleKanaKey();
        } else {
            flushRomaji();
            mState.finish(this);
            changeState(InputStateIdle.INSTANCE);
            changeMode(InputModeHalfLatin.INSTANCE, true);
            updateComposingText();
        }
    }

    /**
     * カーソル位置から行末までの文字列を削除します（Kill Line）。
     *
     * @return 処理が行われた場合は true
     */
    public boolean handleKillLine() {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) {
            return false;
        }
        CharSequence textAfter = ic.getTextAfterCursor(2000, 0);
        if (textAfter == null || textAfter.length() == 0) {
            return false;
        }
        int len;
        int newlineIdx = -1;
        for (int i = 0; i < textAfter.length(); i++) {
            if (textAfter.charAt(i) == '\n' || textAfter.charAt(i) == '\r') {
                newlineIdx = i;
                break;
            }
        }
        if (newlineIdx == 0) {
            len = 1;
        } else if (newlineIdx > 0) {
            len = newlineIdx;
        } else {
            len = textAfter.length();
        }
        return ic.deleteSurroundingText(0, len);
    }

    /**
     * カーソル位置から行頭までの文字列を削除します（Kill Line Backward 相当）。
     *
     * @return 処理が行われた場合は true
     */
    public boolean handleKillLineBackward() {
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) {
            return false;
        }
        CharSequence textBefore = ic.getTextBeforeCursor(2000, 0);
        if (textBefore == null || textBefore.length() == 0) {
            return false;
        }
        int len;
        int newlineIdx = -1;
        for (int i = textBefore.length() - 1; i >= 0; i--) {
            if (textBefore.charAt(i) == '\n' || textBefore.charAt(i) == '\r') {
                newlineIdx = i;
                break;
            }
        }
        if (newlineIdx == textBefore.length() - 1) {
            len = 1;
        } else if (newlineIdx >= 0) {
            len = textBefore.length() - 1 - newlineIdx;
        } else {
            len = textBefore.length();
        }
        return ic.deleteSurroundingText(len, 0);
    }

    /**
     * 設定画面（SettingsActivity）を起動します。
     */
    public void launchSettings() {
        try {
            Intent intent = new Intent(mContext, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e("InputEngine", "Failed to launch SettingsActivity", e);
        }
    }

    /**
     * 絵文字ピッカーを開きます。
     */
    public void openEmojiPicker() {
        if (mContext instanceof InputService) {
            ((InputService) mContext).openEmojiPicker();
        }
    }

    /**
     * 現在のモードに基づき、トグルした際になるべき次の「かな」モードを取得します。
     *
     * @return 次の {@link InputMode}
     */
    public InputMode getToggledKanaMode() {
        return mMode.getToggledKanaMode(this);
    }

    /**
     * 与えられたテキストを、現在のモードの規則に従って最終変換（半角化など）します。
     *
     * @param text 変換対象のテキスト
     * @return 変換後のテキスト
     */
    public CharSequence convertText(CharSequence text) {
        return mMode.convertText(text);
    }

    /**
     * 指定されたテキストをエディタにコミットします。
     *
     * @param text               コミットするテキスト
     * @param newCursorPosition コミット後の新しいカーソル位置
     */
    public void commitTextInternal(CharSequence text, int newCursorPosition) {
        if (mService != null) {
            mService.commitText(text, newCursorPosition);
        }
    }

    /**
     * 新しい入力セッションの開始時に、エンジンの内部状態およびバッファを初期化します。
     * <p>
     * 以前のセッションの見出し語や未確定文字列が残らないよう、バッファをクリアし、
     * エディタ上の表示も最新の状態（空）に更新します。
     * </p>
     */
    public void resetOnStartInput() {
        reset();
        if (mState.isTransient()) {
            changeState(InputStateIdle.INSTANCE);
        }
        mService.requestUIUpdate();
        updateComposingText();
    }

    /**
     * 現在提示されている補完候補リストを取得します。
     *
     * @return 補完候補のリスト、または null
     */
    public List<String> getSuggestionList() {
        return mSuggestionList;
    }

    /**
     * 現在選択されている補完候補を取得します。
     *
     * @return 補完候補文字列、または null
     */
    public String getCurrentSuggestion() {
        if (mSuggestionList.isEmpty()) {
            return null;
        }
        if (mCurrentSuggestionIndex < 0 || mCurrentSuggestionIndex >= mSuggestionList.size()) {
            return null;
        }
        return mSuggestionList.get(mCurrentSuggestionIndex);
    }

    /**
     * 補完リストの中から、前後の候補を選択します。
     *
     * @param isForward 次に進む（正順）なら true
     */
    public void chooseAdjacentSuggestion(boolean isForward) {
        if (mSuggestionList.isEmpty()) {
            return;
        }
        int nextIndex = mCurrentSuggestionIndex;
        if (isForward) {
            nextIndex++;
        } else {
            nextIndex--;
        }

        int listSize = mSuggestionList.size();
        if (nextIndex > listSize - 1) {
            nextIndex = 0;
        } else if (nextIndex < 0) {
            nextIndex = listSize - 1;
        }

        mCurrentSuggestionIndex = nextIndex;
        mService.requestChooseCandidate(mCurrentSuggestionIndex);
        updateFloatingCandidates();
    }

    /**
     * 次の変換候補を選択します。
     * <p>
     * リストの末尾を超えた場合は先頭へループします。
     * </p>
     */
    public void chooseNextCandidate() {
        chooseCandidateInternal(true);
    }

    /**
     * 前の変換候補を選択します。
     * <p>
     * 先頭を超えた場合は変換キャンセル（見出し語入力状態へ復帰）となります。
     * </p>
     */
    public void choosePrevCandidate() {
        chooseCandidateInternal(false);
    }

    private void chooseCandidateInternal(boolean isForward) {
        if (mSegmentManager.hasSegments()) {
            Segment active = mSegmentManager.getActiveSegment();
            if (active == null || active.candidates.isEmpty()) return;
            int listSize = active.candidates.size();
            int nextIndex = active.selectedIndex + (isForward ? 1 : -1);

            if (nextIndex > listSize - 1) {
                nextIndex = 0;
            } else if (nextIndex < 0) {
                nextIndex = listSize - 1;
            }

            active.selectedIndex = nextIndex;
            mCurrentCandidateIndex = nextIndex;
            if (mService != null) {
                mService.requestChooseCandidate(mCurrentCandidateIndex);
            }
            updateFloatingCandidates();
            updateComposingText();
            return;
        }

        if (mCandidateList.isEmpty()) {
            return;
        }
        int nextIndex = mCurrentCandidateIndex;
        if (isForward) {
            nextIndex++;
        } else {
            nextIndex--;
        }

        int listSize = mCandidateList.size();
        if (nextIndex > listSize - 1) {
            mCurrentCandidateIndex = 0;
            mService.requestChooseCandidate(mCurrentCandidateIndex);
            updateFloatingCandidates();
            updateComposingText();
            return;
        } else if (nextIndex < 0) {
            changeState(InputStateComposing.INSTANCE);
            updateSuggestions();
            updateComposingText();

            mCurrentCandidateIndex = 0;
            return;
        }
        mCurrentCandidateIndex = nextIndex;
        mService.requestChooseCandidate(mCurrentCandidateIndex);
        updateFloatingCandidates();
        updateComposingText();
    }

    /**
     * 候補一覧ビューから特定の候補が手動で選択（タップ）された際の処理を行います。
     * 現在の状態が変換中（▼）であれば漢字候補を、入力中（▽）であれば補完候補を選択します。
     *
     * @param index 選択された項目のインデックス
     */
    public void pickCandidateViewManually(int index) {
        if (mState.isConverting()) {
            if (mSegmentManager.hasSegments()) {
                setCandidateIndex(index);
            } else {
                pickCandidate(index);
                updateComposingText();
            }
        } else if (mState == InputStateComposing.INSTANCE) {
            pickSuggestion(index);
            updateComposingText();
        } else if (mState == InputStateIdle.INSTANCE) {
            if (index >= 0 && index < mCandidateList.size()) {
                Candidate c = mCandidateList.get(index);
                commitTextInternal(c.candidate, 1);
                mLastCommittedCandidate = c.candidate;
                updateIdlePredictions();
            }
        }
    }

    /**
     * アイドル状態（確定後）において、直前確定単語に基づく予測候補を候補バーに表示します。
     */
    public void updateIdlePredictions() {
        if (mState != InputStateIdle.INSTANCE) return;
        if (mDictionary == null || mLastCommittedCandidate == null) {
            clearCandidates();
            return;
        }
        List<String> predictedSurfaces = mDictionary.getPredictedSurfaceWords(mLastCommittedCandidate, 10);
        if (predictedSurfaces == null || predictedSurfaces.isEmpty()) {
            clearCandidates();
            return;
        }
        List<Candidate> cands = new ArrayList<>();
        for (String surf : predictedSurfaces) {
            Candidate c = new Candidate(surf, surf);
            c.setType(Candidate.Type.PREDICTION);
            cands.add(c);
        }
        mCandidateList = cands;
        mCurrentCandidateIndex = 0;
        updateCandidates();
    }

    /**
     * 未確定のローマ字入力があるかどうかを判定します。
     *
     * @return 入力中なら true
     */
    public boolean hasComposing() {
        return mComposingBuffer.hasRomaji();
    }

    public StringBuilder getComposingText() {
        return mComposingBuffer.getComposingText();
    }

    public int getComposingCursor() {
        return mComposingBuffer.getComposingCursor();
    }

    public void setComposingCursor(int cursor) {
        mComposingBuffer.setComposingCursor(cursor);
        updateUI();
    }

    public void moveComposingCursor(int delta) {
        mComposingBuffer.moveComposingCursor(delta);
        updateUI();
    }

    public void insertComposingText(String text) {
        mComposingBuffer.insertComposingText(text);
        updateUI();
    }

    public boolean deleteComposingCharBeforeCursor() {
        boolean res = mComposingBuffer.deleteComposingCharBeforeCursor();
        if (res) updateUI();
        return res;
    }

    public boolean deleteComposingCharAfterCursor() {
        boolean res = mComposingBuffer.deleteComposingCharAfterCursor();
        if (res) updateUI();
        return res;
    }

    public boolean killComposingToLineEnd() {
        boolean res = mComposingBuffer.killComposingToLineEnd();
        if (res) updateUI();
        return res;
    }

    public boolean killComposingToLineStart() {
        boolean res = mComposingBuffer.killComposingToLineStart();
        if (res) updateUI();
        return res;
    }

    public void resetComposingCursor() {
        mComposingBuffer.resetComposingCursor();
    }

    public void restoreRemainingComposingText() {
        mComposingBuffer.restoreRemainingComposingText();
    }



    /**
     * エディタ上の未確定文字列（Composing Text）の表示を構築・更新します。
     * <p>
     * 現在の状態シンボル（▽▼）、および見出し語・ローマ字バッファの内容を連結し、
     * 適切な背景色（{@link BackgroundColorSpan}）と下線を付与してエディタに送信します。
     * </p>
     */
    void updateComposingText() {
        SpannableStringBuilder ct = new SpannableStringBuilder();

        if (mState.isConverting()) {
            int targetStart = ct.length();
            if (mSegmentManager.hasSegments()) {
                List<Segment> segments = mSegmentManager.getSegments();
                int focusedIdx = mSegmentManager.getFocusedSegmentIndex();
                for (int i = 0; i < segments.size(); i++) {
                    Segment seg = segments.get(i);
                    int segStart = ct.length();
                    ct.append(convertText(seg.getSelectedSurface()));
                    int segEnd = ct.length();
                    if (i == focusedIdx) {
                        ct.setSpan(new BackgroundColorSpan(mColorConverting), segStart, segEnd, Spanned.SPAN_COMPOSING);
                    } else {
                        ct.setSpan(new BackgroundColorSpan(mColorComposing), segStart, segEnd, Spanned.SPAN_COMPOSING);
                    }
                }
            } else {
                CharSequence stateText = mState.getComposingText(this);
                if (stateText != null) {
                    ct.append(stateText);
                }
                CharSequence converterText = mComposingBuffer.getRomajiBuffer();
                ct.append(converterText);
                int targetEnd = ct.length();
                ct.setSpan(new BackgroundColorSpan(mColorConverting), targetStart, targetEnd, Spanned.SPAN_COMPOSING);
            }

            if (mComposingBuffer.getRemainingComposingText() != null) {
                int remStart = ct.length();
                ct.append(convertText(mComposingBuffer.getRemainingComposingText()));
                int remEnd = ct.length();
                ct.setSpan(new BackgroundColorSpan(mColorComposing), remStart, remEnd, Spanned.SPAN_COMPOSING);
            }
        } else if (mState.isTransient()) {
            if (mState == InputStateComposing.INSTANCE) {
                int cursor = getComposingCursor();
                CharSequence convertedComposing = convertText(mComposingBuffer.getComposingText());
                String composingStr = convertedComposing.toString();
                cursor = Math.max(0, Math.min(composingStr.length(), cursor));

                String firstPart = composingStr.substring(0, cursor);
                String secondPart = composingStr.substring(cursor);
                CharSequence converterText = mComposingBuffer.getRomajiBuffer();

                boolean isPartial = (cursor < composingStr.length());

                int symStart = ct.length();
                ct.append(firstPart);
                ct.append(converterText);
                int targetEnd = ct.length();

                int nonTargetStart = ct.length();
                ct.append(secondPart);
                int nonTargetEnd = ct.length();

                if (isPartial) {
                    // 変換範囲変更時: 変換対象（firstPart）を変換用の背景色（mColorConverting）でハイライト
                    ct.setSpan(new BackgroundColorSpan(mColorConverting), symStart, targetEnd, Spanned.SPAN_COMPOSING);
                    if (nonTargetStart < nonTargetEnd) {
                        ct.setSpan(new BackgroundColorSpan(mColorComposing), nonTargetStart, nonTargetEnd, Spanned.SPAN_COMPOSING);
                    }
                } else {
                    // 通常の見出し語入力時: 全体を mColorComposing でハイライト
                    ct.setSpan(new BackgroundColorSpan(mColorComposing), symStart, ct.length(), Spanned.SPAN_COMPOSING);
                }
            } else {
                int bgStart = ct.length();
                CharSequence stateText = mState.getComposingText(this);
                if (stateText != null) {
                    ct.append(stateText);
                }
                CharSequence converterText = mComposingBuffer.getRomajiBuffer();
                ct.append(converterText);
                int bgEnd = ct.length();
                ct.setSpan(new BackgroundColorSpan(mColorComposing), bgStart, bgEnd, Spanned.SPAN_COMPOSING);
            }
        } else {
            CharSequence converterText = mComposingBuffer.getRomajiBuffer();
            if (converterText != null && converterText.length() > 0) {
                int bgStart = ct.length();
                ct.append(converterText);
                int bgEnd = ct.length();
                ct.setSpan(new BackgroundColorSpan(mColorComposing), bgStart, bgEnd, Spanned.SPAN_COMPOSING);
            }
        }

        int totalLen = ct.length();
        if (totalLen != 0) {
            UnderlineSpan underline = new UnderlineSpan();
            ct.setSpan(underline, 0, totalLen, Spanned.SPAN_COMPOSING);
        }

        if (mService != null) {
            mService.setComposingText(ct, 1);
            updateFloatingCandidates();
        }
    }

    public void conversionStart() {
        mComposingBuffer.splitComposingAtCursorIfNeeded();

        boolean success = conversionStartInternal(false);
        if (!success) {
            StringBuilder sb = new StringBuilder(mComposingBuffer.getComposingText());
            commitTextInternal(mMode.convertText(sb), 1);
            reset();
            changeState(InputStateIdle.INSTANCE);
        }
    }

    /**
     * 単一の読み（文節単位）に対する変換候補リストを取得します。
     */
    public List<Candidate> getSegmentCandidates(String segmentReading, String preferredSurface) {
        List<Candidate> rawCands = new ArrayList<>();

        if (mViterbiDecoder != null) {
            try {
                List<Candidate> viterbiCands = mViterbiDecoder.decode(segmentReading, null);
                if (viterbiCands != null) {
                    rawCands.addAll(viterbiCands);
                }
            } catch (Exception e) {
                Log.e("InputEngine", "Viterbi decode error for segment: " + segmentReading, e);
            }
        }

        if (mDictionary != null) {
            List<Candidate> dictCands = mDictionary.findCandidates(segmentReading);
            if (dictCands != null) {
                rawCands.addAll(dictCands);
            }
        }

        String wideKatakana = RomajiConverter.toWideKatakana(segmentReading);
        String halfKatakana = RomajiConverter.toHalfKatakana(segmentReading);

        Set<String> seen = new HashSet<>();
        List<Candidate> merged = new ArrayList<>();

        if (preferredSurface != null && !preferredSurface.isEmpty()) {
            seen.add(preferredSurface);
            Candidate prefCand = null;
            for (Candidate c : rawCands) {
                if (c != null && preferredSurface.equals(c.candidate)) {
                    prefCand = c;
                    break;
                }
            }
            if (prefCand == null) {
                prefCand = new Candidate(preferredSurface, preferredSurface);
            }
            merged.add(prefCand);
        }

        for (Candidate c : rawCands) {
            if (c != null && c.candidate != null && !c.candidate.isEmpty()) {
                if (seen.add(getCandidateDisplayKey(c))) {
                    merged.add(c);
                }
            }
        }

        if (seen.add(segmentReading)) {
            merged.add(new Candidate(segmentReading, segmentReading));
        }
        if (seen.add(wideKatakana)) {
            merged.add(new Candidate(wideKatakana, wideKatakana));
        }
        if (seen.add(halfKatakana)) {
            merged.add(new Candidate(halfKatakana, halfKatakana));
        }

        return merged;
    }

    /**
     * 現在フォーカスされている文節の候補リストおよびインデックスをエンジンの活性情報に同期させます。
     */
    public void syncActiveSegment() {
        if (mSegmentManager.hasSegments()) {
            Segment active = mSegmentManager.getActiveSegment();
            if (active != null) {
                mCandidateList = active.candidates;
                mCurrentCandidateIndex = active.selectedIndex;
                updateCandidates();
                updateFloatingCandidates();
                updateComposingText();
            }
        }
    }

    /**
     * 次の文節へフォーカスを移動します。
     *
     * @return フォーカス移動が成功した場合は true
     */
    public boolean focusNextSegment() {
        if (mSegmentManager.focusNextSegment()) {
            syncActiveSegment();
            return true;
        }
        return false;
    }

    /**
     * 前の文節へフォーカスを移動します。
     *
     * @return フォーカス移動が成功した場合は true
     */
    public boolean focusPrevSegment() {
        if (mSegmentManager.focusPrevSegment()) {
            syncActiveSegment();
            return true;
        }
        return false;
    }

    /**
     * 分割されている文節のリストを取得します。
     */
    public List<Segment> getSegments() {
        return mSegmentManager.getSegments();
    }

    /**
     * 現在フォーカス中の文節のインデックスを取得します。
     */
    public int getFocusedSegmentIndex() {
        return mSegmentManager.getFocusedSegmentIndex();
    }

    /**
     * 全文節の選択候補文字列を連結した文字列を取得します。
     */
    public String getMultiSegmentText() {
        return mSegmentManager.getMultiSegmentText();
    }

    /**
     * 現在フォーカス中の文節の読長さを変更（拡大・縮小）し、残りをViterbiで再分割します。
     *
     * @param delta +1 で拡大（読長さを1文字増やす）、-1 で縮小（読長さを1文字減らす）
     * @return 変更が行われた場合は true
     */
    public boolean resizeFocusedSegment(int delta) {
        boolean res = mSegmentManager.resizeFocusedSegment(
                delta,
                mComposingBuffer.getSearchQuery(),
                this::getSegmentCandidates,
                mViterbiDecoder
        );
        if (res) {
            syncActiveSegment();
        }
        return res;
    }

    /**
     * 辞書検索プロセスの共通実装です。
     *
     * @param lastCandidate リストの末尾の候補を最初から選択するかどうか
     * @return 1 つ以上の候補が見つかった場合は true
     */
    private boolean conversionStartInternal(boolean lastCandidate) {
        String query = mComposingBuffer.getSearchQuery();

        List<Segment> segments = new ArrayList<>();
        if (mViterbiDecoder != null) {
            try {
                List<Candidate> viterbiCands = mViterbiDecoder.decode(query, null);
                if (viterbiCands != null && !viterbiCands.isEmpty() && viterbiCands.get(0).viterbiPathNode != null) {
                    ViterbiDecoder.PathNode curr = viterbiCands.get(0).viterbiPathNode;
                    List<ViterbiDecoder.PathNode> nodes = new ArrayList<>();
                    while (curr != null && curr.prev != null) {
                        if (curr.reading != null && !curr.reading.isEmpty()) {
                            nodes.add(0, curr);
                        }
                        curr = curr.prev;
                    }

                    StringBuilder sb = new StringBuilder();
                    for (ViterbiDecoder.PathNode n : nodes) {
                        sb.append(n.reading);
                    }

                    if (sb.toString().equals(query) && !nodes.isEmpty()) {
                        for (ViterbiDecoder.PathNode n : nodes) {
                            List<Candidate> segCands = getSegmentCandidates(n.reading, n.surface);
                            segments.add(new Segment(n.reading, segCands, 0));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("InputEngine", "Viterbi decode error", e);
            }
        }

        if (segments.isEmpty()) {
            List<Candidate> fullCands = getSegmentCandidates(query, null);
            if (fullCands.isEmpty()) {
                return false;
            }
            int initialIdx = lastCandidate ? fullCands.size() - 1 : 0;
            segments.add(new Segment(query, fullCands, initialIdx));
        }

        mSegmentManager.setSegments(segments);

        changeState(InputStateConverting.INSTANCE);
        syncActiveSegment();
        return true;
    }




    /**
     * 現在選択中の候補インデックスを取得します。
     *
     * @return 選択インデックス
     */
    public int getCurrentCandidateIndex() {
        if (mState.isConverting() && !mCandidateList.isEmpty()) {
            return mCurrentCandidateIndex;
        } else if (!mSuggestionList.isEmpty()) {
            return mCurrentSuggestionIndex;
        }
        return 0;
    }

    /**
     * 候補選択インデックスを直接指定し、UIおよび Composing Text を更新します。
     *
     * @param index 設定する新しいインデックス
     */
    public void setCandidateIndex(int index) {
        if (mState.isConverting()) {
            if (mSegmentManager.hasSegments()) {
                Segment active = mSegmentManager.getActiveSegment();
                if (active != null && index >= 0 && index < active.candidates.size()) {
                    active.selectedIndex = index;
                    mCurrentCandidateIndex = index;
                    if (mService != null) {
                        mService.requestChooseCandidate(mCurrentCandidateIndex);
                    }
                    updateFloatingCandidates();
                    updateComposingText();
                }
                return;
            }
            if (!mCandidateList.isEmpty()) {
                if (index >= 0 && index < mCandidateList.size()) {
                    mCurrentCandidateIndex = index;
                    if (mService != null) {
                        mService.requestChooseCandidate(mCurrentCandidateIndex);
                    }
                    updateFloatingCandidates();
                    updateComposingText();
                }
            }
        } else if (!mSuggestionList.isEmpty()) {
            if (index >= 0 && index < mSuggestionList.size()) {
                mCurrentSuggestionIndex = index;
                if (mService != null) {
                    mService.requestChooseCandidate(mCurrentSuggestionIndex);
                }
                updateFloatingCandidates();
                updateComposingText();
            }
        }
    }



    private String getCandidateDisplayKey(Candidate c) {
        return c.candidate;
    }

    private List<String> getAllCandidateItems(List<Candidate> list) {
        List<String> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : list) {
            String item = getCandidateDisplayKey(c);
            if (seen.add(item)) {
                items.add(item);
            }
        }
        return items;
    }

    private List<String> getAllSuggestionItems(List<String> list) {
        return new ArrayList<>(list);
    }

    /**
     * FlexboxLayout（チップ折り返し）ポップアップ用表示を更新します。
     * すべての候補を一括配置し、スムーズにスクロール追従させます。
     */
    public void updateFloatingCandidates() {
        if (mService == null) return;

        if (!mService.isFloatingCandidateEnabled()) {
            mService.hideFloatingCandidates();
            return;
        }

        if (mState.isConverting() && !mCandidateList.isEmpty()) {
            List<String> items = getAllCandidateItems(mCandidateList);
            mService.showCandidateFlexboxPopup(null, items, mCurrentCandidateIndex);
        } else if (!mSuggestionList.isEmpty()) {
            List<String> items = getAllSuggestionItems(mSuggestionList);
            mService.showCandidateFlexboxPopup(null, items, mCurrentSuggestionIndex);
        } else {
            mService.hideFloatingCandidates();
        }
    }


    /**
     * 現在の候補リストを整形し、UI 側（InputService）へ送ります。
     */
    private void updateCandidates() {
        if (mService != null) {
            mService.setCandidateObjects(mCandidateList);
            updateFloatingCandidates();
        }
    }

    /**
     * 入力途中の見出し語に基づき、動的補完（Suggestion）リストを更新します。
     */
    public void updateSuggestions() {
        if (!mComposingBuffer.hasComposingText()) {
            mSuggestionList = Collections.emptyList();
            mCandidateList = Collections.emptyList();
            if (mService != null) {
                mService.hideCandidatesView();
                mService.hideFloatingCandidates();
            }
            return;
        }

        String query = mComposingBuffer.getComposingTextString();
        List<Candidate> rawCandidates = new ArrayList<>();

        if (mViterbiDecoder != null) {
            try {
                List<Candidate> viterbiCands = mViterbiDecoder.decode(query, null);
                if (viterbiCands != null) {
                    rawCandidates.addAll(viterbiCands);
                }
            } catch (Exception e) {
                Log.e("InputEngine", "Viterbi decode error", e);
            }
        }

        if (mDictionary != null) {
            List<Candidate> dictCands = mDictionary.findCandidates(query);
            if (dictCands != null) {
                rawCandidates.addAll(dictCands);
            }
        }

        Set<String> predictedSurfaces = new HashSet<>();
        if (mDictionary != null && mLastCommittedCandidate != null) {
            List<String> predicted = mDictionary.getPredictedSurfaceWords(mLastCommittedCandidate, 50);
            if (predicted != null) {
                predictedSurfaces.addAll(predicted);
            }
        }

        String wideKatakana = RomajiConverter.toWideKatakana(query);
        String halfKatakana = RomajiConverter.toHalfKatakana(query);

        Set<String> seen = new HashSet<>();
        List<Candidate> predictionList = new ArrayList<>();
        List<Candidate> otherList = new ArrayList<>();

        for (Candidate c : rawCandidates) {
            if (c != null && c.candidate != null && !c.candidate.isEmpty()) {
                String key = getCandidateDisplayKey(c);
                if (seen.add(key)) {
                    if (predictedSurfaces.contains(c.candidate)) {
                        if (c.type == Candidate.Type.SYSTEM) {
                            c.setType(Candidate.Type.PREDICTION);
                        }
                        predictionList.add(c);
                    } else {
                        otherList.add(c);
                    }
                }
            }
        }

        List<Candidate> merged = new ArrayList<>();
        merged.addAll(predictionList);
        merged.addAll(otherList);

        if (seen.add(query)) {
            merged.add(new Candidate(query, query));
        }
        if (seen.add(wideKatakana)) {
            merged.add(new Candidate(wideKatakana, wideKatakana));
        }
        if (seen.add(halfKatakana)) {
            merged.add(new Candidate(halfKatakana, halfKatakana));
        }

        mCandidateList = merged;
        mCurrentCandidateIndex = 0;

        if (mService != null) {
            mService.setCandidateObjects(mCandidateList);
            updateFloatingCandidates();
        }
    }

    /**
     * 単語登録セッションを開始します。
     * <p>
     * 現在の見出し語（および送り仮名）を保持したまま、新しい登録レイヤーをスタックに積み、
     * ユーザーが登録語本体を入力できる確定（Normal）状態へ移行します。
     * </p>
     */


    /**
     * 現在の候補リストを取得します。
     *
     * @return 候補オブジェクトのリスト
     */
    public List<Candidate> getCandidateList() {
        return mCandidateList;
    }

    /**
     * 現在選択中の候補文字列（送り込み）を取得します。
     *
     * @return 確定用文字列
     */
    public String getCurrentCandidate() {
        if (mCandidateList.isEmpty()) {
            return "";
        }
        Candidate c = mCandidateList.get(mCurrentCandidateIndex);
        return c.candidate;
    }

    /**
     * 現在選択されている変換候補を確定します。
     */
    public void pickCurrentCandidate() {
        pickCandidate(mCurrentCandidateIndex);
    }

    /**
     * 指定されたインデックスの候補を確定し、学習情報を反映させます。
     *
     * @param index 候補のインデックス
     */
    public void pickCandidate(int index) {
        if (!mState.isConverting()) {
            return;
        }

        if (mSegmentManager.hasSegments()) {
            List<Segment> segments = mSegmentManager.getSegments();
            Segment active = mSegmentManager.getActiveSegment();
            if (active != null && index >= 0 && index < active.candidates.size()) {
                active.selectedIndex = index;
            }

            StringBuilder fullSurface = new StringBuilder();
            List<String> surfaces = new ArrayList<>();
            for (Segment seg : segments) {
                String surf = seg.getSelectedSurface();
                fullSurface.append(surf);
                if (surf != null && !surf.isEmpty()) {
                    surfaces.add(surf);
                }
            }

            CharSequence finalCandidate = mMode.convertText(fullSurface.toString());
            commitTextInternal(finalCandidate, 1);

            if (mDictionary != null) {
                if (mEnableLearning) {
                    for (Segment seg : segments) {
                        Candidate selected = seg.getSelectedCandidate();
                        if (selected != null && !selected.isSuggestion() && !selected.isPrediction() && selected.rawCandidate != null) {
                            logI("Learning segment: key=" + seg.reading + ", cand=" + selected.rawCandidate);
                            mDictionary.addEntry(seg.reading, selected.rawCandidate, selected.getPosId(), selected.getCost());
                        }
                    }
                }

                if (!surfaces.isEmpty()) {
                    if (mEnableLearning) {
                        if (mLastCommittedCandidate != null) {
                            mDictionary.learnTransition(mLastCommittedCandidate, surfaces.get(0));
                        }
                        for (int i = 0; i < surfaces.size() - 1; i++) {
                            mDictionary.learnTransition(surfaces.get(i), surfaces.get(i + 1));
                        }
                    }
                    mLastCommittedCandidate = surfaces.get(surfaces.size() - 1);
                } else {
                    mLastCommittedCandidate = finalCandidate.toString();
                }
            } else {
                if (!segments.isEmpty()) {
                    String lastSurf = segments.get(segments.size() - 1).getSelectedSurface();
                    mLastCommittedCandidate = (lastSurf != null && !lastSurf.isEmpty()) ? lastSurf : finalCandidate.toString();
                } else {
                    mLastCommittedCandidate = finalCandidate.toString();
                }
            }

            mSegmentManager.clear();

            if (mComposingBuffer.getRemainingComposingText() != null) {
                mComposingBuffer.restoreRemainingComposingText();
                changeState(InputStateComposing.INSTANCE);
            } else {
                changeState(InputStateIdle.INSTANCE);
            }
            return;
        }

        if (index < 0 || index >= mCandidateList.size()) {
            if (mCandidateList.isEmpty()) {
                reset();
                changeState(InputStateIdle.INSTANCE);
            }
            return;
        }

        Candidate c = mCandidateList.get(index);
        String candidateStr = c.candidate;
        CharSequence finalCandidate = mMode.convertText(candidateStr);
        commitTextInternal(finalCandidate, 1);

        String key = mComposingBuffer.getSearchQuery();

        if (mEnableLearning && !c.isSuggestion() && !c.isPrediction() && c.rawCandidate != null) {
            logI("Learning: key=" + key + ", cand=" + c.rawCandidate);
            if (mLastCommittedCandidate != null) {
                mDictionary.learnTransition(mLastCommittedCandidate, finalCandidate.toString());
            }
            mDictionary.addEntry(key, c.rawCandidate, c.getPosId(), c.getCost());
        } else {
            logI("Skip learning: enable=" + mEnableLearning + ", suggestion/prediction=" + (c.isSuggestion() || c.isPrediction()) + ", raw=" + (c.rawCandidate != null));
        }

        mLastCommittedCandidate = finalCandidate.toString();

        if (mComposingBuffer.getRemainingComposingText() != null) {
            mComposingBuffer.restoreRemainingComposingText();
            changeState(InputStateComposing.INSTANCE);
        } else {
            changeState(InputStateIdle.INSTANCE);
        }
    }

    /**
     * 現在選択中の補完候補（Suggestion）を確定し、変換中状態へ移行します。
     */
    public void pickCurrentSuggestion() {
        pickSuggestion(mCurrentSuggestionIndex);
    }

    /**
     * 指定されたインデックスの補完候補を確定します。
     *
     * @param index 補完リスト内のインデックス
     */
    public void pickSuggestion(int index) {
        if (index < 0 || index >= mSuggestionList.size()) {
            return;
        }
        String s = mSuggestionList.get(index);

        mComposingBuffer.setComposingText(s);
        if (mState == InputStateComposing.INSTANCE) {
            int lastIndex = s.length() - 1;
            int lastCode = s.codePointAt(lastIndex);
            if (RomajiConverter.isAlphabet(lastCode)) {
                mComposingBuffer.getComposingText().deleteCharAt(lastIndex);
                int upperCode = Character.toUpperCase(lastCode);
                processKey(upperCode);
            } else {
                conversionStart();
            }
        }
    }

    /**
     * エンジンの内部状態（バッファ、変換候補、登録スタック等）をすべて初期化します。
     * 辞書以外の実行時キャッシュをクリアし、初期の確定モード（Direct）へ戻る準備を整えます。
     */
    public void reset() {
        mLastCommittedCandidate = null;
        clearBuffers();
    }

    /**
     * 入力バッファや変換候補などの一時的な状態をクリアします。
     */
    public void clearBuffers() {
        mComposingBuffer.clearAll();
        clearCandidates();
    }

    /**
     * 漢字変換候補および補完候補のリストをクリアし、候補一覧ビューを非表示にします。
     * 状態遷移時や、入力をキャンセルしてクリーンアップが必要な際に呼び出されます。
     */
    public void clearCandidates() {
        mSegmentManager.clear();
        mCandidateList = Collections.emptyList();
        mSuggestionList = Collections.emptyList();
        mCurrentCandidateIndex = 0;
        mCurrentSuggestionIndex = 0;
        if (mService != null) {
            mService.hideCandidatesView();
            mService.hideFloatingCandidates();
        }
    }

    /**
     * 入力モードを変更します。
     *
     * @param mode   新しいモード
     * @param finish 現在の状態を強制確定させてリセットする場合は true
     */
    public void changeMode(InputMode mode, boolean finish) {
        if (finish) {
            mState.finish(this);
            changeState(InputStateIdle.INSTANCE);
        }
        mMode = mode;
        updateUI();
    }


    /**
     * 入力状態を変更します。
     *
     * @param state  新しい状態
     * @param finish 現在の状態を強制確定させる場合は true
     */
    public void changeState(InputState state, boolean finish) {
        if (finish) {
            mState.finish(this);
        }
        changeState(state);
    }

    /**
     * 入力状態を変更します。
     *
     * @param state 新しい状態
     */
    public void changeState(InputState state) {
        mState.onExitState(this);
        mState = state;
        mState.onEnterState(this);
        updateUI();
    }

    /**
     * 半角カタカナを使用するかどうかを取得します。
     *
     * @return 使用する場合は true
     */
    public boolean useJisx0201Kana() {
        return mUseJisx0201Kana;
    }

    /**
     * アイコンやツールチップなどのUI表示を更新します。
     */
    private void updateUI() {
        if (mService != null) {
            mService.requestUIUpdate();
        }
    }
}
