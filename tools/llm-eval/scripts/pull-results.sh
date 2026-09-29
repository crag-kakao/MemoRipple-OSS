#!/bin/bash
# Pulls one finished run's results (used after an untethered battery run). Usage: pull-results.sh <serial> <label> <workload>
set -eu
SERIAL="$1"; LABEL="$2"; WORKLOAD="$3"
PKG="io.github.cragcoffee.memoripple.llmbench"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
EVAL="$(cd "$(dirname "$0")/.." && pwd)"
DEV="/sdcard/Android/data/$PKG/files/results"
mkdir -p "$EVAL/results/$LABEL"
"$ADB" -s "$SERIAL" pull "$DEV/$LABEL-$WORKLOAD.jsonl" "$DEV/$LABEL-$WORKLOAD.metrics.json" "$EVAL/results/$LABEL/"
"$ADB" -s "$SERIAL" shell cat "$DEV/$LABEL-$WORKLOAD.done"; echo
