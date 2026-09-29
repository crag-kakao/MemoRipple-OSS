# Journal search — audit before the implementation (2026-09-18)

> **Status:** built on `feature/journal-search` exactly as decided below — see HANDOFF §16.33 and
> the search section of `docs/DOCUMENT_BOUNDARY.md`.

Branch `feature/journal-search`, base main `cf832fa`. Goal: `DocumentSearch` reaches memos,
outlines **and journals**; no FTS, no schema change, `MemoSearch` not stretched over journals —
two searches composed into one `List<DocumentSummary>`.

## What a journal is, for search

| Field | Fact (`data/DiaryEntryEntity.kt`) | Search use |
|---|---|---|
| title | **no column**; the calendar and `DocumentTitles` show the first non-blank body line, else `（本文なし）` (`Timeline.kt`, `DocumentTitles.EMPTY_JOURNAL`) | The first line is part of the body, so a body match already covers the "title-equivalent"; nothing is derived or stored for search |
| `body` | plain text | matched with `MemoSearch.matches` (NFKC-folded substring; terms / "phrase" / `-not`; `#tag` never matches a journal — journals have no tags) |
| `diaryDateEpochDay` | the entry's day (indexed with `createdAt`; several a day since Room 23) | **the date axis** of a journal: a `dateRange` selects on it, never on `createdAt` |
| `createdAt` / `updatedAt` | ms; `updatedAt` moves only on a changed body (`DiaryRepository.saveBody`) | `updatedAt` is the cross-kind sort key, as for memos |
| `state` | DRAFT / FINALIZED / CORRECTING / LOCKED; only LOCKED is read-only | a LOCKED entry is **searchable** (reading is not editing); `DocumentSummary` carries no state — `get(ref)` does |
| photos | separate relation | not searched |
| future comments | separate table, text withheld by SQL until first presentation | **never loaded by search**: excluded by construction, not by a filter |
| PastToday | a projection over `observeEntries()` by month-day | unrelated to search; unchanged |

## Reads available today (`data/DiaryDao.kt`, `DiaryRepository.kt`)

`observeAll()` (all rows, `diaryDateEpochDay DESC, createdAt DESC, id DESC`),
`observeBetween(startEpochDay, endEpochDay)`, `observeForDate`, `entriesForDate`, `findByDate`,
`findById`. **No `LIKE`, no FTS** — and none is added in this round. A dated search therefore uses
the existing range query; an undated one reads every row once and matches in memory.

## Volume and the in-memory choice

Journals are written by hand, at most a few a day: a year of daily writing is a few hundred
rows, a decade a few thousand, each a short text. Reading them all once per search (the
undated case) is the same order of work the wall already does for every keystroke over all memos
(`MemoListViewModel` reads the whole wall and filters with `MemoSearch`). Adequate for v1.
When it stops being adequate (tens of thousands of rows or long bodies), the order of moves is:
(1) always bound the read by a date range from the query or a default recent window,
(2) SQL `LIKE` on `body` in `DiaryDao` to pre-filter, (3) an FTS table over `memos` and
`diary_entries` behind the same `DocumentSearch` call. None of these changes the API.

## Decisions for v1

- **Scope:** `DocumentSearchScope.V1 = {MEMO, OUTLINE, JOURNAL}`; still out: note episodes,
  archived and trashed rows, future comments.
- **Query:** `DocumentQuery(text, kinds, folderId, dateRange, limit)`. `dateRange` is explicit
  epoch days (`DocumentDateRange(startEpochDay, endEpochDayInclusive)`); the caller resolves
  "today / yesterday / last week" with `TimeProvider` — the LLM never computes a date.
  A journal is in range by `diaryDateEpochDay`; a memo or outline by the local day of its
  `createdAt` or `updatedAt` (the calendar's rule, device zone from `TimeProvider`).
- **Empty text:** text blank **and** no date range → empty result (no unbounded scan); text
  blank with a date range → a date search; a kinds filter alone is not enough.
- **Folder:** `folderId` set → memos and outlines in that folder only; journals are not in
  folders and are left out rather than given one.
- **Order:** `updatedAt DESC` across kinds, then kind, then id — one order for the whole result;
  several journals on one day all come back.
- **Result:** `DocumentSummary` only; opening goes `DocumentRef → DocumentNavigator`.
