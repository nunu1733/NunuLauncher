# Plan: Issue #210 stale apply outcome

> Spec: [spec.md](./spec.md)
> Status: implemented ([PR #240](https://github.com/nunu1733/NunuLauncher/pull/240) を merge commit `38f9639163` で squash merge。owner review での承認済み)

## 現在の code の根拠

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `State.Stale` は `data object` (`ManualOrganizationRun.kt:148`)。
  - 経路 1: `start()` 内 `PlanPreviewResult.Stale` → `transitionToStale(operation, emitRejection = true)` (`ManualOrganizationRun.kt:276`)。
  - 経路 2: `confirm()` 内 materialize 失敗 → `transitionToStale(operation, emitRejection = true)` (`ManualOrganizationRun.kt:372`)。
  - 経路 3: `confirm()` → `apply()` の `ApplyResult.Rejected(STALE_REVISION | EXACT_PRECONDITION_FAILED)` → `State.Stale` (`ManualOrganizationRun.kt:393-394`)。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `State.Stale` 描画は `manual_organization_stale` 見出し + `manual_organization_recapture` 行のみ (`ManualOrganizationPreferences.kt:295-304`)。
- strings: `lawnchair/res/values/strings.xml:1032-1033`、ja: `lawnchair/res/values-ja/strings.xml:116-117`。
- 既存テスト:
  - unit: `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` の 290 / 507 / 595 行が `State.Stale` equality。
  - instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt:1347` が `== State.Stale` 比較。`FakeApplication` は `applyResult` / `materializationInvalid` / `inspectPlanOverride` を設定可能。
  - E2E: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt:352` (`staleProductionConfirmationDoesNotWrite`) が実 production module + DB で zero-write を検証済み。

## 変更 module

| File | 変更 |
|---|---|
| `organizer/ui/ManualOrganizationRun.kt` | `State.Stale` を origin 付き data class へ。`StaleOrigin` enum を追加。`transitionToStale` に origin 引数。経路 1 = `DETECTED_BEFORE_REVIEW`、経路 2・3 = `APPLY_BLOCKED`。 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | `State.Stale` 描画を outcome 見出し + origin 別詳細文 + recapture (subtitle 付き) へ。 |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | `manual_organization_stale` → `manual_organization_stale_outcome` 置換、新規 3 string 追加 (spec §D2 の文面)。 |
| `tests/unit/.../ManualOrganizationRunTest.kt` | 3 stale test を origin 付き equality へ更新。 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | stale 適用試行 test 追加 (AC-1)、確認前 stale 表示 test 追加 (AC-2)、ja 解決 list へ新 string 追加 (AC-3)、screenshot test の state 比較更新。 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | `staleProductionConfirmationDoesNotWrite` に `APPLY_BLOCKED` origin 主張を追加 (AC-5)。 |

## Interface / seam

- 変更する seam は `ManualOrganizationRun.State` (UI が観測する state machine) のみ。`ManualOrganizationApplication` facade、`LayoutApplicationModule`、planner 契約、diagnostics record 形式は不変。
- `State.Stale` は UI 専用 observable であり、`StaleOrigin` は `ManualOrganizationRun` 内の enum として定義する (application public 契約へ出さない)。

## Migration / rollback

- migration なし (永続化なし、preference なし)。
- rollback: PR revert。旧 `manual_organization_stale` string は同一 PR で削除されるため、revert で完全に現行へ戻る。

## Test

1. unit: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunTest'` (origin equality)。
2. organizer unit gate: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`。
3. instrumentation: organizer instrumentation lane (既存 ManualOrganization 関連 test class) を emulator で実行し、新規 2 test と更新済み test を green にする。
4. 通常 build: `./gradlew assembleLawnWithQuickstepGithubDebug` の完了確認。
5. format: `./gradlew spotlessCheck`。
6. UI 証拠 (UI 変更 PR として): 既存 `capturesManualOrganizationReviewSurfaces` が記録する stale surface screenshot を取得し、outcome → 破棄詳細 → recapture summary の描画を目視確認する (en)。ja は ja locale 解決 test (新規 4 string が fallback しない) と ja copy の visual review で代替する。

## リスク

- `State.Stale` の shape 変更により、同 equality / exhaustiveness を前提とする箇所が壊れる可能性 → 全参照箇所は grep 済み (spec「現在の code の根拠」)。`when` 分岐で `State.Stale` を使う箇所は UI 1 箇所のみ (`is` で受けるため影響なし)。
- risk label 不要: `organizer/application/**`、Launcher DB、recovery への変更はゼロ (高リスク path 一覧の対象外)。
- 文言の機械テスト限界: 各 string の表示 assertion は「文の組み合わせの矛盾」を検出できない (owner review #1 の指摘どおり)。このため plan の検証に screenshot による目視確認と spec D2 文面の owner review を組み込む。
