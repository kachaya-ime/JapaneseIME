package io.github.kachaya.ime.dictionary;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import io.github.kachaya.ime.engine.ViterbiDecoder;

/**
 * 変換候補1つを表現するデータモデルクラスです。
 */
public class Candidate {

    /** 候補の種類や由来を表す列挙型。 */
    public enum Type {
        SYSTEM,
        LEARNING,
        PREDICTION,
        SUGGESTION,
        LLM
    }

    /** 候補の種類・由来。 */
    public Type type;
    /** 辞書に登録されている未加工の候補文字列。 */
    public String rawCandidate;
    /** 実際にユーザーに提示・確定される候補文字列。 */
    public String candidate;
    /** 辞書エントリ情報（品詞ID、コストなど）。 */
    public WordEntry entry;
    /** Viterbi解析結果のパス（PathNodeチェーンの終端ノード）。 */
    public ViterbiDecoder.PathNode viterbiPathNode;

    /**
     * 新しい変換候補オブジェクトを構築します（デフォルトは SYSTEM）。
     *
     * @param rawCandidate 生の辞書データ
     * @param template     表示・確定用のテンプレート文字列
     */
    public Candidate(String rawCandidate, String template) {
        this.rawCandidate = rawCandidate;
        this.candidate = template;
        this.type = Type.SYSTEM;
    }

    /**
     * 学習候補であるかどうかを判定します。
     *
     * @return 学習候補の場合は true
     */
    public boolean isLearningWord() {
        return type == Type.LEARNING;
    }

    /**
     * 予測候補であるかどうかを判定します。
     *
     * @return 予測候補の場合は true
     */
    public boolean isPrediction() {
        return type == Type.PREDICTION;
    }

    /**
     * 補完候補であるかどうかを判定します。
     *
     * @return 補完候補の場合は true
     */
    public boolean isSuggestion() {
        return type == Type.SUGGESTION;
    }

    /**
     * 候補の種類（Type）を設定します。
     *
     * @param type 候補の種類
     * @return このインスタンス自身
     */
    public Candidate setType(Type type) {
        this.type = type != null ? type : Type.SYSTEM;
        return this;
    }

    /**
     * 辞書エントリ情報を設定します。
     *
     * @param entry 辞書エントリ
     * @return このインスタンス自身
     */
    public Candidate setEntry(WordEntry entry) {
        this.entry = entry;
        return this;
    }

    /**
     * Viterbi解析結果のPathNodeを設定します。
     *
     * @param viterbiPathNode Viterbi解析のパスノード
     * @return このインスタンス自身
     */
    public Candidate setViterbiPathNode(ViterbiDecoder.PathNode viterbiPathNode) {
        this.viterbiPathNode = viterbiPathNode;
        return this;
    }

    /**
     * 品詞ID (POS ID) を取得します。
     *
     * @return 品詞ID
     */
    public int getPosId() {
        return entry != null ? entry.posId : 0;
    }

    /**
     * 評価コストを取得します。
     *
     * @return 評価コスト
     */
    public int getCost() {
        return entry != null ? entry.cost : 0;
    }

    /**
     * 候補のタイプに応じたバッジ文字列を取得します。
     *
     * @return バッジ文字列
     */
    public String getTypeBadge() {
        switch (type) {
            case LEARNING:
                return " [学]";
            case PREDICTION:
                return " [予測]";
            case SUGGESTION:
                return " [予]";
            case LLM:
                return " [AI]";
            default:
                return "";
        }
    }

    /**
     * 指定されたベーステキスト（候補文字列）にタイプバッジを装飾（縮小・グレー化）して付与したCharSequenceを返します。
     *
     * @param baseText 候補のベーステキスト
     * @return 装飾されたCharSequence
     */
    public CharSequence getFormattedText(CharSequence baseText) {
        String badge = getTypeBadge();
        if (badge.isEmpty()) {
            return baseText;
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder(baseText);
        int start = ssb.length();
        ssb.append(badge);
        ssb.setSpan(new RelativeSizeSpan(0.5f), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ForegroundColorSpan(0xFF888888), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }
}
