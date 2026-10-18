package io.github.kachaya.ime.dictionary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.kachaya.ime.BuildConfig
import io.github.kachaya.ime.engine.RomajiConverter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.Locale

/**
 * ユーザーの登録・単語学習内容を保持する「学習辞書」をバイナリ形式（surface, posId, cost）のBLOB配列として管理・検索するクラスです。
 */
class LearningDictionary(context: Context?) {

    companion object {
        private const val LEARNING_DICT = "learning_dict"
        private const val CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS dictionary (`key` TEXT PRIMARY KEY, `value` BLOB NOT NULL) WITHOUT ROWID;"

        private fun serializeEntries(entries: List<LearningDictEntry>): ByteArray {
            try {
                ByteArrayOutputStream().use { baos ->
                    DataOutputStream(baos).use { dos ->
                        dos.writeInt(entries.size)
                        for (e in entries) {
                            dos.writeUTF(e.surface)
                            dos.writeShort(e.posId)
                            dos.writeShort(e.cost)
                        }
                        dos.flush()
                        return baos.toByteArray()
                    }
                }
            } catch (e: Exception) {
                Log.e("LearningDictionary", "Failed to serialize learning entries", e)
                return ByteArray(0)
            }
        }

        private fun deserializeEntries(data: ByteArray?): List<LearningDictEntry> {
            val entries = ArrayList<LearningDictEntry>()
            if (data == null || data.isEmpty()) return entries
            try {
                DataInputStream(ByteArrayInputStream(data)).use { dis ->
                    val count = dis.readInt()
                    for (i in 0..<count) {
                        try {
                            val surface = dis.readUTF()
                            val posId = dis.readShort().toInt() and 0xFFFF
                            val cost = dis.readShort().toInt()
                            entries.add(LearningDictEntry(surface, posId, cost))
                        } catch (eof: EOFException) {
                            Log.w("LearningDictionary", "Reached EOF while deserializing entry $i of $count", eof)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LearningDictionary", "Failed to deserialize learning entries", e)
            }
            return entries
        }
    }

    private val mFilesDirPath: String = context?.filesDir?.absolutePath ?: ""
    private var mDbLearningDict: SQLiteDatabase? = null

    init {
        try {
            val path = "$mFilesDirPath/$LEARNING_DICT.db"
            mDbLearningDict = openOrCreateDictDb(path)
        } catch (e: Exception) {
            Log.e("LearningDictionary", "Failed to open learning dictionary: ${e.message}")
            mDbLearningDict = null
        }
    }

    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(javaClass.simpleName, msg)
        }
    }

    private fun openOrCreateDictDb(path: String): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(CREATE_TABLE_SQL)
        return db
    }

    private fun getBlobFromDb(db: SQLiteDatabase?, key: String?): ByteArray? {
        if (db == null || !db.isOpen || key == null) return null
        try {
            db.rawQuery("SELECT value FROM dictionary WHERE `key` = ?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getBlob(0)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun findKeys(key: String?, limit: Int): List<String> {
        val db = mDbLearningDict
        if (db == null || !db.isOpen || key.isNullOrEmpty()) return emptyList()
        val list = ArrayList<String>()
        val upperBound = key.substring(0, key.length - 1) + (key[key.length - 1].code + 1).toChar()
        try {
            db.rawQuery(
                "SELECT `key` FROM dictionary WHERE `key` >= ? AND `key` < ? ORDER BY `key` ASC",
                arrayOf(key, upperBound)
            ).use { cursor ->
                while (cursor.moveToNext() && list.size < limit) {
                    val str = cursor.getString(0)
                    if (RomajiConverter.isAlphabet(str[str.length - 1].code) && !RomajiConverter.isAlphabet(str[0].code)) {
                        continue
                    }
                    list.add(str)
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun getLearningDictEntry(key: String?): Entry? {
        val blob = getBlobFromDb(mDbLearningDict, key) ?: return null
        val entries = deserializeEntries(blob)
        if (entries.isEmpty() && blob.isNotEmpty()) {
            if (key != null) deleteEntry(key)
            return null
        }
        return Entry(entries.toMutableList())
    }

    fun getLearningDictEntries(key: String?): List<WordEntry> {
        var entry = getLearningDictEntry(key)
        if (entry == null && key != null && key != key.lowercase(Locale.ENGLISH)) {
            entry = getLearningDictEntry(key.lowercase(Locale.ENGLISH))
        }
        if (entry == null || entry.entries.isNullOrEmpty()) {
            return emptyList()
        }
        val list = ArrayList<WordEntry>()
        val safeKey = key ?: ""
        for (le in entry.entries!!) {
            val effectiveCost = Math.min(le.cost, 100)
            val we = WordEntry(safeKey, le.surface, le.posId, effectiveCost)
            we.type = Candidate.Type.LEARNING
            list.add(we)
        }
        return list
    }

    fun getLearningDictionaryName(): String = LEARNING_DICT

    @Synchronized
    fun clearLearningDictionary() {
        val db = mDbLearningDict
        if (db == null || !db.isOpen) return
        try {
            db.execSQL("DELETE FROM dictionary;")
            Log.d("LearningDictionary", "Cleared all entries from learning dictionary.")
        } catch (e: Exception) {
            Log.e("LearningDictionary", "Failed to clear learning dictionary", e)
            throw RuntimeException("Failed to clear learning dictionary", e)
        }
    }

    @Synchronized
    fun reloadLearningDictionary() {
        try {
            val db = mDbLearningDict
            if (db != null && db.isOpen) {
                try {
                    db.close()
                } catch (_: Exception) {
                }
                mDbLearningDict = null
            }
            val path = "$mFilesDirPath/$LEARNING_DICT.db"
            mDbLearningDict = openOrCreateDictDb(path)
        } catch (e: Exception) {
            Log.e("LearningDictionary", "Failed to reload learning dictionary: ${e.message}")
            mDbLearningDict = null
        }
    }

    fun exportLearningDictionary(): List<String> {
        val list = ArrayList<String>()
        val db = mDbLearningDict
        if (db == null || !db.isOpen) return list
        try {
            db.rawQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val blob = cursor.getBlob(1)
                    val entries = deserializeEntries(blob)
                    if (entries.isEmpty() && blob != null && blob.isNotEmpty()) {
                        deleteEntry(key)
                        continue
                    }
                    for (e in entries) {
                        list.add("$key ${e.surface} ${e.posId} ${e.cost}")
                    }
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveOrUpdateEntry(db: SQLiteDatabase?, key: String, value: ByteArray) {
        if (db == null || !db.isOpen) return
        try {
            db.execSQL("INSERT OR REPLACE INTO dictionary (`key`, `value`) VALUES (?, ?)", arrayOf<Any>(key, value))
        } catch (_: Exception) {
        }
    }

    fun importLearningDictionary(entries: List<String>) {
        val db = mDbLearningDict
        if (db == null || !db.isOpen) return
        db.beginTransaction()
        try {
            val map = LinkedHashMap<String, MutableList<LearningDictEntry>>()
            for (entryStr in entries) {
                val parts = entryStr.trim().split("\\s+".toRegex())
                if (parts.size < 2) {
                    continue
                }
                val key = parts[0]
                val surface = parts[1]
                var posId = 0
                var cost = 500
                if (parts.size >= 3) {
                    try {
                        posId = parts[2].toInt()
                    } catch (_: NumberFormatException) {
                    }
                }
                if (parts.size >= 4) {
                    try {
                        cost = parts[3].toInt()
                    } catch (_: NumberFormatException) {
                    }
                }

                val list = map.computeIfAbsent(key) { ArrayList() }
                list.removeIf { e -> e.surface == surface }
                list.add(LearningDictEntry(surface, posId, cost))
            }

            for ((k, v) in map) {
                val blob = serializeEntries(v)
                saveOrUpdateEntry(db, k, blob)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addEntry(key: String, surface: String, posId: Int, cost: Int) {
        val db = mDbLearningDict
        if (db == null || !db.isOpen) {
            Log.e("LearningDictionary", "Learning dictionary is not available for addEntry")
            return;
        }
        logI("addEntry: key=$key, surface=$surface, posId=$posId, cost=$cost")

        val entry = getLearningDictEntry(key)
        val list: MutableList<LearningDictEntry>
        if (entry == null) {
            list = ArrayList()
            list.add(LearningDictEntry(surface, posId, cost))
        } else {
            list = entry.entries ?: ArrayList()
            list.removeIf { e -> e.surface == surface }
            list.add(0, LearningDictEntry(surface, posId, cost))
        }

        val blob = serializeEntries(list)
        saveOrUpdateEntry(db, key, blob)
    }

    fun removeEntry(key: String, surface: String) {
        val entry = getLearningDictEntry(key) ?: return
        val list = entry.entries ?: return

        list.removeIf { e -> e.surface == surface }

        if (list.isEmpty()) {
            deleteEntry(key)
        } else {
            val blob = serializeEntries(list)
            saveOrUpdateEntry(mDbLearningDict, key, blob)
        }
    }

    fun deleteEntry(key: String) {
        val db = mDbLearningDict
        if (db == null || !db.isOpen) return
        try {
            db.execSQL("DELETE FROM dictionary WHERE `key` = ?", arrayOf<Any>(key))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        val db = mDbLearningDict
        if (db != null && db.isOpen) {
            try {
                db.close()
            } catch (_: Exception) {
            }
            mDbLearningDict = null
        }
    }

    class Record(
        @JvmField val key: String,
        @JvmField val surface: String,
        @JvmField val posId: Int,
        @JvmField val cost: Int
    )

    fun getAllRecords(): List<Record> {
        val list = ArrayList<Record>()
        val db = mDbLearningDict
        if (db == null || !db.isOpen) return list
        try {
            db.rawQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val blob = cursor.getBlob(1)
                    val entries = deserializeEntries(blob)
                    if (entries.isEmpty() && blob != null && blob.isNotEmpty()) {
                        deleteEntry(key)
                        continue
                    }
                    for (e in entries) {
                        list.add(Record(key, e.surface, e.posId, e.cost))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    class LearningDictEntry(
        @JvmField val surface: String,
        @JvmField val posId: Int,
        @JvmField val cost: Int
    )

    open class Entry(
        @JvmField var entries: MutableList<LearningDictEntry>?
    )
}
