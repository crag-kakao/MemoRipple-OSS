# AI Confirmed Write — Local LLM Phase 4 (Confirmed Write Execution)

> Branch `feature/ai-confirmed-write` from `main` `8809abe`, 2026-09-20. The first round in which
> a write proposed by the model reaches the boundary — **only after the user presses the confirm
> button on its preview**, once. Read `docs/AI_CHAT_PREVIEW.md` (Phase 3: the modes, the
> orchestrator, the previews) first; this document adds only what Phase 4 adds.

## 1. The Human Confirmation boundary

```
LLM → IntentProposal → SemanticValidator → Resolver → ExecutionPolicy → CommandPreview
                                                                          ↓
                                                              Human Confirmation  ← the user's tap, nothing else
                                                                          ↓
                                                   ConfirmedCommand → CommandExecutor → DocumentAccess
```

- A write preview leaves the orchestrator as `AiInteractionResult.WritePreview(preview, pending)`;
  `pending` is a `PendingWrite` — an opaque, **single-use** ticket wrapping the decision the policy
  made. Nothing on it can write; it exposes the preview and whether it was taken.
- `AiOrchestrator.execute(pending)` is the one place in main sources that calls `confirm()`
  (`AiConfirmedWritePolicyTest` reads every file). It takes the ticket atomically, builds the
  `ConfirmedCommand`, runs the `CommandExecutor` and returns a `WriteOutcome`. A taken ticket
  answers `AlreadyExecuted` and runs nothing.
- The only caller of `execute` is `ChatViewModel.confirmWrite()`, and the only caller of that is
  the preview card's confirm button. No generator, resolver, runtime, data adapter, application
  callback, timer, `LaunchedEffect` or recomposition can reach it — pinned by source policy.
- The model cannot confirm: a `confidence` or `autoConfirm` in its answer changes nothing
  (`AiConfirmedWriteOrchestratorTest`, `ExecutionPolicyTest`).

## 2. Audit (before RED)

| # | Looked at | Found |
|---|---|---|
| 1 | Phase 3 preview UI | One card per write kind, キャンセル only; result and preview in `AiPanelState`; `BackHandler` only while a preview or candidates are up. |
| 2–4 | `RequiresConfirmation.confirm()` / `ConfirmedCommand` | `confirm()` is the only constructor path (internal ctor); nothing called it; the Phase 3 orchestrator dropped the decision, so there was no handle to confirm. |
| 5 | `CommandExecutor` | Direct decision or `ConfirmedCommand`; CREATE with text = create + append against the fresh version; USE_TEMPLATE = create memo + append the body. |
| 6–7 | `DocumentAccess.create / append`, `DocumentVersion` | `create` is a single repository insert; `append` is read → compare `updatedAt` → write in one Room transaction; Conflict / ReadOnly (LOCKED journal) / NotFound / Rejected (future journal day). |
| 8 | Conflict tests | Executor-level and Phase 3 orchestrator-level (stale version → Conflict, nothing written). |
| 9 | LOCKED / READ_ONLY | Refused by the Resolver before a preview; refused again inside the transaction at write time. |
| 10 | Create-empty semantics | Memo / outline rows are kept; a journal left blank is released on leaving, as always. |
| 11 | `TemplateLookupAdapter` | By id, then by name; the body is used as it is. |
| 12 | Snackbar / banners | The wall's lifecycle snackbar (元に戻す); チャット uses result cards, so Phase 4 keeps cards. |
| 13–14 | Undo / history | **No undo for `DocumentAccess.create` / `append` and no repository-level body revert.** The wall has bulk archive / trash undo through the repository; the memo editor has an in-memory text undo stack. |
| 15 | Room transaction boundary | `withTransaction` around append; create is one insert. |
| 16–17 | Navigation after create, open callback | Chat v0 opens what it creates through `onOpen(DocumentRef)` → `DocumentNavigator`; Phase 3's one-shot `pendingOpen` serves success navigation. |
| 18–19 | Preview ownership, Back | The view model; Back closes the preview. |
| 20 | Double submit | Settings use `enabled = !isBusy`; nothing shared — the view model gets an EXECUTING phase and the ticket is single-use. |

**Undo decision (the brief's rule):** no safe undo exists for these writes, so Phase 4 adds no
AI-specific undo. Explicit confirmation, version conflict, duplicate-submit prevention and clear
feedback come first. The bulk lifecycle undo is not reusable here (it restores archive / trash
state, not a body), so nothing is proposed beyond noting it.

## 3. Confirmation UI

The Phase 3 preview card gains a second button beside キャンセル: **作成** (CREATE, USE_TEMPLATE) or
**追記** (APPEND), tag `chat_ai_preview_confirm`. Its caption reads 「「作成」を押すまで書き込みません。」.
Pressing it sets the AI phase to EXECUTING at once: both buttons are disabled, the caption becomes
「書き込んでいます…」 (`chat_ai_executing`), Back does nothing until the write returns, and
`confirmWrite()` refuses a second press. One tap = one execution; a double tap is one write
(instrumentation-guarded for CREATE, APPEND and USE_TEMPLATE).

## 4. CREATE

Preview: 種類 (with the day for a journal), 内容 (or 「空のまま作成」). Confirm → `ConfirmedCommand`
→ `create`, then the first text as an append against the fresh version. Success opens the new
document through the navigator; an empty memo opens writing, as the existing rule says. Two
journals on the same day stay two documents.

## 5. APPEND

Preview: 追記先 (kind + title), 現在の内容, 追加する内容, and the held version
(「M/d HH:mm 時点の内容に対する追記です」). Confirm → `append(ref, text, expectedVersion = the preview's)`.
Success opens the target (the first-candidate choice from the brief; Back returns to チャット with
the success card). Journals and outlines go through the same confirmation.

## 6. USE_TEMPLATE

Preview: the template's name and the resulting body; the destination kind is MEMO. Confirm →
one memo from the body as it is. No field schema.

## 7. Conflict

A document edited elsewhere after the preview (the editor, a restore, another device) fails the
version compare inside the transaction: `WriteOutcome.Conflict`, nothing written, and the ticket
is spent — **no silent retry with the newer version**. The card says 「内容が変更されたため、追記できま
せんでした」「もう一度内容を確認してください。現在のデータは変更されていません。」. A new ask makes a new
preview.

## 8. Read-only, not found, rejected, failed

A LOCKED journal never reaches a preview (Invalid); a journal locked *after* the preview is
`ReadOnly` at execution, untouched. A target removed after the preview is `NotFound`. A
boundary rejection (a journal day that is no longer today) is `Rejected`. A throwing boundary is
`Failed` with the detail in the log only. Every card ends with 「現在のデータは変更されていません」.
`WriteOutcome` = Success(ref) / Conflict / ReadOnly / NotFound / Rejected / Failed / AlreadyExecuted;
the domain's `ExecutionResult` is not shown to the screen.

## 9. Duplicate submit and idempotency

Three layers: the button is disabled while EXECUTING; `confirmWrite()` returns unless the phase
is DONE with a preview and no execution is running; `PendingWrite.take()` is an atomic
compare-and-set, so even two concurrent `execute` calls write once (JVM test). The executor is
not idempotent by itself (a second create would be a second document); the ticket is what makes
the confirmation idempotent.

## 10. Process death

Unchanged from Phase 3: `SavedStateHandle` holds mode and input only. A preview, its ticket, the
resolved command and the raw output are memory. Process death while a preview is shown loses the
preview → nothing is written, nothing resumes (`ChatViewModelConfirmedWriteInstrumentationTest`).
Rotation keeps the preview because the view model survives; the ticket goes with it.

## 11. Runtime independence

The write is not inference. After the preview the model may be unloaded (leaving the mode, the
idle timer, memory pressure); `execute` touches neither the runtime nor the thermal gate, and a
SEVERE thermal status does not stop a confirmed write. Nothing re-infers before the confirmation.
Verified on the JVM and the emulator (runtime released, thermal 3, write succeeds, no reload).

## 12. Cancel and Back

キャンセル: zero writes, the preview and its ticket dropped, the result context emptied, the AI
input kept. Back on a preview = キャンセル; Back while executing does nothing; Back after a success
that opened a document returns to チャット in the AI mode with the success card; a failure card
leaves the AI mode usable; then top-level Back → メモ as before.

## 13. Navigation after a write

CREATE → the created document; APPEND → the target; USE_TEMPLATE → the created memo — all through
`pendingOpen` → `DocumentNavigator.open(DocumentRef)`, never a route string in the screen. The
チャット entry stays on the back stack, so Back comes home. SEARCH / OPEN direct paths are unchanged
and show no confirm.

## 14. Tests

JVM: `AiConfirmedWriteOrchestratorTest` 20 (ticket without a write, no ticket on reads / refusals,
confidence ignored, CREATE with text / empty, APPEND with the held version, TEMPLATE, single-use
and concurrent tickets, CREATE once, conflict with one attempt and no retry, locked-after-preview,
locked-before-preview, removed target, rejected, throwing boundary, runtime unloaded + SEVERE,
two journals a day, no future journal, outcome shape, opaque ticket), `AiConfirmedWritePolicyTest` 5
(one confirm() inside execute, nothing below the boundary or in the application, the one UI
handler with no timer, nothing saved / no table, official path with reads direct); Phase 3
policy tests revised for the confirm button and the single call site.

Device (emulator, scripted runtime, real boundary): `AiConfirmedWriteInstrumentationTest` 13 —
cancel writes nothing for all three; CREATE once + opens + double tap; empty CREATE opens writing;
APPEND once with the held version + opens; journal and outline appends; TEMPLATE once + opens;
conflict untouched with no retry; locked-after-preview read-only, locked-before invalid; removed
target not found; reads and refusals show no confirm; Back closes without writing then leaves;
model unloaded + SEVERE and the write still runs, context clean; two journals a day.
`ChatViewModelConfirmedWriteInstrumentationTest` 2 (one execution per ticket with the EXECUTING
phase refusing a second confirm; a rebuilt view model has no preview and confirm does nothing).
Phase 3 `AiChatPreviewInstrumentationTest` 12 and Chat v0 `ChatFoundationInstrumentationTest` 8
unchanged in behaviour and green.

## 15. Verification on devices

- **Emulator:** all of §14 in the one-process suite (the gate).
- **S20 (`AiConfirmedWriteSmokeTest`, real Qwen3-4B, skipped without `-e llmModelPath`):** the only
  document written is a memo the test itself makes through AI → preview → confirm, appended
  through AI → preview → confirm when the model names it, and removed by id afterwards (trash →
  permanent delete); every other row is compared before and after. No existing document is
  touched. Result: HANDOFF §16.53 and below.

**Result (2026-09-20 01:36–01:38 JST, S20, Qwen3-4B-Instruct-2507 Q4_K_M, USB,
thermal 0 before and after, branch debug build installed in place, versionName 1.1.0):** `OK (1 test)`.

| Step | Card / effect | Wall time |
|---|---|---|
| ask 1 「新しいメモに『AI動作確認 14274』と書いて」 | `chat_ai_preview_create` (nothing written while shown) | 36.2 s |
| confirm 作成 | exactly one memo made, body 「AI動作確認 14274」, opened; Back → 「作成しました」 | < 1 s |
| ask 2 「AI動作確認 14274に『追記テスト』を追記して」 | `chat_ai_needs_information` — the model gave `text` but no target (as in Phase 3); no write | 31.9 s |
| leave the AI mode | runtime UNLOADED | < 1 s |

Every row other than the memo made here was byte-identical before and after; the memo was
removed afterwards (trash → permanent delete by id); the developer file removed; no existing
document was touched. The confirmed APPEND on the device therefore rests on the emulator suite
(the real boundary with a scripted model), not on the S20 — the model's target extraction is the
open case C item, not the confirmation path.

## 16. Not in Phase 4

Automatic write, conversation history, AI draft persistence, model download UI, user model
selector, background agent, UPDATE / REPLACE / DELETE / MOVE / RENAME / ARCHIVE / RESTORE, an
AI-specific undo. Room 24 / Backup 18 unchanged.
