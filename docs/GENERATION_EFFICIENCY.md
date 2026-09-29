# Generation Efficiency — Phase 4B (TTFT)

> Human brief 2026-09-23 (`feature/generation-efficiency`, stacked on `feature/decision-engine`).
> The Fast Path and the DecisionEngine removed the generations that were never needed; this round
> shortens the wait when a generation **is** needed — without touching prompt v1, the grammar, the
> intent semantics or any safety boundary. Measure first, then change.

## 1. Baseline (S20, Phase 2, Ministral 3 3B Q4_K_M)

Cold load 4–6.4 s; TTFT ≈ 15.6 s **warm or cold** — keep-warm buys back the load and nothing
else, so the wait is inside the request itself: prompt evaluation.

## 2. Audit — the native call path, exactly

`ChatViewModel.askText` → `interact()` → `AiResourceController.acquireForGeneration` (lazy load) →
`StructuredIntentGenerator.generate` → `LocalModelRuntimeImpl.generate` → `LlamaCppEngine.render`
(the model's own chat template around system + user, via JNI) → `LlamaCppEngine.generate` →
`nativeGenerate` (`app/src/main/cpp/memoripple_llm.cpp`, llama.cpp b11039, CPU):

| item | finding |
|---|---|
| context creation / destruction | one `llama_context` per load (`n_ctx` 4096 from the descriptor, `n_batch = n_ubatch = 512`, 4 threads, `no_perf = true`), destroyed by `llama_free` on unload |
| tokenizer | `llama_tokenize` once per request, inside `nativeGenerate`; **untimed** before this round |
| KV cache | **`llama_memory_clear` at the top of every `nativeGenerate`** — the whole prompt was re-evaluated on every request; no reuse, no prefix detection, no rewind |
| prompt construction | Kotlin: system = the frozen `intent_system.v1.txt` (2,021 chars); user turn = `ConversationPromptComposer.userMessage` = 「直前の会話」 window lines + 「表示中の候補」 (`result_N: title`) + 「入力」; native: the chat template around both |
| grammar | `llama_sampler_init_grammar` per request, then greedy — deterministic output for a given prompt |
| routes | **one** generation route exists (STRUCTURED_INTENT with the grammar); there is no free-text conversational generation in the product |
| timings before | `promptTokens`, `generatedTokens`, `ttftMs` (from the first decode to the first sample — so it *includes* prompt evaluation), `totalMs` |
| load settings | `LLAMA_LOAD_MODE_MMAP`, no mlock, `n_gpu_layers 0` |
| ownership | the runtime owns the handle; the resource controller owns the runtime's life (idle / memory / thermal / switch / delete → `unload` → `llama_free`) |
| process death | nothing persisted; a fresh context per load |

**Decomposition of the 15.6 s:** the prompt is ≈ 550–650 tokens (prompt v1 alone is 510 / 553
tokens on the two candidates, docs/LLM_PHASE0.md); at the S20's prompt-evaluation rate the
system prompt is the whole wait — see §7 for the measured split.

## 3. Prompt / context inventory (one request)

| part | chars | tokens (S20, Ministral) | stable across requests? |
|---|---|---|---|
| chat-template header | model's | few | yes |
| system (prompt v1) | 2,021 | ≈ 550 | **yes — byte-identical every request** |
| 「直前の会話」 window | ≤ 1,200 (6 lines × ≤ 300) | ≤ ~400 | grows / slides per turn (oldest first) |
| 「表示中の候補」 | ≤ 50 × ~40 | ≤ ~150 | per request |
| 「入力」 | ≤ 200 | ≤ ~70 | per request |
| grammar | 1,279 | not in the prompt (sampler) | — |

Duplicate-context audit: the system prompt is sent once; the schema and output format live only
in prompt v1; the shown results appear once (structured) — the assistant's search result line is a
count (「3件見つかりました。」), not the titles; the anchor is never sent (Phase 8: app-side only);
dates are tokens. The only waste found: **UI-only lines** in the window — write confirmations
(「…の作成内容を確認してください。」, 「追記しました。」) and failure cards (the setup-card text, the
thermal message) — which carry tokens and no meaning for an intent.

## 4. Safe reductions adopted

- `ActiveContextBudget.window` leaves out `WRITE_EVENT` and `FAILURE` lines; `TEXT` (the user's
  words, the assistant's plain text and clarification questions) and `RESULT` (「…を開きました。」,
  「3件見つかりました。」) stay. Bounds (6 / 1,200 / 300) and order are unchanged; a transcript with no
  UI events yields exactly the window it did before (test-pinned); NORMAL semantics unchanged.
- Nothing else in the prompt was touched. Prompt v1 and the grammar are byte-identical (policy-pinned).

## 5. Prefix / KV reuse — the audit and the prototype

**Audit.** llama.cpp's memory API allows a token-level rewind: `llama_memory_seq_rm(mem, 0, p0, -1)`
drops every position ≥ p0 and keeps the rest; the KV for a token depends only on the tokens
before it, so a request whose token sequence shares a prefix with the previous request can reuse
that prefix's KV exactly. Correctness conditions: (1) compare **tokens**, never string offsets;
(2) the previous request's tokens must be exactly what the context holds (prompt + the generated
tokens that were decoded); (3) an identical prompt must still evaluate ≥ 1 token so the logits are
fresh; (4) any error empties everything; (5) the grammar sampler is per request and independent of
the KV; (6) a new context (load) starts empty. Memory: the KV buffer for `n_ctx` is allocated at
context creation whether or not it holds tokens — keeping it filled costs **no extra RAM** (measured
in §7).

**Prototype (adopted, behind an identity).** `nativeGenerate(…, allowPrefixReuse)` keeps
`Session.cache` (the tokens whose KV the context holds), computes `common_prefix(cache, toks)`,
rewinds with `llama_memory_seq_rm`, evaluates only the suffix, and reports `reusedTokens` /
`evaluatedTokens`; `nativeResetCache` clears both. Kotlin allows reuse only inside one
**`GenerationCacheKey(modelId, promptVersion, grammarHash, conversationId, route)`**: a missing or
changed key resets the engine first; the key is forgotten on unload and after an error.

## 6. Cache identity and invalidation

Separate identities, never shared: another conversation (the rule of the brief — even the
system-prompt part is re-evaluated on a switch), another route (only STRUCTURED_INTENT exists; a
future free-text route is another identity), another grammar, another prompt version, another
model. Invalidated by construction: model switch, model delete, idle unload, memory LOW / CRITICAL
unload, thermal release, `release()`, process death — each destroys the native context (`llama_free`)
and the runtime's key. A modified past turn changes the token sequence, so the common prefix ends
at the change and everything after it is re-evaluated (no wrong continuation is possible).

## 7. S20 benchmark

See HANDOFF §16.85 and the table below (filled from `GenerationBenchmarkSmokeTest`, Ministral 3 3B
Q4_K_M from the Phase 0 bench directory, read-only; three conditions × cold / warm × baseline /
optimized × three repeats; greedy sampling, so the intent JSON of baseline and optimized is
compared for equality).

**Load:** 6,761 ms (cold, mmap). **PSS:** 137 MB before → 4,769 MB loaded → 4,882 MB after 20
generations → 216 MB after unload. **Thermal:** 0 at the start, 1 (LIGHT) from the third
`nearMax` baseline run onwards — never SEVERE, and no LOW trim or kill during the run.

**Where TTFT goes.** `promptBuild` 0–4 ms, `tokenize` 4–13 ms, **`promptEval` = TTFT − ~30 ms in
every single run**. Prompt evaluation *is* the wait: 24–36 tokens/s on this CPU. The 15.6 s of
the Phase 2 baseline was ~550 tokens of prompt v1 being re-evaluated on every request.

**Baseline (no cache key → a full evaluation every time), three repeats:**

| condition | prompt tokens | promptEval (ms) | TTFT (ms) |
|---|---|---|---|
| short (2-line window) | 620 | 17,048 / 17,398 / 17,969 | 17,077 / 17,418 / 17,990 |
| nearMax (6 lines, ~1,150 chars) | 1,451 | 47,381 / 48,316 / 49,115 | 47,402 / 48,338 / 49,141 |
| structured intent (no window) | 564 | 21,594 / 21,495 / 21,609 | 21,621 / 21,537 / 21,650 |

**Optimized (one conversation identity).** The *repeat of an identical prompt* is the upper bound —
everything but the last token is reused:

| condition | prompt tokens | reused / evaluated | promptEval (ms) | TTFT (ms) |
|---|---|---|---|---|
| short | 620 | 619 / 1 | 161 / 162 | **188 / 188** |
| nearMax | 1,451 | 1,450 / 1 | 191 / 196 | **217 / 222** |
| structured intent | 564 | 563 / 1 | 139 / 161 | **166 / 187** |

**The realistic case — a new turn in a growing conversation** (the system prefix is reused, the
changed user turn is evaluated):

| request | prompt tokens | reused / evaluated | promptEval (ms) | TTFT (ms) | full-eval equivalent |
|---|---|---|---|---|---|
| consecutive #0 | 891 | 531 / 360 | 14,687 | 14,732 | ≈ 30–36 s at the measured 24–30 tok/s |
| consecutive #1 | 1,127 | 617 / 510 | 21,230 | 21,274 | ≈ 38–46 s |
| intent, first ask of the identity | 564 | 531 / 33 | 1,781 | **1,809** | 21.5 s measured |

**Honest reading.** The 188 ms numbers are the ideal case (the same sentence asked twice) and are
not what a user will usually see. What a user gets is the **system-prompt share removed from every
ask after the first in a conversation**: 531–617 tokens of the 564–1,451 total, i.e. **≈ 40–95 % of
the prompt evaluation**, and the first ask of a conversation is unchanged. A short ask whose window
barely changes drops from ~17–18 s to ~1.8 s; a near-max window that mostly changes drops from
~47–49 s to ~21 s (the reused fraction is smaller). Semantics are unchanged: the intent JSON of
baseline and optimized is **identical in all three conditions** (greedy sampling; hashes compared,
never logged).

**Memory.** +113 MB PSS between "just loaded" and "after 20 generations" — the KV buffer is
allocated for the whole `n_ctx` at load, so keeping it filled costs paging, not new allocation;
no LOW trim, no kill, thermal never above LIGHT.

## 8. Adopted / rejected

**Adopted:** the timing decomposition (numbers only, no product UI); the UI-only-line pruning;
the token-level prefix reuse inside one conversation identity.
**Rejected / not done:** prompt v1 rewrite; any change to threads, batch, context size, mmap,
mlock, sampling (unmeasured micro-optimizations); a tokenization cache (tokenization measured at
single-digit milliseconds — see §7); cross-conversation reuse of the system prefix (possible and
content-free, but excluded by the brief's rule; the cost is one full evaluation per conversation
switch); output-token reductions counted as speed-ups.

## 9. Future work

**The measured next bottleneck is the prompt-evaluation rate itself: 24–36 tokens/s** (4 threads,
`n_batch = n_ubatch = 512`, CPU only). The first ask of every conversation still pays the full
~550-token system prompt at that rate (≈ 17–21 s). Two candidates for a later round, both needing
an A/B on the S20 before anything changes: the thread count (4 vs the S20's big cores) and the
batch / ubatch geometry. Neither was touched here — the brief forbids unmeasured changes, and this
round's measurement was of the reuse, not of the geometry.


If a free-text generation route ever exists it needs its own identity and its own budget; the
cross-conversation system-prefix reuse could be reconsidered with a human decision since the
reused tokens are the byte-identical prompt v1; llama.cpp's own perf counters (`no_perf = false`)
could replace the bridge's stopwatch if finer native timing is wanted.
