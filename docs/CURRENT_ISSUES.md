# Floating VoiceBubble — Current blocking issues

Updated: 2026-08-28

This file is the durable execution ledger for the current stabilization pass. It exists so no issue is lost if a chat context is compacted or restarted.

**Do not declare the app complete while any required final-release verification below is unresolved.**

Status lifecycle: `OPEN` → `FIXED` → `VERIFIED`.

- `FIXED` means the implementation/documentation change is present.
- `VERIFIED` means the stated acceptance evidence passed on the exact source head under test.
- A green generic CI run is not enough to close an item whose acceptance criterion is more specific.
- An item whose final acceptance inherently requires physical-device/provider credentials may remain `FIXED` on the software-verified device candidate. It is not called final until that external acceptance passes.

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
- CI must preserve the R8 release candidate even when the stable signer is deliberately absent from GitHub Secrets; debug APK is never the distribution source.
- After stable signing, re-read the exact exposed file and verify SHA-256, signer certificate, APK signature scheme, ZIP integrity, ABI alignment, and byte-for-byte uncompressed payload equivalence with the validated unsigned/same-signer release candidate.

Acceptance:
- Final response hash equals a fresh hash of the linked file.
- Signer certificate is the existing Floating VoiceBubble stable identity, not a newly generated or runner-local identity.
- Final APK contains the validated current payload and no stale payload substitution occurred.

### P0-02 — Real correction times out across models
Status: FIXED

Observed:
- Actual voice correction can spend a long time and then fall back.
- The user reports this happens regardless of selected model.
- A tiny model test can succeed while the production request times out.

Implemented repair:
- Production diagnostics execute the real `FinalizationEngine` path with short and long vectors rather than a direct tiny correction call.
- A separate `correction-api-reachability` probe now calls the effective generation endpoint without final-ASR/N-best/dictionary/surrounding-context/postprocessing/integrity work, so transport/auth/model reachability is not conflated with the production path.
- Provider HTTP deadlines complete before the outer finalization watchdog and retain structured failure metadata.
- `HttpConnectionDeadline`/timed transport bounds connect/write/header/body work and force-disconnects blocked calls.
- OpenAI-compatible/OpenRouter/Z.AI, Anthropic, and Gemini correction adapters use the deadline contract.
- Valid no-op output does not trigger a hidden second LM call.
- Z.AI thinking-enabled/default requests reserve at least 4096 output tokens so short correction requests are less likely to consume their entire cap in `reasoning_content` before producing final text; thinking-OFF remains compact.

Acceptance:
- Mock/provider transport tests cover immediate success, slow success inside deadline, read timeout, 4xx optional-parameter behavior, empty response, and late response.
- Device diagnostic reports generation-endpoint reachability separately from production-equivalent short/long correction.
- Runtime timeout/fallback reason is visible and persisted in redacted diagnostics.

### P0-03 — Reasoning control is not capability-aware
Status: FIXED

Observed:
- UI previously offered a generic reasoning ladder even when provider/model semantics were different.
- Provider/model APIs do not share one reasoning-control vocabulary or capability set.

Implemented repair:
- Provider/model capability normalization covers OpenAI, OpenRouter, Z.AI, Anthropic, Gemini, and unknown compatible endpoints.
- Known Z.AI GLM-4.5/4.6/4.7 and recognized GLM-5/5.1 families expose provider default / thinking OFF / thinking ON only.
- GLM-5.3 is modeled separately: thinking cannot be disabled and explicit controls use `reasoning_effort=low|high|max` with `thinking.type=enabled`.
- Unknown Z.AI/future compatible models receive no guessed proprietary reasoning field.
- OpenRouter can narrow choices from retrieved per-model metadata.
- Exact wire bodies are regression-tested per provider family.
- Diagnostics use `ReasoningWireDescriptor` to show the redacted effective wire field.

Acceptance:
- Body tests assert exact JSON for supported provider families and exposed reasoning choices.
- UI capability tests prove unsupported choices are not offered as if distinct.
- Effective wire setting is shown without secrets.

### P0-04 — Model/reasoning selection test can use unsaved values while runtime uses old saved values
Status: FIXED

Observed:
- `CorrectionSetupActivity` previously held model/reasoning draft state that could diverge from runtime persisted settings.

Implemented repair:
- Production-equivalent correction testing saves selected endpoint/model/reasoning first, reloads `SettingsStore`, and runs with those persisted values.
- Model/reasoning selection persistence has instrumentation coverage.

Acceptance:
- Instrumentation: select model/reasoning → run test → production-equivalent correction; both observe the same persisted values.

### P0-05 — Silent/insufficiently visible fallback
Status: FIXED

Observed:
- User could see long processing followed by apparent rollback/fallback with insufficient explanation.

Implemented repair:
- `CorrectionStatusStore` persists only redacted operational failure metadata: provider, model, effective reasoning, endpoint, effective wire setting, attempts, HTTP/error/failure stage, response/change state, integrity result, fallback source, and per-attempt connect/write/header/body/total timing.
- RAW/context/dictionary/API keys are not stored there.
- Main settings keeps a visible `前回の文章補正に失敗しました` card until a later successful correction clears it.
- Failure-card instrumentation verifies reason/fallback/wire/timing remain readable and secrets/transcript do not appear.

Acceptance:
- Timeout/error instrumentation verifies explicit failure + labelled RAW fallback.
- Diagnostic/main status reproduces the last operational failure without API keys or sensitive surrounding text by default.

### P0-06 — Misleading “安全ガード” and normal-text rejection
Status: FIXED

Observed:
- `安全ガード` sounded like content-policy censorship although the feature is output integrity/user-preference enforcement.
- Coarse edit-distance checks could reject ordinary correction.

Implemented repair:
- User-facing `安全ガード` wording is removed.
- Edit distance is diagnostic only.
- Whole-output rejection is limited to empty output, catastrophic runaway/truncation, and lexical changes explicitly forbidden by the selected repair mode.
- Regression tests cover punctuation, filler handling, multiple line breaks, normal/strong recognition repair, explicit register rewrite, long corrections, runaway expansion, and catastrophic loss.

Acceptance:
- Ordinary Japanese dictation corpus never receives a policy-like rejection.
- Tests cover allowed ordinary changes and actual catastrophic output failures.

### P0-07 — Line breaks still fail or feel context-blind
Status: FIXED

Observed:
- Requested line breaks were not reliably produced and earlier deterministic fallbacks could split text semantically badly.

Implemented repair:
- Deterministic paragraphing may choose only linguistic candidates: sentence/clause punctuation, discourse/topic markers, and selected clause endings.
- Character-width targets rank valid linguistic candidates only; they never create a blind midpoint boundary.
- A long string with no linguistic boundary is deliberately left unsplit.
- Regression corpus includes conversational, comma-only, punctuation-free, short, multi-paragraph, and forbidden-boundary cases.
- Session trace records `modelLineBreakChanged` separately from `appLineBreakChanged`.

Acceptance:
- Corpus specifies expected/forbidden boundaries rather than only “contains newline”.
- Correction trace distinguishes model-inserted vs app-inserted line breaks.

### P0-08 — Correction prompt is ad hoc and lacks documented research basis
Status: FIXED

Observed:
- The former `CorrectionPrompt` was hand-authored without a documented evidence trail.

Implemented repair:
- `docs/CORRECTION_PROMPT_RESEARCH.md` maps ASR post-editing/N-best/contextual biasing/punctuation/disfluency/minimal-edit research to design decisions.
- Prompt v2 treats the task as ASR post-editing, anchors on RAW, bounds N-best/dictionary/context evidence, preserves register, forbids fact invention, and separates formatting preferences from lexical repair.
- `docs/CORRECTION_QUALITY_CORPUS.md` and executable corpus tests define the fixed quality contract.

Acceptance:
- Research doc contains sources → design principles → prompt decisions → evaluation contract.
- Regression corpus covers ASR repair, hallucination resistance, register preservation, punctuation, fillers, line breaks, mixed language, dictionary/N-best, long utterances, and no-op cases.

### P0-09 — Settings UX is duplicated and structurally confusing
Status: FIXED

Observed:
- Former main/normal/detail/management surfaces overlapped and made it unclear which control affected runtime.

Implemented repair:
- Everyday behavior is consolidated on the main settings screen.
- Generic `詳細` is removed.
- Task-specific pages remain for correction model/API, dictionary, per-app settings, offline models, and cloud recognition.
- UI tests assert legacy/ambiguous controls are absent and primary correction/reasoning/repair controls are present.

Acceptance:
- Exactly one editable instance exists for each everyday setting.
- UI test verifies no duplicate control with conflicting state.
- First viewport makes current correction model, correction on/off, reasoning value/capability, repair strength, and formatting behavior understandable.

### P0-10 — User-facing terminology is developer jargon or ambiguous
Status: FIXED

Observed:
- `ASR`, punctuation-only chips, and `フィラー` exposed implementation jargon or ambiguous actions.

Implemented repair:
- Normal UX uses `音声認識`.
- Formatting actions use explicit labels such as `読点「、」を追加`, `句点「。」を追加`, and `「えー」「あのー」等を削除`.
- User-facing string audit rejects `安全ガード`, unexplained `ASR`, and ambiguous `フィラー` in normal UI.
- Z.AI Coding Plan preset now states `対応ツール向け` because Z.AI's current plan documentation restricts Coding Plan quota to supported tools/products; the app does not misrepresent provider entitlement as ordinary BYOK support.

Acceptance:
- User-facing audit finds no unexplained jargon/development-policy prose.
- API-plan labels do not promise an entitlement the provider does not grant to arbitrary apps.
- UI screenshots are manually reviewed for clarity, not merely control presence.

### P0-11 — App-specific overrides can make effective runtime behavior differ from global UI
Status: FIXED

Observed:
- Runtime applies `AppCorrectionProfile` after global settings, so a main screen showing only globals can make a setting appear ignored.

Implemented repair:
- Main settings shows an explicit recent-app override indicator and summarizes effective differences when an override is active.
- Self-diagnostics and the standalone reachability probe resolve the most recent package through `effectiveSettings(...)` before testing the correction route.

Acceptance:
- Tests prove global vs per-app effective settings and visible override indication agree.

### P0-12 — Gemma download is brittle and external models are duplicated
Status: FIXED

Observed:
- Official E2B/E4B downloads used a one-shot multi-gigabyte HTTP stream with no reliable resume.
- An older import path duplicated already-owned multi-gigabyte models in app-private storage.
- Passing a persisted SAF descriptor as `/proc/self/fd/<n>` was reproduced as `EACCES` on Android 16 when the native library reopened it; LiteRT-LM's Kotlin API requires a filesystem path.

Implemented repair:
- Official Gemma downloads keep a stable partial file, resume with HTTP Range, retain valid bytes across transient failures, verify exact length/SHA-256, and atomically rename on the same filesystem.
- Final official/imported models live at a supported real-file path for LiteRT-LM.
- A `.litertlm` already placed in the supported shared model directory is verified and used directly without a second model-sized copy.
- Document-picker `content://` input is not falsely advertised as no-copy; it imports once to a supported real-file location.
- Legacy `content://` references are explicitly reported as unrunnable/migration-required rather than silently falling back.
- Diagnostics distinguish `shared-real-file-no-copy`, `real-file-path`, and `legacy-content-uri-unrunnable`.

Acceptance:
- JVM: interrupted/resumed download reconstruction, Range-ignored clean restart, malformed Content-Range rejection, and bounded early-EOF retries.
- Device/instrumentation: supported real file opens at same path without app-private duplicate.
- Device/instrumentation: `content://` is not reported as directly runnable and import produces a byte-identical real file.
- Device/instrumentation: legacy/missing sources produce explicit unavailable/migration state.
- **External final acceptance:** full production-equivalent Gemma correction succeeds on at least one supported physical device/backend before P0-12 becomes `VERIFIED` and the build is called final.

## P1 — Required stabilization work

### P1-01 — Production-equivalent diagnostics
Status: FIXED

Implemented:
- `correction-api-reachability` is a separate generation-endpoint probe using the effective per-app settings and provider adapter without final-ASR, N-best, dictionary, surrounding context, postprocessing, or integrity decisions.
- The one-click diagnostic executes the reachability probe first, then the real `FinalizationEngine` production-equivalent checks.
- Production-equivalent checks include a short semantic-repair vector and a long Japanese vector representative of real prompt size.

Verification required:
- API 33/36 instrumentation/UI path completes and copied diagnostic report contains a distinct reachability item plus production short/long items.

### P1-02 — Provider request/response observability
Status: FIXED

Implemented:
- Redacted operational diagnostics carry provider family, normalized generation endpoint, model, effective reasoning label/wire, attempt count, connect/write/header/body/total durations, HTTP status, response-present state, parse/failure stage, integrity outcome, change attribution, and fallback.
- Per-utterance `FinalizationResult` timing is persisted from that utterance itself, avoiding concurrent-utterance metadata mixups.
- API key, RAW, surrounding context, and dictionary content are excluded from the durable correction-failure status.
- Explicit session trace remains in no-backup storage; default operational status remains content-free.

Verification required:
- API 33/36 persistence/UI tests and transport tests pass on the candidate head.
- Secret/transcript negative assertions pass.

### P1-03 — Retry/timeout policy must be latency-aware
Status: FIXED

Implemented:
- Provider network deadlines complete before the outer finalization watchdog.
- Timed HTTP transport bounds the complete non-streaming call and force-disconnects a blocked socket at deadline.
- Valid no-op responses do not trigger a hidden second model call.
- Provider/model reasoning defaults remain defaults unless the user explicitly chooses a supported setting.
- A provider-specific convenience such as Z.AI `do_sample` may be removed on a bounded compatibility retry **without** dropping an explicitly selected reasoning setting. If the reasoning field itself is rejected, the app fails explicitly rather than silently changing semantics.

Verification required:
- Exact timeout/failure metadata remains correct across API 33/36 and production-equivalent probes.
- Retry tests prove non-reasoning convenience removal preserves the selected reasoning wire.

### P1-04 — Provider/model capability research and adapters
Status: FIXED

Implemented:
- `docs/PROVIDER_CAPABILITY_RESEARCH.md` records current official sources and contracts for OpenAI, OpenRouter, Z.AI (including GLM-5.3 and Coding Plan routing/entitlement constraints), Anthropic, Gemini, and unknown compatible endpoints.
- Capability normalization and exact-body tests map those documented controls to each provider adapter.
- Unknown models/compatible endpoints default to omission instead of guessed proprietary fields.

Verification required:
- Exact-body/capability/wire descriptor tests pass on the candidate head.

### P1-05 — Correction quality corpus and regression suite
Status: FIXED

Implemented:
- `docs/CORRECTION_QUALITY_CORPUS.md` defines binary acceptance/scoring rules.
- `CorrectionQualityContractTest` names every required dimension so deleting one fails coverage.
- Dimensions include casual/rough/polite register, homophone/segmentation repair, N-best, dictionary names, Japanese/English mix, filler on/off, punctuation on/off, line-break none/smart/spaced, multi-topic long speech, clean no-op, and context-only hallucination trap.

Verification required:
- Full JVM prompt/postprocessor/guard/provider suite passes on the exact candidate head.

## Previously reported issues that remain part of this pass

These are not superseded by newer reports:

- Processing/status text must be visually distinct from recognized transcript text. Current implementation uses smaller status typography than the transcript.
- A real `補正せず入力` path must bypass LM correction and cannot be overwritten by a late LM result.
- Correction attribution must distinguish LM changes from deterministic app formatting.
- Long-running correction must not block the next recording; commits must preserve utterance order.
- Password/sensitive inputs must not collect surrounding context.
- Recent context must not cross app/field boundaries.
- Failed/cancelled audio must not leave orphan raw recordings.
- Stable signing identity must never change.
- One-click diagnostics must remain user-readable and distinguish endpoint reachability from production-equivalent correction.

## Artifact gates

### Software-verified device candidate

A candidate APK may be handed to the user **for the physical-device acceptance that cannot be performed in CI** only when all of the following are true on one exact source head:

1. Every P0 item except an explicitly physical-device-only acceptance clause is at least `FIXED`, and all software-verifiable acceptance criteria have passed.
2. P1 provider/prompt/diagnostic work required to validate those fixes is `VERIFIED` by the exact candidate-head test evidence.
3. API 33 + API 36 emulator suites pass on that head.
4. JVM/provider-body/mock-transport/prompt-regression suites pass on that head.
5. User-facing API33/API36 screenshots from that head are manually reviewed for terminology, hierarchy, clipping, duplicate controls, and dark/light compatibility evidence available in CI.
6. The exact linked APK is produced from the R8 release candidate of that head, freshly signed with the existing stable identity, and passes hash/signature/ZIP/ABI-alignment/payload-equivalence checks.
7. The artifact filename contains the source SHA and the response reports a fresh hash of the same linked file.

Such an APK is called a **software-verified device candidate**, not a final release.

### Final release

The build may be called **final** only after the device candidate additionally passes the physical-device/provider checks that CI cannot faithfully substitute, including P0-12's production-equivalent Gemma run on a supported device, target OEM Accessibility/InputConnection/IME behavior, microphone/SpeechRecognizer behavior, and any real BYOK account/provider routing used for release acceptance.

This split is intentional: requiring physical-device verification before making the APK available would make the physical-device acceptance impossible to perform.

## Current source baseline

Baseline before this stabilization pass:
- branch: `agent/initial-production-foundation`
- source head: `599f97a2e205c233114af0d908928eb462563992`
- prior CI: Actions #271

The exact software-verified device-candidate head is the head that must satisfy the artifact gate above.

**The prior “complete/software-side exhausted” conclusion remains revoked.**
