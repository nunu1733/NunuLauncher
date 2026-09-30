---
issue: "#487"
status: accepted
requirements: []
updated: 2026-10-01
---

# pathC recovery oracleが別々の起動先を持つfixtureで再びAppliedに到達する

> リスク階層: L（test + docs のみ。planner/application production codeは不変）

## Problem

CI lane `organizer-instrumentation-reservation-recovery-tests` が `56624406fd`（#451）以降、main上で決定的に失敗する。失敗testは `Issue265ManualEditRecoveryInstrumentationTest.pathC_manualEditThenSecondOrganize` で、エラーメッセージは `second organize did not reach Applied: NoChanges`。

機序: `seedLayoutWithFolder()` が6行すべてを同一の起動先（launcher自身のcomponent）で挿入する。手動移動後の2回目のorganizeのcaptureは、intactなfolderと同一起動先のdesktop item 4個を保持する。spec 451 N-1/N-2はそのうち3個を `DUPLICATE_LAUNCH_TARGET` の重複超過分（duplicate surplus）として保持するため、movableなitemが1個以下になり、`minGroupSize` を下回ってfolderが形成されない。結果、planは全item Preserveとなり `NoChanges` を返す。#451以前はこれらのitemはmovableでfolderを形成していたため `Applied` に到達していた。

分類: 決定的なtest defect（stale oracleが#451の受入済み契約と不整合）。Issue 487のcommentに記録済み。

## Outcome

CI lane `organizer-instrumentation-reservation-recovery-tests` がmain上で再び決定的にgreenになる。#269 recovery oracle（pathC）は強度を保つ（2回目のorganizeが `Applied` に到達すること、下流のinvariantは変更しない）。

## Scope

- `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt` へのfixtureのみの変更。各seeded行に別々の起動先を与える。
- 手段はrepo内のprecedent commit `828401fbad`（E2E fixtureへの同一修正）と同じ機構とする。6行すべてに、GitHub debug variantのmerged manifestで宣言済みの相互に異なる6componentを割り当てる — `app.lawnchair.LawnchairLauncher`、`app.lawnchair.ui.preferences.PreferenceActivity`、`app.lawnchair.homeedit.ui.HomeEditSurfaceActivity`、`app.lawnchair.BlankActivity`、`app.lawnchair.smartspace.SmartspacePreferencesShortcut`、`com.android.launcher3.WidgetPickerActivity`（いずれも `context.packageName` を維持し、行はlaunchされない。`SecondaryDisplayLauncher` は `tools:node="remove"` のため対象外）。precedent `828401fbad` は同じ機構を使ったが5行のE2E fixtureで4componentで足りた。本fixtureは6行のため6componentを要する。
- 実装PRで `specs/269-folder-workspace-representability/spec.md` への変更履歴noteを追加する。

## Non-goals

- planner/applicationのproduction code変更はしない。
- `Applied` assertionや下流invariantの弱体化はしない。
- 新規のproduction duplicate-rule testは追加しない（既存の `DuplicateLaunchTargetsTest` 単体testと `828401fbad` のE2E fixtureが既にcoverageしている）。
- PR #486の調査はしない。
- spec 451のamendmentはしない。

## Domain language

既存語（重複アイテム、duplicate surplus、TargetKey）を再利用する。新しいドメイン用語は追加しない。

## Prior art

省略（理由: 階層Lのtest修正）。repo内のprecedent `828401fbad`（同一機構のE2E fixture修正）をProblem/Scope本文で参照済み。

## Behavior scenarios

### Scenario: 別々の起動先を持つfixtureでのpathC

Given 各行が別々の起動先を持つfixtureでseedした状態
When 1回目のorganizeを実行して `Applied` に到達し、`ModelWriter.moveItemInDatabase` でfolder childの1個を手動移動し、2回目のorganizeを実行する
Then 2回目のorganizeは `Applied` に到達する（duplicate filteringによってfolder formationに必要なmovable集合が欠落しないため、folder formationが再度成立する）
And 適用後のcaptureはcanonicalであり、NULL spanがない
And 2回目と同一のmanifest行に対するrepeat organizeは `Applied || NoChanges` を返す。

### Scenario: 失敗/edge case — 同一起動先の行（修正前のfixture形状）

Given 行が同一の起動先を共有する（修正前のfixture形状）
When 全体整理をplanする
Then 重複超過分の保持により正当に `NoChanges` となり、`DUPLICATE_LAUNCH_TARGET` 警告が出る。これはspec 451が規定する挙動であり、既存のplanner単体testがcoverageするため本specでは再testしない。

## Acceptance criteria

- [ ] AC-487-1: 更新したfixtureで `pathC_manualEditThenSecondOrganize` が決定的にpassし、CI lane `organizer-instrumentation-reservation-recovery-tests` がPR上およびmerge後のmainで2連続以上のrunにおいてgreenであること。`seedLayoutWithFolder()` はpathC以外（pathA/pathB、legacy span系、app-pair source系）とも共有されるため、oracleには当該lane全体のgreenを含める。
- [ ] AC-487-2: pathCのassertionが弱体化していないこと。2回目のorganizeは引き続き `Applied` に到達し、canonical capture、no NULL span、2回目vs repeatのmanifest一致assertionは変更しないこと。
- [ ] AC-487-3: 実装diffが指定のinstrumentation test fileとspec文書のみに触れること（`lawnchair/src` のproduction codeなし）。
- [ ] AC-487-4: すべてのfixture行（6行）が相互に異なる起動先を持ち、その6componentがmerged manifestで宣言されていること（上記Scopeの6種）。各componentは `context.packageName` を使い、package-scopedな分類evidenceとpackage+profile単位のcategory overrideは不変であること。testのplanned categories / folder-formation期待値はそれ以外は不変であること。
- [ ] AC-487-5: `specs/269-folder-workspace-representability/spec.md` のchange historyがfixture reconciliationを記録すること（実装PRで行う）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-487-1 | PR上のlaneのCI run URL + merge後のmain run 2連続。`seedLayoutWithFolder()` はpathC以外（pathA/pathB、legacy span系、app-pair source系）とも共有されるため、evidenceはpathC単体でなく当該lane全体のgreenとする |
| AC-487-2 | instrumentation test fileのdiff review（assertion不変の確認） |
| AC-487-3 | 実装PRのdiff（変更file一覧） |
| AC-487-4 | fixture diffのreview（component宣言とmanifest） |
| AC-487-5 | 実装PRでのspec 269 change history差分 |
| 全AC | `./gradlew spotlessCheck`（local）。実装PRではtest-audit skillを適用し6項目のrecordを作成する |

## Open questions

なし。

## Change history

- 2026-10-01: Draft created for #487.
- 2026-10-01: Round-1 review（ChatGPT, Issue comment 5915616105）対応。fixture行6行分の相互異なるmerged manifest componentを明示し、scenario/ACの文言を修正。
- 2026-10-01: Round-2 review でClear。specをacceptedへ更新（Issue comment 5915836541）。
