#!/bin/bash
# The full instrumentation suite as two process lifetimes (HANDOFF §16.20, docs/TEST_INFRASTRUCTURE.md).
#
# The instrumentation process leaks roughly 1 MB of Java heap per test and hits its 192 MB growth
# limit near the 320th test of a single `am instrument` run. Until that leak is found, the official
# full device gate is this script: every test class, split in half by sorted fully-qualified name,
# each half in its own process, `pm clear` before each. The split is derived from the sources, so
# everyone gets the same A / B for the same tree.
#
# Usage: scripts/device-suite.sh [serial] [log-dir]
#   serial   defaults to emulator-5554; a physical phone is refused unless ALLOW_PHONE=1 is set —
#            the S20 and S26 hold real data and their own builds.
#   log-dir  defaults to build/device-suite; A.log, B.log and summary.txt land there.
# Expects the current app and test APKs to be installed already (assembleDebug /
# assembleDebugAndroidTest, then `adb -s <serial> install -r`). Never runs a Gradle install task.
set -u
SERIAL="${1:-emulator-5554}"
LOG_DIR="${2:-build/device-suite}"
PKG="io.github.cragcoffee.memoripple"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$SERIAL" in
  emulator-*) ;;
  *) if [ "${ALLOW_PHONE:-0}" != "1" ]; then echo "refusing physical device $SERIAL (set ALLOW_PHONE=1 to override)"; exit 2; fi ;;
esac
mkdir -p "$LOG_DIR"

# Every top-level class in a test file that declares a @Test, as package.Class, sorted.
classes=$(cd "$ROOT" && grep -rl -E "@Test|@org\.junit\.Test" app/src/androidTest/java --include='*.kt' | while read -r f; do
  pkg=$(grep -m1 "^package " "$f" | awk '{print $2}')
  grep -E "^class [A-Za-z0-9_]+" "$f" | awk -v p="$pkg" '{gsub(/[({:].*/, "", $2); print p"."$2}'
done | sort -u)
total=$(printf '%s\n' "$classes" | wc -l | tr -d ' ')
half=$(( (total + 1) / 2 ))
listA=$(printf '%s\n' "$classes" | head -n "$half" | paste -sd, -)
listB=$(printf '%s\n' "$classes" | tail -n +"$((half + 1))" | paste -sd, -)

run_half() {
  local name="$1"
  local list="$2"
  local log="$LOG_DIR/$name.log"
  "$ADB" -s "$SERIAL" shell pm clear "$PKG" > /dev/null
  {
    echo "half $name  $(printf '%s' "$list" | tr ',' '\n' | wc -l | tr -d ' ') classes  host load $(uptime | sed 's/.*load averages://')"
    "$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$list" "$PKG.test/$PKG.MemoRippleTestRunner" 2>&1 \
      | grep -E "^INSTRUMENTATION_STATUS: (test|stack)=|^INSTRUMENTATION_STATUS_CODE: -[0-9]|^\s+at io.github|Tests run|OK \(|FAILURES|^Error in|^[0-9]+\) " \
      | grep -v "stack=$"
    echo "host load at end $(uptime | sed 's/.*load averages://')"
  } > "$log" 2>&1
  local verdict; verdict=$(grep -E "^OK \(|^Tests run" "$log" | head -1)
  local skips; skips=$(grep -c "INSTRUMENTATION_STATUS_CODE: -4" "$log")
  echo "$name: ${verdict:-NO VERDICT (process died?)}  assumption-skips=$skips"
}

{
  echo "tree $(cd "$ROOT" && git rev-parse --short HEAD)  serial $SERIAL  classes $total (A $half, B $((total - half)))"
  run_half A "$listA"
  run_half B "$listB"
} | tee "$LOG_DIR/summary.txt"
