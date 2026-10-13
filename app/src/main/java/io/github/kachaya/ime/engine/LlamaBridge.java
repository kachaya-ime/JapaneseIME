package io.github.kachaya.ime.engine;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * llama.cpp Native JNI との通信を管理し、モデルの非同期推論およびライフサイクル制御を行うブリッジクラスです。
 */
public class LlamaBridge {

    private static final String TAG = "LlamaBridge";
    private static final String LIB_NAME = "zenz_llama";

    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary(LIB_NAME);
            sLibraryLoaded = true;
            Log.i(TAG, "Native library " + LIB_NAME + " loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library " + LIB_NAME, e);
            sLibraryLoaded = false;
        }
    }

    public interface InferenceCallback {
        void onSuccess(String result);
        void onError(Exception e);
    }

    private static LlamaBridge sInstance;

    private long mNativeHandle = 0;
    private String mCurrentModelPath = null;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private LlamaBridge() {
    }

    public static synchronized LlamaBridge getInstance() {
        if (sInstance == null) {
            sInstance = new LlamaBridge();
        }
        return sInstance;
    }

    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    public synchronized boolean isModelLoaded() {
        if (!sLibraryLoaded || mNativeHandle == 0) {
            return false;
        }
        try {
            return nativeIsLoaded(mNativeHandle);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeIsLoaded link error", e);
            return false;
        }
    }

    public synchronized void init(Context context, String modelPath, int nThreads, int nCtx) {
        if (!sLibraryLoaded) {
            Log.w(TAG, "Native library not loaded. Cannot init LlamaBridge.");
            return;
        }

        if (modelPath == null || modelPath.trim().isEmpty()) {
            Log.w(TAG, "Model path is empty.");
            close();
            return;
        }

        if (mNativeHandle != 0 && modelPath.equals(mCurrentModelPath)) {
            Log.d(TAG, "Model already loaded at " + modelPath);
            return;
        }

        close();

        mExecutor.execute(() -> {
            try {
                String resolvedPath = resolveModelFilePath(context, modelPath);
                if (resolvedPath == null || resolvedPath.isEmpty()) {
                    Log.e(TAG, "Failed to resolve model file path for: " + modelPath);
                    return;
                }

                File file = new File(resolvedPath);
                if (!file.exists()) {
                    Log.e(TAG, "Model file does not exist: " + resolvedPath);
                    return;
                }

                long handle = nativeInit(resolvedPath, Math.max(1, nThreads), Math.max(128, nCtx));
                if (handle != 0) {
                    synchronized (LlamaBridge.this) {
                        mNativeHandle = handle;
                        mCurrentModelPath = modelPath;
                    }
                    Log.i(TAG, "Llama model initialized successfully from: " + resolvedPath);
                } else {
                    Log.e(TAG, "Failed to initialize Llama model from: " + resolvedPath + " (nativeInit returned 0)");
                    if (modelPath.startsWith("content://") && context != null) {
                        File cacheFile = new File(new File(context.getFilesDir(), "models"), "llm_model_cache.gguf");
                        if (cacheFile.exists()) {
                            boolean deleted = cacheFile.delete();
                            Log.w(TAG, "Deleted invalid/incomplete cached model file: " + cacheFile.getAbsolutePath() + " (deleted=" + deleted + ")");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Llama model", e);
            }
        });
    }

    private String resolveModelFilePath(Context context, String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            return null;
        }

        if (modelPath.startsWith("content://")) {
            if (context == null) {
                Log.e(TAG, "Context is null, cannot resolve content URI");
                return null;
            }
            try {
                Uri uri = Uri.parse(modelPath);
                File cacheDir = new File(context.getFilesDir(), "models");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }
                File cacheFile = new File(cacheDir, "llm_model_cache.gguf");

                long expectedSize = -1;
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (pfd != null) {
                        expectedSize = pfd.getStatSize();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get stat size for content URI", e);
                }

                if (cacheFile.exists() && expectedSize > 0 && cacheFile.length() == expectedSize) {
                    Log.i(TAG, "Using existing cached model file: " + cacheFile.getAbsolutePath() + " (" + cacheFile.length() + " bytes)");
                    return cacheFile.getAbsolutePath();
                }

                Log.i(TAG, "Copying content URI to local cache: " + cacheFile.getAbsolutePath());
                try (InputStream is = context.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    if (is == null) {
                        Log.e(TAG, "ContentResolver returned null InputStream for " + modelPath);
                        return null;
                    }
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    long totalRead = 0;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        totalRead += read;
                    }
                    fos.flush();
                    Log.i(TAG, "Successfully copied content URI to cache: " + cacheFile.getAbsolutePath() + " (" + totalRead + " bytes)");
                }
                return cacheFile.getAbsolutePath();
            } catch (Exception e) {
                Log.e(TAG, "Error resolving content URI: " + modelPath, e);
                return null;
            }
        }

        return modelPath;
    }

    public void generateAsync(String prompt, int maxTokens, float temperature, InferenceCallback callback) {
        if (!sLibraryLoaded || mNativeHandle == 0) {
            if (callback != null) {
                callback.onError(new IllegalStateException("Llama model is not loaded."));
            }
            return;
        }

        mExecutor.execute(() -> {
            try {
                long handle;
                synchronized (LlamaBridge.this) {
                    handle = mNativeHandle;
                }
                if (handle == 0) {
                    if (callback != null) {
                        callback.onError(new IllegalStateException("Native handle released."));
                    }
                    return;
                }

                String output = nativeGenerate(handle, prompt, maxTokens, temperature);
                if (callback != null) {
                    callback.onSuccess(output != null ? output : "");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during native generation", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    public synchronized void close() {
        if (mNativeHandle != 0 && sLibraryLoaded) {
            long handleToFree = mNativeHandle;
            mNativeHandle = 0;
            mCurrentModelPath = null;
            mExecutor.execute(() -> {
                try {
                    nativeFree(handleToFree);
                    Log.i(TAG, "Llama model context freed.");
                } catch (Exception e) {
                    Log.e(TAG, "Error freeing native context", e);
                }
            });
        }
    }

    // Native JNI Methods
    private static native long nativeInit(String modelPath, int nThreads, int nCtx);
    private static native String nativeGenerate(long handle, String prompt, int maxTokens, float temperature);
    private static native void nativeFree(long handle);
    private static native boolean nativeIsLoaded(long handle);
}
