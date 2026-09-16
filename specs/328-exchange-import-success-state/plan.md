# Implementation Plan: External Agent Exchange Import成功後の中間状態と次操作の明示

> Issue: #328
> Spec: [spec.md](./spec.md)
> Status: draft — spec D-2 (Back破棄の確認形式) / D-3 (CTA文言) がowner decision待ちのためimplementation-readyではない。

## Current evidence

origin/main (`aab0d293d1a98bf59f5b164693f54ee1a63e3f0b`、2026-09-16確認) のコード事実。#205実装 (PR #325、commit `3df9c7af`) + #331実装 (PR #333、commit `addb25d8181`) 後の状態。

### Import成功時の現行挙動

- `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`
  - `ExchangeFlowStateHolder.import(replyText)` (L401–446): IO上で `controller.importReply(replyText)` を実行し、`ExchangeImportResult.Validated` のとき **即座に** run接続へ進む:
    - runが `State.Selecting` (run内entry): `run.attachIntent(validated)`。`Attached` → `status = IMPORT_ACCEPTED` + `screen = Closed` (画面を閉じて1行status)。`NotAttachable` → `status = RUN_BUSY` + Importing画面へ復帰。
    - それ以外 (idle entry): `run.start(intent = validated)`。`Started` → `IMPORT_ACCEPTED` + `Closed`。`Busy` → `RUN_BUSY` + Importing復帰。
    - 失敗 (`Pipeline(Failure)` / `InputNotReady`) のみ `ExchangeScreen.ImportOutcomeScreen(outcome)` (失敗専用の「取り込み結果」画面、再取り込み付き)。
  - `exchange_import_accepted` 文言 (ja: 「整理案を検証しました。プレビューを生成します…」) は実際の遷移とずれる (idle + 検出Readyなら次は選択surface、検出UnavailableならPreview直行でstatus行が非hostingになる)。
  - `ExchangeScreen` states: `Closed` / `SelectingPrivacy` / `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / `ImportOutcomeScreen`。成功用stateは存在しない。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `start(trigger, intent)` (L388–): fresh run接続seam (#205)。新RunId → `CandidateDetection` → `State.Selecting(runId, candidates, intentScopeCount)` または `runComposedPhase` 直行 (検出Unavailable)。`Busy` は `OrganizationOperationLease` gate。
  - `attachIntent(intent)` (L490–、#331): `State.Selecting` 保持中に一度だけbind。`Attached` / `NotAttachable`。
  - `State.Selecting` は `intentScopeCount` (選択surfaceの件数案内) と `scopeRejection` (`SCOPE_MISMATCH` 表示) を持つ。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - exchange itemsのhosting: run状態がIdle/Cancelled (idle entry、L734–。strategy pickerの下) または `State.Selecting` (run内entry、L340–365。選択surfaceの下、`exchangeBusy` で選択freeze) のときのみ。
  - Idle branchには「整理を開始」row (`coordinator.start(trigger)`、L284) があり、exchange sub-flow表示中もtappable (競合affordance。現行はこれでrunが始まるとexchange itemsが非hostingになり入力/状態が黙って消える)。
  - `ManualOrganizationBackHandler` (L906–): Back = `coordinator.dismiss()`。run active時はrun cancel (bound intent喪失)、idle時は画面離脱。exchange sub-flow独自のBack扱いはない。
- `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt`
  - `importReply(replyText): ExchangeImportOutcome` — `Pipeline(ExchangeImportResult)` / `InputNotReady(reason)`。`ExchangeImportResult.Validated(validated: ValidatedPersonalizedIntent)` が成功値。
- summary導出に使える既存data (`lawnchair/src/app/lawnchair/organizer/personalization/IntentModels.kt`、`IntentValidator.kt`):
  - `PersonalizedIntentV1`: `itemIntents: List<ItemIntent>` (`ref`, `importance`, `desiredGroupRefs`, `groupSemantic`, `pageAffinity`, `regionAffinity`, `preserve`)、`unresolvedRefs: List<String>`、`globalPreference`、`rationale: String?`、`confidence: Int?`。
  - `ValidatedPersonalizedIntent`: `intent` / `export` / `session` (`scopeCandidates`) / `identity`。coverage不変条件により export items = `itemIntents` ∪ `unresolvedRefs` (分割)。
- strings: `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` にexchange系 (~60件、成功系は `exchange_import_accepted` / `exchange_run_busy` のみ)。
- tests: `tests/unit/app/lawnchair/organizer/ui/exchange/ExchangeFlowStateHolderTest.kt` (holderの実経路test、FakeStore/run注入)、`tests/unit/app/lawnchair/organizer/integration/exchange/ExchangeFlowControllerTest.kt`、`tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` (`attachIntent`/`start(intent)`/scope gate)。

推測ではなく上記file/行の確認済み事実。build/testは未実行 (本PRはdocs-only)。

## Design

### Modules and interfaces

- **`organizer/personalization/exchange/` (pure) — 取り込みsummary導出**: `ExchangeImportSummary` (data class: 件数のみ。`recognizedCount`、`unresolvedCount`、`scopeCandidateCount`、optional内訳counts) と純粋関数 `exchangeImportSummary(validated: ValidatedPersonalizedIntent): ExchangeImportSummary`。label・ref・自由文を型に持たせない (非混入を構造で保証)。Android-free (purity guard範囲内)。
- **`organizer/ui/exchange/ExchangeFlowUi.kt` — 取り込み成功状態**:
  - `ExchangeScreen.ImportSuccess(summary, validated, entryKind)` を追加 (`entryKind`: idle / run内)。holderはvalidated intentを **process-localで一時保持** (新規永続化なし)。
  - `ExchangeFlowStateHolder.import()` の `Validated` 分岐を変更: attach/startを即時実行せず `screen = ImportSuccess(...)` へ。`IMPORT_ACCEPTED` statusの即時表示は廃止 (文言の誤用解消)。
  - 新規操作:
    - `continueImport()` (CTA): run内 → `run.attachIntent(validated)`。`Attached` → `screen = Closed`。`NotAttachable` → typed status (成功状態維持)。idle → `run.start(trigger, intent)` (既存のIO dispatcherパターンに従う)。`Started` → `Closed`。`Busy` → typed status (成功状態維持)。
    - `discardImport()`: pending intentを破棄し `screen = Closed`。`ExportSessionStore.invalidate` は呼ばない (再取り込み可能性維持)。
  - 成功状態composable: heading (成功、または未判断ありならwarning語彙) + summary行 (plurals) + 「未適用」明示行 + CTA (`Button`) + 破棄 (`OutlinedButton`、破棄を示すlabel) + 再取り込み案内 (破棄時)。testTag・live region・focus (既存 `FocusTargetText`/`liveRegion` パターン準拠)。
  - Back: 成功状態表示中は独自 `BackHandler` を有効化し、D-2確定内容 (案: 確認dialog → discard) を実行。既存の画面level `ManualOrganizationBackHandler` との順序はCompose BackHandlerの登録順で成功状態側を優先させる。
- **`ui/preferences/destinations/ManualOrganizationPreferences.kt` — hosting調整**:
  - Idle/Cancelled branchの「整理を開始」rowを、`holder.screen is ImportSuccess` (または非Closed全体 — 実装時に判断、最小はImportSuccessのみ) の間無効化。strategy pickerは無効化しない (spec scenario参照)。
  - hosting条件 (idleLike / Selecting) 自体は無変更。
- **strings (`values/` + `values-ja/`)**: 成功heading・未適用行・summary系 (plurals)・未判断説明・CTA・破棄label・破棄確認・再取り込み案内・CTA時busy案内 (既存 `exchange_run_busy` 再利用可否を確認)。jaを正本とする。
- **変更しないseam**: `ManualOrganizationRun.start/attachIntent/confirmSelection`、`ExchangeFlowController.importReply`、`ExchangeImportPipeline`、失敗表示 (`ImportOutcomeScreen`)、export flow (生成〜transport)。CTAは既存seamを呼ぶだけ。

### Data flow

```text
import (貼付/file) → controller.importReply (既存、変更なし)
  ├─ Failure/InputNotReady → ImportOutcomeScreen (既存、無変更)
  └─ Validated → ExchangeImportSummary導出 (pure) → ImportSuccess状態
        ├─ run state不変 (idle: Idle/Cancelled、run内: Selecting+freeze継続、zero-write)
        ├─ CTA → idle: run.start(trigger, intent) / run内: run.attachIntent
        │     ├─ 成功 → Closed → 既存flow (選択 → planning → preview → confirm → apply)
        │     └─ Busy/NotAttachable → typed status (成功状態維持・再試行可)
        └─ 破棄 (明示操作/Back+確認) → intent破棄・session無invalidate → Closed
              (依頼有効期間中は同じ回答textの再取り込みが成立)
```

### Alternatives rejected

- **即時attach/start維持 + 成功bannerを各run状態surfaceに表示**: 成功説明がSelectingとPreview/terminalの2系統のsurfaceに分散し、terminal (NoChanges等) で埋もれる。Issueが求める「中間状態」(次stepの前) と不一致のため不採用。
- **破棄ではなくpending intentの保持 (再表示)**: #205/#331の「validated intentを保持しない」運用と新規lifetime category (保持された未bind intent) を追加することになり、hosting消失・process deathとの整合コストが再取り込み (session TTL内で常に可能、安価) に比して大きいため不採用。明示的破棄 + 再取り込み案内で「黙喪失なし」を満たす。
- **破棄時にsessionをinvalidateする**: 取り込み自体は成功しており依頼はまだ有効。invalidateすると再取り込み (唯一の回復path) を閉じてしまうため不採用。
- **summaryに `rationale`/`confidence` を含める**: untrusted自由文の直接表示・誤解を招く自己申告値であり、件数summaryで要件 (何を認識したか) が足りるため不採用 (spec固定)。
- **`ExchangeStatus` 1行の文言修正のみ**: 「取り込み結果・未適用・次操作」を同時に示す要件を1行statusで満たせず、Preview直行時に表示されない問題も解消しないため不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/` (新規summary file) | `ExchangeImportSummary` + 純粋導出関数 | 件数のみのprivacy-safe summary、pure test |
| `organizer/ui/exchange/ExchangeFlowUi.kt` | `ImportSuccess` state、`continueImport`/`discardImport`、成功composable、Back扱い、`import()` Validated分岐変更 | exchange flow所有のUI state machine |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | 競合「整理を開始」rowの無効化 (成功状態中) | hosting surface側の最小変更 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 新規strings (成功/summary/CTA/破棄/案内) | spec 123 ja/en契約 |
| `organizer/ui/ManualOrganizationRun.kt` | **変更なし** (`start(trigger, intent)` / `attachIntent` をそのまま利用) | seam不変 |
| `organizer/integration/exchange/ExchangeFlowController.kt` | **変更なし** (`importReply` が既に `Validated` を返す) | seam不変 |

## Migration and recovery

- DB schema・storage変更なし。rollback = UI revertのみ。release後の残余dataなし。
- 失敗中のrollback: 不要 (CTAまでzero-write。CTA以降は既存run flowのrecovery)。
- 破棄後の回復: 同じ回答textの再取り込み (export session有効期限内。`CONTEXT_STALE` はhome変更時のみ既存どおり)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | holder unit test: validated → `ImportSuccess`、`start`/`attachIntent` 未呼出、run state不変。hosting instrumentation test | `./gradlew :lawnchair:testLawnchairGithubDebugUnitTest` (既存exchange test suite拡張) |
| AC-2 | string解決 + 表示test (unit/instrumentation) | 同上 |
| AC-3 | holder unit test: CTA → seam呼出 (`Started`/`Attached`)、`Busy`/`NotAttachable` で状態維持。`ManualOrganizationRunTest` 連携 | 同上 |
| AC-4 | `ExchangeImportSummary` unit test (件数導出・`unresolvedRefs` 境界・内訳) + 出力modelのlabel/ref/text非混入 (型構造で保証 + contract test) | 同上 |
| AC-5 | holder unit test: discard → invalidate未呼出・再取り込み往復成立・pending intent消滅。競合row無効化UI test | unit + instrumentation |
| AC-6 | semantics/live region assertion + TalkBack手動evidence | instrumentation + manual |
| AC-7 | font scale最大でのinstrumentation/手動evidence | manual (emulator/device) |
| AC-8 | physical device: Import → CTA → 選択/composition → preview のevidence | device (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-9 | 既存suite regression (`ExchangeFlowStateHolderTest`、`ExchangeFlowControllerTest`、17種失敗表示、`ManualOrganizationRunTest`) | 同上 |

含める観察: unit (state machine遷移・summary導出・discard往復)、contract (summary非混入、failure表示regression)、failure injection (CTA時Busy/NotAttachable、破棄→再取り込み)、a11y/large font、e2e device evidence。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md (用語: 取り込み成功状態、取り込み破棄。acceptance時)
- [ ] DESIGN.md §4/§9 該当箇所への反映 (exchange UIに成功状態が加わること。acceptance時に判断)
- [ ] ADR: なし想定 (破棄semanticsはspec Decisionsに根拠を記録。変更困難性が3条件を満たすかは実装後判断)
- [ ] requirements.md (FR-017 status整理 — 本Issue単独ではFR-017完了を意味しない)

## Dependencies and blockers

- **spec D-2/D-3のowner確定** (Open questions 1/2) — implementation-readyの前提。
- #205/#331/#204/#228/#194/#195: すべてimplemented/acceptedであり技術blockerなし。
- #332 (OPEN、import入力UI): 入力surfaceの再構成が予想されるが、本計画のseam (`Validated` → `ImportSuccess` → CTA → 既存run seam) は入力UIに依存しない。#332との作業順は問わない (並行可)。
- #329 Normalizer (OPEN): framing失敗率を下げる方向だが成功状態の契約には影響しない。

## Risks

- run接続の延期により、検証通過からCTAまでの間にprocess deathが起きるとintentを失う (従来も同philosophy。回復は再取り込み。spec scenarioで明示済み)。
- `run.start` をCTA時まで遅らせることで、CTA時にlayoutが変化している可能性が生じる — 既存の2段stale検証 (`CONTEXT_STALE` はimport時、revision stale checkはplanning/apply時) で既にカバーされる (import時点で検証済みでもCTA後のcompositionで既存stale pathが働く)。CTA遅延実行の経路をtestでcoverageする。
- Back handlerの優先順位 (exchange成功状態 vs 画面level dismiss) の実装忘れは「黙喪失」の再発になる — instrumentation/a11y evidenceで検証。
- #332との並行でimport画面が再構成される場合、成功状態への接続点 (Validated分岐) を共有するためmerge conflict/trackingが必要 — specの責務境界 (入力は#332、成功後は#328) を両PR本文へ明記する。

## Explicitly unverified areas

- 本draft時点でbuild/test未実行 (docs-only変更のため)。
- TalkBack実機での読み分け・large font実機表示・end-to-end evidence は未実施 (実装PR・evidence PRの対象)。
- `exchange_run_busy` 文案がCTA時拒否の案内としてそのまま適合するかは実装時に確認 (適合しなければ新規string)。

## Execution checklist

- [ ] Current behavior reproduced (即時attach/start + 1行statusをtestで固定した上で置換)。
- [ ] summary純粋関数とholder遷移の失敗testを先行追加。
- [ ] Minimal implementation (ImportSuccess + CTA + discard + strings + 競合row無効化)。
- [ ] Migration/recovery verified (不要 — 変更なしの確認)。
- [ ] Full relevant verification completed (unit + regression + a11y/large font + device evidence)。
- [ ] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-16: 初回draft (baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` = origin/main)。現行実装 (`ExchangeFlowUi.import()` の即時attach/start、失敗専用ImportOutcomeScreen、Idle「整理を開始」row・Back handlerの現状) をコード確認して作成。
