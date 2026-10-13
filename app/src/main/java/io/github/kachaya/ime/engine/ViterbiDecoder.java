package io.github.kachaya.ime.engine;

import android.content.Context;
import android.util.Log;

import io.github.kachaya.ime.dictionary.Candidate;
import io.github.kachaya.ime.dictionary.Dictionary;
import io.github.kachaya.ime.dictionary.WordEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ViterbiアルゴリズムとN-best探索を用いて、読み（かな文字列）から最適な文節・文の変換候補を生成するデコーダークラスです。
 * 辞書引き結果と品詞間接続コスト行列を基に、ダイナミックプログラミング（Lattice/格子探索）により最小コストのパスを探索します。
 */
public class ViterbiDecoder {

    private static final String TAG = ViterbiDecoder.class.getSimpleName();
    public static final int DEFAULT_MAX_WORD_LENGTH = 15;

    /**
     * Viterbi探索のラティス（格子）上のノードおよびパスの途中の状態を保持するクラスです。
     */
    public static class PathNode {
        public final String reading;
        public final String surface;
        public final int posId;
        public final int cost;
        public final Candidate.Type type;
        public final PathNode prev;
        public final int totalCost;

        public PathNode(String reading, String surface, int posId, int cost, Candidate.Type type, PathNode prev, int totalCost) {
            this.reading = reading;
            this.surface = surface;
            this.posId = posId;
            this.cost = cost;
            this.type = type != null ? type : Candidate.Type.SYSTEM;
            this.prev = prev;
            this.totalCost = totalCost;
        }

        public boolean isLearningWord() {
            return type == Candidate.Type.LEARNING;
        }
    }

    private final Dictionary mDictionary;
    private final int mNBest;
    private final int mMaxWordLength;
    private final Context mContext;

    private void println(Object obj) {
        if (io.github.kachaya.ime.BuildConfig.DEBUG) {
            Log.i(TAG, String.valueOf(obj));
        }
    }

    /**
     * ViterbiDecoderを構築します。
     *
     * @param dictionary 辞書管理インスタンス
     * @param nBest 生成する候補の最大数（N-best）
     */
    public ViterbiDecoder(Dictionary dictionary, int nBest) {
        this(dictionary, nBest, DEFAULT_MAX_WORD_LENGTH, dictionary != null ? dictionary.getContext() : null);
    }

    /**
     * ViterbiDecoderを構築します。
     *
     * @param dictionary 辞書管理インスタンス
     * @param nBest 生成する候補の最大数（N-best）
     * @param maxWordLength 単語の最大長
     */
    public ViterbiDecoder(Dictionary dictionary, int nBest, int maxWordLength) {
        this(dictionary, nBest, maxWordLength, dictionary != null ? dictionary.getContext() : null);
    }

    /**
     * ViterbiDecoderを構築します。
     *
     * @param dictionary 辞書管理インスタンス
     * @param nBest 生成する候補の最大数（N-best）
     * @param maxWordLength 単語の最大長
     * @param context コンテキスト
     */
    public ViterbiDecoder(Dictionary dictionary, int nBest, int maxWordLength, Context context) {
        mDictionary = dictionary;
        mNBest = nBest;
        mMaxWordLength = maxWordLength > 0 ? maxWordLength : DEFAULT_MAX_WORD_LENGTH;
        mContext = context != null ? context : (dictionary != null ? dictionary.getContext() : null);
    }

    public int getMaxWordLength() {
        return mMaxWordLength;
    }

    /**
     * 指定された読み文字列に対してViterbiアルゴリズムとN-best探索を実行し、変換候補のリストを生成します。
     *
     * @param key 変換対象の読み（かな文字列）
     * @param boundaryIndices 分割位置（文節境界）のインデックスリスト。この境界をまたぐ単語は探索から除外されます。
     * @return 生成された変換候補（Candidate）のリスト
     */
    public List<Candidate> decode(String key, List<Integer> boundaryIndices) {
        if (key == null || key.isEmpty()) {
            return Collections.emptyList();
        }

        int n = key.length();
        // 1. ラティス（格子構造：各文字位置におけるパスのリスト）を初期化する
        @SuppressWarnings("unchecked")
        List<PathNode>[] lattice = new List[n + 1];
        for (int i = 0; i <= n; i++) {
            lattice[i] = new ArrayList<>();
        }

        // 2. 探索の起点となるダミーの開始ノード（位置0）をセットする
        lattice[0].add(new PathNode("", "", 0, 0, Candidate.Type.SYSTEM, null, 0));

        // 3. 読みの先頭から順に各位置（開始インデックス i）を進める
        for (int i = 0; i < n; i++) {
            List<PathNode> currentPaths = lattice[i];
            if (currentPaths.isEmpty()) {
                continue;
            }

            // 4. 単語の終端位置（j）を最大単語長まで走査して部分文字列（subKey）を取り出す
            int maxEnd = Math.min(n, i + mMaxWordLength);
            for (int j = i + 1; j <= maxEnd; j++) {
                if (boundaryIndices != null) {
                    boolean crossesBoundary = false;
                    for (int b : boundaryIndices) {
                        if (i < b && j > b) {
                            crossesBoundary = true;
                            break;
                        }
                    }
                    if (crossesBoundary) {
                        continue;
                    }
                }

                String subKey = key.substring(i, j);
                // 5. 学習辞書およびメイン辞書のエントリ一覧を取得するメソッドを呼び出して候補エントリを取得する
                List<WordEntry> entries = getEntries(subKey);
                if (entries.isEmpty()) {
                    continue;
                }

                for (WordEntry entry : entries) {
                    if (entry.surface.isEmpty()) continue;

                    int posId = entry.posId != 0 ? entry.posId : mDictionary.findSystemDictId(subKey, entry.surface);
                    boolean isLearning = entry.isLearningWord();
                    Candidate.Type pathType = isLearning ? Candidate.Type.LEARNING : Candidate.Type.SYSTEM;

                    // 6. 既存の先行パス（prevPath）と現在のエントリを接続し、品詞間接続コストと単語コストを加算して新しいトータルコストを算出する
                    for (PathNode prevPath : currentPaths) {
                        int connCost = mDictionary != null ? mDictionary.getConnectionCost(prevPath.posId, posId) : 200;
                        int newTotalCost = prevPath.totalCost + connCost + entry.cost;

                        PathNode newNode = new PathNode(
                                subKey,
                                entry.surface,
                                posId,
                                entry.cost,
                                pathType,
                                prevPath,
                                newTotalCost
                        );

                        lattice[j].add(newNode);
                    }
                }

                // 7. 状態爆発を防ぐため、各位置（j）におけるパスをコスト順にソートし、上位N-bestに枝刈り（Pruning）する
                if (lattice[j].size() > mNBest * 4) {
                    lattice[j].sort((p1, p2) -> {
                        boolean l1 = hasLearningWord(p1);
                        boolean l2 = hasLearningWord(p2);
                        if (l1 != l2) {
                            return l1 ? -1 : 1;
                        }
                        return Integer.compare(p1.totalCost, p2.totalCost);
                    });
                    List<PathNode> trimmed = new ArrayList<>();
                    for (int k = 0; k < Math.min(mNBest * 2, lattice[j].size()); k++) {
                        trimmed.add(lattice[j].get(k));
                    }
                    lattice[j] = trimmed;
                }
            }
        }

        // 8. 終端位置（n）に到達した全パスを取得し、コストが最小のものから順にソートする
        List<PathNode> finalPaths = lattice[n];
        if (finalPaths.isEmpty()) {
            return Collections.emptyList();
        }

        finalPaths.sort((p1, p2) -> {
            boolean l1 = hasLearningWord(p1);
            boolean l2 = hasLearningWord(p2);
            if (l1 != l2) {
                return l1 ? -1 : 1;
            }
            return Integer.compare(p1.totalCost, p2.totalCost);
        });

        // 9. デバッグログ出力（上位3個の候補パスを表示）
        printDebugLog(finalPaths);

        List<Candidate> results = new ArrayList<>();
        Map<String, Boolean> seenSurfaces = new HashMap<>();

        int count = 0;
        for (PathNode path : finalPaths) {
            if (count >= mNBest) break;

            StringBuilder surfaceBuilder = new StringBuilder();
            boolean learningDictInvolved = false;
            PathNode curr = path;
            List<PathNode> words = new ArrayList<>();
            // 最適パスを逆順にたどって単語列（構成要素）を復元する
            while (curr != null && curr.prev != null) {
                words.add(0, curr);
                if (curr.isLearningWord()) learningDictInvolved = true;
                curr = curr.prev;
            }

            for (PathNode w : words) {
                surfaceBuilder.append(w.surface);
            }

            String fullSurface = surfaceBuilder.toString();
            if (!fullSurface.isEmpty() && seenSurfaces.put(fullSurface, true) == null) {
                WordEntry fullEntry = new WordEntry(key, fullSurface, path.posId, path.totalCost);
                Candidate candidate = new Candidate(fullSurface, fullSurface).setType(learningDictInvolved ? Candidate.Type.LEARNING : Candidate.Type.SYSTEM)
                        .setEntry(fullEntry)
                        .setViterbiPathNode(path);

                results.add(candidate);
            }
            count++;
        }

        return results;
    }

    /**
     * デバッグログが有効な場合に、最終候補の上位3個のViterbiパス詳細を出力します。
     *
     * @param finalPaths 探索結果の最終パスのリスト
     */
    private void printDebugLog(List<PathNode> finalPaths) {
        if (!io.github.kachaya.ime.BuildConfig.DEBUG) {
            return;
        }
        if (finalPaths == null || finalPaths.isEmpty()) {
            return;
        }

        int limit = Math.min(3, finalPaths.size());
        for (int i = 0; i < limit; i++) {
            PathNode path = finalPaths.get(i);
            PathNode curr = path;
            List<PathNode> words = new ArrayList<>();
            while (curr != null && curr.prev != null) {
                words.add(0, curr);
                curr = curr.prev;
            }

            println("BOS");
            int prevPosId = 0;
            for (PathNode w : words) {
                String posStr = mDictionary != null ? mDictionary.getPosString(w.posId) : String.valueOf(w.posId);
                println(w.reading + ", " + w.surface + ", " + posStr + ", " + w.cost);
                int connCost = mDictionary != null ? mDictionary.getConnectionCost(prevPosId, w.posId) : 200;
                println(connCost);
                prevPosId = w.posId;
            }
            println("EOS");
        }
    }

    private boolean hasLearningWord(PathNode path) {
        PathNode curr = path;
        while (curr != null && curr.prev != null) {
            if (curr.isLearningWord()) {
                return true;
            }
            curr = curr.prev;
        }
        return false;
    }

    private List<WordEntry> getEntries(String subKey) {
        List<WordEntry> list = new ArrayList<>();
        if (mDictionary != null) {
            List<WordEntry> learningEntries = mDictionary.getLearningDictEntries(subKey);
            List<WordEntry> systemEntries = mDictionary.getSystemDictWordEntries(subKey);

            systemEntries.sort(Comparator.comparingInt(e -> e.cost));

            Set<String> learningKeys = new HashSet<>();

            // 1. 学習辞書にあるものをそのまま（直近の選択を上位に維持して）追加
            for (WordEntry le : learningEntries) {
                learningKeys.add(le.surface + "_" + le.posId);
                learningKeys.add(le.surface);
                list.add(le);
            }

            // 2. システム辞書にあるものを追加（読み・表記・品詞が同じものは追加しない）
            for (WordEntry se : systemEntries) {
                String keyWithPos = se.surface + "_" + se.posId;
                if (!learningKeys.contains(keyWithPos) && !learningKeys.contains(se.surface)) {
                    list.add(se);
                }
            }
        }
        return list;
    }
}
