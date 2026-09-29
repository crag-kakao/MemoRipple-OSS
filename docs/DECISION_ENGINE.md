# DecisionEngine — Phase 3

> Human brief 2026-09-23 (`feature/decision-engine`, stacked on `feature/ai-resource-controller`).
> Between the Fast Path's NO_MATCH and the generation model now sits a **pure, deterministic
> decision**: a CERTAIN operation for the safe pipeline, one fixed clarification question, or
> NotApplicable for the model. No classifier, no second GGUF, no embeddings — plain Kotlin rules.

## 1. Purpose, and the difference from the Fast Path

The Fast Path reads **self-contained** sentences (「昨日の日記を開いて」) with no context at all. The
DecisionEngine reads the sentences the Fast Path rightly refused because they need the
**conversation's deterministic context**: 「2番目を開いて」 needs the shown results, 「それに追記して」
needs the anchor. Both layers share the philosophy — coverage loses to caution, a false negative
costs one model call, a false positive would cost trust — and both end in the same
validator → Resolver → policy → preview → Human Confirmation pipeline.

```
User ─ ChatRouter(fast) ─ MATCH ────────────────────────────┐
         │ NO_MATCH                                          ├─ settle(): the safe pipeline
         ▼                                                   │
       DecisionEngine ─ CERTAIN ─ IntentProposal ────────────┘
         │        └──── NEEDS_CLARIFICATION ─ one fixed question (chips of what was shown)
         ▼ NOT_APPLICABLE
       AiResourceController → generation model               (only here may anything load)
```

## 2. Where it lives

- `domain/ai/decision/DecisionEngine.kt`: `DecisionEngine` (interface, **non-suspend** —
  `decide(DecisionInput): DecisionResult`), `DeterministicDecisionEngine` (the Phase 3
  implementation), `DecisionInput`, `DecisionResult` (`Operation` / `NeedsClarification` /
  `NotApplicable`), `DecisionCertainty` (`CERTAIN` / `AMBIGUOUS` — **never a number**),
  `ClarificationSlot`, `ClarificationChoice`, `DecisionOutcome`.
- `AiOrchestrator.interactDecide / completeDecision`: the doors — input assembly (one anchor
  read through the boundary for its title), the engine, then the same `settle()`. **Before the
  resource controller by construction**: neither door can acquire, load or generate.
- `ChatViewModel`: the route order fast → decide → generation; the one ephemeral
  `PendingClarification`; the chips (`chat_clarify_choices`).

## 3. Deterministic context, and its limits

`DecisionInput` is only what this conversation lends: the latest shown results (count and the
titles the user already saw, positional) and the last document anchor (presence, kind, title).
**Cross-conversation isolation is structural** — another conversation's refs simply are not in
the input. The engine invents no id, no Long, no name; a CERTAIN target is an `AiResultRef`
position or the anchor marker the door re-reads through `DocumentAccess`. The Resolver stays the
authority for existence and ambiguity; a **stale anchor is NotFound with no silent retry** and
no second candidate.

## 4. What Phase 3 decides

| shape | with | result |
|---|---|---|
| N番目/件目/つ目/最初/最後 + 開いて/見せて/表示して | shown results, in range | CERTAIN OPEN `result_N` |
| N番目に『B』を追記して | shown results | CERTAIN APPEND `result_N` + body |
| N番目に追記して | shown results | ask 「何を追記しますか？」 |
| それ/これ/さっきの{メモ・日記・アウトライン} + 開いて | anchor, kind agrees | CERTAIN OPEN (anchor) |
| それに『B』を追記して / bare 『B』を追記して | anchor only | CERTAIN APPEND (anchor) |
| それに追記して / bare 追記して | anchor | ask 「何を追記しますか？」 |
| bare 開いて | anchor | CERTAIN OPEN (anchor) |
| 今日・昨日の{メモ・日記・アウトライン}を開いて | — | CERTAIN OPEN date+kind (Resolver's 0/1/many) |
| 今日・昨日の{メモ・日記・アウトライン・記録}を探して | — | CERTAIN SEARCH (dated, bounded) |
| 『B』を追記して / 昨日のやつに… | 1–5 enumerable candidates | ask with chips (shown titles + the anchor) |

**Never decided:** questions (？ or a か/かな/っけ tail — capability questions execute nothing),
negations, compounds (two operations, or a verb not ending the sentence — 「開いて要約して」),
quoted verbs, clause targets, generic-word bodies, out-of-range ordinals, kind-mismatched
demonstratives, missing or vanished context, more than five candidates, and every conversational
sentence. All of it is NotApplicable — the model's, exactly as before.

## 5. Clarification — deterministic, chat-internal, ephemeral

The questions are **fixed strings** (「何を追記しますか？」, 「どの記録に追記しますか？」,
「どれを開きますか？」 — never generated, never a technical word); they are ordinary assistant lines
of the transcript, choices are small chips of titles the user already saw (at most five; too many
means NotApplicable, not a huge form). The one `PendingClarification` lives in the view model's
memory: **only while it is open** is the next message read as the answer to exactly the asked slot
(a body, or a target name the Resolver then searches) — free text is never guessed into an APPEND
body at any other time. A chip pick fills the target; a still-missing append body chains to the
body question. A conversation switch drops it; **a process death drops it** (the question line
stays in the transcript, nothing resumes, nothing writes); it has no execution authority — the
completed proposal still meets the validator, the Resolver, the preview and the one confirmation.

## 6. No model needed

The decide door checks no availability and touches no runtime: 「2番目を開いて」, the body question
and its answered preview all work with **no model installed** — the setup card appears only when
the decision is NotApplicable and the sentence would need the generation model.

## 7. The resource controller

Order pinned by tests: Fast Path → DecisionEngine → `AiResourceController.acquireForGeneration`.
CERTAIN: acquire 0. Clarification: acquire 0. Only NotApplicable, with a model selected, reaches
the controller and its lazy load. Deciding also never resets the controller's idle clock.

## 8. Observability

Debug logs only: `route=FAST / DECISION_CERTAIN / DECISION_CLARIFY / GENERATION` with lengths and
a choice count — never the sentence, a title, a body or an answer. No user-visible word names the
engine, a certainty, a `result_N`, an anchor or a resolver.

## 9. Golden corpus

`DecisionEngineTest` (~100 sentences) is the contract, spanning CERTAIN OPEN / APPEND / SEARCH,
both clarification slots, ordinals, demonstratives, stale and cross-conversation context,
negation, capability questions, conversation, compounds and no-context — CERTAIN cases must
route exactly, negatives must never become an unsafe CERTAIN, and a clarification must ask only
the truly missing slot.

## 10. S20 (2026-09-23, reference, never product UI)

Measured in place with no model selected (HANDOFF §16.83): the fast route answered in **7 ms**
(`route=FAST`), and a sentence the engine declines (「MemoRipple開発に追記して」 — a named target is
not the engine's) walked fast → decide → NotApplicable into `route=GENERATION` in **≈ 1 ms**,
meeting the setup card with **zero loads and zero `AI_RESOURCE` lines** — the decide layer costs
milliseconds next to a 4–6 s load and a ~15 s TTFT. The ordinal, clarification and explicit
context operations cannot be typed over adb (no Japanese IME on the device), so they are pinned
by the seven instrumentation journeys (three runs each) instead; nothing was written on the S20.

## 11. Future — a local classifier

A later `LocalClassifierDecisionEngine` would implement the same `DecisionEngine` interface and
slot into the same door — and **its results would still not bypass the Resolver, the preview or
the Human Confirmation**, exactly as the deterministic engine's do not. Not in this round, by
decision: no classifier GGUF, no cloud classification, no confidence percentages, no automatic
execution from any model's confidence.

## 12. Phase 4A — the lightweight classifier, measured and not adopted (2026-09-23)

A shadow experiment on the research branch `feature/lightweight-decision-shadow` (never merged
into a product branch) measured 0.5–1B classifiers beside this engine's NotApplicable: the
0.5–0.6B class was either inert (no action recognised) or dangerous (up to 50 action false
positives of 67), and the 1B few-shot candidate that did discriminate still made 13 action false
positives at ~8.7 s a label on the S20. **Conclusion: no classifier enters v1 product routing;**
the deterministic engine stays the only decision layer and the generation model the judge of the
remainder. The shadow code, prompts and runtime stay on that branch.
