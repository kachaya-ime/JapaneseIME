# Japanese IME

伝統的なかな漢字変換の操作体系を Android 上で再現した普通の日本語入力メソッドです。

Andorid SKK for Physical Keyboardの変換エンジン部分をViterbiアルゴリズム、連接コスト、N-Bestなどを使ったエンジンに置き換えています。

## 辞書データについて

本アプリの組み込み辞書は、MeCab IPADic 等から生成された語句で構築されています。

## ライセンス等

### 本ソフトウェアについて

本ソフトウェアのソースコードは Apache License, Version 2.0 に基づいて公開されています。

```
Copyright (c) 2026 kachaya

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### llama.cpp

オンデバイス LLM 推論エンジンとして llama.cpp (MIT License) を使用しています。
https://github.com/ggerganov/llama.cpp

### Zenz / ensan-hcl 氏

かな漢字変換 LLM 推論エンジンおよび Zenz モデル (`zenz-v3.2-xsmall.gguf`) として ensan-hcl 氏の成果を使用しています。モデルと各種実装の公開に深く感謝いたします。
- https://github.com/ensan-hcl/llama.cpp
- https://huggingface.co/ensan-hcl/zenz-v3.2-xsmall-gguf

### Jinen / togatogah 氏

かな漢字変換 LLM 推論エンジンおよび Jinen モデル (`jinen-v2-xsmall-Q5_K_M.gguf`) として togatogah 氏の成果を使用しています。モデルと各種実装の公開に深く感謝いたします。
- https://huggingface.co/togatogah/jinen-v2-xsmall.gguf

### SQLite

本ソフトウェアは、辞書エンジンのバックエンドとして SQLite を使用しています。
https://www.sqlite.org/

### sqlite-jdbc (ビルドツール用)

辞書データの構築ツールにおいて org.xerial:sqlite-jdbc (Apache License 2.0) を使用しています。
https://github.com/xerial/sqlite-jdbc

### MeCab IPADic

本ソフトウェアのシステム辞書の生成には mecab-ipadic 辞書データを使用しています。

### 商標・権利関係について

- 「Palm」「Palm V」「Graffiti® 」および「Graffiti® for Android™」は、該当する権利者（株式会社ACCESS等）の商標または登録商標です。
- 本プロジェクトは個人開発による独立したオープンソース実装であり、権利者各社とは組織的・商業的な関係を一切有しません。

## 免責事項

- **無保証**: 本ソフトウェアは「現状のまま」提供され、明示的・暗示的を問わず、いかなる種類の保証（商品性、特定の目的への適合性、および権利の非侵害に関する保証を含むがこれらに限定されない）もおこないません。
- **責任の限定**: 本ソフトウェアの使用または使用不能から生じるいかなる損害（入力データの損失、業務の中断、デバイスの故障など）について、作者および著作権者は一切の責任を負いません。
- **自己責任**: 本ソフトウェアのインストールおよび利用は、すべて利用者の自己責任においておこなってください。
- **開発状況**: 本プロジェクトは個人による開発途上のプログラムであり、予期せぬ動作や不具合が含まれる可能性があります。
- **プライバシーについて**: 本 IME は、ユーザーが入力した情報を開発者が意図的に外部へ送信する機能は備えていません。しかし、オープンソースソフトウェアとして公開されているため、利用者は各自の責任においてソースコードを確認し、安全性を判断してください。
