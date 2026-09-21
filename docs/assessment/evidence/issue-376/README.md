# Issue #376 hub status card復元（D-15）cold-process emulator evidence

> Status: Captured（2026-09-22。RS-AC-01の実機capture）
> Spec: [specs/376-durable-status-recovery-entry/spec.md](../../../specs/376-durable-status-recovery-entry/spec.md)
> Runtime: 専用AVD `issue142_api36`（Android 16 / API 36、1080x2400 @ 420dpi）。
> APK: 実装branch head（`issue-376-recovery-entry`）でbuildしたdebug APK
> （`Lawnchair.15.Dev.(52003249).github.debug.apk`。commit表記はビルド時のhead）。
> 自動test: `OrganizerHubPreferencesInstrumentationTest`（20 tests、CTA・残時間・
> read直列化・復元後再deriveを含む）と `ManualOrganizationPreferencesInstrumentationTest`
> （settings側行は表示のみの回帰）、`ManualOrganizationProductionE2EInstrumentationTest`
> （既存recovery経路の無変更証拠）を同一AVD上で実行し全件成功。
> CI正本は `organizer-instrumentation-issue52-tests` job。
>
> 実施中に判明した実装修正: cold settings-only process（`startLoaderWithoutCallbacks`
> 経由でmodel負荷済み・Launcher非bind）では、`LauncherModel.forceReloadForOrganizer` が
> 即cancelして復元のcorrelated reloadが必ず `MODEL_RELOAD_FAILED` になった。
> Issue #299のrestore reloadと同一の規約（非bind時はtokenless loaderを起動し、
> `loaderStarted`で生成生成を判定）を `LauncherModel` bridgeへ適用して解消
> （[LauncherModel.java](../../../../src/com/android/launcher3/LauncherModel.java) の
> Issue #376節）。recovery protocol・reload完了境界（#150/#152）の契約は不変。

## Captures（実解像度のまま保存）

| file | 条件 | 証跡 |
|---|---|---|
| `evidence1_cold_hub_restorable.png` | force-stop → deep linkでhubをcold起動（Launcher workspaceは未起動）。reconciliation完了後 | RS-AC-01前半: cold processでstatus cardにrestorable行＋残時間「Restorable for about 23 more hours.」＋復元CTAが表示される |
| `evidence2_cold_confirmation_face.png` | CTA tap直後（確認面） | 検査→確認面へ到達。共通の戻り先文のみで適用履歴行なし（spec 230 D2・cold相関なし）。confirm/cancel decision pair（spec 84/230契約どおり） |
| `evidence3_cold_result_face.png` | 確認tap後 | 「The saved layout was restored.」— cold processでの復元実行が成功（spec 13 protocol・writer lease・transaction・検証は既存経路のまま） |
| `evidence4_cold_hub_after_restore.png` | system Back → hub再帰 | 明示的hub帰還でcoordinatorがpre-entry状態へ復帰し、durable statusが再derive: 単一restored pointのため行は「restored or expired」表示へ切替（RS-AC-01後半・RS-AC-02の単一点ケース） |

## 再現手順

```bash
# 0. build & install（debug）
./gradlew assembleLawnWithQuickstepGithubDebug
adb install -r build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.*.github.debug.apk

# 1. production seamで検証済み適用をseedしrestorable stateを残す
#    （2フェーズevidence testのseed phase。CI lane外のevidence tooling）
adb shell am instrument -w \
  -e class app.lawnchair.organizer.ui.OrganizerRestoreColdProcessEvidenceTest#seedRestorableProductionState \
  -e phase seed app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner

# 2. process boundary
adb shell am force-stop app.lawnchair.debug

# 3. cold起動（Launcherを開かずにhubを最初の面とする。spec 271 DS-AC-10と同型）
adb shell "am start -W -n app.lawnchair.debug/app.lawnchair.ui.preferences.PreferenceActivity \
  -e 'app.lawnchair.ui.preferences.DESTINATION_ROUTE' \
  '{\"type\":\"app.lawnchair.ui.preferences.navigation.HomeScreenOrganizer\"}'"

# 4. reconciliation完了を待ち（durable行＋残時間＋CTAが表示される）、
#    CTA → 確認 → 復元 → Back をUI駆動（uiautomator dumpで座標を取りながら）
#    各段階のscreenshotが本dirのevidence1..4。
```

備考:
- 復元の確認tokenはcold process内でfreshに発行され（spec 84 RP-AC-05のregistryは
  module instance state）、永続化されない。seed processのtoken/適用contextは
  force-stopで消滅する（RS-AC-03の実機裏付け。unit層の構造的固定は
  `LayoutApplicationModuleRestorableEntryTest`）。
- 復元完了後の残存 `VERIFIED` pointがある場合の再提示（RS-AC-02の複数点ケース）は
  hub instrumentation testとselector unit testで固定。
- `OrganizerRestoreColdProcessEvidenceTest` のrestore phase（compose駆動版）も
  同一検証を自動化するものだが、実機evidenceは本READMEのdeep link + UI駆動を正本とする。
