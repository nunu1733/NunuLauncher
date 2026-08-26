---
issue: "#156"
status: draft
requirements:
  - AC-156-01-hotseat-tokenless-admission
  - AC-156-02-correlated-reload-progress
  - AC-156-03-deferred-exactly-once
  - AC-156-04-no-contention-compatibility
  - AC-156-05-writer-inventory-coverage
  - AC-156-06-device-recovery-no-starvation-timeout
updated: 2026-08-27
---

# Hotseat のトークンなし書込みは organizer lease 中にモデル実行器を停止させない

## Problem

空の予測バッチを受けた `HotseatPredictionController` は
`HotseatRestoreHelper.restoreBackup` を `MODEL_EXECUTOR` に投入する。現行helperは
同じrunnable内でトークンなしの `ModelDbController.newTransaction()` を開くため、
organizer lease が有効な間は `acquireBlockingQuietly(MODEL_WRITER)` で単一の
モデル実行器を待機させる。[Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156)

この待機中に organizer の自動復旧が相関リロードを要求すると、その `LoaderTask` は
停止したrunnableの後ろで待機する。その結果、復旧側の
`OrganizerModelReloadAdapter` が10秒で timeout となり、lease解放後に初めて
LoaderTaskが開始される。これは apply/recovery の A7 で特定リロードを待ち、
失敗時に正しく自動復旧する既存契約を、書込み競合ではなくexecutor starvationで
破る。[Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150)

## Outcome

organizer または restore-family lease が有効な間、`HotseatRestoreHelper` の
トークンなし `createBackup` / `restoreBackup` 書込みは既存
`LayoutWriteCoordinator.runOrDefer` FIFO に入れられ、`MODEL_EXECUTOR` を直ちに
返す。相関organizerリロードはleaseの有効中に進行し、helperの既存DB更新・reload
意味論はlease解放後に一度だけ実行される。新たなwriter lock、lease kind、公開seamは
導入しない。

## Scope

| 含む項目 | 内容 |
|---|---|
| Hotseat helperのadmission | `HotseatRestoreHelper.createBackup` と `restoreBackup` のMODEL_EXECUTOR hand-offを、既存の `runOrDefer` でgateする。 |
| organizerとの進行保証 | organizer leaseを保持したまま、無関係なtokenless helper taskが待機しても、exact-tokenの相関LoaderTaskが完了できることを実Launcher instrumentationで証明する。 |
| deferred実行の保持 | lease解放後にFIFOの先頭から一度だけ実行され、失敗したentryが後続entryをwedgedにしない既存coordinator契約を維持する。 |
| writer inventory | `quickstep/src` を実行可能inventoryの対象に含め、helperをallowlistへ登録して将来の未gate `ModelDbController` 呼出しをCIで拒否する。 |
| device evidence | fresh default workspaceで復旧legの `MODEL_RELOAD_FAILED` timeout が解消することを、#155の独立したQSB-overlap結果と区別して確認する。 |

## Non-goals

- `LayoutWriteCoordinator` に第二のlock、new public coordinator、new lease kind、または跨thread organizer token再入を加えない。
- `ModelDbController.newTransaction()` のbaseline blocking契約を全呼出し元へ一律に変更しない。今回の対象は、単一の `MODEL_EXECUTOR` へ自ら投げるHotseat helperのtokenless経路である。
- #155が所有するQSB reservation/配置overlapによる初回A7不一致を変更・隠蔽しない。本Issueは復旧reloadのstarvationを除去するものであり、単独でdefault workspaceのA8成功を主張しない。
- recovery protocol、run journal、UI、権限、ネットワーク、Launcher DB schema、backup formatを変更しない。
- 実時間10秒timeoutをtest oracleにしない。timeout定数は非injectableであり、テストはbarrier/latchで進行可否を判定する。

## Domain language

追加・変更するドメイン用語はない。ここでいう「deferred task」は、既存
`LayoutWriteCoordinator` が保持するprocess内FIFOの実装語であり、永続化された
ホームレイアウトまたはrecovery pointではない。

## Behavior scenarios

### Scenario: organizer lease中の空予測によるhotseat復旧

Given organizer apply または自動recoveryが `ORGANIZER` leaseを保持している

And `HotseatPredictionController.setPredictedItems` が空のprediction batchを受ける

When `HotseatRestoreHelper.restoreBackup` が呼ばれる

Then helperのtokenless DB taskは `runOrDefer` FIFOへ追加され、MODEL_EXECUTORは
lease待機を開始せずにdrainする

And exact organizer tokenで開始された相関LoaderTaskは、organizer lease解放前に
`OrganizerModelReloadAdapter.Outcome.COMPLETED` を返せる

And lease解放後、helper taskはFIFO順で一度だけ実行される。

### Scenario: organizer lease中のhotseatバックアップ作成

Given `ORGANIZER` leaseが有効である

When `HotseatRestoreHelper.createBackup` が呼ばれる

Then helperは `newTransaction()` の前に既存defer gateを通る

And leaseの有効中に `MODEL_EXECUTOR` が `MODEL_WRITER` lease待機で停止しない

And lease解放後に既存のbackup table作成とrestore-table cache refreshが一度だけ実行される。

### Scenario: contentionのない通常のhotseat更新

Given coordinatorにorganizer/restore-family leaseがない

When `createBackup` または `restoreBackup` が呼ばれる

Then taskはMODEL_EXECUTORへ通常どおりhand-offされ、既存のtransaction・commit・
cache refresh・restore後の通常reloadの意味論を保持する。

### Scenario: deferred helperの失敗または空のbackup table

Given helper taskがdefer済みである

When lease解放後にbackup tableが存在しない、またはhelperのDB処理が例外となる

Then helper自身の既存のtransaction/exception意味論を保持し、partial commitを成功と
誤認しない

And coordinatorは後続deferred entryを停止、重複実行、または永続的にqueueへ残さない。

### Scenario: fresh default workspaceの自動recovery

Given #155が追跡するQSB-overlapの結果により初回A7失敗から自動recoveryへ進む

And recovery leg中に空prediction batch由来のHotseat restore taskが到着する

When recoveryが相関model reloadを要求する

Then reloadはtokenless helper taskの後ろでstarveせず、`MODEL_RELOAD_FAILED` の
10秒timeoutを原因とするterminal failureにならない

And #155の未解決/解決状態に応じたlayout verification結果は、このstarvation修正と
混同せずに記録される。

## Data and state

- 正本であるホームレイアウトは引き続きLauncher DBであり、helperは既存
  `HYBRID_HOTSEAT_BACKUP_TABLE` のみを利用する。
- coordinatorのdeferred FIFOは一時的なprocess内状態であり、新しい永続queue、
  identity、retention、migrationを持たない。
- schema migration、ZIP/Android backup format、recovery pointの内容は変更しない。
- Hotseat helperが操作する配置アイテムの集合や、organizerの対象集合・lock状態・
  revision不変条件は変更しない。
- helper taskのtransactionは既存の `ModelDbController` / `SQLiteTransaction` seamを
  継続して使用する。deferはadmission時だけを変え、commit/rollbackの意味論を変えない。

## Permissions, privacy, and security

None。新しいpermission、外部通信、telemetry、個人データ、exportは追加しない。
既存のローカルLauncher DBとprocess内coordinatorのみを使用する。

## Accessibility and localization

UI文字列、focus、TalkBack、font scaling、翻訳対象の変更はない。失敗時のユーザー向け
messageも変更しない。

## Acceptance criteria

- [ ] **AC-156-01**: `HotseatRestoreHelper.createBackup` と `restoreBackup` の各tokenless DB taskは、organizer/restore-family leaseが有効な場合、`newTransaction()` より前に `runOrDefer` を通り、MODEL_EXECUTORで `acquireBlockingQuietly` を待たない。
- [ ] **AC-156-02**: organizer leaseを保持し、Hotseatのtokenless taskを投入したdeterministic instrumentation scenarioで、exact-tokenの相関reloadがlease解放前に `COMPLETED` となる。sleepまたは10秒timeoutを成功oracleに使わない。
- [ ] **AC-156-03**: deferされたHotseat taskはlease解放後に一度だけ実行される。task例外またはbackup table不在が、後続deferred workのFIFO drainを妨げない。
- [ ] **AC-156-04**: coordinator競合がない場合、`createBackup` はbackup table作成とcache refreshを、`restoreBackup` は既存restoreと通常reloadを保持する。新たな例外、busy結果、public API、schema変更を導入しない。
- [ ] **AC-156-05**: executable writer inventoryは `quickstep/src` を監査対象とし、Hotseat helperの両transaction経路を明示的なlease/admission理由とともにallowlistで管理する。未登録の同種callerはCIで失敗する。
- [ ] **AC-156-06**: fresh default workspaceでのmanual apply/recoveryのdevice evidenceは、recovery legがtokenless Hotseat taskにより10秒の `MODEL_RELOAD_FAILED` timeoutへ至らないことを示す。#155のlayout-overlap結果は別原因として併記する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-156-01 | `HotseatRestoreHelper` の実経路を起動し、organizer lease保持中にdeferred count/entry開始barrierを観測するinstrumentation test。source reviewではhelperからdirectにMODEL_EXECUTORへtransaction runnableを投入していないことを確認する。 |
| AC-156-02 | `OrganizerModelReloadAdapter` と既存のpre-completion callback barrierを使い、helper task投入後も相関reloadがlease解放前に `COMPLETED` となることをassertする。barrier/latchのみを因果oracleにする。 |
| AC-156-03 | helperの正常、backup table不存在、throwing deferred entryを含むfocused coordinator/instrumentation testsで、FIFO・exactly-once・後続entry進行を確認する。 |
| AC-156-04 | 対象helperの非競合testでbackup tableの存在、cache refresh、restore後のreload要求を観測し、既存semanticsとの差分がないことを確認する。 |
| AC-156-05 | `python3 tools/repo-contract/validate_writer_inventory.py` の成功、およびquickstepの未allowlist transaction callerをfixture/source scanで検出するregression testまたはself-check。 |
| AC-156-06 | API 36 clean emulatorでfresh installをHOMEとして実行し、supported export/logcat/thread dumpでrecovery reloadの開始・完了とterminal outcomeを記録する。成功はA8自体ではなく、timeout-starvation不在で判定する。 |

## Open questions

実装を妨げる未決定の製品・設計判断はない。#155のQSB-overlap修正は
AC-156-06のlayout結果に影響し得る外部依存だが、本Issueのexecutor進行oracleとは
独立である。そのため本Specは #155 のmerge状態を明示してdevice evidenceを記録する。

## Source evidence

| Source | Relevance |
|---|---|
| [Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156) | 症状、thread dump、制約、受入条件の正本。 |
| [Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150) | #156分離の根拠、#155との因果分離、device evidenceの前提。 |
| [Spec #60](../60-executor-writer-admission-audit/spec.md) | tokenless MODEL_EXECUTOR workの既存writer-admission契約。 |
| [Spec #13](../13-safe-layout-application/spec.md) | A7のcorrelated reload・DB/model convergence・失敗時recovery契約。 |
| [Spec #58](../58-serialize-runtime-restores/spec.md) | organizer/restore-family lease中の既存defer意味論。 |

## Change history

- 2026-08-27: #156用のDraftを作成。#150から分離されたexecutor starvationのみを対象化し、#155のlayout-overlap原因を非対象として明記した。
