package io.github.kachaya.ime.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.preference.PreferenceManager

/**
 * 日本語物理キーボード固有のモード切替キー（半角/全角、Fn+Q、英数、かな等）の割り当てを管理するクラスです。
 */
class PhysicalKeyBinding @JvmOverloads constructor(
    @JvmField val keyCode: Int,
    @JvmField val metaState: Int = 0
) {

    /**
     * 未設定状態かどうかを判定します。
     */
    fun isUnset(): Boolean = keyCode == 0

    /**
     * キーイベントがこのキーバインドに一致するか判定します。
     */
    fun matches(event: KeyEvent): Boolean {
        if (isUnset()) {
            return false
        }
        if (event.keyCode != keyCode) {
            return false
        }
        val eventMeta = getNormalizedMetaState(event)
        val targetMeta = metaState and (KeyEvent.META_FUNCTION_ON or KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        return eventMeta == targetMeta
    }

    /**
     * 文字列形式（"keyCode" または "keyCode:metaState"）にシリアライズします。
     */
    fun serialize(): String {
        if (metaState == 0) {
            return keyCode.toString()
        }
        return "$keyCode:$metaState"
    }

    /**
     * 画面表示用のラベルを取得します。
     */
    fun getDisplayLabel(): String {
        if (isUnset()) {
            return "未設定"
        }
        val sb = StringBuilder()
        if ((metaState and KeyEvent.META_FUNCTION_ON) != 0) {
            sb.append("Fn + ")
        }
        if ((metaState and KeyEvent.META_ALT_ON) != 0) {
            sb.append("Alt + ")
        }
        if ((metaState and KeyEvent.META_CTRL_ON) != 0) {
            sb.append("Ctrl + ")
        }
        if ((metaState and KeyEvent.META_SHIFT_ON) != 0) {
            sb.append("Shift + ")
        }
        sb.append(getSingleKeyLabel(keyCode))
        return sb.toString()
    }

    companion object {
        const val KEY_TOGGLE_EN_JP = "physical_key_toggle_en_jp"

        const val KEY_JAPANESE_MODE = "physical_key_japanese_mode"

        const val KEY_ENGLISH_MODE = "physical_key_english_mode"

        /**
         * KeyEvent からサポートされている修飾キーの状態を抽出し、標準化されたメタフラグを返します。
         */
        @JvmStatic
        fun getNormalizedMetaState(event: KeyEvent): Int {
            var meta = 0
            if (event.isFunctionPressed || (event.metaState and KeyEvent.META_FUNCTION_ON) != 0) {
                meta = meta or KeyEvent.META_FUNCTION_ON
            }
            if (event.isAltPressed || (event.metaState and KeyEvent.META_ALT_ON) != 0) {
                meta = meta or KeyEvent.META_ALT_ON
            }
            if (event.isCtrlPressed || (event.metaState and KeyEvent.META_CTRL_ON) != 0) {
                meta = meta or KeyEvent.META_CTRL_ON
            }
            if (event.isShiftPressed || (event.metaState and KeyEvent.META_SHIFT_ON) != 0) {
                meta = meta or KeyEvent.META_SHIFT_ON
            }
            return meta
        }

        /**
         * シリアライズ文字列から PhysicalKeyBinding を復元します。
         */
        @JvmStatic
        fun deserialize(str: String?): PhysicalKeyBinding {
            if (str.isNullOrBlank()) {
                return PhysicalKeyBinding(0)
            }
            return try {
                val parts = str.trim().split(":")
                val code = parts[0].toInt()
                val meta = if (parts.size > 1) parts[1].toInt() else 0
                PhysicalKeyBinding(code, meta)
            } catch (_: Exception) {
                PhysicalKeyBinding(0)
            }
        }

        /**
         * 設定キーに応じたデフォルトのシリアライズ文字列を取得します。
         */
        @JvmStatic
        fun getDefaultSerialized(prefKey: String?): String {
            if (KEY_TOGGLE_EN_JP == prefKey) {
                return "${KeyEvent.KEYCODE_SPACE}:${KeyEvent.META_CTRL_ON}"
            }
            return "0"
        }

        @JvmStatic
        fun load(prefs: SharedPreferences, prefKey: String): PhysicalKeyBinding {
            return load(prefs, prefKey, getDefaultSerialized(prefKey))
        }

        @JvmStatic
        fun load(context: Context, prefKey: String): PhysicalKeyBinding {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return load(prefs, prefKey)
        }

        @JvmStatic
        fun load(prefs: SharedPreferences, prefKey: String, defaultSerializedValue: String): PhysicalKeyBinding {
            val valStr = prefs.getString(prefKey, defaultSerializedValue)
            return deserialize(valStr)
        }

        @JvmStatic
        fun load(context: Context, prefKey: String, defaultSerializedValue: String): PhysicalKeyBinding {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return load(prefs, prefKey, defaultSerializedValue)
        }

        @JvmStatic
        fun save(context: Context, prefKey: String, binding: PhysicalKeyBinding) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putString(prefKey, binding.serialize()).apply()
        }

        /**
         * 日本語キーボード固有キーまたは単一キーの表示用ラベルを取得します。
         */
        @JvmStatic
        fun getSingleKeyLabel(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> "半角/全角"
                KeyEvent.KEYCODE_EISU -> "英数"
                KeyEvent.KEYCODE_KANA -> "かな"
                KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> "ひらがな/カタカナ"
                KeyEvent.KEYCODE_HENKAN -> "変換"
                KeyEvent.KEYCODE_MUHENKAN -> "無変換"
                KeyEvent.KEYCODE_RO -> "ろ (\\ / _)"
                KeyEvent.KEYCODE_YEN -> "¥ (|)"
                223 -> "ひらがな"
                224 -> "カタカナ"
                KeyEvent.KEYCODE_LANGUAGE_SWITCH -> "言語切替"

                KeyEvent.KEYCODE_PROG_RED -> "赤 (PROG_RED)"
                KeyEvent.KEYCODE_PROG_GREEN -> "緑 (PROG_GREEN)"
                KeyEvent.KEYCODE_PROG_YELLOW -> "黄 (PROG_YELLOW)"
                KeyEvent.KEYCODE_PROG_BLUE -> "青 (PROG_BLUE)"

                KeyEvent.KEYCODE_SPACE -> "Space"
                KeyEvent.KEYCODE_ENTER -> "Enter"
                KeyEvent.KEYCODE_DEL -> "Backspace"
                KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
                KeyEvent.KEYCODE_TAB -> "Tab"
                KeyEvent.KEYCODE_ESCAPE -> "Esc"
                KeyEvent.KEYCODE_CAPS_LOCK -> "Caps Lock"
                KeyEvent.KEYCODE_INSERT -> "Insert"
                KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
                KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
                KeyEvent.KEYCODE_MOVE_HOME -> "Home"
                KeyEvent.KEYCODE_MOVE_END -> "End"

                KeyEvent.KEYCODE_DPAD_UP -> "↑"
                KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
                KeyEvent.KEYCODE_DPAD_LEFT -> "←"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
                KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center"

                KeyEvent.KEYCODE_SHIFT_LEFT -> "左Shift"
                KeyEvent.KEYCODE_SHIFT_RIGHT -> "右Shift"
                KeyEvent.KEYCODE_CTRL_LEFT -> "左Ctrl"
                KeyEvent.KEYCODE_CTRL_RIGHT -> "右Ctrl"
                KeyEvent.KEYCODE_ALT_LEFT -> "左Alt"
                KeyEvent.KEYCODE_ALT_RIGHT -> "右Alt"
                KeyEvent.KEYCODE_META_LEFT -> "左Meta (Cmd / Win)"
                KeyEvent.KEYCODE_META_RIGHT -> "右Meta (Cmd / Win)"
                KeyEvent.KEYCODE_FUNCTION -> "Fn"
                KeyEvent.KEYCODE_SYM -> "Sym"

                KeyEvent.KEYCODE_GRAVE -> "` (Grave)"
                KeyEvent.KEYCODE_MINUS -> "-"
                KeyEvent.KEYCODE_EQUALS -> "="
                KeyEvent.KEYCODE_LEFT_BRACKET -> "["
                KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
                KeyEvent.KEYCODE_BACKSLASH -> "\\"
                KeyEvent.KEYCODE_SEMICOLON -> ";"
                KeyEvent.KEYCODE_APOSTROPHE -> "'"
                KeyEvent.KEYCODE_COMMA -> ","
                KeyEvent.KEYCODE_PERIOD -> "."
                KeyEvent.KEYCODE_SLASH -> "/"
                KeyEvent.KEYCODE_AT -> "@"
                KeyEvent.KEYCODE_PLUS -> "+"
                KeyEvent.KEYCODE_STAR -> "*"
                KeyEvent.KEYCODE_POUND -> "#"

                KeyEvent.KEYCODE_F1 -> "F1"
                KeyEvent.KEYCODE_F2 -> "F2"
                KeyEvent.KEYCODE_F3 -> "F3"
                KeyEvent.KEYCODE_F4 -> "F4"
                KeyEvent.KEYCODE_F5 -> "F5"
                KeyEvent.KEYCODE_F6 -> "F6"
                KeyEvent.KEYCODE_F7 -> "F7"
                KeyEvent.KEYCODE_F8 -> "F8"
                KeyEvent.KEYCODE_F9 -> "F9"
                KeyEvent.KEYCODE_F10 -> "F10"
                KeyEvent.KEYCODE_F11 -> "F11"
                KeyEvent.KEYCODE_F12 -> "F12"

                KeyEvent.KEYCODE_NUMPAD_0 -> "テンキー 0"
                KeyEvent.KEYCODE_NUMPAD_1 -> "テンキー 1"
                KeyEvent.KEYCODE_NUMPAD_2 -> "テンキー 2"
                KeyEvent.KEYCODE_NUMPAD_3 -> "テンキー 3"
                KeyEvent.KEYCODE_NUMPAD_4 -> "テンキー 4"
                KeyEvent.KEYCODE_NUMPAD_5 -> "テンキー 5"
                KeyEvent.KEYCODE_NUMPAD_6 -> "テンキー 6"
                KeyEvent.KEYCODE_NUMPAD_7 -> "テンキー 7"
                KeyEvent.KEYCODE_NUMPAD_8 -> "テンキー 8"
                KeyEvent.KEYCODE_NUMPAD_9 -> "テンキー 9"
                KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "テンキー /"
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "テンキー *"
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "テンキー -"
                KeyEvent.KEYCODE_NUMPAD_ADD -> "テンキー +"
                KeyEvent.KEYCODE_NUMPAD_DOT -> "テンキー ."
                KeyEvent.KEYCODE_NUMPAD_ENTER -> "テンキー Enter"
                KeyEvent.KEYCODE_NUMPAD_EQUALS -> "テンキー ="

                else -> {
                    val name = KeyEvent.keyCodeToString(keyCode)
                    if (name != null) {
                        if (name.startsWith("KEYCODE_")) {
                            return name.substring(8)
                        }
                        return name
                    }
                    return keyCode.toString()
                }
            }
        }
    }
}
