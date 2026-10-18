package io.github.kachaya.ime.dictionary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * 連続して確定された単語間の接続関係（Bigram）を学習し、予測候補を検索・提供するクラスです。
 */
class PredictionDictionary(context: Context?) {

    companion object {
        private const val PREDICTION_DICT = "prediction_dict"
        private const val MAX_PREDICTION_ENTRIES = 2000

        private const val CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS bigram_predictions (" +
                    "prev_word TEXT NOT NULL, " +
                    "next_word TEXT NOT NULL, " +
                    "frequency INTEGER NOT NULL DEFAULT 1, " +
                    "last_used INTEGER NOT NULL, " +
                    "PRIMARY KEY (prev_word, next_word));"

        private const val CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_prev_word ON bigram_predictions (prev_word);"
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
        db.execSQL(CREATE_INDEX_SQL)
        return db
    }

    @Synchronized
    fun learnTransition(prevWord: String?, nextWord: String?) {
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
                    "INSERT OR REPLACE INTO bigram_predictions (prev_word, next_word, frequency, last_used) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(pWord, nWord, freq, now)
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
            sql = "SELECT next_word FROM bigram_predictions " +
                    "WHERE prev_word = ? AND next_word LIKE ? || '%' " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?"
            selectionArgs = arrayOf(pWord, prefix.trim(), maxCount.toString())
        } else {
            sql = "SELECT next_word FROM bigram_predictions " +
                    "WHERE prev_word = ? " +
                    "ORDER BY frequency DESC, last_used DESC LIMIT ?"
            selectionArgs = arrayOf(pWord, maxCount.toString())
        }

        try {
            db.rawQuery(sql, selectionArgs).use { cursor ->
                while (cursor.moveToNext()) {
                    val nextWord = cursor.getString(0)
                    val c = Candidate(nextWord, nextWord, "Zenz AI")
                    c.type = Candidate.Type.PREDICTION
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
                "SELECT prev_word, next_word, frequency, last_used FROM bigram_predictions ORDER BY frequency DESC, last_used DESC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        PredictionEntry(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getInt(2),
                            cursor.getLong(3)
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
        @JvmField var prevWord: String?,
        @JvmField var nextWord: String?,
        @JvmField var frequency: Int,
        @JvmField var lastUsed: Long
    ) {
        override fun toString(): String {
            return "$prevWord ➔ $nextWord (頻度: $frequency)"
        }
    }
}
