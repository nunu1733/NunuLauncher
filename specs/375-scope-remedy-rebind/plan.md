# Implementation Plan: scope不一致の原因別remedy（SET_MISMATCH/PROJECTION_MISMATCH）と取り込み済み提案のprocess死後再開（rebind）

> Issue: #375
> Spec: [spec.md](./spec.md)
> Status: draft
> Revision: 3（初回review 3件対応 + 2nd review 2026-09-22 4件対応 + 前提merge後の
> current main `9dc3ec8fed`へのre-entry）

## Current evidence

以下はmain `9dc3ec8fed`（2026-09-22時点。#365/#366/#369〜#374実装merge後。
#374は[PR #399][r399]）での確認済み実装事実である。推測は「未検証」に分離する。
初回draft（baseline `a2b6aba318`）からの差分は、#369/#373/#374の着地による行番号・
実名の追従と、review指摘1/2で要求された設計の確定である。

### Gate と cause の現状

- **cause導出は既に存在する**: `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ScopeBindingGate.kt`
  の `evaluate()`（20-53行）は既に `ScopeMismatchCause`（`IntentValidator.kt` 208-217行のenum。
  `SET_MISMATCH` / `CANDIDATE_UNRESOLVED` / `PROJECTION_MISMATCH`）を導出する:
  依頼scope候補が検出cutに不在・非`AVAILABLE` → `CANDIDATE_UNRESOLVED`（30/32行）、
  依頼scope候補が未選択・依頼外の追加選択 → `SET_MISMATCH`（35/43行）、
  `scopeCandidateDigest` ≠ `CandidateScopeIdentity.digest(current.candidateProjections)` →
  `PROJECTION_MISMATCH`（49-51行）。解決不能の判定が集合差の判定に**先行する**順序は
  既に正しい（spec.mdのcause優先契約と整合）。
- **confirm時早期gateはavailabilityを見ていない**: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  の `confirmSelection()`（607-657行）は `intent.session.scopeCandidates` と確定選択の
  ソート比較のみで、不一致をすべて `SET_MISMATCH` として報告する（625-639行）。
  このとき **`operation.intent` は維持される**（unit test
  `scopeBindingRejectsASelectionMissingTheExportedCandidate` が `intentScopeCount` の
  維持を固定。`tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` 1343行）。
  つまり同一process内の「選択を修正して同じ提案で続行」は構造として既に成立しており、
  本Issueは契約明文化＋差分強調＋cause別案内の追加である。
- **後段gate失敗時はintent破棄＋選択面復帰**: `runComposedPhase` 内のgate失敗処理
  （880-925行付近）は、検出cutが存在する場合 `State.Selecting(..., intentScopeCount = 0,
  scopeRejection = failure)` へ復帰し **`operation.intent = null`** とする（895-901行付近。
  unit test `scopeBindingProjectionDriftReturnsToSelectionWithZeroWrites` 1367行が
  復帰後のre-attach可を固定）。検出cutが無い場合は journal event＋
  `State.ScopeMismatchFailed(failure)` 終端（920行付近、
  `scopeBindingWithoutASelectionSurfaceFailsTypedZeroWrite` 1400行）。
- **State.Selectingの現行field**: `runId` / `detectedCandidates` / `intentScopeCount` /
  `scopeRejection`（280-307行付近）。依頼scopeのidentity集合はUIへ露出していない
  （件数のみ）。差分強調に必要な材料がstateに存在しないことが確認された。
- **`start(trigger, intent)`**: 483-553行。`beginOperation`（1392-1416行）がRUN lease取得
  （`operationGate.tryAcquire`）→ `synchronized(lock)` 内でactive operation不在確認 →
  operation生成 → `State.Capturing` 発行、の順。**admission直前の再検証hookは存在しない**
  （review指摘1の対象構造）。検出 → `State.Selecting(runId, candidates,
  intentScopeCount = intent.session.scopeCandidates.size)`。検出`Unavailable` なら
  選択面を開かずcomposed phase直行（既存契約、spec 228 §7）。
- **attach**: `attachIntent()`（707-716行）は選択面が開いている生存runへのsingle-shot、
  zero-write、`NotAttachable` 戻り値。run-in attach契約の実体。
- **`StartOutcome`**: 237-240行。`Started(runId)` / `Busy` の2系のみ。

### 選択面UI の現状

- **差分強調は存在しない**: `MissingAppSelectionScreen.kt` の
  `missingAppSelectionItems()`（93行〜）はbound intent時の件数案内
  （`R.plurals.exchange_scope_intent_count`、117-124行）と `editsEnabled` 凍結（106行）のみ。
  `ManualOrganizationPreferences.kt` 556-559行が `scopeRejection` を
  `exchangeContractFailureText(rejection)` でerror色text描画（live region assertive）。
  `exchangeContractFailureText` は `ExchangeFlowUi.kt` 3216行で
  `ScopeMismatch → R.string.exchange_failure_scope_mismatch` の単一mapping。
  原因別の文言が存在しない。#373実装の手段別失敗投影（`ExchangeImportFailureDisplay.kt`
  205-211行）も `ScopeMismatch → RECREATE_REQUEST`（`exchange_failure_primary_scope_mismatch` /
  `exchange_failure_scope_mismatch`）の単一remedyであり、選択面remedy（SET_MISMATCH）と
  依頼作り直しremedy（続行不能cause）の区分は本Issueが追加する。
- **選択stateの初期化点**: `ManualOrganizationPreferences.kt` 252-254行
  `var missingAppSelection by remember(selectingState?.runId) {
  mutableStateOf(MissingAppSelectionState(selectingState?.candidates.orEmpty(), emptySet())) }`。
  **runIdをkeyにした常にuncheckedの初期化**（D-1/AC-3の実装）。復元初期値はこのseedを
  置き換える形で入るのが最小変更である。候補のlabel対応表は既にUI側に存在し、差分強調の
  表示材料は追加のdata公開を必要としない（scope identity集合のstate公開のみ必要）。

### 継続経路とsession正本の現状（#374着地後）

- **process-local pending + durable record**: `ExchangeFlowUi.kt` の
  `ExchangeFlowStateHolder` が `pendingValidated`（921行）・`activeAttempt`・
  `pendingDurableRecord`（931行）保持。`continueImport()` →
  `connectRun()`（1240行）: `RUN_IN` なら `run.attachIntent`（owningRunId照合つき）、
  `IDLE` なら `run.start(intent = ...)`。single-flight・attempt token照合・`continuing`
  中不受理の実装あり（spec 328 AC-3）。`ExchangeImportEntryKind { IDLE, RUN_IN }`（230行）。
- **#374のdurable層（実名）**: `PendingImportedIntentStore.kt` —
  `DurablePendingIntent`（`exportId`・`intentIdentitySchemaVersion`・`intentIdentityDigest`・
  `decisions: List<DurableRefEntry>`・`minimizeMovement`・`expiresAtEpochMs`・`entryKind`・
  `discarded`・`createdAtEpochMs`）・`PendingImportEntryKind { IDLE, RUN_IN }`・
  store interface（`save`/`load`/`discard`/`delete`/`deleteIf`）・
  `durablePendingIntentFrom` / `toDecisionsMap`（canonical decisions⇔decisions map変換）。
  `PendingIntentReconcile.kt` — `reconcilePendingIntent(record, session, now)`
  （`Valid` / `Invalid` / `Absent`。exportId一致・TTL・破棄mark・ref集合構造検証）・
  `durableImportSummary(proposal, session)`。
  **review指摘2の契約は#374が確定済み**: recordはimport時に算出した `IntentIdentity`
  （schemaVersion+digest）を保存し、#374 spec Contract notes 3が「#375のrebindは
  保存済みidentityをそのまま用いる」と明言している。
- **再開面（#374着地済み）**: `openPendingImportReview()`（1361-1393行）がIO上で
  record+session読取 → reconcile → `ExchangeScreen.ImportReview(summary, expiresAtEpochMs,
  readAtEpochMs, entryKind)`（186行）採用（Invalidなら `delete()` 清掃＋typed
  `IMPORT_REVIEW_UNAVAILABLE`、面非採用）。**継続CTAは存在しない**（#375が追加する）。
  破棄 `discardImport()`（1408-1424行）はtombstone 2段commit。
  **durable書込の直列化点（現状の限界）**: `pendingWriteMutex`（940行）はdurable save一連処理
  （`launchDurablePendingIntentSave` 1090-1123行。fence 1/2＋`deleteIf`）を直列化するが、
  `discardImport()` の `store.discard()` と `openPendingImportReview()` のInvalid清掃
  `delete()` はmutex外、かつ **session置換（`ExchangeFlowController.generate()` 内の
  新session保存＋旧record削除）もmutex外**である。さらに `AndroidExportSessionStore` と
  `AndroidPendingImportedIntentStore` は別々の内部lockを持つ。したがってholder内mutexの
  拡張だけではrebind admissionの排他根拠にならず、本Issueがprocess-wideな共有gateを
  新設する（Design 7。2nd review指摘1）。
- **置換時のrecord無効化**: `ExchangeFlowController`（`pendingImportStore`注入）が
  新sessionのdurable save成功直後に旧recordを削除する（書込順序「新session保存 →
  旧pending無効化」固定）。holder側は `invalidateImportedIntentForReplacement()`
  （675行）がin-processの成功状態/pending破棄を行う。
- **起動時reconcile**: `PendingImportStartupReconcile` が
  `LawnchairApp.ensureOrganizerStartupReconciliation()` に接続済み。
- **import時の検証構造**: `ExchangeImportPipeline.kt` stage 2（105-138行）=
  session lookup → expiry → **構造digest等価**（125-127行、不一致は
  `ContextStale` typed失敗）→ `SessionExportReconstructor.rebuild(activeSession,
  currentStructural)`（131行、構造divergenceで再構築失敗→`ContextStale`）→ validator。
  **この検証はimport時限り**であり、継続（CTA→run開始）時には再適用されない。
- **planner投影のref解決は`getValue`**: `IntentPlannerAdapter.project()`
  （`IntentPlannerAdapter.kt` 19-54行）が `session.itemRefs.getValue(ref)` /
  `export.items.associate{...}.getValue(ref)` を使用する。参照先Itemが現行compositionに
  存在しない場合、例外経路になる（typedでない）。import時の構造digest検証がこの到達を
  構造的に防いでいる（rebindで24時間窓を開く場合は同等の再検証が前提）。
  `PersonalizedIntentProjection.identity = validated.identity`（50行）—
  **identityはplanner provenanceへ接続される**。
- **identityの現状構造**: `IntentIdentity.kt` — `canonicalRepresentation()`
  （33-45行）は `rationale|<rationale>|<confidence>` 行（37行）をdigestへ含む。
  `IntentValidator` はvalidation成功時に `identity = IntentIdentityCalculator.identity(completed)`
  を算出する（137-143行）。`ValidatedPersonalizedIntent`（149-164行）は
  `identity` をconstructor引数で受け、`completed` はlazy再導出（projection用）である。
  **rebindは文書非所持のためidentity再導出が不可能** — したがってrecord保存値の注入が
  唯一の契約整合解である（#374 Contract notes 3の確定回答。review指摘2の解消）。
- **session正本**: `ContextExportModels.kt` の `ExportSession`（411-444行）は
  `exportId` / `itemRefs` / `categoryRefs` / `scopeCandidates` / `scopeCandidateDigest` /
  `sourceContextDigest` / `expiresAtEpochMs` を持つ。復元初期値にrecord拡張は不要 —
  `scopeCandidates` がそのまま依頼時明示選択である。

## Design

### Modules and interfaces

AGENTS.md設計規約（小さなinterface・既存seamの再利用・platform型を計画moduleのinterfaceへ
漏らさない）に従う。純粋seamの新設は3つまでに留め（cause導出・diff/復元導出・rebind
再構築）、admission anchor hookとexchange mutation gateは既存seam境界上の小さな追加
（排他・検証の仕組み）として分離する。gate自体の判定規則は1行も変更しない。

1. **早期gateのcause導出（`ScopeBindingGate` への純粋追加）**
   - `deriveConfirmMismatch(sessionScope, detected, selected): ScopeMismatchCause?`
     （命名は実装PRで確定。`null` = 集合一致）。依頼scope候補のうち検出cutに不在または
     非`AVAILABLE`のものがあれば `CANDIDATE_UNRESOLVED`、そうでなければ `SET_MISMATCH`。
     `evaluate()` と同じ「解決不能の判定が集合差に先行する」順序を共有する。
   - `ManualOrganizationRun.confirmSelection()` のinlineソート比較（625-639行）をこの呼出に
     置換する。**gateのpass/fail結果は1件も変わらない**（集合一致ならpass、不一致ならfailは
     現行同値。変わるのはfail時のcause labelのみ）。既存unit test（早期gate
     `SET_MISMATCH` + `intentScopeCount` 維持）は回帰として残す。
2. **選択面差分と復元の純粋導出（`ScopeBindingGate` または隣接純粋object）**
   - diff: 入力 `(依頼scope identity集合, 検出cut, 現行選択)`、出力
     `missing（依頼scopeかつ未選択。すべて選択可能row）` / `extra（選択だが依頼scope外）` /
     `unresolvable（依頼scopeだが選択可能rowに存在しない）`。UI表示はこの結果のみを受け、
     判定をUIへ置かない。
   - 復元初期値: 入力 `(依頼scope identity集合, 検出cut)`、出力 `scope ∩ detected`
     （解決可能な依頼scope候補の集合）。決定的・副作用なし。
3. **rebind入力再構築seam（personalization配下の新規純粋object。仮称
   `RebindIntentRebuilder`）**
   - 入力: `DurablePendingIntent`（#374 record）、現行active session、現行構造digest、
     now（注入clock）。
   - 出力: rebuild結果（`ValidatedPersonalizedIntent` 相当のplanning入力 **と**
     源recordの同一性材料）、またはtyped失敗（session不在/TTL/`exportId`不一致/破棄mark →
     `reconcilePendingIntent` と同判定。**構造digest不一致 → `CONTEXT_STALE`意味論の
     typed失敗（spec Contract notes 1）**）。
   - 手順: (a) `reconcilePendingIntent(record, session, now) == Valid` の再確認
     （既存純粋関数の再利用。二重化ではなく同一判定の共有。**本Issueでreconcileへ追加する
     identity shape検証 — schema不一致・digest長不正のrecordを破損として`Invalid`へ落とす
     純粋追加 — を含む。`IntentIdentity`構築時の`require`例外化を防ぎ、全readerで
     一貫したtyped fail-closedにする。2nd review指摘3**）,
     (b) `session.sourceContextDigest == 現行構造digest`（不一致は継続不可・提案残存）,
     (c) `SessionExportReconstructor.rebuild(session, currentStructural)` でexport view再構築
     （既存seam再利用。失敗は `ContextStale` 意味論。`ExchangeImportPipeline` 131-134行と
     同一mapping）, (d) recordの `toDecisionsMap()` からplanner入力を構築する —
     **identityは構築しない・再導出しない**。`ValidatedPersonalizedIntent` の
     constructorへ `IntentIdentity(record.intentIdentitySchemaVersion,
     record.intentIdentityDigest)` を**そのまま注入**する（#374 Contract notes 3
     「#375のrebindは保存済みidentityをそのまま用いる」の実装。`rationale`/`confidence`
     はrecordに存在せず再導出値がimport時identityと一致する保証がないため、注入が唯一の
     契約整合解。review指摘2の確定）。
   - **初回draftで未決定だった構築案 (i)/(ii) は本revisionで解消済み**: 案 (i)の
     「record decisionsから最小 `PersonalizedIntentV1` を構築しcompletedを再現」を採るが、
     **identityのみrecord保存値の注入**へ変更（初回draftの「identityを既存calculatorで
     再導出できる」記述は誤りだった — calculatorは `rationale`/`confidence` 行を含むため
     再導出値は一致しない。review指摘2で修正）。`IntentPlannerAdapter.project()` は
     `validated.completed.decisions`（planner効果）と `validated.identity`（provenance）を
     別々に消費するため、completedの再現（decisions round-trip）とidentityの注入は
     独立に成立する。oracle: 「record decisions → 再構築 → completed == record decisions」
     の往復property test ＋「rebuild結果のprojection（`IntentPlannerAdapter.project`）が
     import直後のprojectionと全field同一（identity含む）」の等価test（SR-AC-03）。
   - anchor用の同一性材料: rebuild出力は**源recordそのもの**を含み、admission anchorの
     比較は **`DurablePendingIntent`のdata class完全一致**（`entryKind`を含む全field）とする
     （部分タプル比較では同一session宛の同一内容再取り込みで`entryKind`のみ変わる置換 —
     復元モードの変化 — を見逃すため。schema拡張不要の最小解。2nd review指摘2）。
4. **coordinator / state の拡張（`ManualOrganizationRun`）**
   - `State.Selecting` へ `intentScopeCandidates: Set<CandidateTarget.AppKey> = emptySet()` と
     `restoredSelection: Set<CandidateTarget.AppKey> = emptySet()` を追加
     （既存 `intentScopeCount` は後方互換のため残す。既存testの期待値を壊さない）。
     emptyが「無し」を表す。内部identity型のみで、durable型・platform型は漏らさない。
   - **admission anchor hook（新設。review指摘1の解消）**: `start` へ
     `admissionAnchor: (() -> AdmissionVerdict)? = null`（命名は実装PRで確定。
     `AdmissionVerdict` は `Admit` / `Refuse` の小さなenum）を追加する。
     `beginOperation` の `synchronized(lock)` 区間内 — active operation不在確認の後、
     operation生成と `State.Capturing` 発行の**前** — でanchor呼出を行う:
     `Refuse` のときは取得直後のRUN leaseを `close()` して何も発行せず
     typed拒否（`StartOutcome`へ `AdmissionRefused` 相当の新系を追加。237-240行の
     sealed interface拡張）を返す。既存呼出（trigger引数のみ・intent引数のみ）は
     既定値 `null` で不変であり、anchorが`null`のとき挙動は現行同値である。
     anchor closureの内部で新鮮なrecord/session/clock読取とanchor判定を行うため、
     **検証時点とadmission時点が同一排他境界内の同一時点で決定的になる**
     （「stale時はrun admission自体を発生させない」を構造で充足。後段abort方式は採らない —
     Alternatives rejected）。
   - `start(trigger, intent, admissionAnchor, selectionRestore)` — 復元モードの指定
     （例: sealed interface `SelectionRestore { None, PreviousExplicit }` 相当。
     命名は実装PRで確定）。`PreviousExplicit` のとき検出後に復元導出を評価し
     `restoredSelection` へ設定する。呼出側（holder）が #374 recordの `entryKind` から
     モードを写像する（RUN_IN由来のrebindのみ `PreviousExplicit`。idle由来・同一process内
     既存CTAは `None`）。durable record型をrun seamへ渡さない。
   - `confirmSelection()` はcause導出を (1) に置換する以外は現行構造（intent維持・
     Selecting復帰・zero-write）を維持する。
5. **UI（`MissingAppSelectionScreen.kt` / `ManualOrganizationPreferences.kt`）**
   - 選択stateのseed: `remember(selectingState?.runId)`（252-254行）の初期値を
     `MissingAppSelectionState(candidates, selectingState?.restoredSelection.orEmpty())` へ。
     復元がある場合もD-1の規約上は「初期値」であり、既存のbulk操作・検索・編集契約は無変更。
   - 差分強調: `missingAppSelectionItems()` へdiff結果を渡し、依頼scopeに含まれる候補rowと
     依頼外の選択rowを強調する（非色依存のaffordance＋semantics state description）。
     `unresolvable` はrowとして存在しないため、案内text側で説明する（選択修正では救済不能な
     旨。spec Failure behavior表）。
   - cause別案内: `exchangeContractFailureText` 系のmappingをcause別へ拡張する
     （`SET_MISMATCH` = 修正案内（続行可を明示）/ `CANDIDATE_UNRESOLVED`・
     `PROJECTION_MISMATCH` = 続行不能・依頼作り直し案内）。#373実装の
     `ExchangeImportFailureDisplay`（import失敗面の `RECREATE_REQUEST` remedy）との
     文言接続はspec Open questions 3のとおり表示面のみで、remedy区分は変更しない。
6. **再開面CTA（`ExchangeFlowUi.kt` の `ExchangeScreen.ImportReview` 面。#374着地構造の上）**
   - 「この提案で続ける」CTA: reconcile通過済みのproposalにのみ表示（#374の
     `openPendingImportReview()` 採用契約の再利用）。押下 → single-flight開始
     （`continuing` 規律。処理中の破棄/Back不受理。spec 328 AC-3と同一）→
     **exchange mutation gate（Design 7）を保持したまま**IO上で:
     (a) 新鮮な `pendingImportStore.load()` + `controller.activeSession()` + clock読取 →
     `reconcilePendingIntent` → (b) (3)の再構築seam（構造digest再検証を含む）→
     typed失敗ならCTA面上のtyped案内（run admissionなし。record残存。`CONTEXT_STALE` 意味論）→
     (c) 有効なら gateを保持したまま
     `run.start(intent = rebuilt, admissionAnchor = { 新鮮読取によるanchor判定
     （reconcile Valid + record完全一致〔`entryKind`含む〕 + TTL） },
     selectionRestore = entryKindから写像)`。
   - anchor結果の扱い: `Busy` → 既存どおりtyped拒否。`AdmissionRefused` → typed拒否を
     面へ表示したうえで面を直ちに読み直す — recordが無効化済みなら `openPendingImportReview()`
     と同一のreconcile経路で清掃・面クローズ（#374契約の再利用）、同一session宛の再取り込みで
     recordが置換されていれば新しいrecordの `ImportReview` 表示へ更新する
     （stale表示の継続をしない。spec Stale state / concurrency）。`Started` なら
     run面（T-09/T-08）へ遷移して成功settle。
   - **成功settleはrecordを削除しない**（spec Contract notes 2。#374の消失系契約維持）。
   - CTA処理中はholder上の取り込み状態を変える操作（破棄・Back・T-15生成導線への離脱）を
     不受理にする（現行 `continuing` 規律の継承）。
7. **exchange mutation gate（process-wide共有排他seam。新設。2nd review指摘1の解消）**
   - **単一の直列化点**: `active session` と `durable record` を変化させうる全操作と、
     rebind継続の [新鮮読取 → anchor判定 → run admission] 区間を、1本の共有lock
     （仮称 `ExchangeMutationGate`。薄いprocess-wide object。productionはDI singleton、
     holderとcontrollerへ注入）で直列化する。対象操作:
     (a) session置換（`ExchangeFlowController.generate()` 内の新session保存＋旧record削除）,
     (b) pre-send cancel等のsession invalidate,
     (c) import成功時のdurable保存（`launchDurablePendingIntentSave`）,
     (d) 破棄tombstone（`discardImport()` の `store.discard()`）,
     (e) reconcile清掃（`openPendingImportReview()` / anchor拒否後の `delete()`）,
     (f) rebind継続sequence。
   - **既存lockとの関係**: 正当性の根拠はgateである。#374の`pendingWriteMutex`はsave内部の
     attempt-fence論理の実装詳細として残すかgateへ包含するかを実装PRで確定する
     （包含する場合、#374のsave fence oracle群は挙動不変でgreenであることを回帰で確認）。
     gate下の各区間はrecord1件・session1件の小さな`AtomicFile`読書きと判定のみであり、
     readiness gate・model load等の長時間処理は行わない（UIのcancel/confirmを長時間
     blockしない境界）。
   - **gateを要求しない経路**: `continueImport` のvalidation→`connectRun`（attach/start
     連結）はsession/recordを変化させないため対象外（durable保存部分のみgate下）。
     run gate（単一active run）・CTA single-flightとの役割分担はspec Stale state /
     concurrency節のとおり。
   - **lock順序の不変条件**: 取得順は「gate → run内部lock → anchor内のstore読取」の
     一方向のみである。gate保持下でrun seamを呼ぶのはrebind継続のみ、run lock区間内で
     gateを取得する経路は存在しないため、deadlock経路は生じない。anchor内の読取は
     record1件とsession1件の小さなlocal file読取であり、anchorが`null`の既存経路には
     読取は発生しない。

### Data flow（rebind継続の全体像）

```text
status card行（#374 reconcile通過）
  → Hub → ImportReview再開面（内容・残時間・破棄。#374着地済み）
  → 「この提案で続ける」（single-flight。exchange mutation gate保持）
      → 新鮮読取: record + active session + clock → reconcilePendingIntent
        （identity shape検証を含む）
      → RebindIntentRebuilder: reconcile再確認 → 構造digest等価（CONTEXT_STALE意味論）
        → export view再構築 → planner入力再構築（identity = record保存値の注入）
      → 失敗: typed案内（依頼を作り直す）。run admissionなし。record残存
      → 成功: run.start(intent, admissionAnchor, selectionRestore)
          admissionAnchor（gate保持・run lock区間内・新鮮読取）:
            reconcile Valid + record完全一致（entryKind含む） + TTL
          → Refuse: typed拒否（lease解放・状態発行なし）。面は読み直し（清掃クローズ or 更新）
          → Admit: ここで初めてRUN admission（State.Capturing発行）
              → 検出 → State.Selecting(intentScopeCandidates, restoredSelection)
                  （RUN_IN由来: 復元初期値。IDLE由来: unchecked＋件数案内）
              → 選択編集（自由） → 「続行」confirm（明示確認1回）
                  → 早期gate（cause導出: SET_MISMATCH=修正で続行可 /
                     CANDIDATE_UNRESOLVED=続行不可） → composed
                  → evaluateScopeBinding（既存・無変更。PROJECTION_MISMATCH）
              → planning（既存IntentPlannerAdapter経路。identity = import時と同一）
                → 確認面（既存）
```

同一process内の既存経路（import成功 → `continueImport` → `connectRun` → attach/start）は
#374が維持する契約どおり不変であり、本計画が触れるのは (1) のcause label導出、
(2) の差分表示、(4) のstate field追加・anchor hook、(6) のCTA追加、(7) のexchange
mutation gate新設である。

### Alternatives rejected

- **確認なしの完全自動復元**（復元＋自動confirm）: TO-BE D-17が明示却下（spec 228明示選択
  契約を弱める）。採らない。
- **anchorなしの「verify-then-start」（gate直列化のみで閉じる）**: record/session書込との
  混線は防げるが、verify時点とadmission時点が異なり、TTL境界越えが決定的に捕捉できない
  （時間はlockで直列化できない）。admission区間内の新鮮読取（anchor hook）と併用して
  初めて決定的になるため不採用（review指摘1の「同一排他境界」要求）。
- **holder内mutex（`pendingWriteMutex`）拡張のみで閉じる案（初回re-entry案）**: session置換
  （`generate()`の新session保存＋旧record削除）がholder mutex外で実行され、session storeと
  pending storeが別内部lockのため排他として不足。process-wideなexchange mutation gateへ
  改訂（2nd review指摘1）。
- **後段abort方式（admission後に遅延検証でabort）**: RUN lease消費・`State.Capturing` 等の
  可観測state発行・journal書込が発生したあとの取り消しとなり、観測契約が複雑化する。
  fail-closed原則はadmission前の単一排他境界での拒否で充足できるため不採用。
- **rebindでidentityを再導出する案**（record decisionsから最小intentを組み
  `IntentIdentityCalculator` で再計算）: calculatorのcanonical表現は `rationale` /
  `confidence` 行を含むため、record（非永続field）からは元のidentityを再現できない。
  import直後とcold rebind後でprovenance identityが変わり、policy provenance契約を壊す。
  record保存の `IntentIdentity` 注入へ確定（review指摘2）。
- **planner-effective contentのみから別identityを定義する案**（review指摘2の選択肢2）:
  #374が既にimport時identityをrecordへ保存しており（Contract notes 3確定済み）、
  別identity定義はspec 204/330のprovenance契約の改訂を不要に要求する。不採用。
- **依頼scope・ref対応表・labelの#374 recordへの複製**: session側正本の複製であり、
  #374のprivacy境界（label/ref対応表/`rationale`/`confidence`非永続）にも抵触する。
  reconcileのlifetime規約（提案が有効な間はsessionが有効）により参照先消失も契約上発生しない。
- **`SCOPE_MISMATCH`の複数class分割**: spec 331 D-5が単一class＋cause detailを選択済み。
  remedy表示の分岐で対応する。
- **早期gate失敗時のintent破棄**: D-17のSET_MISMATCH remedy（同じ提案で続行）と矛盾。
  現行実装もintentを維持しており、現行挙動を契約化する。
- **継続時の構造digest再検証を省略しimport時検証のみに依存**: 24時間窓でfail-openになる
  （planner投影の消失ItemId参照に到達しうる）。不採用（spec Contract notes 1）。
- **diff強調をUI側のscope照合で実装**: scope identity集合がstateに無く、UIがcoordinator
  内部を知る必要がある。state経由の純粋導出に分離する（計算は計画module、表示はUI）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/331-.../spec.md` | D-2 remedy分割・§5経路更新（`Hub → ImportReview` 1経路＋rebind）・attach生存範囲の明確化 | disposition §3.16/§4.1/§5 更新順序 #9（本Issueが所有） |
| `specs/228-.../spec.md` | D-1への復元初期値例外の注記（明示confirm必須） | disposition §3.13（#375が所有） |
| `personalization/exchange/ScopeBindingGate.kt` | 純粋追加: 早期gate cause導出・差分/復元導出 | gate意味論の単一保持点。既存 `evaluate()` は無変更 |
| `ui/ManualOrganizationRun.kt` | `confirmSelection` のcause導出置換・`State.Selecting` field追加・`start` へのadmission anchor hook（新typed `StartOutcome`系）と復元モード引数 | coordinatorが選択確定点・admission境界・復元の所有者 |
| `personalization/` 配下 | rebind入力再構築の純粋seam（新規。identity注入・anchor用同一性材料を含む）＋typed失敗 | disposition §11「実装位置は#375のplanが所有」。既存pipeline/reconstructor/reconcileの再利用 |
| `ui/MissingAppSelectionScreen.kt` | 差分強調の表示・semantics | T-08面の表示契約（spec SR-AC-01） |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | 選択stateの復元seed・cause別案内描画 | hosting層。既存rejection描画の拡張 |
| `ui/exchange/ExchangeFlowUi.kt`（#374着地構造） | 再開CTA（single-flight・gate保持の継続sequence・anchor closure組立）・`exchangeContractFailureText` のcause別拡張・破棄/清掃deleteのgate配下化 | 既存CTA規律（spec 328 AC-3）と#374 CTA様式の継承 |
| `organizer/integration/exchange/` 配下（controller/module）とholder・controller間 | **exchange mutation gate（process-wide共有排他）の新設と注入**。controller `generate()` のsession保存＋旧record削除・pre-send invalidateをgate配下へ | session/recordを変化させる全操作とrebind admissionの直列化点（spec Stale state契約の実装） |
| `personalization/exchange/PendingIntentReconcile.kt` | 純粋追加: identity shape（schema一致・digest長）の破損検証を`Invalid`判定へ | 全readerで一貫したtyped fail-closed（例外化しない）。#374破損契約のidentity次元追加 |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | cause別案内・差分強調a11y・CTA label（T-18語彙）・anchor/継続typed案内 | spec 123契約（ja正本＋en） |
| `tests/unit/.../ManualOrganizationRunTest.kt` ほか | cause導出・復元・継続・anchor/race oracleの新oracle＋既存回帰の無編集green確認 | spec Test oracle表 |
| `tests/organizer-instrumentation/.../ui/` | 差分強調・rebind統合（冷起動）・CTA規律のinstrumentation | spec SR-AC-01/03/07/08 |

source変更は上記のみ。`favorites` / layout DB / recovery DB / export session store /
#374 store schema への接触なし。

## Migration and recovery

- **schema/rule migration**: なし。新規persistent state・既存store format変更なし。
  #374 recordは既存field（`intentIdentity`・`entryKind`含む）のみを消費する。
- **failure中のrollback**: gate失敗・再構築typed失敗・anchor拒否はすべてzero-write
  （既存不変条件の継続）。復元初期値はprocess-localな選択stateであり、失敗時に掃除すべき
  永続物がない。
- **release rollback/downgrade**: PR revertで現行挙動へ戻る。#374 storeが存在しない環境では
  再開面CTAは対象が存在せず自然に無効（表示経路ごと存在しない）。#374着地後のrevertでも
  本機能の追加は表示・検証のみであり残留物は生じない。
- **backup/restore compatibility**: 影響なし（本機能は永続化しない。#374 store自体が
  backup対象外）。
- **ホームレイアウト安全規約**: `favorites` 無接触のため適用対象外。zero-write gateの
  回帰（SR-AC-04）が最終防衛。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| SR-AC-01 | unit: `confirmSelection`の`SET_MISMATCH`（intent維持・修正後通過・zero-write）+ 差分導出純粋関数のtable test。instrumentation: 差分強調表示・非色依存semantics | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` + organizer instrumentation lane |
| SR-AC-02 | unit: 解決不能候補のcause優先（uninstall/disable/配置済みfixture）+ `PROJECTION_MISMATCH`案内区分 + 既存 `ExchangeTargetScopeCouplingTest` 無編集green | 同上 |
| SR-AC-03 | instrumentation: 冷起動 → status card → 再開面 → CTA → 検出 → 復元選択面 → confirm → 確認面。idle由来の非復元。冷起動emulator evidence（manual記録）。unit: rebind再構築seamのprojection全field等価oracle（identity=record保存値）＋decisions round-trip property test | organizer instrumentation lane + API 36 emulator + unit gate |
| SR-AC-04 | 既存scope gate系test群（`ManualOrganizationRunTest` 1343/1367/1400行相当、`ExchangeTargetScopeCouplingTest`）の無編集green + diff review | unit gate |
| SR-AC-05 | instrumentation: 通常run・idle継続のunchecked回帰 + rebind復元の「編集可・confirm必須」（confirmなしでplanに進まない否定的観測） | organizer instrumentation lane |
| SR-AC-06 | 既存attach/freeze oracle（`attachIntent`回帰、`ExchangeImportSuccessInstrumentationTest`）無編集green | instrumentation lane |
| SR-AC-07 | holder/instrumentation: reconcile不通でCTA非表示、single-flight、Busy拒否、成功後record残存・再継続可 + **race oracle（fake store/clock＋実際の`generate()`相当置換経路との並行）: rebuild成功 → session置換/破棄tombstone → anchor typed拒否・run不在の否定的観測（layout/journal 0件とcleanup件数を分離）** + **entryKindフリップ再取り込みoracle（置換検出→拒否→新record読み直し）** | unit + instrumentation |
| SR-AC-08 | unit: 再構築seamのtable test（digest不一致/session不在/TTL → typed失敗、record残存）+ **anchor table test（Valid/record不一致〔entryKind含む〕/TTL越え/identity shape不正）＋「rebuild成功 → TTL境界越え → admission」race oracle（fake clock）** + **identity破損fixture（wrong schema・digest長不正のvalid JSON）で例外なし・typed fail-closed・run不在** + **gate契約のdiff review（session置換・pre-send cancel・record変化操作の全gate配下化。gate不在の競合経路が存在しないこと）** + instrumentation（CTA押下でrun不在の否定的観測・anchor拒否後の面の扱い） | unit + instrumentation + diff review |
| SR-AC-09 | spec 331/228 diff review（Scope節と一致・gate規則不変）+ owner受入記録（Issue #375コメント） | PR diff review |
| SR-AC-10 | strings走査（ja/en name集合・placeholder一致、spec 123 AC-5方式）+ hardcoded literal grep + a11y assertion + light/dark × ja/default screenshot | unit + manual evidence |

含めるべき観点: unit/contract（純粋導出3種の決定性・境界・anchor判定のtable test）、
failure injection（session不在・digest不一致・record不一致・TTL越え・置換/破棄競合・
write不要経路のzero-write証明）、UI/accessibility（semantics・traversal・200%・announce）、
integration（冷起動rebind・単一active run競合）、property（record decisions→再構築→
completedの往復）。performance観点は新規ではなく既存検出/composition経路の再利用のみのため
対象外（NFR-006の既存budget外の新経路なし）。

## Documentation updates

- [x] spec 331改訂（本Issueの実装成果。受入後に実装。SR-AC-09）
- [x] spec 228注記（同上）
- [x] `CONTEXT.md`（原因別remedy / rebind / 選択復元初期値の用語追加 — **本Issueが正本改訂の
      所有者**。#365は完了済みのため、受入後の実装PRで反映する。初回draftの
      「#365との調整」記述を解消）
- [ ] `DESIGN.md` gate 13行（scope binding gateの参照先は変えない。rebind契約の参照追加が
      必要かはspec 331改訂diff review時に判断。既存行は「scope binding gateはspec 331所有」
      で本変更後も正しい）
- [ ] ADR: 不要（本計画の判断はすべて既存accepted契約（TO-BE D-17・disposition・spec 331
      D-5・#374 Contract notes 3）の範囲内。anchorをadmission側の検証点として新設する判断は
      spec 331 gate（candidate側2検証）の外側・admission契約側の追加であり、gate規則の
      変更を含まないためADR条件（変更が高コスト/理由がコードから分からない/実際の選択肢が
      あった）の新規判断には該当しない）

## Execution checklist

- [ ] spec 331/228改訂docsを先行作成しowner受入を得る（AGENTS.md「正本を先に」。#374の
      spec 328 rev.2前倒しと同一様式）
- [ ] Current behavior reproduced: 既存scope gate系testの現行期待値（`SET_MISMATCH`単一
      案内・intent維持/破棄の区別）をgreenで確認
- [ ] 純粋seam（cause導出・diff・復元）を先に実装し、失敗するtestを先に追加（TDD）
- [ ] coordinator/state拡張（`confirmSelection`・`State.Selecting`・`start` anchor hook＋
      復元モード・新typed `StartOutcome`系）
- [ ] exchange mutation gate新設（controller `generate()`置換経路・pre-send invalidate・
      durable save・破棄・清掃delete・rebind sequenceのgate配下化。lock順序の一方向性確認）
- [ ] rebind再構築seam（identity注入・anchor用源record）＋構造digest再検証（typed失敗含む）
      ＋往復property test＋projection全field等価oracle
- [ ] reconcile純粋追加: identity shape破損検証（`Invalid`へ。fixture: wrong schema・
      digest長不正のvalid JSON）
- [ ] admission anchorのtable test＋race oracle（置換〔generate()相当経路と並行〕/破棄/
      TTL越え/entryKindフリップ。fake store/clock）
- [ ] UI（差分強調・cause別案内・復元seed）＋strings（ja/en）
- [ ] 再開面CTA（single-flight・gate保持sequence・Busy/AdmissionRefused扱い・record残存）
      ＋navigation
- [ ] instrumentation（rebind冷起動統合・CTA規律）＋冷起動emulator evidence
- [ ] 既存回帰の無編集green確認（scope gate・attach・CTA契約）
- [ ] PR evidence（実行した検証コマンドと結果・残リスク・Contract notes 1のowner確認結果）
      を記録

## Explicitly unverified areas

- **rebind入力再構築の具体shape**: 最小 `PersonalizedIntentV1` の構築詳細（必須fieldの
  充たし方・`IntentCompletion.complete` とのround-trip成立）は、等価oracle（SR-AC-03）と
  往復property testの実装時に確定する。**identity契約自体は確定済み**
  （record保存値の注入。#374 Contract notes 3との契約連携。初回draftの「実装時検証」
  項目を解消 — review指摘2）。
- **同一process内CTAへの構造digest再検証の適用要否**: spec Contract notes 1のowner確認待ち。
  適用しない場合の現行挙動（短時間窓・import時検証のみ）は既存契約のまま。
- **gateの実装形態**: 共有lock objectの型（coroutine Mutex 1本の薄いobject）、
  `pendingWriteMutex`の包含可否（save fence論理の移管有無）、DI singletonの提供位置は
  実装PRで確定する（契約 — session/recordを変化させる全操作とrebind admissionの直列化 — は
  spec Stale state / concurrency節で固定済み）。
- **anchor拒否後の面読み直しの表示詳細**（清掃クローズ時のtyped文言・置換読み直し時の
  遷移）は実装PRのstring diff / reviewで確定する（非blocking。契約は
  spec Stale state / concurrency節で固定済み）。
- **同一process run-in中のplaced item消失（import〜confirm間の構造変化）**: 既存から存在する
  隣接の未typed経路であり、本Issueのscope外（spec Non-goals）。rebind pathのみ再検証で
  閉じる。将来の別追跡が必要かどうかは実装PRの調査結果で判断する。

[r399]: https://github.com/nunu1733/NunuLauncher/pull/399
