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

**In scope**

- 課題 B1〜B7 の定義と、素の Launcher3/Lawnchair での操作手順（コード根拠付き）。
- 重み付き操作コストの定義（§4）と、各課題のbaseline概算（§6）。
- fixtureホームの定義と再現方法（§5）、計測手順と記録形式（§7）。

**Out of scope**

- 計測の自動化・CI組み込み（[performance-budgets](./performance-budgets.md) §10と同じ分離基準を適用する）。
- organizer run の性能（NFR-006、[performance-budgets](./performance-budgets.md)が正本）。本書は手動編集の操作コストを扱い、面を分ける。
- 改善機能の実装（#445〜#450のspecが本書の目標値を引用する）。

## 2. 課題（B1〜B7）

各課題は「開始状態 → 終了状態」で定義し、途中の操作経路は測定者の選択に委ねない（素のLauncher3/Lawnchairで最短の自然な経路を§3に固定する）。

| ID | 課題 | 開始状態 | 終了状態 |
|---|---|---|---|
| B1 | 新しいアプリを入れ、決めた場所（指定フォルダ）に置く | fixtureホーム + 指定フォルダ（「Benchmark」）が1ページ目にある。対象アプリを未install | アプリのアイコンが指定フォルダ内に存在する |
| B2 | 1ページ目のアイコン5個を3ページ目へ移す | fixtureホーム（§5）。1ページ目に移動対象5個が置かれている | 5個すべてが3ページ目に存在する（相対順は問わない） |
| B3 | 別々のページにある4アプリを新しいフォルダにまとめる | fixtureホーム。4アプリがページ1とページ2に分かれている | 4アプリが同一の新規フォルダに存在する |
| B4 | 使わないアイコン6個をホームから外す | fixtureホーム。対象6個が指定されている | 6個がワークスペースから消えている（アンインストールは不要） |
| B5 | 誤って動かした・外したアイコンを元に戻す | B2/B4の操作を1回実行した直後 | 元の位置・状態に戻っている |
| B6 | アプリを10個入れた後のホームを整える | 直前に10個の新規アプリをinstallした直後（B1の繰り返し） | 「空きセルへの散在が解消され、測定者が整ったと判断する」状態。判定基準は§7で固定する |
| B7 | 重複しているアイコンを見つけて1つにする | fixtureホームに同一起動先の重複アイコンが2組ある | 各組で1個だけが残る |

計測対象は§5のidentity（fixture起動先のlabel）で指定する。位置はその時点のfixture layout（計測開始時のscreenshot）で確認する。

## 3. 素のLauncher3/Lawnchairでの操作手順（コード根拠）

forkは日常編集の振る舞いを変えていない（メモ §2「日常編集」）。よってbaselineはfork自体で測る（メモ §4.1）。以下の行番号は起草時に確認したbaseline（2026-09-26、branch `issue-441-editing-burden-benchmark`）のものである。

### 3.1 ページをまたぐ移動（B2、B1/B3の一部）

- drag中のページ切り替えは、drag対象が隣接ページの領域に入ると `checkDragObjectIsOverNeighbourPages` が隣ページの `CellLayout` を返し、`setDropLayoutForDragObject` → `setCurrentDropLayout` でdrag targetが隣ページへ切り替わることで起こる（`src/com/android/launcher3/Workspace.java:2770-2787`、`src/com/android/launcher3/Workspace.java:2818-2853`）。NORMAL状態ではページをまたぐdragに専用のhover待ち定数は存在せず、指が隣ページの座標範囲に入った時点でtargetが切り替わる。実効的な待ち（アニメ・再配置）は§7の副指標（実測時間）として記録し、定数としては要求しない。
- 概観モード（overview/spring-loaded）では、mini-screen上のhover 500msで `snapToPage` する alarm がある（`ENTER_SPRING_LOAD_HOVER_TIME = 500`、`src/com/android/launcher3/dragndrop/SpringLoadedDragController.java:28`）。B2はNORMAL状態での操作なのでこの待ちは入らないが、ページ切り替えの実効待ち時間を副指標として記録する（§7）。
- drop後、移動先ページへは `snapToPage` が走る（`src/com/android/launcher3/Workspace.java:2306-2310`）。snapアニメ duration は resource 値で、phone は 750ms（`res/values/config.xml:32` `config_pageSnapAnimationDuration`、`src/com/android/launcher3/PagedView.java:650`）、tablet（sw600dp）は 550ms（`res/values-sw600dp/config.xml:19`）。隣接ページへのdrop自体は `ADJACENT_SCREEN_DROP_DURATION = 300`（`src/com/android/launcher3/Workspace.java:184`、`src/com/android/launcher3/Workspace.java:2410`）。
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
- undoのsnackbarは「時間窓ありの1 tap」として、tap=1だが4秒以内であることを記録する（`src/com/android/launcher3/views/Snackbar.java:50`）。
- 副指標は実測時間（秒、ストップウォッチまたは録画計測）。重みは主指標、時間は補助とする（メモ §4.1）。
- **操作数**（利用者の手順の数。tapも長押しも1と数える）は、重み付きコストとは別の指標として定義する（メモ§11 B-9）。#452の「ホーム画面から確認面まで4操作以下」の数え方はこの操作数を使う。重み付きコストが操作の質（dragの距離・待ち時間）を反映するのに対し、操作数は手順の長さだけを測る。
- baseline計測後に重み自体の見直し（例: cross-page dragの係数）を行ってよいが、baselineと改善後は同じ重み表で測る。重みの変更は本書の更新として行い、測定記録に link する。

## 5. fixtureホーム

| 項目 | 値 |
|---|---|
| ページ数 | 3 |
| グリッド | 4列×5行（reference環境と同じ。performance-budgets §2.1。計測端末ではLawnchair設定でこのグリッドを確認する） |
| QSB/Smartspace | 既定（有効）。1ページ目の行0が予約される（4cell）。変更しない |
| アイコン数 | ページ0に15個（移動対象5・削除対象3・重複1組・通常5）+指定フォルダ1 = 16 root（予約を除く16cellに満杯）、ページ1に12個（削除対象3・B3対象2・重複1組・通常5。空き8cell）、ページ2に8個（B3対象2・通常6。空き12cell） |
| フォルダ | B1の指定フォルダ「Benchmark」（ページ0の最初の空きセル、seed済みアイテム2個「Fixture 01」「Fixture 35」を含む。launcher loaderは1項目フォルダを自動でiconへ展開するため、2項目以上が安定条件） |
| 重複 | 同一起動先の2組（「Fixture 02」×2 = ページ0、「Fixture 03」×2 = ページ1）。重複の判定キーは component + profile の一致（§3.5） |
| dock | 既定のまま（測定対象外。seedingで変更しない） |
| 起動先identity | 通常アイコンは互いに異なる起動先（35種のactivity-alias `F01`〜`F35`。component・label・iconがすべて異なる）。instrumentation test APK（`tests/organizer-instrumentation/`）が供給し、実在第三者packageに依存しない。製品manifestは変更しない |

identity→roleの対応（計測対象の指定に使う。正本は seeding instrumentation の入力表 `FIXTURE_LAYOUT` で、本表と一致することをtestが検証する）:

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

**再現手段（instrumentation fixture seeding）**: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt` が、既存の `insertFixtureRow` 型seam（`ManualOrganizationProductionE2EInstrumentationTest` と同一の `modelDbController` 経路）でfixtureホームを構築する。置換対象は「workspaceのfixture対象graph（`CONTAINER_DESKTOP`のroot行とその子孫行）」であり、hotseat行・その子孫・予約領域に重なる行は保持する。削除は子孫→rootの順、insertは上記の決定的配置規則による。「同一入力から同一fixture」であることを1回のinstrumentation実行で検証するtest（restore mode。終了時に元のlayoutへ復元する）と、計測用の永続mode（次節）を同じclassが提供する。`fill_screens.py`（python2.5想定・現行package名と不合）とbackup restore instrumentation（fixture用途に過大）は使わない。

## 6. baseline概算と目標

§4の重みと§3の手順から、§5のfixture（3ページ、QSB有効）での概算。**あくまで手順からの算出であり、実測値ではない。**実測記録の受け皿は `docs/assessment/editing-burden-baseline.md`（§7）。

| 課題 | baseline概算 | 内訳 | 目標（status: 暫定。baseline計測後に#441で確定する） |
|---|---|---|---|
| B1 | 8 | 2ページ目へswipe×1（1）+ 長押し（2）+ 1ページ跨ぎdragで1ページ目の指定フォルダへ追加（4+1=5）。新規アプリは2ページ目の最初の空きセルへ置かれる（§3.4） | 配置先を設定した後の追加操作0（一度だけの設定操作は課題のコストに含めず、別に記録する。メモ§4.1。配置先ポリシーの実装は#446/ADR-0015の後） |
| B2 | 40 | 5個 ×（長押し2 + 2ページ跨ぎdrag 6） | 重み付きコスト50%以上削減（≤20）。B2〜B4の合否はNow-2全体（#448+#449のうち、その課題に最も適した方）で判定し、第1段（#448）単独の削減幅は記録のみで合否には使わない（メモ§4.1/§10） |
| B3 | 20 | フォルダ作成（ページ1でFixture 15をFixture 16へ重ねる: 長押し2 + 同ページdrag 2）+ ページ2の2個（各: swipe 1 + 長押し2 + 1ページ跨ぎdrag 5 = 8） | 同上（≤10）。判定はNow-2全体 |
| B4 | 24 | 6個 ×（長押し2 + Remove targetへの同ページdrag 2） | 同上（≤12）。判定はNow-2全体 |
| B5 | 削除: 1 / 移動: 8 | 削除はundo tap（4秒以内）。移動はundoなしで逆drag（2ページ跨ぎ: 長押し2 + drag 6） | 1操作。対象はforkの編集のうち利用者が画面上で行うもの（項目単位のアクション、編集画面の確定）。新規アプリの配置は対象外。上流のdrag移動の取り消しはNext、上流の削除snackbar（約4秒）はbaselineとして記録（メモ§4.1） |
| B6 | B1×10に準ずる（概算は実測で確定） | 空きセル散在の解消操作。新規アイコンの配置は§3.4の規則に従う | B1の改善に従属して確定（#446のspecで決める） |
| B7 | 8 + 視覚探索分 | 2組 ×（長押し2 + Remove targetへの同ページdrag 2）。視覚探索のswipe・tapは実測で記録 | #451の対象。目標は#451のspecで確定 |

確定手順: baseline実測（§7）後に本節の目標列を確定値へ更新し、メモ §4.1の初期目標（B1=追加操作0、B2〜B4=50%削減、B5=1操作）を確定として載せる。B2〜B4の合否はNow-2全体（#448+#449のうち適した方）で判定する。確定前にNow段階の機能specは受け入れない（メモ §5、NFR-014）。NFR-014のstatus確定は `docs/product/requirements.md` への更新と同時に行う。

## 7. 計測手順

- **計測者**: 保守者（solo）。独立session要件は性能測定には適用しない（[github-workflow](../project/github-workflow.md) のaudit契約はlayout-data書込みを対象とする）。
- **端末**: 実機 Pixel 9a（tegu、API 37、1080x2424 @ 420dpi。`docs/assessment/ac14-physical-device-evidence.md` と同じ端末）。参考として reference emulator R-EMU-1（Pixel 6、API 35。performance-budgets §2.1）でも測定してよいが、値を混ぜて比較しない（performance-budgets §8の環境class分離を準用）。
- **端末状態の固定**: Lawnchairの既定設定で計測する。特にQSB/Smartspaceは有効（§5。`FeatureFlags.topQsbOnFirstScreenEnabled`）、グリッドは4列×5行を確認する。計測中に設定を変更しない。
- **fixtureの構築（seedingの永続mode）**: 計測前に、次の手順でfixtureホームを構築して保持する（§5のseedingと同じclass。通常のconnectedAndroidTestは実行後にAPKをuninstallするためfixture起動先が消える。計測では手動installでAPKを保持する）。

```bash
./gradlew assembleLawnWithQuickstepGithubDebug \
  assembleLawnWithQuickstepGithubDebugAndroidTest
adb install -r "build/outputs/apk/lawnWithQuickstepGithub/debug/"*.apk
adb install -r "build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/"*.apk
adb shell am instrument -w \
  -e persist true \
  -e class app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest#fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
```

persist指定は**seeding test 1メソッドに限定する**。同classの検証用test（`seedingPreservesPreExistingHotseatFolderDescendants`）はdockに行を注入するため、persistでは実行しない（test側でもpersist時はskipする）。

fixture適用後にhomeを開くと、organizerのonboarding提案（「Organize your Home screen?」）が表示されることがある。提案は適用しない（「LATER」で閉じる。提案は確認するまで何も変更しない）。

- 各課題の開始前にfixtureを再構築する（上記 `am instrument` を再実行する）。操作経路は§3に固定し、逸脱があれば記録する。計測終了後は `adb uninstall app.lawnchair.debug.test`（必要ならapp本体も）でfixture起動先を撤去する。
- **端末の前提状態**: Lawnchairが既定ランチャーであること（新規installのアイコン自動追加は既定ランチャーへの配線を含むため）。QSB/Smartspace有効、グリッド4列×5行、ロック配置なし。
- **B1/B6の計測対象アプリ（固定）**: `tests/benchmark-install-targets/` の固定APKを使う。B1は `target01`（1個）、B6は `target01`〜`target10`（10個）。buildは `./gradlew :tests:benchmark-install-targets:assembleDebug`（1回）。
- **install経路（INSTALL_REASON_USER 固定）**: `adb install` はinstall理由が不明（`INSTALL_REASON_UNKNOWN`）のため自動追加が発生しない（`SessionCommitReceiver.java:81` は `INSTALL_REASON_USER` のみ受入）。必ず次のsession installを使う（`4` = user request = `INSTALL_REASON_USER`。実機検証済み、2026-09-26、`docs/assessment/441-fixture-seeding-evidence.md` §3）:

```bash
adb push <target apk> /data/local/tmp/bench-target.apk
SESSION=$(adb shell pm install-create --install-reason 4 | grep -oE '[0-9]+')
adb shell pm install-write -S $(stat -f%z <target apk>) $SESSION base /data/local/tmp/bench-target.apk
adb shell pm install-commit $SESSION
```

  install後、新規アイコンがworkspaceへ出現するのを待つ（broadcast配信の遅延で数十秒かかることがある。Lawnchairが既定ランチャーでないと出現しない）。B6は `target01`〜`target10` を順にinstallする。
- **試行間のreset**: 前試行の対象アプリを `adb uninstall app.lawnchair.benchmark.targetNN` で削除し、fixtureを再seedしてから次試行を開始する。これにより各試行は「対象アプリ未install + fixtureホーム」の同一開始状態へ戻る。
- **計時の開始・終了点**: 録画計測とし、B1/B6の計時は「新規アプリのアイコンがworkspaceへ出現したフレーム」を開始、「終了状態の成立を確認したフレーム」を終了とする。install自体の所要（download・broadcast待ち等の環境依存時間）は編集負担ではないため含めない（§6のB1内訳にinstall操作は含まれない）。B1の重み内訳は「2ページ目へswipe×1 + 長押し + 1ページ跨ぎdrag」であり、アイコン出現後の操作のみを対象とする。
- **回数**: 各課題3回実行し、重み合計は全試行で報告、実測時間は中央値を報告する。ばらつきが大きい場合（max/min > 1.5）は追加2回を実行する。n=3は「手順の確認と目標設定の根拠」であり、統計的判定（performance-budgets §6.3のn=100/300）は求めない。重みは操作回数から決定的に算出されるため、試行間で一致するはずであり、一致しない場合は手順の逸脱として記録する。
- **記録形式**: `docs/assessment/editing-burden-baseline.md` を1文書作り、課題ごとに「試行 / 重み合計 / 実測時間 / 逸脱・備考（ページ切り替えの実効待ち、undoの4秒窓の成否を含む）」の表 + 端末・build・commit SHA・計測日時（performance-budgets §6.1のmetadata規律を準用）を記録する。evidence画像は `docs/assessment/` の既存慣行に従う。
- **B6の判定基準**: 「全ての新規アプリのアイコンが、測定者が意図した配置（指定フォルダまたは明示的に置いた位置）にあり、意図しない空きセルの散在が残っていない」ことを、測定者判断により終了時に1枚のscreenshotで確認する（機械判定はしない。判定の証跡としてscreenshotを記録に添付する）。
- **目標の確定**: baseline実測後に本書の§6を更新する（前節のとおり）。

## Change history

- 2026-09-26: Issue #441の成果物として新設。付録草案（2026-09-24承認）を正本へ移す際に、grid表記をreference環境の実値「4列×5行」へ正規化し（草案の「5列×4行」は列/行の取り違え。`lawnchair/res/xml/device_profiles.xml` の既定phone grid `4_by_5` と performance-budgets §2.1 に一致）、QSB有効時の新規アプリ配置規則（`WorkspaceItemSpaceFinder.java:55-66` の1ページ目除外）に合わせてB1の操作経路とbaseline概算（8）を修正した。fixture identity（35種のactivity-alias、重複2組限定）と決定的配置規則、seedingの永続mode手順は [spec 441](../../specs/441-editing-burden-benchmark/spec.md) で確定した。
- 2026-09-26: 実装検証（`docs/assessment/441-fixture-seeding-evidence.md`）で、launcher loaderが1項目フォルダを自動でiconへ展開する挙動（`LAUNCHER_FOLDER_CONVERTED_TO_ICON`）を確認したため、指定フォルダのseed内容を2個（Fixture 01・35）へ変更した。
- 2026-09-26: Phase 2 reviewの指摘を受け、§7へB1/B6の固定対象アプリ（`tests/benchmark-install-targets/` 10 flavor）・install経路（`pm install-create --install-reason 4` = INSTALL_REASON_USER。`adb install` は理由が不明のため自動追加が起きないことを実機で確認）・試行間reset・計時の開始/終了点を明記し、§5のidentity数を35種へ統一した。
