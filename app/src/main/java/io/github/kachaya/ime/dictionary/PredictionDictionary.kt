package io.github.kachaya.ime.dictionary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * 連続して確定された単語間の接続関係（Bigram）を学習し、予測候補を検索・提供するクラスです。
 * 読み、表記、品詞ID（posId）、コスト（cost）の完全なエントリ属性を保持し、最適化インデックスにより高速な予測を実現します。
 */
class PredictionDictionary(context: Context?) {

    companion object {
        private const val PREDICTION_DICT = "prediction_dict"
        private const val MAX_PREDICTION_ENTRIES = 2000

        private const val CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS bigram_predictions (" +
                    "prev_word TEXT NOT NULL, " +
                    "prev_reading TEXT, " +
                    "prev_pos_id INTEGER, " +
                    "next_word TEXT NOT NULL, " +
                    "next_reading TEXT, " +
                    "next_pos_id INTEGER DEFAULT 0, " +
                    "next_cost INTEGER DEFAULT 0, " +
                    "frequency INTEGER NOT NULL DEFAULT 1, " +
                    "last_used INTEGER NOT NULL, " +
                    "PRIMARY KEY (prev_word, next_word));"

        private const val CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_prev_freq ON bigram_predictions (prev_word, frequency DESC, last_used DESC);"
    }

    private var mDbPredictionDict: SQLiteDatabase? = null

    init {
        val filesDirPath = context?.filesDir?.absolutePath ?: ""
        try {
            val path = "$filesDirPath/$PREDICTION_DICT.db"
            mDbPredictionDict = openOrCreateDb(path)
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Failed to open prediction dictionary: ${e.message}")
            mDbPredictionDict = null
        }
    }

    private fun openOrCreateDb(path: String): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(CREATE_TABLE_SQL)
        
        // Migrate existing tables if columns are missing
        try { db.execSQL("ALTER TABLE bigram_predictions ADD COLUMN prev_reading TEXT;") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE bigram_predictions ADD COLUMN prev_pos_id INTEGER;") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE bigram_predictions ADD COLUMN next_reading TEXT;") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE bigram_predictions ADD COLUMN next_pos_id INTEGER DEFAULT 0;") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE bigram_predictions ADD COLUMN next_cost INTEGER DEFAULT 0;") } catch (_: Exception) {}

        db.execSQL(CREATE_INDEX_SQL)
        return db
    }

    @Synchronized
    fun learnTransition(
        prevWord: String?, 
        nextWord: String?, 
        prevReading: String? = null, 
        prevPosId: Int? = null,
        nextReading: String? = null, 
        nextPosId: Int? = null,
        nextCost: Int? = null
    ) {
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return
        if (prevWord.isNullOrBlank() || nextWord.isNullOrBlank()) {
            return
        }
        val pWord = prevWord.trim()
        val nWord = nextWord.trim()
        if (pWord == nWord) {
            return
        }

        val now = System.currentTimeMillis()
        try {
            db.beginTransaction()
            try {
                var freq = 1
                db.rawQuery(
                    "SELECT frequency FROM bigram_predictions WHERE prev_word = ? AND next_word = ?",
                    arrayOf(pWord, nWord)
                ).use { c ->
                    if (c.moveToFirst()) {
                        freq = c.getInt(0) + 1
                    }
                }
                db.execSQL(
                    "INSERT OR REPLACE INTO bigram_predictions (prev_word, prev_reading, prev_pos_id, next_word, next_reading, next_pos_id, next_cost, frequency, last_used) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        pWord, 
                        prevReading?.trim(), 
                        prevPosId, 
                        nWord, 
                        nextReading?.trim(), 
                        nextPosId ?: 0, 
                        nextCost ?: 0, 
                        freq, 
                        now
                    )
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            pruneIfNeeded()
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Error learning transition: $pWord -> $nWord", e)
        }
    }

    fun predictNextCandidates(prevWord: String?, prefix: String?, maxCount: Int): List<Candidate> {
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return emptyList()
        if (prevWord.isNullOrBlank()) return emptyList()

        val pWord = prevWord.trim()
        val candidates = ArrayList<Candidate>()

        val sql: String
        val selectionArgs: Array<String>

        if (!prefix.isNullOrBlank()) {
            sql = "SELECT next_word, next_reading, next_pos_id, next_cost FROM bigram_predictions " +
                    "WHERE prev_word = ? AND (next_word LIKE ? || '%' OR next_reading LIKE ? || '%') " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?"
            val pref = prefix.trim()
            selectionArgs = arrayOf(pWord, pref, pref, maxCount.toString())
        } else {
            sql = "SELECT next_word, next_reading, next_pos_id, next_cost FROM bigram_predictions " +
                    "WHERE prev_word = ? " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?"
            selectionArgs = arrayOf(pWord, maxCount.toString())
        }

        try {
            db.rawQuery(sql, selectionArgs).use { cursor ->
                while (cursor.moveToNext()) {
                    val nextWord = cursor.getString(0)
                    val nextReading = cursor.getString(1) ?: nextWord
                    val nextPosId = cursor.getInt(2)
                    val nextCost = cursor.getInt(3)

                    val c = Candidate(nextWord, nextWord, "Zenz AI")
                    c.type = Candidate.Type.PREDICTION
                    val we = WordEntry(nextReading, nextWord, nextPosId, nextCost)
                    we.type = Candidate.Type.PREDICTION
                    c.setEntry(we)
                    candidates.add(c)
                }
            }
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Error predicting candidates for: $pWord", e)
        }

        return candidates
    }

    fun getPredictedSurfaceWords(prevWord: String?, maxCount: Int): List<String> {
        val surfaces = ArrayList<String>()
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return surfaces
        if (prevWord.isNullOrBlank()) return surfaces

        val pWord = prevWord.trim()
        val sql = "SELECT next_word FROM bigram_predictions " +
                "WHERE prev_word = ? " +
                "ORDER BY frequency DESC, last_used DESC LIMIT ?"
        val selectionArgs = arrayOf(pWord, maxCount.toString())

        try {
            db.rawQuery(sql, selectionArgs).use { cursor ->
                while (cursor.moveToNext()) {
                    surfaces.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Error getting predicted surfaces for: $pWord", e)
        }
        return surfaces
    }

    fun getAllPredictions(): List<PredictionEntry> {
        val list = ArrayList<PredictionEntry>()
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return list
        try {
            db.rawQuery(
                "SELECT prev_word, prev_pos_id, next_word, next_pos_id, frequency, last_used FROM bigram_predictions ORDER BY prev_word ASC, frequency DESC, last_used DESC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        PredictionEntry(
                            cursor.getString(0),
                            cursor.getInt(1),
                            cursor.getString(2),
                            cursor.getInt(3),
                            cursor.getInt(4),
                            cursor.getLong(5)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun deletePrediction(prevWord: String?, nextWord: String?) {
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return
        try {
            db.execSQL(
                "DELETE FROM bigram_predictions WHERE prev_word = ? AND next_word = ?",
                arrayOf<Any?>(prevWord, nextWord)
            )
        } catch (_: Exception) {
        }
    }

    private fun pruneIfNeeded() {
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return
        try {
            db.rawQuery("SELECT COUNT(*) FROM bigram_predictions", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getLong(0)
                    if (count > MAX_PREDICTION_ENTRIES) {
                        val toDelete = count - MAX_PREDICTION_ENTRIES + 100
                        db.execSQL(
                            "DELETE FROM bigram_predictions WHERE rowid IN (" +
                                    "SELECT rowid FROM bigram_predictions ORDER BY frequency ASC, last_used ASC LIMIT ?);",
                            arrayOf<Any>(toDelete)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Error pruning prediction entries", e)
        }
    }

    @Synchronized
    fun clearPredictionData() {
        val db = mDbPredictionDict
        if (db == null || !db.isOpen) return
        try {
            db.execSQL("DELETE FROM bigram_predictions;")
        } catch (e: Exception) {
            Log.e("PredictionDictionary", "Error clearing prediction data", e)
        }
    }

    @Synchronized
    fun close() {
        val db = mDbPredictionDict
        if (db != null && db.isOpen) {
            try {
                db.close()
            } catch (_: Exception) {
            }
            mDbPredictionDict = null
        }
    }

    class PredictionEntry(
        var prevWord: String?,
        var prevPosId: Int,
        var nextWord: String?,
        var nextPosId: Int,
        var frequency: Int,
        var lastUsed: Long
    ) {
        override fun toString(): String {
            return "$prevWord ➔ $nextWord (頻度: $frequency)"
        }
    }
}
