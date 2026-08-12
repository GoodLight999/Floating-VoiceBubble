# Floating VoiceBubble

Floating VoiceBubble is an Android voice-input tool designed around four non-negotiable properties: true real-time partial transcription, high final accuracy, negligible running cost, and no unexplained waiting state.

## Source of truth

The project Notion page is the **only authoritative requirements/design specification**. Repository documents explain implementation choices and operational details; when they differ, Notion wins.

## Architecture

The app is intentionally a single Android application module. The boundaries inside it are behavioral rather than service-shaped:

- `speech`: one PCM capture feeds live recognition and preserves the exact utterance for final evaluation.
- `accessibility`: owns the overlay and final text insertion through Android's accessibility input connection.
- `correction`: strict minimum-edit correction through BYOK OpenAI-compatible endpoints or on-device Gemma.
- `dictionary`: local SQLite store with no artificial small dictionary ceiling; high-priority entries bias ASR and relevant entries reach correction.
- `trace`: utterance audio and timing/result metadata for reproducible same-audio benchmarks.
- `overlay`: fixed-size, non-jumping live transcript UI where the transcript itself is the progress indicator.

Minimum Android version is 13 (API 33). API 33 lets the app use an accessibility input method for final `commitText` and feed caller-captured audio to `SpeechRecognizer`, keeping live recognition and recorded benchmark audio aligned.

## Modes

**Cloud/BYOK:** Android live recognition + configurable OpenAI-compatible correction. The API key remains in Android Keystore-backed encrypted storage.

**Offline:** Android on-device `SpeechRecognizer` + an imported Gemma 4 E2B/E4B LiteRT-LM model. Offline mode never silently falls back to a cloud recognizer or cloud corrector.

`sherpa-onnx` remains an empirical ASR candidate, especially for offline final recognition. A model is only allowed to drive the live partial UI when it is genuinely streaming; chunked/offline re-decodes are not labeled as partial streaming.

## Build

CI uses JDK 17, Gradle 9.5, AGP 9.3, and Android API 37.

```bash
gradle :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

## Setup on device

1. Grant microphone permission.
2. Enable **Floating VoiceBubble** under Android Accessibility settings.
3. Choose recognition/correction mode in the app.
4. For offline correction, import a compatible `.litertlm` Gemma model.
5. Tap the floating bubble to dictate; tap again to finish manually. Silence endpointing can finish automatically.
