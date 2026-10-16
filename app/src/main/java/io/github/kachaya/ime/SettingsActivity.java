package io.github.kachaya.ime;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import io.github.kachaya.ime.keyboard.KeyConfig;
import io.github.kachaya.ime.keyboard.LayoutManager;
import io.github.kachaya.ime.keyboard.PhysicalKeyBinding;
import io.github.kachaya.ime.zenz.ZenzEngine;

/**
 * 日本語入力の動作設定やカスタマイズを行うための設定画面アクティビティです。
 * <p>
 * Android Jetpack の PreferenceFragmentCompat を使用し、標準的なマルチフラグメント遷移でカテゴリごとの設定画面を表示します。
 * </p>
 */
public class SettingsActivity extends AppCompatActivity implements
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    /** バックアップ設定を JSON ファイルとして出力するための ActivityResultLauncher。 */
    private final ActivityResultLauncher<String> mBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            this::backupSettings
    );

    /** JSON ファイルから設定を復元するための ActivityResultLauncher。 */
    private final ActivityResultLauncher<String[]> mRestoreLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::restoreSettings
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new MainSettingsFragment())
                    .commit();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.title_activity_settings);
        }

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(R.string.title_activity_settings);
                }
            }
        });
    }

    @Override
    public boolean onPreferenceStartFragment(@androidx.annotation.NonNull PreferenceFragmentCompat caller, @androidx.annotation.NonNull Preference pref) {
        final Bundle args = pref.getExtras();
        String fragmentClassName = pref.getFragment();
        if (fragmentClassName == null) {
            return false;
        }
        final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(
                getClassLoader(),
                fragmentClassName);
        fragment.setArguments(args);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, fragment)
                .addToBackStack(null)
                .commit();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(pref.getTitle());
        }
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return true;
        }
        finish();
        return true;
    }

    /** トップレベルのメイン設定画面フラグメントです。 */
    public static class MainSettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            InputService.setupDefaultPreferences(getContext());
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            Preference legalInfoPref = findPreference("legal_info");
            if (legalInfoPref != null) {
                legalInfoPref.setOnPreferenceClickListener(preference -> {
                    if (getActivity() instanceof SettingsActivity) {
                        ((SettingsActivity) getActivity()).showLegalInfoDialog();
                    }
                    return true;
                });
            }

            Preference versionPref = findPreference("app_version");
            if (versionPref != null) {
                versionPref.setSummary(BuildConfig.VERSION_NAME);
            }
        }
    }

    /** 1. 入力・変換エンジン設定フラグメント */
    public static class InputEngineFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_input_engine, rootKey);

            androidx.preference.ListPreference enginePref = findPreference(ZenzEngine.PREF_KEY_CONVERSION_ENGINE);
            if (enginePref != null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                if (!prefs.contains(ZenzEngine.PREF_KEY_CONVERSION_ENGINE)) {
                    boolean zenzEnabled = prefs.getBoolean(ZenzEngine.PREF_KEY_ZENZ_ENABLED, false);
                    enginePref.setValue(zenzEnabled ? ZenzEngine.ENGINE_ZENZ : ZenzEngine.ENGINE_VITERBI);
                }

                enginePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean isZenz = ZenzEngine.ENGINE_ZENZ.equals(newValue);
                    prefs.edit().putBoolean(ZenzEngine.PREF_KEY_ZENZ_ENABLED, isZenz).apply();
                    updateZenzPreferencesState(isZenz);
                    return true;
                });

                boolean isZenz = ZenzEngine.ENGINE_ZENZ.equals(enginePref.getValue());
                updateZenzPreferencesState(isZenz);
            }
        }

        private void updateZenzPreferencesState(boolean isZenz) {
            String[] keys = new String[] {
                    ZenzEngine.PREF_KEY_ZENZ_THREADS,
                    ZenzEngine.PREF_KEY_ZENZ_CTX_SIZE,
                    ZenzEngine.PREF_KEY_ZENZ_MAX_TOKENS,
                    ZenzEngine.PREF_KEY_ZENZ_MAX_CANDIDATES,
                    ZenzEngine.PREF_KEY_ZENZ_USE_CONTEXT,
                    ZenzEngine.PREF_KEY_ZENZ_TEMPERATURE,
                    ZenzEngine.PREF_KEY_ZENZ_TOP_P,
                    ZenzEngine.PREF_KEY_ZENZ_TOP_K
            };
            for (String key : keys) {
                Preference pref = findPreference(key);
                if (pref != null) {
                    pref.setEnabled(isZenz);
                }
            }
        }
    }

    /** 2. 画面キーボード設定フラグメント */
    public static class SoftKeyboardFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_soft_keyboard, rootKey);
        }
    }

    /** 3. 物理キーボード設定フラグメント */
    public static class PhysicalKeyboardFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_physical_keyboard, rootKey);

            setupKeyPref(PhysicalKeyBinding.KEY_TOGGLE_EN_JP, "日英切り替えキー");
            setupKeyPref(PhysicalKeyBinding.KEY_JAPANESE_MODE, "日本語モード変更キー");
            setupKeyPref(PhysicalKeyBinding.KEY_ENGLISH_MODE, "英語モード変更キー");

            Preference openSystemKeyboardPref = findPreference("open_system_hard_keyboard_settings");
            if (openSystemKeyboardPref != null) {
                openSystemKeyboardPref.setOnPreferenceClickListener(preference -> {
                    openSystemPhysicalKeyboardSettings();
                    return true;
                });
            }
        }

        private void openSystemPhysicalKeyboardSettings() {
            Context context = requireContext();
            Intent[] candidateIntents = new Intent[] {
                    new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS),
                    new Intent("android.settings.KEYBOARD_SETTINGS"),
                    new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                    new Intent("android.settings.INPUT_METHOD_AND_LANGUAGE_SETTINGS"),
                    new Intent(Settings.ACTION_SETTINGS)
            };

            boolean launched = false;
            for (Intent intent : candidateIntents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    launched = true;
                    break;
                } catch (Exception ignored) {
                }
            }

            if (!launched) {
                Toast.makeText(context, "システム設定画面を開けませんでした", Toast.LENGTH_SHORT).show();
            }
        }

        private void setupKeyPref(String prefKey, String title) {
            setupKeyPref(prefKey, PhysicalKeyBinding.getDefaultSerialized(prefKey), title);
        }

        private void setupKeyPref(String prefKey, String defaultSerialized, String title) {
            Preference pref = findPreference(prefKey);
            if (pref == null) {
                return;
            }

            Context context = requireContext();
            PhysicalKeyBinding binding = PhysicalKeyBinding.load(context, prefKey);
            pref.setSummary(binding.getDisplayLabel());

            pref.setOnPreferenceClickListener(p -> {
                showKeyCaptureDialog(pref, prefKey, defaultSerialized, title);
                return true;
            });
        }

        private void showKeyCaptureDialog(Preference pref, String prefKey, String defaultSerialized, String title) {
            Context context = requireContext();
            PhysicalKeyBinding currentBinding = PhysicalKeyBinding.load(context, prefKey);
            final PhysicalKeyBinding[] capturedBinding = new PhysicalKeyBinding[]{currentBinding};

            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_key_capture, null);
            TextView keyLabelText = dialogView.findViewById(R.id.text_captured_key);
            Button buttonClear = dialogView.findViewById(R.id.button_clear);
            Button buttonResetDefault = dialogView.findViewById(R.id.button_reset_default);

            keyLabelText.setText(currentBinding.getDisplayLabel());

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setPositiveButton("保存", (d, which) -> {
                        PhysicalKeyBinding.save(context, prefKey, capturedBinding[0]);
                        pref.setSummary(capturedBinding[0].getDisplayLabel());
                    })
                    .setNegativeButton("キャンセル", null)
                    .create();

            buttonClear.setOnClickListener(v -> {
                PhysicalKeyBinding emptyBinding = new PhysicalKeyBinding(0);
                capturedBinding[0] = emptyBinding;
                keyLabelText.setText(emptyBinding.getDisplayLabel());
            });

            buttonResetDefault.setOnClickListener(v -> {
                PhysicalKeyBinding defaultBinding = PhysicalKeyBinding.deserialize(defaultSerialized);
                capturedBinding[0] = defaultBinding;
                keyLabelText.setText(defaultBinding.getDisplayLabel());
            });

            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
                        dialog.dismiss();
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_FUNCTION || keyCode == 119
                            || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
                            || keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
                            || keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                        return true;
                    }

                    int meta = PhysicalKeyBinding.getNormalizedMetaState(event);

                    PhysicalKeyBinding binding = new PhysicalKeyBinding(keyCode, meta);
                    capturedBinding[0] = binding;
                    keyLabelText.setText(binding.getDisplayLabel());
                    return true;
                }
                return false;
            });

            dialog.show();
        }
    }

    /** 4. 表示・UI設定フラグメント */
    public static class DisplayUiFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_display_ui, rootKey);
        }
    }

    /** 5. 辞書・学習設定フラグメント */
    public static class DictionaryFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_dictionary, rootKey);
        }
    }

    /** 6. バックアップ・初期化フラグメント */
    public static class BackupResetFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_backup_reset, rootKey);

            Preference backupPref = findPreference("backup_settings");
            if (backupPref != null) {
                backupPref.setOnPreferenceClickListener(preference -> {
                    if (getActivity() instanceof SettingsActivity) {
                        ((SettingsActivity) getActivity()).mBackupLauncher.launch("ime_backup.json");
                    }
                    return true;
                });
            }

            Preference restorePref = findPreference("restore_settings");
            if (restorePref != null) {
                restorePref.setOnPreferenceClickListener(preference -> {
                    if (getActivity() instanceof SettingsActivity) {
                        ((SettingsActivity) getActivity()).mRestoreLauncher.launch(new String[]{"application/json", "text/plain"});
                    }
                    return true;
                });
            }

            Preference resetPref = findPreference("reset_settings");
            if (resetPref != null) {
                resetPref.setOnPreferenceClickListener(preference -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("設定の初期化")
                            .setMessage("すべての設定を初期状態に戻しますか？\n（学習辞書は初期化されません）")
                            .setPositiveButton("初期化", (dialog, which) -> {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                                prefs.edit().clear().apply();
                                LayoutManager.clearLayouts(requireContext());
                                InputService.setupDefaultPreferences(requireContext());
                                Toast.makeText(requireContext(), "設定を初期化しました", Toast.LENGTH_SHORT).show();
                                if (getActivity() != null) {
                                    getActivity().recreate();
                                }
                            })
                            .setNegativeButton("キャンセル", null)
                            .show();
                    return true;
                });
            }
        }
    }

    private void backupSettings(Uri uri) {
        if (uri == null) {
            return;
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            Map<String, ?> allEntries = prefs.getAll();
            TreeMap<String, Object> sortedMap = new TreeMap<>();

            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (key.endsWith("_normal") || key.endsWith("_shift") || key.endsWith("_symbol") ||
                        key.endsWith("_primary") || key.endsWith("_secondary")) {
                    continue;
                }
                sortedMap.put(key, value);
            }

            for (String key : LayoutManager.getManagedKeys()) {
                String layoutStr = LayoutManager.loadLayout(this, key, null);
                if (layoutStr != null) {
                    sortedMap.put(key, KeyConfig.layoutFromAnyString(layoutStr));
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            int count = 0;
            int total = sortedMap.size();
            for (Map.Entry<String, Object> entry : sortedMap.entrySet()) {
                sb.append("  \"").append(entry.getKey()).append("\": ");
                Object val = entry.getValue();
                if (val instanceof KeyConfig[][]) {
                    sb.append(KeyConfig.layoutToJsonString((KeyConfig[][]) val));
                } else if (val instanceof String) {
                    sb.append(JSONObject.quote((String) val));
                } else {
                    sb.append(val);
                }
                if (++count < total) sb.append(",");
                sb.append("\n");
            }
            sb.append("}");

            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "バックアップを保存しました", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "バックアップの保存に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreSettings(Uri uri) {
        if (uri == null) {
            return;
        }
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject json = new JSONObject(sb.toString());
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();

            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);

                if (value instanceof JSONArray) {
                    String jsonStr = value.toString();
                    LayoutManager.saveLayout(this, key, jsonStr);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                } else if (value instanceof String) {
                    editor.putString(key, (String) value);
                }
            }
            editor.putLong(LayoutManager.PREF_LAYOUT_UPDATED, System.currentTimeMillis());
            editor.apply();
            Toast.makeText(this, "設定を復元しました", Toast.LENGTH_SHORT).show();
            recreate();
        } catch (Exception e) {
            Toast.makeText(this, "復元に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showLegalInfoDialog() {
        try {
            android.webkit.WebView webView = new android.webkit.WebView(this);
            webView.loadUrl("file:///android_asset/legal_info.html");

            new AlertDialog.Builder(this)
                    .setTitle("法的情報・ライセンス")
                    .setView(webView)
                    .setPositiveButton("閉じる", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "ライセンス情報の表示に失敗しました", Toast.LENGTH_SHORT).show();
            Log.e("SettingsActivity", "Failed to load legal info HTML", e);
        }
    }

    /**
     * SharedPreferences の設定キーおよび関連定数を一括管理するインナークラスです。
     */
    public static final class PrefKeys {

        private PrefKeys() {
            // インスタンス化防止
        }

        // --- キーボード種別 ---
        public static final String KEYBOARD_TYPE_QWERTY = "qwerty";
        public static final String KEYBOARD_TYPE_STROKE = "stroke";
        public static final String KEYBOARD_TYPE_TABLET = "tablet";
        public static final String KEYBOARD_TYPE_SYMBOLS = "symbols";
        public static final String KEYBOARD_TYPE_NONE = "none";

        // --- SharedPreferences 設定キー ---
        public static final String KEYBOARD_TYPE = "keyboard_type";
        public static final String KEYBOARD_THEME = "keyboard_theme";
        public static final String AUTO_ASCII_MODE = "auto_ascii_mode";
        public static final String STARTUP_MODE = "startup_mode";
        public static final String SHOW_STATUS_BAR_ICON = "show_status_bar_icon";
        public static final String TOOLTIP_DURATION_SEC = "tooltip_duration_sec";
        public static final String TOOLTIP_POSITION = "tooltip_position";

        public static final String PHYSICAL_KEYBOARD_SHOW_SYMBOL_BAR = "physical_keyboard_show_symbol_bar";
        public static final String HAPTIC_FEEDBACK = "haptic_feedback";
        public static final String SHOW_ANNOTATION = "show_annotation";
        public static final String KEEP_CANDIDATE_BAR_VISIBLE = "keep_candidate_bar_visible";

        public static final String STROKE_ALIGN = "stroke_align";
        public static final String TABLET_ALIGN = "tablet_align";
        public static final String STROKE_WIDTH_SCALE = "stroke_width_scale";
        public static final String TABLET_WIDTH_SCALE = "tablet_width_scale";
        public static final String KEYBOARD_HEIGHT_SCALE = "keyboard_height_scale";
        public static final String CANDIDATE_HEIGHT_SCALE = "candidate_height_scale";
        public static final String CANDIDATE_FONT_SIZE = "candidate_font_size";
    }
}
