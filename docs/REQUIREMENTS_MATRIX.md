# Requirements → Implementation → Verification

The project Notion page is the sole product-requirements authority. This matrix exists to prevent omissions and false completion claims.

| Requirement | Implementation | Automated evidence | Physical-device evidence still required |
|---|---|---|---|
| Floating bubble over arbitrary apps | `VoiceBubbleAccessibilityService`, `FloatingBubbleController` | API33/36 service bind + Activity/instrumentation | Third-party app/WebView/Compose editor ergonomics |
| Bubble only while software keyboard is actually visible | InputMethod session + `AccessibilityWindowInfo.TYPE_INPUT_METHOD` gating; `flagRetrieveInteractiveWindows` | build/manifest + API33/36 Accessibility lifecycle | OEM keyboard window reporting during show/hide/animation |
| Tap and immediately record | caller-owned `AudioCaptureSession` | permission/AudioRecord diagnostics, emulator lifecycle | real mic/HAL startup latency |
| Explicit cancel without committing | independent overlay cancel path invalidates active session/finalization job | state/lifecycle build + emulator service checks | touch ergonomics in real apps |
| Drag to bottom to dismiss | bottom dismiss target + haptic/scale feedback + per-input dismissal state | build/emulator launch | gesture/navigation-bar ergonomics on phone |
| True partial transcript | Android partials or `SherpaStreamingEngine` | Sherpa JNI runtime smoke, backend resolver tests | real speaking latency/readability |
| Long dictation must not disappear at provider segment boundaries | Android API33 caller-audio segmented session + `onSegmentResults`; accumulated fallback for OEM recognizers that still end early | transcript overlap/unit tests + API33 compile/runtime surface | OEM recognizer behavior with multi-minute Japanese dictation |
| Natural pause inside long dictation must not prematurely finalize | adaptive `VoiceEndpointDetector`: 1.4 s normal / 2.2 s after long speech | endpoint timing unit tests | preferred real-world pause threshold |
| No fake Whisper/chunk streaming | architecture explicitly separates streaming and replay/final ASR | code/tests/docs | none |
| Partial remains while finalizing | overlay state machine | JVM/build + emulator launch | perceived UX under real latency |
| New recording may start while previous LLM correction is running | capture session state separated from keyed single-thread finalization jobs; old notices never overwrite new partials | state/code review + build/emulator lifecycle | rapid consecutive real dictation |
| Final ASR may differ from live | `SherpaFinalAsrEngine` | same-WAV replay runner | labeled real Japanese winner selection |
| WAV must be complete before final ASR | `AudioCaptureSession` finalization latch; outcome waits for finalized WAV | code + final-ASR replay tests | very long real recording/storage behavior |
| Final accuracy prioritized | human-reference CER/WER tournament | scorer/runner/parser tests | actual speech corpus results |
| One-shot final insertion | editor-generation tracking + keyed finalization jobs + `commitText` | emulator Accessibility bind | real target-app matrix |
| Target changed → clipboard | generation revalidation/fallback | code + emulator service lifecycle | real focus-race cases |
| Preserve tone/register by default | dynamic `CorrectionPrompt` + `CorrectionGuard` | guard regression tests | subjective edge cases on real dictation |
| Select correction direction by checkbox | comma / period / filler removal / polite / business-polite preferences; deterministic post-LLM enforcement for disabled transforms | correction preference/guard unit tests | subjective preferred punctuation/register output |
| Explicit polite/business mode must actually be allowed to rewrite register | widened edit budget only when register rewrite is explicitly selected; runaway expansion still capped | dedicated polite/business guard tests | subjective business-language quality |
| Huge personal dictionary | SQLite, no product cap | DB instrumentation | scale/perf with user's real large dictionary |
| Dictionary affects ASR + correction | ranked bias + relevant context | DB/tests | OEM recognizer response to biasing |
| Dictionary management | CRUD/search/import/export in `AdvancedToolsActivity` | Android runtime round-trip tests | usability with real dictionary |
| Arbitrary BYOK cloud correction | normalized OpenAI-compatible + Anthropic + Gemini endpoints; provider-specific request parsers | protocol/factory/endpoint tests + optional live diagnostic | provider/account-specific behavior |
| BYOK must expose real failure instead of looking successful via raw fallback | dedicated real connection test UI; runtime displays compact correction failure while preserving raw text | build + live diagnostic path | actual provider credentials/quota/errors |
| API URL + key → model list | `ByokModelDiscovery` with OpenAI-compatible list, Anthropic `has_more/last_id`, Gemini `nextPageToken`; selectable/filterable UI | endpoint resolver tests + build | provider-specific list permissions |
| Gemma E2B/E4B local correction | LiteRT-LM 0.14.0 + variant/fingerprint | unit tests + emulator app runtime | phone latency/RAM/thermal/battery |
| Gemma E2B/E4B one-tap acquisition | `CorrectionSetupActivity` → existing reviewed `OfficialModelCatalog` / `OfficialModelInstaller` | pinned URL catalog job + hash/size installer tests | multi-GB phone network/storage behavior |
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
| App identity is explicit | Manifest label + adaptive/round/monochrome launcher icon | `aapt dump badging` label/icon/launcher assertions | launcher appearance on device |
| Stable APK update identity | optional fixed private keystore via `FVB_SIGNING_*`; CI verifies certificate SHA-256 and debug/release equality | signing workflow + `apksigner` assertions | one-time private GitHub Actions secret provisioning |
| Dark mode and display cutouts | day/night system-bar themes + Compose `Surface` content color + `WindowInsets.safeDrawing` on normal/correction screens | lint/build/emulator launch | HONOR/OEM cutout and gesture-nav visual check |
| Low-noise normal UI | quick correction controls + separate correction setup + advanced console | build/emulator | visual/touch assessment on phone |

## Completion rule

A row is **software-complete** when implementation and automated evidence exist. It is **product-validated** only when any required physical-device evidence in the last column has also been collected.

The intended handoff state for this branch is: every software-completable row is closed; only the last column remains. Stable APK signing is implemented in software, but the private keystore itself intentionally remains outside this public repository and must be provisioned once in GitHub Actions secrets.
