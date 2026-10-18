package io.github.kachaya.ime

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * 辞書データファイルのダウンロードや展開（tar.gz, xz）を担当するクラスです。
 */
class DictDataFetcher(
    private val workPath: Path,
    private val dataPath: Path
) {

    /**
     * 必要に応じて emoji-test.txt や MeCab 辞書データを work ディレクトリ下に自動ダウンロード・展開して準備します。
     */
    @Throws(IOException::class)
    fun prepareData() {
        Files.createDirectories(workPath)
        Files.createDirectories(dataPath)
        val downloadDir = workPath.resolve("downloads")
        Files.createDirectories(downloadDir)

        val emojiTestPath = downloadDir.resolve("emoji-test.txt")
        if (!Files.exists(emojiTestPath)) {
            try {
                downloadFile("https://raw.githubusercontent.com/unicode-org/unicodetools/refs/heads/main/unicodetools/data/emoji/dev/emoji-test.txt", emojiTestPath)
            } catch (e: Exception) {
                println("Failed to download emoji-test.txt from primary URL: ${e.message}")
            }
        }

        val nounCsvPath = dataPath.resolve("Noun.csv")
        if (!Files.exists(nounCsvPath)) {
            val tarGzPath = downloadDir.resolve("mecab-ipadic-2.7.0-20070610.tar.gz")
            try {
                if (!Files.exists(tarGzPath)) {
                    downloadFile("https://drive.google.com/uc?export=download&id=1aQCOnE4aGPz-3tXpt6Mok2fcPgbnnouh", tarGzPath)
                }
                extractTarGz(tarGzPath, dataPath)
            } catch (e: Exception) {
                System.err.println("Failed to download or extract mecab-ipadic: ${e.message}")
                throw e
            }
        }
    }

    companion object {
        /**
         * 指定されたURLからファイルをダウンロードします（HTTPリダイレクト対応）。
         */
        @Throws(IOException::class)
        @JvmStatic
        fun downloadFile(urlString: String, outputPath: Path) {
            println("Downloading $urlString to ${outputPath.toAbsolutePath()}")
            val parent = outputPath.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }

            var url = URI.create(urlString).toURL()
            var conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            var status = conn.responseCode
            while (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val redirectUrl = conn.getHeaderField("Location")
                url = URI.create(redirectUrl).toURL()
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                status = conn.responseCode
            }

            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Failed to download: HTTP status $status")
            }

            conn.inputStream.use { inputStream ->
                Files.newOutputStream(outputPath).use { out ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
            println("Downloaded successfully.")
        }

        /**
         * .tar.gz アーカイブを指定ディレクトリに展開します。
         */
        @Throws(IOException::class)
        @JvmStatic
        fun extractTarGz(tarGzPath: Path, outputDir: Path) {
            println("Extracting ${tarGzPath.toAbsolutePath()} to ${outputDir.toAbsolutePath()}")
            Files.createDirectories(outputDir)

            Files.newInputStream(tarGzPath).use { fileIn ->
                GZIPInputStream(fileIn).use { gzipIn ->
                    BufferedInputStream(gzipIn).use { bufferedIn ->
                        val headerBuf = ByteArray(512)
                        while (true) {
                            val bytesRead = readFully(bufferedIn, headerBuf)
                            if (bytesRead < 512) {
                                break
                            }

                            var allZeros = true
                            for (b in headerBuf) {
                                if (b != 0.toByte()) {
                                    allZeros = false
                                    break
                                }
                            }
                            if (allZeros) {
                                break
                            }

                            val name = parseString(headerBuf, 0, 100)
                            val sizeStr = parseString(headerBuf, 124, 12)
                            val size: Long = try {
                                sizeStr.trim().toLong(8)
                            } catch (_: NumberFormatException) {
                                0L
                            }

                            val typeFlag = headerBuf[156]

                            var targetName = name
                            val slashIdx = name.indexOf('/')
                            if (slashIdx != -1 && slashIdx < name.length - 1) {
                                targetName = name.substring(slashIdx + 1)
                            } else if (name.endsWith("/")) {
                                targetName = ""
                            }

                            if (targetName.isNotEmpty() && targetName != ".") {
                                val targetPath = outputDir.resolve(targetName)
                                if (typeFlag == '5'.code.toByte() || name.endsWith("/")) {
                                    Files.createDirectories(targetPath)
                                } else {
                                    val targetParent = targetPath.parent
                                    if (targetParent != null) {
                                        Files.createDirectories(targetParent)
                                    }
                                    Files.newOutputStream(targetPath).use { out ->
                                        var remaining = size
                                        val dataBuf = ByteArray(8192)
                                        while (remaining > 0) {
                                            val toRead = Math.min(remaining, dataBuf.size.toLong()).toInt()
                                            val r = bufferedIn.read(dataBuf, 0, toRead)
                                            if (r == -1) break
                                            out.write(dataBuf, 0, r)
                                            remaining -= r.toLong()
                                        }
                                    }
                                }
                            }

                            val padding = (512 - (size % 512)) % 512
                            if (padding > 0) {
                                var skipped: Long = 0
                                while (skipped < padding) {
                                    val s = bufferedIn.skip(padding - skipped)
                                    if (s <= 0) break
                                    skipped += s
                                }
                            }
                        }
                    }
                }
            }
            println("Extracted successfully.")
        }

        private fun readFully(inputStream: InputStream, buf: ByteArray): Int {
            var totalRead = 0
            while (totalRead < buf.size) {
                val read = inputStream.read(buf, totalRead, buf.size - totalRead)
                if (read == -1) {
                    break
                }
                totalRead += read
            }
            return totalRead
        }

        private fun parseString(buf: ByteArray, offset: Int, length: Int): String {
            var len = 0
            while (len < length && buf[offset + len] != 0.toByte()) {
                len++
            }
            return String(buf, offset, len, StandardCharsets.UTF_8).trim()
        }
    }
}
