package io.github.kachaya.ime.dictionary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.kachaya.ime.AssetLoader
import io.github.kachaya.ime.engine.RomajiConverter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * アセットから展開される読み取り専用の「システム辞書」を管理・検索するクラスです。
 */
class SystemDictionary(val context: Context?) {

    companion object {
        private const val SYSTEM_DICT = "system_dict"

        private fun deserializeSystemDictBlob(data: ByteArray?): List<SystemDictEntry> {
            val entries = ArrayList<SystemDictEntry>()
            if (data == null || data.isEmpty()) return entries
            try {
                DataInputStream(ByteArrayInputStream(data)).use { dis ->
                    val count = dis.readInt()
                    for (i in 0..<count) {
                        val surface = dis.readUTF()
                        val posId = dis.readShort().toInt() and 0xFFFF
                        val cost = dis.readShort().toInt()
                        entries.add(SystemDictEntry(surface, posId, cost))
                    }
                }
            } catch (e: Exception) {
                Log.e("SystemDictionary", "Failed to deserialize system dict blob", e)
            }
            return entries
        }
    }

    private val mFilesDirPath: String = context?.filesDir?.absolutePath ?: ""
    private var mDbMainDict: SQLiteDatabase? = null
    private var mPosArray: Array<String>? = null

    init {
        try {
            copySystemDict(context)
            val path = "$mFilesDirPath/$SYSTEM_DICT.db"
            mDbMainDict = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e("SystemDictionary", "MainDictionary: ${e.message}")
            mDbMainDict = null
        }
    }

    private fun copySystemDict(context: Context?) {
        if (context == null) return
        val dbFileName = "$mFilesDirPath/$SYSTEM_DICT.db"
        val dbFile = File(dbFileName)

        val checksumFileName = "$mFilesDirPath/${SYSTEM_DICT}_checksum.txt"
        val checksumFile = File(checksumFileName)

        var assetChecksum = AssetLoader.loadAssetString(context, "dictionary/dict_checksum.txt")
        if (assetChecksum != null) {
            assetChecksum = assetChecksum.trim()
        }

        if (dbFile.exists() && dbFile.length() > 0 && checksumFile.exists() && assetChecksum != null) {
            try {
                BufferedReader(InputStreamReader(FileInputStream(checksumFile), StandardCharsets.UTF_8)).use { reader ->
                    val localChecksum = reader.readLine()
                    if (localChecksum != null && localChecksum.trim() == assetChecksum) {
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }

        var extracted = false
        try {
            context.assets.open("dictionary/system_dict.db.zip").use { zipAssetStream ->
                ZipInputStream(zipAssetStream).use { zis ->
                    FileOutputStream(dbFile).use { os ->
                        val entry = zis.nextEntry
                        if (entry != null) {
                            val buf = ByteArray(16 * 1024)
                            var len: Int
                            while (zis.read(buf).also { len = it } > 0) {
                                os.write(buf, 0, len)
                            }
                            os.flush()
                            extracted = true
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("SystemDictionary", "Failed to extract system_dict.db.zip", e)
        }

        if (extracted && assetChecksum != null) {
            try {
                FileOutputStream(checksumFile).use { fos ->
                    fos.write(assetChecksum.toByteArray(StandardCharsets.UTF_8))
                }
            } catch (_: IOException) {
            }
        }
    }

    fun getSystemDictCandidates(key: String?): List<String> {
        val db = mDbMainDict
        if (db == null || !db.isOpen || key.isNullOrEmpty()) {
            return emptyList()
        }
        try {
            db.rawQuery("SELECT `value` FROM dictionary WHERE `key` = ?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0)
                    val entries = deserializeSystemDictBlob(blob)
                    val surfaces = ArrayList<String>(entries.size)
                    for (e in entries) {
                        if (!e.surface.isNullEmpty()) {
                            surfaces.add(e.surface)
                        }
                    }
                    return surfaces
                }
            }
        } catch (e: Exception) {
            Log.e("SystemDictionary", "Error reading system dict for key: $key", e)
        }
        return emptyList()
    }

    fun getSystemDictEntries(key: String?): List<SystemDictEntry> {
        val db = mDbMainDict
        if (db == null || !db.isOpen || key.isNullOrEmpty()) {
            return emptyList()
        }
        try {
            db.rawQuery("SELECT `value` FROM dictionary WHERE `key` = ?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0)
                    return deserializeSystemDictBlob(blob)
                }
            }
        } catch (e: Exception) {
            Log.e("SystemDictionary", "Error reading system dict entries for key: $key", e)
        }
        return emptyList()
    }

    fun getSystemDictWordEntries(key: String?): List<WordEntry> {
        if (key.isNullOrEmpty()) return emptyList()
        val list = getSystemDictEntries(key)
        if (list.isEmpty()) {
            return emptyList()
        }
        val wordEntries = ArrayList<WordEntry>(list.size)
        for (e in list) {
            val we = WordEntry(key, e.surface, e.posId, e.cost)
            we.type = Candidate.Type.SYSTEM
            wordEntries.add(we)
        }
        return wordEntries
    }

    fun findSystemDictId(key: String?, surface: String?): Int {
        val entries = getSystemDictEntries(key)
        for (e in entries) {
            if (e.surface == surface) {
                return e.posId
            }
        }
        return 0
    }

    fun getConnectionCost(prevPosId: Int, posId: Int): Int {
        if (prevPosId == 0 || posId == 0) {
            return 100
        }
        return 200
    }

    fun findKeys(key: String?, limit: Int): List<String> {
        val db = mDbMainDict
        if (db == null || !db.isOpen || key.isNullOrEmpty()) {
            return emptyList()
        }
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

    fun getMainDictCandidates(key: String?): Array<String>? {
        val cands = getSystemDictCandidates(key)
        if (cands.isEmpty()) {
            return null
        }
        return cands.toTypedArray()
    }

    @Synchronized
    fun close() {
        val db = mDbMainDict
        if (db != null && db.isOpen) {
            try {
                db.close()
            } catch (_: Exception) {
            }
            mDbMainDict = null
        }
    }

    @Synchronized
    private fun loadPosArray(context: Context?) {
        if (context == null || mPosArray != null) return
        try {
            context.assets.open("dictionary/pos_id.bin").use { `is` ->
                DataInputStream(`is`).use { dis ->
                    val size = dis.readShort().toInt() and 0xFFFF
                    val array = Array(size) { "" }
                    for (i in 0..<size) {
                        array[i] = dis.readUTF()
                    }
                    mPosArray = array
                }
            }
        } catch (e: Exception) {
            Log.e("SystemDictionary", "Failed to load pos_id.bin", e)
            mPosArray = emptyArray()
        }
    }

    fun getPosString(posId: Int): String {
        loadPosArray(context)
        val array = mPosArray
        if (array != null && posId in array.indices) {
            val s = array[posId]
            return if (s.isNotEmpty()) s else posId.toString()
        }
        return posId.toString()
    }

    open class SystemDictEntry(
        val surface: String,
        val posId: Int,
        val cost: Int
    )

    private fun String?.isNullEmpty(): Boolean = this.isNullOrEmpty()
}
