# AI Conversation History / Safe Conversation Context — Local LLM Phase 8

> Branch `feature/ai-conversation-history` from `main` `7b7e282`, 2026-09-20. Chat v1's AI mode
> becomes a conversation: the user can read past turns, start a new chat, switch between chats, and
> keep them across restarts; the model sees a bounded recent window; 「2番目」, 「それ」 and
> 「さっきのメモ」 are resolved by the app, by rule. Read `docs/AI_SAFE_INTENT_PIPELINE.md`,
> `docs/AI_CHAT_PREVIEW.md`, `docs/AI_CONFIRMED_WRITE.md` and `docs/AI_TARGET_RESOLUTION.md` first.
> **History has no execution authority**: the principle stays AI proposes → the app validates →
> the app resolves the target → preview → Human Confirmation → the app executes.

## 1. Three layers, kept apart

| Layer | What | Where | Who reads it |
|---|---|---|---|
| Persistent conversation history | `ChatConversation` / `ChatMessage` — what the user typed and what the screen showed | Room 25 (`chat_conversations`, `chat_messages`), device-local | the user, on screen |
| Persistent safe conversation context | `SafeConversationContext` — the latest shown results by position (kind, id, the shown title) and the last document anchor | Room 25 (`chat_result_refs`, the anchor columns of the conversation) | the app: rebuilt into a request-scoped `AiResultContext` and lent as `ConversationReferents` |
| Bounded active LLM context | `ConversationWindow` — the newest lines of the transcript within a budget | memory, one ask | the model, inside the user turn |

The model never sees an id, a ref or a hidden state; the transcript never holds a raw answer; the
window never holds the whole history.

## 2. Persistence model (Room 24 → 25)

- `chat_conversations(id, title, createdAt, updatedAt, anchorKind?, anchorId?)`
- `chat_messages(id, conversationId ↘ cascade, role USER|ASSISTANT, kind, content, createdAt)` + index
- `chat_result_refs(conversationId ↘ cascade, ordinal, documentKind, documentId, title)` PK (conversationId, ordinal)

`MIGRATION_24_25` creates the three tables and nothing else (no row of any existing table is
touched; `ChatHistoryMigrationTest` seeds memos, a journal entry and a folder at 24 and reads them
back byte-equivalent at 25 with the chat tables empty). No foreign key points at a document table:
a deleted memo leaves the transcript alone and the Resolver answers NotFound when the anchor is used.
No destructive migration exists anywhere (policy test). Ids are Room autoincrement longs like every
other entity; `ChatDao` / `ChatHistoryRepository` follow the flat `data/` convention.

**Stored:** the visible text only. **Never stored:** raw JSON, the raw model response, the parser
payload, a confidence, the prompt, GBNF output, resolver debug, an exception, a developer note, a
`PendingWrite`, a `ConfirmedCommand`, an `AiResultContext`. `AiConversationPolicyTest` scans the
entity file for such columns.

## 3. Conversation and message lifecycle

- Opening the AI mode, switching modes or pressing 「新しいチャット」 creates nothing. **The first sent
  message creates the conversation**, titled from that message by `ConversationTitle.from`:
  trimmed, one line, whitespace collapsed, ≤ 36 characters with 「…」, 「新しいチャット」 when empty —
  deterministic, no model, no second inference.
- The user's line is appended when the ask starts; the assistant's line when a terminal, stable
  result is on screen — never for LOADING / GENERATING. The line is `AiWording.assistantText`:
  「3件見つかりました。」, 「メモ「x」を開きました。」, 「同じ名前の記録が2件あります。…」, 「メモの作成内容を確認して
  ください。」, then after the confirmation 「メモを作成しました。」 / 「追記しました。」, after a cancel
  「キャンセルしました。」, a refusal's title, a failure's title (no developer detail).
- Past messages are read-only rows. No preview card, candidate row or confirm button is ever
  rebuilt from history; a restart shows the transcript and no preview.
- Operations: 「新しいチャット」, 「履歴」 (a list: title, last activity, delete with a confirmation,
  「すべてのチャット履歴を削除」 with a confirmation), open a conversation from the list. Rename is not
  in Phase 8 (the deterministic title serves; a rename would be a small settings-style edit later).
- The current conversation is one light preference (`chat_last_conversation_id`), so a restart
  returns to the last chat; a deleted id falls back to none. It is the only new DataStore key.
- 検索 mode is untouched: it creates no conversation and writes no history.

## 4. Safe referents

`ConversationReferent.parse(userText)` reads only the head of the sentence, quoted text removed:

| Words | Referent | Resolution |
|---|---|---|
| 「N番目」 / 「N件目」 / 「最初の」 / 「最後の」 | position N in the latest shown results | the stored ref becomes `result_N` of a fresh request context → Resolver |
| 「それ」 / 「これ」 / 「その…」 / 「さっきの…」 / 「前のやつ」 (+ メモ / 日記 / アウトライン) | the last document anchor, kind must agree | `DocumentAccess.get(anchor)` re-read → a one-line context → Resolver |
| anything else | none | `TargetCandidateExtractor` (Phase 6) as before |

Used only when the model gave neither a shown ref nor a name for OPEN / APPEND; a model-given
`result_N` or name still wins. Out of range, no anchor, wrong kind → NeedsInformation (never a
guess); a document that vanished → NotFound; a locked journal → the usual refusal. Every referent
still goes through the Resolver, the preview and the Human Confirmation; an anchor is never
written to directly. The Resolver now re-reads **every** shown ref through the boundary before
using it (`resolve` → `documents.get`), so a stale stored result is NotFound, never opened.

The anchor is set by a unique OPEN, a chosen candidate and a confirmed write (the created or
appended document). The latest results are set by a SEARCH result list and by a candidate list.

## 5. Cross-conversation isolation

Results and the anchor are rows of one conversation; switching conversations replaces the
transcript, the context and the ephemeral result; a new conversation starts with nothing (journey
5: 「2番目を開いて」 in a fresh chat is a question). The old conversation reopens whole from the list.

## 6. Bounded active context

`ActiveContextBudget.window(transcript)`: the newest messages, at most 6 and 1,200 characters,
only the newest message clipped (to 300 characters) — older lines are kept whole or left out, and
the window says 「（それより前の会話は省略）」 when it dropped any. Budget: context 4,096 tokens; prompt
v1 510 / 553 tokens (Qwen / Ministral, docs/LLM_PHASE0.md); the generation reserve 256 tokens; the
shown lines ≤ 50 × ~40 characters; the input ≤ 200 characters — about 2,000 tokens of headroom in
the worst case, so 1,200 characters of Japanese (≈ 1 token per character on these tokenizers) keep
a wide margin. **No automatic summarisation**: older lines simply leave the prompt while staying on
screen. History persistence ≠ model context.

`ConversationPromptComposer` puts the window into the **user turn** as

```
直前の会話:
（それより前の会話は省略）
USER: 昨日の日記を探して
ASSISTANT: 3件見つかりました。

表示中の候補:
result_1: …
入力: 2番目を開いて
```

The system prompt is the frozen prompt v1 asset, byte-identical; the grammar is unchanged; an empty
window yields exactly Phase 0's user turn.

## 7. Process death

Persisted: the chat mode, the input, the search filters (saved state) and the current
conversation id (a preference). Ephemeral: raw model output, the proposal, the candidate, the
`AiResultContext`, the preview, the `PendingWrite`, the `ConfirmedCommand`. Journey 6 proves:
transcript on screen → preview → process death (view-model store cleared + recreation) → transcript
restored → no preview, no confirm button → zero writes; a new 「それに追記して」 makes a fresh preview.

## 8. Privacy

Local-only; no analytics; inference on the device; the model download is the only network path.
**Never logged:** a conversation title, message content, history content, result titles, user
text, assistant text, a DocumentRef or id. **Logged (info level):** `conversation created`,
`ask: windowLines=N truncated=… shown=N`, the referent notes `referent: kind=ORDINAL resolved=…
shown=N` / `referent: kind=ANCHOR present=… kindAgrees=…`, timings, error categories.
`AiLoggingPolicyTest` and `AiConversationPolicyTest` scan every log line.

## 9. Backup policy

- Android backup: `allowBackup="false"` and the exclude-all rules stand; the Room file is not
  backed up.
- Portable backup (`.mrbackup`): **format 18, unchanged**; the payload enumerates its tables by
  hand and the chat tables are not among them. `ChatHistoryBackupExclusionInstrumentationTest`
  writes a conversation with marker strings, prepares a backup, reads every entry of the archive
  and finds neither the markers nor a chat key, then restores it and finds the documents whole.
- Portable export: unchanged (memos, diaries, notes).

## 10. Not built (by decision)

No RAG, vector DB, embedding, semantic search, chunking or retrieval index; no background agent —
every ask is a user action; no destructive intent; no LLM-written title; no automatic summary; no
paging dependency (the transcript is a `LazyColumn`); no retention limit — the database can grow
with use (§12).

## 11. Screen

AI mode (with a model available): the 「新しいチャット」 / 「履歴」 row, the transcript (user lines
right-aligned, assistant lines left, failures on the error container), the current ephemeral
result or status, and the input **pinned at the bottom** with the write note under it. Unavailable
states show the setup card as in Phase 7. The 履歴 screen lists conversations newest first with a
delete icon per row and the delete-all button. Final looks, spacing and colours are for the next
round — the user's own UI / UX review.

## 12. Growth audit (for a later retention decision)

A message row is a few hundred bytes; a conversation with ten turns is under 10 KB; ten chats a day
for a year is under 40 MB — well inside a phone's storage but not nothing. Candidates for a later
round, none adopted now: a per-conversation message cap, an age-based cleanup of empty or
never-reopened chats, a manual 「すべて削除」 (present), a size line in settings.

## 13. Tests

JVM: `ConversationTitleTest` 4, `ActiveContextBudgetTest` 6, `ConversationPromptComposerTest` 5,
`ConversationReferentTest` 4, `ConversationOrchestratorTest` 11, `AiConversationPolicyTest` 7; the
six older policy tests now pin Room 25 with the three chat tables. Device:
`ChatHistoryInstrumentationTest` 4, `ChatHistoryMigrationTest` 2,
`ChatHistoryBackupExclusionInstrumentationTest` 1, `ConversationHistoryInstrumentationTest` 8
(journeys 1–6 + creation rules + delete), `ConversationS20SmokeTest` 1 (skipped without a model).

## 14. S20 smoke

2026-09-20 21:22–21:24 JST, S20, Qwen3-4B-Instruct-2507 through the debug developer
file, USB, the `7bd535a` build installed in place (data intact, the five Phase 0 GGUFs untouched):
**`OK (1 test)`** on the fourth attempt — the first three found two real model habits and were the
reason for two product changes (§15). Turn 1 「MemoRipple開発を開いて」 (the test's own temporary memo)
→ `memo_reading_view` in **36.5 s** (load + generation; `windowLines=0`), the memo becomes the
anchor; turn 2 「それに『会話テスト』を追記して」 → `windowLines=2`, the notes `referent: the sentence
opens with a referent; a model-given name set aside` and `referent: kind=ANCHOR present=true
kindAgrees=true`, then `chat_ai_preview_append` in **35.3 s** naming MemoRipple開発 and 会話テスト —
cancelled; the memo unchanged, no other document touched; the transcript holds 5 lines (title 16
characters); thermal 0 → 1. The conversation the test made was deleted afterwards.


## 15. S20 findings that shaped the code

Two real-model habits surfaced only on the S20 (the scripted runtime never produces them):

1. **A value given and, in the same answer, declared missing.** Qwen answered 「MemoRipple開発を開いて」
   with `targetName = "MemoRipple開発"` *and* `missingFields = ["targetName"]`; the validator added the
   declared gap and the ask ended as a question. Amendment to the Phase 1 rule "the model's
   missingFields are questions": a field whose value is present in the same answer is not a
   question — nothing is guessed, a blank value still is a question, an unknown ref is still
   invalid (`SemanticValidatorSelfContradictionTest`).
2. **A name the model invented for a demonstrative.** For 「それに『会話テスト』を追記して」 with the
   two-line window, the model produced a 5-character `targetName` that exists nowhere (the note
   `target name not found: nameLength=5 …`), and once a bare 「それ」 as the name. Rules: a
   `targetName` that is itself a referent phrase (「それ」, 「さっきのメモ」, 「2番目」, with or without
   a trailing particle) is never searched; and when the **user's own sentence opens with a referent**
   there is no name in it to honour — a model-given name is set aside and the referent is read
   (the anchor, re-validated). A model-given shown ref still stands; a sentence that names a document
   (「買い物を開いて」) still honours the model's name; 「それから」 / 「その他」 / 「これからの計画」 are titles,
   not referents (the sentence-head rule needs a particle or a kind word after the demonstrative).
   This refines Phase 6's "a model-given name is honoured with no fallback": the fallback that
   stays forbidden is the extractor overriding a *name in the sentence* after NotFound; a
   demonstrative in the sentence is not a name.

The diagnostic note on a NotFound name is redacted by design: its length and three booleans
(brackets, kind word, particle), never the name.

## 16. Conversation → memo (Review Batch 2, 2026-09-22; `feature/think-templates`)

The chat's overflow offers **「この会話をメモとして保存」** (`chat_save_conversation`, enabled only when a
conversation with at least one line is on screen). It is a one-way export of the **whole current
conversation** (partial selection is deferred) as an ordinary memo:

- **Deterministic, by rule** — `ConversationExport` (`domain/ai/conversation`): the title line `# <title>`
  (the conversation's own title; 「会話 - yyyy-MM-dd」 when it is empty or the generic 「新しいチャット」),
  then every transcript line under **`## あなた`** / **`## MemoRipple`**, in order, as it was shown. No
  model summarises, no model titles.
- **Nothing internal** — the persistent transcript (§4) holds only what the user typed and what the screen
  showed, so the export carries no id, `result_N`, field key, timing (ms/token, tokens/s, TTFT), model
  detail, ticket or operation state by construction; a SEARCH / OPEN / write line is its human sentence
  (「1件見つかりました。」, 「メモ「…」を開きました。」, 「キャンセルしました。」); a Think result line is its
  readable text (`ConversationExportTest`).
- **The same safe path** — `AiOrchestrator.previewMemo(body)` turns the body into `ResolvedCommand.Create`
  → `ExecutionPolicy.decide` → `CommandPreview.Create` + a single-use `PendingWrite`; the chat shows the
  ordinary 「新規作成の確認」 preview (種類 = メモ, 内容 = the body) with キャンセル / 作成; 作成 is
  `confirmWrite()` → `execute(pending)` → the one `confirm()` → `CommandExecutor` → `DocumentAccess`. No UI
  action writes through a DAO. One ticket = one memo; a double tap is one write; cancel writes nothing; a
  process death during the preview drops the ticket and writes nothing. No model, runtime or network is
  touched (`ReviewBatch2PolicyTest`).
- **The conversation is untouched** — no link, no flag; saving twice makes two memos, which is allowed.
- The memo is an ordinary memo: in the portable backup, the export and the wall like any other.

## 17. Export boundaries (2026-09-22)

Two things can now leave a conversation, both by rule and both one-way: a **memo** (§16) and a
**template draft** (`docs/CHAT_UI_TEMPLATE_V2.md` §19). Their boundary is the same: the transcript is
**data, never authority** — nothing in it runs, names an action, fills a template value, or is
summarised by a model; the memo goes through the preview and the one `confirm()`, the draft through
the template editor's 保存. Both read the transcript's *kinds* (TEXT / RESULT / WRITE_EVENT / FAILURE)
to tell what was said from what was shown, and both carry only what the user saw. The conversation
itself is never changed by either.

## Addendum — the DecisionEngine reads the safe context (Phase 3, 2026-09-23)

`docs/DECISION_ENGINE.md`: the deterministic decision layer consumes exactly the three-layer
contract this document defines — the latest shown results (positions and titles) and the last
document anchor, of the current conversation only — and nothing else: never the transcript's
words, never another conversation's refs, never a stored id. Its ordinal and demonstrative
readings are the Phase 8 rules moved **before** the model for sentences the model never needs to
see; the anchor is still re-read through the boundary, the Resolver still re-validates, and
history still has no execution authority.
