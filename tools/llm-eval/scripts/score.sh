#!/bin/bash
# Scores one results file against the golden dataset. Usage: score.sh <results.jsonl> [summary.json]
set -eu
EVAL="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS="$1"; OUT="${2:-${RESULTS%.jsonl}.summary.json}"
java "$EVAL/scorer/LlmEval.java" score "$EVAL/dataset/golden.jsonl" "$RESULTS" "$OUT"
echo "summary: $OUT"
