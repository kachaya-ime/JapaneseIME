package io.github.kachaya.ime.dictionary

import android.content.Context
import android.util.Log

/**
 * システム辞書、学習辞書、予測辞書のライフサイクル、スレッドセーフなアクセス、
 * リロード、エクスポート/インポートを集中管理するマネージャークラスです。
 */
class DictionaryManager private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val dictionary: Dictionary = Dictionary(appContext)

    fun reloadLearningDictionary() {
        synchronized(this) {
            try {
                dictionary.reloadLearningDictionary()
                Log.i("DictionaryManager", "Learning dictionary reloaded successfully.")
            } catch (e: Exception) {
                Log.e("DictionaryManager", "Failed to reload learning dictionary", e)
            }
        }
    }

    fun clearAllUserDictionaries() {
        synchronized(this) {
            try {
                dictionary.clearLearningDictionary()
                Log.i("DictionaryManager", "All user learning and prediction dictionaries cleared.")
            } catch (e: Exception) {
                Log.e("DictionaryManager", "Failed to clear user dictionaries", e)
            }
        }
    }

    fun exportLearningDictionary(): List<String> {
        synchronized(this) {
            return dictionary.exportLearningDictionary()
        }
    }

    fun importLearningDictionary(entries: List<String>) {
        synchronized(this) {
            try {
                dictionary.importLearningDictionary(entries)
                Log.i("DictionaryManager", "Imported ${entries.size} learning entries.")
            } catch (e: Exception) {
                Log.e("DictionaryManager", "Failed to import learning dictionary", e)
            }
        }
    }

    fun close() {
        synchronized(this) {
            try {
                dictionary.close()
                Log.i("DictionaryManager", "Dictionary manager closed.")
            } catch (e: Exception) {
                Log.e("DictionaryManager", "Failed to close dictionary", e)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: DictionaryManager? = null

        fun getInstance(context: Context): DictionaryManager {
            return instance ?: synchronized(this) {
                instance ?: DictionaryManager(context).also { instance = it }
            }
        }

        fun getDictionary(context: Context): Dictionary {
            return getInstance(context).dictionary
        }
    }
}
