package io.github.kachaya.ime.engine;

/**
 * 「入力モード」の振る舞いを定義するインターフェースです。
 * <p>
 * 入力モード（ひらがな、カタカナ、英数、全角英数）に応じた文字変換のロジック、
 * モード固有のアイコン表示、およびツールチップテキストの提供を Strategy パターンで管理します。
 * </p>
 */
public interface InputMode {
    /**
     * 指定されたキーコードを現在のモードの規則に従って処理します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param code 入力されたキーの Unicode コードポイント
     */
    void processKey(InputEngine context, int code);

    /**
     * キー入力アクションを、現在のモードに従って処理します。
     *
     * @param context 変換エンジンのコンテキスト
     * @param action  割り当てられている InputAction
     * @return イベントを消費した場合は true
     */
    boolean processInputAction(InputEngine context, InputAction action);

    /**
     * 与えられたテキストを、現在のモードの性質に合わせて最終変換（半角化、全角化等）します。
     *
     * @param text 変換対象の文字列
     * @return 変換後の文字列
     */
    CharSequence convertText(CharSequence text);

    /**
     * かなモード（ひらがなとカタカナ）を交互に切り替える際の、遷移先となるモードを取得します。
     *
     * @param context 変換エンジンのコンテキスト
     * @return 遷移先の InputMode インスタンス
     */
    InputMode getToggledKanaMode(InputEngine context);
}
