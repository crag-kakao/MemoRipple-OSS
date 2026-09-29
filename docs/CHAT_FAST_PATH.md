# Chat Fast Path — Phase 1 (ChatRouter)

> Human brief 2026-09-22 (`feature/chat-fast-path`, stacked on `feature/think-templates`). An
> unmistakable ask — 「昨日の日記を開いて」, 「旅行を探して」, 「メモを作って」 — should not wake a
> 3–4 GB model. The Fast Path reads such sentences by rule; everything else goes to the AI
> exactly as before.

## 1. Motivation — why not the LLM for every command

Loading Qwen/Ministral costs seconds, watts and heat (the S20 reaches thermal SEVERE within
minutes of continuous inference; docs/LLM_PHASE0.md). Most chat traffic in practice is a handful
of fixed shapes the app can read deterministically. Sending those through the model buys nothing
— the model would emit the same `IntentProposal` — and costs everything the model costs. It also
makes the chat useless without a model for asks that never needed one.

## 2. The one principle

**The Fast Path shortens only the LLM's proposal generation. It never shortens the safety
pipeline.**

```
                         ┌─ FastIntentRecognizer (rules) ─ IntentProposal ─┐
USER input ─ ChatRouter ─┤                                                ├─ settle():
                         └─ RequiresAi ─ LocalModelRuntime ─ generator ────┘   referent rules (Phase 8)
                                                                               target assist (Phase 6)
                                                                               SemanticValidator
                                                                               Resolver
                                                                               ExecutionPolicy
                                                                               direct read / preview
                                                                               Human Confirmation
                                                                               CommandExecutor
```

Both routes end in the **same** `settle()` — the tail of the old `run()`, extracted, byte-for-byte
the same rules. A write still stops at its preview and the one `confirm()`; a read still runs only
when the policy says Direct. Forbidden from the fast side, and policy-pinned: DAO / Room /
`DocumentAccess` writes, preview or confirmation bypass, `ConfirmedCommand` construction,
destructive intents, confidence-based anything.

## 3. Where it lives

- `domain/ai/fast/ChatFastPath.kt`: `FastIntentRecognizer` (pure — no clock, no store, no runtime,
  no network), `FastIntent(proposal, needsAnchor)`, `ChatRoute` (`Fast` / `RequiresAi`),
  `ChatRouter` (interface) + `DeterministicChatRouter`.
- `AiOrchestrator.interactFast(userText, context, referents, destination): AiInteractionResult?` —
  routes, and on a match runs `settle()`; **no thermal check** (nothing generates; the template
  runner's precedent), **no selection / availability check**, **no runtime**. Null = the AI's.
- `ChatViewModel.askText`: builds context/referents, records the USER line once, tries
  `interactFast`, falls through to `interact` unchanged. The transcript and the result cards are
  identical either way; the initial phase is 「処理しています…」 and only a real load shows
  「AIモデルを読み込んでいます…」.

## 4. Phase 1 shapes (Japanese only; conservative by design)

| shape | proposal |
|---|---|
| (今日/昨日)の日記を(開いて/見せて/表示して) | OPEN, kind=JOURNAL, dateToken |
| 『X』を開いて / Xを開いて (X a plain token) | OPEN, targetName=X |
| (今日/昨日)の日記を(探して/検索して) | SEARCH, kind=JOURNAL, dateToken |
| 『X』という(メモ/文章/記録)を(探して/検索して) | SEARCH, query=X (+kind MEMO for メモ) |
| Xを(探して/検索して) (X a plain token) | SEARCH, query=X |
| (新しい)メモを(作って/作成して) — nothing else in the sentence | CREATE, kind=MEMO |
| 『A』に『B』を(追記/追加/書き足し)して (A, B explicit) | APPEND, targetName=A, text=B |
| (それ/これ)に『B』を追記して | APPEND, text=B, `needsAnchor` |

Dates stay `DateToken`s — the Resolver's `TimeProvider` turns them into days, never the parser.
A *plain token* is a quoted span, or one particle-free run of ≤32 chars that is not a generic
word (メモ, 日記, 記録…), not a demonstrative, not a date word.

**One capability was added inside the pipeline**, not beside it: OPEN with a `dateToken` + a
`documentKind` and no name (「昨日の日記を開いて」). The validator counts a day+kind as a target;
the Resolver searches that day and applies the same 0 / 1 / many rule as a name — one opens,
several are candidates, none is NotFound. The model path gains the same, identically.

## 5. What never matches (the false-positive policy)

Coverage loses to caution: a false negative costs one model call, a false positive costs trust.
Pinned by the golden corpus (`FastIntentRecognizerTest`, the contract):

- **Negation** — 「メモを作らないで」, 「開かなくていい」 → NO_MATCH.
- **Questions** — anything with ？/? (「昨日の日記を開ける？」) → NO_MATCH.
- **Compound commands** — two operations outside quotes (「開いて、そこに追記して」) → NO_MATCH.
- **Quoted verbs are words** — 「『メモを作って』という文章を探して」 is a SEARCH for the words
  メモを作って, never a CREATE.
- **Clauses are not titles** — a query/target/body with particles (「ラーメンについて書いたメモ」)
  → NO_MATCH; the AI can structure it.
- **Bare demonstratives / generic words** — 「それを開いて」, 「メモを探して」 → NO_MATCH.
- **CREATE with anything else in the sentence** (「旅行についてメモを作って」) → NO_MATCH; the
  title/body boundary is the AI's problem.
- **Date-word prefixes** — 「昨日の記録を探して」, 「今日のメモを開いて」 → NO_MATCH: a token
  headed by a date word is a date phrase the Resolver must read, never a title (Phase 6's
  standing rule; found by the full gate 2026-09-22 and pinned).
- **Ordinals** — 「2番目を開いて」, 「3つ目を見せて」 → NO_MATCH: an ordinal is Phase 8's
  referent to the stored result list, never a name and never a body; the history rule keeps it.

## 6. Context reuse — それ rides the anchor, never the parser

「それに『会話テスト』を追記して」 fast-matches with `needsAnchor`: the parser emits APPEND with the
body and **no target**, and the target is settled by the *existing* Phase 8 anchor rule inside
`settle()` (the anchor re-read through the boundary; the Resolver re-validates; the preview and
confirmation unchanged). No anchor → `interactFast` returns null and the sentence goes to the AI
(or, with no model, to the setup card). A vanished anchor is NotFound, as always.

## 7. No model installed

A fast-matched ask now works with **no model, no GGUF, no network, no selection**: open, search,
create-with-preview, append-with-preview. Only a NO_MATCH sentence still meets the setup card
(「AIモデルを設定」 / 「テンプレートを使う」), exactly as before. The journeys pin `TestAiRuntime.loads
== 0` and zero generations on every fast case.

## 8. History, wording, telemetry

- The USER line and the assistant's result line are recorded once, the same as the AI route; the
  transcript cannot tell the routes apart.
- No user-facing word ever names a route (no 「Fast Path」, no 高速経路) — policy-pinned.
- Logs carry `route=FAST / DECISION_CERTAIN / DECISION_CLARIFY / GENERATION` with lengths (Phase 3
  widened the one line) and the existing redacted notes; never the input, a query, a title or a body.

## 9. Performance (S20 reference; not product UI)

Recorded in HANDOFF §16.81: on the S20 with **no model selected**, a fast SEARCH routed in
~20 ms after the send and its result card was on screen at once (`route: fast=true`, zero LOAD /
GENERATION lines); the AI route's first ask remains ~30 s (load) + generation as measured in
Phase 3–8. No benchmark number is shown in the product.

## 9.1 The resource controller (Phase 2, 2026-09-23)

`docs/AI_RESOURCE_CONTROLLER.md` moved the model's lifecycle into an application-scoped
controller. The Fast Path's relationship to it is deliberately **none**: a fast ask acquires no
lease, touches no runtime and never arms, resets or extends the idle clock — a warm model over
which only fast traffic flows reaches its idle deadline unchanged and unloads (journey- and
policy-pinned). Only the AI route's genuine generation leases the model.

## 9.2 Generation efficiency (Phase 4B, 2026-09-23)

`docs/GENERATION_EFFICIENCY.md`: when a sentence does reach the model, its generation is now timed in parts
(tokenize / prompt eval / first token) and the token-level common prefix with the previous request of the
same conversation is reused. The Fast Path is untouched by this: a fast ask still generates nothing and
keys nothing.

## 10. The DecisionEngine (Phase 3, 2026-09-23 — built)

`docs/DECISION_ENGINE.md`: the deterministic decision now sits exactly in this seam — Fast Path
NO_MATCH → DecisionEngine → generation model. It reads what the rules here deliberately refuse
(ordinals over the shown results, demonstratives on the anchor, wider dated kinds), asks one fixed
question when a single slot is missing, and hands everything unsure to the model as before. Still
no classifier model, no semantic search, no embeddings; a future local classifier would implement
the same interface without bypassing the Resolver, the preview or the Human Confirmation.
