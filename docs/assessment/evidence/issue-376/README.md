# Issue #376 hub status card復元（D-15）cold-process emulator evidence

> Status: Captured（2026-09-22。RS-AC-01の実機capture）
> Spec: [specs/376-durable-status-recovery-entry/spec.md](../../../../specs/376-durable-status-recovery-entry/spec.md)
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
| `rsac03_1_preview_live_before_process_death.png` | cold起動 → CTA → 確認面表示中 | RS-AC-03前半: preview（とone-shot token）が生きている状態 |
| `rsac03_2_cold_reentry_shows_cta_again.png` | 上記のままforce-stop → cold再入場 | RS-AC-03中盤: process死でpreview/tokenは消滅し、再入場面では確認面ではなくstatus cardのCTAが再提示される（再開手段は再検査のみ） |
| `rsac03_3_restored_after_reinspection.png` | CTA → 再検査 → 確認 | RS-AC-03後半: fresh tokenでの再検査経由でのみ復元が完結する（旧confirmの再開は存在しない）。実行後の行はrestored or expired |

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

### RS-AC-03（preview表示中のprocess死 → 再検査のみ再開）の追加手順

```bash
# seed → force-stop → cold起動（手順3まで同じ）→ CTA tapで確認面まで進める
#   → rsac03_1（preview生存）をcapture
adb shell am force-stop app.lawnchair.debug     # preview + tokenごと消滅
# → cold再起動（手順3）→ status cardにCTAが再提示（rsac03_2）
# → CTA → 再検査 → 確認 → 復元成功（rsac03_3）→ Back → 行はrestored or expired
```

実施日: 2026-09-22。旧tokenでのconfirmは死んだprocessのregistryごと消滅しているため
不可能であり（unit層の構造的固定: `LayoutApplicationModuleRestorableEntryTest`
freshModuleInstanceCannotConsumeTheOldConfirmationToken）、再開手段はCTAからの
再検査のみであることを実機で確認した。

備考:
- 復元の確認tokenはcold process内でfreshに発行され（spec 84 RP-AC-05のregistryは
  module instance state）、永続化されない。seed processのtoken/適用contextは
  force-stopで消滅する（RS-AC-03の実機裏付け。unit層の構造的固定は
  `LayoutApplicationModuleRestorableEntryTest`）。
- 復元完了後の残存 `VERIFIED` pointがある場合の再提示（RS-AC-02の複数点ケース）は
  hub instrumentation test `restoreSuccessRepresentsTheRemainingPointAfterHubReturn`
  （複数点→最新復元→hub帰還→残存pointの再提示）とselector unit testで固定。
- `OrganizerRestoreColdProcessEvidenceTest` のrestore phase（compose駆動版）も
  同一検証を自動化するものだが、実機evidenceは本READMEのdeep link + UI駆動を正本とする。
