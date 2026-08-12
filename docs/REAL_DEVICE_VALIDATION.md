# Real Device Validation

This document is deliberately limited to evidence CI/emulators cannot honestly produce. If a task can be automated without a physical phone, fix the automation instead of adding it here.

## 0. Build under test

Record before testing:

- Git commit SHA
- APK SHA-256
- Android version / security patch
- phone model / SoC / RAM
- available storage
- battery level and thermal state
- selected ASR/Gemma models

Run `全自動診断` and preserve the redacted report.

## 1. Installation and model acquisition

- Install the validated debug APK.
- Open `管理`.
- Download one Nemotron model from the official catalog.
- Download ReazonSpeech.
- Download Gemma E2B; optionally E4B if storage permits.
- Confirm interrupted/retried downloads do not remove previously installed working models.
- Confirm storage-full and network-loss errors are explicit and recoverable.

Record download duration and any OEM storage/network anomalies. This is not a model-accuracy test.

## 2. Permissions / Accessibility / overlay

Verify:

- microphone permission request and denial/retry
- Accessibility service enable/disable/rebind
- bubble drag/tap behavior
- rotation / app switching
- screen off/on
- service/process recreation
- no duplicate bubbles
- no invisible touch-blocking overlay

## 3. Input target matrix

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

Document app/version for failures.

## 4. Microphone and endpoint behavior

Test:

- quiet room
- normal room noise
- fan/air conditioner
- near/far microphone
- fast speech
- long phrase near maximum duration
- pause inside a sentence
- silence/no speech

Record:

- perceived start latency
- premature endpoint count
- failure to endpoint
- AudioRecord/HAL errors
- dropped-audio warnings

## 5. True-streaming offline path

Enable airplane mode and disable Wi-Fi.

- Select Sherpa/Nemotron streaming.
- Speak naturally and verify partial text changes **before** utterance end.
- Confirm final insertion succeeds without network.
- If correction is enabled, use Gemma or disable correction.
- Confirm no hidden cloud fallback or network-required error after successful setup.

Repeat for each Nemotron chunk intended for benchmarking.

## 6. Same-audio accuracy corpus

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

## 7. Gboard / Wispr Flow / Aqua Voice

Use `競合雛形` from the advanced screen.

For each system:

- capture the transcript from the corresponding real product
- use the same source utterance as closely as the product allows
- note any non-identical audio path
- import transcripts
- rerun tournament

Do not hand-correct competitor output before import.

## 8. Gemma E2B / E4B performance

For each installed variant and backend mode:

- warm model once
- run repeated realistic corrections
- record median / p95 correction latency
- observe peak app memory
- observe sustained thermal behavior
- observe battery impact over a meaningful batch
- confirm GPU→CPU fallback behavior where applicable

Also verify correction quality invariants:

- no politeness rewrite
- no first-person rewrite
- no dialect/register cleanup
- no gratuitous paraphrase
- proper nouns/dictionary corrections improve where expected

## 9. Final-ASR latency tradeoff

Compare live final vs ReazonSpeech on the same labeled sessions.

Record:

- content CER delta
- strict CER delta
- finalization delay
- RTF
- thermal impact

Only choose ReazonSpeech as the default when the real accuracy gain justifies real-phone latency.

## 10. Release gate

The implementation can leave Draft only when:

- no blocker remains in the input-target matrix
- offline path works in airplane mode
- human-labeled benchmark results exist
- selected streaming chunk is evidence-backed
- final-ASR default is evidence-backed
- Gemma default/fallback is evidence-backed
- external comparison has been run if the “beats Gboard/Wispr/Aqua” claim is going to be made

Until then, say exactly what has been measured and do not claim superiority from CI or model reputation.
