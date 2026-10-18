package io.github.kachaya.ime.dictionary

/**
 * 辞書内の単語エントリ（読み、表層形、品詞ID、コスト、学習フラグ等）を保持するデータクラスです。
 */
class WordEntry(
    @JvmField val reading: String,
    @JvmField val surface: String,
    @JvmField val posId: Int,
    @JvmField val cost: Int
) {
    @JvmField
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
