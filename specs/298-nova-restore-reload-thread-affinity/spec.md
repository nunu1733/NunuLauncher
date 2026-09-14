---
issue: "#298"
status: draft
requirements: [TA-AC-01, TA-AC-02, TA-AC-03, TA-AC-04, TA-AC-05, TA-AC-06]
risk: []
updated: 2026-09-14
---

# Nova restore後のworkspace reloadがLauncherのthread-affinity契約を守って完了する

## Problem

Nova backup restoreは、Looperを持たない専用thread（`NovaBackupRestore`、
[lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)
の `restoreDispatcher`）上で実行される。その後続のlauncher model/cache reloadは、
thread-affinity要件が異なる複数のLauncher API（icon cache、model loader、Handler生成を
要求するcomponent）へ到達する。restore/reloadの窓で、restore threadとmodel reload側の
どちらかが契約に反するthreadからこれらのAPIへ到達すると、実行時にassertion違反や
Handler生成失敗が発生し、workspace loadingが中断されるか、完了状態が不確実になる。

実機での観測（#287 T4セッション、Pixel 9a / Android 17、build `15.Dev.(d0f4044)` =
main `d0f40446c7`、2026-09-12記録）:

```text
IllegalStateException: Cache accessed on wrong thread
  （BaseIconCache.assertWorkerThread → LoaderTask.loadWorkspaceImpl 経由のfull stack、
    coroutine継続frameは NovaBackupConverter.kt:167）
Can't create handler inside Thread[NovaBackupRestore]
Desktop items loading interrupted
```

このセッションでは復元自体はpref commitとgrid切替
（`launcher_5_4_4.db` → `launcher_5_5_5.db`）まで到達している。障害はセッション依存
（同日同buildの他セッションは同一手順で成功）であり、レース性を含む。

**観測と現行mainの関係（2026-09-14再確認）。** `d0f40446c7` は現行main
（`9821dec073`）の祖先であるが、2026-09-12時点と異なり、両者間の差分はNova
restore/model reload pathの**本体そのものを変更している**。#299の修正
（PR #314、`9821dec073`にmerge）は、restore完了部のreload発行を
`reloadAfterRestore` → `forceReload` から、generation identity付きの
`RestoreReloadBarrier` → `LauncherModel.dispatchRestoreReload`（loader起動を
`MAIN_EXECUTOR`へdispatch、`startLoaderWithoutCallbacks`へUI thread事前条件を追加、
reload完了のcompletion barrierを導入）へ再構成した。これは本Issueが扱う
restore/reload窓そのものへの変更である。よって上記観測が現行mainの構造に
そのまま妥当するとは **仮定できない**。Phase 1（investigation）の最初の成果は、
3つのsignatureが現行mainで依然として生じうるか、生じる場合のcall chainが
観測時と同一かを確定することである（`docs/assessment/issue-299-nova-restore-capture-invalid.md`
は、#299のfixがactual wrong-thread pathを捕捉・修正しなかったことを明示的に
記録している）。

#168はreload-after-restoreの `IconCache` wrong-thread問題を既に記録し、その修正を
このIssueへ分離している。#58と#168のrestore serialization / authoritative restoreの
修正後も、restore threadとreload側のthread-affinity違反は残存している（観測時点で）。

## Outcome

Nova restore完了後のmodel/cache/reload処理が、各Launcher APIが要求するthread上で
実行される。restoreの繰り返し検証でwrong-thread assertion、Handler生成失敗、
workspace loading中断が一切発生せず、restore後にworkspaceが使用可能になる。
thread-affinity assertionは抑制されず、契約違反の検出器として残る。

## Scope

配信は2 phaseで行う。**Phase 1はinvestigation（調査）のみ**であり、production
code変更を含まない（計画module変更・test追加も原則Phase 2へ回す）。Phase 2の
fix architectureはPhase 1のdecision gateを通過するまで決定しない。

- Nova restore（`NovaBackupConverter.convertAndRestore`）の完了から、correlated
  reload（`RestoreReloadBarrier` → `LauncherModel.dispatchRestoreReload` →
  `MAIN_EXECUTOR`上の `startLoader` / `startLoaderWithoutCallbacks`）とその後の
  LoaderTask / icon cache / UI bindingに至る経路全体のthread handoffの明示化。
  model非活性時のbaseline fallback（`reloadAfterRestore` no-op）を含む。
- 観測された3つの障害signature（wrong-thread cache access、restore thread上の
  Handler生成失敗、workspace loading中断）に対応する、正確なwrong-thread call
  chainの特定。#299再構成後の現行mainでchainが実在するかの確認（Phase 1の
  最初の成果）を含む。
- #299が導入したbarrier構造（restore thread上での `mLock` 保護下の
  `stopLoader` / token swap、`MAIN_EXECUTOR` dispatch、deadline超過時のrestore
  失敗throw）がthread-affinity契約と整合することの確認。barrierがreloadの
  詰まりを「静かな中断」から「明示的なrestore失敗」へ変えるため、本Issueの
  障害signatureの現れ方が変化しうる点をinvestigationで扱う。
- 中断されたreloadがpartially initializedなmodel/cache状態を後続操作へ残すかどうかの
  確認と、必要な場合の状態整合の保証。#299のassessmentが修復点中断の残存観測
  （invalid行の持続とsettle後の修復）を記録済みであり、これを入力として
  継承する。
- restore完了とlauncher lifecycle / model reloadの順序の明示化（2つのreload
  generationが競合しないこと）。既存の#58 restore-family lease、#14 loader
  deferral、#152 reload generation token、#299 restore reload tokenの上に構成する。

## Non-goals

- assertionの除去・抑制、例外のcatch-and-ignore。thread-safety契約は保存する。
- #299（Nova restore直後のOrganizer `CAPTURE_INVALID` 恒常化）。そのfixは
  PR #314としてmerge済み（`9821dec073`）だが、同assessmentはactual wrong-thread
  pathの再現・修正を行わず、本Issueと同一seam（restore/reload窓）への変更を
  意図的にexcludeした。#298と#299の因果関係は引き続き未確立であり、#298の修正は
  #299を解決すると仮定しない。逆も同様。
- #287（grid変更時の `CAPTURE_UNKNOWN_LOCK` 恒常化）。
- ZIP backup restore path（`LawnchairBackup` 経由）の振る舞い変更。reload機構が共有される
  場合でも、本Issueの適用条件はNova restore経路で検証する。
- restore/migrationのfail-closed挙動（#167）や `LayoutWriteCoordinator` のlease契約の
  変更。thread handoffは既存lease契約の内側で行う。
- #299が確立したbarrier契約（successful completionのみを成功とし、deadline超過で
  restoreを失敗させる設計）の変更。本Issueはその内側のthread handoffを対象にする。
- grid適用の順序・耐久性（#168で確立済み。`applyConvertedGrid` のMain thread hopを含む）。

## Domain language

- **Restore thread**: `NovaBackupConverter.restoreDispatcher` の単一thread
  （`NovaBackupRestore`）。Looperを持たない。
- **Model worker thread**: `MODEL_EXECUTOR` のLooper thread（`launcher-loader`）。
  LoaderTaskの実行threadであり、`IconCache`（iconloaderlib `BaseIconCache`）の
  `mBgLooper` でもある。
- **Thread-affinity契約**: APIが呼び出し側threadに要求する制約。iconloaderlibの
  `BaseIconCache.assertWorkerThread` は無条件に_throw_し、Launcher3の
  `Preconditions.assertWorkerThread` はstudio buildでのみ検査する。両者の強度の違いは
  検証設計に影響する。
- **Restore reload completion barrier**: #299が導入した、restoreに対応するmodel
  reload generationのterminalな成功完了を観測する機構
  （`NovaBackupConverter.RestoreReloadBarrier` + `LauncherModel`の
  `RestoreReloadRequest` token）。#299 specのcompletion barrier定義と同一である。

## Behavior scenarios

### Scenario: Nova restore → reload がthread違反なしに完了する

Given 初期状態から有効なNova backupを用意する
When 設定からNova backup restoreを実行し、restore → restored DB/profile commit →
  launcher model reload（completion barrier到達）→ workspace表示まで完了する
Then workspaceが復元内容で使用可能になる
And logcatに `Cache accessed on wrong thread`、`Can't create handler inside
  Thread[NovaBackupRestore]`、`Desktop items loading interrupted` が一切出現しない
And thread-affinity assertion（`assertWorkerThread` 等）がコード上に残っている

### Scenario: 繰り返しrestoreでも障害が再現しない

Given 同一環境でNova restore → 削除 → restoreを繰り返す
When 複数回のrestore/reload cycleを実行する
Then 全cycleで上記scenarioと同一の成功結果が得られる
And 障害が特定セッションに依存しない（レース性の排除を繰り返し実行で確認する）

### Scenario: reloadの中断が不確実な状態を静かに残さない

Given restore直後のreloadが進行中である
When reloadが何らかの例外や中断に遭遇する
Then 中断が diagnostics/log に説明として現れる
And model非活性でない限り、completion barrierが完了を観測できないままrestoreが
  成功returnすることはない（barrierがdeadline超過を明示的なrestore失敗として
  surfaceする。#299契約の維持）
And 後続のreload（process再起動後を含む）が中断状態を引き継がず正常に完了する

### Scenario: organizer application不在時のbaseline fallback

Given organizer applicationが存在しない（`LauncherAppState.getNoCreate()` がnull）
When Nova restoreがreload完了部に到達する
Then reloadは `reloadAfterRestore` のno-op baseline fallbackであり、restore自体は
  既存どおり完了する（#58 baseline fallbackの維持。barrierはdispatchされない）

### Scenario: thread違反が実在した場合は検出され続ける

Given thread-affinity契約に反する呼び出しが将来のコード変更で再導入される
When 該当APIが契約に反するthreadから呼ばれる
Then `BaseIconCache.assertWorkerThread` 等のassertionが引き続きthrowする
And 修正・検証はassertionの弱化や例外の握り潰しによって成立しない

## Data and state

- schema変更、migration、`favorites` の書式変更はしない。staging DB
  （`restored.db` → grid DB）の経路と#168のauthoritative restoreの構造は現状維持。
- 本Issueの修正がlauncher DBへ直接書き込むことはない。DB適用は既存の
  BACKUP_RESTORE/RESTORE lease下の既存moduleが担う。ホームレイアウト安全規約の
  snapshot/transaction/recovery point要件は既存実装が満たすものを変更しない。
- model/cacheのin-memory状態について、reload中断時の残存状態の正本観測（何が残り、
  何が無効化されるか）をinvestigationで記録する。#299 assessmentの修復点中断観測
  （定点1/2の `widgetIdNegative=1 / restored=7`、中断generation後もinvalid行が残存、
  settle到達後は修復）を本Issueのinvestigationの入力とし、actual wrong-thread
  pathでの追試を行う。

## Permissions, privacy, and security

- None。新規permission、外部通信、sensitive dataの追加はない。復元ログにuser layout
  contentを書かない（既存のdiagnostics契約に従う）。

## Accessibility and localization

- None。本Issueは復元UIの文言・操作に触れない。既存のrestore UXは変更しない。

## Acceptance criteria

- [ ] TA-AC-01: 観測されたwrong-thread call chainが正確に特定され、stack/traceの証跡と
  ともに記録されている（3つのsignatureそれぞれの出所が説明できること）。#299再構成後の
  現行main（Phase 1実施時のhead）でchainが実在すること、または障害窓が取り除かれた/
  変化したことが証跡とともに区別して記録されていること。
- [ ] TA-AC-02: Nova restoreがLauncher cache/model APIを許可されないthreadから
  アクセスしない。修正は、該当処理を契約上要求されるthread（model worker thread /
  main thread / restore threadのそれぞれ）へ明示的にdispatchする形で行われる。
- [ ] TA-AC-03: 繰り返しのrestore/reload検証で、`Cache accessed on wrong thread`、
  `Can't create handler inside Thread[NovaBackupRestore]`、workspace loading中断が
  一度も発生しない。
- [ ] TA-AC-04: restore後のworkspace/model reloadが正常に完了し、workspaceが使用可能
  になる。検証はrestore reload completion barrierの完了を待って行う（barrier前の
  一時状態を完了扱いしない。#299のbarrier定義と同一の観測signal）。
- [ ] TA-AC-05: thread-affinity assertionが保存されている（assertionの除去・抑制、
  例外のcatch-and-ignoreは行わない）。
- [ ] TA-AC-06: 実施可能な範囲でregression coverageが追加され、emulatorまたは実機での
  検証が行われている。障害が実restore/reload lifecycleと結合するため、device evidenceを
  要求する。

## Test oracle

| AC | Evidence |
|---|---|
| TA-AC-01 | 再現実行時のlogcat/stack証跡と、特定されたcall chainの記録（plan.md / `docs/assessment/issue-298-<slug>.md` / PR証跡） |
| TA-AC-02 | 特定chainに対する修正の構造的確認（thread hopの明示）+ TA-AC-03のruntime evidence |
| TA-AC-03 | emulator/実機での繰り返しrestore検証。logcatに3 signatureが不在であることの記録。可能ならinstrumentation assertion |
| TA-AC-04 | barrier完了後のworkspace表示とmodel reload完了の確認（#168の `NovaRestoreGridApplicationTest`、#299の `NovaRestoreCapture*Test` harness系instrumentation seamの拡張を含む） |
| TA-AC-05 | assertion箇所が削除・弱化されていないことのdiff reviewと、テストでの契約保持確認 |
| TA-AC-06 | 追加したautomated regression + emulator/実機検証の実行記録 |

## Open questions

- 3つの障害signatureを生む正確なcall chain（TA-AC-01）。#299再構成後の現行mainで
  chainが実在するか（観測build `d0f40446c7` と現行mainの間でrestore/reload窓が
  再構成されたため）。静的読解ではrestore threadから `BaseIconCache` への直接到達点は
  確認できないため、runtime reproductionによる特定が必要。plan.mdのPhase 1
  investigation planを参照。
- 中断されたreloadがpartially initializedなmodel/cache状態を残すかどうか。残す場合、
  その状態が後続のrestore/organizer/capture操作へ影響するか（#299との関係の切り分けを
  含む。#299 assessmentの修復点中断観測が出発点）。
- barrier導入後の障害signatureの現れ方。reload generationが反復的にcancelled/
  timeoutになる場合、障害は「静かな `Desktop items loading interrupted`」から
  「barrier deadline超過による明示的なrestore失敗」へ変化するか。その場合の
  logcat証跡の読み替えをinvestigationで確定する。
- 実emulator環境での再現手順の確立（実機観測はセッション依存・レース性のため）。

## Change history

- 2026-09-12: Draft created for #298（snapshot commit `20a357b7db32`、baseline
  `f9afd8bfde12`）。
- 2026-09-14: Re-entry ruleに従いbaselineを現行main `9821dec073` へ更新。#299 fix
  （PR #314）のmergeによりrestore/reload窓が再構成されたため、Problem/Scopeに
  「観測が現行mainに妥当するとは仮定できない」ことを明記し、barrier後の障害signature
  の変化をinvestigation対象へ追加。reload発行pathの記述を `dispatchRestoreReload`
  構成へ同期。Phase 1（investigation）/ Phase 2（fix）の配信構造を明示。
