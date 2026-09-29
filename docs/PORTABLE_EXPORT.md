# Portable Export（読める形式で書き出す）

MemoRipple Portable Export / Format 1。Backup（`memorripple_backup`）とは責務が異なる別機能である。

| | Backup | Portable Export |
|---|---|---|
| 目的 | MemoRippleへの完全復元 | アプリなしで読む・長期保存 |
| 形式 | 独自container（v10 ZIP / legacy GZIP JSON） | 一般的なZIP＋Markdown＋元写真bytes |
| Import | v1〜v13 Restore（完全置換） | **新規メモ追加のみ**（後述。Restore契約なし） |
| identifier / version | `memorripple_backup` / formatVersion 15（`feature/outliner`；mainは14） | READMEに「Format 1」を記すのみ |

Portable ExportはBackup identifier・formatVersion・Restore互換・Drive transport・dirty generationへ一切影響しない。読み取りとfile書き出しだけを行い、Roomを変更しない。

## ZIP directory contract

```
MemoRipple-Export-YYYYMMDD-HHmm.zip
└── MemoRipple-Export/
    ├── README.md            … 内容・非暗号化・非Backup・EXIF・Future privacyの明示
    ├── INDEX.md             … 全記録への相対リンク（更新新しい順／日付降順）
    ├── EXPORT_WARNINGS.md   … 問題があった場合のみ。一般的理由だけを記す
    ├── memos/active/YYYYMMDD-title-shortid/{memo.md, photos/photo-NN.ext}
    ├── memos/archived/…
    ├── diaries/YYYY/YYYY-MM-DD/{diary.md, photos/…}
    ├── notes/title-shortid/{README.md, episodes/NN-title.md, photos/{cover.ext, NN-photo-MM.ext}}
    └── trash/…              … 「ゴミ箱のメモも含める」ON時のみ（default OFF）
```

- entry pathは`PortableExportNaming.isSafeEntryPath`で全件検証（相対のみ・`..`なし・`\`なし・空segmentなし・重複なし）。
- title由来segmentはOS予約文字を`_`化・40字上限・shortId(recordのid)で衝突回避。
- 順序は決定的: メモ/ノートは`updatedAt DESC, id DESC`、日記は日付降順、写真は`sortOrder ASC, id ASC`。

## Markdown contract

- 本文変換は既存`MemoMarkdownExport.markdownBody`をそのまま使用（`■ `見出し→hash、checkbox・inline装飾・`[[リンク]]`・Work Commentマーカーは原文維持。要約・AI変換なし）。
- memo.md: タイトル / 作成・更新 / お気に入り / ピン留め / タグ / 本文 / `## 写真`（相対リンク）/ `## コメント`（playbackOrder順）。
- User Comment表現はDefaultとの差分だけを添える（例「色: ピンク」）。内部enum名・ID・lane等は出力しない。Work Commentは本文由来のためコメント一覧へ重複出力しない。
- diary.md: 日付見出し / 状態 / 本文 / 写真 / `## 開封済みの未来コメント`。
- note: README.md（メタデータ＋章見出し付き目次）＋`episodes/NN-title.md`（各話が1ファイル、章名はメタデータ行）。表紙は`photos/cover.ext`。

## Photo original-byte handling

- private Attachment Blob（`files/attachments/blobs/<sha>`）からstream copyのみ。decode・再圧縮・Base64・data URI・rotation書き換え・network uploadは行わない。ZIP containerのDEFLATEは可逆で、写真bytes自体は不変。
- extensionは記録済みMIMEから決定（jpeg/png/webp/gif/heic/heif/bmp、不明は`.img`）。
- 同一blobを複数記録が参照する場合は各記録の`photos/`へそれぞれcopyする（フォルダ単位で完結させるため）。
- **EXIF**: 既定は元bytes維持で、撮影日時・機種・位置情報が残り得ることを確認Sheet・READMEに明示。任意で位置情報などを取り除ける（後述のオプション）。

## Missing / corrupt photo

blobの存在とサイズ一致を検証し、不一致はその写真だけをskipして該当Markdownへ「> 写真Nは読み込めなかったため、書き出されませんでした。」を記し、ZIP rootへ`EXPORT_WARNINGS.md`（表示名＋写真番号＋一般理由のみ。path・URI・stack trace・DB内部は記載しない）を作る。書き出し全体は継続する。

## Future Comment privacy

書き出すのは`revealedAt != null && firstPresentedAt != null`（ユーザーが本文提示を完了済み）のみ — 閲覧UIと同じ契約。SEALED / DELIVERED / firstPresentedAt未完了は本文・色・サイズ・強調・motion等すべて出力せず、件数すら残さない。READMEには件数を含まない一般説明だけを記す。

## SAF / streaming / atomicity

- `ACTION_CREATE_DOCUMENT`（`application/zip`）。追加Storage Permissionなし。ユーザーが選んだURIへのみ書く。
- `PortableExportEngine`が`Dispatchers.IO`でsnapshot→plan→cacheDirの一時fileへ完全なZIPをstream生成→既存`SafBackupFileStore.write(uri, file)`でcopy。ZIP全体・写真をmemoryへ載せない（64KiB buffer）。
- 一時fileはsuccess/failure/cancellationのfinallyで削除。SAFへのcopy自体が中断された場合のみ不完全なdocumentが残り得る（SAF providerにatomic renameがないため）。staging成功前にSAFへは一切書かないので「途中失敗が完全なZIPに見える」ことはない。process death中断時はcacheDir内のtemp（OS回収対象）だけが残り得る。
- Progressは件数ベース（文書1＋写真1）。二重実行はViewModelのJobで防止。CancelはcoroutineのensureActiveで各記録・写真境界に効く。
- Foreground Service / WorkManager / Notificationは使わない（foreground中の明示操作）。

## 写真の位置情報などを取り除く（EXIF削除オプション）

Export確認Sheetのトグル（default OFF）。ONの場合、JPEGはplatform `android.media.ExifInterface`で
GPS・撮影日時・機種・作者系タグをnull化してから書き出す（依存追加なし・画像データ非変更＝画質不変・
orientation維持）。書き換えはblobのprivate copyに対して行い、管理下のblob自体は変更しない。
JPEG以外（PNG/WebP/HEIC等）はplatformが書き換えを支えないため元のまま書き出し、Sheetの文言で明示する。
strip失敗時は元bytesのまま書き出し、`EXPORT_WARNINGS.md`へ一般理由だけを記す。

## 書き出したZIPを取り込む（Portable Import）

設定 → 手動バックアップ →「書き出したZIPを取り込む」。**Restoreではない**：Format 1の
`memos/active|archived/*/memo.md` だけを読み、**新規ACTIVEメモとして追加**する。既存データの置換・削除は
一切行わない。`trash/`・日記・ノート・コメント・お気に入り・日付・IDは対象外（それらはBackupの責務）。

- 解析は `PortableMemoParser`（rendererの逆写像。メタデータ行・写真/コメント節は本文に入れない。
  hash見出しはエディタが直接読めるためそのまま）。
- タグは名前で再接続（正規化キー一致、なければ新規作成）。
- 写真はZIPから上限付きtempへ展開し、**Photo Pickerと同一のimport pipeline**
  （MIME検証・decode検証・SHA・atomic install・20枚上限）を `AttachmentContentSource` 差し替えで再利用。
  検証に落ちた写真はskipして件数を報告する。
- 入力は既存 `SafBackupFileStore.read` のサイズ上限付きstagingを通し、memo.mdは1MiB上限。
  取り込みは追加のみのため、中止しても途中まで取り込んだメモが残るだけで既存データは無傷。

## HTML（ブラウザで読む）

ZIPには各Markdownの隣に自己完結のHTML（`memo.html`等）と、ルートに`index.html`を同梱する。
script・外部アセットなしのインラインstyleのみで、展開して`index.html`を開けばブラウザで写真込みで読める。
本文はエスケープした上で見出し・リスト・checkbox・引用だけを整形し、書き換えは行わない。

## PDF（1つのファイル）

Export確認Sheetの「形式」でZIP/PDFを選べる。PDFはplatformの`PdfDocument`＋`StaticLayout`で描画
（依存追加なし・日本語折返しはplatform任せ）。A4で記録が順に流れ、写真は1枚ずつ下限sample decode→
幅fit描画→recycle。**画像は描き直すためEXIF等のメタデータはPDFに入らない**（Sheetにも明示、strip
トグルはPDF選択時は非表示）。ZIPと同じくcache stagingを経てSAFへcopyし、キャンセル/失敗時はstagingを削除。

## 読める形式の自動書き出し（定期実行）

WorkManager・Service・Notificationを使わない**起動時トリガー**方式：アプリを開いたとき
（foreground遷移時に1回）、有効・保存先フォルダあり・間隔経過（毎日/毎週、`PortableAutoExportPolicy`）
なら、`OPEN_DOCUMENT_TREE`で永続許可されたフォルダへ**新しいZIP**（trash除外・写真そのまま）を1つ作成する。
既存ファイルの上書き・削除は行わない（古いファイルの整理はユーザーに委ねる旨をUIに明示しない代わり、
新規作成のみの契約とする）。実行結果（成功/失敗＋時刻）は設定の行に表示。

- 設定キーはすべてdevice-local（enabled / tree URI / interval / lastRun / lastResult）で、
  Backupへ入れずreset/restoreでも触らない。
- フォルダ変更時は旧URIの永続許可をreleaseする。二重実行はAtomicBooleanで防止。
- 時計が巻き戻った場合は「期限到来」として扱い、静かに止まり続けることを避ける。

## 将来対応時の注意

- **Import拡張時**: Format 1はID・コメントanimation・設定・Drive状態・Trash lifecycleを持たない。現行Importは「新規メモとして取り込む」だけであり、今後もそれ以上（復元・置換・同期）を約束しないこと。ファイル名からのメタデータ復元に依存しない（titleは切詰め・置換済み）。
- **暗号化対応時**: ZIP標準暗号は弱い。導入するならage/passphrase等の外側encryptを別formatとして扱い、「暗号化されない」現行文言と互換性判定を同時に更新すること。
