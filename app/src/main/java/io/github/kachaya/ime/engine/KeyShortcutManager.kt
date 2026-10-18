package io.github.kachaya.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * キーショートカット（Ctrl+アルファベット、Shift+特殊キー等）のカスタマイズ設定を管理するクラスです。
 */
object KeyShortcutManager {

    const val PREF_KEY = "ctrl_shortcuts_config"
    const val SHIFT_MASK = 0x10000

    /**
     * OSで予約されている Ctrl キー組み合わせ（Ctrl+A, Ctrl+C など）かどうかを判定します。
     *
     * @param keyCode キーコード
     * @return OS予約されている場合は true
     */
    @JvmStatic
    fun isOsReserved(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_Z -> true
            else -> false
        }
    }

    /**
     * 予約済み（OS予約またはIME予約）のキー組み合わせかどうかを判定します。
     *
     * @param keyCode キーコード
     * @return 予約済みの場合は true
     */
    @JvmStatic
    fun isReserved(keyCode: Int): Boolean {
        if (isOsReserved(keyCode)) {
            return true
        }
        return keyCode == KeyEvent.KEYCODE_SPACE
    }

    /**
     * 後方互換性のためのエイリアス。
     *
     * @param keyCode キーコード
     * @return 予約済みの場合は true
     */
    @JvmStatic
    fun isSystemReserved(keyCode: Int): Boolean {
        return isReserved(keyCode)
    }

    /**
     * 予約キーのラベルを取得します。
     *
     * @param keyCode キーコード
     * @return 予約機能の説明ラベル
     */
    @JvmStatic
    fun getReservedLabel(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> "システム予約 (全選択)"
            KeyEvent.KEYCODE_C -> "システム予約 (コピー)"
            KeyEvent.KEYCODE_V -> "システム予約 (貼り付け)"
            KeyEvent.KEYCODE_X -> "システム予約 (切り取り)"
            KeyEvent.KEYCODE_Y -> "システム予約 (やり直し)"
            KeyEvent.KEYCODE_Z -> "システム予約 (元に戻す)"
            KeyEvent.KEYCODE_SPACE -> "IME予約 (日英切り替え)"
            else -> ""
        }
    }

    /**
     * デフォルトのキー割り当てマップを取得します（Ctrl+Space に日英切り替えを割り当て）。
     *
     * @return キーコードと InputAction のデフォルトマップ
     */
    @JvmStatic
    fun getDefaultMappings(): Map<Int, InputAction> {
        val mappings = HashMap<Int, InputAction>()
        mappings[KeyEvent.KEYCODE_SPACE] = InputAction.TOGGLE_EN_JP
        return mappings
    }

    /**
     * 設定から現在のショートカット割り当てマップを読み込みます。
     *
     * @param context コンテキスト
     * @return キーコードと InputAction のマップ
     */
    @JvmStatic
    fun loadMappings(context: Context): Map<Int, InputAction> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return loadMappings(prefs)
    }

    /**
     * SharedPreferences から現在のショートカット割り当てマップを読み込みます。
     *
     * @param prefs SharedPreferences
     * @return キーコードと InputAction のマップ
     */
    @JvmStatic
    fun loadMappings(prefs: SharedPreferences): Map<Int, InputAction> {
        val mappings = HashMap(getDefaultMappings())
        val jsonStr = prefs.getString(PREF_KEY, null)
        if (jsonStr.isNullOrEmpty()) {
            return mappings
        }
        try {
            val json = JSONObject(jsonStr)
            for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                if (isSystemReserved(keyCode)) {
                    continue
                }
                val letter = ('A' + keyCode - KeyEvent.KEYCODE_A)
                val keyStr = letter.toString()
                if (json.has(keyStr)) {
                    val actionId = json.getString(keyStr)
                    mappings[keyCode] = InputAction.fromId(actionId)
                }
            }
            if (json.has("SPACE") && !isReserved(KeyEvent.KEYCODE_SPACE)) {
                mappings[KeyEvent.KEYCODE_SPACE] = InputAction.fromId(json.getString("SPACE"))
            }
            if (json.has("ENTER")) {
                mappings[KeyEvent.KEYCODE_ENTER] = InputAction.fromId(json.getString("ENTER"))
            }
            if (json.has("TAB")) {
                mappings[KeyEvent.KEYCODE_TAB] = InputAction.fromId(json.getString("TAB"))
            }
            if (json.has("DEL")) {
                mappings[KeyEvent.KEYCODE_DEL] = InputAction.fromId(json.getString("DEL"))
            }
            if (json.has("SHIFT_SPACE")) {
                mappings[KeyEvent.KEYCODE_SPACE or SHIFT_MASK] = InputAction.fromId(json.getString("SHIFT_SPACE"))
            }
            if (json.has("SHIFT_ENTER")) {
                mappings[KeyEvent.KEYCODE_ENTER or SHIFT_MASK] = InputAction.fromId(json.getString("SHIFT_ENTER"))
            }
            if (json.has("SHIFT_TAB")) {
                mappings[KeyEvent.KEYCODE_TAB or SHIFT_MASK] = InputAction.fromId(json.getString("SHIFT_TAB"))
            }
            if (json.has("SHIFT_DEL")) {
                mappings[KeyEvent.KEYCODE_DEL or SHIFT_MASK] = InputAction.fromId(json.getString("SHIFT_DEL"))
            }
        } catch (_: Exception) {
        }
        return mappings
    }

    /**
     * ショートカット割り当てマップを設定に保存します。
     *
     * @param context コンテキスト
     * @param mappings キーコードと InputAction のマップ
     */
    @JvmStatic
    fun saveMappings(context: Context, mappings: Map<Int, InputAction>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        saveMappings(prefs, mappings)
    }

    /**
     * ショートカット割り当てマップを SharedPreferences に保存します。
     *
     * @param prefs SharedPreferences
     * @param mappings キーコードと InputAction のマップ
     */
    @JvmStatic
    fun saveMappings(prefs: SharedPreferences, mappings: Map<Int, InputAction>) {
        try {
            val json = JSONObject()
            for ((code, action) in mappings) {
                if (isSystemReserved(code)) {
                    continue
                }
                when (code) {
                    KeyEvent.KEYCODE_SPACE -> json.put("SPACE", action.id)
                    KeyEvent.KEYCODE_ENTER -> json.put("ENTER", action.id)
                    KeyEvent.KEYCODE_TAB -> json.put("TAB", action.id)
                    KeyEvent.KEYCODE_DEL -> json.put("DEL", action.id)
                    (KeyEvent.KEYCODE_SPACE or SHIFT_MASK) -> json.put("SHIFT_SPACE", action.id)
                    (KeyEvent.KEYCODE_ENTER or SHIFT_MASK) -> json.put("SHIFT_ENTER", action.id)
                    (KeyEvent.KEYCODE_TAB or SHIFT_MASK) -> json.put("SHIFT_TAB", action.id)
                    (KeyEvent.KEYCODE_DEL or SHIFT_MASK) -> json.put("SHIFT_DEL", action.id)
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                        val letter = ('A' + code - KeyEvent.KEYCODE_A)
                        json.put(letter.toString(), action.id)
                    }
                }
            }
            prefs.edit().putString(PREF_KEY, json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * キーショートカットの設定を初期状態にリセットします。
     *
     * @param context コンテキスト
     */
    @JvmStatic
    fun resetToDefaults(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(PREF_KEY).apply()
    }
}
