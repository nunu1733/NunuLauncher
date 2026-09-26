# 編集負担ベンチマーク

> Status: Accepted
> Updated: 2026-09-26
> 出典: 再焦点化方針メモ（2026-09-24承認、Revision 5）§4.1（R-2）、§11（B-9）、§12（B3）。付録全文は [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441)。受理条件とfixture seeding契約は [spec 441](../../specs/441-editing-burden-benchmark/spec.md)
> Requirements: NFR-014
> 形式の参考にした文書: [performance-budgets](./performance-budgets.md)（測定protocol・見直し条件の構成）
> Related: [#439](https://github.com/nunu1733/NunuLauncher/issues/439)（再焦点化Epic）、[#446](https://github.com/nunu1733/NunuLauncher/issues/446)（B6目標）、[#451](https://github.com/nunu1733/NunuLauncher/issues/451)（B7目標）、[#452](https://github.com/nunu1733/NunuLauncher/issues/452)（操作数の引用元）

## 1. 目的とscope

「ホーム画面の日常的な編集と、散らからない状態の維持にかかる手間」（メモ R-1）を、
再現可能な課題（benchmark task）と重み付き操作コストで測る。本書はその定義の正本であり、
Now段階の機能specは「改善する課題と目標値」を本書から引用しなければ受け入れない（メモ §4.1）。

**指標の性質（2026-09-26のオーナー判断で確定）**

重み付き操作コストと操作数は、固定手順（§3）に沿った操作回数への重み適用による**手順コストの会計**である。知覚負担・認知負担・所要時間の測定ではない。fixture identityがダミー（§5）であること、新規アプリの起点がagent手順によるinstall（§7）であることは、この会計の前提と矛盾しない。会計の要件は「手順が決定的に固定され、回数と重み合計が一意に定まること」のみである。人間の実測（touch操作の所要時間計測を含む）は、2026-09-26のオーナー判断（[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)。根拠: 課題規模が日常の流れで行う編集と合わない、ダミーidentityによる認知的ノイズが時間計測に混入する、install経路がユーザーフローとしてありえない）により、本ベンチマークの対象から外した。

**In scope**

- 課題 B1〜B7 の定義と、素の Launcher3/Lawnchair での操作手順（コード根拠付き）。
- 重み付き操作コストの定義（§4）と、各課題のbaseline（決定的算出の確定値。§6）。
- fixtureホームの定義と再現方法（§5）、検証手順（§7）。

**Out of scope**

- 計測の自動化・CI組み込み（[performance-budgets](./performance-budgets.md) §10と同じ分離基準を適用する）。
- organizer run の性能（NFR-006、[performance-budgets](./performance-budgets.md)が正本）。本書は手動編集の操作コストを扱い、面を分ける。
- 改善機能の実装（#445〜#450のspecが本書の目標値を引用する）。
- 人間による実測と、それを前提とした計測protocol（前述の指標の性質。将来の再設計の方針は§7）。

## 2. 課題（B1〜B7）

各課題は「開始状態 → 終了状態」で定義し、途中の操作経路は測定者の選択に委ねない（素のLauncher3/Lawnchairで最短の自然な経路を§3に固定する）。

**共通開始条件**: 各課題の開始時、workspaceは **ページ0（1ページ目）を表示している**。fixture seeding直後の既定表示であり、seeding契約の一部として§5/§7で検証する。例外はB5で、開始時の表示ページは直前の誤操作（B2/B4の1回）の結果として§3.1の視点移動規則から決まる（移動: drop先ページ、削除: 操作したページ）。B6は10個のinstall完了後にページ0へ戻すことをsetup（agent手順。課題コストに含めない）とする。

| ID | 課題 | 開始状態 | 終了状態 |
|---|---|---|---|
| B1 | 新しいアプリを入れ、決めた場所（指定フォルダ）に置く | fixtureホーム + 指定フォルダ（「Benchmark」）が1ページ目にある。対象アプリを未install | アプリのアイコンが指定フォルダ内に存在する |
| B2 | 1ページ目のアイコン5個を3ページ目へ移す | fixtureホーム（§5）。1ページ目に移動対象5個が置かれている | 5個すべてが3ページ目に存在する（相対順は問わない） |
| B3 | 別々のページにある4アプリを新しいフォルダにまとめる | fixtureホーム。4アプリがページ1とページ2に分かれている | 4アプリが同一の新規フォルダに存在する |
| B4 | 使わないアイコン6個をホームから外す | fixtureホーム。対象6個が指定されている | 6個がワークスペースから消えている（アンインストールは不要） |
| B5 | 誤って動かした・外したアイコンを元に戻す | B2/B4の操作を1回実行した直後（表示ページはその操作の結果。共通開始条件の例外） | 元の位置・状態に戻っている |
| B6 | アプリを10個入れた後のホームを整える | 直前に10個の新規アプリをinstallした直後（B1の繰り返し） | 10個すべての新規アプリのアイコンが指定フォルダ内に存在する（新規アイコンの配置内訳は§3.4の走査規則から決定的。2ページ目8個・3ページ目2個） |
| B7 | 重複しているアイコンを見つけて1つにする | fixtureホームに同一起動先の重複アイコンが2組ある | 各組で1個だけが残る |

課題の対象は§5のidentity（fixture起動先のlabel）で指定する（会計上の指定であり、視覚的探索の負担は数えない。§4）。

## 3. 素のLauncher3/Lawnchairでの操作手順（コード根拠）

forkは日常編集の振る舞いを変えていない（メモ §2「日常編集」）。よってbaselineはfork自体で測る（メモ §4.1）。以下の行番号は起草時に確認したbaseline（2026-09-26、branch `issue-441-editing-burden-benchmark`）のものである。

### 3.1 ページをまたぐ移動（B2、B1/B3の一部）

- drag中のページ切り替えは、drag対象が隣接ページの領域に入ると `checkDragObjectIsOverNeighbourPages` が隣ページの `CellLayout` を返し、`setDropLayoutForDragObject` → `setCurrentDropLayout` でdrag targetが隣ページへ切り替わることで起こる（`src/com/android/launcher3/Workspace.java:2770-2787`、`src/com/android/launcher3/Workspace.java:2818-2853`）。NORMAL状態ではページをまたぐdragに専用のhover待ち定数は存在せず、指が隣ページの座標範囲に入った時点でtargetが切り替わる。実効的な待ち（アニメ・再配置）は重み（ページをまたぐdragの係数）に織り込まれており、別途の定数としては要求しない。
- 概観モード（overview/spring-loaded）では、mini-screen上のhover 500msで `snapToPage` する alarm がある（`ENTER_SPRING_LOAD_HOVER_TIME = 500`、`src/com/android/launcher3/dragndrop/SpringLoadedDragController.java:28`）。B2はNORMAL状態での操作なのでこの待ちは入らない。
- drop後、移動先ページへは `snapToPage` が走る（`src/com/android/launcher3/Workspace.java:2306-2310`）。snapアニメ duration は resource 値で、phone は 750ms（`res/values/config.xml:32` `config_pageSnapAnimationDuration`、`src/com/android/launcher3/PagedView.java:650`）、tablet（sw600dp）は 550ms（`res/values-sw600dp/config.xml:19`）。隣接ページへのdrop自体は `ADJACENT_SCREEN_DROP_DURATION = 300`（`src/com/android/launcher3/Workspace.java:184`、`src/com/android/launcher3/Workspace.java:2410`）。
- drop後の視点はdrop先ページへ移る。drag中に対象ページの領域へ入った時点でworkspaceの表示が切り替わり、drop後には `snapToPage` が走る（`src/com/android/launcher3/Workspace.java:2303-2313` の `snapScreen != mCurrentPage` 判定）。§6のbaseline算出はこの視点移動を前提とする。
- 移動（reorder/移動drop）にはundoのsnackbarは出ない。undoは削除のみ（§3.3）。したがってB5の「動かした」の取り消しは、逆方向のdragでしかない。

### 3.2 フォルダ作成・フォルダへの追加（B1、B3）

- アイコンを別アイコンの上に重ねると `manageFolderFeedback` が `DRAG_MODE_CREATE_FOLDER`（既存フォルダなし）または `DRAG_MODE_ADD_TO_FOLDER`（既存フォルダあり）を立てる（`src/com/android/launcher3/Workspace.java:2876-2940`、モード定数は同ファイル286-290）。判定は `getFolderCreationRadius` 内の距離（`src/com/android/launcher3/Workspace.java:2877`）。
- drop時に `mCreateUserFolderOnDrop` / `mAddToExistingFolderOnDrop` が確定し（`src/com/android/launcher3/Workspace.java:2503-2506`）、drop処理でフォルダ作成・参加が実行される（`src/com/android/launcher3/Workspace.java:2026-2031`）。
- 手順: 長押しでdrag開始 → 対象アイコンへ重ねてdrop。ページが違う場合は、drag中にページ端へ運んでページを切り替えた後に対象へ重ねる。
- B1の「決めた場所に置く」は、新規アプリが生成された位置から、1ページ目の指定フォルダ「Benchmark」への追加dragとして現れる（§3.4）。

### 3.3 ホームから外す（B4）とUndo（B5）

- 「外す」は Remove drop target へのdrop。`DeleteDropTarget.onDrop` が `prepareToUndoDelete()` を呼び（`src/com/android/launcher3/DeleteDropTarget.java:120-127`）、`completeDrop` で削除と `onDeleteComplete`（`src/com/android/launcher3/DeleteDropTarget.java:129-137`）。
- 削除後、`DropTargetHandler.onDeleteComplete` が「item_removed + Undo」のsnackbarを出す（`src/com/android/launcher3/DropTargetHandler.kt:93-99`）。Undo tapで `abortDelete()`（`src/com/android/launcher3/DropTargetHandler.kt:87-91`、`src/com/android/launcher3/model/ModelWriter.java:434`）、タイムアウトで `commitDelete()`（DropTargetHandler.kt:97、`src/com/android/launcher3/model/ModelWriter.java:425`）。snackbarのtimeoutは 4000ms（`src/com/android/launcher3/views/Snackbar.java:50` `TIMEOUT_DURATION_MS`）。
- popup（長押しメニュー）には「ホームから外す」に相当するsystem shortcutはなく、登録されているのは UNINSTALL / CUSTOMIZE / （recents有効時）PAUSE_APPS / placement lock（`lawnchair/src/app/lawnchair/LawnchairLauncher.kt:285-295`）。`UNINSTALL_APP` はアンインストールであり「外す」ではない（`src/com/android/launcher3/popup/SystemShortcut.java:337`）。よってB4の素の経路は「長押し → drag → Remove target へdrop」の6個繰り返しである（アクセシビリティアクション REMOVE は存在するが、`src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java:72,87-88`、本ベンチマークはtouch操作を対象とする）。
- B5のUndo: 削除はsnackbarのUndo tap 1操作（4秒以内）。移動はundoが存在しないため逆drag（B2の1回分と同コスト）。

### 3.4 新規アプリの行き先（B1、B6）

- `SessionCommitReceiver` が `INSTALL_REASON_USER` のinstallを `ItemInstallQueue` へ積む（`src/com/android/launcher3/SessionCommitReceiver.java:76-95`）。`pref_add_icon_to_home`（既定true）と `lockHomeScreen` の判定は `isEnabled`（`src/com/android/launcher3/SessionCommitReceiver.java:104-108`）。
- 配置は `AddWorkspaceItemsTask` → `WorkspaceItemSpaceFinder.findSpaceForItem` が既存screenを順に走査して最初の空きセルを返す（`src/com/android/launcher3/model/AddWorkspaceItemsTask.java:124-127`、`src/com/android/launcher3/model/WorkspaceItemSpaceFinder.java:44-98`）。**QSB/Smartspaceが有効の既定状態では、1ページ目（`FIRST_SCREEN_ID`）は配置候補から除外される**（`WorkspaceItemSpaceFinder.java:55-66`、`FeatureFlags.topQsbOnFirstScreenEnabled`）。したがって本ベンチマークのfixture（§5。1ページ目はQSB予約を除き満杯、2ページ目に空きあり）では、新規アプリは **2ページ目の最初の空きセル** に置かれる。B1の「決めた場所に置く」は、2ページ目に生成されたアイコンから、1ページ目の指定フォルダへの1ページ跨ぎdragとして現れる。
- B6は「10個入れた後のホームを整える」であり、新規アイコンは上記の規則に従って2ページ目以降の空きセルへ散在する。空きセル散在（アンインストール後の穴を含む）を解消する操作量を測る。配置先ポリシーの改善対象は#446/ADR-0015（起草担当: [#446](https://github.com/nunu1733/NunuLauncher/issues/446)。将来の `docs/adr/0015-new-app-destination-policy.md`）が担当する。

### 3.5 重複アイコン（B7）

- 重複（同一起動先 = component + profile の一致するアプリ項目。メモ §4.5の定義と同一）の検出に既存の仕組みはなく、視覚探索と個別削除になる。fixtureの重複2組は「Fixture 02」と「Fixture 03」の2個ずつである（§5）。

## 4. 重み付き操作コストの定義

主指標。1課題あたりの合計値を比較する。重みは本書の承認時に確定した。

| 操作 | 重み | 根拠 |
|---|---|---|
| tap | 1 | 最小の単位操作 |
| 長押し（drag開始を含む） | 2 | tapより時間・精度の負担が大きい。drag開始の必須前提 |
| 同一ページ内のdrag | 2 | 長押し後の短距離移動 |
| ページをまたぐdrag | 4 + 越えたページ数 | 隣接ページへのdropで300ms、snapで750msのアニメ待ちに加え、端まで運ぶ精度負担（§3.1）。距離比例の成分を「越えたページ数」で表す |
| ページのswipe（閲覧のための移動） | 1 | 軽いが、対象を探す視覚負担は別途「探索」として数えない（手順に現れた回数だけ数える） |
| 設定画面の1段遷移 | 1 | 設定経路の重さを測る（#452の目標で使用） |
| 確認dialogの1回 | 1 | 確認操作の発生を可視化する |

追加規則:

- 同一課題内の繰り返しはすべて数える（B2は5回分）。
- undoのsnackbarは1 tapとして数える（時間窓4秒。`src/com/android/launcher3/views/Snackbar.java:50`）。
- 副指標の実測時間は、2026-09-26のオーナー判断により指標から外した（§1）。所要時間・知覚負担の測定が必要になった場合は、performance-budgets §10と同じ分離基準で別Issueとして再設計する（§7）。
- **操作数**（利用者の手順の数。tapも長押しも1と数える）は、重み付きコストとは別の指標として定義する（メモ§11 B-9）。#452の「ホーム画面から確認面まで4操作以下」の数え方はこの操作数を使う。重み付きコストが操作の質（dragの距離・待ち時間）を反映するのに対し、操作数は手順の長さだけを測る。
- baseline確定後に重み自体の見直し（例: cross-page dragの係数）を行ってよいが、baselineと改善後は同じ重み表で算出する。重みの変更は本書の更新として行い、baseline表（§6）を再算出して link する。

## 5. fixtureホーム

| 項目 | 値 |
|---|---|
| ページ数 | 3 |
| グリッド | 4列×5行（reference環境と同じ。performance-budgets §2.1。fixture契約testがこのgridでの収容契約を検証する） |
| QSB/Smartspace | 既定（有効）。1ページ目の行0が予約される（4cell）。変更しない |
| アイコン数 | ページ0に15個（移動対象5・削除対象3・重複1組・通常5）+指定フォルダ1 = 16 root（予約を除く16cellに満杯）、ページ1に12個（削除対象3・B3対象2・重複1組・通常5。空き8cell）、ページ2に8個（B3対象2・通常6。空き12cell） |
| フォルダ | B1の指定フォルダ「Benchmark」（ページ0の最初の空きセル、seed済みアイテム2個「Fixture 01」「Fixture 35」を含む。launcher loaderは1項目フォルダを自動でiconへ展開するため、2項目以上が安定条件） |
| 重複 | 同一起動先の2組（「Fixture 02」×2 = ページ0、「Fixture 03」×2 = ページ1）。重複の判定キーは component + profile の一致（§3.5） |
| dock | 既定のまま（測定対象外。seedingで変更しない） |
| 起動先identity | 通常アイコンは互いに異なる起動先（35種のactivity-alias `F01`〜`F35`。component・label・iconがすべて異なる）。instrumentation test APK（`tests/organizer-instrumentation/`）が供給し、実在第三者packageに依存しない。製品manifestは変更しない |

identity→roleの対応（課題対象の指定に使う。正本は seeding instrumentation の入力表 `FIXTURE_LAYOUT` で、本表と一致することをtestが検証する）:

| identity | role | ページ |
|---|---|---|
| Fixture 01 | 指定フォルダの内容 | フォルダ内 |
| Fixture 35 | 指定フォルダの内容 | フォルダ内 |
| Fixture 02 ×2 | B7 重複ペア1 | 0 |
| Fixture 03 ×2 | B7 重複ペア2 | 1 |
| Fixture 04〜08 | B2 移動対象（5個） | 0 |
| Fixture 09〜11 | B4 削除対象（3個） | 0 |
| Fixture 12〜14 | B4 削除対象（3個） | 1 |
| Fixture 15〜16 | B3 対象（2個） | 1 |
| Fixture 17〜18 | B3 対象（2個） | 2 |
| Fixture 19〜23 | 通常（5個） | 0 |
| Fixture 24〜28 | 通常（5個） | 1 |
| Fixture 29〜34 | 通常（6個） | 2 |

正確なcell座標の正本は seeding instrumentation の入力表と決定的配置規則である（文書とコードの二重管理を避ける）。配置規則: 保持行（dock、QSB予約に重なる行）の占有cellと予約領域を避けたrow-majorのfirst-fitで、固定のitem順（ページ0→1→2。フォルダはページ0の先頭）に配置する。4列×5行+既定QSBでは、ページ0は行1〜4の16cellに16 rootが満杯に入り、ページ1の先頭（行0列0）からページ1の12個が並ぶ。

**再現手段（instrumentation fixture seeding）**: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt` が、既存の `insertFixtureRow` 型seam（`ManualOrganizationProductionE2EInstrumentationTest` と同一の `modelDbController` 経路）でfixtureホームを構築する。置換対象は「workspaceのfixture対象graph（`CONTAINER_DESKTOP`のroot行とその子孫行）」であり、hotseat行・その子孫・予約領域に重なる行は保持する。削除は子孫→rootの順、insertは上記の決定的配置規則による。「同一入力から同一fixture」であることを1回のinstrumentation実行で検証するtest（restore mode。終了時に元のlayoutへ復元する）と、fixtureを端末へ保持する永続mode（次節。§3.4実証のagent手順で使う）を同じclassが提供する。`fill_screens.py`（python2.5想定・現行package名と不合）とbackup restore instrumentation（fixture用途に過大）は使わない。

## 6. baselineと目標

§3の手順と§4の重みから、§5のfixture（3ページ、QSB有効）について**決定的に算出した確定値**である（2026-09-26の本書改正で確定。実測値ではない。指標の性質は§1）。開始時の表示ページは§2の共通開始条件（ページ0。B5のみ誤操作の結果ページ）に固定し、内訳は重み表と手順から一意に再計算できる。drop後の視点はdrop先ページへ移るため（§3.1）、課題の完了に必要なページ移動（swipe）も内訳に含める。

| 課題 | baseline | 操作数 | 内訳 | 目標（確定） |
|---|---|---|---|---|
| B1 | 8 | 3 | 2ページ目へswipe×1（1）+ 長押し（2）+ 1ページ跨ぎdragで1ページ目の指定フォルダへ追加（4+1=5）。新規アプリは2ページ目の最初の空きセルへ置かれる（§3.4） | 配置先を設定した後の追加操作0（一度だけの設定操作は課題のコストに含めず、別に記録する。メモ§4.1。配置先ポリシーの実装は#446/ADR-0015の後） |
| B2 | 48 | 18 | 1個目: 長押し2 + 2ページ跨ぎdrag 6。2〜5個目: 各 2ページ分のswipe（2）+ 長押し2 + drag 6（drop後に視点が3ページ目へ移るため）。8 + 4×10 | 重み付きコスト50%以上削減（≤24）。B2〜B4の合否はNow-2全体（#448+#449のうち、その課題に最も適した方）で判定し、第1段（#448）単独の削減幅は記録のみで合否には使わない（メモ§4.1/§10） |
| B3 | 21 | 9 | 1ページ目へswipe（1）+ フォルダ作成（長押し2 + 同ページdrag 2）+ ページ2の2個（各: swipe 1 + 長押し2 + 1ページ跨ぎdrag 5 = 8。drop後に視点が1ページ目へ戻るため） | 同上（≤10）。判定はNow-2全体 |
| B4 | 25 | 13 | ページ0の3個（各: 長押し2 + Remove targetへの同ページdrag 2）+ ページ1へswipe（1）+ ページ1の3個（各4） | 同上（≤12）。判定はNow-2全体 |
| B5 | 削除: 1 / 移動: 8 | 1 / 2 | 削除はundo tap（4秒以内）。移動はundoなしで逆drag（2ページ跨ぎ: 長押し2 + drag 6。視点は移動先ページ） | 1操作。対象はforkの編集のうち利用者が画面上で行うもの（項目単位のアクション、編集画面の確定）。新規アプリの配置は対象外。上流のdrag移動の取り消しはNext、上流の削除snackbar（約4秒）はbaselineとして記録（メモ§4.1） |
| B6 | 84 | 32 | 2ページ目に置かれた8個（各: swipe 1 + 長押し2 + 1ページ跨ぎdrag 5 = 8）+ 3ページ目に置かれた2個（各: swipe 2 + 長押し2 + 2ページ跨ぎdrag 6 = 10）。配置内訳は§3.4の走査規則から決定的（2ページ目の空き8cellを先に消費し、残り2個は3ページ目） | B1の改善に従属（10個分の合計。#446のspecで確定） |
| B7 | 9 | 5 | ページ0のペア削除（長押し2 + 同ページdrag 2）+ ページ1へswipe（1）+ ページ1のペア削除（4）。重複の在処は§5のidentity表で既知であり、探索は数えない（§4） | #451の対象。目標は#451のspecで確定 |

baselineの算出は2026-09-26の本書改正で確定した。メモ §4.1の初期目標（B1=追加操作0、B2〜B4=50%削減、B5=1操作）を確定値として載せた（B2≤24、B3≤10、B4≤12。B2〜B4の合否はNow-2全体（#448+#449のうち適した方）で判定する）。起草時の概算（B2=40、B3=20、B4=24、B6=未確定、B7=8+探索分）は、drop後の視点移動と課題内のページ移動を内訳に含めていなかったため、確定時に再計算した（Change history）。確定前にNow段階の機能specは受け入れない（メモ §5、NFR-014）。NFR-014のstatus確定は `docs/product/requirements.md` への更新と同時に行う。

## 7. 検証手順（agent実行可能）

2026-09-26のオーナー判断により、人間の実測計測は本ベンチマークの対象外である（§1）。本書の検証は、agentが実行でき、その結果がrepositoryへ記録できるものに限る。

1. **fixture契約の検証（§5）**: `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest`（restore mode、2 test）がgreenであること。「同一入力から同一fixture」「hotseat・その子孫・予約領域重複行の保持」「`ReservationOverlapAcceptance` 基準の非交差」「identity一意性と重複2組」を検証する。実行記録: [441-fixture-seeding-evidence](../assessment/441-fixture-seeding-evidence.md)（reference系emulator `nunu_smoke_api35` と実機Pixel 9aの両方で実施済み。実機での検証実績は有効だが、オーナー判断以降は計測には使用しない）。
2. **§3.4の配置規則の実証**: session install（`INSTALL_REASON_USER`）による新規アプリの自動追加が「1ページ目を除外した最初の空きセル（fixtureでは2ページ目の8cell消費後に3ページ目）」へ置かれること。実証記録: 同evidence §3a（`tests/benchmark-install-targets/` の固定対象APKを使用。再検証が必要な場合は同moduleを再利用する）。
3. **§6の算術照合**: baseline表の内訳が§3の手順と§4の重みから一意に再計算できること（操作数を含む）。改正PRの本文に照合表を載せ、reviewで確認する。

将来の人間実測・agent自動計測（UI Automator等）について: 本ベンチマークの会計指標では所要時間・知覚負担を扱わない。それらを測る必要が生じた場合は、課題の再定義（実アプリの使用を含む）と併せて、performance-budgets §10と同じ分離基準で別Issueとして再設計する。2026-09-26のオーナー判断におけるscenario再設計の要否判断は「本改正では行わない」である（理由: §1の指標の性質により会計に実アプリ性は不要なこと、開発フローで人手が必須になる構成を避ける方針）。

## Change history

- 2026-09-26: Issue #441の成果物として新設。付録草案（2026-09-24承認）を正本へ移す際に、grid表記をreference環境の実値「4列×5行」へ正規化し（草案の「5列×4行」は列/行の取り違え。`lawnchair/res/xml/device_profiles.xml` の既定phone grid `4_by_5` と performance-budgets §2.1 に一致）、QSB有効時の新規アプリ配置規則（`WorkspaceItemSpaceFinder.java:55-66` の1ページ目除外）に合わせてB1の操作経路とbaseline概算（8）を修正した。fixture identity（35種のactivity-alias、重複2組限定）と決定的配置規則、seedingの永続mode手順は [spec 441](../../specs/441-editing-burden-benchmark/spec.md) で確定した。
- 2026-09-26: 実装検証（`docs/assessment/441-fixture-seeding-evidence.md`）で、launcher loaderが1項目フォルダを自動でiconへ展開する挙動（`LAUNCHER_FOLDER_CONVERTED_TO_ICON`）を確認したため、指定フォルダのseed内容を2個（Fixture 01・35）へ変更した。
- 2026-09-26: Phase 2 reviewの指摘を受け、§7へB1/B6の固定対象アプリ（`tests/benchmark-install-targets/` 10 flavor）・install経路（`pm install-create --install-reason 4` = INSTALL_REASON_USER。`adb install` は理由が不明のため自動追加が起きないことを実機で確認）・試行間reset・計時の開始/終了点を明記し、§5のidentity数を35種へ統一した。
- 2026-09-26: オーナー判断（[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)）に基づく改正。§1へ指標の性質（手順コストの会計であり知覚負担の測定ではない）を明示し、人間の実測（実機3試行計測・所要時間・B6の測定者判断）を対象外へ変更した。§6のbaselineを固定手順からの決定的算出として確定した（drop後の視点移動と課題内のページ移動を内訳へ織り込み、起草時の概算からB2 40→48、B3 20→21、B4 24→25、B6→84、B7→9へ再計算。操作数列を追加）。目標表を確定値へ更新（B2≤24、B3≤10、B4≤12）。§7を人間計測手順からagent実行可能な検証手順へ改正し、実測記録の受け皿（`docs/assessment/editing-burden-baseline.md`）を廃止した。
