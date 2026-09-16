# Implementation Plan: External Agent Exchange workflow

> Issue: #205
> Spec: [spec.md](./spec.md)
> Status: implemented — spec/planは2026-09-16にaccepted (ChatGPT 2nd re-review Approve、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/205#issuecomment-5683891236)、head `0e4f154cfd1c3dec083415eaa17cf9789d5eb588`)。依存する #204 contractも **accepted・実装済み** (現main)。実装は本planのExecution checklistに従う。

## Re-entry status

- 2026-09-13: 前回snapshot (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`) を検証し、現行main `f9afd8bfde121932c0c8ed965225d52a84d86ab4` をmergeして再anchorした。main差分のうち本planに関係する変更: #228 (scope-composed run・missing-app selection)、#235 (widget strategy placement・semantic placement role)、#271/#288 (durable status・diagnostics export filename)、requirements.md FR-016 implemented化 (FR-017は未割当のまま)。#204はdraftのまま (branch `issue-204-spec-plan` commit `65b9fc859d` で同一baselineへre-anchor、未accept・main未取り込み)。既存のdesign・change set・verification構造に影響する矛盾は見つかっていない。
- 2026-09-14: Owner review "Request changes" (2026-09-13、snapshot `517adbe4` 基準) に対応し、main `c5274b5d0d` をmergeして再anchor。**P1 (process death)**: #205側のprocess-local前提を撤去し、pending export identity・ref mappingのdurable保持を #204 draft (2026-09-13 review対応revision `324e6182ae`) のexport session (`ExportSessionStore`) へ一元化する設計へ変更。process recreation後importを検証対象 (AC-11) へ追加。**P1 (framing所有)**: exchange framingを #205所有と確定し (#204はpayload本体のみ)、`IntentImportParser` をframing抽出 (#205) とpayload検証 (#204) の2段に分離。**P2 (prompt injection)**: security test oracleをfail-closed中心へ変更。main差分 (`f9afd8bfde` → `c5274b5d0d`) の確認: #300 (API 36 UI lane window focus gating、test infra)、#287 (grid変更時のunknown lock回収、`LockAuthoring.kt` folder child rank)、#283 (strategy picker選択表示、RadioButton)、#308 (Compose focus同期fix)、#304 (CI emulator evidence capture、docs)。`ManualOrganizationPreferences.kt` の差分はfocus/readiness制御とstrategy picker表示であり、manual run state machine・preview/confirm surfaceという本planの統合点の構造は不変。planning seam・`InputProvenance`・organizer module構造への変更はなし。#204は依然draft (再review待ち) のため実装blockerは継続。
- 2026-09-15: Owner re-review "Request changes" (snapshot `02f7f90` 基準、残指摘2点) に対応し、main `397d3fd957` をmergeして再anchor。**P1 (process death後のrun再構築)**: import成立後の接続をfresh run再構築 (新RunId・既存run flow・gate経由) と固定し、#228 selectionは再構築せず再選択とすることをdesign/data flow/AC-11へ反映。**P2 (disclosure順序)**: data flowを `privacy mode選択 → package生成 → Pre-send Disclosure → explicit send → 同一packageをtransport` へ修正し、packageのimmutable value化と同一性保証をdesignへ反映。main差分 (`c5274b5d0d` → `397d3fd957`) の確認: #298 (Nova restore reload spec/plan)、#299 (capture invariant実装。`CaptureInvariant.kt` 新設、`OrganizationInputComposer.kt` 変更は `CaptureFailureObserver` のsignature拡張 (diagnostics目的) のみでcomposition input構造は不変)、#315 (bounded CI evidence capture、`DiagnosticsLogger` の `invariant=` field追加)。planning seam・preview path・本planの統合点への構造変更なし。#204は2026-09-14 re-review対応revision `12f773ad61` でdraft継続 (再review待ち・gate Q1/Q3/Q4未解決) のため実装blockerは不変。
- 2026-09-16: Owner re-review "Request changes" (2026-09-15、snapshot `376dc35` 基準、指摘3点) に対応し、main `0cf82bc1e6` をmergeして再anchor。**P1 (framing具体形式)**: framingを完全行marker `-----BEGIN/END NUNULAUNCHER INTENT-----` + typed失敗3種 (`FRAMING_MISSING`/`FRAMING_AMBIGUOUS`/`FRAMING_EMPTY`) として固定し、parser設計・AC-4 corpusを具体化。**P1 (session置換semantics)**: 新規生成開始をsession置換確認でgate、未送信package取消は `ExportSessionStore.invalidate` による明示的失効とする設計へ変更 (AC-13新設)。**Re-anchor**: 差分 `397d3fd957..0cf82bc1e6` は #203実装 (PR #321) と #204 acceptance + 実装 (PR #322) そのものである。`organizer/ui` は差分ゼロ (run state machine・preview/confirm surfaceの統合点は不変)。`organizer/planning`・`organizer/integration` の差分は #203/#204実装自身 (`OrganizationInput.intentPreferences`、`PolicySourceKind.PERSONALIZED_INTENT`、`FullRunExecution` preference消費、`PlacementAllocator` cell hint、usage signal source類)。#204の実装blockerは解消し、open questions (transport・instruction・言語・size) をspec Decisions 1–5として解消した。
- 2026-09-16 (2nd): ChatGPT re-review "Request changes" (snapshot `9b1cd9d` 基準。前回3指摘は解消確認済み、新指摘 P1×1/P2×1) に対応。baselineは `0cf82bc1e6` のまま (re-anchor不要)。**P1 (import envelope上限)**: import text全体へのenvelope上限 1 MiB (UTF-8 byte基準、#205所有。#204 `MAX_INTENT_BYTES` とは別契約) を導入。pipeline入口で正規化・走査の前にbyte検査、全import経路 (貼付付け・clipboard・file) へ同一適用、超過は typed失敗 `INPUT_OVERSIZE` (zero-write)。corpus (巨大prefix/suffix + 小valid payload、marker不在巨大入力、境界値) をAC-4へ追加。**P2 (SessionExportReconstructor seam)**: production input sourceを「export生成とimport再構築が共有する単一adapter (canonical capture → `ExportInputs`/`CanonicalStructuralInputs`。既存 `ProductionOrganizationInputComposer` / `FullTargetSetMaterializer` のfull-target composition経由)」としてDesignへ明記。#228 scope selectionはrun内概念でexchange flow非関与と固定。parity contract test (同一structural stateからのreconstruct一致、signal/label変化の無影響、構造変化で `CONTEXT_STALE` 収束) をVerificationへ追加。Change set表の「#204側module」行を「契約・既存validator等の意味は変更しないが同packageへadditive helperを追加」へ文言統一。
- 2026-09-16 (3rd): **実装merge**。PR #325 (commit `3df9c7af`) がmergeされた。Execution checklistの残項目のうち「Current behavior reproduced」「framing parser・package composer・generation gateの失敗test先行」「Minimal implementation」「AC-11/AC-13含むverification」「PR evidence記録」は本mergeで達成。「Physical-device representative workflow evidence」は後続evidence PRの対象。

## Current evidence

origin/main (`0cf82bc1e61c1874b280a7120dff9594be4fef71`) 時点の確認事実:

- **#204 (accepted・実装済み)**: `lawnchair/src/app/lawnchair/organizer/personalization/` に純粋packageとして `ContextExportBuilder.kt`、`ContextExportCodec.kt`、`ContextExportModels.kt` (定数: `SCHEMA_VERSION="personalization-context-v1"`、`INTENT_SCHEMA_VERSION="personalized-intent-v1"`、`MAX_EXPORT_ITEMS=512`、`MAX_EXPORT_BYTES=256KiB`、`MAX_INTENT_BYTES=128KiB`、自由文上限、`SESSION_TTL_MS=24h`)、`ExportSessionStore.kt`、`IntentCodec.kt`、`IntentIdentity.kt`、`IntentModels.kt`、`IntentPlannerAdapter.kt`、`IntentValidator.kt`、`RandomIdAllocator.kt`、`SourceContextIdentity.kt`、`PersonalizationBuckets.kt`、`PersonalizationSignal{Models,Snapshot,SnapshotSource}.kt`、`SystemUsageAggregator.kt` が存在する。`organizer/integration/AndroidExportSessionStore.kt` が唯一のAndroid/storage境界実装 (`AtomicFile`、`noBackupFilesDir`、単一session file)。test suiteは `tests/unit/app/lawnchair/organizer/personalization/` + `integration/AndroidExportSessionStoreTest.kt`。
- `ExportSessionStore` API (本planが利用するseam): `save(session): Boolean` (失敗時はcallerがexport公開をfail-closed抑制)、`load(exportId): ExportSession?` (**期限切れsessionも返す**。validatorが `SESSION_EXPIRED` と `EXPORT_MISMATCH` を区別)、`active(nowEpochMs): ExportSession?`、`invalidate(exportId)` (失効sessionは以降absentとして読める)。storeは最大1 sessionを保持 (single-active-sessionは保存構造で自然に成立)。
- `ContextExportBuilder.build(inputs: ExportInputs, tier: PrivacyTier, allocator: RandomIdAllocator): BuiltExport` — 純粋・副作用なし。`BuiltExport` は `export` (文書) と `session` (exportId、ref↔`ItemId` map、tier、structural `sourceContextDigest`、signal provenance、時刻) を同時に返す。**sessionのdurable保存はcaller責任** (`save`)。乱数は注入seam (`RandomIdAllocator`、productionは `SecureRandom` 基底)。
- `IntentCodec.encode` はkotlinx.serialization既定 (prettyPrintなし) のため **compact単行canonical JSON** を出力する。`decode(bytes)` は閉schema厳格allow-list (schema外key → `SCHEMA_MISMATCH` 系、`page`/`span` 等のauthority key → `FORBIDDEN_CONTENT`)。**framing設計の根拠**: 正当なJSON (canonical単行・pretty print多行いずれも) はmarker完全一致行を含み得ないため、framingにescapingは不要。
- `IntentValidator.validate(intent, export, session, nowEpochMs, currentStructuralDigest)` — **export文書を引数に要求する**。import時 (process death後を含む) にlauncherが持つdurable stateはsessionのみ (文書は永続しない) であるため、本planのimport orchestrationは **sessionのref map + 現在canonical structural入力から検証用export viewを再構築** する純粋seamを必要とする (後述「Design: SessionExportReconstructor」)。再構築の乖離 (session内refが現在状態へ解決不能等) は `CONTEXT_STALE` へmapする (#204の失敗分類を保存)。
- **planner接続 (実装済み)**: `OrganizationInput.intentPreferences` (optional field)、`PolicySourceKind.PERSONALIZED_INTENT` (第7 policy input、sentinel identity `none`/`sha256Canonical("personalized-intent:none")`)、`IntentPlannerAdapter` (validated intent → preference projection)、全executor (canonical・page-local lift-then-place・global compact・widget stream) でのpreference消費が `FullRunExecution`/`PlacementAllocator` に実装済み (`IntentPreferenceConsumptionTest`、`IntentPreferenceStrategyMatrixTest`、`WidgetIntentAuthorityTest` が存在)。本planはvalidated intentをこの既存seamへ渡すのみ。
- organizer module構造は `lawnchair/src/app/lawnchair/organizer/{planning,integration,ui,rules,locks,diagnostics,application,personalization}` (DESIGN.md §9と一致)。**`organizer/ui` は `397d3fd9..0cf82bc1e6` の差分ゼロ**。`ManualOrganizationRun` は `ManualOrganizationModule` (process-local composition holder) がprocess単位singletonで保持、`stateHolder` (初期 `Idle`)・`activeOperation`・`pending` はmemory上のみ。run開始 (`start()` → `beginOperation()`) は `OrganizationOperationLease` gate (Kind.RUN) を獲得し、active操作中は `StartOutcome.Busy`。#228 `State.Selecting` の非永続 (D-1) は不変 (2026-09-15に `397d3fd9` で検証、ui差分なしにより同一事実)。
- 外部export precedent: `organizer/diagnostics/export/{ExportUi.kt, ExportWriter.kt, DiagnosticsExportFilename.kt}` (user-initiated export、diagnostics port経由、redaction規則は [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md))。
- clipboard precedent: `lawnchair/src/app/lawnchair/util/ClipboardUtils.kt` (現baselineで存在確認済み)。Share Sheet (`ACTION_SEND` + chooser) もbugreport経路で使用実績あり。
- #203 (usage signals) は**実装済み** (PR #321): `PersonalizationSignalSnapshot` はcompositionごとに再構築されるdynamic input (`schemaVersion` + `contentDigest` でcontent-addressed)。`usageSignals` projectionは #204 accepted契約 (bucket ordinal・tier制御・不在時field省略) に固定済み。

## Design

### Modules and interfaces

新規module `lawnchair/src/app/lawnchair/organizer/personalization/exchange/` (純粋部。Android-free。`personalization` packageのpurity guard適用範囲内) と `organizer/integration/exchange/` (transport adapter) および `organizer/ui/` 内のexchange UI:

- `exchange/ExchangeContract.kt` (pure): framing marker定数 (`-----BEGIN NUNULAUNCHER INTENT-----` / `-----END NUNULAUNCHER INTENT-----`、CONTEXT側 `-----BEGIN/END NUNULAUNCHER CONTEXT-----`)、**import envelope上限 `MAX_EXCHANGE_IMPORT_BYTES = 1 MiB` (UTF-8 byte基準、#205所有。#204 `MAX_INTENT_BYTES` とは別契約)**、package/framingのgrammar型、typed失敗型 (`FRAMING_MISSING` / `FRAMING_AMBIGUOUS` / `FRAMING_EMPTY` / `INPUT_OVERSIZE`)。
- `exchange/ExchangePackageComposer.kt` (pure): #204 `ContextExportBuilder.build` の結果 (`BuiltExport.export` のcanonical JSON) + instruction template (英語。Goal / You may / You must / Response format の4section。Response formatにINTENT marker行を明示) → exchange package text。instruction部とdata部 (CONTEXT marker行で囲んだ単行canonical JSON) を分離した単一text。逆方向の `parsePackageStructure` (AC-1機械検証用: marker行による構造検証) を同梱する。composerは生成時に完了する **immutableな値** を返す。
- `exchange/IntentImportParser.kt` (pure): **#205所有のexchange framing抽出**。入口で最初に **envelope上限検査** (import text全体のUTF-8 byte長 > `MAX_EXCHANGE_IMPORT_BYTES` → `INPUT_OVERSIZE`。正規化・走査の前にfail) を行い、通過した入力text (UTF-8、CRLF/CR→LF正規化、先頭BOM除去) から完全行marker (行頭行末ASCII空白除去後に完全一致、case-sensitive) で区切られた領域を抽出する。成功条件: BEGIN marker行ちょうど1つ + END marker行ちょうど1つ + BEGINがENDより前 + 領域非空。失敗は #205固有のtyped framing failure — `FRAMING_MISSING` (marker不在・END先行) / `FRAMING_AMBIGUOUS` (marker行複数出現、nested相当行を含む) / `FRAMING_EMPTY` (有効なmarker対だが領域が空白のみ) — のsealed resultで #204のvalidation failureと区別する。抽出領域はverbatim (領域先頭末尾の空白のみtrim) で、schema解釈を行わず次段へ渡す。**byte長判定の実装規約 (approving reviewのnon-blocking note)**: 巨大`String`に対する`toByteArray(UTF_8).size`は同サイズ級のbyte arrayを追加確保するため、`length > limit` (char数によるfast reject: UTF-8では byte長 ≥ char長) + boundedなbyte count (上限到達で打ち切るcounter) で上限判定自体の追加memoryもboundedにする。
- `exchange/SessionExportReconstructor.kt` (pure): durable session (`ExportSession.itemRefs`) + 現在の `CanonicalStructuralInputs` から `IntentValidator.validate` が必要とする検証用export viewを再構築する。再構築が現在状態へ解決不能な場合は `CONTEXT_STALE` 相当のtyped結果を返す (#204失敗分類を保存)。**#204実装へのadditive変更** (accepted #204契約・既存validator/builderの意味は変更しない。`ContextExportBuilder` と同一のprojection述語を、新規乱数割当ではなくsession固定ref mapで再適用する)。
- **canonical入力sourceの単一化 (P2対応)**: exchange flowのcontext export生成とimport時の検証用export view再構築は、**同一の単一adapter** (canonical capture → `ExportInputs` / `CanonicalStructuralInputs` 導出。既存 `ProductionOrganizationInputComposer` / `FullTargetSetMaterializer` のfull-target composition経由) を共有する。export時とimport時で別ロジックを組むと同一homeでもprojection drift (ref対応・role・mobility・grid投影の変化) が起こり得るため、導出は1箇所に置き両経路から利用する。#228のmissing-app selection / scope-composed target compositionは **run内のcomposition概念** であり、run外のexchange flow (export生成・import再構築) では関与しない — 再構築はexport生成と同じfull-target compositionを使う。parityはcontract testで固定する (「Verification」のreconstruction parity test)。
- `exchange/ExchangeImportPipeline.kt` (pure): **envelope上限検査** → framing抽出 → #204 `IntentCodec.decode` → session照会 (`load`) → `SessionExportReconstructor` → `IntentValidator.validate` までを束ねる純粋pipeline。全失敗 (`INPUT_OVERSIZE` + `FRAMING_*` 3種 + #204 12 class) を統一したtyped resultで返す (UIの失敗表示はこれに一対対応)。session/digestの再計算入力は引数注入 (Android型を漏らさない)。
- `exchange/ExchangeGenerationGate.kt` (pure): session置換確認の判断部。`active(now)` の有無とuser確認結果から `Proceed` / `RequiresConfirmation` / `Aborted` を返す。**承認が得られるまで生成 (build・save・compose) は実行しない**。
- `integration/exchange/` (thin adapter): clipboard (`ClipboardManager`)、Share Sheet (`ACTION_SEND` text/plain + chooser)、file (SAF `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`、text/plain) の各transport。純粋部の結果 (package text) を受け取りAndroid APIへ出すだけ。失敗はtyped result。transport adapterは **disclosure済みのpackage値をそのまま受け取る** 引数のみを持ち、packageを再構成・差し替える経路を持たない。**import側は全経路でenvelope上限を最初に適用する**: file importは `MAX_EXCHANGE_IMPORT_BYTES` + 1 byteまでのbounded readで全量materialize前にfail、貼付付け・clipboard入力もpipeline入口のbyte検査に先に失敗させる (UI入力欄での受領時にも同じ上限で検証する)。超過は `INPUT_OVERSIZE` (zero-write)。
- `ui/`: exchange導線 (manual run操作非active時のみ提示)、privacy mode選択、session置換確認、送信前確認 (生成済みpackageの内容提示)、import画面 (貼付付け・file選択)、失敗・reject表示。既存 `ManualOrganizationRun` / preview surfaceからの導線。UI層はexport↔ref対応をprocess memoryにのみ保持しない (process recreation後のimportは #204のexport session seam経由で再解決する)。
- **package生成とdisclosureの順序**: `privacy mode選択 → [gate確認 (activity session存在時のみ)] → #204 context export生成 (`build`) → session保存 (`save`。失敗はtyped生成失敗でpackageを出さない) → ExchangePackageComposer → package text (生成時に完了するimmutableな値) → Pre-send Disclosure (生成済み値の提示) → explicit send → 同一値をtransport` の順序で固定する。確認対象とtransport対象の同一性は同一immutable valueの受渡しで構造的に保証する (package本文の永続化・digest等の追加永続化はしない)。privacy mode変更等で再生成が起きた場合は既存packageを破棄し、送信前確認からやり直す (flow内再生成はgate確認不要。当該flowのpackageは未送信と追跡できるため)。
- **session置換と取消 (re-review P1対応)**: 新規exchange flow開始時に `ExportSessionStore.active(now)` が非nullならsession置換確認 (#204 single-active-sessionにより既存exchangeが無効化される旨の明示) を必須とする。承認 → 生成続行 (既存sessionは新規saveにより無効化)、辞退 → flow中止 (既存session不変・既存exchange宛回答は引き続きimport可能)。送信前確認の取消は当該未送信sessionを `invalidate(exportId)` で明示失効させる (packageは一度もtransportされておらず、他に影響しない)。launcher側は送信済み/未送信を判定できないため、gateはactivity sessionの存在に一律適用する。
- **process death耐性とimport後のrun再構築**: pendingなexport identity・ref↔内部ID mappingのdurable保持は #204実装の `ExportSessionStore` (durable・TTL 24時間・app-private・backup対象外) が担う。本planは新規storageを追加せず、import時にpayloadの `exportId` から #204 session seamへ問い合わせるのみ。**import成立後の接続はfresh run再構築のみ** であり、既存 `ManualOrganizationRun` のrun state (process-local singleton・非永続) の復元は行わない: validated intentからのrun開始は既存 `start()` / single-active-operation gate経由で新RunIdを発行し、run state machineはIdleから通常flow (detection → selection → planning) を辿る。#228のselection・scope構成は非永続が既存不変条件 (D-1) のため復元せず、通常flowで再選択する。stale bindingは2段: import時の #204 structural `sourceContextDigest` 検証 (`CONTEXT_STALE`) と、fresh runの既存planning/apply時revision stale check。active操作中にimportが到達しgateが `Busy` を返した場合は、typed案内 (run終了後の再import) でzero-write終了し、validated intentを保持しない。

### Data flow

```text
Manual run UI (run非active時導線) → exchange flow開始
  → ExchangeGenerationGate: ExportSessionStore.active(now) != null → session置換確認
      (承認が得られるまで生成不開始。辞退なら中止・既存session不変)
  → privacy mode選択 (EXTERNAL_REDACTED既定 / EXTERNAL_WITH_LABELS)
  → #204 ContextExportBuilder.build (既存canonical入力 + #203 signal snapshot、副作用なし)
  → ExportSessionStore.save (失敗 → typed生成失敗、packageを出さない)
  → ExchangePackageComposer → package text (生成時に完了するimmutableな値)
  → Pre-send Disclosure (生成済みpackageの内容提示)
      ├─ explicit send → transport (clipboard/share/file) は disclosure済みの同一package値を送出
      │    → [外部agent、app外] (この間のprocess deathは #204 durable sessionが担う)
      └─ cancel → ExportSessionStore.invalidate(exportId) (当該未送信session失効)
外部agent返答 → import (貼付付け/file)
  → IntentImportParser: framing抽出 (#205 typed framing failure)
  → ExchangeImportPipeline: #204 IntentCodec decode + session照会 (load)
      + SessionExportReconstructor + IntentValidator (zero-write)
  → validated intent → fresh run再構築 (既存start()/gate経由で新RunId、通常flowのselection再選択)
  → 既存 planner (#204 `intentPreferences` 投影・消費) → preview (#194) → confirm (#195) → apply (spec 13)
```

全失敗 (transport失敗、framing抽出失敗、validation reject、import後のrun開始gate拒否 (`Busy`)) はUIでtyped表示、書込みなし。validated intentはrun開始できなかった場合も保持せず、回復は再importである。

### Alternatives rejected

- promptとdataを単一自由文に混ぜる構成: instruction/data分離が機械検証できず、injection脅威モデルが破綻するため採用しない。
- 返答textからの寛容なJSON抽出 (正規表現・best effort): partial apply・曖昧解釈の危険がありIssueが明示的に禁止するため厳格framingのみ。
- **framingにmarkdown code fence (``` ) を使うoption**: agentが同一返答内の他のcode blockにもfenceを使うため複数候補blockが常態化し、一意抽出契約と相性が悪い。armor様の完全行marker (PGP ASCII armorと同型) はagentにも再現されやすい形式であり、marker行の完全一致判定で一意性が保証できるため採用しない。
- **framingで前後自由文を禁止するoption (marker対のみを返答させる)**: agentは説明文を添えることが多く、禁止すると再依頼率が上がるのみでsafety上の利益がない (marker対が既に領域を確定する)。自由文は許容し、領域外は無視する。
- export/importの中間artifact (package本文・返答text) の永続化: V1で必要な使用要件がなく、privacy表面を広げるため永続しない。ただし **export identity・ref↔内部ID mappingのprocess-local保持** は #205側で採用しない (2026-09-14 revisionで取止め): 標準flowは外部アプリ滞在中のprocess deathを含み、durable保持は #204のexport sessionに一元化する。#205が独自に複製すると二重正本・不整合を生む。
- process recreation後のvalidated intentを **既存runの復元** として扱うoption: run state (`ManualOrganizationRun` の `activeOperation`/`pending` 等) はprocess-local singleton・非永続が既存の設計であり、これを復元するにはrun stateの新規永続化 (scope・selection・preview状態) が必要になる。復元対象はprocess跨ぎで意味が変わるメモリ状態を含み、既存single-active-operation gate・#228 非永続不変条件 (D-1) との整合も必要になるため、V1では採用しない。fresh run再構築 (新RunId・通常flow・再選択) は既存state machineをそのまま再利用し、stale bindingも既存2段検証 (`CONTEXT_STALE` + revision stale check) で賄える。
- disclosure前にpackageを生成しないoption (確認→生成→送信): 確認時に実packageが存在せず、確認内容とtransport内容の一致を保証できない (TOCTOU的ズレ、label-inclusive mode・確認中のstate変化で顕在化) ため採用しない (2026-09-15 revisionで順序を修正)。
- framing規格を #204側へ含める option: #204のscopeはpayload schema・validator・sessionであり、外部agent向けUI規格を含めると契約受入が #205のUX設計に引きずられる。framingは本plan (#205) が所有し、accepted #204のpayload schemaを入力として文言を確定した。
- **session activationをsend承認時へ寄せるoption (re-review P1対応で検討)**: accepted・実装済み #204契約は生成時 (`build` + `save`) にdurable sessionを作成する (外部往復中のprocess death耐性の前提)。activation時点を変えるには #204 accepted契約と実装 (`AndroidExportSessionStore`) の再開が必要になり、得られる利益 (確認gate不要化) は #205側の事前確認で同等に達成できるため採用しない。事前確認 + 取消時の明示的失効で「無言の無効化経路なし」を保証する。
- **送信前確認取消時にsessionを残すoption**: 未送信packageのsessionがactivityのまま残ると、次回flow開始時のgate確認が「取消済みのexchange」に対して表示されUXが混乱する。再生成は安価であり、`invalidate` APIが #204に存在するため、取消時の明示的失効を採用する。
- provider別deep-link / automation: provider lock-inのため非採用 (Non-goals)。
- **share-back受信 (`ACTION_SEND` receiver) のV1組み込み**: manifest receiver登録によりlauncherが全textのshare targetとなり受信面が広がる割に、貼付付けで同等UXが成立するためV1では非採用 (spec Decision 3。V2再検証)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/exchange/` (新規) | ExchangeContract, ExchangePackageComposer, IntentImportParser, SessionExportReconstructor, ExchangeImportPipeline, ExchangeGenerationGate (pure) | 計算とtransport分離、test容易性 (AGENTS.md設計規約)。framing・gate・再構築は #205所有の純粋logic |
| `organizer/personalization/` (#204実装へのadditive) | SessionExportReconstructorが依存する、session固定ref mapでのexport view再構築entry (builder近傍)。**accepted #204契約・既存validator/builder等の意味は変更しない。同packageへadditive helperを追加するのみ** | `IntentValidator.validate` がexport文書を要求するため |
| `organizer/integration/exchange/` (新規) | clipboard/share/file transport adapter | Android APIをthin adapterに局所化 |
| `organizer/ui/` | exchange導線、privacy mode・session置換確認・送信前確認・import・失敗UI | 既存organizer UI surfaceとの一貫性 |
| `organizer/integration/` | (必要最小) validated intent → planning入力への接続点、およびexport生成/import再構築共有のcanonical入力導出adapter接続 | 既存 `ProductionOrganizationInputComposer` / `FullTargetSetMaterializer` + #204 `IntentPlannerAdapter` 経路の再利用 |
| #204側既存実装 (validator・codec・builder・session store・planner adapter) | 仕様・意味の変更なし。上記additive helperのみ同packageへ追加 | 契約所有は #204。session (process death耐性) も #204所有 |

## Migration and recovery

- DB schema変更なし。新規追加のみ。既存run (personalization未選択) の挙動・provenance不変。#205側は新規storageを追加しない (durable sessionは #204側の`ExportSessionStore`が所有し、Launcher favorites DBと独立・backup対象外)。
- exchange package本文・返答textは永続しないため、rollback = 機能を表示しない (feature flag相当の導線制御は実装時に検討)。release rollbackで特別な後処理は不要 (残余する可能性があるのは #204 session fileのみで、TTL 24時間で自然失効)。
- 運用中の回復: 返答text喪失 (clipboard消失・agent側履歴喪失等) → 外部agentからの再copy、または再export (session置換確認経由。#204 single-active-session規則により旧sessionは無効化)。session失効後の到着 → `SESSION_EXPIRED` によるtyped失敗と再export案内。往復中のhome変更 → `CONTEXT_STALE` によるtyped失敗と再export案内。送信前確認取消 → 当該session失効 (再開は再生成)。import成立後にrun開始がgate拒否 (`Busy`) された場合 → typed案内に従い既存runを終了後に再import (validated intentは非永続のため保持しない)。import成立後・planning前のhome変更 → 既存Stale path (zero-write終了)。
- 適用・復旧は既存spec 13 recovery pathをそのまま使い、本workflowは新しい適用経路を作らない。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | package構造 unit/contract test (CONTEXT marker行による分離検証・`parsePackageStructure` 往復) | unit test (pure module) |
| AC-2 | export field契約test (#204 suite連携 + package level検証) | unit test |
| AC-3 | 確認画面・transport instrumentation test | instrumentation / 手動 |
| AC-4 | framing parser / import pipeline unit test。reject corpus: marker不在・END先行 (`FRAMING_MISSING`)、marker複数/nested (`FRAMING_AMBIGUOUS`)、空block (`FRAMING_EMPTY`)、marker前後自由文許容、JSON value内marker部分一致の無影響、CRLF/CR/BOM正規化の決定性property test、**envelope上限 (巨大prefix/suffix + 小valid payload → `INPUT_OVERSIZE`、marker不在巨大入力、境界値 exact-limit/limit+1、上限内受理の対照、file bounded readの早期fail)** | unit test |
| AC-5 | validator failure表示 test (#204 12 class + `FRAMING_*` 3種 + `INPUT_OVERSIZE` の16種失敗表示) | unit + UI test |
| AC-6 | import→preview→confirm統合test | instrumentation |
| AC-7 | injection corpus security test。oracleはfail-closed中心 (影響された応答がunsafe writeに到達しない、zero-write、planner制約不変)。agentのinstruction遵守はoracleに含めない | unit test |
| AC-8 | 依頼review (private API/UI automation/`ACTION_SEND` receiver不使用) + plain text schema test | review / unit test |
| AC-9 | a11y・環境失敗 evidence | 手動 (TalkBack, Switch Access, large font, clipboard無効) |
| AC-10 | representative workflow evidence (app切替往復を含む) | physical device + ChatGPT/Gemini |
| AC-11 | session store seam経由のprocess recreation simulation test: export生成 → session seamの保持状態を残したままparser/pipeline instanceを破棄・再生成 → 失効前import成功。失効後は `SESSION_EXPIRED`、不明sessionは `EXPORT_MISMATCH`。#204 `ExportSessionStore` suiteと連携。加えてprocess recreationを挟んだ **import → fresh run再構築 (新RunId) → preview表示** のintegration test + physical device (AC-10と兼ね可)。**reconstruction parity contract test (P2対応)**: `ContextExportBuilder.build` → session保持 → 同一structural stateから `SessionExportReconstructor` で再構築し、validator/planner authorityに使うfield (`ref` 対応・role・mobility・grid/pageCount) が一致すること。`sourceContextDigest` 対象外の値 (usage signal・label) だけを変えた場合は再構築・validation結果が不当に変わらないこと。structural stateを変えた場合は再構築/validatorが `CONTEXT_STALE` に収束すること | unit test (session seam経由) + integration test + physical device |
| AC-12 | package同一性のunit test (composer → disclosure → transportが同一immutable valueを受渡し、transport adapterがpackageを再構成しない) + 確認画面順序のUI test (privacy mode変更時の再生成・確認やり直し、確認前送信経路の不在) | unit test + UI test |
| AC-13 | `ExchangeGenerationGate` unit test (承認なし生成不開始・辞退時既存session不変・取消時 `invalidate` 呼び出し。session store fake使用) + 確認UI test + **E1送信済み → 確認承認によるE2生成 → E2取消 → E1 import** のscenario test (`EXPORT_MISMATCH` zero-write・失敗説明に再生成案内) + **辞退時はE1 importが引き続き成立** の対照test | unit test + UI test |

含める観察: unit/contract (package構造・framing parser・package同一性・gate判断・envelope上限境界・reconstruction parity)、property (framing抽出の決定性・冪等性、marker文字列入れ替えに対する頑健性)、security (injection corpus、fail-closed oracle)、UI/a11y、failure injection (transport失敗、session失効・不在、`CONTEXT_STALE` 相当、`INPUT_OVERSIZE`、import後のrun開始gate拒否、session保存失敗)、process recreation simulation + import→fresh run→preview integration (AC-11)、session置換scenario (AC-13)。

## Documentation updates

- [ ] spec status/history (acceptance時にacceptedへ)
- [ ] CONTEXT.md (domain language: 外部エージェント交換、交換パッケージ、送信前確認、持ち帰りIntent取り込み、交換フレーミング、交換セッション置換確認。acceptance時。#204用語と合わせて調整)
- [ ] DESIGN.md §4/§9 (personalization exchange module追記、acceptance時)
- [ ] ADR: 初回transport選定・framing形式が「変更困難・理由がコードから分からない・実際の選択肢あり」を満たすかはimplementation後の判断とする (現時点ではspec Decisionsに根拠を記録)
- [ ] requirements.md (FR-017との対応整理、acceptance時)

## Dependencies and blockers

- **#204 (accepted・実装済み — blocker解消)**: schema、tier、validator、intent identity、export session (durable保持・TTL 24時間) は #204 accepted契約として確定し、現mainに実装が存在する (`organizer/personalization/` + `AndroidExportSessionStore`)。**exchange framingのみ本plan (#205) が所有し、このrevisionで固定した** (完全行marker・typed失敗3種)。残る依存は「本spec/planのowner acceptance」のみである。
- #203 (実装済み): `PersonalizationSignalSnapshot` と `usageSignals` projectionは #204 accepted契約に固定済み。signal不在でも本workflowは成立する (optional)。
- #206 (OPEN): 兄弟issue。transport/provider adapterの共通化はintent契約 (#204) のみで行い、UI・provider選択は独立に保つ。

## Risks

- clipboardのsize制限・OS毎の挙動差が大規模layoutでexportを壊す可能性 (package ≤256 KiB + instruction。spec Decision 5のfail-closed + file誘導で対処。実測はAC-9/AC-10)。
- 外部agentの返答がframing指示に従わない頻度が高い場合、UXが反復になる。framingはarmor様marker + 前後自由文許容で再現されやすい形式を選び、typed parse失敗時に再依頼を案内する。遵守率はAC-10 evidenceで早期に把握する。
- 往復中にuserがhomeを変更すると `CONTEXT_STALE` rejectとなり、再exportが必要になる。失敗説明のUX copyが「なぜ拒否されたか・次に何をすべきか」を正確に伝える必要がある。session TTL 24時間は長いagent往復を許容する値 (#204 accepted)。
- session置換確認はactivity sessionが残る期間 (送信後〜TTL/無効化まで) の新規生成に必ず1 step追加する。取消時の明示的失効とTTLで滞留は解消されるが、頻用時の摩擦はV2で再検討しうる。
- `SessionExportReconstructor` が #204実装へのadditive変更であるため、#204側のprojection述語変更時に再構築側の追従が必要になる (同一package内に置き、projection述語を共有することで単一正本化する)。
- V1でexchange導線をmanual run非active時に限定する設計は、active run中の往復開始を許さないUX制約である。scope-composed run (missing-app選択済み) の途中でのexchange利用需要が実使用で示された場合、V2での並行設計再検討が必要になる (run state非永続不変条件との整合が課題)。

## Explicitly unverified areas

- 外部agent (ChatGPT/Gemini) がinstruction部・framing指示 (完全行marker) に実際にどの程度従うかは未検証 (AC-10で実施)。
- clipboard/Share Sheetの実機挙動 (size上限、target有無) は本draft時点で未計測。
- 本planのevidence再確認は2026-09-16時点のmain `0cf82bc1e6` (docs-only差分に対して実施)。build・testは未実行 (docs-only変更のため、full buildは対象外)。

## Execution checklist

- [x] #204 accepted・実装済み (blocker解消。spec/planの仮称解消を含む)。
- [x] 本spec/planのowner acceptance (2026-09-16、ChatGPT 2nd re-review Approve。status: accepted)。
- [x] Current behavior reproduced (導線不在の確認)。
- [x] framing parser・package composer・generation gateの失敗testを先行追加 (framing失敗は #204 validation failureと区別されること)。
- [x] Minimal implementation (composer → transport → import → 既存preview接続)。
- [x] Process recreation simulation test (AC-11: session解決 + import → fresh run再構築 → preview のintegration evidence) と session置換scenario test (AC-13) を含むsecurity/a11y/environment failure verification completed。
- [ ] Physical-device representative workflow evidence recorded (app切替往復を含む)。
- [x] PR evidence and remaining risks recorded。

## Re-entry history

- 2026-09-10: 初回draft (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962`)。
- 2026-09-13: baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` へのmerge re-anchor。Current evidence更新 (organizer構造の #228/#235 差分、`DiagnosticsExportFilename.kt`、`PolicySourceKind` 6値)、#204 draft再anchor状態をDependenciesへ反映。design・change set・verificationの構造は変更なし。
- 2026-09-14: Owner review "Request changes" への対応とbaseline `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` へのmerge re-anchor。process death耐性を #204 durable export session前提へ設計変更 (P1)、exchange framingの #205所有を確定しparser設計を2段へ分離 (P1)、security test oracleをfail-closed中心へ変更 (P2)。AC-11と検証・migration・risk節を更新。#204はdraft継続のため実装blockerは不変。
- 2026-09-15: Owner re-review "Request changes" (残指摘2点) への対応とbaseline `397d3fd95764878366e7c9e5ce41ab65e6f3f9ca` へのmerge re-anchor。**P1**: import後の接続をfresh run再構築 (新RunId・既存gate・#228 selection再選択) と固定し、既存run復元optionをAlternatives rejectedへ記録。Current evidenceへ `ManualOrganizationModule` のprocess-local性・gate・非永続selectionの検証事実を追加。**P2**: data flowを生成 → disclosure → 同一値transportの順序へ修正し、packageのimmutable value化とtransport adapterの再構成禁止をdesignへ追加。AC-11拡張・AC-12新設と検証表・checklist更新。
- 2026-09-16: Owner re-review "Request changes" (指摘3点) への対応とbaseline `0cf82bc1e61c1874b280a7120dff9594be4fef71` へのmerge re-anchor。**P1 (framing)**: 完全行marker・typed失敗3種・escaping不要根拠 (`IntentCodec` compact単行JSON) を固定し、parser・corpus・AC-4を具体化。**P1 (session置換)**: `ExchangeGenerationGate` (事前確認) と取消時 `invalidate` をdesignへ追加し、AC-13・scenario testを新設。session activationをsend時へ寄せるoptionをAlternatives rejectedへ記録。**Re-anchor**: Current evidenceを #203/#204実装済みの実体 (`organizer/personalization/` のAPI、`ExportSessionStore.save/load/active/invalidate`、`IntentValidator` のexport文書引数と `SessionExportReconstructor` 必要性、planner `intentPreferences` 消費) へ全面更新。open questionsをspec Decisions 1–5として解消し、実装blockerを「本specのowner acceptance」のみへ縮小。
- 2026-09-16 (2nd): ChatGPT re-review "Request changes" (P1×1/P2×1) への対応。baseline不変 (`0cf82bc1e6`)。**P1**: import envelope上限 1 MiB (UTF-8 byte基準・#205所有) を `ExchangeContract` / pipeline入口検査 / 全import経路適用 / `INPUT_OVERSIZE` typed失敗として導入し、AC-4 corpus・AC-5失敗表示 (16種) を更新。**P2**: canonical入力source単一化 (export生成とimport再構築が共有する単一adapter、#228 selection非関与) をDesignへ明記し、reconstruction parity contract test をAC-11 evidenceへ追加。Change set表の #204側rowを「契約・意味は変更しないがadditive helperを追加」へ統一。
