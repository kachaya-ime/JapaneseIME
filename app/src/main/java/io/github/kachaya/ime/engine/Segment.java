package io.github.kachaya.ime.engine;

import io.github.kachaya.ime.dictionary.Candidate;

import java.util.Collections;
import java.util.List;

/**
 * 分節変換における 1 つの文節（読み、変換候補リスト、現在選択中の候補インデックス）を表すクラスです。
 */
public class Segment {
    public String reading;
    public List<Candidate> candidates;
    public int selectedIndex;

    public Segment(String reading, List<Candidate> candidates, int selectedIndex) {
        this.reading = reading;
        this.candidates = candidates != null ? candidates : Collections.emptyList();
        this.selectedIndex = selectedIndex;
    }

    /**
     * 現在選択されている候補オブジェクトを取得します。
     *
     * @return 選択中の Candidate。無効なインデックスの場合は null
     */
    public Candidate getSelectedCandidate() {
        if (selectedIndex >= 0 && selectedIndex < candidates.size()) {
            return candidates.get(selectedIndex);
        }
        return null;
    }

    /**
     * 現在選択されている候補の表記文字列を取得します。
     *
     * @return 選択中の候補文字列。未選択の場合は読み文字列
     */
    public String getSelectedSurface() {
        Candidate c = getSelectedCandidate();
        if (c != null && c.candidate != null) {
            return c.candidate;
        }
        return reading;
    }
}
