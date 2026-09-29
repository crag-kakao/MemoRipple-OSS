# Document 境界 (2026-09-18)

The one vocabulary Calendar, Folder, Search and the future Chat use for memos, outlines and
journals, and the one door through which they read and write them. Designed in
`docs/DOCUMENT_BOUNDARY_AUDIT.md`; built on `feature/document-boundary` (HANDOFF §16.31). No
table, entity, id scheme or backup format changed.

## Types (`domain/documents/`)

```
DocumentKind        MEMO | OUTLINE | JOURNAL            of(MemoKind); memoKind (null for JOURNAL)
DocumentRef         (kind, id)                          id = memos.id or diary_entries.id — no global id
DocumentSummary     ref, title, createdAt, updatedAt
DocumentMetadata    Memo(folderId, noteId) | Outline(folderId) | Journal(date, state, editable)
DocumentContent     summary, body, metadata             never a Room entity
DocumentDestination MemoEditor(memoId) | Outliner(memoId) | Journal(entryId)   ← DocumentRef.destination()
DocumentTitles      resolve(title, body, fallback); firstLine; placeholder(kind)
DocumentSearchScope V1 = {MEMO, OUTLINE}
```

`MemoKind` stays the `memos` table's own discriminator (`memo` / `outline`); `DocumentKind.of` is
the only bridge and journals never pass through it. NOTE is not a kind: a note is a container
of memos (`memos.noteId`). A template is a starting point kept in DataStore. A future comment
is a child of a journal with its own lifecycle (below).

UI-only types stay UI-only: `MemoViewMode` (pager page), `SplitReferenceKind` (split pane's
reference, `rememberSaveable`), `EditorToolbarSurface` (toolbar-arrangement preference key and
route segment). They share three words with the kinds and nothing else. Naming debt, noted and
left: `EditorToolbarSurface` lives under `domain/memos`.

## Dependency direction

```
Chat UI → IntentProposal → Resolver → DocumentAccess (domain)   ┐
Search / Calendar / Folder screens ───────────────────────────────┤→ RepositoryDocumentAccess (data)
                                                                  │      → MemoRepository / DiaryRepository → DAO → Room
UI DocumentNavigator ← DocumentDestination ← DocumentRef          ┘
```

`domain/documents` imports nothing from `data/` or `ui/` (a unit test reads the sources and
fails on `Entity`, `Dao`, `Routes.`, `NavController` or a route string). `RepositoryDocumentAccess`
is the only class that sees both sides. AI → DAO / Room directly is impossible by construction:
nothing above the boundary holds a DAO.

## `DocumentAccess` (= `DocumentReader` + `DocumentWriter` + `DocumentSearch`)

| Call | Does | Answers |
|---|---|---|
| `get(ref)` | `MemoRepository.findById` (kind must agree with the row) / `DiaryRepository.findById`, translated | `Found(content)` / `NotFound` |
| `create(DocumentCreate.Memo(folderId) / Outline(folderId) / Journal(date))` | `createEmpty` / `createOutline` / `createEntry` — the kind's own call, the clock from `TimeProvider` | `Done(ref, updatedAt)`; `Rejected` for a future journal day |
| `append(ref, text, expectedUpdatedAt)` | read → compare `updatedAt` → the repository's own `save` / `saveBody`, inside one `withTransaction` | `Done(ref, updatedAt)` / `NotFound` / `ReadOnly` (LOCKED journal) / `Conflict` |
| `search(DocumentQuery)` | `MemoSearch.parse` + `matches` in memory over the wall's memo and outline flows; folder filter; newest `updatedAt` first; `limit` | `List<DocumentSummary>` |

Not offered, on purpose: update / replace, insert at a position, move, delete, trash, any
lifecycle change, anything about future comments. Chat v1 needs CREATE and APPEND; everything
else waits for its own design.

## Search scope (v1, since the journal-search round)

`searchableKinds == DocumentSearchScope.V1 == {MEMO, OUTLINE, JOURNAL}`: standalone memos,
outline documents and journal entries (LOCKED included — reading is not editing). Not reached:
note episodes (`memos.noteId != null`), archived and trashed rows, future comments (a separate
table that search never loads). Two searches, one list: memos and outlines through the wall's
flows, journals through `DiaryRepository` (all rows, or the existing range query when a
`dateRange` is given), each row matched by `MemoSearch` (terms, `"phrase"`, `#tag`, `-not`; a
journal's first line is its title-equivalent and is inside its body), merged and ordered by
`updatedAt` newest first, then kind, then id. `DocumentQuery(text, kinds, folderId, dateRange,
limit)`: a `dateRange` (inclusive epoch days) selects a journal by its diary day and a memo or
outline by the local day it was made or last written; the caller resolves "today / yesterday /
last week" with `TimeProvider`, never the LLM. A `folderId` leaves journals out. A query with no
words and no date range finds nothing. No `LIKE`, no FTS — the volume argument and the order of
future moves are in `docs/JOURNAL_SEARCH_AUDIT.md`.

## Create / Append scope (v1)

- MEMO: create empty in a folder; append = body + `"\n"` + text (an empty body takes the text as its first line).
- OUTLINE: create empty in a folder; append = exactly one new root node at the end via
  `OutlineEditing.appendLine(parse(body), zoomId = null)` then `updateText` — a newline in the
  text becomes a space (a split into several nodes is not v1). No insertion elsewhere, no replace.
- JOURNAL: create for a day (today or earlier; several a day); append = body + `"\n"` + text;
  LOCKED refused. `saveBody(releaseIfBlank = false)` so an append never deletes a draft.

## `expectedUpdatedAt`

There is no revision column. The caller passes back the `updatedAt` it read (from
`DocumentSummary.updatedAt`); the write happens only if the row still carries that value, and
the compare and the write sit in one Room transaction. A mismatch is `Conflict` and nothing is
written — never last-write-wins from Chat. Known edges, measured, not hidden: the memo editor
bumps `updatedAt` even when it saves an unchanged body, so an editor open beside Chat can
produce a harmless false conflict (retry after re-reading); a journal's unchanged save does not
bump it. Pin / favourite / archive / reorder do not touch `updatedAt`, so they never conflict.

## Future Diary — outside the boundary

A future comment has its own life (Sealed → Delivered → AwaitingPresentation → Revealed), its
text is withheld by SQL until first presentation, and it may only be sealed onto *today's* entry.
It is therefore not a document and not part of a journal's `DocumentContent` — not even as a
count. The calendar's delivery banner keeps reading the repository directly, as before. A Chat
capability for it, if ever, is a separate design.

## What Chat will use

`MemoRippleApplication.documentAccess` (a `RepositoryDocumentAccess`) for `search`, `get`,
`create`, `append`; `DocumentRef.destination()` + the UI's `DocumentNavigator.open(ref)` for
OPEN. The Resolver receives `DocumentRef`s and `DocumentSummary`s, never entities; a
`ResolvedCommand` for APPEND carries `(ref, expectedUpdatedAt, text)`. Nothing in this boundary
needs the LLM to exist.

## Tests that pin it

Unit `DocumentTypesTest` (6), `DocumentTitlesTest` (2), `TimelineBucketsTest` (unchanged
behaviour on the shared ref). Device `DocumentAccessInstrumentationTest` (8: get / NOT_FOUND per
kind, create ×3 + future day, append memo / outline root node / journal, LOCKED → READ_ONLY, stale
→ CONFLICT with the body untouched, future comment outside, search scope) and
`DocumentNavigatorInstrumentationTest` (1: memo, outline and journal from the calendar, a
journal from 日記一覧).
