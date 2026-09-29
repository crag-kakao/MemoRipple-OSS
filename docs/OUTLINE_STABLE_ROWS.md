# アウトラインの安定した行 — Stage 1（Room 28 / Backup 22、2026-09-25）

> 人間の判断（2026-09-25）：アウトライナーは「各行をDB上の安定した行として持つ」方式で進める。Room 27→28、Backup 21→22を許可。まず**Stage 1＝stable row ID基盤だけ**を実装し、見た目・操作・本文内容は変えない。写真（PhotoRow＝写真を独立したoutline行として持つ方式）はStage 2で、人間の確認の後。行への添付方式、本文への内部マーカー（`![photo](…)`）は採用しない。ズームの起点の行が消えたときのクラッシュは、Stage 1の前提修正として先に直す。残り3件の以前からの問題は別件（§6）。
> 前提の監査：`docs/OUTLINE_STABLE_IDS_AUDIT.md`。Memo / 日記のblockは`docs/MEMO_CONTENT_BLOCKS.md`。

## 1. ズームの起点が消えたときのクラッシュ（前提修正）

**原因**：ズーム中は、ズームの起点の行も一覧に出て編集できる。その行の先頭でBackspaceを押すと、`OutlineEditing.deleteBackward`が行を上の行（ズームの外）に結合し、起点の行が文書から消える。`OutlinerSession.zoomId`は消えた行を指したまま残り、次の描画で`OutlineEditing.visible → zoomRange → position()`の`require`が失敗して落ちる。起点の行を作った操作をUndoしても同じことが起きる。

**修正**（`OutlinerSession.keepViewOnLines`、`edit`とUndo / Redoの後に毎回）：
- ズームの行がもう無ければ、**変更前の文書で、その行が入れ子になっていた行のうち、今も残っている一番近い行**にズームを移す（「一つ上へ」と同じ行）。そういう行が無ければルート（ズームなし）に戻る。関係のない行には移らない。
- 折りたたみの集合からも、もう無い行を外す（消えた行を指す状態を残さない）。

**テスト**：`OutlinerZoomAnchorTest`（JVM、5件：親への結合 → 親に、最上位の行 → ルート、Undoで消えた行 → 親、残っているズームは動かない、消えた行の折りたたみは消える）、`OutlinerStableRowsInstrumentationTest.joiningTheZoomAnchorAwayDoesNotCrashAndStandsOnTheParent`（端末：ズーム → 起点の行の先頭でBackspace → 落ちずに親のズームへ）。

## 2. データの形（正本は行）

- 表`outline_rows(memoId → memos CASCADE, rowId, position, text)`、主キー`(memoId, rowId)`、索引`memoId`。
- **正本は行、`memos.body`はそのprojection**（`text`を位置の順に`\n`でつなぐ ＝ 今までの`OutlineText.serialize`そのもの）。行と本文を別々に編集できる二重の正本にはしない。
- 行の`text`は、その行の書かれたままの文字（字下げ・記号・タスクの`[ ]`/`[x]`・文字、改行を除く）。構造（深さ・種類）は今まで通り`OutlineText.parseLine`がその文字から読む。行の種類を別に保存しないので、読み方は今までと1文字も変わらない。
- **書くのは`OutlineStore`だけ**。すべての書き込みは、行と本文を同じtransactionで書く。
- `rowId`：そのアウトラインの中で一意、正の数。**保存・再起動・process death・並べ替え・字下げでも変わらない**。新しい行は、画面が持っている「今までに配った最大のid＋1」から配り、**Undoで行が消えても番号は戻さない**（同じ画面の中で同じidが別の行を指すことはない）。別の画面（開き直し）では「保存されている最大のid＋1」から数える。
- 読むときの保険（`OutlineStore.materialize`）：行が無いアウトライン（変換前の形や、テストで直接入れた行）は本文から行を作る（id 1..n）。行のprojectionが本文と違えば（このstoreを通らずに本文が書かれた場合）、本文を行単位の差分で行に載せる — 読み手が見てきたのは本文なので、本文が勝つ。

## 3. 変換（Room 27 → 28）

- 各アウトラインの本文を`\n`だけで切り（アウトライナーの読み方と同じ）、行の順にid 1..n、position 0..n-1で入れる。本文・行の順・字下げ・タスクの状態・空行・末尾の改行・CR・絵文字をすべてそのまま保つので、**projectionは本文とbyte単位で一致**する（`OutlineRowsMigrationTest`で固定）。
- 既存の行（memos）は変えない。Memo（kind `memo`）と日記には触らない。
- 折りたたみ・ズーム・スクロール位置の保存（DataStoreの内容キー、`OutlineFoldKeys`）は**そのまま**。Stage 1では保存の仕方を変えないので、変換の前に保存した状態もそのまま効く。

## 4. 書き込みの入口

| 書き手 | 行への載せ方 |
|---|---|
| アウトライナーの編集（入力・Enter・Backspace・字下げ・移動・ドラッグ・タスク・テンプレート挿入・Undo / Redo） | 画面の文書が持つidのまま`OutlineStore.saveDocument`（600msの自動保存、ON_STOP、離れるとき — 今まで通り） |
| 閲覧モードのタスクのチェック（ViewModelの`updateBody`） | 行単位の差分（`OutlineRows.reconcile`）で文書に載せてから保存。チェックした行は同じid |
| AIの作成・追記（`DocumentAccess`）、分割表示の本文ペイン、その他`MemoRepository.save` | `OutlineStore.saveBody` → 行単位の差分。変わらない行・1行の中だけ変わった行はidを保ち、足された行は新しいid |
| 新しいアウトライン（`createOutline`）、アウトラインの複製・AIの作成（`save(null, kind = OUTLINE)`） | 本文から行を作る（空のアウトラインは空の行1つ） |
| 復元 | Backup 22は行をそのまま（idも）、1〜21は本文から行を作る（§5） |

**行単位の差分**（`OutlineRows.reconcile`、決定的）：先頭と末尾の同じ行を除き、残りを最長共通部分列で対応させる。対応した行はidを保つ。対応した行と行の間で、消えた行と足された行を順に組にし、組になった行は古いidを保つ（1行の中だけが変わった行は同じ行）。余った足された行は新しいid、余った消えた行は消える。

## 5. Backup 22

- `outlineRows`（`memoId`, `rowId`, `position`, `text`）を運ぶ。書き出しは、行がまだ保存されていないアウトラインも読んだ形で書くので、22のファイルは常に完全。
- 22のファイル：検証（`OutlineRowRules`：kind `outline`のメモに属する、idは正で重複なし、position 0..n-1、`text`に改行なし）、**行のidもそのまま復元**し、本文は行から作り直す。
- 1〜21のファイル：本文から行を作る（id 1..n、変換と同じ）。本文は変えない。

## 6. 外の機能と、以前からの問題

- **検索・AI（DocumentAccessの読み・検索・追記）・読み上げ・書き出し・プレビュー・カレンダー・リンク**は今まで通り`memos.body`を読む。bodyはprojectionで、Stage 1では写真の行が無いので、**出力は変換の前と同じ**（`OutlineStoreInstrumentationTest.searchAiSpeechAndExportReadExactlyWhatTheyReadBefore`で、変換前の形と変換後で読み手の出力が一致することを固定）。
- 以前からの問題3件は**別件のまま、未解決**（Stage 1で挙動は変わらない）：
  1. ポータブル書き出しのアウトラインを読み込むとメモになる（kindを運ばない）。
  2. ~~アウトライナーを開いている間に、分割表示やAIの追記などで外から書かれた本文は、アウトライナーの次の保存で上書きされる~~ → **§9で修正済み**（2026-09-25、Stage 2の前に）。
  3. 閲覧モードでタスクを1つチェックすると、折りたたみ・ズーム・Undoが消える（行のidは今は保たれるが、画面は今まで通り外からの書き込みとして読み直す）。
     → **解決（2026-09-25、`fix/outliner-reading-task`、`docs/OUTLINER_READING_TASK.md`）**：閲覧モードのチェックはsessionの編集になり、折りたたみ・ズーム・Undoは残る。
- 日記のAI追記と復元のロック順も別件のまま。

## 7. テスト

- JVM：`OutlineRowsTest`（7：projectionがbyte単位で本文、idの一意性、次のid、末尾の追記、タスクのチェック、途中の書き換え、決定的・重複なし）、`OutlinerStableIdsTest`（9：保存されたidを使う、並べ替え・字下げ・字下げ解除でidが変わらない、編集は他の行のidを変えない、分割は次のidを1つ、結合は上の行が残る、削除はその行だけ、タスクと折りたたみ、Undo / Redoで同じid、Undoで消えたidは再利用しない、保存する本文がbyte単位で一致）、`OutlinerZoomAnchorTest`（5）、`BackupOutlineRowsTest`（4：22の往復でidも戻る、行の無いアウトラインは行ごとに書き出す、1〜21は本文から、不正な行は拒否）。版数を固定していた既存のテストは28 / 22へ。
- 端末：`OutlineRowsMigrationTest`（27→28を実ファイルで：本文がbyte単位で一致、id 1..n、memoに行なし、重複なし）、`OutlineStoreInstrumentationTest`（4：DBを閉じて開き直してもidが残る、本文まるごとの書き手が変わらない行のidを保つ、新規・複製・削除（孤立した行なし）、読み手の出力が変換前と同じ・AIの追記は1行）、`OutlinerStableRowsInstrumentationTest`（5：A 変換前の形が同じに開く、B ズームの起点の削除で落ちない、C 並べ替え → 作り直し → 同じ行・順・id、D 字下げ → 作り直し → 同じ構造、E タスク・折りたたみ・ズーム → 作り直し → 保たれる）。

## 9. 古い画面による上書きの禁止（2026-09-25、Stage 2の前の安全性の修正）

**問題**：アウトライナーを開いたまま、AIの追記（DocumentAccess）や分割表示の本文ペインなどから本文が書かれても、アウトライナーは気づかず、次の保存（入力の後の自動保存、離れるときの保存）で自分の持っている古い内容を書き、外からの書き込みを消していた。PhotoRowが入ると、写真の行を含む並び全体を古い画面が上書きしうるため、行の基盤だけの今のうちに競合の規則を決める。

**監査**：`DocumentAccess.append`はすでに`expectedUpdatedAt`（HANDOFFの`DocumentVersion`の考え方：今は`updatedAt`が版、後で専用の版の列に置き換えられる）をtransactionの中で比べ、違えばConflictで何も書かない。`memos.updatedAt`を動かすのは`MemoDao.updateContent`（題と本文）だけで、ピン・フォルダ・ゴミ箱・並び順は動かさない。→ **アウトラインの版は`updatedAt`のまま、同じ考え方を再利用**する（Room / Backupの変更なし）。

**規則**
- アウトラインへのすべての書き込みは`OutlineStore`を通り、**書くたびに版（`updatedAt`）を必ず前へ進める**（`max(今, 前の版+1)` — 同じミリ秒の2回の書き込みや、時計が戻った場合でも別の版になる）。AIの追記・分割表示・`MemoRepository.save`・アウトライナー自身、どの書き手も同じ。
- アウトライナーの保存（`OutlineStore.saveDocument`）は、画面が最後に読んだ／書いた版を名指しする。版が進んでいれば**Conflict、行も本文も何も書かない**（部分的な書き込みなし）。
- **最後に書いた方を勝たせない。自動mergeもしない**（画面側・AI側・行単位のどれも優先しない）。
- 競合すると：画面の上に「このアウトラインは別の場所で更新されました。ここでの変更は保存されていません。」と［再読み込み］。その間は行が読み取り専用になり、編集・Undo・Redoは何もしない（`OutlinerSession.writable = false`）。自動保存・離れるときの保存も書かない。［再読み込み］で最新の内容と版を読み直し、そこから普通に書ける。
- 折りたたみ・ズームは見方で、書き込みの権限にならない（書かない）。
- アプリが途中で終了したとき：保存待ちだった画面の内容は失われ、次に開いた画面は最新を読む（古い内容が後から書かれることはない）。
- AI側から画面の状態を直接触ることはない（AIは今まで通りDocumentAccessで書き、版が進むだけ）。

**テスト**：`OutlinerConflictSessionTest`（JVM：競合中は編集・Undo・Redoが何もしない、折りたたみ・ズームは書かない）、`OutlineConflictInstrumentationTest`（版N→外からの追記で版が進む→古い画面の保存は拒否→外からの文字・行id・順・projectionがそのまま・部分的な書き込みなし、外からの書き込みがなければ普通に保存、同じミリ秒の2回も別の版）、`OutlinerConflictUiInstrumentationTest`（AIの追記＋古い画面→上書きなし→再読み込みで最新→続けて保存できる、分割表示と同じ書き込み＋離れるときの保存→書かない、画面が保存前に終了→古い内容は書かれず、新しい画面は最新を読む）。

## 8. Stage 2（未着手、人間の確認の後）

写真を独立した行（PhotoRow）として持つ：`TextRow / PhotoRow / TextRow / PhotoRow / TextRow`。PhotoRowは自身のstable idを持ち、projection（本文）には出さない。
