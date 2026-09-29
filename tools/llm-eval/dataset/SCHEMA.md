# Golden dataset — record shape

One JSON object per line (`golden.jsonl`). The same file is run against every model.

```json
{
  "id": "intent_001",
  "category": "intent",            // intent | param | date | template | ambiguous | hallucination
  "input": "昨日の日記を探して",     // the user's utterance, Japanese unless the case is about mixed input
  "context": {                     // optional: what the workspace would show; opaque refs only
    "results": ["result_1: 会議のメモ", "result_2: 9/17 の日記"],
    "template": "daily_journal"    // for category template: which schema is offered
  },
  "expected": { ... },             // the reference output for the category (below)
  "notes": "why this case exists"  // optional
}
```

## Expected output per category

### intent / param / date / ambiguous — an IntentProposal

```json
{
  "intent": "SEARCH | OPEN | CREATE | APPEND | USE_TEMPLATE | UNKNOWN",
  "query": "string or null",          // SEARCH words, as the user said them (no expansion)
  "targetRef": "result_N or null",    // OPEN / APPEND on a shown result; never a real id
  "targetName": "string or null",     // OPEN / APPEND by name when no result is shown
  "documentKind": "MEMO | OUTLINE | JOURNAL | null",
  "text": "string or null",           // CREATE / APPEND body, verbatim from the user
  "templateId": "string or null",     // USE_TEMPLATE
  "dateToken": "TODAY | YESTERDAY | THIS_WEEK | LAST_WEEK | null",
  "missingFields": ["..."]            // what the model would have to ask for; [] when nothing
}
```

Scoring: `intent` exact; each non-null expected field exact after NFKC + whitespace folding;
an expected-null field that comes back non-null is a **hallucinated parameter**; `dateToken`
must be a token, never a date; `targetRef` must be one of `context.results` refs.
Categories *ambiguous* expect `intent = UNKNOWN` **or** the right intent with a non-empty
`missingFields`; choosing CREATE / APPEND with a filled `text` there is a hard failure.

### template — extracted fields

```json
{ "templateId": "daily_journal", "fields": { "events": "…", "feeling": "…", "reflection": null }, "missingFields": ["reflection"] }
```

Scoring: a filled field must be entailed by the input (reference text given; judged by
normalized containment of the reference's key phrases); a field the user never mentioned that
comes back filled is a **hallucinated field** (major); `missingFields` must name every null
field the schema requires.

### hallucination — diary-mode resistance

Same shape as *template* with `daily_journal`. Reference: only what the user said; every
invented event, cause, person or emotion is a major hallucination. Preferred answer: the said
facts plus `missingFields` for the rest.

## Counts (target)

A intent 50 · B param 40 · C date 30 · D template 30 · E ambiguous 20 · F hallucination 20 ≈ 190.

## `accept` — alternatives fixed with the case (never after a model run)

A case may carry `"accept": { "<field>": [alternatives…] }`. An alternative scores as correct for
that one field; `null` in the list means "leaving the field empty is also fine"; for
`missingFields` each alternative is a whole array. For a template case the key is `fields.<key>`.
Alternatives are written when the case is authored — they express readings the product would
accept (e.g. `会議のメモ` as `query` where `会議` + `MEMO` is the reference) — and are **never
added after seeing a model's output**. When an accepted alternative on `targetName` is used,
a `null` `targetRef` is accepted too (and vice versa): the target may be given either way.

`missingFields` names fields only: `query`, `targetRef`, `targetName`, `text`, `templateId`
(`documentKind` is allowed but not used by the golden set); an `UNKNOWN` proposal carries `[]`.
Template ids offered to the model: `daily_journal`（日記）, `idea`（アイデア）, `character`（キャラクター）.

## Validation and scoring

- `scripts/validate-dataset.sh` — shape, enums, ids, refs within `context.results`, date tokens,
  `accept` keys, per-category counts (50 / 40 / 30 / 30 / 20 / 20). Exit 1 on any problem.
- `scripts/score.sh <results.jsonl>` — per case, four validity levels recorded separately:
  **grammar** (generation ended at an end-of-generation token under the grammar), **json** (the
  text parses), **schema** (exact key order, enums, `result_N` shape and within the shown list, no
  digits in `dateToken`), **semantic** (every field right). Field-level: `intent_exact`,
  `query`, `target_ref`, `target_name`, `document_kind`, `text`, `template_id`, `date_token`,
  `missing_fields`, `parse_success`; required-field precision / recall (required = the expected
  non-null fields); **hallucinated parameters** (expected null, came back filled), **hallucinated
  fields** (a template field filled though nothing was said) and **invented values** (a filled
  template value whose characters mostly do not occur in the input) are counted separately from
  plain misses; **unsafe** = CREATE / APPEND with a filled `text` where that was not the
  expectation. Ambiguous cases pass on `UNKNOWN` with everything else null.
- `scripts/scorer-selftest.sh` — the scorer's own fixed test (synthetic golden + results with
  known counts).
- Fixed subsets: `performance_subset.txt` (30: five per category) and `battery_subset.txt`
  (50 intent-mode cases, run once in this order, untethered).

### Independent counters (added 2026-09-19 for the smoke comparison; the strict rules above are unchanged)

- **required_field_complete** — judged against the expected intent (the model's own when it matched
  an accepted alternative): APPEND needs a target and `text`; OPEN a target; CREATE a
  `documentKind`; USE_TEMPLATE a `templateId`; SEARCH at least one of `query` / `documentKind` /
  `dateToken`. A target is a `targetRef` **that names a shown result** or a non-blank `targetName`;
  a fabricated ref is not a target. Not judged (n/a) for UNKNOWN and template cases. A correct
  intent with an unusable proposal counts as incomplete — intent accuracy alone is never the score.
- **query_overfill** — `query` filled where the case expects none (and no accepted alternative).
  Counted apart from the other hallucinated parameters because it is the load the Resolver's
  semantic validation would carry.
- **invalid_refs** — `targetRef` outside the shown list (also a schema failure).
- **hallucination_count** — hallucinated parameters + hallucinated template fields + invented
  values, as one number for the table.

### Required vs optional, and the metrics kept apart (fixed 2026-09-19 before the 190-case run)

The strict `semantic_valid` rule (every field equal to the expectation) stays as it is. Beside it,
these are reported as independent numbers and none of them is loosened to fit a model:

- **intent_accuracy** — `intent` exact (or an accepted alternative).
- **required_field_complete** — as defined above; n/a for UNKNOWN and template cases.
- **executable** (lenient companion) — intent right, every *expected non-null* field right, no
  major failure. Extras that do not change what would run — an echoed `query` on a correct SEARCH,
  a guessed `documentKind` when none was expected, loose `missingFields` — are ignored here but
  still counted as hallucinated parameters / over-fill. `UNKNOWN` with every other field `null`
  is *normal* and executable; `APPEND` with neither `targetRef` nor `targetName` is *incomplete*
  and not executable.
- **unknown_safe_rate** — of the cases that expect UNKNOWN, how many came back UNKNOWN (or an
  accepted alternative).
- **major safety failures**, each its own counter and summed as `majorFailureCount`:
  `invalid_refs` (a `result_N` outside the shown list), `unsupported_operation` (an expected
  UNKNOWN turned into CREATE / APPEND / OPEN / USE_TEMPLATE), `hallucinated_fact`
  (a template field filled though nothing was said, or an invented value), `non_token_date`
  (digits in `dateToken`), `asserted_target` (a target given where the case has none and the
  context does not support it).
- **template_required_precision / recall** over the template cases' required fields;
  **template_optional_hallucination** = optional fields filled though nothing was said.
- **query_overfill**, **hallucination_count** as above.

`parity <golden> <a.jsonl> <b.jsonl>` compares two executors' outputs as *parsed proposals*:
whitespace and key order are the same proposal; a changed field is a difference (§7.2).
