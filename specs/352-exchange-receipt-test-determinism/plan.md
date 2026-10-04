# Implementation Plan: #352 Exchange受領テストのscreen状態レース撤去

---
issue: "#352"
status: draft
updated: 2026-09-23
---

> Status: **draft** — specが `draft` である間は実装を開始しない。2026-09-23 re-entryで最新Issue本文と2件の全コメントを取得し、`origin/main` `17d883a012d66833244d10c00d551215064c35d9` をfetchした。旧snapshot `814ec8e074f92972a628bdf574db8aba9ae8c6af` はmainの祖先ではなく、merge-baseは `3076bdae7ebf8dbb086f251203968c06e9986258` (`git rev-list --count 814ec8e074f92972a628bdf574db8aba9ae8c6af..origin/main` = 241)。本planは現行コードで再確認したroot causeとtest-only修正を記録する。production/test実装はaccepted spec、またはgithub-workflowのbug start gateを満たす追跡済みbug oracleとowner acceptanceが揃うまで開始しない。

## Current evidence (`origin/main` `17d883a0`、2026-09-23)

### 対象コード

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  - `ExchangeFlowStateHolder` は引き続き `settleDispatcher` / `uiDispatcher` をinjectでき、JVM fixtureは両方に `Dispatchers.IO` を使う。
  - `importFromClipboard` / `onFileRead(Text)` は同じ `receiveAndImport` へ入り、envelope通過後に呼出thread上で `screen = ExchangeScreen.Importing(text)` を設定する。
  - 現行 `import` は `beginImportAttempt` からattemptを確定して `scope.launch(Dispatchers.IO)` を開始し、`controller.importReply` 後に `withContext(uiDispatcher)` でsettleする。
  - `settleImport` は現行attempt tokenを確認する。fixtureの未一致replyは `ExportMismatch` となり、stale扱いでなければ **`screen = ExchangeScreen.ImportOutcomeScreen(outcome, rawText = replyText)`** に置換する。
  - #374/#375でdurable pending intent store、attempt-fenced save、mutation gate、invalidation queueが追加された。ただし未一致replyはvalidation失敗なのでdurable success saveへ入らない。fixtureに既存recordや入力変更もなく、active attempt guardはsettleをdropしない。
- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt`
  - `newHolderWithRecordedScope` は `CoroutineScope(Dispatchers.IO + handler)`、`settleDispatcher = Dispatchers.IO`、`uiDispatcher = Dispatchers.IO` のまま。settleはreceipt test threadと並行して進み得る。
  - 対象clipboard/fileテストはどちらも受領呼出直後に `holder.screen as ExchangeScreen.Importing` を行い、その後で `awaitStoreLoad` を呼ぶためcast時点では共通path開始を待っていない。
  - test-only `FakeStore.loadGate` は `loadCalls++` 後に `await(5, TimeUnit.SECONDS)` する。5秒後に自動解除されるため、assert windowを構造的に固定するgateにはならない。
  - `awaitScreenOutcome` はterminal `ImportOutcomeScreen` を最大5秒pollする。`awaitImporting` は遅延settle失効後の安定状態を待つ既存helperであり、一時的なreceipt stateには使わない。
  - 現行fileの全 `as ExchangeScreen...` castを再走査した。対象2件以外は、同期typed failure、terminal state用poll helper、attempt invalidation後の安定screen、または既に採用済みのterminal stateを使うテストであり、同じ「receipt後の即時Importing cast」形は見つからなかった。

### Root cause (確定済みの機構)

1. CI stack traceの `ExchangeScreen$ImportOutcomeScreen cannot be cast to ExchangeScreen$Importing` は、`settleImport` の失敗branchが既に走って `ImportOutcomeScreen` へ置換済みのscreenへの即時castで説明できる。対象fixtureでscreenを非同期に置換する経路は `settleImport` のみであり、他のmutator (`openImport` / `close` / `discardImport` 等) はassert window内で呼ばれない。
2. CI run 35276653532 (head `5f735526b3`) における失敗行640は、当該revisionの対象テストcast行である (2026-09-19に `git show 5f735526b3` で確認)。re-entry時点のcurrent `origin/main` では同castは行807にある。
3. 競合の成立条件: `import()` がlaunchしたIO coroutineが `importReply` を完了させ、`withContext(Dispatchers.IO)` への継続がテストthreadのcastより先に走ること。IO poolは複数threadであり、CI実行環境ではこれが稀に成立する。現行のattempt token guardは、fixtureでattemptがactiveなままなので失敗settleを拒否しない。durable save/mutation gateはvalidated branchにだけ関係し、今回の `ExportMismatch` failure branchは通らない。ローカルの同filter + `--rerun-tasks` 5回 (Issue記載) では成立しなかった、という観察と整合する。
4. `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` は同一fixture・同一 `receiveAndImport` 経路であり、機構上同一の競合に暴露される。

### 対象テストが検証する内容 (維持対象)

- 受領textによるエディタ置換: `receiveAndImport` が採用したtext (= `import(replyText)` へ渡る `replyText` = `settleImport` の `rawText` としても生存) が `reply` と一致する。
- 共通import pathの開始: pipeline decode後の `store.load` 到達 (`FakeStore.loadCalls == 1`)。
- 1操作性: `openImport()` 以外にimport pressを伴わない。

これらは [spec 332](../332-exchange-import-input-ui/spec.md) AC-1/AC-2/AC-3/AC-5のholder unit test oracleである。

### 他の即時castの監査 (origin/main `17d883a0`)

現行test fileの全 `as ExchangeScreen...` occurrenceを検索し、周囲のtest/helperを読んだ。行番号は後続編集で動くためmethod名で分類する。

| Test/helper group | 判定 |
|---|---|
| `clipboardEmptyReadKeepsTheScreenAndSetsTheTypedStatus`, `clipboardNonTextReadKeepsTheScreenAndSetsTheTypedStatus`, `oversizedClipboardTextIsNotAdoptedAndReportsInputOversize`, `fileReadFailureSetsTheTypedFileStatusAndKeepsTheEditor`, `oversizedFileTextIsRejectedByTheSharedEnvelopeGate` | receipt/read failureは同期typed statusのみで、import coroutineを起動しない。`Importing` は安定。 |
| `clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`, `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` | 同一raceの対象。受領直後の`Importing` castを明示gate保持中のassertへ置き換える。 |
| `clipboardReceiptReachesTheParseFirstOutcomeWithEphemeralRawDiscardedOnRetry`, `fileReceiptReachesTheParseFirstOutcomeInOneOperation` | `awaitScreenOutcome` 後にterminal failure screenへcastする。 |
| `awaitImportSuccess`, `awaitImportPersistenceFailure`, `awaitImportReview` | helper自身が対応するterminal stateまでpollしてからcastする。 |
| attempt edit/clear/invalidation tests | editor変更がcurrent attemptを先にinvalidateする契約を対象にしており、遅延settleはscreenを置換しない。これらはreceipt後に有効attemptがsettleする2テストとは別の競合表面。 |
| stale settle / continuation tests | newer attempt tokenまたは既にpoll済みのsuccess stateをassertする。対象2テストと同じ即時receipt-to-outcome castはない。 |

### Re-entry delta review

旧snapshot `814ec8e074f92972a628bdf574db8aba9ae8c6af` はmainの祖先ではなく、mainとのmerge-baseは `3076bdae7ebf8dbb086f251203968c06e9986258`。`origin/main` `17d883a012d66833244d10c00d551215064c35d9` とのpath差分を確認した。

| Path | Current change and effect on #352 |
|---|---|
| `ExchangeFlowUi.kt` | attempt token、durable pending-import persistence、exchange mutation gate/invalidationが加わった。今回の未一致replyはvalidation failureなのでdurable success saveを通らず、active attemptがcurrentのまま `ImportOutcomeScreen` にsettleする。raceは残る。 |
| `ExchangeFlowStateHolderTest.kt` | durable lifecycle・invalidationのfixture/testが多数追加された。対象2 receipt testは依然として同期receipt直後に `Importing` をcastし、`FakeStore.loadGate` は5秒timeoutのまま。 |
| spec 328 | accepted revision 2へ改訂され、success lifecycleをdurable recordへ移した。attempt anchorとfailure settleの意味を確認したが、本fixtureのfailure pathやreceipt replacement oracleは変更されていない。 |
| spec 332 | #373でT-17/T-18責務境界が追記された。入力sourceの一操作path、既存editor内容のreplace (AC-3)、同一path (AC-1/2/5) は引き続き受入済みoracleである。 |
| `docs/project/github-workflow.md`, `docs/engineering/quality-strategy.md` | 旧snapshot以降の差分なし。bug start gateはaccepted specまたはrepository-tracked bug oracleとowner acceptanceを要求し、organizer source gateは `organizer-unit-tests` / `final-status`。docs-only PRではsource jobがskipされる。 |

前回のreview commentが `1285c13cc6` 時点で対象file未変更とした記録は、その後のmain進行を含まないため現状判断には再利用しない。Issue本文は変更しない。

## Ownership / module boundaries

- 変更はtest fileのみ。productionの `ExchangeFlowStateHolder` / `ExchangeFlowController` / pipelineへは触れない (Issue non-goal)。
- `FakeStore.loadGate` の5秒timeoutは他testの既存seamとして残す。受領2テスト用に、明示releaseまでtimeoutなしでparkする `receiptLoadGate` を同じtest fileのFakeStoreへ追加する。productionへ新しいtest hookを追加しない。

## Interfaces / seams

- `receiptLoadGate` は `entered` と `released` のlatchを持つtest-only gateとする。`FakeStore.load` は `loadCalls++` の後に `entered.countDown()`、続けて `released.await()` を呼ぶ。gateがentryを通知しても自動解除しない。test側の `awaitEntered` はpath未到達を検出するため有限timeoutを持てるが、release latchはtimeoutなしとする。
- assertion seamは `holder.screen` と `FakeStore.loadCalls`。`entered.await()` のCountDownLatch happens-beforeにより、`loadCalls` を増やしてloadへ入ったことを確認してから `loadCalls == 1` をassertする。Compose state fieldのreflectionは使わない。
- `awaitScreenOutcome` はgate解放後のterminal drainにだけ使い、一時状態の決定には使わない。

## Control / data flow (修正後の期待flow)

対象fixtureでの経路: receipt (test thread, `screen = Importing(reply)`) → `beginImportAttempt` → IO coroutine (`importReply`: prepare → `store.load`で `receiptLoadGate` にpark → `ExportMismatch`)。gate保持中にtestはeditor replacementと `loadCalls == 1` をassertする。`finally` がgateをreleaseするとpipelineが終わり、`uiDispatcher = IO` 上の `settleImport` が `ImportOutcomeScreen(rawText = reply)` に遷移する。flow自体とproduction codeは変更しない。

## Expected files to change

- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` — 対象2テストの修正、監査結論のcomment、必要ならtest file内helper。
- (本branchに既に含まれる) `specs/352-exchange-receipt-test-determinism/spec.md` / `plan.md`。

## Selected approach: explicit-release `receiptLoadGate`

`FakeStore.loadGate` は既存consumer向けに5秒timeoutのまま残す。対象2テストでは別のtest-only `receiptLoadGate` を追加し、そのrelease latchをtimeoutなしで待つ。5秒経過やscheduler delayでsettleが勝手に進む経路を作らない。

```kotlin
private class ExplicitReleaseGate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun park() {
        entered.countDown()
        released.await() // 明示releaseまで無期限に保持
    }

    fun awaitEntered() {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "store.load must enter the gate")
    }

    fun release() {
        released.countDown()
    }
}
```

`FakeStore.load` は `loadCalls++` の直後に `receiptLoadGate?.park()` を呼ぶ。各受領testはreceipt前にgateを登録し、gate entry後かつrelease前に `loadCalls == 1` と `holder.screen as? ExchangeScreen.Importing` / `replyText == reply` をassertする。ここでは一時状態をpollせず、gateが置換可能な失敗settle自体を停止する。

各testのassert windowは `try/finally` で囲む。finallyでは (1) 必ず `gate.release()`、(2) `awaitScreenOutcome(holder)` で期待するterminal failure screenまでdrain、の順に行う。bodyのassertionが失敗しdrainも失敗した場合はassertion errorを主例外として保持し、drain errorをsuppressed errorにする。これによりfailure pathでもbackground importをgate上に残さない。finally完了後に `ImportOutcomeScreen.rawText == reply` を補助assertし、screen replacement、1回の共通path、単一receipt操作の3点を検証する。

**Approach B (terminal state pollingのみ) は不適合である。** `rawText == reply` は `receiveAndImport` がpipelineへ渡した値を示すが、Compose editor stateへ `Importing(reply)` が採用されたことを示さない。たとえばeditor代入だけが欠落してもimport経路へreplyが渡ればこのassertは通り、Issue #352とspec 332 AC-3のoracleが弱くなる。よってterminal pollingはgate解放後のdrain・補助assertに限る。

既存 `awaitImporting` はattempt失効後の安定state用でありreceiptには転用しない。settle順を `Thread.sleep` で推測する変更も加えない。

## Testing strategy

- 直接確認: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolderTest'` (修正対象class)。
- 反復実行 (spec AC-4): 同filterを `--rerun-tasks` 等で最低20反復し全成功を記録する。race撤去の反証機会を増やす目的であり、通過自体は決定性の証明ではない (下記unverified参照)。
- gate: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`./gradlew spotlessCheck`。
- CI exit criterion: implementation PRの同一head SHAで、`ci.yml` の連続する3 execution attemptを `pull_request` eventとして成功させる。各attemptは `organizer-unit-tests == success` かつ `final-status == success` を満たし、source jobがskipされていないこと。attempt identityは `(run_id, run_attempt)` とする。同一 `run_id` のworkflow rerunでも `run_attempt` が異なれば別attemptとして数え、同一identityの重複計上は禁止する。失敗・job skip・head SHA変更で連続回数をresetする。3 attemptそれぞれのrun ID、run_attempt、URL、head SHA、結果をPR handoff packetに記録する。
- 失敗を再現するテストを書けない件 ([AGENTS.md](../../AGENTS.md) テスト規約の代替証拠): 対象は「テスト自体の非決定性」であり、production timingを改変しない限り失敗を決定的に再現するテストは作れない。代替証拠は (1) root cause機構のcode level裏付け (上記Current evidence)、(2) CI失敗の実在証跡 (run 35276653532、失敗行 = cast行の確認済み)、(3) 修正後テストの決定性に関する構造的議論 (assert対象が「置換されない状態」であること)、(4) 反復実行とCI greenをPRへ記録する。

## Execution checklist

1. 本specをdraftのままreviewへ提示する。本docs-only preparationではtest/production実装を開始しない。
2. 実装開始前に `github-workflow.md` bug start gateを満たす: accepted specとそのexact commit、またはrepository-tracked bug oracleとexact revisionをpacketに固定し、owner acceptanceを記録する。Issue/comment oracleだけを使う場合はpermalink・取得UTC時刻・owner acceptance linkを含める。
3. start gate後、対象2テストを同一の `receiptLoadGate` 方針で修正する。現行cast監査の結論をtest付近へ更新する。
4. `spotlessCheck` → 対象class → 最低20反復 → organizer unit-test gateの順に実行し、exact command/resultを記録する。
5. implementation PRは全AC未完了中は `Refs #352` を使う。AC-1〜AC-5を満たす最終PRだけが `Closes #352` を使い、AC-5の3つのCI run URL/head SHAをhandoff packetへ記録する。
6. 新しい競合経路や対象source変更が見つかった場合は、CI failure evidenceをIssueへ記録し、spec/planを実装継続前に更新する。

## Migration / rollback

- migrationなし。rollbackは当該commitのrevertで完結する (test fileのみ)。

## Failure handling

- `awaitEntered` timeoutは共通pathがgateへ到達しなかったことを示す。`finally` はreleaseしてからterminal drainを試し、assertion failureとdrain failureの両方を記録する。terminal drain timeoutはpipeline/settle未完了または新しいfailure branchを調査し、単にtimeout値を伸ばして閉じない。

## Risk

- 低。test-only差分であり、`risk:` label・高リスクgateの対象外である (高リスクpath一覧にtest fileは含まれない)。
- 残存risk: gate cleanupが壊れるとdispatcher threadをparkしたままにする。専用gateは対象testの `finally` で必ずreleaseし、terminal drainを同じcleanup pathに置く。

## Explicitly unverified areas

- **ローカルでの競合再現は未達成** (Issue記載の5反復も成功)。機構はCI証跡とcode読解で確認したが、「CI実行環境でsettleがcastに先行する」というtimingそのものはstack traceによる間接確認である。explicit-release gateではsettle完了前にeditor oracleを検査するため、自然raceの再現は不要。
- CI run 35276653532 以外の本フレーク観察の有無は調査していない (Issue・#350に記録があるのは1件)。
- 3回連続のsame-head CI成功はこのdraft workでは未取得。spec/plan-only PRでsource jobがskipされるため、implementation PRがsource pathを含んだheadに対して記録する。
- 現行コード確認は `origin/main` `17d883a012d66833244d10c00d551215064c35d9` に限定する。以後の実装再開時にmainが進んでいればre-entry reviewをもう一度行う。

## Re-entry rule

本planのre-entry baselineは `origin/main` `17d883a012d66833244d10c00d551215064c35d9` (2026-09-23 fetch) である。次回再開時はその時点のlatest `origin/main` と全Issueコメントを再取得し、`ExchangeFlowStateHolderTest.kt`・`ExchangeFlowUi.kt`・spec 332/328およびworkflow/qualityの差分を再確認してからplanを再評価する。
