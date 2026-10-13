package io.github.kachaya.ime.keyboard;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.Button;

import io.github.kachaya.ime.InputService;
import io.github.kachaya.ime.R;

/**
 * キーボードの各キー（Button）を生成し、共通のスタイルを適用するためのファクトリクラスです。
 * <p>
 * 実際の入力ビュー（InputView）とカスタマイズ画面（KeyboardCustomizerActivity）の両方で
 * 一貫した見た目を維持するために使用されます。
 * </p>
 */
public class KeyViewFactory {

    /**
     * 指定された KeyConfig に基づいて、スタイル適用済みの Button を生成します。
     *
     * @param context コンテキスト
     * @param config  キーの構成情報
     * @return スタイルが適用された Button インスタンス
     */
    public static Button createKeyButton(Context context, KeyConfig config) {
        Context themedContext = InputService.getThemedContext(context);
        // 機能キー（コードあり）か文字キーかでスタイルと背景を切り替える
        boolean isFunctional = (config.code != KeyConfig.CODE_NONE);
        int style = isFunctional ? R.style.FunctionalKeyButton : R.style.CharacterButton;
        int bgRes = isFunctional ? R.drawable.bg_function_button_selector : R.drawable.bg_character_button_selector;

        // ContextThemeWrapper を使用してスタイルを適用した Button を生成
        Button b = new Button(new ContextThemeWrapper(themedContext, style), null, 0);
        b.setTag(config);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);

        // 背景を明示的に設定（Borderless スタイルによる背景消失の防止）
        b.setBackgroundResource(bgRes);

        // ラベルテキストとフォントサイズを設定
        updateKeyButton(b, config);

        return b;
    }

    /**
     * キーボタンの構成（ラベル、フォントサイズ、機能キー用背景・テキスト色）を最新の KeyConfig に更新します。
     *
     * @param b 対象の Button
     * @param config 設定するキーの構成情報
     */
    public static void updateKeyButton(Button b, KeyConfig config) {
        if (b == null) return;
        if (config == null) {
            updateKeyLabel(b, "");
            b.setTag(null);
            return;
        }

        b.setTag(config);
        updateKeyLabel(b, config.label);

        boolean isFunctional = (config.code != KeyConfig.CODE_NONE);
        int bgRes = isFunctional ? R.drawable.bg_function_button_selector : R.drawable.bg_character_button_selector;
        b.setBackgroundResource(bgRes);

        Context themedContext = InputService.getThemedContext(b.getContext());
        int textColorRes = isFunctional ? R.color.function_button_fg : R.color.character_button_fg;
        b.setTextColor(androidx.core.content.ContextCompat.getColor(themedContext, textColorRes));
    }

    /**
     * キーボタンのラベルテキストを設定し、文字数に応じて適切なフォントサイズ（2文字以上は12sp、1文字以下は22sp）を適用します。
     *
     * @param b 対象の Button
     * @param label 設定するラベル文字列
     */
    public static void updateKeyLabel(Button b, String label) {
        if (b == null) return;
        b.setText(label);
        if (label != null && label.length() >= 2) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        } else {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        }
    }
}
