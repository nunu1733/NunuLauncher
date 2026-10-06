# High-risk audit: PR #541 targetSdk 37 BAL granular opt-in移行

> Status: accepted
> Audit date: 2026-10-06

- Auditor: 独立session（ZCode audit agent。実装sessionとは別の作業として実施。solo保守の独立session再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/541
- Head SHA: 4d61ba06d53d6a0835ddacc87f1eaa1008bff999
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/37470516348
- Criteria: docs/adr/0018-lawnchair-16-rebase.md ADR-0018

## Scope

対象diff（base `e8aced7dbe` = `issue-532-phase2-restart` pinned head → head `4d61ba06d5`）は、source 6 file（計 +38/−29）と証跡docs `docs/assessment/527-bal-granular-evidence/`（28 file: README + screenshot 26枚 + `api37-restart-pi-block-signature.txt` 48,906行）のみである。それ以外のpathの変更はゼロである。

| file | +/− | 変更内容 |
|---|---|---|
| `src/com/android/launcher3/Utilities.java` | +5/−1 | `allowBGLaunch` を `ATLEAST_BAKLAVA` → `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`（granular・可視sender限定）へ移行。`ATLEAST_U` のlegacy `ALLOWED` 分岐はAPI 34/35 runtime用に残置。#527 comment付き |
| `quickstep/src/com/android/launcher3/uioverrides/QuickstepInteractionHandler.java` | +15/−6 | 主経路と2つのfallback経路（hostView非検出・options取得失敗）をhelper経由のBAL opt-inへ。widget提供options bundleの保持を維持したsilent gap修正 |
| `quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt` | +4/−12 | private space market/settings IntentSender 2 siteをhelper経由へ |
| `lawnchair/src/app/lawnchair/qsb/providers/Google.kt` | +2/−5 | QSB search/voice（`startIntentSender`）をhelper経由へ |
| `lawnchair/src/app/lawnchair/smartspace/BcSmartSpaceUtil.kt` | +2/−5 | smartspace card tapをhelper経由へ |
| `lawnchair/src/app/lawnchair/LawnchairApp.kt` | +10/−0 | `BuildConfig.DEBUG && Utilities.ATLEAST_BAKLAVA` gateの `StrictMode.setVmPolicy(detectBlockedBackgroundActivityLaunch().penaltyLog())` を `onCreate` 冒頭に追加。logのみでprocessを殺さない |

full diff（`git diff e8aced7dbe..HEAD -- <6 files>`）を全行読み、5つの非Application fileはすべてActivityOptionsのBAL mode定数選択のversion gate付け替えのみで、PendingIntent作成・起動先・結果処理の変更がないことを確認した。`LawnchairApp.kt` はdebug build限定・log-onlyのStrictMode policy追加のみである。

high-risk分類の根拠は `tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_FILES`（Issue #44 writer inventory）に `lawnchair/src/app/lawnchair/LawnchairApp.kt` が含まれること（path-list membership）のみである。auditの結論として、production書込み経路（Launcher DB write、schema migration、recovery store、model/loader bridge）への追加・変更はなく、PI作成契約・起動先・結果処理・DB pathは不変である。これは受入済みspec（`specs/527-target37-bal-granular/spec-lite.md`、PR #540でaccepted）のScope/Non-goalsと一致する。

emulator検証証跡は `docs/assessment/527-bal-granular-evidence/`（API 37必須／API 36／API 35 legacy分岐のmatrix。README + screenshot + logcat full capture）。証跡実施headは `60d65ed789` であり、これはaudit対象head `4d61ba06d5` のancestorで、両者間の差分は当該証跡docs commitのみ（source 6 fileの `git diff --stat 60d65ed789..HEAD` は空 = コード同一）であるため、証跡はaudit対象コードにそのまま対応する。

## Criteria check

- ADR-0018（accepted revision 7）の適用関係: 本PRはADR-0018 Decision 1固定のupstream `43a21b43` anchor系譜に属するPhase 2 baseline `e8aced7dbe` から分岐している。Decision 3の移行期間規則（`com.android.launcher3` / SystemUI等への新規bridge追加は原則停止）に従い、上流file（`Utilities.java`、`QuickstepInteractionHandler.java`、`SystemApiWrapper.kt`）への変更は定数選択とversion gateへの最小bridge編集に限定され、全siteにIssue番号付きcomment（`NunuLauncher #527: ...`）が近傍に残されている。`com.android.launcher3` 側2 fileへの変更も例外を設けない最小差分である。
- ADR-0018 Decision 3/5（branch方針とrollback点）: 本PRはPhase 4 cutoverまでmergeしない取り扱いであり（PR本文に明記。review・検証記録の正本として保持する）、Decision 3の「切替はrebase branchの全検証完了後、単一のmergeで行う」に従う。Decision 5のrollback点（cutover merge直前の旧main headのtag固定、force-reset不使用）に影響する変更は含まない。audit後はdocs-only commitのみを許容し、code変更時は本headでの再auditを要求する。
- 受入済みspecのVerification義務の履行: emulator matrix（API 37必須／API 36／API 35）が実施され、起動結果・logcat BAL blocked filter・debug StrictMode logの観測が証跡dirに記録されている。自己再起動PIはspec G5の契約どおり新規grantを追加せず、block実測時のfailure signatureをlogcat full captureとして1件のみ記録し、後続の最小mode判断へ渡している。書込み経路を追加しないことの確認（spec Verification最終項）も本auditのdiff全行読みにより再確認した。specが要求する既存test suite gate（organizer unit / instrumentation）はCI runの当該jobでgreenを確認した。

## Executed test surface

本audit sessionがhead `4d61ba06d53d6a0835ddacc87f1eaa1008bff999`（branch `issue-527-bal-granular`、working tree cleanを事前確認）で実行したcommandと結果:

- `git rev-parse HEAD` → `4d61ba06d53d6a0835ddacc87f1eaa1008bff999`。GitHub上のPR #541 head SHAと一致、base = `issue-532-phase2-restart` をAPIで確認。
- `git status` → `nothing to commit, working tree clean`。
- `git diff --stat e8aced7dbe..HEAD` → source 6 file（numstat合計 +38/−29）+ `docs/assessment/527-bal-granular-evidence/` 28 fileのみ。対象外pathの変更ゼロを確認。
- `git diff e8aced7dbe..HEAD -- <6 source files>` → 全行読み。Scope表のとおりBAL mode定数選択とdebug StrictModeのみ。
- `git log --oneline 60d65ed789..HEAD` → 証跡commit 1件のみ（`docs/assessment/527-bal-granular-evidence/` 配下のみのdocs-only差分）。
- `./gradlew spotlessCheck` → `BUILD SUCCESSFUL`（4 actionable tasks: 4 up-to-date、warm cache）。
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 541 --head-sha 4d61ba06d53d6a0835ddacc87f1eaa1008bff999`（本audit記録の作成前。pre-audit state）→ exit 1、`high-risk PR (high-risk path change(s): lawnchair/src/app/lawnchair/LawnchairApp.kt): independent evidence required` / `FAIL: no docs/assessment/pr-541-<slug>.md audit record for this PR`。本記録の作成動機となるgate作動の直接確認である。
- CI merge gate検証（`gh api repos/nunu1733/NunuLauncher/actions/runs/37470516348`、同 `.../jobs`、`.../attempts/3/jobs`、`.../artifacts`）: run 37470516348（event = `pull_request`、head_branch = `issue-527-bal-granular`、head_sha = `4d61ba06d5...`、GitHubのPR関連付け = [541]、path = `.github/workflows/ci.yml`）は現在attempt 4でcompleted / conclusion = success。attempt 4の全16 job（`final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、organizer instrumentation各lane）がsuccess・実行済み（skipなし）で、validatorの必須source job要件を満たす。
- attempt 1〜3の失敗の独立検証: attempt 1（13:40Z）・attempt 2（14:20Z）の `organizer-instrumentation-onboarding-proposal-tests` 失敗は、rerun前のPR comment https://github.com/nunu1733/NunuLauncher/pull/541#issuecomment-6019329604 （2026-10-06T15:13:56Z）が `docs/engineering/quality-strategy.md` のcategory 5（emulator / runner / platform environment defect）として分類・記録済み。本auditはattempt 3（14:46Z失敗、job id 112323712679）のtest report artifact（id 11421626593）を取得して検証し、同一test `OnboardingOrganizationProposalInstrumentationTest#reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome` のみが同一signature（`IllegalStateException: input environment prevented the launcher from resuming; evidence=launcher-resume-timeout; frontmostPackage=com.google.android.apps.nexuslauncher`）で20 test中1件失敗したことを確認した（category 5と同一機序）。
- 実装sessionのlocal再現記録（本auditは記録の裏付け確認のみ。emulatorの再実行は行っていない）: 同一head APKでAPI 36 emulator `issue142_api36` において当該classが20/20 × 2回連続pass。

## Findings

- (a) API 37での自己再起動PI BAL block（spec G5が予見した条件の実測）。failure signatureは `docs/assessment/527-bal-granular-evidence/api37-restart-pi-block-signature.txt` にlogcat full captureとして保存済み（要点: `balAllowedByPiSender: BSP.NONE; resultIfPiSenderAllowsBal: BAL_BLOCK; resultIfPiCreatorAllowsBal: BAL_ALLOW_ALLOWLISTED_COMPONENT`、`realCallingPackage: android.uid.system:1000`）。ユーザー影響なし（再起動自体はHOME消失によるsystem復帰で成功）。accepted spec G5の契約どおり本issueでは新規grantを追加せず、creator側の最小opt-inは本signatureを根拠とする後続判断へ分離済みである。
- (b) onboarding-proposal laneの間欠失敗: 同一runのattempt 1〜3が同一test・同一signatureで失敗し、attempt 4でgreen。attempt 1〜2分はrerun前のPR comment（上記link）がcategory 5として分類記録済みであり、本auditのattempt 3 artifact検証によりattempt 3も同一機序（ENVIRONMENT_ANOMALY: launcher-resume-timeout、foreign frontmost = nexuslauncher）であることを独立に確認した。コード起因を示す証拠はなく（local 20/20 × 2、他job全pass、同一日に他headでもemulator laneの別失敗）、category 5分類を支持する。PR commentの「2回連続failure」記載はattempt 3の結果を網羅していないため、その補完を本auditが行った。
- (c) `tools/repo-contract/measure_upstream_patch_surface.py` のbaseline JSONがv15期（`505dbc4` / `8af117b6`）のものでPhase 2 lineage（upstream `43a21b43`）のancestor検査に失敗し、baseline HEAD無変更でも同一失敗する（pre-existing。PR本文に記録済み）。spec Verificationの代替として、本diffの上流file touchは `Utilities.java` / `QuickstepInteractionHandler.java` / `SystemApiWrapper.kt` の3 fileのみでspecの「上流3 fileへの最小変更」と一致することをdiff全行読みで確認した。baseline JSONの更新は別判断として残る。
- (d) 明示: 本PRは高リスク振る舞い（Launcher DB書込み、schema migration、recovery storeの書込み・復旧）に一切触れていない。high-risk分類は `LawnchairApp.kt` のpath-list membershipのみに基づき、実際のdiffはdebug・log-onlyのStrictMode policyである。したがってホームレイアウト安全規約（snapshot revision照合・recovery point・相対reload等）の適用対象となるlayout書込みは含まない。
- (e) 補足: PR本文のhead SHA記載（`60d65ed789`）は証跡実施時点のheadである。証跡head以降の差分は証跡docs commit（`4d61ba06d5`）のみでsource変更はないため、audit対象headとの整合は損なわれていない。PR本文のhead SHA更新はdocs-onlyで可能だが、機械要件ではない。
