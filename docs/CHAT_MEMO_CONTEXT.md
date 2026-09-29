# チャットの「メモを選択」 — 選んだメモを会話の対象にする（2026-09-26）

> 状態：**実装済み、マージ前で停止。** ブランチ`feature/chat-memo-context`（base：Human UI/UX Reviewの基準`integration/v1-feature-freeze` `a7778ca`）。Human Reviewで見つかった、明示的なUX改善として扱う。Room 29 / Backup 23 / versionCode 4 / 1.1.0 / prompt v1 / grammarは変えていない。

## 1. 人間の決定（2026-09-26）

- **Decision 1**：選んだメモについての自由なQ&A・要約は**入れない**（deferred）。今のモデルは文法で決まった1つの`IntentProposal`（SEARCH / OPEN / CREATE / APPEND / USE_TEMPLATE / UNKNOWN）しか返せず、自由な文章で答える経路がない。それを作るのは新しいAIの能力（prompt v2・モデルが書く文章）で、「prompt v1は凍結」「どのモデルも要約しない」「AIの質問・要約はdeferred」「UI/UX Review中は大きな内部AIの変更をしない」に反する。メモの内容はモデルに送らない。この回の「メモを選択」は**OPEN / APPEND / 対象の無いAPPENDの参照先**に限る — 「このメモを開いて」「『…』を追記して」「追記して」。「このメモを要約して」「このメモについて質問したい」はdeferred。
- **Decision 2**：会話ごとの選択は**Roomを変えず**、端末の設定に会話id → メモidを持つ（ドロワーのピン留めと同じやり方）。
- **Decision 3**：選んだメモは、その会話の既定のanchorとして扱う。優先順位は **名前で明示した文書 → 「N番目」など表示中の結果 → 選んだメモ → 会話の元のanchor → 確認の質問**。
- フォルダ（新しく作るものの保存先）とメモ（既存の文書へのOPEN / APPEND）は同時に選べる。APPENDはフォルダを見ない。CREATEは選んだメモを保存先にしない。
- 選ぶことは書く権限ではない。書き込みは必ず今のパイプライン（Fast Path / DecisionEngine / IntentProposal → Resolver → ExecutionPolicy → プレビュー → 人の確認 → ConfirmedCommand → CommandExecutor → DocumentAccess）を通る。

## 2. 画面

- 入力欄の上：**［📁 フォルダを選択］ ［📄 メモを選択］**を横に並べる（`FlowRow`、狭い画面では2行目に折り返す — どちらも縮めない）。
- 選んだ後：**📄 タイトル ×**（1行、長い題は末尾を省略、最大幅280 dp）。タイトルをタップすると選び直し、×で選択だけを外す（会話の記録・表示中の結果・会話の元のanchorは消えない）。題の無いメモは「無題のメモ」。
- シート「メモを選択」：説明1行、「タイトルや本文で探す」、フォルダの絞り込み（すべてのフォルダ / 壁のフォルダ）、行＝題・本文の1行・更新日時（M/d HH:mm）。何も無い時「メモがありません」、絞り込んで無い時「見つかりませんでした」。選んでも何も書かない。

## 3. 仕組み

| 部品 | 場所 | 役目 |
|---|---|---|
| `MemoChoice` | `domain/ai/conversation/ChatMemoContext.kt` | 選べるメモ：`DocumentRef`（MEMO）、題、1行、更新日時、フォルダ。モデルには渡らない |
| `DocumentMemoChoices`（読むだけのport） | 同上 / `data/ChatHistoryRepository.kt`の`RepositoryMemoChoices` | 壁の`observeStandaloneMemos`（kind memo・アーカイブ無し・ゴミ箱無し・ノートのエピソード無し）を新しい順・最大200件・フォルダで絞る。`observe(id)`は同じ規則で1件（外れたらnull）。`ui/chat`はentity・DAO・Roomに触れない |
| `ChatMemoSelectionStore` | 同上 / `DataStoreChatMemoSelectionStore` | 設定`chat_selected_memo_ids`に`"<会話id>:<メモid>"`の集合。idだけ、バックアップに入らない |
| anchorの差し替え | `ChatViewModel.anchorFor` | 質問のたびに`ConversationReferents.anchor`＝選んだメモ（今も選べるメモなら）、無ければ会話の元のanchor。パイプラインはanchorを`DocumentAccess`で読み直し、種類を確かめてから使う |
| 「この〜」 | `ConversationReferent` / `DeterministicDecisionEngine` | 「この(メモ\|日記\|アウトライン)」を指示語に加えた（種類の語があるときだけ — 「このまちの記録」「このメモ帳」は題のまま）。Fast Pathが「このメモ」を名前として拾っても、Phase 8の規則（指示語は名前ではない）が参照先に読み直す |

**会話ごと**：新しいチャットでは、選んだメモは画面のメモリにだけあり、最初のメッセージで会話ができた時にその会話へ結び付ける（毎回書く — Roomは会話idを使い回すことがあるので、古い選択を引き継がない）。チャットの保存状態は下書きだけ（既存の規則。10のpolicy testが守る）なので、最初のメッセージの前にプロセスが終わるとその選択は消える（書き込みは何も無い）。会話ができた後の選択は設定にあり、プロセスの終了後も戻る。書く権限・プレビュー・`PendingWrite`は戻らない。会話の削除（ドロワー・「履歴を管理」の1件・全件）は会話ストアの`delete` / `deleteAll`の中で選択も消す。選んだメモが削除・アーカイブ・ゴミ箱になったら、選択を外して「メモを選択」に戻す。

**変わらないもの**：DecisionEngineの曖昧さの規則（表示中の結果があるときの対象の無い追記は「どの記録に追記しますか？」、選んだメモはその候補の1つ）、プレビューの時点の版での追記（違えばConflict、黙ってやり直さない）、追記は末尾だけ、モデル無しで動くこと（ロード0）、prompt v1・grammar、Room・Backup、チャットの保存状態。

## 4. テスト

- JVM（REDのあとGREEN）：`ChatMemoContextTest`（3：設定の形、壊れた項目は読まない、シートの1行）、`ConversationReferentPhraseTest` +2（「この〜」は種類の語があるときだけ参照先、題はそのまま）、`DecisionEngineTest` +2（「このメモを開いて／に『…』を追記して／に追記して」、種類違い・anchor無しは判断しない）。
- device `ChatMemoContextInstrumentationTest`（15、REDは13 / 13、3回続けて15 / 15）：2つのチップとシート（アクティブなメモだけ）、選ぶだけでは書き込み0、×、「このメモを開いて」、「『卵』を追記して」→プレビュー→確認の前は0→確認の後は選んだメモの末尾だけ、「追記して」→「何を追記しますか？」→追記、プレビューの後に他で書かれたらConflict、名前で指定したメモが優先、フォルダは作成に・メモは追記に、再生成しても選択が残りプレビューは無い、新しいチャットは最初のメッセージで結び付く、会話AとBの選択は別、会話を消すと選択も消える、ゴミ箱・アーカイブ・削除で選択が外れる。どの操作もモデルのロード0。
- テストの直し（製品は変えていない）：質問が終わるまで待つ、統合されたノードで文字を確かめる、確認の後に開いたメモから戻る、「削除」はRoomのtransactionの中で行を消す（アプリはゴミ箱からしか削除しないので、復元などで残った選択の場合を見る）。
- 近くのチャットのクラス16（`ChatDestination`、`ChatFastPath`、`ChatHomeLauncher`、`ChatDrawer`、`ChatHistory`、`ConversationHistory`、`AiConfirmedWrite`、`AiTargetResolution`、`ChatV1Layout`、`ChatUiReviewFix`、`ChatHintDismiss`、`ChatViewModelState`、`ChatViewModelConfirmedWrite`、`ChatUiTemplateV2`、`NoAiTemplateJourneys`、`BottomNavReselect`）と合わせて133 / 133。unit 1626 / 0。

## 5. ゲートとS20

**ゲート（`memoctx`、commit済みのclean tree `c791cad`）**：clean build、unit **1626 / 0**（1619 ＋ 7）、lint **0 errors**、device suite（1プロセス）**OK 721**（708通過・13 skip・0 failures）＝基準の706 ＋ このクラスの15。基準の実行にあった(class, test)はすべてある（欠け0）。Room 29 / Backup 23、versionCode 4 / 1.1.0、GGUF 0、prompt / grammar変更なし。

**S20**（在来install、DBを先にcopy、AIモデルなし）：チャットを再タップして新しいチャット → 入力欄の上に「フォルダを選択」「メモを選択」が並ぶ → 「メモを選択」のシート（メモだけ — QAのアウトラインは出ない、題・1行・日時）→ QAメモ「QA 旧形式」を選ぶ → 📄 QA 旧形式 × と表示、書き込み0 → 「追記して」を送る → 「何を追記しますか？」（最初のメッセージで会話22ができ、選択が結び付いた）→ 「QA」→ 追記の確認（追記先 メモ「QA 旧形式」、現在の内容、追加する内容 QA、「時点の内容に対する追記です」、確認の前は書き込み0）→ 「追記」→ 本文とtextブロックの末尾に「QA」、メモが開く。copyと比べて変わったのはメモ59（本文・updatedAt・ブロック）と、QAの会話22（9行、確認した追記先としてanchor＝メモ59 — 既存の規則）だけ。integrity ok。
- 補足：S20のシェルは日本語を入力できないので、「追記して」はGboardのローマ字かなで打った（Gboardは元の英字に戻した）。途中で一度チャットの外へBackで出た時、開いていた「何を追記しますか？」の質問は消えていた（その後の「QA」はモデル無しの案内になった、書き込み0）— 質問は既存の設計どおり一時的なもの。やり直した手順で上のとおり通った。

## 6. 見つけたこと（記録のみ、この回では直さない）

- **既存の競合**：ある会話への質問がまだ終わっていない（モデルの生成中など）ときに、その会話を「履歴を管理」などで削除すると、あとから回答の行を書く時に外部キーで失敗し、アプリが落ちる（`ChatDao.insertMessage`）。この機能とは関係なく以前からある。テストで見つかった（テストは質問が終わるのを待つようにした）。
- deferred：選んだメモについての自由なQ&A・要約（Decision 1）。

## 7. Human Reviewの2点（2026-09-27、`fix/chat-memo-folder-and-create`、HANDOFF §16.108）

**1. シートは選んだフォルダで開く。** 以前は`openMemoPicker()`が毎回「すべてのフォルダ」（`"" to null`）から始め、「フォルダを選択」で選んだフォルダを見ていなかった。今はチャットが持っているフォルダ（壁のフォルダと照らした`destination`）で開き、その名前を出し、実際にそのフォルダのメモだけを並べる。選んでいなければ今までどおり「すべてのフォルダ」。S20で見つかった付随の点：フォルダや検索を変えると、一覧は新しい内容になるのに前の先頭のメモの位置に留まり、新しいメモが上に隠れていた — 新しい条件の一覧が届いたら先頭へ戻す（`MemoPickerState.shownFor`）。シートのそれ以外は変えていない。

**2. メモを選んでいる間、チャットがメモを作る操作はそのメモの末尾への追記になる。** 以前、選んだメモはanchor（「このメモ」「『…』を追記して」「追記して」）にしか届かず、CREATEはどれも知らずに新しいメモを作っていた。`CreateDestination`が選んだメモ（`selectedMemo`）も運び（`ChatViewModel.writeDestination()`がすべてのorchestratorの呼び出しに付ける）、メモを選んでいる時の**メモ**のCREATE — AIのCREATE（Fast Path、ホームの「メモ」の質問、DecisionEngine、モデル）、CREATEのテンプレートとThinkの保存、「この会話をメモとして保存」 — は、そのメモの末尾へのAPPENDとして解決する（`Resolver.appendTo`：`DocumentAccess`で読み直し、版を取る — 名前で指した追記先と同じ）。追記の確認 → 人の確認 → 版が動いていればConflict。新しいメモは作らず、フォルダは使わない（メモが優先）。アウトライン・日記の作成はメモではないので今までどおり作る。追記する言葉が無ければ質問、選んだメモが消えていればNotFound。

変わらないもの：会話ごとの選択、削除・アーカイブ・ゴミ箱で外れる規則、会話の削除での解除、実行中の依頼の取り消し・遅れた結果・古いプレビューの拒否、ストアの1 transactionの確認、prompt v1・grammar（メモの内容はモデルに行かない）、Room 29 / Backup 23。既存テスト`theFolderIsForCreatingAndTheMemoForAppending`は、新しい規則ではメモを選んだままの「メモを作って」が追記になるため、×で選択を外してから確かめるようにした（フォルダが新規作成の保存先であることは同じく確かめる）。

**テスト**：JVM `ChatSelectedMemoCreateTest`（5、RED → GREEN）。device `ChatMemoFolderAndCreateInstrumentationTest`（7：フォルダで開き名前と絞り込みが一致、未選択なら「すべてのフォルダ」、すべてに切り替えると一覧の先頭から、ホームの「メモ」の答えとCREATEテンプレートは選んだメモへの追記で新規0、Conflict、選択の無い新しいチャットは今までどおり作る）。**ゲート（`mfc2`、clean tree `160b8df`）**：unit 1631 / 0、lint 0 errors、device OK 740（727通過・13 skip・0 failures）＝733 ＋ 7、欠け0。**S20**：フォルダQを選ぶ → 「メモを選択」は「Q」で開きQのメモだけ → 「すべてのフォルダ」にすると一覧の先頭（「QA 旧形式」）から → 「QA 旧形式」を選び、ホームの「メモ」→「QA-D」→ 追記の確認（保存先の行なし）→ 追記 → 末尾に「QA-D」、メモ数44のまま。copyと比べて変わったのはメモ59とQAの会話27だけ。チャットのフォルダは元の「なし」に戻した。

## 8. 統合（2026-09-27、HANDOFF §16.109）

`fix/chat-memo-folder-and-create` `f59778b`を`integration/v1-feature-freeze` `88f5475`へ`--no-ff`で統合した（`8a471a6`）。ブランチは`88f5475`の上に直接あったのでconflictは無く、統合後のtreeはブランチと同じ。§6の削除の保護（会話ごとの選択、会話の削除での解除、実行中の依頼の取り消し、遅れた結果・古いプレビューの拒否、ストアの1 transactionの確認）とこの節の「選んだメモがメモの作成を受ける」は、統合したtreeでそのまま両立する（`ChatMemoContextDeleteInstrumentationTest`、`ChatDeleteDuringRequestInstrumentationTest`、`ChatDeleteDuringGenerationJourneyTest`を含むtargeted 34 / 34を2回、JVM 5 / 5）。ゲート（`int5`、clean tree `8a471a6`）：unit 1631 / 0、lint 0 errors、device OK 740（727通過・13 skip・0 failures）、ブランチのゲート`mfc2`と同じテストの集合で欠け0。apkのdex / lib / assets / resourcesはS20に入っている`160b8df`のビルドと同一なので、S20への入れ直しはしていない。
