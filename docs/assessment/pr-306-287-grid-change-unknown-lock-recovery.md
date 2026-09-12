# High-risk audit: PR #306 keep folder children reviewable beyond one-page folder capacity (issue #287)

> Status: accepted
> Audit date: 2026-09-13

- Auditor: general-purpose subagent（独立session、実装sessionと別context。本監査では実装・commit・pushを行っていない）
- PR: https://github.com/nunu1733/NunuLauncher/pull/306
- Head SHA: 8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34708809439
- Criteria: specs/287-grid-change-unknown-lock-recovery/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7; docs/adr/0004-organizer-lock-persistence.md ADR-0004

## Scope

監査対象diffは base `f9afd8bfde121932c0c8ed965225d52a84d86ab4` (main) から
head `8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b` までの9ファイル
（+1216 / −2）。`git diff --name-only` で列挙した結果:

- production（1ファイルのみ）:
  `lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt` —
  `LockAuthoringDecision.fitsProfile` の `PlacementState.FolderChild` 分岐のみ。
  `rank < caps.folderMaxColumns * caps.folderMaxRows`（フォルダ1ページ分のcell数）
  の上限を削除し、`rank >= 0` の検証は保持。他の分岐（Workspace cell / Dock rank /
  AppPairChild）、kind・container actionability判定、profile判定、
  `evaluateChange` / `evaluateReviewBatch` の構造は不変。
- tests（5ファイル）:
  - `tests/unit/.../locks/LockAuthoringDecisionTest.kt`（新規）— 容量境界
    （rank 15/16/17/40）single受入、負rank拒否、容量超過メンバーを含む一括
    atomicity、batch既存拒否経路。
  - `tests/unit/.../locks/LockReviewListingTest.kt`（新規）— listing行と
    書込み判定の述語不変（AC-2）と負rank対证。
  - `tests/unit/.../locks/LockAuthoringModuleProtocolTest.kt`（+1 test）—
    容量超過メンバーのfake writer経由単一列plan。
  - `tests/organizer-instrumentation/.../LockAuthoringInstrumentationTest.kt`
    （+2 tests）— 実DB + 実`GridSizeMigrationUtil.markOrganizerLocksUnknown`
    後のreview完結（AC-3a）、移行前capture由来planの`STALE_REVISION`拒否と
    無変更assert（AC-3b）。
  - `tests/organizer-instrumentation/.../GridChangeUnknownLockRecoveryInstrumentationTest.kt`
    （新規、407行）— 同一process chain: organizer適用 → recovery復元（事前状態と
    完全一致assert）→ production経路のgrid変更（db file切替・revision変化・
    全行UNKNOWN化をassert）→ `InputUnavailable(CAPTURE_UNKNOWN_LOCK)` →
    listing確認（容量超過メンバー包含assert）→ 一括review → recapture
    UNKNOWN 0 → 同一processでpreview再到達（AC-4）。
- docs / spec（3ファイル）:
  `specs/287-grid-change-unknown-lock-recovery/spec.md`（status: accepted）、
  同`plan.md`（rev 3）、
  `docs/assessment/issue-287-grid-change-unknown-lock-recovery.md`（AC-6証跡）。

確認したruntime書き込み経路: diffは`LockAuthoringDecision`の判定のみを変更し、
`LockStateDbAdapter` / `LayoutWriteCoordinator` / `ModelWriter` /
`GridSizeMigrationUtil` / recovery store / journalには触れない
（diffファイル一覧とproduction diff本文で確認）。migration対象・schema変更・
permission・network追加はnone。監査はcommit内容に対して行い、作業treeの
未追跡ファイル（`.ux-review/`等）は対象外。

## Criteria check

- **AC-1（フォルダ子のreview可能性）: 確認。**
  `LockAuthoringDecisionTest`（新規分）が、fixture容量4×4=16の境界
  （rank 15/16/17/40）で全`LockTargetState`のsingle受入、負rank
  （`-1`）の`PLACEMENT_OUT_OF_PROFILE`拒否、rank 0/16/40を含む一括reviewの
  atomic plan（writes 3件、全UNLOCKED、expected precondition保持）を固定。
  `LockAuthoringModuleProtocolTest`追加分がfake writer経由で単一列
  （`plan.writes.size == 1`）書込みを固定。production diffが上位制約の削除と
  `rank >= 0`保持のみであることと合致。JVM testは本監査で実行し全pass
  （下記実行記録: LockAuthoringDecisionTest 23 tests / 0 failures他）。
- **AC-2（表示と実効の一致）: 確認。**
  `LockReviewListingTest`の`listed unknown rows meeting the writable
  predicate are accepted by the write decision`が、rank 0..40のフォルダ子41件
  + Dock + desktopをlistingし、全listing行が実際の書込みと同じ
  `LockAuthoringDecision.evaluateChange`で受入されることを検証。
  対证`listed rows outside the writable predicate stay fail-closed on write`
  が負rank行の拒否維持（fail-closed不変）を検証。JVM testは本監査で実行し全pass
  （LockReviewListingTest 7 tests / 0 failures）。
- **AC-3（実DB reviewと旧世代拒否）: 確認（diff読み + CI実行）。**
  `LockAuthoringInstrumentationTest`追加分(a)は実DBに40メンバーのfolderをseedし、
  実`GridSizeMigrationUtil.markOrganizerLocksUnknown(db)`後に最上位rank
  メンバーのsingle review成功（DB列値2を直接assert）と、listing全体のbatch
  review（writes == listing件数）、recaptureのUNKNOWN 0を検証。(b)
  `preMigrationPlanRejectsAsStaleAfterMigrationMarking`は移行前capture由来planが
  marking後に`LockWriteOutcome.Rejected(STALE_REVISION)`で拒否され、
  行がUNKNOWN(0)のまま無変更であることを検証。本監査sessionではemulator
  実行を省略したため、実行証拠はCI run 34708809439のinstrumentation lanes
  （すべてsuccess）による。
- **AC-4（同一process自動回帰）: 確認（diff読み + CI実行）。**
  `GridChangeUnknownLockRecoveryInstrumentationTest.organizerRecoversThroughReviewAfterInProcessGridChange`
  （単一`@Test`）が、spec scenarioの全段階を同一processで実行し各状態をassert:
  preview到達 → 適用成功 → recovery復元（事前layoutStateと完全一致）→
  production経路grid変更（`launcher_5_5_5.db`へのfile切替assert、revision変化
  assert、全行UNKNOWN assert、容量超過メンバー存在assert）→
  `InputUnavailable(InvalidCanonicalCapture(CAPTURE_UNKNOWN_LOCK))` →
  listingへの容量超過メンバー包含assert → 一括review（writes == listing件数）→
  listing空・recapture UNKNOWN 0 → 同一processでrun 3がpreview到達。
  実DB・実loader・実migration（`tryMigrateDB`経由）・実composer・実writerを
  使用。実行証拠はCI instrumentation lanes（success）および既存証跡
  `docs/assessment/issue-287-grid-change-unknown-lock-recovery.md`の
  OrganizerDiag journal転記（`INPUT_NOT_READY err=...CAPTURE_UNKNOWN_LOCK` →
  回復後`PREVIEWED`）。
- **AC-5（fail-closed不変）: 確認。**
  diff内にcomposer / readiness / 既存lock系test fileの改変はなく、lock系
  追加のみ。既存の`batch review still rejects atomically when any member is
  out of profile`（負rank混入時の全体拒否）、`unknown kind rows never enter
  review decisions`等が無変更で残存することをdiffで確認し、本監査のJVM実行
  （52 tests / 0 failures、既存class 4件全体）でも無変更通過を確認。composer
  側gate（`app.lawnchair.organizer.*`全体）はCI `organizer-unit-tests` jobが
  対象commit上で最終success。
- **AC-6（再現フローの端末検証）: 確認（既存証跡）。**
  `docs/assessment/issue-287-grid-change-unknown-lock-recovery.md`が、
  `nunu_qpr2_api36_1` AVDでの同一process chain実行、OrganizerDiag journal
  （run idは不化して`RUN_STARTED`→`APPLY_VERIFIED`→`RECOVERY_RESTORED`→
  `INPUT_NOT_READY CAPTURE_UNKNOWN_LOCK`→回復後`PREVIEWED`）と
  connected test実数（9 tests / 0 failures、既存annotation skip 1件）を記録。
  本監査sessionでのemulator再実行は省略し、この記録とCI lanesを代替証拠とする。
- **AC-7（gate）: 確認。**
  `./gradlew spotlessCheck`（本監査でexit 0）、
  `assembleLawnWithQuickstepGithubDebug`（CI `build-debug-apk` success）、
  organizer unit-test gate（CI `organizer-unit-tests` success。run_attempt 2;
  初回failureの経緯はFindingsに記載）。本監査ではJVM lock系subset
  （52 tests）と`python3 tools/repo-contract/validate_repo_contract.py`
  （OK）を追加実行。

ADR-0004との整合: production diffは`favorites.organizerLockState`のencoding、
migration（`markOrganizerLocksUnknown`）、writerのrevision/precondition機構に
触れず、ADR-0004が定める「UNKNOWN化 + review再確認」という回復経路を
`fitsProfile`の判定誤りを除して機能させる変更である。spec #38の一括review
atomicity・exact precondition、spec #83 / #172のcomposer fail-closed意味論は
production diff内のいずれの箇所も変更しておらず、対応する回帰test
（batch全体拒否、`STALE_REVISION`拒否、`CAPTURE_UNKNOWN_LOCK`発生assert）が
維持されている。

## Executed test surface

本監査sessionで実行したcommandと結果（checkout:
`issue-287-grid-change-unknown-lock-recovery` @ `8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b`）:

| Command | 結果 |
|---|---|
| `git rev-parse HEAD` | `8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b`（PR headと一致） |
| `git diff --name-only f9afd8bfde121932c0c8ed965225d52a84d86ab4..8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b` | 9ファイル（Scopeの列挙どおり） |
| `git diff f9afd8bfde121932c0c8ed965225d52a84d86ab4..8cd7240e6be5eeaf9ef4afc0f39b55b82388e96b -- lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt` | `fitsProfile`フォルダ子分岐のみのproduction変更を確認 |
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.locks.*'` | BUILD SUCCESSFUL（22s、exit 0）。JUnit XML集計: 4 classes / **52 tests / 0 failures / 0 errors / 0 skipped**（LockAuthoringDecisionTest 23、LockAuthoringModuleProtocolTest 12、LockReviewListingTest 7、EffectiveLockEffectsTest 10） |
| `./gradlew spotlessCheck` | BUILD SUCCESSFUL（exit 0） |
| `python3 tools/repo-contract/validate_repo_contract.py` | `repository contract OK (/Users/nunu/Documents/work2/NunuLauncher)` |
| `gh pr view 306 -R nunu1733/NunuLauncher --json ...` | head `8cd7240e6b...`、labels `bug` / `risk: layout-data`、base `main`、state OPEN |
| `gh api repos/nunu1733/NunuLauncher/actions/runs/34708809439 --jq ...` | `CI` workflow、event `pull_request`、head SHA `8cd7240e6b...`（監査対象と一致）、head branch一致、status `completed` / conclusion `success` |
| `gh api repos/nunu1733/NunuLauncher/actions/runs/34708809439/jobs?per_page=100 --jq ...` | 13 jobsすべてsuccess: `final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、`validate-repo-contract`、`changes`、`organizer-instrumentation-*` 7 lanes。`organizer-unit-tests`は`run_attempt: 2`で、attempt 1の`conclusion: failure`を別途APIで確認（Findings参照） |

本監査sessionで未実行とし、CI run 34708809439の該当jobで確認したもの:
`assembleLawnWithQuickstepGithubDebug`（`build-debug-apk` success）、
organizer unit gate全体 `app.lawnchair.organizer.*`（`organizer-unit-tests`
success）、instrumentation test群
（`LockAuthoringInstrumentationTest` /
`GridChangeUnknownLockRecoveryInstrumentationTest`等、
`organizer-instrumentation-*` lanes success）、AC-6のemulator chain再実行
（既存assessment記録とCI lanesを代替証拠とする）。

## Findings

確認できたこと:

- production変更は`LockAuthoringDecision.fitsProfile`のフォルダ子分岐1か所
  のみで、spec / plan / ADR-0004が宣言する範囲と正確に一致する。writer、
  migration、composer、planner、recovery store、journal、schemaには触れない。
  削除された上限が表示pagination定数（`FolderPagedView`のpage分割）であり、
  loaderが永続化rankをそのままloadするという根拠は、spec Problem節の
  実機観測（unknown 125件中47件のみ解消、残り78件がフォルダ内項目で恒常拒否）
  および容量境界・負rankの回帰testと整合する。
- 高リスクgateの必須要件（CI merge gate成功 + 独立監査記録）を満たすために
  必要な機械検証可能な事実（head SHA一致、`pull_request` event、
  `final-status` success、source jobs実行済みsuccess）をGitHub APIで独立確認
  した。
- Blocker / Major問題は見つかっていない。

確認できなかったこと・残課題:

- **CI初回`organizer-unit-tests`失敗の詳細:** GitHub APIでattempt 1
  `failure` → attempt 2 `success`（最終conclusion success）は確認したが、
  attempt 1のlogは取得できず、初回failureが
  `ReadinessGateTest.applyArrivingDuringReconciliationWaitsThenRunsAfterReady`
  のタイミングフレークであったという実装sessionの報告内容自体は本監査では
  独立検証できていない。jobの最終conclusionがsuccessである点がgate要件であり、
  これ(API検証済み)は満たす。
- **emulatorでの手動UI操作・instrumentation再実行は本監査未実施。**
  代替証拠: CI instrumentation lanes（7 lanes success、対象commit上）、
  同一process chain testのコード審査、AC-6既存assessment記録
  （journal転記 + connected 9 tests / 0 failures）。work profile等の
  multi-user環境は本修正の対象外（profile判定不変）であり未検証のまま
  （既存assessmentも同じ限界を記録）。
- **merge後のdocs後続PRが必要:** audited head時点でspec statusは`accepted`、
  AC-7 checkboxは未チェックのままである。spec Change historyは「AC-7はCI
  `final-status`の確認後に完了」としており、`final-status` successは確認済み
  のため、status `implemented`への更新とcheckbox更新をdocs後続PRで行うのが
  整合的な締め処理である（本監査は`docs/assessment/`への記録作成のみで、
  spec/plan/issue状態は変更していない）。
- CI run内のinstrumentation laneはAPI応答で7本（`organizer-instrumentation-*`）
  を確認した。handoff情報では「8 instrumentation lanes」と記載されていたが、
  本監査ではAPI応答の13 jobs（全success）を正として記録する。lane数の齟齬は
  gate判定に影響しない（必須source jobsと`final-status`はすべてsuccess）。
