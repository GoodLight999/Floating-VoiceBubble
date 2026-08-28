# Floating VoiceBubble — provider reasoning capability record

Checked against official provider documentation on **2026-08-28**.

This document is the evidence record behind `ReasoningCapabilities`, provider request adapters, model-selection UI, and redacted diagnostics. The product rule is conservative: only controls whose semantics are known are exposed; unknown compatible APIs and unknown model generations stay on provider/model default.

## OpenAI native API

Primary sources:

- https://developers.openai.com/api/docs/guides/latest-model
- https://developers.openai.com/api/docs/models/gpt-5.6-sol
- https://developers.openai.com/api/docs/models/gpt-5.6-terra
- https://developers.openai.com/api/docs/models/gpt-5.6-luna
- https://developers.openai.com/api/docs/guides/latest-model?model=gpt-5.2

Findings used by Floating VoiceBubble:

- GPT-5.6 Sol/Terra/Luna expose `none`, `low`, `medium`, `high`, `xhigh`, and `max` reasoning effort.
- The Chat Completions surface uses top-level `reasoning_effort`; the Responses API uses `reasoning.effort`.
- Floating VoiceBubble currently uses Chat Completions for the generic text-correction adapter, therefore its native OpenAI wire field is `reasoning_effort`.
- Provider/model default means omitting the optional reasoning field. Voice input is latency-sensitive, so the app never silently upgrades provider default to a high reasoning mode.

Implementation contract:

- `gpt-5.6*`: default plus `none/low/medium/high/xhigh/max`.
- Earlier explicitly recognized GPT-5 generations expose only the levels documented for that generation.
- An unrecognized OpenAI model exposes provider default only until its capability is verified.

## OpenRouter

Primary source:

- https://openrouter.ai/docs/guides/best-practices/reasoning-tokens

Findings used by Floating VoiceBubble:

- OpenRouter normalizes reasoning under a `reasoning` object.
- Gateway effort names are `max`, `xhigh`, `high`, `medium`, `low`, `minimal`, and `none`.
- `GET /api/v1/models` may return a per-model `reasoning` object containing `supported_efforts`, `default_effort`, `default_enabled`, `supports_max_tokens`, and `mandatory`.
- When `mandatory=true`, disable/`none` must not be offered or sent.
- Therefore a manually typed model whose metadata has not been retrieved must not be presented with a fabricated full reasoning ladder.

Implementation contract:

- Wire field: `reasoning.effort`.
- Retrieved model metadata narrows the UI to the exact returned effort set.
- Unverified/manual model IDs use provider/model default until capability metadata is known.

## Z.AI

Primary sources:

- https://docs.z.ai/guides/capabilities/thinking-mode
- https://z.ai/blog/glm-5.3

Findings used by Floating VoiceBubble:

- GLM-4.7 supports turn-level thinking control through `thinking.type=enabled|disabled`; the general Z.AI thinking documentation also describes disabling thinking for GLM-5.2/5.1/5 and the GLM-4.7 series.
- **GLM-5.3 changes this contract:** thinking cannot be disabled. Its API accepts `thinking.type=enabled` and `reasoning_effort=low|high|max`, with `max` as the documented default.
- A client that carries `thinking.type=disabled` forward to GLM-5.3 will fail; Z.AI explicitly documents migration to enabled thinking plus a lower effort when latency is desired.

Implementation contract:

- Known binary-thinking generations (GLM-4.5/4.6/4.7 and GLM-5/5.1 families recognized by the app): provider default / thinking OFF / thinking ON only.
- GLM-5.3 family: provider default / low / high / max; explicit low/high/max sends `thinking.type=enabled` plus the matching `reasoning_effort`.
- GLM-5.3 never exposes an OFF choice.
- Unknown future Z.AI model IDs use provider/model default only rather than inheriting either historical contract by guess.

## Anthropic native Messages API

Primary sources:

- https://platform.claude.com/docs/en/build-with-claude/effort
- https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking
- https://platform.claude.com/docs/en/claude_api_primer

Findings used by Floating VoiceBubble:

- Current supported Claude models use `output_config.effort`; the documented effort vocabulary is `low`, `medium`, `high`, with `xhigh` and/or `max` only on models whose table explicitly supports them.
- Adaptive thinking uses `thinking: {"type":"adaptive"}`. Where adaptive thinking is available, effort is the recommended control for reasoning depth.
- Manual extended-thinking generations are not equivalent to the adaptive effort ladder. Floating VoiceBubble deliberately does not fabricate a depth mapping for such models.
- `max` and `xhigh` availability is model-specific; the capability table must remain model-specific.

Implementation contract:

- Adaptive models: explicit depth emits `thinking.type=adaptive` plus `output_config.effort=<documented-level>`.
- Explicit thinking-off is offered only where the recognized model contract permits it.
- Unsupported/legacy models stay on provider default rather than receiving guessed fields.

## Google Gemini native GenerateContent API

Primary sources:

- https://ai.google.dev/gemini-api/docs/generate-content/thinking
- https://ai.google.dev/gemini-api/docs/latest-model
- https://ai.google.dev/gemini-api/docs/models/gemini-3.7-flash

Findings used by Floating VoiceBubble:

- Gemini 2.5 on the GenerateContent API uses numeric thinking budgets; disabling is model-dependent (2.5 Flash can use zero whereas 2.5 Pro cannot be fully disabled).
- Gemini 3 uses named `thinkingLevel` values instead of treating a numeric budget as a precise depth control.
- Gemini 3.7 Flash supports exactly `low`, `medium`, and `high`; `minimal` is explicitly unsupported and returns an error.
- Gemini 3.1 Pro supports `low`, `medium`, `high`, while Gemini 3 Pro exposes a narrower set.

Implementation contract:

- 2.5 family: send `generationConfig.thinkingConfig.thinkingBudget` only for recognized models/choices.
- 3.x family: send `generationConfig.thinkingConfig.thinkingLevel` only for a documented level.
- 3.7 Flash never offers or sends `minimal`.
- Unknown Gemini IDs use provider/model default until verified.

## Generic OpenAI-compatible endpoints

There is no portable standard for proprietary reasoning extensions beyond the core compatible request shape.

Implementation contract:

- Unknown hosts receive no `reasoning_effort`, `reasoning`, `thinking`, `do_sample`, or other provider-specific reasoning knob merely because they accept `/v1/chat/completions`.
- The UI exposes only provider/model default.
- If a provider-specific non-reasoning convenience is rejected, a bounded portable-core retry may remove that convenience. An explicitly selected reasoning control is **not** silently removed and retried, because that would make a successful test misrepresent the user's chosen runtime configuration.

## Verification mapping

The following tests lock the evidence above to the actual wire body:

- `ReasoningCapabilitiesTest` — model/provider choice sets and normalization.
- `ReasoningWireDescriptorTest` — redacted effective wire fields shown to diagnostics.
- `OpenAiWireBodyTest` — native OpenAI, OpenRouter, Z.AI binary thinking, GLM-5.3 effort, and unknown-compatible request JSON.
- `NativeProviderWireBodyTest` — Anthropic and Gemini request JSON.
- `ByokModelDiscoveryReasoningTest` — model-catalog capability narrowing.
- UI instrumentation — unsupported effort choices are not presented as distinct controls.

A provider documentation change requires updating this file, the capability layer, and exact-body tests in the same change. A green network probe alone is not evidence that the app sent the intended reasoning semantics.
