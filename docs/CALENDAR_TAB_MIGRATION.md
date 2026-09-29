# 日記タブ → カレンダー タブ: migration table and UI proposal (2026-09-18)

Branch `feature/calendar-tab`, base main `21328d5`. Human brief: the main switch becomes
**メモ / カレンダー** now (チャット later, no stub yet); the 日記 tab is absorbed into the Calendar /
Activity Timeline; Journal survives as a document kind inside it; nothing is deleted outright —
the Calendar must hold every job of the diary page before the old page is trimmed.

## 1. What the 日記 tab does today (audit of `ui/diary/DiaryScreen.kt`, `DiaryViewModel.kt`)

| # | Diary-tab feature | Where it lives | Tags |
|---|---|---|---|
| D1 | Delivered-future-comment banner at the very top (`deliveredCount > 0`, 受け取る → `future-reveal/0`) | `FutureDeliveryBanner` | `future_delivery_banner`, `receive_future_comment` |
| D2 | Calendar card: one week by default, the title unfolds the month (setting `diaryCalendarCompact`), ‹ › by week or month, 今日, future days disabled, 日記あり marks | `DiaryCalendar` | `diary_calendar`, `calendar_mode_toggle`, `calendar_today`, `calendar_previous/next_week`, `calendar_previous/next_month`, `calendar_day_<epochDay>` |
| D3 | Selected-day card: one entry → 日記を開く; several → この日の一覧 (`journal-day/{epochDay}`); today → 今日の日記を書く / もう一つ書く (explicit creation, several a day) | `DiarySelectedDateSummary` | `calendar_selected_summary`, `calendar_open_diary`, `calendar_open_day`, `calendar_create_today` |
| D4 | 過去の今日 — same month-day in earlier years (`PastTodayFactory` over all journals) | `PastTodayCard` | `past_today_<epochDay>`, `past_today_open_<epochDay>` |
| D5 | 過去の日記 — every past journal as a card, newest first, or the empty state | `DiaryCard` | `diary_card_<id>`, `diary_past_empty_state`, `diary_list` |
| D6 | Top bar: 日記 title, カレンダー icon (→ `calendar`), 設定 icon | `ProductCompactTopBar` | `diary_compact_top_bar`, `open_calendar` |
| D7 | Journal editor by id, the day list, the `diary-editor/{epochDay}` shim, future-comment send / replay | routes `journal/{id}`, `journal-day/{epochDay}`, `diary-editor/{epochDay}`, `future-reveal/{id}` | unchanged |
| D8 | Month-change / midnight refresh (`refresh()` on resume: `markDueDelivered`, today moves) | `DiaryViewModel` | — |

## 2. Where each job goes (the migration table)

| Diary job | Calendar tab (this round) | Old diary page (kept as 日記一覧) | Later |
|---|---|---|---|
| D1 banner | **Moves.** Same banner, first item of the Calendar list; same tags; `markDueDelivered` on resume moves with it | Also still shown there (harmless duplicate until the page is trimmed) | Remove from the page when it is trimmed |
| D2 month grid | **Already there** (`MonthGrid`, per-kind marks, `timeline_day_<epochDay>`, `timeline_previous/next_month`, `timeline_today`). Month only, as the brief says | Week / month card stays as is | The week view and its setting are reviewed when the page is trimmed — not in this round |
| D3 open / create | **Rows already open** each document (`timeline_item_journal_<id>` → `journal/{id}`). **New:** an explicit 日記を書く button under the selected date for today and past days (`timeline_create_journal`) — `DiaryRepository.createEntry(epochDay)` then `journal/{id}`; several a day; never from navigation alone. The day list is not needed here: every entry is its own row | Unchanged | — |
| D4 過去の今日 | **Moves.** A 過去の今日 section after the day's rows, same cards and tags, whenever there is one | Also still shown there | Remove from the page when it is trimmed |
| D5 過去の日記 list | **Reachable, not duplicated:** a 日記一覧 action in the Calendar top bar (`open_journal_list`) opens the old page (route `diary`) as an ordinary secondary screen — back arrow, no bottom bar | This *is* the page's remaining job | Becomes a plain list screen once D1 / D2 / D4 are trimmed off it |
| D6 top bar | Calendar gets the compact top bar: カレンダー title, 日記一覧 icon, 設定 icon (`calendar_compact_top_bar`) | Its カレンダー icon becomes 戻る (the Calendar is where it came from) | — |
| D7 routes | Unchanged; the Calendar never builds an editor route itself | Unchanged | — |
| D8 refresh | `CalendarViewModel` gets the same on-resume refresh (today, `markDueDelivered`) | Unchanged | — |

Route compatibility: `diary` stays a valid route (the 日記一覧 page); `diary-editor/{epochDay}`,
`journal/{id}`, `journal-day/{epochDay}`, `future-reveal/{id}` unchanged; `calendar` becomes a
top-level destination (bottom bar shown, `navigateTopLevel` with saved state like メモ).

## 3. UI proposal (Calendar tab)

```
┌ カレンダー                          [日記一覧] [設定] ┐   compact top bar (calendar_compact_top_bar)
│ ▸ 未来からコメントが届いています … [受け取る]        │   D1, only when delivered > 0
│ ‹  2026年9月  ›                             今日   │   existing month header
│ 月 火 水 木 金 土 日                                │
│  … month grid with per-kind marks …                │   existing grid, future days disabled
│ [すべて] [作成] [更新]                              │   existing chips
│ 9月18日 (木)                        [日記を書く]    │   D3: timeline_create_journal (today / past day)
│ ├ 07:30 日記   作成      朝の記録                   │   existing rows
│ ├ 09:10 メモ   作成      MemoRipple AI案            │
│ └ 11:40 アウトライン 更新  Calendar再設計            │
│ 過去の今日 — 同じ日に書いた日記                     │   D4, only when non-empty
│ └ 1年前 2025年9月18日 …           [日記を読む]      │
└──────────────────────────────────────────────────┘
[ メモ ]            [ カレンダー ]                        bottom bar (nav_memos / nav_calendar)
```

Placement is Calendar v1's, unchanged; the additions are the banner slot on top, one button on the
selected-date line, one section at the bottom, and two top-bar actions. No screen is deleted.

## 4. Tests (RED first)

Unit: `ProjectBoundaryPolicyTest` — bottom destinations are exactly `nav_memos` / `nav_calendar`,
`Routes.CALENDAR` is a bottom destination, `Routes.DIARY` still exists (compatibility).

Device: `CalendarTabInstrumentationTest` (new) — the bar has two items and カレンダー opens as a
tab with the bar still there; memo / outline / journal rows open from the tab; 日記を書く creates a
journal for today twice (two rows, two ids); 過去の今日 shows on the tab and opens the entry; the
delivered banner shows on the tab and 受け取る opens the reveal; 日記一覧 opens the old page
(`diary_list`, no bottom bar) and 戻る returns to the tab; Back from the tab lands on メモ;
`recreate()` keeps the Calendar tab selected. Existing diary-tab tests are re-pathed:
`nav_diary` → `nav_calendar` (+ `open_journal_list` where the old page itself is the subject).
