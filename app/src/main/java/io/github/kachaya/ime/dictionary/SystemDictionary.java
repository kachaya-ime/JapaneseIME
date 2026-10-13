package io.github.kachaya.ime.dictionary;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import io.github.kachaya.ime.AssetLoader;
import io.github.kachaya.ime.engine.RomajiConverter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * アセットから展開される読み取り専用の「システム辞書」を管理・検索するクラスです。
 */
public class SystemDictionary {

    private static final String SYSTEM_DICT = "system_dict";

    private final Context mContext;
    private final String mFilesDirPath;
    private SQLiteDatabase mDbMainDict;

    public SystemDictionary(Context context) {
        mContext = context;
        mFilesDirPath = context != null ? context.getFilesDir().getAbsolutePath() : "";
        try {
            copySystemDict(context);
            String path = mFilesDirPath + "/" + SYSTEM_DICT + ".db";
            mDbMainDict = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            Log.e("SystemDictionary", "MainDictionary: " + e.getMessage());
            mDbMainDict = null;
        }
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * アセットから、システム辞書の DB ファイル（system_dict.db.zip）を内部ストレージへ展開します。
     */
    private void copySystemDict(Context context) {
        if (context == null) return;
        String dbFileName = mFilesDirPath + "/" + SYSTEM_DICT + ".db";
        File dbFile = new File(dbFileName);

        String checksumFileName = mFilesDirPath + "/" + SYSTEM_DICT + "_checksum.txt";
        File checksumFile = new File(checksumFileName);

        String assetChecksum = AssetLoader.loadAssetString(context, "dictionary/dict_checksum.txt");
        if (assetChecksum != null) {
            assetChecksum = assetChecksum.trim();
        }

        if (dbFile.exists() && dbFile.length() > 0 && checksumFile.exists() && assetChecksum != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(checksumFile), StandardCharsets.UTF_8))) {
                String localChecksum = reader.readLine();
                if (localChecksum != null && localChecksum.trim().equals(assetChecksum)) {
                    return; // チェックサム一致のためスキップ
                }
            } catch (Exception ignored) {
            }
        }

        boolean extracted = false;
        try (InputStream zipAssetStream = context.getAssets().open("dictionary/system_dict.db.zip");
             ZipInputStream zis = new ZipInputStream(zipAssetStream);
             OutputStream os = new FileOutputStream(dbFile)) {
            ZipEntry entry = zis.getNextEntry();
            if (entry != null) {
                byte[] buf = new byte[16 * 1024];
                int len;
                while ((len = zis.read(buf)) > 0) {
                    os.write(buf, 0, len);
                }
                os.flush();
                extracted = true;
            }
        } catch (IOException e) {
            Log.e("SystemDictionary", "Failed to extract system_dict.db.zip", e);
        }

        if (extracted && assetChecksum != null) {
            try (FileOutputStream fos = new FileOutputStream(checksumFile)) {
                fos.write(assetChecksum.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * システム辞書 (system_dict.db) から指定された見出し語（読み）に対応する候補（表層形）のリストを取得します。
     */
    public List<String> getSystemDictCandidates(String key) {
        if (mDbMainDict == null || !mDbMainDict.isOpen() || key == null || key.isEmpty()) {
            return Collections.emptyList();
        }
        try (Cursor cursor = mDbMainDict.rawQuery("SELECT `value` FROM dictionary WHERE `key` = ?", new String[]{key})) {
            if (cursor.moveToFirst()) {
                byte[] blob = cursor.getBlob(0);
                List<SystemDictEntry> entries = deserializeSystemDictBlob(blob);
                List<String> surfaces = new ArrayList<>(entries.size());
                for (SystemDictEntry e : entries) {
                    if (e.surface != null && !e.surface.isEmpty()) {
                        surfaces.add(e.surface);
                    }
                }
                return surfaces;
            }
        } catch (Exception e) {
            Log.e("SystemDictionary", "Error reading system dict for key: " + key, e);
        }
        return Collections.emptyList();
    }

    /**
     * システム辞書 (system_dict.db) から指定された見出し語（読み）に対応する詳細エントリのリストを取得します。
     */
    public List<SystemDictEntry> getSystemDictEntries(String key) {
        if (mDbMainDict == null || !mDbMainDict.isOpen() || key == null || key.isEmpty()) {
            return Collections.emptyList();
        }
        try (Cursor cursor = mDbMainDict.rawQuery("SELECT `value` FROM dictionary WHERE `key` = ?", new String[]{key})) {
            if (cursor.moveToFirst()) {
                byte[] blob = cursor.getBlob(0);
                return deserializeSystemDictBlob(blob);
            }
        } catch (Exception e) {
            Log.e("SystemDictionary", "Error reading system dict entries for key: " + key, e);
        }
        return Collections.emptyList();
    }

    /**
     * システム辞書 (system_dict.db) から指定された見出し語（読み）に対応する WordEntry のリストを取得します。
     */
    public List<WordEntry> getSystemDictWordEntries(String key) {
        List<SystemDictEntry> list = getSystemDictEntries(key);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        List<WordEntry> wordEntries = new ArrayList<>(list.size());
        for (SystemDictEntry e : list) {
            WordEntry we = new WordEntry(key, e.surface, e.posId, e.cost);
            we.type = Candidate.Type.SYSTEM;
            wordEntries.add(we);
        }
        return wordEntries;
    }

    /**
     * 指定された読みと表記に対応するシステム辞書の品詞 ID を取得します。
     */
    public int findSystemDictId(String key, String surface) {
        List<SystemDictEntry> entries = getSystemDictEntries(key);
        for (SystemDictEntry e : entries) {
            if (e.surface.equals(surface)) {
                return e.posId;
            }
        }
        return 0;
    }

    /**
     * 2つの品詞 ID 間の接続コストを取得します。
     */
    public int getConnectionCost(int prevPosId, int posId) {
        if (prevPosId == 0 || posId == 0) {
            return 100;
        }
        return 200;
    }

    /**
     * 前方一致でキーワードを検索します。
     */
    public List<String> findKeys(String key, int limit) {
        if (mDbMainDict == null || !mDbMainDict.isOpen() || key == null || key.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        String upperBound = key.substring(0, key.length() - 1) + (char) (key.charAt(key.length() - 1) + 1);
        try (Cursor cursor = mDbMainDict.rawQuery(
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

    public String[] getMainDictCandidates(String key) {
        List<String> cands = getSystemDictCandidates(key);
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        return cands.toArray(new String[0]);
    }

    public synchronized void close() {
        if (mDbMainDict != null && mDbMainDict.isOpen()) {
            try {
                mDbMainDict.close();
            } catch (Exception ignored) {
            }
            mDbMainDict = null;
        }
    }

    private String[] mPosArray;

    private synchronized void loadPosArray(Context context) {
        if (context == null || mPosArray != null) return;
        try (InputStream is = context.getAssets().open("dictionary/pos_id.bin");
             DataInputStream dis = new DataInputStream(is)) {
            int size = dis.readShort() & 0xFFFF;
            mPosArray = new String[size];
            for (int i = 0; i < size; i++) {
                mPosArray[i] = dis.readUTF();
            }
        } catch (Exception e) {
            Log.e("SystemDictionary", "Failed to load pos_id.bin", e);
            mPosArray = new String[0];
        }
    }

    public String getPosString(int posId) {
        loadPosArray(mContext);
        if (mPosArray != null && posId >= 0 && posId < mPosArray.length) {
            String s = mPosArray[posId];
            return s != null && !s.isEmpty() ? s : String.valueOf(posId);
        }
        return String.valueOf(posId);
    }

    public static class SystemDictEntry {
        public final String surface;
        public final int posId;
        public final int cost;

        public SystemDictEntry(String surface, int posId, int cost) {
            this.surface = surface;
            this.posId = posId;
            this.cost = cost;
        }
    }

    private static List<SystemDictEntry> deserializeSystemDictBlob(byte[] data) {
        List<SystemDictEntry> entries = new ArrayList<>();
        if (data == null || data.length == 0) return entries;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String surface = dis.readUTF();
                int posId = dis.readShort() & 0xFFFF;
                int cost = dis.readShort();
                entries.add(new SystemDictEntry(surface, posId, cost));
            }
        } catch (Exception e) {
            Log.e("SystemDictionary", "Failed to deserialize system dict blob", e);
        }
        return entries;
    }
}
