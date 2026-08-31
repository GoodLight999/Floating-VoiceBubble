# Floating VoiceBubble — correction quality corpus

Updated: 2026-08-28

This corpus is the fixed acceptance surface for correction quality. It is intentionally about **ASR post-editing**, not generic writing quality. The app must preserve what the speaker said and only repair evidence-backed recognition errors or apply explicitly enabled formatting/register operations.

The executable copy of the dimensions and representative inputs lives in `CorrectionQualityContractTest.kt`. Other focused tests cover guard behavior, line-break placement, provider bodies, and production-equivalent transport.

## Required dimensions

| Dimension | Representative contract | Required invariant |
| --- | --- | --- |
| Casual register | `これマジで使いやすいんだよね` | Casual phrasing is not normalized or made polite unless the user requested it. |
| Rough register | `そこ勝手に変えるなって言っただろ` | Roughness is speaker content, not an error. |
| Polite register | `こちらの設定で問題ありません` | Existing politeness is preserved without gratuitous rewriting. |
| ASR homophone / segmentation repair | `音声入力の取り合い…` + N-best `聞き取りAI…` | Strong repair may change multiple characters/words only when N-best/dictionary/context supports the candidate. |
| N-best disambiguation | RAW `取り合いを使う`, alternative `聞き取りAIを使う` | Up to three materially different alternatives reach the evidence packet; RAW stays the anchor. |
| Personal dictionary | `Floating VoiceBubble / フローティング ボイスバブル / FVB` | Relevant term, reading, and aliases may support recognition repair; unrelated dictionary entries do not flood the prompt. |
| Japanese/English mixture | `OpenRouterのreasoning effortを低にする` | Latin product/parameter names are preserved; they are not translated or normalized away. |
| Filler on/off | `えー今日はテストです` | Enabled means remove semantically empty fillers; disabled explicitly forbids filler deletion. |
| Punctuation on/off | same RAW with switches | Enabled adds natural punctuation; disabled does not introduce the forbidden punctuation class. |
| Line breaks none/smart/spaced | multi-sentence Japanese | NONE adds none; SMART uses semantic boundaries only; SMART_SPACED adds a blank line between paragraphs. Character width alone is never a legal boundary. |
| Multiple-topic long utterance | long app-settings discussion | No large omission, arbitrary summary, or register rewrite. Evidence packet stays bounded. |
| Clean no-op | `今日は晴れです` with formatting off | Valid unchanged output is success, not a reason to retry another LM call. |
| Hallucination trap | RAW omits `来週の予算は100万円`; surrounding context contains it | Context may disambiguate words but facts existing only in context must never enter final speech text. |

## Deterministic scoring

Every JVM run scores the corpus as a set of binary contracts:

- **coverage score** = required dimensions represented / required dimensions. Release target: **100%**.
- **prompt-contract score** = prompt invariants passing / prompt invariants. Release target: **100%**.
- **integrity score** = deterministic guard/postprocessor cases accepted or rejected exactly as specified. Release target: **100%**.
- **wire-contract score** = provider/model exact-body tests passing / provider/model exact-body cases. Release target: **100%**.

A failed contract is a regression; there is no weighted average that can hide a missing dimension.

## Live-model scoring

A provider/model cannot be certified from a tiny network probe. When a BYOK or Gemma model is configured, the app's production-equivalent diagnostic runs through the real `FinalizationEngine` path using both short semantic-repair and long Japanese vectors. The diagnostic records provider/model, effective reasoning wire, per-attempt timing, model response presence, integrity result, change attribution, and fallback.

For live-model comparison, record the following per corpus case when credentials/hardware are available:

- accepted/rejected;
- expected lexical repair achieved where evidence is strong;
- forbidden register/fact changes = 0;
- missing spoken content = 0 for short cases and no material loss for long cases;
- punctuation/filler/line-break preference compliance;
- model-change vs deterministic-formatting attribution;
- latency and attempt count.

A model is suitable for default interactive correction only if it has **zero hallucination/register violations** on the fixed corpus and its measured latency is acceptable for voice input. Higher reasoning effort is not assumed to be better; each provider/model setting must earn the extra latency on the same corpus.

## Relationship to prompt research

`docs/CORRECTION_PROMPT_RESEARCH.md` explains why RAW anchoring, bounded N-best, relevant dictionary terms, context-for-disambiguation-only, conservative lexical repair, and separate formatting controls were selected. This file defines the executable behavioral surface those principles must satisfy.
