package io.github.kachaya.ime.engine

import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate

/**
 * 複数文節変換における文節リスト、注目文節のフォーカス位置、および文節の伸縮処理を管理するクラスです。
 */
class SegmentManager {

    /** 文節候補の取得を行うコールバック・インターフェースです。 */
    fun interface CandidateFetcher {
        fun fetchCandidates(reading: String, preferredSurface: String?): List<Candidate>?
    }

    var segments: MutableList<Segment>? = null

    var focusedSegmentIndex: Int = 0

    /**
     * 分節データが存在するかどうかを判定します。
     *
     * @return 1 つ以上の分節が存在する場合は true
     */
    fun hasSegments(): Boolean {
        val segs = segments
        return !segs.isNullOrEmpty()
    }

    /**
     * 現在フォーカス中の Segment オブジェクトを取得します。
     *
     * @return フォーカス中の Segment。存在しない場合は null
     */
    fun getActiveSegment(): Segment? {
        val segs = segments
        if (!segs.isNullOrEmpty() && focusedSegmentIndex in segs.indices) {
            return segs[focusedSegmentIndex]
        }
        return null
    }

    /**
     * 次の文節へフォーカスを移動します。
     *
     * @return 移動が成功した場合は true
     */
    fun focusNextSegment(): Boolean {
        val segs = segments
        if (segs != null && focusedSegmentIndex < segs.size - 1) {
            focusedSegmentIndex++
            return true
        }
        return false
    }

    /**
     * 前の文節へフォーカスを移動します。
     *
     * @return 移動が成功した場合は true
     */
    fun focusPrevSegment(): Boolean {
        val segs = segments
        if (segs != null && focusedSegmentIndex > 0) {
            focusedSegmentIndex--
            return true
        }
        return false
    }

    /**
     * 全文節の選択候補文字列を連結した文字列を取得します。
     *
     * @return 連結された全文節の変換文字列
     */
    fun getMultiSegmentText(): String? {
        val segs = segments ?: return null
        if (segs.isEmpty()) return null

        val active = getActiveSegment()
        val activeCand = active?.getSelectedCandidate()
        if (activeCand != null && activeCand.isZenz()) {
            return activeCand.candidate
        }
        val sb = StringBuilder()
        for (seg in segs) {
            sb.append(seg.getSelectedSurface())
        }
        return sb.toString()
    }

    /**
     * 現在フォーカス中の文節の読長さを変更（拡大・縮小）し、残りの読文字列を Viterbi で再分割します。
     *
     * @param delta +1 で拡大、-1 で縮小
     * @param fullQuery 全検索クエリ文字列
     * @param fetcher 候補取得用フェッチャー
     * @param viterbiDecoder 再分割用の Viterbi デコーダー
     * @return 変更が行われた場合は true
     */
    fun resizeFocusedSegment(
        delta: Int,
        fullQuery: String?,
        fetcher: CandidateFetcher,
        viterbiDecoder: ViterbiDecoder?
    ): Boolean {
        val segs = segments
        if (segs.isNullOrEmpty()) {
            return false
        }
        if (focusedSegmentIndex !in segs.indices) {
            return false
        }

        var startOffset = 0
        for (i in 0..<focusedSegmentIndex) {
            startOffset += segs[i].reading.length
        }

        if (fullQuery == null || startOffset >= fullQuery.length) {
            return false
        }

        val remainingQuery = fullQuery.substring(startOffset)
        val currentReading = segs[focusedSegmentIndex].reading

        val newLen = currentReading.length + delta
        if (newLen < 1 || newLen > remainingQuery.length) {
            return false
        }

        val newFocusedReading = remainingQuery.substring(0, newLen)
        val restQuery = remainingQuery.substring(newLen)

        val newFocusedCands = fetcher.fetchCandidates(newFocusedReading, null)
        val newFocusedSeg = Segment(newFocusedReading, newFocusedCands, 0)

        val restSegments = ArrayList<Segment>()
        if (restQuery.isNotEmpty()) {
            var restViterbi: List<Candidate>? = null
            if (viterbiDecoder != null) {
                try {
                    restViterbi = viterbiDecoder.decode(restQuery, null)
                } catch (e: Exception) {
                    Log.e("SegmentManager", "Viterbi decode error on resize restQuery", e)
                }
            }

            if (!restViterbi.isNullOrEmpty() && restViterbi[0].viterbiPathNode != null) {
                var curr: ViterbiDecoder.PathNode? = restViterbi[0].viterbiPathNode
                val nodes = ArrayList<ViterbiDecoder.PathNode>()
                while (curr != null && curr.prev != null) {
                    if (!curr.reading.isNullOrEmpty()) {
                        nodes.add(0, curr)
                    }
                    curr = curr.prev
                }

                val sb = StringBuilder()
                for (n in nodes) {
                    sb.append(n.reading)
                }

                if (sb.toString() == restQuery && nodes.isNotEmpty()) {
                    for (n in nodes) {
                        val reading = n.reading ?: ""
                        val cands = fetcher.fetchCandidates(reading, n.surface)
                        restSegments.add(Segment(reading, cands, 0))
                    }
                }
            }

            if (restSegments.isEmpty()) {
                val cands = fetcher.fetchCandidates(restQuery, null)
                restSegments.add(Segment(restQuery, cands, 0))
            }
        }

        val updatedSegments = ArrayList<Segment>()
        for (i in 0..<focusedSegmentIndex) {
            updatedSegments.add(segs[i])
        }
        updatedSegments.add(newFocusedSeg)
        updatedSegments.addAll(restSegments)

        segments = updatedSegments
        return true
    }

    /**
     * 文節状態をクリアします。
     */
    fun clear() {
        segments = null
        focusedSegmentIndex = 0
    }
}

/**
 * 分節変換における 1 つの文節（読み、変換候補リスト、現在選択中の候補インデックス）を表すクラスです。
 */
class Segment(
    var reading: String,
    candidates: List<Candidate>?,
    var selectedIndex: Int
) {
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
