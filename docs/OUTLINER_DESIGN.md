# MemoRipple 構造化アウトライナー — 設計監査と実装計画（feature/outliner）

Status: **DESIGN ONLY**（2026-09-13）。本文書は `feature/outliner` 上にだけ存在し、実装は一行も含まない。
分岐元: `main` の `2e80411`（`aa31fe1` 閲覧モードBack修正の直上）。Play向け `main` には持ち込まない。

## 0. 結論（先に）

- **アウトラインは「メモの一種」として持つ。** 正本は今までどおり `MemoEntity.body` のテキストで、
  行頭記号＋半角スペース＋2スペース段下げという既存アウトライン記法をそのまま使う。
  `OutlineNode` は**保存形式ではなく、編集画面のための派生モデル**（テキスト⇄ツリーの可逆変換）である。
  これにより流れるコメント・読み上げ・`[R n]`・行末修飾子・書き出し・Backup・Drive・検索は**一切変更なしで**
  そのまま成立し、「1ノード＝1行＝1コメント」が構造的に保たれる。
- **「アウトライナー」を第3の入口にするなら、そこに並ぶものは“通常メモと別の種類”でなければならない。**
  2026-08-29 に人間は、同じメモを記法の有無で2つのtabに分けていた旧「アウトライン」tabを壁へ統合した
  （`67a3f3d`、`docs/UX_AUDIT.md`「The two walls hold different memos now」「①メモとアウトラインは同じMemoだった」）。
  内容で振り分ける方式は再現してはならない。書き手が**作るときに選ぶ種類**（`memos.kind`）で分ける。
- **Room / Backup の変更は Phase 1 では不要。** 種類列（`kind`）は Phase 2（入口バー）で Room v20 / Backup v15、
  フォルダは Phase 4 で Room v21 / Backup v16。いずれも追加列・追加表のみで、破壊的migrationは使わない。
- Phase 1 は「既存メモ本文をノード編集する画面」だけを作る（入口バー・種類列・フォルダ・リンク実装なし）。

## 1. 現状監査

### 1.1 既存テキストアウトライン仕様（`domain/`）

| 項目 | 現在の実装 | 場所 |
|---|---|---|
| 行の形 | `[半角スペース×2n][記号][半角スペース][本文][ 行末修飾子]` | `WorkCommentSyntax.recognize` |
| 段下げ | 半角スペース2個＝1段（`INDENT_WIDTH = 2`）。`#`〜`######` はハッシュ数−1を段に加算 | 同上 |
| 記号 | 7役割 × 3セット（標準／日本語記号／絵文字）。**認識は常に全セット**、設定は挿入する字だけを決める | `OutlineSymbols`, `OutlineSymbolSelection` |
| 1単位 | 記号のある行は分割しない＝1行1コメント。散文行は再生範囲 BODY のとき文単位に切る | `WorkCommentParser.parse(scope, body)` |
| 深さの意味 | 段が深いほど小さく流れる（「記号の流れ方」） | planner / 役割乗数 |
| 編集操作 | 記号の付け外し・段上げ/段下げ・チェック切替を**生テキスト上で** | `WorkOutlineEditing`（toolbar_indent / toolbar_outdent / toolbar_task） |
| 折りたたみ | 閲覧モードで見出しが「次の同深以浅の見出しまで」を所有し畳める。状態は画面内のみ（非永続） | `BodyReading.ownedLines / visible` |
| 行末修飾子 | 行末の空白区切りトークン（← → ↑ ↓ ×N {色} ~ * ... ↑↑ ↺ …）。閲覧・読み上げでは不可視 | `CommentLineModifiers.strip` |
| `[R n]` | 本文中マーカー→リンク済みコメントが1回流れる。番号は `memo_comments.linkNo`（Room v19 / Backup v14） | `CommentLinkMarkers`, `MemoCommentRepository.assignLink` |
| 読み上げ | 記法・修飾子・装飾を剥いだ言葉だけを engine へ。`[R n]` は発火位置に使う | `SpeechContentComposer.memo` → `SpeechTextPreprocessor` |
| `[[title]]` | **既に存在する。** メモ間リンクはタイトル文字列で解決（`NoteLink.key` = 正規化キー）。id は持たない設計判断 | `domain/NoteLink.kt`, `domain/memos/MemoLinkGraph.kt` |
| バックリンク | `MemoLinkGraph.backlinks` が全メモ本文を走査して算出（キャッシュなし） | 同上 |
| 検索 | `MemoSearch` がタイトル＋本文を語単位で照合 | `domain/memos/MemoSearch.kt` |
| 壁の見え方 | `WallDisplayMode` = メモとアウトライン／メモのみ／アウトラインのみ（端末ローカル、Backup外） | `MemoOrganization.kt` |

`[[title]]` を id ではなくタイトルにした理由はコード内に明記されている:
「書き出しても生き、Backupから戻しても動き、相手のメモが存在する前にも書ける。新しく保存するものは何もない」。
**リネーム時にリンク本文を書き換える処理は現在存在しない**（タイトルを変えるとリンクは未解決になる）。

### 1.2 「メモ」と「ノート」は今なにを指すか

| | メモ | ノート |
|---|---|---|
| データ | `MemoEntity`（title, body, 日時, favorite/pin, archivedAt/trashedAt, `noteId?`, `chapterId?`, `episodeOrder`） | `NoteEntity`（title, subtitle, coverColor, coverBlobSha256?）＋ `NoteChapterEntity`（noteId, title, sortOrder） |
| 意味 | 自由に書き直す1枚の記録 | **続きもの**。エピソード＝`noteId` を持つメモ。章はエピソードの見出し |
| 一覧 | 壁（`observeStandaloneMemos` = `noteId IS NULL`）。ノートに属すメモは壁に出さない（ARCHITECTURE.md） | ノートtab（`NoteListPage`：表紙＋題、行を開くと話一覧） |
| 画面 | `EDITOR`（書く／閲覧モード） | `NOTE_DETAIL`（並べる）／`NOTE_READER`（読む・ルビ・読み上げ追従） |
| 保存 | Room `memos`。Backup DTO `MemoBackupDto` | Room `notes` / `note_chapters`（v16〜v18）。Backup v11〜v13 |
| コメント | 本文から生成（Work）＋保存コメント（User）。壁・INLINE・STAGE・OVERLAY で流れる | エピソードはメモなので同じ機構。READER はページ上に流す |
| TTS | タイトル→本文→（任意で）コメント | READER は文単位、行末修飾子は不可視 |

入口の実装: `MemoListScreen` の `HorizontalPager`（`MEMO_VIEW_MODES = [MEMO, NOTE]`）と `MemoViewModeTabs`
（左寄せの二語、24dp rail）。FAB は立っているtabの物を作る。Bottom navigation は **メモ／日記のみ**（確定事項、変更しない）。

### 1.3 旧「アウトライン」tab の履歴（必読）

| 日付 | commit | 何をしたか |
|---|---|---|
| 2026-08-28 | `250ed6b` | メモ画面を3 tab（メモ／アウトライン／ノート）に分割 |
| 2026-08-28 | `8774832` | 記法のある行を含むメモは自動的にアウトラインtabへ（内容で振り分け） |
| 2026-08-29 | `67a3f3d` | **人間の判断①**: 壁を一つに戻し、アウトラインは「壁の見え方」（`WallDisplayMode`）にした |

理由（UX_AUDIT）: 「本文に `■` を一行書くとMemoが黙って隣のtabへ移る——置き場所を決めていたのは書き手ではなく記法である」
「二つのtabが同じ一覧を見せていることの方が先に目に入」った。同じ文書で「書くときに種別を選ばせる案（Room 16→17）も
あったが、選ばせずに済むならその方がよい」とも記録されている。

今回の要求はこれと矛盾しない。**旧tabは“同じメモの別の見え方”だったのに対し、今回のアウトライナーは“別の種類の文書”を
置く場所**である。したがって成立条件は一つ: **入口に並ぶものは、作成時に「アウトライン」として作られたものだけ**
（内容検出をしない）。これが §3 の `kind` 列の根拠であり、UX_AUDIT に残る歪み「新しいメモは必ずメモのtabに現れる」も
同時に解ける（作った場所に出る）。

## 2. 既存仕様との対応表（新アウトライナーが守ること）

| 既存契約 | 新アウトライナーでの扱い |
|---|---|
| 行頭記号＋半角スペース | ノードの `role` と、その行が**元々使っていた字**を保持して書き戻す（セットを勝手に統一しない） |
| 2スペース＝1段 | `node.depth` ⇄ 先頭スペース数。段上げ/段下げは `WorkOutlineEditing.indent/outdent` と同じ結果の文字列を生む |
| 1行＝1コメント | ノードの本文に改行を入れない。Enter は必ず新ノード（新しい行） |
| 行末修飾子 | `node.suffix` として原文どおり保持し、本文編集で崩さない。ノード用の流れ方UIは Phase 5 以降 |
| `[R n]` | 本文中の文字列としてそのまま保持。番号付与・削除の契約（`MemoCommentRepository`）は不変 |
| `#` 見出し | 読み込みは受け付け、書き戻しも原文維持（`■` へ正規化しない。書き出しの正規化は既存 Markdown 出力の責務） |
| 散文行（記号なし） | `role = null` のノードとして保持。アウトライン文書で Enter で作る新行は書き手の選択セットの「項目」を付ける |
| 空行 | 閲覧モードと同じく「空のノード」ではなく**ノード間の空気**として保持（`BodyReading` の扱いに揃える） |
| 閲覧モード折りたたみ | 見出しの所有規則をそのまま使う。項目ノードの折りたたみは「自分より深い直後の行」を所有（Workflowy 規則） |
| 読み上げ・再生 | 保存形式が同じテキストなので変更なし。ズーム中の再生は「表示中の部分木だけ」を候補に（Phase 3 で判断） |
| `[[title]]` | 既存のタイトル解決を使う。id 化は §4.5 の人間判断待ち |

**往復不変量（Phase 1 の単体テストで固定する）**

```text
serialize(parse(text)) == text            // 整形済みのアウトライン文書について文字列一致
parse(serialize(doc))   == doc            // 木の同一性
parser.parseOutline(serialize(doc)).size == doc.nodes.count { it.role != null }   // 1ノード=1コメント
```

## 3. 推奨データモデル

### 3.1 派生モデル（Phase 1、永続化しない）

```text
OutlineDocument
  nodes: List<OutlineNode>        // 木（roots）
  airBefore: Map<NodeKey, Int>    // そのノードの直前にあった空行数（往復用）

OutlineNode
  key: NodeKey                    // セッション内で安定な識別子（作成順の連番。行番号ではない）
  role: OutlineSymbolRole?        // null = 記号なしの散文行
  glyph: String                   // 原文が使っていた記号（"-", "・", "☐" …）。書き戻しに使う
  depth: Int
  text: String                    // 装飾・[R n] を含む生の本文（1行、改行なし）
  suffix: String                  // 行末修飾子の原文（"" が普通）
  collapsed: Boolean              // 画面状態。Phase 1 はメモリ内のみ
  children: List<OutlineNode>
```

保存は `MemoRepository.save(title, body = serialize(doc))` を既存 600ms debounce で呼ぶだけ。
**Room・Backup・DataStore・権限・依存に変更なし。**

### 3.2 種類列（Phase 2、入口バーと同時）

```text
memos.kind TEXT NOT NULL DEFAULT 'memo'    -- 'memo' | 'outline'   （文字列 id。ordinal は使わない）
INDEX memos(kind)
```

- Room **19 → 20**: `ALTER TABLE memos ADD COLUMN kind TEXT NOT NULL DEFAULT 'memo'` ＋ index。既存行は全て `memo`。
- Backup **formatVersion 14 → 15**: `MemoBackupDto.kind: String = "memo"`。読み込みは `formatVersion >= 15` で
  ゲート、未満は `"memo"`（`linkNo` と同じ書き方）。Restore 互換 **v1〜v15**。不正値は拒否（既存方針）。
- `kind` は**作成時に決まり、画面からは変えない**（Phase 2 の判断。変えられるようにするなら「アウトライナーへ移す」を
  明示操作にし、内容検出はしない）。
- 壁の問い合わせ `observeStandaloneMemos` は `kind = 'memo'` を加え、アウトライナー一覧は `kind = 'outline'`。
  コメント・タグ・写真・ゴミ箱・アーカイブ・検索・書き出し・Drive は列を無視して動く（メモの一種なので）。

### 3.3 フォルダ（Phase 4）

フォルダ階層（置き場所）とノード階層（1文書の中身）は**別物**として設計する。ノートは「続きもの」であって
フォルダではないので、**ノートを流用しない**。

```text
folders(id PK, parentFolderId NULL, name, sortOrder, createdAt, updatedAt)     -- 新表
memos.folderId INTEGER NULL                                                     -- 追加列（FKなし。memos の再作成は禁止：ARCHITECTURE.md）
```

Room **20 → 21**、Backup **15 → 16**（`FolderBackupDto` 追加、`MemoBackupDto.folderId?`）。
削除は「中身を親へ戻す」を既定にし、カスケード削除はしない。まずアウトライン文書だけがフォルダを持つ。
通常メモへ広げるかは人間判断。

### 3.4 `[[ノート名]]` 内部リンク（Phase 5、人間判断あり）

現状は**タイトル解決**が設計判断として明記済み。id 化の要望とは緊張関係にあるので、案を並べる:

| 案 | 本文の形 | rename | 書き出し/Backup | 判定 |
|---|---|---|---|---|
| A. 現状維持 | `[[title]]` | 壊れる（未解決表示） | そのまま | 実装済み。要望を満たさない |
| B. **タイトル本文＋rename伝播**（推奨） | `[[title]]` | 相手のタイトル変更時に、参照元本文の `[[old]]` を `[[new]]` へ書き換える（確認ダイアログ付き、一括） | そのまま | 本文が正本のまま id 相当の安定性。Obsidian 方式 |
| C. 本文に id を埋める | `[[title\|m123]]` 等 | 壊れない | 書き出しで id が漏れる／読み上げ・閲覧で剥ぐ処理が要る | 本文の可読性と「textで持つ」原則を損なう |
| D. リンク表を正本に | id のみ保存 | 壊れない | Backup v+1、表示名は逆引き | 「相手が存在する前に書ける」を失う |

**推奨は B**、必要なら B＋解決キャッシュ表（`memo_links(sourceMemoId, targetMemoId, titleKey)`、保存時に再構築）。
キャッシュはバックリンク性能のためだけで、正本は本文。未作成ノート（未解決リンク）はタップで「その名前で作る」導線。
削除時は参照元をそのまま残し未解決表示（既存挙動）。

## 4. 責務分割

```text
UI 入口（MemoListScreen のアウトライナー page）   … kind='outline' の一覧・新規・最近・フォルダ表示
    ↓ navigate(Routes.outliner(memoId))
OutlinerScreen（Compose）                          … ノード描画、Enter/段上げ/段下げ/折りたたみ/ズーム、戻る
    ↓ event / ↑ OutlinerUiState(document, zoomRoot, selection)
OutlinerViewModel                                  … OutlineDocument の編集操作（純関数を呼ぶ）、autosave、
                                                       既存 MemoEditorViewModel と同じ speech/playback 連携は Phase 3
    ↓ save(body = OutlineText.serialize(doc))
MemoRepository / Room（変更なし）
```

- `domain/outline/OutlineText.kt`（parse / serialize、純関数、単体テスト）
- `domain/outline/OutlineEditing.kt`（insertAfter / indent / outdent / moveUp / moveDown / fold、木の純関数）
- 既存 `WorkOutlineEditing`（文字列上の操作）は残す。二つの実装が同じ結果を生むことを**テストで**固定する。
- 入口 UI は `kind` と `Routes` しか知らない。`OutlineNode` や DB を直接見ない。

## 5. UI 入口案

### 5.1 配置

```text
検索 pill ──────────────────────  ⋮
メモ   アウトライナー   ノート        ← MemoViewModeTabs（三語、左寄せ、同じ 24dp rail）
────────────────────────────────
（HorizontalPager: MEMO | OUTLINER | NOTE）
                                    (+)  ← FAB は立っている page の物を作る
```

- `MEMO_VIEW_MODES = [MEMO, OUTLINER, NOTE]` にする。**メモとノートの間に置ける**（pager は N page、tab は
  ラベル幅で並ぶ実装なので構造変更は不要。三語で rail に収まるかは実機で確認）。
- Bottom navigation は触らない（メモ／日記のみ、確定）。新しい route は編集画面の一つだけ
  （`outliner/{memoId}`）。一覧は page なので route は増えない。
- 1タップで入れる（tab）。通常メモとの違いは**tab の語と、一覧の行の形**（先頭数ノードの箇条書きプレビュー）で示す。
- 戻る: 編集画面 → 一覧 page（pager は立っていた page を保つ）。ズーム中の戻るは「ズームを一段戻す」を先に消費し、
  その後に画面を出る。いずれも `popBackStackFrom(entry)` 経由（`aa31fe1` の契約）。

### 5.2 一覧 page の中身（最小）

- 上: 「最近」＝ `updatedAt` 降順の先頭数件（別セクションにせず、単に並び順の既定にするのが最小）。
- フォルダ（Phase 4 まで無し）: 導入時は折りたたみ見出し行として一覧の中に描き、別画面にしない。
- 行: 題（無題なら先頭ノード）＋先頭2〜3ノードのプレビュー＋更新日。長押しで壁と同じ操作シート（固定・アーカイブ・ゴミ箱）。
- 空状態: 「アウトラインがありません。＋で作ると、1行が1つのコメントになります。」（文言は要調整）
- 新規: FAB（+）。作った物はこの page に出る（`kind='outline'`）。
- 遷移候補図（人間提示のもの）は上記で満たすが、「アウトライン一覧」を**別画面にしない**のが差分（page で済む）。

### 5.3 名称

- 第一候補 **「アウトライナー」を推す。** 理由: 「アウトライン」は既に (a) 壁の見え方（メモとアウトライン／アウトラインのみ）、
  (b) 記法（設定 > アウトライン記号）、(c) 再生内容（アウトライン／コメント）を指す語で、同じ語を「場所」にも使うと
  2026-08-29 に統合した混同が戻る。「アウトライナー」は道具＝場所の名で、tab の隣の「ノート」と同じ重さで読める。
- 一覧の中の一つ一つは「アウトライン」と呼ぶ（tab はアウトライナー、物はアウトライン）。名称の変更は行わない。

## 6. DB / migration 方針まとめ

| Phase | Room | Backup | Restore | 破壊的 | 既存データ |
|---|---|---|---|---|---|
| 1 | 19（変更なし） | 14 | v1–v14 | — | 影響なし |
| 2 | 20（`memos.kind` 追加） | 15 | v1–v15 | なし（ADD COLUMN DEFAULT） | 全て `memo`。壁の見え方は不変 |
| 4 | 21（`folders` 表、`memos.folderId`） | 16 | v1–v16 | なし | 影響なし |
| 5 | 21 or 22（任意の `memo_links` キャッシュ） | 変更なし（キャッシュは Backup 外） | — | なし | 保存時に再構築 |

- `fallbackToDestructiveMigration` は使わない。各 migration は `AppDatabaseMigrationTest` に往路テストを足す。
- ロールバック: Room は downgrade を持たないので、**手動 Backup を取ってから更新**が運用上の rollback（既存と同じ）。
  `kind` を持たない旧アプリで v15 Backup を読むと「未来の版」として拒否される（既存の v15+ 拒否規則どおり）。
- 既存メモを `OutlineNode` に強制変換することは**しない**。変換は無い（同じテキストが正本）。

## 7. コメントリンク・記号セット・行末修飾子の branch 方針

| 項目 | 現状 | main か feature か |
|---|---|---|
| `[R n]` コメントリンク | 実装済み・出荷済み（Room v19 / Backup v14） | 変更は **main 不可**（freeze）。不具合のみ main |
| アウトライン記号セット | 実装済み（3セット、役割別選択、チップ表示） | 同上 |
| 行末修飾子 | 実装済み（`CommentLineModifiers`） | 同上 |
| ノード編集 UI からこれらを付ける操作 | 未実装 | **feature/outliner**（Phase 5） |

つまり三つとも「現行テキスト仕様だけで完結する物」であり既に main にある。新しく増えるのは
**アウトライナー画面からそれらを扱う UI** だけで、それは feature 側。

## 8. フェーズ分割

| Phase | 内容 | Room/Backup | 入口 |
|---|---|---|---|
| **1（次回）** | `OutlineText` parse/serialize＋往復テスト、`OutlineEditing`（insert/indent/outdent/fold）、`OutlinerScreen` 基本表示・Enter・段上げ/段下げ・折りたたみ（メモリ内）、autosave | なし | 開発用のみ: 編集画面 ⋮「アウトライナーで開く」（feature 限定） |
| 2 | `memos.kind`、アウトライナー page（一覧・新規・空状態）、`Routes.OUTLINER` | 20 / 15 | tab 公開 |
| 3 | 折りたたみ状態の端末ローカル永続（DataStore、Backup 外）、ズーム、同階層並べ替え・別階層移動（a11y の移動アクション必須）、再生・読み上げ連携（表示中の部分木） | なし | — |
| 4 | フォルダ（表＋列、一覧内の折りたたみ見出し） | 21 / 16 | — |
| 5 | `[[title]]` rename 伝播、未解決リンクから作成、バックリンク表示、（任意）解決キャッシュ表、ノードからの行末修飾子 UI | — / — | — |
| 6 | 全文検索の kind フィルタ、書き出し（既存 Markdown/DOCX/PDF はそのまま動く） | なし | — |

Phase 1 を「画面だけ」にする理由: schema を触らずに、往復不変量とコメント同一性を実機で確かめられる。ここで崩れる
ならデータモデルを触る前に直せる。

## 9. テスト戦略

- 単体（`app/src/test`）: 往復不変量（§2）、indent/outdent の境界（列0、深さ差の飛び）、fold の可視集合、
  `WorkOutlineEditing` との一致、修飾子・`[R n]`・`#` 見出し・混在セットの保存性、空行の往復。
- 計装（emulator）: OutlinerScreen の Enter／段上げ／折りたたみ／戻る（`MemoViewerBackInstrumentationTest` と同じ
  凍結クロック手法）、ズーム中の戻る、保存後に閲覧モードで同じ本文が読めること、コメント再生で行数が一致すること。
- Phase 2 以降: `AppDatabaseMigrationTest` に 19→20（→21）、`BackupCodecTest` に v15/v16 の往復と v14 の読み込み。
- golden は追加しない（維持理由が生まれたときだけ）。

## 10. 今回やっていないこと（明記）

`OutlineNode` Entity 追加／UI 追加／Enter・段上げ・段下げ・折りたたみ／`[[リンク]]`・backlink／Folder／Room・Backup 変更
／`Routes` 追加／既存メモの変換。**すべて未実装。** main には本文書もない。

## 10.1 Phase 1 実装状況（2026-09-13 追記）

**Phase 1 は本文書 §8 の範囲どおり完了**（詳細は HANDOFF §16.5.2）。`domain/outline/OutlineText.kt`（parse / serialize、
往復不変量3つを 24 fixture で固定）、`domain/outline/OutlineEditing.kt`（updateText / insertAfter / split / indent / outdent /
fold を純関数で）、`ui/outline/OutlinerScreen.kt`（既存 `MemoEditorViewModel` の autosave に serialize して書き戻す）。
入口は編集画面 ⋮「アウトライナーで開く（開発用）」（debug build のみ）。§3.1 の派生モデルどおり保存はしない。
§3.2 以降（`memos.kind`・フォルダ・リンク）は未着手のまま。設計からの差分: `OutlineDocument` は木ではなく**行の列**
（`entries`）で持ち、木の関係は `depth` から都度読む — 本文と1対1で往復するのに最も素直だったため。`airBefore` は
`OutlineBlank` 行として保持することで不要になった。

## 10.2 Phase 2A 実装状況（2026-09-13 追記）

**完了**（記録は HANDOFF §16.5.3）: Backspace（空行削除・前行への結合、子孫は結合先の下へ追従）、部分木ごとの上下移動、
折りたたみの端末ローカル永続（`outliner_folds`、内容キー = depth+text+出現順の FNV-1a）、ノード単位ズーム（Breadcrumb、
Back はズーム→画面の順）、100/500/1000 行の計測（1000 行で serialize 0.19ms、最適化不要）。§3.1 の派生モデル・保存なしの
方針は不変。§3.2 以降（`memos.kind`・フォルダ・リンク）は引き続き未着手。

**S20 実機確認（2026-09-14 追記、記録は HANDOFF §16.5.4）**: 使い捨ての別 applicationId で実機に載せ、Gboard の
Enter / 削除 / 行頭結合、ダークテーマ、a11y ラベル、実 TTS エンジン経由の読み上げを確認。1000 行のスクロールが
実機で破綻していた（全行が text field）ため **編集中の1行だけを field にする** 設計へ変更（median 81ms → 6ms）。
編集画面から開いたときは編集画面の `MemoEditorViewModel` を共有する（戻った画面が古い本文を見せていた）。
残る手動確認項目は HANDOFF §16.5.4 の「未確認」。

## 10.3 Phase 2B 実装状況（2026-09-14 追記）

**完了**（記録は HANDOFF §16.5.5）: `memos.kind TEXT NOT NULL DEFAULT 'memo'`（Room 19→20、ADD COLUMN＋index、
破壊的 migration なし、既存行は全て `memo`）、Backup 14→15（`MemoBackupDto.kind`、読み込みは `formatVersion >= 15` で
ゲート、v1〜v14 は全て `memo` として復元）、ホームの **メモ／アウトライナー／ノート**（§5.1 どおり同じ rail と pager、
Bottom navigation は不変）、アウトライナー page は `kind = 'outline'` のみ、FAB は行を先に作ってから `outliner/{memoId}`
へ（編集画面を経由しない）、編集画面 ⋮ の開発用入口は撤去。§3.2 の「`kind` は作成時に決まり画面からは変えない」は
そのまま実装した。§3.3（フォルダ）・§3.4（`[[title]]`）は未着手。§5.2 の「行の形」は題＋先頭3行のプレビュー＋タグ
（更新日は出していない）。

## 10.4 Phase 2C 実装状況（2026-09-14 追記）

**完了**（記録は HANDOFF §16.5.6）: Memo を id で開く経路を `MemoNavigator`（`ui/MemoRippleApp.kt`）と
`domain/memos/MemoDestination` の一箇所に集約 — memo → Editor、outline → OutlinerScreen を、ホーム・アーカイブ・
ゴミ箱復元後・`[[title]]` チップ・閲覧モードのリンク・backlink・分割表示の「こちらを編集」・ノートの話の編集の
全経路で統一（`[[title]]` の解決方法・parser・MemoLinkGraph は不変）。zoom は `rememberSaveable` に fold と同じ
内容キー（depth+text+出現順）で持ち、再生成後に再特定、見つからなければ root へ（Room/本文/Backup/DataStore には
書かない）。Room 20 / Backup 15 は不変。Samsung キーボード向けの結合ボタンは提案のみ（HANDOFF 参照）。

## 10.5 公開前 S20 最終 QA（2026-09-14 追記）

記録は HANDOFF §16.5.7。製品コードの変更なし。3 タブ・新規作成・Gboard／Samsung Keyboard の編集（Samsung Keyboard も
Backspace の key event を送るため §16.5.6 の結合ボタン案は不要）・`[[title]]`／backlink → アウトライナー・zoom 中回転・
fold 永続・1000 行性能（退行なし）・TTS pipeline を実機で確認し、手動 Backup（v14）を確認した上で本番データを Room 20 へ
通常更新で migration（メモ不変、`kind = memo`）、新規アウトライン作成と Backup 15 を確認。残る未確認は HANDOFF 参照。
**設計上の残り**: `kind = outline` には読み上げ・コメント再生の入口がまだ無い（§4 の Phase 3）。

## 11. 次回の開始地点

1. `git switch feature/outliner` → HEAD がこの文書のコミットであること。
2. `domain/outline/OutlineText.kt` と単体テストから着手（§2 の三つの不変量を先に赤くする）。
3. その後 `OutlinerScreen` 基本表示。入口は編集画面 ⋮ の開発用項目に限る。
4. Phase 1 完了時点で人間レビュー → Phase 2（`kind` 列と tab 公開）の可否を判断。

## 10.6 Folder Phase 実装状況（2026-09-14 追記、`feature/folders`）

§3.3 のとおり **Room 20→21 / Backup 15→16** で `folders(id, name, parentFolderId, createdAt, updatedAt)` と
`memos.folderId` を追加（FK なし、カスケード削除なし、既存文書は全て root = `null`）。記録は HANDOFF §16.6。
フォルダ階層は文書の**置き場所**、アウトラインは本文の**中身**という区別を保ち、`ui/outline/` は無変更。対象は memo と
outline の共通ツリー（kind 別に複製しない）。ノート・日記・未来日記は対象外。削除は「中身を親へ昇格」、循環禁止は
domain（`FolderTree`）と repository transaction の両方で保証、Backup は「所属先不明の文書は root へ」「壊れた木は拒否」。
検索は従来どおり kind 全体（フォルダ限定ではない）。
