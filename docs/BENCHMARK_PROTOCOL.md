# Same-Audio ASR Benchmark Protocol

## Purpose

Select recognition paths using evidence from the **same utterances**, not model reputation. The benchmark separates:

- live UX behavior,
- final transcript accuracy,
- local compute cost/RTF,
- external competitor accuracy.

## Ground truth

Only a human-edited `reference` is ground truth. Never use Gboard, Android SpeechRecognizer, Nemotron, ReazonSpeech, or an LLM output as the reference simply because it looks plausible.

Workflow:

1. Enable session traces.
2. Record real dictation sessions.
3. Advanced → `正解雛形`.
4. Edit only the `reference` column while listening to the source utterance where necessary.
5. Import with `正解取込`.
6. Run `全候補を比較`.

## Required utterance strata

Do not optimize on a single clean reading style. Include at minimum:

1. ordinary colloquial Japanese
2. fast speech
3. hesitations, self-corrections, repetitions
4. Japanese + English/code-switching
5. difficult proper nouns, product names, people/places
6. punctuation-sensitive phrasing where natural
7. short commands/phrases
8. long multi-clause dictation
9. noisy-but-realistic room conditions
10. dictionary-target terms and aliases

Keep the raw source WAV. Do not separately rerecord a candidate unless the product itself cannot accept the saved source; that case must be marked as a non-identical-audio comparison.

## Built-in candidate comparison

`AsrTournamentRunner` evaluates:

- saved live transcript
- every installed Nemotron 3.5 Streaming chunk variant
- ReazonSpeech final ASR when installed

Nemotron replay feeds the saved PCM into the actual sherpa online recognizer in small frames. This is an **exact-WAV model comparison**, not a live-latency claim.

## External competitors

Advanced → `競合雛形` creates rows for:

- Gboard
- Wispr Flow
- Aqua Voice

Enter the transcript produced by the real competitor and import it. If a competitor cannot ingest the exact saved WAV, play the same source under controlled conditions or dictate the same prepared utterance and explicitly record that the audio path is not bit-identical. Do not hide that limitation.

## Metrics

### Content CER — primary Japanese accuracy metric

NFKC + case normalization, whitespace removed, punctuation/symbols removed. Levenshtein errors / human-reference code points.

### Strict CER

Same normalization but punctuation/symbols retained. Useful for final text quality, but do not let punctuation dominate lexical accuracy.

### WER

Only emitted when the reference has meaningful whitespace tokenization. Japanese without reliable segmentation is not reported as a fake one-token WER.

### RTF

Local decode elapsed time / audio duration. Only meaningful for locally replayed candidates. External products do not receive invented RTF values.

### Disagreement

Live↔candidate edit distance may be recorded for diagnostics, but **is not accuracy** without human ground truth.

## Candidate selection

### Streaming chunk

Choose the best Nemotron chunk only after enough labeled real Japanese samples exist. Primary order:

1. content CER
2. strict CER where relevant
3. real-phone streaming responsiveness / dropped-audio behavior
4. RTF/thermal/battery

A statistically tiny CER difference does not justify visibly worse latency or thermals; document the tradeoff rather than pretending there is a universal winner.

### Final ASR

Compare live baseline and ReazonSpeech final result on the same human-labeled sessions. Enable ReazonSpeech by default only if its accuracy gain is meaningful relative to added finalization latency on the target phone.

### External products

Report label count beside every score. Do not rank a system with 3 easy labels above one evaluated on 50 mixed labels without qualification.

## Result preservation

Tournament JSON reports live under `noBackupFilesDir/benchmarks/asr`. For durable research, export/copy the relevant result before app-data reset. Ground-truth and external-result stores are kept separately from generated tournament reports.
