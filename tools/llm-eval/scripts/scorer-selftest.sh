#!/bin/bash
# The scorer's own fixed-expectation test: synthetic golden + results with known counts.
set -eu
EVAL="$(cd "$(dirname "$0")/.." && pwd)"
java "$EVAL/scorer/LlmEval.java" selftest
