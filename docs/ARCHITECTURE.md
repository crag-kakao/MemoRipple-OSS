# MemoRipple 初期アーキテクチャ

## プロジェクト境界

MemoRipple は単一 `app` module の独立 Android アプリである。namespace/applicationId は
1.0 向けに `io.github.cragcoffee.memoripple` として確定済み（2026-09-02。旧
`io.github.cragcoffee.memocomments` は歴史的名称）。他プロジェクトとのコード共有や権限共有は行わない。

## Phase 1 の構成

```text
Compose Screen
    ↓ event / ↑ immutable UI state
ViewModel (StateFlow)
    ↓
MemoRepository
    ↓
Room DAO / MemoEntity
```

- UI: Jetpack Compose、Material 3、Navigation Compose
- 状態: ViewModel + StateFlow による一方向データフロー
- 保存: Room。ローカルDBを正本とし、空のメモは保存しない
- 自動保存: 入力停止から600ms後、および画面の停止・離脱時
- 検索: タイトルと本文を部分一致で検索し、更新日時の降順で表示
- Bottom Navigation: メモ / カレンダー / チャット。設定は各トップ画面右上から開く。チャット v0 は Local AI ではなく DocumentSearch / DocumentAccess を利用する検索・操作 workspace（docs/CHAT_FOUNDATION.md）。旧「日記」ページはカレンダーの top bar から「日記一覧」として開く（docs/CALENDAR_TAB_MIGRATION.md）
- Folder Navigator: メモ一覧のDrawerにフォルダの木（展開 / 折りたたみ、字下げ、選択中の表示）。行は`FolderNavigator.rows`が`FolderTree.flatten`から一度の走査で作る投影で、Entityではない。開いているフォルダと展開集合は`MemoListViewModel`の`SavedStateHandle`に置く（docs/FOLDER_NAVIGATOR_AUDIT.md）
- Document 境界: `domain/documents`（`DocumentKind` / `DocumentRef` / `DocumentSummary` / `DocumentContent` / `DocumentDestination`）と `DocumentAccess`（get / create / append / search）。実装は `data/documents/RepositoryDocumentAccess` が既存 Repository の上に置かれ、Entity / DAO は境界を越えない。Chat → Resolver → DocumentAccess → Repository → Room の順しかない（docs/DOCUMENT_BOUNDARY.md）

## データモデル

Memoの正本は次の `MemoEntity` と、Phase 2Bで追加した `MemoCommentEntity` である。

```text
Memo(id, title, body, createdAt, updatedAt, isFavorite, isPinned, noteId?, chapterId?, episodeOrder)
Note(id, title, subtitle, coverColor, coverBlobSha256?, createdAt, updatedAt)
NoteChapter(id, noteId, title, sortOrder)
MemoComment(id, memoId, text, createdAt, playbackOrder)
```

ノートに属するMemo（`noteId IS NOT NULL`＝エピソード）は**メモの壁に並べない**。同じ文章が二か所に
現れると、どちらが本体なのかが読めなくなるためである。壁は`observeStandaloneMemos`（`noteId IS NULL`）
を見て、`observeMemos`はエピソードを含んだままリンクの照合に使う。Entityは一つなので、ノートから外せば
（`noteId = NULL`）そのまま壁へ戻る。`feature/outliner`（Room 20）では`memos.kind`（`memo`／`outline`、
作成時に決まり本文からは判定しない）が加わり、壁は`kind = 'memo'`、ホームのアウトライナーpageは
`kind = 'outline'`（`observeOutlineDocuments`）を見る。ノート所属（`noteId`）と種別（`kind`）は別の軸である。
Memo を id で開く経路は `MemoNavigator`（`ui/MemoRippleApp.kt`）に一本化し、`MemoDestination` が kind から
編集画面かアウトライナーかを決める——画面側に kind の分岐を置かない。この隔離を保つため、エディタはエピソードに「アーカイブ」を出さない
——アーカイブはノートから見えない場所へ単独で移す唯一の操作だからである。ゴミ箱行きはノート側の問い合わせが
`trashedAt IS NULL`で除くので、隔離と矛盾しない。

`MemoComment.memoId`はMemoへのForeign Keyで、Memo削除時はCASCADEする。本文から得られる
Work Commentは重複保存しない。Phase 3AではMemoとライフサイクルが異なるDiaryを別Entityとして追加する。

```text
DiaryEntry(id, diaryDateEpochDay[unique], body, state, createdAt, updatedAt,
           finalizedAt?, correctionStartedAt?, lockedAt?)
FutureDiaryComment(id, diaryEntryId, text, sealedAt, revealAt, deliveredAt?, revealedAt?,
                   firstPresentedAt?)
```

`DiaryState` は `DRAFT → FINALIZED → CORRECTING → LOCKED` の状態遷移として domain 層で検証する。
日付変更によるロックと未来コメント公開判定は、差し替え可能な `TimeProvider` を通し、UI・Entityへ
現在時刻取得を分散させない。Future Comment は封印後に内容・公開日時を更新できない Repository API
とし、削除だけを許可する。

## Phase 3A: Diary Foundation

DiaryはMemoのタイトル・コメント・再生機能を共有しない。1日単位の状態制約と将来のFuture Diary
Commentの親になる役割がMemoとは異なるため、`diary_entries`を独立EntityとしてRoom version 4で
追加する。Migration 3→4は新規テーブルとunique indexだけを作り、`memos`と`memo_comments`は変更しない。

`diaryDateEpochDay`は端末の現在Time Zoneで得た`LocalDate`をepoch dayの`Long`へ変換した値である。
この列のunique indexで1日1件をDBレベルでも保証する。時刻と今日の日付は`TimeProvider`からのみ取得し、
テストでは任意のLocalDateへ差し替える。

状態遷移は次の一方向だけを`DiaryStatePolicy`と`DiaryRepository`で許可する。

```text
DRAFT → FINALIZED → CORRECTING → LOCKED
   └──────── date changed ───────→ LOCKED
                    FINALIZED ───→ LOCKED
                    CORRECTING ──→ LOCKED
```

- `DRAFT`: 編集可能。入力停止から600ms後と画面停止・離脱時に自動保存する。空の新規DRAFTは保存せず、
  保存済みDRAFTを空にして離脱した場合は履歴から除く。
- `FINALIZED`: 読み取り専用。今日中に限り`CORRECTING`へ一度だけ進める。
- `CORRECTING`: 編集可能。状態自体をRoomへ保存するため、アプリ再生成後も同じ修正を継続する。
- `LOCKED`: 永久に読み取り専用。修正確定またはLocalDate不一致で到達し、時計を戻しても解除しない。

日付期限の確認はDiary一覧・Editorを開く時、resume、保存、初回確定、修正開始、修正確定の前に行う。
Alarm、WorkManager、通知Permissionは使用しない。日記一覧は今日と過去を分け、過去は日付降順のpreview、
Editorは状態に応じて編集可否とActionを切り替える。Phase 3AではDiaryをComment RendererやPresetへ接続せず、
Phase 3BでDiaryEntryを親とする未来コメントを別途追加する。

## Phase 3B: Future Diary Comment

未来コメントはMemo上のUser Commentとは用途・公開制約・親Entityが異なるため、Room version 5で
`future_diary_comments`を独立Entityとして追加する。`diaryEntryId`はDiaryEntryへのForeign Keyで、
将来Diary削除を追加しても孤立しないよう`ON DELETE CASCADE`とする。1つのDiaryから複数作成できる。

状態は重複した文字列列を持たず、timestampから導出する。

```text
SEALED:    deliveredAt == null && revealedAt == null
DELIVERED: deliveredAt != null && revealedAt == null
REVEALED:  revealedAt != null
```

公開日時は選択したLocalDateTimeを選択時点の端末ZoneIdでepoch millisへ変換し、絶対時刻として保存する。
due確認で`revealAt <= TimeProvider.nowMillis()`になった行だけ`deliveredAt`を永続化するため、端末時計を
戻してもSEALEDへ逆戻りしない。Alarm、WorkManager、Service、通知Permissionは使わず、Diary一覧・Detail・
resume時にdue確認する。

封印本文は暗号化ではなくUI上の封印である。SEALED/DELIVERED一覧QueryはSQLの`CASE`で本文列をnullにし、
Repositoryは本文を持たないSummary型へ変換する。通常画面、Dialog、Snackbar、accessibility semanticsへ
本文を渡さない。本文・公開日時を更新するRepository APIは設けず、封印後に許可する変更は削除だけである。

初回受取はDAO transaction内でDELIVERED・未開封を再確認し、`revealedAt`更新に成功した場合だけ本文を返す。
本文は通常Textへ先出しせず、次の既存経路で黒背景Stageを右から左へ流れる瞬間に初めて表示する。

```text
Atomic reveal result
  → FutureDiaryCommentPlaybackMapper
  → PlaybackItem
  → TextMeasurer / CommentLaneAllocator
  → CommentAnimator / CommentRenderer
```

REVEALED後はDiary Detailの履歴だけが本文を保持し、同じ再生経路で何度でも再生できる。並べ替え、編集、
公開日時変更、Diary Work/User Comment、Preset接続はPhase 3Bの対象外である。

## Phase 3B.1: Future Comment Reveal Context

Future Comment Revealは空のStageではなく、Future Commentの`diaryEntryId`から取得したSource Diaryとの
Context画面として扱う。黒背景のread-only画面に元Diaryの日付と本文全文を表示し、その上へpointer inputを
持たない既存`CommentRenderer` Layerを重ねる。長いDiaryは背面の`LazyColumn`でスクロールできる。

初回はSource Diaryを認識できるよう700msの文脈delay後にコメントを右から左へ流す。Animationは開封演出、
Diary本文下のstatic Textは読み返せる記録という別責務にする。初回Playbackの自然完走前はstatic本文を
表示せず、完走後だけ本文、`revealedAt`由来の受取日時、「もう一度再生」を表示する。再再生中はstatic本文を
維持する。後から開いた完了済みCommentは自動再生せず、この記録を最初から表示する。

Room version 6で`firstPresentedAt`を追加する。意味は次のとおりで、SEALED/DELIVERED/REVEALEDという既存状態は
増やさない。

```text
revealedAt != null && firstPresentedAt == null → 初回Presentation未完了
revealedAt != null && firstPresentedAt != null → static本文表示可能
```

`firstPresentedAt`は`CommentAnimator.advanceBy`がPLAYINGから自然終了した場合だけ保存する。stop、background、
画面離脱、ViewModel破棄では保存しない。未完了CommentはDiary履歴でも本文なしの
`AwaitingPresentation`として表示し、再入場すると同じSource Diary上で初回Animationを再試行する。
Migration 5→6では既存開発データを再封印しないため、既存`revealedAt`を`firstPresentedAt`へbackfillする。
SEALED/DELIVERED Queryの本文非露出は維持し、未完了REVEALEDにもSQL `CASE`で本文を流さない。

## Phase 2A: Work Comment と再生の境界

`WorkCommentParser` は Memo 本文を読み取り、記号を除いたテキスト・種類・深さ・元行番号へ変換する。
本文は変更せず、解析結果もDBへ重複保存しない。

```text
Memo.body
  → WorkCommentParser
  → CommentPlaybackPlanner
  → temporary PlaybackTimeline<PlaybackItem>
  → CommentAnimator (app time / pause / resume / linear position)
  → CommentRenderer (Compose overlay)
```

Planner は各行へ開始時刻、右→左の移動時間、レーン、文字倍率、透明度、強調を付与する。
見出しと疑問の前には通常行より長い間を設け、depth ごとに220ms遅延させる。発射基準間隔は
650ms、標準移動時間は8.5秒、見出しは10.5秒である。Timeline は再生時だけメモリ上に作り、
DBへ保存しない。

Animator は動画時刻ではなく Compose frame clock から得たアプリ内差分時間だけを進める。
pause中はframe clockを停止し、resume時は同じelapsedから再開する。終了・手動停止・本文変更・
画面離脱ではTimelineを破棄する。

Renderer はコメントの出所や解析記号を知らず、`PlaybackItem` の最終描画情報だけを受け取る。
本文の上へ重ねるLayerはclipされ、pointer inputと操作semanticsを持たない。経過時間を購読するのも
このLayerだけで、フレーム更新による本文エディタ全体の再composeを避ける。動画依存scheduler、
overlay権限、特殊motionは導入しない。

## Phase 2A.1: 実測レーン解決と表示先

Plannerが作るlaneとstart timeは、アウトライン構造を表す「希望値」として扱う。再生操作時に
Composeの`TextMeasurer`で、実際のfont scale・font weight・画面密度を反映した全コメント幅を測る。
描画幅はFloat実測値を切り上げ、通常余白7dpにglyph safety padding 2dp/sideを別途加える。
このrender幅をコメントSurfaceへ明示適用し、画面より長いコメントは横方向をunboundedで測定する。
Allocatorはrender幅を衝突矩形として使い、その外側へ別途8dpのコメント間安全距離を要求する。
測定値だけをUI非依存の`CommentLaneAllocator`へ渡し、最終lane/start timeを解決する。

```text
PlaybackTimeline (preferred layout)
  + TextMeasurer widths / playback bounds
  → CommentLaneAllocator (pure Kotlin)
  → collision-safe PlaybackTimeline
  → CommentAnimator
  → reusable CommentRenderer
```

同一laneでは、前後コメントの幅、画面幅、発射時刻、移動時間から両者のpx/ms速度を計算する。
後続が速い場合は前コメントが画面を出るまでの最小gapも検査する。希望laneから近い順に即時利用
できるlaneを探し、全laneが使用中なら各laneを二分探索して最短の安全時刻を選ぶ。DBには解決前・
解決後のTimelineを保存しない。

表示先は次の2種類で、AnimatorとRenderer本体を共有する。

- 本文上: 従来どおりMemo Editor上の非操作Layer
- コメントステージ: 上40%の黒いclip領域。本文は下60%に分離

ステージでは白文字、重要コメントは黄色として黒背景とのコントラストを確保する。色は
`CommentRendererColors`で注入し、Rendererや再生エンジンを表示先・背景へ固定しない。利用可能な
高さが小さい横画面では、最低40dpのlane高を基準にlane数を減らし、縦方向の重なりを避ける。
モード切替、画面回転、本文変更では現在の一時Timelineをcleanupする。

## Phase 2B: User Comment

User Commentは`MemoCommentRepository`を通してRoomへ保存し、メモ単位のFlowをEditorへ公開する。
DB version 1→2 migrationは`memo_comments`テーブルと`memoId` indexだけを追加し、既存Memoを保持する。
通常コメントには本文位置、開始時刻、lane、見た目を保存しない。

```text
MemoComment Flow
  → UserCommentPlaybackMapper
WorkCommentParser → CommentPlaybackPlanner
  → CommentPlaybackComposer
  → temporary PlaybackTimeline<PlaybackItem>
  → TextMeasurer → CommentLaneAllocator → CommentAnimator → CommentRenderer
```

ComposerはUser Commentを`playbackOrder ASC, id ASC`にする。Work CommentがあればWork Timeline全体の等分点へ
配置し、なければ0msから1200ms間隔で配置する。`BOTH / WORK_ONLY / USER_ONLY`は再生内容を選び、
`INLINE / STAGE`は表示先を選ぶため、互いに独立している。合成後は出所にかかわらず全項目を同じ
実測幅、glyph余白、8dp安全距離、lane allocatorへ渡す。追加・削除時は再生中Timelineを停止する。

## Phase 2B.1: User Comment再生順

Room version 2→3 migrationで`playbackOrder: Int`を追加する。既存Commentはメモごとに
`createdAt ASC, id ASC`で走査し、0始まりの連続値を割り当てる。作成日時は変更しない。
User Comment一覧とPlayback Composerはともに`playbackOrder ASC, id ASC`を正本の順序として使う。

新規Commentは同じメモの最大orderの末尾へ追加する。右端の48dp Drag Handleで行を移動し、
ドラッグ中はUI上だけで並べ替え、終了時にDAOの1 Transactionで全orderを正規化して保存する。
削除後の正規化と`createdAt ASC, id ASC`へ戻す操作もTransaction内で行う。本文スワイプには
drag gestureを付けず、ハンドルだけが並べ替えを開始する。アクセシビリティ用に上・下移動の
custom actionを公開する。

## Phase 2B.2: Comment Presets

Comment PresetはUser Comment入力欄へ固定文字列を挿入するUIショートカットであり、Entityや
Comment種別ではない。草、拍手、驚き、ｷﾀ━━、ここ好き、がんばれの6種類をコード上で定義する。
入力欄は`TextFieldValue`で選択位置を保持し、Preset文字列を現在カーソル位置へ挿入する。

保存時は通常入力と同じ`MemoCommentRepository.add()`を呼ぶ。そのためPreset由来Commentも
同じcreatedAt、playbackOrder、右端Drag Handle、作成順リセット、USER_ONLY/BOTH、INLINE/STAGE、
TextMeasurer、glyph safety padding、LaneAllocator、Rendererを共有する。Room versionは3のままで、
Preset専用データ、特殊animation、外部ライブラリは追加しない。

## Phase 4A: Settings / Preferences DataStore

Theme、コメント再生速度、コメントサイズ、Memo Stage背景はMemo/Diary単位ではなくアプリ全体の
`AppSettings`へ集約する。保存には単一のPreferences DataStore `app_settings`を使い、Applicationから
共有する`SettingsRepository`だけがkeyの読み書きを担当する。ComposableやActivityはPreferencesを
直接操作しない。Roomのコンテンツとはライフサイクルと用途が異なるためRoom versionは6のまま変更しない。

```text
Preferences DataStore (one app_settings instance)
    ↓ stable String key / safe fallback
SettingsRepository.settings: Flow<AppSettings>
    ├─→ App root → MemoRippleTheme
    ├─→ SettingsViewModel → Settings UI
    ├─→ Memo PlaybackTimeline
    └─→ Future Comment PlaybackTimeline
```

永続値はEnum ordinalではなく、`system/light/dark`、`slow/standard/fast`、
`small/standard/large`、`black/dark_gray/light/theme`という明示的なstable Stringを保存する。
未知値は項目ごとのDefaultへfallbackする。初期値はPhase 3B.1までの挙動を維持するため、ThemeはSYSTEM、
速度とサイズはSTANDARD、Stage背景はBLACKとする。resetはこの4 keyを削除し、同じDefaultへ戻す。

Theme FlowはApp rootで購読し、SYSTEMは端末設定、LIGHT/DARKは強制ColorSchemeとして再起動なしで適用する。
DataStore読込中はMain ThreadをblockせずSYSTEMを自然な初期値として使う。Settings画面は各変更を即時保存し、
現在値をFlowから再表示する。

再生設定はRendererで後付けせず、`PlaybackSettingsApplier`で一時Timelineへ適用する。速度は既存の各Comment
移動時間を`duration / velocityMultiplier`（SLOW 0.80、STANDARD 1.00、FAST 1.25）で変換し、サイズは既存の
見出し・depth・補足・Important等のfontScaleへSMALL 0.90、STANDARD 1.00、LARGE 1.15を乗算する。
変換後TimelineをTextMeasurerとCommentLaneAllocatorへ渡すため、glyph safety、実測幅、追突判定、描画が
同じ最終duration/fontScaleを共有する。Work/User/Preset、INLINE/STAGE、Future初回/再再生の全経路が対象である。

Memo Stage背景はBLACK、DARK_GRAY、LIGHT、THEMEのPaletteを持ち、背景と通常/Important/Surface/Shadow色を
`CommentStagePalette`から既存`CommentRendererColors`へ注入する。LightではImportantを濃いaccentにして
コントラストを保つ。Future Reveal Contextの黒背景は「過去との再会」という別UXのため設定対象外で、
コメント速度とサイズだけを共有する。`AppSettings`単位でまとまっているため、将来Backup/Restoreを追加する際も
Roomデータと分離したまま対象化できるが、Phase 4AではBackup自体は実装しない。

## Phase 4B: Manual Backup / Restore

手動BackupはRoomの物理DBファイルやDataStoreファイルをコピーせず、現在の論理データをversioned DTOへ写す。
Backup formatはRoom schema versionとは独立し、識別子`memorripple_backup`を使う。Phase 6C以降のexportは
`formatVersion = 2`、version 1と2をrestore可能とする。
拡張子`.mrbackup`、MIME type `application/octet-stream`を使用する。UTF-8 JSONをGZIP圧縮するが、GZIPは
暗号化ではない。ファイルにはMemo、MemoCommentと`playbackOrder`、Diaryの全状態、Future Commentの
`deliveredAt / revealedAt / firstPresentedAt`を含む全状態、4つのAppSettingsを常に含める。

```text
Room snapshot + AppSettings
  → BackupMapper → version 2 Backup DTO
  → kotlinx.serialization JSON → GZIP bytes
  → SAF CreateDocument

SAF OpenDocument → bounded read → GZIP / JSON decode
  → format・version・全参照/invariant validation
  → 本文を持たない件数Preview → 最終確認
  → Room完全置換transaction → 既存Diary/Future期限処理
  → AppSettings replaceAll
```

`BackupEngine`はsnapshot、codec、validation、完全置換を調停し、`SafBackupFileStore`はContentResolver I/Oだけを
担当する。CreateDocument/OpenDocumentを使うためstorage permissionは追加しない。JSON/GZIP、validation、
ファイルI/O、bulk restoreはMain thread外で実行する。将来Google Driveを追加する場合もtransportだけを替え、
同じBackup bytesを使用できる境界にする。

Restoreはmergeや部分選択を行わない。既存のMemo/Future子を先に、Diary/Memo親を後に削除し、親から子の順で
Backup内IDを明示insertする完全置換である。DB変更前にformat/version、正ID・table内ID重複、Foreign Key、
Diary日付一意性、連続`playbackOrder`、既知DiaryState、Future本文と公開状態依存、stable Settings値を全件検証する。
Preview型には作成日時と件数しか渡さず、特にSEALED/DELIVERED本文をDialog、Snackbar、semanticsへ渡さない。

Room置換と、既存`DiaryRepository.refreshExpiredEntries()`および
`FutureDiaryCommentRepository.markDueDelivered()`は1つのRoom transaction内で行う。Insertまたは期限処理失敗時は
削除を含め全てrollbackするため、別のrestore前cache snapshotは採用しない。`revealedAt != null &&
firstPresentedAt == null`もそのまま保存し、初回Presentation未完了の意味を維持する。

Preferences DataStoreはRoomとatomic transactionを共有できない。全validation後に旧Settingsをmemoryへ保持し、
Room transaction成功後に4 keyを1回のDataStore editで置換する。Settings書込だけ失敗した場合は旧Settingsの
再適用を試み、成功済みのユーザーデータを再破壊しない。この非atomic性は完了メッセージで区別する。

## Phase 4C: Text-to-Speech / 読み上げ

読み上げはCloud TTSや外部音声APIを使わず、Android frameworkの`TextToSpeech`だけを使用する。
Manifestの`queries`へ`android.intent.action.TTS_SERVICE`を宣言し、Permissionは追加しない。
Application scopeで共有する`SpeechController`が`TextToSpeechGateway`を介してEngineを遅延初期化し、
ScreenごとのEngine生成を避ける。実装Gatewayはインストール済みの日本語Voiceからnetwork不要のものだけを
quality降順・latency昇順・name順で決定的に選び、オフライン日本語Voiceがなければ利用不可として通知する。

```text
Memo / Diary / completed Future Context snapshot
  → SpeechContentComposer / Work syntax preprocessing / preset filter
  → SpeechChunker (getMaxSpeechInputLength, Unicode-safe natural boundaries)
  → SpeechController (session + unique utterance IDs)
  → TextToSpeechGateway (QUEUE_FLUSH, then QUEUE_ADD)
  → Android TextToSpeech / UtteranceProgressListener
```

Memoはタイトル、Work Comment制御記号を除いた本文、任意選択時だけ`playbackOrder ASC, id ASC`の
User Commentを読む。草・拍手・驚き・ｷﾀ━━の非音声Preset完全一致は読み飛ばす。Diaryは状態に関係なく
表示中本文の開始時snapshotを読む。Future Commentは封印体験を迂回しないよう、`firstPresentedAt != null`の
Reveal Contextだけを対象とし、元Diary、Future Comment、両方から選ぶ。SEALED、DELIVERED、初回Presentation
未完了の本文はspeech用UI stateへ渡さない。

Controllerは`IDLE / INITIALIZING / SPEAKING / ERROR / UNAVAILABLE`を公開し、非同期初期化後に保留要求を開始する。
`UtteranceProgressListener`の最終Segment完了だけをsession完了とし、session/utterance IDが一致しないstop後の
遅延callbackは無視する。speak失敗とonErrorはqueueを停止してErrorへ遷移する。長文は
`getMaxSpeechInputLength()`以内へ段落・文末・改行・空白の順で分割し、surrogate pairを分断しない。

Phase 4C.1ではTTSとVisual Comment Playbackの相互排他を解除し、独立した状態と操作として扱う。
Memo本文上・StageのWork/User Comment、およびPresentation完了後のFuture Comment再再生は、TTSと同時に
動作できる。Commentのpause/resume/stopはSpeechへ影響せず、Speechのstart/stopもCommentへ影響しない。
ただし本文・タイトル・User Comment変更時の古いsnapshot cleanupと、画面離脱、background、回転、
ViewModel破棄時のLifecycle cleanupは両方へ適用する。Future Commentは初回Presentation中だけTTSを禁止し、
自然完走によって`firstPresentedAt`が保存された後に限ってAnimationとTTSを同時利用できる。

Instrumentationではこの独立性をAndroid TTS Engineの発話時間へ依存させない。Applicationの小さな
`SpeechController`生成境界をtest runnerから差し替え、明示的なstopまで`SPEAKING`を維持するGatewayで
双方向の停止契約を検証する。通常runtimeは従来どおり`AndroidTextToSpeechGateway`を使用し、framework連携は
`AndroidTextToSpeechSmokeTest`で別に確認する。

## Phase 4D: Google Drive Auto Backup Transport

Google Drive連携は同期・mergeではなく、Phase 4Bの論理Backupをそのまま運ぶopt-in transportである。
`BackupEngine.prepareBackup()`が返すGZIP bytesを変換せず、Drive API v3のresumable uploadで
`appDataFolder`へ送る。復元もdownload bytesを既存`inspect()`、件数preview、最終確認、`restore()`へ渡す。
Room version 6、`memorripple_backup` formatVersion 1は変更しない。

```text
Room 4 tables + AppSettings changes
  → changeGeneration (drive_backup_state DataStore)
  → foreground + 30秒 debounce + single-flight
  → BackupEngine.prepareBackup()
  → DriveBackupTransport / Drive API v3 appDataFolder

Drive download → BackupEngine.inspect() → metadata-only preview
  → user confirmation → BackupEngine.restore() → dirty generation
```

認可にはGoogle Play services Authorization APIの`AuthorizationClient`を使い、要求scopeは
`https://www.googleapis.com/auth/drive.appdata`だけとする。email/profile/openid、`drive.file`、`drive`、
offline accessは要求しない。access tokenとresumable session URIは処理中のmemoryだけで扱い、DataStore、
Room、file、logへ保存しない。自動処理は認可resolutionを表示せず`NEEDS_AUTHORIZATION`として停止し、
接続・今すぐバックアップ・Drive復元というユーザー操作だけが認可UIを開始できる。

remoteは`MemoRipple-auto-backup.mrbackup`という最新1ファイルを正本とする。cacheしたfile IDが404なら
cacheを消して`spaces=appDataFolder`を検索し、同名重複時は`modifiedTime`最新を選ぶ。なければ作成し、
あれば更新する。全サイズでresumable uploadを使うため5MiB超も同じ経路であり、session URIは永続化しない。
401はtoken cacheをclearしてsilent authorizationを1回だけ試し、403でscopeを広げず、5xx/通信失敗では
dirty generationを維持する。

Drive専用Preferences DataStore `drive_backup_state`にはON/OFF、remote file ID、最終成功時刻、現在変更世代、
最終upload世代、機密情報を含まないerror種別だけを保存する。Room InvalidationTrackerで`memos`、
`memo_comments`、`diary_entries`、`future_diary_comments`を、Settings Flowで4設定を集中監視する。
upload開始時の世代だけを成功済みにするため、upload中の変更はdirtyのまま次回対象になる。自動処理は
foreground中だけ30秒debounceし、backgroundでは待機をcancelする。WorkManager、Service、Alarmは使わない。

自動バックアップをONにする前に、Memo/Comment/Diary/Future Comment/AppSettingsを端末外のGoogle Driveへ
送ること、SEALED本文もBackupに含むこと、保存先が通常Drive UIに出ないアプリ専用領域であること、GZIPは
MemoRipple独自のパスワード暗号化ではないことを明示する。OFFはremote data削除やGoogle権限revokeを行わない。
公開前にはGoogle Cloud ConsoleでDrive API、OAuth consent/branding、Privacy Policy URL、実際の
applicationIdとdebug/release/Play App Signing各SHA-1に対応するAndroid OAuth clientを構成し、Google Playの
Data Safetyでoff-device backup、Google Drive共有、SEALED本文を含む保存内容を再確認する。

## Phase 5A: Android Overlay Foundation

Android Overlayは常駐機能ではなく、ユーザーがMemo画面で明示的に開始する最大120秒のone-shot Sessionである。
表示対象はMemoのWork Comment、User Comment、またはBOTHだけで、Diary、Future Comment、TTS、Driveとは接続しない。
`MemoPlaybackTimelineFactory`で既存`WorkCommentParser`、`CommentPlaybackPlanner`、
`UserCommentPlaybackMapper`、`CommentPlaybackComposer`、`PlaybackSettingsApplier`を共有し、ServiceはIntentから
`memoId`、`PlaybackContentMode`、`requestId`だけを受け取る。Memo/Comment/AppSettingsはService開始時に一度だけ
読み、再生中はSnapshotとして扱う。Room version 6、AppSettings DataStore、`memorripple_backup` formatVersion 1、
Drive dirty generationは変更しない。

権限は起動時に要求しない。Overlay表示を選んで再生したとき、アプリ内Disclosureの後に
`Settings.canDrawOverlays()`を確認し、`ACTION_MANAGE_OVERLAY_PERMISSION`を開く。API 23〜29ではpackage URIを
第一候補にできるが、Android 11以降はpackage URIを付けず、個別画面が開くことを仮定しない。Intentをresolveできない
場合は`ACTION_SETTINGS`へfallbackする。設定から戻ったActivity resume/result時に再確認し、memory上の明示的な
Pending requestを許可時だけ1回続行する。拒否してもOverlay以外の機能は制限しない。

再生中は`foregroundServiceType="shortService"`の`OverlayCommentService`をActivity foregroundからだけ開始し、
DB取得より前に`ServiceCompat.startForeground()`を呼ぶ。Serviceは`START_NOT_STICKY`で、boot、receiver、worker、
app detectionから開始せず、process kill後も復元しない。Notification channelは`overlay_comment_playback`/LOW、
通知にはimmutable PendingIntentの停止Actionを置く。`POST_NOTIFICATIONS`は任意で、拒否時もForeground Service用
Notification objectを生成してOverlayを実行する。自然完了、通知停止、App内停止、task removal、shortService timeout、
watchdog、permission/rendering error、`onDestroy`は同じ冪等cleanupへ集約する。

WindowManagerには`TYPE_APPLICATION_OVERLAY`のRootを1枚だけ追加し、背景は透明、flagsは
`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`とする。CommentごとのWindowは作らない。API 31+では
`InputManager.getMaximumObscuringOpacityForTouch()`を参照し、window alphaを
`min(0.70, maximum * 0.90)`へclampする。API 30以下は0.70を使う。可読性は白/黄色の文字、shadow、半透明Dark surfaceで
確保し、Stage Backgroundは適用しない。WindowMetricsのsystem bar/cutout insetを除いたusable boundsを使い、
ComposeViewにはService専用`OverlayLifecycleOwner`、SavedStateRegistryOwner、ViewModelStoreOwnerを設定する。
Renderer、TextMeasurerのceil/render padding/glyph safety、CommentLaneAllocator、CommentAnimatorを再利用する。

Resolved Timelineが120秒を超える場合は削除・切り詰め・並べ替えを行わず開始を拒否する。通常は最後のCommentが左端を
抜けた時点で自動終了し、122秒watchdogは異常残留だけを回収する。Phase 5Aのrotation方針は、安全に同じelapsedを
再配置する代わりにSessionを停止するfallbackであり、古いViewや二重Windowを残さない。第三者アプリの
`HIDE_OVERLAY_WINDOWS`、DRM/secure画面を尊重し、AccessibilityService、UsageStats、MediaProjection、OCR、
foreground app検知など他アプリ内容を読む仕組みは持たない。

Settingsには「Androidの読み上げ設定を開く」導線を置く。公開SDK定数ではない
`com.android.settings.TTS_SETTINGS`は対応Activityをresolveできた場合だけ利用し、利用できなければ
Accessibility設定、一般設定の順にresolveしてfallbackする。resolve後の起動失敗も次候補へ進み、すべて
利用できない場合はSnackbarで通知する。MemoRipple独自のrate、pitch、Voice設定は持たない。

Screen離脱時は共有Engine自体をshutdownせず、process終了時だけ破棄する。TTS用設定・永続データは追加せず、
Room version 6、DataStore schema、Backup format `memorripple_backup` version 1を維持する。

## Phase 5B: Overlay Playback UX

Overlay開始前にMaterial 3のSetup Sheetを表示し、`PlaybackContentMode`、表示範囲、密度をSession単位で選択する。
表示範囲は`full`、`top_half`、`center`、`bottom_half`の安定したIDをIntentへ渡し、system barとcutoutを除いた
usable boundsに対してWindow自体を100%または指定位置の50%へ配置する。密度は`sparse` 1.30、`standard` 1.00、
`dense` 0.80で開始時刻だけを倍率変換し、移動時間、速度、font scaleは変更しない。これらはRoom、DataStore、Backup、
Driveへ保存しないSession-only optionであり、不明なIntent値はPhase 5A互換の`full` / `standard`へfallbackする。

Setupの件数・予想時間とServiceの最終計画は同じ`OverlayPlaybackPlanFactory`を使う。実Display幅、Region高、
`TextMeasurer`のrender幅、40dpの最小lane高を入力し、密度適用後に既存`CommentLaneAllocator`で衝突を解消してから
最終durationを算出する。0件またはresolved durationが120秒を超える場合は開始を無効化し、ServiceもDB Snapshotを
再取得して同じ判定を行う。IntentにはmemoId、content mode、region、density、requestIdだけを置く。

Process-scoped `OverlayPlaybackStateStore`は再生中のmemo/options/item count/durationと
`SystemClock.elapsedRealtime()`基準の開始時刻を保持する。App rootのBannerはこのStateを監視するため、Memo、Diary、
Settings間を移動しても表示され、残り時間を1秒ごとにUI内で更新し、既存Service停止Actionへ接続する。再生中に別Sessionを
要求した場合は自動置換せず確認Dialogを出し、ユーザーが承認した場合だけ現Sessionを停止してから新Requestを開始する。
Window 1枚、`NOT_FOCUSABLE | NOT_TOUCHABLE`、shortService、one-shot、rotation/task removal時停止などPhase 5Aの
安全境界は維持する。

## Phase 6A: UI architecture

UIのdomain orchestrationは各Screen/ViewModelに残し、`ui/components`にはstateを所有しない小さなProduct componentだけを置く。
`ProductSpacing` / `ProductSize`、Material 3 Typography/Shapes/Color rolesを基準とし、Top bar、Section header、Empty state、
Status chip、Settings row、Sheet header、Info banner、destructive actionを共有する。Future RevealとComment rendererの固定色は
演出・可読性契約として例外扱いする。Navigation、Room v6、DataStore、Backup v1、Drive/TTS/Overlay domain contractは変更しない。

## Phase 6B: Diary Calendar and Memory Recall

Diary rootは既存LISTをdefaultに保ち、Session-scopedな`DiaryViewMode`でLIST/CALENDARを切り替える。ViewModelが
`displayedMonth`と`selectedDate`を所有するためConfiguration changeでは維持されるが、DataStoreやBackupへは保存しない。
Calendarからの日記作成・閲覧は既存Diary routeだけを使い、same-day編集、expiry lock、Future Comment送信guardを迂回しない。

Calendar mathはComposeから分離した`DiaryCalendarGridFactory`が`YearMonth`、`firstDayOfWeek`、today、Diary日付集合から
6週×7日の固定42 cellを生成する。週開始は`WeekFields.of(deviceLocale).firstDayOfWeek`をUI境界で取得して明示的に渡す。
Roomは既存unique `diaryDateEpochDay` indexを利用するinclusive month range queryを追加するだけで、schema version 6を維持する。
月ごとにFlow queryは1本で、42 cell個別のqueryは行わない。

「過去の今日」は既存全Diary Flowからpureな`PastTodayFactory`でMonthDay完全一致、today自身除外、新しい年順に導出する。
2月29日は過去の2月29日だけを対象にし、2月28日や3月1日へ補正しない。CalendarとRecallは記録の有無を評価せず、
streak、達成数、Future Comment marker、通知、永続設定を持たない。

## Phase 6C: Memo Organization Foundation

Memoの長期利用に必要な`isFavorite`と`isPinned`を独立した永続属性としてRoom version 7で追加する。
（`isFavorite`はUIから外したが、列・DAO・Backupはそのまま残す。詳細は`UX_AUDIT.md`の該当節。）
Migration 6→7は両列を`NOT NULL DEFAULT 0`で追加し、既存Memo、ID、Comment relationship、timestampを保持する。
本文autosaveはcontent専用UPDATE、Favorite/Pinは専用UPDATEを使うため、organization操作は`createdAt`と
`updatedAt`を変更しない。`MemoRepository`を境界とし、UIからDAOは直接操作しない。

検索、Filter（すべて／ピン留め）、Sort（更新／作成／locale準拠タイトル）は
`MemoOrganizationPolicy`へ分離する。処理順はsource → search → filter → pinned grouping → group内sortで、
同値時はID降順まで比較して決定的にする。Filter/Sort/SearchはViewModelのSession stateであり、configuration changeでは
維持するが、Room、DataStore、Backupには保存しない。

Backup version 2のMemo DTOは`isFavorite`と`isPinned`を含む。version 1 JSONで両fieldが欠ける場合はserialization defaultで
falseへmapし、version 2はtrue/falseを完全往復する。future versionは拒否する。Manual Restoreの完全置換と
`BackupEngine`のbytesは維持され、Google Driveは同じversion 2 GZIP bytesを変換せずappDataFolderへuploadする。
Favorite/Pinは`memos` tableを更新するため既存InvalidationTrackerによりDrive dirty generation対象になる一方、
Session-only Filter/Sortはbackup対象外である。

## Phase 6D: Memo Tags Foundation

TagはMemo列へ埋め込まず、Room version 8の`tags`と`memo_tag_cross_refs`で多対多として保持する。
Migration 7→8は既存Memo、Favorite/Pin、Comment、timestampを変更せず、空のTag集合を追加する。
CrossRefはMemo/Tagの削除時だけcascadeし、反対側の本体は削除しない。Tag作成・rename・delete・attach・detachは
`TagRepository`を境界とし、Memoの`createdAt` / `updatedAt`を更新しない。

Tag identityは`TagNameNormalizer`がtrim後の表示名を保持し、NFKC + `Locale.ROOT` lowercaseから
`normalizedName`を導出する。内部空白は保持し、長さはUTF-16 code unitではなくUnicode code pointで1〜40とする。
DBのunique indexを最終的な競合防止に使い、duplicate constraintはユーザー向けerrorへ変換する。

Memo ListはMemo、全Tag、全CrossRefを各1本のFlowで購読し、memory上で`tagsByMemo`を組み立てるためN+1 queryを
発生させない。処理順はsource → text search → ALL/FAVORITES/PINNED → single Tag filter → pinned grouping → sort。
Tag filterはIDで保持するSession stateで、rename後も維持し、選択Tagの削除をReactiveに検知して解除する。

Backupは同じ`memorripple_backup`のformatVersion 3とし、Tagの`id/name/createdAt`とMemo/Tag relationを追加する。
`normalizedName`はderived dataとして保存せずRestore時に再生成する。v1はFavorite/Pin=false・Tags空、v2は
Favorite/Pinを保持・Tags空、v3はTagとrelationを含めて完全復元する。Restore前にTag ID、正規化名、長さ、
relation pairと参照先を検証し、Memos → Tags → dependents → Relationsの順にtransaction内で挿入する。
DriveはBackupEngineのv3 GZIPをそのまま運び、`tags`と`memo_tag_cross_refs`もdirty generation監視対象に含める。

## Phase 6E: Memo Archive and Trash

Memo lifecycleはRoom version 9のnullable `archivedAt` / `trashedAt`で表す。derived stateの優先順位は
`trashedAt != null`ならTRASHED、次に`archivedAt != null`ならARCHIVED、それ以外はACTIVEであり、両時刻が
存在する状態は「アーカイブからゴミ箱へ移動した」有効な状態である。Lifecycle専用UPDATEは`createdAt` / `updatedAt`を
変更しない。Trashからの復元は`trashedAt`だけをclearし、元がACTIVEならACTIVE、元がARCHIVEDならARCHIVEDへ戻す。

通常のMemo FlowはACTIVEだけ、Archive Flowは`archivedAt != null AND trashedAt IS NULL`、Trash Flowは
`trashedAt != null`だけを返す。物理削除はDAO queryとRepository guardの両方でTRASHEDに限定し、Memo Commentと
Memo/Tag relationは既存foreign keyでcascadeする一方、Tag本体は保持する。空にする操作はRoom transactionでTrash全件を
物理削除する。自動削除、期限、Worker、Alarmは持たない。Overlay snapshotはTRASHEDを拒否し、ACTIVE/ARCHIVEDは許可する。

Backupは`memorripple_backup` formatVersion 4で両時刻を追加する。v1/v2/v3でfieldが欠ける場合はnullへdefaultし、v4は
ACTIVE/ARCHIVED/TRASHEDと両時刻状態を完全往復する。負の時刻はRestore前に拒否し、未知のv5以降も拒否する。Driveは
BackupEngineのv4 GZIP bytesを変換せず、`memos` table invalidationを既存dirty generationへ反映する。

## Phase 6F: Advanced Organization and Bulk Actions

通常のACTIVE Memo一覧だけに、ViewModel内で完結する`MemoSelectionState`を追加する。選択IDと明示的な0件選択modeは
Room、DataStore、Backup、Driveへ保存しない。長押しまたはTop bar menuから開始し、選択中のCard tapはEditor遷移ではなく
toggleになる。Back、close、画面遷移、一括処理完了、選択対象がACTIVE Flowから全て消えた時にclearする。
「表示中をすべて選択」はSearch → ALL/FAVORITES/PINNED → Tag filter → pinned grouping → sortを通過した最終Listだけを対象にする。

一括Favorite/Pin/Archive/Trashは`MemoDao`の`@Transaction` default methodが開始時点でACTIVE IDを再検証し、単一の
set-based UPDATEを実行する。一括Tag add/removeも`TagDao` transaction内でACTIVE Memoと存在Tagを再検証し、CrossRefを
まとめてinsert/deleteする。Favorite/Pin/Tag/LifecycleはいずれもMemoの`createdAt` / `updatedAt`を変更しない。
Archive/Trash Undoは対象IDと操作時刻が現在値に一致する場合だけ戻すため、Snackbar表示後の別操作を上書きしない。

Tag filterはID集合と`TagMatchMode`（ANY/ALL）を持つ。0件はfilterなし、1件は両mode同値、ANYは選択Tagの和集合、
ALLは積集合である。Bottom Sheet内の一時選択は「完了」まで一覧へ反映せず、renameはIDを維持し、deleteされたIDだけを
reactiveに除外する。処理順はACTIVE source → text search → organization filter → multi-tag → pinned grouping → group sortである。

Phase 6FではRoom version 9、migration chain 1→9、`memorripple_backup` formatVersion 4、既存DataStore key、Manifest、
dependency、applicationId/namespaceを変更しない。一括persistent mutationは既存table invalidationによりDrive dirty generationへ
反映されるが、選択状態とfilter状態はdirty対象外である。

## Phase 7A: Comment Expression Foundation

User CommentのAppearanceは`CommentAppearance`（Color／Size／Emphasis semantic role）として保存する。Room version 10の
Migration 9→10は`memo_comments`へstable String 3列を追加し、既存行を`default / standard / normal`へ移行する。
ordinalやARGBは永続化しない。local DBの未知値はUI crashを避けるためdefaultへdecodeする一方、Backup Restoreは未知値を
検証段階で拒否する。Appearance専用DAO UPDATEはtext、createdAt、playbackOrder、Memo.updatedAtを変更しない。

`UserCommentPlaybackMapper`は永続Appearanceをsource-independentな`PlaybackItem`へ変換し、Work plannerは従来のoutline
hierarchyから同じfinal modelを作る。Rendererはsourceを判定せず、semantic color、final fontScale、final fontWeightだけを見る。
Userの最終sizeはbase × per-comment multiplier × global Comment Size、Workは従来relative size × global sizeである。
Colorはrender contextごとのpalette resolverでtoneだけを調整し、role identityを維持する。

Text measurementはfinal sizeとweightを適用後、width/heightを実測する。widthはceil、content padding、2dp glyph safetyを含み、
lane allocationは既存horizontal collision計算を維持する。Plan全体（BOTHはWorkとUser双方）の最大render heightに4dp safety gapを
足した値と40dpの大きい方を共通lane heightとし、利用可能高からlane数を減らす。INLINE／STAGE／OVERLAYとOverlay preflightは
同じmeasurementを使い、120秒上限とWindow alpha 0.70の安全契約を維持する。

Backupは`memorripple_backup` formatVersion 5でAppearance stable IDを往復する。v1〜v4は欠落fieldをdefaultsへmapし、v5は
許可roleのみ受理する。DriveはBackupEngineのGZIP bytesをそのまま運び、既存`memo_comments` invalidationが
Appearance updateをdirty generationへ反映する。DataStore、Manifest、dependencyは変更しない。

## Phase 7B: Comment Motion and Placement

User Commentの流れ方はAppearanceとは独立した`CommentMotion`として保持する。Speedは`slow / standard / fast`のstable IDと
0.85 / 1.00 / 1.20の相対velocity multiplier、Placementは`auto / top / middle / bottom`のstable IDでありordinalは保存しない。
Room version 11のMigration 10→11は`memo_comments`へ`motionSpeed`と`motionPlacement`を追加し、既存行を
`standard / auto`へ移行する。専用Motion UPDATEはtext、createdAt、playbackOrder、Appearance、Memo.updatedAtを変更しない。

最終速度はcomment固有のbase velocity × Global Playback Speed × Per-comment Speedである。User mapperがper-comment倍率を
travel durationへ反映し、既存Settings applierがglobal倍率を合成する。scheduled startは変更しない。Text measurement後の
`CommentLaneAllocator`はdistance / final travel durationから速度を求めるため、SLOWの後にFASTが来る場合を含めて追突・追越を
防止する。Overlay Densityはstart scheduleだけを変え、Speedとは独立する。

PlacementはSurfaceの実lane数が確定した後、`CommentLaneBandResolver`でlane center `(index + 0.5) / laneCount`を上・中央・下の
3 bandへ分類する。該当laneがない場合は1/6、1/2、5/6へ最も近いlaneへfallbackし、1 laneなら常に0とする。指定bandはhard
preferenceであり、混雑時も別bandへ逃がさず安全時刻までdelayする。INLINE、STAGE、Overlay Regionそれぞれの利用可能高から
解決するため、例えばTOP_HALF OverlayのBOTTOMはそのOverlay Surface内の下側を意味する。AUTOとWork Commentは従来の
preferred-lane候補を維持する。Rendererはsourceやpersisted roleを解釈せず、解決済みstyle、lane、timingだけを描画する。

Backupは`memorripple_backup` formatVersion 6でAppearanceとMotionのstable IDを往復する。v1〜v5はMotion欠落を
`standard / auto`へmapし、v6の未知IDとv7以降をRestore前に拒否する。DriveはBackupEngine v6 GZIP bytesを変換せず、Motion
更新は既存`memo_comments` invalidationからdirty generationへ反映する。DataStore、Manifest、dependencyは変更しない。

## Phase 7C: Fixed Comment Foundation

User Commentの`CommentMotionMode`は`flow / fixed_top / fixed_bottom`のstable Stringで保存し、ordinalは使わない。
Room version 12のMigration 11→12は`memo_comments.motionMode`を`flow` defaultで追加する。Fixedへ切り替えても
Speed／Placement値は保持し、Flowへ戻した時に復元する。Motion/Expression専用UPDATEは本文、作成時刻、再生順、Appearance、
Memo更新時刻を変更しない。

再生前に永続modeをsource-independentな`ResolvedPlaybackBehavior`へ解決する。Flowは従来どおり一行で右から左へ移動し、
Fixedは水平中央で4,000ms滞在する。Fixedはper-comment Speedを無視し、Global Playback Speedだけで滞在時間を調整する。
Top／Bottomは各SurfaceおよびOverlay Region相対のhard bandであり、上は昇順、下は降順の候補laneを使う。

Fixed textは最終size／weight適用後、左右16dpのsafe margin内でellipsisなしにwrapして実測する。render width／heightを
paddingとglyph safety込みでlane計画へ渡し、全項目の最大高 + gapをlane heightとする。Surfaceへ収まらない時は再生を開始せず、
INLINE／STAGEはSnackbar、OVERLAYはSetupのdisabled actionと回復文で通知する。

同一laneの衝突はFlow×Flowだけ既存の空間・追越判定を維持し、Flow×FixedとFixed×Fixedは滞在時間区間の重なりを禁止する。
hard band内で空きlaneを探し、なければ安全時刻までqueueする。Pause中はapp clockを進めず、Resumeは同じelapsed timeから続行し、
Stop／自然終了は既存Animatorと単一Overlay windowのcleanup契約を共有する。

BackupはformatVersion 7で`motionMode`を往復する。v1〜v6は欠落modeを`flow`へmapし、v7の未知modeとv8以降を拒否する。
DriveはBackupEngine v7 bytesをそのまま運び、既存`memo_comments` invalidationでdirtyにする。DataStore、Manifest、dependency、
applicationIdは変更しない。

## Phase 7D: Future Comment Expression Foundation

Future CommentはMemo Commentと同じ`CommentAppearance`と`CommentMotionMode`を使い、Color／Size／Emphasisと
Flow／Fixed Top／Fixed Bottomだけを送信時に本文・公開日時と同じ行へinsertする。Future専用role enumは持たず、stable Stringを
Room version 13の`future_diary_comments`へ保存する。Migration 12→13は既存SEALED／DELIVERED／REVEALEDの本文、状態時刻、
`firstPresentedAt`を変更せず、`default / standard / normal / flow`を追加する。local DB未知値はsafe defaultへdecodeする。

Expressionは送信確定までComposable stateであり、draftだけをRoomやDrive dirty generationへ書かない。送信後はReveal後も更新APIを
設けずimmutableとする。SEALED／DELIVERED／初回Presentation待ちのoverview modelは本文とExpressionを持たず、Room queryも
`firstPresentedAt`完了後だけstatic record用metadataを返す。Restore Previewは引き続き件数だけで、本文・Expressionを公開しない。

初回RevealとReplayは`FutureDiaryCommentPlaybackMapper`から既存`PlaybackItem`へ変換し、共通Text measurement、lane layout、
allocator、Animator、Rendererを通る。Flowは既存FutureのStandard速度と位置、FixedはPhase 7Cの4秒dwell、hard Top／Bottom、
safe-width wrapを使い、Global Playback Speed／Comment Sizeだけを合成する。現在のblack Context surfaceにFixedが収まらない場合は
render-timeだけFlowへfallbackし、persisted modeは書き換えない。自然完了時だけ`firstPresentedAt`を記録し、中断時は次回同じ
Expressionで再試行する。ReplayはExpressionを再利用するが`firstPresentedAt`を更新しない。

Backupは`memorripple_backup` formatVersion 8でFuture Expressionのstable source IDsを保存する。v1〜v7の欠落値はdefaultsへmapし、
v8の未知role／modeとv9以降はRestore前に拒否する。resolved color、measured geometry、Fixed→Flow fallbackは保存しない。
DriveはBackupEngine v8 GZIP bytesをそのまま使い、Future insertは既存`future_diary_comments` invalidationでdirtyになる。

## Phase 7E: Special Flow Motion Foundation

Memo User CommentのFlow表現へ`CommentFlowDirection`（`rtl / ltr`）と`CommentFlowEffect`
（`straight / wave`）を追加する。いずれもstable Stringでありordinalは保存しない。Room version 14のMigration 13→14は
`memo_comments.flowDirection`と`flowEffect`を`rtl / straight` defaultで追加し、既存Appearance、Mode、Speed、Placement、
本文、作成時刻、再生順を変更しない。local DBの未知値はRTL／Straightへsafe fallbackする。Fixed中も値は保存するが、
resolved behaviorがFixedならDirection／Effectを描画へ適用しない。

`UserCommentPlaybackMapper`はpersisted roleをsource-independentな`ResolvedFlowDirection`／`ResolvedFlowEffect`へ変換する。
Work CommentとFuture CommentはPlaybackItemのdefaultで常にRTL／Straightとなり、Future EntityやFuture Expression UIへ列・選択肢を
追加しない。RTLは`surfaceWidth - progress × (surfaceWidth + renderWidth)`、LTRは
`-renderWidth + progress × (surfaceWidth + renderWidth)`で左右反転し、同じ幅と速度ならdurationは同一である。
Global／Per-comment Speedは従来どおり絶対速度の大きさだけへ作用し、Directionはscheduled startやdurationを変更しない。

Lane allocatorは同方向FlowについてRTL／LTR共通の進行方向正規化されたgap／追越計算を使う。Directionが異なるFlowは正面衝突を
避けるため同一laneのactive intervalを重ねず、空きlaneがなければ先行item終了までdelayする。Flow×Fixed／Fixed×Fixedは
Phase 7Cのtemporal exclusion、hard Top／Middle／Bottom bandを維持する。

Waveはapp-clock progressを0..1へclampして`sin(progress × 2 cycles × 2π) × amplitude`を返すPure Kotlin
`CommentFlowPath`で解決する。desired amplitudeは6dp。まず全itemの実測render heightへWaveだけ`2 × 6dp`を足し、plan-wide
最大required extent + vertical gapからlane heightとlane countを決定する。その後、実lane geometryから
`(laneHeight - renderHeight - gap) / 2`をsafe maximumとしてeffective amplitudeを再計算する。この順序により循環を避け、
Large／Strongを含むpeak時も隣接laneへ侵入しない。Waveは水平durationを変えず、Pause中は共通elapsed timeが停止するためX/Yが
同時に停止し、Resumeは同じphaseから続く。独立timer、random phase、custom amplitude/cyclesは持たない。

Backupは`memorripple_backup` formatVersion 9でMemo CommentのDirection／Effectを往復する。v1〜v8はDTOに値が存在しても
契約上`rtl / straight`へmapし、v9は既知IDだけを受理、v10以降を拒否する。Future Comment v8 Expressionはそのまま維持し、
Future DTOへDirection／Effectを追加しない。Backup previewにも表現metadataを追加しない。DriveはBackupEngine v9 bytesをそのまま
運び、Motion updateは既存`memo_comments` invalidationでdirtyになる。DataStore、Manifest、dependency、Drive transport、
applicationId／namespaceは変更しない。

## Phase 9A: Photo Attachment Foundation

Memo／Diaryの写真は共有`AttachmentRepository`を境界とし、Photo Picker URIをprivate content-addressed `AttachmentBlobStore`へcopyする。Room（v15で導入、現行はv19）はblob metadataとMemo／Diary relationだけを保持し、binaryをBLOB/Base64にしない。relation table invalidationはDrive dirty generationを進めるが、reference-zero blob GCだけではdirtyにしない。missing/corrupt binaryはread pathでsafe placeholderへ解決する。

Backup（v10でZIP化、現行formatVersion 15 — `feature/outliner`でメモ`kind`を追加、mainは14）は`manifest.json`＋raw `blobs/<sha256>`のdeterministic ZIPで、v1〜v9 GZIP readerを含むv1〜v15 restoreを維持する。SAFとDrive transportはcache fileをstreamし、全manifest／entry／hash／image寸法を検証してからblob install、Room full replacement、orphan GCの順に進む。詳細limitsとprocess-death contractは`ATTACHMENTS.md`を正本とする。

## 画面遷移

```text
メモ一覧 ─→ メモ編集
   └────→ 設定
日記一覧 ─→ 日記Editor / 過去日記Detail
   └────→ 未来コメント初回受取
日記Detail ─→ 開封済み未来コメント再生
   └────→ 設定
```

Phase 1 のメモ CRUD・検索・自動保存、Phase 2A.1 の Work Comment再生・衝突回避・ステージ表示、
Phase 2B の User Comment保存・削除・混合再生までを実装済み。
Diary Comment、User Comment編集、Play Comment、特殊motion、自動/差分/merge backup、暗号化、
Google Drive transportは後続フェーズで実装する。

## AI safety boundary (`domain/ai`, Phase 1, 2026-09-19)

A model's answer never reaches a document directly. The official pipeline is

```
LLM → IntentProposal → SemanticValidator → Resolver → Preview / Confirmation → DocumentAccess
```

`IntentProposal` carries the user's words and tokens only (opaque `result_N` refs, relative
`DateToken`s, no id, no date); `SemanticValidator` judges it against what was said and shown;
`Resolver` turns a valid proposal into a `ResolvedCommand` through `DocumentAccess.search/get`,
the request-scoped `AiResultContext` and `TimeProvider`; `ExecutionPolicy` runs SEARCH / OPEN
directly and demands a preview and the user's confirmation for CREATE / APPEND / USE_TEMPLATE;
`CommandExecutor` writes through `DocumentAccess.create/append` only, with the version the
preview was built from. **GBNF valid ≠ semantic safe**: validity of the shape grants nothing.
Details: `docs/AI_SAFE_INTENT_PIPELINE.md`. The package depends on `domain/documents`,
`domain/diary/TimeProvider` and `domain/memos/MemoTemplate` and on nothing else
(`AiBoundaryPolicyTest`). Chat v0 does not use it yet.

## Local LLM runtime adapter (`domain/ai/runtime`, `data/ai`, Phase 2, 2026-09-19)

The runtime sits *upstream* of the AI safety boundary: `LocalModelRuntime` (load / generate /
unload, one generation at a time, unload allowed mid-generation) is a domain interface; the data
layer implements it over a `NativeInferenceEngine` (llama.cpp behind JNI in `libmemoripple_llm`)
with `ModelProfiles` for the two Phase 1 candidates. `StructuredIntentGenerator` turns user text
and the shown results into prompt v1 + GBNF, and `IntentProposalParser` turns the raw JSON into an
`IntentProposal` through a guarded transport DTO (size limits, opaque refs, tokens). The domain
never names an engine, a vendor, a file format or the platform (`AiRuntimeAdapterPolicyTest`);
another backend is another adapter. Thermal (`ThermalGate`, SEVERE blocks) and memory
(`onTrimMemory` → unload) rules come from Phase 0. Details: `docs/LOCAL_LLM_RUNTIME.md`.

## AI Orchestrator / Chat Integration Preview (`domain/ai/AiOrchestrator.kt`, `ui/chat`, Phase 3, 2026-09-20)

The first connection of the チャット screen to the local runtime. The screen gains a 検索 / AI
segmented control; 検索 is v0 unchanged and never touches a model. The AI mode hands text to
`AiOrchestrator` — the **one door** from a screen to the AI path — and shows an
`AiInteractionResult`: text → thermal → `ModelSelection` → runtime (created on the first ask)
→ load → `StructuredIntentGenerator` → `SemanticValidator` → `Resolver` → `ExecutionPolicy` →
`CommandExecutor` for **Direct reads only**. SEARCH runs and reuses the chat rows; a unique OPEN
goes to the navigator; an ambiguous one lists candidates; CREATE / APPEND / USE_TEMPLATE stop at
their `CommandPreview` with キャンセル only (`confirm()` is called by no one — a policy test reads
the sources); UNKNOWN / Invalid / NeedsInformation / NotFound / ThermalBlocked / ModelUnavailable /
RuntimeError are explained in the user's words with the detail in the log. The result, the
preview and the `result_N` context are request-scoped memory (never `SavedStateHandle`, DataStore
or Room); the saved keys are mode and input. The model is unloaded on leaving the mode, on an
idle timer and on memory pressure — but LOW pressure waits for an ask in flight (loading a model
raises the trim level by itself; found on the S20). The native engine is constructed only behind
the CPU feature check (`productLocalModelRuntime`). No history, no write, no selector, no default
model. Details: `docs/AI_CHAT_PREVIEW.md`.

## Confirmed Write Execution — the Human Confirmation boundary (`domain/ai/AiOrchestrator.kt`, `ui/chat`, Phase 4, 2026-09-20)

The official write path is now complete and has exactly one human step in it:

```
LLM → IntentProposal → SemanticValidator → Resolver → ExecutionPolicy → CommandPreview
      → **Human Confirmation** → ConfirmedCommand → CommandExecutor → DocumentAccess
```

The preview leaves the orchestrator with a single-use `PendingWrite` ticket; the user's tap on
the preview's confirm button is the only thing that turns it into a `ConfirmedCommand`
(`AiOrchestrator.execute(pending)` — the one `confirm()` call in main sources, pinned by
`AiConfirmedWritePolicyTest`). The model, a confidence, a timer, a recomposition, a lifecycle
callback or a process restart cannot confirm; a taken ticket runs nothing again, so a double tap
is one write. The write needs no model: the runtime may be unloaded and the thermal gate SEVERE.
A document that moved after the preview is `Conflict` and nothing is retried; ReadOnly, NotFound,
Rejected and Failed are reported in the user's words. No undo is added (none exists for these
writes); no table, no persistence — a lost preview is a write that never happens. Details:
`docs/AI_CONFIRMED_WRITE.md`.

## Model Management / User Model Choice (`domain/ai/models`, `data/ai/models`, `ui/settings/AiModelsScreen`, Phase 5, 2026-09-20)

The product UX for the model file itself: a bundled catalog (`CatalogEntry` values in the domain,
the two vendor entries in the data layer), `ModelManager` (download → `.part` → SHA-256 → atomic
rename → Installed; guards for CPU support, free space with a margin, battery and metered
network before any byte; one download at a time; range resume after an interruption; cancel /
retry by the user only), `FileModelStore` under `noBackupFilesDir/models/<id>/`,
`HttpModelDownloader` on `HttpURLConnection` (HTTPS only), `AndroidDeviceGuards`, and the one
preference `ai_selected_model_id`. **Installed ≠ selected, and there is no default:** the runtime
loads only the model the user selected and which is installed (`ProductModelSelection`);
selecting or deleting unloads the runtime and never loads. A new settings section 「AI」 →
「Local AIモデル」 shows both candidates neutrally (Balanced / Safety-oriented, size, memory, licence)
with download / resume / retry / cancel / delete (confirmed) / 使用する. Chat's AI mode points to
it when nothing is selected; the search mode never depends on a model. No Room table, no new
permission, no WorkManager, no catalog server. The pipeline and the Human Confirmation boundary
are untouched. Details: `docs/AI_MODEL_MANAGEMENT.md`.

## Target Resolution — the deterministic candidate layer (`domain/ai/TargetCandidateExtractor.kt`, Phase 6, 2026-09-20)

When the model returns OPEN / APPEND with neither a shown `result_N` nor a `targetName` (as
Qwen does for case C), `TargetCandidateExtractor` reads at most one candidate *name* from the
user's own sentence by a few fixed Japanese shapes (the segment before に with quoted text left
out; the segment before を開いて / を見せて / を表示して), refusing demonstratives, generic document
and folder words, date phrases and clauses with sentence particles, and normalizing only NFKC
and whitespace. It has no execution authority and no search: the name goes to the unchanged
Resolver (exact title first, then partial; one resolves, several are Ambiguous, none is
NotFound), then the preview and the Human Confirmation as before. A name the model gave is
honoured as it is, with no fallback after NotFound; a shown ref wins over everything; the
proposal's text is never touched. No confidence, embedding, similarity or vector search; prompt
v1 is byte-identical to Phase 0. Details: `docs/AI_TARGET_RESOLUTION.md`.

## Chat v1 Release Readiness (`domain/ai/AiOrchestrator.kt`, `domain/ai/models`, `ui/chat`, `ui/settings/AiModelsScreen`, Phase 7, 2026-09-20)

An audit round, no new AI capability (`docs/CHAT_V1_RELEASE_READINESS.md`). What changed in the
structure:

- **Availability before an input.** `AiOrchestrator.availability(): ModelAvailability`
  (`Available(model)` / `Unavailable(reason)`) answers from the CPU gate (`deviceSupported`, the
  same `CpuFeatures.hasDotProduct()` the runtime factory uses) and `ModelSelection.availability()`
  — without constructing a runtime. `interact()` reads it first, so a missing or corrupt file and an
  unsupported CPU are `ModelUnavailable` **before any load**. `ChatViewModel.refreshAvailability()`
  asks it when the AI mode opens, on every resume of the screen (`LifecycleResumeEffect`) and after a
  dismissed card; the screen shows the setup card (「AIモデルを設定」 → the `AI_MODELS` route) in place of
  the input while it is unavailable. `AiPanelState.availability` is memory only.
- **`ModelUnavailableReason.MODEL_FILE_CORRUPT`.** `ProductModelSelection.availability()` decides
  the reason from the preference and the file: nothing selected, the selected file gone, the selected
  file not the catalog's verified length; the selection is never switched by the app.
  `ModelManager.scan` reports a wrong-length install as `Failed(CORRUPT_FILE)`, `select` refuses it,
  a retry removes it and downloads afresh; the models screen re-reads the disk on entry.
- **Offline guard.** `DeviceGuards.isOnline()` — a one-shot `ConnectivityManager` read at the download
  decision (after the CPU gate, before the metered question) → `DownloadRequest.Offline`. Same
  permission, same point, never a callback or receiver.
- **Retry is the user's tap.** `ChatViewModel.retry()` asks the failed text again (a load / generation
  / parse failure) or, after a conflict, produces a new preview; nothing loops. The confirm boundary
  is untouched: one `confirm()` caller, one `execute(` in the view model.
- **Shipped downloader is HTTPS-only.** `HttpModelDownloader(allowLoopbackHttp = false)`; the test
  application opts in for the fixture server.
- **Screens.** `ChatScreen` and `AiModelsScreen` are `internal` with defaulted callbacks so the
  layout / theme / semantics tests render them without an activity of the product; chips and card
  actions wrap (`FlowRow`); model cards carry `selected` / `stateDescription`, status lines are polite
  live regions; a state chip beside the category chip tells installed from selected.
- **Pinned by tests.** `ChatV1ReleasePolicyTest` (availability before input, retry without loops,
  offline before metered, no cleartext / developer path / test endpoint in release, no model bytes
  packaged, version untouched, the standing AI rules), the Phase 3–6 policy tests unchanged.

## Conversation History / Safe Conversation Context (`domain/ai/conversation`, `data/Chat*`, `ui/chat`, Phase 8, 2026-09-20)

`docs/AI_CONVERSATION_HISTORY.md`. Three layers, kept apart: the **persistent transcript**
(`ChatConversation` / `ChatMessage`, Room 25 `chat_conversations` + `chat_messages`, only what the
user saw), the **persistent safe context** (`SafeConversationContext`: the latest shown results by
position and the last document anchor — `chat_result_refs` + two columns; rebuilt into a
request-scoped `AiResultContext` and lent as `ConversationReferents`), and the **bounded active
context** (`ConversationWindow` from `ActiveContextBudget`: ≤ 6 messages, ≤ 1,200 chars, composed
into the user turn by `ConversationPromptComposer`; prompt v1 untouched). `ConversationReferent`
reads 「N番目」 / 「それ」 / 「さっきのメモ」 by rule inside `LocalAiOrchestrator.run`, after the model and
before the Resolver; the Resolver re-reads every shown ref through `DocumentAccess.get`.
`ConversationStore` / `LastConversationStore` (domain contracts) are implemented by
`ChatHistoryRepository` / `DataStoreLastConversationStore` (data). `ChatViewModel` creates the
conversation on the first message (`ConversationTitle`), appends the user line and the
`AiWording.assistantText` line, updates the results / anchor from the result, and follows the
`chat_last_conversation_id` preference; `ChatHistoryScreen` (route `chat-history`) lists and deletes.
Nothing in history can execute: one `confirm()` caller, one `execute(` in the view model, the
preview and the ticket ephemeral. Backup format 18 unchanged; the chat tables are outside every
backup and the portable export.

## Chat UI Redesign + Template v2 (`ui/chat`, `domain/memos`, `domain/ai/AiOrchestrator.kt`, 2026-09-21)

`docs/CHAT_UI_TEMPLATE_V2.md`. The chat is one conversation: `ChatViewModel` keeps the draft
(`KEY_INPUT`), the conversation, the ephemeral result and the template form; it no longer
searches or creates through `DocumentAccess` — every read and write goes through
`AiOrchestrator` (`interact` for free text, `runTemplate` for a template, `execute` for the one
confirm). `ChatScreen`: top bar (history / title / new chat / overflow), the transcript with the
cards under it, the bottom bar (＋ / input / send) with the model hint. Template v2 lives in
`domain/memos`: `MemoTemplate` (action / kind / fields / body / searchSpec / targetSpec, defaults =
the legacy shape), `TemplateRenderer` (`{{key}}` only), `TemplateValues`, `TemplateValidation`,
`TemplateFile` (the versioned file). `TemplateRunner` (beside the orchestrator, `internal`) turns a
template into the same `ResolvedCommand` / `IntentProposal` the AI path uses:
`ResolvedCommand.UseTemplate(template, renderedBody, request)` → `CommandPreview.Template(…, kind,
journalDate)`; the `USE_TEMPLATE` intent routes into it. `TemplateEditorRoute` (route
`template-editor`) and the テンプレート screen (import / export through SAF). Storage unchanged:
DataStore JSON, Room 25, backup 18 (`TemplateBackupDto` untouched).

**UI/UX review fixes (2026-09-21, `docs/CHAT_UI_TEMPLATE_V2.md` §13).** `AiOrchestrator.interact`
takes `onTiming: (AiTiming) -> Unit` — the generator's token counts and milliseconds, numbers
only, shown under the current answer and kept in `AiPanelState.timing` for one ask. `ChatViewModel`
takes the `ModelManager` (installed models as `ModelChoice(id, name)`, `selectModel` = the
manager's `select`, `selectNoModel` = `clearSelection`, both unload and never load) and a
`startFresh` flag for the 新しいチャット stage (`Routes.CHAT_STAGE`: the same `ChatRoute` with
`stage = true` — it clears the remembered conversation, follows a history pick, and the host
hides the bottom navigation and paints the window with `chatStageBackground()`). The chat's
Scaffold consumes the navigation-bar insets and the input bar wears `imePadding()`; the host's
bottom navigation gives way while the keyboard is up on the chat. `ProductCompactTopBar` takes a
`containerColor`. The compose icon is `res/drawable/ic_edit_square.xml`.

**AI optional, Template first-class (2026-09-21, `docs/CHAT_UI_TEMPLATE_V2.md` §14).** `domain/memos`
gains `StarterTemplates` (six read-only built-in templates; `find(idOrName)`; never written into
the store), `RecentTemplates` / `RecentTemplate(templateId, lastUsedAt)` / `RecentTemplateStore`
(five, newest first) and `TemplateBodyDisplay` (the body shown as `⟦label⟧`, stored as `{{key}}`;
`nextKey` = `field_N`; sample values and a search description for previews). `TemplateValidation`
speaks in labels and refuses duplicate labels. `data/RecentTemplateRepository` keeps the recents
as one preference (`recent_templates`); `TemplateLookupAdapter` falls back to the starters.
`ChatViewModel` lists the user's templates + the starters, resolves the recents, records a run,
and offers a deterministic `suggestion` (a template whose name the sentence contains) that only a
tap turns into the form. `TemplateRunner` asks for the required fields and an asked target at
once. The editor (`TemplateEditorScreen`) is stepwise with no key anywhere; the templates screen
lists the starters for copying. **Backup format 19**: `TemplateBackupDto` + `TemplateFieldBackupDto`
/ `TemplateSearchBackupDto` / `TemplateTargetBackupDto` (strings on the wire), `TemplateBackupMapping`
both ways, the validator judging by `TemplateValidation`; 1–18 still restore.

**The history drawer (2026-09-21 evening, `docs/CHAT_UI_TEMPLATE_V2.md` §15).** `ChatScreen` wraps its
Scaffold in a `ModalNavigationDrawer`; `ChatUiState.conversations` mirrors the store's list;
`ChatViewModel.openConversation(id)` sets the remembered-conversation preference (which the view
model follows) and `deleteConversation(id)` deletes one conversation. The history screen and its
route stay for managing.

**Template = conversation script (2026-09-21, `docs/CHAT_UI_TEMPLATE_V2.md` §16).** `TemplateField.question`
and `MemoTemplate.targetQuestion` (defaults; in the file and backup 19). `domain/memos/TemplateScript`
is the deterministic engine: `next(template, answers, target)` → `AskTarget` / `Ask(field, index,
total)` / `Ready`; `questionOf`, `spoken`, `targetQuestionOf`, `spokenAnswer`. `ChatViewModel` holds an
ephemeral `TemplateSessionState` (never saved): `pickTemplate` starts the script, `answerCurrent`
(the input), `answerOption` / `answerDate` / `skipCurrent` (the chips), `chooseTarget`,
`editAnswer`, `cancelSession`; an APPEND's target name is looked up through
`AiOrchestrator.runTemplate` with a SEARCH probe; the run goes through `executeTemplate` →
`runTemplate` → the same preview and confirmation. The form card is gone from the chat; the
editor asks for the questions and previews their order.

**The drawer refined (2026-09-21 21:29, `docs/CHAT_UI_TEMPLATE_V2.md` §15.1).** `PinnedConversationStore`
(domain) / `DataStorePinnedConversationStore` over `SettingsRepository.pinnedChatConversationIds`
(`chat_pinned_conversation_ids`, a string set of ids); `ChatViewModel.togglePin(id)` and
`ChatUiState.pinnedConversationIds`; the drawer's search is a local filter over the titles.

**Think templates (2026-09-22, `docs/THINK_TEMPLATES.md`, `feature/think-templates`).** A template's
*flow* beside its action: `MemoTemplate.flow: TemplateFlow = RECORD` (`RECORD` / `THINK`), defaulted,
`TemplateAction` unchanged. A THINK template is a CREATE MEMO template whose script ends in a result
instead of a run: `ThinkTemplates.result(template, answers, today)` (`domain/memos`) renders the body
by `TemplateRenderer` with `{{today}}` (`TemplateValues.TODAY_KEY`, reserved, filled by the clock as
`yyyy-MM-dd`; `⟦今日の日付⟧` in the editor) and 「（なし）」 for a skip; `ThinkTemplates.sectionOf` is the
picker's 記録 / 整理・壁打ち / 探す. `ChatViewModel.executeTemplate(save = false)` routes a THINK
template to `completeThink` — no orchestrator, no runtime — which records the recent and shows
`AiInteractionResult.ThinkResult(template, text, answers)` (declared beside the other results; the
orchestrator never produces it); `saveThinkResult()` sends the same answers down
`executeTemplate(save = true)` → `AiOrchestrator.runTemplate` (the CREATE branch) → `CommandPreview.Template`
→ the one `confirm()`; `finishThink()` drops the session and the card. `TemplateRunner` returns
`TemplateForm` for a THINK template with any unasked key, so a model's USE_TEMPLATE still goes through
the questions. `TemplateValidation`: THINK ⇒ CREATE + MEMO + ≥ 1 field. Wire: `TemplateBackupDto.flow:
String = "record"` in the unchanged format 19 (an older reader ignores the key and sees a CREATE
template); the template file serialises `flow` at formatVersion 1. Four starters `starter-think-*`
(`MAX_STARTERS` 12). Screen: `chat_template_section_record|think|search`, `chat_think_result`,
`chat_think_save`, `chat_think_done`; editor: `template_editor_action_THINK`,
`template_editor_insert_today`.

**Review Batch 2 (2026-09-22, `feature/think-templates`; `docs/CHAT_UI_TEMPLATE_V2.md` §18,
`docs/THINK_TEMPLATES.md` §6.1, `docs/AI_CONVERSATION_HISTORY.md` §16).** *Think 修正:* the result card's
修正 toggles the screen's existing `EditAnswers` list (now for a `ThinkResult` as well as a `WritePreview`)
→ `ChatViewModel.editAnswer(key)` (unchanged: the session steps back to that field with `editing = true`)
→ the answer → `advance` → Ready → `completeThink` again. *Pins:* `PinnedTemplates` (domain: `resolve`
skips an orphan id, `without` keeps a pinned row out of the other sections, `toggle`) +
`PinnedTemplateStore` → `DataStorePinnedTemplateStore` over `SettingsRepository.pinnedTemplateIds`
(`chat_pinned_template_ids`, a newline-joined ordered list of ids; outside the portable backup as a
device preference); `ChatUiState.pinnedTemplateIds`, `ChatViewModel.togglePinTemplate(id)`; the picker
resolves them and lists ピン留め first. *Conversation → memo — the safe export / write boundary:*
`ConversationExport` (`domain/ai/conversation`, pure) renders the transcript by rule;
`AiOrchestrator.previewMemo(body)` — a new interface method beside `runTemplate` — wraps it as
`ResolvedCommand.Create(MEMO)` through `ExecutionPolicy.decide` into `CommandPreview.Create` + a
`PendingWrite`; `ChatViewModel.saveConversationAsMemo()` shows that preview through `showResult`; the
write is still `confirmWrite()` → `execute(pending)` → the one `confirm()` → `CommandExecutor` →
`DocumentAccess`. The chat still reaches no DAO, runtime or network; `AiConfirmedWritePolicyTest` and
`ReviewBatch2PolicyTest` pin the boundary. *Quick create:* unchanged — the picker's
`chat_template_create` → `Routes.templateEditor(null)` → the one `TemplateEditorScreen`.

**Conversation → template draft (2026-09-22, `docs/CHAT_UI_TEMPLATE_V2.md` §19).** *A conversation is data,
not authority.* `ConversationTemplateDraft` (`domain/ai/conversation`, pure) → `MemoTemplate?` from the
title and the transcript: assistant `TEXT` lines that ask + the user's `TEXT` answer → `TemplateField`s
(`field_N`, MULTILINE, optional, the question, a rule-cut label; no default), flow THINK, CREATE MEMO, a
body of `## label` / `{{field_N}}` under `<name> - {{today}}`; null with no pair. `ChatViewModel.
templateDraftFromConversation()` only calls it (no store, no orchestrator). The screen's overflow entry
hands a non-null draft to the route (`onEditTemplateDraft`) → `MemoRippleApp` puts it in
`application.templateDraftHandoff` (`ui/settings/TemplateDraftHandoff`, an `AtomicReference` taken once)
and navigates to `Routes.templateEditor(null)`; `TemplateEditorRoute` takes the hand-off as its initial
template (`isNew`), and its existing `onSave` → `TemplateRepository.save` is the only write. A null draft
shows `chat_template_draft_none`. No new screen, route string, store, Room table or backup field.

**The picker as an entrance; template folders (2026-09-22, `docs/CHAT_UI_TEMPLATE_V2.md` §20).** *Document
folder vs template folder:* the memo wall's folder (`domain/folders`, `FolderEntity` / `FolderRepository`, Room —
a document's *location*, `FolderTree.flatten`, the navigator drawer) and the template folder
(`domain/memos/TemplateFolders.kt`: `TemplateFolder(id, name, order)`, `TemplateFolderPolicy`,
`TemplateFolderStore` → `data/TemplateFolderRepository` on the settings DataStore key `memo_template_folders` —
a template's *organisation* in the ＋ picker) share the word and nothing else: no type, no store, no screen,
no id space; `TemplateFolderPolicyTest` pins that neither package names the other. `MemoTemplate.folderId`
(one flat level, nullable) refers only to a `TemplateFolder`. `TemplatePicker` (domain) computes the root
(`root`: ≤ 3 pins, ≤ 3 recents minus pins, built-in counts, the custom count), a built-in folder's starters
(`builtIn`), 自分のテンプレート's rows (`mine`) and a folder's templates (`inFolder`; an unknown id → 未分類);
`ChatScreen`'s `TemplatePickerSheet` keeps a `PickerPage` and renders one page at a time inside the one
`ModalBottomSheet` — its `BackHandler` is provided the dialog's own `OnBackPressedDispatcherOwner` /
`NavigationEventDispatcherOwner` from the view tree, since activity-compose 1.12 would otherwise register
it on the activity's dispatcher and the sheet's own back-to-dismiss would win. `ChatViewModel` collects
`TemplateFolderStore.folders` into `ChatUiState.templateFolders`. `TemplateEditorRoute` collects the folders
for the editor's selector; `TemplateFoldersRoute` (`Routes.TEMPLATE_FOLDERS`) manages them and, on delete,
calls `TemplateRepository.clearFolder` before `TemplateFolderRepository.delete`. Backup: `BackupEngine`
carries `templateFolderRepository.current()` into `MemoRippleBackupDto.payload.templateFolders` and restores
them beside the templates (`BackupMapper.toTemplateFolders`); the validator checks unique ids and names;
`TemplateFile.export` writes `folderId = null`.

**The chat's folder (2026-09-22, `docs/CHAT_UI_TEMPLATE_V2.md` §21).** `CreateDestination(folderId, name)`
(`domain/documents`) is the user's chosen wall folder for what the chat creates. Ports in `domain/folders/
FolderChoices.kt`: `FolderChoice(id, name, depth)` + `FolderChoices.of(nodes)` (the tree by `FolderTree.flatten`)
and `FolderChoices.destination(choices, chosenId)` (null when nothing is chosen or the folder is gone);
`DocumentFolderChoices` (a Flow of choices, and `create(name)` for 「＋ 新しいフォルダ」 — the wall's own repository at the root) and `ChatDestinationStore` (the preference). Data:
`RepositoryFolderChoices` over `FolderRepository.observeFolders()` and `DataStoreChatDestinationStore` over
`SettingsRepository.chatCreateFolderId` (`chat_create_folder_id`, a Long preference). The chat view model combines
the two into `ChatUiState.folderChoices` / `destination` and clears the preference when its id names no folder;
`selectDestination(id)` writes it. The destination rides every orchestrator call — `interact`, `runTemplate`,
`previewMemo` (a defaulted last parameter) — into `Resolver.resolve(…, destination)` (`create` puts
`destination?.folderId` into `DocumentCreate.Memo` / `Outline`, never `Journal`, and the name onto
`ResolvedCommand.Create.folderName`) and `TemplateRunner.run(…, destination)` (the same for a CREATE template,
`ResolvedCommand.UseTemplate.folderName`); `ExecutionPolicy` copies the name onto `CommandPreview.Create /
Template.folderName` for the 「保存先」 line. The model has no folder word (`IntentProposal`, the parser). At the
boundary `RepositoryDocumentAccess.create` keeps a folder only if `folderDao().findById` still has it. Room 25
unchanged; nothing about the folder is persisted per conversation.

**Chat Fast Path, Phase 1 (2026-09-22, `docs/CHAT_FAST_PATH.md`, `feature/chat-fast-path`).** `domain/ai/fast/
ChatFastPath.kt`: `FastIntentRecognizer` (pure rules → `FastIntent(proposal, needsAnchor)`; the golden corpus
in `FastIntentRecognizerTest` is the contract), `ChatRoute` (`Fast` / `RequiresAi`), `ChatRouter` +
`DeterministicChatRouter` (the seam a future DecisionEngine joins). `LocalAiOrchestrator`: the post-proposal
tail of `run()` is extracted into `settle(proposal, userText, context, referents, destination, onNote,
intentForQuestions)` — the Phase 8 referent rules, the Phase 6 target assist, SemanticValidator → Resolver →
ExecutionPolicy → direct read / preview — and both `run()` (the model's proposal) and the new
`interactFast()` (the rules' proposal) end in it; `interactFast` performs no thermal / selection /
availability check and can never reach the runtime (policy-pinned). `ChatViewModel.askText` records the USER
line once, tries `interactFast`, then falls through to `interact` unchanged. One in-pipeline capability:
OPEN with `dateToken` + `documentKind` and no name — the validator counts a day+kind as a target and
`Resolver.target` searches that day with the same 0 / 1 / many rule. `AiLoggingPolicyTest` /
`ChatFastPathPolicyTest` pin: no content in logs, no route named to the user, one `confirm()`, the fast
package free of runtime / DAO / network / clock.

**AI Resource Controller, Phase 2 (2026-09-23, `docs/AI_RESOURCE_CONTROLLER.md`, `feature/ai-resource-controller`).**
`domain/ai/resource/AiResourceController.kt`: the application-scoped owner of the generation model's
lifecycle — `acquireForGeneration(model)` brackets exactly one generation as a `ResourceLease(runtime,
profile, budget)` (lazy runtime creation, a load only when not READY, one mutex over every transition:
simultaneous requests load once, unload never races load, nothing double-frees); `release()` arms the idle
clock (2 min default / 30 s under ECO–LIMITED, a configurable `IdleUnloadPolicy`, injectable `delayFn` so
tests advance a virtual clock) and **only a generation moves that clock** — the Fast Path, templates, Think
and plain chat reading acquire nothing and extend nothing; the Phase 3 memory amendment lives in the
controller whole (LOW: idle unloads now, mid-lease defers to the release; CRITICAL: unloads now, the runtime
cancels its own generation, write-free); the profile is `LOW_MEMORY > THERMAL_LIMITED > ECO > NORMAL`
(Power Save Mode read one-shot, the existing `ThermalGate` reused — no second monitor, no receiver, no new
permission) and maps to a `GenerationBudget` — NORMAL is byte-for-byte today's ask (6 / 1,200 / 300 / 256),
the reduced budget clips the window to its newest lines and caps the output at 192 tokens, prompt v1 and the
grammar untouched. `LocalAiOrchestrator` lost its own runtime field, idle job and `AiIdleUnload` and goes
through the controller; `release()` still waits for an ask in flight (「AIモデルなし」 mid-answer lets the
answer finish) and the model manager's select / clear / delete sequence is unchanged — selecting still never
loads. **`ChatViewModel.onCleared` no longer unloads**: keep-warm outlives the chat screen, the stage and the
tab switch (the 2026-09-23 brief supersedes Phase 3's screen-tied unload); the controller's clock, memory,
thermal or an explicit switch do all the unloading. Architecture rule: Fast Path — no model resource;
decision / generation — a lease when needed; document operations — independent of the resource lifecycle.
`AiResourcePolicyTest` pins the boundaries; the controller logs `AI_RESOURCE` lifecycle words only.

**DecisionEngine, Phase 3 (2026-09-23, `docs/DECISION_ENGINE.md`, `feature/decision-engine`).**
`domain/ai/decision/DecisionEngine.kt`: a **pure, non-suspend, deterministic** decision between the Fast
Path's NO_MATCH and the generation model — `decide(DecisionInput): DecisionResult` over nothing but this
conversation's safe context (the shown results' count and titles, the anchor's presence / kind / title;
another conversation's refs are structurally absent). CERTAIN operations (ordinals over the shown results,
demonstratives on a kind-agreeing anchor, dated kinds beyond the fast 日記 shapes, target-less appends with
the anchor as the only candidate) become ordinary `IntentProposal`s and walk the same settle() pipeline;
exactly one missing slot becomes one **fixed** question (何を追記しますか？ / どの記録に追記しますか？ /
どれを開きますか？) with chips of titles the user already saw (≤ 5, else NotApplicable); everything unsure —
questions, negations, compounds, quoted verbs, clauses, stale context, generic bodies — is NotApplicable for
the model. `DecisionCertainty` is an enum, never a number, and the door refuses AMBIGUOUS outright. The
doors (`interactDecide` / `completeDecision`) sit **before** the resource controller: CERTAIN and
clarification acquire nothing, load nothing and work with no model; a stale anchor is NotFound with no
retry; the Resolver stays the authority. The chat routes fast → decide → generation
(`route=FAST / DECISION_CERTAIN / DECISION_CLARIFY / GENERATION`, lengths only) and holds one ephemeral
`PendingClarification` — only while it is open is the next message a slot answer; a conversation switch or
a process death drops it, the transcript stays, nothing writes. A future `LocalClassifierDecisionEngine`
would implement the same interface behind the same door, and still never bypass the Resolver, the preview
or the Human Confirmation. `DecisionEnginePolicyTest` pins purity, the order and the wording; the golden
corpus in `DecisionEngineTest` is the contract.

**Generation Efficiency, Phase 4B (2026-09-23, `docs/GENERATION_EFFICIENCY.md`, `feature/generation-efficiency`).**
The one generation route (STRUCTURED_INTENT, grammar, greedy) is now decomposed and cached by identity. Native
(`memoripple_llm.cpp`): `nativeGenerate` times tokenization and prompt evaluation separately, keeps the tokens
whose KV the context holds, and — when the runtime allows it — reuses the **token-for-token** common prefix with
the previous request (`llama_memory_seq_rm` rewinds everything after it; an identical prompt re-evaluates its
last token; any error clears everything); `nativeResetCache` forgets it. Runtime: `GenerationRequest.cacheKey`
= `GenerationCacheKey(modelId, promptVersion, grammarHash, conversationId, route)`; `LocalModelRuntimeImpl`
reuses only inside one key, resets before any other, forgets on unload and after an error — so a model
switch / delete / idle / memory / thermal release (all `unload`) and a process death leave nothing to reuse,
and conversations never share a cache. `ConversationReferents.conversationId` names the identity (null = a
full evaluation). Timing: `AiTiming` / `IntentGeneration.Proposed` / `GenerationResult` carry
tokenize / promptEval / reusedPrefix / evaluated / promptBuild / load — numbers only, one `GEN` log line, no
product UI. Context: `ActiveContextBudget.window` leaves out `WRITE_EVENT` and `FAILURE` lines (UI events) and
keeps words and result lines; bounds and order unchanged. Prompt v1, the grammar, n_batch 512, 4 threads, mmap and
greedy sampling are unchanged (policy-pinned). Rejected on measurement: a tokenization cache (single-digit ms),
unmeasured thread / batch / mmap changes, cross-conversation reuse of the system prefix (excluded by decision).

## The chat's routing, as it stands on main (2026-09-23, the UI/UX Review Baseline)

The four AI rounds are merged; this is the whole path from a typed sentence to a document, and
the order is a contract (`DecisionEnginePolicyTest`, `ChatFastPathPolicyTest`, `AiResourcePolicyTest`,
`GenerationEfficiencyPolicyTest`):

```
USER text
   │
   ▼
Fast Path (domain/ai/fast, pure rules, no context)          ── MATCH ──┐
   │ NO_MATCH                                                          │
   ▼                                                                   │
DecisionEngine (domain/ai/decision, pure rules, this conversation's    │
   │             safe context only)                                    │
   ├─ CERTAIN ─────────────────────────────────────────────────────────┤
   ├─ NEEDS_CLARIFICATION ─ one fixed question + chips ─ answer ───────┤
   │ NOT_APPLICABLE                                                    │
   ▼                                                                   │
AiResourceController.acquireForGeneration  ← the ONLY place that loads │
   │  (lazy load, keep warm, idle unload, memory / power / thermal,    │
   │   generation budget)                                              │
   ▼                                                                   │
Generation model (prompt v1 + grammar, greedy)                         │
   │  Generation Efficiency: token-prefix reuse inside one             │
   │  GenerationCacheKey; timing decomposed; context pruned            │
   ▼                                                                   │
IntentProposal ────────────────────────────────────────────────────────┘
   │
   ▼  settle(): the one safe pipeline for every route
SemanticValidator → Resolver → ExecutionPolicy
   ├─ Direct (SEARCH, unique OPEN) ─ CommandExecutor ─ DocumentAccess
   └─ RequiresConfirmation ─ CommandPreview ─ **Human Confirmation** ─ ConfirmedCommand
                                                     (the one confirm() in main sources)
```

**What each layer may not do.** Fast Path and DecisionEngine: no DAO, no Room, no
`DocumentAccess` write, no `CommandExecutor` call, no preview or confirmation bypass, no
`ConfirmedCommand`, no destructive intent, nothing decided by a score. The resource controller:
no intent, resolver, preview or document knowledge. Templates, Think, 「この会話をメモとして保存」 and
the conversation→template draft reach the same `settle()` / preview / one `confirm()` and load no
model. **Load 0** for: app start, the chat tab, a new chat, history, templates, Think, the Fast
Path, a CERTAIN decision, a clarification, search, the model-manager screen.
