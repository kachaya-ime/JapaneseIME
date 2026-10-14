package io.github.kachaya.ime.engine;

import io.github.kachaya.ime.dictionary.Candidate;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 複数文節変換における文節リスト、注目文節のフォーカス位置、および文節の伸縮処理を管理するクラスです。
 */
public class SegmentManager {

    /** 文節候補の取得を行うコールバック・インターフェースです。 */
    @FunctionalInterface
    public interface CandidateFetcher {
        List<Candidate> fetchCandidates(String reading, String preferredSurface);
    }

    private List<Segment> mSegments = null;
    private int mFocusedSegmentIndex = 0;

    /**
     * 分節データが存在するかどうかを判定します。
     *
     * @return 1 つ以上の分節が存在する場合は true
     */
    public boolean hasSegments() {
        return mSegments != null && !mSegments.isEmpty();
    }

    /**
     * 現在の文節リストを取得します。
     *
     * @return 文節リスト
     */
    public List<Segment> getSegments() {
        return mSegments;
    }

    /**
     * 文節リストを設定します。
     *
     * @param segments 設定する文節リスト
     */
    public void setSegments(List<Segment> segments) {
        mSegments = segments;
        mFocusedSegmentIndex = 0;
    }

    /**
     * 現在フォーカス中の文節インデックスを取得します。
     *
     * @return フォーカス中のインデックス
     */
    public int getFocusedSegmentIndex() {
        return mFocusedSegmentIndex;
    }

    /**
     * 現在フォーカス中の文節インデックスを設定します。
     *
     * @param index 設定するインデックス
     */
    public void setFocusedSegmentIndex(int index) {
        if (mSegments != null && index >= 0 && index < mSegments.size()) {
            mFocusedSegmentIndex = index;
        }
    }

    /**
     * 現在フォーカス中の Segment オブジェクトを取得します。
     *
     * @return フォーカス中の Segment。存在しない場合は null
     */
    public Segment getActiveSegment() {
        if (hasSegments() && mFocusedSegmentIndex >= 0 && mFocusedSegmentIndex < mSegments.size()) {
            return mSegments.get(mFocusedSegmentIndex);
        }
        return null;
    }

    /**
     * 次の文節へフォーカスを移動します。
     *
     * @return 移動が成功した場合は true
     */
    public boolean focusNextSegment() {
        if (mSegments != null && mFocusedSegmentIndex < mSegments.size() - 1) {
            mFocusedSegmentIndex++;
            return true;
        }
        return false;
    }

    /**
     * 前の文節へフォーカスを移動します。
     *
     * @return 移動が成功した場合は true
     */
    public boolean focusPrevSegment() {
        if (mSegments != null && mFocusedSegmentIndex > 0) {
            mFocusedSegmentIndex--;
            return true;
        }
        return false;
    }

    /**
     * 全文節の選択候補文字列を連結した文字列を取得します。
     *
     * @return 連結された全文節の変換文字列
     */
    public String getMultiSegmentText() {
        if (!hasSegments()) {
            return null;
        }
        Segment active = getActiveSegment();
        Candidate activeCand = active != null ? active.getSelectedCandidate() : null;
        if (activeCand != null && activeCand.isZenz()) {
            return activeCand.candidate;
        }
        StringBuilder sb = new StringBuilder();
        for (Segment seg : mSegments) {
            sb.append(seg.getSelectedSurface());
        }
        return sb.toString();
    }

    /**
     * 現在フォーカス中の文節の読長さを変更（拡大・縮小）し、残りの読文字列を Viterbi で再分割します。
     *
     * @param delta          +1 で拡大、-1 で縮小
     * @param fullQuery      全検索クエリ文字列
     * @param fetcher        候補取得用フェッチャー
     * @param viterbiDecoder 再分割用の Viterbi デコーダー
     * @return 変更が行われた場合は true
     */
    public boolean resizeFocusedSegment(int delta, String fullQuery, CandidateFetcher fetcher, ViterbiDecoder viterbiDecoder) {
        if (!hasSegments()) {
            return false;
        }
        if (mFocusedSegmentIndex < 0 || mFocusedSegmentIndex >= mSegments.size()) {
            return false;
        }

        int startOffset = 0;
        for (int i = 0; i < mFocusedSegmentIndex; i++) {
            startOffset += mSegments.get(i).reading.length();
        }

        if (fullQuery == null || startOffset >= fullQuery.length()) {
            return false;
        }

        String remainingQuery = fullQuery.substring(startOffset);
        String currentReading = mSegments.get(mFocusedSegmentIndex).reading;

        int newLen = currentReading.length() + delta;
        if (newLen < 1 || newLen > remainingQuery.length()) {
            return false;
        }

        String newFocusedReading = remainingQuery.substring(0, newLen);
        String restQuery = remainingQuery.substring(newLen);

        List<Candidate> newFocusedCands = fetcher.fetchCandidates(newFocusedReading, null);
        Segment newFocusedSeg = new Segment(newFocusedReading, newFocusedCands, 0);

        List<Segment> restSegments = new ArrayList<>();
        if (!restQuery.isEmpty()) {
            List<Candidate> restViterbi = null;
            if (viterbiDecoder != null) {
                try {
                    restViterbi = viterbiDecoder.decode(restQuery, null);
                } catch (Exception e) {
                    Log.e("SegmentManager", "Viterbi decode error on resize restQuery", e);
                }
            }

            if (restViterbi != null && !restViterbi.isEmpty() && restViterbi.get(0).viterbiPathNode != null) {
                ViterbiDecoder.PathNode curr = restViterbi.get(0).viterbiPathNode;
                List<ViterbiDecoder.PathNode> nodes = new ArrayList<>();
                while (curr != null && curr.prev != null) {
                    if (curr.reading != null && !curr.reading.isEmpty()) {
                        nodes.add(0, curr);
                    }
                    curr = curr.prev;
                }

                StringBuilder sb = new StringBuilder();
                for (ViterbiDecoder.PathNode n : nodes) {
                    sb.append(n.reading);
                }

                if (sb.toString().equals(restQuery) && !nodes.isEmpty()) {
                    for (ViterbiDecoder.PathNode n : nodes) {
                        List<Candidate> cands = fetcher.fetchCandidates(n.reading, n.surface);
                        restSegments.add(new Segment(n.reading, cands, 0));
                    }
                }
            }

            if (restSegments.isEmpty()) {
                List<Candidate> cands = fetcher.fetchCandidates(restQuery, null);
                restSegments.add(new Segment(restQuery, cands, 0));
            }
        }

        List<Segment> updatedSegments = new ArrayList<>();
        for (int i = 0; i < mFocusedSegmentIndex; i++) {
            updatedSegments.add(mSegments.get(i));
        }
        updatedSegments.add(newFocusedSeg);
        updatedSegments.addAll(restSegments);

        mSegments = updatedSegments;
        return true;
    }

    /**
     * 文節状態をクリアします。
     */
    public void clear() {
        mSegments = null;
        mFocusedSegmentIndex = 0;
    }
}
