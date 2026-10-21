package io.github.kachaya.ime.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.viterbi.ViterbiDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 非同期でのサジェスト（入力補完）・予測候補検索とマージを担当するプロバイダークラスです。
 */
class SuggestionProvider(
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    fun interface SuggestionCallback {
        fun onSuggestionsAvailable(requestId: Long, query: String, candidates: List<Candidate>)
    }

    private val executorQueue = LinkedBlockingQueue<Runnable>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, executorQueue
    )
    val suggestionRequestId = AtomicLong(0)

    fun cancelPending() {
        suggestionRequestId.incrementAndGet()
        executorQueue.clear()
    }

    fun requestSuggestions(
        query: String,
        viterbiDecoder: ViterbiDecoder?,
        dictionary: Dictionary?,
        lastCommittedCandidate: String?,
        callback: SuggestionCallback
    ): Long {
        val requestId = suggestionRequestId.incrementAndGet()
        executorQueue.clear()

        if (query.isEmpty()) return requestId

        executor.execute {
            if (requestId != suggestionRequestId.get()) return@execute

            val rawCandidates = ArrayList<Candidate>()

            if (viterbiDecoder != null) {
                try {
                    val viterbiCands = viterbiDecoder.decode(query, null)
                    if (viterbiCands != null) {
                        rawCandidates.addAll(viterbiCands)
                    }
                } catch (e: Exception) {
                    Log.e("SuggestionProvider", "Viterbi decode error", e)
                }
            }

            if (requestId != suggestionRequestId.get()) return@execute

            if (dictionary != null) {
                val dictCands = dictionary.findCandidates(query)
                if (dictCands != null) {
                    rawCandidates.addAll(dictCands)
                }

                val suggestionKeys = dictionary.findSuggestions(query)
                if (suggestionKeys != null) {
                    for (key in suggestionKeys) {
                        if (requestId != suggestionRequestId.get()) return@execute
                        if (!key.isNullOrEmpty() && key != query) {
                            val keyCand = Candidate(key, key, "Zenz AI").setType(Candidate.Type.SUGGESTION)
                            rawCandidates.add(keyCand)

                            val keyCands = dictionary.findCandidates(key)
                            if (keyCands != null) {
                                for (c in keyCands) {
                                    val sugCand = Candidate(c.rawCandidate, c.candidate, "Zenz AI")
                                        .setType(Candidate.Type.SUGGESTION)
                                    sugCand.setEntry(c.entry)
                                    rawCandidates.add(sugCand)
                                }
                            }
                        }
                    }
                }
            }

            if (requestId != suggestionRequestId.get()) return@execute

            val predictedSurfaces = HashSet<String>()
            val lastCand = lastCommittedCandidate
            if (dictionary != null && lastCand != null) {
                val predicted = dictionary.getPredictedSurfaceWords(lastCand, 50)
                if (predicted != null) {
                    predictedSurfaces.addAll(predicted)
                }
            }

            if (requestId != suggestionRequestId.get()) return@execute

            val seen = HashSet<String>()
            val predictionList = ArrayList<Candidate>()
            val otherList = ArrayList<Candidate>()
            val suggestionListCands = ArrayList<Candidate>()

            for (c in rawCandidates) {
                val candStr = c.candidate
                if (!candStr.isNullOrEmpty()) {
                    val key = c.candidate ?: ""
                    if (seen.add(key)) {
                        if (predictedSurfaces.contains(candStr)) {
                            if (c.type == Candidate.Type.SYSTEM) {
                                c.type = Candidate.Type.PREDICTION
                            }
                            predictionList.add(c)
                        } else if (c.isSuggestion()) {
                            suggestionListCands.add(c)
                        } else {
                            otherList.add(c)
                        }
                    }
                }
            }

            val merged = ArrayList<Candidate>()
            merged.addAll(predictionList)
            merged.addAll(otherList)
            merged.addAll(suggestionListCands)

            if (requestId != suggestionRequestId.get()) return@execute

            mainHandler.post {
                if (requestId == suggestionRequestId.get()) {
                    callback.onSuggestionsAvailable(requestId, query, merged)
                }
            }
        }

        return requestId
    }
}
