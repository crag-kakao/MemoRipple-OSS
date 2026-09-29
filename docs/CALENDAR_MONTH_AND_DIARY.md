| a status **chip**, always | **nothing** — unless the day is LOCKED, which is one small line beside the date |
| 「この日記はロックされています」 at the bottom too | gone: a locked day says so once |# Calendar「すべて」as the month, and the Diary as its body

> Human brief 2026-09-23 (`feature/calendar-month-and-diary-calm`, stacked on
> `feature/chat-home-launcher`). Two UI/UX changes to things that already exist — the calendar's
> 「すべて」 and the diary page's weights. No new capability, no schema move.

## 1. What 「すべて」 means now

Before, every filter answered **「この日になにをしたか」**, so choosing a day with nothing on it left
the page empty and the calendar could not answer 「今月なにを書いたか」.

**「すべて」 is now the month**: the displayed month, day by day, **newest day first**, with memos,
outlines and journal entries together. **作成 / 更新 stay the selected day's** (the human's decision
of 2026-09-23), so the calendar keeps both of its uses:

| | |
|---|---|
| choose a date, with 作成 or 更新 | that day's records |
| 「すべて」 | the whole displayed month |

The line above the list says which: 「2026年9月の記録」 under すべて, 「9月24日（木）」 otherwise.
「日記を書く」 is unchanged and always writes for the **selected** day — it says so to a screen
reader (「9月24日（木）の日記を書く」).

## 2. The meanings did not change

`TimelineBuckets` already defined them, and they are untouched:

- **作成** — `createdAt` falls on the day;
- **更新** — `updatedAt` falls on the day and is not the creation itself;
- **すべて** — either; a document that did both on one day is one row saying 作成・更新;
- a **journal** sits on its **diary day** (that is what a day means for it) and counts as updated on
  the day it was written to.

`TimelineBuckets.monthItems` is the **same rule applied to every day the rows touch**, so a month is
the sum of its days. One refinement belongs to the month alone: a document created on one day and
updated on another is **one row, on the later of the two**, still labelled 作成・更新 — a month is
read once through, while a day view answers for its own day.

An empty month says 「この月の記録はありません」 (`timeline_month_empty`), which is deliberately not
the day's sentence 「この日の記録はありません」 (`timeline_empty`).

## 3. How the month is fetched

**No new query and no schema change.** `CalendarViewModel` already loads the whole displayed month —
`memoRepository.observeTouchedBetween(monthWindow)` and `diaryRepository.observeEntriesBetween(month)`
— because the grid's dots need it. The month list is a **projection over rows already in memory**;
nothing scans the database from Compose, and `monthItems` is only computed under すべて.

Known and unchanged from the day view: a journal whose **diary day** is in another month is not in
this month's rows even if it was written to this month, because the journal query is by diary day.

Untouched: the grid, its dots, the selection rule (no future day), 今日, 日記を書く, 過去の今日, the
delivered-future banner, and the canonical `DocumentRef` route every row opens through.

## 4. The Diary page

The page is the body. Everything it could do, it still does.

| before | now |
|---|---|
| a status **chip**, always | a small `labelSmall` line; the error colour only for ロック済み |
| an **OutlinedTextField** | the text on the page — no card, no outline, `bodyLarge`, its placeholder the only thing in an empty day |
| photos above the body | photos under the body (and still out of the way while the keyboard is up) |
| 「今日の気持ちを、指定した日時まで封印して送れます。」 | 「指定した日時まで封印して送れます。」 |
| a full empty-state block for future comments | 「まだメッセージはありません。」 |
| 「コメントを送る」 | 「未来へ送る」 |

The top bar was already the date plus the reading action, and stays so. The save status stays small
at the bottom, beside the photo action. 未来の自分へ keeps its divider and reads as the secondary
section it is.

**Unchanged, and tested:** autosave and its `保存中… / 保存済み`; the DRAFT / FINALIZED / CORRECTING /
LOCKED lifecycle and every transition; a LOCKED day read-only (the field offers no way to set text)
with no photo action; the reading action; the attachment action and its label; Future Diary creation,
sealing, delivery and reveal; Back; and what survives a process recreation.

### 4.2 A preview keeps its line breaks, and the body carries the caret (2026-09-24, the user's review)

A diary preview used to flatten the entry with `body.replace('\n', ' ')`, so a day written in sections
read as one run-on line. Every preview now draws the body **as it was written** — the 過去の日記
card, the selected day's card, the first of several on a day, 過去の今日 and a day's entry list —
still cut at the same number of lines.

And the body is the **memo editor's own field**. A Material `TextField` given a fixed height lays
its text out but does not scroll its cursor into view, so an open keyboard hid the line being
written. `EditorTextField` (a `BasicTextField`) inside the same bounded box scrolls with the caret,
exactly as the memo editor has always done; it keeps the tag, the placeholder and the read-only
behaviour of a locked day, and draws no markup of its own.

Whether the caret is on screen is a scroll offset Compose does not expose, so the test pins what it
can — a long day taken whole, kept, and still one field of thirty-odd laid-out lines — and the
keyboard itself is checked on a device.

One more thing the keyboard showed: while it is up the page scrolls and its column used to **fill
the viewport**, so whatever the bounded body did not take was left as a band of nothing after
未来の自分へ. The column now takes only the height of what it holds while it scrolls; without the
keyboard it still fills, because there the body absorbs the slack.

### 4.1 The state is shown only when it still means something (2026-09-24, the user's review)

「編集中」 is left over from the retired one-a-day lifecycle: a journal is no longer written once and
confirmed, so the word describes nothing the reader can act on. Nothing in the product produces
`FINALIZED` or `CORRECTING` any more either — they come back only from an old backup. `LOCKED` is
the one state that still changes what the page does.

So **every place that drew the state now draws it only for LOCKED**: the diary editor, the 日記一覧
card for the selected day, the 過去の日記 card, and the list of a day's entries. And a locked day
**says so once** — the sentence that repeated it at the bottom of the editor is gone, leaving the
small line beside the date.

The lifecycle itself, `DiaryStatePolicy`, the storage, the backup and the portable export's wording
are untouched: only what the screen says changed. The three test classes that asserted the locked
sentence now assert that one line, so they still test something.

## 5. Storage

Room **25**, Backup **19**, versionCode 4 / 1.1.0 — all unchanged. No new preference, permission or
dependency; the calendar and the diary store nothing new.

## 6. Not in this round

A month list that folds by kind, a jump from the grid to a day's group in the month list, 作成 / 更新
as month-wide filters (the human chose the day), Future Diary in the month list (it means something
else and keeps its own path), and any change to the diary lifecycle or the Future Diary data model.
