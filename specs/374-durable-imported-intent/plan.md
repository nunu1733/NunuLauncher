# Implementation Plan: 取り込み済み提案（pending intent）のdurable化とhub status card接続（D-08）

> Issue: #374
> Spec: [spec.md](./spec.md)
> Status: implemented（2026-09-21。PR [#399](https://github.com/nunu1733/NunuLauncher/pull/399)
> merge `9dc3ec8fed`。CI final-status green・独立audit
> [docs/assessment/pr-399-durable-imported-intent.md](../../docs/assessment/pr-399-durable-imported-intent.md)）

## Current evidence

以下はmain `c05435a947`（2026-09-21時点のorigin/main。#365/#366/#369〜#373実装merge後）での
確認済み実装事実である。行番目は目安である。推測は「Explicitly unverified areas」に分離する。

- **pending intentはprocess-local・画面state限定**:
  `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` の
  `ExchangeFlowStateHolder` が `private var pendingValidated: ValidatedPersonalizedIntent?`
  （822行付近）と attempt token（`ImportAttempt`、794行付近。`activeAttempt` 815行付近）を保持する。
  holderは `ManualOrganizationPreferences.kt` でhostingされるcomposition単位で生成され、画面離脱・
  process死で消失する。成功状態は `ExchangeScreen.ImportSuccess(summary, entryKind, attemptToken,
  continuing)`（158行付近。#373でも変更なし・回帰として固定）。
- **import成功時の接続点**: `settleImport()`（886行付近）がvalidation settleを処理し
  `pendingValidated = pipeline.validated`（900行付近）のうえ `ImportSuccess` を採用する
  （906〜917行。summaryは `exchangeImportSummary(pipeline.validated.completed, scopeCount,
  categoryKindByRef = pipeline.validated.export.categories.associate { it.ref to it.kind })`）。
  CTAは `continueImport()`（980行付近）→ `connectRun()`（950行付近）→ `run.start(intent)` /
  `run.attachIntent(validated)`。settleは `settleContinue`（1002行付近）: Success → attempt/pending
  clear・`screen = Closed`、Busy/NotAttachable/Failed → `continuing=false` で成功状態維持。
  破棄は `discardImport()`（1050行付近、zero-write。durable書込なし）。
  D-2のBack確認dialogは `ExchangeImportSuccessBackHandler`（1905行付近・hosting level）。
- **canonical representationとref**: `personalization/IntentCompletion.kt` の
  `CompletedPersonalIntent(exportId, decisions: Map<String, RefDecision>, globalPreference,
  rationale, confidence)`。map key = export-scoped item ref（全export refが恰好1回出現）。
  `RefDecision = Authored(intent: ItemIntent) | UnresolvedAuthored | UnresolvedByOmission`。
  `ItemIntent(ref, importance, desiredGroupRefs: List<String>?, groupSemantic, pageAffinity,
  regionAffinity, preserve)`。`GroupSemantic(categoryRef, proposalLabel)` は
  **exactly-one-of**（`categoryRef != null != (proposalLabel != null)`）で、`proposalLabel` は
  KDoc「run-scoped proposal・never persisted」（#336規則。本Issueがdurable pending intent
  storeへの保存へ改訂する）。`GlobalPreference` は `minimizeMovement: Boolean?` のみ。
  **`proposalLabel` はplanner-effective値である**: `IntentPlannerAdapter.project()` は
  `semantic?.proposalLabel` をそのまま `ItemPreference.groupProposalLabel`（formation key）へ
  渡し、`validated.identity` を `PersonalizedIntentProjection.identity`（policy provenance）へ
  渡す。`IntentIdentityCalculator.canonicalRepresentation()` は `rationale|..|confidence` 行と
  `ItemIntent.canonicalRow()`（proposalLabel含む）からdigestを算出するため、identityの再現には
  算出済みdigestの保存が必要（raw text永続は不要）。
- **summary導出の既存純粋関数**: `personalization/exchange/ExchangeImportSummary.kt` の
  `exchangeImportSummary(completed, scopeCandidateCount, categoryKindByRef)`。出力は件数のみ
  （recognizedCount / noJudgmentCount / 内訳4種 / builtInCategoryCount / userCategoryCount /
  proposedGroupCount（`groupSemantic?.proposalLabel != null` の計数） / placementCount /
  keepCount / minimizeMovement / scopeCandidateCount）。`categoryKindByRef` は現行では
  export文書 `export.categories` から作るが、**session側 `ExportSession.categoryRefs:
  Map<String, CategoryIdentity>`（`BuiltIn`/`UserDefined` 判別）から同一の ref→kind対応を導出できる**。
  `scopeCandidateCount` は `session.scopeCandidates.size`。record+sessionからの再構成に必要な
  追加要素は decisions（ref・proposalLabel text含む）・minimizeMovement・`intentIdentity`
  （保存済み正本）であり、`IntentPlannerAdapter.project` 相当のprojectionが全field再現できる
  （export viewは既存の `SessionExportReconstructor.rebuild(session, current)` から
  再構築。構造digest不一致は `CONTEXT_STALE`）。
- **durable storeの既存pattern（本計画の雛形）**:
  `lawnchair/src/app/lawnchair/organizer/personalization/ExportSessionStore.kt`（純粋seam。
  `save` / `load(exportId)` / `active(nowEpochMs)` / `invalidate(exportId)`）と
  `lawnchair/src/app/lawnchair/organizer/integration/AndroidExportSessionStore.kt`
  （`noBackupFilesDir` + `AtomicFile` + private nested `@Serializable` record + mapping +
  `SCHEMA_VERSION = 2` 検査（不一致はnull退化） + corruption/書込失敗のfail-closed「recordなし」 +
  `synchronized(lock)`。単一active sessionは1 file = 1 sessionで自然に成立）。module accessorは
  `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeSessionStoreModule.kt`
  （module-per-concern object、`@Volatile` instance）。pure package（personalization配下）に
  `@Serializable` は存在しない（wire変換は手書きcodec）— durable recordもintegration側private
  record + pure modelへのmappingとする。
- **session置換の書込点**: `ExchangeFlowController` の private `generate(tier, composedInputs)`
  （100行付近）が `store.save(built.session)`（106行付近）成功後にencode・composeへ進む。
  「新session保存 → 旧pending無効化」の書込順序はこの直後にpending無効化を置くことで実現する。
  同一process内の成功状態/pending（holder側）は現行ではgenerate経路で無効化されない
  （`openImport` / `close` / `onImportTextChange` / `discardImport` / settlesのみ）—
  本Issueで置換時無効化を追加する。`cancelDisclosure()`（134行付近）がpre-send cancel
  （`store.invalidate(session.exportId)`）。
- **#372実装のT-15機構（依頼行で共有する）**: `readActiveRequestIntoSelecting()`（327行付近:
  `controller.nowEpochMs()` と `controller.activeSession()` 読取）・
  `refreshActiveRequest()`（322行付近）・失効時刻にscheduleした1回再読取
  `scheduleExpiryReRead`（346行付近）・残時間導出 `requestRemainingDisplay(expiresAtEpochMs,
  nowMs): RequestRemaining`（2066行付近の純粋関数。`exchange_request_remaining_hours` plurals /
  `exchange_request_remaining_under_hour`）。T-15面は `ExchangeScreen.SelectingPrivacy(
  replacementConfirmationRequired, activeRequestExpiresAtEpochMs, activeRequestReadAtEpochMs)`。
  置換確認は `ExchangeReplacementConfirm`（face。`exchange_replacement_title/warning/confirm/
  decline`。#372実装済みのD-13語彙）。
- **#366実装のhub status card（行の追加先）**:
  `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt`。
  `OrganizerHubPreferences(modifier, run?)`（74行付近。`run ?: ManualOrganizationModule.get(context)`
  でcold process到達）。読取は `LaunchedEffect(showDurableStatus, readinessState)` で
  `coordinator.readDurableOrganizerStatus()`（spec 271 seam）。durable行は
  `hubDurableStatusItems(status)`（197行付近・閉域語彙）で、**提案行・依頼行の挿入点は
  durable status行（138行付近）と開始CTA（139行付近）の間**（TO-BE §13-5「状態→残期限→操作」の
  挿入点。#366 specが明記）。checking行は `HubCheckingLine`。run進行中は
  `showDurableStatus`（`Idle`/`Cancelled` のみ）でdurable行/checking行を隠す（提案行・依頼行は
  session TTL従属でありrun stateと独立。本Issueでは隠蔽対象としない）。
- **startup reconciliation trigger**: `LawnchairApp.ensureOrganizerStartupReconciliation()`
  （128行付近。`AtomicBoolean organizerReconciliationStarted` のcompareAndSetでprocess-scoped
  idempotent。専用threadでmodel load待ち→`layoutApplicationModule.reconcileAtStart()`）。
  callersはLauncher resume・`ManualOrganizationModule.get()`・`ExchangeFlowModule.buildController()`。
  **pending storeのreconcileはstore完結（session store・pending store・clockのみ）のため
  readiness gate・model loadを待たず、trigger threadの先頭で実行できる**
  （spec Contract notes 8。hub未開封のfresh processでも実行される）。
- **置換確認・破棄の現行strings**: `exchange_replacement_warning`（en 1365行 / ja 448行。
  #372実装済みのD-13語彙「新規作成すると以前の依頼は破棄され、その回答は取り込めなくなります。
  続行しますか?」— 取り込み済み提案の破棄追記が本Issueの差分）。取り込み破棄:
  `exchange_import_discard`（破棄して閉じる）/ `_confirm_title/_confirm_body/_confirm` /
  `exchange_import_discarded_guidance`（en 1481〜1485行 / ja 563〜567行）。CTA:
  `exchange_import_cta_idle`（この提案で整理を進める）/ `exchange_import_cta_run_in`
  （選択に戻って整理を確定する）→ T-18語彙（この提案で続ける）へ改訂。
- **#373実装の失敗面様式（persistence失敗の追加先）**: `ExchangeImportOutcomeScreen(outcome,
  rawText)` と `ExchangeImportFailureDisplay`（`ImportFailureRemedy { RETRY_IMPORT, REPASTE,
  RECREATE_REQUEST }` + 面レベル常設（中断する `exchange_import_interrupt` = zero-write close・
  診断を開く））。persistence step由来のtyped失敗は同様式（primary remedy = 保存再試行）で追加する。

## Design

### Modules and interfaces

AGENTS.md設計規約（小さなinterfaceの背後へ大きな振る舞いを隠す・既存patternの優先）に従い、
新設は **純粋seam 1 + Android実装 1 + module accessor 1 + reconcile/projection 1** に留める。

1. **`PendingImportedIntentStore`（新規純粋seam、`app.lawnchair.organizer.personalization`）**
   - 役割: 取り込み済み提案のdurable保持。`ExportSessionStore` と同型の小interface。
     例（命名は実装PRで確定。signatureの意図のみ拘束する）:
     - `save(proposal): Boolean`（単一active上書き。atomic。失敗はfalse — 呼出側はtyped失敗へ）
     - `load(): DurablePendingIntent?`（不在/破損/未知schemaはnull。fail-closed）
     - `discard(): Boolean`（**user-visible破棄のtombstone 2段commit**: `discarded=true` の
       atomic書換に成功したらtrueを返し、引き続きbest-effortで物理削除する。tombstone commit
       失敗はfalse — 呼出側はtyped失敗へ）
     - `delete()`（reconcile清掃・置換無効化の共通出口。best-effort物理削除。
       読取時reconcile（exportId mismatch・破棄mark検証を含む）が正本であるためtombstone不要）
   - record model（`DurablePendingIntent` 相当。pure package・`@Serializable`なし）:
     `exportId`、**`intentIdentity`（schemaVersion + digest。import時に算出した
     `IntentIdentity` の正本。#375 rebindのprovenanceに用いる）**、canonical decisions
     （`List<DurableRefDecision>` 相当: `ref` + `Authored{importance, desiredGroupRefs:
     List<String>, groupSemantic（exactly-one-of: `categoryRef` / `proposalLabel` text）,
     pageAffinity, regionAffinity, preserve} | UnresolvedAuthored | UnresolvedByOmission`）、
     planner-effective `minimizeMovement: Boolean`、`expiresAtEpochMs`（session複製値。表示の
     正本はsession側 — spec契約）、`entryKind`（IDLE / RUN_IN。#375用）、`discarded: Boolean`
     （tombstone）、`createdAtEpochMs`。**`rationale`/`confidence`/ref↔内部ID対応表
     （itemRefs/categoryRefs相当）/app label・folder title・category displayNameはfieldとして
     存在させない**（DI-AC-10の型による保証。field不在assertionをcontract testにする。
     `proposalLabel` textはplanner-effective formation key（`ItemPreference.groupProposalLabel`）
     として保存する）。
   - purity: interfaceとrecordは純粋packageに置き、Android依存はintegration側のみ
     （`ExportSessionStore` と同一の純粋性の境界）。
2. **`AndroidPendingImportedIntentStore`（新規、`app.lawnchair.organizer.integration`）**
   - `AndroidExportSessionStore` を雛形にした実装: `noBackupFilesDir`（backup除外）、
     `AtomicFile`（単一recordのatomic書込 — saveは上書き、discardは `discarded=true` 書換後に
     best-effort削除）、`SCHEMA_VERSION` 検査（不一致・decode失敗・IOException → null退化）、
     `synchronized(lock)`。private nested `@Serializable` record + pure modelへのmapping。
     `DI-AC-04` の寛容読みはこの実装に帰着する。
3. **module accessor（新規、`app.lawnchair.organizer.integration.exchange`）**
   - `ExchangeSessionStoreModule` と同型のmodule-per-concern object
     （仮称 `PendingImportedIntentModule`）。
4. **reconcile / projection（`personalization/exchange` 配下の純粋object）**
   - **reconcile純粋関数**: 入力 `(durable record | null, active session | null, nowEpochMs)`、
     出力 `有効(record) / 無効(削除対象) / なし`。判定条件はspec契約どおり
     (a) `record.exportId == session.exportId`（session不在も無効）、(b) session TTL
     （record複製値とsession値の不一致はsession側を正とする）、(c) `discarded` mark、
     (d) 構造検証（decode結果のref集合がsession `itemRefs.keys` と一致しないものは破損として無効）。
     table-driven unit test（DI-AC-05）の対象。
   - **status card projection（閉域語彙）**: spec 271の `OrganizerDurableStatus` と同一様式の
     閉じた読み取り専用model（仮称 `PendingProposalCard`）:
     `PROPOSAL(expiresAtEpochMs) / NONE` 相当（+依頼行は `activeSession()` の有無と
     `expiresAtEpochMs` をそのまま運ぶ。残時間textへの変換は `requestRemainingDisplay` 相当の
     既存純粋関数・T-15と同一語彙）。件数サマリの再構成（内容表示）はImportReview側の導出であり、
     status card行は存在・残時間・開封のみを運ぶ（payload非表示）。reconcile失敗・読取失敗は
     `NONE`（fail-closed。発明した状態を作らない）。
   - **summary再構成・planner projection等価の純粋導出**: record + session →
    `exchangeImportSummary` と同一入力基準のsummary（`categoryKindByRef` は
    `session.categoryRefs` の `CategoryIdentity` 判別から、`scopeCandidateCount` は
    `session.scopeCandidates.size` から。recordのdecisions + minimizeMovementから全countを
    再現）。さらに **planner projection等価**: record + session + 既存の
    `SessionExportReconstructor.rebuild(session, current)`（export view再構築。構造digest不一致
    は `CONTEXT_STALE`）から `IntentPlannerAdapter.project` 相当のprojectionを再構成し、
    import直後のprojectionと **全field同一**（identity（保存済み `IntentIdentity`）・
    itemPreferences・`groupProposalLabel`（複数提案labelケース）・`globalMinimizeMovement`）
    であることをoracleで固定（DI-AC-01）。実装は
    (i) record→`ValidatedPersonalizedIntent` 復元関数（identityは保存済み正本を用い、
    再導出しない）、(ii) projection入力の抽象、のいずれかで純粋に行う（実装PRで選択。
    いずれも現行 `exchangeImportSummary` / `IntentPlannerAdapter` の呼出側・契約は変更しない）。
   - 配置: reconcile・projection・summary再構成はexchange契約側（`personalization/exchange`、
     純粋）に置き、呼出点（controller・status card読取・起動時）から使う。`LayoutApplicationModule`
     （application/recovery所有）へは置かない — 提案storeはexchange/personalization契約の
     所有物であり、recovery storeと正本が異なるため（DESIGN §4.2のprojection所有原則の適用）。

### Data flow

- **保存（import成功。commit条件化）**: `settleImport()` の `Validated` 分岐で
  `pendingValidated = pipeline.validated` の後、**durable保存を試み、成功して初めて
  `screen = ImportSuccess` を採用する**。保存はrecord構築（`validated.completed` のdecisions
  （ref・proposalLabel text含む）+ `validated.intent.globalPreference.minimizeMovement` +
  `validated.session.exportId/expiresAtEpochMs` + `validated.identity`（算出済み正本）+
  entry種別）→ `store.save()`（`Dispatchers.IO`。
  既存のsettle hopと同一coroutine構造）。失敗時は `ImportOutcomeScreen` 系ではなく
  persistence step由来のtyped失敗state（#373 D-11様式: 保存再試行CTA・常設の中断/診断。
  再試行はvalidation結果を保持したまま `store.save()` のみ再実行。中断は保存せずclose）。
  anchor契約（attempt token・single-flight・`continuing` guard）は一切変更しない（DI-AC-07）。
- **CTA settleでのrecord不変**: `settleContinue` の全分岐（Success/Busy/NotAttachable/Failed）
  でdurable recordへのwrite・削除を行わない（消失系は破棄・期限切れ・置換のみ。#375「継続成功は
  提案を消費しない」契約。DI-AC-07）。
- **置換無効化（3保持場所）**: `ExchangeFlowController.generate()` の `store.save(newSession)`
  成功直後に (1) pending storeの `delete()`、(2) holder側の成功状態/pending無効化
  （controller→holder通知、またはholderがgenerate経路で必ず経由する既存hookでの無効化）。
  書込順序固定（DI-AC-03）。cancelDisclosure（pre-send cancel）はsession invalidateのみで
  pendingには触れない — session不在が次のreconcileで無効化する（単一の正本経路を維持）。
- **読取（status card / ImportReview）**: 読取のたびに reconcile → 有効ならprojectionを導出、
  無効なら `delete()` して `NONE`。ImportReview再開面はrecord + active session +
  summary再構成の純粋導出で件数サマリを再構成する（validated intentの全量再構築はしない —
  #375の領域）。再開面の破棄は確認dialog（D-13）→ `store.discard()`（tombstone 2段commit）→
  成功で画面close・失敗でtyped案内（提案は有効なまま残る）。
- **起動時reconcile**: `LawnchairApp.ensureOrganizerStartupReconciliation()` のtrigger thread
  **先頭**（model load待ちの前。store完結のためgate不要・idempotent性はtriggerの
  `AtomicBoolean` が既に保証）でreconcileを実行する（spec Contract notes 8。
  「hub未開封のfresh process起動でstale record清掃」が契約。projection初回読取への集約は
  契約を満たさないため不採用）。
- **依頼行の読取**: status card読取時に `activeSession()`（`ExportSessionStore.active`）を
  読み、有効時に行を表示（残時間は `requestRemainingDisplay` 相当・T-15と同一語彙）。
  再読取triggerはT-15と同一（面への進入・lifecycle resume・表示中sessionの失効時刻にschedule
  した1回再読取）。行の操作はT-15（依頼作成面）を開く（hub→run面の導線。既存の
  `PreferenceNavigation` routeへexchange flowをT-15状態で開くparamを渡す。実装PRで確定）。

### Alternatives rejected

- **session storeへのpending併記（1 fileにsession + intent）**: spec 204 storeのschema変更と
  downgrade時の寛容読みを複雑化する。disposition §7.1は「新store」を規定しており、分離が正本。
- **`PersonalizedIntentV1` wire JSONの保存**: authored文書（`rationale` 等）をdurable化する
  privacy境界の拡大と、#330 D-4「authored文書はdiagnostics用」規約との不整合。canonical
  decisionsの専用recordを採用（spec契約どおり）。
- **refを全て内部stable IDへ変換して保存（逆変換契約）**: cold-process summaryと#375 rebindには
  canonical decisions（ref基準）で十分であり、ref→内部ID変換のround-trip契約・oracleを新設する
  複雑性に見合わない（初回review指摘1の代替案。session対応表を正本とする方が既存契約に沿う）。
- **session置換とpending破棄のatomic commit**: disposition §7.1が「要求しない」と明示。
  読取時reconcileを正本とする。
- **明示破棄の単純delete**: session置換と異なり明示破棄にはexportId mismatchの救済がないため、
  delete失敗・commit直後process deathで破棄済み提案が再表示される。tombstone 2段commitを採用
  （初回review指摘2）。
- **durable保存失敗時のprocess-local後退**: Issue Outcome「消失の系は破棄・期限切れ・置換のみ」
  に反する例外lifecycleを作る。保存成功をcommit条件とする（初回review指摘3）。
- **CTA成功settleでのrecord消費削除**: Issue本文の消失系契約（破棄・期限切れ・置換のみ）と
  #375実装予定spec（「継続成功は提案を消費しない」「成功・失敗・拒否のいずれもdurable recordへ
  writeしない」）と正反対になる上、best-effort deleteでは「消費済み提案が未適用として
  status cardへ復活する」不整合が残る。CTA settleではrecordへ書かない（2nd review指摘2）。
- **`proposalLabel` のBoolean flag化**: `IntentPlannerAdapter` がlabel textそのものを
  `ItemPreference.groupProposalLabel`（formation key）としてplannerへ渡すため、flag化は
  「どのitem同士が同じ提案groupか」の復元を失う。正規化済みlabel textを保存する
  （2nd review指摘1）。
- **status card行への件数サマリ表示**: D-02は「durable事実と進行中状態の単一閲覧面」を要求するが、
  行の構造（存在・残時間・開封）は#366のstatus card契約（payload非表示・閉域語彙）と
  spec 271の様式に揃える。内容はImportReviewで見せる（TO-BE §5.3の再開1経路と一致）。
- **起動時reconcileをprojection初回読取へ集約**: hubを一度も開かない起動ではprojection初回読取が
  発生せず「起動時にも適用」を満たさない（初回review指摘5）。共有triggerの先頭実行を採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/personalization/` | 新規: `PendingImportedIntentStore` seam + record model + reconcile純粋関数 + summary再構成導出 | 純粋seamはpersonalization package（`ExportSessionStore` と同一の純粋性の境界） |
| `lawnchair/src/app/lawnchair/organizer/integration/` | 新規: `AndroidPendingImportedIntentStore`（`noBackupFilesDir` + AtomicFile + schema version + private `@Serializable` record/mapping） | Android/storage境界はintegration（`AndroidExportSessionStore` と同一配置） |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/` | 新規: module accessor（`@Volatile` singleton）。既存 `ExchangeFlowModule` へstore注入の追加 | module-per-concern規約。controllerは生成・import orchestrationの所有者であり書込順序契約（置換無効化）をここに置く |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `settleImport()` へのdurable保存呼出追加（commit条件化）・保存失敗typed state・破棄のtombstone化（`discardImport()` がstore.discardを呼ぶ・D-13両入口確認1回）・CTA copyのT-18語彙 | import lifecycleの唯一のUI持能手。anchor/single-flight契約は無変更。CTA settleでrecordへ書かない（#375契約） |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt` | `generate()` のsession保存直後に旧pending無効化 + holderへの置換通知。reconcile呼出の提供 | 書込順序固定の唯一の位置 |
| `personalization/IntentModels.kt` | `GroupSemantic.proposalLabel` のKDoc「never persisted」規定を本契約へ改訂（durable pending intent storeへの保存はlayout DB・category storeへの永続化ではない） | 2nd review指摘1。planner-effective formation keyのlossless保存 |
| `ui/preferences/destinations/OrganizerHubPreferences.kt`（#366実装） | 提案行・依頼行の追加（durable status行と開始CTAの間。TalkBack「状態→残期限→操作」）・依頼行のT-15導線 | #366実装のstatus card構造への接続（挿入点は#366 spec明記）。#366実装merge済みのため推定不要 |
| ImportReview再開面（`ExchangeFlowUi.kt` の再開形態 or 新規composable） | record+sessionからの件数サマリ再構成・残時間・破棄（tombstone）。継続CTAなし。hubからの開放導線 | spec DI-AC-01/08。表示modelはsummary再構成純粋導出の再利用 |
| `ManualOrganizationRun.kt` / hub読取経路 | 提案projection・依頼行projectionの読取seam追加（`ManualOrganizationModule` 経由の既存様式） | cold process到達保証（spec 271 DS-AC-10と同一の初期化経路）を共有 |
| `LawnchairApp.kt`（起動経路） | `ensureOrganizerStartupReconciliation()` trigger thread先頭へpending reconcile hook追加 | hub未開封のfresh processでも清掃が走る契約（spec Contract notes 8）。triggerのidempotent性を壊さない最小hook |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | `exchange_replacement_warning` 拡張（取り込み済み提案の破棄追記）・persistence失敗typed案内・status card行（提案・依頼）・破棄確認（D-13両入口）・CTA copy（この提案で続ける）（ja正本） | spec 123契約 |
| specs 328 / 205 / 366 | rev.2 / pending規定改訂 / HUB-AC-03縮小（実装PR内または直前のdocs PR） | disposition §5 更新順序 #8 |

## Migration and recovery

- **migration**: なし（新store追加のみ。既存dataの変換は存在しない）。
- **置換のfailure**: 書込順序「新session保存 → 旧pending無効化」の途中でprocess death /
  I/O failureが発生した場合、stale recordは起動時・読取時reconcileでfail-closed清掃される
  （DI-AC-05の必須oracle: fake clock/storeによるprocess death遷移の再現 +
  `AtomicFile` 相当の書込失敗注入）。
- **破棄のfailure**: tombstone commit失敗 = 破棄不成立（typed失敗・提案保持）。tombstone commit
  成功後の物理削除失敗 / commit直後process death = 読取時reconcileの破棄mark検証が清掃
  （DI-AC-05/08の必須oracle）。
- **保存のfailure**: typed失敗（保存再試行）。成功状態を採用しないため、部分的なdurable状態
  （recordなし成功表示）は構造的に発生しない。
- **downgrade**: 旧版は本storeを読む経路を持たない（file名・schemaが独立のため自然的に無視）。
  再upgrade後の最初の読取でreconcileがstale recordを清掃する。run・layout DBへの影響なし。
- **restore**: `noBackupFilesDir` のためbackupされない。restore後は提案なしの状態から始まる
  （TO-BE §7.3どおり明示）。
- **rollback**: PR revertで機能のみ消失。残留recordは旧版で読まれず、再適用後のreconcileで
  清掃される。recovery point・`favorites` への接触は一切ないため、ホームレイアウト安全規約の
  transaction/recovery point要件は本store単体のatomic書込で充足する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| DI-AC-01 | store unit test（保存・record内容・単一active置換・run state不変）+ holder unit test（保存成功が成功状態採用条件・保存失敗で不採用）+ **planner projection等価unit test**（record+session+`SessionExportReconstructor` 再構成 ≡ import直後の `IntentPlannerAdapter.project` 結果。identity・itemPreferences・`groupProposalLabel`（複数提案labelケース）・`globalMinimizeMovement` 全field同一・件数サマリ全count一致）+ instrumentation（cold processのstatus card行 → 再開面）+ emulator cold-start evidence | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、API 36 AVD |
| DI-AC-02 | store unit test（clock注入・TTL/session従属/invalidate連動） | unit gate |
| DI-AC-03 | controller/holder unit test（置換承認 → 旧pending削除・書込順序・同一process成功状態/pending破棄）+ dialog文言test（en/ja） | unit gate + strings走査 |
| DI-AC-04 | store unit test（backup除外path・未知schema/破損fail-closed・downgrade相当） | unit gate |
| DI-AC-05 | reconcile table-driven unit test（exportId不一致/session不在/TTL/破棄mark/破損/未知schema/ref集合不一致 → 清掃）+ process death oracle + write failure注入oracle + **tombstone commit直後death・削除失敗oracle** + **起動時reconcile test（hub未開封startupのみ）** | unit gate + instrumentation |
| DI-AC-06 | spec 328 rev.2差分review + owner受入記録 | docs PR review（Issue #374コメント） |
| DI-AC-07 | 既存 `ExchangeFlowStateHolderTest` のgreen（無編集またはanchor契約非依存の更新のみ）+ 保存追加後regression + **CTA成功/gate拒否/failure settleいずれでもrecordへwrite・削除なしtest** | unit gate |
| DI-AC-08 | holder/instrumentation test（破棄確定 → tombstone commit → close・行消滅・session生存・再取り込み成立・**tombstone commit失敗注入 → typed失敗・画面維持**・両入口確認1回） | unit + instrumentation |
| DI-AC-09 | Compose semantics assertion（読み順「状態→残期限→操作」。提案行・依頼行）+ 200% font scale + Switch Access traversal + screenshot evidence | instrumentation lane + emulator |
| DI-AC-10 | record model field契約test（保存field存在（`intentIdentity`・`proposalLabel` text含む）+ 非対象field不在: rationale/confidence/対応表/app label・folder title・category displayName）+ `noBackupFilesDir` 機械確認 + summary導出contract test回帰 | unit gate |
| DI-AC-11 | spec 205・366差分review + 依頼行・提案行instrumentation test（存在/不在・残時間・T-15/ImportReview到達）+ 既存exchange系regression | unit gate + diff review |
| DI-AC-12 | ja/en strings走査（spec 123 AC-5方式）+ hardcoded literal grep | 機械確認 |
| DI-AC-13 | holder unit test（保存失敗 → typed失敗・再試行で成功状態へ・中断で非保存・status card行なし）+ strings test | unit gate |

含めるべき観点: unit/contract（store・reconcile・summary再構成・record field境界）、
failure injection（書込失敗・tombstone commit失敗・物理削除失敗・置換途中のprocess death）、
integration（import → 保存 → cold process読取 → 破棄 → 再取り込み）、UI/accessibility
（status card読み順・再開面・200%）、regression（spec 328 anchor/single-flight・spec 205
AC-13・#372 T-15・#373失敗面）。

## Incremental implementation order

1. **spec受入**: 本specのowner受入（`draft` → `accepted`）。Contract notes 1〜8の解釈確認を含む。
2. **spec 328 revision 2 + spec 205改訂 + spec 366 HUB-AC-03縮小（docs PR）**: 本specの契約を
   spec 328/205/366へ反映し、owner受入を得る（disposition §5 更新順序 #8。DI-AC-06/11。
   **#328実装Issueの着手はこの受入後**）。前提merge（#365/#366/#372/#373）は充足済み。
3. **store縦切り（source PR群の最初）**: 純粋seam + Android実装 + module accessor +
   reconcile純粋関数 + summary再構成/planner projection等価導出 + table-driven unit test（DI-AC-02/04/05のstore部分・
   projection等価oracle）。UI変更なし。
4. **import lifecycle接続**: `settleImport` 保存commit条件化・保存失敗typed state・置換無効化
   （3保持場所）・破棄tombstone化（D-13確認）+ 置換確認文言拡張 +
   CTA copy改訂（DI-AC-01/03/07/08/13のcontroller/holder部分）。
5. **起動時reconcile hook**: `LawnchairApp` trigger thread先頭への追加 + startupのみoracle
   （DI-AC-05）。
6. **status card接続・ImportReview再開面**: #366実装status cardへの提案行・依頼行追加 +
   読取seam + 再開面 + hub導線（DI-AC-01/08/09/11）。
7. **a11y・strings・evidence**: DI-AC-09/12 + device evidence。
8. **spec status更新 + 関連正本の更新**（`CONTEXT.md` 取り込み済み提案・取り込み破棄の用語更新、
   FR-017 status表記は実装完了時 — disposition §5 #11）。

## Dependencies / blockers

- **spec 328 rev.2の所有**: 本Issue（disposition §3.12/§5 #8）。rev.2執筆は上記順序2であり、
  本task（spec/plan整備）では行わない。**実装着手の前提**: 本specとrev.2のowner受入後
  （#365/#366/#372/#373 mergeは2026-09-21時点で充足済み）。
- **#375**: 再開面の継続CTA有効化・rebind・SCOPE_MISMATCH原因別remedyの所有。本planは
  recordにentry種別を残す以外のrebind実装を含まない。
- **並行作業のseam調整**: `ExchangeFlowUi.kt` / `ExchangeFlowController.kt` は #373（実装済み）
  と同じfile群。#374開始時点でmainは `c05435a947`（#373実装merge後）であり競合相手は
  #375（未着手）のみ。merge順の制約は解消済み。

## Risks

- **durable化によるanchor契約の破壊リスク**: 保存をsettle経路へ入れる際、attempt token
  anchor・single-flight・`continuing` guardの既存oracleが壊れないことをDI-AC-07で先に固定する
  （store縦切りとUI接続を分離するのはこのため）。保存をawaitしてから `ImportSuccess` を採用する
  ためsettleの非同期構造が変わる点（遅延Validation settleのdrop条件はattempt token不変）に注意。
- **stale提案の表示残存リスク**: reconcile漏れ（新規読取経路の追加時にreconcileを素通りする
  実装）がstale表示を再生する。読取seamを1箇所に集約し、新規読取経路がreconcileを必ず通る
  構造（projection関数経由のみ）にする。
- **tombstone実装のatomic性**: `discard()` のtombstone書換は既存recordのatomic書換
  （AtomicFile）でなければならない（部分書込でmarkが落ちると再表示の危険）。
  store unit testで「tombstone commit = read-modify-writeのatomic性」をfail-closed検証する。
- **`risk: privacy`**: 新規durable store（intent内容・refを含む）を追加するため。実装PRの
  label決定時に高リスクPR要件（CI merge gate + 独立audit記録）の適用可否を判定する
  （`risk: layout-data`/`risk: migration` には該当しない見込み — layout DB/recovery DBへ
  触れないため。ただし最終判断は実装PRのdiffで行う）。

## Explicitly unverified areas

- **#375のrebindがrecordへ要求するfield集合**: 2nd review指摘1/3により、canonical decisions
  （ref・`proposalLabel` text含む）+ `intentIdentity` + minimizeMovement + entry種別 +
  identity anchor（exportId）+ 失効時刻までを保存契約として確定済み（#375 review
  comment `5740062562` のblocking指摘への回答）。#375実装でさらに追加fieldが必要になった
  場合はspec改訂で拡張する（privacy再評価を含む）。
- **hub→T-15導線の実装様式**: hub行からT-15（run面のexchange flow state）を開く導線は
  既存 `PreferenceNavigation` routeへのparam追加が想定だが、正確な接続は実装PRで確定する
  （#366実装のroute構造は確認済み）。
- **依頼行・提案行の具体的な視覚構造**（行 vs section・checking様式の踏襲有無）は実装PRで確定する
  （挿入点・TalkBack順・閉域語彙の契約はspecで固定済み）。
- **残時間表示の経時更新**: T-15と同一（進入/resume/失効時1回再読取・連続時計なし）を踏襲する
  ため、新規判断は不要。表示文言のja最終形は実装PRで確定する。

## Documentation updates

- [ ] spec status/history（本spec、実装完了時に `implemented` 化 — owner受入フローに従う）
- [ ] spec 328 rev.2（順序2のdocs PR）
- [ ] spec 205 pending規定改訂（同上）
- [ ] spec 366 HUB-AC-03縮小（同上）
- [ ] CONTEXT.md（`取り込み済み提案` の末尾保留文のdurable化実現への置換、`取り込み破棄` の
      durable削除・D-13確認契約への更新 — 本Issueの実装PRで実施）
- [ ] DESIGN.md（data ownership §7への新store記載 — 実装PRで要否判断。§4.2のprojection所有
  原則との整合を確認）
- [ ] ADR（不要と判断: store様式は既存spec 204/271の延長であり、判断の新規性はdisposition §7.1が
  既に保持する）

## Execution checklist

- [x] Spec accepted（owner review、Contract notes 1〜8確認 — ChatGPT review Approved
      `5761252403`、owner指示によりPhase 2進行）。
- [x] spec 328 rev.2 + spec 205改訂 + spec 366縮小（実装branch内docs commit。
      owner受入は最終PR reviewで確定）。
- [x] Current behavior reproduced（process死・画面離脱で提案消失、`pendingValidated` の
      画面state限定 — 実装前の現行挙動はPhase 1調査で確認済み）。
- [x] Tests fail for the missing behavior（store/reconcile/summary再構成 —
      新規test群が契約を先に固定）。
- [x] Minimal implementation completed（store縦切り → lifecycle接続 → 起動hook →
      status card接続・ImportReview）。
- [x] Migration/recovery verified（置換無効化・tombstone commit失敗注入・corrupt record・
      downgrade相当の未知schema — unit test。process death遷移oracleは
      store再生成test + reconcile testの組合せで固定）。
- [x] Full relevant verification completed（unit gate green（1657 tests）・spotless green・
      assemble green。CI merge gate `final-status` green（全organizer instrumentation lane含む。
      3件のImportReview instrumentation test修復を含むhead `34d405a333` でgreen、docs-only
      後続commit経由でmerge `9dc3ec8fed`）。実装review: ChatGPT review Approved
      （comment `5763681310` @ `32d7f341c0`。初回Changes requested 高1・中3 → 全解消確認）。
- [x] PR evidence and remaining risks recorded（独立audit
      [docs/assessment/pr-399-durable-imported-intent.md](../../docs/assessment/pr-399-durable-imported-intent.md)
      + PR本文。#375引き継ぎ事項（entry種別・`intentIdentity`・rebind用record fields）を
      audit Findingsに記録）。
