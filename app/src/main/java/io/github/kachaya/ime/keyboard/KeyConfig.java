package io.github.kachaya.ime.keyboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1つのキーの定義（ラベルとウェイト）を保持するクラスです。
 * 通常・シフト・記号などの各レイアウトは、このオブジェクトの独立したリストとして構成されます。
 */
public class KeyConfig {

    // --- Functional Codes ---
    public static final int CODE_NONE = 0;
    public static final int CODE_SHIFT = 1;
    public static final int CODE_ENTER = 2;
    public static final int CODE_BACKSPACE = 3;
    public static final int CODE_SPACE = 4;
    public static final int CODE_SYM = 5;
    public static final int CODE_CTRL = 6;
    public static final int CODE_TAB = 7;
    public static final int CODE_LEFT = 8;
    public static final int CODE_RIGHT = 9;
    public static final int CODE_UP = 10;
    public static final int CODE_DOWN = 11;
    public static final int CODE_GAP = 12;
    public static final int CODE_ABC = 13;
    public static final int CODE_SHORTCUT = 14; // Graffitiのショートカット
    public static final int CODE_CANCEL = 15;   // Graffitiのキャンセル、右下→左上
    public static final int CODE_8_2_8 = 16;    // Graffitiでは未定義、左下→右上→左下
    public static final int CODE_10_4_10 = 17;  // Graffitiでは未定義、左上→右下→左上
    public static final int CODE_ESC = 18;
    public static final int CODE_MODE = 19;

    /** 機能キーの定義情報（コード、文字列識別名、デフォルト表示ラベル） */
    private static class KeyEntry {
        final int code;
        final String name;
        final String defaultLabel;

        KeyEntry(int code, String name, String defaultLabel) {
            this.code = code;
            this.name = name;
            this.defaultLabel = defaultLabel;
        }
    }

    /**
     * 機能キーのマッピングテーブル。
     * 新しい機能キーを追加する場合は、このテーブルに1行追加するだけで自動的に対応されます。
     */
    private static final KeyEntry[] KEY_TABLE = new KeyEntry[]{
            new KeyEntry(CODE_SHIFT,     "SHIFT",     "⇧"),
            new KeyEntry(CODE_ENTER,     "ENTER",     "⏎"),
            new KeyEntry(CODE_BACKSPACE, "BACKSPACE", "⌫"),
            new KeyEntry(CODE_SPACE,     "SPACE",     "⌴"),
            new KeyEntry(CODE_SYM,       "SYM",       "@!?"),
            new KeyEntry(CODE_CTRL,      "CTRL",      "Ctrl"),
            new KeyEntry(CODE_TAB,       "TAB",       "Tab"),
            new KeyEntry(CODE_LEFT,      "LEFT",      "◂"),
            new KeyEntry(CODE_RIGHT,     "RIGHT",     "▸"),
            new KeyEntry(CODE_UP,        "UP",        "▴"),
            new KeyEntry(CODE_DOWN,      "DOWN",      "▾"),
            new KeyEntry(CODE_GAP,       "GAP",       "Gap"),
            new KeyEntry(CODE_ABC,       "ABC",       "abc"),
            new KeyEntry(CODE_ESC,       "ESC",       "Esc"),
            new KeyEntry(CODE_MODE,      "MODE",      "あ/A"),
    };

    private static final Map<Integer, KeyEntry> CODE_TO_ENTRY_MAP = new HashMap<>();
    private static final Map<String, Integer> STRING_TO_CODE_MAP = new HashMap<>();

    static {
        for (KeyEntry entry : KEY_TABLE) {
            CODE_TO_ENTRY_MAP.put(entry.code, entry);
            STRING_TO_CODE_MAP.put(entry.name.toUpperCase(), entry.code);
        }
        STRING_TO_CODE_MAP.put("TOGGLE_EN_JP", CODE_MODE);
        STRING_TO_CODE_MAP.put("EN_JP", CODE_MODE);
    }

    /** ファイル保存用の識別名に変換します */
    public static String codeToString(int code) {
        KeyEntry entry = CODE_TO_ENTRY_MAP.get(code);
        return entry != null ? entry.name : null;
    }

    /** 識別名からコードに変換します（大文字小文字を区別しません） */
    public static int stringToCode(String s) {
        if (s == null || s.isEmpty()) {
            return CODE_NONE;
        }
        Integer code = STRING_TO_CODE_MAP.get(s.toUpperCase());
        return code != null ? code : CODE_NONE;
    }

    /** 各機能のデフォルトの表示ラベルを返します */
    public static String getDefaultLabel(int code) {
        KeyEntry entry = CODE_TO_ENTRY_MAP.get(code);
        return entry != null ? entry.defaultLabel : "";
    }

    public String label;
    public float weight;
    public int code;

    /** 文字キーのコンストラクタ（ウェイト 1.0） */
    public KeyConfig(String label) {
        this(label, 1.0f, CODE_NONE);
    }

    /** 文字キーのコンストラクタ（ウェイト指定） */
    public KeyConfig(String label, float weight) {
        this(label, weight, CODE_NONE);
    }

    /** 機能キーのコンストラクタ（ウェイト 1.0） */
    public KeyConfig(int code) {
        this(getDefaultLabel(code), 1.0f, code);
    }

    /** 機能キーのコンストラクタ（ウェイト指定） */
    public KeyConfig(int code, float weight) {
        this(getDefaultLabel(code), weight, code);
    }

    /** 内部用マスターコンストラクタ */
    public KeyConfig(String label, float weight, int code) {
        this.label = label;
        this.weight = weight;
        this.code = code;
    }

    /**
     * このキーが長押しによるリピート入力（連打）に対応しているかどうかを判定します。
     *
     * @return リピート入力対応キーなら true
     */
    public boolean isRepeatable() {
        switch (code) {
            case CODE_BACKSPACE:
            case CODE_LEFT:
            case CODE_RIGHT:
            case CODE_UP:
            case CODE_DOWN:
                return true;
            default:
                return false;
        }
    }

    /**
     * レイアウト（2次元配列）を JSON 形式の文字列に変換します。
     * <p>
     * 各キーを 1 行に配置する人間が読みやすいインデント付きの形式で出力します。
     * </p>
     *
     * @param layout 変換対象のレイアウト配列
     * @return JSON 文字列
     */
    public static String layoutToJsonString(KeyConfig[][] layout) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < layout.length; i++) {
            sb.append("    [\n");
            for (int j = 0; j < layout[i].length; j++) {
                KeyConfig config = layout[i][j];
                String escapedLabel = config.label.replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("      { \"label\": \"").append(escapedLabel).append("\"");
                if (config.code != CODE_NONE) {
                    sb.append(", \"code\": \"").append(codeToString(config.code)).append("\"");
                }
                if (config.weight != 1.0f) {
                    sb.append(", \"weight\": ").append(config.weight);
                }
                sb.append(" }");
                if (j < layout[i].length - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("    ]");
            if (i < layout.length - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    /**
     * 複数のレイアウト（通常・シフト・記号等）を含むマップを、バックアップ用の JSON 形式文字列に変換します。
     *
     * @param layouts 保存するレイアウトのマップ
     * @return JSON 文字列
     */
    public static String backupToJsonString(Map<String, KeyConfig[][]> layouts) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        List<String> keys = new ArrayList<>(layouts.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            sb.append("  \"").append(key).append("\": ");
            sb.append(layoutToJsonString(layouts.get(key)));
            if (i < keys.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * JSON 文字列からレイアウト（KeyConfig の 2 次元配列）を復元します。
     * <p>
     * 1次元配列（1行のみ）の JSON にも対応し、内部で 2 次元配列に包み直します。
     * </p>
     *
     * @param s パース対象の JSON 文字列
     * @return 復元された KeyConfig[][] 配列
     */
    public static KeyConfig[][] layoutFromAnyString(String s) {
        if (s == null || s.isEmpty()) {
            return new KeyConfig[0][0];
        }

        try {
            JSONArray json = new JSONArray(s);
            if (json.length() > 0 && json.optJSONObject(0) != null) {
                // 1次元配列（1行のみ）の場合、2次元配列に包み直す
                JSONArray wrapper = new JSONArray();
                wrapper.put(json);
                json = wrapper;
            }
            int rowCount = json.length();
            KeyConfig[][] layout = new KeyConfig[rowCount][];
            for (int i = 0; i < rowCount; i++) {
                JSONArray jsonRow = json.optJSONArray(i);
                if (jsonRow == null) {
                    layout[i] = new KeyConfig[0];
                    continue;
                }
                layout[i] = new KeyConfig[jsonRow.length()];
                for (int j = 0; j < jsonRow.length(); j++) {
                    JSONObject obj = jsonRow.optJSONObject(j);
                    if (obj == null) {
                        layout[i][j] = new KeyConfig("");
                        continue;
                    }
                    String label = obj.optString("label", "");
                    float weight = (float) obj.optDouble("weight", 1.0);
                    int code = obj.has("code") ? stringToCode(obj.getString("code")) : CODE_NONE;
                    layout[i][j] = new KeyConfig(label, weight, code);
                }
            }
            return layout;
        } catch (JSONException e) {
            // 解析不能な場合は空のレイアウトを返す
            return new KeyConfig[0][0];
        }
    }

    @Override
    public String toString() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("label", label);
            if (weight != 1.0f) {
                obj.put("weight", weight);
            }
            if (code != CODE_NONE) {
                obj.put("code", codeToString(code));
            }
        } catch (JSONException ignored) {
        }
        return obj.toString();
    }

    /**
     * JSON 文字列から単一の KeyConfig を生成します。
     *
     * @param s 単一キーの JSON 文字列
     * @return KeyConfig インスタンス
     */
    public static KeyConfig fromString(String s) {
        try {
            JSONObject obj = new JSONObject(s);
            String label = obj.optString("label", "");
            float weight = (float) obj.optDouble("weight", 1.0);
            int code = obj.has("code") ? stringToCode(obj.getString("code")) : CODE_NONE;
            return new KeyConfig(label, weight, code);
        } catch (Exception e) {
            return new KeyConfig("", 1.0f);
        }
    }

}
