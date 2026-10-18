package io.github.kachaya.ime

import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.kachaya.ime.engine.InputAction
import io.github.kachaya.ime.engine.KeyShortcutManager

/**
 * キーショートカットの設定・カスタマイズを行うアクティビティです。
 */
class ShortcutActivity : AppCompatActivity() {

    companion object {
        private val CUSTOMIZABLE_ACTIONS = InputAction.entries.toTypedArray()
    }

    private var mListView: ListView? = null
    private var mAdapter: ShortcutAdapter? = null
    private var mMappings: MutableMap<Int, InputAction> = HashMap()

    private class ShortcutItem(
        val keyCode: Int,
        val title: String,
        val isReserved: Boolean,
        val summary: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut)

        supportActionBar?.apply {
            title = "ショートカットキーの設定"
            setDisplayHomeAsUpEnabled(true)
        }

        mListView = findViewById(R.id.list_view)

        loadData()

        mListView?.setOnItemClickListener { _, _, position, _ ->
            val adapter = mAdapter ?: return@setOnItemClickListener
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            if (item.isReserved) {
                Toast.makeText(this, "${item.title} は予約済みのため変更できません", Toast.LENGTH_SHORT).show()
            } else {
                showActionChoiceDialog(item)
            }
        }
    }

    private fun loadData() {
        mMappings = HashMap(KeyShortcutManager.loadMappings(this))
        val items = ArrayList<ShortcutItem>()

        val specialKeys = intArrayOf(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DEL
        )
        val specialTitles = arrayOf(
            "Ctrl + Space",
            "Ctrl + Enter",
            "Ctrl + Tab",
            "Ctrl + Backspace"
        )

        for (i in specialKeys.indices) {
            val key = specialKeys[i]
            val isReserved = KeyShortcutManager.isReserved(key)
            val summary = if (isReserved) {
                KeyShortcutManager.getReservedLabel(key)
            } else {
                val action = mMappings[key] ?: InputAction.NONE
                action.label
            }
            items.add(ShortcutItem(key, specialTitles[i], isReserved, summary))
        }

        val shiftSpecialKeys = intArrayOf(
            KeyEvent.KEYCODE_SPACE or KeyShortcutManager.SHIFT_MASK,
            KeyEvent.KEYCODE_ENTER or KeyShortcutManager.SHIFT_MASK,
            KeyEvent.KEYCODE_TAB or KeyShortcutManager.SHIFT_MASK,
            KeyEvent.KEYCODE_DEL or KeyShortcutManager.SHIFT_MASK
        )
        val shiftSpecialTitles = arrayOf(
            "Shift + Space",
            "Shift + Enter",
            "Shift + Tab",
            "Shift + Backspace"
        )

        for (i in shiftSpecialKeys.indices) {
            val key = shiftSpecialKeys[i]
            val action = mMappings[key] ?: InputAction.NONE
            items.add(ShortcutItem(key, shiftSpecialTitles[i], false, action.label))
        }

        for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val letter = ('A' + keyCode - KeyEvent.KEYCODE_A)
            val title = "Ctrl + $letter"
            val isReserved = KeyShortcutManager.isReserved(keyCode)
            val summary = if (isReserved) {
                KeyShortcutManager.getReservedLabel(keyCode)
            } else {
                val action = mMappings[keyCode] ?: InputAction.NONE
                action.label
            }
            items.add(ShortcutItem(keyCode, title, isReserved, summary))
        }

        val adapter = ShortcutAdapter(items)
        mAdapter = adapter
        mListView?.adapter = adapter
    }

    private fun showActionChoiceDialog(item: ShortcutItem) {
        val labels = Array(CUSTOMIZABLE_ACTIONS.size) { "" }
        var currentIndex = 0
        val currentAction = mMappings[item.keyCode] ?: InputAction.NONE

        for (i in CUSTOMIZABLE_ACTIONS.indices) {
            labels[i] = CUSTOMIZABLE_ACTIONS[i].label
            if (CUSTOMIZABLE_ACTIONS[i] == currentAction) {
                currentIndex = i
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${item.title} の機能設定")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedAction = CUSTOMIZABLE_ACTIONS[which]
                mMappings[item.keyCode] = selectedAction
                KeyShortcutManager.saveMappings(this, mMappings)
                loadData()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.shortcut_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_reset_defaults -> {
                AlertDialog.Builder(this)
                    .setTitle("初期値に戻す")
                    .setMessage("すべてのキーショートカットの設定を初期状態に戻しますか？")
                    .setPositiveButton("リセット") { _, _ ->
                        KeyShortcutManager.resetToDefaults(this)
                        loadData()
                        Toast.makeText(this, "設定を初期状態に戻しました", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private inner class ShortcutAdapter(items: List<ShortcutItem>) :
        ArrayAdapter<ShortcutItem>(this@ShortcutActivity, R.layout.item_shortcut, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_shortcut, parent, false)

            val item = getItem(position)
            val titleView = view.findViewById<TextView>(R.id.key_title)
            val summaryView = view.findViewById<TextView>(R.id.action_summary)

            if (item != null) {
                titleView.text = item.title
                summaryView.text = item.summary

                if (item.isReserved) {
                    titleView.alpha = 0.5f
                    summaryView.alpha = 0.5f
                } else {
                    titleView.alpha = 1.0f
                    summaryView.alpha = 1.0f
                }
            }

            return view
        }
    }
}
