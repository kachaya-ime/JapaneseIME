package io.github.kachaya.ime

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.google.android.material.snackbar.Snackbar
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.dictionary.PredictionDictionary

/**
 * 予測辞書（Bigram接続学習）の内容を管理・閲覧・検索・削除するためのアクティビティです。
 */
class PredictionDictionaryTool : AppCompatActivity() {

    private var mPredictionDictionary: PredictionDictionary? = null
    private var mDictionary: Dictionary? = null
    private var mAdapter: PredictionDictionaryAdapter? = null
    private var mAllEntries = ArrayList<PredictionDictionary.PredictionEntry>()
    private var mFilteredEntries = ArrayList<PredictionDictionary.PredictionEntry>()
    private var mCurrentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prediction_dictionary_tool)
        mPredictionDictionary = PredictionDictionary(this)
        mDictionary = Dictionary(this)

        supportActionBar?.apply {
            title = "予測辞書ツール"
            setDisplayHomeAsUpEnabled(true)
        }

        val listView = findViewById<ListView>(R.id.list_view)
        listView.emptyView = findViewById(R.id.empty_text)

        val adapter = PredictionDictionaryAdapter(mFilteredEntries)
        mAdapter = adapter
        listView.adapter = adapter

        refreshList()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entries = mFilteredEntries
            if (position in entries.indices) {
                showDeleteDialog(entries[position])
            }
        }
    }

    override fun onDestroy() {
        mPredictionDictionary?.close()
        mDictionary?.close()
        super.onDestroy()
    }

    private fun refreshList() {
        val dict = mPredictionDictionary ?: return
        mAllEntries.clear()
        mAllEntries.addAll(dict.getAllPredictions())
        filterAndDisplay(mCurrentQuery)
    }

    private fun filterAndDisplay(query: String) {
        mCurrentQuery = query
        mFilteredEntries.clear()
        if (query.isBlank()) {
            mFilteredEntries.addAll(mAllEntries)
        } else {
            val q = query.lowercase()
            val dict = mDictionary
            for (entry in mAllEntries) {
                val prevPosStr = dict?.getPosString(entry.prevPosId)?.lowercase() ?: ""
                val nextPosStr = dict?.getPosString(entry.nextPosId)?.lowercase() ?: ""
                val pWord = if (entry.prevWord == "__BOS__") "文頭 bos" else (entry.prevWord?.lowercase() ?: "")
                if (pWord.contains(q) ||
                    (entry.nextWord?.lowercase()?.contains(q) == true) ||
                    prevPosStr.contains(q) ||
                    nextPosStr.contains(q)) {
                    mFilteredEntries.add(entry)
                }
            }
        }

        val adapter = mAdapter ?: return
        adapter.notifyDataSetChanged()
    }

    private fun showDeleteDialog(entry: PredictionDictionary.PredictionEntry) {
        val dict = mDictionary
        val nextPosStr = if (dict != null) dict.getPosString(entry.nextPosId) else ""
        AlertDialog.Builder(this)
            .setTitle("予測接続の削除")
            .setMessage("「${entry.prevWord} ➔ ${entry.nextWord}」(品詞: $nextPosStr) の学習データを削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                mPredictionDictionary?.deletePrediction(entry.prevWord, entry.nextWord)
                Snackbar.make(findViewById(R.id.list_view), "接続データを削除しました", Snackbar.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dictionary_tool_menu, menu)
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "予測接続・品詞を検索..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterAndDisplay(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterAndDisplay(newText ?: "")
                return true
            }
        })

        menu.findItem(R.id.menu_export_user)?.isVisible = false
        menu.findItem(R.id.menu_clear_user)?.title = "予測辞書をクリア"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_clear_user -> {
                onClickClearPredictionDictionary()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onClickClearPredictionDictionary() {
        AlertDialog.Builder(this)
            .setTitle("予測辞書のクリア")
            .setMessage("予測接続（Bigram）のすべての学習データを削除しますか？")
            .setPositiveButton("はい、削除します") { _, _ ->
                mPredictionDictionary?.clearPredictionData()
                Snackbar.make(findViewById(R.id.list_view), "予測辞書をクリアしました", Snackbar.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("いいえ", null)
            .show()
    }

    private inner class PredictionDictionaryAdapter(items: List<PredictionDictionary.PredictionEntry>) :
        ArrayAdapter<PredictionDictionary.PredictionEntry>(this@PredictionDictionaryTool, R.layout.item_prediction_dictionary, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_prediction_dictionary, parent, false)
            val entry = getItem(position)

            val prevWordView = view.findViewById<TextView>(R.id.text_prev_word)
            val nextWordView = view.findViewById<TextView>(R.id.text_next_word)
            val prevPosView = view.findViewById<TextView>(R.id.text_prev_pos)
            val nextPosView = view.findViewById<TextView>(R.id.text_next_pos)

            if (entry != null) {
                val isBoz = entry.prevWord == "__BOS__"
                prevWordView.text = if (isBoz) "文頭 (BOS)" else (entry.prevWord ?: "")
                nextWordView.text = entry.nextWord ?: ""

                val dict = mDictionary
                val prevPosStr = if (dict != null && entry.prevPosId > 0) dict.getPosString(entry.prevPosId) else if (isBoz) "文頭" else "不明"
                val nextPosStr = if (dict != null && entry.nextPosId > 0) dict.getPosString(entry.nextPosId) else "不明"

                prevPosView?.text = "左品詞: $prevPosStr"
                nextPosView?.text = "右品詞: $nextPosStr"
            }

            return view
        }
    }
}
