package io.github.kachaya.ime.engine

import java.util.HashMap
import java.util.HashSet

/**
 * ローマ字からかなへの変換、および文字種変換（全角/半角、ひらがな/カタカナ）を担当するスタティックユーティリティクラスです。
 */
object RomajiConverter {

    /**
     * 単一のローマ字変換ルールを保持するデータクラスです。
     */
    class Rule(
        val key: String,
        val value: String,
        val next: String?
    )

    private val RULES_MAP = HashMap<String, Rule>()
    private val PREFIX_SET = HashSet<String>()
    private val HALF_WIDTH_KATAKANA_MAP = HashMap<Char, String>()

    private val DEFAULT_ROMAJI_TABLE = arrayOf(
        arrayOf("a", "あ"), arrayOf("i", "い"), arrayOf("u", "う"), arrayOf("e", "え"), arrayOf("o", "お"),
        arrayOf("ba", "ば"), arrayOf("bi", "び"), arrayOf("bu", "ぶ"), arrayOf("be", "べ"), arrayOf("bo", "ぼ"),
        arrayOf("bya", "びゃ"), arrayOf("byi", "びぃ"), arrayOf("byu", "びゅ"), arrayOf("bye", "びぇ"), arrayOf("byo", "びょ"),
        arrayOf("ca", "か"), arrayOf("ci", "し"), arrayOf("cu", "く"), arrayOf("ce", "せ"), arrayOf("co", "こ"),
        arrayOf("cha", "ちゃ"), arrayOf("chi", "ち"), arrayOf("chu", "ちゅ"), arrayOf("che", "ちぇ"), arrayOf("cho", "ちょ"),
        arrayOf("cya", "ちゃ"), arrayOf("cyi", "ちぃ"), arrayOf("cyu", "ちゅ"), arrayOf("cye", "ちぇ"), arrayOf("cyo", "ちょ"),
        arrayOf("da", "だ"), arrayOf("di", "ぢ"), arrayOf("du", "づ"), arrayOf("de", "で"), arrayOf("do", "ど"),
        arrayOf("dha", "でゃ"), arrayOf("dhi", "でぃ"), arrayOf("dhu", "でゅ"), arrayOf("dhe", "でぇ"), arrayOf("dho", "でょ"),
        arrayOf("dwa", "どぁ"), arrayOf("dwi", "どぃ"), arrayOf("dwu", "どぅ"), arrayOf("dwe", "どぇ"), arrayOf("dwo", "どぉ"),
        arrayOf("dya", "ぢゃ"), arrayOf("dyi", "ぢぃ"), arrayOf("dyu", "ぢゅ"), arrayOf("dye", "ぢぇ"), arrayOf("dyo", "ぢょ"),
        arrayOf("fa", "ふぁ"), arrayOf("fi", "ふぃ"), arrayOf("fu", "ふ"), arrayOf("fe", "ふぇ"), arrayOf("fo", "ふぉ"),
        arrayOf("fwa", "ふぁ"), arrayOf("fwi", "ふぃ"), arrayOf("fwu", "ふぅ"), arrayOf("fwe", "ふぇ"), arrayOf("fwo", "ふぉ"),
        arrayOf("fya", "ふゃ"), arrayOf("fyi", "ふぃ"), arrayOf("fyu", "ふゅ"), arrayOf("fye", "ふぇ"), arrayOf("fyo", "ふょ"),
        arrayOf("ga", "が"), arrayOf("gi", "ぎ"), arrayOf("gu", "ぐ"), arrayOf("ge", "げ"), arrayOf("go", "ご"),
        arrayOf("gwa", "ぐぁ"), arrayOf("gwi", "ぐぃ"), arrayOf("gwu", "ぐぅ"), arrayOf("gwe", "ぐぇ"), arrayOf("gwo", "ぐぉ"),
        arrayOf("gya", "ぎゃ"), arrayOf("gyi", "ぎぃ"), arrayOf("gyu", "ぎゅ"), arrayOf("gye", "ぎぇ"), arrayOf("gyo", "ぎょ"),
        arrayOf("ha", "は"), arrayOf("hi", "ひ"), arrayOf("hu", "ふ"), arrayOf("he", "へ"), arrayOf("ho", "ほ"),
        arrayOf("hya", "ひゃ"), arrayOf("hyi", "ひぃ"), arrayOf("hyu", "ひゅ"), arrayOf("hye", "ひぇ"), arrayOf("hyo", "ひょ"),
        arrayOf("ja", "じゃ"), arrayOf("ji", "じ"), arrayOf("ju", "じゅ"), arrayOf("je", "じぇ"), arrayOf("jo", "じょ"),
        arrayOf("jya", "じゃ"), arrayOf("jyi", "じぃ"), arrayOf("jyu", "じゅ"), arrayOf("jye", "じぇ"), arrayOf("jyo", "じょ"),
        arrayOf("ka", "か"), arrayOf("ki", "き"), arrayOf("ku", "く"), arrayOf("ke", "け"), arrayOf("ko", "こ"),
        arrayOf("kwa", "くぁ"), arrayOf("kwi", "くぃ"), arrayOf("kwu", "くぅ"), arrayOf("kwe", "くぇ"), arrayOf("kwo", "くぉ"),
        arrayOf("kya", "きゃ"), arrayOf("kyi", "きぃ"), arrayOf("kyu", "きゅ"), arrayOf("kye", "きぇ"), arrayOf("kyo", "きょ"),
        arrayOf("la", "ぁ"), arrayOf("li", "ぃ"), arrayOf("lu", "ぅ"), arrayOf("le", "ぇ"), arrayOf("lo", "ぉ"),
        arrayOf("lya", "ゃ"), arrayOf("lyi", "ぃ"), arrayOf("lyu", "ゅ"), arrayOf("lye", "ぇ"), arrayOf("lyo", "ょ"),
        arrayOf("ma", "ま"), arrayOf("mi", "み"), arrayOf("mu", "む"), arrayOf("me", "め"), arrayOf("mo", "も"),
        arrayOf("mya", "みゃ"), arrayOf("myi", "みぃ"), arrayOf("myu", "みゅ"), arrayOf("mye", "みぇ"), arrayOf("myo", "みょ"),
        arrayOf("na", "な"), arrayOf("ni", "に"), arrayOf("nu", "ぬ"), arrayOf("ne", "ね"), arrayOf("no", "の"),
        arrayOf("nya", "にゃ"), arrayOf("nyi", "にぃ"), arrayOf("nyu", "にゅ"), arrayOf("nye", "にぇ"), arrayOf("nyo", "にょ"),
        arrayOf("pa", "ぱ"), arrayOf("pi", "ぴ"), arrayOf("pu", "ぷ"), arrayOf("pe", "ぺ"), arrayOf("po", "ぽ"),
        arrayOf("pya", "ぴゃ"), arrayOf("pyi", "ぴぃ"), arrayOf("pyu", "ぴゅ"), arrayOf("pye", "ぴぇ"), arrayOf("pyo", "ぴょ"),
        arrayOf("ra", "ら"), arrayOf("ri", "り"), arrayOf("ru", "る"), arrayOf("re", "れ"), arrayOf("ro", "ろ"),
        arrayOf("rya", "りゃ"), arrayOf("ryi", "りぃ"), arrayOf("ryu", "りゅ"), arrayOf("rye", "りぇ"), arrayOf("ryo", "りょ"),
        arrayOf("sa", "さ"), arrayOf("si", "し"), arrayOf("su", "す"), arrayOf("se", "せ"), arrayOf("so", "そ"),
        arrayOf("sha", "しゃ"), arrayOf("shi", "し"), arrayOf("shu", "しゅ"), arrayOf("she", "しぇ"), arrayOf("sho", "しょ"),
        arrayOf("swa", "すぁ"), arrayOf("swi", "すぃ"), arrayOf("swu", "すぅ"), arrayOf("swe", "すぇ"), arrayOf("swo", "すぉ"),
        arrayOf("sya", "しゃ"), arrayOf("syi", "しぃ"), arrayOf("syu", "しゅ"), arrayOf("sye", "しぇ"), arrayOf("syo", "しょ"),
        arrayOf("ta", "た"), arrayOf("ti", "ち"), arrayOf("tu", "つ"), arrayOf("te", "て"), arrayOf("to", "と"),
        arrayOf("tha", "てゃ"), arrayOf("thi", "てぃ"), arrayOf("thu", "てゅ"), arrayOf("the", "てぇ"), arrayOf("tho", "てょ"),
        arrayOf("tsa", "つぁ"), arrayOf("tsi", "つぃ"), arrayOf("tsu", "つ"), arrayOf("tse", "つぇ"), arrayOf("tso", "つぉ"),
        arrayOf("twa", "とぁ"), arrayOf("twi", "とぃ"), arrayOf("twu", "とぅ"), arrayOf("twe", "とぇ"), arrayOf("two", "とぉ"),
        arrayOf("tya", "ちゃ"), arrayOf("tyi", "ちぃ"), arrayOf("tyu", "ちゅ"), arrayOf("tye", "ちぇ"), arrayOf("tyo", "ちょ"),
        arrayOf("va", "ゔぁ"), arrayOf("vi", "ゔぃ"), arrayOf("vu", "ゔ"), arrayOf("ve", "ゔぇ"), arrayOf("vo", "ゔぉ"),
        arrayOf("vwa", "ゔぁ"), arrayOf("vwi", "ゔぃ"), arrayOf("vwu", "ゔぅ"), arrayOf("vwe", "ゔぇ"), arrayOf("vwo", "ゔぉ"),
        arrayOf("vya", "ゔゃ"), arrayOf("vyi", "ゔぃ"), arrayOf("vyu", "ゔゅ"), arrayOf("vye", "ゔぇ"), arrayOf("vyo", "ゔょ"),
        arrayOf("wa", "わ"), arrayOf("wi", "ゐ"), arrayOf("wu", "う"), arrayOf("we", "ゑ"), arrayOf("wo", "を"),
        arrayOf("wha", "うぁ"), arrayOf("whi", "うぃ"), arrayOf("whu", "う"), arrayOf("whe", "うぇ"), arrayOf("who", "うぉ"),
        arrayOf("xa", "ぁ"), arrayOf("xi", "ぃ"), arrayOf("xu", "ぅ"), arrayOf("xe", "ぇ"), arrayOf("xo", "ぉ"),
        arrayOf("xka", "ヵ"), arrayOf("xke", "ヶ"),
        arrayOf("xtu", "っ"), arrayOf("xtsu", "っ"),
        arrayOf("xwa", "ゎ"),
        arrayOf("xya", "ゃ"), arrayOf("xyi", "ぃ"), arrayOf("xyu", "ゅ"), arrayOf("xye", "ぇ"), arrayOf("xyo", "ょ"),
        arrayOf("ya", "や"), arrayOf("yi", "いぃ"), arrayOf("yu", "ゆ"), arrayOf("ye", "いぇ"), arrayOf("yo", "よ"),
        arrayOf("za", "ざ"), arrayOf("zi", "じ"), arrayOf("zu", "ず"), arrayOf("ze", "ぜ"), arrayOf("zo", "ぞ"),
        arrayOf("zya", "じゃ"), arrayOf("zyi", "じぃ"), arrayOf("zyu", "じゅ"), arrayOf("zye", "じぇ"), arrayOf("zyo", "じょ"),

        arrayOf("n'", "ん"),
        arrayOf("n", "ん"),
        arrayOf("nn", "ん"),

        arrayOf("bb", "っ", "b"),
        arrayOf("cc", "っ", "c"),
        arrayOf("dd", "っ", "d"),
        arrayOf("ff", "っ", "f"),
        arrayOf("gg", "っ", "g"),
        arrayOf("hh", "っ", "h"),
        arrayOf("jj", "っ", "j"),
        arrayOf("kk", "っ", "k"),
        arrayOf("ll", "っ", "l"),
        arrayOf("mm", "っ", "m"),
        arrayOf("pp", "っ", "p"),
        arrayOf("rr", "っ", "r"),
        arrayOf("ss", "っ", "s"),
        arrayOf("tt", "っ", "t"),
        arrayOf("vv", "っ", "v"),
        arrayOf("ww", "っ", "w"),
        arrayOf("xx", "っ", "x"),
        arrayOf("yy", "っ", "y"),
        arrayOf("zz", "っ", "z"),

        arrayOf("-", "ー"),
        arrayOf("~", "\u301C"),
        arrayOf("[", "「"),
        arrayOf("]", "」"),
        arrayOf(".", "。"),
        arrayOf(",", "、"),

        arrayOf("z ", "　"), arrayOf("z!", "！"), arrayOf("z#", "♯"), arrayOf("z(", "（"), arrayOf("z)", "）"),
        arrayOf("z*", "※"), arrayOf("z+", "±"), arrayOf("z,", "‥"), arrayOf("z-", "\u301C"), arrayOf("z.", "…"),
        arrayOf("z/", "・"), arrayOf("z0", "○"), arrayOf("z1", "①"), arrayOf("z2", "②"), arrayOf("z3", "③"),
        arrayOf("z4", "④"), arrayOf("z5", "⑤"), arrayOf("z6", "⑥"), arrayOf("z7", "⑦"), arrayOf("z8", "⑧"),
        arrayOf("z9", "⑨"), arrayOf("z:", "："), arrayOf("z;", "；"), arrayOf("z<", "〈"), arrayOf("z=", "≠"),
        arrayOf("z>", "〉"), arrayOf("z?", "？"), arrayOf("z@", "◎"), arrayOf("z[", "『"), arrayOf("z\\", "＼"),
        arrayOf("z]", "』"), arrayOf("zh", "←"), arrayOf("zj", "↓"), arrayOf("zk", "↑"), arrayOf("zl", "→"),
        arrayOf("z{", "【"), arrayOf("z}", "】"), arrayOf("z~", "\uFF5E")
    )

    private fun put(k: Char, v: String) {
        HALF_WIDTH_KATAKANA_MAP[k] = v
    }

    init {
        for (entry in DEFAULT_ROMAJI_TABLE) {
            val key = entry[0]
            val valStr = entry[1]
            val next = if (entry.size > 2) entry[2] else null
            addRule(key, valStr, next)
        }

        put('ア', "ｱ"); put('イ', "ｲ"); put('ウ', "ｳ"); put('エ', "ｴ"); put('オ', "ｵ")
        put('カ', "ｶ"); put('キ', "ｷ"); put('ク', "ｸ"); put('ケ', "ｹ"); put('コ', "ｺ")
        put('サ', "ｻ"); put('シ', "ｼ"); put('ス', "ｽ"); put('セ', "ｾ"); put('ソ', "ｿ")
        put('タ', "ﾀ"); put('チ', "ﾁ"); put('ツ', "ﾂ"); put('テ', "ﾃ"); put('ト', "ﾄ")
        put('ナ', "ﾅ"); put('ニ', "ﾆ"); put('ヌ', "ﾇ"); put('ネ', "ﾈ"); put('ノ', "ﾉ")
        put('ハ', "ﾊ"); put('ヒ', "ﾋ"); put('フ', "ﾌ"); put('ヘ', "ﾍ"); put('ホ', "ﾎ")
        put('マ', "ﾏ"); put('ミ', "ﾐ"); put('ム', "ﾑ"); put('メ', "ﾒ"); put('モ', "ﾓ")
        put('ヤ', "ﾔ"); put('ユ', "ﾕ"); put('ヨ', "ﾖ")
        put('ラ', "ﾗ"); put('リ', "ﾘ"); put('ル', "ﾙ"); put('レ', "ﾚ"); put('ロ', "ﾛ")
        put('ワ', "ﾜ"); put('ヲ', "ｦ"); put('ン', "ﾝ")
        put('ァ', "ｧ"); put('ィ', "ｨ"); put('ゥ', "ｩ"); put('ェ', "ｴ"); put('ォ', "ｫ")
        put('ャ', "ｬ"); put('ュ', "ｭ"); put('ョ', "ｮ"); put('ッ', "ｯ")
        put('ガ', "ｶﾞ"); put('ギ', "ｷﾞ"); put('グ', "ｸﾞ"); put('ゲ', "ｹﾞ"); put('ゴ', "ｺﾞ")
        put('ザ', "ｻﾞ"); put('ジ', "ｼﾞ"); put('ズ', "ｽﾞ"); put('ゼ', "ｾﾞ"); put('ゾ', "ｿﾞ")
        put('ダ', "ﾀﾞ"); put('ヂ', "ﾁﾞ"); put('ヅ', "ﾂﾞ"); put('デ', "ﾃﾞ"); put('ド', "ﾄﾞ")
        put('バ', "ﾊﾞ"); put('ビ', "ﾋﾞ"); put('ブ', "ﾌﾞ"); put('ベ', "ﾍﾞ"); put('ボ', "ﾎﾞ")
        put('パ', "ﾊﾟ"); put('ピ', "ﾋﾟ"); put('プ', "ﾌﾟ"); put('ペ', "ﾍﾟ"); put('ポ', "ﾎﾟ")
        put('ゔ', "ｳﾞ"); put('ー', "ｰ")
        put('、', "､"); put('。', "｡"); put('・', "･"); put('「', "｢"); put('」', "｣")
    }

    private fun addRule(key: String, value: String, next: String?) {
        RULES_MAP[key] = Rule(key, value, next)
        for (i in 1..<key.length) {
            PREFIX_SET.add(key.substring(0, i))
        }
    }

    @Synchronized
    fun setSeparator(key: String, value: String) {
        addRule(key, value, null)
    }

    /**
     * 与えられた文字がアルファベット (A-Z, a-z) かどうかを判定します。
     */
    fun isAlphabet(code: Int): Boolean {
        return (code in 'A'.code..'Z'.code) || (code in 'a'.code..'z'.code)
    }

    /**
     * 半角文字（ASCII）を対応する全角文字に変換します。
     */
    fun toWideLatin(code: Int): Int {
        if (code == 0x20) {
            return 0x3000
        }
        if (code == 0x00A5) {
            return 0xFFE5
        }
        if (code in 0x21..0x7E) {
            return code - 0x20 + 0xFF00
        }
        return code
    }

    /**
     * 文字列内のすべての半角 ASCII 文字を全角に変換します。
     */
    fun toWideLatin(str: CharSequence?): CharSequence? {
        if (str == null) {
            return null
        }

        val len = str.length
        val buf = StringBuilder(len)
        for (i in 0..<len) {
            buf.append(toWideLatin(str[i].code).toChar())
        }
        return buf
    }

    /**
     * ひらがなを対応する全角カタカナに変換します。
     */
    fun toWideKatakana(ch: Char): Char {
        if (ch in 'ぁ'..'ゖ') {
            return (ch.code - 'ぁ'.code + 'ァ'.code).toChar()
        }
        return ch
    }

    /**
     * 文字列内のすべてのひらがなをカタカナに変換します。
     */
    fun toWideKatakana(cs: CharSequence?): String {
        if (cs == null) return ""
        val sb = StringBuilder()
        for (i in 0..<cs.length) {
            sb.append(toWideKatakana(cs[i]))
        }
        return sb.toString()
    }

    /**
     * 文字列内のすべての全角ひらがな・カタカナを半角カタカナに変換します。
     */
    fun toHalfKatakana(cs: CharSequence?): String {
        if (cs == null) return ""
        val sb = StringBuilder()
        val katakana = toWideKatakana(cs)
        for (i in 0..<katakana.length) {
            val c = katakana[i]
            sb.append(HALF_WIDTH_KATAKANA_MAP.getOrDefault(c, c.toString()))
        }
        return sb.toString()
    }

    /**
     * ローマ字変換結果を格納する保持用データクラスです。
     */
    class ConversionResult(
        val convertedText: String,
        val pendingRomaji: String
    )

    /**
     * ローマ字文字列を入力として受け取り、最長一致アルゴリズムで「かな文字列」と残りの「未確定ローマ字（pending）」に変換します。
     */
    fun convert(input: CharSequence?): ConversionResult {
        if (input == null || input.length == 0) {
            return ConversionResult("", "")
        }

        val converted = StringBuilder()
        val buffer = StringBuilder(input.toString())

        while (buffer.isNotEmpty()) {
            val current = buffer.toString()

            var matchedRule: Rule? = null
            var matchLen = 0

            for (len in current.length downTo 1) {
                val sub = current.substring(0, len)
                val r = RULES_MAP[sub]
                if (r != null) {
                    matchedRule = r
                    matchLen = len
                    break
                }
            }

            if (matchedRule != null) {
                if (matchLen == current.length && PREFIX_SET.contains(current)) {
                    break
                }

                converted.append(matchedRule.value)
                buffer.delete(0, matchLen)
                if (matchedRule.next != null) {
                    buffer.insert(0, matchedRule.next)
                }
            } else {
                if (PREFIX_SET.contains(current)) {
                    break
                }

                converted.append(buffer[0])
                buffer.deleteCharAt(0)
            }
        }

        return ConversionResult(converted.toString(), buffer.toString())
    }

    /**
     * 残っている未確定ローマ字を強制的に可能な限り変換して文字列を構築します。
     */
    fun flushRomaji(pending: CharSequence?): String {
        if (pending == null || pending.length == 0) {
            return ""
        }

        val converted = StringBuilder()
        val buffer = StringBuilder(pending.toString())

        while (buffer.isNotEmpty()) {
            val current = buffer.toString()

            var matchedRule: Rule? = null
            var matchLen = 0

            for (len in current.length downTo 1) {
                val sub = current.substring(0, len)
                val r = RULES_MAP[sub]
                if (r != null) {
                    matchedRule = r
                    matchLen = len
                    break
                }
            }

            if (matchedRule != null) {
                converted.append(matchedRule.value)
                buffer.delete(0, matchLen)
                if (matchedRule.next != null) {
                    buffer.insert(0, matchedRule.next)
                }
            } else {
                converted.append(buffer[0])
                buffer.deleteCharAt(0)
            }
        }
        return converted.toString()
    }
}
