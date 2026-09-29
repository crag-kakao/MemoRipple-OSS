# Memo本文のブロック化（Content Blocks）

> 2026-09-24。人間の決定（同日）：Room 25→26 許可、位置は**行番号ではなくブロックの順序**で持つ、Backupは**20へ上げる**、既存メモは**写真を本文の上**に置く、Editorは**Text / Photo blockを正式な編集単位**にする、`memos.body`は消さず**projection**として残す、Memoのみ（Outliner / Diaryへは展開しない）。
> 最初に出した「行番号anchor案」は、本文の上部で改行を増減すると意味がずれるため**不採用**。
>
> **状態：実装済み・マージ前**（`feature/calendar-month-and-diary-calm`、未コミット）。§8に実装の記録。

## 1. Source of truth

- **Memo（kind = `memo`）の本文の正本は `memo_content_blocks`**（順序付きのText / Photo blockの列）。
- **`memos.body` はprojection**：Text blockのうち空でないものを順に`\n`で結合した文字列。blockを書く同じtransactionの中で、repositoryだけが計算して書く。**画面・AI・importなどがbodyを独立に編集して正本になることはない。**
- bodyを丸ごと渡してくる既存の書き手（AIの追記、分割表示の参照ペイン、複製、エピソード作成、Markdown/portable import）は、`MemoRepository.save`の中で**reconcile**される：旧projectionと新bodyの共通の頭と尻尾から「変わった範囲」を求め、それを含むText blockだけを書き換える（§6）。
- Outline（kind = `outline`）はblockを持たない。これまでどおり`body`が正本。kindは作成時に決まり、あとから変わる画面は無い（監査済み）。

## 2. Schema（Room 26）

```
memo_content_blocks(
  id INTEGER PK AUTOINCREMENT,
  memoId INTEGER NOT NULL  → memos(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,              -- 0..n-1（"order"はSQLの予約語）
  type TEXT NOT NULL,                     -- 'text' | 'photo'
  text TEXT,                              -- textのときだけ（空文字可）
  photoAttachmentId INTEGER  → memo_photo_attachments(id) ON DELETE CASCADE
)
index(memoId), unique index(photoAttachmentId)
```

- `(memoId, position)` はuniqueにしない（並べ替えの途中で一時的に重なるため）。連続性は書き込み側で正規化し、Backupの検証で確かめる。
- 写真1枚＝photo block 1つ（`photoAttachmentId` unique）。写真の行が消えればblockもcascadeで消え、repositoryが同じtransactionで残りを正規化する。
- 写真の`sortOrder`は**photo blockの順序のprojection**として保つ（Backup検証の0..n-1、並べ替えsheet、viewer、書き出しの「## 写真」はそのまま動く）。

**Migration 25→26**（CREATE TABLE＋INSERT、既存行は変えない）：
kind = `memo` の各メモに、写真を`sortOrder`順にphoto block（position = sortOrder）、最後に`text = body`のtext block 1つ。→ `body`のprojectionは元の`body`と1文字も変わらない。写真の位置は今の見た目（本文の上）と同じ。

## 3. Backup 20

- payloadに`memoContentBlocks: [{id, memoId, position, type, text?, photoAttachmentId?}]`。
- **20の読み込み**：検証——blockのmemoが存在しkind = memo、typeとtext / photoの組み合わせ、photo blockの写真行が同じメモに属する、メモの写真行はちょうど1回ずつphoto blockに出る、positionが0..n-1、text blockが1つ以上。bodyはblockから計算し直す（正本はblock）。
- **1〜19の読み込み**：Migrationと同じ規則でblockを導く（写真が上、本文が下）。restore互換は維持。
- 19以前のreaderは20を拒否する（通常の前方非互換。20にした理由：レイアウトはノート内容の一部で、黙って捨てさせない）。

## 4. 不変条件（書くときに保つ、読むときは許容する）

- N1 blockは空にならない。**最後のblockはtext**（写真の後ろに書く場所がある）。
- N2 写真が0枚になったら、text blockを1つに結合する（projectionは不変）。
- N3 positionは0..n-1。写真の`sortOrder`はphoto blockの順。
- 読み込み時の保険：blockを持たないmemo → 写真が上・本文が下として導く。どのblockにも出てこない写真 → 先頭の写真の並びに加える。次に書くときに正式化する。

## 5. 編集（Editor）

- 写真が1枚も無いメモは、**今と同じ1つの本文欄**（中身は唯一のtext block）。レイアウトも挙動も変えない。
- 写真があるメモは、タイトル（と、keyboardのないときタグ・リンク）の下で、**blockを上から順に並べた1本の縦スクロール列**：text blockは本文と同じ見た目の欄（文字の高さだけ）、photo blockは本文幅の画像。タイトルは今と同じく上に残り、本文の領域の中で列がスクロールする。
- **今編集しているtext block**が、これまでの`bodyField`の役を引き継ぐ。ツールバー（太字・ハイライト・チェックリスト）、テンプレート／メモリンク／コメントリンクの挿入、括弧の自動補完、markupの保護、タスクのtapはこれまでどおりこの1つの欄に効く。
- **Enter**：そのtext blockの中の改行（blockは分けない）。
- **写真を追加**：まず本文を保存。写真は**caretの位置**に入る（複数なら選んだ順）。caretが文章の途中なら、そのtext blockをcaretで2つに分け、前の部分・写真・後ろの部分の順にし、後ろの部分の先頭へcaretを移す（切れ目の改行1つがblockの境目になるので、行の端で分けたときprojectionは変わらない。行の途中なら、そこで行が分かれる）。caretが末尾（または本文欄を触っていないとき）は、そのtext blockの**直後**に写真、その後ろに新しい空のtext blockを置いてそこへ。caretが先頭、または空のtext blockなら写真はその**前**に入り、そのblockが書く場所のまま。先頭へは集めない。（2026-09-24 S26：caretを文字の間に置いても写真が文章の最後に入っていた → 修正）
- **Backspace（text blockの先頭）**：前がtextなら2つを1つに結合する（境界の1文字を消すのと同じ）。前が写真なら何もしない（キーボードで写真は消さない）。
- **写真の操作（編集中）**：写真そのものはtapにも長押しにも反応しない。写真の右上の**丸い⋮**から「フルスクリーン」（既存のviewer）、「写真一覧」（このメモの写真を以前のサムネイルの横並び＋「並べ替え」で見せるsheet）、「削除」（確認のあと、その写真だけを消す。前後のtext blockは残す。写真が0枚になったらN2）。「上へ」「下へ」は廃止（2026-09-24 S26の指摘）。閲覧モードでは写真のtapで従来どおりviewer。
- **Undo / Redo**：履歴の1件＝（text blockのid, 直前の値）。戻すとそのblockに戻ってcaretも戻る。**写真の追加・並べ替え・削除・blockの結合は構造の変更なので、その時点で履歴を区切る**（写真の操作はもともとUndoの対象外）。文章のUndo/Redoはこれまでどおり動く。
- **Markdown装飾・タスク・コメント印`[Rn]`**：どれも1行の中で完結する（blockの境界は必ず行の境界）ので、blockに分けても壊れない。コメントは`linkNo`で本文の`[Rn]`を指すだけで、位置を持たない。
- **閲覧モード**：projectionを行ごとに表示し、写真をblockの位置（projectionの行の境目）に差し込む。見出しの折りたたみ、読み上げの追従、コメント印へのジャンプ、行のダブルタップでの編集開始は、どれもprojectionの行番号のまま動く。編集と閲覧で写真の位置は同じ。
- **キーボード**：写真は縮めない。ページが編集中の欄のcaretを追う。
- 通常のEditorには「写真 N枚」などの管理表示を出さない。並べ替えは ⋮ →「写真を並べ替え」（写真どうしの入れ替え：写真が入る枠の位置はそのままで、中身の写真が入れ替わる）。

## 6. 周辺機能

| 機能 | 扱い |
|---|---|
| Search（壁のSQL `LIKE`、AIの`DocumentSearch`） | projection（`body`）を使う。変更なし |
| AI DocumentAccess | 読みはprojection。追記は`save`のreconcileで**最後のtext block**の末尾へ。新規作成は1つのtext block |
| TTS / 再生 | projectionの行。変更なし |
| 一覧のpreview・文字数・リンクgraph・ノート閲覧 | projection。変更なし |
| Plain text（コピー・共有・PDF） | projection |
| Markdown export | projection＋末尾の「## 写真」（今回は変えない。本文中への配置は後日の候補） |
| Archive / Trash / 復元 | memos行のフラグだけ。blockはそのまま。完全削除はcascade |
| Process death | 本文は今と同じ600msのautosave。構造の変更は即座に1つのtransaction。起動し直すとDBから組み立て、編集中のblockは最後のtext block |
| 分割表示の参照ペインでの編集 | `save`のreconcileを通る |
| 複製 | 本文だけを複製（写真は今も複製しない）→ text block 1つ |

**reconcileの規則**（純関数・unit test）：変わった範囲が1つのtext blockの中（または末尾）なら、そのblockだけを書き換える。projectionの末尾への追加は最後のtext blockへ。複数のtext blockにまたがる変更は、関係するtext blockを先頭のものに結合し、間にあった写真はその直後へ順番を保って置く。写真とtextはどちらも失われない。

## 7. 実装の順序

1. Room 26＋Backup 20＋migration（1〜19のrestoreを含む）
2. Blockのdomain（純関数）とrepository
3. 既存Memoのmigrationの確認
4. 閲覧モードのrenderer
5. Block editor
6. Search / AI / TTS / exportの互換確認
7. Full regression、S20（既存メモのmigrationと、新規の文章→写真→文章→写真）

## 8. 実装の記録（2026-09-24）

**ファイル**
- `domain/memos/MemoContent.kt` — 純関数：projection、legacy、resolve（読むときの保険）、normalize、insertPhotos（caretの位置で分ける）、mergeWithPrevious、reassignPhotos、photoBreaks、lineToBlock、reconcile。
- `data/MemoContentBlockEntity.kt`（entity＋DAO）、`data/AppDatabase.kt`（v26、`MIGRATION_25_26`）。
- `data/MemoContentStore.kt` — blockを書く唯一の場所。書くたびにbodyをprojectionにし、写真の`sortOrder`をblock順にそろえる。
- `data/MemoRepository.kt` — 新規メモは1つのtext block。既存メモへのbody丸ごとの書き込みは`saveBody`（reconcile）。editorは`saveBlocks`。
- `data/AttachmentRepository.kt` — 写真の追加（`writingTextId`の`caret`の位置、caretがなければその直後、`writingTextId`もなければ上の写真の並びへ）、削除後の正規化、並べ替え後のslot入れ替え。
- `backup/BackupDtos.kt`（`BACKUP_FORMAT_VERSION = 20`、`MemoContentBlockBackupDto`）、`BackupMapper.kt`（`MemoContentBackup`）、`BackupValidator.kt`（`MemoContentBlockRules`、`INVALID_CONTENT_BLOCKS`）、`data/BackupDao.kt`。
- `ui/memos/MemoEditorViewModel.kt` — `blocks` / `activeTextId` / `structureVersion` / `pendingCaret`。`updateBody`は書いているtext blockへ。構造の変更の前に必ず本文を保存する（`flushSave`）。outlinerには渡さない（`contentStore = null`）。
- `ui/memos/MemoBlockColumn.kt` — 書くときの列。写真の右上の丸い⋮（フルスクリーン / 写真一覧 / 削除、削除は確認）。写真一覧は`PhotoAttachmentStrip`をbottom sheetで。
- `ui/memos/MemoEditorScreen.kt` — 本文欄を書いているtext blockに結びつけ、Undo履歴をblock付きに、閲覧モードに写真を渡し、全文を使う箇所（コピー・文字数・折りたたみ・コメント印の検索と数）はprojectionを読む。
- `ui/memos/MemoReadingView.kt` — `photoBreaks` / `photoContent`：写真をリストの項目として行の間に置き、行へのスクロールと読み上げ追従は項目の位置で引き直す。
- `ui/attachments/InlinePhoto.kt` — 1枚の写真（比率の規則、Fit。tapは閲覧モードだけ）。前の段階の「写真一覧（gallery）」は削除。

**検証（テスト）**
- JVM：`MemoContentTest`（15）、`BackupContentBlocksTest`（4）、`MemoInlinePhotoPolicyTest`（5）、既存のRoom 25 / Backup 19を指していた版数の確認を26 / 20へ。
- 端末：`MemoContentMigrationTest`（25→26を実ファイルで）、`MemoContentBlocksInstrumentationTest`（8：文章→写真→文章→写真、削除、並べ替え、AIの追記、import、上下、outline、検索）、`MemoBlockEditorInstrumentationTest`（9：書く順番、閲覧の順番、別のtextへの移動、メニューからの移動と削除、Undo、管理表示なし、再生成、写真なしのメモ）、既存の移行・Backup復元・写真repositoryのsuiteが26 / 20で通ること。

**S20・gateで見つけて直したこと**
- 新規メモの最初の保存で、書いている途中のtextにidが付いたときにcaretが末尾へ飛んでいた（別のtextへの移動と取り違えていた）→ text同士の移動のときだけ入れ替える。
- 移行したメモを閲覧で開くと、写真より下から表示されていた（blockが遅れて届き、一覧が本文の行を画面に残したまま上に写真を足した）→ メモとblockを同じ状態で一度に渡す。
- 編集に入ると列が勝手にスクロールしていた → caretを追うのはfocusのあるtextだけ。

**既知の制限**
- ソフトキーボードがtext blockの先頭でBackspaceのキーイベントを送らない場合、blockの結合は起きない（何も消えないだけ）。ハードウェアキーでは動く。
- 写真選択の画面から戻った直後、長い本文のtext blockは次の入力までスクロール位置が先頭に戻ることがある（キーボードが一度閉じるため。以前からの挙動）。
- 書き出し（Markdown / PDF）の写真は、今まで通り本文の後ろの「## 写真」。本文中の位置で出すのは後日の候補。

## 9. S26レビューの修正（2026-09-24夜）

人間の指摘（S26のスクリーンショット3枚と、参考として別アプリの写真の⋮メニュー）：

1. **caretを文字の間に置いて写真を入れても、文章の最後に入る** → 写真はcaretの位置に入る（§5）。写真ボタンを押した時点で本文欄にfocusがあれば、そのcaret（選択範囲なら先頭）を`MemoEditorViewModel.rememberPhotoCaret`に預け、写真選択から戻った`addPhotos`がそれを一度だけ使う（`AttachmentRepository.importMemoPhotos(…, caret)` → `MemoContentStore.placePhoto` → `MemoContent.insertPhotos`）。2枚目以降は分けた後ろの文章の前（1枚目の直後）に入る。本文欄を触っていなければ従来どおり末尾の後ろ。
2. **写真の右上に丸い⋮、上へ・下へは消す** → `MemoBlockColumn`の写真に`memo_photo_menu_<id>`（32dpの半透明の円、48dpのタッチ領域）。
3. **⋮の中身は「フルスクリーン」と「削除」、それに以前のサムネイル表示** → 以前の表示の名前は「**写真一覧**」とした（このメモの写真をサムネイルの横並びで見渡し、「並べ替え」もそこからできる）。中身は以前と同じ`PhotoAttachmentStrip`（`写真 n/20枚`・並べ替え・tapでviewer）をbottom sheetで出す。管理のための表示なので枚数を出してよい（通常のEditorには出さない、は変わらない）。
4. **ワンタップでフルスクリーン、長押しで編集、は編集中はしない** → `InlinePhoto`の`onClick`はnull可、`onLongClick`は削除。閲覧モードの写真のtapでviewerは残した（閲覧モードには⋮が無いため）。

`MemoContent.move` / `canMove`、`MemoContentStore.movePhoto`、`MemoEditorViewModel.movePhoto`は使い道がなくなったので削除。並べ替えは ⋮ →「写真を並べ替え」と、写真一覧の「並べ替え」（どちらも枠の位置はそのままで中身を入れ替える）。Room 26 / Backup 20は変更なし。

テスト：`MemoContentTest`（caretで分ける・行の端・両端・2枚目）、`MemoInlinePhotoPolicyTest`（⋮の3項目、上へ・下へ・長押しなし、編集中の写真はtapなし）、`MemoContentBlocksInstrumentationTest.photosPickedWithTheCaretBetweenTheWordsGoBetweenThem`、`MemoBlockEditorInstrumentationTest.aPhotosRoundMenuOffersFullScreenTheOverviewAndDeleteAndThePictureTakesNoTouch`（写真のtap・長押しで何も開かない、⋮の3項目、フルスクリーン、写真一覧の並べ替え）。

## 10. S26レビューの続き（2026-09-24 23時）

- 写真一覧（サムネイルの横並び）の「並べ替え」を行の**右端**へ（`PhotoAttachmentStrip`：`Spacer(weight)`の後ろ）。日記など、この列を使うすべての画面で同じ。
- 「写真を並べ替え」sheetの移動を、設定 → ショートカットバーと**同じ動き**にした：ハンドルを引くと行が持ち上がって指に付き、他の行が滑って場所を空ける（`CardDragController`の交差モード、`animateItem`、`carriedCard`、端での自動スクロール）。以前の「半行分動いたら1つ入れ替え」は削除。保存は今まで通り「完了」で1回。読み上げ操作の「前へ移動」「後ろへ移動」は残す。説明文は「ハンドルを引いて移動できます。…」。
- `memo_content_blocks`を`DRIVE_DIRTY_TABLES`に追加（blockだけが変わる書き込みもDriveバックアップの対象として検知する。監査で見つけた漏れ）。
- 日記とアウトライナーへの展開は設計監査のみ：`docs/PHOTO_PLACEMENT_DIARY_OUTLINE.md`（人間の判断待ち）。

## 11. 日記もContent Block（Room 27 / Backup 21、人間の判断 2026-09-25）

**判断**：日記はMemoと同じordered content block方式にする。Room 26→27、Backup 20→21。既存の日記は今の見た目を保つため**本文 → 写真**の順で移行する。アウトライナーの`![写真](photo:…)`行方式は採用しない（本文に内部マーカーを埋めると、検索・AI・読み上げ・書き出し・複製・プレビューなど読む箇所すべてに例外が要り、漏れたときの影響が大きい）。日記のAI追記と復元のロック順の問題は**別件**（写真のblock化とは混ぜず、この後に独立したconcurrency fixとして扱う）。

**保存**
- 表`diary_content_blocks(id, diaryEntryId → diary_entries CASCADE, position, type 'text'|'photo', text?, photoAttachmentId? → diary_photo_attachments CASCADE, unique)`。正本はblock、`diary_entries.body`はprojection。
- 書くのは`DiaryContentStore`だけ（`MemoContentStore`と同じ規則）。blockと同じtransactionでbodyを書き、**bodyが変わったときは`updatedAt`も動かす**（AIの追記は`updatedAt`を版として確認するため、写真で行が分かれた場合も含めて言葉の変化はすべて版に出る）。写真の`sortOrder`はblock順にそろえる。
- **LOCKEDの日には何も書かない**：`DiaryContentStore`の書き込みはすべてLOCKEDを断る（読むだけ）。`AttachmentRepository.importDiaryPhotos`もLOCKEDの日には1枚も入れない（以前は画面だけが守っていた）。
- 純関数は`MemoContent`を共用。`legacy` / `resolve`に`photosFirst`を足した（Memoは写真が上＝既定、日記は`photosFirst = false`：本文の後ろに写真、その後に書く場所の空のtext。blockの無い写真は最後の写真の後ろへ）。
- `MIGRATION_26_27`：表と索引を作り、各日記に「本文を1つのtext（position 0）→ 写真をsortOrder順に → 写真があれば空のtext」を入れる。既存の行は変えない。DRAFT / FINALIZED / CORRECTING / LOCKEDのどれも同じ。
- `DRIVE_DIRTY_TABLES`に`diary_content_blocks`を追加。

**Backup 21**：`diaryContentBlocks`。21のファイルは検証（`DiaryContentBlockRules`：日記に属する、形、0..n-1、textが1つ以上、写真行を1回ずつ）、本文はblockから作り直す。1〜20のファイルは移行と同じ「本文 → 写真」で作る。書き出しは、blockがまだ保存されていない日記も読んだ形で書くので、21のファイルは常に完全。lifecycle（state、finalizedAt、correctionStartedAt、lockedAt）とFuture Diaryはそのまま運ばれる。

**書き込みの入口**
- 編集画面：`DiaryRepository.saveBlocks(entryId, texts, releaseIfBlank)`（同じmutex、LOCKEDは拒否、変化なしは何も書かない、離れるときprojectionが空で写真が無いDRAFTは削除）。
- 本文まるごとの書き込み（AIの`appendToJournal`）：`DiaryRepository.saveBody` → `DiaryContentStore.saveBody`（`MemoContent.reconcile`、末尾の追記は最後のtextへ）。
- 作成：`createEntry`が空のtextを1つ作る（カレンダー、日記一覧、日の一覧、AIのJournal作成）。
- 写真：`importDiaryPhotos(entryId, uris, writingTextId, caret)`（caretの位置で文章を分ける。2枚目以降は1枚目の直後）、削除は`afterPhotoDeleted`（前後の文章は残る）、並べ替えは`reassignPhotos`（枠はそのまま中身を入れ替え）。
- 結合：`DiaryRepository.mergeTextBlocks`（Backspaceが文章の先頭で届いたとき）。

**画面**（`DiaryEditorScreen` / `DiaryEditorViewModel`）
- 写真の無い日記は今まで通り1つの本文欄（見た目・高さ・キーボード時のスクロールも同じ）。ただし欄はcaretを持つ値になり、**最初の写真もcaretの位置に入る**。
- 写真のある日記は、Memoの`MemoBlockColumn`を`plain = true`（装飾もタスクの箱も描かない。日記の欄はもともと描かない）で使う。文章と写真を順番に1本の列で、写真は本文幅・元の比率。編集中の写真はタップ・長押しに反応せず、右上の⋮から「フルスクリーン / 写真一覧 / 削除」（LOCKEDの日は削除なし）。**「写真 N枚」などの管理表示は出さない**（写真一覧のsheetの中だけ）。「未来の自分へ」は最後のblockの後ろ、同じスクロールの中。
- 写真を追加：先に本文を保存 → caretの位置へ → 下の文章の先頭から書き続ける。
- 読み上げは`state.body`（projection）を読む。保存の間隔（600ms）、離れるときの保存、ON_STOPの保存は同じ。

**変わらないもの**：DRAFT / FINALIZED / CORRECTING / LOCKED、Future DiaryのSEALED / DELIVERED / REVEALED（未来コメントは`diaryEntryId`で日記に付き、blockの書き込みは触らない）、読み上げ、検索（bodyを読む）、AI DocumentAccess（bodyを読み、追記はreconcile）、autosave、process death（VMはDBから読み直す。block・caretは画面の一時状態）、Backup 1〜20のrestore、未来コメントの画面（元の日記は文字だけを表示 — 今と同じ）、書き出し（写真は本文の後ろ — Memoと同じ既知の制限）。

**テスト**：`MemoContentTest.aJournalWrittenBeforeBlocksKeepsItsWordsAboveItsPhotos`、`BackupDiaryContentBlocksTest`（4）、`MemoInlinePhotoPolicyTest`（日記がblock列を使う、plain、footer、caret）、`DiaryContentMigrationTest`（26→27を実ファイルで、4つのstate）、`DiaryContentBlocksInstrumentationTest`（8：文章→写真→文章→写真、caret、削除、LOCKED、空の下書き、AI追記と版、変化なし、未来コメント）、`DiaryBlockEditorInstrumentationTest`（5：順番と⋮と枚数なし、未来の自分への位置、入力、LOCKED、写真なし）。版数を固定していた既存のテストは27 / 21へ。
