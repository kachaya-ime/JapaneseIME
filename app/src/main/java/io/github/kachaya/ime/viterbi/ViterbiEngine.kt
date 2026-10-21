package io.github.kachaya.ime.viterbi

import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.engine.ConversionEngine
import io.github.kachaya.ime.engine.RomajiConverter

/**
 * Viterbi アルゴリズムと辞書検索をカプセル化した ConversionEngine の実装クラスです。
 */
class ViterbiEngine(
    private val dictionary: Dictionary?,
    var nbest: Int = 10,
    override var isEnabled: Boolean = true
) : ConversionEngine {

    override val name: String = "Viterbi"

    var viterbiDecoder: ViterbiDecoder? = ViterbiDecoder(dictionary, nbest)
        private set

    fun updateNBest(newNBest: Int) {
        if (nbest != newNBest || viterbiDecoder == null) {
            nbest = newNBest
            viterbiDecoder = ViterbiDecoder(dictionary, nbest)
        }
    }

    fun decodeViterbi(query: String): List<Candidate> {
        val decoder = viterbiDecoder ?: return emptyList()
        return try {
            decoder.decode(query, null)
        } catch (e: Exception) {
            Log.e("ViterbiEngine", "Viterbi decode error for: $query", e)
            emptyList()
        }
    }

    override fun convertSync(query: String, preferredSurface: String?): List<Candidate> {
        if (query.isEmpty()) return emptyList()

        if (!isEnabled) {
            val surface = preferredSurface ?: query
            val plainCand = Candidate(surface, surface, name)
            val wideK = RomajiConverter.toWideKatakana(query)
            val halfK = RomajiConverter.toHalfKatakana(query)

            val cands = mutableListOf(plainCand)
            if (!wideK.isNullOrEmpty() && wideK != query) cands.add(Candidate(wideK, wideK, name))
            if (!halfK.isNullOrEmpty() && halfK != query) cands.add(Candidate(halfK, halfK, name))
            return cands
        }

        val rawCands = ArrayList<Candidate>()

        val viterbiCands = decodeViterbi(query)
        rawCands.addAll(viterbiCands)

        if (dictionary != null) {
            val dictCands = dictionary.findCandidates(query)
            rawCands.addAll(dictCands)
        }

        val seen = HashSet<String>()
        val convertedCands = ArrayList<Candidate>()
        val plainCands = ArrayList<Candidate>()

        if (!preferredSurface.isNullOrEmpty()) {
            seen.add(preferredSurface)
            var prefCand = rawCands.find { it.candidate == preferredSurface }
            if (prefCand == null) {
                prefCand = Candidate(preferredSurface, preferredSurface, name)
            }
            if (isPlainReadingCandidate(query, preferredSurface)) {
                plainCands.add(prefCand)
            } else {
                convertedCands.add(prefCand)
            }
        }

        for (c in rawCands) {
            val candStr = c.candidate
            if (!candStr.isNullOrEmpty()) {
                if (seen.add(candStr)) {
                    if (isPlainReadingCandidate(query, candStr)) {
                        plainCands.add(c)
                    } else {
                        convertedCands.add(c)
                    }
                }
            }
        }

        val merged = ArrayList<Candidate>()
        merged.addAll(convertedCands)
        merged.addAll(plainCands)

        Log.d("ViterbiEngine", "[Viterbi Segment Candidates Generated] segmentReading=\"$query\", count=${merged.size}, candidates=${merged.map { it.candidate }}")

        return merged
    }

    override fun convertAsync(query: String, leftContext: String?, callback: ConversionEngine.AsyncCallback) {
        val cands = convertSync(query, null)
        for (c in cands) {
            callback.onCandidateAvailable(c)
        }
    }

    override fun cancelPending() {}

    private fun isPlainReadingCandidate(query: String, candStr: String): Boolean {
        if (candStr == query) return true
        val wideK = RomajiConverter.toWideKatakana(query)
        if (candStr == wideK) return true
        val halfK = RomajiConverter.toHalfKatakana(query)
        return candStr == halfK
    }
}
