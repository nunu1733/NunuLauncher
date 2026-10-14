# Issue #586 — JIT settings-return timeout の failure-time receipts と controlled targeted execution の記録

> Status: complete (investigation + diagnostics delivered; original cause remains unknown)
> Date: 2026-10-15
> Spec: [specs/586-jit-timeout-cause-distinguish/spec.md](../../specs/586-jit-timeout-cause-distinguish/spec.md) (accepted via PR #597)
> Evidence: [docs/assessment/586-jit-timeout-receipts/](./586-jit-timeout-receipts/)

## 目的

Issue #586 の終了条件（controllerd baseline/candidate targeted execution による predicate/lifecycle/publication の切り分け、cleanup前のreceipts確保、supported evidence に基づく分類）の実行記録。

## Original run の確立済み事実（Issue本文から、再掲）

- CI run [38321690999](https://github.com/nunu1733/NunuLauncher/actions/runs/38321690999)、source `77b6b97768822158d3a3b13bafa6267a0b57e4f1`、manual-organization-ui lane。
- `UsageAccessJitInstrumentationTest.settingsResumeAfterGrantResumesThroughTheProductionPredicate` が line 259 の `waitUntil`（`State.InputUnavailable` 待ち、上限30s）で36.081s `ComposeTimeoutException`。同テストスレッド例外・アプリANRは不成立。deny/fallback兄弟は同一runでPASS。
- failure-time artifact は集約失敗後（app uninstall、NexusLauncher復帰後）の取得で、timeout時点のscreenshot/compose-state receiptは存在しない。
- 当該commit時点の `newProductionRunner` はpublication thread未注入（= 既定 `DirectRunPublicationThread`）。#585修正（`5cefa6df`、PR #584）で当該fixtureは `HandlerRunPublicationThread` を使うようになったが、原timeoutの原因は証明されていない。

## 実装（本PR）

- `RunUsageAccessJitDialogHost` / `ManualOrganizationPreferences` に既定no-op（null）の `grantCheckTickTracer: ((Int) -> Unit)?` を追加。grant-check `LaunchedEffect` のguard通過直後（bounded re-read前）に1回追記する。既定経路の振る舞い・公開API・状態遷移は不変（spec 586 review round 1 のtest-only passive tracer seam契約）。
- `UsageAccessJitInstrumentationTest` に failure-time receipts ヘルパー（`waitUntilInputUnavailableOrCaptureReceipts` / `buildSettingsReturnTimeoutReceipts` / `GrantCheckTickTrace`）を追加し、settings-return 2テストの最終 `InputUnavailable` 待ちを包む。待ち条件・上限・assertionは変更前と同一。receiptsはlock-free append-onlyで、failure時にのみ読まれる。
- receiptsの最小集合: runner state種別、gate phase/revision、production predicate値（appops復元前）、grant-check tick入口receipt、RUN_STARTED有無、preparationPhase、operationActive、dialog存在、上限8,000字にtruncateしたsemantics tree。

## Receipts経路の意図発火確認（AC-1、恒久化しない1回限りの確認）

`waitUntil` 上限を一時的に1msへ下げたlocal overlay（commitしていない一時差分）でreceipts経路を発火させた。出力されたfailure message（API 36 local emulator、2026-10-15）:

```text
java.lang.AssertionError: settings-return wait timed out; failure-time receipts (captured before cleanup, spec 586)
runnerState=AwaitingUsageAccessJit (AwaitingUsageAccessJit(runId=RunId(value=371aaaa371aaaa371aaaa371aaaa371a), selection=null, isOwner=true))
gatePhase=Presented gateRevision=2
usageAccessPredicateGranted=true
grantCheckTickEntered=[1]
journalRunStarted=false (events=[RUN_STARTED, INPUT_NOT_READY])
preparationPhase=CAPTURE
operationActive=false
dialogPresent=false settingsUnavailablePresent=false
semanticsTree=Printing with useUnmergedTree = 'false'
Node #1 at (l=0.0, t=0.0, r=1080.0, b=2400.0)px ...
```

全receiptフィールド（tick入口receiptを含む）がfailure messageへ出力され、元例外（`ComposeTimeoutException`）はcauseで保持された。一時差分は確認後にrevert済み（恒久的な常時失敗テストは残していない）。

## Controlled targeted execution（AC-2/AC-3）

実行環境（全run共通）: host macOS arm64、local emulator AVD `issue142_api36`（API 36 / Android 16、`sdk_gphone64_arm64`）。**CI（API 36 / x86_64 / pixel_7_pro / disable-animations / clean boot）とはarch・emulator image・実行履歴が異なる**。実行は `connectedLawnWithQuickstepGithubDebugAndroidTest` のclass/method filter。結果XMLは [586-jit-timeout-receipts/](./586-jit-timeout-receipts/) へ保存。

| 系列 | source状態 | 対象 | 回数 | 結果 |
|---|---|---|---|---|
| receipts意図発火 | impl + 一時1ms上限overlay | grant method | 1 | receipts出力確認（上記）→ revert |
| baseline | impl（Handler publication + diagnostics） | settings-return 2本を含むclass全体 | 5 | **settings-return 2本は5/5回PASS**（11–16s）。XML: impl-class-run1/2.xml（+保存前の3run） |
| 対照 | stash適用で一時再現した main（`cd50255146` + spec） | settings-return 2本を含むclass全体 | 2 | settings-return 2本PASS（11–14s）。XML: clean-class-run1/2.xml |
| candidate | impl + Direct-publication overlay（[candidate-overlay.diff](./586-jit-timeout-receipts/candidate-overlay.diff)：`newProductionRunner` から `publicationThread = HandlerRunPublicationThread()` を除去＝77b6b97時代相当） | grant method | 5 | **5/5回PASS（12–22s）。原signature（30s無復帰timeout）は非再現**。XML: candidate-grant-run1..5.xml |

## 分類（supported evidence の範囲で）

1. **原timeout（CI 38321690999）の原因は、本実行では確定しない**。candidate（77b6b97時代のDirect-publication fixture相当）は本環境で5/5 PASSであり、publication-fixture exposureへの分類を**支持も反証もしない**。環境差（arch、emulator image、CIのclean boot/animation無効、実行履歴）が大きいため、local非再現を「本番CIでの非存在」と解釈しない。
2. 観測契約どおり、**original unknown は維持される**。将来のCI発生時には、本diagnosticsのreceipts（tick入口receiptの有無、gate phase、predicate値、RUN_STARTED有無、dialog合成receipt）が spec 586 の対応表に従って predicate / lifecycle-tick / post-tick / machine-publication / teardown異常 への切り分けを直接提供する。
3. #585修正（Handler publication）は当該fixtureの既知のfocus-defect exposureを除去する修正であり、原timeoutの解決とは主張しない（Issue #585/#586の本文どおり）。
4. timeout上限・lane構成・filterへの変更は行っていない。恒久laneの追加も行っていない（既存manual-organization-ui laneがownerのまま）。

## 調査中の環境観察（本Issueのscope外・分類しない）

local実行中、`jitDialogBackDeclinesAndContinuesWithoutCancellingTheRun`（BACK keyevent経路）が **tree非依存**で局所的に失敗した（impl 5/5、clean HEAD 2/2のclass実行で失敗。method単体では1回PASS/1回FAIL）。失敗は `pressBack()` が注入したshell `KEYCODE_BACK` の配信系（logcat上は `WindowOnBackDispatcher: sendCancelIfRunning` の連鎖）で止まっており、settings-return経路・本diagnostics・本diffとは無関係であることを stash A/B で確認した。これは本長寿命local emulatorの環境問題のシグネチャであり、CI（同testが最新exact-head runでPASS継続）や#586の原signature（settings-return待ち）とは異なる。**本Issueでは分類せず、必要なら別Issue（failure class 3/5候補）として追跡する。**

## 未証明範囲

- 原timeoutの原因クラス（unknown維持）。
- local非再現の一般性（API 37 local emulator、x86_64 image、CI exact-headでの反復は未実施 — CI exact-head での将来発生時にdiagnosticsが効く設計）。
- BACK keyevent環境問題の原因（本Issue scope外、上記のとおり）。
