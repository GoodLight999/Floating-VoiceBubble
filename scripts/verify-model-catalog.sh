#!/usr/bin/env bash
set -euo pipefail

BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
URLS=(
  "$BASE/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-80ms-int8-2026-06-11.tar.bz2"
  "$BASE/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11.tar.bz2"
  "$BASE/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2"
  "$BASE/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11.tar.bz2"
  "$BASE/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2"
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
  "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true"
)

for url in "${URLS[@]}"; do
  echo "Probing $url"
  curl \
    --fail \
    --silent \
    --show-error \
    --location \
    --head \
    --retry 3 \
    --retry-delay 2 \
    --retry-all-errors \
    --connect-timeout 15 \
    --max-time 60 \
    "$url" >/dev/null
done

echo "All official model catalog URLs resolved successfully."
