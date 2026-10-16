package io.github.kachaya.ime.zenz;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.kachaya.ime.dictionary.Candidate;
import io.github.kachaya.ime.engine.RomajiConverter;

/**
 * llama.cpp と Zenz v3 (かな漢字変換LLM) を統合する変換エンジンクラスです。
 * <p>
 * 高速ストリーミング変換、Prefix Caching による追従性向上、
 * および読みと表記の文節境界を自動判定するアライメント抽出機能を備えます。
 * </p>
 */
public class ZenzEngine {
    private static final String TAG = "ZenzEngine";

    public static final String PREF_KEY_CONVERSION_ENGINE = "conversion_engine";
    public static final String ENGINE_VITERBI = "viterbi";
    public static final String ENGINE_ZENZ = "zenz";

    public static final String PREF_KEY_ZENZ_ENABLED = "zenz_enabled";
    public static final String PREF_KEY_ZENZ_MODEL_PATH = "zenz_model_path";
    public static final String PREF_KEY_ZENZ_THREADS = "zenz_threads";
    public static final String PREF_KEY_ZENZ_CTX_SIZE = "zenz_ctx_size";
    public static final String PREF_KEY_ZENZ_MAX_TOKENS = "zenz_max_tokens";
    public static final String PREF_KEY_ZENZ_MAX_CANDIDATES = "zenz_max_candidates";
    public static final String PREF_KEY_ZENZ_GPU_LAYERS = "zenz_gpu_layers";
    public static final String PREF_KEY_ZENZ_TEMPERATURE = "zenz_temperature";
    public static final String PREF_KEY_ZENZ_TOP_P = "zenz_top_p";
    public static final String PREF_KEY_ZENZ_TOP_K = "zenz_top_k";
    public static final String PREF_KEY_ZENZ_USE_CONTEXT = "zenz_use_context";

    /** assets に同梱されているデフォルト GGUF モデルのファイル名 */
    public static final String DEFAULT_ASSET_MODEL_NAME = "zenz-v3.2-xsmall.gguf";

    private static boolean sLibraryLoaded;

    static {
        try {
            System.loadLibrary("zenz_engine");
            sLibraryLoaded = true;
            Log.i(TAG, "libzenz_engine.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library zenz_engine", e);
            sLibraryLoaded = false;
        }
    }

    private Context mContext;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private boolean mEnabled = false;
    private String mModelPath = "";
    private int mThreads = 4;
    private int mCtxSize = 512;
    private int mMaxTokens = 16;
    private int mMaxCandidates = 3;
    private int mGpuLayers = 0;
    private float mTemperature = 0.0f;
    private float mTopP = 0.90f;
    private int mTopK = 50;
    private boolean mUseContext = false;

    private String mLoadedModelPath = "";
    private int mLoadedThreads = -1;
    private int mLoadedCtxSize = -1;
    private int mLoadedGpuLayers = -1;
    private int[] mLastPromptTokens = null;

    private native boolean nativeInit(String modelPath, int nThreads, int nCtx, int nGpuLayers);

    private native boolean nativeIsLoaded();

    private native void nativeClose();

    private native int[] nativeTokenize(String text, boolean parseSpecial);

    private native int nativeGetCtxSize();

    private native void nativeClearKvCache();

    private native void nativeClearKvCacheAfter(int pos);

    private native boolean nativeEvalTokens(int[] tokens, int startPos, int nTokens);

    private native int nativeSampleToken(float temperature, int topK, float topP);

    private native int[] nativeSampleTopKTokens(int k);

    private native byte[] nativeTokenToPiece(int token);

    private native int nativeGetEosToken();

    /**
     * 読みと表記の対応関係（アライメント）データモデルクラスです。
     */
    public static class AlignmentPair {
        /** ひらがな/カタカナの読み文字列。 */
        public final String reading;
        /** 漢字かな混じりの表記文字列。 */
        public final String surface;

        /**
         * 新しいアライメントペアを構築します。
         *
         * @param reading 読み
         * @param surface 表記
         */
        public AlignmentPair(String reading, String surface) {
            this.reading = reading;
            this.surface = surface;
        }

        @Override
        public String toString() {
            return reading + "|" + surface;
        }
    }

    /**
     * 指定された文字ブロックが漢字（CJK統合漢字、漢字拡張、々等）であるか判定します。
     *
     * @param str 判定対象文字列
     * @return 漢字ブロックの場合 true
     */
    public static boolean isKanjiBlock(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        int codePoint = str.codePointAt(0);
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
                (codePoint >= 0x4E00 && codePoint <= 0x9FFF) ||
                (codePoint >= 0x3400 && codePoint <= 0x4DBF) ||
                codePoint == 0x3005; // 々
    }

    /**
     * 表記（surface）を「漢字ブロック」と「かな/記号ブロック」の連続セグメントに分割します。
     * <p>例: "猫を飼いたい" -> ["猫", "を", "飼い", "たい"]</p>
     *
     * @param surface 分割対象の表記文字列
     * @return セグメント分割された文字列リスト
     */
    public static List<String> segmentSurface(String surface) {
        List<String> segments = new ArrayList<>();
        if (surface == null || surface.isEmpty()) {
            return segments;
        }

        StringBuilder sb = new StringBuilder();
        boolean currentIsKanji = isKanjiBlock(String.valueOf(surface.charAt(0)));

        for (int i = 0; i < surface.length(); i++) {
            char c = surface.charAt(i);
            boolean isK = isKanjiBlock(String.valueOf(c));
            if (isK == currentIsKanji) {
                sb.append(c);
            } else {
                if (sb.length() > 0) {
                    segments.add(sb.toString());
                    sb.setLength(0);
                }
                sb.append(c);
                currentIsKanji = isK;
            }
        }
        if (sb.length() > 0) {
            segments.add(sb.toString());
        }
        return segments;
    }

    /**
     * 入力された読み（ひらがな/カタカナ）とモデルが生成した表記（漢字かな混じり文）を照合し、
     * 各ブロックの読みと表記のアライメント（対応関係）を自動抽出します。
     *
     * @param reading 入力された読み文字列
     * @param surface モデルが生成した漢字かな混じり表記
     * @return アライメントペア (AlignmentPair) のリスト
     */
    public static List<AlignmentPair> alignReadingAndSurface(String reading, String surface) {
        List<AlignmentPair> alignments = new ArrayList<>();
        if (reading == null || reading.isEmpty() || surface == null || surface.isEmpty()) {
            return alignments;
        }

        String normReading = RomajiConverter.toWideKatakana(reading);
        List<String> segments = segmentSurface(surface);

        int readingIdx = 0;
        int nReading = normReading.length();

        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            boolean isKanji = isKanjiBlock(seg);

            if (!isKanji) {
                String normSeg = RomajiConverter.toWideKatakana(seg);
                int matchPos = normReading.indexOf(normSeg, readingIdx);

                if (matchPos == readingIdx) {
                    String origReading = reading.substring(readingIdx, Math.min(reading.length(), readingIdx + seg.length()));
                    alignments.add(new AlignmentPair(origReading, seg));
                    readingIdx += normSeg.length();
                } else if (matchPos != -1 && matchPos - readingIdx < 8) {
                    String matchedReading = reading.substring(readingIdx, Math.min(reading.length(), matchPos));
                    alignments.add(new AlignmentPair(matchedReading, seg));
                    readingIdx = matchPos + normSeg.length();
                } else {
                    alignments.add(new AlignmentPair(seg, seg));
                }
            } else {
                String nextAnchor = null;
                if (i + 1 < segments.size()) {
                    nextAnchor = RomajiConverter.toWideKatakana(segments.get(i + 1));
                }

                int endIdx = nReading;
                if (nextAnchor != null && !nextAnchor.isEmpty()) {
                    int anchorPos = normReading.indexOf(nextAnchor, readingIdx + 1);
                    if (anchorPos != -1) {
                        endIdx = anchorPos;
                    }
                }

                if (readingIdx < endIdx) {
                    String matchedReading = reading.substring(readingIdx, Math.min(reading.length(), endIdx));
                    alignments.add(new AlignmentPair(matchedReading, seg));
                    readingIdx = endIdx;
                } else {
                    alignments.add(new AlignmentPair("", seg));
                }
            }
        }

        if (readingIdx < reading.length() && !alignments.isEmpty()) {
            AlignmentPair last = alignments.get(alignments.size() - 1);
            String extraReading = reading.substring(readingIdx);
            alignments.set(alignments.size() - 1, new AlignmentPair(last.reading + extraReading, last.surface));
        }

        return alignments;
    }

    /**
     * 変換結果を受け取るための非同期コールバックインターフェースです。
     */
    public interface ConversionCallback {
        /**
         * Zenz AI 候補が1つ生成されるたびに即座に呼び出されます（ストリーミング用）。
         *
         * @param candidate 生成された候補オブジェクト
         */
        void onCandidateAvailable(Candidate candidate);

        /**
         * すべての AI 生成プロセスが完了したときに呼び出されます。
         */
        void onConversionComplete();
    }

    public ZenzEngine(Context context) {
        mContext = context;
    }

    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    public boolean isEnabled() {
        return mEnabled && isLoaded();
    }

    public boolean isUseContext() {
        return mUseContext;
    }

    public boolean isLoaded() {
        if (!sLibraryLoaded) {
            return false;
        }
        try {
            return nativeIsLoaded();
        } catch (Exception e) {
            Log.e(TAG, "Error checking nativeIsLoaded", e);
            return false;
        }
    }

    /**
     * SharedPreferences から設定値を読み込み、エンジン設定を再反映します。
     *
     * @param prefs アプリ共有設定オブジェクト
     */
    public void reloadPreferences(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        String engine = prefs.getString(PREF_KEY_CONVERSION_ENGINE, null);
        if (engine != null) {
            mEnabled = ENGINE_ZENZ.equals(engine);
        } else {
            mEnabled = prefs.getBoolean(PREF_KEY_ZENZ_ENABLED, false);
        }
        mModelPath = prefs.getString(PREF_KEY_ZENZ_MODEL_PATH, "");
        try {
            mThreads = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_THREADS, "4"));
        } catch (Exception e) {
            mThreads = 4;
        }
        try {
            mCtxSize = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_CTX_SIZE, "512"));
        } catch (Exception e) {
            mCtxSize = 512;
        }
        try {
            mMaxTokens = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_MAX_TOKENS, "16"));
        } catch (Exception e) {
            mMaxTokens = 16;
        }
        if (mMaxTokens <= 0) {
            mMaxTokens = 16;
        }
        try {
            mMaxCandidates = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_MAX_CANDIDATES, "3"));
        } catch (Exception e) {
            mMaxCandidates = 3;
        }
        if (mMaxCandidates <= 0) {
            mMaxCandidates = 3;
        }
        try {
            mGpuLayers = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_GPU_LAYERS, "0"));
        } catch (Exception e) {
            mGpuLayers = 0;
        }
        try {
            mTemperature = Float.parseFloat(prefs.getString(PREF_KEY_ZENZ_TEMPERATURE, "0.0"));
        } catch (Exception e) {
            mTemperature = 0.0f;
        }
        try {
            mTopP = Float.parseFloat(prefs.getString(PREF_KEY_ZENZ_TOP_P, "0.90"));
        } catch (Exception e) {
            mTopP = 0.90f;
        }
        try {
            mTopK = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_TOP_K, "50"));
        } catch (Exception e) {
            mTopK = 50;
        }
        if (mTopK < 0) {
            mTopK = 50;
        }
        mUseContext = prefs.getBoolean(PREF_KEY_ZENZ_USE_CONTEXT, false);

        if (mEnabled) {
            if (!mModelPath.isEmpty() && new File(mModelPath).exists()) {
                initAsync(mModelPath, mThreads, mCtxSize, mGpuLayers, null);
            } else {
                initFromAssetsAsync(mContext, DEFAULT_ASSET_MODEL_NAME, mThreads, mCtxSize, mGpuLayers, null);
            }
        } else {
            closeAsync();
        }
    }

    /**
     * assets に同梱されたモデルファイルを内部ストレージに抽出・準備（初回のみ実行）します。
     *
     * @param context       コンテキスト
     * @param assetFileName assets内のモデルファイル名
     * @return 抽出準備されたモデルファイルの絶対パス (失敗時 null)
     */
    public static String prepareModelFileFromAssets(Context context, String assetFileName) {
        if (context == null || assetFileName == null || assetFileName.isEmpty()) {
            return null;
        }

        File targetFile = new File(context.getFilesDir(), assetFileName);
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile.getAbsolutePath();
        }

        try (java.io.InputStream in = context.getAssets().open(assetFileName);
             java.io.FileOutputStream out = new java.io.FileOutputStream(targetFile)) {

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            Log.i(TAG, "Successfully extracted asset model " + assetFileName + " to " + targetFile.getAbsolutePath());
            return targetFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Error extracting asset model: " + assetFileName, e);
            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
            return null;
        }
    }

    /**
     * assets 内の GGUF モデルを初期化・ロードします。
     *
     * @param context       コンテキスト
     * @param assetFileName assets内のモデルファイル名
     * @param nThreads      CPUスレッド数
     * @param nCtx          コンテキスト長
     * @param nGpuLayers    GPUレイヤー数
     * @param onComplete    完了時コールバック
     */
    public void initFromAssetsAsync(Context context, String assetFileName, int nThreads, int nCtx, int nGpuLayers, Runnable onComplete) {
        if (!sLibraryLoaded || context == null) {
            return;
        }

        mExecutor.execute(() -> {
            String modelPath = prepareModelFileFromAssets(context, assetFileName);
            if (modelPath != null) {
                if (isLoaded() && modelPath.equals(mLoadedModelPath) && nThreads == mLoadedThreads && nCtx == mLoadedCtxSize && nGpuLayers == mLoadedGpuLayers) {
                    Log.d(TAG, "Model already loaded. Skipping.");
                    if (onComplete != null) {
                        mMainHandler.post(onComplete);
                    }
                    return;
                }

                boolean success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers);
                if (success) {
                    mLoadedModelPath = modelPath;
                    mLoadedThreads = nThreads;
                    mLoadedCtxSize = nCtx;
                    mLoadedGpuLayers = nGpuLayers;
                    Log.i(TAG, "Successfully loaded GGUF model: " + modelPath);
                } else {
                    mLoadedModelPath = "";
                    mLoadedThreads = -1;
                    mLoadedCtxSize = -1;
                    mLoadedGpuLayers = -1;
                    Log.e(TAG, "Failed to load GGUF model: " + modelPath);
                }
            } else {
                Log.e(TAG, "Failed to prepare model file from assets: " + assetFileName);
            }

            mLastPromptTokens = null;
            if (onComplete != null) {
                mMainHandler.post(onComplete);
            }
        });
    }

    /**
     * GGUF モデルを非同期スレッドでロード・初期化します。
     *
     * @param modelPath  GGUFモデルファイルの絶対パス
     * @param nThreads   CPUスレッド数
     * @param nCtx       コンテキスト長 (n_ctx)
     * @param nGpuLayers GPUオフロードレイヤー数
     * @param onComplete 完了時実行用Runnable
     */
    public void initAsync(String modelPath, int nThreads, int nCtx, int nGpuLayers, Runnable onComplete) {
        if (!sLibraryLoaded) {
            return;
        }
        mExecutor.execute(() -> {
            if (isLoaded() && modelPath.equals(mLoadedModelPath) && nThreads == mLoadedThreads && nCtx == mLoadedCtxSize && nGpuLayers == mLoadedGpuLayers) {
                Log.d(TAG, "Model already loaded with same params. Skipping.");
                if (onComplete != null) {
                    mMainHandler.post(onComplete);
                }
                return;
            }
            File file = new File(modelPath);
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Model file inaccessible: " + modelPath);
                if (onComplete != null) {
                    mMainHandler.post(onComplete);
                }
                return;
            }
            boolean success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers);
            if (success) {
                mLoadedModelPath = modelPath;
                mLoadedThreads = nThreads;
                mLoadedCtxSize = nCtx;
                mLoadedGpuLayers = nGpuLayers;
            } else {
                mLoadedModelPath = "";
                mLoadedThreads = -1;
                mLoadedCtxSize = -1;
                mLoadedGpuLayers = -1;
            }
            mLastPromptTokens = null;
            if (onComplete != null) {
                mMainHandler.post(onComplete);
            }
        });
    }

    /**
     * モデルおよびネイティブリソースを非同期でクローズ・解放します。
     */
    public void closeAsync() {
        if (!sLibraryLoaded) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                if (nativeIsLoaded()) {
                    nativeClose();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing native context", e);
            } finally {
                mLoadedModelPath = "";
                mLoadedThreads = -1;
                mLoadedCtxSize = -1;
                mLoadedGpuLayers = -1;
                mLastPromptTokens = null;
            }
        });
    }

    /**
     * Zenz v3 特殊タグフォーマットに準拠したプロンプト文字列を生成します。
     *
     * @param reading      カタカナ変換前の入力読み
     * @param contextLeft  カーソル直前のテキスト（左文脈）
     * @param contextRight カーソル直後のテキスト（右文脈）
     * @return 成形されたプロンプト文字列
     */
    public String formatZenzV3Prompt(String reading, String contextLeft, String contextRight) {
        String katakana = RomajiConverter.toWideKatakana(reading);
        String cleanContext = "";
        if (contextLeft != null && !contextLeft.trim().isEmpty()) {
            cleanContext = contextLeft.replaceAll("[\\r\\n\\t]", "").replaceAll("^[a-zA-Z]+", "").trim();
        }
        StringBuilder sb = new StringBuilder();

        if (!cleanContext.isEmpty()) {
            sb.append("\uEE02").append(cleanContext);
        }
        if (contextRight != null && !contextRight.trim().isEmpty()) {
            String cleanRight = contextRight.replaceAll("[\\r\\n\\t]", "").trim();
            if (!cleanRight.isEmpty()) {
                sb.append("\uEE07").append(cleanRight);
            }
        }
        sb.append("\uEE00").append(katakana);
        return sb.toString();
    }

    /**
     * 現在の設定（文脈使用フラグ）に合わせてプロンプトを成形します。
     *
     * @param reading     入力読み
     * @param contextLeft 左文脈
     * @return プロンプト文字列
     */
    public String formatPrompt(String reading, String contextLeft) {
        return mUseContext ? formatZenzV3Prompt(reading, contextLeft, "") : formatZenzV3Prompt(reading, "", "");
    }

    /**
     * バックグラウンドスレッドで非同期にかな漢字変換を実行します。
     *
     * @param reading     入力読み
     * @param contextLeft 左文脈
     * @param callback    結果受領用コールバック
     */
    public void convertAsync(String reading, String contextLeft, ConversionCallback callback) {
        if (!isEnabled() || reading == null || reading.isEmpty()) {
            if (callback != null) {
                callback.onConversionComplete();
            }
            return;
        }

        mExecutor.execute(() -> convertSyncInternal(reading, contextLeft, callback));
    }

    /**
     * プロンプトやレスポンスに含まれるUnicode文字コード 0xEE00 〜 0xEEFF (Private Use Area) を
     * 16進数値の可視テキストタグ文字列（例: <U+EE00>, <U+EE01> 等）に変換します。
     *
     * @param text 変換対象テキスト
     * @return 可視タグ化された文字列
     */
    public static String formatVisibleText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0xEE00 && c <= 0xEEFF) {
                sb.append(String.format(Locale.US, "<U+%04X>", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 生成された文字列から特殊タグ（0xEE00〜0xEEFF）、置換文字(\uFFFD)、制御文字を完全に除去・無害化します。
     *
     * @param text クリーニング対象テキスト
     * @return 無害化されたクリーンな文字列
     */
    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            // Zenz特殊タグ (0xEE00〜0xEEFF) を除外
            if (c >= 0xEE00 && c <= 0xEEFF) {
                continue;
            }

            // \uFFFD (文字化け/置換文字) を除外
            if (c == '\uFFFD') {
                continue;
            }

            // 非標準制御文字 (改行・タブ以外の0x00〜0x1F) を除外
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                continue;
            }

            sb.append(c);
        }

        return sb.toString().trim();
    }

    private boolean containsStopToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0xEE00 && c <= 0xEEFF) || c == '\uFFFD') {
                return true;
            }
        }
        return false;
    }

    private String trimStopToken(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if ((c >= 0xEE00 && c <= 0xEEFF) || c == '\uFFFD') {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * バックグラウンドスレッドで実行されるストリーミング版・同期変換ロジックです。
     */
    private void convertSyncInternal(String reading, String contextLeft, ConversionCallback callback) {
        String prompt = formatPrompt(reading, contextLeft);
        String visiblePrompt = formatVisibleText(prompt);
        Log.i(TAG, "==================== [ZENZ LLM TEXT SEND/RECV] ====================");
        Log.i(TAG, "[ZENZ PROMPT PROCESSED]:\n" + visiblePrompt + "<U+EE01>");
        Log.i(TAG, "------------------------------------------------------------------");
        int maxTokens = mMaxTokens > 0 ? mMaxTokens : 16;
        int[] promptTokens = nativeTokenize(prompt, true);
        if (promptTokens == null || promptTokens.length == 0) {
            mLastPromptTokens = null;
            if (callback != null) {
                mMainHandler.post(callback::onConversionComplete);
            }
            return;
        }

        int nCtx = nativeGetCtxSize();
        if (promptTokens.length > nCtx - maxTokens - 1) {
            int newLen = Math.max(1, nCtx - maxTokens - 1);
            int[] truncated = new int[newLen];
            System.arraycopy(promptTokens, 0, truncated, 0, newLen);
            promptTokens = truncated;
        }

        // Prefix Caching 判定
        int commonPrefixLen = 0;
        if (mLastPromptTokens != null) {
            int maxLen = Math.min(mLastPromptTokens.length, promptTokens.length);
            while (commonPrefixLen < maxLen && mLastPromptTokens[commonPrefixLen] == promptTokens[commonPrefixLen]) {
                commonPrefixLen++;
            }
        }

        if (commonPrefixLen > 0) {
            nativeClearKvCacheAfter(commonPrefixLen);
            int diffLen = promptTokens.length - commonPrefixLen;
            if (diffLen > 0) {
                int[] diffTokens = new int[diffLen];
                System.arraycopy(promptTokens, commonPrefixLen, diffTokens, 0, diffLen);
                if (!nativeEvalTokens(diffTokens, commonPrefixLen, diffLen)) {
                    mLastPromptTokens = null;
                    nativeClearKvCache();
                    if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                        if (callback != null) {
                            mMainHandler.post(callback::onConversionComplete);
                        }
                        return;
                    }
                }
            }
        } else {
            nativeClearKvCache();
            if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                mLastPromptTokens = null;
                if (callback != null) {
                    mMainHandler.post(callback::onConversionComplete);
                }
                return;
            }
        }

        mLastPromptTokens = promptTokens;
        int eosToken = nativeGetEosToken();
        int promptLen = promptTokens.length;

        // 出力開始タグ \uEE01 を評価
        int[] outputStartTokens = nativeTokenize("\uEE01", false);
        if (outputStartTokens == null || outputStartTokens.length == 0) {
            outputStartTokens = nativeTokenize("\uEE01", true);
        }

        int outputStartLen = 0;
        if (outputStartTokens != null && outputStartTokens.length > 0) {
            if (nativeEvalTokens(outputStartTokens, promptLen, outputStartTokens.length)) {
                outputStartLen = outputStartTokens.length;
            }
        }

        int genStartPos = promptLen + outputStartLen;

        // Top-N 分岐生成
        int maxCandidates = mMaxCandidates > 0 ? mMaxCandidates : 3;
        int[] topTokens = nativeSampleTopKTokens(maxCandidates);
        Set<String> addedSurfaces = new HashSet<>();

        if (topTokens != null && topTokens.length > 0) {
            for (int branch = 0; branch < topTokens.length; branch++) {
                int firstToken = topTokens[branch];
                if (firstToken < 0 || firstToken == eosToken) {
                    continue;
                }

                if (branch > 0) {
                    nativeClearKvCacheAfter(genStartPos);
                }

                java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

                byte[] firstBytes = nativeTokenToPiece(firstToken);
                if (firstBytes != null && firstBytes.length > 0) {
                    outputStream.write(firstBytes, 0, firstBytes.length);
                }

                int currPos = genStartPos;
                if (!nativeEvalTokens(new int[]{firstToken}, currPos++, 1)) {
                    continue;
                }

                int topK = mTopK > 0 ? mTopK : 50;
                float topP = mTopP > 0.0f ? mTopP : 0.90f;
                float temp = mTemperature;

                // 単一の高速直接生成パス
                for (int i = 1; i < maxTokens; i++) {
                    int currToken = nativeSampleToken(temp, topK, topP);
                    if (currToken < 0 || currToken == eosToken) {
                        break;
                    }

                    byte[] pieceBytes = nativeTokenToPiece(currToken);
                    if (pieceBytes != null && pieceBytes.length > 0) {
                        outputStream.write(pieceBytes, 0, pieceBytes.length);

                        String currentStr = "";
                        try {
                            currentStr = outputStream.toString(StandardCharsets.UTF_8.name());
                        } catch (Exception ignored) {
                        }
                        if (containsStopToken(currentStr)) {
                            break;
                        }
                    }
                    if (!nativeEvalTokens(new int[]{currToken}, currPos++, 1)) {
                        break;
                    }
                }

                String rawText = "";
                try {
                    rawText = outputStream.toString(StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                }
                String surfaceResult = sanitizeText(trimStopToken(rawText));

                if (surfaceResult.isEmpty()) {
                    continue;
                }

                // 高速自動アライメント解析
                List<AlignmentPair> alignments = alignReadingAndSurface(reading, surfaceResult);

                Log.i(TAG, "[ZENZ RECV RAW BRANCH " + branch + "]: " + formatVisibleText(rawText) + " => [SURFACE]: " + surfaceResult);
                Log.i(TAG, "[ZENZ ALIGNMENT BRANCH " + branch + "]: " + alignments);

                if (addedSurfaces.add(surfaceResult)) {
                    final Candidate zenzCandidate = new Candidate(reading, surfaceResult, "Zenz AI")
                            .setType(Candidate.Type.SYSTEM);

                    if (callback != null) {
                        mMainHandler.post(() -> callback.onCandidateAvailable(zenzCandidate));
                    }
                }
            }
        }

        nativeClearKvCacheAfter(promptLen);

        if (reading != null && !reading.isEmpty()) {
            if (addedSurfaces.add(reading)) {
                final Candidate kanaCand = new Candidate(reading, reading, "ひらがな").setType(Candidate.Type.SYSTEM);
                if (callback != null) {
                    mMainHandler.post(() -> callback.onCandidateAvailable(kanaCand));
                }
            }

            String katakana = RomajiConverter.toWideKatakana(reading);
            if (katakana != null && addedSurfaces.add(katakana)) {
                final Candidate kataCand = new Candidate(katakana, katakana, "カタカナ").setType(Candidate.Type.SYSTEM);
                if (callback != null) {
                    mMainHandler.post(() -> callback.onCandidateAvailable(kataCand));
                }
            }
        }

        Log.i(TAG, "[ZENZ COMPLETE]");
        Log.i(TAG, "============================================================");

        if (callback != null) {
            mMainHandler.post(callback::onConversionComplete);
        }
    }

    public void release() {
        if (sLibraryLoaded) {
            try {
                if (nativeIsLoaded()) {
                    nativeClose();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing", e);
            } finally {
                mLoadedModelPath = "";
                mLoadedThreads = -1;
                mLoadedCtxSize = -1;
                mLoadedGpuLayers = -1;
                mLastPromptTokens = null;
            }
        }
    }
}
