---
issue: "#352"
status: draft
requirements: []
risk: []
updated: 2026-09-19
---

# Exchange受領テストのscreen状態レースを撤去し、CI上で決定的に成功する

## Problem

[Issue #352](https://github.com/nunu1733/NunuLauncher/issues/352) が、`ExchangeFlowStateHolderTest.clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` がCI上で稀に失敗することを報告した。証跡は [CI run 35276653532](https://github.com/nunu1733/NunuLauncher/actions/runs/35276653532) (PR #350の初回run、head `5f735526b369686a668ea4568928cf6d6c069e16`、失敗jobのrerunで成功。#350変更file外の既存テスト):

```text
java.lang.ClassCastException: ExchangeScreen$ImportOutcomeScreen cannot be cast to ExchangeScreen$Importing
    at ExchangeFlowStateHolderTest.kt:640
```

当該revision (2026-09-19に `git show 5f735526b3` で確認) の640行目は、対象テスト内の即時cast `holder.screen as ExchangeScreen.Importing` そのものである。

Root causeの機構はbaseline `3076bdae7e` のcode読解と上記CI証跡で確定している (実装詳細は [plan.md](./plan.md) 参照):

1. テストは `newHolderWithRecordedScope` fixture (UI settle先として `uiDispatcher = Dispatchers.IO` を注入) を使い、`importFromClipboard` を呼ぶ。
2. `importFromClipboard` → `receiveAndImport` は呼出thread上で同期的に `screen = ExchangeScreen.Importing(reply)` を設定し、`import(reply)` がholder scope (`Dispatchers.IO`) へvalidation tailをlaunchする。
3. 未一致reply (`unmatchedMarkedReply`) のtailは `store.load` → `ExportMismatch` 失敗となり、`settleImport` が `uiDispatcher = IO` 上で `screen = ImportOutcomeScreen(outcome, rawText = reply)` へ置換する。このsettleはテストthreadの即時castと競合し、CI実行環境ではsettleが先に完了してcastが失敗する。ローカル再実行では競合が滅多に成立しない (Issue記載の同filter + `--rerun-tasks` 5回は全成功)。

同一fixture・同一受領helperを使う兄弟テスト `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` (`onFileRead` 経由) は、同一の即時castパターンを持ち、機構上同一のレースに暴露されている (CI観察は未記録だが、構造は同一である)。 Observabilityの性質上、CI観察の有無はこのテストが安全であることの根拠にならない。

このテストがpinする振る舞いは [spec 332](../332-exchange-import-input-ui/spec.md) (implemented) の受入条件のoracleである: AC-1 (clipboard 1操作 → 共通import path)、AC-2 (file 1操作 → 共通import path)、AC-3 (clipboard/file読込が既存内容を置き換える)、AC-5 (全sourceが同一共通import path)。フレーク撤去はこのoracle守備範囲を維持したまま行う必要がある。

## Outcome

2つの受領テスト (clipboard / file) は、coroutine schedulerや実行環境のtimingに依存せず、CI上で決定的に成功する。テストがassertする内容は現行どおりであり、検証強度は落ちない:

1. 受領1操作で読み取ったtextがエディタ内容を置き換え、共通import pathの入力になること。
2. 追加のimport押下なしに共通import path (`store.load` 到達) が開始すること。

## Scope

- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` のみの変更である:
  - 対象2テスト (`clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath`、`fileTextReceiptRunsTheSameCommonImportPathInOneOperation`) の待ち合わせ・assert構造の修正。
  - 必要なら同test file内へのhelper追加・既存helper (`awaitStoreLoad`、`awaitScreenOutcome` 等) の再利用。
  - 同一レース様式を持つ他の即時castが無いことの監査結果を、対象テスト付近のcommentとして記録すること (実装者とreviewerが監査結論を同一file上で確認できるようにする)。

## Non-goals

- `ExchangeFlowStateHolder` を含むproduction sourceの挙動変更 (Issue本文のnon-goal)。production側のsettle seam・dispatcher構成は変更しない。
- 対象外のテスト (settleが走らない受領失敗系、既にpolling helperを使う成功系・outcome系) のstyle統一。
- 新規test framework / libraryの導入、CI workflowの変更。
- `ExchangeImportSurfaceInstrumentationTest` 等の他test seamへの本件の波及修正 (計測されたフレークは本test fileに局限される)。

## Observable behavior (テスト決定性の契約)

本specが「直すテスト」に要求する契約である。実装者とreviewerはこれで修正の適否を一意に判定できる:

1. **決定性**: 受領系テストは、assert時点でassert対象のscreen状態が「これ以降に置換されない状態」であることを構造的に保証しなければならない。許容される形は次のいずれかであり、いずれも既存seamのみを使う:
   - **(A) settle保持による安定化**: 既存の `FakeStore.loadGate` (#328 review導入の、session loadでのvalidation保持seam) でsettleを `store.load` 内で保持し、gate解放前のwindow内で `Importing(replyText)` をassertする。gateは最後に必ず解放し、テスト終了時にholder scope内に未回収のcoroutineを残さない。
   - **(B) 安定終端状態へのpolling**: 既存 `awaitScreenOutcome` と同様のpollingで安定終端状態 (`ImportOutcomeScreen`。該当fixtureでは未一致replyが必ず失敗settleする) へ到達してからassertする。受領textが共通import pathの入力になったことは `ImportOutcomeScreen.rawText == reply` で検証する (settleImportへ渡る `replyText` は `receiveAndImport` が採用した置換後textと同一値である)。
2. **禁止様式**: 遷移途中の一時状態 (`Importing`) を「pollingで待つ」実装は禁止する。settleがpolling開始前に完了した場合、screenは二度と `Importing` へ戻らず、pollingはtimeoutして新しいフレークになる。既存 `awaitImporting` helperはattempt失効後に `Importing` が安定しているテスト向けであり、受領系テストへ転用してはならない。状態が置換され得る相手に対する `Thread.sleep` 順序依存も導入しない。
3. **検証対象の維持**: 修正後も各テストは (1) 受領textによる置換 (Aは `Importing.replyText == reply`、Bは `ImportOutcomeScreen.rawText == reply` で観測)、(2) 共通import pathの開始 (`store.load` 到達、`loadCalls == 1`)、(3) 1操作性 (追加press不要) を検証する。検証対象のいずれかをdropする修正は本spec違反である。
4. **2テスト同時修正**: clipboard受領とfile受領の両テストを同一方針で修正する。片方のみの修正は、既知の同一レースを残すことになるため行わない。

## Domain language

用語の追加・変更なし。本Issueで扱うのはテスト決定性であり、ドメイン挙動は不变である。

## Behavior scenarios

### Scenario: clipboard受領テストはsettleと競合しない

Given `newHolderWithRecordedScope` fixtureと未一致marker reply
When `importFromClipboard` を1回呼び、共通import pathの開始を既存helperで確認する
Then clipboard受領のassertは、settleが完了している (B) か、settleが保持されている (A) ため、いずれの実行timingでも成功する
And `ClassCastException` が発生し得る即時castが存在しない

### Scenario: file受領テストも同一方針で決定的である

Given 同一fixtureと未一致marker reply
When `onFileRead(FileExchangeRead.Text(reply))` を1回呼ぶ
Then clipboard受領と同一の決定性契約が成立する

### Scenario: 修正によって検証が弱化しない

Given 修正済みの受領テスト
When 受領textの置換・共通import path開始・1操作性を確認する
Then 3点すべてが修正後のテストに残っており、spec 332 AC-1/AC-2/AC-3/AC-5のoracle守備範囲が維持されている

### Scenario: 失敗時の振る舞い (テストが環境timingに戻った場合)

Given 修正後もCI上で対象テストが失敗した
When 失敗stackを調査する
Then 失敗原因が本specの決定性契約違反 (一時状態への無同期assert) か、新たな競合経路かを判別できる (失敗messageにassert対象状態と、その状態へ至った経路が記録されている)
And 新たな競合経路だった場合は本Issueへ証跡を記録し、specを更新してから次の修正を行う

## Data and state

None。テストのみの変更であり、永続化・migration・layout・DB への影響はない。テスト内で既存の `FakeStore` / `loadGate` seamを使うのみである。

## Permissions, privacy, and security

None。production source・string・permission・通信を変更しない。テストfixtureはsynthetic identityのみを使う (既存どおり)。

## Accessibility and localization

None。UI・stringへ触れない。

## Dependency

- blockするdependencyなし。実行gateは既存の `organizer-unit-tests` CI job (`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`) である ([quality-strategy](../../docs/engineering/quality-strategy.md))。
- 保護対象の正本: [spec 332](../332-exchange-import-input-ui/spec.md) (implemented、AC-1/AC-2/AC-3/AC-5)。参照のみで変更しない。

## Compatibility / migration

None。test fileのみの差分であり、production挙動・API・data形式は不变。rollbackは当該commitのrevertで完結する。

## Acceptance criteria

- [ ] AC-1: 対象2テストに、in-flightなsettleが置換し得るscreen状態への無同期assertが存在しない。修正が契約1の (A) または (B) のいずれかで構成されていることがdiff reviewで確認できる。
- [ ] AC-2: 契約3の検証対象3点 (置換・共通path開始・1操作性) が修正後テストに残っている。検証対象のdropがない。
- [ ] AC-3: diffが `ExchangeFlowStateHolderTest.kt` (および本spec/plan) のみであり、production sourceを含まない。
- [ ] AC-4: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolderTest'` の反復実行 (最低20反復) が全成功であり、かつ `--tests 'app.lawnchair.organizer.*'` gateと `./gradlew spotlessCheck` が成功する。
- [ ] AC-5: 修正PRのCIで `organizer-unit-tests` job (final-status構成) が成功する。
- [ ] AC-6: Issue #352本文の受入条件「当該テストがCI上で連続成功する」について、race撤去は1回のgreen runでは証明できないため、本specでは「決定性の構造的根拠 (契約1・2) + AC-4の反復実行 + AC-5のCI成功」をもって検証完了と定義し、merge後のCI観察を残存確認とする。この定義はIssue本文の文言を置き換えるものではなく、検証方法の明確化である (owner判断で変更可能)。

## Unresolved decisions

- 契約1の (A) / (B) の選択は実装判断として残す (両方とも本spec適合)。選択基準は [plan.md](./plan.md) のdecision ruleに記載する。product decisionの未決は存在しない。
- 本specは `status: draft` である。Ownerのacceptance記録まではimplementation-readyではない ([github-workflow](../../docs/project/github-workflow.md) のbug種別start gate)。
