# 日記のAI追記と復元のlock order — 監査（2026-09-25）

> 状態：**実装済み（§6）。merge前で停止。** 監査とRED（§1〜§5）は人間が承認（2026-09-25）。ブランチ`fix/diary-lock-order`（base：Outliner PhotoRow Stage 2の最終checkpoint `d997e2a`）。Outliner / Memo / PhotoRow / Calendar / 下のバーには触れない。Room 29 / Backup 23は変えない。

## 1. 関係するlock

| 記号 | 実体 | 性質 |
|---|---|---|
| **M_file** | `AttachmentRepository.fileMutex` | coroutineの`Mutex`（再入不可） |
| **M_diary** | `DiaryRepository.operationMutex` | coroutineの`Mutex`（再入不可）。日記のすべての書き込み（作成・本文・ブロック・結合）と、復元の`withOperationLock` |
| **M_future** | `FutureDiaryCommentRepository.mutex` | coroutineの`Mutex`。未来の自分へのすべての操作と、復元の`withOperationLock` |
| **T** | Roomの書き込みtransaction（`database.withTransaction`、Room 2.8.4） | 書き込みは1つずつ。transactionを持つcoroutineが終わるまで、他の`withTransaction`・書き込みは待つ。同じcoroutineの中の入れ子は再入できる |

ほかのlock：`DiaryEditorViewModel.saveMutex`（画面ごと。最初に取るだけで、その後に取る経路は無い）、`AiOrchestrator.lock`（復元・日記の経路は取らない）、`DriveBackupCoordinator.operationMutex`（`tryLock`だけ — 待たないので循環に入らない）。

## 2. 経路ごとの取得順

| 経路 | 取得順 | 呼び出し |
|---|---|---|
| 復元 | **M_file → M_diary → M_future → T** | `BackupEngine.restore` → `AttachmentRepository.installForRestore`（M_file）→ `writeRoom` → `DiaryRepository.withOperationLock`（M_diary）→ `FutureDiaryCommentRepository.withOperationLock`（M_future）→ `database.withTransaction`（T）→ `BackupDao.replaceAll` |
| 日記の自動保存・離れるときの保存 | M_diary → T | `DiaryEditorViewModel.saveCurrent` → `DiaryRepository.saveBlocks`（M_diary）→ `DiaryContentStore.saveTexts`（T） |
| 日記の作成 | M_diary → T | `DiaryRepository.createEntry`（M_diary）→ `DiaryDao.insert` / `DiaryContentStore.createFor`（T） |
| 写真の追加 | M_file → T | `AttachmentRepository.importDiaryPhotos` → `importPhotos`（M_file）→ `withTransaction`（T）→ `DiaryContentStore.placePhoto`（入れ子のT） |
| 写真の掃除 | M_file → T | `AttachmentRepository.garbageCollect`（M_file）→ DAO |
| 未来の自分へ | M_future → T | `FutureDiaryCommentRepository.create / reveal… / markDueDelivered`（M_future）→ DAO |
| **AIの日記への追記** | **T → M_diary** | `AiOrchestrator.execute` → `CommandExecutor` → `DocumentAccess.append` → **`RepositoryDocumentAccess.appendToJournal`：`database.withTransaction {`（T）… `diary.saveBody(…)` → `DiaryRepository.saveBody`：`operationMutex.withLock {`（M_diary）** |

AIの追記以外は、すべて **M_file → M_diary → M_future → T** の順序（部分順序）に従っている。**AIの日記への追記だけが T → M_diary の逆順**。

## 3. 逆順取得が実在する（具体的なcall path）

- **Path A（AI追記）**：`RepositoryDocumentAccess.appendToJournal` が `database.withTransaction` で **Tを取り**、その中で `DiaryRepository.saveBody` が **M_diaryを待つ**。
- **Path B（復元）**：`BackupEngine.restore` の `writeRoom` が `DiaryRepository.withOperationLock` で **M_diaryを取り**、`database.withTransaction` で **Tを待つ**。
- **Path B'（自動保存）**：`DiaryRepository.saveBlocks` が **M_diaryを取り**、`DiaryContentStore.saveTexts` の `withTransaction` で **Tを待つ**。

Aが先にTを取り、BまたはB'がそのあいだにM_diaryを取ると、互いに相手の持つlockを待ち続ける。**復元が無くても、自動保存とAI追記だけで同じdeadlockが起こりうる**（B'）。transactionを持ったまま止まるので、そのあいだアプリの他のすべての書き込み（メモ・チャット履歴など）もTを待って止まる。

メモ / アウトラインへのAI追記（`appendToMemo`：T → `MemoRepository.save`）は、`MemoRepository`にmutexが無いので逆順にならない。

## 4. RED（`DiaryLockOrderInstrumentationTest`、実装前）

アプリと同じ配線（1つの`DiaryRepository`をAIの`RepositoryDocumentAccess`と本物の`BackupEngine`で共有、自動保存は`DiaryRepository.saveBlocks`そのもの）。テストが足すのは、名前の付いた経路をentryの最初の読み取りで一時停止できるDAOだけ（危険な順序に並べるため）。deadlock = 両方が10秒たっても終わらない（テストがその後で取り消す）。

| テスト | 内容 | 実装前 |
|---|---|---|
| AI → 復元 | AIの追記がtransactionの中にいるときに復元が始まる | **deadlock（RED）** |
| 復元 → AI | 復元がM_diaryを持っているときにAIが追記 | 通る（復元が先、追記はConflictで何も書かない、復元は完全） |
| 自動保存 ＋ AI | 自動保存がM_diaryを持っているときにAIが追記 | **deadlock（RED）** |
| LOCKED | LOCKEDの日記にAI追記と自動保存 | 通る（ReadOnly、何も書かれない） |
| 失敗 / rollback | 待っている復元を取り消し、AIの追記が続く | 通る（途中の状態なし、追記は1回、未来の自分へは不変） |
| process recreation | AI → 復元の後、DBを閉じて開き直し、新しいlockで追記 | **deadlock（RED、最初の段階で）** |

3回続けて同じ3件がRED、同じ3件がgreen（決定的）。修正後に確かめる不変条件：日記の内容の消失0、追記の重複0、復元の途中状態0、LOCKEDへの書き込み0、未来の自分へ不変。

## 5. 修正方針（人間が承認、2026-09-25 — 実装は§6）

**全経路で取得順を M_file → M_diary → M_future → T に統一する。** 逆順はAIの日記への追記の1か所だけなので、その経路を順序に合わせる：

- 読み取り → 版の比較 → 書き込みを`DiaryRepository`の中へ移す（例：`DiaryRepository.appendIfUnchanged(entryId, text, expectedUpdatedAt)`）。**M_diaryを先に取り**、その中で`DiaryContentStore`の**1つのtransaction**（T）で「存在・LOCKEDでない・版が同じ」を確かめてから書く。結果（Done / Conflict / ReadOnly / NotFound）は今と同じ。
- `RepositoryDocumentAccess.appendToJournal`は外側の`withTransaction`をやめ、その1つの呼び出しにする。
- 版の比較と書き込みは、今と同じく1つのtransactionの中なので、途中で他の書き手が入ることは無い（M_diaryは日記のすべての書き手が通る。写真の追加はM_diaryを通らないが、比較と書き込みが同じtransactionなので割り込めない）。
- 再発防止：`withTransaction { … }`の中から`DiaryRepository` / `FutureDiaryCommentRepository`の操作を呼ばないことをソースで確かめるpolicy test、各repositoryのKDocにlock順序を明記。
- timeout・retry loop・lockの削除は使わない。Room / Backupの変更は不要。

変更は1経路・2〜3ファイルに収まり、データの形も結果の意味も変えないため、**重大な設計変更ではない**と判断する。ただし指示どおり、実装前にここで止める。

## 6. 実装（2026-09-25）

**許される順序（アプリのすべての経路）：M_file → M_diary → M_future → T。** 変えたのはAIの日記への追記の経路だけ。

| | 実装前 | 実装後 |
|---|---|---|
| 復元 | M_file → M_diary → M_future → T | 同じ（変更なし） |
| 自動保存・保存 | M_diary → T | 同じ（変更なし） |
| 日記の作成 | M_diary → T | 同じ（変更なし） |
| 写真 | M_file → T | 同じ（変更なし） |
| 未来の自分へ | M_future → T | 同じ（変更なし） |
| **AIの日記への追記** | **T → M_diary**（逆順） | **M_diary → T** |

実装後、どの経路もこの順序に沿って取り、逆順（どこかでTを持ったままM_diary / M_futureを待つ）は無い。

- `DiaryRepository.appendIfUnchanged(entryId, expectedUpdatedAt, newBody)`：**M_diaryを取り**、`DiaryContentStore.inTransaction`で**1つのRoom transaction**に入り、その中で entryを読む → 状態（LOCKEDなど書けない状態はReadOnly）→ 版の比較（違えばConflict）→ 新しい本文を書く（`DiaryContentStore.saveBody`、同じtransactionに入る）→ 更新後のentryを返す。読み取り・比較・書き込みは同じM_diaryと同じtransactionの中なので、途中に他の書き手は入れない（TOCTOUなし）。結果`DiaryAppendResult`：Done / Conflict / ReadOnly / NotFound（今までと同じ意味）。retry無し、timeout無し、最後の書き手の勝ちにしない。
- `RepositoryDocumentAccess.appendToJournal`：外側の`database.withTransaction`をやめ、`appendIfUnchanged`を1回呼んで結果を`DocumentWriteResult`へそのまま移すだけ。メモ / アウトラインの追記（`appendToMemo`）は変えていない（逆順が無いため）。
- `DiaryContentStore.inTransaction`：M_diaryを持った呼び手のための、1つのtransaction（中の書き込みは同じtransactionに入る）。
- `DiaryRepository`のKDocに順序を明記：「Allowed lock order: M_file → M_diary → M_future → T。ここのメソッドをRoom transactionの中から呼ばない」。`FutureDiaryCommentRepository`・`BackupEngine`は変えていない（コメントも）。

### 6.1 再発防止（`DiaryLockOrderPolicyTest`、JVM）

既存のpolicy testと同じくソースを読むが、1つの綴りに頼らない：
- lockを取るメソッドは、2つのrepository自身から導く（本体がそのmutexを取るメソッド）。新しい書き込みAPIが増えても自動で対象になる（`markDueDeliveredLockHeld`のようにlockを取らないものは対象外）。
- その型の値の名前（引数・property・lazy）を各ファイルの宣言から見つける。
- `withTransaction { … }` / `inTransaction { … }`のblockを括弧の対応で切り出し、その中でlockを取るメソッドを呼んでいれば失敗（repository自身のtransactionの中での自分のlockメソッドの呼び出しも）。
- 検査器そのものを、以前の逆順のコードで試す（見逃さないこと）。実際に、修正前の`RepositoryDocumentAccess.kt`を一時的に戻すと、このテストは`data/documents/RepositoryDocumentAccess.kt: diary.saveBody`を指して失敗した（確認後、修正版に戻した）。
- 復元の`writeRoom`がM_diary → M_future → Tの順であることも確かめる。

### 6.2 テスト（`DiaryLockOrderInstrumentationTest`、13件）

- RED → GREEN：AI → 復元、自動保存 ＋ AI、process recreationの3件がdeadlockしなくなった。
- 引き続きGREEN：復元 → AI（Conflict、何も書かない）、LOCKED（書き込み0）、取り消した復元（途中の状態なし・追記1回）。
- 追加（7件）：AIの追記2つが同時（1つがDone、もう1つはConflict、重複・途中書き込みなし）／自動保存とAI追記を20回（毎回終わる、本文 = ブロックのprojection）／復元 ＋ 自動保存 ＋ AI追記（deadlockなし、最終状態は復元か自動保存のどちらかで完全、未来の自分へはbackupのとおり）／古い版はConflict・LOCKEDはReadOnly・無い日記はNotFound（書き込み0）／transactionの中で書き込みの後に失敗（すべてrollback、lockは解放され次の書き手が通る）／取り消し（lockが解放される、何も書かれない）／process recreation（途中で終わった追記の後、ファイルは完全に開き直せ、次の追記が通る）。
- 3回続けて 13 / 13。

### 6.3 不変条件

日記の内容の消失0、追記の重複0、復元の途中状態0、LOCKEDへの書き込み0、未来の自分へ不変（追記・自動保存は触れない。復元はbackupのとおりにする）— いずれも上のテストで確かめている。Room 29 / Backup 23 / versionCode 4 / 1.1.0は変更なし。

### 6.4 ゲートとS20

- **ゲート（`lockorder`、未コミットの木 = このコミットの木）**：clean build、unit **1593 / 0**（policy test 4件を含む）、lint **0 errors**、端末スイート（1プロセス）**OK 665 / 0 failures**（`DiaryLockOrderInstrumentationTest` 13件を含む）。Room 29 / Backup 23、versionCode 4 / 1.1.0。
- **S20**（その場で上書き、先にDBを写した。clear / uninstallなし）：既存の日記（QAの日記1件）が開く／行末に「 QA」を足して離れると保存され、本文 = ブロックのprojection／force-stopして開き直しても同じ文が見える／integrity ok、日記以外の表は導入前の写しと同一。**LOCKED**：今のアプリにはLOCKEDを作る経路が無い（退役したライフサイクルの古い行か、復元でだけ来る）。S20にLOCKEDの日記は無く、作るにはDBを直接書き換えるか復元で上書きする必要があるため、実機では行っていない — LOCKEDは本物のDBでの端末テスト（2件）で確かめている。
