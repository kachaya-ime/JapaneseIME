package io.github.kachaya.ime;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import io.github.kachaya.ime.dictionary.PredictionDictionary;

/**
 * 予測辞書（Bigram接続学習）の内容を管理・閲覧するためのアクティビティです。
 */
public class PredictionDictionaryTool extends AppCompatActivity {

    private PredictionDictionary mPredictionDictionary;
    private ArrayAdapter<String> mAdapter;
    private List<PredictionDictionary.PredictionEntry> mPredictionEntries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_tool);
        mPredictionDictionary = new PredictionDictionary(this);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("予測辞書ツール（接続学習）");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        ListView listView = findViewById(R.id.list_view);
        listView.setEmptyView(findViewById(R.id.empty_text));

        mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(mAdapter);

        refreshList();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (mPredictionEntries != null && position >= 0 && position < mPredictionEntries.size()) {
                PredictionDictionary.PredictionEntry entry = mPredictionEntries.get(position);
                showDeleteDialog(entry);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mPredictionDictionary != null) {
            mPredictionDictionary.close();
        }
        super.onDestroy();
    }

    private void refreshList() {
        mAdapter.clear();
        mPredictionEntries = mPredictionDictionary.getAllPredictions();
        List<String> displayStrings = new ArrayList<>();
        for (PredictionDictionary.PredictionEntry entry : mPredictionEntries) {
            displayStrings.add(entry.toString());
        }
        mAdapter.addAll(displayStrings);
        mAdapter.notifyDataSetChanged();
    }

    private void showDeleteDialog(PredictionDictionary.PredictionEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("予測接続の削除")
                .setMessage("「" + entry.prevWord + " ➔ " + entry.nextWord + "」の学習データを削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    mPredictionDictionary.deletePrediction(entry.prevWord, entry.nextWord);
                    Snackbar.make(findViewById(R.id.list_view), "接続データを削除しました", Snackbar.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
