# Floating VoiceBubble

Android 13+ 向けのフローティング音声入力。**Notion仕様を唯一の製品要件**とし、通常入力では軽快な部分文字起こし、確定時には精度優先のfinal ASR＋最小限のLLM補正を組み合わせます。

## 現在の実装

- Accessibility overlay のマイクバブル。入力欄を跨いだ場合は安全にclipboard fallback。
- caller-owned 16 kHz mono PCM16 `AudioRecord`、ローカルendpointing、同一WAV/N-best/session trace。
- Android `SpeechRecognizer` の真のpartial result。
- **sherpa-onnx + Nemotron 3.5 Streaming** による真ストリーミング完全オフラインpartial ASR。
  - 80 / 160 / 560 / 1120 ms int8モデルに対応。
  - オフラインモードではcloud/OEM recognizerへ黙ってfallbackしない。
- **ReazonSpeech Zipformer** の別系統final-ASR。保存した同一WAVを再認識しRTFを記録。
- OpenAI-compatible / Anthropic Messages / Gemini `generateContent` BYOK補正。
- LiteRT-LM 0.14.0 + Gemma 4 E2B/E4B オンデバイス補正。GPU→CPU fallback。
- 補正は口調・語尾・一人称・方言等を保存するminimum-edit guard付き。
- Android Keystore AES/GCMでBYOK secretを保存。
- SQLite個人辞書。件数上限を設けず、term/reading/alias/weight/use-countを管理。
- 高度管理画面から辞書の検索・追加・更新・削除・TSV import/export。
- **公式モデルカタログ**からNemotron/ReazonSpeech/Gemmaを直接導入可能。
  - Gemma: 公式size + SHA-256完全一致必須。
  - ASR: HTTPS固定URL → 必要ファイルだけ安全展開 → 構造/サイズ検証 → SHA付きmanifest → crash-safe atomic promotion。
- 人間正解ラベルによるCER / punctuation-stripped CER / 条件付きWER。
- 同一WAVトーナメントで live / 導入済みNemotron全chunk / ReazonSpeech / 競合ASRを横並び比較。
- Gboard / Wispr Flow / Aqua Voiceの外部結果用TSV import/export。
- 一クリック自動診断。shareable reportからAPI key・録音・辞書本文を除外。

## 通常画面と高度管理

通常設定画面は日常入力に必要な設定へ限定します。右下の **「管理」** から高度設定・検証画面へ入り、以下を扱います。

1. 公式モデルの取得・検証・選択
2. 個人辞書CRUD / TSV
3. human ground-truth TSV
4. Gboard / Wispr Flow / Aqua Voice比較TSV
5. 同一WAV ASRトーナメント

## モデルの扱い

### Streaming partial ASR

Nemotron 3.5 Streamingは**本物のonline recognizer**として使用します。保存WAVを使うトーナメント時だけ、同じonline recognizerへWAVを20 msフレームでreplayします。これはモデル比較用であり、live streaming latencyとは呼びません。

### Final ASR

ReazonSpeech Zipformerはfinal-ASR候補です。VADやchunk再decodeを「真ストリーミングpartial」とは呼びません。

### Gemma

公式LiteRT Community artifactはexact size + SHA-256で識別します。upstream artifactが更新された場合は`GemmaModelVerifier`と`OfficialModelCatalog`を同時に更新し、既知の直前revisionは互換用に保持します。

## ビルド・検証

```bash
./scripts/verify-all.sh
```

Windows:

```powershell
./scripts/verify-all.ps1
```

CIは以下を実施します。

- lint
- JVM unit tests
- debug APK
- release/R8
- APK archive/native ABI checks
- 16 KiB alignment
- APK signature + manifest/minSdk/targetSdk/launcher assertions
- API 33 / API 36 emulator install + launch + Accessibility bind + instrumentation

配布APKの完全機能ABIは `arm64-v8a` と `x86_64` です。

## 何が実機待ちか

**コード・CI・自動化で閉じられる項目は実装対象です。** 残すのは実デバイス・実音声でしか観測できないものだけです。

- マイク/HAL/OEM差
- overlayの手触り・focus遷移・各種third-party input field/WebView/Compose editor
- 実際の日本語音声でNemotron chunk/final-ASRのCER勝者決定
- Gboard / Wispr Flow / Aqua Voiceへ同一音声を入れた実測結果
- Gemma E2B/E4Bの実機CPU/GPU latency / peak memory / thermal / battery
- 公式巨大モデルの実端末ネットワーク・ストレージ環境での取得確認

手順は [`docs/REAL_DEVICE_VALIDATION.md`](docs/REAL_DEVICE_VALIDATION.md) を参照してください。

## 引き継ぎ

- **製品仕様**: Notion（唯一の要件ソース）
- **実装状態・次の一手**: [`HANDOFF.md`](HANDOFF.md)
- **要件→実装→検証対応**: [`docs/REQUIREMENTS_MATRIX.md`](docs/REQUIREMENTS_MATRIX.md)
- **ベンチ規約**: [`docs/BENCHMARK_PROTOCOL.md`](docs/BENCHMARK_PROTOCOL.md)
- **実機検証**: [`docs/REAL_DEVICE_VALIDATION.md`](docs/REAL_DEVICE_VALIDATION.md)

PR #1は、実機データを捏造して完成扱いしないためDraftを維持します。
