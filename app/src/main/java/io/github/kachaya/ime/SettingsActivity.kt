package io.github.kachaya.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import io.github.kachaya.ime.keyboard.KeyConfig
import io.github.kachaya.ime.keyboard.LayoutManager

import io.github.kachaya.ime.zenz.ZenzEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/**
 * 日本語入力の動作設定やカスタマイズを行うための設定画面アクティビティです。
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    val mBackupLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> backupSettings(uri) }

    val mRestoreLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> restoreSettings(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, MainSettingsFragment())
                .commit()
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_activity_settings)
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                supportActionBar?.setTitle(R.string.title_activity_settings)
            }
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val fragmentClassName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            fragmentClassName
        )
        fragment.arguments = args

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()

        supportActionBar?.title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }

    class MainSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            InputService.setupDefaultPreferences(requireContext())
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            val legalInfoPref = findPreference<Preference>("legal_info")
            legalInfoPref?.setOnPreferenceClickListener {
                val act = activity
                if (act is SettingsActivity) {
                    act.showLegalInfoDialog()
                }
                true
            }

            val versionPref = findPreference<Preference>("app_version")
            versionPref?.summary = BuildConfig.VERSION_NAME
        }
    }

    class InputEngineFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_input_engine, rootKey)

            val zenzSwitch = findPreference<TwoStatePreference>(ZenzEngine.PREF_KEY_ZENZ_ENABLED)
            if (zenzSwitch != null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                if (prefs.contains(ZenzEngine.PREF_KEY_CONVERSION_ENGINE) && !prefs.contains(ZenzEngine.PREF_KEY_ZENZ_ENABLED)) {
                    val engineVal = prefs.getString(ZenzEngine.PREF_KEY_CONVERSION_ENGINE, ZenzEngine.ENGINE_ZENZ)
                    zenzSwitch.isChecked = (ZenzEngine.ENGINE_ZENZ == engineVal)
                }

                zenzSwitch.setOnPreferenceChangeListener { _, newValue ->
                    val isChecked = newValue as Boolean
                    prefs.edit()
                        .putBoolean(ZenzEngine.PREF_KEY_ZENZ_ENABLED, isChecked)
                        .putString(ZenzEngine.PREF_KEY_CONVERSION_ENGINE, if (isChecked) ZenzEngine.ENGINE_ZENZ else ZenzEngine.ENGINE_VITERBI)
                        .apply()
                    updateZenzPreferencesState(isChecked)
                    true
                }

                updateZenzPreferencesState(zenzSwitch.isChecked)
            }

            val threadPref = findPreference<ListPreference>(ZenzEngine.PREF_KEY_ZENZ_THREADS)
            if (threadPref != null) {
                threadPref.summaryProvider = null
                val availableCores = Runtime.getRuntime().availableProcessors()
                fun updateThreadSummary(value: String?) {
                    val threads = value?.toIntOrNull() ?: 4
                    val actualThreads = threads.coerceIn(1, maxOf(1, availableCores))
                    threadPref.summary = "検出コア数: ${availableCores}コア / 使用スレッド数: ${actualThreads}スレッド"
                }
                updateThreadSummary(threadPref.value)
                threadPref.setOnPreferenceChangeListener { _, newValue ->
                    updateThreadSummary(newValue as? String)
                    true
                }
            }
        }

        private fun updateZenzPreferencesState(isZenz: Boolean) {
            val keys = arrayOf(
                ZenzEngine.PREF_KEY_ZENZ_THREADS,
                ZenzEngine.PREF_KEY_ZENZ_CTX_SIZE,
                ZenzEngine.PREF_KEY_ZENZ_MAX_TOKENS,
                ZenzEngine.PREF_KEY_ZENZ_MAX_CANDIDATES,
                ZenzEngine.PREF_KEY_ZENZ_USE_CONTEXT,
                ZenzEngine.PREF_KEY_ZENZ_TEMPERATURE,
                ZenzEngine.PREF_KEY_ZENZ_TOP_P,
                ZenzEngine.PREF_KEY_ZENZ_TOP_K
            )
            for (key in keys) {
                val pref = findPreference<Preference>(key)
                pref?.isEnabled = isZenz
            }
        }
    }

    class KeyboardMainFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_main, rootKey)
        }
    }

    class KeyboardQwertyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_qwerty, rootKey)
        }
    }

    class KeyboardTabletFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_tablet, rootKey)
        }
    }

    class KeyboardStrokeFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_stroke, rootKey)
        }
    }

    class KeyboardSymbolsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_symbols, rootKey)
        }
    }

    class KeyboardNoneFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_keyboard_none, rootKey)
        }
    }

    class DisplayUiFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_display_ui, rootKey)
        }
    }

    class DictionaryFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_dictionary, rootKey)
        }
    }

    class BackupResetFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_backup_reset, rootKey)

            val backupPref = findPreference<Preference>("backup_settings")
            backupPref?.setOnPreferenceClickListener {
                val act = activity
                if (act is SettingsActivity) {
                    act.mBackupLauncher.launch("ime_backup.json")
                }
                true
            }

            val restorePref = findPreference<Preference>("restore_settings")
            restorePref?.setOnPreferenceClickListener {
                val act = activity
                if (act is SettingsActivity) {
                    act.mRestoreLauncher.launch(arrayOf("application/json", "text/plain"))
                }
                true
            }

            val resetPref = findPreference<Preference>("reset_settings")
            resetPref?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("設定の初期化")
                    .setMessage("すべての設定を初期状態に戻しますか？\n（学習辞書は初期化されません）")
                    .setPositiveButton("初期化") { _, _ ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        prefs.edit().clear().apply()
                        LayoutManager.clearLayouts(requireContext())
                        InputService.setupDefaultPreferences(requireContext())
                        Toast.makeText(requireContext(), "設定を初期化しました", Toast.LENGTH_SHORT).show()
                        activity?.recreate()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                true
            }
        }
    }

    private fun backupSettings(uri: Uri?) {
        if (uri == null) {
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt").use { os ->
                if (os == null) return
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val allEntries = prefs.all
                val sortedMap = TreeMap<String, Any?>()

                for ((key, value) in allEntries) {
                    if (key.endsWith("_normal") || key.endsWith("_shift") || key.endsWith("_symbol") ||
                        key.endsWith("_primary") || key.endsWith("_secondary")
                    ) {
                        continue
                    }
                    sortedMap[key] = value
                }

                for (key in LayoutManager.getManagedKeys()) {
                    val layoutStr = LayoutManager.loadLayout(this, key, "")
                    if (layoutStr.isNotEmpty()) {
                        sortedMap[key] = KeyConfig.layoutFromAnyString(layoutStr)
                    }
                }

                val sb = StringBuilder()
                sb.append("{\n")
                var count = 0
                val total = sortedMap.size
                for ((key, value) in sortedMap) {
                    sb.append("  \"").append(key).append("\": ")
                    if (value is Array<*> && value.isArrayOf<Array<KeyConfig>>()) {
                        @Suppress("UNCHECKED_CAST")
                        sb.append(KeyConfig.layoutToJsonString(value as Array<Array<KeyConfig>>))
                    } else if (value is String) {
                        sb.append(JSONObject.quote(value))
                    } else {
                        sb.append(value)
                    }
                    if (++count < total) sb.append(",")
                    sb.append("\n")
                }
                sb.append("}")

                os.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                Toast.makeText(this, "バックアップを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "バックアップの保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSettings(uri: Uri?) {
        if (uri == null) {
            return
        }
        try {
            contentResolver.openInputStream(uri).use { isStream ->
                if (isStream == null) return
                BufferedReader(InputStreamReader(isStream, StandardCharsets.UTF_8)).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }

                    val json = JSONObject(sb.toString())
                    val editor = PreferenceManager.getDefaultSharedPreferences(this).edit()

                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.get(key)

                        if (value is JSONArray) {
                            val jsonStr = value.toString()
                            LayoutManager.saveLayout(this, key, jsonStr)
                        } else if (value is Boolean) {
                            editor.putBoolean(key, value)
                        } else if (value is Int) {
                            editor.putInt(key, value)
                        } else if (value is Long) {
                            editor.putLong(key, value)
                        } else if (value is Double) {
                            editor.putFloat(key, value.toFloat())
                        } else if (value is Float) {
                            editor.putFloat(key, value)
                        } else if (value is String) {
                            editor.putString(key, value)
                        }
                    }
                    editor.putLong(LayoutManager.PREF_LAYOUT_UPDATED, System.currentTimeMillis())
                    editor.apply()
                    Toast.makeText(this, "設定を復元しました", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "復元に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun showLegalInfoDialog() {
        try {
            val webView = WebView(this)
            webView.loadUrl("file:///android_asset/legal_info.html")

            AlertDialog.Builder(this)
                .setTitle("法的情報・ライセンス")
                .setView(webView)
                .setPositiveButton("閉じる", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "ライセンス情報の表示に失敗しました", Toast.LENGTH_SHORT).show()
            Log.e("SettingsActivity", "Failed to load legal info HTML", e)
        }
    }

    object PrefKeys {
        const val KEYBOARD_TYPE_QWERTY = "qwerty"
        const val KEYBOARD_TYPE_STROKE = "stroke"
        const val KEYBOARD_TYPE_TABLET = "tablet"
        const val KEYBOARD_TYPE_SYMBOLS = "symbols"
        const val KEYBOARD_TYPE_NONE = "none"

        const val KEYBOARD_TYPE = "keyboard_type"
        const val KEYBOARD_THEME = "keyboard_theme"
        const val AUTO_ASCII_MODE = "auto_ascii_mode"
        const val STARTUP_MODE = "startup_mode"
        const val SHOW_STATUS_BAR_ICON = "show_status_bar_icon"
        const val TOOLTIP_DURATION_SEC = "tooltip_duration_sec"
        const val TOOLTIP_POSITION = "tooltip_position"

        const val HAPTIC_FEEDBACK = "haptic_feedback"

        const val STROKE_ALIGN = "stroke_align"
        const val TABLET_ALIGN = "tablet_align"
        const val STROKE_WIDTH_SCALE = "stroke_width_scale"
        const val TABLET_WIDTH_SCALE = "tablet_width_scale"
        const val KEYBOARD_HEIGHT_SCALE = "keyboard_height_scale"
        const val CANDIDATE_HEIGHT_SCALE = "candidate_height_scale"
        const val CANDIDATE_FONT_SIZE = "candidate_font_size"
    }
}
