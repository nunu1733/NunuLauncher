# Implementation Plan: #352 Exchange受領テストのscreen状態レース撤去

---
issue: "#352"
status: draft
updated: 2026-09-19
---

> Status: **draft** — specが `draft` である間は実装を開始しない ([github-workflow](../../docs/project/github-workflow.md) のbug種別start gate)。本planは baseline `3076bdae7ebf8dbb086f251203968c06e9986258` (origin/main 2026-09-19) の実装code調査に基づく。これはfix planではなく、root cause確認結果と、確定した機構に対するテスト修正の実装計画である。機構の根拠はCI証跡とcode読解であり、後述の「Explicitly unverified areas」を参照のこと。

## Current evidence (baseline `3076bdae7e` 実装調査)

### 対象コード

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  - `ExchangeFlowStateHolder` constructor: `settleDispatcher` (transport結果のhop先、222行目付近) と `uiDispatcher` (display state更新のhop先、229行目付近、既定 `Dispatchers.Main`) がinjectable (#332 review R3で導入。「JVM holder tests assert the terminal display states without an Android Main looper」)。
  - `importFromClipboard` (479行目): 同期的に `transport.read()` → `receiveAndImport(text)`。
  - `receiveAndImport` (497行目): envelope gate → **呼出thread上で** `screen = ExchangeScreen.Importing(text)` を設定 → `import(text)`。
  - `import` (628行目): arbiter gate → `beginImportAttempt()` → `scope.launch(Dispatchers.IO) { controller.importReply(...) ; withContext(uiDispatcher) { settleImport(...) } }`。
  - `settleImport` (651行目): attempt tokenが現行なら `Validated` → `ImportSuccess`、それ以外 → **`screen = ExchangeScreen.ImportOutcomeScreen(outcome, rawText = replyText)`**。
- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt`
  - `newHolderWithRecordedScope` (211行目): `scope = CoroutineScope(Dispatchers.IO + handler)`、`settleDispatcher = Dispatchers.IO`、**`uiDispatcher = Dispatchers.IO`**。つまりこのfixtureではsettleがIO pool上で即座に実行され得る。
  - `awaitStoreLoad` (247行目): `store.loadCalls >= expected` を50ms刻みで最大5s poll (load開始 = `loadCalls++` が `loadGate` awaitより前である点に注意: load開始は確認できるが、settle完了は確認できない)。
  - 対象テスト `clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath` (654行目): `importFromClipboard` 直後に **667行目 `holder.screen as ExchangeScreen.Importing`** (無条件cast) → `assertEquals(reply, importing.replyText)` → `awaitStoreLoad(store, 1)`。
  - 兄弟テスト `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` (674行目): `onFileRead(FileExchangeRead.Text(reply))` 直後に **685行目で同一の無条件cast**。
  - `FakeStore.loadGate` (96行目, #328 review導入): `load(exportId)` 内で `loadCalls++` の後にlatch awaitする。「validationをsession loadで保持する」既存テストseam。
  - `awaitScreenOutcome` (771行目): `screen is ImportOutcomeScreen` を50ms刻みで最大5s poll。既存のoutcome系テスト (716行目、741行目) が使用。
  - `awaitImporting` (1086行目): `Importing.replyText == text` をpoll。ただし使用箇所はすべて「attempt失効後に `Importing` が安定している」テストであり、受領系の一時状態には使えない。

### Root cause (確定済みの機構)

1. CI stack traceの `ExchangeScreen$ImportOutcomeScreen cannot be cast to ExchangeScreen$Importing` は、`settleImport` の失敗branchが既に走って `ImportOutcomeScreen` へ置換済みのscreenへの即時castでだけ発生し得る。対象fixtureでscreenを非同期に置換する経路は `settleImport` のみであり、他のmutator (`openImport` / `close` / `discardImport` 等) は対象テストのassert window内でテストthreadから呼ばれない。
2. CI run 35276653532 (head `5f735526b3`) における失敗行640は、当該revisionの対象テストcast行である (2026-09-19に `git show 5f735526b3` で確認)。現行mainでは #328系のテスト追加分により行番号が667へずれている。
3. 競合の成立条件: `import()` がlaunchしたIO coroutineが `importReply` を完了させ、`withContext(Dispatchers.IO)` への継続がテストthreadのcastより先に走ること。IO poolは複数threadであり、CI実行環境ではこれが稀に成立する。ローカルの同filter + `--rerun-tasks` 5回 (Issue記載) では成立しなかった、という観察と整合する。
4. `fileTextReceiptRunsTheSameCommonImportPathInOneOperation` は同一fixture・同一 `receiveAndImport` 経路であり、機構上同一の競合に暴露される。

### 対象テストが検証する内容 (維持対象)

- 受領textによるエディタ置換: `receiveAndImport` が採用したtext (= `import(replyText)` へ渡る `replyText` = `settleImport` の `rawText` としても生存) が `reply` と一致する。
- 共通import pathの開始: pipeline decode後の `store.load` 到達 (`FakeStore.loadCalls == 1`)。
- 1操作性: `openImport()` 以外にimport pressを伴わない。

これらは [spec 332](../332-exchange-import-input-ui/spec.md) AC-1/AC-2/AC-3/AC-5のholder unit test oracleである。

### 他の即時castの監査 (対象外の根拠)

同test file内の `as ExchangeScreen` castを実装時に全数監査した結果 (baseline `3076bdae7e`):

| 行 | テスト | 判定 |
|---|---|---|
| 615, 631, 649, 697, 788 | 受領失敗系 (Empty / NotText / oversize / file失敗) | importをlaunchしないためsettleが存在しない。`Importing` は `openImport()` 設定値として安定。対象外 |
| 667 | clipboard受領 (本件) | **racy — 修正対象** |
| 685 | file受領 | **racy — 修正対象** |
| 717, 742 | outcome系 | `awaitScreenOutcome` 経由で安定終端をpoll済み。対象外 |
| 726 | 同テストのretry部 | 直前の `awaitScreenOutcome` でsettle完結済みであり、その後の `openImport()` は同期的。対象外 |
| 1055-1065, 1068-1084 | `awaitImportSuccess` / `awaitClosed` | polling helper自体。安定状態を待つ。対象外 |
| 1252, 1505 | 逆順settle / ABA系 | 遅延settleはtoken照合でdropされscreenを置換しないため、`ImportSuccess` castは置換と競合しない。対象外 (sleepによる順序依存はあるが、失敗樣式がcast失敗ではなくassert失敗であり、観察された不具合と異なる。本Issueでは触らない) |
| 1398, 1415, 1433 | 入力変更系 | editor変更がattemptを失効させるため遅延settleはdropされ、`Importing` は安定。対象外 |
| 1086-1093 | `awaitImporting` | 使用箇所は安定状態のpoll。ただし **受領系テストへの転用は禁止** (一時状態を待つと新規フレークになる。spec契約2) |

## Ownership / module boundaries

- 変更はtest fileのみ。productionの `ExchangeFlowStateHolder` / `ExchangeFlowController` / pipelineへは触れない (Issue non-goal)。
- 既存test seamのみを使う: `FakeStore.loadGate`、`awaitStoreLoad`、`awaitScreenOutcome`。productionへ新しいtest hookを追加しない。

## Interfaces / seams

- 待ち合わせのseamは「holderの表示状態 (`holder.screen`)」と「`FakeStore.loadCalls` / `loadGate`」の2つ。内部実装 (Compose state fieldのreflection等、256行目の `screenStateField`) は既存のdisclosure系テスト専用であり、本修正では新規に使わない。

## Control / data flow (修正後の期待flow)

対象fixtureでのsettle可能な単一経路: `receiveAndImport` (Main test thread, `screen = Importing(reply)`) → `import` → IO coroutine (`importReply`: prepare → `store.load` [loadCalls++ → loadGate待ち] → ExportMismatch) → `withContext(uiDispatcher = IO)` → `settleImport` (`screen = ImportOutcomeScreen(rawText = reply)`)。

修正はこのflowに対し、assert時点でsettleを「完了済み (B)」または「保持中 (A)」にするだけであり、flow自体は変更しない。

## Expected files to change

- `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` — 対象2テストの修正、監査結論のcomment、必要ならtest file内helper。
- (本branchに既に含まれる) `specs/352-exchange-receipt-test-determinism/spec.md` / `plan.md`。

## Fix approaches と decision rule

### Approach A (推奨): loadGate保持によるassert windowの安定化

```text
store.loadGate = CountDownLatch(1)
holder.importFromClipboard(...)        // または onFileRead(...)
awaitStoreLoad(store, expected = 1)    // 共通pathがloadへ到達したことを確認
val importing = holder.screen as ExchangeScreen.Importing   // settleは保持中のため決定的
assertEquals(reply, importing.replyText)
store.loadGate.countDown()             // 必ず解放する (未回収coroutineの放置禁止)
awaitScreenOutcome(holder)             // (推奨) settle完結を待ってテストをdrainする
```

- 現行assert集合を **そのまま** 維持する (cast・replyText一致・loadCalls==1)。「検証強度を落とさない」の最も強い読みに適合する。
- `loadGate` は `loadCalls++` の後にawaitするため、`awaitStoreLoad` が真に「load進入」を確認できる点が整合する。
- 注意: gate解放忘れはcoroutineを最大5s残置する (`loadGate?.await(5, TimeUnit.SECONDS)`)。解放 + `awaitScreenOutcome` によるdrainを必須とする。
- 注意: `unmatchedMarkedReply` は `store.load` に到達する (prepare → load の順)。`loadGate` は到達を block するだけであり、失敗結果は解放後に生成される。

### Approach B: 安定終端状態へのpolling置換

```text
holder.importFromClipboard(...) / holder.onFileRead(...)
awaitStoreLoad(store, expected = 1)
awaitScreenOutcome(holder)
val outcomeScreen = holder.screen as ExchangeScreen.ImportOutcomeScreen
assertEquals(reply, outcomeScreen.rawText)   // 受領textが置換後のimport入力であること
assertEquals(1, store.loadCalls)
```

- 既存outcome系テスト (`clipboardReceiptReachesTheParseFirstOutcomeWithEphemeralRawDiscardedOnRetry` 701行目、`fileReceiptReachesTheParseFirstOutcomeInOneOperation` 732行目) と同一パターンになり、file受領テストと共通化しやすい。
- `Importing` の一時的な表示内容そのものはassertしなくなるが、契約上の挙動 (置換後textが共通pathの入力になる) は `rawText` で観測されるため、spec 332 AC-3/AC-5の検証は維持される。
- `awaitScreenOutcome` は該当fixture (必ず失敗settle) でのみ成立する。このfixtureは未一致reply固定であるため問題ない。

### Decision rule

- 両approachともspec契約1に適合する。単独Issueとしては **Approach A** を推奨する (assert集合を変更せず、diff最小)。既存outcome系テストとのパターン統一をreviewが望む場合はApproach Bでよい。混在 (clipboard はA / file はB) は同一レースの修正として対称性を欠くため避ける。
- どちらの場合もspec契約2 (一時状態polling禁止、`awaitImporting` 転用禁止、`Thread.sleep` 順序依存の新規導入禁止) を守る。
- loadGate方式を採る場合、`FakeStore.loadGate` が複数loadで破綻しないよう、対象テストは1 importのみとする (現行どおり)。

## Testing strategy

- 直接確認: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolderTest'` (修正対象class)。
- 反復実行 (spec AC-4): 同filterを `--rerun-tasks` 等で最低20反復し全成功を記録する。race撤去の反証機会を増やす目的であり、通過自体は決定性の証明ではない (下記unverified参照)。
- gate: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`./gradlew spotlessCheck`。
- CI: 修正PRの `organizer-unit-tests` job (final-status構成) 成功をPR本文へ記録する。
- 失敗を再現するテストを書けない件 ([AGENTS.md](../../AGENTS.md) テスト規約の代替証拠): 対象は「テスト自体の非決定性」であり、production timingを改変しない限り失敗を決定的に再現するテストは作れない。代替証拠は (1) root cause機構のcode level裏付け (上記Current evidence)、(2) CI失敗の実在証跡 (run 35276653532、失敗行 = cast行の確認済み)、(3) 修正後テストの決定性に関する構造的議論 (assert対象が「置換されない状態」であること)、(4) 反復実行とCI greenをPRへ記録する。

## Execution checklist

1. specをownerへ提示し、acceptedを取得する (本taskでは行わない)。
2. 対象2テストをdecision ruleに従い修正する。監査結論 (上記表の要約) を対象テスト付近のcommentへ記録する。
3. `spotlessCheck` → 直接確認 → 反復実行 → organizer gate の順に実行し、結果をPRへ記録する。
4. PR (`Refs #352`) を作成し、CI `organizer-unit-tests` 成功を確認してmergeする。本Issueの終了条件 (CI連続成功) をこのPRが満たすかは、AC-6の検証定義をもってowner判断とする。
5. merge後、対象テストのCI失敗観察が続く場合は本Issue (または後続Issue) へ証跡を記録し、spec/planを更新する。

## Migration / rollback

- migrationなし。rollbackは当該commitのrevertで完結する (test fileのみ)。

## Failure handling

- 修正が既存テストを失敗させる場合 (例: loadGate方式でawaitStoreLoadがtimeout)、それはテスト修正の欠陥でありproduction不具合の可能性も含めて調査する。`awaitStoreLoad` のtimeoutは「共通pathがloadへ到達していない」ことを意味し、revertして原因を特定する。

## Risk

- 低。test-only差分であり、`risk:` label・高リスクgateの対象外である (高リスクpath一覧にtest fileは含まれない)。
- 残存risk: 修正が別の非決定性 (例: Approach Aでのgate解放忘れによる遅延settleの後着) を持ち込むこと。これはreviewと反復実行で検出する。

## Explicitly unverified areas

- **ローカルでの競合再現は未達成** (Issue記載の5反復も成功)。機構はCI証跡とcode読解で確定しているが、「CI実行環境でsettleがcastに先行する」というtiming事実そのものは観察証跡 (stack trace) による間接確認である。Approach A/Bはいずれも「先行してもしなくても成功する」構造のため、この未確認は修正の有効性に影響しない。
- CI run 35276653532 以外の本フレーク観察の有無は調査していない (Issue・#350に記録があるのは1件)。
- baseline `3076bdae7e` 以降に #327/#328/#337/#348系Exchange変更が対象テストへ与えた影響は、`git log` と現行fileの読解で確認済み (対象テスト自体は #332実装時に追加され、#328系でfixture/行番号が変化。レース機構は不変)。

## Re-entry rule

本planはbaseline `3076bdae7ebf8dbb086f251203968c06e9986258` 時点のsnapshotである。実装再開時は最新 `origin/main` と全Issueコメントを再取得し、対象テスト・`ExchangeFlowUi.kt`・spec 332/328の差分を再確認してから本planの妥当性を再評価すること。
