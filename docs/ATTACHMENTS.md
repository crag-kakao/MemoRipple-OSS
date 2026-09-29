# Photo Attachment Architecture

Phase 9AではMemoとDiaryだけが写真を持つ。Android Photo Pickerの複数選択を使い、広域storage permissionやpersisted URIには依存しない。選択したoriginal bytesはmain thread外でprivate temp fileへstreamし、50 MiB、40,000 px/辺、250 MPを上限としてMIME、実decode、寸法を検証する。

## Storage and ownership

- Binary: `files/attachments/blobs/<lowercase-sha256>`
- Metadata: Room `attachment_blobs`（kind stable IDは`image`）
- Ownership: `memo_photo_attachments` / `diary_photo_attachments`
- Record上限: 20枚
- 同一owner＋SHAはunique、owner間では同じblobを共有する。
- Relationの`sortOrder`は初回選択順を保持する。Phase 9Aではdrag reorder UIを追加しない。

Importはtemp copyとSHA計算、sampled platform `ImageDecoder` validation、blobへのatomic install、Room transactionの順で進む。DB insert前のprocess deathはorphan fileだけを残す。relation削除、owner cascade、restore後はDB referenceをsource of truthにGCし、同じSHAを参照する別recordのbinaryを削除しない。missing/corrupt blobは自動でrelationを消さず、UIが「写真を表示できません」を示す。

`AttachmentImageLoader`は表示前にsizeとcontent SHAを検証し、同じ長さへ破損したlocal blobもplaceholderへ落とす。検証後はImageDecoderのorientation handlingとtarget sample sizeを使い、thumbnail／viewer用bitmapをblob SHA＋target bucket keyの小さなmemory LRUへ保持する。original全画素の常時decode、Room BLOB、Base64、frame/recomposition単位のdecodeは行わない。

## Photo viewer

Phase 9CのViewerはpersisted `sortOrder`のままCompose Foundation `HorizontalPager`へ渡し、通常の1x状態では左右swipe、1xを超える状態ではpage-local panを優先する。scaleは1〜4x、double tapは2x／1x reset、TalkBack custom actionは0.5x刻みの「拡大」「縮小」と「等倍に戻す」を提供する。1.01以下をpagerへ戻せるnormal stateとして扱い、1xではtranslationを必ず0へ戻す。前後Buttonと`N / total`はgestureを使えない場合の操作経路として残す。

Pan boundsはviewportとbitmapのaspect ratioからfit-center後の実表示幅・高さを求め、拡大後にviewportを越えた分の半分だけ移動可能にする。portrait、landscape、square、letterboxを同じpure transform mathで扱い、NaN／Infinity／zero dimensionはidentityへ安全に落とす。pageを離れる、削除でcurrent itemが変わる、missing／corruptへ移る場合はtransformを1xへresetし、zoom stateはRoom、Backup、SavedStateへ保存しない。

Viewer decode targetはviewport長辺の2倍とsource長辺の小さい方を候補にし、さらにARGB bitmapが約4M pixels（約16 MiB）を超えないsample sizeを適用する。Gesture frameは`graphicsLayer`のFloat／Offsetだけを更新し、file read、SHA、ImageDecoder、Bitmap生成を行わない。cache keyはblob SHA＋target bucketを維持し、cache lookup前のsize／SHA integrity validationも省略しない。missing／corrupt pageはplaceholderのまま前後移動でき、編集可能ownerだけ既存Deleteを利用できる。

Viewerのopen、swipe、zoom、pan、closeはsession UIでありDrive dirty generationを進めない。Deleteだけが既存attachment relation mutationとしてdirtyになる。Memo ACTIVE／Archive／Trash、Diary DRAFT／CORRECTING／FINALIZED／LOCKEDの既存read-only境界、Room（本文書執筆時v15、現行v20 — `feature/outliner`のみ、mainはv19）、Backup（執筆時v10、現行formatVersion 15 — 同branchのみ、mainは14）、20枚上限は変更しない。

## Photo reorder

Phase 9BではMemoとDiaryの既存relation IDを並べた専用Sheetを使う。通常の写真stripへdrag handleを常設せず、写真が2枚以上かつownerが編集可能な場合だけ「並べ替え」を表示する。MemoはACTIVE、DiaryはDRAFT／CORRECTINGで利用でき、Archive／Trash／FINALIZED／LOCKEDはread-onlyを維持する。

Sheet内のdragはlocal draftだけを更新し、キャンセル、戻る、dismiss、保存前のprocess deathではRoomを変更しない。「完了」で現在のrelation集合と順序案を照合し、重複、欠落、余分なID、別ownerのIDを拒否したうえで、単一Room transaction内で`sortOrder`を0から連続値へ正規化する。同じ順序はDB writeを行わない。relation ID、owner ID、blob SHA、`createdAt`、ownerの`updatedAt`、binaryは変更しない。

Drag gestureを利用できないユーザー向けに各rowは「前へ移動」「後ろへ移動」のaccessibility custom actionを持つ。欠損・破損blobもplaceholderのまま並べ替え可能で、reorderを理由にrelationやblobを削除しない。実際の順序変更だけが既存`memo_photo_attachments`／`diary_photo_attachments` invalidationを通じてDrive dirty generationを進める。Backup（v10以降、現行v14でも同じ）は既存`sortOrder`をそのままround-tripし、format追加は行わない。

## Diary and other surfaces

DRAFT/CORRECTINGだけが写真を追加・削除でき、FINALIZED/LOCKEDはread-onlyで表示する。本文が空でも写真があればDiary/Memoをcontentとして保持し、photo-only Diaryを確定できる。Future Comment、User Comment、Work Comment、TTS、Overlayへ写真を渡さない。Future Revealのsource context photo stripはanimation surfaceの安全性を優先してPhase 9Aではtext-onlyのままにする。

## Backup v10

`memorripple_backup` v10はZIP containerで、`manifest.json`とSHA順の`blobs/<sha256>` raw entriesを持つ。legacy v1〜v9は従来GZIP JSONで読み取る。productionのSAF／Drive pathはbounded temp fileとstream copyを使い、backup全体をByteArrayへmaterializeしない。

Security limitsはmanifest 8 MiB、blob 50 MiB、最大10,001 entries、blob expanded total 2 GiB、container 2 GiB + 72 MiB。absolute path、`..`、backslash、duplicate/unexpected entry、missing blob、size/hash/dimension mismatch、truncated container、future v11+をRoom replacement前に拒否する。validated blobを先にcontent-addressed storeへinstallし、その後Roomをtransactional replacementするため、中断時は既存DBまたは回収可能なorphanだけが残る。

## Phase 9A.1 boundary closure

Limit判定は50 MiBと40,000 px／250 MPをinclusive boundaryとしてpure logicへ集約し、pixel積は`Long`で評価する。ImportはContentResolverを小さなstream source boundaryで包み、zero byte、random/truncated data、wrong MIME、decode failure、途中IOException、batch partial failureを実Android ImageDecoderとin-memory Roomで決定的に検証する。19＋3枚は1枚だけ追加して20枚で停止し、20＋1枚はrelation/blobを作らない。

同一owner重複、Memo間／Diary共有、relation単独削除、final reference、orphan file、連続GC、Archive／Trash／Restore／Permanent Delete／Empty Trashをreference-aware testで固定した。GCはlowercase SHA namespaceのorphanだけを回収し、non-SHA fileやdirectoryをblind deleteしない。Backup readerはstream limitsとSHAに加えてZIP central directoryの完全性を先に検証するため、local entryだけ読めるtruncated archiveもRoom変更前に拒否する。

## Memoの写真はContent Block（2026-09-24）

Memo（kind = memo）の写真は、本文と同じ順序付きのblock列の一部（`memo_content_blocks`、Room 26、Backup 20。設計と実装は`docs/MEMO_CONTENT_BLOCKS.md`）。写真1枚＝photo block 1つで、`memo_photo_attachments`の行を指す（unique、行の削除でcascade）。写真の`sortOrder`はphoto blockの順のprojectionとして保たれ、Backup検証の0..n-1・並べ替えsheet・viewerはそのまま使える。写真は本文幅・元の比率・`ContentScale.Fit`（3:4より縦長、3:1より横長だけ枠を設ける）・`shapes.small`で描き、ページには枚数や見出しを出さない。写真を追加するとcaretの位置に入る（文章の途中ならそこで分かれる）。編集中の写真はtap・長押しに反応せず、右上の丸い⋮から「フルスクリーン / 写真一覧（サムネイルの横並びと並べ替え）/ 削除」。閲覧モードのtapはviewer。並べ替えは ⋮ →「写真を並べ替え」（写真が入る枠の位置は変えず中身を入れ替える）。

Outliner・Diary・Split参照ペイン・ライフサイクル画面は従来の`PhotoAttachmentStrip`のまま。blobの保存、GC、restoreの流れは変わらない。
