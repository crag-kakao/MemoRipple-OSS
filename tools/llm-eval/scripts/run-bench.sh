#!/bin/bash
# Runs one workload of the Phase 0 bench app for one model on one device and waits for the
# results (docs/LLM_PHASE0.md §6–§7).
#
# Usage: tools/llm-eval/scripts/run-bench.sh <serial> <model.gguf> <label> [workload] [extra am args...]
#   workload: smoke (5 cases) | accuracy (every golden case) | performance (dataset/performance_subset.txt)
#             | battery (dataset/battery_subset.txt; unplug USB after "started") | lifecycle (load/unload ×3)
#   extra am args are passed to `am start` verbatim, e.g. --ei limit 20, --es assistantPrefix '<think>\n\n</think>\n\n'
#
# Never touches io.github.cragcoffee.memoripple: the bench app is io.github.cragcoffee.memoripple.llmbench
# and only its own external files directory is written (models, dataset, grammar, templates, prompts, results).
set -eu
SERIAL="$1"; MODEL="$2"; LABEL="$3"; WORKLOAD="${4:-accuracy}"; shift 4 2>/dev/null || shift $#
PKG="io.github.cragcoffee.memoripple.llmbench"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
EVAL="$ROOT/tools/llm-eval"
IN="/data/local/tmp/llmbench"               # inputs: world-readable, so the app can read what adb pushed
DEV="/sdcard/Android/data/$PKG/files"     # results: the bench app's own external files dir (it creates results/)
MODEL_BASE="$(basename "$MODEL")"

"$ADB" -s "$SERIAL" shell pm path "$PKG" >/dev/null || { echo "bench app not installed on $SERIAL" >&2; exit 2; }

"$ADB" -s "$SERIAL" shell mkdir -p "$IN/models" "$IN/dataset" "$IN/grammar" "$IN/templates" "$IN/prompts"
"$ADB" -s "$SERIAL" push "$EVAL/dataset/golden.jsonl" "$IN/dataset/" >/dev/null
for f in performance_subset.txt battery_subset.txt smoke_subset.jsonl parity_subset.txt; do
  [ -f "$EVAL/dataset/$f" ] && "$ADB" -s "$SERIAL" push "$EVAL/dataset/$f" "$IN/dataset/" >/dev/null
done
"$ADB" -s "$SERIAL" push "$EVAL/grammar/." "$IN/grammar/" >/dev/null
"$ADB" -s "$SERIAL" push "$EVAL/templates/." "$IN/templates/" >/dev/null
"$ADB" -s "$SERIAL" push "$EVAL/prompts/." "$IN/prompts/" >/dev/null
if [ -f "$MODEL" ]; then
  if ! "$ADB" -s "$SERIAL" shell "test -f '$DEV/models/$MODEL_BASE'"; then
    echo "pushing $MODEL_BASE ($(du -h "$MODEL" | cut -f1))"
    "$ADB" -s "$SERIAL" push "$MODEL" "$IN/models/"
  fi
fi
"$ADB" -s "$SERIAL" shell chmod -R a+rX "$IN"

BASE="$LABEL-$WORKLOAD"
# Never start hot: wait (up to 20 min) for the thermal status to be 0 (docs/LLM_PHASE0.md §8).
for i in $(seq 1 40); do
  st="$("$ADB" -s "$SERIAL" shell dumpsys thermalservice | grep "Thermal Status" | head -1 | grep -o "[0-9]*$")"
  [ "${st:-0}" = "0" ] && break
  echo "thermal status $st — waiting"; sleep 30
done
# The device must be awake with the screen on: asleep, Android starves the app's threads (seen on the S20:
# four inference threads at 0 % CPU for eight minutes). Kept on only while on USB and restored at the end.
"$ADB" -s "$SERIAL" shell "svc power stayon usb; input keyevent KEYCODE_WAKEUP"
# A fresh process per run: RAM figures start from a cold state and a stale activity cannot swallow the start.
"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
"$ADB" -s "$SERIAL" shell "rm -f '$DEV/results/$BASE.done'"
"$ADB" -s "$SERIAL" shell am start -W -n "$PKG/.BenchActivity" --es model "$MODEL_BASE" --es label "$LABEL" --es workload "$WORKLOAD" "$@" >/dev/null
echo "started $WORKLOAD for $LABEL on $SERIAL"
if [ "$WORKLOAD" = "battery" ]; then
  echo "unplug USB now; re-plug when the screen says done, then run: $0 $SERIAL $MODEL $LABEL pull"
  exit 0
fi
while ! "$ADB" -s "$SERIAL" shell "test -f '$DEV/results/$BASE.done'" 2>/dev/null; do sleep 5; done
mkdir -p "$EVAL/results/$LABEL"
"$ADB" -s "$SERIAL" pull "$DEV/results/$BASE.jsonl" "$DEV/results/$BASE.metrics.json" "$EVAL/results/$LABEL/" >/dev/null
echo "status: $("$ADB" -s "$SERIAL" shell cat "$DEV/results/$BASE.done")"
"$ADB" -s "$SERIAL" shell svc power stayon false
echo "results: $EVAL/results/$LABEL/$BASE.jsonl  $EVAL/results/$LABEL/$BASE.metrics.json"
