package io.github.kachaya.ime.zenz

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.engine.ConversionEngine
import io.github.kachaya.ime.engine.RomajiConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * llama.cpp と Zenz v3 (かな漢字変換LLM) を統合する変換エンジンクラスです。
 */
class ZenzEngine(private val mContext: Context?) : ConversionEngine {

    override val name: String = "Zenz AI"

    private val mExecutorQueue = LinkedBlockingQueue<Runnable>()
    private val mExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, mExecutorQueue
    )
    private val mMainHandler: Handler = Handler(Looper.getMainLooper())
    private val mCurrentRequestId = AtomicLong(0)

    private var mEnabled: Boolean = false
    private var mModelPath: String = ""
    private var mThreads: Int = 4
    private var mCtxSize: Int = 512
    private var mMaxTokens: Int = 16
    private var mMaxCandidates: Int = 3
    private var mGpuLayers: Int = 0
    private var mTemperature: Float = 0.0f
    private var mTopP: Float = 0.90f
    private var mTopK: Int = 50
    var isUseContext: Boolean = false
        private set

    private var mLoadedModelPath: String = ""
    private var mLoadedThreads: Int = -1
    private var mLoadedCtxSize: Int = -1
    private var mLoadedGpuLayers: Int = -1
    private var mLastPromptTokens: IntArray? = null

    private external fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int, nGpuLayers: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeClose()
    private external fun nativeTokenize(text: String, parseSpecial: Boolean): IntArray?
    private external fun nativeGetCtxSize(): Int
    private external fun nativeClearKvCache()
    private external fun nativeClearKvCacheAfter(pos: Int)
    private external fun nativeEvalTokens(tokens: IntArray, startPos: Int, nTokens: Int): Boolean
    private external fun nativeSampleToken(temperature: Float, topK: Int, topP: Float): Int
    private external fun nativeSampleTopKTokens(k: Int): IntArray?
    private external fun nativeTokenToPiece(token: Int): ByteArray?
    private external fun nativeGetEosToken(): Int

    /**
     * 変換結果を受け取るための非同期コールバックインターフェースです。
     */
    interface ConversionCallback {
        /**
         * Zenz AI 候補が1つ生成されるたびに即座に呼び出されます（ストリーミング用）。
         */
        fun onCandidateAvailable(candidate: Candidate)

        /**
         * すべての AI 生成プロセスが完了したときに呼び出されます。
         */
        fun onConversionComplete()
    }

    override val isEnabled: Boolean
        get() = mEnabled && isLoaded()

    fun isLoaded(): Boolean {
        if (!sLibraryLoaded) {
            return false
        }
        return try {
            nativeIsLoaded()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking nativeIsLoaded", e)
            false
        }
    }

    /**
     * SharedPreferences から設定値を読み込み、エンジン設定を再反映します。
     */
    fun reloadPreferences(prefs: SharedPreferences?) {
        if (prefs == null) {
            return
        }
        mEnabled = if (prefs.contains(PREF_KEY_ZENZ_ENABLED)) {
            prefs.getBoolean(PREF_KEY_ZENZ_ENABLED, true)
        } else {
            val engine = prefs.getString(PREF_KEY_CONVERSION_ENGINE, ENGINE_ZENZ)
            ENGINE_ZENZ == engine
        }
        mModelPath = prefs.getString(PREF_KEY_ZENZ_MODEL_PATH, "") ?: ""
        val availableCores = Runtime.getRuntime().availableProcessors()
        val defaultThreads = minOf(4, maxOf(1, availableCores - 1))
        val rawThreads = try {
            prefs.getString(PREF_KEY_ZENZ_THREADS, defaultThreads.toString())?.toInt() ?: defaultThreads
        } catch (_: Exception) {
            defaultThreads
        }
        mThreads = rawThreads.coerceIn(1, maxOf(1, availableCores))
        Log.i(TAG, "CPU Cores detected: $availableCores. Configured zenz_threads: $mThreads (user configured: $rawThreads)")
        mCtxSize = try {
            prefs.getString(PREF_KEY_ZENZ_CTX_SIZE, "512")?.toInt() ?: 512
        } catch (_: Exception) {
            512
        }
        mMaxTokens = try {
            prefs.getString(PREF_KEY_ZENZ_MAX_TOKENS, "16")?.toInt() ?: 16
        } catch (_: Exception) {
            16
        }
        if (mMaxTokens <= 0) {
            mMaxTokens = 16
        }
        mMaxCandidates = try {
            prefs.getString(PREF_KEY_ZENZ_MAX_CANDIDATES, "3")?.toInt() ?: 3
        } catch (_: Exception) {
            3
        }
        if (mMaxCandidates <= 0) {
            mMaxCandidates = 3
        }
        mGpuLayers = try {
            prefs.getString(PREF_KEY_ZENZ_GPU_LAYERS, "0")?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
        mTemperature = try {
            prefs.getString(PREF_KEY_ZENZ_TEMPERATURE, "0.0")?.toFloat() ?: 0.0f
        } catch (_: Exception) {
            0.0f
        }
        mTopP = try {
            prefs.getString(PREF_KEY_ZENZ_TOP_P, "0.90")?.toFloat() ?: 0.90f
        } catch (_: Exception) {
            0.90f
        }
        mTopK = try {
            prefs.getString(PREF_KEY_ZENZ_TOP_K, "50")?.toInt() ?: 50
        } catch (_: Exception) {
            50
        }
        if (mTopK < 0) {
            mTopK = 50
        }
        isUseContext = prefs.getBoolean(PREF_KEY_ZENZ_USE_CONTEXT, false)

        if (mEnabled) {
            if (mModelPath.isNotEmpty() && File(mModelPath).exists()) {
                initAsync(mModelPath, mThreads, mCtxSize, mGpuLayers, null)
            } else {
                initFromAssetsAsync(mContext, DEFAULT_ASSET_MODEL_NAME, mThreads, mCtxSize, mGpuLayers, null)
            }
        } else {
            closeAsync()
        }
    }

    fun initFromAssetsAsync(context: Context?, assetFileName: String?, nThreads: Int, nCtx: Int, nGpuLayers: Int, onComplete: Runnable?) {
        if (!sLibraryLoaded || context == null) {
            return
        }

        mExecutor.execute {
            val modelPath = prepareModelFileFromAssets(context, assetFileName)
            if (modelPath != null) {
                if (isLoaded() && modelPath == mLoadedModelPath && nThreads == mLoadedThreads && nCtx == mLoadedCtxSize && nGpuLayers == mLoadedGpuLayers) {
                    Log.d(TAG, "Model already loaded. Skipping.")
                    if (onComplete != null) {
                        mMainHandler.post(onComplete)
                    }
                    return@execute
                }

                val success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers)
                if (success) {
                    mLoadedModelPath = modelPath
                    mLoadedThreads = nThreads
                    mLoadedCtxSize = nCtx
                    mLoadedGpuLayers = nGpuLayers
                    Log.i(TAG, "Successfully loaded GGUF model: $modelPath")
                } else {
                    mLoadedModelPath = ""
                    mLoadedThreads = -1
                    mLoadedCtxSize = -1
                    mLoadedGpuLayers = -1
                    Log.e(TAG, "Failed to load GGUF model: $modelPath")
                }
            } else {
                Log.e(TAG, "Failed to prepare model file from assets: $assetFileName")
            }

            mLastPromptTokens = null
            if (onComplete != null) {
                mMainHandler.post(onComplete)
            }
        }
    }

    fun initAsync(modelPath: String, nThreads: Int, nCtx: Int, nGpuLayers: Int, onComplete: Runnable?) {
        if (!sLibraryLoaded) {
            return
        }
        mExecutor.execute {
            if (isLoaded() && modelPath == mLoadedModelPath && nThreads == mLoadedThreads && nCtx == mLoadedCtxSize && nGpuLayers == mLoadedGpuLayers) {
                Log.d(TAG, "Model already loaded with same params. Skipping.")
                if (onComplete != null) {
                    mMainHandler.post(onComplete)
                }
                return@execute
            }
            val file = File(modelPath)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Model file inaccessible: $modelPath")
                if (onComplete != null) {
                    mMainHandler.post(onComplete)
                }
                return@execute
            }
            val success = nativeInit(modelPath, nThreads, nCtx, nGpuLayers)
            if (success) {
                mLoadedModelPath = modelPath
                mLoadedThreads = nThreads
                mLoadedCtxSize = nCtx
                mLoadedGpuLayers = nGpuLayers
            } else {
                mLoadedModelPath = ""
                mLoadedThreads = -1
                mLoadedCtxSize = -1
                mLoadedGpuLayers = -1
            }
            mLastPromptTokens = null
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
                if (nativeIsLoaded()) {
                    nativeClose()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing native context", e)
            } finally {
                mLoadedModelPath = ""
                mLoadedThreads = -1
                mLoadedCtxSize = -1
                mLoadedGpuLayers = -1
                mLastPromptTokens = null
            }
        }
    }

    fun formatZenzV3Prompt(reading: String, contextLeft: String?, contextRight: String?): String {
        val katakana = RomajiConverter.toWideKatakana(reading)
        var cleanContext = ""
        if (!contextLeft.isNullOrBlank()) {
            cleanContext = contextLeft.replace("[\r\n\t]".toRegex(), "").replace("^[a-zA-Z]+".toRegex(), "").trim()
        }
        val sb = StringBuilder()

        if (cleanContext.isNotEmpty()) {
            sb.append("\uEE02").append(cleanContext)
        }
        if (!contextRight.isNullOrBlank()) {
            val cleanRight = contextRight.replace("[\r\n\t]".toRegex(), "").trim()
            if (cleanRight.isNotEmpty()) {
                sb.append("\uEE07").append(cleanRight)
            }
        }
        sb.append("\uEE00").append(katakana)
        return sb.toString()
    }

    fun formatPrompt(reading: String, contextLeft: String?): String {
        return if (isUseContext) formatZenzV3Prompt(reading, contextLeft, "") else formatZenzV3Prompt(reading, "", "")
    }

    override fun convertSync(query: String, preferredSurface: String?): List<Candidate> {
        return emptyList()
    }

    override fun convertAsync(
        query: String,
        leftContext: String?,
        callback: ConversionEngine.AsyncCallback
    ) {
        if (!isEnabled || query.isEmpty()) return
        convertAsync(query, leftContext, object : ConversionCallback {
            override fun onCandidateAvailable(candidate: Candidate) {
                callback.onCandidateAvailable(candidate)
            }

            override fun onConversionComplete() {}
        })
    }

    override fun cancelPending() {
        cancelPendingRequests()
    }

    /**
     * キューに溜まっている未実行の変換リクエストをすべてキャンセルし、
     * 実行中の変換処理も即座に中断します。
     */
    fun cancelPendingRequests(): Long {
        val newId = mCurrentRequestId.incrementAndGet()
        mExecutorQueue.clear()
        Log.d(TAG, "All pending Zenz requests cancelled. New Request ID: $newId")
        return newId
    }

    fun convertAsync(reading: String?, contextLeft: String?, callback: ConversionCallback?): Long {
        val requestId = mCurrentRequestId.incrementAndGet()
        mExecutorQueue.clear()

        if (!isEnabled || reading.isNullOrEmpty()) {
            callback?.onConversionComplete()
            return requestId
        }

        mExecutor.execute {
            if (requestId != mCurrentRequestId.get()) {
                Log.d(TAG, "[ZENZ REQUEST CANCELLED IN QUEUE] reqId=$requestId (current=${mCurrentRequestId.get()})")
                return@execute
            }
            convertSyncInternal(requestId, reading, contextLeft, callback)
        }
        return requestId
    }

    private fun containsStopToken(text: String?): Boolean {
        if (text.isNullOrEmpty()) {
            return false
        }
        val len = text.length
        for (i in 0..<len) {
            val c = text[i]
            if ((c in '\uEE00'..'\uEEFF') || c == '\uFFFD') {
                return true
            }
        }
        return false
    }

    private fun trimStopToken(text: String?): String {
        if (text.isNullOrEmpty()) {
            return ""
        }
        val len = text.length
        for (i in 0..<len) {
            val c = text[i]
            if ((c in '\uEE00'..'\uEEFF') || c == '\uFFFD') {
                return text.substring(0, i)
            }
        }
        return text
    }

    private fun isCancelled(requestId: Long): Boolean = requestId != mCurrentRequestId.get()

    private fun convertSyncInternal(requestId: Long, reading: String, contextLeft: String?, callback: ConversionCallback?) {
        if (isCancelled(requestId)) {
            Log.d(TAG, "[ZENZ CANCELLED BEFORE START] reqId=$requestId")
            return
        }

        val prompt = formatPrompt(reading, contextLeft)
        val visiblePrompt = formatVisibleText(prompt)
        Log.i(TAG, "==================== [ZENZ LLM TEXT SEND/RECV] (reqId=$requestId) ====================")
        Log.i(TAG, "[ZENZ PROMPT PROCESSED]:\n$visiblePrompt<U+EE01>")
        Log.i(TAG, "------------------------------------------------------------------")
        val maxTokens = if (mMaxTokens > 0) mMaxTokens else 16
        var promptTokens = nativeTokenize(prompt, true)
        if (isCancelled(requestId)) {
            Log.d(TAG, "[ZENZ CANCELLED AFTER TOKENIZE] reqId=$requestId")
            return
        }
        if (promptTokens == null || promptTokens.isEmpty()) {
            mLastPromptTokens = null
            if (!isCancelled(requestId)) {
                callback?.let { mMainHandler.post { it.onConversionComplete() } }
            }
            return
        }

        val nCtx = nativeGetCtxSize()
        if (promptTokens.size > nCtx - maxTokens - 1) {
            val newLen = Math.max(1, nCtx - maxTokens - 1)
            val truncated = IntArray(newLen)
            System.arraycopy(promptTokens, 0, truncated, 0, newLen)
            promptTokens = truncated
        }

        var commonPrefixLen = 0
        val lastTokens = mLastPromptTokens
        if (lastTokens != null) {
            val maxLen = Math.min(lastTokens.size, promptTokens.size)
            while (commonPrefixLen < maxLen && lastTokens[commonPrefixLen] == promptTokens[commonPrefixLen]) {
                commonPrefixLen++
            }
        }

        if (isCancelled(requestId)) {
            Log.d(TAG, "[ZENZ CANCELLED BEFORE KV EVAL] reqId=$requestId")
            mLastPromptTokens = null
            return
        }

        if (commonPrefixLen > 0) {
            nativeClearKvCacheAfter(commonPrefixLen)
            val diffLen = promptTokens.size - commonPrefixLen
            if (diffLen > 0) {
                val diffTokens = IntArray(diffLen)
                System.arraycopy(promptTokens, commonPrefixLen, diffTokens, 0, diffLen)
                if (!nativeEvalTokens(diffTokens, commonPrefixLen, diffLen)) {
                    mLastPromptTokens = null
                    nativeClearKvCache()
                    if (!nativeEvalTokens(promptTokens, 0, promptTokens.size)) {
                        if (!isCancelled(requestId)) {
                            callback?.let { mMainHandler.post { it.onConversionComplete() } }
                        }
                        return
                    }
                }
            }
        } else {
            nativeClearKvCache()
            if (!nativeEvalTokens(promptTokens, 0, promptTokens.size)) {
                mLastPromptTokens = null
                if (!isCancelled(requestId)) {
                    callback?.let { mMainHandler.post { it.onConversionComplete() } }
                }
                return
            }
        }

        if (isCancelled(requestId)) {
            Log.d(TAG, "[ZENZ CANCELLED AFTER PROMPT EVAL] reqId=$requestId")
            mLastPromptTokens = null
            return
        }

        mLastPromptTokens = promptTokens
        val eosToken = nativeGetEosToken()
        val promptLen = promptTokens.size

        var outputStartTokens = nativeTokenize("\uEE01", false)
        if (outputStartTokens == null || outputStartTokens.isEmpty()) {
            outputStartTokens = nativeTokenize("\uEE01", true)
        }

        var outputStartLen = 0
        if (outputStartTokens != null && outputStartTokens.isNotEmpty()) {
            if (nativeEvalTokens(outputStartTokens, promptLen, outputStartTokens.size)) {
                outputStartLen = outputStartTokens.size
            }
        }

        val genStartPos = promptLen + outputStartLen

        val maxCandidates = if (mMaxCandidates > 0) mMaxCandidates else 3
        val topTokens = nativeSampleTopKTokens(maxCandidates)
        val addedSurfaces = HashSet<String>()

        if (topTokens != null && topTokens.isNotEmpty()) {
            for (branch in topTokens.indices) {
                if (isCancelled(requestId)) {
                    Log.d(TAG, "[ZENZ CANCELLED IN BRANCH LOOP] reqId=$requestId")
                    break
                }

                val firstToken = topTokens[branch]
                if (firstToken < 0 || firstToken == eosToken) {
                    continue
                }

                if (branch > 0) {
                    nativeClearKvCacheAfter(genStartPos)
                }

                val outputStream = ByteArrayOutputStream()

                val firstBytes = nativeTokenToPiece(firstToken)
                if (firstBytes != null && firstBytes.isNotEmpty()) {
                    outputStream.write(firstBytes, 0, firstBytes.size)
                }

                var currPos = genStartPos
                if (!nativeEvalTokens(intArrayOf(firstToken), currPos++, 1)) {
                    continue
                }

                val topK = if (mTopK > 0) mTopK else 50
                val topP = if (mTopP > 0.0f) mTopP else 0.90f
                val temp = mTemperature

                for (i in 1..<maxTokens) {
                    if (isCancelled(requestId)) {
                        Log.d(TAG, "[ZENZ CANCELLED IN TOKEN GEN] reqId=$requestId")
                        break
                    }

                    val currToken = nativeSampleToken(temp, topK, topP)
                    if (currToken < 0 || currToken == eosToken) {
                        break
                    }

                    val pieceBytes = nativeTokenToPiece(currToken)
                    if (pieceBytes != null && pieceBytes.isNotEmpty()) {
                        outputStream.write(pieceBytes, 0, pieceBytes.size)

                        var currentStr = ""
                        try {
                            currentStr = outputStream.toString(StandardCharsets.UTF_8.name())
                        } catch (_: Exception) {
                        }
                        if (containsStopToken(currentStr)) {
                            break
                        }
                    }
                    if (!nativeEvalTokens(intArrayOf(currToken), currPos++, 1)) {
                        break
                    }
                }

                if (isCancelled(requestId)) {
                    Log.d(TAG, "[ZENZ CANCELLED AFTER BRANCH GENERATION] reqId=$requestId")
                    break
                }

                var rawText = ""
                try {
                    rawText = outputStream.toString(StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                }
                val surfaceResult = sanitizeText(trimStopToken(rawText))

                if (surfaceResult.isEmpty()) {
                    continue
                }

                Log.i(TAG, "[ZENZ RECV RAW BRANCH $branch]: " + formatVisibleText(rawText) + " => [SURFACE]: " + surfaceResult)

                if (addedSurfaces.add(surfaceResult)) {
                    val zenzCandidate = Candidate(reading, surfaceResult, "Zenz AI")
                        .setType(Candidate.Type.ZENZ)

                    if (!isCancelled(requestId)) {
                        callback?.let { mMainHandler.post { it.onCandidateAvailable(zenzCandidate) } }
                    }
                }
            }
        }

        nativeClearKvCacheAfter(promptLen)

        if (!isCancelled(requestId)) {
            Log.i(TAG, "[ZENZ COMPLETE] reqId=$requestId")
            Log.i(TAG, "============================================================")
            callback?.let { mMainHandler.post { it.onConversionComplete() } }
        } else {
            Log.i(TAG, "[ZENZ REQUEST CANCELLED/ABORTED] reqId=$requestId")
        }
    }

    override fun release() {
        if (sLibraryLoaded) {
            try {
                if (nativeIsLoaded()) {
                    nativeClose()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing", e)
            } finally {
                mLoadedModelPath = ""
                mLoadedThreads = -1
                mLoadedCtxSize = -1
                mLoadedGpuLayers = -1
                mLastPromptTokens = null
            }
        }
    }

    companion object {
        private const val TAG = "ZenzEngine"

        const val PREF_KEY_CONVERSION_ENGINE = "conversion_engine"
        const val ENGINE_VITERBI = "viterbi"
        const val ENGINE_ZENZ = "zenz"

        const val PREF_KEY_ZENZ_ENABLED = "zenz_enabled"
        const val PREF_KEY_ZENZ_MODEL_PATH = "zenz_model_path"
        const val PREF_KEY_ZENZ_THREADS = "zenz_threads"
        const val PREF_KEY_ZENZ_CTX_SIZE = "zenz_ctx_size"
        const val PREF_KEY_ZENZ_MAX_TOKENS = "zenz_max_tokens"
        const val PREF_KEY_ZENZ_MAX_CANDIDATES = "zenz_max_candidates"
        const val PREF_KEY_ZENZ_GPU_LAYERS = "zenz_gpu_layers"
        const val PREF_KEY_ZENZ_TEMPERATURE = "zenz_temperature"
        const val PREF_KEY_ZENZ_TOP_P = "zenz_top_p"
        const val PREF_KEY_ZENZ_TOP_K = "zenz_top_k"
        const val PREF_KEY_ZENZ_USE_CONTEXT = "zenz_use_context"

        const val DEFAULT_ASSET_MODEL_NAME = "zenz-v3.2-xsmall.gguf"

        @JvmStatic
        var isLibraryLoaded: Boolean = false
            private set
            get() = sLibraryLoaded

        private var sLibraryLoaded: Boolean = false

        init {
            try {
                System.loadLibrary("zenz_engine")
                sLibraryLoaded = true
                Log.i(TAG, "libzenz_engine.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library zenz_engine", e)
                sLibraryLoaded = false
            }
        }

        @JvmStatic
        fun prepareModelFileFromAssets(context: Context?, assetFileName: String?): String? {
            if (context == null || assetFileName.isNullEmpty()) {
                return null
            }

            val targetFile = File(context.filesDir, assetFileName!!)
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
                        Log.i(TAG, "Successfully extracted asset model $assetFileName to ${targetFile.absolutePath}")
                        return targetFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting asset model: $assetFileName", e)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                return null
            }
        }

        @JvmStatic
        fun formatVisibleText(text: String?): String {
            if (text.isNullEmpty()) {
                return ""
            }

            val sb = StringBuilder()
            val len = text!!.length

            for (i in 0..<len) {
                val c = text[i]
                if (c in '\uEE00'..'\uEEFF') {
                    sb.append(String.format(Locale.US, "<U+%04X>", c.code))
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun sanitizeText(text: String?): String {
            if (text.isNullEmpty()) {
                return ""
            }

            val sb = StringBuilder()
            val len = text!!.length

            for (i in 0..<len) {
                val c = text[i]

                if (c in '\uEE00'..'\uEEFF') {
                    continue
                }

                if (c == '\uFFFD') {
                    continue
                }

                if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                    continue
                }

                sb.append(c)
            }

            return sb.toString().trim()
        }

        private fun String?.isNullEmpty(): Boolean = this.isNullOrEmpty()
    }
}
