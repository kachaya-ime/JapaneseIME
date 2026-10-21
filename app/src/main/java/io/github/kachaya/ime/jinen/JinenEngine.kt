package io.github.kachaya.ime.jinen

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.engine.ConversionEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * togatoga 氏による karukan / jinen 日本語かな漢字変換エンジンを統合するクラスです。
 */
class JinenEngine(private val mContext: Context?) : ConversionEngine {

    override val name: String = "Jinen AI"

    private val mExecutorQueue = LinkedBlockingQueue<Runnable>()
    private val mExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, mExecutorQueue
    )
    private val mMainHandler: Handler = Handler(Looper.getMainLooper())
    private val mCurrentRequestId = AtomicLong(0)

    private var mEnabled: Boolean = false
    private var mModelPath: String = ""
    private var mThreads: Int = 4
    private var mTemperature: Float = 0.0f
    private var mUseContext: Boolean = false

    private external fun nativeInit(modelPath: String, nThreads: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeClose()
    private external fun nativeConvert(prompt: String, maxCandidates: Int, temperature: Float): Array<String>?

    override val isEnabled: Boolean
        get() = mEnabled && isLoaded()

    fun isLoaded(): Boolean {
        if (!sLibraryLoaded) {
            return false
        }
        return try {
            nativeIsLoaded()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking nativeIsLoaded for JinenEngine", e)
            false
        }
    }

    fun reloadPreferences(prefs: SharedPreferences?) {
        if (prefs == null) {
            return
        }
        mEnabled = prefs.getBoolean(PREF_KEY_JINEN_ENABLED, false)
        mModelPath = prefs.getString(PREF_KEY_JINEN_MODEL_PATH, "") ?: ""
        val availableCores = Runtime.getRuntime().availableProcessors()
        val defaultThreads = minOf(4, maxOf(1, availableCores - 1))
        val rawThreads = try {
            prefs.getString(PREF_KEY_JINEN_THREADS, defaultThreads.toString())?.toInt() ?: defaultThreads
        } catch (_: Exception) {
            defaultThreads
        }
        mThreads = rawThreads.coerceIn(1, maxOf(1, availableCores))

        mTemperature = try {
            prefs.getString(PREF_KEY_JINEN_TEMPERATURE, "0.0")?.toFloat() ?: 0.0f
        } catch (_: Exception) {
            0.0f
        }
        mUseContext = prefs.getBoolean(PREF_KEY_JINEN_USE_CONTEXT, false)

        if (mEnabled) {
            if (mModelPath.isNotEmpty() && File(mModelPath).exists()) {
                initAsync(mModelPath, mThreads, null)
            } else {
                initFromAssetsAsync(mContext, DEFAULT_ASSET_MODEL_NAME, mThreads, null)
            }
        } else {
            closeAsync()
        }
    }

    fun initFromAssetsAsync(context: Context?, assetFileName: String?, nThreads: Int, onComplete: Runnable?) {
        if (!sLibraryLoaded || context == null) {
            return
        }
        mExecutor.execute {
            val modelPath = prepareModelFileFromAssets(context, assetFileName)
            if (modelPath != null) {
                val success = nativeInit(modelPath, nThreads)
                if (success) {
                    Log.i(TAG, "Successfully initialized Jinen GGUF model: $modelPath")
                } else {
                    Log.e(TAG, "Failed to initialize Jinen GGUF model: $modelPath")
                }
            } else {
                Log.e(TAG, "Failed to prepare Jinen model file from assets: $assetFileName")
            }
            if (onComplete != null) {
                mMainHandler.post(onComplete)
            }
        }
    }

    fun initAsync(modelPath: String, nThreads: Int, onComplete: Runnable?) {
        if (!sLibraryLoaded) {
            return
        }
        mExecutor.execute {
            val file = File(modelPath)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Jinen model file inaccessible: $modelPath")
                if (onComplete != null) {
                    mMainHandler.post(onComplete)
                }
                return@execute
            }
            val success = nativeInit(modelPath, nThreads)
            if (success) {
                Log.i(TAG, "Successfully initialized Jinen GGUF model from path: $modelPath")
            } else {
                Log.e(TAG, "Failed to initialize Jinen GGUF model from path: $modelPath")
            }
            if (onComplete != null) {
                mMainHandler.post(onComplete)
            }
        }
    }

    fun closeAsync() {
        if (!sLibraryLoaded) {
            return
        }
        mExecutor.execute {
            try {
                nativeClose()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing Jinen native resources async", e)
            }
        }
    }

    /**
     * jinen-v2 形式のプロンプトを構築します (NFKC 正規化を適用)。
     * フォーマット:
     *   左コンテキストあり: \uEE02<context>\uEE00<reading>\uEE01
     *   左コンテキストなし: \uEE00<reading>\uEE01
     */
    fun buildKarukanPrompt(query: String, leftContext: String?): String {
        val contextMarker = "\uEE02"
        val inputMarker = "\uEE00"
        val outputMarker = "\uEE01"

        val rawPrompt = if (mUseContext && !leftContext.isNullOrEmpty()) {
            "$contextMarker$leftContext$inputMarker$query$outputMarker"
        } else {
            "$inputMarker$query$outputMarker"
        }

        // NFKC 正規化 (必須。入れないと精度が大きく落ちる)
        return java.text.Normalizer.normalize(rawPrompt, java.text.Normalizer.Form.NFKC)
    }

    override fun convertSync(query: String, preferredSurface: String?): List<Candidate> {
        return convertSyncWithContext(query, preferredSurface)
    }

    fun convertSyncWithContext(query: String, leftContext: String? = null): List<Candidate> {
        if (!isEnabled || query.isEmpty()) {
            Log.d(TAG, "[convertSync Skipped] isEnabled=$isEnabled, query=\"$query\"")
            return emptyList()
        }
        val prompt = buildKarukanPrompt(query, leftContext)
        Log.i(TAG, "==> [Jinen convertSync Start] query=\"$query\", prompt=\"${prompt.replace("\n", "\\n")}\"")
        return try {
            val results = nativeConvert(prompt, 5, mTemperature) ?: return emptyList()
            val candidates = results.mapIndexed { index, surface ->
                val rank = index + 1
                val cleanSurface = surface.trim()
                Log.i(TAG, "  [Jinen Candidate Generated] rank=$rank, surface=\"$cleanSurface\", query=\"$query\"")
                Candidate(
                    rawCandidate = query,
                    candidate = cleanSurface
                ).apply {
                    type = Candidate.Type.JINEN
                }
            }
            Log.i(TAG, "<== [Jinen convertSync Complete] query=\"$query\", totalCandidates=${candidates.size}")
            candidates
        } catch (e: Exception) {
            Log.e(TAG, "Error in Jinen convertSync for query=\"$query\"", e)
            emptyList()
        }
    }

    override fun convertAsync(
        query: String,
        leftContext: String?,
        callback: ConversionEngine.AsyncCallback
    ) {
        if (!isEnabled || query.isEmpty()) {
            Log.d(TAG, "[convertAsync Skipped] isEnabled=$isEnabled, query=\"$query\"")
            return
        }
        val requestId = mCurrentRequestId.incrementAndGet()
        Log.i(TAG, "==> [Jinen convertAsync Queued] requestId=$requestId, query=\"$query\", leftContext=\"$leftContext\"")
        mExecutor.execute {
            if (mCurrentRequestId.get() != requestId) {
                Log.d(TAG, "[Jinen convertAsync Cancelled] requestId=$requestId is outdated.")
                return@execute
            }
            val candidates = convertSyncWithContext(query, leftContext)
            for ((index, candidate) in candidates.withIndex()) {
                if (mCurrentRequestId.get() != requestId) {
                    Log.d(TAG, "[Jinen convertAsync Cancelled Mid-stream] requestId=$requestId is outdated.")
                    break
                }
                mMainHandler.post {
                    Log.i(TAG, "<== [Jinen Async Callback Executed] rank=${index + 1}, candidate=\"${candidate.candidate}\"")
                    callback.onCandidateAvailable(candidate)
                }
            }
        }
    }

    override fun cancelPending() {
        mCurrentRequestId.incrementAndGet()
        mExecutorQueue.clear()
    }

    override fun release() {
        cancelPending()
        if (sLibraryLoaded) {
            try {
                nativeClose()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing JinenEngine native resources", e)
            }
        }
    }

    companion object {
        private const val TAG = "JinenEngine"

        const val PREF_KEY_JINEN_ENABLED = "jinen_enabled"
        const val PREF_KEY_JINEN_MODEL_PATH = "jinen_model_path"
        const val PREF_KEY_JINEN_THREADS = "jinen_threads"
        const val PREF_KEY_JINEN_TEMPERATURE = "jinen_temperature"
        const val PREF_KEY_JINEN_USE_CONTEXT = "jinen_use_context"

        const val DEFAULT_ASSET_MODEL_NAME = "jinen-v2-xsmall-Q5_K_M.gguf"

        private var sLibraryLoaded = false

        init {
            try {
                System.loadLibrary("llama_engine")
                sLibraryLoaded = true
                Log.i(TAG, "Successfully loaded libllama_engine.so (Original llama.cpp)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libllama_engine.so", e)
            }
        }

        @JvmStatic
        fun prepareModelFileFromAssets(context: Context?, assetFileName: String?): String? {
            if (context == null || assetFileName.isNullOrEmpty()) {
                return null
            }

            val targetFile = File(context.filesDir, assetFileName)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            try {
                context.assets.open(assetFileName).use { `in` ->
                    FileOutputStream(targetFile).use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (`in`.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                        out.flush()
                        Log.i(TAG, "Successfully extracted Jinen asset model $assetFileName to ${targetFile.absolutePath}")
                        return targetFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting Jinen asset model: $assetFileName", e)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                return null
            }
        }
    }
}
