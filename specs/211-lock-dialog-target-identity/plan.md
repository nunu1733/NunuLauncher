# Plan: Issue #211 Placement lock確認ダイアログの対象行表示

> Spec: [spec.md](./spec.md) (status: accepted)
> Status: planned → PR #264 review fix in progress
> Revision 4 (2026-09-09): PR #264 review (P1, Request changes) への対応。
> row description の縮約では同 page 別 cell / 別 folder 同 position / 別 app pair
> を区別できないため、dialog へ D4 区別行 (Position: row/column / Folder: /
> App pair:) を常時追加 + collision fixture test。非 blocking 指摘として
> evidence capture test を regression suite から分離 (instrumentation
> argument gate)。Revision 3 (2026-09-09): 2nd plan review (head e6079a1277) への対応。F2 を
> page p2 へ移動して F との "Home screen 2" 衝突を解消 (R-1)、protected 行の
> 合成を `summary_double` に修正 (R-2)、変更 module 表の旧 key 名残存を修正 (R-3)。
> Revision 2 (2026-09-09): plan review (Request changes, head be50cbd67e) への対応。
> fixture の page 構成を修正して description を全行一意化 (M-1)、count assert を
> 既定戦略として明記 (N-1)、string key を `_target_title` へ変更 (N-2)、
> protected 行を fixture へ追加 (N-3)。

## 現在の code の根拠

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt`
  - `LockChangeDialog` (`PlacementLockPreferences.kt:305-378`): `Available` 経路で
    本文を `buildString` で 1 つの `Text` node へ連結
    (`organizer_lock_dialog_current_state` + scope 説明 + effect note、
    `PlacementLockPreferences.kt:322-334`)。**対象の title・配置概要は含まない**
    — 本 spec の問題箇所。dialog title は `entry.stored` で
    `organizer_lock_dialog_title_lock` / `_unlock` / `_review` を選択
    (`PlacementLockPreferences.kt:337-347`)。
  - `LockRow` (`PlacementLockPreferences.kt:267-291`): 行は
    `entry.title.textOrFallback(entry)` を title、`placementDescription(entry, profileLabel)`
    を description に表示する。**行とダイアログで共有すべき合成関数はこの 2 つ**。
  - `placementDescription` (`PlacementLockPreferences.kt:392-439`): placement 種別
    (`organizer_lock_screen_placement_desktop` / `_dock` / `_folder` / `_app_pair`) に
    profile label (`profileLabels[entry.profile.value]`) と
    effectively-protected 表示 (`organizer_lock_screen_effectively_locked`) を
    `organizer_lock_screen_placement_summary_double` / `_triple` で連結する
    @Composable private 関数。
  - `textOrFallback` (`PlacementLockPreferences.kt:441-444`): title が
    blank/absent のとき `entry.item.value` へ fallback する private extension。
  - `openDialog` (`PlacementLockPreferences.kt:150-157`): `explain(item, LOCKED)` を
    IO で取得して `dialogEntry` / `dialogExplanation` を set。ダイアログは
    `entry != null && explanation != null` で表示 (`PlacementLockPreferences.kt:228-240`)。
  - `profileLabels` state (`PlacementLockPreferences.kt:87, 101-114`) は
    composable scope で解決済み。`LockChangeDialog` へ渡す値の供給源。
- `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt`
  - `showDialog` (`OrganizerLockShortcut.kt:77-126`): ポップアップ側は
    `organizer_lock_dialog_*` を共有するが、対象は long-press した
    可視アイコン。本 spec の対象外 (spec D3)。
- strings (en `lawnchair/res/values/strings.xml:921-925`、
  ja `lawnchair/res/values-ja/strings.xml:32-36`):
  `organizer_lock_dialog_title_lock` / `_unlock` / `_review` /
  `_current_state` / `_review_intro` が既存。新規 string の追加位置はこの block。
- 既存 test: `tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt`
  - fake `LockCapturePort` / `LockStateWriterPort` と `screenState()` fixture で
    `PlacementLockPreferences` を Compose test する既存 surface (4 test)。
  - dialog 本文への substring assert の先例:
    `folderLockDialogExplainsChildCoverageBeforeMutation`
    (`OrganizerLockScreenTest.kt:224-242`、`onAllNodesWithText(effect, substring = true)`)。
  - 書込み前確認の oracle: `unknownReviewResolvesOnlyThroughConfirmedDialog`
    (`OrganizerLockScreenTest.kt:193-221`)。

## 変更 module

| File | 変更 |
|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | ① `LockChangeDialog` の `Available` 経路で、本文 `Text` の前に 2 つの `Text` node を追加: (a) `organizer_lock_dialog_target_title` で title (`entry.title.textOrFallback(entry)` と同一規則) を導入する行、(b) `placementDescription(entry, profileLabel)` と**同一呼び出し**の配置概要行。② `LockChangeDialog` の呼び出し側 (`PlacementLockPreferences`) から `profileLabels[entry.profile.value]` を引数で渡す。③ `buildString` の既存本文 (state + scope + effect、UNKNOWN は review intro 追加) は現行のまま第 3 node とする。④ **Revision 4 (D4)**: 配置概要行の直後に D4 区別行を追加 — Desktop は `organizer_lock_dialog_target_position` (cell.y+1 / cell.x+1)、folder child は `organizer_lock_dialog_target_folder` (listing map から parent title 解決、fallback は raw id)、app pair member は `organizer_lock_dialog_target_app_pair` (同様)。Dock / Unsupported では区別行を出さない。呼び出し側からは解決済みの `parentTitle` (`parentTitleOf(entry, entries.orEmpty())` の結果) を引数で渡す。 |
| `lawnchair/res/values/strings.xml` | `organizer_lock_dialog_target_title` + Revision 4 の 3 string (`_target_position` / `_target_folder` / `_target_app_pair`) 追加 (値は下表。実装時に #161 style guide で最終確認)。 |
| `lawnchair/res/values-ja/strings.xml` | 同 key の ja を同時追加 (#123 契約)。 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt` | 同名 fixture (下記) と新規 test 2 件を追加。既存 4 test は無変更で継続成功させる。 |
| `specs/38-lock-authoring-unknown-review/spec.md` | §Launcher UI surfaces の管理画面 bullet へ、確認ダイアログが行と同一の識別情報 (title + 配置概要) を表示する旨を 1 文追記 + Change history に本 spec 参照を追記 (docs-only)。 |

## 文言 (実装時に最終化、en/ja 同時)

| Key | en (案) | ja (案) |
|---|---|---|
| `organizer_lock_dialog_target_title` (新規) | `Target: %1$s` | `対象: %1$s` |
| `organizer_lock_dialog_target_position` (Revision 4) | `Position: row %1$d, column %2$d` | `位置: %1$d 行 %2$d 列` |
| `organizer_lock_dialog_target_folder` (Revision 4) | `Folder: %1$s` | `フォルダ: %1$s` |
| `organizer_lock_dialog_target_app_pair` (Revision 4) | `App pair: %1$s` | `アプリペア: %1$s` |

- `%1$s` (title) には行と同一の表示タイトル (`textOrFallback` 結果) を渡す。
  配置概要は別 node で `placementDescription` をそのまま表示するため、
  separator や連結 format を新規に作らない（行と同じ値をそのまま出す）。
- 区別行 (D4, Revision 4) は `LockPlacementSummary` の detail から合成:
  position の `%1$d`/`%2$d` は cell.y+1 / cell.x+1 (1-based、行/列の語順は
  sort key の y→x と一致)。folder/app pair の `%1$s` は listing map から
  解決した parent 行の title (fallback は raw item id)。
- key 名が `_title` であるのは、この string が title 導入行のためである
  （`placementDescription` の語感と衝突させない）。ja 語彙は glossary の
  「配置 (placement)」に従い、「対象」は
  `organizer_lock_screen_review_all_confirm` (ja) の文脈語彙と整合。

## Test 設計 (spec AC との対応)

fixture: 既存 `screenState()` を変更せず、新規 private builder
`sameTitleState()` を作る。構成 (review M-1 修正後):

- `Google` (Application, UNLOCKED, `PlacementState.Workspace(page p0, cell (0,0))`) →
  description は `organizer_lock_screen_placement_desktop` = "Home screen 1"
- `Google` (Application, UNLOCKED, `PlacementState.FolderChild(parent F, rank 0)`) →
  description は `organizer_lock_screen_placement_folder` = "Inside a folder, position 1"
- `F` (Folder, UNLOCKED, `StructureState.FolderMembers([Google child], 0)`,
  `PlacementState.Workspace(page p1, cell (0,0))`) →
  description は **"Home screen 2"**（別 page p1 を fixture に追加する。
  同じ p0 に置くと folder 行の description も "Home screen 1" になり、
  description で行を一意特定できなくなるため）
- `Protected` (Application, UNLOCKED, `PlacementState.FolderChild(parent F2, rank 0)`,
  parent F2 は LOCKED) → F2 は `PlacementState.Workspace(page p2, cell (0,0))`
  に置く（**F と同じ p1 に置くと Desktop 合成は pageOrder のみを使うため
  F も F2 も "Home screen 2" となり description が衝突する。p2 を追加して
  F2 の description を "Home screen 3" にする**）。
  Protected 行の description は `organizer_lock_screen_placement_summary_double`
  の合成 = **"Inside a folder, position 1 · Protected by a locked parent"**
  (`profileLabels` は test 環境で personal のみのため空 map、`profileLabel == null`
  なので double 合成になる。triple にはならない)。
  `placementDescription` の protected 合成経路を dialog 側でも観測可能にする
  (review N-3)。

fixture 内の全行で description 文字列が一意になる ("Home screen 1" /
"Inside a folder, position 1" / "Home screen 2" / "Home screen 3" /
protected 行の double 合成値)。title (`Google`) が重複するのは同名行の
再現に必要なため、tap 対象の特定は description text で行う。

test 1 `dialogNamesTheTappedRowAmongSameTitleRows` (AC-1, AC-2):

1. tap する行を description text で一意に特定する（fixture により description
   は全行一意。`onNodeWithText(desc).performClick()`）。
2. tap 後、dialog 内で `Target: Google` が表示されること
   (`composeRule.onNodeWithText(context.getString(R.string.organizer_lock_dialog_target_title, "Google")).assertIsDisplayed()`)。
3. **count assert (既定戦略, review N-1)**: tap 行の description は
   `onAllNodesWithText(desc).fetchSemanticsNodes().size == 2`（背景 list の行
   1 + dialog の対象行 1）、非 tap の同名対抗行 ("Inside a folder, position 1") の
   description は size == 1 のまま（背景行のみ、dialog には
   現れない）。description が fixture で一意であるため、count 比較で
   「dialog に表示された description が tap 行と同一であり、他方ではない」ことを
   順序依存なしに検証できる。
4. 閉じてもう一方の行 ("Inside a folder, position 1") を tap し、count が
   入れ替わること（dialog 側 description の差し替え確認）。
5. `Protected` 行 (description "Inside a folder, position 1 ·
   Protected by a locked parent") でも step 2-3 を繰り返し、
   protected 合成値がそのまま dialog へ出ることを確認する。

test 2 `dialogTargetRowIsIndependentTextNode` (AC-4):

1. dialog 表示中に `Target: Google` node と配置概要 node が別 node として
   存在することを assert（`onNodeWithText` が両方とも単独で match する。
   1 つの連結 Text に含まれる場合は substring でしか match しないため、
   単独 match の成功自体が node 分離の証拠になる）。
2. `unmergedTree` は既存 test の規約（`onAllNodesWithText` の既定 merge 動作）に従う。

test 3 (既存 4 test の継続成功 = AC-3): 既存 test は無変更のまま実行する。

test 4 `dialogResolvesCollidingDescriptionsWithDisambiguator` (AC-2, Revision 4):
既存 `screenState()` fixture を用いる — fixture 自体が description 衝突を
持つ（"Locked App" Workspace(p0, (0,0)) の plain 行と "Folder Child"
FolderChild(201, rank 0) は description が異なるが、collision 起点として
**"201" folder (Workspace p0, (1,0))** と "Locked App" (p0) は description
"Home screen 1" が同一で title も異なるため不適）。よって collision 専用
fixture `collidingTitleState()` を新設する:

- `Google` (Application, UNLOCKED, Workspace(p0, cell (0,0)))
- `Google` (Application, UNLOCKED, Workspace(p0, cell (3,1))) — 同 page 別 cell、
  row description も "Home screen 1" で同一 (review P1 の第 1 例)
- `G` (Folder "G", UNLOCKED, Workspace(p1, (0,0)), `FolderMembers(emptyList())`)
- `H` (Folder "H", UNLOCKED, Workspace(p1, (2,0)), `FolderMembers(emptyList())`)
- `Google` (Application, UNLOCKED, FolderChild(G, rank 0))
- `Google` (Application, UNLOCKED, FolderChild(H, rank 0)) — 別 folder 同
  position、row description も "Inside a folder, position 1" で同一
  (review P1 の第 2 例)

検証:

1. 1 つ目の "Home screen 1" 行を tap → dialog に
   `Target: Google` + `Home screen 1` + `Position: row 1, column 1` が
   表示されること。
2. 閉じて 2 つ目の "Home screen 1" 行を tap → `Position: row 2, column 4`
   (`GridCell(x=3, y=1)` → row=y+1=2, column=x+1=4) に差し替わること。同一 description の 2 行が
   区別行で区別できることを exact match で assert。
3. 1 つ目の "Inside a folder, position 1" 行 (G 配下) を tap →
   `Folder: G` が表示されること。
4. 2 つ目 (H 配下) を tap → `Folder: H` に差し替わること。
5. dialog open 前後で `writer.writes.size == 0` を維持すること。

test 5 (非 blocking 指摘対応, Revision 4): `capturesLockDialogTargetEvidence`
を通常 regression 実行から分離する。test に `@AssumeDevice` のような annotation は
使わず、Gradle instrumentation argument gate を採用する: test 本体先頭で
`androidx.test.filters` / `InstrumentationRegistry.arguments` により
`-e captureEvidence true` が無ければ `assumeTrue` で skip。これにより
通常の `connectedLawnWithQuickstepGithubDebugAndroidTest` (CI / local full run)
は evidence 書込みを行わず、証憑更新時のみ
`-Pandroid.testInstrumentationRunnerArguments.captureEvidence=true` で
実行する。`OrganizerLockScreenTest` の 7 test 構成 (regression 6 + capture 1)
を維持し、第二 test seam を作らない。

注意: dialog 表示中も背景 list は semantics tree に残る
(既存 test が dialog button と row 待ち合わせを同じ tree で行っている)。
test 1 の count assert はこの前提の上に立っており、dialog が背景 node を
除外する window 分離をしたとしても count は 2 → 1 側に寄るだけで
意味論（tap 行 description が dialog に現れ、対抗行は現れない）は保たれる。
D4 区別行は独立 Text node であるため、test 2 の「単独 exact match」戦略は
区別行にも同じく適用できる (`Position: row 1, column 1` が単独 node として
match する)。

## Interface / seam

- 変更する seam なし。`LockAuthoringModule`、`LockStateEntry`、
  `LockExplanation`、`OrganizerLocks` は無変更。表示値の合成は
  `PlacementLockPreferences` 内の既存 private 関数に統一される。
- 新規 dependency、新権限、新通信、DB migration はなし。

## Migration / rollback

- migration なし（永続化なし、schema 変更なし）。
- rollback: PR revert。UI 表示 1 node 群の追加と strings 追加のみであり、
  revert で完全に現行へ戻る。

## Test / 検証手順

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
# API 36.1 emulator (nunu_qpr2_api36_1) 接続時:
./gradlew -PandroidSerialNumber=emulator-5554 connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.locks.OrganizerLockScreenTest
```

- JVM gate (`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`)
  は UI file 変更でも compile が通ることを保証する（CI `organizer-unit-tests` job と同一）。
- connected test は spec 38 と同じく local emulator evidence であり、
  CI class filter には含まれない。結果を PR へ記録する。
- UI 証拠: emulator で管理画面を開き、同名 2 行 fixture を実機操作で再現できる
  ような配置（同 app を page と folder に配置）で dialog の en/ja screenshot を取得し、
  200% font scale の wrap 確認も含めて PR へ添付する (AC-1, AC-4)。

## リスク

- risk label 不要: 変更は `organizer/ui/preferences` 配下の表示 file、
  strings、instrumentation test、spec docsのみ。高リスク path 一覧
  (`organizer/application/**` 等) には含まれず、Launcher DB /
  recovery store / planner への変更はゼロである。
- 既存 test への影響: `LockChangeDialog` 本文の node 構成変更により、
  本文 1 node を前提とする既存 assert が壊れる可能性。
  既存 assert は substring / 個別 text match
  (`folderLockDialogExplainsChildCoverageBeforeMutation`、
  `unknownReviewResolvesOnlyThroughConfirmedDialog`) であり、
  本文が第 3 node として現行文字列を維持するため壊れない見込み。
  実行で確認する。
- 文言の機械テスト限界: description 値の en/ja での自然さは
  #161 の LQA 規約（style guide + glossary）に依存し、機械 test では
  検証しない。screenshot + spec 承認時の文言確認で担保する。
