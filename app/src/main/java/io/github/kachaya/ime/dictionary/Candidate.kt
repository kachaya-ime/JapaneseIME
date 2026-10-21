package io.github.kachaya.ime.dictionary

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import io.github.kachaya.ime.engine.Segment
import io.github.kachaya.ime.viterbi.ViterbiDecoder

/**
 * 変換候補1つを表現するデータモデルクラスです。
 */
class Candidate constructor(
    var rawCandidate: String?,
    var candidate: String?,
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
    var type: Type = Type.SYSTEM

    /** 辞書エントリ情報（品詞ID、コストなど）。 */
    var entry: WordEntry? = null

    /** Viterbi解析結果のパス（PathNodeチェーンの終端ノード）。 */
    var viterbiPathNode: ViterbiDecoder.PathNode? = null

    /** 注釈（アノテーション）情報。 */
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

/**
 * 辞書内の単語エントリ（読み、表層形、品詞ID、コスト、学習フラグ等）を保持するデータクラスです。
 */
class WordEntry(
    val reading: String,
    val surface: String,
    val posId: Int,
    val cost: Int
) {
    var type: Candidate.Type = Candidate.Type.SYSTEM

    /**
     * この単語が学習単語であるかどうかを判定します。
     *
     * @return 学習単語の場合は true
     */
    fun isLearningWord(): Boolean {
        return type == Candidate.Type.LEARNING
    }

    override fun toString(): String {
        return "$reading $surface"
    }
}

/**
 * 候補確定時の単語学習・N-gram遷移学習ロジックを担当するヘルパークラスです。
 */
class CandidateLearner(private val dictionary: Dictionary) {

    fun learnSegments(
        segments: List<Segment>,
        lastCommittedCandidate: String?
    ): String? {
        var prevKey = if (lastCommittedCandidate.isNullOrBlank() || isPunctuation(lastCommittedCandidate)) "__BOS__" else lastCommittedCandidate
        var prevReading: String? = null
        var prevPosId: Int? = null
        var lastSurf: String? = null

        for (seg in segments) {
            val selected = seg.getSelectedCandidate()
            val raw = selected?.rawCandidate
            if (selected != null && !selected.isSuggestion() && !selected.isPrediction() && !selected.isZenz() && raw != null) {
                dictionary.addEntry(seg.reading, raw, selected.getPosId(), selected.getCost())
            }

            val surf = seg.getSelectedSurface()
            if (surf.isNotEmpty()) {
                val reading = selected?.entry?.reading ?: seg.reading
                val posId = selected?.getPosId()
                val cost = selected?.getCost()

                dictionary.learnTransition(prevKey, surf, prevReading, prevPosId, reading, posId, cost)

                prevKey = surf
                prevReading = reading
                prevPosId = posId
                lastSurf = surf
            }
        }
        return lastSurf
    }

    fun learnCandidateEntries(
        c: Candidate,
        defaultKey: String,
        defaultRaw: String,
        lastCommittedCandidate: String?
    ): String {
        val pathNode = c.viterbiPathNode
        if (pathNode != null) {
            var curr: ViterbiDecoder.PathNode? = pathNode
            val words = ArrayList<ViterbiDecoder.PathNode>()
            while (curr != null && curr.prev != null) {
                if (!curr.reading.isNullOrEmpty() && !curr.surface.isNullOrEmpty()) {
                    words.add(0, curr)
                }
                curr = curr.prev
            }
            if (words.isNotEmpty()) {
                var prevSurface = lastCommittedCandidate

                for (i in words.indices) {
                    val w = words[i]
                    dictionary.addEntry(w.reading!!, w.surface!!, w.posId, w.cost)
                    if (i == 0) {
                        val pKey = if (prevSurface.isNullOrBlank() || isPunctuation(prevSurface)) "__BOS__" else prevSurface
                        dictionary.learnTransition(pKey, w.surface, null, null, w.reading, w.posId, w.cost)
                    } else {
                        val prevW = words[i - 1]
                        dictionary.learnTransition(prevW.surface, w.surface, prevW.reading, prevW.posId, w.reading, w.posId, w.cost)
                    }
                    prevSurface = w.surface
                }
                return prevSurface ?: (c.candidate ?: defaultRaw)
            }
        }
        val candStr = c.candidate ?: defaultRaw
        val lastCand = lastCommittedCandidate
        val pKey = if (lastCand.isNullOrBlank() || isPunctuation(lastCand)) "__BOS__" else lastCand
        dictionary.learnTransition(pKey, candStr, null, null, c.entry?.reading, c.getPosId(), c.getCost())
        dictionary.addEntry(defaultKey, defaultRaw, c.getPosId(), c.getCost())
        return candStr
    }

    companion object {
        fun isPunctuation(text: String?): Boolean {
            if (text.isNullOrBlank()) return true
            val trimmed = text.trim()
            val lastChar = trimmed.last()
            return lastChar == '。' || lastChar == '．' || lastChar == '！' || lastChar == '？' || lastChar == '.' || lastChar == '!' || lastChar == '?'
        }
    }
}
