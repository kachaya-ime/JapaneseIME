package io.github.kachaya.ime.engine

import android.content.Context
import android.util.Log
import io.github.kachaya.ime.BuildConfig
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.dictionary.WordEntry
import java.util.Collections

/**
 * ViterbiアルゴリズムとN-best探索を用いて、読み（かな文字列）から最適な文節・文の変換候補を生成するデコーダークラスです。
 */
class ViterbiDecoder constructor(
    private val mDictionary: Dictionary?,
    private val mNBest: Int,
    maxWordLength: Int = DEFAULT_MAX_WORD_LENGTH,
    context: Context? = null
) {
    companion object {
        private val TAG = ViterbiDecoder::class.java.simpleName
        const val DEFAULT_MAX_WORD_LENGTH = 15
    }

    /**
     * Viterbi探索のラティス（格子）上のノードおよびパスの途中の状態を保持するクラスです。
     */
    class PathNode(
        val reading: String?,
        val surface: String?,
        val posId: Int,
        val cost: Int,
        type: Candidate.Type?,
        val prev: PathNode?,
        val totalCost: Int
    ) {
        val type: Candidate.Type = type ?: Candidate.Type.SYSTEM

        fun isLearningWord(): Boolean = type == Candidate.Type.LEARNING
    }

    val maxWordLength: Int = if (maxWordLength > 0) maxWordLength else DEFAULT_MAX_WORD_LENGTH

    private val mContext: Context? = context ?: mDictionary?.getContext()

    private fun println(obj: Any?) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, obj.toString())
        }
    }

    /**
     * 指定された読み文字列に対してViterbiアルゴリズムとN-best探索を実行し、変換候補のリストを生成します。
     *
     * @param key 変換対象の読み（かな文字列）
     * @param boundaryIndices 分割位置（文節境界）のインデックスリスト。
     * @return 生成された変換候補（Candidate）のリスト
     */
    fun decode(key: String?, boundaryIndices: List<Int>?): List<Candidate> {
        if (key.isNullOrEmpty()) {
            return emptyList()
        }

        val n = key.length
        @Suppress("UNCHECKED_CAST")
        val lattice = Array(n + 1) { ArrayList<PathNode>() }

        lattice[0].add(PathNode("", "", 0, 0, Candidate.Type.SYSTEM, null, 0))

        for (i in 0..<n) {
            val currentPaths = lattice[i]
            if (currentPaths.isEmpty()) {
                continue
            }

            val maxEnd = Math.min(n, i + maxWordLength)
            for (j in i + 1..maxEnd) {
                if (boundaryIndices != null) {
                    var crossesBoundary = false
                    for (b in boundaryIndices) {
                        if (i < b && j > b) {
                            crossesBoundary = true
                            break
                        }
                    }
                    if (crossesBoundary) {
                        continue
                    }
                }

                val subKey = key.substring(i, j)
                val entries = getEntries(subKey)
                if (entries.isEmpty()) {
                    continue
                }

                for (entry in entries) {
                    if (entry.surface.isEmpty()) continue

                    val posId = if (entry.posId != 0) entry.posId else (mDictionary?.findSystemDictId(subKey, entry.surface) ?: 0)
                    val isLearning = entry.isLearningWord()
                    val pathType = if (isLearning) Candidate.Type.LEARNING else Candidate.Type.SYSTEM

                    for (prevPath in currentPaths) {
                        val connCost = mDictionary?.getConnectionCost(prevPath.posId, posId) ?: 200
                        val newTotalCost = prevPath.totalCost + connCost + entry.cost

                        val newNode = PathNode(
                            subKey,
                            entry.surface,
                            posId,
                            entry.cost,
                            pathType,
                            prevPath,
                            newTotalCost
                        )

                        lattice[j].add(newNode)
                    }
                }

                if (lattice[j].size > mNBest * 4) {
                    lattice[j].sortWith { p1, p2 ->
                        val l1 = hasLearningWord(p1)
                        val l2 = hasLearningWord(p2)
                        if (l1 != l2) {
                            if (l1) -1 else 1
                        } else {
                            p1.totalCost.compareTo(p2.totalCost)
                        }
                    }
                    val trimmed = ArrayList<PathNode>()
                    val limit = Math.min(mNBest * 2, lattice[j].size)
                    for (k in 0..<limit) {
                        trimmed.add(lattice[j][k])
                    }
                    lattice[j] = trimmed
                }
            }
        }

        var finalPaths = lattice[n]
        if (finalPaths.isEmpty()) {
            return emptyList()
        }

        finalPaths.sortWith { p1, p2 ->
            val l1 = hasLearningWord(p1)
            val l2 = hasLearningWord(p2)
            if (l1 != l2) {
                if (l1) -1 else 1
            } else {
                p1.totalCost.compareTo(p2.totalCost)
            }
        }

        printDebugLog(finalPaths)

        val results = ArrayList<Candidate>()
        val seenSurfaces = HashMap<String, Boolean>()

        var count = 0
        for (path in finalPaths) {
            if (count >= mNBest) break

            val surfaceBuilder = StringBuilder()
            var learningDictInvolved = false
            var curr: PathNode? = path
            val words = ArrayList<PathNode>()
            while (curr != null && curr.prev != null) {
                words.add(0, curr)
                if (curr.isLearningWord()) learningDictInvolved = true
                curr = curr.prev
            }

            for (w in words) {
                surfaceBuilder.append(w.surface)
            }

            val fullSurface = surfaceBuilder.toString()
            if (fullSurface.isNotEmpty() && seenSurfaces.put(fullSurface, true) == null) {
                val fullEntry = WordEntry(key, fullSurface, path.posId, path.totalCost)
                val candidate = Candidate(fullSurface, fullSurface, "Zenz AI")
                    .setType(if (learningDictInvolved) Candidate.Type.LEARNING else Candidate.Type.SYSTEM)
                    .setEntry(fullEntry)
                    .setViterbiPathNode(path)

                results.add(candidate)
            }
            count++
        }

        return results
    }

    private fun printDebugLog(finalPaths: List<PathNode>?) {
        if (!BuildConfig.DEBUG) {
            return
        }
        if (finalPaths.isNullOrEmpty()) {
            return
        }

        val limit = Math.min(3, finalPaths.size)
        for (i in 0..<limit) {
            val path = finalPaths[i]
            var curr: PathNode? = path
            val words = ArrayList<PathNode>()
            while (curr != null && curr.prev != null) {
                words.add(0, curr)
                curr = curr.prev
            }

            println("BOS")
            var prevPosId = 0
            for (w in words) {
                val posStr = mDictionary?.getPosString(w.posId) ?: w.posId.toString()
                println("${w.reading}, ${w.surface}, $posStr, ${w.cost}")
                val connCost = mDictionary?.getConnectionCost(prevPosId, w.posId) ?: 200
                println(connCost)
                prevPosId = w.posId
            }
            println("EOS")
        }
    }

    private fun hasLearningWord(path: PathNode?): Boolean {
        var curr = path
        while (curr != null && curr.prev != null) {
            if (curr.isLearningWord()) {
                return true
            }
            curr = curr.prev
        }
        return false
    }

    private fun getEntries(subKey: String): List<WordEntry> {
        val list = ArrayList<WordEntry>()
        if (mDictionary != null) {
            val learningEntries = mDictionary.getLearningDictEntries(subKey)
            val systemEntries = ArrayList(mDictionary.getSystemDictWordEntries(subKey))

            systemEntries.sortWith(Comparator.comparingInt { e: WordEntry -> e.cost })

            val learningKeys = HashSet<String>()

            for (le in learningEntries) {
                learningKeys.add("${le.surface}_${le.posId}")
                learningKeys.add(le.surface)
                list.add(le)
            }

            for (se in systemEntries) {
                val keyWithPos = "${se.surface}_${se.posId}"
                if (!learningKeys.contains(keyWithPos) && !learningKeys.contains(se.surface)) {
                    list.add(se)
                }
            }
        }
        return list
    }
}
