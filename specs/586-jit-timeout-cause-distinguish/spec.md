---
issue: "#586"
status: draft
requirements: []
updated: 2026-10-15
---

# JIT settings-return timeoutの原因切り分け（fixture/lifecycle/predicate/publication）とfailure-time receipts

Risk tier: L（test-only diagnostics + 調査証跡。production path・DB・高リスクpath一覧に触れない。plan.mdを要求しない階層であり、本specの実行マトリクス節が調査手順を兼ねる）

## Problem

`UsageAccessJitInstrumentationTest.settingsResumeAfterGrantResumesThroughTheProductionPredicate` が CI run 38321690999（source `77b6b97768822158d3a3b13bafa6267a0b57e4f1`、manual-organization-ui lane）で36.081s、line 259の `waitUntil`（`State.InputUnavailable` 待ち、上限30s）で `ComposeTimeoutException` となり、テスト本体・同スレッドの例外もアプリANRも確立されていない。deny/fallback兄弟テストは同一runでPASSした。failure-time artifactは集約失敗後（app uninstall、NexusLauncher復帰後）の取得で、timeout時点のscreenshot/compose-state receiptが存在しない。原因は「unknown」のままであり、#576とはsignatureが異なるため分類を継承しない。

当該commit時点で `newProductionRunner` はpublication threadを注入しておらず（既定 `DirectRunPublicationThread`）、#585の修正（`5cefa6df`、PR #584）で当該fixtureは実productionの `HandlerRunPublicationThread` を使うよう修正済みである。ただし #585 の修正は兄弟テストのfocus-defectに対するものであり、本timeoutの原因を証明しない（Issue本文のとおり「future exact-head runがPASSしても原原因は未証明」）。

production hostの復帰経路は静的に全boundedである: ON_RESUME観察（`settingsRequested == true`が必要）→ `grantCheckTick` 増分 → `awaitUsageAccessGrant`（上限1500ms、付与の有無にかかわらず継続）→ 無条件の `resumeRunOnWorker()` → `continueAfterUsageAccessGate`（run lock下の単発claim）→ `runComposedPhase` → `InputUnavailable`。したがってgrant遅延だけでは30s無復帰を説明できず、lifecycle/tick不発、resume coroutineのキャンセル・例外、publication経路、その他runtime failureの区別receiptが必要である。

## Outcome

JIT settings-return系の待ちがtimeoutしたとき、cleanup前にfailure-time receipts（runner状態種別、gate phase/revision、production predicate値、journal RUN_STARTED有無、dialog合成receipt）が記録され、次回の失敗が原因クラスの切り分けに直接使える。さらに制御されたtargeted実行（baseline=HEAD、candidate=77b6b97時代のDirect-publication fixture variant）の結果が原因クラスの分類として記録され、original unknownが「分類された」か「この環境では非再現として限界付きで保持される」かが証跡つきで確定する。

## Scope

- 既存owner（`UsageAccessJitInstrumentationTest`）内でのみ動くfailure diagnostics: settings-return 2テスト（付与兄弟・deny兄弟）の最終 `InputUnavailable` 待ちを、timeout時にreceiptsを付したfailureへ変換するヘルパーへ置き換える。receiptsの最小集合:
  - runner state種別と内容（例: `AwaitingUsageAccessJit(runId, selection, isOwner)`）
  - gate snapshotの `phase` と `revision`（`UsageAccessJitGateProvider` 経由のproduction gate）
  - timeout時点のproduction predicate値（`UsageAccess.isGranted(context)`、appops復元前）
  - grant-check tick入口receipt（`GRANT_CHECK_TICK_ENTERED` 相当。後述のtest-only passive tracer seamが記録する）
  - journal状態（`RUN_STARTED` 発火有無 — application doubleのdiagnostics listから）
  - `preparationPhase` と `operationActive`
  - compose receipt: dialog tag存在（非throw観察）と、上限付きsemantics tree（`onRoot().printToString()`、文字数上限でtruncate）
- receiptsは **passive/append-only** であること: 記録はlock-freeな追加専用バッファ（例: `AtomicBoolean`/`ConcurrentLinkedQueue`）への追記のみとし、synchronous logging・lock獲得・await条件・production/testの進行条件・同期プリミティブとして使わない。読み出しはfailure時のみ。
- grant-check tick入口の観測のための **test-only passive tracer seam**: 既存のinjectable pattern（`usageAccessSettingsOpener` / `jitLifecycleOwner` / `ManualOrganizationRun.writeThreadTracker` / `ExchangeFlowStateHolder.onSettleObserved` と同型）で、host構成に既定no-op（null）の1引数を追加し、tick入口（guard通過直後）で1回追記する。既定経路の振る舞い・公開API・production動作は不変。
- 制御されたtargeted実行と分類記録（`docs/assessment/issue-586-<slug>.md`）:
  - baseline: HEAD（Handler publication、diagnostics込み）でのtargeted実行。pass pathがdiagnosticsで乱されないことの確認を兼ねる。
  - candidate: 77b6b97時代のfixture variant（`publicationThread` 未注入＝Direct publication相当）をlocal overlay（commitしない一時差分）としてtargeted実行し、原signature（30s無復帰timeout）の再現成否をreceiptsつきで記録する。
  - receipt → 原因クラスの対応（分類の観測契約。receiptで直接観測した事実以上の原因名を付けない。複数クラスと整合する場合は `unclassified` とし、無理に決めつけない）:
    - tick入口receipt無し、gate=Presented、RUN_STARTED無 → lifecycle/tick経路未実行（ON_RESUME observer不発 / `settingsRequested` 不整合 / effect再構成）
    - tick入口receipt有り、predicate=false → 付与未観測。fallback復帰が正しければ `InputUnavailable` に到達するため、到達しない場合は後続のresume/claim経路側（下段へ）
    - tick入口receipt有り、gate=Presented、RUN_STARTED無 → post-tick経路（re-read後の `resumeRunOnWorker` / claim不達。resume launch不実行・キャンセル等）。細分が決められない場合は `unclassified`
    - gate=Resolved、RUN_STARTED無 → claim済み（resolve実行済み）でcomposed phaseが完走していない（machine/publication側）
    - RUN_STARTED有 → resume後のmachine内部での停止（composition以降）
    - gate=Presented かつ dialog不在 → host teardownの異常（abandonされない残留owner）
  - Direct-publication variantで再現した場合: publication fixture exposureへの分類（#585修正が当該signatureを解く傍証）。非再現の場合: 分類は「この環境/platformでは非再現」に限定し、original unknownは維持する（診断は将来のCI発生時に切り分けを提供する）。
- 証跡記録: 対象SHA、実行環境（API level、arch、emulator image）、実行回数、receipts、分類とその根拠境界、未証明範囲を `docs/assessment/issue-586-<slug>.md` とIssueコメントへ記録する。

## Non-goals

- production **動作**の変更（既定経路の振る舞い・公開API・状態遷移は不変）。`ManualOrganizationRun.kt`、`RunPublicationThread.kt`、`UsageAccess.kt`、run machine は不変。organizer UI sourceへの変更は前述のtest-only passive tracer seam（既定no-op引数1つ）に限定する。
- timeout上限の引上げ、新規テストクラス/新規CI lane/新規filterの追加、lane構成・`ci_portfolio_map.yml` の変更（既存manual-organization-ui laneがownerのまま）。
- 将来のPASSをもってflake解決・原因解決と主張すること（Issue本文のとおりautoCloseしない）。
- #576 / #473 / #418 系の他のunknown失敗の分類。
- 上流（Lawnchair）への報告（fork固有test harnessの問題クラス）。
- リトライ・注文 increases等のマスキング手法の導入。

## Domain language

なし（実装語のみ）。

## Prior art

- なし（調査済み）。Compose UI testのfailure-time診断は `ComposeTestRule` のsemantics dump（`printToString()`）が標準手段で、本specは既存owner内のreceipt集約に限定する（2026-10-15確認）。外部ライブラリ/新規レポータの導入は不採用（階層L・最小差分の契約）。

## Behavior scenarios

### Scenario: settings-return待ちがtimeoutしたとき、cleanup前にreceiptsが取れる

Given settings-returnテスト（付与兄弟またはdeny兄弟）が `State.InputUnavailable` を30s待っている
When 待ちがtimeoutする
Then テストfailureのメッセージにrunner state種別、gate phase/revision、production predicate値、grant-check tick入口receipt、RUN_STARTED有無、preparationPhase、operationActive、dialog存在、上限付きsemantics treeが含まれる
And receiptsはテスト本体のfinally（appops deny復元）より前に取得される
And 元の `ComposeTimeoutException` はcauseとして保持される

### Scenario: diagnosticsはpass pathを変えない

Given 同一テストが通常どおり `InputUnavailable` に到達する
When diagnosticsヘルパーが待ちを包む
Then 待ち条件・上限・assertionは変更前と同一であり、追加のsleep/poll/リトライを導入しない
And pass時の診断コードはfailure経路以外で観察可能な状態を変えない

### Scenario: receiptsの記録はtimingを変えない

Given grant-check tick入口tracerとfailure時receipt収集が実装されている
When テストがpass/failureどちらの経路を通っても
Then 記録はlock-freeな追加専用バッファへの追記のみで、synchronous logging・lock獲得・await条件・進行条件を構成しない
And tracerはproduction既定経路ではno-opであり、公開API・状態遷移を変えない

### Scenario: 制御実行が原因クラスを分類する

Given baseline（HEAD）とcandidate（Direct-publication相当overlay）のtargeted実行が同一の手順・記録様式で行われる
When 各実行のreceiptsが記録される
Then 再現の成否とreceiptが原因クラス（predicate / lifecycle-tick / publication / その他runtime）の分類として `docs/assessment/` に記録される
And 非再現の場合、分類は「非再現環境の限定」でありoriginal unknownは維持される

### Scenario: 分類の限界を超えない記録

Given controlled実行が原CI（API 36 x86_64、CI emulator image）と異なる環境（例: local arm64 emulator）で行われる
When 環境差が結果に影響し得る場合
Then 記録は環境差を明示し、CI exact-head runの結果と混同しない
And 盲目的なtimeout増加・恒久lane追加を行わない

## Data and state

None（テストとdocsのみ。永続化・migration・layout書込みなし）。

## Permissions, privacy, and security

None（新規permission・外部送信なし。semantics treeのreceiptにはrun画面のtext要素が含まれ得るが、CI/localのapp-private test report内にのみ出力され、appops値はpermission状態そのものである）。

## Accessibility and localization

None（テストharness内部の診断出力であり、user-facing UIに影響しない）。

## Acceptance criteria

- [ ] AC-1: settings-return 2テストの最終 `InputUnavailable` 待ちがtimeoutした場合、cleanup前のreceipts（runner state種別、gate phase/revision、predicate値、grant-check tick入口receipt、RUN_STARTED有無、preparationPhase、operationActive、dialog存在、上限付きsemantics tree）がfailureメッセージに含まれ、元例外がcauseで保持される。receiptsはpassive/append-onlyでtiming・同期を変えない。diagnosticsは既存owner内にのみ存在し、新規クラス/lane/filter/timeout変更を伴わない。production sourceへの変更は既定no-opのtest-only tracer seam引数1つに限定される。
- [ ] AC-2: baseline（HEAD、Handler publication、diagnostics込み）のtargeted実行でsettings-return 2テストが連続してPASSし、pass pathがdiagnosticsで乱されないことが実行証跡（invocation、回数、環境）とともに記録される。
- [ ] AC-3: candidate（77b6b97時代Direct-publication相当のlocal overlay）のtargeted実行が行われ、原signatureの再現成否がreceiptsつきで記録される。
- [ ] AC-4: `docs/assessment/issue-586-<slug>.md` に、original runの確立済み事実、controlled実行の対象SHA・環境・回数・結果、receipt→原因クラス対応に基づく分類、その根拠境界と未証明範囲が記録され、Issueへ結果コメントが投稿される。分類はsupported evidenceに限定され、flake解決主張・timeout増加・恒久laneを行わない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | diagnosticsヘルパーのtimeout経路をlocal emulator上で意図的に発火させた1回のtargeted実行（receiptsがfailureメッセージへ出ることの確認。恒久的な常に失敗するテストは残さない）。加えてtest-audit skillの適用記録（primary owner boundary・既存coverage重複なし・CI分類不变） |
| AC-2 | local emulator（API 36）でのtargeted class実行log（クラス指定 `UsageAccessJitInstrumentationTest`、複数回）。CI exact-headの結果とは区別して記録 |
| AC-3 | 同上（overlay適用sourceのSHA + overlay差分の記録、実行回数、receipts） |
| AC-4 | `docs/assessment/issue-586-<slug>.md` + Issueコメント（CI run / 実行logへのlink、対象SHA） |

## 実行マトリクス（階層Lのためplan.mdの代わりに本specに含める）

1. 診断実装（AC-1。tick入口tracer seamを含む）→ spotlessCheck + 単体compile確認。
2. Timeout receiptsの意図発火確認（AC-1、1回のみの恒久化しない確認）。
3. Baseline targeted実行（AC-2、API 36 emulator、付与兄弟＋deny兄弟を含むclass単位、≥3回）。
4. Candidate overlay実行（AC-3、同一手順、≥5回。再現失敗なら5回で打ち切り、環境差を記録）。
5. 分類記録とIssueコメント（AC-4）。

## Open questions

なし（blocking問いなし。実行環境がCIと異なることによる非再現の限界はAC-4の記録様式で扱う）。

## Change history

- 2026-10-15: Draft created for #586.
- 2026-10-15: Review round 1（PR #597コメント）: tick入口receiptの追加とreceipt→原因クラス対応の一意化、receiptsのpassive/append-only制約、test-only tracer seam（既定no-op）へのscope限定を反映。
