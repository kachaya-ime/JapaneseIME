package io.github.kachaya.ime.dictionary

import android.content.Context
import android.util.Log
import io.github.kachaya.ime.BuildConfig
import java.util.Locale

/**
 * システム辞書、学習辞書、および予測辞書を統合管理するファサード（Facade）クラスです。
 */
class Dictionary(context: Context?) {

    val systemDictionary: SystemDictionary = SystemDictionary(context)
    val learningDictionary: LearningDictionary = LearningDictionary(context)
    val predictionDictionary: PredictionDictionary = PredictionDictionary(context)

    fun getContext(): Context? = systemDictionary.context

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(javaClass.simpleName, msg)
        }
    }

    /**
     * システム辞書および学習辞書から漢字変換候補を検索・統合します。
     *
     * @param key 見出し語（読み）
     * @return 候補リスト
     */
    fun findCandidates(key: String): List<Candidate> {
        var sysWordEntries = systemDictionary.getSystemDictWordEntries(key)
        if (sysWordEntries.isEmpty() && key != key.lowercase(Locale.ENGLISH)) {
            sysWordEntries = systemDictionary.getSystemDictWordEntries(key.lowercase(Locale.ENGLISH))
        }

        var learningEntry = learningDictionary.getLearningDictEntry(key)
        if (learningEntry == null && key != key.lowercase(Locale.ENGLISH)) {
            learningEntry = learningDictionary.getLearningDictEntry(key.lowercase(Locale.ENGLISH))
        }

        val learningWordEntries = ArrayList<WordEntry>()
        if (learningEntry?.entries != null) {
            for (le in learningEntry.entries!!) {
                learningWordEntries.add(WordEntry(key, le.surface, le.posId, le.cost).apply {
                    type = Candidate.Type.LEARNING
                })
            }
        }

        val seenSurfaces = HashSet<String>()
        val candidates = ArrayList<Candidate>()

        // Add learning entries first (preserving priority/order)
        for (we in learningWordEntries) {
            if (seenSurfaces.add(we.surface)) {
                val c = Candidate(we.surface, we.surface, "Zenz AI")
                    .setType(Candidate.Type.LEARNING)
                    .setEntry(we)
                candidates.add(c)
            }
        }

        // Add system entries
        for (we in sysWordEntries) {
            if (seenSurfaces.add(we.surface)) {
                val c = Candidate(we.surface, we.surface, "Zenz AI")
                    .setType(Candidate.Type.SYSTEM)
                    .setEntry(we)
                candidates.add(c)
            }
        }

        return candidates
    }

    /**
     * 動的補完のために、指定された文字列で始まる見出し語を検索します。
     *
     * @param key 入力途中のプレフィックス
     * @return 前方一致する見出し語のリスト
     */
    fun findSuggestions(key: String): List<String> {
        val list = ArrayList(systemDictionary.findKeys(key, 5))
        if (key != key.lowercase(Locale.ENGLISH)) {
            for (k in systemDictionary.findKeys(key.lowercase(Locale.ENGLISH), 5)) {
                if (!list.contains(k)) {
                    list.add(k)
                }
            }
        }

        val learningKeys = learningDictionary.findKeys(key, 5)
        var idx = 0
        for (s in learningKeys) {
            list.remove(s)
            list.add(idx++, s)
        }
        return list
    }

    /**
     * 直前の確定単語に基づく予測候補（2単語接続学習）を取得します。
     */
    fun predictNextCandidates(prevWord: String?, prefix: String?, maxCount: Int): List<Candidate> {
        return predictionDictionary.predictNextCandidates(prevWord, prefix, maxCount)
    }

    fun getPredictedSurfaceWords(prevWord: String?, maxCount: Int): List<String> {
        return predictionDictionary.getPredictedSurfaceWords(prevWord, maxCount)
    }

    /**
     * 直前単語と今回単語の確定接続関係（Bigram）を学習します。
     */
    fun learnTransition(
        prevWord: String?,
        nextWord: String?,
        prevReading: String? = null,
        prevPosId: Int? = null,
        nextReading: String? = null,
        nextPosId: Int? = null,
        nextCost: Int? = null
    ) {
        predictionDictionary.learnTransition(prevWord, nextWord, prevReading, prevPosId, nextReading, nextPosId, nextCost)
    }

    fun getLearningDictionaryName(): String {
        return learningDictionary.getLearningDictionaryName()
    }

    fun clearLearningDictionary() {
        learningDictionary.clearLearningDictionary()
        predictionDictionary.clearPredictionData()
    }

    fun reloadLearningDictionary() {
        learningDictionary.reloadLearningDictionary()
    }

    fun getSystemDictCandidates(key: String?): List<String> {
        return systemDictionary.getSystemDictCandidates(key)
    }

    fun getSystemDictEntries(key: String?): List<SystemDictionary.SystemDictEntry> {
        return systemDictionary.getSystemDictEntries(key)
    }

    fun getSystemDictWordEntries(key: String?): List<WordEntry> {
        return systemDictionary.getSystemDictWordEntries(key)
    }

    fun getLearningDictEntries(key: String?): List<WordEntry> {
        return learningDictionary.getLearningDictEntries(key)
    }

    fun findSystemDictId(key: String?, surface: String?): Int {
        return systemDictionary.findSystemDictId(key, surface)
    }

    fun getConnectionCost(prevPosId: Int, posId: Int): Int {
        return systemDictionary.getConnectionCost(prevPosId, posId)
    }

    fun exportLearningDictionary(): List<String> {
        return learningDictionary.exportLearningDictionary()
    }

    fun getAllLearningRecords(): List<LearningDictionary.Record> {
        return learningDictionary.getAllRecords()
    }

    fun getPosString(posId: Int): String {
        return systemDictionary.getPosString(posId)
    }

    fun importLearningDictionary(entries: List<String>) {
        learningDictionary.importLearningDictionary(entries)
    }

    fun addEntry(key: String, valStr: String, posId: Int, cost: Int) {
        var minCost = if (cost > 0) cost else 500

        val sysEntries = systemDictionary.getSystemDictEntries(key)
        val learningEntry = learningDictionary.getLearningDictEntry(key)

        var foundMatchPos = false

        for (se in sysEntries) {
            if (se.posId == posId || posId == 0) {
                if (!foundMatchPos || se.cost < minCost) {
                    minCost = se.cost
                    foundMatchPos = true
                }
            }
        }

        if (learningEntry?.entries != null) {
            for (le in learningEntry.entries!!) {
                if (le.surface != valStr) {
                    if (le.posId == posId || posId == 0) {
                        if (!foundMatchPos || le.cost < minCost) {
                            minCost = le.cost
                            foundMatchPos = true
                        }
                    }
                }
            }
        }

        if (!foundMatchPos) {
            for (se in sysEntries) {
                if (se.cost < minCost) {
                    minCost = se.cost
                }
            }
            if (learningEntry?.entries != null) {
                for (le in learningEntry.entries!!) {
                    if (le.surface != valStr && le.cost < minCost) {
                        minCost = le.cost
                    }
                }
            }
        }

        val learnedCost = Math.max(0, minCost - 1)
        learningDictionary.addEntry(key, valStr, posId, learnedCost)
    }

    fun removeEntry(key: String, valStr: String) {
        learningDictionary.removeEntry(key, valStr)
    }

    fun deleteEntry(key: String) {
        learningDictionary.deleteEntry(key)
    }

    fun commitChanges() {
    }

    fun close() {
        systemDictionary.close()
        learningDictionary.close()
        predictionDictionary.close()
    }

    open class Entry(entries: MutableList<LearningDictionary.LearningDictEntry>?) : LearningDictionary.Entry(entries)

    open class SystemDictEntry(surface: String, posId: Int, cost: Int) : SystemDictionary.SystemDictEntry(surface, posId, cost)
}
