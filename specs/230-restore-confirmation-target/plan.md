# Plan: Issue #230 restore 確認の復元対象説明

> Spec: [spec.md](./spec.md) (status: accepted, head `436f2a7a54`)
> Status: draft (owner review 待ち)

## 現在の code の根拠

- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `State.RecoveryPreview(val result: RecoveryPreviewResult)` は result のみを運ぶ (`ManualOrganizationRun.kt:161`)。
  - `appliedPoint` / `lastVerifiedApply` は `ApplyResult.Applied` のときのみ、同一 `synchronized(lock)` 区間で同時更新される (`ManualOrganizationRun.kt:416-419`) — spec D2 の正 (invariant)。
  - `beginRecoveryPreview()` は `lastVerifiedApply != null && appliedPoint != null` のときだけ inspection し、`State.RecoveryPreview(preview)` を publish する (`ManualOrganizationRun.kt:429-466`、publish は 453-461)。相関ゲートの実装位置はこの publish 区間内である。
  - `cancelRecoveryPreview()` は `lastVerifiedApply` へ戻すのみ (zero-write、`ManualOrganizationRun.kt:468-475`)。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `State.RecoveryPreview` の描画は status 1 文 + decision pair のみ (`ManualOrganizationPreferences.kt:379-406`)。`recoveryPreviewMessage()` の `Restorable` → `manual_organization_recovery_preview` mapping (`ManualOrganizationPreferences.kt:1100-1113`)。
- strings (en `lawnchair/res/values/strings.xml:1041-1056`、ja `lawnchair/res/values-ja/strings.xml:123-138`):
  - `previous layout` 系: `manual_organization_recovery` (en 1049 / ja 131)、`manual_organization_recovery_inspecting` (en 1050 / ja 132)、`manual_organization_apply_rolled_back` (en 1041 / ja 123)、`manual_organization_apply_recovered` (en 1042 / ja 124)。
  - `saved layout` 系 (対象語は変更しない): `manual_organization_recovery_confirm` / `_recovering` / `_recovery_restored` / `_recovery_not_available` / `_recovery_failed` (en 1052-1056 / ja 134-138)。
  - `manual_organization_recovery_preview` (en 1051 / ja 133) は値を共通の戻り先文へ置換 (key は維持)。
- 既存テスト:
  - unit: `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt:778` (`cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`)。FakeApplication は `recoveryPreview` を設定可能 (`ManualOrganizationRunTest.kt:898-944`)。
  - E2E: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt:159` (`manualRunUsesProductionCaptureApplyVerificationAndRecovery`)、recovery 区間 232-244、fixture helper `clearRecoveryStoreArtifacts` (436) / `restoreFavorites` (463)。
  - instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` (state 遷移 + ja locale 解決 list + screenshot surface)。

## 変更 module

| File | 変更 |
|---|---|
| `organizer/ui/ManualOrganizationRun.kt` | `State.RecoveryPreview` を `(result, appliedSummary: Summary?)` へ。`beginRecoveryPreview()` の publish 区間内で相関ゲートを計算: `result is RecoveryPreviewResult.Restorable` かつ `(lastVerifiedApply?.result as? ApplyResult.Applied)?.pointId == result.pointId` のとき `lastVerifiedApply.summary`、それ以外は `null` を運ぶ。他の state・遷移・diagnostics は無変更。 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | `State.RecoveryPreview` 描画へ apply 履歴行を追加: `currentState.appliedSummary != null` のとき `SummaryText` 1 行 (prefix + 非 0 区分を `" / "` で連結、全 0 なら行ごと省略)。status 見出し・decision pair・focus/liveRegion 構成は現行維持。 |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | ① `manual_organization_recovery_preview` の値を共通の戻り先文へ置換。② 新規 4 string: `manual_organization_recovery_history_prefix` / `_history_moved` / `_history_new_folders` / `_history_new_pages`。③ 用語統一 4 key (`manual_organization_recovery`、`_recovery_inspecting`、`_apply_rolled_back`、`_apply_recovered`) の `previous layout` / 「以前のレイアウト」を `saved layout` / 「保存したレイアウト」へ置換。en/ja 同時。 |
| `tests/unit/.../ManualOrganizationRunTest.kt` | (a) pointId 一致 → `appliedSummary` 運搬、(b) `appliedSummary == null` の state 構築可能 + UI fallback 前提、(c) pointId 不一致 → `null`、(d) Apply A → Apply B → B のみ、(e) 新規 run instance (restart 相当) は recovery 確認へ遷移しない、non-restorable → `null`。既存 cancel test の継続成功。 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | RecoveryPreview surface の履歴行描画 test、`appliedSummary == null` fallback 描画 test (confirm は有効)、ja locale 解決 list へ新 string 追加、screenshot surface の state 比較更新。 |
| `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` | recovery 区間拡張: `State.RecoveryPreview.appliedSummary` が適用 plan の summary と一致することの assert。apply 後に外部 row を 1 件追加した case を追加し、confirm 後の capture が apply 前の rows と一致 (余分 row は recovery write-set により明示削除 = SA-18) することを deterministic に検証。 |

## 文言 (実装時に最終化、en/ja 同時)

| Key | en (案) | ja (案) |
|---|---|---|
| `manual_organization_recovery_preview` (値置換) | `The saved layout — this returns your home layout to the state before the organization you reviewed on this screen. Confirmation is required to proceed.` | `保存したレイアウト — この画面で確認した整理を適用する前の状態へ、ホームレイアウトを戻します。実行するには確認が必要です。` |
| `manual_organization_recovery_history_prefix` (新規) | `Changes made by the last organization:` | `直前の整理での変更:` |
| `manual_organization_recovery_history_moved` (新規) | `moved %1$d placements` | `移動 %1$d 件` |
| `manual_organization_recovery_history_new_folders` (新規) | `created %1$d folders` | `新規フォルダ %1$d 個` |
| `manual_organization_recovery_history_new_pages` (新規) | `added %1$d pages` | `新規ページ %1$d ページ` |
| `manual_organization_recovery` (用語) | `Restore the previous layout` → `Restore saved layout` | `以前のレイアウトに戻す` → `保存したレイアウトに戻す` |
| `manual_organization_recovery_inspecting` (用語) | `…the previous layout…` → `…the saved layout…` | `以前のレイアウトを…` → `保存したレイアウトを…` |
| `manual_organization_apply_rolled_back` (用語) | `Your previous layout remains in place.` → `Your saved layout remains in place.` | `以前のレイアウトのままです。` → `保存したレイアウトのままです。` |
| `manual_organization_apply_recovered` (用語) | `Your previous layout was restored.` → `Your saved layout was restored.` | `以前のレイアウトを復元しました。` → `保存したレイアウトを復元しました。` |

履歴行の組み立ては UI 側で行う: 非 0 区分のみを宣言順 (moved → folders → pages) で `" / "` 連結し、全 0 なら行自体を省略する (spec D3/D4)。`RecoveryPreviewSummary` (spec 84) と application 契約には手を触れない。

## Interface / seam

- 変更する seam は `ManualOrganizationRun.State` (UI が観測する state machine) のみ。`ManualOrganizationApplication` facade、`LayoutApplicationModule` (spec 84 の `inspectRecovery` / `RecoveryPreviewResult` / `RecoveryPreviewSummary`)、spec 13 の protocol、diagnostics record 形式は不変。
- `appliedSummary` は process-local な表示用 derived value であり、`Summary` (既存型) の再参照にすぎない。新識別子・新永続化は追加しない (spec D2)。

## Migration / rollback

- migration なし (永続化なし、preference なし、schema 変更なし)。
- rollback: PR revert。string の値置換と state field 追加のみのため、revert で完全に現行へ戻る。

## Test (spec AC との対応)

1. unit: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunTest'` — AC-1 / AC-2 (a)–(e) / AC-4。
2. organizer unit gate: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`。
3. instrumentation: emulator で `ManualOrganizationPreferencesInstrumentationTest` (履歴行描画、fallback 描画、ja 解決) と `ManualOrganizationProductionE2EInstrumentationTest` (AC-5: appliedSummary 一致 + 外部 row 追加 case の pre-state 一致) を実行。
4. 通常 build: `./gradlew assembleLawnWithQuickstepGithubDebug`。
5. format: `./gradlew spotlessCheck`。
6. UI 証拠: emulator で Apply → Home 確認 → Organizer 再訪 → Restore confirmation (en/ja screenshot) → Cancel 経路と Restore 経路の両方を記録し PR へ添付 (AC-7)。AC-3 は strings diff + `previous layout` 系残存 grep + #161 LQA 規約の用語確認で検証する。

## リスク

- `State.RecoveryPreview` の shape 変更により equality / `is` 比較が壊れる可能性 → 参照箇所は unit test・instrumentation test・UI 1 箇所のみ (grep 済み)。positional constructor を使う箇所は named argument へ寄せる。
- risk label 不要: `organizer/application/**`、Launcher DB、recovery store への変更はゼロ (高リスク path 一覧の対象外)。Cancel / confirm の zero-write 契約は既存 unit test と E2E が oracle。
- 文言の機械テスト限界: 件数行の組み合わせ (0 区分の省略、全 0 の行省略) は unit/instrumentation の描画 test で覆盖しきれない可能性があるため、screenshot による目視確認と spec D3/D4 文面の owner review を検証に組み込む (spec 210 の plan と同一方針)。
- 履歴行の truth (`Summary`) と表示の対応は E2E の appliedSummary assert で検証するが、「履歴件数 ≠ restore 実差分」の性質自体は spec 13 の recovery write-set 検証 (SA-18) に依存する。外部 row 追加 case はこの依存の統合面での確認である。
