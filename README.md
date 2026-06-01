# 録音サマリー (rokuonsumm)

24時間“声のライフログ”を録り、**端末内で**音声区間検出・話者識別・要約までこなす Android アプリ。
「音声ファイルマネージャ」ではなく「日々の発言を読み返す読み物」を目指している。

## 特徴

- **常時録音 + ギャップレス5分ロール** — 録りっぱなしでも5分ごとに自動でセグメント分割（`setMaxDuration`非依存の確実な seal→start 方式）。
- **端末内 VAD（Silero VAD v5 / ONNX）** — 人の声がある区間だけを文字起こしAPIに送る。無音・雑音は端末で破棄。
- **端末内 話者識別 + 自動クラスタリング（3D-Speaker CAM++ / ONNX）**
  - 登録した話者は声紋（192次元コサイン照合）でラベル付け。
  - 未登録の声は「人物1, 人物2…」へ自動クラスタリング。再登場した声だけ昇格させ、一見さん（TV・通行人）は「不明」据え置き。
  - 発話をタップ → 再生して確認 → 名前を付けると、同じ声紋の過去発話もまとめてリラベル（命名の磁石効果）。
- **文字起こし** — Groq Whisper（`whisper-large-v3-turbo`, temperature=0 + 固有名詞プロンプト）。
- **幻覚フィルタ** — Whisper が無音で吐く「ご視聴ありがとうございました」等を、本文を残したまま除去（scrub方式）。
- **要約** — 既定は「使わない（全文コピペ）」。使う場合は Groq / OpenAI / Anthropic / Gemini を選択可。用途別（要約 / スライド / 日記 / TODO抽出 / 議事録）にプロンプトを前置きしてコピーも可能。
- **全文検索（LIKE 部分一致）** — 日本語ログに強い。

## 技術スタック

- Kotlin / ViewBinding（Compose 不使用）
- Room（DB, KSP）/ WorkManager / DataStore / Coroutines
- ONNX Runtime Android（VAD・話者埋め込み推論）
- MediaRecorder（AAC/MPEG-4, 16kHz mono）+ MediaCodec デコード
- OkHttp（API呼び出し）

minSdk 29 / targetSdk 36 / JVM 17

## ビルド

1. Android Studio で開く（CLI gradle はセキュリティソフトの loopback ブロックで失敗する環境あり → GUI ビルド推奨）。
2. `app/src/main/assets/` に `silero_vad.onnx` と `campplus.onnx` が同梱されていること。
3. **Build → Generate App Bundles or APKs → Generate APKs**。
4. APIキー（Groq 等）はアプリ内「設定」で実行時に入力。**リポジトリにキーは含まれない。**

## プライバシー

- 音声処理（VAD・声紋）は端末内で完結。
- 文字起こし/要約に外部APIを使う場合のみ、当該区間の音声・テキストが送信される。
- APIキーは端末の DataStore に保存。ソースには含めない。
- 開発中に端末から吸い出したDB・ログ・スクショ（個人の発言を含む）は `.gitignore` で除外済み。
