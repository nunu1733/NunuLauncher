# High-risk audit: PR #541 targetSdk 37 BAL granular opt-in移行（revision 2: review round 1対応後の再audit）

> Status: accepted
> Audit date: 2026-10-07

- Auditor: 独立session（ZCode audit agent。実装sessionとは別の作業として実施。solo保守の独立session再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/541
- Head SHA: de913083dabc63cea0a23278cbc95432b8ae37f1
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/37493739107
- Criteria: docs/adr/0018-lawnchair-16-rebase.md ADR-0018

## Scope

対象diff（base `e8aced7dbe` = `issue-532-phase2-restart` pinned head → head `de913083da`）は、source 6 file（計 +39/−29、下表）と証跡docs `docs/assessment/527-bal-granular-evidence/`（29 file: README + screenshot 26枚 + `api37-restart-pi-block-signature.txt` 48,906行 + `api37-strictmode-policy-inherit-verify.txt` 5,891行）とaudit記録 `docs/assessment/pr-541-bal-granular-audit.md` のみである。それ以外のpathの変更はゼロである。

revision履歴: 初回audit（revision 1、2026-10-06）はhead `4d61ba06d5`（CI run 37470516348、attempt 4で全16 job success）をpinした。その後のChatGPT review round 1（blocking 0 / medium 1 / low 1）でのsource変更は `LawnchairApp.kt` の1行（+comment 1行）のみであり、残りはdocs（証跡READMEのwording同期、新規verify txt、本audit記録のrevision 2書き換え）である。

| file | +/− | 変更内容 |
|---|---|---|
| `src/com/android/launcher3/Utilities.java` | +5/−1 | `allowBGLaunch` を `ATLEAST_BAKLAVA` → `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`（granular・可視sender限定）へ移行。`ATLEAST_U` のlegacy `ALLOWED` 分岐はAPI 34/35 runtime用に残置。#527 comment付き |
| `quickstep/src/com/android/launcher3/uioverrides/QuickstepInteractionHandler.java` | +15/−6 | 主経路と2つのfallback経路（hostView非検出・options取得失敗）をhelper経由のBAL opt-inへ。widget提供options bundleの保持を維持したsilent gap修正 |
| `quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt` | +4/−12 | private space market/settings IntentSender 2 siteをhelper経由へ |
| `lawnchair/src/app/lawnchair/qsb/providers/Google.kt` | +2/−5 | QSB search/voice（`startIntentSender`）をhelper経由へ |
| `lawnchair/src/app/lawnchair/smartspace/BcSmartSpaceUtil.kt` | +2/−5 | smartspace card tapをhelper経由へ |
| `lawnchair/src/app/lawnchair/LawnchairApp.kt` | +11/−0 | `BuildConfig.DEBUG && Utilities.ATLEAST_BAKLAVA` gateの `StrictMode.setVmPolicy(detectBlockedBackgroundActivityLaunch().penaltyLog())` を `onCreate` 冒頭に追加。logのみでprocessを殺さない。review round 1でpolicy構築をfresh `Builder()` から既存VM policy継承の `Builder(StrictMode.getVmPolicy())` へ修正（下記） |

`LawnchairApp.kt` の結論: debug build限定（`BuildConfig.DEBUG`）・log-only（`penaltyLog()` のみでprocessを殺さない）であり、かつreview round 1対応以降は `StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())` で既存VM policyを継承してBAL検出を上書きするため、framework提供の既存detectionを落とさない。

full diff（`git diff e8aced7dbe..HEAD -- <6 files>`）を全行読み、5つの非Application fileはすべてActivityOptionsのBAL mode定数選択のversion gate付け替えのみで、PendingIntent作成・起動先・結果処理の変更がないことを確認した（revision 1での確認から当該5 fileは不変。`git diff 4d61ba06d5..HEAD -- <5 files>` は空）。`LawnchairApp.kt` はdebug限定・log-onlyのStrictMode policy追加と、そのpolicy継承1行の修正のみである。

high-risk分類の根拠は `tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_FILES`（Issue #44 writer inventory）に `lawnchair/src/app/lawnchair/LawnchairApp.kt` が含まれること（path-list membership）のみである。auditの結論として、production書込み経路（Launcher DB write、schema migration、recovery store、model/loader bridge）への追加・変更はなく、PI作成契約・起動先・結果処理・DB pathは不変である。これは受入済みspec（`specs/527-target37-bal-granular/spec-lite.md`、PR #540でaccepted）のScope/Non-goalsと一致する。

emulator検証証跡は `docs/assessment/527-bal-granular-evidence/`（API 37必須／API 36／API 35 legacy分岐のmatrix。README + screenshot + logcat full capture）。元matrixの証跡実施headは `60d65ed789` で、`60d65ed789` と `4d61ba06d5` の間のsource差分はゼロ（証跡docs commitのみ）である。`4d61ba06d5` 以降のsource差分は `LawnchairApp.kt` のpolicy継承1行のみであり、その影響は新 head `de913083da` のAPKで実施した再検証 `api37-strictmode-policy-inherit-verify.txt`（2026-10-07、下記Criteria check）が直接カバーするため、証跡全体がaudit対象コードに対応する。

## Criteria check

- ADR-0018（accepted revision 7）の適用関係: 本PRはADR-0018 Decision 1固定のupstream `43a21b43` anchor系譜に属するPhase 2 baseline `e8aced7dbe` から分岐している。Decision 3の移行期間規則（`com.android.launcher3` / SystemUI等への新規bridge追加は原則停止）に従い、上流file（`Utilities.java`、`QuickstepInteractionHandler.java`、`SystemApiWrapper.kt`）への変更は定数選択とversion gateへの最小bridge編集に限定され、全siteにIssue番号付きcomment（`NunuLauncher #527: ...`）が近傍に残されている。`com.android.launcher3` 側2 fileへの変更も例外を設けない最小差分である。
- ADR-0018 Decision 3/5（branch方針とrollback点）: 本PRはPhase 4 cutoverまでmergeしない取り扱いであり（PR本文に明記。review・検証記録の正本として保持する）、Decision 3の「切替はrebase branchの全検証完了後、単一のmergeで行う」に従う。Decision 5のrollback点（cutover merge直前の旧main headのtag固定、force-reset不使用）に影響する変更は含まない。revision 1 audit後のコード変更は本再auditの対象であり、本記録がその再auditである。本head以降はdocs-only commitのみを許容し、code変更時は再度本headでの再auditを要求する。
- 受入済みspecのVerification義務の履行: emulator matrix（API 37必須／API 36／API 35）が実施され、起動結果・logcat BAL blocked filter・debug StrictMode logの観測が証跡dirに記録されている。自己再起動PIはspec G5の契約どおり新規grantを追加せず、block実測時のfailure signatureをlogcat full captureとして1件のみ記録し、後続の最小mode判断へ渡している。書込み経路を追加しないことの確認（spec Verification最終項）も本auditのdiff全行読みにより再確認した。specが要求する既存test suite gate（organizer unit / instrumentation）はCI runの当該jobでgreenを確認した。
- review round 1対応後の再検証（policy継承の影響確認）: `docs/assessment/527-bal-granular-evidence/api37-strictmode-policy-inherit-verify.txt`（5,891行のlogcat full capture。head `de913083da` のdebug APKをAPI 37 emulatorで実施）により、policy継承変更後も既知のrestart-PI blockが `BackgroundActivityLaunchViolation` として引き続き検出されることを確認した（`Process app.lawnchair.debug restarted`（01:10:45.516）後、`PendingIntent Activity start blocked ... PendingIntent was created in app.lawnchair.debug ... app.lawnchair.debug could opt in to grant BAL privileges when creating`（01:10:50.366）の1件のみ。block後もlauncherは `+state_resumed` / `LAUNCHER_ONRESUME`（01:10:50.378–382）で復帰し、検出がlog-onlyであることも実測どおり）。detectorの検出能力がpolicy継承によって損なわれていないことの直接証跡である。

## Executed test surface

本audit session（revision 2）がhead `de913083dabc63cea0a23278cbc95432b8ae37f1`（branch `issue-527-bal-granular`、working tree cleanを事前確認）で実行したcommandと結果:

- `git status` → `nothing to commit, working tree clean`。`git rev-parse HEAD` → `de913083dabc63cea0a23278cbc95432b8ae37f1`。GitHub上のPR #541 head SHA（API `pulls/541` の `.head.sha`）と一致、base = `issue-532-phase2-restart` をAPIで確認。
- `git diff --stat 4d61ba06d53d6a0835ddacc87f1eaa1008bff999..HEAD` → 4 fileのみ: `docs/assessment/527-bal-granular-evidence/README.md`（wording同期）、同dir新規 `api37-strictmode-policy-inherit-verify.txt`、`docs/assessment/pr-541-bal-granular-audit.md`（audit記録）、`lawnchair/src/app/lawnchair/LawnchairApp.kt`。revision 1以降のsource変更がこの1 fileに限定されていることを確認。
- `git diff 4d61ba06d5..HEAD -- lawnchair/src/app/lawnchair/LawnchairApp.kt` → 全行読み。`StrictMode.VmPolicy.Builder()` → `StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())` の1行置換と、継承理由のcomment 1行追記のみ。gate条件（`BuildConfig.DEBUG && Utilities.ATLEAST_BAKLAVA`）、`detectBlockedBackgroundActivityLaunch()`、`penaltyLog()` は不変。
- `git diff --numstat e8aced7dbe..de913083da -- <6 source files>` → 合計 +39/−29（上表の内訳どおり）。
- `./gradlew spotlessCheck` → `BUILD SUCCESSFUL in 7s`（4 actionable tasks: 4 up-to-date、warm cache）。
- CI merge gate検証（`gh api repos/nunu1733/NunuLauncher/actions/runs/37493739107`、同 `.../jobs?per_page=100`）: 当該run（event = `pull_request`、head_branch = `issue-527-bal-granular`、head_sha = `de913083da...`、GitHubのPR関連付け = [541]、path = `.github/workflows/ci.yml`）はattempt 2でcompleted / conclusion = success。全16 job（`final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、organizer instrumentation各lane、`validate-repo-contract` 等）がsuccess・実行済み（skipなし）で、validatorの必須source job要件を満たす。
- attempt 1失敗の独立確認: `organizer-instrumentation-production-input-tests` のみが `ProductionOrganizationInputInstrumentationTest#productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites`（競合guard assertion、1回不成立）で失敗。rerun前のPR comment https://github.com/nunu1733/NunuLauncher/pull/541#issuecomment-6021289965 （2026-10-06T17:01:06Z）が `docs/engineering/quality-strategy.md` のcategory 3（test synchronization / timing sensitivity）として分類・記録済み（failure-time evidence: ANR 0件・dropbox entries無し・ActivityManager Slow operation 58–88ms のみ。同一laneは直近の全CI runでgreen。local同一head APKで当該class 3回実行しcomposer test全pass。本PRのdiffに当該testへの因果pathなし）。本auditはcomment内容とrun APIの `run_attempt: 2`（17:01:14Z開始 = comment 6秒後）・attempt 2全job successを突合した。rerunでgreen。
- 旧audited headの検証履歴: revision 1が検証したCI run 37470516348（attempt 4、全16 job success）はhead `4d61ba06d5` でのmerge gate記録として有効であり、BAL mode対象6 fileのうち `LawnchairApp.kt` 以外の5 fileは `4d61ba06d5` 以降変更がないため、その検証は現headの当該fileにもそのまま対応する。
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 541 --head-sha de913083dabc63cea0a23278cbc95432b8ae37f1`（本記録のrevision 2書き換え前。pre-audit state）→ exit 1、`FAIL: high-risk evidence gate (docs/assessment/pr-541-bal-granular-audit.md): changes after the audited Head SHA are not docs-only (lawnchair/src/app/lawnchair/LawnchairApp.kt); re-audit against the new head`。本再auditの作成動機となるgate作動の直接確認である。同一command（本記録のrevision 2書き換え後）→ exit 0、`PASS: audit docs/assessment/pr-541-bal-granular-audit.md covers de913083dabc63cea0a23278cbc95432b8ae37f1 with independent CI evidence (docs/adr/0018-lawnchair-16-rebase.md)`。

## Findings

- (a) API 37での自己再起動PI BAL block（spec G5が予見した条件の実測。policy継承変更後も不変）。failure signatureは `docs/assessment/527-bal-granular-evidence/api37-restart-pi-block-signature.txt`（revision 1時点、要点: `balAllowedByPiSender: BSP.NONE; resultIfPiSenderAllowsBal: BAL_BLOCK; resultIfPiCreatorAllowsBal: BAL_ALLOW_ALLOWLISTED_COMPONENT`、`realCallingPackage: android.uid.system:1000`）に加え、policy継承変更後のhead `de913083da` APKでの再現が `api37-strictmode-policy-inherit-verify.txt` にlogcat full captureとして保存済み（`BackgroundActivityLaunchViolation` 1件、launcher復帰成功）。ユーザー影響なし（再起動自体はHOME消失によるsystem復帰で成功）。accepted spec G5の契約どおり本issueでは新規grantを追加せず、creator側の最小opt-inは本signatureを根拠とする後続判断へ分離済みである。
- (b) emulator laneの間欠失敗の分類履歴（2件。いずれもrerun前のPR commentでの分類記録・rerunで解消）: 1件目はCI run 37470516348（head `4d61ba06d5`）のonboarding-proposal laneで、attempt 1〜3が同一test・同一signature（`launcher-resume-timeout`、foreign frontmost = nexuslauncher）で失敗。attempt 1〜2分は https://github.com/nunu1733/NunuLauncher/pull/541#issuecomment-6019329604 がcategory 5（emulator / runner / platform environment defect）として分類し、attempt 3分はrevision 1 auditがartifact検証で同機序と独立確認した（attempt 4でgreen）。2件目はCI run 37493739107（head `de913083da`）のproduction-input laneで、attempt 1が競合guard assertion `productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites` で1回失敗。 https://github.com/nunu1733/NunuLauncher/pull/541#issuecomment-6021289965 がcategory 3（test synchronization / timing sensitivity。ANR 0・Slow operation軽度のみ・sibling head全green・local 3回green・PR diffに因果pathなし）として分類し、attempt 2 rerunでgreen。2件ともコード起因を示す証拠がない。
- (c) `tools/repo-contract/measure_upstream_patch_surface.py` のbaseline JSONがv15期（`505dbc4` / `8af117b6`）のものでPhase 2 lineage（upstream `43a21b43`）のancestor検査に失敗し、baseline HEAD無変更でも同一失敗する（pre-existing。PR本文に記録済み）。spec Verificationの代替として、本diffの上流file touchは `Utilities.java` / `QuickstepInteractionHandler.java` / `SystemApiWrapper.kt` の3 fileのみでspecの「上流3 fileへの最小変更」と一致することをdiff全行読みで確認した。baseline JSONの更新は別判断として残る。
- (d) 明示: 本PRは高リスク振る舞い（Launcher DB書込み、schema migration、recovery storeの書込み・復旧）に一切触れていない。high-risk分類は `LawnchairApp.kt` のpath-list membershipのみに基づき、実際のdiffはdebug・log-onlyのStrictMode policy（既存VM policy継承）である。したがってホームレイアウト安全規約（snapshot revision照合・recovery point・相対reload等）の適用対象となるlayout書込みは含まない。
- (e) review low対応（API 35 QSBのE2E未確認の明確化）: 証跡READMEのAPI 35結果表row 02を「QSB tapは **E2E起動未確認**（当該imageにGoogle search gatewayが不在でlauncherに留まった。legacy分岐はコード上変更前と同一。BAL block 0件）」へ同期済みで、PR本文にも同一の限定が記録されている。API 35でのQSB E2E起動は本証跡では未検証であり、legacy `ATLEAST_U`/`ALLOWED` 分岐の振る舞い確認はコード読み + BAL block 0件の観測による。今後API 35でGoogle search gatewayが解決できるimageでの再確認が必要になった場合は別判断として残る。
