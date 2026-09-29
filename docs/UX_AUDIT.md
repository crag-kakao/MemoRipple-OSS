# MemoRipple UX Audit — Phase 6F

Compose UIをMemo、Diary、Future Comment、Settings、Backup、Drive、Overlayの順に横断監査した記録。
Phase 6AではNavigationやdomain stateを変更せず、共通ルールと局所的な不統一だけを修正した。

## Phase 9C — Photo viewer experience

- Viewerは写真を主役にするdark neutral Dialogを維持し、常時chromeはClose、`N / total`、編集可能時のDelete、前後Buttonだけに限定した。system barsを隠すimmersive modeやdot indicatorは追加しない。
- 1xのsingle-finger horizontal swipeはpage移動、pinchまたは1x超のsingle-finger gestureは同一pageのzoom／panとして扱う。zoom reset後はPagerへ自然に戻り、pageを離れたtransformは保持しない。
- Zoomは1〜4x、double tap 2x、Accessibilityは0.5x stepの拡大／縮小／等倍custom actionと倍率state descriptionを持つ。TalkBackの左右swipeに依存せず既存前後Buttonで移動できる。
- fit-center実表示boundsでpanをclampし、missing／corrupt pageでも前後移動とCloseを失わない。Delete後はnext/current indexへclampし、最後の1枚なら既存Viewerを閉じる。
- viewport長辺×2のdecode qualityと約4M-pixel上限を併用し、gesture frameでdecode、hash、bitmap allocationを行わない。Viewer interactionはRoom／Backup／Drive dirtyへ保存しない。

## Phase 9B — Photo reorder

- 通常の写真stripは閲覧と追加を主役に保ち、写真が2枚以上の編集可能ownerにだけ「並べ替え」を出す。drag handleは専用Sheet内に限定した。
- Sheetはthumbnail、現在位置、48dpのdrag targetを持ち、移動中は小さなelevation／scaleだけでfeedbackする。caption、cover、gallery化は追加しない。
- TalkBackではrowを「写真 N / 合計」と読み、「前へ移動」「後ろへ移動」のcustom actionだけで同じ並べ替えを完了できる。先頭／末尾には不可能なactionを公開しない。
- Sheetはlocal draft方式で、キャンセルとdismissは永続化しない。保存失敗時はpersisted orderへ戻り、「写真の並べ替えを保存できませんでした」とだけ伝える。
- MemoのArchive／Trash、DiaryのFINALIZED／LOCKEDでは並べ替えを提示しない。Photo reorderは本文、comment、calendar、TTS、Overlayの情報密度を増やさない。

## Screen inventory

| Screen / UI | Purpose | Primary action | Secondary action | Navigation / Top bar | Empty / Error / Loading | Confirmation | Touch / Accessibility | Phase 6A finding |
|---|---|---|---|---|---|---|---|---|
| Memo List | Memo検索・整理・一覧 | 新しいメモ、Favorite | Filter、Pin、Sort、削除、Settings | Memo title、Sort、Settings、2-tab nav | 作成/Search/Filter 0件を分離 | Delete | Star/overflow/filter/sort 48dp、stable id | Pinを静かに先頭group化、整理操作を段階表示 |
| Memo Editor | writing-first編集 | 本文入力、Favorite | Pin、Comment、playback、TTS | Backはautosave経路、Memo title | Loading、Snackbar | Memo delete | Star toggle、overflow、helper 48dp | 整理属性をautosave timestampから分離 |
| Memo Archive | 長期保管Memoの閲覧・検索 | Memoを開く／一覧へ戻す | ゴミ箱へ移動 | Memo overflow、Back | Archive/Search 0件 | 不要 | stable id、明示Action | 通常一覧から分離しつつ既存Editorを再利用 |
| Memo Trash | 削除候補の確認 | 復元 | 完全削除、空にする | Memo overflow、Back | Trash/Search 0件 | 完全削除／空にする | read-only Card、明示Action | 元のACTIVE/ARCHIVEDへ復元 |
| Tag Picker | Memoへの複数Tag付与 | Tag選択 | 検索、新規作成、解除 | EditorのBottom Sheet | 0件/検索0件 | 不要 | row/chip 48dp、正規化検索 | 本文編集やtimestampから独立 |
| Tag Management | Tag辞書の管理 | 新規作成 | rename、delete | Memo List overflow、Back | 説明付きEmpty、Snackbar | delete consequence | Tag名を含む操作説明、48dp | 使用Memo件数をReactive表示 |
| Comment Sheet | Comment追加・順序 | 追加 | preset、reorder、delete | Sheet header/close | 説明付きEmpty | delete/reset | drag 48dp、custom actions | 「入力候補」へcopy整理、狭い高さに適応 |
| Playback Controls | Inline/Stage/Overlay開始 | Play/Pause/Resume | Stop、mode/settings | Editor内 | disabled reasonはSetupに表示 | Overlay置換 | Flow layout、Icon description | Large text時の横溢れを回避 |
| Overlay Setup | Session option選択 | 再生 | content/region/density | scrollable Sheet | 0件/120秒超理由 | active session置換 | selectable chip/tag | shared headingsとPreview hierarchyを適用 |
| Global Overlay Banner | active session把握 | 停止 | remaining表示 | App root、全route | Service errorはSnackbar | 不要 | merged description、明確なStop | 共通Info Banner化 |
| Diary List | 今日と過去の日記 | 今日の日記を書く | past entry、Settings | Diary title、2-tab nav | 今日/過去Empty | 不要 | Cards/button/stable id | Section/Empty/Statusを統一 |
| Diary Calendar | 月の中の記録を見渡す | 日付選択、既存日記を開く | 前月/翌月/今日、LIST切替 | Diary root内、既存route再利用 | 選択日のDiary有無をSummary表示 | 不要 | 42 cell、48dp相当、統合semantics | Session mode、locale週開始、未来日disabled |
| Past Today | 過去の同じ月日の記録と再会 | 日記を読む | なし | Diary root内、既存route再利用 | 0件ならSection非表示 | 不要 | Card action、stable id | MonthDay完全一致、静かなRecall |
| Diary Editor | state machineに沿う記録 | 確定/修正 | Future、TTS | dateをScreen identity、Back autosave | Loading、Snackbar | finalize/correction | action wording、wrap | enumを日本語Status Chipへ統一 |
| Future Comment Section | 未来へ送る・状態確認 | コメントを送る | replay/delete | Diary内Section | privacy-safe Empty | send/delete | Icon descriptions | Sealed本文を出さずCard/Chip化 |
| Future Creator Sheet | 本文と公開日時指定 | 未来へ送る | date/time | scrollable Sheet/close | inline validation | irreversible send | wrap/scroll | consequenceは既存Dialog維持 |
| Future Reveal | 初回受取と再閲覧 | animation/replay | TTS/close | fixed black stage | unavailable/loading | TTS selection | Back/Stop descriptions | 固定色・演出順序を意図的に維持 |
| Settings | app設定の整理 | setting selection | reset、system settings | Settings title/Back | Snackbar/progress rows | reset/disclosures | row merged semantics/48dp | Section順・共通Row・用語を整理 |
| Manual Backup | local export/restore | 作成/復元 | file picker | Settings内Section | progress/Snackbar | 2-step full replacement | busy時disable | Google DriveとSectionを分離 |
| Google Drive | auto/manual remote backup | toggle / backup now | restore | Settings内Section | local status/progress/error | disclosure/restore | switch/action rows | ON/OFFを有効/無効へ、状態hierarchy改善 |

## Cross-cutting audit

### Top bars and navigation

Memo List、Memo Editor、Diary List、Diary Editor、Settingsを`ProductTopBar`へ統一した。
EditorのSystem BackとTop bar Backは同じsave/cleanup callbackを維持する。Future Revealは没入型black stageなので例外。
Bottom NavigationはMemo/Diaryの2-tabを維持し、Settingsは両Listの同じTop bar位置から開く。

### Empty states

- Memo 0件: 説明と「新しいメモ」。
- Search 0件: 検索結果であることを明示し、作成0件と区別。
- Diary: 今日の作成actionを維持し、過去0件へ説明を追加。
- Comment: 用途説明を追加。
- Future: 公開予定を確認する場所であることを説明。SEALED本文は表示しない。

### Dialogs and irreversible actions

Memo/Comment/Future delete、完全置換Restoreはerror colorのActionへ統一。
Diary確定・一度だけの修正、Future送信、Restoreのconsequence本文は短縮せず維持した。
Buttonは「キャンセル」「削除」「未来へ送る」「修正を確定する」等、結果を表す語彙を使用する。

### Sheets and small screens

Overlay Setup、Playback Settings、Future Creatorはvertical scroll可能。Comment listはbounded LazyColumnを残し、
利用可能高に応じて縮められる。Horizontal option群はscrollを維持する。

### Copywriting and technical wording

UI上の「User Comment」は「コメント」、「Work Comment」は「アウトライン」へ統一した。
Drive disclosureのGZIP表記は「圧縮して保存」へ変更。DataStore、Room、shortService等はUIに表示しない。
内部enumはDiary/Futureともユーザー向け日本語へ変換する。

### Loading, errors, and double action

Backup、Restore、Driveは処理中indicatorと既存`isBusy` disableを維持する。TTSは初期化表示と重複開始防止を維持。
ErrorはViewModelで日本語へ変換され、Snackbarまたは局所statusへ表示される。不可逆確認をSnackbarへ移していない。

### Performance

Memo/Diary/Future/CommentのLazy listはentity id keyを維持する。Overlay Planは`remember`と共有Factoryを維持。
新規shared componentsはstateを所有せず、repository/domain計算をComposableへ移していない。
Memo organizationはpureなpolicyで500件以上を一括処理し、LazyColumnのstable entity id keyを維持する。

### Memo organization

FavoriteとPinを別概念として実装した。FavoriteはCardとEditorから直接切替、Pinはoverflowから切替とし、
どちらも確認なしで反映する。検索語はFilter/Sort変更で消さず、Pinned/Unpinned各group内に選択Sortを適用する。
Session stateはconfiguration changeで維持するが、次回起動の既定はALL / 更新が新しい順である。
EditorとListはRoom Flowを正本に同期し、organization更新は本文autosaveやupdatedAtへ混在させない。

### Memo tags

Editorでは割当TagをFlowRowで表示し、Pickerから複数Tagを即時attach/detachできる。List Cardは3件と`+N`へ
抑え、Tag数が多くてもMemo title・Favorite・overflowの優先順位を維持する。Tag filterは既存検索、Favorite、Pin、
Sortと合成され、renameは選択IDを維持し、deleteは選択を自動解除する。Managementのduplicate/invalid nameは
日本語Snackbarへ変換し、作成・renameの二重送信をdisabled状態で防ぐ。Tag操作ではMemo timestampを更新しない。

### Memo archive and trash

通常一覧と通常検索はACTIVEだけを扱い、ArchiveとTrashはTop bar overflow配下の専用画面へ分離した。Archiveは既存Editorを
再利用して編集・Tag・Comment・再生・TTSを維持する。Trashは本文を編集させず、復元・完全削除だけを表示する。
Lifecycle actionの前にはEditorのpending autosaveをflushし、createdAt/updatedAtを変えない。Archive/Trash移動は
Snackbar Undo、完全削除と空にする操作はDialogで結果と不可逆性を説明する。自動削除や保持期限は導入しない。

### Advanced organization and bulk actions

通常一覧へ、長押しと明示menuの両方から入れる選択modeを追加した。選択中はCard tapでEditorを開かずtoggleし、Search、
Favorite/Pin filter、Tag filter、Sortを固定する。Back、画面遷移、処理完了時に選択を破棄し、Archive/TrashによってACTIVE
Flowから対象が消えた場合も残存IDをreconcileする。「表示中をすべて選択」は最終的に表示されたListだけを選ぶ。

Favorite/Pinは混在状態を曖昧toggleせず、追加と解除を別Actionにした。Tag addは全Tag、Tag removeは選択Memoの少なくとも
1件に付くTagだけを候補にし、Tag本体は削除しない。複数Tag filterは確定式Bottom SheetとANY/ALLを採用し、rename/deleteへ
ID基準で追従する。一括Archive/TrashのSnackbar Undoは操作時刻guardにより後続状態を上書きしない。

### Comment expression

Comment composerへ常時全optionを展開せず、「表現: 標準」からColor／Size／Emphasis／Speed／PlacementとStatic Previewを開く。色名、選択状態、
48dp操作領域を持ち、Sheetはlandscapeとlarge fontでもscrollして決定Actionへ到達できる。保存成功後だけdefaultへresetし、
Preset挿入では選択Appearance/Motionを維持する。既存CommentはListの「表現を変更」から同じSheetを再利用し、確認Dialogなしで保存する。

非default AppearanceはComment Listに「ピンク・大きめ・強調」のようなquiet summaryとして表示し、色だけに依存しない。
Archived Memoは編集可能、TRASHED MemoにはExpression変更導線を出さない。再生はINLINE／STAGE／OVERLAYで同じsemantic roleと
Surface相対Placementを保ち、本文、TTS text、再生順を変更しない。SpeedはGlobal設定と独立した穏やかな相対差に留める。
同じbandのSLOW/FAST混在は追突を避けるため開始をdelayし、配置指定を別bandへ崩さない。逆走、波、バウンド、
custom speed/Y位置は将来候補とする。

### Fixed comment foundation

Unified Expression Sheetの先頭にFlow／Fixed Top／Fixed Bottomを置き、固定表示を発見できるようにした。Fixed選択中は無効な
Speed／Placementを非表示にし、保持されている設定がFlow復帰時に使われることをsupporting textで説明する。Static Previewと
quiet summaryはTop／Bottomを位置と文字の両方で伝え、large font／landscapeでもSheetをscrollして決定へ到達できる。

長いFixedはSurface safe widthでwrapし、全文と実測高を使う。表示領域に収まらない場合はsilent clippingせず、INLINE／STAGEの
SnackbarまたはOverlay Setupのdisabled actionで回復方法を提示する。Pause／ResumeはFixedの残り滞在時間を保ち、Overlayも
既存のnon-touchable single window、safe alpha、Region相対配置、120秒preflightを維持する。

Reverse、Wave、Bounce、ZigzagなどのSpecial MotionはFixedと混ぜず、次Phase候補として残す。

### Future comment expression

Future Creatorへ共有Expression entryとblack surface Previewを追加し、Color／Size／Emphasis／Flow／Fixed Top／Fixed Bottomだけを
送信前に選べるようにした。送信確認はExpressionもimmutableになることを短く追記し、保存失敗時はSheetと全draftを保持する。
SEALED／DELIVERED Cardとaccessibility treeには本文同様Expression summaryを出さない。

初回RevealはSource Diary Contextを維持したままPersisted styleとbehaviorを共通Rendererへ渡す。Flow、Fixed Top、Fixed Bottomは
自然完了時だけ初回受取済みにし、停止・background・navigationでは未完了のまま次回再試行する。Replayは同じExpressionを使うが
初回完了時刻を更新しない。Fixedが現在Surfaceに収まらない時はpersisted modeを保ったままFlowへfallbackし、本文clipや
開封不能を避ける。Static recordはquietな本文を維持し、non-default summaryだけを補足する。

Future per-comment Speed、Future Flow Placement、Future Direction／Waveは将来候補として残す。

### Special flow motion

Memo CommentのFlow時だけDirection（右から左／左から右）とEffect（直線／波）を共有Expression Sheetへ追加した。
Fixed中はSpeed／Placementとともに非表示にし、保存値を破棄しない。Flow復帰時にLTR／Waveを復元し、保存成功後のComposerだけを
RTL／Straightへresetする。Presetは本文だけを変更し、既存Comment editはAppearance、Speed、Placementを含む全Expressionを
一括保存する。

Direction／Effectは文字labelとselected semanticsを持ち、Static Preview summaryでも明示する。Sheet全体はvertical scrollを
維持するためfont scale 1.0／1.3／1.5とlandscapeでも決定Actionへ到達でき、SYSTEM／LIGHT／DARK paletteの既存契約を再利用する。
INLINE／STAGE／OVERLAYは同じresolved behavior、lane geometry、Rendererを利用し、Overlayのsingle non-touchable window、Region
相対band、safe alpha、120秒preflightを変更しない。

Waveは6dp desired amplitudeを実測render heightとlane geometryでclampし、隣接laneへ越境させない。LTR／RTL混在は正面衝突の
精密交点計算をUI価値より優先せず、同一laneを時間排他して安全側へ倒す。Bounce／Zigzagは次候補だが、path種別を追加する際も
水平durationをSource of Truthとし、独立timerやRendererのsource分岐を追加しない。

### Comment experience polish and motion QA

Expression SheetをPhase 7Fで再監査し、「プレビュー」「見た目」「表示方法」「流れ方」のSectionを明示した。Flow選択時だけ流れ方を
表示するprogressive disclosureは保ち、追加の折りたたみは導入していない。SheetタイトルとSection titleにはheading semantics、
Size／Emphasis／表示方法にはgroup名を含むcontent descriptionを追加した。48dp操作領域、selected semantics、静的Preview、
defaultとの差分だけを表示するquiet summaryは維持する。Fixed modeはsummaryで必ず「上に固定／下に固定」と示す。

代表実機matrixとしてRTL／LTR × Straight／Wave、SLOW／FAST、TOP／MIDDLE／BOTTOM、SMALL／LARGE／STRONG、Fixed Top／Bottom、
混在Timelineを確認した。追突・正面衝突・隣接lane侵入・突然の消失はなく、hard placementを崩さず実用的な時間内に提示された。
Waveは視認できる一方で本文を過度に妨げず、LTRもRTLの自然な鏡像として見える。Speed 0.85／1.00／1.20、Wave 6dp・2 cycles、
Fixed 4000msはいずれも差が理解でき、可読性とCalm Motionの均衡が取れていたため変更しない。

INLINEでは本文操作を奪わず、STAGEの黒背景では全7色のsemantic identityと可読性を確認した。OVERLAYはアプリ、明るいHome、
text-heavyなAndroid設定上でFlow／Wave／Fixedを確認し、下層Settingsのscrollが継続した。single root、NOT_FOCUSABLE、
NOT_TOUCHABLE、safe alphaは変更せず、comment-local surfaceとshadowで背景差を吸収できているためPalette／Rendererも変更しない。

Future CommentはColor／Size／Emphasis／Flow／Fixedだけの意図的なsubsetを維持する。初回Revealの演出とStatic recordの余韻を分け、
User CommentのDirection／Waveを追加しない。コード監査ではWaveが既存app clock progressを使い、frameごとのPath／List／Formatter生成や
独立timerを持たないことを確認した。端点、pause／resume、1000件mixed planの既存回帰を維持し、新しいMotionは追加しない。

### Quality readiness and visual regression

Phase 8AではSC-51A上でTalkBackを実際に有効化し、Memo List／Editor／Comment／Comment Expressionの代表導線を日本語音声で確認した。focus trap、意味不明なicon action、destructive actionの曖昧さは確認されなかった。Diary／Future／Settings／Backup／Drive／Overlay、Selection／Tag／Archive／Trashは実機semantics treeとFull Instrumentationで回帰し、全項目の追加実聴matrixは継続QAとする。SEALED、DELIVERED、`firstPresentedAt`未完了のFuture Commentは本文とExpression metadataをaccessibility treeへ出さない。実聴後はAccessibility service、touch exploration、media volumeを作業前状態へ戻す。

Visual Regressionは新dependencyを追加せず、API 36／1080×2400／420dpi／font scale 1.0／ja-JPの固定Emulatorで`UiAutomation.takeScreenshot()`を使う。FLOW LightとFIXED DarkのExpression Sheetをdeterministic baselineとし、通常TestはGoldenを上書きしない。物理Samsung端末はvendor rendering差があるためpixel合否へ使わない。詳細は`VISUAL_REGRESSION.md`に記録する。

57件のRTL／LTR、Straight／Wave、Fixed Top／Bottom、Color、Large／Strongを混在させた約98秒Overlayを実機で確認した。再生中も下層Settingsのtap／scroll／keyboardを利用でき、自然終了、Global Banner停止、Notification停止、rotationの全経路でWindow／Service／Notification／Bannerが残らない。single non-touchable Windowと120秒上限は変更しない。

実DocumentsUIでBackup v9を作成し、Picker、privacy-safe Preview、Restoreを完走した。Memo、Favorite、Pin、Tag、Archive、Trash、User Comment Expression、Diary、Future Expression、Settingsを復元し、試験fileだけを削除した。実Google Driveは`drive.appdata`でv9 backup、同一remote file update、silent re-authorization、privacy-safe Preview、full restoreを確認し、access tokenは永続化されていない。

Process restartではpersistent data、Future delivery state、Drive generation、Settingsを保持し、Selection／Filter／Overlay session等の一時状態を復元しない。Release variantはminify／resource shrinkなし、release signing未設定の既存構成でAPK／AAB／lintを生成し、debug credential、test gateway、本文・token logのproduction混入がないことを監査する。

Font scale 1.5＋landscapeでDiary EditorのFuture送信Actionへ到達できない問題を確認したため、short-height時だけ画面全体をscroll可能にした。Portraitのwriting surfaceは維持する。Memo Editor、Expression Sheet、Diary Calendar、Future Sheet、Settingsはhigh-constraint条件、長文、empty/error代表状態でActionへ到達できる。新Motion、schema、Backup format、permission、dependencyは追加しない。

### Operational signing and device readiness

Phase 8BではSC-51AのSamsung TalkBack音声を使い、Selection、Tag、Archive／Trash、Diary、Future、Settings、Backup／Drive、
Overlay Setup／Global Bannerまで残りの主要導線を実聴した。Focus trap、意味不明なIcon、操作困難な重複読み上げはなく、Toggle／Selected、
削除確認、Snackbar／Dialogの結果を理解できた。SEALED／DELIVEREDは本文とExpressionを読まず、`firstPresentedAt`未完了はStatic本文も
TTS導線も公開しない。QA後はTalkBack、touch exploration、音量、font／rotation／animation、Overlay権限と既存Accessibility serviceを
作業前状態へ戻した。

専用small-screen条件をAPI 36 Emulator、720×1280 px、320 dpi（360×640 dp）、ja-JP、font scale 1.0、Portrait、animation 0として固定した。
Memo、Comment Expression、Diary、Future、Settings、Archive／Trash、Overlay Setupを確認し、font scale 1.5＋LandscapeではMemo Listと
Memo Editorの上部Controlが本文を押し出す問題を確認した。short-height時だけFilterとList、Editor全体を同じscroll領域へ入れ、通常Portraitの
weight契約を維持した。決定、未来へ送る、Backup／Restore、Overlay再生はscrollで到達できる。small-screen Goldenはscroll state依存と
baseline増殖を避け、今回は追加せずreachability QAへ分離する。

Releaseは既存unsigned構成を変えず、repository外のthrowaway keyで一時署名したAPKをclean Emulatorへinstallした。Memo／Tag／Comment
Expression／STAGE、Diary／Calendar／Future Composer、Settings／Manual Backup導線と短いOverlayの自然終了を確認し、key、password material、
一時署名済みAPKを削除した。Temporary certificateはOAuthへ登録せず、Phase 8Aのdebug-signed Drive v9実Smokeをintegration baselineとして
維持する。正式Upload Key生成、Play App Signing enrollment、Play upload／公開は行わない。署名identityと公開前gateは
内部のリリース手順書（非公開）を正本とする。

Full Instrumentation中にDrive dirty generationの更新済み値を新規Flow collectorが一度だけ見落とすtest raceを再現した。Production code、
timeout、固定delayは変更せず、Test helperが現在値を再購読してobservable generationを確認するようにした。Room 14、Backup v9、DataStore、
Manifest Permission、Dependency、applicationIdは不変とする。

### Quiet Ripple brand mark

Phase 8Dでは正式表示名MemoRippleのPrimary Brand Markとして、中心核と二重の開いた波紋から成るQuiet Rippleを導入した。SC-51A
（API 33）のsquircle maskと固定API 36 Emulatorのcircle maskで、markの欠け、safe zone逸脱、背景との同化がないことを確認した。
API 36のThemed Iconを実際に有効化したHome画面でもmonochrome silhouetteが保たれ、通常色から意味が変わらない。QA後はThemed Icon、
Dark mode、Overlay権限を作業前状態へ戻した。

Android 12+のcold startをLIGHT／DARKで確認し、system Splashが同じadaptive launcher iconとtheme backgroundを使い、白／黒flash、
不必要な待機、独自Splash Activityを発生させないことを確認した。warm startはplatform標準遷移のままとし、custom animationは追加しない。
API 30 Pixel Emulatorでもlegacy launcher maskと直接起動を確認した。API 29〜30はfallbackをplatformへ委ね、pre-12専用Splashを新設しない。

Overlay foreground notificationは専用monochrome small iconへ分離した。API 36の実Notification Shadeで24dp相当の中心核と二重波紋を
識別でき、launcher backgroundを含むblobや空白iconにならないことを確認した。既存のtitle、channel、Stop action、foreground-service
cleanupには変更を加えていない。

既存Visual RegressionのUI goldenはブランド資産の変更対象外であり、reference EmulatorのFLOW Light／FIXED Dark baselineを維持する。
ブランドmark自体はadaptive mask、themed tint、system Splash、notification tintというplatform依存表示をmanual device matrixで検証し、
pixel goldenを増やさない。Ripple Labs／XRP／crypto／finance、Wi-Fi、音量、再生の記号へ寄せず、「静かな記録＋動くコメント＋時間を
越えた波紋」を一つの核と二重波紋で表す。

### App identity surface and About

Phase 8EではSettingsの既存順序（外観、コメント、読み上げ、手動バックアップ、Google Drive、Androidオーバーレイ、その他）を維持し、
最下部へ「アプリ情報」を追加した。About／Privacyはnavigation action、VersionはBuildConfig由来のstatic informationとして区別し、
Bottom Navigationはメモ／日記のまま変更しない。Settingsが長くなっても既存LazyColumnで各Actionへ到達できる。

Aboutは72dpの静止Quiet Ripple、headingとしてのMemoRipple、事実ベースの二文説明、Versionだけで構成する。Brand Markはdecorativeで
TalkBack focusを持たず、Versionは「バージョン 0.1.0」と一まとまりで読める。Privacyは端末内保存、Manual Backup、optional Drive、
Android TTS engine、Overlayの実装factsをSection化し、Drive／Overlayの操作前DisclosureとFuture Comment privacy契約は変更しない。

Font scale 1.0／1.3／1.5、portrait／landscape、360×640dp相当small-screenではSettings、About、Privacyをscroll可能にしてBackと全文への
到達を優先する。Light／Dark／Systemは既存Material rolesを使い、Quiet RippleはTheme primaryでtintする。既存FLOW Light／FIXED Darkの
Visual Regressionを正本として維持し、静的で共有component中心のAboutはGolden増殖よりnavigation／semantics testを優先する。

OSSはdirect runtime inventoryを`OSS_LICENSES.md`へ記録したが、releaseの全transitive artifactに対するauthoritativeなNotice／License asset
生成pipelineがない。推測した本文や不完全な一覧を表示せず、Phase 8Eではdead rowを置かない。新plugin／dependencyを追加せず、公開前の
明示的なRelease Gateへ送る。

### OSS license and privacy publication foundation

Phase 8Fでは`:app:releaseRuntimeClasspath`の169 components（direct 17、transitive 152）をPOM、parent POM、AAR/JARのMETA-INF、Google Play
services同梱third-party noticeから監査した。Licenseが同じproject familyだけを静かな一覧へまとめ、DataStore external Protocol Buffersの
BSD licenseはAndroidXのApache groupから分離した。Google Play services SDK本体をOSSと誤表示せず、同梱第三者noticeだけを独立表示する。

Settingsのアプリ情報へ「オープンソースライセンス」を追加し、Library／License一覧からoffline全文へ遷移する。WebView、network、検索、
新dependencyは使わない。List、detail、Back、長文scrollを既存Product componentとMaterial typographyで構成し、Font scale、landscape、
360×640dp相当でも全文へ到達できる設計とする。通常buildでassetを上書きせず、dependency更新時だけ明示生成・reviewする。

アプリ内Privacyは短いOverviewを維持し、Future Commentの非表示が暗号化ではないこと、backup fileが非暗号化でrestoreがreplacementである
ことを補足した。詳細の正本はプライバシーポリシー、Play提出用実装factsは内部資料（非公開）へ分離する。Contact、effective date、
public URLはdocs内Release Gateだけに置き、ユーザー向けbuildへplaceholderを含めない。

### Memo empty-state hierarchy and Quiet Ripple UI

Memo 0件画面では、利用対象のないSearch／Filter／Sortを先に見せ、中央CTAとFABが同じ作成Actionを重複していた。機能単位では正しくても、
「メモを書く」よりorganizationを優先し、Material component catalogのように見える原因だった。0件時は整理ControlとFABを隠し、静的な
Quiet Ripple mark、MemoとCommentの関係を伝える一文、単一の「最初のメモを書く」を視覚中心にする。Memoが1件以上になった時だけSearch、
作成FABへ切り替え、検索／Filter結果0件では条件を残して回復可能にする。

通常一覧も既存Controlの整列ではなく情報設計から再構成した。Search末尾の単一Actionから「表示するメモ」Sheetを開き、Favorite／Pinned、
Sort、Tagを必要時だけ選ぶ。非default条件だけSearch直下へ短く要約し、常設Filter Chip群を廃止した。Memo rowはBackgroundと一体化したoutlineなしのflat layout、
作成はlabel付きExtended FABとし、一覧上で同時に判断する部品数を減らす。機能は削除せず、低頻度操作を一段深く配置する。

Light／Dark paletteはQuiet Ripple tealをPrimaryへ統一し、BackgroundとSurfaceを分離した。Typographyは小ささで静けさを表現せず、
Screen title 24sp、本文16sp、supporting 14spを基準に階層と行間で落ち着きを作る。Bottom NavigationはMaterial既定の大きなpillを外し、
Primary iconと短い静的indicator、常時labelで選択を伝える。Comment以外の装飾motionは追加しない。
さらにBottom Navigationをsystem inset除外64dp、各destination 88dpへcompact化し、2 destinationを中央へまとめた。日記の大きな
Segmented Buttonは固定Tab Headerへ置き換え、一覧／カレンダーをHorizontal Pagerの左右swipeで移動できるようにした。各pageは独立した
LazyColumnを持ち、Calendarの選択月・選択日とListの縦scroll位置を相互に壊さない。Tab tapはaccessibility用の直接操作として残す。
page分割にあたり、従来は表示modeに関わらず画面上部へ出していた未来コメントの配信Bannerと「過去の今日」を、一覧pageだけに置く判断をした。
Calendar pageは選択日の文脈に専念させ、同じ内容を2つのpageへ重複表示しない。「過去の今日」はDiary Calendarの正確な月日recall自体を変更する
ものではなく、露出範囲だけを一覧pageへ限定する。ただし配信済み未来コメントの初回開封導線はこのBannerだけで、Diary editorのDelivered行は
削除しか持たず、配信通知も存在しない。Calendar pageを選んだままだと未受取に気付けないため、代わりに「一覧」Tab labelの右へ4dpのprimary dotを
出す。Calendar Dayの日記dotと同じ語彙を流用し、件数badgeにはしない。dot枠は常時確保してTab labelを動かさず、TalkBackへはTabの
contentDescriptionで「未受取N件」を伝える。Tab tapがそのまま受け取り導線になるため、accessibility代替操作も同じ経路で満たされる。
Calendar page滞在中にBannerが出ないこととTab dotで到達できることは、instrumentation testで固定した。
上部もBottom Navigationとの重複を監査し、大きな「メモ／日記」titleを削除した。Memoは56dp barの左に整理menu、中央に現在のscope、
右にSearchを置き、常設Search surfaceをTap時だけbar内入力へ置き換える。Filter／Sort／Tag、Archive／Trash、Settingsは整理menuから
段階的に開示し、検索中も表示条件を変更できる。Diaryは同じ56dp barへ一覧／カレンダーを最大224dp幅で中央配置し、左右48dp slotを
対称に予約してSettingsによる中心ずれを防いだ。Screen destination名はBottom Navigationだけで伝え、content開始位置を上へ戻した。
Theme token変更に伴い、固定API 36／1080×2400／420dpi／ja-JP環境で既存FLOW Light／FIXED Darkを明示recordし、
通常比較2/2 passを確認した。Golden更新は今回の意図したpalette／Typography変更だけを含み、自動overwrite契約は変更しない。
あわせて未定義のまま残っていたsurfaceContainer階調、inverse系、onBackground、surfaceTintをThemeへ明示した。未定義ロールは
Material baselineの紫寄りneutralへfallbackし、実機でSheet地色`#F7F2FA`、Snackbarのaction`#D0BCFF`、Switch trackと
TimePicker文字盤`#E6E0E9`として出ていた。Sheet地色がGolden対象のCommentExpressionSheetそのものだったため、Goldenは
この1回だけ再recordした。`tertiary`系は第3accentの決定が未了のため意図的に未定義のまま残す。

### Diary body keeps its outlined box — considered and deferred

`DESIGN_SYSTEM.md`の「Editor and playback surfaces」は「**Memo Editor**はCardではなく静かな紙面。Title／BodyをOutlined boxで
囲わず」と書いており、Memo Editorへ限定している。Diary editorが`OutlinedTextField`のままなのは統一漏れではない。実際この
sessionで一度「一般則」と誤読しかけたため、判断の記録をここへ残す。

統一案を実機（SC-51A・Dark）で実装して現状と比較した。差が大きいのは**空の状態**だった。Memo editorは本文の上がTitleだけなので
枠なしで成立するが、Diary editorは本文の上に「日記」見出し、状態Chip、写真sectionが積まれており、枠を外すと写真sectionとの境目が
曖昧になって本文の始まりが読み取りにくい。入力後は差が小さく、どちらも成立する。

意味の面でも枠は整合する。Diaryは一日一件でDRAFT→FINALIZED→CORRECTING→LOCKEDと編集可能性が閉じていく。§1.2の
「constrained editing is deliberate」に対して、枠はその有限性の視覚表現として読める。Memoが自由に書き換える作業面であるのと
対になっている。

以上より現状維持とした。**恒久決定ではなく、今回見送っただけである。** 再検討する場合は空状態での本文の始まりの読み取りやすさを
必ず一緒に見ること。統一だけを目的に枠を外すと、その一点が悪化する。

### Brand title typography

`DESIGN_SYSTEM.md`のTypography表はBrand titleを`displaySmall` 28sp／36sp／SemiBoldと定めていたが、製品で唯一のBrand title
であるAbout画面は`headlineMedium`を使っていた。`headlineMedium`はThemeで上書きしていない唯一の使用中roleで、Material baseline
のまま描画されていた。逆に`displaySmall`は定義済みでどこからも使われていなかった。docではなく実装を動かし、About画面を
`displaySmall`へ揃えた。これで製品が描画するTypography roleは全てThemeで定義済みになり、未上書きroleはゼロになった。

### The comment culture this app quotes, quoted correctly

人間の指定で、コメントの語彙をニコニコに合わせた：色は同じ10色・同じ並び（黒は暗所で白フチ）、
表現sheetに「設定内容を保持する」とリセット、chipはお気に入り（追加・削除・上限10）、
listの帯は「コメント一覧」＋「編集」。

**goldenは意図的に再生成した。** 表現sheetの色が7→10になったのはこの依頼そのものなので、
基準環境で record → 差し替え → 再照合まで行った。バックアップの単体テストが1件落ちたのは
「未知の色の例」に orange を使っていたためで、例を本当に未知の値へ替えた——**規約の値域を広げると、
昨日の『不正な例』が今日の正会員になる**。なお旧バージョンのアプリはorange等を含むbackupを
INVALID_COMMENT_APPEARANCEとして拒否する（前方互換は契約外だが、事実として記す）。

### The comment sheet joins the grammar the rest of the app already speaks
### The comment sheet joins the grammar the rest of the app already speaks

人間の指摘は「コメントのUIがよくない、まず見出しを消せ」。レビューすると、このSheetだけが
appの規律の外に居た：一言のコメントに常時3つの操作（⋮・🗑・＝）、⋮が「メニュー」の約束を破って
表現エディタを直接開き、ほぼ常にdisabledの「作成順に戻す」がヘッダーの一等地に常駐、閉じ方が三重（✕・handle・scrim）、
空状態が器の半分、行の塗りが枠線言語より前の古い面言語。

直し方は新発明ではなく**編入**である：行は本文＋表現サマリだけにして操作は長押しへ（Memo Cardと同じ）、
並べ替えはモードへ（Note画面と同じ）、表現変更は🎨へ（コンポーザと同じ）、行はページ色＋`outline`枠へ
（壁と同じ）、見出し・✕・「入力候補」ラベル・timestampは削除。ヘッダー行そのものが消え、
Sheetは書く場所から始まる。

### The finish pass: the same discipline, finally applied to surfaces

自分の再設計を自分でレビューしたら、**線に課した規律が面に課されていなかった**。白いpillは1.11:1、
`primaryContainer`のFABは1.10:1——「見えない線は引かない」と書いた同じappが、最重要ボタンと最上段の器を
影だけで立たせていた。五手で直した：

1. FABは`primary`塗り＋白グリフ（5.8:1）。pillはCardと同じ「ページ色＋`outline`枠」へ合流。
2. 左端railを24dpの一本に。tabは全幅セル中央をやめ左寄せの二語へ（本文開始 約150dp→約110dp）。
3. 「選択中」は`primary`の文字、上下で同じ語彙。下線はtabの持ち物。
4. ▶はpill（「メモを検索」の器）の外へ。流せる時は`primary`で灯る——中心体験が画面上に色を持った。
5. tab行下の髪線復活＋スクロール中はbarが`surfaceContainerHigh`へ持ち上がる（→ 2026-09-25、利用者のreviewで持ち上がりは廃止。barは常にページの色、髪線は残す。HANDOFF §16.99）。

教訓として残すべきは1で、**規律は適用範囲を書かないと半分しか効かない**。「Cardの境界は`outline`」は
守られていたが、面のコントラストには誰も物差しを当てていなかった。DESIGN_SYSTEMに「非テキストの面も3:1」
を明文化した。

### The rest of the redesign went in: the bar is a field, the drawer is places, the tools follow the keyboard

残りの提案（②〜⑤・⑦）を実装した。

**②壁の最上段を検索fieldにした。** 検索は⌕で入るmodeだったが、modeに入ってから打つ一手が消えた。
☰と▶がfieldに乗り、⋮が命令を持つ。**③Drawerは行き先だけ**になり、11行が7行＋Tagへ減った。
**⑦壁の強い色は「書く＋」だけ**になった。▶をfloating buttonからbarの線iconへ降ろしたことで、
IconButtonの本物のdisabled stateが手に入り、「無効を色で偽装する」workaroundごと消えた。

**④Editorの下段は書く姿勢と一緒に出入りする。** 常時30近く出ていた操作が、読む姿勢では💬⚙■の3つになる。
gateは**fieldのfocus（またはIME）**である。最初はIME可視性だけでgateしたが、instrumentationではIMEの出現が
保証されず6件が落ちた——「写真を追加」のように**一文字も打たずに使う道具**があるので、道具はcaretを置いた
瞬間に出るのが正しく、それはテストからも決定的に押せる。

**⑤▶は前回の方式で流れる。** 方式・内容・オーバーレイの領域と密度は`rememberSaveable`（画面を閉じると
忘れる）だったのをdevice-localのDataStoreへ移した。**毎回オーバーレイを選び直していた人の3tapが1tapになる。**
テストは端末を共有するので、`@Before`で`resetPlaybackStyle()`を呼んで方式の漏れを断つ——これを忘れると
overlay系のテストが次のテストのinline前提を壊す。

### The redesign asked what a screen would look like built today, and two answers went in

人間の依頼は「既存UIを一旦忘れ、今日ゼロから作るならどう置くかで再設計せよ。ただし機能は消すな」。
七つの提案のうち、人間が選んだ①（tab統合）と⑥（日記の一枚化）を実装した。

**①メモとアウトラインは同じMemoだった。** 本文に`■`を一行書くとMemoが黙って隣のtabへ移る——置き場所を
決めていたのは書き手ではなく記法である。壁を一つにし、Cardの中で骨組みを描かせた。旧2tabの見え方は
表示条件の「表示」に**表示モードとして丸ごと残した**ので、機能の削除はない。人間の問い「Keepのように
切り替えられるか」への答えがこの置き場所である。表示は端末に記憶するが、backupと初期化には含めない——
復元が他端末の読み方を変えるべきではない。

**⑥日記の2tabは、開いた画面が最初に訊くのが行き先の選択だった。** 月＋今日選択済みの一枚にし、
下へ読むことが過去へ読むことにした。未来コメントのBannerがpage最上部に来たので、「別のtabに居ると
気付けない」問題（旧・4dp dotで緩和していた）はdotごと消えた。

テストの書き直しで3件、旧一覧の「日付テキストをタップして開く」経路を放置して落とした。開く動線は
`calendar_open_diary`ボタンに一本化されたのだから、テストも同じ戸口を通る。

### The border was never the problem, the colour was

人間が実機で使った上での判断。Keepのように枠が欲しい、という話である。

design passのとき、私はCardの境界線を外した。`outlineVariant`が背景に対して1.13:1しかなく、**引かれていないのと
同じ**だったからで、そこまでは測って正しかった。間違えたのはその次で、**「この線は見えない」から「境界線という
手段が駄目だ」へ一般化した。** 他の色で測っていない。`outline`ならlightで1.73:1、darkで1.96:1ある。
`DESIGN_SYSTEM.md`には最初から「内容の面を区切るCard境界は`outline`」と書いてあり、また文書の方が正しかった。

Keepの作りを見ると、Cardの塗りがPageと同色で、区切りは境界線だけである。塗りの差も影も無いので、境界線が唯一の
縁になり、薄くても目が拾う。こちらは白いCard＋影だったので、そこへ境界線を足せば区切りが三つ重なる。
**手段を足すのではなく、置き換えた。**

一つに絞るという design passの判断自体は変えていない。絞る先が影から境界線に変わっただけである。

### The chapter was a box, and the documentation had always said it was not

人間の指摘。前回入れた「章ごと入れ替え」が違うという話で、調べたら**このapp自身の設計に反していた**。

`NoteStructure.kt`の冒頭にはずっとこう書いてある——「chapters are headings placed over a single ordered run of
episodes, **not containers that hold them**」。カクヨムの構造を解説した記事も同じことを言う——「エピソードと
エピソードの間に見出しを挿入するという形」「章や節はエピソードの親要素ではなく、**単純な区切り行**」。

**文書は最初から正しく、実装だけが箱だった。** `chapterId`という列があるので、つい親子だと読んでしまう。

`NoteArrangement`を足して、Noteを一本の行の並びとして扱うようにした。handleはどの行でも一行ずつ動き、見出しは
episodeを越えられる。**所属は位置から読む**——見出しの下に来たepisodeがその章のものになる。`chapterId`は
引きずるのではなく、落ち着いた場所から書き戻す。これは「書かれたものから読む」の、いつもの形である。

**test tagが行ごとに一意でなかった。** 章のhandleが全章で`note_drag_chapter`を共有していたので、二つ目の章が
できた瞬間にtestが二件一致で落ちた。episode側は最初から`note_drag_episode_$memoId`だったので、章だけ揃って
いなかったことになる。

**flakeの原因が二つ見つかった。** 「emulatorの劣化」という前回の見立ては誤りで、新品でも再現した。一つは
`performClick`が**無効なnodeでも例外を投げない**こと——入力がflowで届く前に押すと空振りし、来ない結果を待って
timeoutする。前提が満たされるまで待つように直した（十箇所）。もう一つは**testどうしの状態の持ち越し**で、
DBを消さないtestがあったため後半ほど画面が重くなっていた。`@Before`で毎回空から始めるようにした。
これで全件が二回連続で緑になった。**timeoutは一msも触っていない。**

なお三度目に出た「No compose hierarchies found」は今度こそ環境で、`dumpsys power`が`mWakefulness=Asleep`を
返した。emulatorの画面が三十分のtimeoutで寝ていた。見立てを言う前に確かめる、というだけの話である。

### Arranging became a mode, and the chapters joined it

人間の指示。参照はカクヨムのhelp（「章と並び順を編集」で入り、右端のiconで動かし、「完了」で保存。
「新しい章を追加」から位置を指定）。

**modeにしたのは、読むことが普通で並べ替えが特別だからである。** 全行に握り手が付いている表は、読むものに見えない。
章のoverflowも同じmodeへ入れた——読んでいる最中に見出しを消す方法を差し出す場面はない。

**行から話数を消した。** 順番そのものが番号なので、行ごとに書くのは二度言うことになる。加えてdragの直後の一瞬は
実際と食い違うので、小さな嘘でもあった。読み上げは行を特定できねばならないので、handleの説明はTitleで言う。

**章の追加は両端のどちらかを訊く。** 一画面に入力欄と二つのButtonを置き、押す瞬間に位置が決まる。どこへでも
置ける形にしなかったのは、人が実際に欲しいのが両端だからで、途中へ入れるのは移動＝handleの仕事である。

**`reorderChapters`もまた、呼ぶ画面が無かった。** `reorderEpisodes`、`setCoverColor`に続いて三度目である
（DAOだけのものを数えれば四度目）。実装された能力に到達手段が無い状態がこのcodebaseに繰り返し現れるので、
新しい機能を足す前にまず既にあるものを探す方が早い、という記録としてここに残す。

**testが崩れたが、環境だった。** `awaitNode`のtimeoutが五秒で切れる失敗が出たが、**毎回違うtestが落ち、分離実行では
通った**。同じtestが安定して落ちるなら実装だが、入れ替わるのは環境の形である。§8はtimeoutの一律引き上げを禁じて
いるので、五秒を十秒にする前にemulatorをcold bootした——それで131/0に戻った。長時間動かしたemulatorは劣化する。
このsessionで三度目の、同じ診断である。

### A note says a second line, and the page stops repeating its own cover

人間の指示。参照はPenCakeのsubtitle画面。**Room 17→18、backup 12→13、restore v1–v13。**
subtitleは保存が要るので列なしには成り立たない。写真のときと同じ性質の変更で、かつ機能そのものが指示だったので
実装まで進めた上で報告した。commit一つのrevertで戻せる形にしてある。

**七つ入れた。** 名前の次にsubtitleを訊く二枚目。次を書くButtonを画面下へ。その言い方を数で変える——一つも無ければ
「エピソードを執筆」、あれば「次のエピソードを書く」。「章と話」を「章とエピソード」へ。「章へ移す」を「章の編集」へ。
Noteの自分のpageからcoverを外してsubtitleを置く。一覧のNoteに長押し。

**pageからcoverを外したのは、そこがcoverの仕事ではないからである。** Titleは上のbarにあり、coverは一覧で
Noteを見分けるためのもので、自分のpageでは既にそのNoteを見ている。場所はNoteが自分について言う一行へ渡した。

**長押しにcoverは入れていない。** coverは自分のpageで実物の大きさを見ながら選ぶものなので、一覧のsheetに
小さく置くと同じ操作が二箇所になる。sheetにあるのは開かずに言えること——名前、subtitle、存在すべきかどうか。

**「章の編集」は表記だけを変えた。** 動作は従来どおり「選択したepisodeを章へ移す」で、選択が無ければ押せない。
章そのものを編集する画面が要るならそれは別の機能であり、人間に確認を出してある。

**test fixtureが位置引数で壊れた。** `NoteBackupDto(60, "夜明け前に君と", "plum", 240, 270)`のような組み方をしていた
ので、`subtitle`を三番目に挿した瞬間`"plum"`がsubtitleになった。名前付き引数へ直し、ついでにsubtitleを入れて
新しい列が往復testを通るようにした。**「compileが通った」と書いたのが本体のsource setだけだったのも、このとき
分かった。**

**同じraceを三度踏んだので、全経路を塞いだ。** dialog、表現sheet、再生設定sheet——どれも別windowで、semanticsの
登録がclickの一frame後になる。開いた直後に中へ手を伸ばしている箇所を数え、七つに`awaitNode`を置いた。
再生設定sheetにはtestTagが無かったので付けた。固定時間の待機は一度も入れていない。

### A cover may carry a picture, and Room went to 17 for it

人間の承認を得た上でのdata contract変更。**Room 16→17、backup formatVersion 11→12、restore v1–v12。**
§5は「実際のdata contract変更なしに版を上げるな」と定めるが、これは実際の変更である。既存backupとの関係が
変わるので、進める前に人間へ確認した。

`notes`に`coverBlobSha256 TEXT`（NULL可）を足しただけで、写真の実体は`AttachmentBlobStore`のsha256内容addressに
既にある。**保存機構は作り直していない。** 同じ写真をMemoに貼ってcoverにも使えば、fileは一つである。
FKは付けていない。SQLiteは既存tableにALTERでFKを足せないからで、これはこのsessionで一度踏んだ落とし穴である。

**最も危なかったのはgarbage collectorだった。** `AttachmentDao`の未参照判定は`memo_photo_attachments`と
`diary_photo_attachments`しか見ていなかったので、そのままcoverを足せば**設定した直後にfileが回収されて消える**。
両queryに`notes.coverBlobSha256`を足し、migration testで挙動を直接固定した——挿入直後は未参照に出る、coverに
すると出なくなり`deleteBlobIfUnreferenced`が0を返す、外すと1を返す。実機でもapp再起動を二度かけてfileが
残ることを見ている（起動時にgarbage collectが走る）。

`NoteBackupDto.coverBlobSha256`は既定値null。**format 11以前のbackupは、写真の無いNoteとしてそのまま読める。**

**migrationの一覧が二重管理だった。** 版を17にした瞬間、内容と無関係なmigration testが13本落ちた。各testが
`MIGRATION_1_2`から順に手で並べていたためである。`AppDatabase.MIGRATIONS`を単一の情報源にして、本体のbuilderと
test 15箇所の両方をそこへ向けた。次に版を上げる人は一行足せば済む。

### A note is named before it exists

人間の指示。参照はSimplenoteのFABとPenCakeのtitle画面で、送られてきた実機screenshotが理由をそのまま写していた。
**「新しいノート」が三つ並んでいて、開くまでどれがどれか分からない。**

**押した瞬間にNoteが作られていた。** 名前は後から変えられるが、変えなければ全部同じ名前で残る。名前を付けるのは
どうせ書き手が最初にすることなので、先に訊く。問いだけの画面を一枚使い、入るまで`次へ`は押せない。出口が
「キャンセル」ではなく「閉じる」なのは、**取り消す対象がまだ無い**からである。

**FABがiconになった。** 「新しいメモ」「新しいノート」というlabelは壁で一番幅を取る要素だった。何を作るかは
立っているtabが言っている。読み上げには`contentDescription`で残してある。

**coverから文字を消した。** Titleは`NoteCover`を描くどの場所でも隣に並んでいたので、先頭六文字を面にも重ねるのは
同じことを二度言っていた。しかも六文字では二つのNoteを見分けられないことが多い。coverは色で見分けるものにして、
その色を選べるようにした。`setCoverColor`はDAOからViewModelまで実装済みで、**呼ぶ画面だけが無かった**
——`reorderEpisodes`と同じ形が二度目である。

**自分の書いた規則を破っていたのも直した。** Noteの0件状態に「最初のノートを作る」が残っていた。
`DESIGN_SYSTEM.md`に「0件Empty StateにPrimary Buttonを置かない。FABが既に同じことを言っている」と書いたのは
こちらだが、Memo側だけ直してNote側を見ていなかった。

**未着手が一つある。** coverへの写真の埋め込みはRoom 16→17とbackup 11→12を要する。`AttachmentBlobStore`は
sha256の内容addressで既にあるが、Noteにはそれを指す列が無い。§5の「実際のdata contract変更なしに版を上げるな」は
満たすが、既存backupとの関係が変わるので人間の判断を待っている。

### The outline could already have been a note

人間の判断。四つの選択肢を図で見た上での指示である。

**これまで、骨を書いてから小説にするには同じ言葉を二度打つ必要があった。** `■ 発端 / ■ 中盤 / ■ 結末`と書いた
あと、Noteを作り、話を一つずつ作り、Titleを打ち直す。骨組みは既にそこにあるのに。Hakogakiが「箱書きを変えると
本文に反映」で解いているのはここで、**このappのOutline tabは既に箱書きの半分をやっていた**。繋がっていなかった
だけである。

**四つ決めた。** 元の覚書は残す。深さは読まず全部を話にする。見出しの下の行は本文に入れる。行き先は選べる。

深さを読まないのは、「章と話に分ける」案が**下に深い見出しを持たない見出しを、話が一つも無い章にしてしまう**から
である。骨組みの途中では普通に起こる形なので、変換のたびに空の章ができる。加えて「見出しの深さ＝構造」という規則を
新しく持ち込むことになり、Outlineを書くときに「これは章になる深さか」を考えさせる。段下げは近さを言うだけの
ものだったし、章分けは既存の「章へ移す」で、実際に何話あるか見えてからできる。

**一方向にしたのは、双方向が答えられない問いを持つからである。** `■ 中盤`を`■ 転`に書き換えたとき、その下に
既に三千字書かれていたらどうなるのか。どんな答えを選んでも、それは誰かが覚えておく規則になる。一度渡したら
別々のものにすれば、問い自体が生まれない。

**実機で開いて一つ見つけた。** Noteが一つも無いときのsheetが「作ると、**このメモが最初の話になります**」と
言っていた。「ノートに追加」の文言をそのまま流用していたためで、この流れでは元のMemoは話にならない。
**画面が嘘をついていた。** 空のときの文言を渡せるようにして直した。testは通っていたので、開かなければ残っていた。

Roomは変わっていない。`noteId`・`episodeOrder`・`addEpisode`は既にあったものを使うだけである。

### What the category writes with, and what this app could not

人間の指示で、Google Playの「小説 書く」圏を調べた上での実装。二点入れた。

**調べて分かったこと。** 同じ売り方のappが実在する（「純純執筆 — 日記・小説・メモ帳・ノート・物語」「Lite Writer:
書籍/ノート/メモ」）。多目的路線は受け入れられているが、その分だけ混んでいる。圏の当たり前は、縦書き（TATEditor,
縦式, Hakogaki）、記号のone button入力（NOVEWRITE「ルビ・挿絵・括弧・傍点のquick menu」、TATEditor「ルビ / 傍点 /
…… / ―― をボタン1つ」）、記号の自動補完（純純執筆）、構成と本文の連動（Hakogaki「箱書き・縦書きeditorが完全同期」、
Scrivener「シーンをカード化して並べ替え」）、章ごとの分量、日々の統計。

**入れた一つ目は記号である。** Toolbarには見出し・項目・補足・重要・疑問が並んでいたが、それは**このappの記法**で
あって、小説を書く人が一行おきに要るものではない。`「」`も`……`も一つも無かった。`……`と`――`はflickで出しにくい
記号の代表でもある。開き括弧の自動補完も付けたが、**手で打ったときだけ**にした。長い引用を貼り付ける人は括弧を
開いていない。ルビは投稿sitesの書式`｜親文字《ふりがな》`をそのまま持ち、`BodyText.readable`が剥がす。新しい列は無い。

**二つ目は話の並べ替えである。** 調べる前から`reorderEpisodes`はDAOからViewModelまで通っていて、**呼ぶ画面だけが
無かった**。実装済みの能力に到達手段が無い状態が続いていたことになる。移動は**同じ見出しの中**に限った。章は箱では
なく見出しなので、またぐ移動は行が動かないまま話数だけ変えてしまう。掴む場所と`CustomAccessibilityAction`の両方を
置いたのは、順番をgestureだけに預けないためである。

**採らなかったもの。** 人物相関図と世界観設定の専用table（Nola / ストーリープロッター）。開発方針の「他のappに
あるからという理由で足さない」に正面から当たる上、このappは「書かれたものから読む」で通してきた。設定資料を専用
tableで持つのはその逆を向く。`[[リンク]]`があるので人物メモへのリンクで足りる。

**縦書きは見送っている。** 期待値は圏で最大だが`BasicTextField`が縦書きを持たないので工数の桁が違う。やるなら
閲覧mode（`MemoReadingView`）だけを縦にする「書くのは横、読み返すのは縦」に絞る。中心体験と噛み合う形ではある。

### The palette moved to where the comment is written

人間の指示による。参照はニコニコ動画のcomment palette。

**Keyboardを畳むButtonを外した。** IMEは自分の閉じる手段を持っており、systemのback gestureもある。同じことを三つ目の
場所からもできるようにしていた。

**表現への入口が入力欄の横へ来た。** 以前は「表現: 赤・大きめ・強調・…」という全幅のText Buttonが、入力欄と追加Buttonの
下に三段目として積まれていた。いまはPalette icon・入力欄・送信iconの一行である。Paletteは**選ばれている色で着色**するので、
開かなくても今の設定が読める。全文はcontentDescriptionに入れたので読み上げからは失われておらず、testも
`assertContentDescriptionContains`へ移した。**見えるtextを消したらtestの読み方も変える**、というだけの話である。

**表現sheetがpaletteの形になった。** 七つの決定がそれぞれ見出しと48dpのchipを持ち、全部見るのにscrollが要った。
いまは「labelと、その横に小さな選択肢」の行を積む。色は色そのものの丸、大きさは`A`をその大きさで。Section headingは
置いていない。行が自分の名前を持つので、見出しは同じことを二度言うだけだった。

大きさの`A`は**倍率を誇張していない**。実際の`scaleMultiplier`は0.90／1.00／1.15と狭く、参照アプリほど差が付かない。
広く見せれば分かりやすくなるが、それは嘘である。差を大きくしたいなら倍率そのものを変える話になる。

**goldenを差し替えた。** `VisualRegressionTest`が撮っているのがこのsheetそのものなので、当然一致しなくなる。
§8は「goldenを通すために再生成するな、goldenの変更は製品判断」と定めるが、**この画面を作り直せという指示が
その製品判断**である。手順は、先に基準環境で落ちることを確認し（差分372140/2332800 px、約16%）、
`-e recordVisualGoldens true`で記録し、pullして差し替え、再実行で照合した。寸法は1080x2160のまま変わっていない。

前回「goldenは変わったはず」と測らずに書いて外したので、今回は**落ちるところまで見てから**動かしている。

### One toolbar, one indicator, and two screens that stopped naming themselves

実機のスクリーンショットを見た人間の指示による。

**Screenが自分の名前を名乗るのをやめた。** Memo Editorの上部barは「メモ」、Diary Editorは日付の下にもう一度「日記」と
書いていた。どちらも、そこへ辿り着いた道が既に言ったことの反復である。`ProductTopBar`の`title`をnullableにした。
Diary側の「日記」は「読み上げを準備中…」も兼ねていたので、**その状態表示だけは残した**。消せば機能が減る。
消した見出しの分だけ`DIARY_COMPACT_BODY_CHROME`を210dp→170dpへ詰めている。

**下のtoolbarが二段から一段になった。** 再生系（コメント・再生設定）と執筆系が、同じ大きさのiconで別々の行に積まれ、
どちらが何なのか行からは言えなかった。

一度、置き方を間違えた。使用頻度が低いという理由で再生設定をscroll strip末尾へ送ったところ、**実機testが8本落ちた。**
横scrollの奥は「低頻度」ではなく**到達不能**である。落ちたのはtestの都合ではなく、設計が悪いという報告だった。
分け方を「**scrollするのは書く道具、固定するのはそれ以外**」に改めて通した。overflowへ逃がす案もあったが、
`memo_editor_more`は`state.exists`でしか出ないため、未保存のMemoから再生設定へ触れなくなるので採らなかった。

**Tab indicatorがlabelの幅になった。** 全幅3dpの帯はMaterial 1の描き方で、内容と無関係に画面を古く見せる。
幅と中心をpager offsetで補間しているので、追従のslideは失っていない。メモと日記で同じ実装を共有する。

**やらなかったこと。** `ProductCompactTopBar`をM3の`TopAppBar`へ、tabを`PrimaryTabRow`へ置き換える案は見送った。
`DESIGN_SYSTEM.md`が「compact top barの56dpは下限であって固定値ではない。大きなfont scaleではTab labelを縮小も
切り詰めもせず、barを必要な高さまで伸ばして折り返す」と決めており、**M3の`TopAppBar`は高さが固定**なのでこれを壊す。
accessibilityの後退と引き換えに標準部品を得る取引になるので、置き換えで欲しかった中身（indicatorの形）だけを入れた。

**訂正。** review時に「選択modeと検索modeでtabが消えるのは上部barの輪郭が定まらない証拠」と書いたが、これは強すぎた。
件数や検索欄がtabと入れ替わるのはAndroidのcontextual action barの標準的な振る舞いであって、誤りではない。

### The tag chip was a button by accident

Editorのタグ行が、Title直下で本文より大きく見えていた。design passのスクリーンショットで気付いた。人間の指示で直した。

原因は`heightIn(min = ProductSize.minimumTouchTarget)`。このリポジトリでは全域でタッチ領域確保に使っている作法だが、
**Chipに限っては要らない上に害がある。** Material3のclickableな`Surface`が既に`minimumInteractiveComponentSize()`で
タッチ領域を48dpへ広げており、描画は32dpのままになる。そこへ`heightIn`を足すと、広がるのは箱の方である。
実測で46dp、M3標準の1.4倍だった。

作法から外れる変更なので、記憶で押し切らずtestで固定した。`getUnclippedBoundsInRoot().height < 40dp`と
`assertTouchHeightIsEqualTo(48.dp)`を同時に置いてある。**小さく描かれていること**と**依然として押せること**は
別の主張なので、両方書かないと片方が黙って壊れる。実測29dpになった。

文字は変えていない。Chipのlabelは`labelLarge`（15sp SemiBold）でM3標準の14sp Mediumより重いが、これはButton全体で
使っているroleなので、ここだけ変えると統一が崩れる。大きさの話とは別軸として残す。

### The goldens were never skipping for the reason assumed

design passのあと「行間を上げたのだからgoldenは変わったはず、再記録が要る」と書いた。**これは外れていた。**

基準環境を作って実際に走らせたところ、既存goldenのまま**2本とも通った**。`CommentExpressionSheet`が使うroleは、
今回動かした`bodySmall`／`bodyMedium`／`labelSmall`と重なっていなかった。再記録は不要である。

本当の問題は別にあった。**goldenは手元のどの環境でも走っていなかった。** Pixel_10は高さ2424pxでlocaleがen-US、
SC-51Aはそもそも解像度が違う。`assumeReferenceEnvironment`が両方でskipするので、`connectedDebugAndroidTest`が
緑でもgoldenは一度も照合されていない。**緑がgoldenの証拠にならない**という状態が続いていたことになる。

合わせ方は`DESIGN_SYSTEM.md`に書いた。Google Play imageは`adb root`も`setprop persist.sys.locale`も拒むので、
端末全体のlocaleではなく`cmd locale set-app-locales`でapp単位に当てる。解像度は`wm size 1080x2400`で上書きする。

教訓として記録しておく。**変わったはずだと予測した所は、測るまで変わっていない。** 今回のdesign passで数字を出した
指摘は全部当たっていたが、数字を出さずに予測した所だけが外れた。

### A design pass over the memo wall

実機のUI/UXレビューを受けての一括修正。人間の判断である。指摘は10点あったが、効果の大きい5点だけを入れた。

**測って分かったこと。** 批評の一番の収穫は、感覚ではなく数字で確かめたところにあった。

- 文字色に`outline` (#B3BEB8) を使っていた箇所が4つあり、白地に対して **1.91:1**、Cardの塗りに対して **1.69:1**。
  WCAG AAは4.5:1なので、2.4倍足りない。明るい場所では読めていなかった。`outline`はdivider用のroleであって、
  文字のroleではない。全部`onSurfaceVariant` (5.2:1) へ移した。無効状態だけは意図的に薄いまま残している。
- 全Cardに引いていた1dpの境界線は、背景に対して **1.17:1**、Cardの塗りに対して **1.11:1**。
  つまり**一本も見えていなかった**。塗りと境界線と背景差を三重に掛けた上で、そのうち一つが不発という状態だった。
  境界線を外し、shadow 1dpひとつに絞った。`DESIGN_SYSTEM.md`の「shadowより境界線を優先する」を書き換えている。
  あの規則が悪いのではなく、この色域では成立しないというだけである。
- 行高がLatinの設定のままだった。`bodySmall` 12/16 = 1.33倍で日本語を6行。1.6倍が要る。13/21へ上げ、
  Keep CardのpreviewはKeep Cardの高さが暴れないよう4行へ減らした。読めない6行より読める4行の方が多く伝わる。
- Spacing tokenを定義しておきながら 5dp・7dp・9dp・18dp が散らばっていた。**Tokenがあるのに守られていない**のが、
  Tokenが無いより悪く見える。実機を見ながら1pxずつ動かした跡に読めるからで、ここが「素人っぽさ」の本体だった。
- 左端が3系統あった。上部bar実効24dp、一覧20dp、Editor 24dp。☰とCardが4dpずれ、一覧からEditorへ移ると本文が
  横へ飛んでいた。24dpへ統一した。

**下端の二つを同じ族にした。** 「新しいメモ」がExtended FAB、「流す」が手作りのSurface + IconButtonで、役の高さが
同じなのに部品系が違っていた。どちらが主役か画面が言えていない。流す方を`SmallFloatingActionButton`にして、
大きさで主従を言うようにした。停止は横ではなく**上へ積む**。以前は再生を押すと横幅が倍になり、押した指の下で
layoutが動いていた。

なお「流す」をprimaryへ昇格させる案も出たが、採らなかった。中心体験は書くことだと決まっているので、
**主役は書く／流すは同じ家族の小さい方**が正しい関係である。

**FABを常設にした。** ノートtabでノートが0件だとFABが消えていたので、tabをswipeするたび右下が点滅していた。
主要Actionが空のtabで消えるのは、最も必要な瞬間に無いということ。合わせて0件Empty Stateの「最初のメモを書く」を
外した。FABが既に同じことを言っているので、二つ目は視線を割るだけになる。

**入れなかったもの。** 上部barとtabをM3標準部品（`TopAppBar` / `PrimaryTabRow`）へ置き換える案は効果が大きいが、
選択modeと検索modeの構造を作り直すことになり、実機test 125本の相当数に触れる。tab indicatorが全幅3dpでMaterial 1に
見える件と、上部barの輪郭が状態ごとに変わる件も、そこに含まれる。別作業として切り出す。

### The two walls hold different memos now

メモとアウトラインが同じMemoを二通りに見せていたのを、**別のMemoを持つ**ように変えた。人間の判断である。

壁打ちのときの合意は「同じ海、違うのは何が流れるか」だった。実機で使ってみると、二つのtabが同じ一覧を見せていることの方が
先に目に入り、見方の違いは冗長さの後ろに隠れてしまった。ここは実際に触ってみないと分からなかった所である。

分け方は**書かれた内容**にした。記法のある行を一つでも含むMemoはアウトラインへ、一つも無いMemoはメモへ。列は増やしていないし、
どちらであるかを記録してもいない。`BodyReading.hasStructure`が本文を読んで決める。だからMemoは、**見出しを付けた瞬間に棚へ移る**。
ここまでの「textで表せるものはtextで持つ」「書かれたものから読む」の延長である。書くときに種別を選ばせる案（Room 16→17）もあったが、
選ばせずに済むならその方がよい。

空のときの言い方を足した。片方が空でもう片方が埋まっている状態が普通に起こるので、「メモがありません」とだけ言うと、よそに
ある場合には嘘になる。メモのtabが空なら「書いたメモは見出しや項目を持っているので、アウトラインにあります」、棚が空なら
「見出しや項目を付けたメモが、ここへ移ってきます」と言う。

Streamもそのtabに居るMemoだけから作る。メモの壁は散文を、棚は骨組みを流すという関係は変わらないが、材料が重ならなくなった。

一つ残る歪みがある。**新しいメモは必ずメモのtabに現れる。** 書き始めた時点では記法が無いからで、棚のtabで作っても同じである。
書いて見出しを付ければ棚へ移るが、作った場所に出ないのは説明が要る。作成時に`■ `を入れておく案もあったが、書き捨てられたときに
記法だけのMemoが残るので採らなかった。

unit test 481本、実機instrumentation 125本、lint 0 error。

### Holding a memo asks about that memo

長押しをUpNoteの形に変えた。押さえるとすぐ選択に入るのではなく、**そのMemoについてのSheetが開く**。人間の判断である。

前の形の何が違ったか。長押しですぐ選択に入るのは、「このMemoに何かしたい」を「複数のMemoを選びたい」へ勝手に読み替えていた。
たいていは一つのMemoに一つのことをしたいだけで、そのために選択という状態を通らされていた。Sheetにすると、選択は
**答えの一つ**に戻る。

並びは、いつもの操作（Pin・Tag）、Memoを動かす操作（複製・ノートへ・選択・リンクをコピー）、しまう操作（Archive・ゴミ箱）。
ゴミ箱だけ赤くしてある。UpNoteの「クイックアクセス」は星を外したときに無くした概念なので入れず、「再配置」は表示条件の並び順が
担っているので入れていない。代わりにこのAppにあるもの——Tagとノートとリンク——を入れた。

新しく作ったのは三つ。**複製**はTitleに「のコピー」を付ける。`[[リンク]]`はTitleで解決するので、二つのMemoが同じTitleを
持つと指せなくなるからである。**ノートに追加**はそのMemoを末尾の話にする。ノートが無ければその場で作れて、そのMemoが最初の話になる。
**リンクをコピー**は`[[タイトル]]`をclipboardへ置く。Titleの無いMemoは指せないので、そこだけ押せなくして理由を書いた。

1件でも複数でも同じ道を通る点は変えていない。1件は要素が一つのSetとして同じBulkの経路へ入り、Snackbarも取り消しも同じである。

unit test 479本、実機instrumentation 124本、lint 0 error。

### One way to act on a memo, not two

Cardの⋮を外し、Memoへの操作を長押しからの選択に一本化した。人間の判断で、Keepに倣っている。

同じことをする道が二つあったのが問題だった。⋮からは1件へPin・Archive・ゴミ箱、長押しからは複数へPin・Tag・Archive・ゴミ箱。
数が1か複数かだけで別のUIを通っていて、しかも⋮の方はTagを掛けられなかった。長押しに寄せると**1件でも複数でも同じ道**になり、
できることの差も消える。

Card自体には何も置かない。選択していないときのCardは、押せるものが自分自身しか無い状態になった。選択中だけ印を出す。
アウトラインの棚も同じMemoなので、同じように長押しできる。

⋮を外したことで、単体用の`archive`/`moveToTrash`/`setPinned`と`lifecycleEvents`が誰からも呼ばれなくなったので消した。
1件を選んでBulkを掛けると同じ結果になり、取り消しも同じSnackbarから出る。

なおこの変更で、Memoへの操作は**発見しづらくなった**。Keepも同じ性質を持つ。⋮は目に見えるが、長押しは知っている必要がある。
それでも道が一本である方がよいという判断である。

unit test 479本、実機instrumentation 124本、lint 0 error。

### Three tabs, and a place for writing that has an order

Memo画面をメモ／アウトライン/ノートの3tabにし、ノートという入れ物を作った。人間の判断である。壁打ちを重ね、実機サイズの
画像を二度出してから着手した。

**tabの違いは「流れるもの」の違いにした。** メモとアウトラインは同じMemoの同じ壁で、片方は散文を、もう片方は骨組みを流す。
見た目の違い（高さの揃わないCardと、同じ大きさの棚）は、その声の違いを目で見える形にしたものである。
3つが横並びに見えて実は「2つは見方・1つは入れ物」という非対称は、隠さずそのまま置いた。

その前段として、**本文が流れるようにした。** それまで流れていたのは記法のある行だけで、一方で読み上げは本文を全部読んでいた。
これは意図ではなく残っていた歪みだった。散文は文へ切って流す。動きながら読むものだからである。記法のある行は長くても切らない。
記法を付けたのは書き手が「これで一つ」と言ったということなので。

**壁のstreamは一つのMemoずつではなく、Memoから一行ずつ順番に取る。** 一つを読み切ってから次へ行くとqueueになる。混ぜると
書庫が喋る。頼まれるまで流れないのは、読む画面と同じ判断である。

**ノートは順番のあるMemoの束**とした。Room 15→16、Backup 10→11。既存のMemoは全部「どのノートにも属さない」状態で残る。
章は入れ物ではなく見出しにした。話は一本の並びで、番号は章をまたいで通り、章に属さない話は先頭に来る。書き始めはまだ章が
無いからである。ノートを消しても話は残る。消えるのは並び順と章だけで、これは実機testで確かめてある。

**題の無い話を下書きとした。** 状態を記録する列は作っていない。題を付けた瞬間が下書きでなくなる瞬間であり、それは
書かれたものが既に語っていることなので、読み取るだけにした。ここまでの「textで表せるものはtextで持つ」の延長である。

実装して初めて分かったことが二つあった。

一つ目。**`memos`の`noteId`に外部キーを付けられなかった。** SQLiteは既存tableに外部キーを足せず、足すにはtableの作り直しが要る。
`memos`はComment・Tag・写真の親なので、制約ひとつのために作り直すのは、ぶら下がっている全部を危険にさらす。「ノートを消したら
話を解放する」はDAOの1文にした。読める場所に置いた方が安全である。migration testが落ちて気づいた。

二つ目。**Cardの壁では「前」が「上」ではない。** 2列なので、順序を確かめるtestが位置の比較のままでは意味を成さなくなった。
読み順（上、同じなら左）で比べるよう直した。

unit test 479本、実機instrumentation 123本、lint 0 error。

### A drawer for where to look, and one mark instead of two

左上の☰をMenuからDrawerへ変え、Memoの星（お気に入り）を外した。どちらも人間の判断である。

**Drawer**にした理由は、Tagの一覧がMenuに収まらないからである。Menuは項目が決まっているものの置き場で、Tagは
Memoが増えるほど伸びる。Drawerは上から「見る範囲」（すべて／固定／アーカイブ／ゴミ箱）、次にTag、最後に一覧そのものへの
操作（表示条件、選択、読み込み、書き出し、設定）という並びにした。今見ている範囲がselectedで分かる。Tagを一つ押すと
その範囲になり、同じTagをもう一度押すと外れる。Tagがまだ無い間は空欄にせず、そうなる条件を書いてある。

DrawerはMemo一覧の中に置いた。App全体へ持ち上げると状態も一緒に持ち上がる。この位置ならBottom navigationが隠れず、
Drawerを開けたまま日記へ移れる。選択中はedge swipeを無効にした。整理の最中にDrawerが顔を出すのは邪魔である。

**星**は外した。上部固定とTagがある以上、三つ目の「特別な印」は選ぶ手間が増えるだけで、どれを使うべきかも曖昧になる。
Filterも「すべて／ピン留め」の二つになり、Card上のiconも一つ減った。

ただし**`isFavorite`列は消していない。** 消すにはRoom 15→16のmigrationが要り、既に星を付けたMemoの情報が戻らない形で
失われる。§14.2は実際にデータ契約が変わるときだけversionを上げよと言っている。ここで変わったのは見せ方であって、
持ち物ではない。列、DAO、`MemoRepository`のsetter、Backupのmapping、そしてそれらのtestはそのまま残してあるので、
Backupの往復も従来どおりで、必要になれば戻せる。UIから届かなくなっただけである。

unit test 448本、実機instrumentation 118本、lint 0 error。

### The photo strip shows what is there, and nothing else

MemoとDiaryの本文上にあった「写真 0/20枚」と「写真を追加」のtileを外した。人間の判断である。追加はKeyboardの上のAction列に
既にあり、写真が一枚も無いrecordで大きな空の置き場所が本文の場所を取っていた。

Stripは在るものだけを見せる。写真が無ければheaderごと何も出さない。写真があればthumbnailだけを出し、Add tileは無い。
取り込み中だけspinnerを出す。押せる場所ではなく、進んでいることの合図である。

**Diaryには書式Toolbarが無かった。** Memoは`toolbar_add_photo`がKeyboardの上にあるので前提が成り立つが、Diaryの
`DiaryEditorActions`は保存状態と確定Buttonだけで、tileを外すと**写真を追加する道が無くなる**。依頼どおりtileは外した上で、
Diaryの確定Buttonと同じ行の左端へ`diary_add_photo`を置いた。書く画面から写真へ届く道は常に一本ある。

`PhotoAttachmentStrip`から`onAdd`を落とした。Stripは追加を知らなくてよい。呼び出し側3箇所（Memo Editor、Diary Editor、
Archive/Trash）も合わせて整理した。Room、Backup、`AttachmentLimits`は変更していない。

unit test 449本、実機instrumentation 117本、lint 0 error。

### The heading mark moved off the hash

見出しの記号を`# `から`■ `へ変えた。人間の判断である。理由は二つある。

一つは、`#`がこの記法の中で二役を持っていたこと。行頭の`# `は見出しだが、書き出し・読み込みでは`#仕事`がTagである。
同じ字が二つの意味を担っていた。

もう一つは、[[folding]]で報告した制限。よそで書かれた`## `が見出しとして読めず、折りたためなかった。`#`を見出しの席から
外したことで、`# ` `## ` `### `を**読む**ための入口として素直に開けられるようになった。hashの数が深さになる。深さの表し方は
Indentとhash数の二通りになるが、**書くのは常にIndentひとつ**で、hashは読むためだけにある。ここは非対称のままでよい。

**既存メモは何も壊れない。** `# 見出し`は今も見出しとして読まれる。Toolbarが新しく書く記号が`■ `に変わっただけで、
本文の一括書き換えはしていない。RoomもBackupも変更していない。

書き出しの扱いだけは、以前決めた「変換せずそのまま出す」を**意図して改めた**。`■ `はよそのEditorが知らない印であり、
よそから見えない見出しは書き出しとは言えない。見出しだけは等価なhashへ書き換えて出す。深さがhashの本数になる。
読み込み側がhashの見出しを読むので、この書き換えで失われるものは無く、往復して同じ文書に戻ることはunit testで確かめた。
書き換えるのは見出しだけで、他の行には一切触れない。

unit test 449本、実機instrumentation 116本、lint 0 error。

### Folding needed a place without a caret

折りたたみ見出しとインポートを追加した。Room v15とBackup formatVersion 10は変更していない。

**折りたたみ**は素直に実装できない。[[inline-decoration]]で「Editorは記法を隠さない。offsetが1対1のままになり、
保存されている形と見えている形が一致する」と決めてある。折りたたみはテキストを隠すことなので、これと正面から衝突する。
`VisualTransformation`で隠せば`OffsetMapping`が要り、caretが隠れた領域に入る余地が生まれる。自分で書いた規則を黙って破る
わけにはいかないので、**caretが存在しない場所**を作った。

**閲覧モード**である。Playbackを読み取り専用の閲覧状態にしたとき（§17）と同じ考え方の延長で、本文を編集可能なfieldではなく
文書として描く。caretが無いので記法を隠しても壊れるものが無く、隠せるからこそ折りたためる。同時に、編集可能なfieldでは
無理だったことが二つできるようになった。**Checkboxをその場で叩ける**ことと、**`[[リンク]]`に触れて辿れる**ことである。

閲覧モードは何も書かない。Toolbarは「すべて折りたたむ／展開」と「編集」だけにし、Tag、写真、Titleも編集を誘わない状態にした。
書かないモードが書けそうに見えるのが一番悪い。

見出しは次の同階層以下の見出しまでを所有する。折った見出しには隠した行数を出す。折りたたみ状態はsource lineで持つので、
Checkboxを叩いても位置がずれない。永続化はしていない。記法が消えるぶん意味も消えるので、`! `と`? `には小さなiconを、
`- `には中黒を、`> `には縦罫を当てた。

**インポート**は書き出しと同じ形で読む。`---`で区切り、先頭の`# `がTitle、末尾のhashtagだけの行がTag。往復してもとに戻ることは
unit testで確かめてある。`---`もhashtag行も無いファイルは1件のメモになる。よそのMarkdownはそれが正しい。Front matterは
ファイルの説明であって本文ではないので落とす。

件数を見せて承諾を得るまで何も書かない。書くのは新しいメモだけで、今あるメモは変えない。取り消したければゴミ箱へ入れれば済む。
ファイルが指定したTagは無ければ作って付ける。ファイルがそう言っている以上、それがそのメモの居場所だからである。ただし
hashtag行と判定するのは**末尾のhashtagだけの行**に限る。本文中で`#仕事`に触れただけの行をTagと読み違えないためである。

なお、この記法は`# `だけを見出しとして扱う。よそのファイルの`## `は本文として、書かれたまま残る。書き出しが`## `を作ることは
無いので往復は壊れないが、外から持ち込んだ見出しは折りたためない。記法を増やすと深さの表し方が二つになるので、増やさなかった。

unit test 439本、実機instrumentation 115本、lint 0 error。

### Six more writing tools, none of them a schema change

Checkbox、文字数、全文検索、書き出し、Memo間リンク、Templateを追加した。[[inline-decoration]]で採った
「textで表せるものはtextで持つ」という判断をそのまま延長し、**Room v15とBackup formatVersion 10は変更していない**。
復元v1〜10、Drive、読み上げ、既存Backupはそのまま動く。

**Checkbox**は`- [ ] `／`- [x] `のCommonMark記法とした。`- `で始まるので`WorkCommentSyntax`では項目より先に判定する。
Toolbarの一つのActionが「無し→未完了→完了→無し」と一巡する。完了行は消さず取り消し線で示す。Playbackでは淡く流れる。
終わった仕事もそのMemoの一部だからである。

**文字数**はMarkerを外した「読まれる文字」を数え、Keyboardの直上へ置いた。書いている最中に見えなければ意味が無い。
タスクがある本文では進捗も同じ行に出る。

**全文検索**はスペース区切りAND、`"..."`、`#タグ`、`-除外`に対応し、NFKC畳み込みで半角カナと全角英数を互いに見つける。
結果Cardは冒頭ではなく当たった行を見せ、一致語へ印を付ける。Archiveとゴミ箱も同じ判断を通るようにしたので、
SQLのLIKEで一語だけという振る舞いは残っていない。構文はEmpty stateで教える。結果が無いときが最も読まれるからである。

**書き出し**はMarkdown。本文がもともとMarkdownの形なので、変換せずそのまま出す。EditorからはそのMemo一件、
一覧からは**表示中のMemo**を書き出す。検索やタグで絞ってから書き出せて、「すべて」という第二の隠れた概念を作らずに済む。
写真は含めない。Memo全体を運ぶのはBackupの仕事である。

**Memo間リンク**は`[[タイトル]]`。idではなくタイトルで結ぶので、書き出しても壊れず、Backupから戻しても効き、
まだ無いMemoも指せる。未作成のリンクは落とさず「（未作成）」として見せる。先に参照を書くのは普通の書き方である。
Editorは向こう向きのリンクと逆参照の両方を、タグや写真と同じ場所に出す。どれも「このMemoがどこに属するか」を言うものだからである。

**Template**だけは判断が違う。Templateは書かれたものではなく書くための道具なので、Roomではなく設定と同じDataStoreへ置いた。
その結果Backupには含まれず端末内に留まる。これは隠さずSheetに書いてある。Backupへ載せるならformatVersion 11の判断になるので、
そのときは§14.2に従って別途諮る。

挿入ActionはOverflow menuではなくToolbarに置いた。Overflowは`state.exists`を待つが、Templateが最も要るのは
まだ保存されていない新しいMemoだからである。

Markerを外して読む処理は`BodyText.readable`一箇所へ集約した。読み上げ、Playback、Preview、検索が同じ判断を共有する。
unit test 412本、実機instrumentation 113本、lint 0 error。

### Inline decoration carried in the body text

太字・ハイライト・文字色を追加した。保存方法は人間の判断でMarkdown準拠の記法とし、本文text内へ持つことにした。`**太字**`、
`==ハイライト==`、色付きは`==red:文字==`。色idはCommentが既に永続化している`CommentColorRole`をそのまま使うので、色の意味が
アプリ内で一つに保たれ、Backupの互換を新しく考える必要も無い。

この選択によりRoom v15とBackup formatVersion 10は**変更していない**。復元v1〜10、Drive、読み上げ、既存Backupがそのまま動く。
装飾情報を別に持つ案も検討したが、Room 15→16とBackup 10→11、復元互換、Drive、migration testの工数が大きく、§14.2が求める
「データ契約が実際に変わるときだけ上げる」に照らして、textで表せるものをわざわざ構造化する理由が無かった。

`InlineTextMarkup`をdomainへ置き、spanの検出、strip、選択範囲のtoggleを純Kotlinで実装した。unit test 13本。読み上げ
（`SpeechTextPreprocessor`）とPlayback（`WorkCommentParser`）は記法を外したtextを受け取る。Commentは言葉だけを運ぶという既存の
考え方をそのまま延長した。

Editorでは記法を隠していない。`VisualTransformation`で記号を淡色にし、中身へboldとhighlightを当てるだけにしてある。記号を隠すと
offset mappingが必要になり、caret位置や選択がずれる余地が生まれる。隠さなければoffsetは1対1のままで、保存されている形と見えて
いる形も一致する。UpNoteのmarkdown modeと同じ考え方である。

### Keyboard toolbar rebuilt around the caret

note、Notion、UpNoteのmobile editorを調べ、Keyboard直上のToolbarに何が並びどう振る舞うかを比較した。三者に共通するのは、
Toolbarがcaretのある行または選択へ作用すること、同じ書式を再度押すと外れること、Undo／Redoが同じ帯にあること、そして
Keyboardを閉じるActionが常に届く位置にあることだった。Notionは横scrollする帯に`+`、image、H、Undo／Redo、indentを並べ、
閉じるActionを右端へ固定する。UpNoteはmarkdown shortcutを軸にし、backでKeyboardとToolbarを同時に畳む。

比較の結果、MemoRipple側に明確な不具合が見つかった。Toolbarが`body + prefix`で**常に本文末尾へ追記**しており、caretの位置を
無視していた。行の途中で「見出し」を押すと、離れた末尾に`# `だけが増える。押すたびに記号が積み重なり、外す手段も無かった。

`WorkOutlineEditing`をdomainへ追加し、caret行または選択が触れた全行に対してtoggle、置換、indent／outdentを行う純Kotlin関数として
実装した。`WorkCommentSyntax`が認識する記号とINDENT_WIDTHをそのまま使うので、Playbackとspeechの解釈と必ず一致する。unit testを
11本置いた。UI側は`BasicTextField`を`TextFieldValue`ベースへ変え、selectionをToolbarへ渡している。

Toolbarの構成は写真追加、Undo、Redo、記号5種、Outdent、Indentを横scrollさせ、Keyboardを閉じるActionだけ右端へ固定した。写真は
Keyboard表示中にPhoto stripを畳む変更で触れなくなっていたため、ここから戻した。Undo／RedoはEditorのbody専用で、Memo一覧の
Snackbar undoとは別物である。「子項目」chipは廃止した。`  - `という固定の組み合わせしか作れず、Indentならどの記号にも効いて
`WorkCommentSyntax`のdepthをそのまま表現できる。

Rich text、checkbox、table、slash menuは実装していない。本文はWork Comment parserが読む素のtextであり、これらはRoomとBackupの
形式変更を伴う。§14.2に照らして、データ契約を変える理由が product decisionとして示されるまでは持ち込まない。

### Editor cursor tracking and the IME

Memo editorで入力すると、cursorが画面外へ出たまま追従せず、Top barが画面外へ押し出されてTitleがStatus barと重なっていた。
原因はManifestに`windowSoftInputMode`が無く、systemがCompose階層からscroll可能領域を検出できずADJUST_PANを選んでいたこと。
`enableEdgeToEdge()`は呼ばれているのにIMEがinsetとして届かず、OSがwindowごとpanしていた。`dumpsys`の
`mFocusedWindowSoftInputMode=STATE_UNSPECIFIED|ADJUST_PAN`で確定した。`adjustResize`を宣言し、両EditorでIME insetを消費する。

insetを正しく扱うと、今度はcontent高さが実際に縮む。ここで二つのことが分かった。一つ目は、scroll可能なcolumnの中で
本文欄の高さを無制限にすると`BasicTextField`が内部scrollを持てず、cursorを自分でview内へ運べないこと。高さは有界にし、
かつviewport内で終わるようにしないと、cursorはbox下端＝折り返しの外に置かれる。二つ目は、MemoRippleのeditorは本文の上に
Title、Tag row、Photo stripを常駐させており、keyboard表示中はそれらだけでviewportをほぼ使い切ること。実測で本文に残る高さは
約13dpだった。Tag rowとPhoto stripはtypingの道具ではないので、keyboard表示中だけ畳む。閉じれば戻る。本文は約215dpを得る。

Playback controlsとStructure toolbarはkeyboardの直上へ固定した。書いている最中に届かない位置へ落ちるのはShort-height layouts
の「primary actionを画面外へ固定しない」に反する。再生／一時停止／再開はTop barのaction列へ移した。Top barは幅が限られるため
transportは常に1個だけ置き、停止は固定bar側に置く。二つ並べると最右端が1143pxとなり1080pxの画面から溢れてclipされる。

最後に、`updatePlaybackBounds`の「再生領域がresizeされたら停止する」既存契約とぶつかった。keyboardの開閉がresizeになるため、
一時停止を押した瞬間にfocusが外れてIMEが閉じ、PLAYINGからPAUSEDを飛ばしてIDLEへ落ちる。計測ではclick直前`pause=1 stop=1`、
直後`play=1`。安全契約に例外を作らず、再生中はTitleとBodyを`readOnly`にしてkeyboardが開けないようにした。再生は鑑賞modeとする。
typing中に再生を押した場合はkeyboardが閉じ切ってから再生を開始する。この順序ならresizeはまだIDLEのうちに済み、停止契約に触れない。

検証はPhysical SC-51Aで行った。`adjustResize`でfocus時にlayoutが動くようになったため、文字入力直後にmain clockを止めて操作する
instrumentationが、layoutの落ち着く前に次の操作へ進んで競合する。再生も「keyboardを閉じてから開始」するので、clockを止めた
testではその待ちが進まない。実利用では起きない。該当5箇所へ`closeSoftKeyboard()`と`waitForIdle()`を足して、製品と同じ順序を
testでも明示した。assertionは一つも削っていない。

なお、この3件の破綻はemulatorでは検出できなかった。hostのmemory圧でsoftware GL renderingへ落ちた状態のemulatorはsuite一回に
47〜50分かかり、変更の有無に関わらず毎回別のtestが落ちる。同じ3件をPhysical SC-51Aで走らせるとpush済みHEADは`OK (3 tests)`、
変更ありは3件失敗と、差が一度で出た。所要2分13秒。emulatorが劣化している間は、判定にPhysical deviceを使う方が速く確実である。

### Card boundary contrast after the flat-surface redesign

再設計でDiary Cardは塗り分けをやめ、`Surface`と1dpの境界線だけで面を示す形になった。その境界線が`outlineVariant`だったため、
Light背景に対して1.17:1しかなく、WCAG 1.4.11が非テキストへ求める3:1を大きく下回っていた。角丸と余白でも面は判別できるが、
低視力ではCardの範囲が伝わりにくい。

`outlineVariant`はcode内8箇所のうち5箇所がdivider（top bar、bottom navigation、SectionHeader、Memo Editor）で、Card境界は3箇所だけ。
tokenごと濃くするとdividerまで太くなるため、role分離を採った。`outline`はThemeに定義済みでどこからも使われておらず、Material 3の
意味づけでも`outline`は意味のある境界、`outlineVariant`は装飾的な区切りである。Card境界を`outline`へ移し、`outline`自体を
Light `#B3BEB8`／Dark `#3E4842`にした。dividerは`outlineVariant`のまま変えていない。

実機で4候補をrenderして比較した結果の採用値。Card面に対してLight 1.91:1／Dark 1.80:1で、境界が読める一方で線自体は主張しない。
3:1を満たすのは更に濃い案だけだが、線が視覚要素として立ち上がり静けさを損なうため採らなかった。LightとDarkの見え方が揃うことも
選定理由に含む。`outline`はMaterial componentのOutlinedTextFieldやSwitchのborderにも既定で使われるため、それらも1.43:1から
1.79:1へ改善した。Goldenは日記Cardを含まないため再recordは不要。

### Compact top bar at large font scales

font scale 2.0で実機確認したところ、Bottom Navigationは64dp内へ収まり（内容59.8dp）切り詰めは無かった。一方Diaryの
compact top barは高さ56dp固定だったため、Tab labelの「カレンダー」が「カレンダ」へ切り詰められ、Tab groupの中央配置も崩れていた。
DESIGN_SYSTEMは「Textは縮小せずwrap/scrollを優先」「narrow/large textはscrollまたはwrap」「Tab groupを画面中央からずらさない」を
求めており、これに反していた。barの56dpを下限へ変え、必要な高さまで伸ばして折り返す形にした。既定の折り返しでは長音符だけが
行頭へ落ちるため、`LineBreak.Heading`でCJKの行分割規則を適用し「カレン／ダー」となるようにした。

font scale 1.0では幾何が変更前と一致することを実測で確認した。indicatorは3.0dp、全幅dividerとの隙間は0dp、Tab幅112dp、
Tab group中心は画面中心と一致。なお切り詰めはsemantics上のtextを変えないため、この退行はnodeベースのtestでは検出できない。
Golden matrixを広げる判断は別途とし、現時点では手動QA項目として扱う。

### Tag sheet reachability while the IME is shown

Memo一覧のFlakyなinstrumentationを追う過程で、`TagFilterSheet`と`BulkTagSheet`が入力欄と末尾Actionを同時に持ちながら
`imePadding()`を持たず、bounded LazyColumnも末尾Actionへ高さを譲らないことが分かった。DESIGN_SYSTEMの「Bottom sheets」と
「Short-height layouts」はkeyboard表示中も末尾Actionへ到達可能にすることを求めており、この2つのSheetだけが例外になっていた。
同一fileの他Sheetは既に`imePadding()`を使っている。両Sheetへ`imePadding()`を足し、LazyColumnを`weight(1f, fill = false)`で
上限つきかつ譲る形へ変えた。

計測はホストのmemory圧でemulatorがsoftware GL renderingへ落ちた状態で行った。修正前は`apply_tag_filter`のtapが取りこぼされ、
待機を60秒へ広げても15回中3回失敗した。つまりtimeout不足ではなくActionが押せていない。Sheet修正だけで25回中23回、
testでIMEを閉じてから触る形を足して25回中25回になった。timeoutは5秒のままとし、広げていない。

### Phase 9C / 9C.1 photo viewer gesture QA

Photo Viewerは黒い没入surface、fit-center開始、横swipe、pinch zoom、zoom中pan、double tap 1x／2x、前後Action、現在位置、
editable時だけの確認付き削除を共通契約とする。SC-51A（API 33）へ縦・横・正方形・wideを含む6000〜8000px級JPEG 5枚を
実Photo Pickerから取り込み、左右swipe、pinch、pan、double tap、reset後swipe、削除後index補正、Light／Dark、rotation安全終了を確認した。
Phase 9Bの並べ替えも実ハンドル操作後に「完了」で保存し、Viewer先頭画像へ永続順が反映された。

実機指では2xから1xへ戻す2回目のdouble tapだけが、わずかな指ぶれをzoom中pan recognizerが先にconsumeして失敗した。変形開始前の
one-finger移動をtouch slopまで蓄積し、slop未満のtapをdouble-tap recognizerへ残すよう修正した。Multi-touchは即時、slop超過後のpanは
同じ累積deltaから開始するため、pinch／panの応答性とgesture conflict契約を維持する。2px／1px jitterを含むInstrumentationで
1x→2x→1xを固定し、SC-51A上でもユーザー実操作による成功を確認した。

Viewerを5回ずつ計10回開閉・巡回した参考値は、5回後TOTAL PSS約335.9 MiB、10回後約336.2 MiBでplateauし、FATAL／ANR／OOMは
なかった。大画像decodeは既存target-size sampleとLRUを維持し、20枚物理fixtureは端末汚染を増やさずPhase 9A.1のAPI 36実20枚回帰へ
委ねる。Samsung TalkBack実聴では閉じる、削除、前／次、画像位置、zoom actionの意味とfocus順に問題がなく、QA後はAccessibility、
touch exploration、Dark／rotation／font scale／stay-awakeを作業前状態へ戻した。QA専用memo、内部attachment、端末・host fixtureは削除した。

## Intentional exceptions

- Future Revealのblack/white/grayは受取contextと可読性の契約。
- Overlay/Stage/Comment rendererの固定色、font size、glyph paddingは再生契約。
- Future Revealは通常TopAppBarを使わず、overlay animationのため独自Back/Stop配置を維持。
- Memo本文はBasicTextFieldを維持し、Autosaveやwriting surfaceをCardで囲わない。

## Deferred observations

### Phase 9A photo attachments

Memo／Diaryは同じPhoto Strip／Viewerを使い、複数選択、20枚上限、sampled thumbnail、fit-center preview、確認付き削除へ到達できる。Photo-only MemoとDiaryをcontentとして扱い、DiaryはDRAFT／CORRECTINGだけ編集、FINALIZED／LOCKEDはread-onlyとする。Archive／Trash lifecycleではowner relationを保持し、permanent delete後だけreference-aware GCする。

Font 1.5、landscape、360×640dpでは既存screen scroll契約内にstripを置き、本文と確定Actionを失わない。Light／Dark／SystemではMaterial surface rolesでborder／placeholder／Dialog contrastを維持する。Future Reveal source photoはblack animation contextを圧迫しない安全なlayout検討が必要なためtext contextを維持した。Camera、caption、OCR、generic file、zoom/edit/shareはfollow-upでありPhase 9Aへ含めない。

### Phase 9A.1 photo boundary QA

Disposable API 36 Emulatorでprogrammatic fixture 20枚を実Photo Pickerから一括選択した。20/20到達時は追加tileが消え、「20枚の写真を追加しました」と静かに結果を示す。横strip、Viewer 1→6/20、Portrait→Landscape recreation、process stop／restartを通してANR、index crash、relation lossはなく、photo-only recordは「無題のメモ／写真20枚」として復元した。Viewerはrotation時に安全に閉じる現契約を採用する。

同じ20枚datasetのDocumentsUI backup、Preview（Memo 1件／写真20枚、hash・filename非表示）、二段階確認、Full Restoreが成功した。表示はthumbnail／viewerともtarget-sampled decodeを使い、20枚操作後の参考値はTOTAL PSS約129.5 MiB、FATAL／ANR／OOMなしだった。Physical TalkBack実聴と実Google Drive v10 mutation smokeは行わず、自動semanticsとFake Drive file-streaming regressionをrelease gateとして維持する。

- LandscapeでDiary Editorの本文とFuture Sectionを同時に広く見せるadaptive two-paneはNavigation/Layoutの大変更になる。
- Settingsがさらに増えた場合の検索・deep linkは大型機能として別Phaseで検討する。
- CalendarのMonth Picker、年表示、任意Swipe navigationは長期Diary向けの将来候補とする。
- Tag color、階層、mergeは別Phaseで検討する。
- Screenshot regression framework、dynamic color、final branding/localizationは別Phaseとする。
