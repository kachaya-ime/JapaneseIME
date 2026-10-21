# JapaneseIME アーキテクチャおよび技術ガイド

本ドキュメントは、`JapaneseIME` の内部アーキテクチャ、変換エンジン（ConversionEngine）、状態管理（InputState）、UIブリッジ（InputEngineUiBridge）、学習機構、および実装上の技術的詳細を体系的にまとめた技術ドキュメントです。機能やモジュールを追加・変更する際のリファレンスとして記述されています。

---

## 1. 全体アーキテクチャとモジュール構成

本プロジェクトは 2 つのサブプロジェクトで構成されています：
* **`:app` (Android App)**: ソフトウェアキーボード UI (`InputView`, 各種キーボードビュー)、サービス (`InputService`)、入力・変換エンジン (`InputEngine`)、および辞書管理。
* **`:tool` (Non-Android / 辞書ビルダー)**: MeCab/IPADIC 互換のシステム辞書バイナリや接続コストマトリクスをビルド・最適化するためのツール群。

### 1.1 アーキテクチャとデザインパターン

```mermaid
graph TD
    A[InputService / InputView] -->|キー入力 / イベント| B[InputEngine]
    B -->|入力状態制御| C[InputState (Strategy)]
    B -->|入力モード制御| D[InputMode (Strategy)]
    B -->|非同期サジェスト検索| E[SuggestionProvider]
    B -->|プラグイン変換| F[ConversionEngine (Plugin)]
    F -->|実装: Viterbi| G[ViterbiConversionEngine]
    F -->|実装: Zenz AI| H[ZenzConversionEngine]
    B -->|確定時単語/N-gram学習| I[CandidateLearner]
    B -->|UI描画・テキスト送信| J[InputEngineUiBridge (Bridge)]
    J -->|反映| A
```

---

## 2. 拡張ポイントと変更手順

機能追加や改修を行う際の拡張ポイントと手順です。

### 2.1 変換エンジンの追加 (`ConversionEngine`)
新しい変換エンジン（例: 他の LLM、ルールベース変換、Gemini Nano 連携など）を追加する場合：
1. `ConversionEngine` インターフェースを実装したクラスを作成します。
   - `convertSync(query, preferredSurface)`: 同期検索候補を返します。
   - `convertAsync(query, leftContext, callback)`: 非同期/ストリーミング推論候補をコールバックで返します。
2. `InputEngine` のエンジンリスト（またはインスタンス生成部）に登録します。
3. `InputEngine` 本体のコードを変更せずに新しい変換エンジンを利用可能です（Open-Closed Principle）。

### 2.2 入力モードの追加 (`InputMode`)
新しい入力モード（例: フリック専用モード、直接英字モードなど）を追加する場合：
1. `InputMode` インターフェースを実装したクラス/enumを作成します。
2. モード固有の文字変換ロジック（`convertText`）やキー処理（`processKey`）を定義します。

### 2.3 ショートカットキー機能の追加 (`InputAction`)
新しい操作アクション（例: 音声入力起動、クリップボード履歴表示など）を追加する場合：
1. `InputAction` enum に新しいアクションを追加します。
2. `InputState`（`InputStateComposing`, `InputStateConverting`, `InputStateIdle`）の `processInputAction` 内でアクションの挙動を実装します。
3. `ShortcutActivity`（設定画面）に反映され、物理キーボードやカスタマイズキーに割り当て可能になります。

### 2.4 JVM 上での単体テスト (`InputEngineUiBridge`)
Android 実機やエミュレータを起動せずに、`InputEngine` のロジックを直接テストする場合：
1. `InputEngineUiBridge` インターフェースを模倣する Mock / Fake クラスを作成します。
2. `InputEngine(null, null, fakeBridge)` としてインスタンス化することで、`app/src/test/` 内で JUnit テストを実行できます。

---

## 3. 変換エンジンと学習システム

`JapaneseIME` は、統計的アプローチ（`ViterbiConversionEngine`）とオンデバイスAIアプローチ（`ZenzConversionEngine` / `llama.cpp`）を統一された `ConversionEngine` インターフェース経由で統合しています。

### 3.1 Zenz LLM 変換と Viterbi 学習のブリッジ
* **Zenz エンジン (`ZenzEngine` / `ZenzConversionEngine`)**:
  - `llama.cpp` の JNI (`libzenz_engine.so`) を経由し、オンデバイスで GGUF モデル（`zenz-v3.2-xsmall.gguf` 等）を実行。
  - ストリーミング推論により、UIスレッドをブロックせずに候補を順次反映。
* **N-best (10〜50) Viterbi アライメント学習 (`CandidateLearner`)**:
  - ユーザーが候補を確定した際、`CandidateLearner` が Viterbi の分割パス（単語ノード群）を解析。
  - 単語・分節単位で品詞ID・コストおよび Bigram 遷移（`learnTransition`）を `LearningDictionary` / `PredictionDictionary` に学習。

### 3.2 サジェスト候補および予測変換
* **非同期サジェストプロバイダ (`SuggestionProvider`)**:
  - タイピング中にバックグラウンドスレッドプールでシステム辞書・学習辞書から前方一致候補を抽出し、メインスレッドでマージ・反映します。
* **一元辞書マネージャー (`DictionaryManager`)**:
  - `SystemDictionary`, `LearningDictionary`, `PredictionDictionary` のライフサイクル・排他制御・リロード・エクスポート/インポートを一括管理します。

---

## 4. 実装上の技術的注意点（Unicode・サロゲートペア対応）

1. **文字数・インデックス操作**:
   - 本IMEでは、テキスト処理において **Unicode コードポイント単位**（`codePointAt` / `charCount` / `appendCodePoint`）を使用し、絵文字などのサロゲートペア分断を防いでいます。
2. **SQLite プレフィックス範囲検索 (`findKeys`)**:
   - 上限値の計算に `key + "\uFFFF"` を採用し、絵文字末尾でも検索可能です。
