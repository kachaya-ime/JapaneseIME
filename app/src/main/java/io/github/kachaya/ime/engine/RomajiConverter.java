package io.github.kachaya.ime.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ローマ字からかなへの変換、および文字種変換（全角/半角、ひらがな/カタカナ）を担当するスタティックユーティリティクラスです。
 * <p>
 * 変換テーブルはクラス内部に埋め込まれており、最長一致アルゴリズムによってローマ字をひらがなに置換します。
 * </p>
 */
public class RomajiConverter {

    /**
     * 単一のローマ字変換ルール（例: "ka" -> "か", "kk" -> "っ", next="k"）を保持するデータクラスです。
     */
    public static class Rule {
        public final String key;
        public final String value;
        public final String next;

        public Rule(String key, String value, String next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final Map<String, Rule> RULES_MAP = new HashMap<>();
    private static final Set<String> PREFIX_SET = new HashSet<>();
    private static final Map<Character, String> HALF_WIDTH_KATAKANA_MAP = new HashMap<>();

    private static final String[][] DEFAULT_ROMAJI_TABLE = {
            {"a", "あ"}, {"i", "い"}, {"u", "う"}, {"e", "え"}, {"o", "お"},
            {"ba", "ば"}, {"bi", "び"}, {"bu", "ぶ"}, {"be", "べ"}, {"bo", "ぼ"},
            {"bya", "びゃ"}, {"byi", "びぃ"}, {"byu", "びゅ"}, {"bye", "びぇ"}, {"byo", "びょ"},
            {"ca", "か"}, {"ci", "し"}, {"cu", "く"}, {"ce", "せ"}, {"co", "こ"},
            {"cha", "ちゃ"}, {"chi", "ち"}, {"chu", "ちゅ"}, {"che", "ちぇ"}, {"cho", "ちょ"},
            {"cya", "ちゃ"}, {"cyi", "ちぃ"}, {"cyu", "ちゅ"}, {"cye", "ちぇ"}, {"cyo", "ちょ"},
            {"da", "だ"}, {"di", "ぢ"}, {"du", "づ"}, {"de", "で"}, {"do", "ど"},
            {"dha", "でゃ"}, {"dhi", "でぃ"}, {"dhu", "でゅ"}, {"dhe", "でぇ"}, {"dho", "でょ"},
            {"dwa", "どぁ"}, {"dwi", "どぃ"}, {"dwu", "どぅ"}, {"dwe", "どぇ"}, {"dwo", "どぉ"},
            {"dya", "ぢゃ"}, {"dyi", "ぢぃ"}, {"dyu", "ぢゅ"}, {"dye", "ぢぇ"}, {"dyo", "ぢょ"},
            {"fa", "ふぁ"}, {"fi", "ふぃ"}, {"fu", "ふ"}, {"fe", "ふぇ"}, {"fo", "ふぉ"},
            {"fwa", "ふぁ"}, {"fwi", "ふぃ"}, {"fwu", "ふぅ"}, {"fwe", "ふぇ"}, {"fwo", "ふぉ"},
            {"fya", "ふゃ"}, {"fyi", "ふぃ"}, {"fyu", "ふゅ"}, {"fye", "ふぇ"}, {"fyo", "ふょ"},
            {"ga", "が"}, {"gi", "ぎ"}, {"gu", "ぐ"}, {"ge", "げ"}, {"go", "ご"},
            {"gwa", "ぐぁ"}, {"gwi", "ぐぃ"}, {"gwu", "ぐぅ"}, {"gwe", "ぐぇ"}, {"gwo", "ぐぉ"},
            {"gya", "ぎゃ"}, {"gyi", "ぎぃ"}, {"gyu", "ぎゅ"}, {"gye", "ぎぇ"}, {"gyo", "ぎょ"},
            {"ha", "は"}, {"hi", "ひ"}, {"hu", "ふ"}, {"he", "へ"}, {"ho", "ほ"},
            {"hya", "ひゃ"}, {"hyi", "ひぃ"}, {"hyu", "ひゅ"}, {"hye", "ひぇ"}, {"hyo", "ひょ"},
            {"ja", "じゃ"}, {"ji", "じ"}, {"ju", "じゅ"}, {"je", "じぇ"}, {"jo", "じょ"},
            {"jya", "じゃ"}, {"jyi", "じぃ"}, {"jyu", "じゅ"}, {"jye", "じぇ"}, {"jyo", "じょ"},
            {"ka", "か"}, {"ki", "き"}, {"ku", "く"}, {"ke", "け"}, {"ko", "こ"},
            {"kwa", "くぁ"}, {"kwi", "くぃ"}, {"kwu", "くぅ"}, {"kwe", "くぇ"}, {"kwo", "くぉ"},
            {"kya", "きゃ"}, {"kyi", "きぃ"}, {"kyu", "きゅ"}, {"kye", "きぇ"}, {"kyo", "きょ"},
            {"la", "ぁ"}, {"li", "ぃ"}, {"lu", "ぅ"}, {"le", "ぇ"}, {"lo", "ぉ"},
            {"lya", "ゃ"}, {"lyi", "ぃ"}, {"lyu", "ゅ"}, {"lye", "ぇ"}, {"lyo", "ょ"},
            {"ma", "ま"}, {"mi", "み"}, {"mu", "む"}, {"me", "め"}, {"mo", "も"},
            {"mya", "みゃ"}, {"myi", "みぃ"}, {"myu", "みゅ"}, {"mye", "みぇ"}, {"myo", "みょ"},
            {"na", "な"}, {"ni", "に"}, {"nu", "ぬ"}, {"ne", "ね"}, {"no", "の"},
            {"nya", "にゃ"}, {"nyi", "にぃ"}, {"nyu", "にゅ"}, {"nye", "にぇ"}, {"nyo", "にょ"},
            {"pa", "ぱ"}, {"pi", "ぴ"}, {"pu", "ぷ"}, {"pe", "ぺ"}, {"po", "ぽ"},
            {"pya", "ぴゃ"}, {"pyi", "ぴぃ"}, {"pyu", "ぴゅ"}, {"pye", "ぴぇ"}, {"pyo", "ぴょ"},
            {"ra", "ら"}, {"ri", "り"}, {"ru", "る"}, {"re", "れ"}, {"ro", "ろ"},
            {"rya", "りゃ"}, {"ryi", "りぃ"}, {"ryu", "りゅ"}, {"rye", "りぇ"}, {"ryo", "りょ"},
            {"sa", "さ"}, {"si", "し"}, {"su", "す"}, {"se", "せ"}, {"so", "そ"},
            {"sha", "しゃ"}, {"shi", "し"}, {"shu", "しゅ"}, {"she", "しぇ"}, {"sho", "しょ"},
            {"swa", "すぁ"}, {"swi", "すぃ"}, {"swu", "すぅ"}, {"swe", "すぇ"}, {"swo", "すぉ"},
            {"sya", "しゃ"}, {"syi", "しぃ"}, {"syu", "しゅ"}, {"sye", "しぇ"}, {"syo", "しょ"},
            {"ta", "た"}, {"ti", "ち"}, {"tu", "つ"}, {"te", "て"}, {"to", "と"},
            {"tha", "てゃ"}, {"thi", "てぃ"}, {"thu", "てゅ"}, {"the", "てぇ"}, {"tho", "てょ"},
            {"tsa", "つぁ"}, {"tsi", "つぃ"}, {"tsu", "つ"}, {"tse", "つぇ"}, {"tso", "つぉ"},
            {"twa", "とぁ"}, {"twi", "とぃ"}, {"twu", "とぅ"}, {"twe", "とぇ"}, {"two", "とぉ"},
            {"tya", "ちゃ"}, {"tyi", "ちぃ"}, {"tyu", "ちゅ"}, {"tye", "ちぇ"}, {"tyo", "ちょ"},
            {"va", "ゔぁ"}, {"vi", "ゔぃ"}, {"vu", "ゔ"}, {"ve", "ゔぇ"}, {"vo", "ゔぉ"},
            {"vwa", "ゔぁ"}, {"vwi", "ゔぃ"}, {"vwu", "ゔぅ"}, {"vwe", "ゔぇ"}, {"vwo", "ゔぉ"},
            {"vya", "ゔゃ"}, {"vyi", "ゔぃ"}, {"vyu", "ゔゅ"}, {"vye", "ゔぇ"}, {"vyo", "ゔょ"},
            {"wa", "わ"}, {"wi", "ゐ"}, {"wu", "う"}, {"we", "ゑ"}, {"wo", "を"},
            {"wha", "うぁ"}, {"whi", "うぃ"}, {"whu", "う"}, {"whe", "うぇ"}, {"who", "うぉ"},
            {"xa", "ぁ"}, {"xi", "ぃ"}, {"xu", "ぅ"}, {"xe", "ぇ"}, {"xo", "ぉ"},
            {"xka", "ヵ"}, {"xke", "ヶ"},
            {"xtu", "っ"}, {"xtsu", "っ"},
            {"xwa", "ゎ"},
            {"xya", "ゃ"}, {"xyi", "ぃ"}, {"xyu", "ゅ"}, {"xye", "ぇ"}, {"xyo", "ょ"},
            {"ya", "や"}, {"yi", "いぃ"}, {"yu", "ゆ"}, {"ye", "いぇ"}, {"yo", "よ"},
            {"za", "ざ"}, {"zi", "じ"}, {"zu", "ず"}, {"ze", "ぜ"}, {"zo", "ぞ"},
            {"zya", "じゃ"}, {"zyi", "じぃ"}, {"zyu", "じゅ"}, {"zye", "じぇ"}, {"zyo", "じょ"},

            {"n'", "ん"},
            {"n", "ん"},
            {"nn", "ん"},

            {"bb", "っ", "b"},
            {"cc", "っ", "c"},
            {"dd", "っ", "d"},
            {"ff", "っ", "f"},
            {"gg", "っ", "g"},
            {"hh", "っ", "h"},
            {"jj", "っ", "j"},
            {"kk", "っ", "k"},
            {"ll", "っ", "l"},
            {"mm", "っ", "m"},
            {"pp", "っ", "p"},
            {"rr", "っ", "r"},
            {"ss", "っ", "s"},
            {"tt", "っ", "t"},
            {"vv", "っ", "v"},
            {"ww", "っ", "w"},
            {"xx", "っ", "x"},
            {"yy", "っ", "y"},
            {"zz", "っ", "z"},

            {"-", "ー"},
            {"~", "\u301C"},
            {"[", "「"},
            {"]", "」"},
            {".", "。"},
            {",", "、"},

            {"z ", "　"}, {"z!", "！"}, {"z#", "♯"}, {"z(", "（"}, {"z)", "）"},
            {"z*", "※"}, {"z+", "±"}, {"z,", "‥"}, {"z-", "\u301C"}, {"z.", "…"},
            {"z/", "・"}, {"z0", "○"}, {"z1", "①"}, {"z2", "②"}, {"z3", "③"},
            {"z4", "④"}, {"z5", "⑤"}, {"z6", "⑥"}, {"z7", "⑦"}, {"z8", "⑧"},
            {"z9", "⑨"}, {"z:", "："}, {"z;", "；"}, {"z<", "〈"}, {"z=", "≠"},
            {"z>", "〉"}, {"z?", "？"}, {"z@", "◎"}, {"z[", "『"}, {"z\\", "＼"},
            {"z]", "』"}, {"zh", "←"}, {"zj", "↓"}, {"zk", "↑"}, {"zl", "→"},
            {"z{", "【"}, {"z}", "】"}, {"z~", "\uFF5E"},

    };

    private static void put(char k, String v) {
        HALF_WIDTH_KATAKANA_MAP.put(k, v);
    }

    static {
        for (String[] entry : DEFAULT_ROMAJI_TABLE) {
            String key = entry[0];
            String val = entry[1];
            String next = entry.length > 2 ? entry[2] : null;
            addRule(key, val, next);
        }

        put('ア', "ｱ"); put('イ', "ｲ"); put('ウ', "ｳ"); put('エ', "ｴ"); put('オ', "ｵ");
        put('カ', "ｶ"); put('キ', "ｷ"); put('ク', "ｸ"); put('ケ', "ｹ"); put('コ', "ｺ");
        put('サ', "ｻ"); put('シ', "ｼ"); put('ス', "ｽ"); put('セ', "ｾ"); put('ソ', "ｿ");
        put('タ', "ﾀ"); put('チ', "ﾁ"); put('ツ', "ﾂ"); put('テ', "ﾃ"); put('ト', "ﾄ");
        put('ナ', "ﾅ"); put('ニ', "ﾆ"); put('ヌ', "ﾇ"); put('ネ', "ﾈ"); put('ノ', "ﾉ");
        put('ハ', "ﾊ"); put('ヒ', "ﾋ"); put('フ', "ﾌ"); put('ヘ', "ﾍ"); put('ホ', "ﾎ");
        put('マ', "ﾏ"); put('ミ', "ﾐ"); put('ム', "ﾑ"); put('メ', "ﾒ"); put('モ', "ﾓ");
        put('ヤ', "ﾔ"); put('ユ', "ﾕ"); put('ヨ', "ﾖ");
        put('ラ', "ﾗ"); put('リ', "ﾘ"); put('ル', "ﾙ"); put('レ', "ﾚ"); put('ロ', "ﾛ");
        put('ワ', "ﾜ"); put('ヲ', "ｦ"); put('ン', "ﾝ");
        put('ァ', "ｧ"); put('ィ', "ｨ"); put('ゥ', "ｩ"); put('ェ', "ｴ"); put('ォ', "ｫ");
        put('ャ', "ｬ"); put('ュ', "ｭ"); put('ョ', "ｮ"); put('ッ', "ｯ");
        put('ガ', "ｶﾞ"); put('ギ', "ｷﾞ"); put('グ', "ｸﾞ"); put('ゲ', "ｹﾞ"); put('ゴ', "ｺﾞ");
        put('ザ', "ｻﾞ"); put('ジ', "ｼﾞ"); put('ズ', "ｽﾞ"); put('ゼ', "ｾﾞ"); put('ゾ', "ｿﾞ");
        put('ダ', "ﾀﾞ"); put('ヂ', "ﾁﾞ"); put('ヅ', "ﾂﾞ"); put('デ', "ﾃﾞ"); put('ド', "ﾄﾞ");
        put('バ', "ﾊﾞ"); put('ビ', "ﾋﾞ"); put('ブ', "ﾌﾞ"); put('ベ', "ﾍﾞ"); put('ボ', "ﾎﾞ");
        put('パ', "ﾊﾟ"); put('ピ', "ﾋﾟ"); put('プ', "ﾌﾟ"); put('ペ', "ﾍﾟ"); put('ポ', "ﾎﾟ");
        put('ヴ', "ｳﾞ"); put('ー', "ｰ");
        put('、', "､"); put('。', "｡"); put('・', "･"); put('「', "｢"); put('」', "｣");
    }

    private static void addRule(String key, String value, String next) {
        RULES_MAP.put(key, new Rule(key, value, next));
        for (int i = 1; i < key.length(); i++) {
            PREFIX_SET.add(key.substring(0, i));
        }
    }

    public static synchronized void setSeparator(String key, String value) {
        addRule(key, value, null);
    }

    private RomajiConverter() {
        // スタティックユーティリティクラス
    }

    /**
     * 与えられた文字がアルファベット (A-Z, a-z) かどうかを判定します。
     *
     * @param code Unicode コードポイント
     * @return アルファベットなら true
     */
    public static boolean isAlphabet(int code) {
        return ((code >= 'A' && code <= 'Z') || (code >= 'a' && code <= 'z'));
    }

    /**
     * 半角文字（ASCII）を対応する全角文字に変換します。
     *
     * @param code 半角文字のコードポイント
     * @return 対応する全角文字のコードポイント
     */
    public static int toWideLatin(int code) {
        if (code == 0x20) {
            return 0x3000;
        }
        if (code == '\u00A5') {
            return 0xFFE5;
        }
        if (0x21 <= code && code <= 0x7E) {
            return code - 0x20 + 0xFF00;
        }
        return code;
    }

    /**
     * 文字列内のすべての半角 ASCII 文字を全角に変換します。
     *
     * @param str 変換対象の文字列
     * @return 全角変換後の文字列。入力が null なら null。
     */
    public static CharSequence toWideLatin(CharSequence str) {
        if (str == null) {
            return null;
        }

        int len = str.length();
        StringBuilder buf = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            buf.append((char) toWideLatin(str.charAt(i)));
        }
        return buf;
    }

    /**
     * ひらがなを対応する全角カタカナに変換します。
     *
     * @param ch 変換対象のひらがな
     * @return 対応するカタカナ。範囲外なら元の文字。
     */
    public static char toWideKatakana(char ch) {
        if (ch >= 'ぁ' && ch <= 'ゖ') {
            return (char) (ch - 'ぁ' + 'ァ');
        }
        return ch;
    }

    /**
     * 文字列内のすべてのひらがなをカタカナに変換します。
     *
     * @param cs 変換対象の文字列
     * @return カタカナ変換後の文字列
     */
    public static String toWideKatakana(CharSequence cs) {
        if (cs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cs.length(); i++) {
            sb.append(toWideKatakana(cs.charAt(i)));
        }
        return sb.toString();
    }

    /**
     * 文字列内のすべての全角ひらがな・カタカナを半角カタカナに変換します。
     *
     * @param cs 変換対象の文字列
     * @return 半角カタカナ変換後の文字列
     */
    public static String toHalfKatakana(CharSequence cs) {
        if (cs == null) return "";
        StringBuilder sb = new StringBuilder();
        String katakana = toWideKatakana(cs);
        for (int i = 0; i < katakana.length(); i++) {
            char c = katakana.charAt(i);
            sb.append(HALF_WIDTH_KATAKANA_MAP.getOrDefault(c, String.valueOf(c)));
        }
        return sb.toString();
    }

    /**
     * ローマ字変換結果を格納する保持用データクラスです。
     */
    public static class ConversionResult {
        public final String convertedText;
        public final String pendingRomaji;

        public ConversionResult(String convertedText, String pendingRomaji) {
            this.convertedText = convertedText != null ? convertedText : "";
            this.pendingRomaji = pendingRomaji != null ? pendingRomaji : "";
        }
    }

    /**
     * ローマ字文字列を入力として受け取り、最長一致アルゴリズムで「かな文字列」と残りの「未確定ローマ字（pending）」に変換します。
     *
     * @param input 変換対象のローマ字文字列
     * @return ConversionResult (convertedText, pendingRomaji)
     */
    public static ConversionResult convert(CharSequence input) {
        if (input == null || input.length() == 0) {
            return new ConversionResult("", "");
        }

        StringBuilder converted = new StringBuilder();
        StringBuilder buffer = new StringBuilder(input);

        while (buffer.length() > 0) {
            String current = buffer.toString();

            Rule matchedRule = null;
            int matchLen = 0;

            // 最長一致検索: 先頭からの最長のキーを探す
            for (int len = current.length(); len > 0; len--) {
                String sub = current.substring(0, len);
                Rule r = RULES_MAP.get(sub);
                if (r != null) {
                    matchedRule = r;
                    matchLen = len;
                    break;
                }
            }

            if (matchedRule != null) {
                // 完全一致で、かつ全体がより長いルールのプレフィックスになっている場合は後続入力を待つ
                if (matchLen == current.length() && PREFIX_SET.contains(current)) {
                    break;
                }

                converted.append(matchedRule.value);
                buffer.delete(0, matchLen);
                if (matchedRule.next != null) {
                    buffer.insert(0, matchedRule.next);
                }
            } else {
                // 完全ルールに当てはまらないが、将来の入力でルールに一致する可能性があるプレフィックスの場合
                if (PREFIX_SET.contains(current)) {
                    break;
                }

                // 完全ルールにもプレフィックスにも一致しない場合、先頭1文字をそのまま確定して進む
                converted.append(buffer.charAt(0));
                buffer.deleteCharAt(0);
            }
        }

        return new ConversionResult(converted.toString(), buffer.toString());
    }

    /**
     * 残っている未確定ローマ字を強制的に可能な限り変換して文字列を構築します。
     *
     * @param pending 未確定ローマ字
     * @return かな変換後の文字列
     */
    public static String flushRomaji(CharSequence pending) {
        if (pending == null || pending.length() == 0) {
            return "";
        }

        StringBuilder converted = new StringBuilder();
        StringBuilder buffer = new StringBuilder(pending);

        while (buffer.length() > 0) {
            String current = buffer.toString();

            Rule matchedRule = null;
            int matchLen = 0;

            for (int len = current.length(); len > 0; len--) {
                String sub = current.substring(0, len);
                Rule r = RULES_MAP.get(sub);
                if (r != null) {
                    matchedRule = r;
                    matchLen = len;
                    break;
                }
            }

            if (matchedRule != null) {
                converted.append(matchedRule.value);
                buffer.delete(0, matchLen);
                if (matchedRule.next != null) {
                    buffer.insert(0, matchedRule.next);
                }
            } else {
                converted.append(buffer.charAt(0));
                buffer.deleteCharAt(0);
            }
        }
        return converted.toString();
    }
}
