---
issue: "#156"
status: accepted
requirements:
  - AC-156-01-atomic-hotseat-admission
  - AC-156-02-race-safe-correlated-reload-progress
  - AC-156-03-deferred-exactly-once
  - AC-156-04-no-contention-compatibility
  - AC-156-05-writer-inventory-coverage
  - AC-156-06-device-recovery-no-starvation-timeout
updated: 2026-08-27
---

# Hotseat のトークンなし書込みはいかなるadmission順序でもモデル実行器を停止させない

## Problem

空の予測バッチを受けた `HotseatPredictionController` は
`HotseatRestoreHelper.restoreBackup` を `MODEL_EXECUTOR` に投入する。現行helperは
同じrunnable内でトークンなしの `ModelDbController.newTransaction()` を開くため、
organizer lease が有効な間は `acquireBlockingQuietly(MODEL_WRITER)` で単一の
モデル実行器を待機させる。[Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156)

この待機中にorganizerの自動recoveryが相関reloadを要求すると、その `LoaderTask` は
停止したrunnableの後ろで待機する。その結果、復旧側の
`OrganizerModelReloadAdapter` が10秒でtimeoutとなり、lease解放後に初めて
LoaderTaskが開始される。これはapply/recoveryのA7で特定reloadを待ち、失敗時に
正しく自動recoveryする既存契約を、書込み競合ではなくexecutor starvationで破る。
[Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150)

単にhelper呼出し時に `runOrDefer` を評価し、その即時側でDB bodyを後から
`MODEL_EXECUTOR` へpostするだけでは十分ではない。判定後・executor実行前に別threadが
organizer/restore-family leaseを取得すると、DB bodyは再びblocking acquisitionへ到達する。
そのため、**admission判定とMODEL_WRITER所有権の取得またはFIFO登録は同じcoordinator
critical sectionで完結しなければならない**。

## Outcome

`HotseatRestoreHelper` のtokenless `createBackup` / `restoreBackup` DB taskは、
MODEL_EXECUTOR上で実行を開始するたび、既存 `LayoutWriteCoordinator` に対して
atomicに **(a) MODEL_WRITER leaseを取得してそのleaseでtransactionを実行する** か、
**(b) 現在のholderの後ろの既存FIFOへMODEL_EXECUTOR再投入を登録して直ちに戻る**。
外部leaseを待つblocking acquisitionは実行しない。

したがって、helper呼出し時にleaseがある場合だけでなく、gate判定からexecutor実行までに
leaseが取得されるraceでも、tokenless helperはqueueへ退避する。exact organizer tokenを
持つ相関LoaderTaskは既存capabilityで進行し、helperのDB更新・通常reload意味論は
writer leaseを実際に取得した1回だけ実行される。新たなwriter lock、lease kind、公開の
並行seamは導入しない。

## Scope

| 含む項目 | 内容 |
|---|---|
| atomic Hotseat admission | `HotseatRestoreHelper.createBackup` と `restoreBackup` の各MODEL_EXECUTOR taskが、transaction開始と同時にatomicなlease取得またはFIFO再投入を行う。 |
| existing coordinatorの拡張 | `LayoutWriteCoordinator` 内に、空き時のMODEL_WRITER lease取得とbusy時のdeferred continuation登録を同一monitorで行う限定internal operationを追加する。continuationはMODEL_EXECUTORへ戻す。 |
| transaction seam | atomic admissionで取得したouter MODEL_WRITER leaseの下で既存 `newTransaction()` を呼び、既存same-thread MODEL_WRITER reentryを利用する。新しい `ModelDbController` / `SQLiteTransaction` overloadは追加しない。 |
| organizerとの進行保証 | held organizer lease、helper task、exact-token correlated LoaderTaskを組み合わせ、gate時は無leaseで直後にorganizer leaseを取得するraceでもreloadが進行することを実Launcher instrumentationで証明する。 |
| deferred実行の保持 | busy時のhelper continuationは既存FIFO順で一度だけMODEL_EXECUTORへ再投入される。entry失敗が後続entryをwedgedにしない既存coordinator契約を維持する。 |
| writer inventory | `quickstep/src` を実行可能inventoryの対象に含め、helperのtransaction経路をallowlistへ登録する。inventoryはwriter存在・登録の検出を担い、gate構造の保証はfocused testが担う。 |
| device evidence | fresh default workspaceで復旧legの `MODEL_RELOAD_FAILED` timeoutが解消することを、#155の独立したQSB-overlap結果と区別して確認する。 |

## Non-goals

- `LayoutWriteCoordinator` に第二のlock、new coordinator type、new lease kind、または跨thread organizer token再入を加えない。
- `ModelDbController.newTransaction()` の全baseline callerのblocking契約を一律に変更しない。新しいatomic operationを必要とするのは、単一の `MODEL_EXECUTOR` 上でtokenless DB workを実行するHotseat helperである。取得済みleaseを受け取る新しい `ModelDbController` / `SQLiteTransaction` overloadも追加しない。
- #155が所有するQSB reservation/配置overlapによる初回A7不一致を変更・隠蔽しない。本Issueはrecovery reloadのstarvationを除去するものであり、単独でdefault workspaceのA8成功を主張しない。
- recovery protocol、run journal、UI、権限、ネットワーク、Launcher DB schema、backup formatを変更しない。
- 実時間10秒timeoutやsleepをtest oracleにしない。latch/barrierの待機上限はtest hangのguardだけであり、合否は順序イベントとlease状態で判定する。
- scannerで新規に見つかった別種のwriter ownership/lifecycle問題を、このIssueで無条件に修正しない。同一のtokenless MODEL_EXECUTOR blocking-admission原因だけを本Issueに含める。

## Domain language

追加・変更するドメイン用語はない。ここでいう「atomic admission」は、既存
`LayoutWriteCoordinator` の内部monitor下で、MODEL_WRITER lease取得とbusy時のFIFO
continuation登録を不可分に行う実装語である。「deferred task」は既存coordinatorが保持する
process内FIFOの実装語であり、永続化されたホームレイアウトまたはrecovery pointではない。

## Behavior scenarios

### Scenario: organizer lease中の空予測によるhotseat復旧

Given organizer apply または自動recoveryが `ORGANIZER` leaseを保持している

And `HotseatPredictionController.setPredictedItems` が空のprediction batchを受ける

When `HotseatRestoreHelper.restoreBackup` のMODEL_EXECUTOR taskがatomic admissionを試みる

Then taskはMODEL_WRITER leaseを待機せず、既存FIFOへMODEL_EXECUTOR continuationを登録して
直ちに戻る

And exact organizer tokenで開始された相関LoaderTaskは、organizer lease解放前に
`OrganizerModelReloadAdapter.Outcome.COMPLETED` を返せる

And lease解放後、helper transactionはMODEL_EXECUTOR上でFIFO順に一度だけ実行される。

### Scenario: gate直後にorganizer leaseを取得するadmission race

Given Hotseat helperがMODEL_EXECUTOR上で実行待ちであり、atomic admission直前のtest barrierで
停止している

And gateを評価した時点ではcoordinatorにholderがいない

When 別threadが `ORGANIZER` leaseを取得してからbarrierを解放する

Then helperはleaseを持たないまま `newTransaction()` のblocking fallbackへ進まない

And atomic admissionはbusy holderを観測してMODEL_EXECUTOR continuationをFIFOへ登録する

And organizerのexact-token correlated LoaderTaskはouter lease解放前に進行する。

### Scenario: contentionのない通常のhotseat更新

Given coordinatorにholderがない

When `createBackup` または `restoreBackup` のMODEL_EXECUTOR taskがatomic admissionを試みる

Then coordinatorは同じcritical sectionでMODEL_WRITER leaseを取得する

And helperは取得済みleaseで既存のtransaction・commit・cache refresh・restore後の通常reloadを
一度だけ実行する

And taskは外部leaseをsynchronously waitしない。

### Scenario: busyとなったhelper continuationの再実行

Given Hotseat helper taskがbusy holderの後ろにdeferされている

When holderがleaseを解放する

Then FIFO entryはDB bodyではなくMODEL_EXECUTORへのcontinuationを一度だけ実行する

And continuationは再度atomic admissionを試み、取得成功時だけDB bodyを実行する

And別のholderが先に取得されていた場合も、再びFIFOへ戻り、blocking waitまたはduplicate DB workを
行わない。

### Scenario: deferred helperの失敗または空のbackup table

Given helper taskがdefer済みである

When lease解放後にbackup tableが存在しない、またはhelperのDB処理が例外となる

Then helper自身の既存transaction/exception意味論を保持し、partial commitを成功と誤認しない

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
- coordinatorのdeferred FIFO、admission lease、MODEL_EXECUTOR continuationはいずれも
  一時的なprocess内状態であり、新しい永続queue、identity、retention、migrationを持たない。
- schema migration、ZIP/Android backup format、recovery pointの内容は変更しない。
- Hotseat helperが操作する配置アイテムの集合や、organizerの対象集合・lock状態・
  revision不変条件は変更しない。
- helper taskはatomic admissionで取得したouter MODEL_WRITER leaseの下で既存
  `ModelDbController.newTransaction()` を呼ぶ。既存のsame-thread MODEL_WRITER reentryが
  inner transaction leaseを供給し、outer leaseはwrapperの `finally` で閉じる。新しい
  transaction overloadは追加しない。admissionだけをatomicにし、DB mutation、commit、
  rollbackの意味論を変更しない。

## Permissions, privacy, and security

None。新しいpermission、外部通信、telemetry、個人データ、exportは追加しない。
既存のローカルLauncher DBとprocess内coordinatorのみを使用する。

## Accessibility and localization

UI文字列、focus、TalkBack、font scaling、翻訳対象の変更はない。失敗時のユーザー向け
messageも変更しない。

## Acceptance criteria

- [ ] **AC-156-01**: `HotseatRestoreHelper.createBackup` と `restoreBackup` の各MODEL_EXECUTOR DB taskは、`LayoutWriteCoordinator` の同一critical sectionで、取得済みMODEL_WRITER leaseによるtransaction実行またはFIFOへのMODEL_EXECUTOR continuation登録のいずれかを完了する。tokenless helperは `acquireBlockingQuietly` を呼ばず、外部leaseをsynchronously waitしない。
- [ ] **AC-156-02**: gate時にholder無し、executor実行直前に別threadがorganizer leaseを取得するdeterministic raceを含むinstrumentation scenarioで、helperはbusy FIFOへ退避し、exact-tokenの相関reloadがouter lease解放前に `COMPLETED` となる。sleepまたは10秒timeoutを成功oracleに使わない。
- [ ] **AC-156-03**: deferされたHotseat continuationはholder解放後にMODEL_EXECUTORへ一度だけ再投入され、取得成功時にのみDB bodyを一度実行する。再投入とDB bodyの間に新たなholderが現れても再deferされ、task例外またはbackup table不在が後続FIFO drainを妨げない。
- [ ] **AC-156-04**: coordinator競合がない場合、`createBackup` はbackup table作成とcache refreshを、`restoreBackup` は既存restoreと通常reloadを保持する。新たな例外、busy結果、public product API、schema変更を導入しない。
- [ ] **AC-156-05**: executable writer inventoryは `quickstep/src` を監査対象とし、Hotseat helperのtransaction経路を明示的なlease/admission理由とともにallowlistで管理する。inventoryの責務はwriter存在・allowlist登録の検出とし、atomic gateが除去されていないことはAC-156-01/02のfocused testが担保する。
- [ ] **AC-156-06**: fresh default workspaceでのmanual apply/recoveryのdevice evidenceは、recovery legがtokenless Hotseat taskにより10秒の `MODEL_RELOAD_FAILED` timeoutへ至らないことを示す。#155のlayout-overlap結果は別原因として併記する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-156-01 | concrete helperのMODEL_EXECUTOR taskを、admission直前・busy登録後・transaction body開始のlatchで観測するinstrumentation test。テスト用observerは旧direct transaction pathがblocking admissionへ入った事実も観測し、pre-fixでredとなる。 |
| AC-156-02 | helper taskをadmission直前barrierで止め、別threadでorganizer leaseを取得してから解放する。busy FIFO登録・MODEL_EXECUTOR returnを順序oracleとし、その後の `OrganizerModelReloadAdapter` がpre-releaseで `COMPLETED` となることをassertする。 |
| AC-156-03 | helperのnormal/deferred/redeferred実行をentry IDまたはlatchで数え、lease解放後の一回のMODEL_EXECUTOR hand-offと一回のDB bodyをassertする。backup table不存在とthrowing entryでは既存 `LayoutWriteCoordinatorTest` のFIFO isolation coverageを組み合わせる。 |
| AC-156-04 | 対象helperの非競合fixture DBでbackup tableの存在、cache refresh、restore後のreload要求を観測し、既存semanticsとの差分がないことを確認する。 |
| AC-156-05 | `python3 tools/repo-contract/validate_writer_inventory.py` の成功と、quickstep内の未allowlisted `ModelDbController` transaction callerを検出するchecker regression/self-check。atomic admissionの構造検証はこのscannerへ委譲しない。 |
| AC-156-06 | API 36 clean emulatorでfresh installをHOMEとして実行し、supported export/logcat/thread dumpでrecovery reloadの開始・完了とterminal outcomeを記録する。成功はA8自体ではなく、timeout-starvation不在で判定する。 |

## Open questions

実装を妨げる未決定の製品・設計判断はない。#155のQSB-overlap修正は
AC-156-06のlayout結果に影響し得る外部依存だが、本Issueのexecutor進行oracleとは
独立である。そのため本Specは #155 のmerge状態を明示してdevice evidenceを記録する。

`quickstep/src` 拡張で新たに検出されたpathは、同一のtokenless MODEL_EXECUTOR blocking-
admission原因である場合のみ本Issueに追加する。その他のpathはassessmentへ根拠を記録し、
新規Issueへ分離する。このstop conditionはscopeを拡大せず、inventoryをfail-openにしない。

## Source evidence

| Source | Relevance |
|---|---|
| [Issue #156](https://github.com/nunu1733/NunuLauncher/issues/156) | 症状、thread dump、制約、受入条件の正本。 |
| [Issue #156 review](https://github.com/nunu1733/NunuLauncher/issues/156#issuecomment-5432714104) | `runOrDefer` の判定後race、inventoryの責務境界、quickstep監査のscope stop conditionを指摘したレビュー。 |
| [Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150) | #156分離の根拠、#155との因果分離、device evidenceの前提。 |
| [Spec #60](../60-executor-writer-admission-audit/spec.md) | tokenless MODEL_EXECUTOR workの既存writer-admission契約。 |
| [Spec #13](../13-safe-layout-application/spec.md) | A7のcorrelated reload・DB/model convergence・失敗時recovery契約。 |
| [Spec #58](../58-serialize-runtime-restores/spec.md) | organizer/restore-family lease中の既存defer意味論。 |

## Change history

- 2026-08-27: #156用のDraftを作成。#150から分離されたexecutor starvationのみを対象化し、#155のlayout-overlap原因を非対象として明記した。
- 2026-08-27: Reviewに対応。admission判定後raceを閉じるatomic lease-or-FIFO operation、raceを再現するdeterministic oracle、inventoryの限定責務、quickstep監査のstop conditionを追加した。
- 2026-08-27: Re-reviewに対応。transaction seamをPlanと整合させ、outer MODEL_WRITER leaseの下で既存 `newTransaction()` のsame-thread reentryを用いること、新しい `ModelDbController` / `SQLiteTransaction` overloadを追加しないことを明記した。Reviewの受入判断によりstatusを `accepted` へ遷移した。
