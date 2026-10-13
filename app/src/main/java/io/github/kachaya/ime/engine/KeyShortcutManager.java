package io.github.kachaya.ime.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * キーショートカット（Ctrl+アルファベット、Shift+特殊キー等）のカスタマイズ設定を管理するクラスです。
 */
public class KeyShortcutManager {

    public static final String PREF_KEY = "ctrl_shortcuts_config";
    public static final int SHIFT_MASK = 0x10000;

    /**
     * システムで予約されている Ctrl キー組み合わせ（Ctrl+A, Ctrl+C など）かどうかを判定します。
     *
     * @param keyCode キーコード
     * @return システム予約されている場合は true
     */
    public static boolean isSystemReserved(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_V:
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_Y:
            case KeyEvent.KEYCODE_Z:
                return true;
            default:
                return false;
        }
    }

    /**
     * システム予約キーのラベルを取得します。
     *
     * @param keyCode キーコード
     * @return システム予約機能の説明ラベル
     */
    public static String getReservedLabel(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                return "システム予約 (全選択)";
            case KeyEvent.KEYCODE_C:
                return "システム予約 (コピー)";
            case KeyEvent.KEYCODE_V:
                return "システム予約 (貼り付け)";
            case KeyEvent.KEYCODE_X:
                return "システム予約 (切り取り)";
            case KeyEvent.KEYCODE_Y:
                return "システム予約 (やり直し)";
            case KeyEvent.KEYCODE_Z:
                return "システム予約 (元に戻す)";
            default:
                return "";
        }
    }

    /**
     * デフォルトのキー割り当てマップを取得します（システム予約キー以外はすべて未設定）。
     *
     * @return キーコードと InputAction のデフォルトマップ
     */
    public static Map<Integer, InputAction> getDefaultMappings() {
        return new HashMap<>();
    }

    /**
     * 設定から現在のショートカット割り当てマップを読み込みます。
     *
     * @param context コンテキスト
     * @return キーコードと InputAction のマップ
     */
    public static Map<Integer, InputAction> loadMappings(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return loadMappings(prefs);
    }

    /**
     * SharedPreferences から現在のショートカット割り当てマップを読み込みます。
     *
     * @param prefs SharedPreferences
     * @return キーコードと InputAction のマップ
     */
    public static Map<Integer, InputAction> loadMappings(SharedPreferences prefs) {
        Map<Integer, InputAction> mappings = getDefaultMappings();
        String jsonStr = prefs.getString(PREF_KEY, null);
        if (jsonStr == null || jsonStr.isEmpty()) {
            return mappings;
        }
        try {
            JSONObject json = new JSONObject(jsonStr);
            for (int keyCode = KeyEvent.KEYCODE_A; keyCode <= KeyEvent.KEYCODE_Z; keyCode++) {
                if (isSystemReserved(keyCode)) {
                    continue;
                }
                char letter = (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
                String keyStr = String.valueOf(letter);
                if (json.has(keyStr)) {
                    String actionId = json.getString(keyStr);
                    mappings.put(keyCode, InputAction.fromId(actionId));
                }
            }
            if (json.has("SPACE")) {
                mappings.put(KeyEvent.KEYCODE_SPACE, InputAction.fromId(json.getString("SPACE")));
            }
            if (json.has("ENTER")) {
                mappings.put(KeyEvent.KEYCODE_ENTER, InputAction.fromId(json.getString("ENTER")));
            }
            if (json.has("TAB")) {
                mappings.put(KeyEvent.KEYCODE_TAB, InputAction.fromId(json.getString("TAB")));
            }
            if (json.has("DEL")) {
                mappings.put(KeyEvent.KEYCODE_DEL, InputAction.fromId(json.getString("DEL")));
            }
            if (json.has("SHIFT_SPACE")) {
                mappings.put(KeyEvent.KEYCODE_SPACE | SHIFT_MASK, InputAction.fromId(json.getString("SHIFT_SPACE")));
            }
            if (json.has("SHIFT_ENTER")) {
                mappings.put(KeyEvent.KEYCODE_ENTER | SHIFT_MASK, InputAction.fromId(json.getString("SHIFT_ENTER")));
            }
            if (json.has("SHIFT_TAB")) {
                mappings.put(KeyEvent.KEYCODE_TAB | SHIFT_MASK, InputAction.fromId(json.getString("SHIFT_TAB")));
            }
            if (json.has("SHIFT_DEL")) {
                mappings.put(KeyEvent.KEYCODE_DEL | SHIFT_MASK, InputAction.fromId(json.getString("SHIFT_DEL")));
            }
        } catch (Exception ignored) {
        }
        return mappings;
    }

    /**
     * ショートカット割り当てマップを設定に保存します。
     *
     * @param context  コンテキスト
     * @param mappings キーコードと InputAction のマップ
     */
    public static void saveMappings(Context context, Map<Integer, InputAction> mappings) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        saveMappings(prefs, mappings);
    }

    /**
     * ショートカット割り当てマップを SharedPreferences に保存します。
     *
     * @param prefs    SharedPreferences
     * @param mappings キーコードと InputAction のマップ
     */
    public static void saveMappings(SharedPreferences prefs, Map<Integer, InputAction> mappings) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<Integer, InputAction> entry : mappings.entrySet()) {
                int code = entry.getKey();
                if (isSystemReserved(code)) {
                    continue;
                }
                if (code == KeyEvent.KEYCODE_SPACE) {
                    json.put("SPACE", entry.getValue().getId());
                } else if (code == KeyEvent.KEYCODE_ENTER) {
                    json.put("ENTER", entry.getValue().getId());
                } else if (code == KeyEvent.KEYCODE_TAB) {
                    json.put("TAB", entry.getValue().getId());
                } else if (code == KeyEvent.KEYCODE_DEL) {
                    json.put("DEL", entry.getValue().getId());
                } else if (code == (KeyEvent.KEYCODE_SPACE | SHIFT_MASK)) {
                    json.put("SHIFT_SPACE", entry.getValue().getId());
                } else if (code == (KeyEvent.KEYCODE_ENTER | SHIFT_MASK)) {
                    json.put("SHIFT_ENTER", entry.getValue().getId());
                } else if (code == (KeyEvent.KEYCODE_TAB | SHIFT_MASK)) {
                    json.put("SHIFT_TAB", entry.getValue().getId());
                } else if (code == (KeyEvent.KEYCODE_DEL | SHIFT_MASK)) {
                    json.put("SHIFT_DEL", entry.getValue().getId());
                } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
                    char letter = (char) ('A' + code - KeyEvent.KEYCODE_A);
                    json.put(String.valueOf(letter), entry.getValue().getId());
                }
            }
            prefs.edit().putString(PREF_KEY, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * キーショートカットの設定を初期状態にリセットします。
     *
     * @param context コンテキスト
     */
    public static void resetToDefaults(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(PREF_KEY).apply();
    }
}
