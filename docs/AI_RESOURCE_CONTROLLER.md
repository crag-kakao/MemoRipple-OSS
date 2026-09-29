# AI Resource Controller — Phase 2

> Human brief 2026-09-23 (`feature/ai-resource-controller`, stacked on `feature/chat-fast-path`).
> The generation model is a **leased resource**: nothing but a genuinely generative request loads
> it, a finished answer leaves it warm, and the controller's own clock — never a screen's
> lifecycle — unloads it. Opening the chat is never loading a model.

## 1. Purpose

Before this round the model's residency was stitched into three places: the orchestrator's own
idle job, the chat view model's `onCleared` (Phase 3's screen-tied unload) and the application's
trim hook. Phase 2 moves the whole lifecycle into one application-scoped owner,
`domain/ai/resource/AiResourceController.kt`, with one serialized state, one policy and one clock.
The controller knows **nothing** of intents, resolvers, previews or documents — its world is the
runtime, a clock and three environment readings (memory, thermal, power).

```
User message ─ ChatRouter ─┬─ FAST ────────────── safe pipeline (no resource touched)
                           └─ AI required ─ AiResourceController.acquireForGeneration()
                                                │  lazy runtime creation, load if not READY
                                                ▼
                                          ResourceLease(runtime, profile, budget)
                                                │  generation (one at a time, as before)
                                                ▼
                                          lease.release() ── keep warm ── idle timeout ── unload
```

## 2. Lazy load

Load 0 by construction for: app start, the chat tab, a new chat, the history and its drawer,
templates, Think, the Fast Path, DocumentSearch, and the model manager screen — none of them call
`acquireForGeneration`, and only `acquireForGeneration` can load. The first genuinely generative
request creates the runtime (the holder's one instance) and loads the selected model; with no
selected model the orchestrator answers the existing setup flow before the controller is reached.

## 3. Keep warm / idle unload

A release does not unload: it stamps the generation and arms the idle clock. The unload fires when
the window passes **and** no lease is active; a new acquire cancels the pending clock. The window
is `IdleUnloadPolicy(timeoutMillis = 2 min, reducedTimeoutMillis = 30 s under ECO / LIMITED)` — a
**configurable policy, not a decided product default**; the S20 comparison below informs the human
decision later.

Only a generation moves the deadline. A Fast Path hit, a template run, a Think session, plain chat
reading, the drawer — none of them arm, reset or extend it (policy- and journey-pinned: fast
traffic over a warm model still meets the same deadline). The app going to the background is not
an unload either; background + the elapsed window unloads as normal.

## 4. Resource states, concurrency, the lease

The controller's state is **derived from the runtime** (`Unloaded / Loading / Ready(model) /
Generating(model) / Unloading / Failed(reason)`) — never a second state machine that could lie
Ready after a failed load. One mutex serializes every transition: simultaneous requests load once
and share the warm model; an unload and a load cannot interleave; a duplicate unload frees nothing
twice (the runtime's own lock then serializes the native side — the JNI handle is owned by
`LocalModelRuntimeImpl`, nulled under its engine lock, exactly as before). A `ResourceLease` is
idempotent to release; the idle clock waits for the **last** active lease.

## 5. Memory pressure (the Phase 3 amendment, moved whole)

- **MODERATE** — nothing.
- **LOW** — an idle model unloads at once; with a lease active the unload is **deferred to the
  release** (the S20 rule: loading a model raises the trim level by itself; nothing is cancelled
  mid-answer).
- **CRITICAL** — unloads immediately; the runtime stops a running generation itself, the request
  surfaces as a cancelled, write-free outcome (no proposal → nothing enters the Safe Intent
  Pipeline), and the release afterwards frees nothing again.

The trim hook (`MemoRippleApplication.onTrimMemory`) still maps the platform levels and hands the
pressure to the orchestrator, which now just forwards it to the controller — no callback touches
the native side directly.

## 6. Power Saver / thermal / profile

`PerformanceProfile` with the fixed precedence **LOW_MEMORY > THERMAL_LIMITED > ECO > NORMAL**:

- Power Save Mode is read **one-shot** at the moment a generation is budgeted
  (`PowerManager.isPowerSaveMode`; no receiver, no monitoring, no permission — Phase 7's
  `isOnline` philosophy). ECO shrinks the budget and the keep-warm window; it never forbids the
  AI and never switches the model.
- Thermal reuses the **existing** `ThermalGate` (no second monitor): THROTTLED (light/moderate)
  is the LIMITED profile; SEVERE still blocks a new generation exactly where it always did — in
  the orchestrator before the acquire and in the generator. No user-facing thermal wording
  changed; ordinary temperature swings are never announced.
- A deferred memory unload marks the in-flight profile LOW_MEMORY.

## 7. Generation budget

`GenerationBudget(maxRecentMessages, maxContextChars, maxMessageChars, maxOutputTokens)`:

| profile | window | output |
|---|---|---|
| NORMAL | 6 messages / 1,200 chars / 300 per message — **exactly today's `ActiveContextBudget`** | 256 tokens — today's default |
| ECO / THERMAL_LIMITED / LOW_MEMORY | 4 messages / 800 chars / 300 | 192 tokens |

The NORMAL ask is byte-for-byte the ask before this round (test-pinned). The reduced budget only
clips the conversation window to its newest lines and caps the output; **prompt v1 and the
grammar are untouched** (their byte-identity guards stand). Conversation Summary does not exist.

## 8. The Fast Path and everything else deterministic

The Fast Path never calls the controller — no acquire, no runtime, no clock. So do the template
runner, Think, `previewMemo`, the confirmed-write executor and every read of the chat. A warm
model over which only fast traffic flows reaches its idle deadline unchanged and unloads.
Architecture rule, pinned in tests and here: **Fast Path: no model resource. Decision /
generation: a lease when needed. Document operations: independent of the resource lifecycle.**

## 9. Model switch / delete / 「AIモデルなし」 / screens

`ModelManager.select / clearSelection / delete` keep their sequence (unload → selection change →
[delete files]) through `AiOrchestrator.release()`, which now waits for an ask in flight (the
standing rule: a running answer finishes first) and then unloads through the controller
(`reason=explicit`). Selecting a model still **never loads it** — the next generation lazy-loads
the new choice. **`ChatViewModel.onCleared` no longer unloads** (the 2026-09-23 brief supersedes
Phase 3's screen-tied rule): leaving the chat, closing the stage or switching tabs costs no
reload; the idle clock owns the routine unload.

## 10. Failures / process death

A failed load is `Refused` to the orchestrator (the existing error cards; nothing written) and the
controller's state stays honest (`Failed` / `Unloaded`, never `Ready`); the next ask retries the
load. A failed or cancelled generation produces no proposal, so nothing enters the pipeline and no
pending write can exist. Nothing about the resource state is persisted: a process restart begins
`Unloaded`; only `ai_selected_model_id` survives (DataStore, as before) and nothing auto-loads.

## 11. Observability

`AiResourceMetrics(loads, unloads, generations, lastLoadMillis, lastUnloadReason)` — internal
consistency, tests and debugging; never a product surface. Log lines carry lifecycle words only:
`AI_RESOURCE load_start / load_done millis= / load_failed reason= / unload reason=idle|memory_low|
memory_critical|explicit / profile=ECO / memory=LOW deferred=`. Never a user message, prompt,
document content, template answer or generated text (the standing AI logging rule scans these
lines too).

## 12. S20 measurements (2026-09-23, reference, never product UI)

Ministral 3 3B (Q4_K_M, 2.1 GB) through the debug developer path, in place, no personal-data
writes (details HANDOFF §16.82):

- **Chat open / fast SEARCH: load 0** (no `AI_RESOURCE` line at all; `route: fast=true` in ~20 ms).
- **First genuine ask (cold):** load **6,400 ms** (first touch) / **4,063–4,067 ms** on later
  cold loads (page cache); TTFT **15.6–15.8 s**; 2.85–2.86 tok/s; ≈ 26 s to the parsed answer.
- **Warm second ask:** **reload 0** (no `load_start`); TTFT **≈ 15.6 s** — unchanged, because
  TTFT is prompt-evaluation-bound on this CPU. Keep-warm buys back the 4–6.4 s load, not the TTFT.
- **Idle:** the 2-minute default fired at exactly +120 s after the release; under Battery Saver
  the ask logged `profile=ECO` and the reduced 30 s window fired at ≈ +30 s; the same model both
  times (no switch).
- **The amendment, observed live:** the very first load raised the trim level itself → `memory=LOW
  deferred=true` mid-request → the answer completed normally → `unload reason=memory_low`.

**Timeout trade-off observations** (the default stays a policy, the human decides): the reload
penalty is 4–6.4 s and warmth does not improve TTFT; a warm Ministral holds ≈ 4.7 GB and the S20
issues LOW trim of its own accord under memory competition — so a long window (5–10 min) buys
little over 2 min here (LOW tends to reclaim first), while a 30 s window costs a 4–6 s reload on
consecutive asks that straddle it. 2 min as the middle default and 30 s under ECO look sound on
this data.

## 13. Future — DecisionEngine (not in this round)

The controller is the resource half of the seam `ChatRouter` opened: a later DecisionEngine (a
small classifier, adaptive routing) would sit between the rules and the LLM and *ask* the
controller for a lease like any other generation. Not built now, by decision: no Decision Model,
no second runtime, no automatic model switching (hot ≠ swap 3B→1B), no 1B fallback, no semantic
search / embeddings, no summaries, no cloud, no autonomous background AI.

## 14. The DecisionEngine before the controller (Phase 3, 2026-09-23)

`docs/DECISION_ENGINE.md`: the deterministic decision sits **before** `acquireForGeneration` —
a CERTAIN operation and a clarification acquire nothing and load nothing, and deciding never
resets the idle clock; only NotApplicable reaches this controller and its lazy load.

## 15. The generation cache lives inside the context the controller owns (Phase 4B, 2026-09-23)

`docs/GENERATION_EFFICIENCY.md`: the prefix reuse is a property of the loaded native context. Every
unload this controller performs — idle, memory LOW / CRITICAL, thermal, an explicit switch or
delete — destroys that context and with it the cache, and the runtime forgets its key; a warm model
keeps its cache only as long as it keeps its context. The KV buffer is allocated at load for the
whole `n_ctx` whether or not it holds tokens, so keeping it filled adds no RAM.
