package io.github.kachaya.ime;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * 辞書データファイルのダウンロードや展開（tar.gz, xz）を担当するクラスです。
 */
public class DictDataFetcher {

    /** 作業ディレクトリパス。 */
    private final Path workPath;
    /** 辞書データ格納ディレクトリパス。 */
    private final Path dataPath;

    /**
     * DictDataFetcher を初期化します。
     *
     * @param workPath    作業ディレクトリパス
     * @param dataPath    辞書データ格納ディレクトリパス
     */
    public DictDataFetcher(Path workPath, Path dataPath) {
        this.workPath = workPath;
        this.dataPath = dataPath;
    }

    /**
     * 必要に応じて emoji-test.txt や MeCab 辞書データを work ディレクトリ下に自動ダウンロード・展開して準備します。
     *
     * @throws IOException 入出力エラー
     */
    public void prepareData() throws IOException {
        Files.createDirectories(workPath);
        Files.createDirectories(dataPath);
        Path downloadDir = workPath.resolve("downloads");
        Files.createDirectories(downloadDir);

        // 1. emoji-test.txt の確認・ダウンロード
        Path emojiTestPath = downloadDir.resolve("emoji-test.txt");
        if (!Files.exists(emojiTestPath)) {
            try {
                downloadFile("https://raw.githubusercontent.com/unicode-org/unicodetools/refs/heads/main/unicodetools/data/emoji/dev/emoji-test.txt", emojiTestPath);
            } catch (Exception e) {
                System.out.println("Failed to download emoji-test.txt from primary URL: " + e.getMessage());
            }
        }

        // 2. mecab-ipadic の確認・ダウンロード・展開
        Path nounCsvPath = dataPath.resolve("Noun.csv");
        if (!Files.exists(nounCsvPath)) {
            Path tarGzPath = downloadDir.resolve("mecab-ipadic-2.7.0-20070610.tar.gz");
            try {
                if (!Files.exists(tarGzPath)) {
                    downloadFile("https://drive.google.com/uc?export=download&id=1aQCOnE4aGPz-3tXpt6Mok2fcPgbnnouh", tarGzPath);
                }
                extractTarGz(tarGzPath, dataPath);
            } catch (Exception e) {
                System.err.println("Failed to download or extract mecab-ipadic: " + e.getMessage());
                throw e;
            }
        }

    }

    /**
     * 指定されたURLからファイルをダウンロードします（HTTPリダイレクト対応）。
     *
     * @param urlString  ダウンロード元URL
     * @param outputPath 保存先パス
     * @throws IOException 入出力エラー
     */
    public static void downloadFile(String urlString, Path outputPath) throws IOException {
        System.out.println("Downloading " + urlString + " to " + outputPath.toAbsolutePath());
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        int status = conn.getResponseCode();
        while (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_SEE_OTHER) {
            String redirectUrl = conn.getHeaderField("Location");
            url = URI.create(redirectUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            status = conn.getResponseCode();
        }

        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download: HTTP status " + status);
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(outputPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        System.out.println("Downloaded successfully.");
    }

    /**
     * .tar.gz アーカイブを指定ディレクトリに展開します（--strip-components 1 相当の処理を含む）。
     *
     * @param tarGzPath .tar.gz ファイルのパス
     * @param outputDir 展開先ディレクトリ
     * @throws IOException 入出力エラー
     */
    public static void extractTarGz(Path tarGzPath, Path outputDir) throws IOException {
        System.out.println("Extracting " + tarGzPath.toAbsolutePath() + " to " + outputDir.toAbsolutePath());
        Files.createDirectories(outputDir);

        try (InputStream fileIn = Files.newInputStream(tarGzPath);
             GZIPInputStream gzipIn = new GZIPInputStream(fileIn);
             BufferedInputStream in = new BufferedInputStream(gzipIn)) {

            byte[] headerBuf = new byte[512];
            while (true) {
                int bytesRead = readFully(in, headerBuf);
                if (bytesRead < 512) {
                    break;
                }

                boolean allZeros = true;
                for (byte b : headerBuf) {
                    if (b != 0) {
                        allZeros = false;
                        break;
                    }
                }
                if (allZeros) {
                    break;
                }

                String name = parseString(headerBuf, 0, 100);
                String sizeStr = parseString(headerBuf, 124, 12);
                long size;
                try {
                    size = Long.parseLong(sizeStr.trim(), 8);
                } catch (NumberFormatException e) {
                    size = 0;
                }

                byte typeFlag = headerBuf[156];

                // Strip components (strip 1 component: e.g., "mecab-ipadic-.../Noun.csv" -> "Noun.csv")
                String targetName = name;
                int slashIdx = name.indexOf('/');
                if (slashIdx != -1 && slashIdx < name.length() - 1) {
                    targetName = name.substring(slashIdx + 1);
                } else if (name.endsWith("/")) {
                    targetName = "";
                }

                if (!targetName.isEmpty() && !targetName.equals(".")) {
                    Path targetPath = outputDir.resolve(targetName);
                    if (typeFlag == '5' || name.endsWith("/")) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        try (OutputStream out = Files.newOutputStream(targetPath)) {
                            long remaining = size;
                            byte[] dataBuf = new byte[8192];
                            while (remaining > 0) {
                                int toRead = (int) Math.min(remaining, dataBuf.length);
                                int r = in.read(dataBuf, 0, toRead);
                                if (r == -1) break;
                                out.write(dataBuf, 0, r);
                                remaining -= r;
                            }
                        }
                    }
                }

                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) {
                    long skipped = 0;
                    while (skipped < padding) {
                        long s = in.skip(padding - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }
            }
        }
        System.out.println("Extracted successfully.");
    }

    /**
     * 入力ストリームから指定されたバイト数分だけ完全に読み込みます。
     *
     * @param in  入力ストリーム
     * @param buf 格納先バッファ
     * @return 実際に読み込んだバイト数
     * @throws IOException 入出力エラー
     */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int totalRead = 0;
        while (totalRead < buf.length) {
            int read = in.read(buf, totalRead, buf.length - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * tar アーカイブヘッダーの指定オフセットと長さから文字列をパースします。
     *
     * @param buf    バイト配列バッファ
     * @param offset オフセット
     * @param length 長さ
     * @return パースされた文字列
     */
    private static String parseString(byte[] buf, int offset, int length) {
        int len = 0;
        while (len < length && buf[offset + len] != 0) {
            len++;
        }
        return new String(buf, offset, len, StandardCharsets.UTF_8).trim();
    }
}
