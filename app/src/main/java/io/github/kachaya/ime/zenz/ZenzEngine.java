package io.github.kachaya.ime.zenz;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 元のソース通りの正しいインポートパスに修正しました
import io.github.kachaya.ime.dictionary.Candidate;
import io.github.kachaya.ime.engine.RomajiConverter;

/**
 * llama.cpp と Zenz v3 (かな漢字変換LLM) を統合するエンジンクラスです。
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

    private static boolean sLibraryLoaded = false;

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

    private final Context mContext;
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

    private native int nativeSampleToken(float temperature, int topK, float topP); // 引数拡張

    private native int[] nativeSampleTopKTokens(int k);

    private native byte[] nativeTokenToPiece(int token);

    private native int nativeGetEosToken();

    public interface ConversionCallback {
        /**
         * Zenz AI候補が1つ生成されるたびに、即座に呼び出されます（ストリーミング用）
         */
        void onCandidateAvailable(Candidate candidate);

        /**
         * すべてのAI生成プロセスが終了したときに呼び出されます
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
        if (!sLibraryLoaded) return false;
        try {
            return nativeIsLoaded();
        } catch (Exception e) {
            Log.e(TAG, "Error checking nativeIsLoaded", e); return false;
        }
    }

    public void reloadPreferences(SharedPreferences prefs) {
        if (prefs == null) return;
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

        if (mEnabled && !mModelPath.isEmpty()) {
            initAsync(mModelPath, mThreads, mCtxSize, mGpuLayers, null);
        } else {
            closeAsync();
        }
    }

    public void initAsync(String modelPath, int nThreads, int nCtx, int nGpuLayers, Runnable onComplete) {
        if (!sLibraryLoaded) return;
        mExecutor.execute(() -> {
            if (isLoaded() && modelPath.equals(mLoadedModelPath) && nThreads == mLoadedThreads && nCtx == mLoadedCtxSize && nGpuLayers == mLoadedGpuLayers) {
                Log.d(TAG, "Model already loaded with same params. Skipping.");
                if (onComplete != null) mMainHandler.post(onComplete);
                return;
            }
            File file = new File(modelPath);
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Model file inaccessible: " + modelPath);
                if (onComplete != null) mMainHandler.post(onComplete);
                return;
            }
            boolean success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers);
            if (success) {
                mLoadedModelPath = modelPath; mLoadedThreads = nThreads; mLoadedCtxSize = nCtx; mLoadedGpuLayers = nGpuLayers;
            } else {
                mLoadedModelPath = ""; mLoadedThreads = -1; mLoadedCtxSize = -1; mLoadedGpuLayers = -1;
            }
            mLastPromptTokens = null;
            if (onComplete != null) mMainHandler.post(onComplete);
        });
    }

    public void closeAsync() {
        if (!sLibraryLoaded) return;
        mExecutor.execute(() -> {
            try {
                if (nativeIsLoaded()) nativeClose();
            } catch (Exception e) {
                Log.e(TAG, "Error closing native context", e);
            } finally {
                mLoadedModelPath = ""; mLoadedThreads = -1; mLoadedCtxSize = -1; mLoadedGpuLayers = -1; mLastPromptTokens = null;
            }
        });
    }

    public String formatZenzV3Prompt(String reading, String contextLeft, String contextRight) {
        String katakana = RomajiConverter.toWideKatakana(reading);
        String cleanContext = "";
        if (contextLeft != null && !contextLeft.trim().isEmpty()) {
            cleanContext = contextLeft.replaceAll("[\\r\\n\\t]", "").replaceAll("^[a-zA-Z]+", "").trim();
        }
        StringBuilder sb = new StringBuilder();
        if (!cleanContext.isEmpty()) sb.append("\uEE02").append(cleanContext);
        if (contextRight != null && !contextRight.trim().isEmpty()) {
            String cleanRight = contextRight.replaceAll("[\\r\\n\\t]", "").trim();
            if (!cleanRight.isEmpty()) sb.append("\uEE07").append(cleanRight);
        }
        sb.append("\uEE00").append(katakana);
        return sb.toString();
    }

    public String formatPrompt(String reading, String contextLeft) {
        return mUseContext ? formatZenzV3Prompt(reading, contextLeft, "") : formatZenzV3Prompt(reading, "", "");
    }

    public void convertAsync(String reading, String contextLeft, ConversionCallback callback) {
        if (!isEnabled() || reading == null || reading.isEmpty()) {
            if (callback != null) {
                callback.onConversionComplete();
            }
            return;
        }

        mExecutor.execute(() -> {
            // 同期メソッド内で逐次コールバックを叩く構造に変更
            convertSyncInternal(reading, contextLeft, callback);
        });
    }

    /**
     * プロンプトやレスポンスに含まれるUnicode文字コード 0xEE00 〜 0xEEFF (Private Use Area) を
     * 16進数値の可視テキストタグ文字列（例: <U+EE00>, <U+EE01> 等）に変換します。
     */
    public static String formatVisibleText(String text) {
        if (text == null || text.isEmpty()) return "";

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

    private boolean containsStopToken(String text) {
        if (text == null || text.isEmpty()) return false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0xEE00 && c <= 0xEEFF) {
                return true;
            }
        }
        return false;
    }

    private String trimStopToken(String text) {
        if (text == null || text.isEmpty()) return "";
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0xEE00 && c <= 0xEEFF) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * バックグラウンドスレッドで実行されるストリーミング版・同期変換ロジック
     * 1つのブランチ（候補）の生成が完了するたびに、即座にメインスレッドのコールバックへポストします。
     */
    private void convertSyncInternal(String reading, String contextLeft, ConversionCallback callback) {
        String prompt = formatPrompt(reading, contextLeft);
        String visiblePrompt = formatVisibleText(prompt);
        Log.i(TAG, "==================== [ZENZ LLM TEXT SEND/RECV] ====================");
        Log.i(TAG, "[ZENZ PROMPT PROCESSED]:\n" + visiblePrompt + "<U+EE01>");
        Log.i(TAG, "------------------------------------------------------------------");
        int maxTokens = 16; // レスポンス向上のため16に最適化
        int[] promptTokens = nativeTokenize(prompt, true);
        if (promptTokens == null || promptTokens.length == 0) {
            mLastPromptTokens = null;
            if (callback != null) mMainHandler.post(callback::onConversionComplete);
            return;
        }

        int nCtx = nativeGetCtxSize();
        if (promptTokens.length > nCtx - maxTokens - 1) {
            int newLen = Math.max(1, nCtx - maxTokens - 1);
            int[] truncated = new int[newLen];
            System.arraycopy(promptTokens, 0, truncated, 0, newLen);
            promptTokens = truncated;
        }

        // --- Prefix Caching 判定 ---
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
                    mLastPromptTokens = null; nativeClearKvCache();
                    if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                        if (callback != null) mMainHandler.post(callback::onConversionComplete);
                        return;
                    }
                }
            }
        } else {
            nativeClearKvCache();
            if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                mLastPromptTokens = null;
                if (callback != null) mMainHandler.post(callback::onConversionComplete);
                return;
            }
        }

        mLastPromptTokens = promptTokens;
        int eosToken = nativeGetEosToken();
        int promptLen = promptTokens.length;

        // 生成開始直前に \uEE01 (出力開始タグ) を 1 トークンのみ評価
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
        int maxCandidates = 3; // レスポンス向上のため3に最適化
        int[] topTokens = nativeSampleTopKTokens(maxCandidates);
        java.util.Set<String> addedSurfaces = new java.util.HashSet<>();

        if (topTokens != null && topTokens.length > 0) {
            for (int branch = 0; branch < topTokens.length; branch++) {
                int firstToken = topTokens[branch];
                if (firstToken < 0 || firstToken == eosToken) continue;
                if (branch > 0) nativeClearKvCacheAfter(genStartPos);

                java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
                byte[] firstBytes = nativeTokenToPiece(firstToken);
                if (firstBytes != null && firstBytes.length > 0) outputStream.write(firstBytes, 0, firstBytes.length);

                int currPos = genStartPos;
                if (!nativeEvalTokens(new int[]{firstToken}, currPos++, 1)) continue;

                int consecutiveSameTokenCount = 0;
                int lastToken = firstToken;

                for (int i = 1; i < maxTokens; i++) {
                    int topK = (mTemperature > 0.0f) ? 50 : 0;
                    float topP = (mTemperature > 0.0f) ? 0.95f : 0.0f;

                    int currToken = nativeSampleToken(mTemperature, topK, topP);
                    if (currToken < 0 || currToken == eosToken) break;

                    if (currToken == lastToken) {
                        if (++consecutiveSameTokenCount >= 5) break;
                    } else {
                        consecutiveSameTokenCount = 0;
                    }
                    lastToken = currToken;

                    byte[] pieceBytes = nativeTokenToPiece(currToken);
                    if (pieceBytes != null && pieceBytes.length > 0) {
                        outputStream.write(pieceBytes, 0, pieceBytes.length);
                        String currentStr = "";
                        try { currentStr = outputStream.toString("UTF-8"); } catch (Exception ignored) {}
                        if (containsStopToken(currentStr)) break;
                    }
                    if (!nativeEvalTokens(new int[]{currToken}, currPos++, 1)) break;
                }

                // 確定した1本の文字列をトリミング
                String rawText = "";
                try {
                    rawText = outputStream.toString("UTF-8");
                } catch (Exception ignored) {}

                String visibleRawText = formatVisibleText(rawText);
                String candidateText = trimStopToken(rawText).trim();

                Log.i(TAG, "[ZENZ RECV RAW]: " + visibleRawText + " => [CANDIDATE]: " + candidateText);

                if (!candidateText.isEmpty() && addedSurfaces.add(candidateText)) {
                    final Candidate zenzCandidate = new Candidate(candidateText, candidateText, "Zenz AI")
                            .setType(Candidate.Type.SYSTEM);

                    // 【最重要】1つの候補が完成した瞬間に、即座にUIスレッドへ通知（ストリーミング）
                    if (callback != null) {
                        mMainHandler.post(() -> callback.onCandidateAvailable(zenzCandidate));
                    }
                }
            }
        }

        // 次のライブ入力変更時のPrefix Cacheヒットのため、KV Cacheを読みの末尾 (\uEE01の手前) まで巻き戻す
        nativeClearKvCacheAfter(promptLen);

        // システムフォールバック (かな・カナ) も即時に通知
        if (reading != null && !reading.isEmpty()) {
            if (addedSurfaces.add(reading)) {
                final Candidate kanaCand = new Candidate(reading, reading, "ひらがな").setType(Candidate.Type.SYSTEM);
                if (callback != null) mMainHandler.post(() -> callback.onCandidateAvailable(kanaCand));
            }

            String katakana = RomajiConverter.toWideKatakana(reading);
            if (katakana != null && addedSurfaces.add(katakana)) {
                final Candidate kataCand = new Candidate(katakana, katakana, "カタカナ").setType(Candidate.Type.SYSTEM);
                if (callback != null) mMainHandler.post(() -> callback.onCandidateAvailable(kataCand));
            }
        }

        Log.i(TAG, "[ZENZ COMPLETE]");
        Log.i(TAG, "============================================================");

        // すべての生成プロセスがクリーンに完了したことを通知
        if (callback != null) {
            mMainHandler.post(callback::onConversionComplete);
        }
    }

    public void release() {
        if (sLibraryLoaded) {
            try {
                if (nativeIsLoaded()) nativeClose();
            } catch (Exception e) {
                Log.e(TAG, "Error closing", e);
            } finally {
                mLoadedModelPath = ""; mLoadedThreads = -1; mLoadedCtxSize = -1; mLoadedGpuLayers = -1; mLastPromptTokens = null;
            }
        }
    }
}
