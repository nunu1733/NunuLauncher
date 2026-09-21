# Independent audit: PR #403 hub status cardから復元flowへ接続する（cold process起点、D-15）（#376）

> Status: accepted（監査記録。merge条件は本記録を含むhead上での `high-risk-evidence` green確認）
> Audit date: 2026-09-22

- Auditor: 独立監査セッション (ZCode/GLM general-purpose agent による監査専用session。実装sessionとは別の作業主体。solo保守の独立session規定に基づく)
- PR: https://github.com/nunu1733/NunuLauncher/pull/403
- Head SHA: c0a4126ac26d5e847e336c05d4fa076fba7f8b2f
- Audited code head: c0a4126ac26d5e847e336c05d4fa076fba7f8b2f（= Head SHA。監査時点でbranch先端にそれ以降のcommitは存在しない。直前のmerge commit `18ebea7b77` との差分 `18ebea7b77..c0a4126ac2` は `docs/assessment/evidence/issue-376/README.md` の相対link修正1行のみのdocs-onlyであることを確認済み）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35655543974 (`ci.yml`, `pull_request` event, 上記Head SHA上で実行され **completed / success** — 14 job全てsuccess、`final-status` green [job 106524218317])
- `High-risk gate / high-risk-evidence`: https://github.com/nunu1733/NunuLauncher/actions/runs/35655544048 は **failure** — 原因は本監査記録 (`docs/assessment/pr-403-durable-status-recovery-entry.md`) が対象head上に存在しないことのみである。本記録の追加がその是正であり、記録commit後のgate再実行でgreenになることをmerge operatorが確認すること。
- Criteria: specs/376-durable-status-recovery-entry/spec.md @ `c0a4126ac2` — RS-AC-01, RS-AC-02, RS-AC-03, RS-AC-04, RS-AC-05, RS-AC-06, RS-AC-07, RS-AC-08, RS-AC-09, RS-AC-10（Design decisions D1–D6を含む）。spec status: accepted（owner受入 @5764528122記録済み）

## Scope

監査対象diffは指示どおり `git diff 065ac8ccc5..c0a4126ac2` (66 files, +9401/−201)。ただしこのうち **#374実装（PR #399、main merge #401）と #375 spec/plan（PR #402）の取り込み分** が含まれる。`origin/main` (`5065ce3b10`) がbranch headのancestorであることを `git merge-base --is-ancestor` で確認したため、**PR固有のdeltaは `origin/main...c0a4126ac2` = 42 files, +2231/−63** であり、監査の本体はこれに対して行った（#374は既に独立audit記録 `docs/assessment/pr-399-durable-imported-intent.md` で検証済み）。

production code の変更は次の 11 file に限定される:

- `lawnchair/src/app/lawnchair/organizer/application/public/RestorableRecoveryEntry.kt` (新規: 閉じた `RestorableRecoveryEntry` + sealed `RemainingWindow`)
- `lawnchair/src/app/lawnchair/organizer/application/lifecycle/RestorableRecoveryPointSelector.kt` (新規: 純粋selector。最新検証済み1点選択 + 粗粒度残時間)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` (+41: `readRestorableRecoveryEntry()` 追加のみ)
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (+196: façade委譲、`beginRecoveryPreviewFromDurableEntry()`、entry origin（`RecoveryEntryOrigin`）とpre-entry状態保持、`recoveryCancelTargetLocked()` によるorigin別戻り先、`leaveRecoveryResultToHub()`、`beginOperation()` でのorigin解消、arm/consume handoff、`processInstanceId`)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` (+110: restorable行の残時間 + 復元CTA、status read→entry readの直列化、CTA navigation)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (+88: `durableRecovery` route引数、destination所有admission（NonCancellable + handoff + 自己pop）、`interruptAndNavigate` の明示的hub帰還分岐)
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` (+5: `HomeScreenManualOrganization.durableRecovery`) / `PreferenceNavigation.kt` (+1: 引数通過)
- `src/com/android/launcher3/LauncherModel.java` (+85/−: `forceReloadForOrganizer` の非bind時tokenless loader起動、`OrganizerReloadRequest.loaderStarted`、`cancelOrganizerReloadIfCurrent` 追加)
- `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java` (+6: timeout/interrupt時の `cancelOrganizerReloadIfCurrent`)
- `lawnchair/res/values/strings.xml` (+5) / `values-ja/strings.xml` (+4)（残時間plurals + 1時間未満string）

残りは新規/拡張test（unit 3 file、instrumentation 11 file。うち7 fileはfaçade member追加の1〜4行のみ）、spec 271/366 companion revision、spec 376 plan.md追記、`DESIGN.md` §4.2、evidence README + PNG 7枚（`docs/assessment/evidence/issue-376/`）、inventory evidence追記。

`CONTEXT.md` はdeltaに含まれない（spec「見込み: 不要」どおり新ドメイン概念なし）。credential・生成物・端末固有設定は含まれない。

## 監査確認結果

**(1) spec 84/13/230/52/89/271 公開契約の不変 — PASS。** `RecoveryPreviewProtocol.kt` / `RecoveryProtocol.kt` / `OrganizerDurableStatus.kt` / `RecoveryInspectionSnapshot.kt` はdeltaに一切含まれずbyte-identical。`LayoutApplicationModule` への変更は `readRestorableRecoveryEntry()` のadditive追加のみで、既存 `inspectRecovery` / `confirmRecoveryPreview` / `durableOrganizerStatus` / token registry（module instance field）は無変更。coordinatorの旧entry（`beginRecoveryPreview`）は外部挙動不変（origin記録が内部bookkeepingとして追加され、cancel戻り先は `recoveryCancelTargetLocked()` の `AppliedSurface`/null → `lastVerifiedApply ?: State.Idle` で現行どおり）。旧path oracle `freshRunInstanceDoesNotReachRecoveryPreview` / `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface` / `recoveryPreviewCarriesTheCorrelatedApplyHistory` は `ManualOrganizationRunTest.kt` への **削除行ゼロ**（diff確認）で無編集のまま、本監査の独立unit再実行でgreen。

**(2) LauncherModel bridge（Issue #299規約の適用、#150/#152契約の維持）— PASS。** source独立確認:

- **token identity gate（#152）**: `completeOrganizerReload` は無変更。snapshotはloader binder境界でgeneration局所の `organizerToken` に束縛してcaptureされ、`mOrganizerReloadToken != token` なら破棄される（token一致時のみcompleted配信）。
- **exactly-once terminalization（#150）**: 新tokenのterminal経路は (a) `stopLoader()` → `cancelOrganizerReload()`、(b) 登録時のdisplaced `superseded.cancelled.run()`（stopLoaderが実停止しなかった残留を1回だけ）、(c) main executor上の `neverStarted`（tokenをmLock内でclearしてからlock外でcancelled）、(d) 新規 `cancelOrganizerReloadIfCurrent(requestId)`（identity一致時のみclear→cancelled）、(e) loader完了 `completeOrganizerReload` — いずれもmLock下のidentity確認+clearを前提とし、二重terminalは構造的に起こらない。
- **非bind時分岐（本PR新設）**: 旧codeは `!hasCallbacks()` で即 `token.cancelled` — cold settings-only process（spec 271 DS-AC-10 bridgeでmodel負荷済み・callback非bind）では復元のcorrelated reloadが必ず失敗していた。新codeはcaller thread先にtoken登録 → `MAIN_EXECUTOR` 上でtoken identity再確認（#299と同型のrace closure。この間のsupersede/cancelは黙って脱出）→ bound時 `startLoader()` / 非bind時 `startLoaderWithoutCallbacks()`（既存bridgeの再利用、loader機械の新設なし）→ `loaderStarted` 未設定のまま残ればtokenをclearしてcancelled。organizer reloadは登録時に `mModelLoaded = false` となるため `bindDirectly` 直結bind経路は起こらず、async generation生成時の `organizerToken.loaderStarted = true`（#299の `RestoreReloadRequest` と同一箇所・同一規約）が正しい生成判断を与える。adapterのtimeout/interrupt放棄時にstale tokenを残さない新規cancel-if-currentも #299のrestore側と対称。upstream由来の他経路（通常 `startLoader()` 呼び出し等）への影響差分はない。

**(3) ホームレイアウト安全規約（AGENTS.md）— PASS。** 復元適用のwrite pathは無変更で、既存 `confirmRecovery()` → module `confirmRecoveryPreview` → `recoverWithApplicationBehavior` → `RecoveryProtocol.recover`（spec 13。revision一致・transaction・recovery point・再検証は既存protocolが所有。該当fileはdelta外）のみである。新設の `readRestorableRecoveryEntry()` は `durableOrganizerStatus()` と同一のfail-closed gate契約（readiness gate未ready → null、`ordinaryMutex.tryAcquire` 非block、snapshot read失敗/`Unavailable` → null、`finally` release）で、書込み・lifecycle遷移・retention cleanup・journal eventなし（unit `entryReadIsZeroWriteAndSilent` のcounter assert + hub oracleの `diagnostics.events` 空assert）。UI/read layerから新write pathはなく、CTAはarm + navigateのみ（admissionはrun面destination所有、zero-write）。entry read失敗ではCTAを出さず表示のみへ後退（fail-closed安全側）。

**(4) D5/D6 coordinator・UI契約 — PASS。** source確認:

- admission: RECOVERY lease取得後、lock下で `activeOperation == null && recoveryLease == null && state ∈ {Idle, Cancelled}` を要求し、不成立ならlease closeして静かに不受理（unit `durableEntryIsSilentlyRejectedOutsideIdleAndCancelledWithoutLeaseLeak`）。admission時のentry re-readがnullなら不受理（`durableEntryIsRejectedWhenTheSelectionReadFailsClosed`）。
- 戻り先: cancel（`cancelRecoveryPreview`）・dismiss中のrecovery取消・検査throw経路のいずれもorigin束縛で、HubStatusCard起点はpre-entry状態（`Idle`/`Cancelled`）へ復帰し `State.Applied` へ戻らない（unit 3件: Idle起点/Cancelled起点/Applied対比）。
- hub帰還: `leaveRecoveryResultToHub()` は `RecoveryResultState && HubStatusCard` のときのみpre-entry状態へ復帰。`dismiss()` の `RecoveryResultState`（`NoActiveOperation`・state残留）は **無変更**（host cleanup・診断pushではresult/safe-support面が維持 — unit `hubOriginRecoveryResultStateSurvivesTheGenericDismissal` + instrumentation `restoreFailureKeepsTheResultFaceAcrossTheDiagnosticsRoundTrip`）。旧Applied originでは明示的操作も含め現行挙動（`legacyRecoveryResultStateIsUnchangedByTheExplicitHubReturn`）。`beginOperation()` がoriginを解消（`newRunAdmissionDissolvesTheHubRecoveryEntryOrigin`）。
- 適用履歴行: status card entryのadmission条件（Idle/Cancelled）と `lastVerifiedApply` 生存条件の構造的排他により `correlated` は常にnull（`State.RecoveryPreview(preview, appliedSummary = null)` を直接publish。spec 230 D2 gateの再利用）。
- read直列化（D6）: hubは同一effect内でstatus readを先に行い `ORGANIZED_RESTORABLE` のときのみentry read（`LaunchedEffect` 内直列。`hubReadsTheEntryHintOnlyAfterARestorableStatusAndNeverConcurrently` がread log `status→entry` と `maxConcurrentReads == 1` を固定）。run面は `durableRecovery = true` でstatus read自体を抑制し自己競合を封じる。entry read fail-closed時は行を表示のみに後退（`hubRestorableRowStaysDisplayOnlyWhenTheEntryHintFailsClosed`）、再読込契機で回復（`failClosedEntryHintRecoversOnTheNextReReadTrigger`）。
- handoff: hub CTAはarm → 同期navigate。run面destinationは `rememberSaveable` + `processInstanceId` で同一process再入場の再admissionを防ぎ、arm未消費・process死後は自己pop。admission窗口はNonCancellableで閉じ、host離脱競合時はpre-entry状態へ復帰、popは自entryがcurrentのときのみ（`durableEntryLaunchHandoffIsConsumedExactlyOnceAndLostOnProcessDeath` + `durableRecoveryRouteWithoutHandoffPopsBackToTheHubWithoutInspecting`）。

**(5) D1/D2/D3 選択と残時間 — PASS。** selectorは純粋（I/O・Android型なし）で、`VERIFIED` + `checksumValid` + `now < createdAt + RETENTION_MILLIS`（`RetentionPolicy.RETENTION_MILLIS` を単一sourceで参照、literal複製なし。ちょうど24hで除外 — DS-AC-04語義と一致）、`createdAtMs` 最大 + `pointId` 辞書順tie-break（決定的）。`RemainingWindow` は切捨て・1..24 clamp・`LessThanOneHour` 区分。公開型は閉じた2型のみで、pointIdはopaque handleとしてcoordinatorまで（描画・log・永続化なし）。`OrganizerDurableStatus` enumは拡張されず（D2）、settings側run面のdurable行はCTAなしの表示のみ維持（同fileのdurable行はdelta非変更。`ManualOrganizationPreferencesInstrumentationTest` はfaçade追加1行のみで既存oracle無編集green）。

## Criteria check

- **RS-AC-01 (PASS)** — cold-process emulator evidence `docs/assessment/evidence/issue-376/README.md` + `evidence1..4` PNG（force-stop → hub最初の面 → Launcherを開かずCTA→検査→確認→復元 → Back → hubで「restored or expired」へ再derive。有効point 1点のprecondition明記）。state-machine unit `durableEntryOpensPreviewFromIdleAndExplicitHubReturnRestoresPreEntryState`、instrumentation `restoreSuccessAndExplicitHubReturnReDeriveTheDurableStatus`、CI lane外の2-phase `OrganizerRestoreColdProcessEvidenceTest`（seed/restore、README手順と対応）。
- **RS-AC-02 (PASS)** — selector unit 8件（単一点/複数点最新/tie-break/除外/境界±1ms/window境界/空null）+ `durableEntryRepresentsTheNextRemainingPointAfterHubReturn`（unit）+ `restoreSuccessRepresentsTheRemainingPointAfterHubReturn`（instrumentation。複数点→最新復元→hub帰還→残存point再提示）。24h/最大3点/tombstone契約はspec 13側file無変更。選択UIは存在しない（UI diffに選択面なし）。
- **RS-AC-03 (PASS)** — `LayoutApplicationModuleRestorableEntryTest`: `freshModuleInstanceCannotConsumeTheOldConfirmationToken`（fresh module = fresh registry。process死の構造的surrogate）+ `sameModuleInstanceStillConsumesTheTokenUntilItIsSpent`（coordinator再構築ではsurrogateにならない対比。testコメント明記）+ 既存one-shot消費契約の継続。`durableEntryLaunchHandoffIsConsumedExactlyOnceAndLostOnProcessDeath`（unit）。evidence `rsac03_1..3`（preview生存 → force-stop → 再入場はCTA再提示 → 再検査経由でのみ復元）。
- **RS-AC-04 (PASS)** — unit 10件（admission不受理・lease単一flight・Idle/Cancelled起点のcancel戻り先・hub帰還・dismiss非変化・旧origin対比・origin解消）+ instrumentation（`hubExposesTheRestoreCtaAndNoOtherRunResultAffordancesAndStartsNothing` へのrename更新、`hubHidesStatusRowsWhileRunIsActiveAndReshowsAfterCancel` 無編集継続、診断round tripでresult面維持）。writer lease / RECOVERY lease / run admissionの機構はdelta非変更。`interruptAndNavigate` はhub帰還のみを明示経路に載せ、他は現行dismiss。
- **RS-AC-05 (PASS)** — TOCTOU/stale/busyのtyped結果は既存検査・confirm機構のまま（該当file無変更）。entry read fail-closedでCTAなし、cancel zero-write、期限切れ後の行は表示のみ（`hubRendersRestoredOrExpiredStatusLine` ほか既存oracle継続）。
- **RS-AC-06 (PASS)** — window unit（切捨て/clamp/1時間未満）+ read直列化oracle（重複なし・非restorable時にentry read呼ばれない・fail-closed後の再読込回復）+ 読み順「状態→残期限→操作」（`hubStatusRowsPrecedeTheActionsInReadingOrder` 拡張）+ CTA `Role.Button`（`hubRowsExposeNameRoleAndStateToAssistiveTechnology`）+ 200% font scale（既存oracle継続）。絶対時刻・identifier・件数はentry型に存在しない。
- **RS-AC-07 (PASS)** — companion revision実施をdiff確認: spec 271（Non-goalsのspec 376参照置換、DS-AC-07のstatus card正位化、Open questions解決、Change history 2026-09-22行）、spec 366（HUB-AC-03の復元CTA部分supersede注記、Change history、oracle更新記録）。`OrganizerDurableStatus` 型・派生・他DS-AC契約は無変更。spec 84/13/230/52契約fileは無変更で、既存suite無編集greenは(1)と独立unit再実行が根拠。companion revisionのowner受入はspecの実装PR実施規定とPR review processに委ねられた範囲であり、本記録はdiff内容の一致を検証した（受入行為自体はPR reviewでのowner確認対象 — merge operatorに注記）。
- **RS-AC-08 (PASS)** — production diffへの新規 `RunEvent`/journal/diagnostics発行はgrep照会で不在。entry readは無音（unit）。hub oracleが `diagnostics.events == emptyList` を固定。確認実行のdiagnosticsは既存経路のまま。
- **RS-AC-09 (PASS)** — 新規resource 2件（plurals `manual_organization_recovery_remaining_hours`、string `manual_organization_recovery_remaining_under_one_hour`）が `values/` と `values-ja/` の両方に存在（EN one/other、ja other — locale正）。placeholder `%1$d` は使用quantity間で整合（EN one項は文脈上引数不要）。CTA labelは既存 `manual_organization_recovery` 再利用。UIはresource由来のみでhardcoded user-visible literalなし。a11y oracleはRS-AC-06のとおり。
- **RS-AC-10 (条件付き成立 → 記録commitで完全成立)** — 検証対象head `c0a4126ac2…` 上で `CI / final-status` が **success**（run 35655543974。source jobs `organizer-unit-tests` / `check-style` / `build-debug-apk` をskipなしで含む14 job全success。`validate-repo-contract` もsuccess）。`high-risk-evidence` のみ本記録の不在を理由にfailure — 本記録がその是正である。

## Executed test surface（本監査session内、対象head `c0a4126ac2…` 上で独立実行）

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL (exit 0)。result XML: 151 classes / **1683 tests / 0 failures / 0 errors / 0 skipped**（新規 `RestorableRecoveryPointSelectorTest` 8件 + `LayoutApplicationModuleRestorableEntryTest` 7件を含む）

emulator実行は本監査sessionでは行っていない（下記「未確認範囲」）。

## CI証拠（監査完了時点）

- **run 35655543974** (`ci.yml`, `pull_request`, head `c0a4126ac2…`, branch `issue-376-recovery-entry`): **completed / success**。`changes` / `build-debug-apk` / `validate-repo-contract` / `check-style` / `organizer-unit-tests` / organizer-instrumentation 全9 lane（api35 / db-migration / issue99 / issue155 / issue53 / issue299 / issue332 / shared-writer / **issue52（hub UI class群を含むlane**）のすべて success。`final-status` job success（job 106524218317）。
- **run 35655544048** (`high-risk-gate.yml`, 同head): `high-risk-evidence` failure。失敗原因は監査記録fileの不在のみ（gateの機械検証対象が未存在）。本記録を含むdocs commitのpush後に同一検証対象コード上でgateがgreenになることをmerge operatorが確認すること（pr-276 round 2/3と同一の是正パターン）。

## Findings

- **Blocking: なし。** 監査項目 (1)–(5) と RS-AC-01..09 はすべて対象headで成立した。RS-AC-10のCI側要件はhead上で成立、audit記録要件は本記録で充足。
- 参考 (非blocking): **emulator evidenceの画素内容は本監査からは機械検証できない**（PNG画像の状態文言・手順はREADMEの記述とファイル名・枚数・時刻の整合で確認したにすぎない）。代替として、同一挙動を自動化する instrumentation oracle（復元後再derive・再検査のみ再開・handoff消失時の自己pop）とunit層の構造的固定（token registry所有境界・handoff exactly-once）が同一headでgreenであることを根拠にした。「Launcherを開かない」手順自体はworker取得手順への依存として明示する（pr-276 round 2/3と同一の限界。因果chain — gate READYのproduction経路がreconciliation triggerのみ — は仕組み上裏付けられる）。
- 参考 (非blocking): **keyboard/DPAD oracle範囲**。復元flow帰還時のfocus restorationは `restoreCtaOpensTheRecoveryFlowOnTheRunFaceAndBackReturnsToTheHub` への `awaitFocused` assert追加（物理DPAD非依存）で固定されたが、復元CTA自体への実DPAD traversal到達を専用に検証するoracleは無い（CTAは `clickable(role = Button)` でfocus targetを所有する既存規約、traversal系oracle `hubTraversalReachesStartDiagnosticsAndMaterialsInOrder` はstart/診断/材料を対象。spec 366 HUB-AC-07規約の範囲内と判断するが、将来CTA行が増えた際のtraversal oracle拡張余地）。
- 参考 (非blocking): `retentionBoundaryIsInclusiveAtExactlyTwentyFourHours` のtest名は語義と紛らわしい（実装・assertion・DS-AC-04は「ちょうど24hで除外」で一致し、testは±1ms両側を正しく検証する）。名称改善余地。
- 参考 (非blocking): spec 376のfrontmatter statusはhead時点で `accepted`。workflow先例（specs/231/89/271）ではlandするPRで `implemented` へ遷移させるため、merge時のstatus遷移とIssue #376 close（PR本文 `Closes #376`）をmerge operatorが確認することが望ましい。
- 参考 (非blocking): `forceReloadForOrganizer` のloader起動がcaller threadから `MAIN_EXECUTOR` へ移された。#299 `dispatchRestoreReload` と同一規約（caller thread先のtoken登録 + main上のidentity再確認）であり、bound時の既存挙動は不変。LauncherModelは高リスクpathだが、差分はorganizer/restore reload token機構内に収まる。
- 未確認範囲: (i) cold-process emulator flow本体の独立再実行（emulator未起動・app data保護。上記の自動oracleとREADME目視で代替）; (ii) `OrganizerRestoreColdProcessEvidenceTest` の実行（CI lane外のevidence toolingであり、READMEのdeep link + UI駆動を正本とする運用）; (iii) 物理deviceでのprocess death再現（emulatorがrestart-equivalent代理）; (iv) companion revisionのowner受入行為自体（diff一致は検証済み。PR reviewでの確認対象）。

## Overall verdict

**Approve（条件: 本記録を含むdocs commitのpush後、同一検証対象コード上で `High-risk gate / high-risk-evidence` がgreenになることをmerge operatorが確認すること）** — 対象head `c0a4126ac26d5e847e336c05d4fa076fba7f8b2f` のdeltaに対し RS-AC-01..09 を独立検証済み。既存recovery protocol（spec 13/84）のwrite pathと公開契約は無変更で、新規経路は読み取り専用・fail-closed・zero-writeであることをsource・unit・instrumentationの三層で確認した。LauncherModel bridgeは #299規約どおりで #150/#152のterminal境界契約（identity gate・exactly-once terminalize・snapshot gate）を維持する。CI merge gate（`final-status`）はhead上でsuccess（run 35655543974、14 job全green）。`high-risk-evidence` は本記録の不在のみを理由にredであり、本記録の追加がその是正である。
