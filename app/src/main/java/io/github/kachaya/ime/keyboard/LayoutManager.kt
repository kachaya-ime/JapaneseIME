package io.github.kachaya.ime.keyboard

import android.content.Context
import android.util.Log
import io.github.kachaya.ime.AssetLoader
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * キーボード配列の永続化（保存・読み込み）およびカスタマイズ用パレット・構成情報を一元管理するクラスです。
 */
object LayoutManager {
    private const val TAG = "LayoutManager"
    private const val LAYOUT_DIR = "layouts"

    const val PREF_LAYOUT_UPDATED = "layout_updated_at"

    /** パレット用：特殊キー (QWERTY等) */
    @JvmField
    val PALETTE_SPECIAL_KEYS: List<KeyConfig> = listOf(
        KeyConfig(KeyConfig.CODE_SPACE),
        KeyConfig(KeyConfig.CODE_ENTER),
        KeyConfig(KeyConfig.CODE_BACKSPACE),
        KeyConfig(KeyConfig.CODE_SHIFT),
        KeyConfig(KeyConfig.CODE_CTRL),
        KeyConfig(KeyConfig.CODE_MODE),
        KeyConfig(KeyConfig.CODE_TAB),
        KeyConfig(KeyConfig.CODE_LEFT),
        KeyConfig(KeyConfig.CODE_UP),
        KeyConfig(KeyConfig.CODE_DOWN),
        KeyConfig(KeyConfig.CODE_RIGHT),
        KeyConfig(KeyConfig.CODE_SYM),
        KeyConfig(KeyConfig.CODE_ABC),
        KeyConfig(KeyConfig.CODE_GAP),
        KeyConfig(KeyConfig.CODE_ESC)
    )

    /** パレット用：Tablet特殊キー（Symキーを除外） */
    @JvmField
    val PALETTE_SPECIAL_KEYS_TABLET: List<KeyConfig> = listOf(
        KeyConfig(KeyConfig.CODE_SPACE),
        KeyConfig(KeyConfig.CODE_ENTER),
        KeyConfig(KeyConfig.CODE_BACKSPACE),
        KeyConfig(KeyConfig.CODE_SHIFT),
        KeyConfig(KeyConfig.CODE_CTRL),
        KeyConfig(KeyConfig.CODE_TAB),
        KeyConfig(KeyConfig.CODE_LEFT),
        KeyConfig(KeyConfig.CODE_UP),
        KeyConfig(KeyConfig.CODE_DOWN),
        KeyConfig(KeyConfig.CODE_RIGHT),
        KeyConfig(KeyConfig.CODE_GAP),
        KeyConfig(KeyConfig.CODE_ESC)
    )

    /** パレット用：英数字 */
    @JvmField
    val PALETTE_ALPHA_KEYS: List<KeyConfig> = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".map { KeyConfig(it.toString()) }

    /** パレット用：記号（QWERTYカスタマイズ用） */
    @JvmField
    val PALETTE_SYMBOL_KEYS: List<KeyConfig> = "0123456789!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~¥".map { KeyConfig(it.toString()) } + KeyConfig(KeyConfig.CODE_ESC)

    /** パレット用：記号バー専用 */
    @JvmField
    val PALETTE_SYMBOL_BAR_KEYS: List<KeyConfig> = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~¥".map { KeyConfig(it.toString()) } + KeyConfig(KeyConfig.CODE_ESC)

    @JvmStatic
    fun loadLayout(context: Context, key: String, defaultValue: String): String {
        val file = getLayoutFile(context, key)
        if (file.exists()) {
            val sb = StringBuilder()
            try {
                BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line).append("\n")
                    }
                    return sb.toString().trim()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load layout: $key", e)
            }
        }
        return defaultValue
    }

    @JvmStatic
    fun saveLayout(context: Context, key: String, layoutJson: String) {
        val file = getLayoutFile(context, key)
        val dir = file.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create directory: ${dir.absolutePath}")
        }

        try {
            FileOutputStream(file).use { fos ->
                fos.write(layoutJson.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save layout: $key", e)
        }
    }

    @JvmStatic
    fun getManagedKeys(): List<String> {
        return listOf(
            "custom_qwerty_layout_normal",
            "custom_qwerty_layout_shift",
            "custom_qwerty_layout_symbol",
            "custom_tablet_layout_normal",
            "custom_tablet_layout_shift",
            "custom_symbols_layout"
        )
    }

    @JvmStatic
    fun getSpecialKeysPalette(targetPrefKey: String?): List<KeyConfig> {
        if ("custom_tablet_layout" == targetPrefKey) {
            return PALETTE_SPECIAL_KEYS_TABLET
        }
        return PALETTE_SPECIAL_KEYS
    }

    @JvmStatic
    fun getAlphaKeysPalette(): List<KeyConfig> {
        return PALETTE_ALPHA_KEYS
    }

    @JvmStatic
    fun getSymbolKeysPalette(targetPrefKey: String?): List<KeyConfig> {
        return if ("combined_symbols" == targetPrefKey) PALETTE_SYMBOL_BAR_KEYS else PALETTE_SYMBOL_KEYS
    }

    @JvmStatic
    fun isSymbolBar(targetPrefKey: String?): Boolean {
        return "combined_symbols" == targetPrefKey
    }

    @JvmStatic
    fun hasSymbolMode(targetPrefKey: String?): Boolean {
        return "custom_qwerty_layout" == targetPrefKey
    }

    private fun getLayoutFile(context: Context, key: String): File {
        val dir = File(context.filesDir, LAYOUT_DIR)
        return File(dir, "$key.json")
    }

    @JvmStatic
    fun clearLayout(context: Context, targetPrefKey: String?) {
        if ("combined_symbols" == targetPrefKey) {
            val file = getLayoutFile(context, "custom_symbols_layout")
            if (file.exists() && !file.delete()) {
                Log.e("LayoutManager", "Failed to delete file: ${file.absolutePath}")
            }
        } else {
            val suffixes = arrayOf("_normal", "_shift", "_symbol")
            for (suffix in suffixes) {
                val file = getLayoutFile(context, "$targetPrefKey$suffix")
                if (file.exists() && !file.delete()) {
                    Log.e("LayoutManager", "Failed to delete file: ${file.absolutePath}")
                }
            }
        }
    }

    @JvmStatic
    fun clearLayouts(context: Context) {
        val dir = File(context.filesDir, LAYOUT_DIR)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.exists() && !file.delete()) {
                        Log.w("LayoutManager", "Failed to delete file: ${file.absolutePath}")
                    }
                }
            }
        }
    }

    object DefaultLayouts {
        @JvmStatic
        fun get(context: Context?, key: String?): String? {
            return when (key) {
                "custom_qwerty_layout_normal" -> AssetLoader.loadAssetString(context, "layouts/qwerty_normal.json")
                "custom_qwerty_layout_shift" -> AssetLoader.loadAssetString(context, "layouts/qwerty_shift.json")
                "custom_qwerty_layout_symbol" -> AssetLoader.loadAssetString(context, "layouts/qwerty_symbol.json")
                "custom_tablet_layout_normal" -> AssetLoader.loadAssetString(context, "layouts/tablet_normal.json")
                "custom_tablet_layout_shift" -> AssetLoader.loadAssetString(context, "layouts/tablet_shift.json")
                "combined_symbols", "custom_symbols_layout" -> AssetLoader.loadAssetString(context, "layouts/combined_symbols.json")
                else -> null
            }
        }
    }
}
