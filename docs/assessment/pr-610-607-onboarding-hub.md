# Independent audit: PR #610 onboarding organizer hub routing (spec 607, tier M)

> Status: proposed（audit記録。merge gate「CI final-status」が本記録時点で未達のためaccepted判定は行わない）
> Audit date: 2026-10-17

- Auditor: 独立session（solo保守）。実装を行ったsessionとは別の、独立したsubagent sessionが本auditを実施した。diff・spec・testの検証は、本sessionでのread-only読み取りにより直接実施した。emulator実行の再実行は行っていない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/610（base `main`、head branch `issue-607-onboarding-organizer-hub`、state OPEN、`MERGEABLE`）
- Head SHA（監査対象）: `4c4e308e8f2a383e5d2c465f74944956a76426b2`（`gh pr view --json headRefOid` で確認。本地の `main..4c4e308` merge-baseは `68c6a406b562d22d573e2e6eb4d606cd57587efd`）
- 実装packet記載の実装base: `4152b5e56d69b283e7768672f17aae76b56f7aac`（spec受入後のcanonical amendment head。本auditのdiff範囲検証は両者で一致することをfile列挙で確認した: base `68c6a40`基準でも20 files / +979 −403、packet記載の13 files / +437 −323はspec/docs更新を除いたsource/test部分に相当）
- Criteria: `specs/607-onboarding-organizer-hub/spec.md` @ accepted commit `6299eba07bec67abc5d178a5ec2466a13f9ca90e`（`git show --stat` でfrontmatter `status: accepted` のみの1-file commitであることを確認。tier M）
- Phase 2 ChatGPT review: **Approve, blocking 0** — https://github.com/nunu1733/NunuLauncher/issues/607#issuecomment-6238027129（旧direct-admission seam除去・one-shot guard維持・singleTop opt-in限定・`REVIEWED` 記録のrun面開始行集約・route recreation契約を確認のうえApprove。実機owner確認・CI final-status・独立auditをmerge gateとして免除しない旨を明記）

## 1. Diff許可path列挙（spec Verification節の適合）

`gh pr diff 610 -R nunu1733/NunuLauncher` のdiff全文（20 files）を本sessionで列挙した。spec md:323-329の許可pathと照合:

| path | spec許可path | 合否 |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | onboarding提案の遷移先 / outcome controller（organizer/ui配下の既存範囲内refactor） | ✓ |
| `lawnchair/src/app/lawnchair/ui/preferences/components/NavigationActionPreference.kt` | navigation構成（hub開始CTAの重複navigation防止の最小差分） | ✓ |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | run UIの開始呼び出しwiring（明示許可path） | ✓ |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` / `HomeScreenPreferences.kt` | navigation構成 | ✓ |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` / `PreferenceRoutes.kt` | navigation構成（typed route化） | ✓ |
| `docs/product/organization-run-ux.md` / `organizer-to-be-ux.md`、`specs/607…/53/370/232/369` | specs/docs更新（必須となる正本更新） | ✓ |
| `tests/unit/…/OrganizationOnboardingProposalTest.kt`、`tests/organizer-instrumentation/…/{OnboardingOrganizationProposal, OrganizerHubPreferences, OrganizerHubDragGuard, OrganizerRestoreColdProcessEvidence, StrategyT05ProductionNavigation}Test.kt` | navigation test / oracle更新 | ✓ |

- **`src/`（Launcher3/AOSP bridge）への差分: 0行**（diff列挙で確認。`src/com/android/launcher3/model/**` も含まれない）。
- DB schema / migration path / 新規書込み経路: なし。`LauncherProposalStore` の `Context` 受け一般化は既存proposal store（LauncherPrefs）の既存範囲内変更であり、DB書込みではない。specの「書込み経路を追加しないことの確認」と適合。
- navigation構成変更のみでpopup項目追加等を含まないため、upstream patch surface計測不要（spec Verificationの免除条件に該当）。

## 2. AC-1〜AC-7 と Test oracle の対応

参照した正本: spec 607 @ `6299eba0`（受入条件は spec md:266-304、Test oracleは md:306-314）。test実装をコード上で確認:

| AC | oracle / 実装の確認結果 | 判定 |
|---|---|---|
| AC-1 | instrumentation `confirmOpensHubOnceAndHubBackOrUpLeavesDeferredWithoutStarting`（本sessionで実装読取。「確認」tap→hub label表示、stale activation 2回でも `monitor.hits == 1`（二重activity起動なし）、Back/Up→launcher、`assertDeferredWithoutRun`（`DEFERRED` + journal 0件）） | ✓ 装置確認済み |
| AC-3 | `OrganizationOnboardingProposal.kt` の `beginReview()` からadmission経路が除去され `controller.defer()` → hub起動のみ。旧`admitReview` seam自体を削除（production diffで確認）。unit `confirmOpensHubOnceAndHubBackOrUpLeavesDeferredWithoutStarting` 相当のjournal 0件assert（diagnostics snapshot empty）。旧guard `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` は残っておらず、**test名・fixture責務・obsolete理由（コメント行: `// #607 supersedes proposal-level Busy/retry: Confirm is navigation, not admission.`）ごと置換済み** | ✓ |
| AC-4 | `realTouchConfirmThenHubThenRunStartPersistsReviewedForTheActualOnboardingRun`: hub→T-07前置き（`manual_organization_preamble_scope`・face trace `PREAMBLE`）→戻って再→run面開始→`waitUntil(15_000)` で `RUN_STARTED` 後に `REVIEWED` がLauncherPrefsへ、`RUN_STARTED.trigger == ONBOARDING_PROPOSAL` をassert。**repeated-tap back-stack oracle**: `onboardingHubMaterialRoundTripAndRepeatedTouchKeepOneRunEntryWithoutAdmission` が `mainClock.autoAdvance = false` 下で同一hub CTAへ40ms間隔のreal touch二重activateを行い、`HomeScreenManualOrganization` のback-stack entryが1件・entry=`ONBOARDING`・`runner.state == Idle`（admission 0）を決定的に確認。production側は `startRun()` 7箇所を1 wrapperに集約し `ManualOrganizationPreferences.kt:359` で `recordOnboardingReviewedOnStart` 呼出し（本sessionで確認）。`NavigationActionPreference.kt:37-44` の `singleTop` はopt-in（既定false）でhub CTAのみ有効化 | ✓ 装置確認済み |
| AC-5 | 上記 `realTouchConfirmThenHubThenRunStart…` 内のpreamble Back→hub、`confirmOpensHubOnce…` のhub Back/Up→launcher、`assertDeferredWithoutRun` 反復、activity recreation後の自動admissionなし（`beforeRecreation.recreate()` 後 `DEFERRED` 維持）。unit defer/skip回帰群（`unseenFreshInstall…` / `deferredProposalIsSuppressedOnlyForTheCurrentProcess` / `skippedAndReviewedOutcomesNeverAutoResurface` / `busyAndRefusedOnboardingStartsPreserveDeferred`）の読取 | ✓ 装置確認済み |
| AC-2 / AC-6 | instrumentation 材料往復（strategy行→back→hub route回復・entry ONBOARDING不変）、`OrganizerHubPreferencesInstrumentationTest` 33/33（packet記録、差分は型調整のみ）、settings側のdefault `MANUAL` 不変 | ✓ 装置確認済み（実機screenshotは未達、§4参照） |
| AC-7 | spec 53（AC-003/§4.1/§8）・spec 370（Outcome/Scope/OCB-AC-01/Busy scenario/retry Given/Test oracle）・TO-BE（D-16/T-19/§6.1）がspec 607「必須となる正本更新」候補としてdiffに含まれる。旧direct-admission名testは新名へ置換され**unit側も旧`controller.review(admit)` fixtureを廃止**（`busyAndRefusedOnboardingStartsPreserveDeferred` / `startedOnboardingStartRecordsReviewedOnlyForTheOnboardingTrigger` へ移設、`Trigger.ONBOARDING_PROPOSAL` でないtriggerでは `REVIEWED` を記録しないことをassert） | ✓ diff列挙でnormative更新対象が同梱、obsolete理由はtest内コメントとPR本文に記録 |

unit test（8 tests / `OrganizationOnboardingProposalTest.kt`）とinstrumentation群のoracle契約（test名・fixture責務・obsolete理由の同一変更更新、repeated-tap back-stack、`Started` 後の`REVIEWED`記録、trigger assert、proposal側one-shot guard維持の`reviewButton.isEnabled` assert）はoracle節の要求どおりである。

## 3. packet検証結果の引用と既存問題の独立性確認

実装packet（https://github.com/nunu1733/NunuLauncher/issues/607#issuecomment-6237888647）に記録された検証（すべてhead `4c4e308e8f`）を引用する（本auditでgradle/emulatorは再実行していない。PR本文の検証表と同一内容で一致照合済み）:

- `./gradlew spotlessCheck` → PASS
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → PASS（organizer package全体、`OrganizationOnboardingProposalTest` 8 tests含む）
- `./gradlew assembleLawnWithQuickstepGithubDebug` / `...AndroidTest` → PASS
- `OnboardingOrganizationProposalInstrumentationTest`（API 36 AVD `issue607_qa_api36`）→ **20/20 PASS**
- `OrganizerHubPreferencesInstrumentationTest` → **33/33 PASS**（AC-6既存回帰）
- `OrganizerHubDragGuardInstrumentationTest` / `StrategyT05ProductionNavigationTest` → PASS
- `ManualOrganizationPreferencesInstrumentationTest` → **53/56 green**。残3テスト（`largeChangeGroupsTruncateBehindExpandAction` / `previewDetailsRenderConcreteChangeListMatchingPreviewCounts` / `changeRowsArePlainReadableNodesWithoutLiveRegion`）はCompose idle待ちの決定的停滞（環境既存問題）
- `OrganizerRestoreColdProcessEvidenceTest` → seed phaseが既存不具合で失敗（#417以降stale。spec 607のoracle対象外）

既存問題が #607 diffと無関係であることを本audit自身でもコード確認した:

- **停滞3テストの実行経路**: `ManualOrganizationPreferencesInstrumentationTest.kt` の `previewDetailsRenderConcreteChangeListMatchingPreviewCounts`（:1252付近）と `largeChangeGroupsTruncateBehindExpandAction`（:1653付近）は、`runner.start()` を直接呼びpreview/diagram描画経路（`ManualOrganizationRun` state machine・coordinator・concrete preview）を行使する。本diffの変更点である `startRun()` wrapper（`ManualOrganizationPreferences.kt:356-359`）・hub CTA・提案遷移を通らない（`grep` で確認。当該fixtureに `startRun()` 呼出しはなく、`runner.start()` 直接呼出しである）。同classの残り53件（hub/開始wrapper経由を含む）が同一buildでgreenであることは停滞環境側の問題を支持する。
- **`OrganizerRestoreColdProcessEvidenceTest` のstale**: 差分は当該fileへの変更がroute型呼出しのcompile-level修正（`startDestination = HomeScreenOrganizer()` の1行。diff行確認済み）のみで、テスト本体は触っていない。テスト本体は `run.confirmSelection` を使う#376時代（PR #403最終更新）のprotocolであり、`run.start()` 後のstateが `Selecting` を前提とするが、state `ScopeConfirmed` は #417（`b22adea292`）で導入されたため#417以降stale。本diffが当該seed失敗の原因ではない。
- 両問題ともimplementation packetでのstash実験（2件の停滞はbaseline再現済み）と一致し、#607によるregressionと判定しないことを本auditも認める。ただしCI lane（`manual-organization-ui-tests` / `onboarding-proposal-tests`）がhead上で同じ問題を出す場合は、packetと同じ要因で説明できることがmerge前のdone条件である。

## 4. 未達merge gate（本記録時点）

本PRのmergeは、以下が揃うまで行わない（packet・ChatGPT review・specとも一致）:

1. **実機でのowner screenshot/録画**（AC-1: 初回→hub表示、AC-2: hub材料の確認・変更、AC-4: hub開始→run面）。spec Verification節（md:318-322）は実機をmerge必須条件として明示し、emulatorは補助のみ。本記録時点で実機evidenceは未記録。
2. **実装head `4c4e308e8f` でのCI `final-status` green**。`gh pr checks 610`（audit実行時点）では `organizer-unit-tests` / `build-debug-apk` / `organizer-instrumentation-onboarding-proposal-tests` / `manual-organization-ui-tests` / `category-override-tests` / `exchange-import-ui-tests` / `method-choice-journey-tests` が **pending**、`db-migration` / `production-input` / `reservation-recovery` / `restore-capture` / `shared-writer` は **skipping**（変更非対象判定。diff列挙と整合）。`final-status` の成功は未確認。
3. 併せてPR上のreviewDecisionは未記録（`reviewDecision: ""`）。Phase 2 ChatGPT reviewはApprove（Comment型）だが、GitHub上のApproveに正しく反映されるか含めて、merge前のPR状態との整合を再確認すること。

## 5. 監査結論

- diff範囲・oracle・wiring・normative更新・obsolete理由の記録はaccepted spec 607の受入条件AC-1〜AC-7と**整合する**。実質的なspec逸脱・DB/migration越境・bridge越境は見つからなかった（`src/` 差分0）。
- environment上の既存問題（図面3テスト停滞、`OrganizerRestoreColdProcessEvidenceTest` seed失敗）は、コード上の実行経路確認により本diffと無関係であることを本auditでも確認した。
- 懸念はmerge gateの未達（§4）にある。mergeは上記3点の完了後に行うこと。本auditはPR番号 `610`・対象head `4c4e308e8f…` に対して発行されるが、headを変えるrebase/force-pushが発生した場合、本記録は対象headとして**無効になる**ため、その時点で再監査またはamendmentが必須である。
