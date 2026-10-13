package io.github.kachaya.ime.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

/**
 * 日本語物理キーボード固有のモード切替キー（半角/全角、Fn+Q、英数、かな等）の割り当てを管理するクラスです。
 */
public class PhysicalKeyBinding {

    public static final String KEY_TOGGLE_EN_JP = "physical_key_toggle_en_jp";
    public static final String KEY_JAPANESE_MODE = "physical_key_japanese_mode";
    public static final String KEY_ENGLISH_MODE = "physical_key_english_mode";

    private final int keyCode;
    private final int metaState;

    /**
     * PhysicalKeyBinding を構築します。
     *
     * @param keyCode キーコード
     */
    public PhysicalKeyBinding(int keyCode) {
        this(keyCode, 0);
    }

    /**
     * PhysicalKeyBinding を構築します（修飾キー指定付き）。
     *
     * @param keyCode   キーコード
     * @param metaState メタキー状態（例: KeyEvent.META_FUNCTION_ON）
     */
    public PhysicalKeyBinding(int keyCode, int metaState) {
        this.keyCode = keyCode;
        this.metaState = metaState;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public int getMetaState() {
        return metaState;
    }

    /**
     * 未設定状態かどうかを判定します。
     *
     * @return 未設定の場合は true
     */
    public boolean isUnset() {
        return keyCode == 0;
    }

    /**
     * KeyEvent からサポートされている修飾キー（Fn, Alt, Ctrl, Shift）の状態を抽出し、標準化されたメタフラグを返します。
     *
     * @param event KeyEvent
     * @return 正規化されたメタフラグ
     */
    public static int getNormalizedMetaState(KeyEvent event) {
        int meta = 0;
        if (event.isFunctionPressed() || (event.getMetaState() & KeyEvent.META_FUNCTION_ON) != 0) {
            meta |= KeyEvent.META_FUNCTION_ON;
        }
        if (event.isAltPressed() || (event.getMetaState() & KeyEvent.META_ALT_ON) != 0) {
            meta |= KeyEvent.META_ALT_ON;
        }
        if (event.isCtrlPressed() || (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
            meta |= KeyEvent.META_CTRL_ON;
        }
        if (event.isShiftPressed() || (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
            meta |= KeyEvent.META_SHIFT_ON;
        }
        return meta;
    }

    /**
     * キーイベントがこのキーバインドに一致するか判定します。
     *
     * @param event KeyEvent
     * @return 一致する場合は true
     */
    public boolean matches(KeyEvent event) {
        if (isUnset()) {
            return false;
        }
        if (event.getKeyCode() != keyCode) {
            return false;
        }
        int eventMeta = getNormalizedMetaState(event);
        int targetMeta = metaState & (KeyEvent.META_FUNCTION_ON | KeyEvent.META_ALT_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
        return eventMeta == targetMeta;
    }

    /**
     * 文字列形式（"keyCode" または "keyCode:metaState"）にシリアライズします。
     *
     * @return シリアライズ文字列
     */
    public String serialize() {
        if (metaState == 0) {
            return String.valueOf(keyCode);
        }
        return keyCode + ":" + metaState;
    }

    /**
     * シリアライズ文字列から PhysicalKeyBinding を復元します。
     *
     * @param str シリアライズ文字列
     * @return PhysicalKeyBinding オブジェクト
     */
    public static PhysicalKeyBinding deserialize(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new PhysicalKeyBinding(0);
        }
        try {
            String[] parts = str.trim().split(":");
            int code = Integer.parseInt(parts[0]);
            int meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new PhysicalKeyBinding(code, meta);
        } catch (Exception e) {
            return new PhysicalKeyBinding(0);
        }
    }

    /**
     * 設定キーに応じたデフォルトのシリアライズ文字列を取得します。
     *
     * @param prefKey 設定キー（例: "physical_key_toggle_en_jp"）
     * @return デフォルトのシリアライズ文字列（「日英切り替えキー」は半角/全角、それ以外は "0"）
     */
    public static String getDefaultSerialized(String prefKey) {
        if (KEY_TOGGLE_EN_JP.equals(prefKey)) {
            return String.valueOf(KeyEvent.KEYCODE_ZENKAKU_HANKAKU);
        }
        return "0";
    }

    /**
     * SharedPreferences からキーバインド設定を読み込みます（デフォルト値は設定キーに応じて自動選択）。
     */
    public static PhysicalKeyBinding load(SharedPreferences prefs, String prefKey) {
        return load(prefs, prefKey, getDefaultSerialized(prefKey));
    }

    /**
     * Context 経由で SharedPreferences からキーバインド設定を読み込みます（デフォルト値は設定キーに応じて自動選択）。
     */
    public static PhysicalKeyBinding load(Context context, String prefKey) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return load(prefs, prefKey);
    }

    /**
     * SharedPreferences からキーバインド設定を読み込みます。
     *
     * @param prefs                  SharedPreferences
     * @param prefKey                設定キー
     * @param defaultSerializedValue デフォルトのシリアライズ文字列
     * @return PhysicalKeyBinding オブジェクト
     */
    public static PhysicalKeyBinding load(SharedPreferences prefs, String prefKey, String defaultSerializedValue) {
        String val = prefs.getString(prefKey, defaultSerializedValue);
        return deserialize(val);
    }

    /**
     * Context 経由で SharedPreferences からキーバインド設定を読み込みます。
     */
    public static PhysicalKeyBinding load(Context context, String prefKey, String defaultSerializedValue) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return load(prefs, prefKey, defaultSerializedValue);
    }

    /**
     * キーバインド設定を SharedPreferences に保存します。
     */
    public static void save(Context context, String prefKey, PhysicalKeyBinding binding) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(prefKey, binding.serialize()).apply();
    }

    /**
     * 画面表示用のラベルを取得します。
     *
     * @return 表示用文字列（例: "Fn + Q", "半角/全角"）
     */
    public String getDisplayLabel() {
        if (isUnset()) {
            return "未設定";
        }
        StringBuilder sb = new StringBuilder();
        if ((metaState & KeyEvent.META_FUNCTION_ON) != 0) {
            sb.append("Fn + ");
        }
        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            sb.append("Alt + ");
        }
        if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
            sb.append("Ctrl + ");
        }
        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            sb.append("Shift + ");
        }
        sb.append(getSingleKeyLabel(keyCode));
        return sb.toString();
    }

    /**
     * 日本語キーボード固有キーまたは単一キーの表示用ラベルを取得します。
     *
     * @param keyCode キーコード
     * @return 画面表示用ラベル（例: "半角/全角", "Space", "Backspace", "F1", "↑" 等）
     */
    public static String getSingleKeyLabel(int keyCode) {
        switch (keyCode) {
            // --- 日本語キーボード固有キー ---
            case KeyEvent.KEYCODE_ZENKAKU_HANKAKU:
                return "半角/全角";
            case KeyEvent.KEYCODE_EISU:
                return "英数";
            case KeyEvent.KEYCODE_KANA:
                return "かな";
            case KeyEvent.KEYCODE_KATAKANA_HIRAGANA:
                return "ひらがな/カタカナ";
            case KeyEvent.KEYCODE_HENKAN:
                return "変換";
            case KeyEvent.KEYCODE_MUHENKAN:
                return "無変換";
            case KeyEvent.KEYCODE_RO:
                return "ろ (\\ / _)";
            case KeyEvent.KEYCODE_YEN:
                return "¥ (|)";
            case 223: // KeyEvent.KEYCODE_JAPANESE_HIRAGANA
                return "ひらがな";
            case 224: // KeyEvent.KEYCODE_JAPANESE_KATAKANA
                return "カタカナ";
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
                return "言語切替";

            // --- リモコンカラーボタンキー ---
            case KeyEvent.KEYCODE_PROG_RED:
                return "赤 (PROG_RED)";
            case KeyEvent.KEYCODE_PROG_GREEN:
                return "緑 (PROG_GREEN)";
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return "黄 (PROG_YELLOW)";
            case KeyEvent.KEYCODE_PROG_BLUE:
                return "青 (PROG_BLUE)";

            // --- 基本操作・編集キー ---
            case KeyEvent.KEYCODE_SPACE:
                return "Space";
            case KeyEvent.KEYCODE_ENTER:
                return "Enter";
            case KeyEvent.KEYCODE_DEL:
                return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "Delete";
            case KeyEvent.KEYCODE_TAB:
                return "Tab";
            case KeyEvent.KEYCODE_ESCAPE:
                return "Esc";
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return "Caps Lock";
            case KeyEvent.KEYCODE_INSERT:
                return "Insert";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "Page Up";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "Page Down";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "Home";
            case KeyEvent.KEYCODE_MOVE_END:
                return "End";

            // --- カーソルキー ---
            case KeyEvent.KEYCODE_DPAD_UP:
                return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "→";
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return "D-Pad Center";

            // --- 修飾キー ---
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return "左Shift";
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return "右Shift";
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return "左Ctrl";
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return "右Ctrl";
            case KeyEvent.KEYCODE_ALT_LEFT:
                return "左Alt";
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return "右Alt";
            case KeyEvent.KEYCODE_META_LEFT:
                return "左Meta (Cmd / Win)";
            case KeyEvent.KEYCODE_META_RIGHT:
                return "右Meta (Cmd / Win)";
            case KeyEvent.KEYCODE_FUNCTION:
                return "Fn";
            case KeyEvent.KEYCODE_SYM:
                return "Sym";

            // --- 記号キー ---
            case KeyEvent.KEYCODE_GRAVE:
                return "` (Grave)";
            case KeyEvent.KEYCODE_MINUS:
                return "-";
            case KeyEvent.KEYCODE_EQUALS:
                return "=";
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return "[";
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return "]";
            case KeyEvent.KEYCODE_BACKSLASH:
                return "\\";
            case KeyEvent.KEYCODE_SEMICOLON:
                return ";";
            case KeyEvent.KEYCODE_APOSTROPHE:
                return "'";
            case KeyEvent.KEYCODE_COMMA:
                return ",";
            case KeyEvent.KEYCODE_PERIOD:
                return ".";
            case KeyEvent.KEYCODE_SLASH:
                return "/";
            case KeyEvent.KEYCODE_AT:
                return "@";
            case KeyEvent.KEYCODE_PLUS:
                return "+";
            case KeyEvent.KEYCODE_STAR:
                return "*";
            case KeyEvent.KEYCODE_POUND:
                return "#";

            // --- ファンクションキー ---
            case KeyEvent.KEYCODE_F1:
                return "F1";
            case KeyEvent.KEYCODE_F2:
                return "F2";
            case KeyEvent.KEYCODE_F3:
                return "F3";
            case KeyEvent.KEYCODE_F4:
                return "F4";
            case KeyEvent.KEYCODE_F5:
                return "F5";
            case KeyEvent.KEYCODE_F6:
                return "F6";
            case KeyEvent.KEYCODE_F7:
                return "F7";
            case KeyEvent.KEYCODE_F8:
                return "F8";
            case KeyEvent.KEYCODE_F9:
                return "F9";
            case KeyEvent.KEYCODE_F10:
                return "F10";
            case KeyEvent.KEYCODE_F11:
                return "F11";
            case KeyEvent.KEYCODE_F12:
                return "F12";

            // --- テンキー ---
            case KeyEvent.KEYCODE_NUMPAD_0:
                return "テンキー 0";
            case KeyEvent.KEYCODE_NUMPAD_1:
                return "テンキー 1";
            case KeyEvent.KEYCODE_NUMPAD_2:
                return "テンキー 2";
            case KeyEvent.KEYCODE_NUMPAD_3:
                return "テンキー 3";
            case KeyEvent.KEYCODE_NUMPAD_4:
                return "テンキー 4";
            case KeyEvent.KEYCODE_NUMPAD_5:
                return "テンキー 5";
            case KeyEvent.KEYCODE_NUMPAD_6:
                return "テンキー 6";
            case KeyEvent.KEYCODE_NUMPAD_7:
                return "テンキー 7";
            case KeyEvent.KEYCODE_NUMPAD_8:
                return "テンキー 8";
            case KeyEvent.KEYCODE_NUMPAD_9:
                return "テンキー 9";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                return "テンキー /";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                return "テンキー *";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return "テンキー -";
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                return "テンキー +";
            case KeyEvent.KEYCODE_NUMPAD_DOT:
                return "テンキー .";
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "テンキー Enter";
            case KeyEvent.KEYCODE_NUMPAD_EQUALS:
                return "テンキー =";

            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name != null) {
                    if (name.startsWith("KEYCODE_")) {
                        return name.substring(8);
                    }
                    return name;
                }
                return String.valueOf(keyCode);
        }
    }
}
