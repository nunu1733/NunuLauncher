# Issue #441 — Fixture Seeding Verification Evidence

> Status: Complete（AC-2/AC-3/AC-4の実行記録。baseline実測（AC-7）は別記録 `editing-burden-baseline.md`（計測後に作成））
> Date: 2026-09-26
> Environment: reference系emulator `nunu_smoke_api35`（Pixel 6、API 35、1080x2400 @ 420dpi。performance-budgets §2.1のreference profile）。grid 4列×5行（既定 `4_by_5`）、QSB/Smartspace有効（既定）
> Build: Lawnchair 15 Dev debug APK（commit `7717a82` worktree。branch `issue-441-editing-burden-benchmark`）+ androidTest APK（fixture起動先35 aliasを含む）
> Host: macOS（darwin arm64）、JDK 21

## 1. Restore mode（AC-2/AC-3/AC-4のoracle実行）

2 test（fixture同一性契約 + 既存hotseatフォルダとその子孫の非自明保持）を実行する。

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest
```

Result: **BUILD SUCCESSFUL — 2 tests, PASS**（`fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations`、`seedingPreservesPreExistingHotseatFolderDescendants`）。実行SHA等の詳細は§5。

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

## 3a. B1 install protocol実機検証（Phase 2 review対応、2026-09-26）

B1/B6のinstall経路と自動配置を同emulatorで検証した:

- `adb install`（reason 0 = `INSTALL_REASON_UNKNOWN`）では `SessionCommitReceiver` が「Removing PromiseIcon ... install reason: 0」を出し自動追加が**起きない**ことを確認（logcat）。
- `pm install-create --install-reason 4`（4 = user request = `INSTALL_REASON_USER`）→ `install-write` → `install-commit` のsession installにより、**新規アイコン「Benchmark Target 01」がfixtureの2ページ目の最初の空きcell（screen=1, cell(0,3)）へ自動配置された**（install約8秒後）。配置の正はDB行（`favorites` のscreen=1, cellX=0, cellY=3）と `b1-target01-page1.png` である。なおlogcatの「AddWorkspaceItemsTask: Adding item info to workspace ... cell(0,0)」は、配置cell決定前のqueue項目（`ItemInstallQueue` のPendingInstallShortcutInfo）を示すlogであり、最終配置座標ではない。
- 検証に使った固定対象アプリは `tests/benchmark-install-targets/`（flavor `target01`。10 flavorでB6の10個を賄う）。
- Lawnchairが既定ランチャーでない場合、新規アイコンは出現しない（`pm clear` 後に既定homeがNexusLauncherへ戻った状態で再現し、`cmd package set-home-activity` でLawnchairへ戻すと解消）。§7の端末前提状態に記載。

evidence: `b1-target01-page1.png`（2ページ目のrow 3に「Benchmark ...」アイコンが見える）。

## 4. 残置事項

- AC-7/AC-8: 実機Pixel 9aでのbaseline計測（3試行/課題、中央値、max/min > 1.5で追加2試行）と目標確定+NFR-014確定。保守者が定義文書§7の手順で実施する（後続PRが `Closes #441` となる）。
- 本文書のエミュレータ実行は追加evidenceである。PRのmerge gateはCI run（`surface_organizer_ui` 差分としてmanual-organization-ui laneが起動）で確認する。

## 5. Clean checkout実行記録（head `7102b0d4bf`）

実装reviewのprovenance指摘を受け、**クリーンcheckout（git worktree、submodule初期化済み）で全検証を再実行**した（2026-09-26）。最終検証対象headは `7102b0d4bf`（`941f332f81` の直後のhotseat slot修正1件を含む）。本節以降の追記はdocumentation差分のみであり、検証対象treeとソースコードは同一である。

| 項目 | Command | Result |
|---|---|---|
| AC-2/3/4（fixture契約2 test） | `ANDROID_SERIAL=emulator-5554 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest` | BUILD SUCCESSFUL。2 test PASS |
| §2 persist（method限定、review指摘1対応） | `am instrument -w -e persist true -e class ...EditingBurdenBenchmarkFixtureSeedingInstrumentationTest#fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations ...`（clean checkoutからbuild） | 正常終了（Time 0.962）。DB検証: `Dock folder` 行=0（検証用注入なし）、hotseat行=4（既定dockのまま）、desktop app行=35、合計42行=fixture+既定dock+フォルダ内容 |
| AC-6 | `./gradlew spotlessCheck` | BUILD SUCCESSFUL |
| AC-5（portfolio整合） | `validate_ci_portfolio.py` / `test_validate_ci_portfolio.py` | OK / OK |
| AC-1（link・契約） | `validate_repo_contract.py` / `test_validate_repo_contract.py` | repository contract OK（両方） |

環境: macOS darwin arm64、JDK 21（Homebrew 21.0.12）、reference系emulator `nunu_smoke_api35`（Pixel 6、API 35、4列×5行）。
