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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.kachaya.ime.dictionary.Candidate;
import io.github.kachaya.ime.engine.RomajiConverter;

/**
 * llama.cpp と Zenz v3 (かな漢字変換LLM) を統合するエンジンクラスです。
 */
public class ZenzEngine {
    private static final String TAG = "ZenzEngine";

    public static final String PREF_KEY_ZENZ_ENABLED = "zenz_enabled";
    public static final String PREF_KEY_ZENZ_MODEL_PATH = "zenz_model_path";
    public static final String PREF_KEY_ZENZ_THREADS = "zenz_threads";
    public static final String PREF_KEY_ZENZ_CTX_SIZE = "zenz_ctx_size";
    public static final String PREF_KEY_ZENZ_GPU_LAYERS = "zenz_gpu_layers";
    public static final String PREF_KEY_ZENZ_TEMPERATURE = "zenz_temperature";
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
    private int mGpuLayers = 0;
    private float mTemperature = 0.0f;
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
    private native int nativeSampleToken(float temperature);
    private native int[] nativeSampleTopKTokens(int k);
    private native byte[] nativeTokenToPiece(int token);
    private native int nativeGetEosToken();

    public interface ConversionCallback {
        void onResult(List<Candidate> candidates);
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

    public boolean isLoaded() {
        if (!sLibraryLoaded) return false;
        try {
            return nativeIsLoaded();
        } catch (Exception e) {
            Log.e(TAG, "Error checking nativeIsLoaded", e);
            return false;
        }
    }

    public void reloadPreferences(SharedPreferences prefs) {
        if (prefs == null) return;
        mEnabled = prefs.getBoolean(PREF_KEY_ZENZ_ENABLED, false);
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
            mGpuLayers = Integer.parseInt(prefs.getString(PREF_KEY_ZENZ_GPU_LAYERS, "0"));
        } catch (Exception e) {
            mGpuLayers = 0;
        }

        try {
            mTemperature = Float.parseFloat(prefs.getString(PREF_KEY_ZENZ_TEMPERATURE, "0.0"));
        } catch (Exception e) {
            mTemperature = 0.0f;
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
            if (isLoaded() &&
                    modelPath.equals(mLoadedModelPath) &&
                    nThreads == mLoadedThreads &&
                    nCtx == mLoadedCtxSize &&
                    nGpuLayers == mLoadedGpuLayers) {
                Log.d(TAG, "Zenz model is already loaded with same parameters. Skipping reload.");
                if (onComplete != null) mMainHandler.post(onComplete);
                return;
            }

            File file = new File(modelPath);
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Model file inaccessible or does not exist: " + modelPath);
                if (onComplete != null) mMainHandler.post(onComplete);
                return;
            }
            Log.i(TAG, "Initializing Zenz model in background...");
            boolean success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers);
            Log.i(TAG, "Zenz model init result: " + success);

            if (success) {
                mLoadedModelPath = modelPath;
                mLoadedThreads = nThreads;
                mLoadedCtxSize = nCtx;
                mLoadedGpuLayers = nGpuLayers;
                mLastPromptTokens = null;
            } else {
                mLoadedModelPath = "";
                mLoadedThreads = -1;
                mLoadedCtxSize = -1;
                mLoadedGpuLayers = -1;
                mLastPromptTokens = null;
            }

            if (onComplete != null) {
                mMainHandler.post(onComplete);
            }
        });
    }

    public void closeAsync() {
        if (!sLibraryLoaded) return;
        mExecutor.execute(() -> {
            try {
                if (nativeIsLoaded()) {
                    nativeClose();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing native Zenz context", e);
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
     * Zenz v3 推奨プロンプトフォーマットを構築します：
     * \uEE02<context>\uEE00<input_katakana>\uEE01
     */
    public String formatZenzV3Prompt(String reading, String contextLeft, String contextRight) {
        String katakana = RomajiConverter.toWideKatakana(reading);

        String cleanContext = "";
        if (contextLeft != null && !contextLeft.trim().isEmpty()) {
            cleanContext = contextLeft.replaceAll("[\\r\\n\\t]", "")
                    .replaceAll("^[a-zA-Z]+", "")
                    .trim();
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
        sb.append("\uEE00").append(katakana).append("\uEE01");
        return sb.toString();
    }

    public String formatPrompt(String reading, String contextLeft) {
        if (!mUseContext) {
            return formatZenzV3Prompt(reading, "", "");
        }
        return formatZenzV3Prompt(reading, contextLeft, "");
    }

    /**
     * 外部から呼び出される非同期変換メソッドです。
     */
    public void convertAsync(String reading, String contextLeft, ConversionCallback callback) {
        if (!isEnabled() || reading == null || reading.isEmpty()) {
            if (callback != null) callback.onResult(Collections.emptyList());
            return;
        }

        mExecutor.execute(() -> {
            List<Candidate> candidates = convertSyncInternal(reading, contextLeft);
            if (callback != null) {
                mMainHandler.post(() -> callback.onResult(candidates));
            }
        });
    }

    private boolean containsStopToken(String text) {
        if (text == null) return false;
        return text.contains("\uEE00") || text.contains("\uEE01") ||
                text.contains("\uEE02") || text.contains("\uEE07") ||
                text.toLowerCase().contains("<u+ee");
    }

    private String trimStopToken(String text) {
        if (text == null) return "";
        int minIndex = text.length();
        String[] stopStrings = {"\uEE00", "\uEE01", "\uEE02", "\uEE07", "<U+EE", "<u+ee"};
        for (String stopStr : stopStrings) {
            int idx = text.indexOf(stopStr);
            if (idx != -1 && idx < minIndex) {
                minIndex = idx;
            }
        }
        return text.substring(0, minIndex);
    }

    /**
     * 【実装完了】バックグラウンドスレッドで実行される内部同期変換メソッド
     */
    private synchronized List<Candidate> convertSyncInternal(String reading, String contextLeft) {
        // 1. プロンプト生成
        String prompt = formatPrompt(reading, contextLeft);
        Log.i(TAG, "Zenz prompt: " + prompt);

        int maxTokens = 64;

        // 2. トークナイズ
        int[] promptTokens = nativeTokenize(prompt, true);
        if (promptTokens == null || promptTokens.length == 0) {
            mLastPromptTokens = null;
            return Collections.emptyList();
        }

        int nCtx = nativeGetCtxSize();
        if (promptTokens.length > nCtx - maxTokens) {
            int newLen = Math.max(1, nCtx - maxTokens);
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
            // 共通プレフィックス位置以降の古い KV キャッシュをクリア
            nativeClearKvCacheAfter(commonPrefixLen);

            int diffLen = promptTokens.length - commonPrefixLen;
            if (diffLen > 0) {
                int[] diffTokens = new int[diffLen];
                System.arraycopy(promptTokens, commonPrefixLen, diffTokens, 0, diffLen);
                if (!nativeEvalTokens(diffTokens, commonPrefixLen, diffLen)) {
                    Log.e(TAG, "Failed to decode diff prompt tokens. Falling back to full eval.");
                    mLastPromptTokens = null;
                    nativeClearKvCache();
                    if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                        return Collections.emptyList();
                    }
                } else {
                    Log.d(TAG, "Prefix Cache Hit! Common prefix len: " + commonPrefixLen + ", diff len: " + diffLen);
                }
            } else {
                Log.d(TAG, "Prefix Cache Full Hit! Common prefix len: " + commonPrefixLen);
            }
        } else {
            // 完全不一致または初回評価: KV キャッシュを全削除して評価
            nativeClearKvCache();
            if (!nativeEvalTokens(promptTokens, 0, promptTokens.length)) {
                Log.e(TAG, "Failed to decode prompt");
                mLastPromptTokens = null;
                return Collections.emptyList();
            }
        }

        // 次回比較用に今回のプロンプトを保存
        mLastPromptTokens = promptTokens;
        int eosToken = nativeGetEosToken();
        int promptLen = promptTokens.length;

        // 3. Top-N 分岐生成 (最初のトークンの上位 logits から複数候補を生成)
        int maxCandidates = 5;
        int[] topTokens = nativeSampleTopKTokens(maxCandidates);

        List<Candidate> candidates = new ArrayList<>();
        java.util.Set<String> addedSurfaces = new java.util.HashSet<>();

        if (topTokens != null && topTokens.length > 0) {
            for (int branch = 0; branch < topTokens.length; branch++) {
                int firstToken = topTokens[branch];
                if (firstToken < 0 || firstToken == eosToken) {
                    continue;
                }

                // 2番目以降の分岐では、プロンプト直後以降の KV キャッシュをクリアしてプロンプト状態に戻す
                if (branch > 0) {
                    nativeClearKvCacheAfter(promptLen);
                }

                StringBuilder generatedText = new StringBuilder();
                java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

                // 先頭トークンを出力に追加
                byte[] firstBytes = nativeTokenToPiece(firstToken);
                if (firstBytes != null && firstBytes.length > 0) {
                    try {
                        outputStream.write(firstBytes);
                    } catch (Exception e) {
                        Log.e(TAG, "Error writing piece bytes", e);
                    }
                }

                int currPos = promptLen;
                if (!nativeEvalTokens(new int[]{firstToken}, currPos++, 1)) {
                    continue;
                }

                // 残りトークンのデコードループ
                int consecutiveSameTokenCount = 0;
                int lastToken = firstToken;

                for (int i = 1; i < maxTokens; i++) {
                    int currToken = nativeSampleToken(mTemperature);
                    if (currToken < 0 || currToken == eosToken) {
                        break;
                    }

                    if (currToken == lastToken) {
                        consecutiveSameTokenCount++;
                        if (consecutiveSameTokenCount >= 5) {
                            Log.w(TAG, "Zenz generation loop detected in branch " + branch + ". Aborting branch.");
                            break;
                        }
                    } else {
                        consecutiveSameTokenCount = 0;
                    }
                    lastToken = currToken;

                    byte[] pieceBytes = nativeTokenToPiece(currToken);
                    if (pieceBytes != null && pieceBytes.length > 0) {
                        try {
                            outputStream.write(pieceBytes);
                        } catch (Exception e) {
                            Log.e(TAG, "Error writing piece bytes", e);
                        }

                        String currentStr = "";
                        try {
                            currentStr = outputStream.toString("UTF-8");
                        } catch (Exception ignored) {}

                        if (containsStopToken(currentStr)) {
                            currentStr = trimStopToken(currentStr);
                            generatedText.setLength(0);
                            generatedText.append(currentStr);
                            break;
                        }

                        generatedText.setLength(0);
                        generatedText.append(currentStr);
                    }

                    if (!nativeEvalTokens(new int[]{currToken}, currPos++, 1)) {
                        Log.e(TAG, "Failed to decode next token");
                        break;
                    }
                }

                String candidateText = generatedText.toString().trim();
                if (candidateText.contains("<")) {
                    candidateText = candidateText.split("<")[0].trim();
                }

                if (!candidateText.isEmpty() && addedSurfaces.add(candidateText)) {
                    candidates.add(new Candidate(candidateText, candidateText, "Zenz AI")
                            .setType(Candidate.Type.ZENZ));
                }
            }
        }

        // 次回の Prefix Caching のため、生成されたトークン以降の KV キャッシュをクリアしてプロンプト長状態を保持
        nativeClearKvCacheAfter(promptLen);

        // 4. 第二・第三候補: IMEとしての基本フォールバック候補 (ひらがな・カタカナ)
        if (reading != null && !reading.isEmpty()) {
            if (addedSurfaces.add(reading)) {
                candidates.add(new Candidate(reading, reading, "ひらがな")
                        .setType(Candidate.Type.SYSTEM));
            }

            String katakana = RomajiConverter.toWideKatakana(reading);
            if (katakana != null && addedSurfaces.add(katakana)) {
                candidates.add(new Candidate(katakana, katakana, "カタカナ")
                        .setType(Candidate.Type.SYSTEM));
            }
        }

        return candidates;
    }

    /**
     * 【同期的な即時解放版】
     */
    public void release() {
        Log.i(TAG, "release() called. Direct native close...");
        if (sLibraryLoaded) {
            try {
                if (nativeIsLoaded()) {
                    nativeClose();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error directly closing native Zenz context", e);
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
