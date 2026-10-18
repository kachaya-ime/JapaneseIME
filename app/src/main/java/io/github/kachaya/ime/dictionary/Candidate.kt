package io.github.kachaya.ime.dictionary

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import io.github.kachaya.ime.engine.ViterbiDecoder

/**
 * 変換候補1つを表現するデータモデルクラスです。
 */
class Candidate @JvmOverloads constructor(
    @JvmField var rawCandidate: String?,
    @JvmField var candidate: String?,
    zenzAi: String? = null
) {
    /** 候補の種類や由来を表す列挙型。 */
    enum class Type {
        SYSTEM,
        LEARNING,
        PREDICTION,
        SUGGESTION,
        ZENZ
    }

    /** 候補の種類・由来。 */
    @JvmField
    var type: Type = Type.SYSTEM

    /** 辞書エントリ情報（品詞ID、コストなど）。 */
    @JvmField
    var entry: WordEntry? = null

    /** Viterbi解析結果のパス（PathNodeチェーンの終端ノード）。 */
    @JvmField
    var viterbiPathNode: ViterbiDecoder.PathNode? = null

    /** 注釈（アノテーション）情報。 */
    @JvmField
    var annotation: String? = null

    /**
     * 学習候補であるかどうかを判定します。
     *
     * @return 学習候補の場合は true
     */
    fun isLearningWord(): Boolean = type == Type.LEARNING

    /**
     * 予測候補であるかどうかを判定します。
     *
     * @return 予測候補の場合は true
     */
    fun isPrediction(): Boolean = type == Type.PREDICTION

    /**
     * 補完候補であるかどうかを判定します。
     *
     * @return 補完候補の場合は true
     */
    fun isSuggestion(): Boolean = type == Type.SUGGESTION

    /**
     * Zenz LLM 変換候補であるかどうかを判定します。
     *
     * @return Zenz 候補の場合は true
     */
    fun isZenz(): Boolean = type == Type.ZENZ

    /**
     * 注釈情報（annotation）を設定します。
     *
     * @param annotation 注釈文字列
     * @return このインスタンス自身
     */
    fun setAnnotation(annotation: String?): Candidate {
        this.annotation = annotation
        return this
    }

    /**
     * 候補の種類（Type）を設定します。
     *
     * @param type 候補の種類
     * @return このインスタンス自身
     */
    fun setType(type: Type?): Candidate {
        this.type = type ?: Type.SYSTEM
        return this
    }

    /**
     * 辞書エントリ情報を設定します。
     *
     * @param entry 辞書エントリ
     * @return このインスタンス自身
     */
    fun setEntry(entry: WordEntry?): Candidate {
        this.entry = entry
        return this
    }

    /**
     * Viterbi解析結果のPathNodeを設定します。
     *
     * @param viterbiPathNode Viterbi解析のパスノード
     * @return このインスタンス自身
     */
    fun setViterbiPathNode(viterbiPathNode: ViterbiDecoder.PathNode?): Candidate {
        this.viterbiPathNode = viterbiPathNode
        return this
    }

    /**
     * 品詞ID (POS ID) を取得します。
     *
     * @return 品詞ID
     */
    fun getPosId(): Int = entry?.posId ?: 0

    /**
     * 評価コストを取得します。
     *
     * @return 評価コスト
     */
    fun getCost(): Int = entry?.cost ?: 0

    /**
     * 候補のタイプに応じたバッジ文字列を取得します。
     *
     * @return バッジ文字列
     */
    fun getTypeBadge(): String {
        return when (type) {
            Type.LEARNING -> " [L]"
            Type.PREDICTION -> " [P]"
            Type.SUGGESTION -> " [S]"
            Type.ZENZ -> " [Z]"
            else -> ""
        }
    }

    /**
     * 指定されたベーステキスト（候補文字列）にタイプバッジを装飾（縮小・グレー化）して付与したCharSequenceを返します。
     *
     * @param baseText 候補のベーステキスト
     * @return 装飾されたCharSequence
     */
    fun getFormattedText(baseText: CharSequence): CharSequence {
        val badge = getTypeBadge()
        if (badge.isEmpty()) {
            return baseText
        }
        val ssb = SpannableStringBuilder(baseText)
        val start = ssb.length
        ssb.append(badge)
        ssb.setSpan(RelativeSizeSpan(0.45f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(ForegroundColorSpan(0xFF888888.toInt()), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }
}
