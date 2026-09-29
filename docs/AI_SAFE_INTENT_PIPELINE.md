# Local LLM Phase 1 — Product AI Boundary / Safe Intent Pipeline

Branch `feature/ai-safe-intent-pipeline` (from main `0495cc8`), 2026-09-19. **No model runs in
the product yet.** This phase builds the boundary that makes a wrong model answer harmless, and
proves it with fixture proposals. Chat v0 is unchanged; nothing here is wired to a screen.

## 1. Why (from Phase 0)

Phase 0 (`docs/LLM_PHASE0.md`) showed that a GBNF grammar fixes the *shape* of an answer and
nothing else: a 1 B model produced perfectly valid JSON that named results never shown, dates
never said and facts that never happened; the 3–4 B candidates still missed the APPEND target in
case C and echoed the utterance into `query`. **GBNF valid ≠ semantic safe.** So every model
answer goes through

```
LLM → IntentProposal → SemanticValidator → Resolver → Preview / Confirmation → DocumentAccess
```

and no stage before the last one can touch a document.

## 2. Audit before the build (2026-09-19)

- `domain/documents`: `DocumentKind {MEMO, OUTLINE, JOURNAL}`, `DocumentRef(kind, id)`,
  `DocumentSummary`, `DocumentContent(metadata: Memo | Outline | Journal(date, state, editable))`,
  `DocumentAccess = Reader + Writer + Search`. `create(DocumentCreate)` makes an *empty* document;
  `append(ref, text, expectedUpdatedAt)` is the only write — transactional, `Conflict` when the
  row moved, `ReadOnly` for a LOCKED journal, `Rejected` for a future journal day. `updatedAt`
  already plays the version token on the API (a revision column may replace it).
- `DocumentQuery(text, kinds, folderId, dateRange, limit)`: `isBounded` = words or a date range;
  journals by diary day, memos / outlines by local day; `DocumentSearchScope.V1` keeps note
  episodes, archive, trash and future comments out. **Future Diary** is a separate entity and is
  not reachable through `DocumentAccess` at all.
- `DocumentNavigator` is private to `ui/MemoRippleApp.kt`; `ChatViewModel` resolves its date
  presets with `TimeProvider` and calls `DocumentAccess` directly; **no preview / confirmation UI
  exists yet** to reuse.
- `TemplateRepository` (DataStore) holds `MemoTemplate(id, name, body)` — **no field schema exists
  in the product**; Phase 0's field templates were evaluation fixtures. USE_TEMPLATE therefore
  means "a memo from the template's body"; nothing is filled in.
- `TimeProvider.currentLocalDate()`; `DiaryState {DRAFT, FINALIZED, CORRECTING, LOCKED}`,
  `DiaryStatePolicy.canEdit`.
- No Room schema change is needed and none was made (Room 24, Backup 18 unchanged).

## 3. The package `domain/ai`

Pure domain: it imports `domain/documents`, `domain/diary/TimeProvider`, `domain/memos/MemoTemplate`
and nothing else — no DAO, entity, Room, UI, route string, DataStore, llama.cpp or bench
(`AiBoundaryPolicyTest`).

| type | role |
|---|---|
| `AiIntent` | SEARCH, OPEN, CREATE, APPEND, USE_TEMPLATE, UNKNOWN. `fromModel(raw)` maps any other word — DELETE, UPDATE, MOVE, RENAME, ARCHIVE, RESTORE, a typo, nothing — to UNKNOWN. No destructive intent exists. |
| `AiResultRef` | the opaque `result_N` (1..99) a model may cite; `parse` accepts only that shape. Not a `DocumentRef`. |
| `DateToken` / `DateTokens` | TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK; resolved to a `DocumentDateRange` by `TimeProvider` (weeks Monday..Sunday). A model date is not a token. |
| `IntentProposal` | intent, query, targetRef (`AiResultRef?`), targetName, documentKind, text, templateId, dateToken, missingFields. **No Long, no LocalDate, no id field** (guarded by test). |
| `AiResultContext` | one request's shown results: `result_N → DocumentSummary/DocumentRef`; `shownLines()` is what a prompt carries (label + title, never an id). Request-scoped, not serializable, never stored. |
| `SemanticValidator` | `Valid` / `Invalid(reasons)` / `NeedsInformation(fields)`; see §4. |
| `Resolver` | valid proposal → `ResolvedCommand` through `DocumentAccess.search/get`, `AiResultContext`, `DateTokens`, `TemplateLookup`. Never writes; never picks among equals. |
| `ResolvedCommand` | `Search(query)`, `Open(target)`, `Create(kind, request, initialText)`, `Append(target, text, expectedVersion, currentBody)`, `UseTemplate(template)` — the first place a real `DocumentRef` appears. |
| `DocumentVersion` | the token a write must find in place (today `updatedAt`). |
| `CommandPreview` | `Create`, `Append` (with `expectedVersion`), `Template` (the body as is) — UI-free. |
| `ExecutionPolicy` | `Direct` (SEARCH, uniquely resolved OPEN) / `RequiresConfirmation(command, preview)` (every write) / `Blocked` (Rejected, NeedsInformation, Ambiguous, NotFound). Takes a `ModelSignal(confidence)` and ignores it. |
| `ConfirmedCommand` | produced only by `RequiresConfirmation.confirm()`; the executor has no method for an unconfirmed write. |
| `CommandExecutor` | `execute(Direct)` → `Searched` / `Opened(ref)`; `execute(ConfirmedCommand)` → `Written(ref, version)` / `Conflict` / `NotFound` / `ReadOnly` / `Rejected`, through `DocumentAccess.create` / `append` only. |
| `TemplateLookup` | by id, then by name; `data/TemplateLookupAdapter` sits on `TemplateRepository`. |

## 4. Rules the validator and resolver enforce

- **SEARCH** needs words, a date token or a kind; otherwise `UNBOUNDED_SEARCH`. (A kind-only query
  reaches the boundary unbounded and finds nothing — the boundary has no "everything" listing.)
- **OPEN** needs a shown `result_N` or a name → else `NeedsInformation(TARGET_NAME)`.
- **CREATE** needs a kind; an empty memo is a valid create (the editor's "blank memo opens
  writing" rule); a journal is made for today or, on YESTERDAY, yesterday — never a future day.
- **APPEND** needs a target and non-blank text: no text → `NeedsInformation(TEXT)`, blank →
  `BLANK_TEXT`; a LOCKED journal → `TARGET_READ_ONLY` before any write.
- **USE_TEMPLATE** needs a template id or name; unknown → `NotFound(TEMPLATE_ID)`; the preview is
  the body, nothing filled in.
- **UNKNOWN** → `UNKNOWN_INTENT`, never executable.
- A `result_N` outside the shown list → `REF_NOT_IN_CONTEXT`; a real-id-looking string is not a
  ref at all (`AiResultRef.parse` returns null).
- A name that matches several documents → `Ambiguous(candidates)`; none → `NotFound`; a document
  of another kind than the proposal says → `KIND_MISMATCH`.
- The model's own `missingFields` are honoured as questions, never as licence to guess.
- Every write carries the version read at preview time; a changed version at execution →
  `Conflict`, nothing written.

## 5. Case C, deterministic

「MemoRipple開発に『Folder対応完了』を追記して」 → `IntentProposal(APPEND, targetName = MemoRipple開発,
text = Folder対応完了)` → `DocumentAccess.search("MemoRipple開発")` → one hit → `get` → current
body and version → `ResolvedAppend` → `AppendPreview(expectedVersion)` → confirmation →
`append(ref, text, version)`. Several hits → `Ambiguous`; none → `NotFound`; the target missing
from the proposal (as three of four Phase 0 models answered) → `NeedsInformation(TARGET_NAME)`,
never a guess. A bigger model is not the fix; this path is.

## 6. Not in Phase 1

Chat UI changes, AI input, model selector, download UI, llama.cpp production JNI, GGUF loader,
model manager, prompt renderer, GBNF integration, any Room table (`ai_sessions`, `ai_messages`,
`ai_drafts`, `ai_results`, model tables), Future Diary in the AI path, a default model. The
runtime supports the two Phase 0 candidates through one interface later; nothing here depends on
which.

## 7. Tests (RED → GREEN, 53 + 3 policy)

`domain/ai/*Test`: the six intents and the UNKNOWN mapping of unsupported words; no id / Long /
LocalDate in a proposal; opaque ref parsing; the four tokens through `TimeProvider`; bounded
SEARCH; OPEN / CREATE / APPEND / USE_TEMPLATE requirements; blank APPEND; UNKNOWN blocked;
`result_1` mapping and a ref beyond the list; unique / zero / several candidates; case C resolved
and case C without a target; kind mismatch; LOCKED journal; the AI scope = V1 and today-only
journals; previews for the three writes; SEARCH / OPEN direct; stale version → Conflict; the
preview keeps its version; `DocumentAccess` reached only after `confirm()`; confidence ignored;
context not persisted. `AiBoundaryPolicyTest`: no DAO / entity / Room / UI / route / llama /
llmbench in `domain/ai`; only boundary types; Chat v0 untouched.

## Phase 8 amendment — a declared gap with the value present (2026-09-20)

`SemanticValidator` still treats the model's `missingFields` as questions, never as a licence to
guess. One case is excluded since Phase 8 (found on the S20): a field the model calls missing
**while giving its value in the same answer** — `targetName = "MemoRipple開発"` with
`missingFields = ["targetName"]`. The value is there; no question is asked and nothing is guessed. A
blank value stays a question; a `result_N` outside the shown list stays invalid
(`SemanticValidatorSelfContradictionTest`).
