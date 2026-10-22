package io.github.kachaya.ime.karukan

import android.content.Context
import android.util.Log
import io.github.kachaya.ime.dictionary.Candidate
import io.github.kachaya.ime.engine.ConversionEngine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class KarukanEngine(
    private val context: Context,
    override var isEnabled: Boolean = false
) : ConversionEngine {

    override val name: String = "Karukan"

    private var isModelLoaded: Boolean = false
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "KarukanEngine"
        private const val DEFAULT_ASSET_MODEL_NAME = "jinen-v2-xsmall-Q5_K_M.gguf"

        @JvmStatic
        fun prepareModelFileFromAssets(context: Context?, assetFileName: String): String? {
            if (context == null || assetFileName.isEmpty()) return null

            val targetFile = File(context.filesDir, assetFileName)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            return try {
                context.assets.open(assetFileName).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        outputStream.flush()
                        Log.i(TAG, "Successfully extracted Karukan asset model $assetFileName to ${targetFile.absolutePath}")
                        targetFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting Karukan asset model: $assetFileName", e)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                null
            }
        }
    }

    init {
        executor.execute {
            try {
                Log.i(TAG, "Loading libkarukan_android.so...")
                System.loadLibrary("karukan_android")

                prepareModelFileFromAssets(context, "tokenizer.json")
                val modelPath = prepareModelFileFromAssets(context, DEFAULT_ASSET_MODEL_NAME)
                if (modelPath != null) {
                    val success = nativeInit(modelPath)
                    isModelLoaded = success
                    Log.i(TAG, "libkarukan_android.so initialized with model: $modelPath (success = $success)")
                } else {
                    Log.e(TAG, "Failed to prepare Karukan model file from assets: $DEFAULT_ASSET_MODEL_NAME")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load libkarukan_android.so or initialize nativeInit()", e)
            }
        }
    }

    override fun convertSync(query: String, preferredSurface: String?): List<Candidate> {
        if (!isEnabled) {
            Log.d(TAG, "convertSync skipped because KarukanEngine is disabled (isEnabled = false)")
            return emptyList()
        }
        if (query.isEmpty()) return emptyList()

        return try {
            Log.d(TAG, "convertSync requested for query='$query'")
            val results = nativeConvert(query)
            Log.i(TAG, "nativeConvert('$query') returned ${results.size} candidates: ${results.joinToString()}")
            results.map { candidateStr ->
                Candidate(candidateStr, query, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during nativeConvert('$query')", e)
            emptyList()
        }
    }

    override fun convertAsync(
        query: String,
        leftContext: String?,
        callback: ConversionEngine.AsyncCallback
    ) {
        val candidates = convertSync(query, null)
        for (candidate in candidates) {
            callback.onCandidateAvailable(candidate)
        }
    }

    override fun cancelPending() {}

    @Suppress("JniMissingFunction")
    private external fun nativeInit(modelPath: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeConvert(query: String): Array<String>
}
