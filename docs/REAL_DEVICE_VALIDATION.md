# Real Device Validation

This document is deliberately limited to evidence CI/emulators cannot honestly produce. If a task can be automated without a physical phone, fix the automation instead of adding it here.

## 0. Build under test

Record before testing:

- Git commit SHA
- APK SHA-256
- APK signing certificate SHA-256
- Android version / security patch
- phone model / SoC / RAM
- available storage
- battery level and thermal state
- selected ASR/Gemma models

Run `全自動診断` and preserve the redacted report.

Before replacing an installed build, confirm whether the new APK is signed by the same stable certificate. A runner-local debug-signed APK is not a stable-update artifact.

## 1. Installation, app identity, and model acquisition

- Install the validated APK.
- Confirm the launcher shows **Floating VoiceBubble** and the dedicated VoiceBubble icon rather than a generic/default icon.
- If testing update continuity, install the next build over the previous build without uninstalling and confirm Android accepts the update.
- Open `API / Gemma` and use the one-tap Gemma E2B installer; repeat with E4B if storage permits.
- Open `管理` and download one Nemotron model plus ReazonSpeech from the official catalog.
- Confirm interrupted/retried downloads do not remove previously installed working models.
- Confirm storage-full and network-loss errors are explicit and recoverable.

Record download duration and any OEM storage/network anomalies. This is not a model-accuracy test.

## 2. Permissions / Accessibility / keyboard-visible bubble

Verify:

- microphone permission request and denial/retry
- Accessibility service enable/disable/rebind
- focus a text field and **do not** open the software keyboard: bubble must remain hidden
- open the software keyboard: bubble must appear
- close the software keyboard while the text field stays focused: bubble must disappear
- close the keyboard during recording: microphone capture must not continue invisibly
- reopen the keyboard: one bubble only; no duplicate overlay
- bubble drag/tap behavior
- drag the bubble into the bottom dismiss zone: visible dismiss target + haptic/scale feedback, then release to close it for that input
- move to another fresh input after dismissal: bubble can appear again
- rotation / app switching
- screen off/on
- service/process recreation
- no invisible touch-blocking overlay

Test at least the device's default keyboard and one alternate IME if available.

## 3. Cancel vs finalize semantics

From a text field with the bubble visible:

1. Start speaking, then tap **キャンセル**. No partial/final text from that utterance may be inserted.
2. Start speaking again and finalize normally. The result must insert once.
3. Finalize an utterance while BYOK/Gemma correction is intentionally slow, then cancel while the correction is pending. That pending result must not later insert itself.
4. Start another utterance after cancellation and verify the session is clean.

The stop/finalize affordance and explicit cancel must remain distinct.

## 4. Input target matrix

For each target, type before/after with the normal keyboard and then VoiceBubble:

- simple native text field
- multiline field
- Chrome/Firefox web input
- WebView-based app
- Compose text field
- chat/messaging app
- note editor
- browser address/search field

For each, verify:

- cursor location
- one-shot insertion only
- no duplicated text
- target switch during recognition triggers clipboard fallback
- same-app field switch is detected
- clipboard contains **final guarded text**, not stale partial text
- closing the input/keyboard does not leave a hidden bubble or microphone session

Document app/version for failures.

## 5. Microphone, segmented recognition, and endpoint behavior

Test:

- quiet room
- normal room noise
- fan/air conditioner
- near/far microphone
- fast speech
- at least 2–5 minutes of continuous dictation
- several natural sentence-internal pauses around 1–2 seconds during long dictation
- a deliberate silence longer than the long-dictation endpoint threshold
- silence/no speech

For Android/OEM SpeechRecognizer modes specifically:

- speak long enough to force provider segmentation if the OEM/provider imposes it
- verify each provider segment remains in the final transcript
- verify no boundary phrase is duplicated when adjacent provider segments overlap
- verify an ordinary provider `final`/timeout in the middle does not terminate the VoiceBubble utterance while the caller-owned audio source remains active

Expected local VAD behavior:

- normal short utterance: sustained silence around 1.4 s may finalize
- after long speech: a natural 1.8 s pause should survive; sustained silence around 2.2 s should finalize

Record:

- perceived start latency
- premature endpoint count
- failure to endpoint
- AudioRecord/HAL errors
- dropped-audio warnings
- any segment loss/duplication

## 6. Correction-in-flight concurrency

Use a deliberately slow BYOK model/network so correction takes several seconds.

1. Speak utterance A and finalize it.
2. While A is still being corrected, immediately start speaking utterance B.
3. Verify B's live partial remains visible and continues updating.
4. When A completes, it may insert into the still-valid target, but A's completion UI must not overwrite B's live partial UI.
5. Finalize B and verify both utterances are preserved and inserted once in order.
6. Repeat while switching the target field between A and B; stale-target output must go to clipboard rather than the wrong editor.

## 7. BYOK model discovery and real connection test

For every provider actually intended for use:

- OpenAI-compatible endpoint (for example the configured compatible provider)
- Anthropic official API if used
- Gemini official API if used

Verify:

- entering a supported base URL does not require manually typing the full generation endpoint
- `モデル一覧を取得` returns models allowed by that key/account
- provider pagination does not truncate a multi-page list
- filtering and selecting a model populates the selected model ID
- `保存して接続テスト` sends a real correction request and reports success/failure explicitly
- invalid key, invalid model, quota/rate-limit, and unreachable endpoint do **not** look like a silent successful correction
- after a failed correction during normal dictation, the raw recognized text is preserved and the failure state is visible

Never put real API keys in screenshots or shared diagnostic reports.

## 8. Correction-direction controls

Test the same utterance against each control independently.

- `、` OFF: model must not add new Japanese commas
- `。` OFF: model must not add new Japanese periods
- `フィラー除去` OFF: filler content must not disappear merely because the model prefers cleaner prose
- `フィラー除去` ON: obvious meaningless fillers may be removed without summarizing content
- `丁寧語` ON: natural desu/masu conversion is allowed and should actually occur
- `ビジネス敬語` ON: business-register expansion is allowed without adding greetings, apologies, facts, or conclusions that were never spoken
- both register controls OFF: original rough/casual/register style must remain

Also verify `丁寧語` and `ビジネス敬語` are mutually exclusive in the UI.

## 9. Dark mode, cutout, and system bars

Test light and dark system themes on a device with a display cutout/front camera area and gesture navigation.

Verify:

- top title text has correct contrast in both themes
- no title/control is hidden behind the status bar or camera cutout
- correction setup screen obeys the same safe drawing area
- system status/navigation bar icon contrast is readable
- no bottom action is hidden under the gesture/navigation region
- launcher themed icon is legible if the launcher supports themed icons

Capture screenshots only if they do not reveal API keys or private text.

## 10. True-streaming offline path

Enable airplane mode and disable Wi-Fi.

- Select Sherpa/Nemotron streaming.
- Speak naturally and verify partial text changes **before** utterance end.
- Confirm final insertion succeeds without network.
- If correction is enabled, use Gemma or disable correction.
- Confirm no hidden cloud fallback or network-required error after successful setup.

Repeat for each Nemotron chunk intended for benchmarking.

## 11. Same-audio accuracy corpus

Follow `BENCHMARK_PROTOCOL.md`.

Target a mixed corpus, not only clean reading. Preserve at least enough samples to expose differences across:

- colloquial Japanese
- fast speech
- self-corrections/repetitions
- JP/EN code-switching
- difficult proper nouns
- long dictation
- dictionary terms
- realistic noise

Export human-reference TSV and run the tournament after labeling.

## 12. Gboard / Wispr Flow / Aqua Voice

Use `競合雛形` from the advanced screen.

For each system:

- capture the transcript from the corresponding real product
- use the same source utterance as closely as the product allows
- note any non-identical audio path
- import transcripts
- rerun tournament

Do not hand-correct competitor output before import.

## 13. Gemma E2B / E4B performance

For each installed variant and backend mode:

- warm model once
- run repeated realistic corrections
- record median / p95 correction latency
- observe peak app memory
- observe sustained thermal behavior
- observe battery impact over a meaningful batch
- confirm GPU→CPU fallback behavior where applicable

Verify both one-tap downloaded E2B/E4B models pass normal correction. Also verify correction quality invariants with all explicit register controls OFF:

- no politeness rewrite
- no first-person rewrite
- no dialect/register cleanup
- no gratuitous paraphrase
- proper nouns/dictionary corrections improve where expected

Then enable polite/business mode separately and verify the requested register conversion is not incorrectly rejected by the minimum-edit guard.

## 14. Final-ASR latency tradeoff

Compare live final vs ReazonSpeech on the same labeled sessions.

Record:

- content CER delta
- strict CER delta
- finalization delay
- RTF
- thermal impact

Only choose ReazonSpeech as the default when the real accuracy gain justifies real-phone latency.

## 15. Release gate

The implementation can leave Draft only when:

- stable signing certificate is provisioned for the install/update artifact path
- no blocker remains in the input-target matrix
- keyboard-visible-only bubble behavior is confirmed on the target OEM/IME
- explicit cancel and bottom-drag dismiss are reliable
- multi-minute dictation has no segment loss/duplication or premature endpointing
- correction-in-flight → next-recording concurrency preserves both utterances
- BYOK model discovery and real connection test pass for the intended provider/account
- dark mode/cutout rendering is correct on the target phone
- offline path works in airplane mode
- human-labeled benchmark results exist
- selected streaming chunk is evidence-backed
- final-ASR default is evidence-backed
- Gemma default/fallback is evidence-backed
- external comparison has been run if the “beats Gboard/Wispr/Aqua” claim is going to be made

Until then, say exactly what has been measured and do not claim superiority from CI or model reputation.
