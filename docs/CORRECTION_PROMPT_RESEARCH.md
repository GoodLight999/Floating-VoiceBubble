# Correction prompt research and design basis

Updated: 2026-08-22

This document exists because the previous correction prompt was hand-authored without a sufficiently explicit research trail. The stabilization pass must not select prompt wording by intuition alone.

## Task definition

Floating VoiceBubble is not asking an LLM to “improve writing”. It is performing **ASR post-editing**:

1. recover words that the speech recognizer plausibly misheard;
2. use N-best hypotheses, a personal dictionary, and recent same-field context only as evidence;
3. preserve the speaker’s register, slang, roughness, claims, uncertainty, and intent;
4. apply only user-requested presentation transforms (punctuation, filler removal, line breaks, optional register conversion);
5. avoid introducing facts or paraphrases that were not spoken.

The failure costs are asymmetric: a false correction that changes what the user said is generally worse than leaving an uncertain ASR error untouched.

## Primary research

### Ma et al. 2023 — Can Generative Large Language Models Perform ASR Error Correction?

- arXiv: https://arxiv.org/abs/2307.04172
- Studies zero/one-shot generative ASR error correction using ASR N-best hypotheses.
- Both unconstrained generation and constrained selection from the N-best list are evaluated.

Design consequence:
- N-best hypotheses are legitimate evidence for a post-editor; do not rely only on 1-best RAW.
- We should evaluate a small few-shot example rather than assuming a long prose instruction is sufficient.

### Chen et al. 2023 — HyPoradise: An Open Baseline for Generative Speech Recognition with Large Language Models

- NeurIPS 2023: https://papers.nips.cc/paper_files/paper/2023/hash/6492267465a7ac507be1f9fd1174e78d-Abstract-Datasets_and_Benchmarks.html
- arXiv: https://arxiv.org/abs/2309.15701
- Uses N-best hypotheses for generative error correction and provides a large benchmark of hypothesis/reference pairs.

Design consequence:
- Treat correction as a hypothesis-to-transcription task, not generic rewriting.
- Benchmark prompts on fixed hypothesis/reference examples; do not judge them by a few anecdotes.

### Ma et al. 2024 — ASR Error Correction using Large Language Models

- arXiv: https://arxiv.org/abs/2409.09554
- Expands N-best error correction and explores constrained decoding based on N-best/lattices.
- Notes that unrestricted generation can hurt in unseen domains.

Design consequence:
- The prompt must make the evidence boundary explicit and default to no lexical change when evidence is weak.
- A post-editor should not be rewarded for producing merely fluent text.

### Hu et al. 2024 — Listen Again and Choose the Right Answer

- Findings of ACL 2024: https://aclanthology.org/2024.findings-acl.37/
- Identifies two important GER problems: the LLM lacks direct source-speech awareness, and N-best hypotheses are often redundant.
- Redundant N-best content can confuse the LLM about which tokens actually need attention and increase miscorrection.

Design consequence:
- **Do not dump every N-best string indiscriminately.**
- Send only distinct hypotheses that materially differ from RAW, with a small cap.
- Explicitly tell the model that RAW/N-best are acoustic evidence and that linguistic plausibility alone is insufficient to invent a replacement.

### Udagawa et al. 2024 — Robust ASR Error Correction with Conservative Data Filtering

- EMNLP 2024 Industry Track: https://aclanthology.org/2024.emnlp-industry.20/
- Focuses on Japanese ASR error correction and overcorrection.
- Proposes that correction targets should improve linguistic acceptability **and be inferable from available context such as source phonemes**; otherwise the model should learn to make no correction.

Design consequence:
- This is a central product rule: **uncertain lexical content stays unchanged.**
- Context may disambiguate acoustically plausible candidates but must not become a source of new claims.
- Evaluation must explicitly count overcorrection, not only “how many ASR errors were fixed”.

### Asano et al. 2025 — Contextual ASR Error Handling with LLMs Augmentation for Goal-Oriented Conversational AI

- COLING 2025 Industry Track: https://aclanthology.org/2025.coling-industry.32/
- Uses lexical/semantic similarity between N-best and context, plus phonetic correspondence between context and hypotheses.
- Reports gains while maintaining precision/false-positive rate.

Design consequence:
- Recent conversation context is useful only when coupled to lexical/phonetic evidence from the utterance.
- The model should never copy a fact from context merely because it makes the sentence coherent.

### Chen et al. 2023 — Generative error correction for code-switching speech recognition using large language models

- arXiv: https://arxiv.org/abs/2310.13013
- Uses diverse N-best hypotheses for code-switched speech.

Design consequence:
- Include Japanese/English mixed utterances in the evaluation corpus.
- Preserve terms that are acoustically plausible even when they are not ordinary Japanese vocabulary.

### Punctuation restoration literature

- Alam et al. 2020, W-NUT: https://aclanthology.org/2020.wnut-1.18/
- Punctuation restoration is a distinct ASR post-processing task intended to improve readability.

Design consequence:
- Punctuation is a presentation transform and should not be conflated with semantic error correction.
- Punctuation-only changes must never trip the lexical-integrity checker.

## Prompt design principles for VoiceBubble v2

1. **ASR post-editor identity** — never call the task “writing improvement”, “rewrite”, or “polish”.
2. **Evidence hierarchy** — RAW is the anchor; differing N-best hypotheses and dictionary terms are acoustic/lexical evidence; recent context is disambiguation evidence only.
3. **Conservative lexical edits** — change words only when the proposed correction is supported by the available evidence. Fluency alone is not enough.
4. **No context leakage** — facts/claims present only in surrounding context must not enter the output.
5. **Preserve register** — rough language, slang, fragments, uncertainty, and casual style are data, not defects.
6. **Formatting is separate** — punctuation, filler removal, line breaks, and explicit register conversion are user-selected transforms and must not be treated as semantic correction risk.
7. **Small evidence packet** — send a small number of materially different N-best candidates, only relevant dictionary entries, and a bounded recent context window.
8. **No hidden chain-of-thought requirement** — the model may reason internally if its API/model supports it, but the task requests only the final transcript. Voice correction is latency-sensitive; deeper reasoning is opt-in and must be evaluated.
9. **Return transcript only** — no Markdown, explanation, confidence text, quotes, or JSON unless a future structured-output implementation is explicitly adopted and benchmarked.
10. **Measure overcorrection** — prompt evaluation records both error fixes and clean-span regressions.

## Evidence-packet limits to evaluate

Previous implementation limits were too large for a latency-sensitive post-editor:

- N-best: up to 8 full strings.
- dictionary: up to 96 entries.
- surrounding context: up to 1500 characters.

Stabilization candidate:

- N-best: RAW + at most **3 materially different alternatives**.
- dictionary: at most **24 relevant entries**, ranked by relevance/priority.
- surrounding context: at most **600 trailing characters** from the same non-sensitive app/field context.

These are evaluation candidates, not sacred constants. Keep the smallest packet that preserves correction quality on the regression corpus.

## Prompt v2 structure

System message should be short and operational:

- identify the role as an ASR post-editor;
- state the evidence hierarchy;
- prohibit unsupported lexical changes/context fact injection;
- preserve register;
- state the selected formatting operations;
- request transcript-only output.

User message should use clearly delimited fields:

- RAW transcript;
- materially different N-best alternatives only;
- relevant dictionary terms only;
- bounded surrounding context;
- explicit instruction to output the recovered transcript and nothing else.

Avoid long duplicated prose in both system and user messages.

## Evaluation corpus / acceptance metrics

The prompt cannot be accepted without a fixed Japanese corpus containing:

- clean transcript / expected no lexical change;
- casual and rough language;
- polite language;
- obvious homophone/segmentation ASR errors;
- N-best disambiguation;
- personal-dictionary proper nouns;
- Japanese/English code switching;
- context that helps disambiguate;
- **hallucination traps** where context contains true-looking facts that were not spoken;
- filler removal on and off;
- punctuation on and off;
- line-break none/smart/spaced;
- long multiple-topic dictation;
- short fragments that should not be “completed” into prose.

Report at minimum:

- lexical correction success on known ASR errors;
- overcorrection rate on clean spans;
- register-preservation failures;
- context-leak/hallucination failures;
- punctuation/filler/line-break contract failures;
- latency per provider/model/reasoning setting.

A candidate prompt is accepted only by the corpus/eval, not because it sounds persuasive to a developer.