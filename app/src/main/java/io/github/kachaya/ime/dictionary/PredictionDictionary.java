package io.github.kachaya.ime.dictionary;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 連続して確定された単語間の接続関係（Bigram）を学習し、予測候補を検索・提供するクラスです。
 */
public class PredictionDictionary {

    private static final String PREDICTION_DICT = "prediction_dict";
    private static final int MAX_PREDICTION_ENTRIES = 2000;

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS bigram_predictions (" +
                    "prev_word TEXT NOT NULL, " +
                    "next_word TEXT NOT NULL, " +
                    "frequency INTEGER NOT NULL DEFAULT 1, " +
                    "last_used INTEGER NOT NULL, " +
                    "PRIMARY KEY (prev_word, next_word));";

    private static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_prev_word ON bigram_predictions (prev_word);";

    private SQLiteDatabase mDbPredictionDict;

    private SQLiteDatabase openOrCreateDb(String path) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL(CREATE_TABLE_SQL);
        db.execSQL(CREATE_INDEX_SQL);
        return db;
    }

    public PredictionDictionary(Context context) {
        String filesDirPath = context != null ? context.getFilesDir().getAbsolutePath() : "";
        try {
            String path = filesDirPath + "/" + PREDICTION_DICT + ".db";
            mDbPredictionDict = openOrCreateDb(path);
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Failed to open prediction dictionary: " + e.getMessage());
            mDbPredictionDict = null;
        }
    }

    /**
     * 直前に確定された単語と、今回確定された単語の接続関係を学習・保持します。
     *
     * @param prevWord 前回の確定単語
     * @param nextWord 今回の確定単語
     */
    public synchronized void learnTransition(String prevWord, String nextWord) {
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return;
        if (prevWord == null || prevWord.trim().isEmpty() || nextWord == null || nextWord.trim().isEmpty()) {
            return;
        }
        String pWord = prevWord.trim();
        String nWord = nextWord.trim();
        if (pWord.equals(nWord)) {
            return;
        }

        long now = System.currentTimeMillis();
        try {
            mDbPredictionDict.beginTransaction();
            try {
                int freq = 1;
                try (Cursor c = mDbPredictionDict.rawQuery(
                        "SELECT frequency FROM bigram_predictions WHERE prev_word = ? AND next_word = ?",
                        new String[]{pWord, nWord})) {
                    if (c.moveToFirst()) {
                        freq = c.getInt(0) + 1;
                    }
                }
                mDbPredictionDict.execSQL(
                        "INSERT OR REPLACE INTO bigram_predictions (prev_word, next_word, frequency, last_used) VALUES (?, ?, ?, ?)",
                        new Object[]{pWord, nWord, freq, now});
                mDbPredictionDict.setTransactionSuccessful();
            } finally {
                mDbPredictionDict.endTransaction();
            }
            pruneIfNeeded();
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Error learning transition: " + pWord + " -> " + nWord, e);
        }
    }

    /**
     * 直前の確定単語（および現在入力中のプレフィックス）に基づき、次に来る可能性が高い予測候補を取得します。
     *
     * @param prevWord 前回の確定単語
     * @param prefix   現在入力中の読み・表記プレフィックス（任意のフィルター、null 可）
     * @param maxCount 最大取得件数
     * @return 予測候補のリスト
     */
    public List<Candidate> predictNextCandidates(String prevWord, String prefix, int maxCount) {
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return Collections.emptyList();
        if (prevWord == null || prevWord.trim().isEmpty()) return Collections.emptyList();

        String pWord = prevWord.trim();
        List<Candidate> candidates = new ArrayList<>();

        String sql;
        String[] selectionArgs;

        if (prefix != null && !prefix.trim().isEmpty()) {
            sql = "SELECT next_word FROM bigram_predictions " +
                    "WHERE prev_word = ? AND next_word LIKE ? || '%' " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?";
            selectionArgs = new String[]{pWord, prefix.trim(), String.valueOf(maxCount)};
        } else {
            sql = "SELECT next_word FROM bigram_predictions " +
                    "WHERE prev_word = ? " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?";
            selectionArgs = new String[]{pWord, String.valueOf(maxCount)};
        }

        try (Cursor cursor = mDbPredictionDict.rawQuery(sql, selectionArgs)) {
            while (cursor.moveToNext()) {
                String nextWord = cursor.getString(0);
                Candidate c = new Candidate(nextWord, nextWord);
                c.setType(Candidate.Type.PREDICTION);
                candidates.add(c);
            }
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Error predicting candidates for: " + pWord, e);
        }

        return candidates;
    }

    public List<String> getPredictedSurfaceWords(String prevWord, int maxCount) {
        List<String> surfaces = new ArrayList<>();
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return surfaces;
        if (prevWord == null || prevWord.trim().isEmpty()) return surfaces;

        String pWord = prevWord.trim();
        String sql = "SELECT next_word FROM bigram_predictions " +
                "WHERE prev_word = ? " +
                "ORDER BY frequency DESC, last_used DESC LIMIT ?";
        String[] selectionArgs = new String[]{pWord, String.valueOf(maxCount)};

        try (Cursor cursor = mDbPredictionDict.rawQuery(sql, selectionArgs)) {
            while (cursor.moveToNext()) {
                surfaces.add(cursor.getString(0));
            }
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Error getting predicted surfaces for: " + pWord, e);
        }
        return surfaces;
    }

    public List<PredictionEntry> getAllPredictions() {
        List<PredictionEntry> list = new ArrayList<>();
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return list;
        try (Cursor cursor = mDbPredictionDict.rawQuery(
                "SELECT prev_word, next_word, frequency, last_used FROM bigram_predictions ORDER BY frequency DESC, last_used DESC", null)) {
            while (cursor.moveToNext()) {
                list.add(new PredictionEntry(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getLong(3)
                ));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void deletePrediction(String prevWord, String nextWord) {
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return;
        try {
            mDbPredictionDict.execSQL(
                    "DELETE FROM bigram_predictions WHERE prev_word = ? AND next_word = ?",
                    new Object[]{prevWord, nextWord});
        } catch (Exception ignored) {
        }
    }

    private void pruneIfNeeded() {
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return;
        try (Cursor cursor = mDbPredictionDict.rawQuery("SELECT COUNT(*) FROM bigram_predictions", null)) {
            if (cursor.moveToFirst()) {
                long count = cursor.getLong(0);
                if (count > MAX_PREDICTION_ENTRIES) {
                    long toDelete = count - MAX_PREDICTION_ENTRIES + 100;
                    mDbPredictionDict.execSQL(
                            "DELETE FROM bigram_predictions WHERE rowid IN (" +
                                    "SELECT rowid FROM bigram_predictions ORDER BY frequency ASC, last_used ASC LIMIT ?);",
                            new Object[]{toDelete});
                }
            }
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Error pruning prediction entries", e);
        }
    }

    public synchronized void clearPredictionData() {
        if (mDbPredictionDict == null || !mDbPredictionDict.isOpen()) return;
        try {
            mDbPredictionDict.execSQL("DELETE FROM bigram_predictions;");
        } catch (Exception e) {
            Log.e("PredictionDictionary", "Error clearing prediction data", e);
        }
    }

    public synchronized void close() {
        if (mDbPredictionDict != null && mDbPredictionDict.isOpen()) {
            try {
                mDbPredictionDict.close();
            } catch (Exception ignored) {
            }
            mDbPredictionDict = null;
        }
    }

    public static class PredictionEntry {
        public String prevWord;
        public String nextWord;
        public int frequency;
        public long lastUsed;

        public PredictionEntry(String prevWord, String nextWord, int frequency, long lastUsed) {
            this.prevWord = prevWord;
            this.nextWord = nextWord;
            this.frequency = frequency;
            this.lastUsed = lastUsed;
        }

        @Override
        public String toString() {
            return prevWord + " ➔ " + nextWord + " (頻度: " + frequency + ")";
        }
    }
}
