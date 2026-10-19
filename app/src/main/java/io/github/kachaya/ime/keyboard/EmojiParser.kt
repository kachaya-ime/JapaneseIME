package io.github.kachaya.ime.keyboard

import android.content.Context
import android.graphics.Paint
import android.util.Log
import io.github.kachaya.ime.AssetLoader
import java.io.BufferedReader
import java.io.StringReader

/**
 * tool 側で生成された emoji.txt を読み込み、グループごとに分類した絵文字アイテムを返すパーサークラスです。
 */
object EmojiParser {
    private const val TAG = "EmojiParser"

    class EmojiItem(
        var emoji: String,
        var description: String,
        var group: String
    )

    /**
     * emoji.txt を読み込み、グループごとに分類したマップを返します。
     *
     * @param context コンテキスト
     * @return グループ名をキーとする EmojiItem リストのマップ
     */
    fun parse(context: Context): Map<String, List<EmojiItem>> {
        val groupMap = LinkedHashMap<String, MutableList<EmojiItem>>()

        var text = AssetLoader.loadAssetString(context, "dictionary/emoji.txt")
        if (text == null) {
            text = AssetLoader.loadAssetString(context, "emoji.txt")
        }
        if (text == null) {
            return getFallbackEmojis()
        }

        val paint = Paint()

        try {
            BufferedReader(StringReader(text)).use { reader ->
                var currentGroup = "Smileys & Emotion"
                var currentItems: MutableList<EmojiItem>? = null
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.isEmpty()) continue
                    if (trimmed.startsWith("# ")) {
                        currentGroup = trimmed.substring(2).trim()
                        currentItems = ArrayList()
                        groupMap[currentGroup] = currentItems
                        continue
                    }
                    if (currentItems == null) {
                        currentItems = ArrayList()
                        groupMap[currentGroup] = currentItems
                    }
                    if (paint.hasGlyph(trimmed)) {
                        currentItems.add(EmojiItem(trimmed, "", currentGroup))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse emoji.txt", e)
        }

        groupMap.entries.removeIf { entry -> entry.value.isEmpty() }

        if (groupMap.isEmpty()) {
            return getFallbackEmojis()
        }

        return groupMap
    }

    private fun getFallbackEmojis(): Map<String, List<EmojiItem>> {
        val map = LinkedHashMap<String, List<EmojiItem>>()
        val smileys = ArrayList<EmojiItem>().apply {
            add(EmojiItem("😀", "grinning face", "Smileys & Emotion"))
            add(EmojiItem("😂", "face with tears of joy", "Smileys & Emotion"))
            add(EmojiItem("😍", "smiling face with heart-eyes", "Smileys & Emotion"))
            add(EmojiItem("👍", "thumbs up", "People & Body"))
            add(EmojiItem("❤️", "red heart", "Smileys & Emotion"))
        }
        map["Smileys & Emotion"] = smileys
        return map
    }
}
