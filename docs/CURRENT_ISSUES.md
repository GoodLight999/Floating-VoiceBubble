# Floating VoiceBubble — Current blocking issues

Updated: 2026-08-27

This file is the durable execution ledger for the current stabilization pass. It exists so no issue is lost if a chat context is compacted or restarted.

**Do not declare the app complete while any P0/P1 item below is OPEN.**

Status lifecycle: `OPEN` → `FIXED` → `VERIFIED`.

- `FIXED` means code was changed.
- `VERIFIED` means the stated acceptance tests passed on the same source head that produced the candidate APK.
- A green generic CI run is not enough to close an item whose acceptance criterion is more specific.

## P0 — Release blockers

### P0-01 — User-facing APK artifact integrity
Status: OPEN

Observed:
- A stale/wrong APK was previously handed to the user while the response claimed it was the newest CI payload.
- The handed file hash/ZIP-entry count did not match the declared artifact.

Required fix:
- Never reuse a stale `stable-latest.apk` by filename alone.
- Build/sign only from the exact final green source head.
- Use a unique filename containing the source SHA.
- After signing, re-read the exact file exposed to the user and verify SHA-256, signer certificate, APK signature scheme, ZIP integrity, ABI alignment, and byte-for-byte uncompressed payload equivalence with the validated CI APK.

Acceptance:
- Final response hash equals a fresh local hash of the linked file.
- Final APK contains expected current UI strings/features and does not contain removed legacy strings.

### P0-02 — Real correction times out across models
Status: FIXED

Observed:
- Actual voice correction can spend a long time and then fall back.
- The user reports this happens regardless of selected model.
- The short “real correction test” can finish immediately with reasoning at provider default while the real voice path times out.

Implemented repair:
- Production diagnostics now execute the real `FinalizationEngine` path with short and long vectors rather than a direct tiny `TextCorrector.correct()` call.
- The provider HTTP read deadline is shorter than the outer finalization watchdog, preserving structured provider timeout metadata instead of losing it to an outer generic timeout.
- `HttpConnectionDeadline` applies a wall-clock deadline to connect/write/header/body work and force-disconnects blocked calls.
- OpenAI-compatible/OpenRouter/Z.AI, Anthropic, and Gemini correction adapters use the same deadline contract.
- There is no hidden second LM retry for a valid no-op response.

Acceptance:
- Mock provider tests cover immediate success, slow success, read timeout, 4xx unsupported-parameter retry, empty response, and late response.
- Device diagnostic distinguishes provider reachability from production-equivalent correction.
- Runtime timeout/fallback reason is visible and persisted in the redacted trace.

### P0-03 — Reasoning control is not capability-aware
Status: FIXED

Observed:
- UI previously offered a generic reasoning ladder even when provider/model semantics were different.
- Provider/model APIs do not share one reasoning control vocabulary or capability set.

Implemented repair:
- Provider/model capability normalization covers OpenAI, OpenRouter, Z.AI, Anthropic, Gemini, and unknown compatible endpoints.
- Z.AI exposes only provider default / thinking OFF / thinking ON; fake depth levels are not shown.
- Unknown compatible endpoints expose provider default only.
- OpenRouter can narrow choices from retrieved per-model metadata.
- Exact wire bodies are regression-tested per provider family.
- Diagnostics use `ReasoningWireDescriptor` to show the redacted effective wire field, e.g. `reasoning_effort=max`, `thinking.type=disabled`, or `thinkingConfig.thinkingBudget=0`.

Acceptance:
- Snapshot/body tests assert the exact JSON sent for every supported provider family and every exposed reasoning choice.
- UI capability test proves unsupported choices are not offered as if they were distinct.
- Effective wire setting is shown in diagnostics without secrets.

### P0-04 — Model/reasoning selection test can use unsaved values while runtime uses old saved values
Status: FIXED

Observed:
- `CorrectionSetupActivity` previously held model/reasoning draft state that could diverge from runtime persisted settings.

Implemented repair:
- The production-equivalent correction test saves the selected endpoint/model/reasoning first, then reloads `SettingsStore` and passes those persisted values into the real finalization path.
- Model/reasoning selection persistence has instrumentation coverage.

Acceptance:
- Instrumentation test: select model/reasoning → run test → begin production-equivalent correction; both observe the same persisted values.

### P0-05 — Silent/insufficiently visible fallback
Status: FIXED

Observed:
- User could see long processing followed by apparent rollback/fallback with insufficient explanation.

Implemented repair:
- `CorrectionStatusStore` persists only redacted operational failure metadata: provider, model, effective reasoning, latency, attempts, HTTP/error class, response/change state, integrity result, endpoint, and fallback source.
- RAW/context/dictionary/API keys are not stored there.
- Main settings keeps a visible “前回の文章補正に失敗しました” card until a later successful correction clears it.
- Activity-recreation instrumentation verifies the failure reason and RAW fallback remain readable.

Acceptance:
- Timeout/error instrumentation verifies the user receives an explicit failure message and RAW fallback is labelled as such.
- Diagnostic/main status can reproduce the last failure without exposing API keys or sensitive surrounding text by default.

### P0-06 — Misleading “安全ガード” and normal-text rejection
Status: FIXED

Observed:
- “安全ガード” sounded like content-policy censorship although the feature is only output integrity/user preference enforcement.
- Coarse edit-distance checks could reject ordinary correction.

Implemented repair:
- User-facing `安全ガード` wording is removed.
- Edit distance is diagnostic only.
- Whole-output rejection is limited to empty output, catastrophic runaway/truncation, and lexical changes explicitly forbidden by the user's selected repair mode.
- Regression tests cover punctuation, filler handling, multiple line breaks, normal/strong ASR repair, register rewrites when explicitly requested, long corrections, runaway expansion, and catastrophic loss.

Acceptance:
- Regression corpus of ordinary Japanese dictation never receives a policy-like rejection.
- Tests cover punctuation-only changes, filler removal, multiple line breaks, strong ASR repair, register preservation, long corrections, and actual runaway output.

### P0-07 — Line breaks still fail or feel context-blind
Status: FIXED

Observed:
- Requested line breaks were not reliably produced and earlier deterministic fallbacks could split text semantically badly.

Implemented repair:
- Deterministic paragraphing may choose only linguistic candidates: sentence/clause punctuation, discourse/topic markers, and selected clause endings.
- Target character widths only rank valid linguistic candidates; they never create a blind midpoint boundary.
- A long string with no linguistic boundary is deliberately left unsplit.
- Regression corpus includes the reported conversational sentence, comma-only Japanese, punctuation-free Japanese, short utterances, multiple paragraphs, and forbidden split positions.
- Session trace schema records `modelLineBreakChanged` separately from `appLineBreakChanged`.

Acceptance:
- Corpus tests specify expected/forbidden boundaries rather than just “contains a newline”.
- Real correction trace distinguishes model-inserted vs app-inserted line breaks.

### P0-08 — Correction prompt is ad hoc and lacks documented research basis
Status: FIXED

Observed:
- The former `CorrectionPrompt` was hand-authored without a documented evidence trail.

Implemented repair:
- `docs/CORRECTION_PROMPT_RESEARCH.md` records ASR post-editing/N-best/contextual biasing/punctuation/disfluency/minimal-edit research and maps sources to design principles.
- Prompt v2 treats the task as ASR post-editing, anchors on RAW, limits N-best/dictionary/context evidence, preserves register, forbids fact invention, and separates explicit formatting preferences from lexical repair.
- Prompt/regression tests cover repair and hallucination-sensitive behavior.

Acceptance:
- `docs/CORRECTION_PROMPT_RESEARCH.md` contains sources → design principles → prompt decisions → evaluation corpus/results.
- Prompt regression/benchmark covers ASR repair, hallucination resistance, register preservation, punctuation, fillers, line breaks, and no-op cases.

### P0-09 — Settings UX is duplicated and structurally confusing
Status: FIXED

Observed:
- The former main/normal/detail/management surfaces overlapped and made it unclear which control affected runtime.

Implemented repair:
- Everyday behavior is consolidated on the main settings screen.
- Generic `詳細` is removed.
- Task-specific pages remain for model/API, dictionary, per-app settings, offline models, and diagnostics.
- UI tests assert legacy/ambiguous controls are absent and the primary correction/reasoning/repair controls are present.

Acceptance:
- There is exactly one editable instance for each everyday setting.
- UI test verifies no duplicate control with conflicting state.
- First viewport makes current correction model, correction on/off, reasoning capability/value, ASR-repair strength, and formatting behavior understandable.

### P0-10 — User-facing terminology is developer jargon or ambiguous
Status: FIXED

Observed:
- `ASR`, punctuation-only chips, and `フィラー` exposed implementation jargon or ambiguous actions.

Implemented repair:
- Normal UX uses `音声認識`.
- Formatting actions use explicit labels such as `読点「、」を追加`, `句点「。」を追加`, and `「えー」「あのー」等を削除`.
- User-facing string audit currently finds no `安全ガード`, unexplained `ASR`, or ambiguous `フィラー` in `app/src/main`.

Acceptance:
- User-facing string audit finds no unexplained `ASR`, “安全ガード”, MVP/development-policy language, or ambiguous `フィラー` toggle.
- UI screenshots are reviewed for clarity, not merely presence of controls.

### P0-11 — App-specific overrides can make effective runtime behavior differ from global UI
Status: FIXED

Observed:
- Runtime applies `AppCorrectionProfile` after global settings, so a main screen showing only globals can make a setting appear ignored.

Implemented repair:
- Main settings shows an explicit recent-app override indicator and summarizes effective differences when an override is active.
- Self-diagnostics resolves the most recent package through `effectiveSettings(...)` before reporting the correction route or running production probes.

Acceptance:
- Tests prove global vs per-app effective settings and visible override indication agree.

### P0-12 — Gemma download is brittle and external models are duplicated
Status: FIXED

Observed:
- Official E2B/E4B downloads used a one-shot multi-gigabyte HTTP stream with no reliable resume.
- An older import path duplicated already-owned multi-gigabyte models in app-private storage.
- A later attempt to pass a persisted SAF descriptor as `/proc/self/fd/<n>` was reproduced as `EACCES` on Android 16 when the native library reopened the path. LiteRT-LM's current Kotlin API accepts a filesystem model path, not a generic `content://` stream/descriptor.

Implemented repair:
- Official Gemma downloads keep a stable partial file, resume with HTTP Range, retain valid bytes across transient failures, verify exact length/SHA-256, and atomically rename on the same filesystem.
- Final official/imported models live in the supported real-file model directory so LiteRT-LM receives an ordinary absolute path.
- A `.litertlm` already placed in the supported shared model directory is verified and used directly without a second model-sized copy.
- Android document-picker `content://` input is **not** falsely advertised as no-copy. The picker imports it once into the supported real-file directory and the UI says that a copy is being made.
- Persisted legacy `content://` references are explicitly reported as unrunnable/migration-required rather than silently falling back.
- Diagnostics distinguish `shared-real-file-no-copy`, `real-file-path`, and `legacy-content-uri-unrunnable`.

Acceptance:
- JVM regression: an injected mid-stream I/O failure leaves the partial bytes intact, the next request resumes from that exact offset, and the reconstructed file matches the source byte-for-byte.
- JVM regression: a server that ignores Range and returns HTTP 200 is reused as a clean restart without an extra request.
- JVM regression: malformed Content-Range is rejected without corrupting the existing partial and repeated early EOF stops after bounded retries.
- Device/instrumentation: a real `.litertlm` in the supported model directory is opened by the same absolute path and creates no app-private duplicate.
- Device/instrumentation: a `content://` reference is never reported as directly runnable; picker import produces a byte-identical real file that is runnable by path.
- Device/instrumentation: a legacy/missing source produces an explicit unavailable/migration state rather than silent fallback.
- Full production-equivalent Gemma correction succeeds on at least one supported device/backend before P0-12 becomes `VERIFIED`.

## P1 — Required stabilization work

### P1-01 — Production-equivalent diagnostics
Status: OPEN

Required:
- Separate `API疎通テスト` from `本番相当の補正テスト`.
- Production-equivalent test must use the real Finalization request construction, effective settings, provider adapter, timeout/retry, postprocessing, and integrity checker.
- Include a long Japanese test vector representative of real prompt size in addition to the short semantic repair probe.

### P1-02 — Provider request/response observability
Status: OPEN

Required:
- Redacted diagnostics record provider family, normalized endpoint host/path, model, capability profile, effective reasoning wire setting, attempt number, connect/read duration, HTTP status, response-present flag, and parse failure.
- Never record API key.
- Surrounding text/raw transcript should only be included in explicit trace mode and remain in no-backup storage.

### P1-03 — Retry/timeout policy must be latency-aware
Status: FIXED

Implemented:
- Provider network deadlines now complete before the outer finalization watchdog.
- `HttpConnectionDeadline` bounds the complete non-streaming HTTP call and force-disconnects a blocked socket at deadline.
- Valid no-op responses do not trigger a hidden second model call.
- Reasoning defaults remain provider/model defaults unless the user explicitly selects a supported setting.

Required verification:
- Exact provider timeout/failure metadata remains correct across API 33/36 and production-equivalent probes.
- Per-phase/per-attempt latency observability is still tracked under P1-02.

### P1-04 — Provider/model capability research and adapters
Status: OPEN

Required research/verification:
- OpenAI native reasoning controls and model support.
- OpenRouter reasoning abstraction and per-model supported parameters.
- Z.AI `thinking` behavior and model-specific support.
- Anthropic effort/adaptive thinking support by model/API version.
- Gemini 2.5 thinking budget vs Gemini 3 thinking level and model-specific constraints.
- Generic OpenAI-compatible endpoints: portable core only unless capabilities are known.

Document all claims against current official docs and test request bodies.

### P1-05 — Correction quality corpus and regression suite
Status: OPEN

Required corpus dimensions:
- Japanese casual speech / rough register / polite speech.
- Clear ASR homophone/segmentation errors.
- N-best disambiguation.
- Personal-dictionary names.
- English/Japanese mixed terms.
- Filler removal on/off.
- Punctuation on/off.
- Line breaks none/smart/spaced.
- Multiple-topic long utterances.
- No-op clean transcript.
- Hallucination trap/context contains facts not spoken in RAW.

Record exact expected invariants and score candidate prompts/models.

## Previously reported issues that remain part of this pass

These are not superseded by newer reports:

- The processing/status text must be visually distinct from recognized transcript text.
- A real `補正せず入力` path must bypass LM correction and cannot be overwritten by a late LM result.
- Correction attribution must distinguish LM changes from deterministic app formatting.
- Long-running correction must not block the next recording; commits must preserve utterance order.
- Password/sensitive inputs must not collect surrounding context.
- Recent context must not cross app/field boundaries.
- Failed/cancelled audio must not leave orphan raw recordings.
- Stable signing identity must never change.
- One-click diagnostics must remain available, but its UI should be user-readable rather than developer-oriented.

## Release gate

A candidate APK can be handed to the user only when:

1. Every P0 item is `VERIFIED` on one exact source head.
2. P1 provider/prompt/diagnostic work required to validate P0 is also `VERIFIED`.
3. API 33 + API 36 emulator suites pass on that head.
4. JVM/provider-body/mock-server/prompt-regression suites pass on that head.
5. User-facing screenshots are manually reviewed for terminology, layout, and duplicate controls.
6. The exact linked APK is freshly signed from that head and artifact integrity checks pass.
7. The PR and Notion spec are updated to the same head and no longer claim that software-side work is exhausted.

## Current source baseline

Baseline before this stabilization pass:
- branch: `agent/initial-production-foundation`
- source head: `599f97a2e205c233114af0d908928eb462563992`
- prior CI: Actions #271

Current stabilization work continues on the same branch; the exact candidate head is the head that must satisfy the release gate above.

**The prior “complete/software-side exhausted” conclusion is explicitly revoked by this ledger.**
