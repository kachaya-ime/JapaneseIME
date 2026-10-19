package io.github.kachaya.ime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import io.github.kachaya.ime.dictionary.Dictionary
import io.github.kachaya.ime.dictionary.LearningDictionary
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * ユーザー辞書の内容を管理するための管理用アクティビティです。
 */
class LearningDictionaryTool : AppCompatActivity() {

    private var mDictionary: Dictionary? = null

    private val mExportResultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onExportActivityResult(result) }

    private var mAdapter: LearningDictionaryAdapter? = null
    private var mOptionsMenu: Menu? = null

    class LearningItem(
        val key: String,
        val surface: String,
        val posId: Int,
        val posString: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary_tool)
        val dict = Dictionary(this)
        mDictionary = dict

        supportActionBar?.apply {
            title = "学習辞書ツール"
            setDisplayHomeAsUpEnabled(true)
        }

        val listView = findViewById<ListView>(R.id.list_view)
        listView.emptyView = findViewById(R.id.empty_text)

        val items = ArrayList<LearningItem>()
        val adapter = LearningDictionaryAdapter(items)
        mAdapter = adapter
        listView.adapter = adapter

        refreshList()

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (item != null) {
                showCandidateSelectionDialog(item)
            }
        }
    }

    override fun onDestroy() {
        mDictionary?.close()
        super.onDestroy()
    }

    private fun refreshList() {
        val dict = mDictionary ?: return
        val adapter = mAdapter ?: return
        val records = dict.getAllLearningRecords()
        val items = ArrayList<LearningItem>()
        for (r in records) {
            val posString = dict.getPosString(r.posId)
            items.add(LearningItem(r.key, r.surface, r.posId, posString))
        }
        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun showCandidateSelectionDialog(item: LearningItem) {
        AlertDialog.Builder(this)
            .setTitle("単語エントリの削除")
            .setMessage("「${item.key} ➔ ${item.surface}」(品詞: ${item.posString} [ID: ${item.posId}]) を削除しますか？")
            .setPositiveButton("削除する") { _, _ ->
                val view = findViewById<View>(R.id.list_view)
                mDictionary?.removeEntry(item.key, item.surface)
                Snackbar.make(view, "「${item.key} : ${item.surface}」を削除しました", Snackbar.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mOptionsMenu = menu
        menuInflater.inflate(R.menu.dictionary_tool_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val optionsMenu = mOptionsMenu
        val adapter = mAdapter
        if (optionsMenu != null && adapter != null) {
            val hasItems = adapter.count > 0

            val exportItem = optionsMenu.findItem(R.id.menu_export_user)
            val clearItem = optionsMenu.findItem(R.id.menu_clear_user)

            exportItem?.isEnabled = hasItems
            clearItem?.isEnabled = hasItems
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_export_user -> {
                onClickExportDictionary()
                true
            }
            R.id.menu_clear_user -> {
                onClickClearUserDictionary()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun getDefaultFileName(): String {
        val name = mDictionary?.getLearningDictionaryName() ?: "learning_dict"
        return "$name.txt"
    }

    fun exportDictionary(): List<String> {
        return mDictionary?.exportLearningDictionary() ?: emptyList()
    }

    private fun onClickExportDictionary() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, getDefaultFileName())
        }
        mExportResultLauncher.launch(intent)
    }

    private fun onExportActivityResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            val resultData = result.data
            val uri = resultData?.data
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri, "wt").use { outputStream ->
                        if (outputStream != null) {
                            BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                                for (entry in exportDictionary()) {
                                    writer.write("$entry\n")
                                }
                                writer.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun onClickClearUserDictionary() {
        AlertDialog.Builder(this)
            .setTitle("学習辞書のクリア")
            .setMessage("学習辞書のすべての単語を削除しますか？\n（インポートした追加辞書は保持されます）")
            .setPositiveButton("はい、削除します") { _, _ ->
                mDictionary?.clearLearningDictionary()
                Snackbar.make(findViewById(R.id.list_view), "学習辞書をクリアしました", Snackbar.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("いいえ", null)
            .show()
    }

    private inner class LearningDictionaryAdapter(items: List<LearningItem>) :
        ArrayAdapter<LearningItem>(this@LearningDictionaryTool, R.layout.item_learning_dictionary, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_learning_dictionary, parent, false)

            val item = getItem(position)
            val readingView = view.findViewById<TextView>(R.id.text_reading)
            val surfaceView = view.findViewById<TextView>(R.id.text_surface)
            val posView = view.findViewById<TextView>(R.id.text_pos)

            if (item != null) {
                readingView.text = item.key
                surfaceView.text = item.surface
                posView.text = item.posString
            }

            return view
        }
    }
}
