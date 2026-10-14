package io.github.kachaya.ime.dictionary;

import android.content.Context;
import android.util.Log;

import io.github.kachaya.ime.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * システム辞書、学習辞書、および予測辞書を統合管理するファサード（Facade）クラスです。
 */
public class Dictionary {



    private final SystemDictionary mSystemDictionary;
    private final LearningDictionary mLearningDictionary;
    private final PredictionDictionary mPredictionDictionary;

    public Dictionary(Context context) {
        mSystemDictionary = new SystemDictionary(context);
        mLearningDictionary = new LearningDictionary(context);
        mPredictionDictionary = new PredictionDictionary(context);
    }

    public Context getContext() {
        return mSystemDictionary != null ? mSystemDictionary.getContext() : null;
    }

    public SystemDictionary getSystemDictionary() {
        return mSystemDictionary;
    }

    public LearningDictionary getLearningDictionary() {
        return mLearningDictionary;
    }

    public PredictionDictionary getPredictionDictionary() {
        return mPredictionDictionary;
    }

    private void logI(String msg) {
        if (BuildConfig.DEBUG) {
            Log.i(getClass().getSimpleName(), msg);
        }
    }



    /**
     * システム辞書および学習辞書から漢字変換候補を検索・統合します。
     *
     * @param key 見出し語（読み）
     * @return 候補リスト
     */
    public List<Candidate> findCandidates(String key) {
        List<String> list1 = new ArrayList<>();
        Set<String> set1 = new HashSet<>();

        // 1. システム辞書の検索
        List<String> cands1 = mSystemDictionary.getSystemDictCandidates(key);
        if ((cands1 == null || cands1.isEmpty()) && !key.equals(key.toLowerCase(Locale.ENGLISH))) {
            cands1 = mSystemDictionary.getSystemDictCandidates(key.toLowerCase(Locale.ENGLISH));
        }
        if (cands1 != null) {
            for (String c : cands1) {
                if (!c.isEmpty() && set1.add(c)) {
                    list1.add(c);
                }
            }
        }

        // 2. 学習辞書の検索
        LearningDictionary.Entry entry = mLearningDictionary.getLearningDictEntry(key);
        if (entry == null && !key.equals(key.toLowerCase(Locale.ENGLISH))) {
            entry = mLearningDictionary.getLearningDictEntry(key.toLowerCase(Locale.ENGLISH));
        }
        List<String> list2 = null;
        if (entry != null && entry.entries != null) {
            list2 = new ArrayList<>();
            for (LearningDictionary.LearningDictEntry le : entry.entries) {
                list2.add(le.surface);
            }
        }
        Set<String> learningCandsSet = new HashSet<>();
        if (list2 != null) {
            int idx = 0;
            for (String s : list2) {
                learningCandsSet.add(s);
                list1.remove(s);
                list1.add(idx++, s);
            }
        }

        if (list1.isEmpty()) {
            return Collections.emptyList();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (String rawCand : list1) {
            String template = rawCand != null ? rawCand : "";
            boolean isLearning = learningCandsSet.contains(rawCand);
            Candidate c = new Candidate(rawCand, template, "Zenz AI");
            if (isLearning) {
                c.setType(Candidate.Type.LEARNING);
            }
            candidates.add(c);
        }
        return candidates;
    }

    /**
     * 動的補完のために、指定された文字列で始まる見出し語を検索します。
     *
     * @param key 入力途中のプレフィックス
     * @return 前方一致する見出し語のリスト
     */
    public List<String> findSuggestions(String key) {
        List<String> list = new ArrayList<>(mSystemDictionary.findKeys(key, 5));
        if (!key.equals(key.toLowerCase(Locale.ENGLISH))) {
            for (String k : mSystemDictionary.findKeys(key.toLowerCase(Locale.ENGLISH), 5)) {
                if (!list.contains(k)) {
                    list.add(k);
                }
            }
        }

        List<String> learningKeys = mLearningDictionary.findKeys(key, 5);
        int idx = 0;
        for (String s : learningKeys) {
            list.remove(s);
            list.add(idx++, s);
        }
        return list;
    }

    /**
     * 直前の確定単語に基づく予測候補（2単語接続学習）を取得します。
     *
     * @param prevWord 前回の確定単語
     * @param prefix   入力中のプレフィックス（任意のフィルター）
     * @param maxCount 最大取得件数
     * @return 予測候補のリスト
     */
    public List<Candidate> predictNextCandidates(String prevWord, String prefix, int maxCount) {
        return mPredictionDictionary.predictNextCandidates(prevWord, prefix, maxCount);
    }

    public List<String> getPredictedSurfaceWords(String prevWord, int maxCount) {
        return mPredictionDictionary.getPredictedSurfaceWords(prevWord, maxCount);
    }

    /**
     * 直前単語と今回単語の確定接続関係（Bigram）を学習します。
     *
     * @param prevWord 前回の確定単語
     * @param nextWord 今回の確定単語
     */
    public void learnTransition(String prevWord, String nextWord) {
        mPredictionDictionary.learnTransition(prevWord, nextWord);
    }

    public String getLearningDictionaryName() {
        return mLearningDictionary.getLearningDictionaryName();
    }

    public void clearLearningDictionary() {
        mLearningDictionary.clearLearningDictionary();
        mPredictionDictionary.clearPredictionData();
    }

    public void reloadLearningDictionary() {
        mLearningDictionary.reloadLearningDictionary();
    }

    public List<String> getSystemDictCandidates(String key) {
        return mSystemDictionary.getSystemDictCandidates(key);
    }

    public List<SystemDictionary.SystemDictEntry> getSystemDictEntries(String key) {
        return mSystemDictionary.getSystemDictEntries(key);
    }

    public List<WordEntry> getSystemDictWordEntries(String key) {
        return mSystemDictionary.getSystemDictWordEntries(key);
    }

    public List<WordEntry> getLearningDictEntries(String key) {
        return mLearningDictionary.getLearningDictEntries(key);
    }

    public int findSystemDictId(String key, String surface) {
        return mSystemDictionary.findSystemDictId(key, surface);
    }

    public int getConnectionCost(int prevPosId, int posId) {
        return mSystemDictionary.getConnectionCost(prevPosId, posId);
    }

    public List<String> exportLearningDictionary() {
        return mLearningDictionary.exportLearningDictionary();
    }

    public List<LearningDictionary.Record> getAllLearningRecords() {
        return mLearningDictionary.getAllRecords();
    }

    public String getPosString(int posId) {
        return mSystemDictionary != null ? mSystemDictionary.getPosString(posId) : String.valueOf(posId);
    }

    public void importLearningDictionary(List<String> entries) {
        mLearningDictionary.importLearningDictionary(entries);
    }

    public void addEntry(String key, String val, int posId, int cost) {
        int minCost = cost > 0 ? cost : 500;

        List<SystemDictionary.SystemDictEntry> sysEntries = mSystemDictionary != null ? mSystemDictionary.getSystemDictEntries(key) : Collections.emptyList();
        LearningDictionary.Entry learningEntry = mLearningDictionary != null ? mLearningDictionary.getLearningDictEntry(key) : null;

        boolean foundMatchPos = false;

        for (SystemDictionary.SystemDictEntry se : sysEntries) {
            if (se.posId == posId || posId == 0) {
                if (!foundMatchPos || se.cost < minCost) {
                    minCost = se.cost;
                    foundMatchPos = true;
                }
            }
        }

        if (learningEntry != null && learningEntry.entries != null) {
            for (LearningDictionary.LearningDictEntry le : learningEntry.entries) {
                if (!le.surface.equals(val)) {
                    if (le.posId == posId || posId == 0) {
                        if (!foundMatchPos || le.cost < minCost) {
                            minCost = le.cost;
                            foundMatchPos = true;
                        }
                    }
                }
            }
        }

        if (!foundMatchPos) {
            for (SystemDictionary.SystemDictEntry se : sysEntries) {
                if (se.cost < minCost) {
                    minCost = se.cost;
                }
            }
            if (learningEntry != null && learningEntry.entries != null) {
                for (LearningDictionary.LearningDictEntry le : learningEntry.entries) {
                    if (!le.surface.equals(val) && le.cost < minCost) {
                        minCost = le.cost;
                    }
                }
            }
        }

        int learnedCost = Math.max(0, minCost - 1);
        mLearningDictionary.addEntry(key, val, posId, learnedCost);
    }

    public void removeEntry(String key, String val) {
        mLearningDictionary.removeEntry(key, val);
    }

    public void deleteEntry(String key) {
        mLearningDictionary.deleteEntry(key);
    }

    public void commitChanges() {
    }

    public void close() {
        if (mSystemDictionary != null) {
            mSystemDictionary.close();
        }
        if (mLearningDictionary != null) {
            mLearningDictionary.close();
        }
        if (mPredictionDictionary != null) {
            mPredictionDictionary.close();
        }
    }

    public static class Entry extends LearningDictionary.Entry {
        public Entry(List<LearningDictionary.LearningDictEntry> entries) {
            super(entries);
        }
    }

    public static class SystemDictEntry extends SystemDictionary.SystemDictEntry {
        public SystemDictEntry(String surface, int posId, int cost) {
            super(surface, posId, cost);
        }
    }
}
