package io.github.kachaya.ime.keyboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 1つのキーの定義（ラベルとウェイト）を保持するクラスです。
 * 通常・シフト・記号などの各レイアウトは、このオブジェクトの独立したリストとして構成されます。
 */
class KeyConfig constructor(
    var label: String,
    var weight: Float = 1.0f,
    var code: Int = CODE_NONE
) {
    /** 機能キーのコンストラクタ */
    constructor(code: Int, weight: Float = 1.0f) : this(getDefaultLabel(code), weight, code)

    /**
     * このキーが長押しによるリピート入力（連打）に対応しているかどうかを判定します。
     *
     * @return リピート入力対応キーなら true
     */
    fun isRepeatable(): Boolean {
        return when (code) {
            CODE_BACKSPACE,
            CODE_LEFT,
            CODE_RIGHT,
            CODE_UP,
            CODE_DOWN -> true
            else -> false
        }
    }

    override fun toString(): String {
        val obj = JSONObject()
        try {
            obj.put("label", label)
            if (weight != 1.0f) {
                obj.put("weight", weight.toDouble())
            }
            if (code != CODE_NONE) {
                obj.put("code", codeToString(code))
            }
        } catch (_: JSONException) {
        }
        return obj.toString()
    }

    companion object {
        // --- Functional Codes ---
        const val CODE_NONE = 0
        const val CODE_SHIFT = 1
        const val CODE_ENTER = 2
        const val CODE_BACKSPACE = 3
        const val CODE_SPACE = 4
        const val CODE_SYM = 5
        const val CODE_CTRL = 6
        const val CODE_TAB = 7
        const val CODE_LEFT = 8
        const val CODE_RIGHT = 9
        const val CODE_UP = 10
        const val CODE_DOWN = 11
        const val CODE_GAP = 12
        const val CODE_ABC = 13
        const val CODE_SHORTCUT = 14
        const val CODE_CANCEL = 15
        const val CODE_8_2_8 = 16
        const val CODE_10_4_10 = 17
        const val CODE_ESC = 18
        const val CODE_MODE = 19

        private class KeyEntry(
            val code: Int,
            val name: String,
            val defaultLabel: String
        )

        private val KEY_TABLE = arrayOf(
            KeyEntry(CODE_SHIFT, "SHIFT", "⇧"),
            KeyEntry(CODE_ENTER, "ENTER", "⏎"),
            KeyEntry(CODE_BACKSPACE, "BACKSPACE", "⌫"),
            KeyEntry(CODE_SPACE, "SPACE", "⌴"),
            KeyEntry(CODE_SYM, "SYM", "@!?"),
            KeyEntry(CODE_CTRL, "CTRL", "Ctrl"),
            KeyEntry(CODE_TAB, "TAB", "Tab"),
            KeyEntry(CODE_LEFT, "LEFT", "◂"),
            KeyEntry(CODE_RIGHT, "RIGHT", "▸"),
            KeyEntry(CODE_UP, "UP", "▴"),
            KeyEntry(CODE_DOWN, "DOWN", "▾"),
            KeyEntry(CODE_GAP, "GAP", "Gap"),
            KeyEntry(CODE_ABC, "ABC", "abc"),
            KeyEntry(CODE_ESC, "ESC", "Esc"),
            KeyEntry(CODE_MODE, "MODE", "あ/A")
        )

        private val CODE_TO_ENTRY_MAP = HashMap<Int, KeyEntry>()
        private val STRING_TO_CODE_MAP = HashMap<String, Int>()

        init {
            for (entry in KEY_TABLE) {
                CODE_TO_ENTRY_MAP[entry.code] = entry
                STRING_TO_CODE_MAP[entry.name.uppercase()] = entry.code
            }
            STRING_TO_CODE_MAP["TOGGLE_EN_JP"] = CODE_MODE
            STRING_TO_CODE_MAP["EN_JP"] = CODE_MODE
        }

        /** ファイル保存用の識別名に変換します */
        fun codeToString(code: Int): String? {
            val entry = CODE_TO_ENTRY_MAP[code]
            return entry?.name
        }

        /** 識別名からコードに変換します（大文字小文字を区別しません） */
        fun stringToCode(s: String?): Int {
            if (s.isNullOrEmpty()) {
                return CODE_NONE
            }
            val code = STRING_TO_CODE_MAP[s.uppercase()]
            return code ?: CODE_NONE
        }

        /** 各機能のデフォルトの表示ラベルを返します */
        fun getDefaultLabel(code: Int): String {
            val entry = CODE_TO_ENTRY_MAP[code]
            return entry?.defaultLabel ?: ""
        }

        /**
         * レイアウト（2次元配列）を JSON 形式の文字列に変換します。
         */
        fun layoutToJsonString(layout: Array<Array<KeyConfig>>): String {
            val sb = StringBuilder()
            sb.append("[\n")
            for (i in layout.indices) {
                sb.append("    [\n")
                for (j in layout[i].indices) {
                    val config = layout[i][j]
                    val escapedLabel = config.label.replace("\\", "\\\\").replace("\"", "\\\"")
                    sb.append("      { \"label\": \"").append(escapedLabel).append("\"")
                    if (config.code != CODE_NONE) {
                        sb.append(", \"code\": \"").append(codeToString(config.code)).append("\"")
                    }
                    if (config.weight != 1.0f) {
                        sb.append(", \"weight\": ").append(config.weight)
                    }
                    sb.append(" }")
                    if (j < layout[i].size - 1) {
                        sb.append(",")
                    }
                    sb.append("\n")
                }
                sb.append("    ]")
                if (i < layout.size - 1) {
                    sb.append(",")
                }
                sb.append("\n")
            }
            sb.append("  ]")
            return sb.toString()
        }

        /**
         * 複数のレイアウト（通常・シフト・記号等）を含むマップを、バックアップ用の JSON 形式文字列に変換します。
         */
        fun backupToJsonString(layouts: Map<String, Array<Array<KeyConfig>>>): String {
            val sb = StringBuilder()
            sb.append("{\n")
            val keys = ArrayList(layouts.keys)
            for (i in keys.indices) {
                val key = keys[i]
                val layout = layouts[key]
                if (layout != null) {
                    sb.append("  \"").append(key).append("\": ")
                    sb.append(layoutToJsonString(layout))
                    if (i < keys.size - 1) {
                        sb.append(",")
                    }
                    sb.append("\n")
                }
            }
            sb.append("}")
            return sb.toString()
        }

        /**
         * JSON 文字列からレイアウト（KeyConfig の 2 次元配列）を復元します。
         */
        fun layoutFromAnyString(s: String?): Array<Array<KeyConfig>> {
            if (s.isNullOrEmpty()) {
                return emptyArray()
            }

            try {
                var json = JSONArray(s)
                if (json.length() > 0 && json.optJSONObject(0) != null) {
                    val wrapper = JSONArray()
                    wrapper.put(json)
                    json = wrapper
                }
                val rowCount = json.length()
                val layout = Array(rowCount) { emptyArray<KeyConfig>() }
                for (i in 0..<rowCount) {
                    val jsonRow = json.optJSONArray(i)
                    if (jsonRow == null) {
                        layout[i] = emptyArray()
                        continue
                    }
                    val row = Array(jsonRow.length()) { KeyConfig("") }
                    for (j in 0..<jsonRow.length()) {
                        val obj = jsonRow.optJSONObject(j)
                        if (obj == null) {
                            row[j] = KeyConfig("")
                            continue
                        }
                        val label = obj.optString("label", "")
                        val weight = obj.optDouble("weight", 1.0).toFloat()
                        val code = if (obj.has("code")) stringToCode(obj.getString("code")) else CODE_NONE
                        row[j] = KeyConfig(label, weight, code)
                    }
                    layout[i] = row
                }
                return layout
            } catch (_: JSONException) {
                return emptyArray()
            }
        }

        /**
         * JSON 文字列から単一の KeyConfig を生成します。
         */
        fun fromString(s: String?): KeyConfig {
            if (s.isNullOrEmpty()) return KeyConfig("", 1.0f)
            return try {
                val obj = JSONObject(s)
                val label = obj.optString("label", "")
                val weight = obj.optDouble("weight", 1.0).toFloat()
                val code = if (obj.has("code")) stringToCode(obj.getString("code")) else CODE_NONE
                KeyConfig(label, weight, code)
            } catch (_: Exception) {
                KeyConfig("", 1.0f)
            }
        }
    }
}
