# llm-eval — Local LLM Phase 0 harness (non-shipping)

Everything under `tools/llm-eval/` and the optional `:llmbench` module is **benchmark only**: not a
dependency of `:app`, not in any AAB, never touching Room, `DocumentAccess` or MemoRipple's data.
See `docs/LLM_PHASE0.md` for the plan, the checkpoints and the results.

```
tools/llm-eval/
  README.md            this file
  dataset/
    SCHEMA.md          the JSONL record shape, expected-output shapes, `accept`, scoring rules
    golden.jsonl       the golden dataset: 190 cases (A intent 50 · B param 40 · C date 30 ·
                       D template 30 · E ambiguous 20 · F hallucination 20), fixed before any model ran
    golden.sample.jsonl  one case per category (documentation)
    smoke_subset.jsonl   the 10-case technical smoke (the brief's A–F + four golden cases); `smoke` runs it
    performance_subset.txt / battery_subset.txt   fixed case lists for those workloads
  prompts/
    intent_system.txt  the round-1 system prompt for categories A/B/C/E (Japanese, shared by every model)
    template_system.txt  the round-1 system prompt for D/F ({{TEMPLATE_NAME}}, {{TEMPLATE_ID}}, {{FIELD_LIST}})
  grammar/
    intent_proposal.gbnf   GBNF for IntentProposal (categories A, B, C, E)
    template_fields.gbnf.template   GBNF template; the bench app generates one grammar per template schema
  templates/
    daily_journal.json idea.json character.json   the declarative template schemas used by D/F
  scorer/
    LlmEval.java       validator + scorer + self-test (JDK 17 single-file launch; this Mac has no Python)
  scripts/
    run-bench.sh       push inputs + model, run one workload of the bench app on one device, pull results
    pull-results.sh    pull a finished (untethered battery) run
    validate-dataset.sh · score.sh · scorer-selftest.sh
  results/             pulled results (gitignored except small summaries)
```

## Running

```bash
./gradlew -PllmBench=true :llmbench:assembleDebug      # the only way :llmbench enters the build
adb -s <serial> install -r llmbench/build/outputs/apk/debug/llmbench-debug.apk
tools/llm-eval/scripts/run-bench.sh <serial> ~/llm-models/<model>.gguf <label> smoke|accuracy|performance|battery|lifecycle [am extras]
tools/llm-eval/scripts/score.sh tools/llm-eval/results/<label>/<label>-accuracy.jsonl
```

On the device the inputs (dataset, prompts, grammars, templates, models) live under
`/data/local/tmp/llmbench/` — world-readable, so the app can read what `adb push` wrote (files
pushed into the app's own external directory belong to the shell user and are unreadable to it).
The script waits for thermal status 0, wakes the device and keeps the screen on over USB for the
run (a dozing phone starves the inference threads), and restores that afterwards. Results are
written by the app into its own external files directory
(`/sdcard/Android/data/io.github.cragcoffee.memoripple.llmbench/files/results/`) and pulled from
there. The S26 serial is refused by the script; MemoRipple's own package is never touched.

Models (GGUF) are never committed; they live under `~/llm-models/` on the Mac and under
`/data/local/tmp/llmbench/models/` on the device.
