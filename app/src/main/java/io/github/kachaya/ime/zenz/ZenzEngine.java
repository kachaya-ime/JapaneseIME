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

    private native boolean nativeInit(String modelPath, int nThreads, int nCtx, int nGpuLayers);
    private native boolean nativeIsLoaded();
    private native void nativeClose();
    private native String nativeConvert(String prompt, int maxTokens, float temperature);

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
            } else {
                mLoadedModelPath = "";
                mLoadedThreads = -1;
                mLoadedCtxSize = -1;
                mLoadedGpuLayers = -1;
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

    public void convertAsync(String reading, ConversionCallback callback) {
        convertAsync(reading, "", callback);
    }

    public List<Candidate> convertSync(String reading, String contextLeft) {
        if (!isEnabled() || reading == null || reading.isEmpty()) {
            return Collections.emptyList();
        }
        return convertSyncInternal(reading, contextLeft);
    }

    public List<Candidate> convertSync(String reading) {
        return convertSync(reading, "");
    }

    public static String escapeControlTags(String str) {
        if (str == null || str.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= '\uEE00' && c <= '\uEE07') {
                sb.append(String.format("<U+%04X>", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<Candidate> convertSyncInternal(String reading, String contextLeft) {
        if (!isLoaded()) return Collections.emptyList();

        String prompt = formatPrompt(reading, contextLeft);
        Log.d(TAG, "Zenz conversion prompt: " + escapeControlTags(prompt));

        long startTime = System.currentTimeMillis();
        String result = nativeConvert(prompt, 64, mTemperature);
        long duration = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Zenz conversion raw result: '" + escapeControlTags(result) + "' (took " + duration + " ms)");

        if (result == null || result.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return parseCandidates(reading, result);
    }

    private List<Candidate> parseCandidates(String reading, String rawResult) {
        List<Candidate> candidates = new ArrayList<>();
        if (rawResult == null) return candidates;

        String clean = rawResult.trim();

        // Truncate at the first control character or special token
        String[] tags = new String[]{"\uEE00", "\uEE01", "\uEE02", "\uEE03", "\uEE04", "\uEE05", "\uEE06", "\uEE07", "<|im_end|>", "<|endoftext|>", "</s>", "<s>", "[PAD]", "[UNK]"};
        for (String tag : tags) {
            int idx = clean.indexOf(tag);
            if (idx != -1) {
                clean = clean.substring(0, idx);
            }
        }

        clean = clean.trim();
        if (clean.isEmpty()) return candidates;

        // Separate multiple candidate lines if any
        String[] lines = clean.split("\n");
        for (String line : lines) {
            String candStr = line.trim();
            if (!candStr.isEmpty()) {
                Candidate candidate = new Candidate(candStr, reading);
                candidate.setType(Candidate.Type.ZENZ);
                candidate.setAnnotation("Zenz LLM");
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    public void release() {
        closeAsync();
        mExecutor.shutdown();
    }
}
