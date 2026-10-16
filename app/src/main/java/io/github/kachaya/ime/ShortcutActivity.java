package io.github.kachaya.ime;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.kachaya.ime.engine.InputAction;
import io.github.kachaya.ime.engine.KeyShortcutManager;

/**
 * キーショートカットの設定・カスタマイズを行うアクティビティです。
 */
public class ShortcutActivity extends AppCompatActivity {

    /** カスタマイズ可能なアクションのリスト。 */
    private static final InputAction[] CUSTOMIZABLE_ACTIONS = InputAction.values();

    private ListView mListView;
    private ShortcutAdapter mAdapter;
    private Map<Integer, InputAction> mMappings;

    /**
     * リストビューに表示するショートカット設定の 1 項目を表すデータクラスです。
     */
    private static class ShortcutItem {
        /** キーコード。 */
        final int keyCode;
        /** 表示用タイトル（例: "Ctrl + A"）。 */
        final String title;
        /** OSシステム予約キーかどうか。 */
        final boolean isReserved;
        /** 設定中の機能または予約機能の説明ラベル。 */
        final String summary;

        ShortcutItem(int keyCode, String title, boolean isReserved, String summary) {
            this.keyCode = keyCode;
            this.title = title;
            this.isReserved = isReserved;
            this.summary = summary;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shortcut);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("ショートカットキーの設定");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mListView = findViewById(R.id.list_view);

        loadData();

        mListView.setOnItemClickListener((parent, view, position, id) -> {
            ShortcutItem item = mAdapter.getItem(position);
            if (item == null) {
                return;
            }
            if (item.isReserved) {
                Toast.makeText(this, item.title + " は予約済みのため変更できません", Toast.LENGTH_SHORT).show();
            } else {
                showActionChoiceDialog(item);
            }
        });
    }

    /**
     * SharedPreferences から設定を読み込み、リスト表示用のデータを構築します。
     */
    private void loadData() {
        mMappings = KeyShortcutManager.loadMappings(this);
        List<ShortcutItem> items = new ArrayList<>();

        // 特殊キー: Ctrl + Space, Ctrl + Enter, Ctrl + Tab, Ctrl + Backspace
        int[] specialKeys = new int[]{
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_DEL
        };
        String[] specialTitles = new String[]{
                "Ctrl + Space",
                "Ctrl + Enter",
                "Ctrl + Tab",
                "Ctrl + Backspace"
        };

        for (int i = 0; i < specialKeys.length; i++) {
            int key = specialKeys[i];
            boolean isReserved = KeyShortcutManager.isReserved(key);
            String summary;
            if (isReserved) {
                summary = KeyShortcutManager.getReservedLabel(key);
            } else {
                InputAction action = mMappings.get(key);
                if (action == null) {
                    action = InputAction.NONE;
                }
                summary = action.getLabel();
            }
            items.add(new ShortcutItem(key, specialTitles[i], isReserved, summary));
        }

        // Shift 特殊キー: Shift + Space, Shift + Enter, Shift + Tab, Shift + Backspace
        int[] shiftSpecialKeys = new int[]{
                KeyEvent.KEYCODE_SPACE | KeyShortcutManager.SHIFT_MASK,
                KeyEvent.KEYCODE_ENTER | KeyShortcutManager.SHIFT_MASK,
                KeyEvent.KEYCODE_TAB | KeyShortcutManager.SHIFT_MASK,
                KeyEvent.KEYCODE_DEL | KeyShortcutManager.SHIFT_MASK
        };
        String[] shiftSpecialTitles = new String[]{
                "Shift + Space",
                "Shift + Enter",
                "Shift + Tab",
                "Shift + Backspace"
        };

        for (int i = 0; i < shiftSpecialKeys.length; i++) {
            int key = shiftSpecialKeys[i];
            InputAction action = mMappings.get(key);
            if (action == null) {
                action = InputAction.NONE;
            }
            items.add(new ShortcutItem(key, shiftSpecialTitles[i], false, action.getLabel()));
        }

        for (int keyCode = KeyEvent.KEYCODE_A; keyCode <= KeyEvent.KEYCODE_Z; keyCode++) {
            char letter = (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
            String title = "Ctrl + " + letter;
            boolean isReserved = KeyShortcutManager.isReserved(keyCode);
            String summary;
            if (isReserved) {
                summary = KeyShortcutManager.getReservedLabel(keyCode);
            } else {
                InputAction action = mMappings.get(keyCode);
                if (action == null) {
                    action = InputAction.NONE;
                }
                summary = action.getLabel();
            }
            items.add(new ShortcutItem(keyCode, title, isReserved, summary));
        }

        mAdapter = new ShortcutAdapter(items);
        mListView.setAdapter(mAdapter);
    }

    /**
     * ショートカットキーへの割り当てアクションを選択するダイアログを表示します。
     *
     * @param item 変更対象の ShortcutItem
     */
    private void showActionChoiceDialog(ShortcutItem item) {
        String[] labels = new String[CUSTOMIZABLE_ACTIONS.length];
        int currentIndex = 0;
        InputAction currentAction = mMappings.get(item.keyCode);
        if (currentAction == null) {
            currentAction = InputAction.NONE;
        }

        for (int i = 0; i < CUSTOMIZABLE_ACTIONS.length; i++) {
            labels[i] = CUSTOMIZABLE_ACTIONS[i].getLabel();
            if (CUSTOMIZABLE_ACTIONS[i] == currentAction) {
                currentIndex = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(item.title + " の機能設定")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> {
                    InputAction selectedAction = CUSTOMIZABLE_ACTIONS[which];
                    mMappings.put(item.keyCode, selectedAction);
                    KeyShortcutManager.saveMappings(this, mMappings);
                    loadData();
                    dialog.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.shortcut_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.menu_reset_defaults) {
            new AlertDialog.Builder(this)
                    .setTitle("初期値に戻す")
                    .setMessage("すべてのキーショートカットの設定を初期状態に戻しますか？")
                    .setPositiveButton("リセット", (dialog, which) -> {
                        KeyShortcutManager.resetToDefaults(this);
                        loadData();
                        Toast.makeText(this, "設定を初期状態に戻しました", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * ショートカットキー項目を ListView に表示するためのカスタムアダプターです。
     */
    private class ShortcutAdapter extends ArrayAdapter<ShortcutItem> {

        ShortcutAdapter(List<ShortcutItem> items) {
            super(ShortcutActivity.this, R.layout.item_shortcut, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_shortcut, parent, false);
            }

            ShortcutItem item = getItem(position);
            TextView titleView = view.findViewById(R.id.key_title);
            TextView summaryView = view.findViewById(R.id.action_summary);

            if (item != null) {
                titleView.setText(item.title);
                summaryView.setText(item.summary);

                if (item.isReserved) {
                    titleView.setAlpha(0.5f);
                    summaryView.setAlpha(0.5f);
                } else {
                    titleView.setAlpha(1.0f);
                    summaryView.setAlpha(1.0f);
                }
            }

            return view;
        }
    }
}
