# Plan: Issue #211 Placement lock確認ダイアログの対象行表示

> Spec: [spec.md](./spec.md) (status: draft → review経由でaccepted)
> Status: planned (review承認後に実装開始)

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
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | ① `LockChangeDialog` の `Available` 経路で、本文 `Text` の前に 2 つの `Text` node を追加: (a) `organizer_lock_dialog_target_description` で title (`entry.title.textOrFallback(entry)` と同一規則) を導入する行、(b) `placementDescription(entry, profileLabel)` と**同一呼び出し**の配置概要行。② `LockChangeDialog` の呼び出し側 (`PlacementLockPreferences`) から `profileLabels[entry.profile.value]` を引数で渡す。③ `buildString` の既存本文 (state + scope + effect、UNKNOWN は review intro 追加) は現行のまま第 3 node とする。 |
| `lawnchair/res/values/strings.xml` | `organizer_lock_dialog_target_description` 追加 (値は下表。実装時に #161 style guide で最終確認)。 |
| `lawnchair/res/values-ja/strings.xml` | 同 key の ja を同時追加 (#123 契約)。 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt` | 同名 fixture (下記) と新規 test 2 件を追加。既存 4 test は無変更で継続成功させる。 |
| `specs/38-lock-authoring-unknown-review/spec.md` | §Launcher UI surfaces の管理画面 bullet へ、確認ダイアログが行と同一の識別情報 (title + 配置概要) を表示する旨を 1 文追記 + Change history に本 spec 参照を追記 (docs-only)。 |

## 文言 (実装時に最終化、en/ja 同時)

| Key | en (案) | ja (案) |
|---|---|---|
| `organizer_lock_dialog_target_description` (新規) | `Target: %1$s` | `対象: %1$s` |

- `%1$s` には行と同一の表示タイトル (`textOrFallback` 結果) を渡す。配置概要は
  別 node で `placementDescription` をそのまま表示するため、
  separator や連結 format を新規に作らない（行と同じ値をそのまま出す）。
- ja 語彙は glossary の「配置 (placement)」に従う。「対象」は
  `organizer_lock_screen_review_all_confirm` (ja) の文脈語彙と整合。

## Test 設計 (spec AC との対応)

fixture: 既存 `screenState()` を変更せず、新規 private builder
`sameTitleState()` を作る。構成:

- `Google` (Application, UNLOCKED, `PlacementState.Workspace(page p0, cell (0,0))`)
- `Google` (Application, UNLOCKED, `PlacementState.FolderChild(parent F, rank 0)`)
- `F` (Folder, UNLOCKED, `StructureState.FolderMembers([Google child], 0)`,
  `PlacementState.Workspace(page p0, cell (1,0))`)

test 1 `dialogNamesTheTappedRowAmongSameTitleRows` (AC-1, AC-2):

1. tap する行は description text で一意に特定する（title は重複するため
   `onAllNodesWithText` + description string で絞る。行全体は
   `placementDescription` 値 "Home screen 1" / "Inside a folder, position 1" で区別できる）。
2. tap 後、dialog 内で `Target: Google` が表示されること。
3. dialog 内に tap した行の `placementDescription` 値と同一文字列が存在すること。
   実装は行 description と dialog description が同一合成関数の結果であるため、
   fixture 上の期待値は `context.getString(R.string.organizer_lock_screen_placement_folder, 1)` 等
   resource から組む。
4. 閉じてもう一方の行を tap し、逆側の description に差し替わること。

test 2 `dialogTargetRowIsIndependentTextNode` (AC-4):

1. dialog 表示中に `Target: Google` node と配置概要 node が別 node として
   存在することを assert（`onNodeWithText` が両方とも単独で match する。
   1 つの連結 Text に含まれる場合は substring でしか match しないため、
   単独 match の成功自体が node 分離の証拠になる）。
2. `unmergedTree` は既存 test の規約（`onAllNodesWithText` の既定 merge 動作）に従う。

test 3 (既存 4 test の継続成功 = AC-3): 既存 test は無変更のまま実行する。

注意: dialog 表示中も背景 list は semantics tree に残るため、
「他方の description が dialog に現れない」(AC-2) は背景 list node との
誤検出を避けるため、**dialog 内の description node を
`onAllNodesWithText(desc)` のうち dialog 側 node で確認する** 方式を取る。
実装時に Compose test の window 挙動を確認し、必要なら
`onNodeWithText(desc).assertExists()` を dialog の `Target:` node 直後の
順序依存なしで dialog node 群に限定して assert する。背景 list にも
同一文字列が存在する場合（行 description と dialog description が同値）、
「dialog に現れる」ことの assert は成立するが「背景にだけ現れる」場合との
区別は `Target:` 導入行との近接で確認する。具体 assert 方法は
実装時に Compose test の実測で確定し、AC-2 の意味論
（dialog 文言だけで対象を区別できる）を裏付けることを PR へ記録する。

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
