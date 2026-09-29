# アウトライナーの閲覧モードでタスクをチェックしても、折りたたみ・ズーム・Undoが消えない（2026-09-25）

> 状態：実装済み。ブランチ`fix/outliner-reading-task`（base：Outliner PhotoRow Stage 2の最終checkpoint `d997e2a`）。merge前で停止。`docs/OUTLINE_STABLE_ROWS.md`で「まだ開いている問題 (3)」としていたもの。Diary / Memo / Calendar / 下のバー / 書き出し→読み込みの問題には触れない。Room 29 / Backup 23 / versionCode 4 / 1.1.0は変えない。

## 1. 監査

1. **閲覧モードのチェックは別の保存経路か** — はい。編集モードのチェック（行のチェックボックス）は`OutlinerSession.edit { OutlineEditing.toggleTask }`で、sessionの編集として扱われる。閲覧モードのチェック（`MemoReadingView`の`reading_task_N`）は、本文の文字列を丸ごと書き換え（`WorkOutlineEditing.toggleTaskAt(state.body, line)`）、`MemoEditorViewModel.updateBody`を呼んでいた。`updateBody`はアウトラインでは、その本文を行単位の差分（`OutlineRows.reconcile`）で行に載せ、`updateOutline`で版つきの保存に回す — **データは正しかった**（行のid・写真の行・本文・保存・版の確認）。
2. **sessionは作り直されているか** — いいえ。`OutlinerSession`は同じもの（`remember(viewModel)`）。
3. **idは保たれ、画面の状態だけが失われているか** — はい。reconcileした文書がそのまま`state.outline`になり、sessionもそれを受け取るので、行のidはすべて同じ。失われていたのはsessionの画面の状態だけ。
4. **どこでresetされていたか** — route（`OutlinerRoute`）の`LaunchedEffect(state.isLoading, state.body)`が、変わった本文で`session.acceptBody(body, stored)`を呼ぶ。`acceptBody`は「外で書き換えられた本文」を受け取るための入口で、本文が前と違えば`collapsed = emptySet()`、`zoomId = null`、`undoStack.clear()`、`redoStack.clear()`をする。さらに折りたたみに触れていた場合、離れるときに`persistFolds()`が**空の折りたたみを保存**するので、折りたたみは次に開いたときも戻らなかった。
5. **最小修正でできるか** — はい。閲覧モードのチェックを、編集モードのチェックと同じくsessionの編集として通せばよい。

## 2. 修正

- `OutlinerRoute`：閲覧モードへ`onToggleReadingTask(line)`を渡す。中身は
  `session.edit(viewModel::updateOutline) { current -> OutlineEditing.rewriteLine(current, OutlineEditing.entryIdAtBodyLine(current, line)) { raw -> WorkOutlineEditing.toggleTaskAt(raw, 0, symbols) } }`。
  **書かれる文字は以前と1文字も変わらない**（同じ`toggleTaskAt`を、その行だけに当てる）。sessionの編集なので、folds・zoom・Undo / Redoはそのまま、`updateOutline`で版つきの保存（古い画面による上書きの禁止、`docs/OUTLINE_STABLE_ROWS.md` §9）に回り、sessionは自分のechoとして受け取る（resetしない）。
- `OutlineEditing.entryIdAtBodyLine(document, line)`：閲覧ページの行（本文の行 = 写真の行を除いたentry）からidへ。
- `OutlinerSession`を閲覧モードの準備より前に作るよう、`remember`の1行を上へ移した。
- 閲覧ページ自身の見出しの折りたたみ（`collapsedLines`）は、もとから別の状態で、変わらない。
- 写真の行：`rewriteLine`は写真の行を書き換えない。並び・深さ・折りたたみでの隠れ方・ズームでの絞り込みは変わらない。

## 3. Undo

**チェックは、ほかの編集と同じくUndoできる**（第一候補、編集モードのチェックと同じ）。元に戻すでチェックが戻り、やり直すでまたチェックされ、その下のそれまでの履歴もそのまま残る。

**Redoについての判断（報告）**：「Redoが残っているときにチェック → Redoが消えない」について。チェックが編集である以上、ほかの編集（文字を打つ、編集モードのチェック）と同じく、**新しい編集はそれより前のやり直しを終わらせる**。前のやり直しを残すと、やり直しがチェックより前の写しに戻し、チェックを黙って消してしまう。そのため、テストでは「resetで履歴が消えない」こと、「チェックそのものが元に戻す / やり直すでき、その下の履歴も残る」ことを確かめている。

## 4. テスト

- 端末 `OutlinerReadingTaskInstrumentationTest`（6）：折りたたみが残る／ズームが残る（パンくずは帰る、外は隠れたまま）／Undoの履歴が残り、チェック自体も元に戻す・やり直すできる／チェック前のやり直しの後でも、チェックの元に戻す・やり直すが効く／行のid・種類・並び（写真の行を含む）が同じで、変わったのはチェックした行だけ、本文 = 行のprojection、保存され、activityの作り直しの後も残る／外の書き込み（分割表示）の後のチェックは上書きしない（外の書き込みはそのまま、衝突の表示）。**実装前：6件中4件がRED**（折りたたみ・ズーム・Undo・Redo）、2件（データ・古い画面の禁止）は前から通る。実装後6 / 6（3回）。
- JVM `OutlinerReadingTaskTest`（4）：本文の行 → id（写真の行は行ではない）／すべての行で、新しい経路の本文が以前の`toggleTaskAt`の本文とバイト単位で同じ、idと写真の行はそのまま／sessionを通すと折りたたみ・ズーム・Undo / Redoが残る（echoでもresetしない）／以前の経路（本文を丸ごと → `acceptBody`）がresetしていたことの記録。
- 近いクラス（Playback、Screen、Conflict、PhotoRows、StableRows、ZoomRestore、Toolbar、Home）66 / 66。

## 5. ゲートとS20

- **ゲート（`readtask`）**：clean build、unit **1593 / 0**（JVM 4件を含む）、lint **0 errors**、端末スイート（1プロセス）**OK 658 / 0 failures**（`OutlinerReadingTaskInstrumentationTest` 6件を含む）。Room 29 / Backup 23、versionCode 4 / 1.1.0。ゲートの後に変えたのは、使われなくなった`import`（`OutlinerPlayback.kt`の`WorkOutlineEditing`）1行の削除だけ（compileで確認）。
- **S20**（その場で上書き、先にDBを写した。clear / uninstallなし）：QAのアウトライン（メモ24、フォルダQ）で「QA three」を「after photo」の下へ字下げしタスクにし、「after photo」を折りたたむ → 閲覧モードでチェック → 行2は同じidで`[x]`、写真の行は同じ位置 → 編集モードへ戻ると**折りたたみはそのまま** → 取り消すでチェックが戻る（`[ ]`、折りたたみはそのまま）→ 離れてforce-stop、開き直すと折りたたみも行も保存どおり。integrity ok、メモ24以外のデータは導入前の写しと同一。
