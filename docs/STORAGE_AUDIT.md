# Internal storage audit — what MemoRipple writes, what grows, what is safe to trim

Audit date: 2026-09-17, tree `main` `a9d6487` (1.1.0 / vc4, Room 22, Backup 17). Read-only:
measurements were taken with `du` / `ls` through `run-as`; nothing was deleted or vacuumed.
This is the baseline to compare against after the Diary / Calendar / Chat work.

## 1. Measured today

| device | total app data | databases | files | cache | note |
|---|---|---|---|---|---|
| SC-51A (S20, real data: 2 memos, 2 folders, 0 photos) | ~430 KB | 400 KB (`memo-ripple.db` 160 KB, `-wal` 206 KB, `-shm` 32 KB) | 30 KB | — | measured 2026-09-16 before the 21→22 migration; the WAL alone is larger than the DB |
| emulator-5554 (test residue) | 1,120 KB | 712 KB (db 164 KB, wal 495 KB, shm 32 KB) | 72 KB (attachments 24, datastore 24, comment_fonts 8) | 320 KB (26 files: 13 `restore-*.preferences_pb`, 3 `b03-*.png`, 2 `.lck`, `drive-tag-dirty-*`, `backup/` 24 KB) | every cache file was written by instrumentation tests, not by the product |

Directories the app can use: `filesDir` (attachments, datastore, comment_fonts, profileInstalled),
`cacheDir` (backup / drive / portable staging only), `databases`. Not used: `noBackupFilesDir`,
`getExternalFilesDir`, `shared_prefs`, `openFileOutput`, WorkManager, log files.

## 2. Where bytes go (inventory)

| path | written by | grows with | cleaned by | safe to delete without losing user data |
|---|---|---|---|---|
| `databases/memo-ripple.db` | Room (`AppDatabase.kt:531-537`; default WAL, no `setJournalMode`, no `setAutoCloseTimeout`) | memo/outline/episode bodies, comments, diary entries, future comments, tags, notes, folders | user deletes + empty trash only; **never `VACUUM`ed**, freed pages stay in the file | no |
| `databases/memo-ripple.db-wal`, `-shm` | SQLite | write volume between auto-checkpoints; the app never checkpoints explicitly | SQLite (auto-checkpoint at 1000 pages; truncates on last close) | no (not while open) |
| `files/attachments/blobs/<sha256>` | `AttachmentBlobStore` | imported / restored photos, deduplicated by content hash (no refcount column; references counted live by `AttachmentDao.unreferencedBlobShas`) | `AttachmentRepository.garbageCollect()` on app start, on relation-table invalidation, after photo delete / cover clear / import / restore | no — unreferenced blobs already go automatically |
| `files/attachments/blobs/.<sha>-<uuid>.tmp`, `files/attachments/tmp/*.tmp` | import / install staging, portable-export metadata strip | interrupted operations | the same GC sweeps them | yes |
| `files/comment_fonts/<uuid>.ttf` | `CommentFontStore` (≤ 30 MiB each, list capped at 20) | each installed comment font | user delete only — **no GC**; `SettingsScreen.kt:186-188` installs the file *before* the list is truncated to 20, so a 21st font leaves an orphan file forever | partial (orphans yes, listed fonts no) |
| `files/datastore/app_settings.preferences_pb` | `SettingsRepository`, `TemplateRepository` | 55 string keys; capped lists: favourite comments 10, user fonts 20, speech dictionary 500, my cover colours 6, templates 30; **uncapped: `outliner_folds` and `outliner_scroll`** — one entry per memo ever opened in the outliner, never pruned when the memo is deleted (only `clearOutlinerFolds()` wipes all) | — | no (holds every setting) |
| `files/datastore/drive_backup_state.preferences_pb` | `DriveBackupStateStore` | fixed (6 scalars) | — | no |
| `cache/backup/backup-<uuid>.mrbackup` | `BackupEngine.prepareBackup` | one per local / Drive backup run | `PreparedBackup.discard()` on every live path; **leaks on process death before the SAF picker returns** | yes |
| `cache/backup/import/saf-<uuid>.mrbackup` | `SafBackupFileStore` | one per restore / import pick | caller `discard()` in `finally` | yes |
| `cache/backup/restore/restore-<uuid>/` | `BackupContainer.inspect` | the whole blob set of an inspected archive (≤ 2 GiB) | `RestoreCandidate.discard()`; **leaks on process death between inspect and confirm / cancel** | yes |
| `cache/backup/drive/download-<uuid>.mrbackup` | `DriveApi` | one per Drive restore | discarded on success / over-size | yes |
| `cache/portable-export*.zip / .pdf`, `cache/portable-import*` | `PortableExportEngine`, `PortableImportEngine` | one per run | `finally { delete() }` everywhere | yes |
| SAF folder chosen for 読める形式の自動書き出し | `PortableAutoExporter` | **one new full ZIP per interval, never overwritten, never deleted** (by design, `PortableAutoExporter.kt:17-19`) | nothing | user-owned storage outside the sandbox — the largest open-ended growth source |
| `files/profileInstalled`, GMS / Drive SDK caches | platform / libraries | bytes | platform | yes (regenerated) |

Not on disk at all: undo / redo (in-memory `EDITOR_HISTORY_LIMIT` = 50, dropped with the
composition), bulk-action undo (ViewModel snapshot), comment drafts (`rememberSaveable`), TTS audio
(`TextToSpeech.speak` only, no `synthesizeToFile`), image thumbnails (in-memory `LruCache` 16 MiB),
overlay state (`MutableStateFlow`), logs, crash reports.

Trash / archive: a trashed memo keeps its comments, tags, photo relations and blobs until it is
permanently deleted or the trash is emptied — there is no retention or automatic purge. Deleting a
note or a chapter releases its episodes (rows are kept); deleting a folder re-parents its children.
Diary rows are deleted only as blank drafts; FINALIZED / LOCKED entries and delivered future
comments accumulate for life. All of that is user data, not waste.

## 3. Growth factors, ranked

1. **Photos** — the only source that reaches tens or hundreds of MB. Dedup and GC already work;
   the cost is the trash: bytes are held until the trash is emptied.
2. **The auto-export ZIP folder** — unbounded by design, but outside the sandbox and user-visible.
3. **The DB's free pages after deletes / empty-trash** — never returned; grows to the high-water
   mark. With bodies and comments as the only large text, this stays small for most users, but it
   is the one place where "delete" does not make the app smaller.
4. **WAL** — routinely larger than the DB on small databases (S20: 206 KB vs 160 KB). Bounded by the
   auto-checkpoint; a checkpoint on last close would return it to zero.
5. **`outliner_folds` / `outliner_scroll`** — small per memo, but never pruned; the only unbounded
   preference.
6. **Orphan comment fonts and leaked backup staging** — rare, only after a crash or a 21st font, but
   never reclaimed.

Future additions the plan will bring and their expected class: Chat log (text rows, class 3), draft /
template state (DataStore or a small table), search index / FTS (an FTS5 shadow table roughly the
size of the indexed text — would double the text footprint; not needed at today's volumes),
embeddings (hundreds of bytes to a few KB per document; only worth it with a real index), the AI
model itself (GB-scale, must live in its own directory, own screen, own delete).

## 4. Safe reductions (keep every user document)

| candidate | effect | cost / risk | recommendation |
|---|---|---|---|
| Sweep `cacheDir/backup/**` and `cache/portable-*` on app start for files older than the current process (e.g. > 1 day) | reclaims leaked staging after a crash; today zero for a healthy device | none for user data; must not touch a restore staging that is in flight (age check) | **do** (a few lines next to the existing `attachments/tmp` sweep) |
| Prune `outliner_folds` / `outliner_scroll` entries whose memo id no longer exists, at the existing GC point | bounds the only uncapped preference | a memo restored from backup gets a fresh (unfolded) view — acceptable | do, low priority |
| Delete `comment_fonts` files not referenced by `user_comment_fonts`, and truncate the list *before* writing the 21st file | reclaims orphans | none | do, low priority |
| `PRAGMA wal_checkpoint(TRUNCATE)` after a big write (restore, empty trash) or on last close | WAL back to zero | a checkpoint is cheap on these sizes; never run it inside a transaction | measure first on the emulator with a 5,000-memo fixture; adopt if the WAL routinely exceeds a few MB |
| `VACUUM` after empty trash / restore | returns freed pages to the OS | rewrites the whole DB (needs 2× free space, blocks writers for its duration); on a 160 KB DB it is instant, on 50 MB it is seconds | do **not** schedule blindly; expose as part of a future "ストレージ" screen action or run only when free pages exceed a threshold (`PRAGMA freelist_count`) — verify on the emulator first |
| Trash retention (auto-purge after N days) | frees photo blobs | **deletes user data** — a product decision, not a cleanup | not without a human decision and a visible setting |
| Cap the auto-export folder (keep last N ZIPs) | bounds the largest growth source | deletes files the user may rely on; folder is user-owned | offer as an option (default: keep all), never silently |

Nothing in this table removes a memo, an outline, a note, a diary entry, a comment or a referenced
photo.

## 5. Baseline to keep

Record these again after each schema-changing phase and compare:

```text
S20  2026-09-16 22:1x  db 163,840 B  wal 206,032 B  shm 32,768 B  files 30 KB  (2 memos, 2 folders)
S20  2026-09-16 22:3x  after main's 21→22: same sizes (ADD COLUMN only), user_version 22
emu  2026-09-17        db 167,936 B  wal 506,792 B  files 72 KB  cache 320 KB (all test residue)
```

A future 設定 › ストレージ screen can be fed from four numbers the app can compute itself:
`databases/` (db + wal + shm), `files/attachments/blobs/`, `cacheDir`, and later the model
directory and the chat log — without ever offering a button that deletes a document.

## 6. Decisions taken on this audit (2026-09-17, human — HANDOFF §16.18)

Approved for automatic implementation now: the `cache/backup` staging sweep, orphan outliner
fold / scroll pruning, orphan comment-font cleanup — **implemented on `feature/three-axes-prep`
(HANDOFF §16.19): `StagingSweeper`, `SettingsRepository.pruneOutlinerViewState`,
`CommentFontStore.removeUnlisted`, all from `MemoRippleApplication.sweepLeftovers()` at start.** WAL checkpoint and VACUUM wait for measurement.
Auto-export ZIP generations and trash auto-purge are not implemented. AI models and caches will
live under `noBackupFilesDir/ai/` and are never part of a backup; chat logs stay out of the
portable backup by default.
