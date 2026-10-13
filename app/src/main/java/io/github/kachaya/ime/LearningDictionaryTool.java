package io.github.kachaya.ime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import io.github.kachaya.ime.dictionary.Dictionary;
import io.github.kachaya.ime.dictionary.LearningDictionary;

/**
 * ユーザー辞書の内容を管理するための管理用アクティビティです。
 * <p>
 * 学習済みエントリの一覧表示、個別の削除、外部ストレージへのエクスポート、
 * およびテキストファイルからのインポート機能を提供します。
 * Android の Storage Access Framework (SAF) を介して、セキュアなファイル操作を行います。
 * </p>
 */
public class LearningDictionaryTool extends AppCompatActivity {

    /** 辞書マネージャ。BTree データベースへの実操作を委譲します。 */
    private Dictionary mDictionary;
    /** 辞書エクスポート用の保存先指定を処理する結果ランチャー。 */
    private final ActivityResultLauncher<Intent> mExportResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onExportActivityResult);
    /** ListView に辞書エントリ（4カラム項目）を表示するためのアダプター。 */
    private LearningDictionaryAdapter mAdapter;
    /** アクティビティのオプションメニューへの参照。リストの空き状況に応じた動的制御に使用します。 */
    private Menu mOptionsMenu;

    /**
     * 学習辞書項目のデータクラスです（読み、表記、品詞ID、品詞文字列）。
     */
    public static class LearningItem {
        public final String key;
        public final String surface;
        public final int posId;
        public final String posString;

        public LearningItem(String key, String surface, int posId, String posString) {
            this.key = key;
            this.surface = surface;
            this.posId = posId;
            this.posString = posString;
        }
    }

    /**
     * アクティビティ生成時のライフサイクルメソッドです。
     * UI コンポーネントの初期化、辞書データの読み込み、および削除用ダイアログの設定を行います。
     *
     * @param savedInstanceState 保存された状態
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_tool);
        mDictionary = new Dictionary(this);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("学習辞書ツール");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        ListView listView = findViewById(R.id.list_view);
        listView.setEmptyView(findViewById(R.id.empty_text));

        List<LearningItem> items = new ArrayList<>();
        mAdapter = new LearningDictionaryAdapter(items);
        listView.setAdapter(mAdapter);

        // 初回起動時のデータ同期
        refreshList();

        // 項目タップによる候補個別削除フローの設定
        listView.setOnItemClickListener((parent, view, position, id) -> {
            LearningItem item = mAdapter.getItem(position);
            if (item != null) {
                showCandidateSelectionDialog(item);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mDictionary != null) {
            mDictionary.close();
        }
        super.onDestroy();
    }

    /**
     * リスト表示を最新の状態に更新します。
     */
    private void refreshList() {
        List<LearningDictionary.Record> records = mDictionary.getAllLearningRecords();
        List<LearningItem> items = new ArrayList<>();
        for (LearningDictionary.Record r : records) {
            String posString = mDictionary.getPosString(r.posId);
            items.add(new LearningItem(r.key, r.surface, r.posId, posString));
        }
        mAdapter.clear();
        mAdapter.addAll(items);
        mAdapter.notifyDataSetChanged();
        invalidateOptionsMenu();
    }

    /**
     * 指定されたエントリを解析し、候補を個別に削除するためのダイアログを表示します。
     *
     * @param item 解析対象の学習辞書エントリ
     */
    private void showCandidateSelectionDialog(LearningItem item) {
        new AlertDialog.Builder(this)
                .setTitle("単語エントリの削除")
                .setMessage("「" + item.key + " ➔ " + item.surface + "」(品詞: " + item.posString + " [ID: " + item.posId + "]) を削除しますか？")
                .setPositiveButton("削除する", (dialog, which) -> {
                    View view = findViewById(R.id.list_view);
                    mDictionary.removeEntry(item.key, item.surface);
                    Snackbar.make(view, "「" + item.key + " : " + item.surface + "」を削除しました", Snackbar.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /**
     * メニューリソースをインフレートし、ActionBar へ配置します。
     *
     * @param menu メニューインスタンス
     * @return 常に true
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mOptionsMenu = menu;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.dictionary_tool_menu, menu);
        return true;
    }

    /**
     * メニューの表示直前に状態を更新します。
     * リストが空の場合は、エクスポートや全件削除を選択できないように無効化します。
     *
     * @param menu メニューインスタンス
     * @return 常に true
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mOptionsMenu != null) {
            boolean hasItems = mAdapter.getCount() > 0;

            MenuItem exportItem = mOptionsMenu.findItem(R.id.menu_export_user);
            MenuItem clearItem = mOptionsMenu.findItem(R.id.menu_clear_user);

            if (exportItem != null) {
                exportItem.setEnabled(hasItems);
            }
            if (clearItem != null) {
                clearItem.setEnabled(hasItems);
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * オプションメニューの選択イベントを処理します。
     *
     * @param item 選択された項目
     * @return 処理を消費した場合は true
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.menu_export_user) {
            onClickExportDictionary();
            return true;
        } else if (itemId == R.id.menu_clear_user) {
            onClickClearUserDictionary();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 外部ファイル用の推奨ファイル名を取得します。
     *
     * @return デフォルトファイル名
     */
    public String getDefaultFileName() {
        return mDictionary.getLearningDictionaryName() + ".txt";
    }

    /**
     * 現在の学習辞書の内容を文字列リストとして取得します。
     *
     * @return 辞書データ全件のリスト
     */
    public List<String> exportDictionary() {
        return mDictionary.exportLearningDictionary();
    }

    /**
     * エクスポート処理（保存先ファイル作成ピッカー）を起動します。
     */
    private void onClickExportDictionary() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, getDefaultFileName());
        mExportResultLauncher.launch(intent);
    }

    /**
     * エクスポートピッカーの結果を受け取り、辞書データを指定された URI へ書き出します。
     *
     * @param result ピッカーの戻り値
     */
    private void onExportActivityResult(ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent resultData = result.getData();
            if (resultData != null) {
                Uri uri = resultData.getData();
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (outputStream != null) {
                        try (BufferedWriter writer = new BufferedWriter((new OutputStreamWriter(outputStream)))) {
                            for (String entry : exportDictionary()) {
                                writer.write(entry + "\n");
                            }
                            writer.flush();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 学習辞書の全件削除（初期化）を実行します。
     * 実行前にユーザーへ警告ダイアログを表示します。
     */
    private void onClickClearUserDictionary() {
        new AlertDialog.Builder(this)
                .setTitle("学習辞書のクリア")
                .setMessage("学習辞書のすべての単語を削除しますか？\n（インポートした追加辞書は保持されます）")
                .setPositiveButton("はい、削除します", (dialog, which) -> {
                    mDictionary.clearLearningDictionary();
                    Snackbar.make(findViewById(R.id.list_view), "学習辞書をクリアしました", Snackbar.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("いいえ", null)
                .show();
    }

    /**
     * 学習辞書項目を 3 カラム構成の ListView に表示するためのカスタムアダプターです。
     */
    private class LearningDictionaryAdapter extends ArrayAdapter<LearningItem> {

        LearningDictionaryAdapter(List<LearningItem> items) {
            super(LearningDictionaryTool.this, R.layout.item_learning_dictionary, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_learning_dictionary, parent, false);
            }

            LearningItem item = getItem(position);
            TextView readingView = view.findViewById(R.id.text_reading);
            TextView surfaceView = view.findViewById(R.id.text_surface);
            TextView posView = view.findViewById(R.id.text_pos);

            if (item != null) {
                readingView.setText(item.key);
                surfaceView.setText(item.surface);
                posView.setText(item.posString);
            }

            return view;
        }
    }

}
