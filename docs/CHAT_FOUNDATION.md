# チャット, v0 — the search and command workspace (2026-09-18)

Branch `feature/chat-foundation`, base main `fdb1034`. The bottom bar is now
**メモ / カレンダー / チャット**. The チャット tab is **not an AI chat yet**: it is the search and
command workspace a Local LLM will later sit in, built on `DocumentSearch` / `DocumentAccess` /
`DocumentNavigator` so that the boundary is exercised by a real screen before any model exists.

> **Phase 3 (2026-09-20, `docs/AI_CHAT_PREVIEW.md`):** the screen now has a 検索 / AI segmented
> control. Everything below describes the 検索 mode, which is unchanged and is still the default;
> the AI mode is documented there. Chat never depends on the model: with no model, the search mode
> works as before.

## What it does (and only that)

| Part | Behaviour | Through |
|---|---|---|
| Search field (`chat_input`, "メモ・日記を検索…") | Everything typed is a **search**. "新しいメモを作って" is looked up, not obeyed — reading intent from a sentence is the model's job, later. Enter / the search icon runs it. | `DocumentQuery(text, kinds, dateRange)` → `DocumentAccess.search` |
| Date chips 今日 / 昨日 / 今週 | A quick date, kept as its meaning (`ChatDatePreset`) and resolved on use by `TimeProvider`; 今週 = Monday..Sunday (the calendar grid's week). The same chip again clears it. No LLM date arithmetic. | `DocumentDateRange` |
| Kind chips すべて / メモ / アウトライン / 日記 | `DocumentKind` itself with chat-side labels; no chat kind enum. | `DocumentQuery.kinds` |
| Results | `DocumentSummary` rows: kind, title, updated day — no body, no snippet, no re-sort (the boundary's order `updatedAt DESC → kind → id`). A tap hands the `DocumentRef` to the app's `DocumentNavigator`; Back returns with the query and results kept. | `DocumentRef` → navigator |
| Empty states | Nothing is listed until asked: no words and no date = idle ("検索する言葉を入力するか、日付を選んでください"); a search with no match says "この条件の記録はありません". | the boundary's bounded rule |
| テンプレート chip | Opens the existing templates screen; Back returns. Nothing copied. | `Routes.TEMPLATES` (app side) |
| 新しいメモ / 新しいアウトライン / 今日の日記を書く | `DocumentAccess.create(Memo / Outline / Journal(today))` → `DocumentRef` → navigator. A journal can be made several times a day; one left blank is released as always. | the boundary |
| カレンダーを開く | A tab switch (not a document operation). | `navigateTopLevel` |

Not here: free-text CREATE / APPEND / UPDATE / REPLACE / DELETE / MOVE / RENAME, conversation
history, any Room table, model settings or "AI 未導入" placeholders.

## State

`ChatViewModel` + `SavedStateHandle` (`chatQuery`, `chatKinds`, `chatDatePreset`; since Phase 3 also
`chatMode`, `chatAiInput`): rotation, a tab switch and process death all bring back the query, the
kinds, the preset, the mode and the AI input; a restored workspace that was asking for something
asks again. Nothing in DataStore. The AI result, preview and `result_N` context are never saved
(docs/AI_CHAT_PREVIEW.md §10).

## Dependency direction

`ui/chat` imports `domain/documents`, `domain/diary/TimeProvider` and, since Phase 3, the
`AiOrchestrator` interface and its result types from `domain/ai` — no DAO, no entity, no Room, no
route string, no calendar UI helper, no generator / resolver / executor / runtime / engine
(`ChatBoundaryPolicyTest`, `AiChatPreviewPolicyTest` read the sources). The
app (`MemoRippleApp`) gives the screen four callbacks: `onOpen(DocumentRef)` = the navigator,
templates, calendar tab, settings.

## Navigation

`Routes.CHAT` is a top-level destination with the same `navigateTopLevel` (saved state, single
top, restore) as メモ and カレンダー: tab state survives switching and `recreate()`; Back from the
tab lands on メモ; a document opened from a result or a command returns to チャット on Back.

## Debt: one week rule

チャット's 今週 and the main calendar grid use Monday..Sunday; the old diary page (日記一覧) still
uses the locale's first day of the week (`WeekFields.of(locale)`, Sunday for ja). Left as is for
now (human decision 2026-09-18); when the date resolver for the Local LLM phase is designed,
Calendar, チャット and the resolver should share one week rule.

## One editor rule

A memo that exists but holds nothing yet opens **writing**, not reading (the same rule an
episode already had): 新しいメモ from チャット makes an empty row through the boundary and the
editor has nothing to read there, only a page to fill.

## Tests

Unit: `ChatDatePresetTest` (today, yesterday across a year end, 今週 Monday..Sunday incl. Sunday
and Monday edges), `ProjectBoundaryPolicyTest` (three destinations), `ChatBoundaryPolicyTest`
(imports, no routes, boundary use). Device: `ChatFoundationInstrumentationTest` 8 (the three
tabs and top-level behaviour; idle vs no-match; words across the kinds + kind chips; date chips
from the clock + kind; result → document → Back with state kept; recreate(); the commands;
templates) and `ChatViewModelStateInstrumentationTest` 1 (the process-death restore) — the 43
points of the brief, with the memory-leak tripwires and the calendar / folder / navigation
suites unchanged around them.

Phase 3 adds `AiChatPreviewInstrumentationTest` 12, one restore case in
`ChatViewModelStateInstrumentationTest`, and `AiChatPreviewSmokeTest` (S20, skipped without a
model path); the v0 suite above is unchanged and stays green beside the AI mode.
