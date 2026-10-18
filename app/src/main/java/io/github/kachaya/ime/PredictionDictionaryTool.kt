package io.github.kachaya.ime

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import io.github.kachaya.ime.dictionary.PredictionDictionary

/**
 * 予測辞書（Bigram接続学習）の内容を管理・閲覧するためのアクティビティです。
 */
class PredictionDictionaryTool : AppCompatActivity() {

    private var mPredictionDictionary: PredictionDictionary? = null
    private var mAdapter: ArrayAdapter<String>? = null
    private var mPredictionEntries: List<PredictionDictionary.PredictionEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary_tool)
        val dict = PredictionDictionary(this)
        mPredictionDictionary = dict

        supportActionBar?.apply {
            title = "予測辞書ツール（接続学習）"
            setDisplayHomeAsUpEnabled(true)
        }

        val listView = findViewById<ListView>(R.id.list_view)
        listView.emptyView = findViewById(R.id.empty_text)

        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        mAdapter = adapter
        listView.adapter = adapter

        refreshList()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entries = mPredictionEntries
            if (entries != null && position in entries.indices) {
                showDeleteDialog(entries[position])
            }
        }
    }

    override fun onDestroy() {
        mPredictionDictionary?.close()
        super.onDestroy()
    }

    private fun refreshList() {
        val adapter = mAdapter ?: return
        val dict = mPredictionDictionary ?: return
        adapter.clear()
        val entries = dict.getAllPredictions()
        mPredictionEntries = entries
        val displayStrings = entries.map { it.toString() }
        adapter.addAll(displayStrings)
        adapter.notifyDataSetChanged()
    }

    private fun showDeleteDialog(entry: PredictionDictionary.PredictionEntry) {
        AlertDialog.Builder(this)
            .setTitle("予測接続の削除")
            .setMessage("「${entry.prevWord} ➔ ${entry.nextWord}」の学習データを削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                mPredictionDictionary?.deletePrediction(entry.prevWord, entry.nextWord)
                Snackbar.make(findViewById(R.id.list_view), "接続データを削除しました", Snackbar.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
