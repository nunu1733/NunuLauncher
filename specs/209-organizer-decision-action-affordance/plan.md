# Implementation Plan: Organizer の decision action affordance

> Issue: #209
> Spec: [spec.md](./spec.md)
> Status: planned

## Current evidence

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`: `State.Preview` の confirm / cancel は list 末尾の `ClickablePreference` 2 item。`State.RecoveryPreview` の confirm / cancel も同様。`State.Applied` の `Restore the previous layout` も `ClickablePreference`。
- `lawnchair/src/app/lawnchair/ui/preferences/components/controls/ClickablePreference.kt`: `PreferenceTemplate` + `clickable` のみ。fill / border 無し → 静的 text と視覚等価。
- decision 表現の既存参照: `PreferenceClickConfirmation` (確認 bottom sheet: cancel=`OutlinedButton` / confirm=`Button`)、`PermissionDialog` (confirm=`Button` / dismiss=`TextButton`)。
- `MaterialTheme` / M3 button は preference 系 surface で既に使用済み。新 dependency は不要。
- 既存 test: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` — focus 復帰 / liveRegion / traversal (`pressDownUntilFocused`) / 200% font scale / ja locale 解決 / degraded fallback を網羅。traversal test は現行順序 (status → expand → confirm → cancel) を主張しており、新順序へ更新が必要。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `ManualOrganizationPreferences.kt` | (1) `State.Preview`: 見出し直後に decision group item を追加し、末尾の confirm / cancel `ClickablePreference` 2 item を削除。(2) `State.RecoveryPreview`: 同構成の decision group。(3) `State.Applied`: `Restore the previous layout` を `FilledTonalButton` item へ。新規 private composable `DecisionActionsRow` (`Column` + `Button` / `OutlinedButton` / `FilledTonalButton`) を追加 | spec 209 D1–D3 の正本実装場所 |
| `tests/.../ManualOrganizationPreferencesInstrumentationTest.kt` | 同時視認 test (初期表示 + 展開後)、`Role.Button` test、Applied recovery entry / RecoveryPreview 描画 test を追加。既存 traversal test を新順序へ更新 | AC-1–4 の正証明 |
| `specs/52-manual-full-organization-vertical-slice/spec.md` | §"Preview and details" 付近の traversal 記述を新順序へ更新 (decision group は spec 209 を参照) | spec 209 AC-5 |
| `specs/195-organizer-confirmation-change-list/spec.md` | change history に issue #209 による traversal 順序更新を追記し、AC-5 / scenario 内の順序表記を新順序へ更新 | 同上 |

## Migration and recovery

- schema / rule migration: なし。zero-write。PR revert で戻る。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | instrumentation test: 6-move fixture で初期表示時と展開後の confirm/cancel `assertIsDisplayed`、degraded fallback 含む | organizer instrumentation lane (API 36.1) |
| AC-2 | instrumentation test: recovery entry / RecoveryPreview の button 描画主張 | 同上 |
| AC-3 | instrumentation test: `SemanticsProperties.Role == Role.Button` | 同上 |
| AC-4 | 既存 test 群の通過 (traversal test は新順序へ更新) | 同上 |
| AC-5 | PR diff review | 手動 |
| AC-6 | `./gradlew spotlessCheck`、`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`assembleLawnWithQuickstepGithubDebug`、CI `final-status` | local + CI |

実装順 (縦切り):

1. `DecisionActions` composable + Preview state への配置変更 (末尾 item 削除)。
2. RecoveryPreview / Applied への適用。
3. instrumentation test 追加・更新。
4. spec 52 / spec 195 の記述更新。
5. 検証実行 → PR。

## Documentation updates

- [ ] spec 209 status/history (merge 時 `implemented`)
- [ ] spec 52 / spec 195 の traversal 記述更新 (AC-5)
- [ ] CONTEXT.md / DESIGN.md / ADR — presentation-only のため更新不要見込み
