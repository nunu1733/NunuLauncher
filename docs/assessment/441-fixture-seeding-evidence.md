# Issue #441 — Fixture Seeding Verification Evidence

> Status: Complete（AC-2/AC-3/AC-4の実行記録。baseline実測（AC-7）は別記録 `editing-burden-baseline.md`（計測後に作成））
> Date: 2026-09-26
> Environment: reference系emulator `nunu_smoke_api35`（Pixel 6、API 35、1080x2400 @ 420dpi。performance-budgets §2.1のreference profile）。grid 4列×5行（既定 `4_by_5`）、QSB/Smartspace有効（既定）
> Build: Lawnchair 15 Dev debug APK（commit `7717a82` worktree。branch `issue-441-editing-burden-benchmark`）+ androidTest APK（fixture起動先35 aliasを含む）
> Host: macOS（darwin arm64）、JDK 21

## 1. Restore mode（AC-2/AC-3/AC-4のoracle実行）

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest
```

Result: **BUILD SUCCESSFUL — 1 test, PASS**（`fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations`）。

検証内容（spec 441 AC-2〜4に対応）:

- AC-2: 同一fixture入力表から「fixture対象graph削除→insert」を2回実行し、正規化projection（container/screen/cell/span/type/intent/title/rank、folder参照はid解決、`_ID`/`MODIFIED`は除外）が一致（P1==P2）。収容契約（全root収容、参照grid 4×5でページ0がQSB予約を除き満杯、ページ1に空きcell≥1）もassertionで成立。
- AC-3: hotseat行・その子孫・予約領域重複行の前後一致。fixture全行について `ReservationOverlapAcceptance.overlaps`（ADR-0010の唯一の受入述語）がfalse。test終了時にfavoritesが復元され、永続残SIなし（`assertEquals(originalRows, snapshotFavorites())`）。
- AC-4: desktop app行35、identity一意性（33 distinct identities on desktop）、同一起動先の重複が指定2組のみ（Fixture 02 ×2 = ページ0、Fixture 03 ×2 = ページ1。判定キー component + profile）、フォルダ「Benchmark」とその内容（Fixture 01・35）。

## 2. Persist mode（計測手順§7の検証）

計測手順（定義文書§7）と同じ手動手順を同環境で実行し、fixtureが端末へ保持されることを確認した:

```bash
adb install -r "build/outputs/apk/lawnWithQuickstepGithub/debug/"*.apk
adb install -r "build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/"*.apk
adb shell am instrument -w -e persist true \
  -e class app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
```

Result: **OK (1 test)**。persist modeでは復元をskipするためfixtureが端末に残る。launcher起動後のfavorites表に、フォルダ「Benchmark」（`itemType=2`、ページ0 (0,1)）とその内容2行（Fixture 01・35、`container=55`）が残ることを `run-as ... sqlite3` で確認した。

## 3. Fixtureホームの視覚証跡

persist mode適用後のホーム（実物。`connectedLawnWithQuickstepGithubDebugAndroidTest` の自動uninstall対象外となる手動install環境）:

| Page | Screenshot | 内容 |
|---|---|---|
| 0 | `fixture-page0.png` | QSB（既定「Tap to set up」）+ 指定フォルダ「Benchmark」（2項目プレビューアイコン）+ Fixture 04〜11・重複ペア「Fixture 02」×2（視覚的に同一起動先と分かる隣接配置）・19〜23。ページ0満杯（予約除く16 root） |
| 2 | `fixture-page2.png` | Fixture 17・18（B3対象）+ 29〜34（通常）の8個。残り12cellが空き（B2の受け皿） |

## 4. 実装中に確認した注意事項

- **1項目フォルダの自動展開**: launcher loaderは項目1個のフォルダをiconへ自動変換する（logcat `LAUNCHER_FOLDER_CONVERTED_TO_ICON`、`LoaderTask.sanitizeData`）。fixtureフォルダはseed済みアイテム2個（Fixture 01・35）を含む（plan A-8/A-10 Revision 5）。
- **organizerのonboarding提案**: fixture適用後のhome起動時に「Organize your Home screen?」のonboarding提案（T-19）が表示されることがある。提案は確認まで何も変更しないため、「LATER」で閉じて計測する（定義文書§7に記載）。
- **汚染された事前状態**: 不正なfolder参照（存在しないfolderへのcontainer）が残るDBでは、上流のsnapshot処理が意図的にcrashする（`QuickstepModelDelegate.getContainer`、b/173838775対策のupstream設計）。本fixtureはクリーンな状態を前提とし、seeding自体はそのような行を生成しない（本testが同一性・保持を検証する）。

## 5. 残置事項

- AC-7/AC-8: 実機Pixel 9aでのbaseline計測（3試行/課題、中央値、max/min > 1.5で追加2試行）と目標確定+NFR-014確定。保守者が定義文書§7の手順で実施する（後続PRが `Closes #441` となる）。
