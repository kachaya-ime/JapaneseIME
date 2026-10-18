package io.github.kachaya.ime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * アセットフォルダからのデータ読み込みを担当するユーティリティクラスです。
 */
object AssetLoader {

    /**
     * 指定されたアセットファイルを UTF-8 文字列として読み込みます。
     *
     * @param context コンテキスト
     * @param fileName アセット内のファイル名（パス）
     * @return ファイルの内容。エラーが発生した場合は null。
     */
    @JvmStatic
    fun loadAssetString(context: Context?, fileName: String): String? {
        var inputStream: InputStream? = null
        if (context != null) {
            try {
                inputStream = context.assets.open(fileName)
            } catch (_: Exception) {
            }
        }
        if (inputStream == null) {
            val classLoader = AssetLoader::class.java.classLoader
            if (classLoader != null) {
                inputStream = classLoader.getResourceAsStream("assets/$fileName")
                    ?: classLoader.getResourceAsStream(fileName)
            }
        }
        if (inputStream == null) {
            var file = File("src/main/assets/$fileName")
            if (!file.exists()) {
                file = File("app/src/main/assets/$fileName")
            }
            if (file.exists()) {
                try {
                    inputStream = FileInputStream(file)
                } catch (_: Exception) {
                }
            }
        }
        val stream = inputStream ?: return null

        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 指定されたアセットファイルを読み込み、JSONArray としてパースします。
     *
     * @param context コンテキスト
     * @param fileName アセット内のファイル名
     * @return パースされた JSONArray。エラーまたはファイル不在時は null。
     */
    @JvmStatic
    fun loadJsonArray(context: Context?, fileName: String): JSONArray? {
        return try {
            val json = loadAssetString(context, fileName)
            if (json != null) JSONArray(json) else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 指定されたアセットファイルを読み込み、JSONObject としてパースします。
     *
     * @param context コンテキスト
     * @param fileName アセット内のファイル名
     * @return パースされた JSONObject。エラーまたはファイル不在時は null。
     */
    @JvmStatic
    fun loadJsonObject(context: Context?, fileName: String): JSONObject? {
        return try {
            val json = loadAssetString(context, fileName)
            if (json != null) JSONObject(json) else null
        } catch (_: Exception) {
            null
        }
    }
}
