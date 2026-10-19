package io.github.kachaya.ime

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Objects
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * data ディレクトリにある CSV データを読み込んでフィルタリング・ソートを行い、
 * 同じ読み（reading）ごとにまとめた上でバイナリ形式で SQLite データベース（system_dict）に保存するツールクラスです。
 */
class DictBuilder constructor(
    private val workPath: Path = Paths.get("work"),
    private val dataPath: Path = workPath.resolve("mecab-ipadic")
) {

    private val skippedWriters: MutableMap<String, BufferedWriter> = LinkedHashMap()
    private val skippedCounts: MutableMap<String, Long> = LinkedHashMap()

    @Throws(IOException::class)
    fun build() {
        DictDataFetcher(workPath, dataPath).prepareData()

        if (!Files.exists(dataPath) || !Files.isDirectory(dataPath)) {
            System.err.println("Data directory not found: ${dataPath.toAbsolutePath()}")
            return
        }

        Files.createDirectories(workPath)
        val tsvOutputPath = workPath.resolve(TSV_OUTPUT_FILENAME)
        println("Starting dictionary building from: ${dataPath.toAbsolutePath()}")

        val posStatsMap = HashMap<Int, PosStats>()
        var entries = loadEntries(posStatsMap)

        sortAndWriteTsv(entries, tsvOutputPath)

        entries = adjustCosts(entries)

        val dbPath = workPath.resolve("$SYSTEM_DICT_NAME.db")
        buildSqliteDatabase(entries, dbPath)

        val sourceDbFile = dbPath
        val path = Paths.get(ASSETS_DIR)
        val zipOutputFile = path.resolve("$SYSTEM_DICT_NAME.db.zip")
        zipAndChecksumDatabase(sourceDbFile, zipOutputFile, path.resolve("dict_checksum.txt"))

        val dumpTsvPath = workPath.resolve(DB_DUMP_FILENAME)
        dumpDbToTsv(dbPath, dumpTsvPath)

        processLeftIdDef()
        processMatrixDef()
        processEmojiData()

        println("Dictionary building completed. Output saved to ${tsvOutputPath.toAbsolutePath()}")
    }

    @Throws(IOException::class)
    private fun loadEntries(posStatsMap: MutableMap<Int, PosStats>): List<DictEntry> {
        val rawEntries = ArrayList<DictEntry>()
        skippedWriters.clear()
        skippedCounts.clear()

        try {
            for (fileName in TARGET_FILES) {
                val csvFile = dataPath.resolve(fileName)
                if (Files.exists(csvFile)) {
                    readIpadicCsvFileToRaw(csvFile, rawEntries, posStatsMap)
                } else {
                    println("Target CSV file not found (skipped): $fileName")
                }
            }
        } finally {
            for (writer in skippedWriters.values) {
                try {
                    writer?.close()
                } catch (_: IOException) {
                }
            }
        }

        println("Total raw loaded entries: ${rawEntries.size}")

        val filteredEntries = filterEntries(rawEntries)

        println("Total filtered entries: ${filteredEntries.size}")
        println("Skipped summary:")
        for ((key, value) in skippedCounts) {
            println("  - $key: $value")
        }
        return filteredEntries
    }

    @Throws(IOException::class)
    private fun sortAndWriteTsv(entries: List<DictEntry>, tsvOutputPath: Path) {
        println("Sorting entries by reading (Gojuon order)...")
        (entries as ArrayList<DictEntry>).sortWith(
            Comparator
                .comparing<DictEntry, String> { e -> e.reading }
                .thenComparing<String> { e -> e.surface }
                .thenComparingInt { e -> e.leftId }
                .thenComparingInt { e -> e.rightId }
                .thenComparingInt { e -> e.cost }
                .thenComparing<String> { e -> e.fileName }
        )

        println("Outputting TSV to: ${tsvOutputPath.toAbsolutePath()}")
        Files.newBufferedWriter(tsvOutputPath, StandardCharsets.UTF_8).use { writer ->
            for (entry in entries) {
                writer.write(entry.reading)
                writer.write("\t")
                writer.write(entry.surface)
                writer.write("\t")
                writer.write(entry.leftId.toString())
                writer.write("\t")
                writer.write(entry.toFeatureString())
                writer.write("\t")
                writer.write(entry.cost.toString())
                writer.write("\t")
                writer.write(entry.fileName)
                writer.newLine()
            }
        }
    }

    private fun adjustCosts(entries: List<DictEntry>): List<DictEntry> {
        println("Grouping entries by reading and POS (leftId)...")
        val groupedByReadingAndPos = LinkedHashMap<ReadingPosKey, MutableList<DictEntry>>()
        for (entry in entries) {
            val key = ReadingPosKey(entry.reading, entry.leftId)
            groupedByReadingAndPos.computeIfAbsent(key) { ArrayList() }.add(entry)
        }

        println("Processing groups and reassigning costs based on script rank...")
        val updatedEntries = ArrayList<DictEntry>()

        for ((_, list) in groupedByReadingAndPos) {
            if (list.size != 1) {
                val sortedCosts = list.map { e -> e.cost }.sorted()
                val sortedEntries = ArrayList(list)
                sortedEntries.sortWith(
                    Comparator
                        .comparingInt<DictEntry> { e -> getScriptRank(e.surface) }
                        .thenComparingInt { e -> e.cost }
                        .thenComparing<String> { e -> e.surface }
                )

                val processedList = ArrayList<DictEntry>()
                for (i in sortedEntries.indices) {
                    processedList.add(sortedEntries[i].withCost(sortedCosts[i]))
                }

                updatedEntries.addAll(processedList)
            } else {
                updatedEntries.addAll(list)
            }
        }

        return updatedEntries
    }

    @Throws(IOException::class)
    private fun buildSqliteDatabase(entries: List<DictEntry>, dbPath: Path) {
        println("Grouping entries by reading and saving to SQLite database...")
        val groupedEntries = LinkedHashMap<String, MutableList<DictEntry>>()
        for (entry in entries) {
            groupedEntries.computeIfAbsent(entry.reading) { ArrayList() }.add(entry)
        }

        val parent = dbPath.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        try {
            Files.deleteIfExists(dbPath)
        } catch (_: IOException) {
        }

        println("Creating SQLite database at: ${dbPath.toAbsolutePath()}")
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"

        try {
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode = OFF;")
                    stmt.execute("PRAGMA synchronous = OFF;")
                }

                conn.autoCommit = false

                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE IF NOT EXISTS dictionary (`key` TEXT PRIMARY KEY, `value` BLOB NOT NULL) WITHOUT ROWID;")
                }

                val insertSql = "INSERT INTO dictionary (`key`, `value`) VALUES (?, ?);"
                conn.prepareStatement(insertSql).use { pstmt ->
                    for ((reading, list) in groupedEntries) {
                        val dictSerializationEntries = ArrayList<Entry>()
                        for (e in list) {
                            dictSerializationEntries.add(Entry(e.surface, e.leftId, e.cost))
                        }
                        val bytes = serialize(dictSerializationEntries)

                        pstmt.setString(1, reading)
                        pstmt.setBytes(2, bytes)
                        pstmt.addBatch()
                    }
                    pstmt.executeBatch()
                }

                conn.commit()
            }
        } catch (e: SQLException) {
            throw IOException("Failed to create SQLite database at $dbPath", e)
        }

        println("SQLite database successfully saved to: ${dbPath.toAbsolutePath()}")
    }

    @Throws(IOException::class)
    private fun zipAndChecksumDatabase(sourceDbFile: Path, zipOutputFile: Path, checksumFile: Path) {
        if (zipOutputFile.parent != null) {
            Files.createDirectories(zipOutputFile.parent)
        }
        println("Zipping database to: ${zipOutputFile.toAbsolutePath()}")
        ZipOutputStream(Files.newOutputStream(zipOutputFile)).use { zos ->
            Files.newInputStream(sourceDbFile).use { `is` ->
                val entry = ZipEntry(sourceDbFile.fileName.toString())
                zos.putNextEntry(entry)
                val buf = ByteArray(16 * 1024)
                var len: Int
                while (`is`.read(buf).also { len = it } > 0) {
                    zos.write(buf, 0, len)
                }
                zos.closeEntry()
            }
        }
        println("Database successfully zipped and saved to assets.")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(zipOutputFile).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val sb = StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            val checksum = sb.toString()

            if (checksumFile.parent != null) {
                Files.createDirectories(checksumFile.parent)
            }
            Files.writeString(checksumFile, checksum, StandardCharsets.UTF_8)
            println("Dict checksum (SHA-256) written to: ${checksumFile.toAbsolutePath()}")
        } catch (e: NoSuchAlgorithmException) {
            System.err.println("Failed to compute SHA-256 checksum: ${e.message}")
        }
    }

    private fun processLeftIdDef() {
        val inputLeftIdDefPath = dataPath.resolve("left-id.def")
        if (Files.exists(inputLeftIdDefPath)) {
            try {
                val outputIdDefBinPath = Paths.get(ASSETS_DIR).resolve(POS_ID_BIN_OUTPUT_FILENAME)
                convertLeftIdDefToBinary(inputLeftIdDefPath, outputIdDefBinPath)
            } catch (e: Exception) {
                System.err.println("Failed to process left-id.def to binary: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("Source left-id.def not found at: ${inputLeftIdDefPath.toAbsolutePath()}")
        }
    }

    private fun processMatrixDef() {
        val inputMatrixDefPath = dataPath.resolve("matrix.def")
        val outputMatrixPath = Paths.get(ASSETS_DIR).resolve(MATRIX_OUTPUT_FILENAME)
        if (Files.exists(inputMatrixDefPath)) {
            try {
                convertMatrixDefToBigEndianBin(inputMatrixDefPath, outputMatrixPath)
            } catch (e: Exception) {
                System.err.println("Failed to process matrix.def: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("Source matrix.def not found at: ${inputMatrixDefPath.toAbsolutePath()}")
        }
    }

    private fun processEmojiData() {
        var inputEmojiPath = workPath.resolve("downloads").resolve("emoji-test.txt")
        if (!Files.exists(inputEmojiPath)) {
            inputEmojiPath = workPath.resolve("emoji-test.txt")
        }
        val outputEmojiPath = Paths.get(ASSETS_DIR).resolve("emoji.txt")
        if (Files.exists(inputEmojiPath)
            || Files.exists(File("${ASSETS_DIR}emojis.json").toPath())
            || Files.exists(File("app/src/main/assets/emojis.json").toPath())
        ) {
            try {
                convertEmoji(inputEmojiPath, outputEmojiPath)
            } catch (e: Exception) {
                System.err.println("Failed to process emoji data: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("Source emoji-test.txt not found at: ${inputEmojiPath.toAbsolutePath()}")
        }
    }

    @Throws(IOException::class)
    protected fun readIpadicCsvFileToRaw(csvFile: Path, rawEntries: MutableList<DictEntry>, posStatsMap: MutableMap<Int, PosStats>) {
        println("Processing ipadic CSV file: ${csvFile.fileName}")
        val fileName = csvFile.fileName.toString()

        BufferedReader(InputStreamReader(Files.newInputStream(csvFile), Charset.forName("EUC-JP"))).use { reader ->
            var line: String?
            var lineCount = 0L
            while (reader.readLine().also { line = it } != null) {
                lineCount++
                val l = line?.trim() ?: continue
                if (l.isEmpty()) {
                    continue
                }
                val entry = DictEntry.fromCsvLine(l, fileName, lineCount)
                if (entry != null) {
                    rawEntries.add(entry)
                    val stats = posStatsMap.computeIfAbsent(entry.leftId) { PosStats() }
                    stats.add(entry.cost)
                }
            }
        }
    }

    protected fun filterEntries(rawEntries: List<DictEntry>): List<DictEntry> {
        val filtered = ArrayList<DictEntry>()

        for (rawEntry in rawEntries) {
            var entry = rawEntry

            var skip = false
            when (entry.pos) {
                "助詞" -> {
                    if (!entry.surface.matches("^[\\p{IsHan}\\p{IsHiragana}]+$".toRegex())) {
                        logSkipped("particle_surface_not_hiragana", entry)
                        skip = true
                    }
                }
                "名詞" -> {
                    when (entry.pos1) {
                        "一般" -> {
                            entry = entry.withCost(entry.cost + 1000)
                        }
                        "固有名詞" -> {
                            if ("組織" == entry.pos2) {
                                entry = entry.withCost(entry.cost + 5000)
                            } else {
                                entry = entry.withCost(entry.cost + 3000)
                            }
                        }
                    }
                }

                else -> {}
            }

            if (skip) {
                continue
            }

            filtered.add(entry)
        }

        return filtered
    }

    @Synchronized
    protected fun logSkipped(reason: String, entry: DictEntry) {
        skippedCounts.merge(reason, 1L) { a, b -> a + b }
        var writer = skippedWriters[reason]
        if (writer == null) {
            try {
                val path = workPath.resolve("skipped_$reason.tsv")
                if (path.parent != null) {
                    Files.createDirectories(path.parent)
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                skippedWriters[reason] = writer
                println("Skipped log ($reason) saved to: ${path.toAbsolutePath()}")
            } catch (_: IOException) {
            }
        }
        if (writer != null) {
            try {
                writer.write("${entry.fileName}\t${entry.lineNumber}\t${entry.originalLine}")
                writer.newLine()
                writer.flush()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        const val SYSTEM_DICT_NAME = "system_dict"

        private const val ASSETS_DIR = "app/src/main/assets/dictionary/"
        private const val MATRIX_OUTPUT_FILENAME = "matrix.bin"
        private const val POS_ID_BIN_OUTPUT_FILENAME = "pos_id.bin"

        private const val TSV_OUTPUT_FILENAME = "dict_entries.tsv"
        private const val DB_DUMP_FILENAME = "system_dict_dump.tsv"

        private val TARGET_FILES = listOf(
            "Adj.csv",
            "Adnominal.csv",
            "Adverb.csv",
            "Auxil.csv",
            "Conjunction.csv",
//            "Filler.csv",
//            "Interjection.csv",
            "Noun.adjv.csv",
            "Noun.adverbal.csv",
            "Noun.csv",
            "Noun.demonst.csv",
            "Noun.nai.csv",
            "Noun.name.csv",
            "Noun.number.csv",
            "Noun.org.csv",
            "Noun.others.csv",
            "Noun.place.csv",
            "Noun.proper.csv",
            "Noun.verbal.csv",
//            "Others.csv",
            "Postp-col.csv",
            "Postp.csv",
            "Prefix.csv",
            "Suffix.csv",
//            "Symbol.csv",
            "Verb.csv"
        )

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val builder = DictBuilder()
                builder.build()
            } catch (e: Exception) {
                System.err.println("Error in DictBuilder: ${e.message}")
                e.printStackTrace()
            }
        }



        @Throws(IOException::class)
        fun convertLeftIdDefToBinary(inputDefPath: Path, outputPath: Path) {
            println("Converting left-id.def (EUC-JP) to binary pos_id.bin: ${inputDefPath.toAbsolutePath()}")
            if (!Files.exists(inputDefPath)) {
                throw FileNotFoundException("left-id.def not found at: ${inputDefPath.toAbsolutePath()}")
            }

            val lines = Files.readAllLines(inputDefPath, Charset.forName("EUC-JP"))
            val idMap = HashMap<Int, String>()
            var maxId = -1

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue
                }
                val spaceIdx = trimmed.indexOf(' ')
                if (spaceIdx != -1) {
                    try {
                        val id = trimmed.substring(0, spaceIdx).trim().toInt()
                        val posStr = trimmed.substring(spaceIdx + 1).trim()
                        idMap[id] = posStr
                        if (id > maxId) {
                            maxId = id
                        }
                    } catch (_: NumberFormatException) {
                    }
                }
            }

            val size = maxId + 1
            val posArray = Array(size) { i -> idMap.getOrDefault(i, "") }

            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeShort(size)
                for (i in 0..<size) {
                    dos.writeUTF(posArray[i])
                }
            }

            if (outputPath.parent != null) {
                Files.createDirectories(outputPath.parent)
            }
            try {
                Files.deleteIfExists(outputPath)
            } catch (_: IOException) {
            }
            Files.write(outputPath, baos.toByteArray())
            println("Successfully saved binary pos_id.bin to: ${outputPath.toAbsolutePath()}")
        }

        @Throws(IOException::class)
        fun convertMatrixDefToBigEndianBin(inputDefPath: Path, outputPath: Path) {
            println("Converting matrix.def to Big Endian matrix.bin: ${inputDefPath.toAbsolutePath()}")
            if (!Files.exists(inputDefPath)) {
                throw FileNotFoundException("matrix.def not found at: ${inputDefPath.toAbsolutePath()}")
            }

            val rows: Int
            val cols: Int
            val matrix: Array<ShortArray>

            BufferedReader(InputStreamReader(Files.newInputStream(inputDefPath), StandardCharsets.UTF_8)).use { reader ->
                val line = reader.readLine() ?: throw IllegalArgumentException("matrix.def is empty")
                val dims = line.trim().split("\\s+".toRegex())
                if (dims.size < 2) {
                    throw IllegalArgumentException("Invalid matrix.def header: $line")
                }
                rows = dims[0].toInt()
                cols = dims[1].toInt()

                matrix = Array(rows) { ShortArray(cols) }

                var l: String?
                while (reader.readLine().also { l = it } != null) {
                    val trimmed = l?.trim() ?: continue
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue
                    }
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size < 3) {
                        continue
                    }
                    val r = parts[0].toInt()
                    val c = parts[1].toInt()
                    val cost = parts[2].toShort()
                    if (r in 0..<rows && c in 0..<cols) {
                        matrix[r][c] = cost
                    }
                }
            }

            val requiredLength = 4 + rows * cols * 2
            val outBuffer = ByteBuffer.allocate(requiredLength)
            outBuffer.order(ByteOrder.BIG_ENDIAN)
            outBuffer.putShort(rows.toShort())
            outBuffer.putShort(cols.toShort())

            for (r in 0..<rows) {
                for (c in 0..<cols) {
                    outBuffer.putShort(matrix[r][c])
                }
            }

            if (outputPath.parent != null) {
                Files.createDirectories(outputPath.parent)
            }
            try {
                Files.deleteIfExists(outputPath)
            } catch (_: IOException) {
            }
            Files.write(outputPath, outBuffer.array())
            println("Successfully saved Big Endian matrix.bin to: ${outputPath.toAbsolutePath()}")
        }

        @Throws(IOException::class)
        fun convertEmoji(inputPath: Path, outputTxtPath: Path) {
            println("Converting emoji data to: ${outputTxtPath.toAbsolutePath()}")
            val groupMap = LinkedHashMap<String, MutableList<String>>()

            var jsonFile = File("${ASSETS_DIR}emojis.json")
            if (!jsonFile.exists()) {
                jsonFile = File("app/src/main/assets/emojis.json")
            }
            if (jsonFile.exists()) {
                val jsonContent = Files.readString(jsonFile.toPath())
                BufferedReader(StringReader(jsonContent)).use { reader ->
                    var line: String?
                    var currentGroup = "Smileys & Emotion"
                    while (reader.readLine().also { line = it } != null) {
                        val l = line?.trim() ?: continue
                        if (l.endsWith("\": [")) {
                            val quote1 = l.indexOf('"')
                            val quote2 = l.indexOf('"', quote1 + 1)
                            if (quote1 != -1 && quote2 != -1) {
                                currentGroup = l.substring(quote1 + 1, quote2)
                            }
                        } else if (l.contains("\"emoji\":")) {
                            val idx = l.indexOf("\"emoji\"")
                            val quote3 = l.indexOf('"', l.indexOf(':', idx))
                            val quote4 = l.indexOf('"', quote3 + 1)
                            if (quote3 != -1 && quote4 != -1) {
                                val emoji = l.substring(quote3 + 1, quote4)
                                groupMap.computeIfAbsent(currentGroup) { ArrayList() }.add(emoji)
                            }
                        }
                    }
                }
            }

            if (groupMap.isEmpty()) {
                val lines = ArrayList<String>()
                if (Files.exists(inputPath)) {
                    BufferedReader(InputStreamReader(Files.newInputStream(inputPath), StandardCharsets.UTF_8)).use { reader ->
                        var fileLine: String?
                        while (reader.readLine().also { fileLine = it } != null) {
                            if (fileLine != null) lines.add(fileLine)
                        }
                    }
                }

                val componentCodePoints = HashSet<String>()
                var currentGroup = "Smileys & Emotion"
                for (txtLine in lines) {
                    val trimmed = txtLine.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("# group:")) {
                        currentGroup = trimmed.substring(8).trim()
                        continue
                    }

                    if (trimmed.startsWith("#")) {
                        continue
                    }

                    val semicolonIdx = trimmed.indexOf(';')
                    if (semicolonIdx != -1) {
                        if (currentGroup.equalsIgnoreCase("Component") || currentGroup.lowercase().contains("component")) {
                            val codePointsPart = trimmed.substring(0, semicolonIdx).trim()
                            for (cp in codePointsPart.split("\\s+".toRegex())) {
                                if (cp.isNotEmpty()) {
                                    componentCodePoints.add(cp)
                                }
                            }
                        }
                    }
                }

                currentGroup = "Smileys & Emotion"
                for (txtLine in lines) {
                    val trimmed = txtLine.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("# group:")) {
                        currentGroup = trimmed.substring(8).trim()
                        continue
                    }

                    if (trimmed.startsWith("#")) {
                        continue
                    }

                    val semicolonIdx = trimmed.indexOf(';')
                    if (semicolonIdx != -1 && trimmed.contains("fully-qualified")) {
                        if (currentGroup.equalsIgnoreCase("Component") || currentGroup.lowercase().contains("component")) {
                            continue
                        }

                        val codePointsPart = trimmed.substring(0, semicolonIdx).trim()
                        var containsComponentCode = false
                        for (cp in codePointsPart.split("\\s+".toRegex())) {
                            if (componentCodePoints.contains(cp)) {
                                containsComponentCode = true
                                break
                            }
                        }
                        if (containsComponentCode) {
                            continue
                        }

                        val hashIdx = trimmed.indexOf('#')
                        if (hashIdx != -1) {
                            val commentPart = trimmed.substring(hashIdx + 1).trim()
                            val parts = commentPart.split("\\s+".toRegex(), 3)
                            if (parts.isNotEmpty()) {
                                val emoji = parts[0]
                                groupMap.computeIfAbsent(currentGroup) { ArrayList() }.add(emoji)
                            }
                        }
                    }
                }
            }

            if (groupMap.isEmpty()) {
                val smileys = ArrayList<String>()
                smileys.add("😀")
                smileys.add("😂")
                smileys.add("😍")
                smileys.add("👍")
                smileys.add("❤️")
                groupMap["Smileys & Emotion"] = smileys
            }

            val sb = StringBuilder()
            for ((key, value) in groupMap) {
                sb.append("# ").append(key).append("\n")
                for (emoji in value) {
                    sb.append(emoji).append("\n")
                }
            }

            if (outputTxtPath.parent != null) {
                Files.createDirectories(outputTxtPath.parent)
            }
            try {
                Files.deleteIfExists(outputTxtPath)
            } catch (_: IOException) {
            }
            Files.writeString(outputTxtPath, sb.toString(), StandardCharsets.UTF_8)
            println("Successfully generated emoji.txt at: ${outputTxtPath.toAbsolutePath()}")
        }

        @Throws(IOException::class)
        fun dumpDbToTsv(dbPath: Path, outputTsvPath: Path) {
            val dbFile = if (dbPath.fileName.toString().endsWith(".db")) dbPath else dbPath.resolveSibling("${dbPath.fileName}.db")
            if (!Files.exists(dbFile)) {
                System.err.println("DB file not found for dump: $dbFile")
                return
            }

            val idMap = HashMap<Int, String>()
            val defPath = dbPath.resolveSibling("mecab-ipadic").resolve("left-id.def")
            if (Files.exists(defPath)) {
                try {
                    val lines = Files.readAllLines(defPath, Charset.forName("EUC-JP"))
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val spaceIdx = trimmed.indexOf(' ')
                        if (spaceIdx != -1) {
                            try {
                                val id = trimmed.substring(0, spaceIdx).trim().toInt()
                                val posStr = trimmed.substring(spaceIdx + 1).trim()
                                idMap[id] = posStr
                            } catch (_: NumberFormatException) {}
                        }
                    }
                } catch (_: Exception) {}
            }

            println("Dumping DB to TSV: ${outputTsvPath.toAbsolutePath()}")
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"

            try {
                DriverManager.getConnection(url).use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT `key`, `value` FROM dictionary ORDER BY `key` ASC").use { rs ->
                            Files.newBufferedWriter(outputTsvPath, StandardCharsets.UTF_8).use { writer ->
                                var totalEntries = 0L
                                while (rs.next()) {
                                    val reading = rs.getString(1)
                                    val valueBytes = rs.getBytes(2)

                                    try {
                                        val entries = deserialize(valueBytes)
                                        for (e in entries) {
                                            writer.write(reading)
                                            writer.write("\t")
                                            writer.write(e.surface)
                                            writer.write("\t")
                                            val posString = idMap[e.posId] ?: e.posId.toString()
                                            writer.write(posString)
                                            writer.write("\t")
                                            writer.write(e.cost.toString())
                                            writer.newLine()
                                            totalEntries++
                                        }
                                    } catch (_: IOException) {
                                        System.err.println("Error reading DB entries for reading: $reading")
                                    }
                                }
                                println("Dump completed. Total entries written: $totalEntries")
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IOException("Failed to dump SQLite DB to TSV", e)
            }
        }

        fun toHiragana(s: String?): String? {
            if (s == null) return null
            val sb = StringBuilder()
            for (c in s.toCharArray()) {
                if (c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ') {
                    sb.append((c.code - 'ァ'.code + 'ぁ'.code).toChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        fun getScriptRank(s: String?): Int {
            if (s == null) return 0
            return if (isValidReading(s)) {
                2
            } else if (isKatakanaOnly(s)) {
                1
            } else {
                0
            }
        }

        fun isKatakanaOnly(s: String?): Boolean {
            return s != null && s.matches("^[\\p{IsKatakana}ー]+$".toRegex())
        }

        fun hasKatakana(s: String?): Boolean {
            return s != null && s.matches(".*[\\p{IsKatakana}ー].*".toRegex())
        }

        fun isValidBase(s: String?): Boolean {
            if (s == null) return false
            if (s.matches(".*[0-9A-Za-z:].*".toRegex())) return false
            return true
        }

        fun isValidReading(s: String?): Boolean {
            return s != null && s.matches("^[ぁ-ゖー]+$".toRegex())
        }

        fun isValidSurface(s: String?): Boolean {
            return s != null && s.matches("^[\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}ー〆々\\p{So}]+$".toRegex())
        }

        @Throws(IOException::class)
        fun serialize(entries: List<Entry>): ByteArray {
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(entries.size)
                for (e in entries) {
                    dos.writeUTF(e.surface)
                    dos.writeShort(e.posId)
                    dos.writeShort(e.cost)
                }
            }
            return baos.toByteArray()
        }

        @Throws(IOException::class)
        fun deserialize(data: ByteArray?): List<Entry> {
            val entries = ArrayList<Entry>()
            if (data == null) return entries
            DataInputStream(ByteArrayInputStream(data)).use { dis ->
                val count = dis.readInt()
                for (i in 0..<count) {
                    val surface = dis.readUTF()
                    val posId = dis.readShort().toInt()
                    val cost = dis.readShort().toInt()
                    entries.add(Entry(surface, posId, cost))
                }
            }
            return entries
        }

        private fun String.equalsIgnoreCase(other: String): Boolean = this.equals(other, ignoreCase = true)
    }

    class PosStats {
        var min: Int = Int.MAX_VALUE
        var max: Int = Int.MIN_VALUE
        var sum: Long = 0
        var count: Int = 0

        fun add(cost: Int) {
            if (cost < min) min = cost
            if (cost > max) max = cost
            sum += cost.toLong()
            count++
        }
    }

    class ReadingPosKey(
        val reading: String,
        val leftId: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReadingPosKey) return false
            return leftId == other.leftId && reading == other.reading
        }

        override fun hashCode(): Int {
            return Objects.hash(reading, leftId)
        }
    }

    class DictEntry(
        val surface: String,
        val leftId: Int,
        val rightId: Int,
        val cost: Int,
        val pos: String,
        val pos1: String,
        val pos2: String,
        val pos3: String,
        val ctype: String,
        val cform: String,
        val base: String,
        val reading: String,
        val rawReading: String,
        val fileName: String,
        val originalLine: String,
        val lineNumber: Long
    ) {
        fun withCost(newCost: Int): DictEntry {
            return DictEntry(
                surface, leftId, rightId, newCost,
                pos, pos1, pos2, pos3,
                ctype, cform, base,
                reading, rawReading, fileName, originalLine, lineNumber
            )
        }

        fun toFeatureString(): String {
            return listOf(pos, pos1, pos2, pos3, ctype, cform, base, reading).joinToString(",")
        }

        companion object {
            fun fromCsvLine(csvLine: String?, fileName: String, lineNumber: Long): DictEntry? {
                if (csvLine.isNullOrBlank()) {
                    return null
                }

                val tokens = java.util.regex.Pattern.compile(",").split(csvLine, -1)
                if (tokens.size < 13) {
                    return null
                }

                return try {
                    val surface = tokens[0]
                    val leftId = tokens[1].toInt()
                    val rightId = tokens[2].toInt()
                    val cost = tokens[3].toInt()

                    if (leftId != rightId) {
                        return null
                    }
                    if (cost <= 0) {
                        return null
                    }

                    val pos = tokens[4]
                    val pos1 = tokens[5]
                    val pos2 = tokens[6]
                    val pos3 = tokens[7]
                    val ctype = tokens[8]
                    val cform = tokens[9]

                    val base = tokens[10]
                    val rawReading = tokens[11]
                    val reading = toHiragana(rawReading) ?: ""

                    DictEntry(
                        surface, leftId, rightId, cost,
                        pos, pos1, pos2, pos3,
                        ctype, cform, base,
                        reading, rawReading, fileName, csvLine, lineNumber
                    )
                } catch (_: NumberFormatException) {
                    null
                }
            }
        }
    }

    class Entry(
        val surface: String,
        val posId: Int,
        val cost: Int
    )
}
