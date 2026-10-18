package io.github.kachaya.ime.engine

import io.github.kachaya.ime.dictionary.Candidate

/**
 * 分節変換における 1 つの文節（読み、変換候補リスト、現在選択中の候補インデックス）を表すクラスです。
 */
class Segment(
    @JvmField var reading: String,
    candidates: List<Candidate>?,
    @JvmField var selectedIndex: Int
) {
    @JvmField
    var candidates: List<Candidate> = candidates ?: emptyList()

    /**
     * 現在選択されている候補オブジェクトを取得します。
     *
     * @return 選択中の Candidate。無効なインデックスの場合は null
     */
    fun getSelectedCandidate(): Candidate? {
        if (selectedIndex in candidates.indices) {
            return candidates[selectedIndex]
        }
        return null
    }

    /**
     * 現在選択されている候補の表記文字列を取得します。
     *
     * @return 選択中の候補文字列。未選択の場合は読み文字列
     */
    fun getSelectedSurface(): String {
        val c = getSelectedCandidate()
        val candidateStr = c?.candidate
        if (candidateStr != null) {
            return candidateStr
        }
        return reading
    }
}
