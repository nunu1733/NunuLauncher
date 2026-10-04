# Plan: Issue #231 applied result outcome

> Spec: [spec.md](./spec.md)
> Status: implemented ([PR #262](https://github.com/nunu1733/NunuLauncher/pull/262) を merge commit `0f86386921` で squash merge。Phase1 review APPROVE、owner review 指摘対応済み)

## 現在の code の根拠

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `State.Applied(result, summary)` は confirm 時に `pendingPlan.summary` (planning Summary) を保持 (`ManualOrganizationRun.kt:421-424`)。`ApplyResult.Applied` のとき `appliedPoint` / `lastVerifiedApply` も保持 (`ManualOrganizationRun.kt:426-429`)。
  - **変更不要** (state shape / coordinator 契約は不変; spec D1)。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `State.Applied` 分岐: `applyMessage(currentState.result)` 見出し + `summaryItems(currentState.summary)` + recovery/start-again (`ManualOrganizationPreferences.kt:329-374`)。
  - `summaryItems` = `contextItems` + `changeCountItems` + `constraintItems` (`ManualOrganizationPreferences.kt:677-683`)。
  - `changeCountItems` が proposal 件数行 4 種を描画 (`manual_organization_moved_count` / `preserved_count` / `new_folders_count` / `new_pages_count`、`ManualOrganizationPreferences.kt:729-751`)。preview の degraded mode と `PlanningRejected` も同一関数を使用するため、**置き換えは `State.Applied` + `ApplyResult.Applied` の case に限定して分岐する**。
  - 理由 breakdown / warning / constraint 行の文字列は en が過去形・中立 (`Moved as a single placement: %1$d`、`Preserved locked placements: %1$d`、`Lock constraint preserved: %1$d` 等、`strings.xml:1072-1131`) であり、変更不要 (spec D3)。
  - `pluralStringResource` は既に import 済み (`ManualOrganizationPreferences.kt:37`、recovery history 行で使用)。
- strings: proposal 件数行 en `strings.xml:1026-1029`、ja `values-ja/strings.xml:108-111`。成功見出し en `strings.xml:1039`、ja `values-ja/strings.xml:121`。
- 既存テスト:
  - unit: `tests/unit/.../ManualOrganizationRunTest.kt` は state-level 主張のみ (`State.Applied.summary` / `.result`)。**UI 文言変更の影響なし**。
  - instrumentation: `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt`
    - `manual_organization_moved_count` の主張は **preview surface** のみ (行 328 / 462、`concreteChangeListDetails` の `PreviewCounts` 行) — 本変更で不変。
    - ja locale fallback 検出 test `japaneseResourcesResolveEveryConcretePreviewString` (行 1238 起点; strings list と plurals list の反復主張) — 新規 plurals を追加する箇所。
    - screenshot evidence test `capturesManualOrganizationReviewSurfaces` (行 1355-1414) が `success` screenshot を既に記録。
    - 非成功 surface の既存 test: `applyResult = ApplyResult.Unresolved` を設定する recovery-failure 系 test (設定箇所は行 1392 / 1482、`waitUntil` 主張は行 1411 / 1497)。
  - E2E: `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` `manualRunUsesProductionCaptureApplyVerificationAndRecovery` (行 159) が `preview.summary` 件数 (moved 2 / newFolder 1) と適用後 DB (3 items) を検証済み。適用前後 capture 差分からの導出主張を追加する。

## 変更 module

| File | 変更 |
|---|---|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `State.Applied` 分岐を `result is ApplyResult.Applied` で 2 分岐: 成功 case は新規 private `appliedResultItems(summary)` — **現行 `summaryItems` と同一の行順・interleave** (contextItems → moved count → movedByReason → preserved count → preservedByReason → new folders count → new pages count → rejectedByReason/unplacedByReason 反復 (常に空、行生成なし) → warnings → constraintItems) で、4 count 行の文字列のみ新規 plurals へ in-place 差し替え。非成功 case は現行 `summaryItems(summary)`。既存 `changeCountItems` は無変更。 |
| `lawnchair/res/values/strings.xml` | 新規 4 plurals (`manual_organization_applied_moved_count` / `applied_preserved_count` / `applied_new_folders_count` / `applied_new_pages_count`、spec D4 の en 文面)。既存 string の変更なし。 |
| `lawnchair/res/values-ja/strings.xml` | 同 4 plurals の ja (`other` のみ、spec D4 の文面)。 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | 新規 test: 成功適用の完了形描画主張 (AC-1/2)、recovery preview cancel 後の再表示主張 (AC-3)、非成功 1 case の現行描画主張 (AC-5)、ja/en plurals 解決 (AC-6)。既存 preview 主張・既存 test は無変更。 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | `manualRunUsesProductionCaptureApplyVerificationAndRecovery` に、適用前後の layout capture (`before` / `afterApply`、既に in-scope) 差分から導出した moved / new-folder 件数と `applied.summary` 件数の一致主張を追加 (AC-4)。 |
| `specs/52-manual-full-organization-vertical-slice/spec.md` | §"Result and recovery" に 1 文追記 (検証済み成功画面は完了形 applied counts を報告し、proposal の future-tense 件数行を表示しない)。 |

## Interface / seam

- 変更する seam: なし (UI 表示層のみ)。`ManualOrganizationRun.State`、`ManualOrganizationApplication`、`LayoutApplicationModule`、planner、diagnostics、strings の既存契約はすべて不変。
- 新規 public 型 / 新規 module / 新規 state なし。UI 内の private 関数追加のみ。

## Migration / rollback

- migration なし (永続化なし、preference なし、Launcher DB への影響なし)。
- rollback: PR revert。新規 4 plurals は同一 PR で追加されるため、revert で完全に現行へ戻る。

## Test

1. format: `./gradlew spotlessCheck`。
2. unit gate: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (既存 green の確認; UI 文言変更のため unit 影響はないはず)。
3. build: `./gradlew assembleLawnWithQuickstepGithubDebug`。
4. instrumentation: emulator (API 36.1) で organizer instrumentation test class を実行し、新規 test と既存 test を green にする。env は [building guide](../../docs/engineering/building.md) に従う。
5. device evidence (AC-8): emulator で適用成功 flow を実行し、result surface の screenshot を en と ja で記録し PR へ添付。Home 往復後の再訪 capture を含める。`capturesManualOrganizationReviewSurfaces` の `success` screenshot を差し替え/併記する。
6. spec 52 の追記 diff を PR で確認 (AC-7)。

## リスク

- risk label 不要: `organizer/application/**`、Launcher DB、recovery、grid migration への変更はゼロ (高リスク path 一覧の対象外、zero-write 表示層)。
- 既存 test への波及: `manual_organization_moved_count` 等 4 string の既存主張は preview surface のみ (`grep` 済み)。`State.Applied` 分岐の 2 分割により非成功 surface は既存関数を通し続けるため、既存 Unresolved 系 test は無変更で green であること verification で確認する。
- 文言の機械テスト限界: 完了形/予定形の「読み」は test が文字列存在/不在でしか主張できないため、D4 の文面 review と en/ja screenshot evidence を検証に組み込む。
