# Floating VoiceBubble — Engineering Handoff

> **Authority rule:** Product requirements live in the project Notion page. This file describes implementation state and operating procedure; it must not silently redefine requirements.

> **ACTIVE STABILIZATION — READ FIRST:** the previous “software-side work is exhausted” conclusion is revoked. Before doing anything else, read `docs/CURRENT_ISSUES.md`. That file is the durable OPEN → FIXED → VERIFIED ledger for the 2026-08-22 stabilization pass and overrides the stale “remaining work boundary” language later in this historical handoff until the ledger is fully verified.

## Resume point

- Repository: `GoodLight999/Floating-VoiceBubble`
- Working branch: `agent/initial-production-foundation`
- Draft PR: `#1 Build production-quality Android voice input foundation`
- **Current execution ledger:** `docs/CURRENT_ISSUES.md`
- Platform: Android 13+ (`minSdk 33`, `targetSdk 36`)
- Full runtime ABIs: `arm64-v8a`, `x86_64`
- Build: Gradle 9.5.0 / Java 17
- ASR runtime: sherpa-onnx v1.13.5
- Local correction runtime: LiteRT-LM 0.14.0

**Before changing code:** read the Notion specification, `docs/CURRENT_ISSUES.md`, this file, `docs/REQUIREMENTS_MATRIX.md`, and the latest PR body/CI result.

## Architecture

### Input lifecycle

1. `VoiceBubbleAccessibilityService` tracks the current editor generation and whether an actual `TYPE_INPUT_METHOD` window is visible.
2. The bubble exists only for a live software-keyboard input surface; hiding the IME also prevents invisible microphone capture.
3. Tapping the bubble starts caller-owned 16 kHz mono PCM16 capture.
4. Android/OEM live recognition uses API33 caller-audio segmented-session mode when honored. `onSegmentResults` commits provider segments; ordinary finals/recoverable segment-ending errors are retained and restarted as an OEM fallback rather than treated as VoiceBubble EOF.
5. `TranscriptAccumulator` merges segment overlap so provider boundary text is not duplicated or discarded.
6. Local endpointing is deliberately conservative for long dictation: ordinary silence threshold is 1.4 s and sustained long dictation requires 2.2 s.
7. Partial transcript stays visible while listening. Explicit cancel discards the active session; dragging to the bottom dismiss target closes VoiceBubble for that input.
8. Recognition completion and LLM correction are separate lifecycle stages. The previous utterance may correct in a keyed finalization job while a new microphone session begins; an old finalization notice cannot overwrite a newer partial transcript.
9. Optional final-ASR re-decodes the exact finalized WAV. A latch prevents final-ASR from racing an unfinished PCM→WAV wrap.
10. Personal dictionary terms + N-best/context enter correction.
11. BYOK or Gemma performs correction according to the explicit punctuation/filler/register controls. `CorrectionGuard` currently enforces output/preference invariants; **its current behavior and user-facing naming are under active P0 review in `docs/CURRENT_ISSUES.md`.**
12. Final text is committed once only if the original editor generation is still valid.
13. If the target moved/disappeared or commit fails, final text is copied to clipboard.

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

`CorrectionSetupActivity` currently provides API/model configuration and testing, but **its draft-vs-persisted settings behavior, reasoning capability model, and test-vs-runtime equivalence are active P0 defects**. Do not assume its “real correction test” represents production behavior until those ledger items are VERIFIED.

API secrets are Android-Keystore protected. Offline correction can never resolve to BYOK.

### Model storage and recovery

All large models are under `noBackupFilesDir`.

- `AtomicDirectoryInstaller`: ASR directory last-known-good replacement + interrupted-update recovery.
- `AtomicFileInstaller`: Gemma/benchmark data file last-known-good replacement + recovery.
- ASR stores create SHA-256 manifests after structural validation.
- Official Gemma downloads require exact reviewed byte size and SHA-256 before promotion.

### Official model acquisition

The advanced management screen and correction setup use `OfficialModelCatalog` + `OfficialModelInstaller`.

- Nemotron 3.5 Streaming int8: official sherpa-onnx GitHub release URLs.
- ReazonSpeech Zipformer int8: official sherpa-onnx GitHub release URL.
- Gemma 4 E2B/E4B LiteRT-LM: official LiteRT Community Hugging Face URLs.

URLs/fingerprints are source-controlled, not remotely configured. ASR `.tar.bz2` extraction emits only reviewed required basenames; duplicates/missing files are rejected.

### Dictionary

`PersonalDictionary` is SQLite-backed and intentionally unbounded at the product layer. It supports upsert/delete/get, search over term/reading/alias, import/export, ranked ASR bias terms, correction context, and use-count ranking.

### Benchmarking

Human references are authoritative. Recognizer disagreement is never called accuracy. Benchmarking/development rationale belongs in engineering/docs, **not ordinary user-facing settings UI**.

## App identity and signing

- Manifest label: `Floating VoiceBubble`
- Adaptive launcher icon plus round and Android themed monochrome icon are source-controlled.
- CI asserts package/label/icon/launcher metadata with `aapt`.
- Stable sideload/update signing uses a private JKS supplied through `FVB_SIGNING_*` environment variables / GitHub Actions secrets.
- CI verifies APK signature and can pin the expected certificate SHA-256 with `FVB_SIGNING_CERT_SHA256`.
- The private signing key must never be committed to this public repository. See `docs/SIGNING.md`.
- **Artifact provenance is an active P0 gate** because a stale/wrong APK was previously handed to the user. Use unique source-SHA filenames and re-hash the exact linked file.

## UI state

The current UI split is **not considered final**. `docs/CURRENT_ISSUES.md` records P0 defects covering duplicated normal/detail/management settings, developer jargon, ambiguous punctuation/filler controls, and invisible effective per-app overrides. The stabilization target is one canonical everyday settings surface plus clearly scoped subpages.

## Automated diagnostics / CI

`SelfDiagnostics` exists, but its current short provider probe is **not production-equivalent**. The active ledger requires separate reachability and full-finalization probes, effective-settings reporting, provider/body observability, and explicit timeout/fallback evidence.

CI must stay green for pinned model URLs, lint, JVM tests, debug/release assembly, APK/native alignment/signature/manifest checks, API33, and API36. **Do not weaken tests to make a change green.**

## Remaining work boundary — historical, currently superseded

The earlier intended boundary was “only physical-device/external-provider observations remain.” Real-device evidence falsified that assumption. **Until every P0 in `docs/CURRENT_ISSUES.md` is VERIFIED, substantial software-side work remains.**

## Invariants — do not regress

1. Do not call chunked re-decode “true streaming”.
2. Do not infer ground truth from another ASR.
3. Do not silently use cloud when offline mode is enabled.
4. Do not rewrite tone/register unless the user explicitly selected a register conversion.
5. Do not delete the last-known-good model before a replacement is verified/promoted.
6. Do not expose API keys, recordings, dictionary text, or the APK signing private key in source/shareable diagnostics.
7. Do not treat emulator/build green as proof of microphone quality or ASR superiority.
8. Do not replace the Notion spec with this handoff file.
9. Do not treat a provider/OEM recognition segment boundary as the end of the user's VoiceBubble utterance.
10. Do not let an older correction result overwrite a newer active transcript UI.
11. Do not expose development-policy prose or unexplained engineering jargon as user-facing product copy.
12. Do not call output-integrity validation a “safety guard” in user-facing UI.

## Handoff closeout procedure

Before ending a development session:

1. Update `docs/CURRENT_ISSUES.md`: no issue may skip `FIXED`/`VERIFIED` evidence.
2. Ensure branch/PR head contains all intended changes.
3. Run full Actions (model-catalog + verify + API33 + API36) plus the issue-specific provider/prompt/mock-server regressions required by the ledger.
4. Update PR #1 body with the exact state; never claim complete while ledger blockers remain.
5. Append a concise status note to Notion without rewriting authoritative requirements.
6. Only after all P0/P1 release gates are VERIFIED may a stable APK be freshly signed and handed to the user.

## Validation pointer

Do **not** pin a supposedly current head in this tracked file. Use:

- `docs/CURRENT_ISSUES.md`: current stabilization state and acceptance criteria.
- PR #1 body: current high-level status.
- GitHub Actions for current PR head: CI status.
- Notion: authoritative product requirements and stabilization note.

If a later CI run is red or real-device evidence contradicts a prior assumption, that evidence reopens the relevant ledger item.