# Floating VoiceBubble — Engineering Handoff

> **Authority rule:** Product requirements live in the project Notion page. This file describes implementation state and operating procedure; it must not silently redefine requirements.

## Resume point

- Repository: `GoodLight999/Floating-VoiceBubble`
- Working branch: `agent/initial-production-foundation`
- Draft PR: `#1 Build production-quality Android voice input foundation`
- Platform: Android 13+ (`minSdk 33`, `targetSdk 36`)
- Full runtime ABIs: `arm64-v8a`, `x86_64`
- Build: Gradle 9.5.0 / Java 17
- ASR runtime: sherpa-onnx v1.13.5
- Local correction runtime: LiteRT-LM 0.14.0

**Before changing code:** read the Notion specification, this file, `docs/REQUIREMENTS_MATRIX.md`, and the latest PR body/CI result.

## Architecture

### Input lifecycle

1. `VoiceBubbleAccessibilityService` tracks the current editor generation.
2. Floating bubble starts caller-owned 16 kHz mono PCM16 capture.
3. The same audio stream feeds endpointing, live recognizer, and optional session WAV trace.
4. Partial transcript stays visible while listening/finalizing.
5. Optional final-ASR re-decodes the exact finalized WAV.
6. Personal dictionary terms + N-best/context enter correction.
7. BYOK or Gemma performs minimum-edit correction; `CorrectionGuard` can reject it.
8. Final text is committed once only if the original editor generation is still valid.
9. If the target moved/disappeared or commit fails, final text is copied to clipboard.

### Recognition backends

- Android system `SpeechRecognizer`
- Android on-device `SpeechRecognizer`
- sherpa-onnx Nemotron 3.5 Streaming (`80/160/560/1120 ms`)

`RecognitionBackendResolver` enforces the offline contract: full offline requires the Sherpa model and never silently falls back to a cloud/OEM path.

### Final ASR

`SherpaFinalAsrEngine` supports the ReazonSpeech Zipformer candidate. It is intentionally separate from live partial ASR. ReazonSpeech is **not** described as true streaming.

### Correction

- OpenAI-compatible BYOK
- Anthropic Messages
- Gemini `generateContent`
- Gemma 4 E2B/E4B via LiteRT-LM

API secrets are Android-Keystore protected. Offline correction can never resolve to BYOK.

### Model storage and recovery

All large models are under `noBackupFilesDir`.

- `AtomicDirectoryInstaller`: ASR directory last-known-good replacement + interrupted-update recovery.
- `AtomicFileInstaller`: Gemma/benchmark data file last-known-good replacement + recovery.
- ASR stores create SHA-256 manifests after structural validation.
- Official Gemma downloads require exact reviewed byte size and SHA-256 before promotion.

### Official model acquisition

The advanced management screen uses `OfficialModelCatalog` + `OfficialModelInstaller`.

- Nemotron 3.5 Streaming int8: official sherpa-onnx GitHub release URLs.
- ReazonSpeech Zipformer int8: official sherpa-onnx GitHub release URL.
- Gemma 4 E2B/E4B LiteRT-LM: official LiteRT Community Hugging Face URLs.

URLs/fingerprints are source-controlled, not remotely configured. ASR `.tar.bz2` extraction emits only reviewed required basenames; duplicates/missing files are rejected.

### Dictionary

`PersonalDictionary` is SQLite-backed and intentionally unbounded at the product layer. It supports:

- upsert/delete/get
- literal substring search over term/reading/alias
- CSV/TSV import
- TSV export
- ranked ASR bias terms
- relevant correction context
- use-count ranking

Advanced UI provides search/edit/delete/import/export without loading the whole dictionary at once.

### Benchmarking

Human references are authoritative. Recognizer disagreement is never called accuracy.

`AsrTournamentRunner` compares, on the same saved utterances:

- current live transcript
- every installed Nemotron chunk model, replayed through its actual online recognizer
- ReazonSpeech final ASR
- imported external results (Gboard / Wispr Flow / Aqua Voice)

Metrics: strict code-point CER, punctuation/symbol-stripped content CER, WER only when tokenization is meaningful, and RTF where locally measurable.

## UI split

- `MainActivity` / `VoiceBubbleSettingsScreen`: normal product settings.
- `AdvancedToolsActivity`: model acquisition, dictionary management, ground truth, competitor imports, ASR tournament.
- Main screen exposes one compact `管理` FAB. Research/debug controls do not dominate daily-use UI.

## Automated diagnostics / CI

`SelfDiagnostics` checks what software can check without real-world audio/OEM behavior. Shareable output redacts secrets and user content.

CI must stay green for:

- pinned official-model URL reachability
- lint
- JVM tests
- debug + release/R8 assembly
- APK archive/native ABI checks
- 16 KiB alignment
- signature/manifest assertions
- API 33 emulator install/launch/Accessibility/instrumentation
- API 36 emulator install/launch/Accessibility/instrumentation

Do not weaken tests to make a change green.

## Remaining work boundary

At handoff, the intended boundary is: **only observations that intrinsically require a physical Android device, real microphone/audio, third-party apps/services, or device performance remain.**

Use `docs/REAL_DEVICE_VALIDATION.md`. Do not reopen already-closed architectural questions unless real-device evidence falsifies an assumption.

Typical device-only outputs:

- OEM microphone/HAL behavior
- overlay/focus/input compatibility in real third-party apps
- labeled Japanese CER on real utterances
- external competitor transcripts generated by their actual products
- Gemma CPU/GPU latency, memory, thermals, battery
- multi-GB download behavior under real phone storage/network constraints

## Invariants — do not regress

1. Do not call chunked re-decode “true streaming”.
2. Do not infer ground truth from another ASR.
3. Do not silently use cloud when offline mode is enabled.
4. Do not rewrite tone/register for cosmetic polish.
5. Do not delete the last-known-good model before a replacement is verified/promoted.
6. Do not expose API keys, recordings, or dictionary text in shareable diagnostics.
7. Do not treat emulator/build green as proof of microphone quality or ASR superiority.
8. Do not replace the Notion spec with this handoff file.

## Handoff closeout procedure

Before ending a development session:

1. Ensure working branch/PR head contains all intended changes.
2. Run the full GitHub Actions workflow and wait for model-catalog + verify + API33 + API36.
3. Update PR #1 body with the exact validated head/run/artifact.
4. Append a concise implementation-status note to Notion without rewriting the authoritative requirements.
5. Stop adding software work when `docs/REQUIREMENTS_MATRIX.md` has no non-device gap; proceed with `docs/REAL_DEVICE_VALIDATION.md` instead.

## Validation pointer

Do **not** pin a supposedly “current head” in this tracked file: editing the SHA here would create a new commit and immediately make that SHA stale. The durable validation snapshot is therefore:

- **PR #1 body:** exact validated head, GitHub Actions run, APK artifact and digest.
- **GitHub Actions for the current PR head:** source of truth for model-catalog / verify / API33 / API36 status.
- **`docs/REQUIREMENTS_MATRIX.md`:** source of truth for the software-vs-device boundary.

At a proper handoff closeout, the software-completable requirement rows are closed and the only remaining actions are those in `docs/REAL_DEVICE_VALIDATION.md`. If a later CI run is red, that red run overrides this readiness statement until fixed.
