# Audit: PR #486 「ホームを整理」workspace popup項目（#452 Phase 2）

> Status: **HOLD（条件付きGO）。内容面の実装・test・patch surface・CI attributionはspec Revision 3と整合し、本auditで新たなblocking findingは確認していない。ただし (1) `final-status` が赤（main由来の既存失敗 #487。本PR diffへの帰因なし）、(2) accepted specのAC-5（実機操作数記録）とAC-6（実機TalkBack/Switch Access）が未完了、の2点が解消するまでmerge不可。前者はowner判断事項（既存flake/障害のgate扱い）、後者はownerの物理デバイスでの確認事項である。**
> Audit date: 2026-09-30

- Auditor: 独立session（general-purpose subagent）。実装sessionではなく、本PRのdiff作成・Phase 1/2 review・検証実行に関与していない。実装sessionの主張は無検証では採用せず、repo/PR/CI evidenceから独立に再確認した。
- PR: https://github.com/nunu1733/NunuLauncher/pull/486
- Base SHA: `56624406fda754ee6789380dbed415af86583fcc`（origin/main。#451 merge後）
- Head SHA: `16f9cba0f1ea4fd731a92be9ac00cb454dd3b7ef`
- Spec revision identity: `specs/452-organizer-home-entry/spec.md` **accepted, Revision 3**（Phase 1 review round 1 [Request changes](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912068245) → round 2 [3指摘解消確認](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912289396) → round 3 [Clear](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912426507)で受理。Revision 3 snapshot [comment](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912395484)）。head上のspecは frontmatter `status: accepted` を維持し、Change historyの2026-09-30 entryで「implemented（ただし受入はAC-5/AC-6の実機確認待ち）」と自己記述している（Phase 2 review round 2指摘への対応どおり）。
- Issue: https://github.com/nunu1733/NunuLauncher/issues/452（本文+全コメント7件を確認。snapshot/review/acceptanceの経過はspec Change historyと一致）

## Scope reviewed

- 対象diff: `git diff 56624406fd..16f9cba0f1` = **30 files, +720/-39**。監査者が全pathを列挙し、production code 3 file・resource 3 file・test 1 file・CI 1 file・docs/spec/assessment・evidence PNG 17点に分類して確認した。
- 変更path一覧（production/source）:
  - `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt`（+95行相当）
  - `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt`（1行）
  - `lawnchair/res/values/strings.xml` / `lawnchair/res/values-ja/strings.xml`（各+2）
  - `lawnchair/res/drawable/ic_organize_home.xml`（新規31行）
  - `tests/unit/app/lawnchair/ui/popup/LauncherOptionsPopupOrderTest.kt`（新規199行）
  - `.github/workflows/ci.yml`（`organizer-unit-tests` の `--tests` filterへ `app.lawnchair.ui.popup.*` 追加）
  - `docs/engineering/ci-test-portfolio.md` / `docs/engineering/quality-strategy.md`（mirror更新）
  - `docs/assessment/upstream-patch-surface-baseline.json`（再採択）、spec/plan/evidence文書、evidence PNG 17点
- **AC-7（書込み経路なし）: PASS**。`organizer/` 配下・`src/com/android/launcher3/**`（model含む全上流）・DB/migration pathはdiff内に**1件も存在しない**（`git diff --name-only` で機械確認）。唯一の書込みは `restoreMissingPopupOptions` 内の既存 `launcherPopupOrder` 経路で、本PRはそこにno-op guardを追加するのみ。handlerは `PreferenceActivity.createIntent(view.context, HomeScreenManualOrganization())` + `startActivity` + `true` のみ（onboarding実例と同一仕組み）。`OptionsPopupView.java` は無変更。

## Spec/AC conformance

- **AC-1: PASS（コード+JVM test+emulator evidence）**。`DEFAULT_ORDER` は `carousel, lock, edit_mode, organize_home, edit_surface, wallpaper, widgets, home_settings, sys_settings` となり、`organize_home` は既定ON。`getMetadataForOption("organize_home")` は `home_screen_organize` / `ic_organize_home` へmapping。stringsはEN "Organize home screen" / JA「ホームを整理」（確定copyどおり）。iconは2×2 rounded-square cell（arc使用。review round 1のP2指摘「sharp-corner square」修正後のpath）+ 右上4-point sparkleで、accepted glyphと一致。
- **AC-2: PASS（コード+emulator evidence）**。handlerはrun面routeを直接開き、hub中継なし。admission/start契約への変更なし（`organizer/` 無変更で機械確認済み）。
- **AC-3: PASS（コード+JVM test+emulator evidence）**。lock filterは `filterVisiblePopupOptions` に切り出され、`hiddenWhileLocked = {edit_mode, organize_home, edit_surface, widgets}`。`LauncherPopupPreference` のlock無効化分岐へ `organize_home` を追加（`edit_mode`/`widgets` と同一扱い）。#449の `edit_surface` が分岐に無い現状はspec/planどおり #449残課題として触れていない（本PRの責務外。正しい範囲限定）。
- **AC-4: PASS（JVM test。guard wiringはcode review）**。`mergeMissingPopupOptions` はDEFAULT_ORDER相対の位置挿入の純粋関数で、既存項目のorder・enabledを不変に保つ。spec Scenarioの3世代（#449前のorder両方挿入 / #449後のprepend済み `edit_surface` の直前挿入 / 未保存=等価戻り値）をtestが固定。書込みguardは `restoreMissingPopupOptions` 冒頭の `if (mergedOptions == currentOptions) return` で実装され、現行の無条件writeを廃止している（spec Revision 3の契約どおり）。guard wiring自体はspec Test oracleの明記どおりJVM test対象外で、本auditがcode reviewで確認した。
- **AC-5 / AC-6: UNVERIFIED（実機分）**。emulator証跡（`docs/assessment/452-organizer-home-entry-evidence/` 17点。build `3f6bd67`）は3操作経路・TalkBack起動を示すが、accepted specは両ACとも**実機**を要求する。evidence文書とPR本文はこれをowner確認事項として明示しており、隠蔽はない。spec frontmatterは `accepted` を維持し受入未完了であることもChange historyに明記（Phase 2 review round 2の指摘対応）。
- **AC-8: PASS**。`measure_upstream_patch_surface.py --target 16f9cba0f1 --enforce-baseline` を監査者が再実行 → **exit 0 PASS**。baseline JSON（`docs/assessment/upstream-patch-surface-baseline.json`, captured_on 2026-09-30）の `expected_measurement` は **93 counted files, +20327/-1089** でPR本文記載と一致。新規 `organizer-home-entry` bridge groupは `LauncherPopupPreference.kt` と `ic_organize_home.xml` を持ち、rationaleに「`LauncherOptionsPopup.kt` 自体は #449 の `homeedit-edit-surface` groupへcounted・#452分のgrowthはrationale付きでadopt」と明記（spec AC-8の要求どおり）。`--target 3f6bd67a6f --verify` も **PASS**（baseline再採択commit `4cc1ae8355` は PR head `16f9cba0f1` に含まれるため、headでのenforce-baseline PASSと整合する。REVIEW REQUIRED growthは再採択により解消済み）。
- **Test oracle追従**: JVM testを `app.lawnchair.ui.popup.*` として `organizer-unit-tests` gate filterへ追加（#458と同じroute）。`ci-test-portfolio.md` と `quality-strategy.md` のmirrorを同一PRで更新（planが要求した手順どおり）。test-audit記録がPR本文にある。

## Test surfaces reviewed

- `tests/unit/app/lawnchair/ui/popup/LauncherOptionsPopupOrderTest.kt`（9 test。PR本文は「8 test」とあるが実fileは9 test — `metadata maps...` 追加後の値。表記の軽微な不一致として記録）:
  1. DEFAULT_ORDER構成+organize_home既定ON（AC-1）
  2. `getMetadataForOption("organize_home")` のlabel/icon mapping（AC-1。review round 1指摘3の対応）
  3. #449前のorderへの両項目位置挿入+enabled保持（AC-4）
  4. #449 prepend済み `edit_surface` の直前挿入（AC-4）
  5. 並べ替え済みorderでのDEFAULT_ORDER followers挿入+enabled不変（AC-4）
  6. no-missing等価=書込みguard条件（AC-4）
  7. 未知identifier保持（rollback安全性）
  8. lock中の可視filter（AC-3）
  9. 非lock時のenabled/carousel filter（AC-3）
- spec Test oracleとの対応: AC-1（DEFAULT_ORDER+metadata分岐）/ AC-3（lock filter）/ AC-4（位置挿入の決定性・不変性・guard条件）はいずれもoracleどおりJVM testで固定されている。AC-2はemulator evidence+code reviewで代用（oracleの許容範囲）。
- **本auditがローカルでgradle testを実行していないことの明示**: JVM unit test suite（1852 tests greenとPR本文が主張）は本auditでは未再実行。代わりにCI `organizer-unit-tests` job（run 36735045202）がhead `16f9cba0f1` で **pass** していることを確認した（`app.lawnchair.ui.popup.*` filter込み）。CI上の同一filter実行をlocal再実行の代替とみなす。

## CI runs

- PR run: [36735045202](https://github.com/nunu1733/NunuLauncher/actions/runs/36735045202)（head `16f9cba0f1`、pull_request）。`gh pr checks 486` の結果: **15 job pass / 2 fail**。failは `organizer-instrumentation-reservation-recovery-tests` と `final-status` のみ。`organizer-unit-tests`（新filter込み）・全他instrumentation lane・build/style/validateはpass。
- High-risk gate run: [36735045176](https://github.com/nunu1733/NunuLauncher/actions/runs/36735045176)（head `16f9cba0f1`）は **success**。ただしjob logは「low-risk PR: independent-evidence gate not required (labels=[], no high-risk paths changed)」と出力しており、本PRがlabel/path条件で機械gateの対象外と判定されたことを意味する。本recordは独立auditとして書かれるが、機械gateは本recordを要求していない。label付与運用（`risk:` labelの有無）はowner側の確認事項として記録する。
- 過去run: 36727993594 / 36732170217（review round中のhead）も同一のreservation-recovery失敗、High-risk gate 36727993679 / 36732170051 はsuccess。

## main由来失敗の帰因判断（#487）

- 失敗test: `app.lawnchair.organizer.application.Issue265ManualEditRecoveryInstrumentationTest > pathC_manualEditThenSecondOrganize`、`java.lang.IllegalStateException: second organize did not reach Applied: NoChanges`（`Issue265ManualEditRecoveryInstrumentationTest.kt:157`）。
- **main単体run [36715379223](https://github.com/nunu1733/NunuLauncher/actions/runs/36715379223)（`56624406fd` = 本PRのbase、#451 merge直後）で同一lane・同一test・同一message・同一行番号で失敗することを監査者がjob log（job 109900594644）で直接確認した。**
- **PR run（job 109962597654）でも同一test・同一message・同一行番号で失敗することを確認した。**
- 本PRのdiffはorganizer application/planning/recovery経路に触れない（AC-7確認どおり）。`NoChanges` は2回目のorganizeがAppliedに到達しない問題であり、popup項目追加・preference補完・resource追加と因果経路がない。
- 調査Issue [#487](https://github.com/nunu1733/NunuLauncher/issues/487)（OPEN、2026-09-30作成）が存在し、main `56624406fd` run・PR 3 runでの再現・#451（PR #485）merge後初発の仮説を記録している。
- **帰因判断: 本PRのdiffに帰因する失敗は0件と判断する。** `final-status` の赤はmain自体が既に赤であることの反映である。merge可否（赤のままmergeするか）は #476 追跡監査と同じくowner判断事項であり、本auditは判断を代行しない。

## Known limitations

1. **AC-5/AC-6の実機確認が未完了**（owner確認事項。emulator証跡は補助）。spec受入（Exit criteria）はこの完了まで閉じない。spec/PR本文/evidence文書の3箇所で一貫して明示されている。
2. **`final-status` が赤**（上記のとおりmain由来 #487）。機械要件「検証対象commit上でfinal-statusが成功」は、ownerが例外を認めない限り成立しない。
3. **PR本文の「8 test」表記**は実fileの9 testと不一致（軽微。review round 1対応でmetadata test追加後の更新漏れ）。
4. **High-risk gateが本PRをlow-risk判定**（labels空・high-risk path無し）で機械監査を要求していない。階層Mの軽量specでは正当だが、`risk:` label運用の前提はowner確認の余地がある。
5. #449の `edit_surface` がpopup編集画面のlock分岐に無い現状は本PRでは不変（spec/planどおり #449残課題。本PRの欠陥ではない）。

## Unverified areas（本auditが実施しなかったもの）

- JVM unit test suiteのローカル再実行（CIの同一filter実行で代替。前述）。
- instrumentation testのローカル実行（emulator未使用。CI lane結果とjob logで確認）。
- 実機（物理デバイス）でのAC-5/AC-6確認（owner確認事項。本auditは代替できない）。
- emulator PNGの画素内容の精査（17点の存在とgit追跡を確認。各画像の視覚内容の妥当性判断はPR reviewコメント群とevidence文書の記述に依存した）。
- `#487` の技術的原因の調査（本auditの範囲外。帰因判断に必要な範囲のみ確認）。

## Verdict

**HOLD（条件付きGO）**。内容面（実装・test oracle・patch surface再採択・CI attribution）はaccepted spec Revision 3と整合し、本auditで新たなblocking findingは確認しなかった。mergeは次の2点の解消後に行うこと:

1. AC-5（実機3操作記録）とAC-6（実機TalkBack/Switch Access確認）のowner実施と記録（spec受入完了の前提）。
2. `final-status` の扱い: #487がmain由来であることのowner判断（例外承認または#487解消後のgreen run）。green runが存在しない限り、現行の高リスク独立エビデンス要件を機械的に満たす状態にはならない（本PRは機械gate上low-risk判定のため即座には阻害しないが、owner判断の記録を要する）。

コード変更を伴う対応（実機証跡の再取得を除く）が発生した場合は再auditを要する。
