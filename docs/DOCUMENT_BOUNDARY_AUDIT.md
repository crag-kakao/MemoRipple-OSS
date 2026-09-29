# Document 境界 — audit and design notes (2026-09-18)

> **Status (2026-09-18 night):** §11 items 1–4 and 6 built on `feature/document-boundary` — see
> `docs/DOCUMENT_BOUNDARY.md` for the final shape and HANDOFF §16.31 for the round. Item 5 (journal
> reach in search) is deliberately deferred to its own round. The audit below is as written.

Branch `feature/document-boundary-audit`, base main `66ac415`. **Audit only: no product code, no
schema, no entity merge.** Purpose: name the one semantic Document boundary that Calendar, Folder,
Search and the future Chat / Local AI share — without merging the physical tables. Every claim
below carries a `path:line` from the tree at `66ac415`.

## 1. The types today — definition, use, nature

| Type | Defined | Used from | Domain or UI | Persisted? | Values |
|---|---|---|---|---|---|
| `MemoKind` | `domain/memos/MemoKind.kt:12` | `MemoEntity`, `MemoRepository`, `MemoDestination`, `BackupMapper/Validator`, `Timeline.kt`, `CalendarScreen`, `MemoRippleApp` (9 files) | **Domain, storage-facing** | **Yes** — `memos.kind` (`"memo"` / `"outline"`), Backup ≥ 15 `MemoBackupDto.kind` | `MEMO`, `OUTLINE` |
| `DocumentKind` | `domain/calendar/Timeline.kt:18` | `Timeline.kt`, `CalendarScreen` (2 files) | **Domain, cross-cutting** | No (a projection; `of(MemoKind)` at `:25`) | `MEMO`, `OUTLINE`, `JOURNAL` + label |
| `DocumentRef` | `Timeline.kt:33` | same 2 files | Domain | No | `Memo(memoId, kind: MemoKind)`, `Journal(entryId)` |
| `TimelineItem` | `Timeline.kt:55` | same 2 files | Domain projection for one screen | No | id, kind, title, createdAt, updatedAt, displayDate, sourceRef, activity, timeLabel, atMillis |
| `MemoViewMode` | `ui/memos/MemoViewModePages.kt:110` | `MemoListScreen` (pager page) | **UI** | No — `rememberPagerState` (`MemoListScreen.kt:707`) | `MEMO`, `OUTLINER`, `NOTE` |
| `EditorToolbarSurface` | `domain/memos/EditorToolbarOrder.kt:33` | editor, outliner, settings screens, toolbar-order routes (7 files) | **UI preference key** (lives in `domain/memos` but names a *bar*, not a document) | Yes — DataStore keys per surface (`SettingsRepository.kt:955-958`), and as a route segment (`Routes.toolbarOrder(surface)`) | `MEMO`, `NOTE`, `OUTLINER` |
| `SplitReferenceKind` | `domain/split/SplitWorkspace.kt:9` | editor, split pane, outliner playback (4 files) | **UI state** | Transient — `rememberSaveable` (`MemoEditorScreen.kt:940`, `OutlinerPlayback.kt:274`) | `MEMO`, `NOTE`, `OUTLINE` |
| `MemoDestination` | `domain/memos/MemoDestination.kt:15` | `MemoNavigator` (`ui/MemoRippleApp.kt:592`) | Domain (where a kind opens, no route string) | No | `EDITOR`, `OUTLINER` |
| `DiaryState` | `domain/diary/DiaryState.kt` | diary repository / editor | Domain, storage-facing | Yes — `diary_entries.state` | `DRAFT`, `FINALIZED`, `CORRECTING`, `LOCKED` (only LOCKED is read-only, `DiaryStatePolicy.kt:17`) |

Three things that are *not* document kinds but sit next to them:

- **Note** (`data/NoteEntities.kt:15`): an ordered run of memos ("episodes"); no body of its own;
  membership is `memos.noteId / chapterId / episodeOrder` (`MemoEntity.kt:29-33`). A container,
  not a document — `DocumentKind` rightly has no NOTE.
- **Template** (`domain/memos/MemoTemplate.kt:5`, `data/TemplateRepository.kt:25`): a starting
  point for a memo, kept in DataStore (`memo_templates`), carried by Backup 18
  (`BackupMapper.kt:132`). A setting, not a document. *Stale comment:* `TemplateRepository.kt:22`
  still says templates are "not carried by a backup".
- **Future diary comment** (`data/FutureDiaryCommentEntity.kt:20`): a child row of a journal
  entry with its own Sealed → Delivered → AwaitingPresentation → Revealed lifecycle
  (`FutureDiaryCommentRepository.kt:136-167`); text hidden in SQL until first presentation
  (`FutureDiaryCommentDao.kt:13-30`). See §10.

## 2. Duplicated meaning

| Overlap | Verdict |
|---|---|
| `MemoKind.MEMO/OUTLINE` ⊂ `DocumentKind.MEMO/OUTLINE/JOURNAL` | Intended layering, not duplication: `MemoKind` is the `memos` row's stored discriminator; `DocumentKind` is the cross-cutting vocabulary and already derives from it (`Timeline.kt:25`). **Keep both**, one direction (`DocumentKind.of(MemoKind)`), never the reverse for JOURNAL. |
| `MemoViewMode`, `SplitReferenceKind`, `EditorToolbarSurface` — all say MEMO / OUTLINE(R) / NOTE | Same three words, three different things: *which pager page is up*, *what the lower pane shows*, *which bar's arrangement*. Each carries NOTE, which is not a document kind; two are transient, one is a preference key and a route segment. **Not duplicates of `DocumentKind`; do not merge** (§3). |
| `DocumentRef.Memo(memoId, kind: MemoKind)` vs a `DocumentRef(kind: DocumentKind, id)` | The calendar's ref carries the *memo* kind because its consumer is `MemoNavigator.open(id, MemoKind)`. A boundary-level ref keyed on `DocumentKind` is the cleaner shape (§6); the calendar's can become a view of it. |
| `BodyReading.hasStructure` (`domain/BodyReading.kt:42`) vs `MemoKind.OUTLINE` | Historical: the comment at `:37-41` still says "nothing records which a memo is" — it does now (`memos.kind`). `hasStructure` is a *body-shape* predicate used by the wall's display split; the kind is the identity. **Stale comment, not a design conflict.** |
| `TimelineItem.title` derivation (first non-blank body line, `Timeline.kt:121/146`) vs `MemoViewModePages.kt:529` outline card title | Two copies of "title, else first line, else placeholder". A `DocumentSummary` (§4) should own this rule once. |

## 3. What stays UI-only (deliberately)

- `MemoViewMode` — pager state. Not persisted, not semantic.
- `SplitReferenceKind` / `SplitReference` — what the lower pane looks at, saved with the screen only.
- `EditorToolbarSurface` — a per-bar preference key and route segment; renaming or merging it would
  migrate DataStore keys for no gain. (It sits under `domain/memos`; that is a naming wart, not a
  boundary problem.)
- `MemoDestination` — stays as the memo-side routing rule; the boundary's `open` builds on it (§7).

Rule: a type joins the Document vocabulary only if Search, Calendar, Folder *and* Chat would all
ask it the same question. None of the three above pass that test.

## 4. What moves toward a shared domain vocabulary (design, not code)

`DocumentKind { MEMO, OUTLINE, JOURNAL }` **can** be MemoRipple's common vocabulary. Evidence: every
cross-cutting surface already reduces to it — the calendar (`TimelineBuckets`), the navigator
(`MemoDestination` for two of the three), the search scope (§5), backups (one DTO per kind).
Proposed shape, in `domain/documents/` (new package, pure Kotlin, no Room import):

```kotlin
enum class DocumentKind { MEMO, OUTLINE, JOURNAL }            // moved from Timeline.kt; label stays UI-side
data class DocumentRef(val kind: DocumentKind, val id: Long)  // id = memos.id or diary_entries.id; no global id
data class DocumentSummary(val ref: DocumentRef, val title: String, val createdAt: Long, val updatedAt: Long,
                           val folderId: Long? /* journals: null */)
data class DocumentContent(val summary: DocumentSummary, val body: String, val metadata: DocumentMetadata)
sealed interface DocumentMetadata { data class Memo(val noteId: Long?, val tags: List<String>) ; data class Outline(...) ;
                                    data class Journal(val diaryDate: LocalDate, val state: DiaryState) }
```

- `MemoKind` stays the `memos`-internal representation; `DocumentKind.of(MemoKind)` is the only
  bridge, and `DocumentRef.Memo/Journal` in `Timeline.kt` becomes either an alias of the new ref
  or a thin view (`toDocumentRef()`), so the calendar keeps working unchanged.
- No new global id: `(kind, id)` is unique because MEMO / OUTLINE share `memos.id` and JOURNAL is
  `diary_entries.id`. `DocumentRef(OUTLINE, 5)` and `DocumentRef(MEMO, 5)` name the same row
  only if the row's kind matches; the access layer rejects a ref whose kind disagrees with the row.

## 5. Memo / Outline / Journal — common ground and differences

| | MEMO | OUTLINE | JOURNAL |
|---|---|---|---|
| Table / id | `memos.id` | `memos.id` (`kind = 'outline'`) | `diary_entries.id` |
| Title | stored column, user-typed (`MemoEntity.kt:21`); first-line fallback at display | column, normally `""` (`OutlinerScreen.kt:338` never sets it) | none; first body line at display (`Timeline.kt:146`) |
| Body | plain text | plain text read as an outline (`OutlineText.parse/serialize`, round-trip exact, `OutlineText.kt:83-90`) | plain text |
| Place | `memos.folderId` | `memos.folderId` | none (a journal is placed by date) |
| Time | `createdAt` / `updatedAt`, caller-supplied `now` (`MemoRepository.save(…, now, …)`) | same | `createdAt` / `updatedAt` from the repository's `TimeProvider` (`DiaryRepository.kt:61,96`) + `diaryDateEpochDay` |
| Create | `createEmpty(now, folderId)` / `save(null, …)` (`MemoRepository.kt:63,108`) | `createOutline(now, folderId)` (`:72`) | `createEntry(epochDay)` (`DiaryRepository.kt:60`) |
| Write | `save(existing, title, body, now, kind, folderId)` → `updateContent` (`MemoDao.kt:94`); **unchanged body still bumps `updatedAt`** at this layer | same | `saveBody(id, body, releaseIfBlank)`; **unchanged body leaves `updatedAt` alone** (`DiaryRepository.kt:95`); LOCKED refused (`:85`) |
| Append | none (read → concat → `save`) | none at repository level; `OutlineEditing.appendLine(doc, zoomId = null)` (`OutlineEditing.kt:64`) appends a root `- ` node in the domain | none |
| Editability | none at the data layer (a trashed memo can still be `save`d) | same | `DiaryStatePolicy.canEdit` (LOCKED) |
| Lifecycle | favourite / pin / archive / trash / restore / purge | same | draft release on blank; states kept but only LOCKED matters |
| Children | comments, photos, tags, note membership | comments, photos, tags | photos, **future comments** |
| Open | `MemoDestination.EDITOR` → `editor/{id}` | `MemoDestination.OUTLINER` → `outliner/{id}` | `journal/{entryId}` (callers navigate directly, `MemoRippleApp.kt:228,396,418`) |

Common: an id, a kind, a body, `createdAt` / `updatedAt`, "created this day / touched this day"
(already unified by `TimelineBuckets`). Different: title ownership, place, clock ownership,
unchanged-body rule, editability, children.

## 6. Search — the minimal boundary

**Today.** One engine, `MemoSearch` (`domain/memos/MemoSearch.kt:34`): `parse(raw)` → terms / `"phrase"` /
`#tag` / `-not`; `matches(query, title, body, tagNames)`; in memory, NFKC-folded. The wall reads
*all* standalone memos and outlines with an empty SQL query (`MemoListViewModel.kt:146,148`) and
filters via `MemoOrganizationPolicy.organize` (`MemoOrganization.kt:72`). The DAO `LIKE` clauses
are never fed a term (`MemoRepository.kt:41-52` callers all pass `""`). No FTS table.

| Reach | Standalone memos | Outlines | Note episodes | Journals | Archived / trashed |
|---|---|---|---|---|---|
| Wall search | yes | yes (own list) | **no** (`noteId IS NULL`, `MemoDao.kt:35`) | **no** (other table, no diary source) | **no** (own screens: `MemoLifecycleViewModel.kt:46-67`, which *do* reach archived outlines and episodes) |
| Diary page / calendar | — | — | — | **no search at all** (`DiaryDao` has no LIKE) | — |

**Design.** Chat SEARCH = the LLM produces a *query*, MemoRipple runs it. So the boundary needs
one function and one query type, and the matching stays where it is:

```kotlin
data class DocumentQuery(val text: String /* MemoSearch syntax */, val kinds: Set<DocumentKind> = all,
                         val folderId: Long? = null, val includeEpisodes: Boolean = false,
                         val from: LocalDate? = null, val to: LocalDate? = null, val limit: Int = 50)
suspend fun search(query: DocumentQuery): List<DocumentSummary>   // on DocumentAccess (§9)
```

Implementation sketch (no FTS, no schema): memos / outlines through the existing DAO flows +
`MemoSearch.matches`; journals through `DiaryDao.observeAll` / `observeBetween` + the same
`MemoSearch.matches(query, title = "", body = entry.body)`; date bounds map to `createdAt` /
`updatedAt` for memos and to `diaryDateEpochDay` for journals (the calendar's rule). Episodes and
archived rows are opt-in flags so Chat cannot surface a trashed memo by accident. FTS is a later
optimisation behind the same function.

## 7. Open — the minimal boundary

Today the domain already stops short of route strings for memos (`MemoDestination`), while the
journal route is spelled by every caller. The boundary keeps that separation and completes it:

```kotlin
sealed interface DocumentDestination {          // domain/documents — no NavController, no strings
    data class MemoEditor(val memoId: Long) : DocumentDestination
    data class Outliner(val memoId: Long) : DocumentDestination
    data class Journal(val entryId: Long) : DocumentDestination
}
fun DocumentRef.destination(): DocumentDestination   // MEMO → MemoEditor, OUTLINE → Outliner, JOURNAL → Journal
```

UI side: one `DocumentNavigator` (the present `MemoNavigator`, `ui/MemoRippleApp.kt:562`, widened)
maps a `DocumentDestination` to `Routes.editor / outliner / journal`. `open(ref)` therefore
belongs to the **UI** layer as `navigator.open(ref)`; the domain only answers *what kind of
screen*. `kindOf(id)` (the lookup when a caller has only a memo id) stays in the navigator; a
`DocumentRef` never needs it because it carries the kind. Chat's OPEN resolves to a
`DocumentRef` and hands it to the navigator — it never sees a route.

## 8. Create / Append — the minimal boundary

| Operation | Available today | Boundary shape (v1) |
|---|---|---|
| Create memo | `MemoRepository.createEmpty(now, folderId)`; `save(null, title, body, now, MEMO, folderId)` | `create(kind = MEMO, folderId, title?, body?) → DocumentRef` |
| Create outline | `createOutline(now, folderId)` (body `""`, `title ""`) | `create(kind = OUTLINE, folderId, body?)`; a body must round-trip `OutlineText` |
| Create journal | `DiaryRepository.createEntry(epochDay)` (body `""`, DRAFT); no folder; several per day | `create(kind = JOURNAL, date)`; the day is the only "place" |
| Append to memo | none; read → concatenate → `save` | `append(ref, text)` = load, `body + "\n" + text`, `save` |
| Append to outline | domain only: `OutlineEditing.appendLine(parse(body), zoomId = null)` (`OutlineEditing.kt:64`) adds one root `- ` node; UI is the only caller (`OutlinerScreen.kt:836`) | `append(ref, text)` = parse → `appendLine` root → set text → serialize → `save`. **v1 limited to one new root node at the end**; no insertion at a position, no replace |
| Append to journal | none; `saveBody(id, existingBody + text)` | `append(ref, text)` = load, respect `canEdit` (LOCKED → refused), `saveBody` |

Every path exists at repository / domain level already; what is missing is the one use-case
class that hides the three signatures, supplies the clock for memos (so Chat never passes `now`),
and enforces refusals uniformly (`DocumentWriteResult { Done(ref) | NotFound | ReadOnly | Rejected(reason) }`).

## 9. Concurrency — `expectedVersion` on today's columns

- There is **no revision column** on `memos` or `diary_entries`; the only guards are per-ViewModel
  (`MemoEditorViewModel.kt:170-171,703` `contentRevision/savedRevision`; `DiaryEditorViewModel.kt:104,256`
  `savedBody`; `SplitWorkspace.kt:433,449` content compare). Two editors on one memo overwrite
  each other (last write wins). `MemoRepository.save` bumps `updatedAt` even for an unchanged
  body; `DiaryRepository.saveBody` does not (`:95`).
- `updatedAt` can serve as the **expected version** for Chat v1 without a new column: a
  `ResolvedCommand(ref, expectedUpdatedAt, operation)` is applied only if the row's `updatedAt`
  still equals `expectedUpdatedAt` (read → compare → write inside one transaction /
  `operationMutex`). Caveats to design in, not around: (a) memo `updatedAt` has millisecond
  resolution and is caller-supplied — the use case must read it back from the row it loaded, not
  from a clock; (b) a memo's `updatedAt` also moves on an unchanged-body save from the editor
  (§5), which is a harmless false conflict, not a lost update; (c) favourite / pin / archive /
  reorder do **not** move `updatedAt` (`MemoDao.kt:99-103,235`), so a concurrent pin is not a
  conflict — correct for body appends. No revision counter is needed for v1.

## 10. Future Diary — outside the common API

The future comment is not a document: it has no place, its text is withheld by SQL until first
presentation, its lifecycle is time-driven (`markDueDelivered` on resume, `revealNext`,
`markFirstPresented`), and creation is gated to *today's* entry (`FutureDiaryCommentRepository.kt:54-62`).
Folding it into `DocumentKind.JOURNAL` would let a search or a Chat APPEND reach sealed text.
**Recommendation:** Future Diary = specialised feature with its own lifecycle; `DocumentContent`
for a JOURNAL exposes at most a *count* of future comments in its metadata, never their text;
no `DocumentKind.FUTURE_COMMENT`. Chat may later get an explicit, separate capability for it.

## 11. Minimal implementation before Chat (proposal, in priority order)

Nothing below changes a table, a DTO, a route or the editor. Each is small and testable alone.

1. **`domain/documents/Document.kt`** — `DocumentKind` (moved; `Timeline.kt` re-exports or
   imports it), `DocumentRef(kind, id)`, `DocumentSummary`, `DocumentContent`, `DocumentMetadata`,
   `DocumentDestination` + `DocumentRef.destination()`. The calendar's `DocumentRef.Memo/Journal`
   becomes a `toDocumentRef()` view; `TimelineItem` gains nothing.
2. **`DocumentAccess` use case (`domain/documents/DocumentAccess.kt`, implemented in `data/` as
   `DocumentAccessImpl(memoRepository, diaryRepository, folderRepository, tagRepository, timeProvider)`)**:
   `search(DocumentQuery)`, `get(ref): DocumentContent?`, `summaries(refs)`, `create(...)`,
   `append(ref, text, expectedUpdatedAt)`. Memo appends supply `now` from `TimeProvider`; outline
   appends go through `OutlineText` + `OutlineEditing.appendLine` (root only); journal appends
   respect `canEdit`. All return a `DocumentWriteResult`. This is the only door Chat's Resolver
   and UseCases get — **no DAO, no Room type crosses it** (results are domain classes, not entities).
3. **`DocumentNavigator`** — rename / widen `MemoNavigator` to accept a `DocumentDestination`
   (the journal route joins the two memo routes); the three direct `Routes.journal(...)` calls in
   `MemoRippleApp.kt` go through it. Behaviour unchanged.
4. **Title rule in one place** — `DocumentSummary.title` owns "title, else first non-blank line,
   else 無題のメモ / 無題のアウトライン / （本文なし）"; `Timeline.kt:121,146` and
   `MemoViewModePages.kt:529` call it.
5. **Journal search reach** — `search` covers journals from day one (the wall's own search stays
   memo / outline as it is; the calendar gets no search field in this step).
6. **Two stale comments** — `BodyReading.kt:37-41` ("nothing records which a memo is") and
   `TemplateRepository.kt:19-23` ("not carried by a backup"): fix the words, not the code.

Deferred by the brief: entity merge, journal → `memos`, a global document table, Backup format
changes, FTS, embeddings / vector DB, Chat UI, Local LLM, any destructive operation. Also
deferred by this audit: a revision counter (§9 shows `updatedAt` suffices for v1), arbitrary
outline insertion / replace, note episodes as a document kind (they remain memos inside a note).

## 12. Tests that would pin the boundary (for the implementation round)

Unit: `DocumentRef.destination()` per kind; `DocumentSummary.title` fallbacks; `DocumentAccess`
against the existing fakes — search reaches memo / outline / journal and honours `kinds`,
`folderId`, dates, `includeEpisodes`; `append` on MEMO / OUTLINE (root node only, round-trip
exact) / JOURNAL (LOCKED → `ReadOnly`); `expectedUpdatedAt` mismatch → `Rejected(Conflict)` and no
write; unchanged-body semantics recorded as they are today. Device: the calendar, wall and
journal routes still open through the navigator (existing `CalendarInstrumentationTest`,
`MainActivityNavigationTest`, `JournalInstrumentationTest` unchanged).
