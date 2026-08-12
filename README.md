# Floating VoiceBubble

Android 13+ voice input utility centered on a floating accessibility overlay. The product/design requirements live in the linked Notion specification; this README documents the implementation and verification surface rather than replacing that specification.

## Current implementation

- caller-owned 16 kHz mono PCM capture with reusable WAV traces
- true Android `SpeechRecognizer` partial results for the current live path
- N-best retention, endpoint detection, personal-dictionary ASR biasing
- accessibility overlay with transcript-preserving finalization UI
- one-shot final text insertion with editor-generation revalidation
- clipboard fallback when the original field changed/disappeared or insertion failed
- effectively unbounded SQLite personal dictionary with normalized aliases
- minimum-edit correction guard that rejects tone/register-changing rewrites
- BYOK routing for OpenAI-compatible Chat Completions, Anthropic Messages, and Gemini `generateContent`
- on-device LiteRT-LM Gemma correction from imported `.litertlm` models
- offline-mode cloud suppression
- session WAV/N-best/raw/final/timing traces for same-audio benchmarking
- in-app one-click automatic diagnostics with PASS/WARN/FAIL/SKIP and redacted JSON output

## One-click diagnostics

Open **診断 / ベンチマーク → 全自動診断を実行**. It checks, in one operation:

- Android/API support and microphone permission
- `AudioRecord` initialization
- Accessibility Service enabled state
- system and on-device `SpeechRecognizer` availability
- personal dictionary SQLite readability
- trace storage read/write/delete
- configured Gemma model presence and fixed-text inference
- configured BYOK endpoint with a fixed-text live request
- offline-mode cloud suppression
- minimum-edit correction guard behavior

The diagnostic report never includes the API key, recorded audio, or the user's dictionary text. A redacted JSON report can be copied directly from the app.

## Developer verification

Unix/macOS/WSL:

```bash
bash scripts/verify-all.sh
```

Windows PowerShell:

```powershell
.\scripts\verify-all.ps1
```

Without an attached Android target these commands run lint, JVM tests, Debug/Release(R8) builds, APK archive validation, and signature/manifest inspection. With an attached emulator/device they additionally install and launch the app and run connected instrumentation tests.

CI goes further automatically on pull requests: after the static/build verification job it boots Google APIs emulators for API 33 and API 36, installs the generated APK, launches `MainActivity`, enables and binds the disposable emulator's Accessibility Service, and runs Android instrumentation tests covering launcher startup, Android Keystore persistence, personal-dictionary persistence/relevance, and session-trace persistence.

## Deliberately not claimed complete yet

A successful build and emulator suite do not prove the product's ASR-quality goals. The following remain empirical/product work rather than something to fake in CI:

- choose and integrate the best final ASR from same-audio Japanese benchmarks
- productize a truly streaming fully-offline Japanese partial-ASR path rather than depending on the device vendor's on-device `SpeechRecognizer`
- validate explicit Gemma E2B/E4B model acquisition/selection and real-phone CPU/GPU performance
- compare the exact same spoken samples against Gboard, Wispr Flow, Aqua Voice, and candidate final ASRs
- exercise third-party app input targets and OEM/vendor recognizer behavior on real Android hardware

ReazonSpeech Zipformer is treated as an offline/final-ASR benchmark candidate, not mislabeled as a true streaming partial recognizer.
