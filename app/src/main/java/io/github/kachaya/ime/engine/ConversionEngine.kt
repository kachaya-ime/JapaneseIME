package io.github.kachaya.ime.engine

import io.github.kachaya.ime.dictionary.Candidate

/**
 * かな・読み文字列から変換候補を生成する変換エンジンの統一インターフェースです。
 * Viterbi 統計デコーダー、Zenz LLM 推論エンジン、あるいは将来の新しい変換エンジン群を
 * プラグイン的・一元的に扱うために抽象化されています。
 */
interface ConversionEngine {
    val name: String
    val isEnabled: Boolean

    /**
     * 同期的に変換候補を検索・生成します（Viterbi / 辞書デコーダーなど）。
     */
    fun convertSync(query: String, preferredSurface: String? = null): List<Candidate>

    /**
     * 非同期ストリーミング/バックグラウンドで変換候補を生成します（Zenz LLM など）。
     */
    fun convertAsync(
        query: String,
        leftContext: String?,
        callback: AsyncCallback
    )

    fun cancelPending()

    fun release() {}

    fun interface AsyncCallback {
        fun onCandidateAvailable(candidate: Candidate)
    }
}
