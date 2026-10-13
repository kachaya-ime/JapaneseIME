package io.github.kachaya.ime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * data ディレクトリにある CSV データを読み込んでフィルタリング・ソートを行い、
 * 同じ読み（reading）ごとにまとめた上でバイナリ形式（surface, left_id, cost）で SQLite データベース（ipa_main_dict）に保存するツールクラスです。
 * <p>
 * また、内容確認用の TSV ファイル出力、
 * および接続コスト行列（matrix.bin）のリトルエンディアンからビッグエンディアンへの変換と
 * app/src/main/assets/ipa_matrix.bin への保存処理を統合しています。
 * </p>
 */
public class DictBuilder {

    /** システム辞書の DB ファイルベース名。 */
    public static final String SYSTEM_DICT_NAME = "system_dict";

    private static final String ASSETS_DIR = "app/src/main/assets/dictionary/";
    private static final String MATRIX_OUTPUT_FILENAME = "matrix.bin";
    private static final String POS_ID_BIN_OUTPUT_FILENAME = "pos_id.bin";

    private static final String TSV_OUTPUT_FILENAME = "dict_entries.tsv";
    private static final String DB_DUMP_FILENAME = "system_dict_dump.tsv";
//    private static final String LEFT_ID_DEF_FILENAME = "left-id.def";

    /**
     * 辞書構築に使用する CSV ファイルの一覧（テーブル）。
     */
    private static final List<String> TARGET_FILES = Arrays.asList(
            "Adj.csv",              // 形容詞
            "Adnominal.csv",        // 連体詞
            "Adverb.csv",           // 副詞
            "Auxil.csv",            // 助動詞
            "Conjunction.csv",      // 接続詞
            "Filler.csv",           // フィラー
            "Interjection.csv",     // 感動詞 挨拶が含まれる
            "Noun.adjv.csv",        // 名詞,形容動詞語幹
            "Noun.adverbal.csv",    // 名詞,副詞可能
            "Noun.csv",             // 名詞,一般
            "Noun.demonst.csv",     // 名詞,代名詞
            "Noun.nai.csv",         // 名詞,ナイ形容詞語幹
            "Noun.name.csv",        // 名詞,固有名詞,人名,名
            "Noun.number.csv",      // 名詞,数
            "Noun.org.csv",         // 名詞,固有名詞,組織
            "Noun.others.csv",      // 名詞,非自立
            "Noun.place.csv",       // 名詞,固有名詞,地域,一般
            "Noun.proper.csv",      // 名詞,固有名詞,一般
            "Noun.verbal.csv",      // 名詞,サ変接続
            "Others.csv",           // その他,間投
            "Postp-col.csv",        // 助詞,格助詞,連語
            "Postp.csv",            // 助詞
            "Prefix.csv",           // 接頭詞,名詞接続
            "Suffix.csv",           // 名詞,接尾
            "Symbol.csv",           // 記号
            "Verb.csv"              // 動詞
    );

    /** 辞書データ格納ディレクトリパス。 */
    private final Path dataPath;
    /** 作業ディレクトリパス。 */
    private final Path workPath;
    /** スキップされたエントリを記録するファイルライターのマップ。 */
    private final Map<String, BufferedWriter> skippedWriters = new LinkedHashMap<>();
    /** スキップされたエントリのカウントを保持するマップ。 */
    private final Map<String, Long> skippedCounts = new LinkedHashMap<>();

    /**
     * デフォルトのディレクトリパス設定で DictBuilder を初期化します。
     */
    public DictBuilder() {
        this.workPath = Paths.get("work");
        this.dataPath = workPath.resolve("mecab-ipadic");
    }

    /**
     * 辞書構築ツールのエントリーポイント。
     *
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        try {
            DictBuilder builder = new DictBuilder();
            builder.build();
        } catch (Exception e) {
            System.err.println("Error in DictBuilder: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * data ディレクトリ内の使用対象 CSV ファイルを走査し、全エントリを読み込んで
     * 読み（reading）の50音順にソートした上で TSV 出力を行います（最後のカラムに元ファイル名を追加）。
     *
     * @throws IOException ファイル読み込み・書き込みエラー等
     */
    public void build() throws IOException {
        new DictDataFetcher(workPath, dataPath).prepareData();

        if (!Files.exists(dataPath) || !Files.isDirectory(dataPath)) {
            System.err.println("Data directory not found: " + dataPath.toAbsolutePath());
            return;
        }

        Files.createDirectories(workPath);
        Path tsvOutputPath = workPath.resolve(TSV_OUTPUT_FILENAME);
        System.out.println("Starting dictionary building from: " + dataPath.toAbsolutePath());

        // 1. CSVデータの読み込み（同時に posStatsMap を構築）
        Map<Integer, PosStats> posStatsMap = new HashMap<>();
        List<DictEntry> entries = loadEntries(posStatsMap);

        // 2. 50音順ソート＆TSV出力
        sortAndWriteTsv(entries, tsvOutputPath);

        // 3. 同一読み・品詞グループごとのコスト調整（スクリプトランク考慮）
        entries = adjustCosts(entries);

        // 4. SQLiteデータベースの構築
        Path dbPath = workPath.resolve(SYSTEM_DICT_NAME + ".db");
        buildSqliteDatabase(entries, dbPath);

        // 5. DBのzip圧縮＆チェックサム生成
        Path sourceDbFile = dbPath;
        Path path = Paths.get(ASSETS_DIR);
        Path zipOutputFile = path.resolve(SYSTEM_DICT_NAME + ".db.zip");
        zipAndChecksumDatabase(sourceDbFile, zipOutputFile, path.resolve("dict_checksum.txt"));

        // 6. 各種定義・補助ファイルの処理
        Path dumpTsvPath = workPath.resolve(DB_DUMP_FILENAME);
        dumpDbToTsv(dbPath, dumpTsvPath);

        processLeftIdDef();
        processMatrixDef();
        processEmojiData();

        System.out.println("Dictionary building completed. Output saved to " + tsvOutputPath.toAbsolutePath());
    }

    /**
     * 対象の CSV ファイル（MeCab ipadic および Neologd）から生エントリを読み込み、
     * フィルタリングを適用して有効なエントリのリストを返します。
     *
     * @param posStatsMap 品詞統計情報を格納するマップ
     * @return フィルタリング済みの辞書エントリリスト
     * @throws IOException 入出力エラー
     */
    private List<DictEntry> loadEntries(Map<Integer, PosStats> posStatsMap) throws IOException {
        List<DictEntry> rawEntries = new ArrayList<>();
        skippedWriters.clear();
        skippedCounts.clear();

        try {
            // mecab-ipadic ファイルの読み込み
            for (String fileName : TARGET_FILES) {
                Path csvFile = dataPath.resolve(fileName);
                if (Files.exists(csvFile)) {
                    readIpadicCsvFileToRaw(csvFile, rawEntries, posStatsMap);
                } else {
                    System.out.println("Target CSV file not found (skipped): " + fileName);
                }
            }

        } finally {
            for (BufferedWriter writer : skippedWriters.values()) {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        System.out.println("Total raw loaded entries: " + rawEntries.size());

        List<DictEntry> filteredEntries = filterEntries(rawEntries);

        System.out.println("Total filtered entries: " + filteredEntries.size());
        System.out.println("Skipped summary:");
        for (Map.Entry<String, Long> entry : skippedCounts.entrySet()) {
            System.out.println("  - " + entry.getKey() + ": " + entry.getValue());
        }
        return filteredEntries;
    }

    /**
     * 辞書エントリを読み（reading）、表層形、ID、コスト等の順でソートし、
     * 中間確認用の TSV ファイルとして出力します。
     *
     * @param entries       辞書エントリのリスト
     * @param tsvOutputPath 出力先 TSV ファイルのパス
     * @throws IOException  入出力エラー
     */
    private void sortAndWriteTsv(List<DictEntry> entries, Path tsvOutputPath) throws IOException {
        System.out.println("Sorting entries by reading (Gojuon order)...");
        entries.sort(Comparator
                .comparing((DictEntry e) -> e.reading)
                .thenComparing(e -> e.surface)
                .thenComparingInt(e -> e.leftId)
                .thenComparingInt(e -> e.rightId)
                .thenComparingInt(e -> e.cost)
                .thenComparing(e -> e.fileName)
        );

        System.out.println("Outputting TSV to: " + tsvOutputPath.toAbsolutePath());
        try (BufferedWriter writer = Files.newBufferedWriter(tsvOutputPath, StandardCharsets.UTF_8)) {
            for (DictEntry entry : entries) {
                writer.write(entry.reading);
                writer.write('\t');
                writer.write(entry.surface);
                writer.write('\t');
                writer.write(String.valueOf(entry.leftId));
                writer.write('\t');
                writer.write(entry.toFeatureString());
                writer.write('\t');
                writer.write(String.valueOf(entry.cost));
                writer.write('\t');
                writer.write(entry.fileName);
                writer.newLine();
            }
        }
    }

    /**
     * 同一の読みと品詞IDを持つエントリグループに対し、
     * 文字種ランク（ひらがな、カタカナ、漢字など）と既存コストに基づいてコストを再割り当て・調整します。
     *
     * @param entries 調整前の辞書エントリリスト
     * @return 調整後の辞書エントリリスト
     */
    private List<DictEntry> adjustCosts(List<DictEntry> entries) {
        System.out.println("Grouping entries by reading and POS (leftId)...");
        Map<ReadingPosKey, List<DictEntry>> groupedByReadingAndPos = new LinkedHashMap<>();
        for (DictEntry entry : entries) {
            ReadingPosKey key = new ReadingPosKey(entry.reading, entry.leftId);
            groupedByReadingAndPos.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }

        System.out.println("Processing groups and reassigning costs based on script rank...");
        List<DictEntry> updatedEntries = new ArrayList<>();

        for (Map.Entry<ReadingPosKey, List<DictEntry>> mapEntry : groupedByReadingAndPos.entrySet()) {
            List<DictEntry> list = mapEntry.getValue();
            if (list.size() != 1) {
                List<Integer> sortedCosts = list.stream().map(e -> e.cost).sorted().toList();
                List<DictEntry> sortedEntries = new ArrayList<>(list);
                sortedEntries.sort(Comparator
                        .comparingInt((DictEntry e) -> getScriptRank(e.surface))
                        .thenComparingInt(e -> e.cost)
                        .thenComparing(e -> e.surface)
                );

                List<DictEntry> processedList = new ArrayList<>();
                for (int i = 0; i < sortedEntries.size(); i++) {
                    processedList.add(sortedEntries.get(i).withCost(sortedCosts.get(i)));
                }

                updatedEntries.addAll(processedList);
            } else {
                updatedEntries.addAll(list);
            }
        }

        return updatedEntries;
    }

    /**
     * 辞書エントリを読みごとにグループ化し、SQLite データベース（dictionary テーブル）にシリアライズして保存します。
     *
     * @param entries 辞書エントリのリスト
     * @param dbPath  SQLite データベースファイルのパス
     * @throws IOException 入出力エラー
     */
    private void buildSqliteDatabase(List<DictEntry> entries, Path dbPath) throws IOException {
        System.out.println("Grouping entries by reading and saving to SQLite database...");
        Map<String, List<DictEntry>> groupedEntries = new LinkedHashMap<>();
        for (DictEntry entry : entries) {
            List<DictEntry> list = groupedEntries.computeIfAbsent(entry.reading, k -> new ArrayList<>());
            list.add(entry);
        }

        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
        try {
            Files.deleteIfExists(dbPath);
        } catch (IOException e) {
            // ignore
        }

        System.out.println("Creating SQLite database at: " + dbPath.toAbsolutePath());
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = OFF;");
                stmt.execute("PRAGMA synchronous = OFF;");
            }

            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS dictionary (`key` TEXT PRIMARY KEY, `value` BLOB NOT NULL) WITHOUT ROWID;");
            }

            String insertSql = "INSERT INTO dictionary (`key`, `value`) VALUES (?, ?);";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (Map.Entry<String, List<DictEntry>> mapEntry : groupedEntries.entrySet()) {
                    String reading = mapEntry.getKey();
                    List<DictEntry> list = mapEntry.getValue();

                    List<Entry> dictSerializationEntries = new ArrayList<>();
                    for (DictEntry e : list) {
                        dictSerializationEntries.add(new Entry(e.surface, e.leftId, e.cost));
                    }
                    byte[] bytes = serialize(dictSerializationEntries);

                    pstmt.setString(1, reading);
                    pstmt.setBytes(2, bytes);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            throw new IOException("Failed to create SQLite database at " + dbPath, e);
        }

        System.out.println("SQLite database successfully saved to: " + dbPath.toAbsolutePath());
    }

    /**
     * 生成された JDBM データベースファイルを zip 圧縮して assets に保存し、
     * さらにその SHA-256 チェックサムを計算してテキストファイルに出力します。
     *
     * @param sourceDbFile  元の DB ファイルパス
     * @param zipOutputFile 出力先 zip ファイルパス
     * @param checksumFile  チェックサム出力ファイルパス
     * @throws IOException   入出力エラー
     */
    private void zipAndChecksumDatabase(Path sourceDbFile, Path zipOutputFile, Path checksumFile) throws IOException {
        if (zipOutputFile.getParent() != null) {
            Files.createDirectories(zipOutputFile.getParent());
        }
        System.out.println("Zipping database to: " + zipOutputFile.toAbsolutePath());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipOutputFile));
             InputStream is = Files.newInputStream(sourceDbFile)) {
            ZipEntry entry = new ZipEntry(sourceDbFile.getFileName().toString());
            zos.putNextEntry(entry);
            byte[] buf = new byte[16 * 1024];
            int len;
            while ((len = is.read(buf)) > 0) {
                zos.write(buf, 0, len);
            }
            zos.closeEntry();
        }
        System.out.println("Database successfully zipped and saved to assets.");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(zipOutputFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String checksum = sb.toString();

            if (checksumFile.getParent() != null) {
                Files.createDirectories(checksumFile.getParent());
            }
            Files.writeString(checksumFile, checksum, StandardCharsets.UTF_8);
            System.out.println("Dict checksum (SHA-256) written to: " + checksumFile.toAbsolutePath());
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Failed to compute SHA-256 checksum: " + e.getMessage());
        }
    }

    /**
     * left-id.def ファイルを処理してバイナリ形式に変換します。
     */
    private void processLeftIdDef() {
        Path inputLeftIdDefPath = dataPath.resolve("left-id.def");
        if (Files.exists(inputLeftIdDefPath)) {
            try {
                Path outputIdDefBinPath = Paths.get(ASSETS_DIR).resolve(POS_ID_BIN_OUTPUT_FILENAME);
                convertLeftIdDefToBinary(inputLeftIdDefPath, outputIdDefBinPath);
            } catch (Exception e) {
                System.err.println("Failed to process left-id.def to binary: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("Source left-id.def not found at: " + inputLeftIdDefPath.toAbsolutePath());
        }
    }

    /**
     * matrix.def ファイルを処理してビッグエンディアンのバイナリ形式に変換します。
     */
    private void processMatrixDef() {
        Path inputMatrixDefPath = dataPath.resolve("matrix.def");
        Path outputMatrixPath = Paths.get(ASSETS_DIR).resolve(MATRIX_OUTPUT_FILENAME);
        if (Files.exists(inputMatrixDefPath)) {
            try {
                convertMatrixDefToBigEndianBin(inputMatrixDefPath, outputMatrixPath);
            } catch (Exception e) {
                System.err.println("Failed to process matrix.def: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("Source matrix.def not found at: " + inputMatrixDefPath.toAbsolutePath());
        }
    }

    /**
     * 絵文字データファイル（emoji-test.txt / emojis.json）を処理して絵文字アセットを生成します。
     */
    private void processEmojiData() {
        Path inputEmojiPath = workPath.resolve("downloads").resolve("emoji-test.txt");
        if (!Files.exists(inputEmojiPath)) {
            inputEmojiPath = workPath.resolve("emoji-test.txt");
        }
        Path outputEmojiPath = Paths.get(ASSETS_DIR).resolve("emoji.txt");
        if (Files.exists(inputEmojiPath)
                || Files.exists(new File(ASSETS_DIR + "emojis.json").toPath())
                || Files.exists(new File("app/src/main/assets/emojis.json").toPath())) {
            try {
                convertEmoji(inputEmojiPath, outputEmojiPath);
            } catch (Exception e) {
                System.err.println("Failed to process emoji data: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("Source emoji-test.txt not found at: " + inputEmojiPath.toAbsolutePath());
        }
    }


    /**
     * 品詞ごとのコスト統計情報を保持するクラスです。
     */
    public static class PosStats {
        /** 最小コスト。 */
        public int min = Integer.MAX_VALUE;
        /** 最大コスト。 */
        public int max = Integer.MIN_VALUE;
        /** コストの合計値。 */
        public long sum = 0;
        /** エントリ数。 */
        public int count = 0;

        /**
         * 新しいコスト値を統計に追加します。
         *
         * @param cost 追加するコスト値
         */
        public void add(int cost) {
            if (cost < min) min = cost;
            if (cost > max) max = cost;
            sum += cost;
            count++;
        }

    }

    /**
     * 読み（reading）と品詞 ID（leftId）を組み合わせたマップ用のキー保持クラスです。
     */
    public static class ReadingPosKey {
        /** 読み。 */
        public final String reading;
        /** 左文脈ID（品詞ID）。 */
        public final int leftId;

        /**
         * ReadingPosKey を初期化します。
         *
         * @param reading 読み
         * @param leftId  左文脈ID
         */
        public ReadingPosKey(String reading, int leftId) {
            this.reading = reading;
            this.leftId = leftId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReadingPosKey that = (ReadingPosKey) o;
            return leftId == that.leftId && Objects.equals(reading, that.reading);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reading, leftId);
        }
    }


    /**
     * data/left-id.def （EUC-JP エンコーディング）を読み込み、
     * id に対する文字列が格納された1次元配列のバイナリ形式（pos_id.bin）に変換して assets に保存します。
     * 先頭には配列の次元（要素数 / size）が格納されます。
     *
     * @param inputDefPath 入力 left-id.def のパス
     * @param outputPath 出力 pos_id.bin のパス
     * @throws IOException 入出力エラー
     */
    public static void convertLeftIdDefToBinary(Path inputDefPath, Path outputPath) throws IOException {
        System.out.println("Converting left-id.def (EUC-JP) to binary pos_id.bin: " + inputDefPath.toAbsolutePath());
        if (!Files.exists(inputDefPath)) {
            throw new FileNotFoundException("left-id.def not found at: " + inputDefPath.toAbsolutePath());
        }

        List<String> lines = Files.readAllLines(inputDefPath, Charset.forName("EUC-JP"));
        Map<Integer, String> idMap = new HashMap<>();
        int maxId = -1;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx != -1) {
                try {
                    int id = Integer.parseInt(line.substring(0, spaceIdx).trim());
                    String posStr = line.substring(spaceIdx + 1).trim();
                    idMap.put(id, posStr);
                    if (id > maxId) {
                        maxId = id;
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        int size = maxId + 1;
        String[] posArray = new String[size];
        for (int i = 0; i < size; i++) {
            posArray[i] = idMap.getOrDefault(i, "");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeShort((short) size);
            for (int i = 0; i < size; i++) {
                dos.writeUTF(posArray[i]);
            }
        }

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.write(outputPath, baos.toByteArray());
        System.out.println("Successfully saved binary pos_id.bin to: " + outputPath.toAbsolutePath());
    }

    /**
     * data/matrix.def （テキスト形式）を読み込み、ビッグエンディアンの binary 形式（matrix.bin）に変換して assets に保存します。
     *
     * @param inputDefPath  入力 matrix.def のパス
     * @param outputPath    出力 matrix.bin のパス
     * @throws IOException  入出力エラー
     */
    public static void convertMatrixDefToBigEndianBin(Path inputDefPath, Path outputPath) throws IOException {
        System.out.println("Converting matrix.def to Big Endian matrix.bin: " + inputDefPath.toAbsolutePath());
        if (!Files.exists(inputDefPath)) {
            throw new FileNotFoundException("matrix.def not found at: " + inputDefPath.toAbsolutePath());
        }

        int rows;
        int cols;
        short[][] matrix;

        try (BufferedReader reader = Files.newBufferedReader(inputDefPath, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException("matrix.def is empty");
            }
            line = line.trim();
            String[] dims = line.split("\\s+");
            if (dims.length < 2) {
                throw new IllegalArgumentException("Invalid matrix.def header: " + line);
            }
            rows = Integer.parseInt(dims[0]);
            cols = Integer.parseInt(dims[1]);

            matrix = new short[rows][cols];

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                int r = Integer.parseInt(parts[0]);
                int c = Integer.parseInt(parts[1]);
                short cost = Short.parseShort(parts[2]);
                if (r >= 0 && r < rows && c >= 0 && c < cols) {
                    matrix[r][c] = cost;
                }
            }
        }

        int requiredLength = 4 + rows * cols * 2;
        ByteBuffer outBuffer = ByteBuffer.allocate(requiredLength);
        outBuffer.order(ByteOrder.BIG_ENDIAN);
        outBuffer.putShort((short) rows);
        outBuffer.putShort((short) cols);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                outBuffer.putShort(matrix[r][c]);
            }
        }

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.write(outputPath, outBuffer.array());
        System.out.println("Successfully saved Big Endian matrix.bin to: " + outputPath.toAbsolutePath());
    }

    /**
     * emojis.json または emoji-test.txt を読み込み、グループごとに整理された絵文字アセットファイル（emoji.txt）を生成します。
     *
     * @param inputPath     入力ファイルのパス
     * @param outputTxtPath 出力先テキストファイルのパス
     * @throws IOException 変換処理中の例外
     */
    public static void convertEmoji(Path inputPath, Path outputTxtPath) throws IOException {
        System.out.println("Converting emoji data to: " + outputTxtPath.toAbsolutePath());
        Map<String, List<String>> groupMap = new LinkedHashMap<>();

        File jsonFile = new File(ASSETS_DIR + "emojis.json");
        if (!jsonFile.exists()) {
            jsonFile = new File("app/src/main/assets/emojis.json");
        }
        if (jsonFile.exists()) {
            String jsonContent = Files.readString(jsonFile.toPath());
            BufferedReader reader = new BufferedReader(new StringReader(jsonContent));
            String line;
            String currentGroup = "Smileys & Emotion";
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.endsWith("\": [")) {
                    int quote1 = line.indexOf('"');
                    int quote2 = line.indexOf('"', quote1 + 1);
                    if (quote1 != -1 && quote2 != -1) {
                        currentGroup = line.substring(quote1 + 1, quote2);
                    }
                } else if (line.contains("\"emoji\":")) {
                    int idx = line.indexOf("\"emoji\"");
                    int quote3 = line.indexOf('"', line.indexOf(':', idx));
                    int quote4 = line.indexOf('"', quote3 + 1);
                    if (quote3 != -1 && quote4 != -1) {
                        String emoji = line.substring(quote3 + 1, quote4);
                        groupMap.computeIfAbsent(currentGroup, k -> new ArrayList<>())
                                .add(emoji);
                    }
                }
            }
        }

        if (groupMap.isEmpty()) {
            List<String> lines = new ArrayList<>();
            if (Files.exists(inputPath)) {
                try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
                    String fileLine;
                    while ((fileLine = reader.readLine()) != null) {
                        lines.add(fileLine);
                    }
                }
            }

            Set<String> componentCodePoints = new HashSet<>();
            String currentGroup = "Smileys & Emotion";
            for (String txtLine : lines) {
                txtLine = txtLine.trim();
                if (txtLine.isEmpty()) continue;

                if (txtLine.startsWith("# group:")) {
                    currentGroup = txtLine.substring(8).trim();
                    continue;
                }

                if (txtLine.startsWith("#")) {
                    continue;
                }

                int semicolonIdx = txtLine.indexOf(';');
                if (semicolonIdx != -1) {
                    if (currentGroup.equalsIgnoreCase("Component") || currentGroup.toLowerCase().contains("component") || currentGroup.toLowerCase().contains("component")) {
                        String codePointsPart = txtLine.substring(0, semicolonIdx).trim();
                        for (String cp : codePointsPart.split("\\s+")) {
                            if (!cp.isEmpty()) {
                                componentCodePoints.add(cp);
                            }
                        }
                    }
                }
            }

            currentGroup = "Smileys & Emotion";
            for (String txtLine : lines) {
                txtLine = txtLine.trim();
                if (txtLine.isEmpty()) continue;

                if (txtLine.startsWith("# group:")) {
                    currentGroup = txtLine.substring(8).trim();
                    continue;
                }

                if (txtLine.startsWith("#")) {
                    continue;
                }

                int semicolonIdx = txtLine.indexOf(';');
                if (semicolonIdx != -1 && txtLine.contains("fully-qualified")) {
                    if (currentGroup.equalsIgnoreCase("Component") || currentGroup.toLowerCase().contains("component") || currentGroup.toLowerCase().contains("compornent")) {
                        continue;
                    }

                    String codePointsPart = txtLine.substring(0, semicolonIdx).trim();
                    boolean containsComponentCode = false;
                    for (String cp : codePointsPart.split("\\s+")) {
                        if (componentCodePoints.contains(cp)) {
                            containsComponentCode = true;
                            break;
                        }
                    }
                    if (containsComponentCode) {
                        continue;
                    }

                    int hashIdx = txtLine.indexOf('#');
                    if (hashIdx != -1) {
                        String commentPart = txtLine.substring(hashIdx + 1).trim();
                        String[] parts = commentPart.split("\\s+", 3);
                        if (parts.length >= 1) {
                            String emoji = parts[0];
                            groupMap.computeIfAbsent(currentGroup, k -> new ArrayList<>())
                                    .add(emoji);
                        }
                    }
                }
            }
        }

        if (groupMap.isEmpty()) {
            List<String> smileys = new ArrayList<>();
            smileys.add("😀");
            smileys.add("😂");
            smileys.add("😍");
            smileys.add("👍");
            smileys.add("❤️");
            groupMap.put("Smileys & Emotion", smileys);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : groupMap.entrySet()) {
            sb.append("# ").append(entry.getKey()).append("\n");
            for (String emoji : entry.getValue()) {
                sb.append(emoji).append("\n");
            }
        }

        if (outputTxtPath.getParent() != null) {
            Files.createDirectories(outputTxtPath.getParent());
        }
        Files.writeString(outputTxtPath, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Successfully generated emoji.txt at: " + outputTxtPath.toAbsolutePath());
    }

    /**
     * SQLite データベースファイルから内容を読み込み、確認用の TSV ファイルとして出力します。
     * 1行の形式: reading \t surface \t id \t cost
     *
     * @param dbPath         SQLite データベースのパス
     * @param outputTsvPath  出力先 TSV ファイルパス
     * @throws IOException   入出力エラー
     */
    public static void dumpDbToTsv(Path dbPath, Path outputTsvPath) throws IOException {
        Path dbFile = dbPath.getFileName().toString().endsWith(".db") ? dbPath : dbPath.resolveSibling(dbPath.getFileName() + ".db");
        if (!Files.exists(dbFile)) {
            System.err.println("DB file not found for dump: " + dbFile);
            return;
        }

        System.out.println("Dumping DB to TSV: " + outputTsvPath.toAbsolutePath());
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC");
             BufferedWriter writer = Files.newBufferedWriter(outputTsvPath, StandardCharsets.UTF_8)) {

            long totalEntries = 0;
            while (rs.next()) {
                String reading = rs.getString(1);
                byte[] valueBytes = rs.getBytes(2);

                try {
                    List<Entry> entries = deserialize(valueBytes);
                    for (Entry e : entries) {
                        writer.write(reading);
                        writer.write('\t');
                        writer.write(e.surface);
                        writer.write('\t');
                        writer.write(String.valueOf(e.posId));
                        writer.write('\t');
                        writer.write(String.valueOf(e.cost));
                        writer.newLine();
                        totalEntries++;
                    }
                } catch (IOException e) {
                    System.err.println("Error reading DB entries for reading: " + reading);
                }
            }
            System.out.println("Dump completed. Total entries written: " + totalEntries);
        } catch (SQLException e) {
            throw new IOException("Failed to dump SQLite DB to TSV", e);
        }
    }


    /**
     * MeCab-ipadic の CSV ファイル（EUC-JP）を読み込んでフィルタリングせずに生のリストに追加します。
     *
     * @param csvFile     処理対象の CSV ファイルパス
     * @param rawEntries  生のエントリリスト
     * @param posStatsMap 品詞統計マップ
     * @throws IOException ファイル読み込みエラー
     */
    protected void readIpadicCsvFileToRaw(Path csvFile, List<DictEntry> rawEntries, Map<Integer, PosStats> posStatsMap) throws IOException {
        System.out.println("Processing ipadic CSV file: " + csvFile.getFileName());
        String fileName = csvFile.getFileName().toString();

        try (BufferedReader reader = Files.newBufferedReader(csvFile, Charset.forName("EUC-JP"))) {
            String line;
            long lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.trim().isEmpty()) {
                    continue; // 空行はスキップ
                }
                DictEntry entry = DictEntry.fromCsvLine(line, fileName, lineCount);
                if (entry != null) {
                    rawEntries.add(entry);
                    PosStats stats = posStatsMap.computeIfAbsent(entry.leftId, k -> new PosStats());
                    stats.add(entry.cost);
                }
            }
        }
    }

    /**
     * 読み込まれた生エントリのリストを検証し、不要なエントリを除外します。
     *
     * @param rawEntries 生のエントリリスト
     * @return フィルタリング済みのエントリリスト
     */
    protected List<DictEntry> filterEntries(List<DictEntry> rawEntries) {
        List<DictEntry> filtered = new ArrayList<>();

        for (DictEntry entry : rawEntries) {

            // 原形が有効か
            if (!isValidBase(entry.base)) {
                logSkipped("base", entry);
                continue;
            }

            // 読みが有効か
            if (!isValidReading(entry.reading)) {
                logSkipped("reading", entry);
                continue;
            }

            // 助詞で表記がカタカナを含むものはスキップ
            if ("助詞".equals(entry.pos) && hasKatakana(entry.surface)) {
                logSkipped("particle_surface_contains_katakana", entry);
                continue;
            }

            // 表記が有効か
            if (!isValidSurface(entry.surface)) {
                logSkipped("surface", entry);
                continue;
            }

            switch (entry.pos) {
                case "名詞" -> {
                    switch (entry.pos1) {
                        case "固有名詞" -> {
                            switch (entry.pos2) {
                                case "組織" -> {
                                    logSkipped("proper_organization", entry);
                                    continue;
                                }
                                case "人名" -> {
                                    switch (entry.pos3) {
                                        case "一般" -> {
                                            logSkipped("proper_person_general", entry);
                                            continue;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                case "記号" -> {
                    switch (entry.pos1) {
                        case "一般" -> {
                            switch (entry.reading) {
                                case "かおもじ", "かおもじー" -> {
                                    logSkipped("kaomoji", entry);
                                    continue;
                                }
                            }
                        }
                    }
                }
            }

            filtered.add(entry);
        }

        return filtered;
    }

    /**
     * 共通化されたスキップログ出力メソッド（DictEntry用）。reason と entry のみを引数に取り、ログを記録します。
     *
     * @param reason スキップ理由（キー）
     * @param entry  対象の DictEntry
     */
    protected synchronized void logSkipped(String reason, DictEntry entry) {
        skippedCounts.merge(reason, 1L, Long::sum);
        BufferedWriter writer = skippedWriters.get(reason);
        if (writer == null) {
            try {
                Path path = workPath.resolve("skipped_" + reason + ".tsv");
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                skippedWriters.put(reason, writer);
                System.out.println("Skipped log (" + reason + ") saved to: " + path.toAbsolutePath());
            } catch (IOException e) {
                // ignore
            }
        }
        if (writer != null) {
            try {
                writer.write(entry.fileName + "\t" + entry.lineNumber + "\t" + entry.originalLine);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // 無視またはログ出力
            }
        }
    }

    /**
     * MeCab形式のCSV行（1行）をすべて保持するデータクラス。
     */
    public static class DictEntry {
        public final String surface;       // 1. 表層形（例: "学校", "走っ"）
        public final int leftId;           // 2. 左文脈ID（接続行列のインデックス）
        public final int rightId;          // 3. 右文脈ID（接続行列のインデックス）
        public final int cost;             // 4. 単語コスト（出現頻度。小さいほど優先）
        public final String pos;           // 5. 品詞 大分類（例: "名詞", "動詞"）
        public final String pos1;          // 6. 品詞 細分類1（細分類1 / sub1）
        public final String pos2;          // 7. 品詞 細分類2（細分類2 / sub2）
        public final String pos3;          // 8. 品詞 細分類3（細分類3 / sub3）
        public final String ctype;         // 9. 活用型（Conjugation type。例: "五段・ラ行", "*"）
        public final String cform;         // 10. 活用形（Conjugation form。例: "連用形", "*"）
        public final String base;          // 11. 原形（Base form / Lemma。例: "走る"）
        public final String reading;       // 12. 読み（カタカナ表記 / yomi。例: "ガッコウ"）
        public final String rawReading;    // 元の読み（カタカナ等）
        public final String fileName;      // 元ファイル名
        public final String originalLine;  // 元の行
        public final long lineNumber;      // 行番号

        /**
         * DictEntry を初期化します。
         *
         * @param surface      表層形
         * @param leftId       左文脈ID
         * @param rightId      右文脈ID
         * @param cost         コスト
         * @param pos          品詞大分類
         * @param pos1         品詞細分類1
         * @param pos2         品詞細分類2
         * @param pos3         品詞細分類3
         * @param ctype        活用型
         * @param cform        活用形
         * @param base         原形
         * @param reading      読み
         * @param rawReading   元の読み
         * @param fileName     元ファイル名
         * @param originalLine 元の行
         * @param lineNumber   行番号
         */
        public DictEntry(
                String surface,
                int leftId,
                int rightId,
                int cost,
                String pos,
                String pos1,
                String pos2,
                String pos3,
                String ctype,
                String cform,
                String base,
                String reading,
                String rawReading,
                String fileName,
                String originalLine,
                long lineNumber
        ) {
            this.surface = Objects.requireNonNull(surface, "surface cannot be null");
            this.leftId = leftId;
            this.rightId = rightId;
            this.cost = cost;
            this.pos = Objects.requireNonNull(pos, "pos cannot be null");
            this.pos1 = Objects.requireNonNull(pos1, "pos1 cannot be null");
            this.pos2 = Objects.requireNonNull(pos2, "pos2 cannot be null");
            this.pos3 = Objects.requireNonNull(pos3, "pos3 cannot be null");
            this.ctype = Objects.requireNonNull(ctype, "ctype cannot be null");
            this.cform = Objects.requireNonNull(cform, "cform cannot be null");
            this.base = Objects.requireNonNull(base, "base cannot be null");
            this.reading = Objects.requireNonNull(reading, "reading cannot be null");
            this.rawReading = Objects.requireNonNull(rawReading, "rawReading cannot be null");
            this.fileName = Objects.requireNonNull(fileName, "fileName cannot be null");
            this.originalLine = Objects.requireNonNull(originalLine, "originalLine cannot be null");
            this.lineNumber = lineNumber;
        }

        /**
         * 指定されたコストを持つ新しい DictEntry インスタンスを返します。
         *
         * @param newCost 新しいコスト
         * @return DictEntry インスタンス
         */
        public DictEntry withCost(int newCost) {
            return new DictEntry(
                    surface, leftId, rightId, newCost,
                    pos, pos1, pos2, pos3,
                    ctype, cform, base,
                    reading, rawReading, fileName, originalLine, lineNumber
            );
        }

        /**
         * MeCab形式のCSV行（1行）をすべてパースしてDictEntryを生成します。
         *
         * @param csvLine CSVの1行
         * @param fileName ファイル名
         * @param lineNumber 行番号
         * @return すべてのフィールドが埋まったDictEntry。フォーマット不正時はnull。
         */
        public static DictEntry fromCsvLine(String csvLine, String fileName, long lineNumber) {
            if (csvLine == null || csvLine.isBlank()) {
                return null;
            }

            // カンマで分割。末尾の空カラム（発音など）を切り捨てないよう、limitに-1を指定
            String[] tokens = csvLine.split(",", -1);
            if (tokens.length < 13) {
                return null;
            }

            try {
                String surface = tokens[0];
                int leftId = Integer.parseInt(tokens[1]);
                int rightId = Integer.parseInt(tokens[2]);
                int cost = Integer.parseInt(tokens[3]);

                if (leftId != rightId) {
                    return null;
                }
                if (cost <= 0) {
                    return null;
                }

                String pos = tokens[4];
                String pos1 = tokens[5];
                String pos2 = tokens[6];
                String pos3 = tokens[7];
                String ctype = tokens[8];
                String cform = tokens[9];

                String base = tokens[10];
                String rawReading = tokens[11];
                String reading = toHiragana(rawReading);

                return new DictEntry(
                        surface, leftId, rightId, cost,
                        pos, pos1, pos2, pos3,
                        ctype, cform, base,
                        reading, rawReading, fileName, csvLine, lineNumber
                );
            } catch (NumberFormatException e) {
                // IDやコストが数値として不正な行はスキップ
                return null;
            }
        }

        /**
         * MeCabの標準的な「品詞特徴量（Feature）」文字列に再結合して返します。
         * デバッグやコーパスへの書き戻しに便利です。
         *
         * @return 例: "名詞,一般,*,*,*,*,学校,ガッコウ"
         */
        public String toFeatureString() {
            return String.join(",",
                    pos, pos1, pos2, pos3,
                    ctype, cform, base,
                    reading
            );
        }
    }

    /**
     * 文字列内の全角カタカナをひらがなに変換します。
     *
     * @param s 変換対象の文字列
     * @return 変換後のひらがな文字列
     */
    public static String toHiragana(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 'ァ' && c <= 'ヶ' || c == 'ヽ' || c == 'ヾ') {
                sb.append((char) (c - 'ァ' + 'ぁ'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 文字列の文字種ランクを取得します。
     *
     * @param s 文字列
     * @return スクリプトランク
     */
    public static int getScriptRank(String s) {
        if (s == null) return 0;
        if (isValidReading(s)) {
            return 2;
        } else if (isKatakanaOnly(s)) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * 文字列がカタカナ（および長音）のみで構成されているか判定します。
     *
     * @param s 判定対象の文字列。null の場合は false を返します。
     * @return カタカナのみの場合は true
     */
    public static boolean isKatakanaOnly(String s) {
        return s != null && s.matches("^[\\p{IsKatakana}ー]+$");
    }

    /**
     * 文字列がカタカナ（および長音）を少なくとも1文字含んでいるか判定します。
     *
     * @param s 判定対象の文字列。null の場合は false を返します。
     * @return カタカナを含む場合は true
     */
    public static boolean hasKatakana(String s) {
        return s != null && s.matches(".*[\\p{IsKatakana}ー].*");
    }

    /**
     * 原形が有効かどうかを判定します。
     *
     * @param s 原形文字列
     * @return 有効な場合は true
     */
    public static boolean isValidBase(String s) {
        if (s == null) return false;
        // 「じゅうごせんち	名詞,固有名詞,一般,*,*,*,15cm,じゅうごせんち」のようなエントリーが大量にある
        // 半角英数字を含むものはスキップ
        if (s.matches(".*[0-9A-Za-z:].*")) return false;

        return true;
    }

    /**
     * 読みが有効なひらがなであるかを判定します。
     *
     * @param s 読み文字列
     * @return 有効な場合は true
     */
    public static boolean isValidReading(String s) {
        return s != null && s.matches("^[ぁ-ゖー]+$");
    }

    /**
     * 表層形が有効であるかを判定します。
     *
     * @param s 表層形文字列
     * @return 有効な場合は true
     */
    public static boolean isValidSurface(String s) {
        return s != null && s.matches("^[\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}ー〆々\\p{So}]+$");
    }

    /**
     * 辞書エントリのリストをバイト配列にシリアライズします。
     *
     * @param entries エントリのリスト
     * @return シリアライズされたバイト配列
     * @throws IOException 入出力エラーが発生した場合
     */
    public static byte[] serialize(List<Entry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(entries.size());
            for (Entry e : entries) {
                dos.writeUTF(e.surface);
                dos.writeShort((short) e.posId);
                dos.writeShort((short) e.cost);
            }
        }
        return baos.toByteArray();
    }

    /**
     * バイト配列から辞書エントリのリストをデシリアライズします。
     *
     * @param data バイト配列
     * @return 復元された辞書エントリのリスト
     * @throws IOException 入出力エラーが発生した場合
     */
    public static List<Entry> deserialize(byte[] data) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (data == null) return entries;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String surface = dis.readUTF();
                short posId = dis.readShort();
                short cost = dis.readShort();
                entries.add(new Entry(surface, posId, cost));
            }
        }
        return entries;
    }

    /**
     * 辞書データベース内エントリ（単語、品詞ID、コスト）を保持するデータクラスです。
     */
    public static class Entry {
        /** 単語の表層形。 */
        public final String surface;
        /** 品詞 ID。 */
        public final int posId;
        /** 単語のコスト（スコア）。 */
        public final int cost;

        /**
         * 辞書エントリを初期化します。
         *
         * @param surface 表層形
         * @param posId   品詞ID
         * @param cost    コスト
         */
        public Entry(String surface, int posId, int cost) {
            this.surface = surface;
            this.posId = posId;
            this.cost = cost;
        }
    }

}
