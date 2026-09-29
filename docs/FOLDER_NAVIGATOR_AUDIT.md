# Folder 階層 Navigator — audit and the state / UI proposal (2026-09-18)

> **Status (2026-09-18 night):** proposal applied on the branch as described in §2 — see HANDOFF
> §16.28 for the commits, the gate and the S20 look. The audit below is as written before the change.

Branch `feature/folder-navigator`, base main `8559a26`. Human brief: audit first (FolderTree,
`FolderEntity.parentFolderId`, the picker's flatten, how `currentFolder` is held, the stacked
BackHandlers, navigation between folder list / memo list / editor, SavedStateHandle vs
rememberSaveable vs ViewModel, the move / rename / delete / promotion rules), then an
expandable, collapsible tree navigator; no folder schema change unless the audit proves one
necessary; Outliner nodes never in the folder tree; Editor untouched.

## 1. Audit

| Area | Today | Finding |
|---|---|---|
| **Schema** | `folders(id, name, parentFolderId, createdAt, updatedAt)`, index on `parentFolderId`, no FK; `memos.folderId`. | Enough. `depth` / `path` columns are **not needed**: the tree is at most a few hundred rows, read whole by `observeAll()`, and `FolderTree.flatten` gives depth in one pass. A stored depth would have to be kept in step on every move — a second source of truth for nothing. **No schema change.** |
| **FolderTree** (`domain/folders`) | `flatten(folders, order)` — children grouped once, explicit stack, depth per row, orphans / cycles skipped (O(n log n) for the sibling sorts). `children`, `ancestors`, `path`, `descendants`, `canMoveTo`, `canHold`, `isWellFormed`, `promotionTarget`. Unit: `FolderTreeTest`, `FolderTreeFlattenTest`, `FolderTreeScaleTest` (1,000 wide / 100 deep). | The navigator's rows are a filter over `flatten`'s output: a row is visible when every ancestor is expanded. One pass with a "collapsed at depth d" cursor keeps it O(n). No new walk; no O(n²). |
| **Picker flatten** | `orderedTree(folders)` in `FolderSection.kt` = `flatten` with a collator order, `FolderPickerSheet` indents by depth, excludes the moving folder's subtree. | Reused as is for the picker. The navigator uses the same `order` so both lists agree on sibling order. |
| **currentFolder** | `MutableStateFlow<Long?>` inside `MemoListViewModel` ("UI state: lives with this view model and nowhere else"). The view model is scoped to the `memos` back-stack entry; `navigateTopLevel` pops with `saveState = true`, so the entry — and the view model — survive a tab switch; `Activity.recreate()` keeps it too. | **Gap:** after process death the entry is restored (NavController saved state) but the view model is new → the wall comes back at the **root** while the breadcrumb, selection and search were where the user left them. Also `leaveFolder()` reads `uiState.value.folders`, which is empty before the first emission. Not a crash, but a reset the user did not ask for. |
| **Folder ⇄ wall ⇄ editor navigation** | One route (`memos`) for the wall; a folder is a way of looking, not a destination. New memo: `editor/0?folderId=` with the current folder. Opening a document pushes the editor / outliner; Back returns to the wall as it was. Breadcrumb `folder_crumb_*`, folder cards `folder_row_<id>`, hold sheet → rename / move / delete, ⋮ → 新しいフォルダ. | Keep one route. A route per folder would multiply back-stack entries and fight the selection / search state; the folder belongs to the wall page's own state. |
| **BackHandlers on the wall** (4) | `isSelectionMode` → clear selection (l.388); `arrangingNote != null` → stop arranging (l.679); `currentFolderId != null && query blank && !selection` → leave folder (l.706); `query not blank && !selection` → clear query (l.858). Compose gives the **last composed enabled** handler precedence. | The guards make three of them exclusive, but **arranging a note inside a folder** enables both the arranging and the folder handler; the folder one is composed later and wins, so Back leaves the folder instead of ending the arrangement. Plus the drawer's own Back (M3 `ModalNavigationDrawer` closes on Back) and, later, the navigator's. **One handler with an explicit order** fixes this. |
| **SavedStateHandle / rememberSaveable / ViewModel today** | No `SavedStateHandle` anywhere in the app. `rememberSaveable` holds the search text; everything else UI-local is `remember`. View models are created with plain factories. | `viewModel(factory = viewModelFactory { initializer { … createSavedStateHandle() } })` works on a `NavBackStackEntry` without other changes; the existing factory pattern stays for everything else. |
| **Move / rename / delete / promotion** | `FolderRepository`: every change one transaction checked against `FolderTree` (`canMoveTo` → `WouldCycle`; missing target; delete = documents + child folders promoted to the parent, `promotionTarget`). Restore refuses a malformed tree (`isWellFormed`). Device: `FolderInstrumentationTest` (10). | Unchanged. The navigator only reads the tree; the same repository calls are reached from its rows' hold sheet. `isWellFormed` and cycle refusal stay the guarantee. |

## 2. Proposal (short)

**Where.** The navigator is the wall's drawer (`memo_drawer`), which already holds the *places*
(すべてのメモ, 固定, アーカイブ, ゴミ箱, tags, 設定) — the future `Navigator ├─ Folders ├─ Calendar
├─ Search ├─ Recent` entry point. A **フォルダ** section is added under the places as a tree;
the drawer body becomes a `LazyColumn` so hundreds of folders scroll.

```
memo_drawer
  すべてのメモ / 固定したメモ / アーカイブ / ゴミ箱
  フォルダ                       [新しいフォルダ]
  ├ すべて (root)                  ← navigator_root
  ├ ▸ 開発                          navigator_folder_<id> / navigator_toggle_<id>
  │   ├ ▾ MemoRipple   (selected)
  │   │   ├ AI設計       (leaf: no toggle)
  │   │   └ Calendar設計
  │   └ CharacterLauncher
  ├ 創作
  └ 個人
  タグ … / タグを管理 / 設定
```

**Rows.** `FolderNavigator.rows(folders, order, expanded, selected): List<FolderRow>` in
`domain/folders`, where `FolderRow(folderId, name, depth, hasChildren, expanded, selected)` is a
projection (never an entity). Built from `FolderTree.flatten` in one pass. Tap a row → the wall
shows that folder (`goToFolder`) and the drawer closes; tap the chevron → expand / collapse; hold
→ the existing rename / move / delete sheet. Indent = depth × 16 dp, capped so a deep chain
still leaves room for the name; the row's semantics carry `selected`.

**State.** `expanded: Set<Long>` and `currentFolderId` live in `MemoListViewModel` backed by a
`SavedStateHandle` (`LongArray` / `Long?` keys), so they survive rotation, a tab switch and
process death alike — and `revealing(selected)` adds the current folder's ancestors to
`expanded` whenever the current folder changes, so the selected row is always visible.
No DataStore: the tree's shape is a session's way of looking, not a preference; nothing in
the brief needs it across app installs or backups.

**Back.** One `BackHandler` on the wall, in this order: navigator (drawer) open → close it;
selection → clear; arranging a note → stop; search text → clear; inside a folder → parent; else
not handled (the app's own navigation). The drawer's built-in Back stays (it is first anyway).

**Not touched.** Schema, `FolderRepository`, the editor, the outliner, the picker, the breadcrumb
and folder cards on the wall (they stay as the in-place view; the navigator is the overview).

## 3. RED tests

Unit — `FolderNavigatorRowsTest`: roots only when nothing is expanded; a child appears when its
parent is expanded, a grandchild when both are; collapsing a parent hides every descendant;
depth per row; `hasChildren`; `selected`; `revealing` returns the ancestors of the selected
folder; orphans and cycles stay out; 5,000 folders in a bounded time.

Device — `FolderNavigatorInstrumentationTest`: roots in the drawer; expand child and grandchild;
collapse; depth by indentation; the selected row; a tap switches the wall to that folder (and
the breadcrumb says so); sibling switch; an empty folder; a five-deep chain; `recreate()` keeps
expansion and selection; Back order (drawer, then folder, then root); rename / move / delete +
promotion keep the tree right; 300 folders scroll. Plus a view-model test with a prepared
`SavedStateHandle` (the process-death case) restoring the current folder and the expansion.
