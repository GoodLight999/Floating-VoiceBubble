# Floating VoiceBubble

Android 13+ 向けのフローティング音声入力。**Notion仕様を唯一の製品要件**とし、通常入力では軽快な部分文字起こし、確定時には精度優先のfinal ASR＋必要な方向だけを選べるLLM補正を組み合わせます。

## 現在の実装

- Accessibility overlay のマイクバブル。
  - **実際のソフトウェアキーボードが表示されている間だけ**出現。
  - 明示的な「キャンセル」で現在の音声/未投入結果を破棄。
  - 画面下端へドラッグするとdismiss target＋触覚/縮小エフェクトを出し、ドロップで閉じる。
  - 入力欄を跨いだ場合は安全にclipboard fallback。
- caller-owned 16 kHz mono PCM16 `AudioRecord`、ローカルendpointing、同一WAV/N-best/session trace。
- Android 13+ `SpeechRecognizer` のcaller-audio **segmented session**＋`onSegmentResults`。
  - OEMがsegmented modeを無視して途中final/errorを返しても、区間を蓄積して認識だけ再開。
  - 区間境界の重複語は`TranscriptAccumulator`で併合。
  - 長文は自然な間で切れにくいよう、自動終端を通常1.4秒／長文2.2秒の無音へ適応。
- Android `SpeechRecognizer` の真のpartial result。
- **sherpa-onnx + Nemotron 3.5 Streaming** による真ストリーミング完全オフラインpartial ASR。
  - 80 / 160 / 560 / 1120 ms int8モデルに対応。
  - オフラインモードではcloud/OEM recognizerへ黙ってfallbackしない。
- **ReazonSpeech Zipformer** の別系統final-ASR。完成済みの同一WAVだけを再認識しRTFを記録。
- 録音とLLM確定処理を分離。前の発話を補正中でも次の録音を開始でき、古い確定表示が新しいpartialを上書きしない。
- OpenAI-compatible / Anthropic Messages / Gemini `generateContent` BYOK補正。
  - API URLを各providerのベースURLまで入力すれば生成endpointへ正規化。
  - API URL＋keyからモデル一覧を自動取得。Anthropic/Geminiのページネーションも追跡。
  - 実際の補正リクエストを送る接続テストを用意し、失敗をraw fallbackだけで隠さない。
- 最終補正の方向をチェックだけで指定可能。
  - `、`を付ける
  - `。`を付ける
  - フィラー除去
  - 丁寧語
  - ビジネス敬語
  - 未選択の句読点/フィラー除去はモデル後段でも決定論的に拒否。
  - 語調変更は明示選択時だけ広い編集予算を許し、通常時はminimum-edit guardを維持。
- LiteRT-LM 0.14.0 + Gemma 4 E2B/E4B オンデバイス補正。GPU→CPU fallback。
  - E2B/E4Bは補正設定画面からワンタップ自動導入。
  - 公式size + SHA-256完全一致必須。
- Android Keystore AES/GCMでBYOK secretを保存。
- SQLite個人辞書。件数上限を設けず、term/reading/alias/weight/use-countを管理。
- 高度管理画面から辞書の検索・追加・更新・削除・TSV import/export。
- **公式モデルカタログ**からNemotron/ReazonSpeech/Gemmaを直接導入可能。
  - ASR: HTTPS固定URL → 必要ファイルだけ安全展開 → 構造/サイズ検証 → SHA付きmanifest → crash-safe atomic promotion。
- 人間正解ラベルによるCER / punctuation-stripped CER / 条件付きWER。
- 同一WAVトーナメントで live / 導入済みNemotron全chunk / ReazonSpeech / 競合ASRを横並び比較。
- Gboard / Wispr Flow / Aqua Voiceの外部結果用TSV import/export。
- 一クリック自動診断。shareable reportからAPI key・録音・辞書本文を除外。
- `Floating VoiceBubble` の明示的なlauncher labelとadaptive/round/monochrome icon。
- 通常画面と補正設定画面は`WindowInsets.safeDrawing`＋明暗テーマに対応。

## 通常画面と高度管理

通常画面の最上段には、日常的に切り替える補正方向だけを置きます。**「API / Gemma」**からBYOK接続・モデル選択・Gemma自動導入へ進み、右下の **「管理」** から研究/検証系の高度設定へ入ります。

高度管理では以下を扱います。

1. 公式モデルの取得・検証・選択
2. 個人辞書CRUD / TSV
3. human ground-truth TSV
4. Gboard / Wispr Flow / Aqua Voice比較TSV
5. 同一WAV ASRトーナメント
6. 一クリック自動診断

## モデルの扱い

### Streaming partial ASR

Nemotron 3.5 Streamingは**本物のonline recognizer**として使用します。保存WAVを使うトーナメント時だけ、同じonline recognizerへWAVを20 msフレームでreplayします。これはモデル比較用であり、live streaming latencyとは呼びません。

### Final ASR

ReazonSpeech Zipformerはfinal-ASR候補です。VADやchunk再decodeを「真ストリーミングpartial」とは呼びません。

### Gemma

公式LiteRT Community artifactはexact size + SHA-256で識別します。upstream artifactが更新された場合は`GemmaModelVerifier`と`OfficialModelCatalog`を同時に更新し、既知の直前revisionは互換用に保持します。

## APK署名

公開リポジトリへprivate signing keyは置きません。固定署名APKを作る場合は、同一JKSをGitHub Actions Secretsへ一度だけ登録します。

- `FVB_SIGNING_KEYSTORE_B64`
- `FVB_SIGNING_STORE_PASSWORD`
- `FVB_SIGNING_KEY_ALIAS`
- `FVB_SIGNING_KEY_PASSWORD`
- 任意: `FVB_SIGNING_CERT_SHA256`

設定済みならdebug/release APKは同じ固定署名を使用し、CIがcertificate SHA-256まで検査します。未設定のCI APKはビルド継続用のrunner-local debug署名であり、固定更新IDとは扱いません。詳細は [`docs/SIGNING.md`](docs/SIGNING.md)。

## ビルド・検証

```bash
./scripts/verify-all.sh
```

Windows:

```powershell
./scripts/verify-all.ps1
```

CIは以下を実施します。

- pinned official-model URL reachability
- lint
- JVM unit tests
- debug APK
- release/R8
- APK archive/native ABI checks
- 16 KiB alignment
- APK signature + manifest/minSdk/targetSdk/label/icon/launcher assertions
- API 33 / API 36 emulator install + launch + Accessibility bind + instrumentation

配布APKの完全機能ABIは `arm64-v8a` と `x86_64` です。

## 何が実機待ちか

**コード・CI・自動化で閉じられる項目は実装対象です。** 残すのは実デバイス・実音声・実provider credentialsでしか観測できないものだけです。

- マイク/HAL/OEM差
- Android/OEM recognizerのsegmented-session実挙動と長時間日本語音声
- overlayの手触り・IME可視判定・focus遷移・各種third-party input field/WebView/Compose editor
- 実際の日本語音声でNemotron chunk/final-ASRのCER勝者決定
- Gboard / Wispr Flow / Aqua Voiceへ同一音声を入れた実測結果
- BYOK各providerの実credential/quota/model-list挙動
- Gemma E2B/E4Bの実機CPU/GPU latency / peak memory / thermal / battery
- 公式巨大モデルの実端末ネットワーク・ストレージ環境での取得確認
- 固定APK署名用private keystoreのGitHub Actions Secretsへの一回限りの登録

手順は [`docs/REAL_DEVICE_VALIDATION.md`](docs/REAL_DEVICE_VALIDATION.md) を参照してください。

## 引き継ぎ

- **製品仕様**: Notion（唯一の要件ソース）
- **実装状態・次の一手**: [`HANDOFF.md`](HANDOFF.md)
- **要件→実装→検証対応**: [`docs/REQUIREMENTS_MATRIX.md`](docs/REQUIREMENTS_MATRIX.md)
- **APK署名**: [`docs/SIGNING.md`](docs/SIGNING.md)
- **ベンチ規約**: [`docs/BENCHMARK_PROTOCOL.md`](docs/BENCHMARK_PROTOCOL.md)
- **実機検証**: [`docs/REAL_DEVICE_VALIDATION.md`](docs/REAL_DEVICE_VALIDATION.md)

PR #1は、実機データを捏造して完成扱いしないためDraftを維持します。
