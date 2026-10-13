package io.github.kachaya.ime.dictionary;

import androidx.annotation.NonNull;

/**
 * 辞書内の単語エントリ（読み、表層形、品詞ID、コスト、学習フラグ等）を保持するデータクラスです。
 */
public class WordEntry {
    /** 単語の読み（かな文字列）。 */
    public final String reading;
    /** 単語の表層形（表記）。 */
    public final String surface;
    /** 品詞 ID。 */
    public final int posId;
    /** 単語の生コスト（評価値）。 */
    public final int cost;

    /** 単語エントリのタイプ（システム/学習など）。 */
    public Candidate.Type type;

    /**
     * WordEntry を構築します。
     *
     * @param reading 読み文字列
     * @param surface 表層形文字列
     * @param posId   品詞ID
     * @param cost    評価コスト
     */
    public WordEntry(String reading, String surface, int posId, int cost) {
        this.reading = reading;
        this.surface = surface;
        this.posId = posId;
        this.cost = cost;
        this.type = Candidate.Type.SYSTEM;
    }

    /**
     * この単語が学習単語であるかどうかを判定します。
     *
     * @return 学習単語の場合は true
     */
    public boolean isLearningWord() {
        return type == Candidate.Type.LEARNING;
    }

    @NonNull
    @Override
    public String toString() {
        return reading + " " + surface;
    }
}
