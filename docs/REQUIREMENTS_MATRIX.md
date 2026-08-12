# Requirements → Implementation → Verification

The project Notion page is the sole product-requirements authority. This matrix exists to prevent omissions and false completion claims.

| Requirement | Implementation | Automated evidence | Physical-device evidence still required |
|---|---|---|---|
| Floating bubble over arbitrary apps | `VoiceBubbleAccessibilityService`, `FloatingBubbleController` | API33/36 service bind + Activity/instrumentation | Third-party app/WebView/Compose editor ergonomics |
| Tap and immediately record | caller-owned `AudioCaptureSession` | permission/AudioRecord diagnostics, emulator lifecycle | real mic/HAL startup latency |
| True partial transcript | Android partials or `SherpaStreamingEngine` | Sherpa JNI runtime smoke, backend resolver tests | real speaking latency/readability |
| No fake Whisper/chunk streaming | architecture explicitly separates streaming and replay/final ASR | code/tests/docs | none |
| Partial remains while finalizing | overlay state machine | JVM/build + emulator launch | perceived UX under real latency |
| Final ASR may differ from live | `SherpaFinalAsrEngine` | same-WAV replay runner | labeled real Japanese winner selection |
| Final accuracy prioritized | human-reference CER/WER tournament | scorer/runner/parser tests | actual speech corpus results |
| One-shot final insertion | editor-generation tracking + `commitText` | emulator Accessibility bind | real target-app matrix |
| Target changed → clipboard | generation revalidation/fallback | code + emulator service lifecycle | real focus-race cases |
| Preserve tone/register | `CorrectionPrompt` + `CorrectionGuard` | guard regression tests | subjective edge cases on real dictation |
| Huge personal dictionary | SQLite, no product cap | DB instrumentation | scale/perf with user's real large dictionary |
| Dictionary affects ASR + correction | ranked bias + relevant context | DB/tests | OEM recognizer response to biasing |
| Dictionary management | CRUD/search/import/export in `AdvancedToolsActivity` | Android runtime round-trip tests | usability with real dictionary |
| Arbitrary BYOK cloud correction | OpenAI-compatible + Anthropic + Gemini | protocol/factory tests + optional live diagnostic | provider/account-specific behavior |
| Gemma E2B/E4B local correction | LiteRT-LM 0.14.0 + variant/fingerprint | unit tests + emulator app runtime | phone latency/RAM/thermal/battery |
| Full offline mode | Sherpa streaming + local Gemma/none; no cloud fallback | resolver/diagnostic tests | airplane-mode real speech session |
| Offline must never silently cloud-fallback | resolver hard requirement | unit + diagnostic policy check | network trace optional confirmation |
| Free/negligible operating cost | local paths + BYOK choice | architecture | actual provider pricing is external/time-varying |
| Official model acquisition | source-controlled catalog + safe downloader/extractor | catalog + archive tests | multi-GB phone network/storage behavior |
| Model updates must not destroy good model | atomic backup/promote/recover | JVM crash-recovery tests | process-kill during real large download/import |
| Same-audio benchmark | saved WAV + replay tournament | runner/scorer tests | collect representative real utterances |
| Japanese colloquial/fast/EN-JP/proper nouns/corrections | benchmark protocol defines corpus strata | protocol artifact | record and label corpus |
| Compare Gboard/Wispr/Aqua | external result TSV store + scorer | parser/store runtime tests | run actual competitor products on same utterances |
| Do not fake accuracy from disagreement | ground truth required for CER/WER | scorer/runner invariants | none |
| Advanced diagnostics | `SelfDiagnostics` redacted report | instrumentation | OEM-only probes where applicable |
| One-command developer verification | `scripts/verify-all.sh/.ps1` | CI equivalence | optional local connected-device run |
| CI install/launch/runtime checks | GitHub Actions API33/36 matrix | workflow | physical device still distinct |
| 16 KiB native compatibility | CI `zipalign -P 16` + ABI checks | workflow | target phone install |
| Low-noise normal UI | normal settings + separate advanced console | build/emulator | visual/touch assessment on phone |

## Completion rule

A row is **software-complete** when implementation and automated evidence exist. It is **product-validated** only when any required physical-device evidence in the last column has also been collected.

The intended handoff state for this branch is: every software-completable row is closed; only the last column remains.
