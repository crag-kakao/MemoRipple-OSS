# AI Chat Preview — Local LLM Phase 3 (AI Orchestrator / Chat Integration Preview)

> Branch `feature/ai-chat-preview` from `main` `3c6c1d5`, 2026-09-20. The first round that
> connects the チャット screen to the local runtime. Reads run; **every write stops at a preview**;
> no automatic write exists and no control on the screen can reach the boundary's `create` /
> `append`. Read `docs/CHAT_FOUNDATION.md` (Chat v0), `docs/AI_SAFE_INTENT_PIPELINE.md` (Phase 1)
> and `docs/LOCAL_LLM_RUNTIME.md` (Phase 2) first; this document adds only what Phase 3 adds.

## 1. Audit (before RED)

| # | Looked at | Found |
|---|---|---|
| 1 | Chat v0 UI / `ChatViewModel` | One screen file, one view model; four callbacks from the app (open ref, templates, calendar, settings); everything typed is a search; commands create through the boundary. |
| 2 | Saved state | `chatQuery`, `chatKinds`, `chatDatePreset` only; results are re-searched, never saved. |
| 3 | Chat top-level navigation | `Routes.CHAT` with `navigateTopLevel`; Back from the tab lands on メモ through the start destination; the screen had no `BackHandler`. |
| 4 | `DocumentNavigator` | Private to `MemoRippleApp`; `open(DocumentRef)` is the only door; `ChatRoute` receives `documentNavigator::open`. |
| 5 | Phase 1 `domain/ai` | Validator, Resolver (Ambiguous / NotFound / NeedsInformation / KIND_MISMATCH / LOCKED read-only), `ExecutionPolicy` (Direct for SEARCH / OPEN, RequiresConfirmation + `CommandPreview` for every write), `CommandExecutor` (Direct or `ConfirmedCommand` only). |
| 6 | Phase 2 `LocalIntentPipeline` | Ends at the Resolver; takes an `AiResultContext`; the generator checks thermal, then runtime state, then generates and parses. |
| 7 | `LocalModelRuntimeHolder` | Lazy on the application; the `LlamaCppEngine` constructor already loads the native library, so nothing may construct it before the CPU feature check. |
| 8 | `RuntimeState` | UNLOADED / LOADING / READY / GENERATING / UNLOADING / FAILED — the screen shows LOADING and GENERATING. |
| 9 | `ThermalGate` | Injectable; the platform gate reads `PowerManager`; 0 ALLOWED, 1–2 THROTTLED, ≥ 3 BLOCKED. |
| 10 | `ModelProfiles` | Two candidates, no default. |
| 11 | `DocumentAccess` / `DocumentSearch` | v1 scope; `append` is version-checked; `create` makes an empty document. |
| 12 | Templates route | Reached from the template chip; `TemplateLookupAdapter` serves the Resolver. |
| 13 | Dialog / sheet / card parts | `ProductEmptyState`, `ProductInfoBanner`, `SectionHeader`, chat `ResultRow`; no segmented control in the app yet (Material 3 provides one). |
| 14 | Loading / error parts | Chat v0 shows `検索中…` as a text line; errors are worded, never technical (DESIGN_SYSTEM "Snackbar and errors"). |
| 15 | Recreation policy | The view model survives rotation; process death restores from the saved keys; instrumentation runs in `TestMemoRippleApplication`, which shares the real database and clears it in most chat tests — those must never run on the S20. |
| 16 | Model file existence | `File.isFile` under `noBackupFilesDir/models/<name>` inside `load`. |
| 17 | Developer-only model path injection | Only the Phase 2 smoke's instrumentation arguments (`llmModelPath`, `llmModelId`) building a `DeveloperPath` descriptor. |

Two Phase 1 / 2 policy tests asserted that `ui/chat` never mentions `domain.ai` ("Chat v0 unchanged
until Phase 3"); they were revised to the Phase 3 rule (the orchestrator is the only door), not
deleted.

## 2. Search mode / AI mode

チャット has a segmented control **[ 検索 ] [ AI ]** at the top (`chat_mode_search` / `chat_mode_ai`).

- **検索** is the default and is v0 unchanged: the input reads 「メモ・日記を検索…」, the date chips,
  the kind chips, the template chip and the 定型操作 stay as they were; free text is 100 % search;
  nothing in the search mode touches a model. `ChatFoundationInstrumentationTest` (v0) stays green
  around the new mode.
- **AI** has its own input (「MemoRippleに頼む…」, ImeAction Send, a send button `chat_ai_send`) and
  a one-line note that the mode finds, opens and *shows what a write would do* — it does not say the
  AI rewrites anything. The search input is never repurposed.
- If the runtime is unavailable (no model, unsupported CPU, hot device, a failure), the AI mode says
  so in one card and the search mode keeps working. Chat never depends on the model.

## 3. AI Orchestrator (`domain/ai/AiOrchestrator.kt`)

The screen never calls a generator, a resolver or an executor. It calls one interface:

```kotlin
interface AiOrchestrator {
    suspend fun interact(userText: String, context: AiResultContext, onProgress: (AiProgress) -> Unit = {}): AiInteractionResult
    fun runtimeState(): RuntimeState
    suspend fun release()           // leaving the AI mode / idle timer: unload
}
```

`LocalAiOrchestrator` runs, in this order:

```
thermal gate (SEVERE → ThermalBlocked, before anything is loaded)
→ ModelSelection.selected()      (null → ModelUnavailable(NO_MODEL_CONFIGURED))
→ runtime()                      (created on the first ask, never when the screen opens)
→ load if not READY              (MODEL_FILE_MISSING / UNSUPPORTED_DEVICE → ModelUnavailable; else RuntimeError(LOAD))
→ StructuredIntentGenerator      (prompt v1 + GBNF; Refused → ThermalBlocked / RuntimeError(GENERATION | PARSE))
→ Resolver (SemanticValidator inside) → ExecutionPolicy.decide
→ Direct        → CommandExecutor.execute(direct)  → SearchResults | Open
   RequiresConfirmation → WritePreview(preview)     ← nothing else leaves; confirm() is never called
   Blocked.*    → Unknown | Invalid | NeedsInformation | Ambiguous | NotFound
```

One interaction at a time (a second waits on the mutex). A throwing runtime becomes
`RuntimeError`, never a crash. `AiChatPreviewPolicyTest` reads the sources: the executor is
constructed only by the orchestrator and its assembly, no main source calls `.confirm(`, and
`ExecutionDecision.Direct` is the only executed branch.

## 4. UI result types

```kotlin
sealed interface AiInteractionResult {
    SearchResults(query, results)           // ran; rows reuse the chat result row
    Open(target: DocumentSummary)           // unique OPEN; handed to the navigator once (pendingOpen)
    WritePreview(preview: CommandPreview)   // CREATE / APPEND / USE_TEMPLATE; cancel only
    Ambiguous(candidates)                   // choose; the choice is app-side, no second generation
    NeedsInformation(fields, intent)        // "追記先が分かりません" / "開く対象が分かりません" …
    NotFound(field)                         // name or template nobody has
    Invalid(reasons)                        // e.g. TARGET_READ_ONLY, KIND_MISMATCH, REF_NOT_IN_CONTEXT
    Unknown                                 // UNKNOWN intent, or a forbidden verb the model tried
    ThermalBlocked
    ModelUnavailable(reason)                // NO_MODEL_CONFIGURED | MODEL_FILE_MISSING | UNSUPPORTED_DEVICE
    RuntimeError(stage, developerDetail)    // LOAD | GENERATION | PARSE; the detail goes to the log only
}
```

User wording (screen) vs developer detail (log): the view model logs `RuntimeError.developerDetail`
under the tag `AiChat`; the screen shows 「AIモデルを読み込めませんでした」 / 「AIが応答できませんでした」 /
「AIの応答を理解できませんでした」 and 「現在のデータは変更されていません」. No status code, exception
name or model name reaches the screen.

Cards and their tags: `chat_ai_search_results`, `chat_ai_opened`, `chat_ai_candidates` +
`chat_ai_candidate_<kind>_<id>`, `chat_ai_preview_create` / `_append` / `_template` with
`chat_ai_preview_version` and `chat_ai_preview_cancel`, `chat_ai_needs_information`,
`chat_ai_not_found`, `chat_ai_invalid`, `chat_ai_unknown`, `chat_ai_thermal_blocked`,
`chat_ai_model_unavailable`, `chat_ai_runtime_error`; status lines `chat_ai_status_loading`
(「AIモデルを読み込んでいます…」) and `chat_ai_status_generating` (「考えています…」).

## 5. Write preview rule

> **Superseded in part by Phase 4 (2026-09-20, `docs/AI_CONFIRMED_WRITE.md`):** the preview card now
> offers キャンセル and one confirm button; the confirm runs the write once through the Human
> Confirmation boundary. Everything else in this section — what the preview shows, nothing written
> while it is shown, the held version as the guard — stands.

CREATE, APPEND and USE_TEMPLATE reach `ExecutionDecision.RequiresConfirmation`; the orchestrator
hands the screen the `CommandPreview` and **nothing else** — no `ConfirmedCommand`, no
`ResolvedCommand`. The preview card shows:

- Create: 種類 (メモ / アウトライン / 日記 with the day for a journal), 内容 (or 「空のまま作成」);
- Append: 追記先 (kind + title), 現在の内容 (up to 6 lines), 追加する内容, and the held version
  (「M/d HH:mm 時点の内容に対する追記です」, `chat_ai_preview_version`);
- Template: the template's name and body as it is — no field schema (Phase 0's evaluation schema
  never enters the product `MemoTemplate`).

Below it: 「この画面では内容の確認までです。まだ書き込みません。」 and **[ キャンセル ]** only. There is
no 実行 / 保存 / 追記 button, disabled or otherwise (`chat_ai_preview_confirm` does not exist —
a policy test says so). The database is byte-identical while and after a preview is shown
(`AiChatPreviewInstrumentationTest` snapshots memos, journals and templates); the held version
is the guard a later confirmation phase will use — a document that moved after the preview
answers CONFLICT through the domain path (`AiOrchestratorTest`).

Case C, the permanent fixture: 「MemoRipple開発に『Folder対応完了』を追記して」 → APPEND → the resolver
finds the unique target → the append preview (追記先 MemoRipple開発, 追加する内容 Folder対応完了, the
current body) → キャンセル. Still not appended, on the JVM, on the emulator and on the S20.

## 6. Ambiguity and NeedsInformation

- Several documents fit the name → `Ambiguous(candidates)` → 「どれを開きますか？」 with one row per
  candidate; nothing opens by itself; a tap opens that `DocumentRef` through the navigator and the
  list closes — an app-side step, not a second generation (`TestAiRuntime.requests` stays 1).
  The Phase 1 rule stands: an exact title match is preferred, so 「MemoRipple開発」 with a
  「MemoRipple開発 仕様」 beside it resolves uniquely; two documents titled exactly 「MemoRipple開発」
  are ambiguous.
- A required field the model did not give → `NeedsInformation(fields, intent)` → a card naming
  what is missing in the intent's words (追記先 / 開く対象 / 追記する内容 / どれを作るか / どのテンプレート /
  何を探すか) and 「対象や内容を含めて、もう一度入力してください」. There is no conversation, so the
  user types again; the `missingFields` a model names are questions, never a licence to guess.

## 7. Runtime states on the screen

First ask in the AI mode: UNLOADED → LOADING (「AIモデルを読み込んでいます…」) → READY → GENERATING
(「考えています…」) → the result card. A later ask while the model is loaded shows only
「考えています…」. `AiProgress` (LOADING_MODEL, GENERATING) is what the orchestrator reports; the view
model maps it to `AiPhase`. No further personification.

## 8. Thermal

The orchestrator checks the gate **before** the model is loaded (SEVERE → `ThermalBlocked`, no
load, no runtime even created), and the generator checks it again before generating. LIGHT /
MODERATE allow one generation. The screen says 「端末が熱いため、AIをいったん休ませています」 and that
the search mode still works. Verified on the JVM and on the emulator with an injected status —
the S20 is never heated to SEVERE on purpose.

## 9. Lifecycle (no resident model)

> **Amended by AI Resource Controller Phase 2 (2026-09-23, docs/AI_RESOURCE_CONTROLLER.md):** the
> screen-tied unload below is superseded — `ChatViewModel.onCleared` no longer releases, keep-warm
> outlives the chat, and the controller's idle clock (2 min / 30 s reduced), memory pressure,
> thermal policy or an explicit model switch do all the unloading. `AiIdleUnload` is gone; the
> idle rule lives in `IdleUnloadPolicy`. The rest of this section stands as history.

- The runtime is created on the first ask, not when チャット opens.
- ~~Leaving the AI mode or clearing the view model calls `release()` → unload~~ (superseded above;
  `release()` remains the model manager's explicit switch / deselect / delete path and still waits
  for an ask in flight).
- A conservative idle timer (2 minutes after the last **generation** — a fast ask or a template
  moves nothing) unloads a model nobody is using. Unload on LOW / CRITICAL memory stands.
- Phase 0's rule remains the reason: the S20 reaches thermal SEVERE within minutes of continuous
  inference; load → a command or a few → idle → unload.

## 10. Ephemeral state

Saved state (`SavedStateHandle`): `chatQuery`, `chatKinds`, `chatDatePreset` (v0) + `chatMode`,
`chatAiInput`. That is the whole list — a policy test reads the constants and every `set(` call.

**Never persisted** (memory of the view model only, regenerable): the raw model output, the
`IntentProposal`, the `ResolvedCommand`, the `CommandPreview`, the `AiInteractionResult`, the
`AiResultContext` (`result_N` ↔ `DocumentRef`) — not in `SavedStateHandle`, DataStore or Room.
`AiResultContext` and the result types are neither `Serializable` nor `Parcelable`. Rotation keeps
them (the view model survives); process death drops them and restores only mode and input
(`ChatViewModelStateInstrumentationTest`).

The result context is request-scoped: it is exactly the rows on screen. A new ask consumes it
and replaces it with its own search results (or nothing); dismissing a card empties it; leaving
the mode empties it. The model sees `result_N: title` lines and never an id or a kind.

## 11. No history, no write, no selector

- No `chat_sessions` / `chat_messages` / `ai_*` tables, no conversation memory, no draft
  persistence: Room stays 24, Backup 18. Only the latest result card is on screen.
- No write execution: `CommandExecutor` runs Direct reads only; `ConfirmedCommand` is produced by
  no one.
- No model download UI, no user-facing selector, no default: `InstalledModelSelection` (data/ai)
  answers with the first candidate whose file is installed under `noBackupFilesDir/models`, or, in
  a **debug** build, what `models/developer.properties` names (`modelId=…`, optional `path=…`);
  a developer file naming an unknown model is *no model*, never a guess; a release build ignores
  the file, so a developer path never reaches a shipped flow.
- The native engine is constructed only by `productLocalModelRuntime` after `CpuFeatures.hasDotProduct()`;
  on a CPU without dotprod a refusing runtime answers UNSUPPORTED_DEVICE and the library is never
  loaded (`AiChatPreviewPolicyTest.theNativeEngineIsNeverConstructedBeforeTheCpuFeatureCheck`).

## 12. Back behaviour

- Chat top-level: Back → メモ (unchanged).
- A document opened from an AI result: Back → チャット, still in the AI mode.
- A write preview on screen: Back → the preview closes (the `BackHandler` is enabled only then).
- A candidate list on screen: Back → the list closes. Then top-level Back → メモ.
- The wall's single `BackHandler` and the folder rules are untouched (this handler lives on the
  chat screen and is enabled only while a preview or a candidate list is up).

## 13. Tests

JVM: `AiOrchestratorTest` 35 (availability, thermal, load / generation / parse failures, a
throwing runtime, progress, SEARCH, unique / shown-ref / ambiguous OPEN, NeedsInformation,
UNKNOWN and a forbidden verb, NotFound, the three previews, case C with an untouched fake
boundary, a journal title-line target, a confidence that changes nothing, a LOCKED journal, the
held version → CONFLICT, no confirm in the source, the v1 scope, release / idle / serialisation of
asks, the prompt context), `ModelSelectionTest` 8, `AiChatPreviewPolicyTest` 7; the Phase 1 / 2
policy tests revised (`AiBoundaryPolicyTest`, `AiRuntimeAdapterPolicyTest`).

Device (emulator, one process, the scripted `TestAiRuntime` inside `TestMemoRippleApplication`;
parser → validator → resolver → policy → boundary → navigator real): `AiChatPreviewInstrumentationTest`
12 — search default and untouched; mode switch + recreation; an AI search rendering the v0 rows;
unique OPEN → document → Back to the AI mode; ambiguous → candidates → choice; NeedsInformation /
UNKNOWN / forbidden verb / LOCKED / malformed / non-JSON explained with the database unchanged;
case C preview with cancel only and a byte-identical database; CREATE / journal CREATE / template
previews making nothing; Back closes preview, then candidates, then leaves for メモ; no model /
missing file / unsupported CPU / hot device / load failure explained and search still working;
loading and thinking lines; unload on leaving the mode; the shown results as the next context.
`ChatViewModelStateInstrumentationTest` +1 (mode and input restored, nothing else saved).
`ChatFoundationInstrumentationTest` (v0) unchanged and green.

S20: `AiChatPreviewSmokeTest` — the real UI, the real runtime, a real model, the device's own data,
nothing cleared or inserted; skipped without `-e llmModelPath` (§14).

## 14. S20 smoke (Qwen3-4B-Instruct-2507, one model)

See §16.51 of `HANDOFF.md` for the record; the command:

```text
adb -s <serial> shell am instrument -w -r -e class io.github.cragcoffee.memoripple.AiChatPreviewSmokeTest \
  -e llmModelPath /data/local/tmp/llmbench/models/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf \
  -e llmModelId qwen3-4b-instruct-2507 \
  io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
```

The test writes the debug-only developer file naming the model, launches `MainActivity`, opens
チャット, switches to AI, asks 「昨日の日記を探して」 (expects the search-results card), asks case C
(expects a preview or a safe refusal, never an open, and a byte-identical database), asks
「今日の天気を教えて」 (expects a safe stop), leaves the AI mode (expects the runtime UNLOADED), and
removes the developer file.

**Result (2026-09-20 00:25–00:29 JST, S20, USB, thermal 0 at start, debug build of
the branch installed in place over the QA install, versionName 1.1.0, data intact):** `OK (1 test)`.

| Step | Card | Wall time | Notes |
|---|---|---|---|
| ask 1 「昨日の日記を探して」 | `chat_ai_search_results` | 35.9 s (load ≈ 5 s + generation) | 0 rows — the device holds no journal for 2026-09-19; raw: SEARCH / JOURNAL / YESTERDAY |
| ask 2 「MemoRipple開発に『Folder対応完了』を追記して」 | `chat_ai_needs_information` (「追記先が分かりません」) | 33.8 s | raw: APPEND, `text` = 「Folder対応完了」, `targetName` null, `missingFields` [targetName] — the model did not extract the target (prompt v1, Phase 0 known weakness); the device also holds no such document (0 candidates). Safe refusal; **database byte-identical** before / after |
| ask 3 「今日の天気を教えて」 | `chat_ai_unknown` | 32.0 s | raw: UNKNOWN (with a stray `dateToken: TODAY`, ignored) |
| leave the AI mode | — | < 1 s | runtime UNLOADED |

Generation figures from the diagnostic re-runs: 30.2–32.7 s per ask, 65–76 tokens, time to first
token 18.8–19.7 s (prompt v1 is long; the S20 CPU evaluates it every ask). Thermal 0 → 1 after six
generations; never SEVERE. Developer file removed afterwards (`no_backup/models` empty).

**Finding fixed during the smoke (first run failed):** the Phase 2 memory-pressure hook unloaded
the model between load and generate (`runtime not ready: UNLOADING` 6 s after the ask) — loading a
2.5 GB model raises the trim level by itself. Memory pressure now goes through the orchestrator:
LOW is ignored while an ask is in flight, CRITICAL still unloads (`AiOrchestratorMemoryPressureTest`).

**Open for the human:** on the S20 the case C preview cannot be shown without a 「MemoRipple開発」
document (never inserted into real data); and with prompt v1 Qwen answered case C with the text but
without the target. Both the deterministic case C target extraction the Phase 1 decision names and
prompt v2 are candidates for a later round, not changed here.

## 15. Not in Phase 3 (next rounds)

Write confirmation and execution, model download UI, a user-facing model selector, conversation
history, AI draft persistence, a background agent, any automatic write. The ARM CPU feature
decision (A / B / C) stays open; the release build size is re-measured before the download UI.

## 16. Phase 7 amendments (docs/CHAT_V1_RELEASE_READINESS.md)

- The AI mode asks `AiOrchestrator.availability()` **before** it offers an input and again whenever
  the screen comes back in front; while unavailable it shows the setup card (§DESIGN_SYSTEM) — no
  input, no send, no failure to discover. An ask that still finds no model becomes the same card.
- `ModelUnavailableReason` gains `MODEL_FILE_CORRUPT`; a missing file is `MODEL_FILE_MISSING`, not
  「選択されていません」; the selection is never switched by the app.
- Wording: 「処理しています…」 while generating; the thermal card says 「端末が熱くなっているため、AIを一時停止して
  います」; every runtime-failure card offers 「再試行」 (one tap, one new ask; never automatic).
- The lifecycle, memory and thermal rules of §8–§9 are unchanged and re-tested.

## 17. Phase 8 amendment (docs/AI_CONVERSATION_HISTORY.md)

§10's "no chat table" and §11's "no history" are superseded: Room 25 holds the transcript and the
safe context of each conversation (only what the user saw; never a raw answer, a preview, a
ticket or a result context). The ephemeral rule stands for the current result, the preview and the
`PendingWrite`; a process death keeps the transcript and drops the preview with zero writes. The
`result_N` context is rebuilt per request from the conversation's stored results.

## 13. When the model is bypassed (2026-09-22, `docs/CHAT_FAST_PATH.md`)

A sentence the Fast Path's golden corpus recognizes (a fixed OPEN / SEARCH / plain CREATE /
explicit APPEND shape) becomes its `IntentProposal` by rule and never wakes the runtime: no
selection check, no load, no generation, no thermal gate (nothing generates). The proposal then
walks this document's pipeline unchanged — validator, Resolver, policy, preview, the one
confirmation. Everything else, and every referent without an anchor, takes the AI route exactly
as described here; with no model, only a NO_MATCH sentence meets the setup card.
