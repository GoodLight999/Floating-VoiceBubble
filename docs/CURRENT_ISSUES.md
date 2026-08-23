# Floating VoiceBubble — Current blocking issues

Updated: 2026-08-23

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
Status: OPEN

Observed:
- Actual voice correction can spend a long time and then fall back.
- The user reports this happens regardless of selected model.
- The short “real correction test” can finish immediately with reasoning at provider default while the real voice path times out.

Known implementation mismatch:
- Test path calls `TextCorrector.correct()` directly on a small fixed probe.
- Runtime path uses `FinalizationEngine`, a larger prompt (RAW + N-best + context + dictionary), bounded retry, app-profile effective settings, and outer watchdog.
- Therefore the current test is not production-equivalent.

Required fix:
- Make a production-equivalent correction probe that uses the same effective settings resolution, request construction, provider adapter, timeout/retry policy, post-processing, integrity checks, and finalization path as real dictation.
- Keep a separate fast network/provider probe only if clearly labelled as such.
- Measure and expose per-attempt latency and exact failure stage.
- No silent timeout fallback.

Acceptance:
- Mock provider tests cover immediate success, slow success, read timeout, 4xx unsupported-parameter retry, empty response, and late response.
- Device diagnostic distinguishes provider reachability from production-equivalent correction.
- Runtime timeout/fallback reason is visible and persisted in the redacted trace.

### P0-03 — Reasoning control is not capability-aware
Status: OPEN

Observed:
- UI offers a generic 8-level reasoning ladder for every provider/model.
- Provider/model APIs do not share one reasoning control vocabulary or capability set.
- Current code maps Z.AI levels to a binary `thinking.enabled/disabled`, so multiple displayed levels can produce the same wire request.

Required fix:
- Introduce a provider/model capability layer.
- UI must show only semantics the selected endpoint/model can actually express.
- OpenAI, OpenRouter, Z.AI, Anthropic, Gemini, and generic OpenAI-compatible endpoints must each have an explicit adapter and tests.
- Unsupported reasoning controls must be omitted rather than sprayed at generic compatible endpoints.
- Generic/custom endpoints should default to “provider default” unless capability is known.

Acceptance:
- Snapshot/body tests assert the exact JSON sent for every supported provider family and every exposed reasoning choice.
- UI capability test proves unsupported choices are not offered as if they were distinct.
- Effective wire setting is shown in diagnostics without secrets.

### P0-04 — Model/reasoning selection test can use unsaved values while runtime uses old saved values
Status: OPEN

Observed in current code:
- `CorrectionSetupActivity` stores model/reasoning in Compose draft state.
- “このモデルで実補正テスト” uses the draft values directly.
- Actual voice input reloads persisted `SettingsStore` values.
- The user can therefore successfully test one model/reasoning configuration and then unknowingly run another.

Required fix:
- Selecting a model/reasoning option must either persist immediately or the test must explicitly save and then read back the exact effective configuration before executing.
- Runtime and test must display the same effective model/provider/reasoning summary.

Acceptance:
- Instrumentation test: select model/reasoning → run test → begin production-equivalent correction; both observe the same persisted values.

### P0-05 — Silent/insufficiently visible fallback
Status: OPEN

Observed:
- User sees long processing followed by apparent rollback/fallback with insufficient explanation.
- A short-lived bubble status is not adequate evidence for a failure that may take tens of seconds.

Required fix:
- Fallback must never be silent.
- Keep a visible failure state long enough to read and make the last failure available from the main screen until the next successful correction.
- Persist a redacted “last correction result” with provider, model, effective reasoning mode, elapsed time, attempt count, HTTP/error class, LM response/change status, integrity-check result, and fallback source.

Acceptance:
- Timeout/error instrumentation verifies the user receives an explicit failure message and RAW fallback is labelled as such.
- Diagnostic screen can reproduce the last failure without exposing API keys or sensitive surrounding text by default.

### P0-06 — Misleading “安全ガード” and normal-text rejection
Status: OPEN

Observed:
- “安全ガード” sounds like content-policy censorship, but `CorrectionGuard` is actually an output-integrity/user-preference check.
- Ordinary text can be rejected for line-break/filler/punctuation invariants or coarse expansion/contraction bounds.
- The user has seen the guard trigger on normal text.

Required fix:
- Remove the phrase “安全ガード” from all user-facing UI/messages.
- There is no content-safety classifier in this feature and the UI must not imply one.
- Reduce the internal checker to narrow output-integrity invariants: empty/invalid output, catastrophic runaway/truncation, and actions explicitly forbidden by user settings.
- Do not reject an otherwise valid LM correction merely because allowed formatting or ASR repair changed the text substantially.
- Map internal rejection reasons to concrete Japanese explanations.

Acceptance:
- Regression corpus of ordinary Japanese dictation never receives a policy-like rejection.
- Tests cover punctuation-only changes, filler removal, multiple line breaks, strong ASR repair, register preservation, long corrections, and actual runaway output.

### P0-07 — Line breaks still fail or feel context-blind
Status: OPEN

Observed:
- User reports requested line breaks are still not reliably produced.
- Previous deterministic fallbacks produced semantically bad splits and were partially reworked, but real-device behavior is still unacceptable.

Required fix:
- Re-evaluate line-break responsibility between LM and deterministic postprocessor.
- Use a regression corpus containing the user’s reported sentences, Japanese with only `、`, no punctuation, multiple topics, short single sentences, lists, and conversational fragments.
- Never insert a width-only arbitrary break.
- When “適宜改行” is enabled, production-equivalent tests must demonstrate meaningful paragraph boundaries.

Acceptance:
- Corpus tests specify expected/forbidden boundaries rather than just “contains a newline”.
- Real correction trace distinguishes model-inserted vs app-inserted line breaks.

### P0-08 — Correction prompt is ad hoc and lacks documented research basis
Status: OPEN

Observed:
- Current `CorrectionPrompt` was hand-authored and cannot honestly be described as being derived from a documented literature review.

Required fix:
- Research ASR error correction / generative error correction, N-best rescoring/post-editing, contextual biasing, punctuation restoration, disfluency/filler handling, and constrained/minimal-edit LLM post-editing.
- Record primary sources and the specific design principle taken from each.
- Rebuild the prompt from those principles; do not cargo-cult wording from papers.
- Keep “preserve register / do not invent facts / use N-best+context+dictionary / perform explicit formatting requests” as tested behavioral contracts.
- Benchmark candidate prompts on a fixed Japanese corpus rather than selecting by intuition.

Acceptance:
- `docs/CORRECTION_PROMPT_RESEARCH.md` contains sources → design principles → prompt decisions → evaluation corpus/results.
- Prompt regression/benchmark covers ASR repair, hallucination resistance, register preservation, punctuation, fillers, line breaks, and no-op cases.

### P0-09 — Settings UX is duplicated and structurally confusing
Status: OPEN

Observed:
- Current product has a main/normal settings surface, a “詳細” surface, model setup, and “管理・検証”; settings and concepts overlap.
- User describes this as a maze and cannot tell which control actually affects runtime.
- The current “詳細” button has no clear user value.

Required fix:
- One canonical user settings surface for everyday behavior.
- Remove the generic “詳細” mode and duplicated controls.
- Separate only task-specific subpages: correction model/API, personal dictionary, per-app overrides, offline model management, diagnostics.
- Internal benchmarks/developer verification must not be mixed into ordinary user settings.
- No nested tiny scrollable control region; settings must use the full usable screen.

Acceptance:
- There is exactly one editable instance for each everyday setting.
- UI test verifies no duplicate control with conflicting state.
- First viewport makes current correction model, correction on/off, reasoning capability/value, ASR-repair strength, and formatting behavior understandable.

### P0-10 — User-facing terminology is developer jargon or ambiguous
Status: OPEN

Observed:
- `ASR` is exposed to users without explanation.
- Chips labelled `、`, `。`, and `フィラー` are ambiguous; `フィラー` especially does not state whether checking it removes or inserts them.
- Developer phrases such as “真のストリーミングASR”, benchmarking rationale, implementation choices, and development-policy prose are present in user-facing settings.

Required fix:
- Replace `ASR` in normal UX with `音声認識`; technical names only where needed for model management.
- Replace punctuation/filler shorthand with action labels, e.g. `読点「、」を追加`, `句点「。」を追加`, `「えー」「あのー」などを削除`.
- Explain outcomes, not implementation ideology.
- Remove development-policy/architecture prose from the app. Keep it in docs/Notion only.

Acceptance:
- User-facing string audit finds no unexplained `ASR`, “安全ガード”, MVP/development-policy language, or ambiguous `フィラー` toggle.
- UI screenshots are reviewed for clarity, not merely presence of controls.

### P0-11 — App-specific overrides can make effective runtime behavior differ from global UI
Status: OPEN

Observed:
- Runtime applies `AppCorrectionProfile` after loading global settings.
- Main UI currently shows global values, not necessarily the effective values for the app where dictation is used.
- This can make a setting appear ignored.

Required fix:
- Make per-app overrides explicit and sparse.
- When a recent/current target app has overrides, show that the app overrides global behavior and summarize the effective differences.
- Diagnostics and production-equivalent tests must report effective settings after profile application.

Acceptance:
- Tests prove global vs per-app effective settings and visible override indication agree.

### P0-12 — Gemma download is brittle and external models are duplicated
Status: FIXED

Observed:
- Official E2B/E4B downloads used a one-shot multi-gigabyte HTTP stream with a 60-second read timeout and no Range resume.
- A transient stall could discard gigabytes of progress and force a complete restart.
- The older local-import route copied an already-owned `.litertlm` into app-private storage, needlessly consuming another 2–4 GB.

Required fix:
- Official Gemma downloads must keep a stable partial file, resume with HTTP Range, tolerate transient I/O/408/429/5xx failures, and never discard valid completed bytes merely because one connection stalls.
- Retry must be bounded and visible; it must never become an infinite loop.
- Validate exact expected byte length and SHA-256 before promoting a partial download.
- Promotion must be an atomic rename on the same filesystem, not a second model-sized copy.
- Existing seekable `.litertlm` files selected through Android's document picker must be usable in place with a persisted read grant; no app-private duplicate is created.
- Runtime, diagnostics, and settings must understand both app-private file paths and persisted external document references.
- Releasing an external model must not delete the user's source file.

Acceptance:
- JVM regression: an injected mid-stream I/O failure leaves the partial bytes intact, the next request resumes from that exact offset, and the reconstructed file matches the source byte-for-byte.
- JVM regression: a server that ignores Range and returns HTTP 200 is reused as a clean restart without an extra request.
- JVM regression: malformed Content-Range is rejected without corrupting the existing partial and repeated early EOF stops after bounded retries.
- Device/instrumentation: select a seekable external `.litertlm`, restart the app, and verify the same source is still accessible without an app-private model copy.
- Device/instrumentation: deleting/moving the selected external source produces an explicit unavailable-model state rather than a silent fallback.
- Full production-equivalent Gemma correction succeeds from the external reference on at least one supported device/backend.

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
Status: OPEN

Required:
- Review the fixed 30 s first attempt + 10 s retry + 45 s outer watchdog.
- Do not automatically retry a no-op if the first response is already valid for the requested operations.
- Provider/model capability and task type should determine whether reasoning is worth enabling.
- Avoid non-streaming long-thinking defaults for a latency-sensitive voice-editing task unless the user explicitly asks for them.

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

**That prior “complete/software-side exhausted” conclusion is explicitly revoked by this ledger.**
