package io.github.kachaya.ime.dictionary;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import io.github.kachaya.ime.BuildConfig;
import io.github.kachaya.ime.engine.RomajiConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ユーザーの登録・単語学習内容を保持する「学習辞書」をバイナリ形式（surface, posId, cost）のBLOB配列として管理・検索するクラスです。
 */
public class LearningDictionary {

    private static final String LEARNING_DICT = "learning_dict";
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS dictionary (`key` TEXT PRIMARY KEY, `value` BLOB NOT NULL) WITHOUT ROWID;";

    private final String mFilesDirPath;
    private SQLiteDatabase mDbLearningDict;

    private void logI(String msg) {
        if (BuildConfig.DEBUG) {
            Log.i(getClass().getSimpleName(), msg);
        }
    }

    private SQLiteDatabase openOrCreateDictDb(String path) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL(CREATE_TABLE_SQL);
        return db;
    }

    public LearningDictionary(Context context) {
        mFilesDirPath = context != null ? context.getFilesDir().getAbsolutePath() : "";
        try {
            String path = mFilesDirPath + "/" + LEARNING_DICT + ".db";
            mDbLearningDict = openOrCreateDictDb(path);
        } catch (Exception e) {
            Log.e("LearningDictionary", "Failed to open learning dictionary: " + e.getMessage());
            mDbLearningDict = null;
        }
    }

    private byte[] getBlobFromDb(SQLiteDatabase db, String key) {
        if (db == null || !db.isOpen() || key == null) return null;
        try (Cursor cursor = db.rawQuery("SELECT value FROM dictionary WHERE `key` = ?", new String[]{key})) {
            if (cursor.moveToFirst()) {
                return cursor.getBlob(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] serializeEntries(List<LearningDictEntry> entries) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(entries.size());
            for (LearningDictEntry e : entries) {
                dos.writeUTF(e.surface != null ? e.surface : "");
                dos.writeShort(e.posId);
                dos.writeShort(e.cost);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e("LearningDictionary", "Failed to serialize learning entries", e);
            return new byte[0];
        }
    }

    private static List<LearningDictEntry> deserializeEntries(byte[] data) {
        List<LearningDictEntry> entries = new ArrayList<>();
        if (data == null || data.length == 0) return entries;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                try {
                    String surface = dis.readUTF();
                    int posId = dis.readShort() & 0xFFFF;
                    int cost = dis.readShort();
                    entries.add(new LearningDictEntry(surface, posId, cost));
                } catch (java.io.EOFException eof) {
                    Log.w("LearningDictionary", "Reached EOF while deserializing entry " + i + " of " + count, eof);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e("LearningDictionary", "Failed to deserialize learning entries", e);
        }
        return entries;
    }

    public List<String> findKeys(String key, int limit) {
        if (mDbLearningDict == null || !mDbLearningDict.isOpen() || key == null || key.isEmpty()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        String upperBound = key.substring(0, key.length() - 1) + (char) (key.charAt(key.length() - 1) + 1);
        try (Cursor cursor = mDbLearningDict.rawQuery(
                "SELECT `key` FROM dictionary WHERE `key` >= ? AND `key` < ? ORDER BY `key` ASC",
                new String[]{key, upperBound})) {
            while (cursor.moveToNext() && list.size() < limit) {
                String str = cursor.getString(0);
                if (RomajiConverter.isAlphabet(str.charAt(str.length() - 1)) && !RomajiConverter.isAlphabet(str.charAt(0))) {
                    continue;
                }
                list.add(str);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public Entry getLearningDictEntry(String key) {
        byte[] blob = getBlobFromDb(mDbLearningDict, key);
        if (blob == null) {
            return null;
        }
        List<LearningDictEntry> entries = deserializeEntries(blob);
        if (entries.isEmpty() && blob.length > 0) {
            deleteEntry(key);
            return null;
        }
        return new Entry(entries);
    }

    public List<WordEntry> getLearningDictEntries(String key) {
        Entry entry = getLearningDictEntry(key);
        if (entry == null && key != null && !key.equals(key.toLowerCase(java.util.Locale.ENGLISH))) {
            entry = getLearningDictEntry(key.toLowerCase(java.util.Locale.ENGLISH));
        }
        if (entry == null || entry.entries == null || entry.entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<WordEntry> list = new ArrayList<>();
        for (LearningDictEntry le : entry.entries) {
            int effectiveCost = Math.min(le.cost, 100);
            WordEntry we = new WordEntry(key, le.surface, le.posId, effectiveCost);
            we.type = Candidate.Type.LEARNING;
            list.add(we);
        }
        return list;
    }

    public String getLearningDictionaryName() {
        return LEARNING_DICT;
    }

    public synchronized void clearLearningDictionary() {
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) return;
        try {
            mDbLearningDict.execSQL("DELETE FROM dictionary;");
            Log.d("LearningDictionary", "Cleared all entries from learning dictionary.");
        } catch (Exception e) {
            Log.e("LearningDictionary", "Failed to clear learning dictionary", e);
            throw new RuntimeException("Failed to clear learning dictionary", e);
        }
    }

    public synchronized void reloadLearningDictionary() {
        try {
            if (mDbLearningDict != null && mDbLearningDict.isOpen()) {
                try {
                    mDbLearningDict.close();
                } catch (Exception ignored) {
                }
                mDbLearningDict = null;
            }
            String path = mFilesDirPath + "/" + LEARNING_DICT + ".db";
            mDbLearningDict = openOrCreateDictDb(path);
        } catch (Exception e) {
            Log.e("LearningDictionary", "Failed to reload learning dictionary: " + e.getMessage());
            mDbLearningDict = null;
        }
    }

    public List<String> exportLearningDictionary() {
        List<String> list = new ArrayList<>();
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) return list;
        try (Cursor cursor = mDbLearningDict.rawQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC", null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                byte[] blob = cursor.getBlob(1);
                List<LearningDictEntry> entries = deserializeEntries(blob);
                if (entries.isEmpty() && blob != null && blob.length > 0) {
                    deleteEntry(key);
                    continue;
                }
                for (LearningDictEntry e : entries) {
                    list.add(key + " " + e.surface + " " + e.posId + " " + e.cost);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void saveOrUpdateEntry(SQLiteDatabase db, String key, byte[] value) {
        if (db == null || !db.isOpen()) return;
        try {
            db.execSQL("INSERT OR REPLACE INTO dictionary (`key`, `value`) VALUES (?, ?)", new Object[]{key, value});
        } catch (Exception ignored) {
        }
    }

    public void importLearningDictionary(List<String> entries) {
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) return;
        mDbLearningDict.beginTransaction();
        try {
            Map<String, List<LearningDictEntry>> map = new LinkedHashMap<>();
            for (String entryStr : entries) {
                String[] parts = entryStr.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                String key = parts[0];
                String surface = parts[1];
                int posId = 0;
                int cost = 500;
                if (parts.length >= 3) {
                    try {
                        posId = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 4) {
                    try {
                        cost = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {}
                }

                List<LearningDictEntry> list = map.computeIfAbsent(key, k -> new ArrayList<>());
                list.removeIf(e -> e.surface.equals(surface));
                list.add(new LearningDictEntry(surface, posId, cost));
            }

            for (Map.Entry<String, List<LearningDictEntry>> entry : map.entrySet()) {
                byte[] blob = serializeEntries(entry.getValue());
                saveOrUpdateEntry(mDbLearningDict, entry.getKey(), blob);
            }

            mDbLearningDict.setTransactionSuccessful();
        } finally {
            mDbLearningDict.endTransaction();
        }
    }

    public void addEntry(String key, String surface, int posId, int cost) {
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) {
            Log.e("LearningDictionary", "Learning dictionary is not available for addEntry");
            return;
        }
        logI("addEntry: key=" + key + ", surface=" + surface + ", posId=" + posId + ", cost=" + cost);

        Entry entry = getLearningDictEntry(key);
        List<LearningDictEntry> list;
        if (entry == null) {
            list = new ArrayList<>();
            list.add(new LearningDictEntry(surface, posId, cost));
        } else {
            list = entry.entries;
            list.removeIf(e -> e.surface.equals(surface));
            list.add(0, new LearningDictEntry(surface, posId, cost));
        }

        byte[] blob = serializeEntries(list);
        saveOrUpdateEntry(mDbLearningDict, key, blob);
    }

    public void removeEntry(String key, String surface) {
        Entry entry = getLearningDictEntry(key);
        if (entry == null) {
            return;
        }

        entry.entries.removeIf(e -> e.surface.equals(surface));

        if (entry.entries.isEmpty()) {
            deleteEntry(key);
        } else {
            byte[] blob = serializeEntries(entry.entries);
            saveOrUpdateEntry(mDbLearningDict, key, blob);
        }
    }

    public void deleteEntry(String key) {
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) return;
        try {
            mDbLearningDict.execSQL("DELETE FROM dictionary WHERE `key` = ?", new Object[]{key});
        } catch (Exception ignored) {
        }
    }

    public synchronized void close() {
        if (mDbLearningDict != null && mDbLearningDict.isOpen()) {
            try {
                mDbLearningDict.close();
            } catch (Exception ignored) {
            }
            mDbLearningDict = null;
        }
    }

    public static class Record {
        public final String key;
        public final String surface;
        public final int posId;
        public final int cost;

        public Record(String key, String surface, int posId, int cost) {
            this.key = key;
            this.surface = surface;
            this.posId = posId;
            this.cost = cost;
        }
    }

    public List<Record> getAllRecords() {
        List<Record> list = new ArrayList<>();
        if (mDbLearningDict == null || !mDbLearningDict.isOpen()) return list;
        try (Cursor cursor = mDbLearningDict.rawQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC", null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                byte[] blob = cursor.getBlob(1);
                List<LearningDictEntry> entries = deserializeEntries(blob);
                if (entries.isEmpty() && blob != null && blob.length > 0) {
                    deleteEntry(key);
                    continue;
                }
                for (LearningDictEntry e : entries) {
                    list.add(new Record(key, e.surface, e.posId, e.cost));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static class LearningDictEntry {
        public final String surface;
        public final int posId;
        public final int cost;

        public LearningDictEntry(String surface, int posId, int cost) {
            this.surface = surface;
            this.posId = posId;
            this.cost = cost;
        }
    }

    public static class Entry {
        public List<LearningDictEntry> entries;

        Entry(List<LearningDictEntry> e) {
            entries = e;
        }
    }
}
