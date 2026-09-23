---
issue: "#352"
status: draft
requirements: []
risk: []
updated: 2026-09-23
---

# Exchange受領テストのscreen状態レースを撤去し、CI上で決定的に成功する

## Problem

[Issue #352](https://github.com/nunu1733/NunuLauncher/issues/352) が、`ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` がCI上で稀に失敗することを報告した。証跡は [CI run 35276653532](https://github.com/nunu1733/NunuLauncher/actions/runs/35276653532) (PR #350の初回run、head `5f735526b369686a668ea4568928cf6d6c069e16`、失敗jobのrerunで成功。#350変更file外の既存テスト):

```text
java.lang.ClassCastException: ExchangeScreen$ImportOutcomeScreen cannot be cast to ExchangeScreen$Importing
    at ExchangeFlowStateHolderTest.kt:640
```

当該revision (2026-09-19に `git show 5f735526b3` で確認) の640行目は、対象テスト内の即時cast `holder.screen as ExchangeScreen.Importing` そのものである。

旧snapshot `814ec8e074f92972a628bdf574db8aba9ae8c6af` のre-entry ruleに従い、2026-09-23に最新Issueコメントと現行 `origin/main` (`17d883a012d66833244d10c00d551215064c35d9`) を再取得した。前回レビュー時のmain `1285c13cc6` からさらに進み、対象source/testとspec 328/332が変更されている。現行コードでrace機構を再評価した結果を [plan.md](./plan.md) に記録する。

Root causeの機構は失敗stack traceと現行コード読解で引き続き成立する (実装詳細は [plan.md](./plan.md) 参照):

1. テストは `newHolderWithRecordedScope` fixture (UI settle先として `uiDispatcher = Dispatchers.IO` を注入) を使い、`importFromClipboard` を呼ぶ。
2. `importFromClipboard` → `receiveAndImport` は呼出thread上で同期的に `screen = ExchangeScreen.Importing(reply)` を設定し、`import(reply)` がholder scope (`Dispatchers.IO`) へvalidation tailをlaunchする。
3. 未一致reply (`unmatchedMarkedReply`) のtailは `store.load` → `ExportMismatch` 失敗となり、現在のattemptが有効なら `settleImport` が `uiDispatcher = IO` 上で `screen = ImportOutcomeScreen(outcome, rawText = reply)` へ置換する。このsettleはテストthreadの即時castと競合し、CI実行環境ではsettleが先に完了してcastが失敗する。ローカル再実行では競合が滅多に成立しない (Issue記載の同filter + `--rerun-tasks` 5回は全成功)。

同一fixture・同一受領helperを使う兄弟テスト `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` (`onFileRead` 経由) は、同一の即時castパターンを持ち、機構上同一のレースに暴露されている (CI観察は未記録だが、構造は同一である)。 Observabilityの性質上、CI観察の有無はこのテストが安全であることの根拠にならない。

このテストがpinする振る舞いは [spec 332](../332-exchange-import-input-ui/spec.md) (implemented) の受入条件のoracleである: AC-1 (clipboard 1操作 → 共通import path)、AC-2 (file 1操作 → 共通import path)、AC-3 (clipboard/file読込が既存内容を置き換える)、AC-5 (全sourceが同一共通import path)。フレーク撤去はこのoracle守備範囲を維持したまま行う必要がある。

## Outcome

2つの受領テスト (clipboard / file) は、coroutine schedulerや実行環境のtimingに依存せず、CI上で決定的に成功する。テストがassertする内容は現行どおりであり、検証強度は落ちない:

1. 受領1操作で読み取ったtextがエディタ内容を置き換え、共通import pathの入力になること。
2. 追加のimport押下なしに共通import path (`store.load` 到達) が開始すること。

## Re-entry findings

2026-09-19の最新review comment ([Changes requested](https://github.com/nunu1733/NunuLauncher/issues/352#issuecomment-5741518282)) の3指摘を次の契約に反映する。

- `FakeStore.loadGate` の現行5秒timeoutを決定性の根拠に使わない。対象2テストでは、明示解放までtimeoutなしで保持するtest-only gateを使う。
- `ImportOutcomeScreen.rawText == reply` はeditorの置換を単独では証明しないため、終端状態assertのみの案を適合解として認めない。editor置換はgate保持中の `Importing.replyText == reply` で直接assertする。
- Issue本文の「CI上で連続成功」を維持し、同じimplementation PRの同一head SHAに対するCI成功を3回連続で終了条件にする。失敗またはsource jobのskipは連続回数を0へ戻す。

現行 [spec 328](../328-exchange-import-success-state/spec.md) はaccepted revision 2でdurable import lifecycleとattempt anchorを持つが、このfixtureは `ExportMismatch` でvalidationが失敗し、durable success保存経路へ入らない。receiptから失敗settleまでのraceは残る。[spec 332](../332-exchange-import-input-ui/spec.md) は #373 によるT-17/T-18責務境界を追記した一方、clipboard/fileの既存内容置換 (AC-3) と共通path (AC-1/2/5) は維持している。

## Scope

- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` のみの変更である:
  - 対象2テスト (`clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`、`fileTextReceiptRunsTheSameCommonImportPathInOneOperation`) の待ち合わせ・assert構造の修正。
  - 必要なら同test file内へのexplicit gate/helper追加と、terminal drain用 `awaitScreenOutcome` の再利用。
  - 同一レース様式を持つ他の即時castが無いことの監査結果を、対象テスト付近のcommentとして記録すること (実装者とreviewerが監査結論を同一file上で確認できるようにする)。

## Non-goals

- `ExchangeFlowStateHolder` を含むproduction sourceの挙動変更 (Issue本文のnon-goal)。production側のsettle seam・dispatcher構成は変更しない。
- 対象外のテスト (settleが走らない受領失敗系、既にpolling helperを使う成功系・outcome系) のstyle統一。
- 新規test framework / libraryの導入、CI workflowの変更。
- `ExchangeImportSurfaceInstrumentationTest` 等の他test seamへの本件の波及修正 (計測されたフレークは本test fileに局限される)。

## Observable behavior (テスト決定性の契約)

本specが「直すテスト」に要求する契約である。実装者とreviewerはこれで修正の適否を一意に判定できる:

1. **決定性 (必須gate方式)**: clipboard/fileの両テストは、受領を始める前にtest-onlyの明示解放gateを `FakeStore.load` に設定する。gateは `loadCalls` を記録した後で明示解放まで無期限に保持し、自動timeoutでsettleを進めてはならない。テストはgateへの到達を確認した後、解放前に `Importing.replyText == reply` をassertする。既存 `FakeStore.loadGate` の5秒timeoutだけでは本契約を満たさない。
2. **失敗時の解放とdrain**: gateは各テストの `try/finally` で必ず解放する。finally内でholderを期待するterminal state (`ImportOutcomeScreen`) までdrainし、assertion failureとdrain failureの両方がある場合は元のassertion failureを保持してdrain failureをsuppressed errorとして記録する。cleanup中の例外でもgate解放は先に完了する。
3. **終端状態assertの位置づけ**: gate解放後の `ImportOutcomeScreen.rawText == reply` は共通path入力の補助assertとして許容するが、それ単独でeditor置換を検証したものとは扱わない。終端状態pollingだけで受領2テストを置き換える案はspec不適合である。
4. **禁止様式**: 遷移途中の一時状態 (`Importing`) をpollingで待つ実装、既存 `awaitImporting` helperの受領系への転用、およびsettle順序を `Thread.sleep` で推測する実装は禁止する。
5. **検証対象の維持**: 各テストは (1) gate保持中のeditor置換 (`Importing.replyText == reply`)、(2) 共通import path開始 (`store.load` 到達と `loadCalls == 1`)、(3) 追加import押下なしの1操作性を維持する。terminal drain後にraw textが同一replyであることも確認する。
6. **2テスト同時修正**: clipboard受領とfile受領の両テストを同一のgate/assert/cleanup方針で修正する。片方のみの修正は行わない。

## Domain language

用語の追加・変更なし。本Issueで扱うのはテスト決定性であり、ドメイン挙動は不変である。

## Behavior scenarios

### Scenario: clipboard受領テストはsettleと競合しない

Given `newHolderWithRecordedScope` fixture、明示解放gate、未一致marker reply
When `importFromClipboard` を1回呼び、gateのentry通知で共通import path到達を確認する
Then load gateは明示解放されるまで保持され、clipboard受領中に `Importing.replyText == reply` をassertできる
And assert成功・失敗のどちらでもgateがfinallyで解放され、terminal stateまでdrainされる

### Scenario: file受領テストも同一方針で決定的である

Given 同一fixtureと未一致marker reply
When `onFileRead(FileExchangeRead.Text(reply))` を1回呼ぶ
Then clipboard受領と同一の明示gate・editor置換assert・finally cleanup・terminal drainが成立する

### Scenario: 修正によって検証が弱化しない

Given 修正済みの受領テスト
When 受領textの置換・共通import path開始・1操作性を確認する
Then 3点すべてが修正後のテストに残っており、spec 332 AC-1/AC-2/AC-3/AC-5のoracle守備範囲が維持されている
And `ImportOutcomeScreen.rawText` 単独をeditor置換の証拠としていない

### Scenario: 失敗時の振る舞い (テストが環境timingに戻った場合)

Given 修正後もCI上で対象テストが失敗した
When 失敗stackを調査する
Then 失敗原因が本specの決定性契約違反 (一時状態への無同期assert) か、新たな競合経路かを判別できる (失敗messageにassert対象状態と、その状態へ至った経路が記録されている)
And 新たな競合経路だった場合は本Issueへ証跡を記録し、specを更新してから次の修正を行う

## Data and state

None。テストのみの変更であり、永続化・migration・layout・DB への影響はない。test-only `FakeStore` に明示解放までtimeoutなしで保持するgateを追加または同等化する。

## Permissions, privacy, and security

None。production source・string・permission・通信を変更しない。テストfixtureはsynthetic identityのみを使う (既存どおり)。

## Accessibility and localization

None。UI・stringへ触れない。

## Dependency

- blockするdependencyなし。実行gateは既存の `organizer-unit-tests` CI job (`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`) である ([quality-strategy](../../docs/engineering/quality-strategy.md))。spec/plan-only PRでsource jobがskipされたrunは実装ACの証拠に数えない。
- 保護対象の正本: [spec 332](../332-exchange-import-input-ui/spec.md) (implemented、AC-1/AC-2/AC-3/AC-5)。参照のみで変更しない。

## Compatibility / migration

None。test fileのみの差分であり、production挙動・API・data形式は不変。rollbackは当該commitのrevertで完結する。

## Acceptance criteria

- [ ] AC-1: 対象2テストが、明示解放までtimeoutなしで保持するtest-only gateを `store.load` 到達前に設定し、gate保持中に `Importing.replyText == reply` をassertする。5秒timeout gateのみ、または終端状態pollingのみの案は不適合。
- [ ] AC-2: 契約3の検証対象3点 (置換・共通path開始・1操作性) が修正後テストに残っている。検証対象のdropがない。
- [ ] AC-3: diffが `ExchangeFlowStateHolderTest.kt` (および本spec/plan) のみであり、production sourceを含まない。
- [ ] AC-4: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolderTest'` の反復実行 (最低20反復) が全成功であり、かつ `--tests 'app.lawnchair.organizer.*'` gateと `./gradlew spotlessCheck` が成功する。
- [ ] AC-5: Issue本文の「当該テストがCI上で連続成功する」を満たすため、同一implementation PRの同一head SHAで `ci.yml` が3回連続 `pull_request` eventとして成功する。各runで `organizer-unit-tests` がskipされず成功し、`final-status` も成功すること。workflow rerunは同一SHAの1回として数える。失敗、job skip、head SHA変更は連続回数を0へ戻す。3 runすべてのURLとhead SHAをPR handoff packetに記録する。

## Unresolved decisions

- 本specは `status: draft` である。Ownerのacceptance記録まではimplementation-readyではない ([github-workflow](../../docs/project/github-workflow.md) のbug種別start gate)。
