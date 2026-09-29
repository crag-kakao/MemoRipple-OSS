# Diary redesign audit — every place that depends on 「1日1Diary」

Audit date: 2026-09-17. Tree: `main` `a9d6487` (product code `c7d7cdc`, 1.1.0 / vc4, Room 22,
Backup 17). Read-only; nothing in this document has been changed in code yet.

Purpose: before the diary becomes "dated journal entries, several per day", list what today
enforces one entry per day, what consumes the DRAFT / FINALIZED / CORRECTING / LOCKED machine, and
what a migration must preserve. Baseline on this tree: unit 870 / 0, lintDebug 0 errors (32
warnings, 1 hint), full device suite OK (317, 4 assumption skips) on the same product code
(HANDOFF §16.14), S20 real-data migration 21→22 verified (§16.15).

## 1. The contract as it exists

| layer | what enforces one entry per day | file |
|---|---|---|
| Room schema | `UNIQUE INDEX index_diary_entries_diaryDateEpochDay` on `diary_entries(diaryDateEpochDay)` | `DiaryEntryEntity.kt:8-11`, created by `MIGRATION_3_4` (`AppDatabase.kt:112-116`) |
| DAO | `findByDate(epochDay) … LIMIT 1`; `insertIgnoringConflict` (IGNORE → `-1` means "the day already has its row"); `lockEntriesOutsideDate` (the midnight sweep, raw `'LOCKED'` literal); `ORDER BY diaryDateEpochDay DESC` with no id tiebreak | `DiaryDao.kt:12-49` |
| Repository | three get-or-create-by-date paths: `saveBody` (:63-114), `finalizeDraft` (:116-155), `ensureDraftForAttachment` (:202-229); `isToday(epochDay)`; every write behind one mutex | `DiaryRepository.kt` |
| Navigation | the editor route is `diary-editor/{epochDay}` — addressed by **date**, no route can name a particular entry | `MemoRippleApp.kt:105, 119, 372-382` |
| UI | selected-day card = `monthEntries.firstOrNull { date == selected }`; `DiaryCalendarDay.hasDiary: Boolean` (presence, not count); "past" = `date != today`; the editor VM takes `diaryDateEpochDay`, not an id | `DiaryScreen.kt:162, 214-218, 279-282, 543-609`; `DiaryCalendar.kt:8-15`; `DiaryEditorViewModel.kt:61, 327-351` |
| Backup | `BackupValidator.validateDiaries` refuses a file whose diary dates are not distinct (`DUPLICATE_DIARY_DATE`) — with **no format-version gate** | `BackupValidator.kt:260-271` |
| Portable export | the export folder and `diary.md` path are derived from the date → two entries on one day collide | `PortableExportPlanner.kt:108-118` |
| Future comments | `create()` accepts only *the* entry whose date is today (`NOT_DIARY_DATE`); everything else is keyed by `diaryEntryId` | `FutureDiaryCommentRepository.kt:58-62`, `FutureDiaryCommentEntity.kt:8-33` |

Dates: `diaryDateEpochDay` is an epoch **day** (`Long`); every other timestamp is epoch millis; no
text dates, no stored zone. "Today" always comes from `TimeProvider.currentLocalDate()` (system
zone). Midnight crossing: `DiaryEditorViewModel.refreshForResume` shows 「日付が変わったため…」 and
the lazy sweep locks yesterday on the next read/resume/save (no scheduled job).

## 2. The state machine and its consumers

`DiaryState { DRAFT, FINALIZED, CORRECTING, LOCKED }`, `DiaryStatePolicy.canTransition`:
DRAFT→{FINALIZED, LOCKED}, FINALIZED→{CORRECTING, LOCKED}, CORRECTING→{LOCKED}, LOCKED→∅;
`canEdit` = DRAFT ∨ CORRECTING. Per-state timestamps live on the row (`finalizedAt`,
`correctionStartedAt`, `lockedAt`).

Consumers (all must be touched or explicitly left alone by the redesign):

- data: `DiaryRepository.kt` (15 sites), `DiaryConverters.kt` (`name` ↔ enum, `valueOf` throws on
  unknown), raw literals in `DiaryDao.kt:39, 45`.
- ui: `DiaryEditorViewModel.kt:47, 55, 257, 346, 360, 378` (`:346` synthesises LOCKED for a past day
  with no row); `DiaryEditorScreen.kt:435-462` (state → action button: `finalize_diary`,
  `start_correction`, `finish_correction`, `diary_locked`); `DiaryScreen.kt:686-691` (labels
  編集中／確定済み／修正中／ロック済み).
- backup: `BackupMapper.kt:82, 219, 336-343` (lower-case storage ids), `BackupValidator.kt:389`
  (`DIARY_STATES`).
- portable export: `PortableExportEngine.kt:471-476` (state → Japanese label in the export).
- overlay: none. speech / playback: none (they consume revealed future comments only).

Future comments have their own lifecycle by nullable timestamps (`sealedAt → revealAt →
deliveredAt? → revealedAt? → firstPresentedAt?`), not an enum; SEALED / DELIVERED / REVEALED are
UI names for those. It is independent of `DiaryState` and can stay as is.

## 3. Tests that pin the contract (will need to change or be re-scoped)

One entry per date:
`DiaryRepositoryTest` (`blankDraftIsNotStoredAndTheSameDateReusesOneEntry`,
`aNewLocalDateCanCreateItsOwnSingleEntry`, `monthRangeObserves…`, `clearingAPersistedDraft…`, and
the fake DAO at :186-238 which re-implements the unique-date rule);
`AppDatabaseMigrationTest.migrationFrom3To8KeepsMemoCommentOrderAndCreatesUniqueDiaryDates`
(asserts the duplicate insert returns `-1`); `DiaryCalendarDaoInstrumentationTest`;
`BackupValidatorTest.rejectsDuplicateDiaryDate`.

State machine / midnight: `DiaryStatePolicyTest` (2), `DiaryRepositoryTest`
(`finalizeCorrectionAndPermanentLock…`, `localDateChangeLocksEveryEditableState…`,
`lockIsIrreversibleEvenWhenTheClockMovesBack`, `expiredFinalizeIsRejected…`),
`MainActivityNavigationTest.diaryCanFinalizeUseItsSingleCorrectionAndBecomeLocked`,
`…diaryPhotoReorderEntryFollowsDraftCorrectionAndLockedStates`,
`…calendarAndPastTodayOpenExistingLockedDiaryWithoutFutureSendAction`,
`AppDatabaseMigrationTest` 4→8 / 5→8 / 12→13, `BackupValidatorTest.rejectsUnknownDiaryState`.

Date-keyed UI: `MainActivityNavigationTest` (`bottomNavigationMovesBetweenMemoAndDiary`,
`deliveredFutureCommentIsAnnounced…`, `diaryCalendarSelectsToday…`, `theCalendarOpensAsOneWeek…`,
`diaryPhotoRelationSurvives…`, `memoDiaryAndSearchEmptyStates…`), `DiaryCalendarTest` (6),
`PastTodayFactoryTest` (3), `ProjectBoundaryPolicyTest.navigationHasMemoAndDiaryAsTheOnlyBottomDestinations`.

Id-keyed and mostly safe: `FutureDiaryCommentRepositoryTest` (8), playback mapper tests,
`FutureCommentExpressionInstrumentationTest`, photo / backup / export / drive-dirty tests that seed a
diary row per day.

## 4. What the data actually is (S20, 2026-09-16, read-only)

Real data on the SC-51A: `diary_entries` 0 rows, `future_diary_comments` 0 rows; memos 2, folders 2.
The emulator carries test residue only. So the redesign migrates a schema that real users have not
yet filled — but the Play vc3 testers may have entries, and the migration must be correct for any
number of rows.

## 5. createdAt / updatedAt semantics (for the Calendar's 作成 / 更新 split)

- All four tables store epoch millis; only `diaryDateEpochDay` is a day.
- **memos:** `updatedAt` is written only by `MemoDao.updateContent` (title/body save). Favourite,
  pin, archive, trash, restore, folder move, sort do **not** bump it. Opening / reading does not bump
  it (`MemoEditorViewModel` saves only when the content revision moved). The wall orders by
  `updatedAt DESC`, so this is a real "last written" signal.
- **notes:** bumped by rename / cover colour / subtitle / cover picture; `sortIndex` does not bump
  it; the shelf shows `lastWrittenAt = MAX(episode.updatedAt, note.updatedAt)`.
- **folders:** bumped by rename / re-parent only; moving a memo into a folder bumps neither.
- **diary:** bumped by every save — including `saveNow` on ON_PAUSE / Back with no dirty guard, so
  merely opening today's entry and leaving moves `updatedAt`; the midnight sweep also writes it.
  Invisible today (diary orders by date), it becomes visible once entries carry an intra-day order or
  appear in a "更新" list → the diary editor needs the memo editor's revision guard.
- Indices on time columns: only `diary_entries.diaryDateEpochDay` (unique) and
  `future_diary_comments.revealAt`. **No index on `memos.updatedAt` / `createdAt`.**

## 6. Redesign pressure points (what a migration plan has to answer)

1. **Drop the unique index** (`DROP INDEX`, no table rebuild) and replace the three
   `insertIgnoringConflict`-based get-or-create paths with explicit "create a new entry" / "open
   entry N". Keep a non-unique index on the day for range queries, plus an index on `(day, createdAt)`
   for intra-day order.
2. **Route by id**: add `diary-editor/entry/{entryId}` (or reuse the memo editor's shape) and keep
   `diary-editor/{epochDay}` only as "open or create today's first entry" for old callers /
   PastToday.
3. **State**: map DRAFT / FINALIZED / CORRECTING → `NORMAL` (editable) and LOCKED → `LOCKED` for
   *new* behaviour, but keep the old enum values readable (`DiaryConverters.valueOf` must not throw
   on old rows; backups from 1.0 / 1.1 carry the lower-case ids). Two options: (a) keep the column
   and the old names, change only the policy so FINALIZED / CORRECTING behave as NORMAL — no
   migration at all; (b) add `entryState` with a rewrite. Option (a) is the reversible one and is the
   recommended first step; the midnight sweep becomes opt-in ("lock past days" setting) or is
   dropped.
4. **Backup**: relax `DUPLICATE_DIARY_DATE` only for `formatVersion ≥ 18`, bump
   `BACKUP_FORMAT_VERSION` to 18, keep restoring v1–17 unchanged; 1.1.0 must refuse a v18 file
   (it already refuses anything above 17).
5. **Export**: per-entry folder names (`YYYY-MM-DD/HHmm-<id>/`) or one `diary.md` per day with
   sections.
6. **Future comments**: change the `create()` gate from "the entry of today" to "an entry whose day
   is today"; nothing else changes (already id-keyed).
7. **UI**: `DiaryCalendarDay.hasDiary` → a count; the selected-day card → a list; PastToday shows
   the day's entries; the "today" rules (zone, midnight) stay in `TimeProvider` — the app, never an
   LLM, resolves relative dates.

Nothing above deletes a row or a column. The only DDL is `DROP INDEX` + `CREATE INDEX`, and every
existing row keeps its state value. HANDOFF §2 decisions to reopen explicitly before this work:
bottom navigation メモ / 日記 only (the plan moves to メモ / カレンダー / チャット), the Diary list page
as the sole home of the delivery banner and PastToday.

## 7. Decisions taken on this audit (2026-09-17, human — HANDOFF §16.18)

- Keep the state column and the four stored values; DRAFT / FINALIZED / CORRECTING become an
  ordinary editable journal, LOCKED stays locked, the midnight sweep is retired for ordinary
  journals. Future diary comments stay as they are.
- Documents are opened by entry id; dates are grouping / search keys only. The date route
  `diary-editor/{epochDay}` migrates to an id route with a compatibility path designed first.
- Calendar buckets existing timestamps by the device zone at display time; no day column is added;
  "today" is local 00:00–23:59:59.
- The 日記 tab is absorbed into カレンダー; Journal remains as a kind, several per day.

## 8. Implemented (2026-09-17, `feature/journal-calendar` — HANDOFF §16.20)

Room 23 (index swap only), `journal/{entryId}` + `journal-day/{epochDay}` + the date shim,
LOCKED-only read-only policy, no midnight sweep, editor save guard, Backup 18 (duplicate dates
allowed from 18, templates carried), export paths per entry. The tests listed in §3 were rewritten
first (RED) and are green on the emulator.

## 9. Calendar v1 (2026-09-18, `feature/calendar-timeline` — HANDOFF §16.22)

The §5 plan as built: no day column, days bucketed by the device zone at display time
(`TimelineBuckets`), indexes on `memos.createdAt` / `memos.updatedAt` (Room 24), a month range
query, one time axis over memos, outlines and journal entries with [すべて] [作成] [更新].
