#!/bin/bash
# Validates tools/llm-eval/dataset/golden.jsonl (shape, enums, refs within context, counts per category).
set -eu
EVAL="$(cd "$(dirname "$0")/.." && pwd)"
java "$EVAL/scorer/LlmEval.java" validate "${1:-$EVAL/dataset/golden.jsonl}"
