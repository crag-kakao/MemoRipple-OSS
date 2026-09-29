# MemoRipple Design System

Material 3の操作性・状態管理・accessibilityを土台にしながら、Memo・Diary・Comment・Settingsへ
MemoRipple固有の視覚言語を適用する基準。中心となる考え方は「静かな記録の上で、言葉だけが動く」。
Material componentの初期値を並べるのではなく、色・形・余白・情報量をこの文書の役割へ制限する。

## Design principles

### Quiet

書くこと・読むことを邪魔しない。大きな装飾、常時動く演出、強すぎる警告色を避ける。

### Content First

MemoとDiaryの本文を最重要のVisual Elementとし、操作UIは必要な時に見つけられる強さへ抑える。

### Progressive Disclosure

Comment編集、再生方法、Overlay詳細、Backup等の高度な操作はSheetやSettingsにまとめ、Editorへ常時並べない。

### Clear Consequence

Diary確定、Future Comment送信、Restore、Deleteは、実行後に何ができなくなるかをDialog本文と動詞のButtonで示す。

### Consistent Interaction

同じ意味のActionには同じIcon、位置、語彙を使う。BackはTop barとSystem Backで同じ保存・cleanup経路を通す。

### Calm Motion

AnimationはSheet/Dialog、状態変化、Comment playbackの理解に使う。画面表示時の装飾motionは追加しない。

### Accessible by Default

主要操作は48dp以上、Icon-only buttonにはcontentDescription、見出しにはheading semanticsを持たせる。
Textは縮小せずwrap/scrollを優先し、Light/Dark双方でMaterial color roleを使う。

## Foundations

### Quiet Ripple UI language

- `Primary = 意思・決定`。画面の主Action、選択状態、再生開始だけに使い、大面積の装飾へ使わない。
- `Surface = 作業面`。Editor、Card、Sheet等、内容または操作が独立した面に使う。
- `Background = 静かな余白`。Screen全体のcanvasに使い、Surfaceと同色にせず、面の階層を消さない。
- 1画面／Dialog／SheetのPrimary Actionは原則1つ。同じ作成Actionを中央ButtonとFABで重複させない。
- 通常UIは静止し、製品固有のmotionはComment playbackへ集中させる。
- Dynamic Colorは標準採用せず、Light／DarkのQuiet Ripple paletteを一貫して使う。

### Spacing

`ProductSpacing`を反復レイアウトの基準にする。

| Token | Value | 主用途 |
|---|---:|---|
| `xs` | 4dp | Chip内、密接した補助情報 |
| `sm` | 8dp | Label間、IconとText、compact padding |
| `md` | 12dp | List/Card内の要素間 |
| `lg` | 16dp | Section内、標準vertical rhythm |
| `xl` | 24dp | Sheet、Empty State、広い区切り |
| `xxl` | 32dp | Sheet末尾、強いSection分離 |

画面左右余白は`ProductSize.screenHorizontalPadding = 24dp`をアプリ全体の**唯一の左端**とする。Editorも一覧も
Sheetもこの値を使う。上部barは自身の12dp paddingに48dp icon boxの半分が加わって同じ24dpへ届くので、☰とCardの
左端が縦に揃う。近いが一致しない値（20dpと24dp）が同居すると、画面を移るたび文字がわずかに飛び、それが最も
安く見える。Calendar modeの8dpだけが意図的例外である。

Spacing tokenに無い値を直接書かない。5dp・7dp・9dp・18dpのような値は、実機を見ながら1pxずつ動かした跡として
読まれる。Tokenがあるのに守られていない状態は、Tokenが無い状態より悪い。
Comment rendererのglyph safetyや衝突間隔は描画仕様であり、このSpacingへ置換しない。

### Shape

Material 3 `Shapes`をThemeで定義する。

| Role | Radius |
|---|---:|
| extraSmall / small | 8dp |
| medium | 12dp |
| large | 16dp |
| extraLarge | 24dp |

Card、Status Chip、Dialog、Sheetは`MaterialTheme.shapes`を優先する。特殊なComment renderer形状は維持する。

### Typography

直接sp指定を避け、Themeで上書きした次のMaterial 3 roleを使う。

| Product role | Material role / size |
|---|---|
| Brand title | `displaySmall` 28sp / 36sp / SemiBold |
| Screen title | `titleLarge` 24sp / 32sp / SemiBold |
| Memo/Diary identity | `headlineSmall` 20sp / 28sp / SemiBold |
| Section title | `titleMedium` 16sp / 24sp / SemiBold |
| Body | `bodyLarge` 16sp / 26sp |
| Supporting text | `bodyMedium` 14sp / 23sp |
| Secondary body | `bodySmall` 13sp / 21sp |
| Metadata | `labelSmall` 12sp / 18sp |
| Button / Label | `labelLarge` 15sp / 20sp / SemiBold |
| Warning | body role + `colorScheme.error` |

日本語を流す役は**行高を字送りの1.6倍以上**取る。仮名と漢字は全角boxに詰まるので、Latin向けの1.3倍では字面が
潰れて内容と無関係に安く見える。`bodySmall`はMetadataではなく**Cardの中身を読ませる役**なので、12sp/16spから
13sp/21spへ上げた。12spが面の主役になっている画面があれば、それは役の選び違いである。

Comment playbackの18sp×fontScaleは再生仕様なので例外とする。

再生速度とコメントサイズは設定のsliderで連続値（速度×0.80〜×2.00、サイズ90%〜160%、5%刻み）。正確な値は
端末ローカルに持ち、backupの語彙（ゆっくり/標準/速い・小さめ/標準/大きめ）は凍結されたままなので、slider
を動かすたび**最も近い名前**も書き戻す — 他端末へrestoreしても近い速さに着地する。restoreと設定リセットは
名前を正とし、slider値を消す。適用点は`PlaybackSettingsApplier`一箇所。

流れるコメントの書体は設定「コメントのフォント」で選ぶ（デフォルト＝端末の文字（既定・意図的にnull）／
ゴシック体＝同梱のNoto Sans JP, OFL-1.1／明朝体＝同梱のNoto Serif JP, OFL-1.1／丸文字体＝同梱の
Kosugi Maru, Apache-2.0）。名前の付いた書体はすべて同梱で、どの端末でも同じ字を描く — system任せでは
serifが和文をゴシックに落とす端末や、OEM・持ち主が字を替えた端末で約束が破れる。端末ローカルの
「見え方」設定であり、バックアップにも設定リセットにも含めない。描画と事前計測
（`measureCommentTextMetricsPx`）は同じ`LocalCommentFontFamily`から取ること。計測だけ既定書体のままだと、
幅の当てが外れてlane割りが崩れる。

コメントは既定で、縁取り（`COMMENT_CUTOUT_EDGE` 1.6dpのstroke、色は板のshadow色）だけの
くり抜き文字で流れる — 動画サイトの字幕の描き方。設定「コメントの文字枠」をオンにすると半透明の板を
着る。縁は計測が確保している2dpのglyph safety paddingの内側に収まるので、計測は板の有無を知らなくて
よい。設定「コメントの透過」（0〜80%、ニコニコのコメント透過に倣う）はコメント全体 — 文字・縁・板 —
に一枚のalphaとして掛かる（`graphicsLayer`）。オーバーレイも同じ設定を読む。

### Color

設定「外観」（旧・テーマ。ライト/ダーク/システム。backupに残る唯一のテーマ設定）に加えて、
**テーマカラー**と**パレットスタイル**を持つ（2026-08-30）。テーマカラーは配色の種:
既定（ブルー #1E6FD9 — launcherの雫と同じ色）／Material 15色。ダイナミックは持たない
（2026-08-30、人間の決定で削除）。
パレットスタイルは種の広げ方で、Material Color UtilitiesのVariant（TonalSpot〜Content）を
そのままの名で選ぶ。すべての種で有効。生成は常にMaterialKolor
（`rememberDynamicColorScheme`）に任せる — 手組みのミントパレットは退役し、アプリは自分の
アイコンと同じ青で目覚める。splash等のXML側（`memo_primary`/`memo_background`）も同じ青系。両設定とも端末ローカル（backup書式は凍結のまま）。

LightではPrimary `#166B5B`、Background `#F6F8F5`、Surface `#FFFFFF`を基準にする。
DarkではPrimary `#8FD3BA`、Background `#0F1412`、Surface `#171D1A`を基準にする。
選択状態は`primaryContainer`へ統一し、無関係なlavender等を混在させない。
surfaceContainer階調、inverse系、onBackground、surfaceTintまでThemeで明示する。未定義ロールはMaterial baselineの
紫寄りneutralへfallbackし、Sheet地色やSnackbarのactionとして画面へ出るため、`colorScheme`任せにしない。
Sheetは`Surface`と同じ面として扱うので、`surfaceContainerLow`はSurface相当の値にする。
`tertiary`系はQuiet Ripple第3accentの決定が未了のため意図的に未定義のまま残し、新規UIで使わない。
通常UIは`MaterialTheme.colorScheme`のsurface、surfaceContainer、onSurfaceVariant、primary、errorを使う。
固定色を許可する例外は、Future Revealのblack context、Overlay/Stageの可読性、Comment rendererのshadowとglyph色。
これらは背景上の可読性と演出契約のためTheme色へ置換しない。

### Buttons and cards

- Primary Buttonは52dp以上、12dp radius、動詞labelを持ち、画面の主目的へだけ使う。
- Tonal Buttonは可逆な二次Action、Text Buttonは閉じる／戻る／補助Actionへ使う。
- Error Filled Buttonは完全削除等の最終確認に限定する。通常画面の破壊Actionはerror色のText／Outlineを使う。
- FABは**icon一つ**。何を作るかは立っているtabが言っているので、labelは壁で一番幅を取る要素になる。
  読み上げのために`contentDescription`へは何を作るかを入れる。
- FABは**常設**。0件でも検索中でも消さない。主要Actionが空のtabで消えるのは、最も必要な瞬間に無いということ。
  隠すのは選択中だけで、そこでは対象が別にあるので押しても何も作らない。
- 0件Empty StateにPrimary Buttonを置かない。FABが既に同じことを言っているので、二つ目は視線を割るだけになる。
- Cardは独立した内容単位またはCard全体が操作対象の場合だけ使う。Editor、Empty State、Settings各行、単なるSectionを囲わない。
- **Chipに`heightIn(min = 48dp)`を掛けない。** Material3のclickableな`Surface`は`minimumInteractiveComponentSize()`で
  タッチ領域を48dpへ広げるが、描画されるboxは32dpのままである。ここへ`heightIn`を足すと**箱まで48dpになり**、
  Chipがボタンに見える。Editorのタグ行では、空の「+ タグ」が本文より大きい要素としてTitle直下に居座っていた。
  他のcomponent（Row、ListItem、IconButton）は自前でタッチ領域を確保しないので`heightIn`が要る。Chipだけが例外である。
- Memo/OutlineのCardは**quiet filled card**（1.0 visual polish・2026-09-03で確定）。通常カードは枠を持たず、`surfaceContainerLow`の塗り・`shapes.large`・カード間の余白だけで区切る。**選択中のカードだけが1dp `primary`の枠**を着る——その線は状態であって装飾ではない。かつてのoutlined card（Page同色＋1dp `outline`枠）は下記の測定とともに歴史として残す:
  Keepがそう描いている——塗りの差も影も無いので、境界線が唯一の縁になり、薄くても目が拾う。
  分離の手段は一つに絞る。塗り・境界線・影を重ね掛けしない。
- **その境界線は`outline`であって`outlineVariant`ではない。** 測ると、Pageに対して`outlineVariant`は1.13:1で
  肉眼では引かれていないのと同じ、`outline`はlightで1.73:1・darkで1.96:1。Keep自身の境界線は白地に約1.35:1
  なので、これで十分強い。
  かつてこのappは境界線をCardから外したことがあるが、あれは**手段ではなく色の選び違い**だった。
  「見えないから境界線をやめる」と一般化する前に、他の色で測る。
- 選択中のCardは境界線を`primary`にする。塗りが変わるのは同時でよいが、縁も一緒に応える。
- Cardを入れ子にしない。Cardの中にもう一枚Surfaceを敷くと、Cardではなく額縁に見える。

### Editor and playback surfaces

- 定型文（TextSnippet）はショートカットバーの挿入族の一員: テンプレートが「メモ一枚の型」を差すのに
  対し、定型文は「よく使う言葉・文」をカーソル位置へ差す。棚（sheet）の中で追加・編集・削除まで完結し、
  端末ローカルでバックアップに含まれない。

- ショートカットバーの棚（テンプレート・メモへのリンク・コメントリンク）は**一度に一枚**。別の道具を押せば入れ替わり、
  同じ道具をもう一度押せば閉じる。積み重ならないので、✕の後ろに前の棚が残ることはない。棚は編集画面の
  窓の中に立つ（modal sheetは別窓でkeyboardを下げてしまう）が、手には再生設定sheetと同じ約束を守る：
  下から滑り上がり、handleを持ち、handleか見出しを下へ引けば（高さの四分の一か、速い一払い）滑り去る。
  中のlistは自分でscrollするので、引けるのは棚の頭だけ。テンプレートの棚は「テンプレートを作成」で
  名前と内容の棚に変わり、保存すると一覧に戻って新しい一枚が先頭に立つ——設定一覧へは行かない。

- **列を運ぶ動きは一つ**。コメント一覧の並べ替えも、設定のショートカットバーの並べ替えも、同じ`CardDragController`：
  handleを持った列が浮いて指に付いてき、隣の列は通り過ぎるときに滑ってよける（`animateItem`）。離すと一度だけ
  順を書き、列は自分の作った場所に落ち着く。閾値ごとに入れ替わる段階式のdragは使わない。
- **閲覧モードから筆へ戻る扉は三つ**。タイトルはsingle tap（keyboardはタイトルへ）、書かれた行はdouble tap
  （caretはその行の末尾——文章の続き——、keyboardは本文へ；タイトルもtapで末尾へ）、何も書かれていない場所のdouble tapは今まで通り編集モードへ
  （fieldは取らない）。読み上げか再生の最中は、どの扉も開かない——tapは再生のもの。

- **コメントSheetに見出しは無い**。「新しいコメント」と書かれたfieldの上に「コメント」と書く価値はなく、
  Sheetのhandleが閉じ方も言っている。✕も無い——swipe・scrim・backで足りる。最初の一行は書く場所である。
- コメントの行は**書かれたものだけ**：本文と、あれば表現サマリ。操作は**長押しで訊く**（表現を変更・削除）
  ——Memo Card・Note行と同じ文法。並べ替えは**モード**で、＝と「作成順に戻す」はそのモードと一緒に現れる。
- 行の区切りは他と同じ**ページ色＋`outline`枠**。drag中は枠が`primary`になり影が付く。
- コメントの色は**ニコニコと同じ10色・同じ並び**：標準（白＝themeの文字色）・赤・ピンク・オレンジ・黄・緑・
  シアン・青・紫・黒。各色はsurfaceごとに可読側へ振る（lightの黄はオーカー等、既存の方針のまま）。
  **黒だけはedgeを持つ**——暗いstageでは白いshadowを纏う。あちらが黒コメントを白フチで描くのと同じ理由。
- 表現sheetの composer 変種は**設定内容を保持する**switchと**リセット**を持つ（ニコニコの対）。
  保持ONなら送信後も表現が残り、端末が記憶する。既存コメントの表現編集にはどちらも出ない——
  あれは次のコメントの話ではない。
- コメントのchipは**持ち主のお気に入り**（既定の6つは出発点）。tapで書き、**長押しで削除を訊き**、
  行末の＋で追加。上限10（あちらと同じ）。listの上の帯は「コメント一覧」と**編集**（＝並べ替えモードの入口）。
- 表現の変更はどこでも**palette**（🎨）。⋮は「メニューが開く」という約束のiconなので、直接何かを開く用途に使わない。
- 空のコメント欄は**一行の静かな文**。器の半分を使って空を語らない。作成日時は行に描かない——
  一言の下に毎回timestampが並ぶと、本文より説明が長い行になる。

- タスク記法も**箱を見せる**。`- [ ] `/`- [x] `は本文に残るが、描画では☐/☑と空白の二文字に置換する
  （`TaskGlyphs`）。太字と違い隠すだけでは箱が残らないので、ここだけは本物のOffsetMappingを張る——
  単調・全域で、caretは箱の両側に立て、backspaceは記法を一文字ずつ食う。未完の箱は`primary`。
- ハイライトの色選びは**focusを取らないpopup**（`PopupProperties(focusable=false)`）。BottomSheetは
  自分のwindowでkeyboardを引き下ろす——書く道具がしてはならないことである。
- **壁は起動を一度だけ迎える**。アプリを開くと、壁にコメントが流れる（`autoPlayOnLaunch`、既定ON、
  設定>コメントでOFF可）。流れるコメントはこのappの中心で、最初の一回は自己紹介である。
  process毎に一度きり：後から書いても勝手に再生しない。壁のロードを待ち、stop-on-change効果の後に息を
  一拍置いてから流す。
- 混合の壁だけが**Keepの身振りで寝かせられる**。メモとアウトラインの表示では、検索ピルの右の
  トグルが1列（横いっぱいのカードが縦に並ぶ、平たい壁）と2列（160dpに詰まる格子）を行き来する。
  アイコンは押した先の形を見せる——本家がそうするように。選んだ形は端末ローカル
  （`wallSingleColumn`）で、バックアップにもリセットにも関わらない。**単体表示は形を変えない**：
  メモのみはKeepカードの段組みのまま、アウトラインのみは骨組みの棚のまま。旧タブの姿こそが
  それらの正体で、トグルは出ない。
- 表示（メモのみ/アウトラインのみ）は**モードであって絞り込みではない**。帯にも出ず、クリアにも
  戻されない。帯とクリアが引き受けるのは本物の絞り込み——ピン留め・タグ・普段と違う並び——だけで、
  クリアは選んだ表示に手を触れない。空の絞り込み画面の「すべて表示」だけが、表示まで含めた帰り道。
- 並び順は**︙に住む**。表示条件のシートは「なにを見るか」（表示・対象・タグ）だけを持ち、
  「どの順で見るか」は︙の並び順（現在値を添え書き）から一段のメニューで選ぶ。
- 並び順の名前は 更新が新しい順 / 作成が新しい順 / タイトル順 / **手動で並べた順**（2026-09-21）。並び順は「見方」であって絞り込みではない：
  タブ下の帯（絞り込みの帯）には出さず、クリアでも戻さない。
- **壁は壁の上でだけ喋る**。メモを開く・タブを替える・アプリを離れる——壁が前面でなくなった瞬間に
  streamは停止する（ON_PAUSEで`stopWall`）。遷移の上を漂わず、凍ったまま待ち伏せて戻った人に
  再開もしない。留守中に喋り続けるのはoverlayの仕事で、壁の仕事ではない。
- 動きは**placementで、生成はmountで**。コメントの毎フレームの座標はModifier.offsetのlambda内で
  時計（`State<Long>`）を読んで計算する——recomposeもremeasureも起こさず、箱を置き直すだけ。
  コメントは`key(item.id)`で自分の同一性を持ち、一枚の退場が後続の再構築を誘発しない。
  frame clockはnanosの端数を次のframeへ繰り越す——時間を落とせば遅れ、不均等に配れば震えになる。
- 太字の削除は**見えている文字の削除**。markerは不可視である以上、その中に落ちたキーボード編集は
  「見えていた編集」に読み替える（`InlineMarkupEditing.guard`）：末尾backspaceは最後の一字、
  範囲削除はmarkerを丸ごと残すか丸ごと消すかの二択、中身が尽きたspanはmarkerごと消える。
  IME変換中（composition非null）は一切触らない。
- 太字は**太字を見せる**。`**`のmarkerは本文に残る（file formatは彼らのもの）が、描画では透明の
  極小サイズで畳む——目に届くのは記法ではなく結果。mappingは恒等のままなので、caretはmarkerの中を
  歩けるし、backspaceは一文字ずつ消せる。
- ハイライトの色選びは**コメントパレットと同じswatch**。同じパレットなのだから同じ顔をする。
  名前付きchip十個は段落で、色十個は一瞥である。
- 画面上部の**帯は一つ**。外側のscaffoldがすでにステータスバーを避けているのに、TopAppBarが
  自分でももう一度避けていた——同じ空を二度譲る死んだ帯である。バーのinsetsはゼロにし、高さは
  コンパクトバーと同じ56dpに揃える。タイトルはバーの直下に立つ（上xs・下sm）。
- タグ行は**設定で消せる**（設定>編集「タグを非表示にする」、`editorHideTags`、端末ローカル）。
  消えるのは編集画面の行だけで、壁のカードは自分のタグを着たまま——書く面を空けたい人のための
  設定であって、タグをやめる設定ではない。
- ショートカットバーは**通常一段、望めば二段**。一段は書く道具がスクロールし、届くべきもの
  （コメント・設定・停止）が右端に固定される低い帯。設定>編集でオンにすると、編集の道具（写真・
  履歴・太字・ハイライト・散文記号）が上段、挿入と構造（テンプレ・リンク・チェックボックス・
  見出し・階層）が下段の二段に立ち、固定の尾は下段の右端に残る。端末ローカル
  （`editorToolbarTwoRows`）で、バックアップにもリセットにも関わらない。
- Toolbarは**文の種類に従う**。episode（prose）ではルビ・「」・『』・……・――を出し、
  見出し・項目・チェックボックス・階層は出さない。looseなメモではその逆。
  買い物リストにダッシュは要らず、小説に箇条書きの深さは要らない。判定は`state.isEpisode`。
- Toolbarのbuttonは**caretを奪わない**。Compose 1.7からtouchでもclickがfocusを動かし、fieldからfocusが
  抜けてkeyboardが閉じる——書く道具が書く姿勢を壊す。barとコメントsheetの操作は全て
  `focusProperties { canFocus = false }`（`keepsCaret()`）を宣言する。
- Toolbarのglyphは**22dp**（sheet・drawerと同じ）で、48dp targetの間に追加のspacingを置かない。
  targetは既に13dpの空気を持っている。
- 一枚のtext fieldでできたdialog（ノートの名前・サブタイトル・章）は**caretとkeyboardを連れて開く**。
  打つために存在する画面が、まずtapを要求してはいけない。
- Editor下段は**書く姿勢と一緒に出入りする**。書く道具（写真・undo・太字・記法・ルビ・括弧…）は
  title/bodyのfieldがfocusを持つ間（またはkeyboardが上がっている間）だけで、それ以外ではその一段は
  💬（コメント）・⚙（再生設定）・■（停止）だけになる。読み返している画面に本文以外がほぼ無くなる。
  gateがIMEだけでないのは、「写真を追加」のように一文字も打たずに使う道具があるからで、
  道具はcaretを置いた瞬間に出る。
- 再生の方式（本文上／ステージ／オーバーレイ・再生内容・オーバーレイの領域と密度）は**端末が憶える**。
  ▶は前回の方式で一押しで流れる。保存先は`SettingsRepository`のdevice-local key群で、壁の表示と同じ理由で
  backupにも初期化にも含めない。オーバーレイの権限確認だけは毎回で、これは飛ばせない。

- Screenが自分の名前を名乗らない。Memo Editorの直下にはMemoのTitleがあり、Diary Editorの上部barには日付がある。
  最も広いslotで「メモ」「日記」と繰り返すのは、そこへ辿り着いた道が既に言ったことの反復である。`ProductTopBar`の
  `title`はnullableで、nullならslotを空にする。
- Editor下部のtoolbarは**一段**。分け方は「**scrollするのは書く道具、固定するのはそれ以外**」の一本にする。
  - scroll側: 写真、undo/redo、太字、ハイライト、テンプレート、リンク、checkbox、見出し／項目／補足／重要／疑問、階層
  - 固定側: コメント、再生設定、停止（再生中のみ）、keyboardを閉じる
  横scrollの奥は「低頻度」ではなく**到達不能**である。誰も見つけられない設定は存在しないのと同じなので、
  頻度が低いことを理由にscrollの向こうへ送らない。
- Toolbarは**日本語の散文が要る記号**を持つ。`「」`『』・`……`・`――`・ルビ。このappの記法（見出し・項目）は既に
  並んでいるが、小説を書く人が一行おきに要るのはそちらではない。`……`と`――`はflickで出しにくい記号の代表である。
- 開き括弧を**手で打った**ときだけ閉じ括弧を足す。貼り付けは打鍵ではないので何もしない。
- ルビは`｜親文字《ふりがな》`。投稿sitesが読む書式をそのまま持つので、貼れば向こうで組まれる。`BodyText.readable`が
  剥がすので、読み上げ・検索・文字数は言葉の側を見る。
- Keyboardを畳むButtonは置かない。IMEは自分の閉じる手段を持っており、systemのback gestureもある。
- Commentを書く場所には、**入力欄の左にPalette、右に送信**を置く。Paletteは選ばれている色で着色し、開かずとも
  今の設定が読めるようにする。表現の全文はcontentDescriptionへ入れて、読み上げからは失わせない。
- Comment表現のsheetは「**labelと、その横に小さな選択肢**」の行を積む。Section headingは置かない。行が自分の名前を
  持つので、見出しは同じことを二度言うだけになる。
  - 色は色そのものの丸で見せる。色の名前を読むより速い。
  - 大きさは`A`をその大きさで描く。ただし**倍率を誇張しない**。0.90／1.00／1.15は実際に狭く、広く見せるのは嘘になる。
  - 選択肢は34dpで描き、selectableな`Surface`がtouch targetを48dpへ広げる。

- Memo EditorはCardではなく静かな紙面。Title／BodyをOutlined boxで囲わず、本文領域を最大のvisual elementにする。
- Memo本文自体は動かさず、Comment Layerだけを動かす。Layerはscroll、tap、keyboardを奪わない。
- Flow Commentはchat bubble化せず、文字と局所的なshadow／outlineで可読性を確保する。
- Fixed Commentは必要時だけ局所的な半透明Surfaceを許可し、全幅bannerにはしない。
- StageはCommentのための専用dark surfaceとし、control UIはplayback layerから分離する。
- 書式ToolbarはKeyboardの直上へ固定し、caretのある行に作用させる。本文末尾へ追記しない。
- 同じ記号を再度押したら外す。別の記号は置き換える。積み重ねない。選択が複数行にまたがる場合は触れた全行へ同じ判断を適用する。
- 階層はIndent／Outdentで操作し、記号と組み合わせる。「子項目」のような固定の組み合わせをToolbarへ並べない。
- Toolbarは横scrollさせ、Keyboardを閉じるActionだけはscroll域の外へ固定する。項目が増えても閉じられなくならないようにする。
- 見出しは`■ `。Toolbarが書くのはこれひとつ。`#`は世の中ではTagの印でもあるので、この記法では一つの意味に絞る。
- ただし`# ` `## ` `### `も見出しとして**読む**。よそで書かれたファイルがここでも同じように畳めて読めるようにする。hashの数が深さで、Indentと同じように足し合わせる。
- 深さの表し方はIndentとhash数の二通りあるが、書くのは常にIndentひとつ。hashは読むためだけの入口である。
- 本文の装飾はMarkdown準拠の記法として本文textへ持つ。`**太字**`、`==ハイライト==`、`==red:文字==`。RoomとBackupは変更しない。
- 装飾の色idはCommentと同じ`CommentColorRole`を使う。色の意味をアプリ内で一つに保つ。
- Editorは記法を隠さず、記号を淡色にして中身へstyleを当てる。offsetが1対1のままになり、保存されている形と見えている形が一致する。
- Playbackと読み上げへ渡すtextからは記法を外す。Commentは言葉だけを運ぶ。
- 流す範囲は設定で選ぶ。「記法のある行だけ」か「本文すべて」か。読み上げが本文を全部読む以上、流れる側だけ一部というのは筋が通らない。
- 散文は文へ切って流す。Commentは動きながら読むもので、段落のままでは読めない。
- 記法のある行は長くても切らない。記法を付けたのは書き手が「これで一つ」と言ったということである。
- Checkboxは`- [ ] `／`- [x] `のCommonMark記法で持つ。Toolbarの一つのActionが「無し→未完了→完了→無し」と一巡する。
- 完了した行は消さず、取り消し線と淡色で示す。書いたものはEditorに残る。
- Memo間の参照は`[[タイトル]]`として本文textへ持つ。idではなくタイトルで結ぶので、書き出しても壊れず、まだ無いMemoも指せる。
- 未作成のリンクは落とさず「（未作成）」として見せる。先に参照を書くのは普通の書き方である。
- Templateは書かれたものではなく書くための道具なので、Roomではなく設定と同じDataStoreへ置く。端末内に留まりBackupには含まれない。Sheetでそう明示する。
- 文字数はMarkerを外した「読まれる文字」を数える。Keyboardの上に置き、書いている最中に見えるようにする。
- 記法を外して読む処理は`BodyText.readable`一箇所に集める。読み上げ、Playback、Preview、検索が同じ判断を共有する。

### Notes

- ノートは順番のあるMemoの束。Memoは今までどおり単体で存在でき、ノートに入っても同じMemoである。
- 表紙は描く。TitleがあればそのRecordは既に名前を持っており、最初の一語より先に絵を要求しない。
- 章は入れ物ではなく**見出し**。話は一本の並びで、第N話の番号は章をまたいで通す。章に属さない話は先頭に来る。書き始めはまだ章が無いからである。
- 行は⌄でその場に開き、表紙とTitleはノート自身のPageへ進む。読むのと整えるのは別の場所でする。
- ノートのPageはtable。複数選んでまとめて章へ移すために使うので、listではなくtableの形にする。
- **題の無い話は下書き。** 何も記録しない。題を付けた瞬間が下書きでなくなる瞬間であり、それは書かれたものが既に語っている。
- 話を読むPageは組みを変える。行間を広く取り、段落に字下げを入れる。書きかけのMemoではなく読み物として扱う。
- 読んでいる間、**▷を押すまでコメントは流れない。** 流すものが無い話では▷を押せなくする。
- ノートを消しても話は残る。消えるのは並び順と章だけ。

### Reading mode

- 空行は**書かれたとおり**残す。`ReaderProse.paragraphs`は空行を空のentryとして返し、pageは1行分
  （30sp）の空きとして描く。原稿の呼吸を詰めない。末尾の空行だけは、そこで終わるpageに意味を持たないので
  落とす。
- 読み上げにはコメントと同じ三つの動詞がある: 読んでいる間は**一時停止と停止**が並び、一時停止中は
  **再開と停止**。engineは語の途中で息を止められないので、一時停止＝文を覚えて止める・再開＝その文を
  頭から読み直す（pageが立ち止まる粒はどのみち文である）。止まっている間もその文のtintは残り、
  どこから戻るかをpageが指し続ける。
- 閲覧モードは**アシスト読書**を持つ。読み上げ中、いま話している文が淡いtint（primary 16%）を着て、
  ページはその文を上1/3へ連れてくる。文をタップするとそこから読み直し、止まらず次の文へ歩き続ける — ただし声が**係のとき**
  （読み上げ中か一時停止中）だけ。停止後の静かなpageはタップしても黙っており、読み上げボタンが唯一の入口。
  一つの定義（`ReaderProse`）が画面の切り方と声の切り方の両方なので、カーソルは必ず正しい文を指す。
  声は`SpeechController.speakSegments`に文のリストをそのまま渡し、engineのutterance境界＝文境界になる。

- ルビ（`｜親文字《ふりがな》`）は閲覧モードだけが**描く**。他の面（壁・コメント・検索・カード）は
  `BodyText.readable`が親文字へ畳む。閲覧は`readableKeepingRuby`で記法を残し、`ProseTyping.rubyRuns`で
  切って、ルビ組ごとにinline boxとして流し込む。箱の底はTextBottomで本文と同じ床 — 同じ書体の同じ
  descentなのでベースラインが合う。ルビは親文字の半分（`RUBY_SCALE` 0.5）、30spの行間が既に空けている
  肩の上に立つ。ルビ組は行中で折り返さない。

- Editorは記法を隠さない。隠すとOffsetMappingが要り、caretと選択がずれる余地が生まれる。折りたたみはこれと両立しないので、caretの無い**閲覧モード**に置く。
- 閲覧モードは何も書かない。Toolbarは「すべて折りたたむ／展開」と「編集」だけ。Tag、写真、Titleも編集を誘わない状態にする。
- 見出しは次の同階層以下の見出しまでを所有し、折りたたむとそれを隠す。折った見出しには隠した行数を出す。
- 折りたたみ状態はsource lineで持つ。Checkboxを叩いても行数は変わらないので位置がずれない。永続化はしない。
- 閲覧モードでは記法を外し、効果だけを残す。Checkboxはその場で叩け、`[[リンク]]`は触れて辿れる。未作成のリンクは見せるが、辿れるふりはしない。
- Markerを持たない種類（`! `重要、`? `疑問）は、記法が消えたぶんを小さなiconで補う。`- `は中黒、`> `は縦罫。意味が消えないようにする。

### Export

- 本文はそのまま出す。Item、Checkbox、装飾はよそのEditorでも同じ意味になる。
- 例外は見出しだけ。`■ `はよそが知らない印であり、よそから見えない見出しは書き出しとは言えない。見出しは等価なhashへ書き換えて出し、深さがその本数になる。
- 読み込み側がhashの見出しを読むので、この書き換えで失われるものは無い。往復して同じ文書に戻る。

### Import

- 書き出しと同じ形で読む。`---`で区切り、先頭の`# `がTitle、末尾のhashtagだけの行がTag。どれも無いファイルは1件のメモになる。
- Front matterはファイルの説明であって本文ではないので落とす。
- 件数を見せて承諾を得るまで何も書かない。書くのは新しいメモだけで、今あるメモは変えない。
- ファイルが指定したTagは、無ければ作って付ける。ファイルがそう言っている以上、それがそのメモの居場所である。

### Search

- 語はスペース区切りでAND。語を足すほど絞れる。`"..."`は語順を保ち、`#名前`はタグだけを見て、`-語`は除外する。
- 照合はNFKC畳み込みで行う。半角カナと全角英数が互いに見つかる。タグの照合と同じ規則を使う。
- 検索はMarkerを外した本文を読む。`これは**太字**です`は`これは太字です`で見つかる。
- 結果Cardは冒頭ではなく「当たった行」をMarkerを外して見せ、一致語へ印を付ける。なぜ結果なのかがCard自身で分かる。
- 検索構文はEmpty stateで教える。結果が無いときが最も読まれる場所である。

### Memo tabs

- Memo画面の最上段は**検索fieldそのもの**。検索はiconで入るmodeではなく、画面の一行目が言葉を受け取る。
  fieldの中は☰（Drawer）とfieldだけ。右の⋮がlistへの命令（表示条件・選択・読み込み・書き出し）を持つ。
  tabはその下の行、**左寄せの二語**。全幅セル中央に置くと二語が画面の1/4と3/4に散り、間に何もない帯が走る。
- **非テキストの面も3:1**。線に課した規律を面にも課す。白いpillはこのページに対して1.11:1、
  `primaryContainer`のFABは1.10:1で、どちらも**影だけで立っていた**。pillはCardと同じ
  「ページ色＋`outline`枠」に合流し（画面の面言語が一つになる）、FABは`primary`塗り＋`onPrimary`グリフ
  （lightで5.8:1）。壁で唯一の塗りが、初めて目に見える。
- 壁の▶は**pillの外**、pillと⋮の間に立つ。pillのlabel「メモを検索」は器の中身の意味を規定するので、
  壁全体を流す▶が中に居ると「検索結果を再生する」と読める。流せる時は`primary`で灯る——
  中心体験がこの画面で色を持つ唯一の場所。IconButtonは本物のdisabled stateを持つ。
- **左端のrailは24dpの一本**。pill・tabの語・Cardの左端が同じ柱に揃う。縦に読むとき視線の柱が
  段ごとにずれるのが、色より先に素人に見える。
- **選択中の語彙は一つ**：選ばれたものは`primary`の文字で語る（上tabも下navも）。下線はtabだけの持ち物で、
  tabとnavの区別はそこに残る。
- barはtab行の下に髪線を持つ。**色はページの`background`のまま、スクロールしても変わらない**（利用者のreview、
  2026-09-25、HANDOFF §16.99）。以前は壁がスクロールしている間`surfaceContainerHigh`へ一段持ち上がっていたが、
  上端の色が変わるのが気になるとの判断で固定にした。どこでbarが終わるかは髪線が言う。
- Memo画面のtabは**メモ／ノート**の2tab。横swipeでも移る。別のtitle行は作らない。
  かつてアウトラインが3つ目のtabだったが、そこに並ぶのは1つ目と**同じMemo**だった——本文に`■`を一行書くと
  Memoが黙って隣のtabへ移り、書き手ではなく記法が置き場所を決めていた。**壁は一つ**にする。
- 一つの壁の上で、Cardが自分の種類を言う：記法のあるMemoは**縦の罫の後ろに見出しをスラッシュで連ねた一行**
  （例：`│ 発端 / 港の場面 / 結末`、見出しが無ければ記法行の文で代用）、無いMemoは冒頭の言葉を見せる。
  統合の壁でCardが言うべきは「自分が何者か」までで、構造を原寸で読む場所は「アウトラインのみ」表示が持っている。
- 旧2tabの見え方は**表示条件の「表示」**として残る：メモとアウトライン（既定）／メモのみ／アウトラインのみ。
  Keepのgrid/list切り替えと同じ場所づけで、絞っている間は壁の上に「◯◯のみ・クリア」の行が出る。
  選んだ表示は端末に記憶するが、**backupにも「設定を初期値に戻す」にも含めない**。復元が他端末の読み方を変えるべきではない。
- どちらであるかは記録しない。書かれたものから読む。
- 絞った表示が空でも壁が埋まっていることは普通に起こるので、空のときは**どちらの意味で空か**を言う。「メモがありません」とだけ言うのは、よそにある場合には嘘になる。
- Tab indicatorは**labelの幅**に合わせ、上端だけを丸めた3dpにする。tab全幅の帯は十年前のtabの描き方で、一目で
  年代が分かる。幅と中心をpager offsetで補間するので、追従のslideは保つ。
- **メモ**はKeep風の壁。Cardの高さは中身に従う。Checkboxのあるメモはboxを、他は冒頭の言葉を見せる。※Checkboxも記法なので、実際にはCheckboxのあるMemoは棚側に居る。
- Outlineは**話になれる**。`■`の行がそれぞれ話のTitleになり、その下の行がその話の本文の書き出しに入る。
  骨組みは既に書かれているので、Noteにするために同じ言葉をもう一度打たせない。
  - 橋は**一方向**。作ったあとは別々のMemoで、覚書は一字も変わらない。双方向にすると「見出しを直したら三千字が
    どうなるか」に答えを出さねばならず、その答えは誰かが覚えておく規則にしかならない。
  - **深さは読まない。** 入れ子の見出しも普通の話である。Outlineの段下げは「どれだけ近い話か」を言うだけで、
    それ以上の意味を持ったことがない。ここで意味を与えると、書くときに「これは章になる深さか」を考えさせる。
  - Blockが共有するindentだけ外す。内側の段差は書かれたまま残す。
  - `■`を持たないMemoにはこの項目を出さない。空のNoteは答えではない。
- 並べ替えは**mode**である。「章と並び順の編集」で入り、「完了」で出る。modeの中だけhandleと「新しい章を追加」を
  出す。外では行は読むものなので、全行に握り手が付いていると読むものに見えない。
- 章のoverflowも同じmodeに属す。読んでいる最中は、見出しを消す方法を差し出す場面ではない。
- 章もhandleで動かす。episodeと同じ仕組みに揃え、`CustomAccessibilityAction`も同じように置く。
- 章の追加は**両端のどちらか**を訊く。入力欄と「冒頭に追加」「末尾に追加」の一画面で、押す瞬間に位置が決まる。
  途中へ入れるのは移動であって、移動はhandleの仕事である。
- 表の行に**話数を書かない**。順番そのものが番号なので、行ごとに書くのは同じことを二度言うことになる。
  ただし読み上げは行を特定できねばならないので、handleの説明はTitle（無題ならその語）で言う。
- Noteは**一本の並び**である。章の見出しはepisodeの親ではなく、episodeとepisodeの間に立つ行にすぎない。
  handleはどの行でも同じ挙動——一行ずつ動き、見出しはepisodeを越えられる。
- **所属は位置から読む。** 見出しの下に来たepisodeがその章のものになり、見出しより上へ出れば章から外れ、
  すべての見出しより上ならどこにも属さない。`chapterId`は持ち続けるが、引きずるのではなく落ち着いた場所から
  書き戻す。書き込みは一つのtransactionにまとめる——半分だけ反映された並びは誰も書いていないNoteである。
- ドラッグの相手は掴む場所（handle）にし、同じ移動を`CustomAccessibilityAction`でも名前で出す。
  handleのtest tagは行ごとに一意にする。共通のtagは、二つ目の章ができた瞬間に二件一致する。
- Noteは**名前の次にsubtitle**を訊く。任意のものなので二枚目に置き、空でも`完了`できる。誰も止めない順で訊く。
- Noteの自分のpageにcoverは出さない。Titleは上のbarにあり、coverは一覧で見分けるためのものなので、場所はsubtitleへ渡す。
- 次を書くButtonは**画面下**。下へ読む表の上に置かない。手が既にある所へ置く。言い方は数で変える——
  一つも無ければ「エピソードを執筆」、あれば「次のエピソードを書く」。
- Noteも**長押しで訊く**。Memoと同じgestureで、名前・subtitle・**表紙の色**・削除。写真だけは自分のpageに置く。
  押した手応えは**行の全体**に出す。Memo Cardは押せば丸ごと光るのに、Noteの行は真ん中だけが光っていた——
  行の一部だけが応えるのは、応えたというより誤射に見える。反応する範囲は、押せる範囲と同じ形にする。
  実物の大きさを見ながら選ぶものだからで、`NoteCoverColorSheet`は写真の操作を渡さなければ色だけのsheetになる。
- Noteのpageでは、Noteが自分について言う一行を左、**数えたものを右**に置く。読むものとちらりと見るものが
  同じ列を分け合う必要はない。章のButtonも右へ寄せる。
- Noteは**名前を入れてから作られる**。押した瞬間に作ると「新しいノート」ばかりの棚になり、開くまで区別がつかない。
  問いだけの画面を一枚使う。何も作られていないので、出口は「キャンセル」ではなく「閉じる」である。
- Noteのcoverは**色の上に写真**。写真は任意で、無ければ色だけ。ファイルが失われても色は残るので、cover が穴に
  ならない。写真の実体は`AttachmentBlobStore`のsha256内容addressなので、Memoに貼った写真をcoverにしても実体は一つ。
- Noteに入れたMemoは**メモの壁から降りる**。ノートで書いたものはノートで読む。同じ文章が壁とノートの
  両方に並ぶと、どちらが本体なのか読み手にも書き手にも決められない。消えたのではなく**行き先が決まった**
  だけなので、ノートから外せばそのまま壁へ戻る。エピソードに「アーカイブ」を出さないのも同じ理由で、
  ノートから見えない場所へ一話だけ移す操作は、ノートを穴あきにする。
- Noteのcoverに**文字を描かない**。Titleはどのcoverの隣にも必ず並んでいるので、先頭数文字を重ねるのは同じことを
  二度、しかも下手に言うことになる。数文字では二つのNoteを見分けられない。coverは**色で見分ける**ものにして、
  その色を書き手が選べるようにする。色見本は実際に描かれるのと同じ16:9で並べる。色の名前より色そのものが速い。
- Noteのcoverは**16:9**。呼び出し側はwidthだけを渡し、高さは`aspectRatio`が出す。縦横を別々に書くと、近いが
  一致しない数の組（100×60など）で静かに比率が崩れる。
- **アウトライン**は同じ大きさの棚。Card一枚の中にTitleと骨を積む。全部同じ形なので、長さではなく構造が比べられる。
- 棚の中は**4段の型階層**を持つ。Title `titleSmall`／見出し `bodySmall`+SemiBold・onSurface／項目 `bodySmall`・onSurface／
  注釈と問い `bodySmall`・onSurfaceVariant。同じ大きさのまま太さと色で段を作る。
  tab名が「骨組み」を約束している以上、見出しが見出しに見えないのは機能が成立していない状態である。
- 段下げは1段16dp。文字より狭い段下げは階層として読めない。
- 選択中はtabのswipeを止める。整理の最中にtabが変わらないようにする。
- FABは今のtabが作るものを作る。ノートのtabで「新しいメモ」を出さない。
- Card自体には操作を置かない。overflowも星も出さない。Memoに何ができるかは**長押し**から辿る。
- 長押しは**そのMemoについて訊く**。Sheetが答えるのもそのMemoについてで、選択に入るのは答えの一つに過ぎない。
- Sheetの並びは、いつもの操作（Pin／Tag）、Memoを動かす操作（複製／ノートへ／選択／リンク）、しまう操作（Archive／ゴミ箱）。ゴミ箱だけerror色にする。
- 1件でも複数でも同じ道を通る。1件は要素が一つのSetであり、結果もSnackbarも取り消しも同じ。
- 選択中だけCardへ印を出す。選択していないときのCardは、押せるものが自分自身しか無い状態にする。
- アウトラインの棚もMemoの壁と同じMemoなので、同じように長押しできる。
- 複製のTitleには「のコピー」を付ける。`[[リンク]]`はTitleで解決するので、二つのMemoが同じTitleを持てない。

### Wall playback

- メモとアウトラインのtabは、**壁全体の上を流れる**streamを持つ。Cardごとに流さない。
- 壁の声は既定で各Memoの**タイトル欄だけ**（点呼）。設定「タイトルではなくメモの内容を流す」をONに
  すると本文（朗読）になる。起動時の挨拶と▶は**同じ声**であり、この設定に一緒に従う。オーバーレイへ
  送るときもserviceが同じ設定を読み、同じものを流す。
- 内容を流すとき、Streamはそのtabに居るMemoから**一行ずつ順番に**取る。一つのMemoを読み切ってから次へ行くとqueueになる。混ぜることで「書庫が喋る」。
- 何を取るかがtabの違いそのもの。メモは散文を、棚は骨組みを送る。同じMemo、同じCard、声だけが違う。
- **頼まれるまで流れない。** 操作は書くButtonの反対側に置き、**同じFABの族**にする。大きい方が新しく始め、
  小さい方が既にあるものを聞かせる。役の高さが同じなのに部品系が違うと、どちらが主役か言えなくなる。
- 停止は**上へ積む**。再生中に横へ伸びると、押した指の下でlayoutが動く。
- FABにdisabled状態は無いので、言うことが無い壁では色を落として`semantics { disabled() }`を付け、位置は保つ。
- 壁が変わったらstreamは止める。表示されていないMemoから作ったstreamは何についてでもない。
- 上限は120行。壁が持てる行数と、人が見ていられる行数は別である。
- 設定「メモホームのコメントをオーバーレイで流す」をONにすると、壁の▶はstreamを自分の空ではなく
  オーバーレイ（他アプリの上）へ送る。関門（権限の開示・置き換え確認・通知の選択）はEditorの▶と同じ
  `OverlayLaunchGates`を通る。**起動時の挨拶は常にinline**。開いただけのアプリが権限dialogを歩かせては
  ならない。オーバーレイへ送るのは押した瞬間の壁のMemo idsで、serviceが本文を読み直す。

### Navigation drawer

- 左上の☰はMenuではなくDrawerを開く。Tagの一覧はMenuに収まらない。Memoが増えるほど伸びるからである。
- **Drawerには行き先だけを置く**：見る範囲（すべて／固定／アーカイブ／ゴミ箱）、Tag、タグを管理、設定。
  listへの命令（表示条件・選択・読み込み・書き出し）はbarの⋮へ移した。場所と動詞が同じ縦一列に並ぶと、
  開くたびに全部読むことになる。
- Drawerの幅は**300dp**。一語の行き先の列に画面の大半は要らない。Tagが無いときの説明文も置かない——
  「タグを管理」がすぐ下に居て、存在自体が同じことを言っている。
- 今見ている範囲をselectedで示す。Tagを一つ選ぶとその範囲になり、同じTagをもう一度押すと外れる。
- Tagが無い間は「メモにタグを追加すると、タグがこちらに表示されます。」と書く。空の場所を黙って空にしない。
- DrawerはMemo一覧の中に置く。Bottom navigationは隠れないので、開いたままカレンダーへ移れる。
- Drawerは場所の一覧であり、その中に「フォルダ」の木を持つ（2026-09-18、docs/FOLDER_NAVIGATOR_AUDIT.md）。行は深さ16dpずつ字下げ（6段で頭打ち）、子を持つ行だけchevron、開いているフォルダは`secondaryContainer`で示す。将来のNavigator（Folders / Calendar / Search / Recent）の入口はこのDrawer。
- 選択中はedge swipeを無効にする。整理の最中にDrawerが顔を出さないようにする。
- **行の寸法は長押しSheetと同じにする。** 高さ48dp、icon 22dp、labelは`bodyLarge`、左の余白は24dp。
  `NavigationDrawerItem`の既定は56dp・24dp・`labelLarge`（この app では15sp semi-bold）で、後ろに見えている
  画面より一回り大きい。**同じ「行の並んだ面」が二つの寸法を持つと、大きい方が別のappに見える。**
  M3の部品はそのまま使い、寸法だけこちらの尺度へ合わせる——選択中のpillもrippleもsemanticsも部品のものが要る。

### Bottom navigation

- Top-level destinationは「メモ」「カレンダー」「チャット」（2026-09-18、docs/CALENDAR_TAB_MIGRATION.md、docs/CHAT_FOUNDATION.md）。「チャット」v0はAIチャットではなく、DocumentSearch / DocumentAccessの上の検索・操作workspace。旧「日記」ページは「日記一覧」としてカレンダーの下にある二次画面で、barを出さない。Archive／Trash／Settingsは追加しない。
- Navigation contentは64dp高、各destinationは88dp幅で中央へまとめ、2項目を画面半幅まで引き伸ばさない。system gesture insetは別領域として確保する。
- Iconは22〜24dp、labelは常時表示し、各destination全体を48dp以上のtouch targetにする。
- Material既定の大きなselected pillを使わず、Primary iconと静的な3dp indicatorで選択を示す。
- Surface背景、上端`outlineVariant`、0dp tonal elevationとし、labelを常時表示する。
- Editor、Stage、Selection、Dialog／Sheetでは表示しない。切替時の装飾motionは追加しない。

### チャット AI mode

- 「チャット」は`[ 検索 ] [ AI ]`のsegmented controlで二つのmodeを持つ（2026-09-20、docs/AI_CHAT_PREVIEW.md）。検索がdefaultで、v0のまま（「メモ・日記を検索…」、日付chip、kind chip、テンプレート、定型操作）。検索入力をAI入力に置き換えない。
- AI modeの入力は「MemoRippleに頼む…」。添えるnoteは「探す・開く・追記や新規作成の内容を確認する」と書き、AIがメモを書き換えるかのような表現を避ける。
- 状態はtext lineで示す。「AIモデルを読み込んでいます…」「考えています…」まで。擬人化やspinnerの演出は足さない。
- 結果はCard一枚。検索結果は既存result rowをそのまま再利用し、OPENは開いたことを一行で示す。候補が複数なら「どれを開きますか？」と候補rowを並べ、勝手に開かない。
- Write previewは「追記の確認」「新規作成の確認」「テンプレートから作成の確認」。追記先・現在の内容・追加する内容・版の時刻を見せる。Actionは`キャンセル`と、操作に応じた`作成`または`追記`の二つ（2026-09-20、Phase 4、docs/AI_CONFIRMED_WRITE.md）。「「作成」を押すまで書き込みません。」を添え、押した瞬間に両buttonを無効化して「書き込んでいます…」に変える。二度押しは一回の書き込み。結果はCard一枚：「作成しました」「追記しました」、または「内容が変更されたため、追記できませんでした」「この日記は確定済みのため、追記できませんでした」「対象が見つからないため…」。失敗Cardには必ず「現在のデータは変更されていません。」を添える。
- 断る時も静かに。「AIモデルがまだ利用できません」「端末が熱いため、AIをいったん休ませています」「追記先が分かりません」「この依頼はまだ扱えません」。status code、例外名、モデル名、runtimeの内部語は出さず、「検索モードはそのまま使えます」「現在のデータは変更されていません」を添える。
- Back：preview／候補一覧が出ていればそれを閉じる。それ以外はtop-levelのBack（メモへ）のまま。

### Local AIモデル（設定）

- 設定に「AI」sectionを置き、「Local AIモデル」画面へ入る（2026-09-20、docs/AI_MODEL_MANAGEMENT.md）。導入文は「使うモデルを一つ選択してください」。何も選ばれていない状態を正常として扱い、勝手に選ばない。
- 候補はCard一枚ずつ。名前、`Balanced` / `Safety-oriented` のchip、Phase 0実測にもとづく中立な一文、長所と留意点を各二行、「ダウンロード 約2.15 GB ・ 読み込み時のメモリ 約4.75 GB ・ ライセンス」、公開元の注記。順位語（おすすめ・最強・No.1）は使わない。
- 状態は一行の文で示し、ダウンロード中だけprogress barを足す：「未ダウンロード」「ダウンロード中 0.80 GB / 2.15 GB」「ファイルを検証しています…」「ダウンロード済み」「ダウンロード途中（再開できる）」「ファイルの検証に失敗したため、削除しました」。
- Actionは状態に応じて一つか二つ：`ダウンロード` / `再開` / `再試行` / `キャンセル`、`削除`、`使用する`。選択中は「使用中」chip。
- 数GBの通信・削除は必ずDialogで確認する。モバイルデータ：「モバイルデータでダウンロードしますか？」に容量を添える。削除：「メモや日記は削除されません」を明記し、削除buttonはerror color。
- 空き容量・バッテリー・一度に一つ、の断りはCardではなくbannerで一行。非対応端末はerror containerのbannerで「この端末では現在Local AIを利用できません」と述べ、downloadを無効化する。
- Chat AI modeの「AIモデルがまだ利用できません」Cardには「設定を開く」を添える。検索modeは常に使える。

### Brand mark

- MemoRippleのPrimary Brand Markは`言葉のしずく`（2026-08-30、持ち主自身のデザインを採用）。
  水色の空（`brand_icon_background` #DFF4FF）に、青いしずく（`brand_icon_mark` #1E6FD9）。
  しずくの中に**白い3行の言葉**が休み、その下を静かな波（mark色 45%）が受ける。
  書かれた言葉がひとしずくになって、波紋の水面へ落ちていく。
- 元図は160×160のSVG（持ち主提供）。adaptive canvasへは中心(80,80)基準・係数0.45で写像し、
  マスク可視域(≈72dp)に対する元図の比率を保っている。
- 強いgradient、shadow、3D、文字を加えない。monochromeはしずくの単色シルエット。
  notification（overlay通知）は従来のまま。
- Brand Mark自体は動かさない。「コメントは動くが、アプリUIそのものは静か」を守る。

## Shared components

- `ProductTopBar`: Screen title、Back、actionsの順序を統一する。
- `SectionHeader`: title、任意のsupporting text、divider、heading semantics。
- `ProductEmptyState`: icon／Quiet Ripple visual、title、description、任意のprimary action。重要な0件状態はprominent hierarchyを使う。
- `ProductStatusChip`: Diary/Futureの内部enumを出さない静かな状態表現。
- `ProductSettingsRow`: title、supporting text、trailing、48dp、merged semantics。
- `ProductSheetHeader`: Sheet titleと任意のClose action。
- `ProductInfoBanner`: Overlay/Future deliveryの短い状態と明確なAction。
- `DestructiveTextButton`: Delete/完全置換Restoreをerror colorで示す。

万能Componentは作らず、画面固有のstate orchestrationは各Screenに残す。

## Interaction patterns

### App information and About

- Settingsの最下部に「アプリ情報」を置き、通常設定やBackup操作より低いvisual weightでAbout／Privacy／Versionをまとめる。
- Aboutは72dp以下の静止したQuiet Ripple、製品名、短い事実ベースの説明、Build metadata由来Versionだけを表示する。
- AboutのBrand Markはdecorativeとし、製品名Textをaccessibility上のidentityにする。Brand Markへ独立focusや読み上げを付けない。
- About／Privacyは既存`ProductTopBar`と縦scrollを使い、font scale 1.5＋landscapeでもBackと全文へ到達できるようにする。
- Versionはactionではなくmerged semanticsを持つstatic rowとし、package名、build type、Package Identity Probe状態を表示しない。
- Privacy overviewは実装factsを短いSectionに分け、Drive／Overlayの操作前Disclosureを置き換えない。
- 完全なOSS attribution sourceがない場合は「準備中」のdead rowを出さず、Release Gateとして文書化する。

### Empty states

空白だけにしない。何が空か、次に何ができるかを短く示す。検索0件は作成0件と区別する。
Memo 0件ではSearch／Filter／Sort／FABを隠し、Quiet Ripple mark、製品価値、単一の「最初のメモを書く」を視覚中心にする。
Memoが1件以上になった時点でSearch／Filter／Sort／FABへ切り替える。検索／Filter結果0件では条件Controlを残して回復可能にする。

### Dialogs

title → consequence → cancel → actionの順にする。Buttonは「はい」ではなく「削除」「未来へ送る」等の動詞を使う。
Deleteと完全置換Restoreはerror color。Diary確定は重要だが破壊ではないためprimary actionを使う。

### Bottom sheets

`ProductSheetHeader`、24dp左右余白、Section heading、末尾Actionを基本にする。高さ不足時はvertical scrollまたは
bounded LazyColumnを用い、keyboard表示時もActionへ到達可能にする。

### Snackbar and errors

Snackbarは成功、一時的error、短いrecovery通知に使う。不可逆確認はDialogで行う。
HTTP status、Exception名、DB/transport内部語は表示せず、「再接続が必要」「現在のデータは変更されていない」等へ変換する。

### Accessibility

- minimum touch target: 48dp
- Icon-only action: contentDescription必須
- decorative icon: contentDescriptionなし
- Section: heading semantics
- reorder: custom accessibility actionsを維持
- narrow/large text: horizontal/vertical scrollまたはwrap
- TalkBack順序: Screen identity → content → primary action → secondary action
- Material componentを自前実装へ置き換えたTab／Bottom Navigationは、親へ`selectableGroup()`を付けて「N個中M個目」を失わない。
- `BasicTextField`はplaceholderが読み上げ名にならないため、`contentDescription`で入力欄の名前を明示する。

### Motion and haptics

Phase 6Aでは新しい装飾animationとhapticを追加しない。既存playback、Sheet、Dialog、状態transitionを維持し、
system animator scaleをCompose/Android componentへ委ねる。

### Short-height layouts

- Font scaleやlandscapeで利用可能な高さが小さい入力画面は、本文領域だけでなく画面全体をscroll可能にし、primary actionを画面外へ固定しない。
- Activityは`adjustResize`を宣言する。宣言が無いとsystemはComposeのscroll領域を検出できずADJUST_PANを選び、window全体をpanしてTop barを画面外へ追い出す。IMEはinsetとして受け取り、`consumeWindowInsets`と`imePadding`でcontent側が引き取る。
- scroll可能なcolumnの中に置く入力欄は高さを有界にし、viewport内で終わらせる。無制限だと`BasicTextField`が内部scrollを持てず、cursorを自分でview内へ運べない。
- keyboard表示中は、typingの道具でないchrome（Tag row、Photo strip）を畳んで本文へ高さを渡す。閉じれば戻す。
- Top barのaction slotは**48dpを下限とする**。固定幅にすると、iconは収まるが語は収まらず、「編集」が「編」の下に
  「集」と折れる。中身に合わせて伸ばす。
- Top barのaction列は幅が限られる。同時に置くiconは、状態が変わっても増えない設計にする。
- IME表示中も末尾Actionへ到達できるようにする。通常のportraitでは既存の広いwriting surfaceを維持し、short-height条件だけcompact layoutへ切り替える。
- Pixel goldenは固定Emulatorを基準にし、vendor差のある物理端末は手動QAへ分離する。
- **Pixel_10の初期状態はgoldenの基準環境ではない。** 実測で高さ2424px・locale en-USなので、`assumeReferenceEnvironment`が
  そのままではskipする。goldenを実際に走らせるには、起動後に次を当てる。

  ```
  adb shell wm size 1080x2400
  adb shell cmd locale set-app-locales io.github.cragcoffee.memoripple --locales ja-JP
  ```

  Google Play imageは`adb root`も`setprop persist.sys.locale`も拒むので、端末全体ではなく**app単位のlocale**で合わせる。
  終わったら`adb shell wm size reset`で戻す。
- 通常の`connectedDebugAndroidTest`はこの上書きをしないため、goldenは**黙ってskipされる**。緑であることはgoldenが
  照合された証拠にならない。design変更のあとは上の手順で明示的に走らせて確認する。

## Diary Calendar

- 日記は**一枚のpage**。かつて「一覧／カレンダー」の2tabだったが、開いた画面が最初に訊くのが行き先の選択に
  なっていた。今は答える：**月が上、その下に今日が選択済み**で「今日の日記を書く」まで1tap、下へ読み進む
  ことが過去へ読み進むことになる（過去の今日 → 過去の日記）。日付を選ぶと変わるのはCardであって画面ではない。
- 旧一覧はこの同じpageの下方向として残る。行き先としての「一覧」は要らない。
- 未来コメントの配信Bannerは**pageの最上部**。tabが無くなったので「別のtabに居ると気付けない」が起こらず、
  旧・Tab labelの4dp dotも役目を終えた。
- Top barは中央にlabel「日記」、右に48dp slotのSettings。compact top barの56dpは下限であって固定値ではない。
- 状態機械（DRAFT→FINALIZED→CORRECTING→LOCKED）には触れない。変えたのは入口だけである。
- Today、Past Today、Diary entryはBackground上のSurface＋1dp outlineで統一し、旧来の大きなtonal fillを使わない。
- Calendarは**一週間が既定**。日々の仕事は「今日がどこに立っているか」を言うことで、一行の週がそれに答える。
  月全体は**月名（chevron付き）をtapして開閉**し、選択はdevice-localに記憶される。
  週表示の←→は**週単位**で動き、移動は選択日を連れて動くので、下のCardは常に窓が立っている日を語る。
  未来の週へは進めない。週の行では隣月の日も描く——週は週である（このためmonth entriesの購読窓は月の前後
  一週間まで広げてある）。
- Calendarの日付は**数字を素で置く**。40dpの円は七日ぶんの「ボタンの列」を毎行描いていた。
  Diary存在は日付下の**3dp×18dpの下線**で示し、記録日数や連続日数を評価しない。
- Todayは`primary`の**塗りのpill**、Selectedは`primaryContainer`のpill。48dpのtouch高さは変えない。
- 月の実在しない週の行は描かない（gridは計算のために6週へpadされているが、他月だけの行は48dpの無である）。
- Headerは月名が左のrailから読まれ、操舵（今日・前月・翌月）は右へまとめる。
- Future dayは`onSurfaceVariant`の低いalphaとdisabled semanticsを使い、新規作成Actionを持たせない。
- 状態色をDRAFT/FINALIZED/CORRECTING/LOCKEDごとに増やさず、選択後SummaryのStatus Chipで伝える。
- 7列で48dp相当のhit areaを確保するため、Calendar modeだけ画面左右余白を8dpへ縮める。通常20dp rhythmの意図的例外とする。
- Gridは最大幅560dpとし、wide screenでは中央配置する。画面全体のLazyColumnだけをscrollさせる。
- Day semanticsへ日付、today、Diary有無、selected、future disabledを統合し、dot自体は装飾nodeにしない。

## Memo organization

- Memo top barは56dp、左右48dp action slot、中央scope labelで構成する。Bottom Navigationと重複する「メモ」は表示せず、中央は「すべて／お気に入り／Tag名」など現在の一覧範囲を示す。
- ACTIVE Memoが1件以上ある時だけ右端にSearch iconを表示する。Tap後はtop bar内をBack／検索入力／表示条件に置き換え、通常一覧へ常設Search fieldを置かない。
- 左端の整理menuへFilter／Sort／Tag、Selection、Archive／Trash、Settingsを段階的に集約する。検索中も右端の表示条件Actionを残し、Searchとの合成を失わない。
- 一覧上へFilter Chip群を常設しない。非default条件だけcontent先頭へ短いText summaryと「クリア」で示す。
- 通常一覧の作成Actionはlabel付きExtended FAB、0件時は中央Primary Buttonへ切り替え、同時表示しない。
- Memo一覧はBackgroundと一体化したoutlineなしのflat rowとし、通常状態をCardとして囲わない。選択中だけprimary containerで操作状態を示し、強いelevationは使わない。
- FavoriteはCard上の48dp Star toggleで1 tap操作とし、outline/filledとtoggle semanticsの両方で状態を伝える。
- Pinは低頻度操作としてoverflow menuへ置き、状態はtitle横の小さなpinだけで示す。Card全体は強調色で塗らない。
- Pinned専用の大きなSection headerは作らず、選択Sortを保ったまま一覧先頭へ静かにgroup化する。
- Sortは整理menuから開く「表示するメモ」Sheetへまとめ、現在値をselected stateで示す。長いSort名は常時Top barへ表示しない。
- Memo 0、Search 0、Favorite 0、Pinned 0、Search + Filter 0は理由と回復Actionを区別する。
- EditorはStarを直接操作し、Pin/Deleteをoverflowへ段階的に開示する。organization操作に確認Dialogは出さない。

## Memo tags

- Editorの割当済みTagは静かなInput ChipとしてFlowRowに置き、Chip全体を48dp相当の解除Actionにする。
- Tag PickerはBottom Sheet内で正規化検索と複数選択を行い、全TagをMemo Listへ常時横並びにしない。
- Memo Cardはlocale準拠順の先頭3件だけを表示し、残りは`+N`で要約してtitle・Favorite・overflowを圧迫しない。
- Tag filterはALL/FAVORITES/PINNEDとは別軸の単一選択とし、選択Tag名をChipとaccessibility descriptionへ反映する。
- Tag管理はTop bar overflowから開き、名前・使用Memo件数・rename/deleteを一覧化する。0件Tagも保持する。
- Tag削除Dialogは「Memoから外れるがMemo自体は残る」ことを明記し、error colorの動詞Actionを使う。
- Tag色・Icon・階層・任意並べ替えは持たず、Material color rolesとlocale準拠名称順でquiet designを維持する。

## Memo archive and trash

- ArchiveとTrashはBottom Navigationへ増やさず、Memo ListのTop bar overflowから開く。
- Archive Cardはtitle、本文要約、Tag、Favorite、アーカイブ日時をquietなsurfaceで示し、既存Editorへ遷移できる。
- Archived Editorは「アーカイブ中」を明示し、本文、Tag、Comment、Favorite、Pin、再生、TTSの既存操作を維持する。
- Trash Cardはread-onlyとし、Card tapでEditorを開かない。復元と完全削除だけを明確な動詞で配置する。
- Archive/Trash移動はSnackbarの「元に戻す」を提供し、完全削除と「ゴミ箱を空にする」は結果を説明するDialogを必須にする。
- Archive 0件、Trash 0件、各検索0件は別のEmpty State copyを使う。状態を評価する色や達成表現は追加しない。

## Memo selection and bulk actions

- 選択は通常のACTIVE Memo一覧だけで使い、長押しに加えてTop bar overflowの「メモを選択」を必ず用意する。
- 選択中はTop barを件数・Back・一括menuへ置き換え、Search／Filter／Sortをdisabledにして隠れた選択を作らない。
- 選択Cardはquietなsecondary containerとCheckbox、selected semanticsを併用し、色だけに依存しない。
- 0件の明示選択modeでも「表示中をすべて選択」へ到達可能とし、その他の一括Actionはdisabledにする。
- Favorite／Pinは「追加／解除」の明示的な終状態を選ばせる。Tag add/removeは検索可能な複数選択Bottom Sheetを使う。
- 一括Archive／Trashは確認Dialogを挟まず、件数を示すSnackbarとstale-safe Undoを提供する。完全削除は一括対象にしない。
- Tag filterは複数選択を「完了」で確定し、2件以上では「いずれか（ANY）／すべて（ALL）」を表示する。
- Tag filter Chipは0件なら「タグ」、1件ならTag名、複数なら件数で要約し、large fontやlandscapeでもTop barを圧迫しない。

## Comment expression

- Color roleは標準／赤／青／緑／黄／水色／ピンク。swatchだけでなく色名、selected semanticsを必ず併用する。
- Per-comment sizeは小さめ0.90／標準1.00／大きめ1.15で、Global Comment Sizeと乗算する。
- Emphasisは通常（既存Medium）／強調（Bold）。色やsizeを暗黙に連動させない。
- Composerは「表現: 要約」からscroll可能なBottom Sheetへ段階的に開示し、Static PreviewはPlaybackと同じstyle/palette resolverを使う。
- 保存成功時だけComposer Appearance/Motionを標準へ戻し、失敗時はtextと全選択を保持する。Presetはtextだけを変更する。
- Comment Listは本文を派手に着色せず、非default時だけ静かな日本語summaryを表示してTalkBack descriptionも付ける。
- Playback paletteはLight／Dark／Stage／Overlay背景に応じてtoneを調整してよいが、semantic color identityとglyph shadowを維持する。
- Speedはゆっくり0.85／標準1.00／速い1.20でGlobal Playback Speedと乗算し、Placementは自動／上側／中央／下側と表記する。
- Speed/PlacementはColor/Size/Emphasisと連動させない。Static Previewには動きを追加せず「速い・上側」のようなlabelで示す。
- Filter Chipは48dp操作領域、明示label、selected semanticsを持つ。Sheetはlarge fontとlandscapeでもActionまでscrollできる。
- 「上側」は流れるコメントのSurface相対band、「上に固定」は水平中央で上bandに滞在する表示方法として区別する。
- 表示方法は「流れる／上に固定／下に固定」を明示し、Fixed中はSpeed／Placement controlsを隠すが保存値は維持する。
- Fixed Previewは動かさずTop／Bottom位置とsafe-width wrappingを示し、一覧summaryは「上に固定／下に固定」を文字で伝える。
- Fixedは複数行を許可して全文を表示する。Surfaceに収まらない長文は切り詰めず、文字を小さくするかFlowへ戻す回復文を示す。
- Expression Sheetの情報階層は「タイトル → プレビュー → 見た目 → 表示方法 → 流れ方 → Action」とする。「流れ方」はMemo CommentでFlowを選んだ時だけ表示し、追加の折りたたみは設けない。
- タイトルと各Section見出しはheading semanticsを持つ。Size／Emphasis／表示方法はgroup名を含む説明を持ち、TalkBackでも選択対象の軸を区別できるようにする。
- 一覧summaryはdefaultとの差分だけを表示する。Fixedの「上に固定／下に固定」、非defaultの「左から右／波」は省略せず、情報量を抑えながら意味を残す。
- Motion定数は読みやすさとCalm Motionを優先する。Per-comment Speed 0.85／1.00／1.20、Wave desired amplitude 6dp・2 cycles、Fixed dwell 4000msを基準とし、実機で明確な可読性問題が確認された場合だけ小幅に調整する。

## Future comment expression

- Future Creatorは「表現: 標準」から送信前だけ共有Expression Sheetを開く。Speed／Flow Placementは表示しない。
- PreviewはFuture Revealと同じblack surface paletteで静的に示し、Flow／Fixed Top／Fixed Bottomを位置とlabelで伝える。
- 送信確認では本文・日時に加えて表現も変更できないことを一文で示す。失敗時は本文、日時、Expressionを保持する。
- SEALED／DELIVEREDでは表示、supporting text、TalkBack semanticsのいずれにもExpression metadataを出さない。
- 初回RevealはPersisted Expressionを演出へ適用する。Static record本文は従来の落ち着いた白系表示を維持し、完了後だけ
  non-default Expressionを小さなsummaryで示す。
- Fixedが将来のSurfaceへ収まらない場合は編集を要求せずFlowとして安全に提示する。fallback自体を警告や設定変更として見せない。

## Flow direction and wave

- Memo Commentが「流れる」の時だけ、速さ・位置に続けて「方向」と「動き」を表示する。
- Directionは「右から左／左から右」、Effectは「直線／波」と表記し、「逆方向」「reverse」など内部語を使わない。
- 各選択肢は48dp操作領域、文字label、selected semanticsを持つ。arrowやwave iconを補助利用しても文字を省略しない。
- Fixed選択中は速さ・位置・方向・動きをすべて隠すが、Flowへ戻した時に以前の値を復元する。
- Static Previewはanimationせず、「速い・上側・左から右・波」のようなlabelで全軸を確認できるようにする。
- 非default summaryは「ピンク・大きめ・速い・左から右・波」のように意味を保ち、default軸は省略する。
- Waveは自身のlane内だけで上下し、隣接laneやSurface外へ出ない。色やiconだけでWaveを示さない。
- Future CommentはPhase 7Dのsubsetを維持し、Direction／Wave controlsを表示しない。

## Photo attachments

- Memoに星（お気に入り）は無い。上部固定とTagがあれば、もう一つの「特別な印」は要らない。
- 写真は本文を補うquietな88dp horizontal stripとし、Memo Listをgallery化しない。
- Stripは**在るものだけを見せる**。写真が無いrecordではheaderごと何も出さない。無いものの置き場所が本文の場所を取らない。
- 追加はActionであり、Keyboardの上のAction列に置く。MemoはToolbarの`toolbar_add_photo`、DiaryはAction列の`diary_add_photo`。Stripの中にAdd tileを置かない。
- Diaryは書式Toolbarを持たないので、写真を追加するActionは確定Buttonと同じ行の左端に置く。書く画面から写真へ届く道は常に一本ある。
- 取り込み中だけはStripにspinnerを出す。押せる場所ではなく、進んでいることの合図である。
- Add actionはiconだけにせず`contentDescription`を「写真を追加」、thumbnailは「添付写真 N / 合計」、ViewerはClose／index／前後／削除を文字semanticsで伝える。
- Viewerはfit-center、deleteは確認Dialogを使う。missing/corrupt binaryは静かなplaceholderにし、technical hash/pathを表示しない。
- Diaryの編集可否は本文と同じDRAFT/CORRECTING契約に従う。写真だけをFINALIZED/LOCKEDで特別編集可能にしない。
- Photo-only recordは「無題」と枚数metadataで静かに表し、searchは引き続きtext-onlyとする。

## チャット AI mode — setup and failure cards (Phase 7)

- **Setup card** (`chat_ai_model_unavailable`): shown *instead of* the AI input while no model can
  answer. One title with the reason, one body line ending in what works now (「検索モードは今すぐ使えます」
  / 「検索モードはそのまま使えます」), one filled button 「AIモデルを設定」 (`chat_ai_open_settings`) that opens
  Local AIモデル directly. The four reasons: nothing selected (「AIモデルを選択すると、メモや日記を自然な
  言葉で検索・操作できます」), the selected file missing, the selected file corrupt, an unsupported CPU
  (no button — nothing to set up). 「閉じる」 appears only when the card is the result of an ask.
- **Status lines** (「AIモデルを読み込んでいます…」 / 「処理しています…」 / 「書き込んでいます…」): body-large
  on `onSurfaceVariant`, polite live regions. The model is not a person: no 「考えています」.
- **Failure cards**: a plain title, 「現在のデータは変更されていません」 where data could have changed, the
  search mode named, and one action where one exists — 「再試行」 on a load / generation / parse failure,
  「もう一度確認する」 on a conflict (a new preview, never a re-run). Thermal: 「端末が熱くなっているため、AIを
  一時停止しています」.
- **Wrapping**: the search chips, the card action rows and the preview's キャンセル / 作成・追記 are
  `FlowRow`s — they wrap at large font scales; nothing runs off a 360 dp column.

## Local AIモデル — status chips and semantics (Phase 7)

- Each card: the name on its own line, then a wrapping chip row — the category chip (Balanced /
  Safety-oriented, never a rank), a **state chip** (未ダウンロード / ダウンロード途中 / ダウンロード中 / 検証中 /
  ダウンロード済み / 失敗, `ai_model_state_<id>`) and, only when selected, 使用中 (`ai_model_selected_<id>`).
  Installed and selected are two chips; the status sentence below the facts says the rest.
- Semantics: the card is `selected` when in use and carries a `stateDescription` (the status sentence,
  「、使用中」 appended when selected); the progress bar exposes its range; on an unsupported CPU every
  download control is disabled.
- Messages: offline 「ネットワークに接続していないため、ダウンロードを始められません。ダウンロード済みのモデルはオフラインでも
  使えます。」; a corrupt file 「ファイルが壊れています（サイズが一致しません）。再試行するとダウンロードし直します。」 with 再試行
  and 削除.

## チャット AI mode — conversation (Phase 8)

- Under the mode switch: 「新しいチャット」 and 「履歴」 as outlined buttons (a `FlowRow`), disabled while
  the model works.
- The transcript: user lines right-aligned on `surfaceContainerHigh`, assistant lines left on
  `surfaceContainerLow` (failures on `errorContainer`), each with a small 「あなた」 / 「MemoRipple」
  label; read-only, no button ever inside a past line; the newest line scrolls into view.
- The current result, preview or status stays a card below the transcript, as before.
- The input is pinned at the bottom (`bottomBar`), with the one-line write note under it.
- 履歴 screen: rows with the title (two lines max), the last activity, a delete icon; the current
  chat on `secondaryContainer` and `selected`; the delete dialogs follow the Local AI モデル pattern
  (「…を削除しますか？」, 削除 in the error colour, キャンセル, and 「メモや日記は削除されません」).
- Final looks are for the user's own UI / UX review round; this is the minimum structure.

## チャット — conversation-first (2026-09-21)

- Top bar (the reference shape, UI review 2026-09-21): the menu icon (history) left — a back arrow on
  the 新しいチャット stage; the conversation title centred (「チャット」 with none) with the model as a
  tappable subtitle (a chevron; the list: installed models / 「AIモデルなし」 / 「モデルを管理」); the
  edit-square compose icon and the overflow right. No segmented control, no chips, no fixed-action
  buttons anywhere on the chat.
- Transcript: USER as a right-aligned dark rounded bubble on `surfaceContainerHigh` (`extraLarge`, 84 %
  width); ASSISTANT flat on the left, no bubble, failures in the error colour, a small meta row under
  the line (a 16 dp copy icon; the model's timing in `labelSmall` under the current answer). A small
  scroll-to-bottom FAB, bottom-centre, while the list is scrolled up. Operation cards (results,
  candidates, previews, outcomes, the setup card) sit under the last assistant line, as before.
- The stage (新しいチャット): the same screen on a black background over a dark theme (the lowest surface
  over a light one), system bars included, without the bottom navigation.
- Empty state: one quiet question and three example lines, tappable.
- Bottom bar: ＋ (templates), the rounded input (「メッセージを入力」), send; a one-line model hint above it
  when free text cannot be asked. It follows the keyboard; on the tab the bottom navigation gives way
  while the keyboard is up.
- Template picker: a modal bottom sheet of rows (name, description, 作成 / 検索 / 追記) with
  「テンプレートを作成」 and 「テンプレートを管理」 at the end.
- Template form: a card with one control per field (text fields, choice chips, a checkbox for
  はい/いいえ), 「キャンセル」 and 「確認へ」 / 「検索」; missing fields named in the error colour.
- Template first-class (2026-09-21): the picker opens at full height — 最近使ったテンプレート (when any), すべての
  テンプレート (the user's own, then the six starters with a small 「スターター」 mark), 「＋ テンプレートを作成」,
  「テンプレートを管理」. The form speaks the user's words: a date as 「今日」 / 「昨日」 chips + 「日付を選ぶ」
  (a calendar), はい・いいえ as a switch, 「（必須）」 on a required field and 「会議名を入力してください」 when
  it is empty. With no model a free-text send answers with one card that offers 「AIモデルを設定」 and
  「テンプレートを使う」; a sentence containing a template's name gets a one-line suggestion with 「使う」.
- The template editor is a five-step flow (基本情報 → 操作 → 入力項目 → 内容 → 確認) with 戻る / 次へ at the
  bottom, action and kind as chips in plain words, fields as cards (name, kind chips, a required
  switch, an initial value, 上へ / 下へ / 削除), 「＋ 項目を挿入」 above the body inserting `⟦名前⟧`, a preview
  card on the last step. No key, type name or JSON word anywhere.
- The history drawer (2026-09-21 evening, refined 21:29): the menu icon opens a modal drawer on
  `surfaceContainerLow`, 80 % wide, with no inset of its own — 「チャット」 headline tight at the top with a
  search icon (a field 「履歴を検索」 below it when open), 「ピン留め」 when any, 「最近」, rows of title +
  「M/d HH:mm」 (the current one on `secondaryContainer`; a long press opens ピン留め / 削除), a filled
  「新しいチャット」 with the edit-square icon, a text 「履歴を管理」.
- Template = conversation script (2026-09-21, §16): a template asks one question at a time as a flat assistant
  line; the user answers as a right bubble through the message input (placeholder 「回答を入力」) or the chips
  under the question (今日 / 昨日 / 日付を選ぶ, はい / いいえ, the choices, スキップ, やめる); no form card, no
  second screen. The preview card gains 「修正」, which lists the answers as rows. The transcript starts 16 dp
  under the top bar.
- Small fixes from the S20 (2026-09-21 night): a user bubble wraps its words (capped at 84 %), right-aligned;
  やめる only on the first question; the input caret is `onSurface`, the selection the primary.
- Think templates (2026-09-22, `docs/THINK_TEMPLATES.md`): the picker groups its rows under three light labels —
  記録 / 整理・壁打ち / 探す — below 最近使ったテンプレート, an empty group unshown; the rows stay the same small rows
  (a Think template says 整理する). A Think conversation ends in a flat assistant line 「整理すると、こんな内容です。」
  followed by the rendered result, and a small card under it with only 「メモとして保存」 (filled) and 「終了」 (text) —
  the words are the transcript line, the card is what can be done with them; no result screen, no preview until
  the save is asked for, and then the ordinary 「テンプレートから作成の確認」. The editor's 操作 gains 整理する beside
  作成する / 探す / 追記する, and the insert menu offers ⟦今日の日付⟧.
- Review Batch 2 (2026-09-22): the ＋ picker's order is ピン留め → 最近使ったテンプレート → 記録 → 整理・壁打ち → 探す →
  「＋ テンプレートを作成」 / 「テンプレートを管理」, an empty section unshown, a pinned row shown once. Pin and unpin live in
  a **long-press menu on the row** (「ピン留め」 / 「ピン留めを外す」, a pin icon, anchored on the right — the drawer's
  convention), never as a standing icon on every row. The Think result card reads 修正 · メモとして保存 · 終了 — one
  outlined, one filled, one text button, in that order; 修正 opens the same small list the preview's 修正 opens.
  「この会話をメモとして保存」 sits in the chat's overflow, not beside the input, and leads to the ordinary
  「新規作成の確認」 preview. No new large card anywhere in this batch.
- Overflow actions (2026-09-22): a conversation-level action that leads somewhere else — 「この会話をメモとして保存」,
  「この会話からテンプレートを作成」 — lives in the chat's ⋮ overflow, enabled only when there is a conversation, and
  leads straight to the existing surface (the preview, the editor). No standing button beside the input, no new
  screen, no success toast; the one failure is a small dismissable card in the conversation.
- The ＋ picker is an entrance, not a list (2026-09-22): at the root at most three pins and three recents run directly;
  below them **compact folder rows** — a folder icon, the name, the count in `labelMedium`, a chevron — never a card, never
  a grid; 記録 / 整理・壁打ち / 探す / 自分のテンプレート. A folder opens **inside the same sheet** as a page with a back arrow
  and the folder's name at the top; Back walks one page up. An empty section is not shown; 自分のテンプレート with no folder
  says 「まだフォルダはありません」 and still offers 未分類. Folder management is a plain list (name, count, up / down,
  rename, delete) under テンプレートを管理, and the editor picks a folder with one row that opens a menu.
- The chat's bar is **one pill** (2026-09-22, ChatGPT's reference): ＋ inside on the left, the text, × when there is text, 送る
  inside on the right — never buttons beside the field, never a microphone. Above it a small **folder chip**
  (Codex's 「プロジェクトを選択」): 「フォルダを選択」, or 「📁 仕事 ×」; a menu of the wall's folders indented by depth
  with 「フォルダなし（通常）」 first and 「＋ 新しいフォルダ」 under it; the chip itself carries no ×. The preview gains one 「保存先」 line only when a folder is chosen.
- The final looks are the user's call in the continuing Personal Review; this is the structure.
