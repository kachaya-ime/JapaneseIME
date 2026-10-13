package io.github.kachaya.ime.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * zenz 言語モデル向けの入出力フォーマット変換および候補抽出を担当するユーティリティクラスです。
 */
public class ZenzPromptFormatter {

    public enum PromptStyle {
        CHAT_ML,
        PLAIN
    }

    private PromptStyle mPromptStyle = PromptStyle.PLAIN;

    public ZenzPromptFormatter() {
        this(PromptStyle.PLAIN);
    }

    public ZenzPromptFormatter(PromptStyle style) {
        this.mPromptStyle = style != null ? style : PromptStyle.PLAIN;
    }

    public PromptStyle getPromptStyle() {
        return mPromptStyle;
    }

    public void setPromptStyle(PromptStyle style) {
        this.mPromptStyle = style != null ? style : PromptStyle.PLAIN;
    }

    /**
     * 入力された「読み（かな）」から zenz 変換用プロンプトを構築します。
     *
     * @param reading 変換対象の読み文字列
     * @return 構築されたプロンプト文字列
     */
    public String buildPrompt(String reading) {
        if (reading == null) {
            reading = "";
        }
        reading = reading.trim();
        return "かな: " + reading + "\n漢字: ";
    }

    /**
     * 確定済み文脈テキスト（歴史・直前の単語）から続きの言葉を予測するプロンプトを構築します。
     *
     * @param contextHistory 直前の確定文脈テキスト（例: "猫を"）
     * @return 構築された予測用プロンプト文字列
     */
    public String buildPredictivePrompt(String contextHistory) {
        if (contextHistory == null) {
            contextHistory = "";
        }
        contextHistory = contextHistory.trim();
        return "文脈: " + contextHistory + "\n続き: ";
    }

    /**
     * LLM の生の出力結果から変換結果文字列を抽出・クリーンアップします。
     *
     * @param rawOutput LLM の生成結果テキスト
     * @return クリーンアップされた変換結果
     */
    public String parseOutput(String rawOutput) {
        if (rawOutput == null) {
            return "";
        }

        String cleaned = rawOutput;

        // Strip Private Use Area unicode characters (e.g. \uE601 / )
        cleaned = cleaned.replaceAll("[\\uE000-\\uF8FF]", "");
        // Strip special LLM tokens and artifacts
        cleaned = cleaned.replaceAll("<\\|[^>]*\\|>", "")
                         .replaceAll("\\|[a-zA-Z0-9_]*\\|>", "")
                         .replace("</s>", "")
                         .replace("<|endoftext|>", "");

        // If system or prompt echo exists, clean up prefix
        int assistantIdx = cleaned.lastIndexOf("<|assistant|>\n");
        if (assistantIdx != -1) {
            cleaned = cleaned.substring(assistantIdx + "<|assistant|>\n".length());
        }

        int kanjiIdx = cleaned.lastIndexOf("漢字: ");
        if (kanjiIdx != -1) {
            cleaned = cleaned.substring(kanjiIdx + "漢字: ".length());
        }

        // Clean newlines or leading/trailing whitespace
        cleaned = cleaned.trim();
        int newlineIdx = cleaned.indexOf('\n');
        if (newlineIdx != -1) {
            cleaned = cleaned.substring(0, newlineIdx).trim();
        }

        return cleaned;
    }

    /**
     * 出力テキストを候補リストとして抽出します（スラッシュやカンマで複数候補が返された場合にも対応）。
     *
     * @param rawOutput LLM の生成結果テキスト
     * @return 変換候補文字列のリスト
     */
    public List<String> extractCandidates(String rawOutput) {
        List<String> candidates = new ArrayList<>();
        if (rawOutput == null || rawOutput.isEmpty()) {
            return candidates;
        }

        // Handle models that use Private Use Area delimiters like \uE600 () and \uE601 ()
        if (rawOutput.contains("\uE600") || rawOutput.contains("\uE601") || rawOutput.contains("") || rawOutput.contains("")) {
            String[] parts = rawOutput.split("[\uE000-\uF8FF]+");
            for (String part : parts) {
                String candidate = parseOutput(part);
                if (!candidate.isEmpty() && !candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        String parsed = parseOutput(rawOutput);
        if (!parsed.isEmpty()) {
            candidates.add(parsed);
        }
        return candidates;
    }
}
