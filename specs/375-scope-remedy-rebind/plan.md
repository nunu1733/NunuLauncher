# Implementation Plan: scope不一致の原因別remedy（SET_MISMATCH/PROJECTION_MISMATCH）と取り込み済み提案のprocess死後再開（rebind）

> Issue: #375
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下はmain `a2b6aba318`（2026-09-19時点のorigin/main、disposition着地後）での確認済み
実装事実である。推測は「未検証」に分離する。

### Gate と cause の現状

- **cause導出は既に存在する**: `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ScopeBindingGate.kt`
  の `evaluate()` は既に `ScopeMismatchCause`（`IntentValidator.kt` 208行付近のenum。
  `SET_MISMATCH` / `CANDIDATE_UNRESOLVED` / `PROJECTION_MISMATCH`）を導出する:
  依頼scope候補が検出cutに不在・非`AVAILABLE` → `CANDIDATE_UNRESOLVED`（30/32行）、
  依頼scope候補が未選択・依頼外の追加選択 → `SET_MISMATCH`（35/43行）、
  `scopeCandidateDigest` ≠ `CandidateScopeIdentity.digest(current.candidateProjections)` →
  `PROJECTION_MISMATCH`（49-51行）。解決不能の判定が集合差の判定に**先行する**順序は
  既に正しい（spec.mdのcause優先契約と整合）。
- **confirm時早期gateはavailabilityを見ていない**: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  の `confirmSelection()`（448行付近）は `intent.session.scopeCandidates` と確定選択の
  ソート比較のみで、不一致をすべて `SET_MISMATCH` として報告する（466-468行）。
  このとき **`operation.intent` は維持される**（unit test
  `scopeBindingRejectsASelectionMissingTheExportedCandidate` が `intentScopeCount == 1` の
  維持を固定。`tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` 1107-1131行）。
  つまり同一process内の「選択を修正して同じ提案で続行」は構造として既に成立しており、
  本Issueは契約明文化＋差分強調＋cause別案内の追加である。
- **後段gate失敗時はintent破棄＋選択面復帰**: `runComposedPhase` 内のgate失敗処理
  （630-661行）は、検出cutが存在する場合 `State.Selecting(..., intentScopeCount = 0,
  scopeRejection = failure)` へ復帰し **`operation.intent = null`** とする
  （unit test `scopeBindingProjectionDriftReturnsToSelectionWithZeroWrites` 1131-1163行が
  復帰後のre-attach可を固定）。検出cutが無い場合は journal event＋
  `State.ScopeMismatchFailed(failure)` 終端（651-661行、
  `scopeBindingWithoutASelectionSurfaceFailsTypedZeroWrite` 1164-1180行）。
- **State.Selectingの現行field**: `runId` / `detectedCandidates` / `intentScopeCount` /
  `scopeRejection`（245-254行付近）。依頼scopeのidentity集合はUIへ露出していない
  （件数のみ）。差分強調に必要な材料がstateに存在しないことが確認された。
- **`start(trigger, intent)`**: 388-427行。検出 → `State.Selecting(runId, candidates,
  intentScopeCount = intent.session.scopeCandidates.size)`。検出`Unavailable` なら
  選択面を開かずcomposed phase直行（既存契約、spec 228 §7）。
- **attach**: `attachIntent()`（482行付近）は選択面が開いている生存runへのsingle-shot、
  zero-write、`NotAttachable` 戻り値。run-in attach契約の実体。

### 選択面UI の現状

- **差分強調は存在しない**: `MissingAppSelectionScreen.kt` の
  `missingAppSelectionItems()` はbound intent時の件数案内
  （`R.plurals.exchange_scope_intent_count`、117-131行）と `editsEnabled` 凍結のみ。
  `ManualOrganizationPreferences.kt` 412-424行が `scopeRejection` を
  `exchangeContractFailureText(rejection)` でerror色text描画（live region assertive）。
  `exchangeContractFailureText` は `ExchangeFlowUi.kt` 1857行で
  `ScopeMismatch → R.string.exchange_failure_scope_mismatch` の単一mapping
  （en 1373行 / ja 461行「選択したアプリが依頼内容と一致していません。同じアプリを選び直すか、
  依頼を再作成してください。」）。原因別の文言が存在しない。
- **選択stateの初期化点**: `ManualOrganizationPreferences.kt` 224-227行
  `var missingAppSelection by remember(selectingState?.runId) {
  mutableStateOf(MissingAppSelectionState(selectingState?.candidates.orEmpty(), emptySet())) }`。
  **runIdをkeyにした常にuncheckedの初期化**（D-1/AC-3の実装）。復元初期値はこのseedを
  置き換える形で入るのが最小変更である。候補のlabel対応表
  （`missingAppSelection.candidates.map { it.target to it.label }`、409-411行）は既に
  UI側に存在し、差分強調の表示材料は追加のdata公開を必要としない（scope identity集合の
  state公開のみ必要）。

### 継続経路とsession正本の現状

- **process-local pending**: `ExchangeFlowUi.kt` の `ExchangeFlowStateHolder` が
  `pendingValidated`（570行）・`activeAttempt`（563行）保持。`continueImport()`（745行）→
  `connectRun()`（715行付近）: `RUN_IN` なら `run.attachIntent`（owningRunId照合つき）、
  `IDLE` なら `run.start(intent = ...)`。`discardImport()`（819行）はzero-write。
  single-flight・attempt token照合・`continuing` 中不受理の実装あり（spec 328 AC-3）。
  `ExchangeImportEntryKind { IDLE, RUN_IN }`（162行）。
- **import時の検証構造**: `ExchangeImportPipeline.kt` stage 2（105-138行）=
  session lookup → expiry → **構造digest等価**（125-127行、不一致は
  `ContextStale` typed失敗）→ `SessionExportReconstructor.rebuild(activeSession,
  currentStructural)`（131行、構造divergenceで再構築失敗）→ validator。
  **この検証はimport時限り**であり、継続（CTA→run開始）時には再適用されない。
- **planner投影のref解決は`getValue`**: `IntentPlannerAdapter.project()`
  （`IntentPlannerAdapter.kt` 20-60行）が `session.itemRefs.getValue(ref)` /
  `export.items.associate{...}.getValue(ref)` を使用する。参照先Itemが現行compositionに
  存在しない場合、例外経路になる（typedでない）。import時の構造digest検証がこの到達を
  構造的に防いでいる（rebindで24時間窓を開く場合は同等の再検証が前提）。
- **session正本**: `ContextExportModels.kt` の `ExportSession`（409-444行付近）は
  `exportId` / `itemRefs`（ref→内部id。placedはItemId、candidateは安定identity）/
  `categoryRefs` / `scopeCandidates`（依頼scope候補identityの正本）/
  `scopeCandidateDigest` / `sourceContextDigest` / `expiresAtEpochMs` を持つ
  （`ContextExportBuilder.kt` 181-207行で構築）。
  **復元初期値にrecord拡張は不要** — `scopeCandidates` がそのまま依頼時明示選択である
  （run-in flowは凍結集合=依頼scopeをconfirm時完全一致gateが強制するため）。
- **validated intentのidentity**: `IntentValidator.kt` 149-163行。
  `identity = IntentIdentityCalculator.identity(completed)`、`completed` はintent文書からの
  lazy再導出。rebindで文書非所持（recordはcanonical decisionsのみ。`rationale`/
  `confidence` は#374契約により非永続）の場合の再構築は「Design」節の新seamとなる。

### 先行spec draftの契約（本計画の前提）

- **#374 draft（`4b28b45543`、branch `issue-374-spec-plan`）**: durable record
  （`exportId`・canonical decisions・planner-effective `minimizeMovement`・失効時刻・
  **`entryKind`（IDLE/RUN_IN。#375の由来判定用）**・破棄mark）・読取時reconcile
  （起動時＋status card/ImportReview読取時）・再開面は **内容・残時間・破棄のみで
  継続CTAなし**（Contract notes 2が継続/rebindを#375へ委譲）・同一process内CTA従来挙動は
  #374が維持。
- **#369 draft（`3c39ceb2f8`、branch `issue-369-spec-plan`）**: T-08は候補あり時の
  一時遷移（D-06: 0件＋intent未bound-or-scope∅のときのみ不進入。intent-boundでscope候補が
  あるときは選択面を開く）・T-13統合失敗面に `ScopeMismatchFailed` を統合
  （spec 331のre-export案内を原因文言として保存）・「T-08選択面の復元初期値（D-17、#375）と
  scope mismatch remedyの原因別明文化（#375）」をNon-goalsとして明示委譲。
- **disposition（`a2b6aba318`）**: §3.16（spec 331 Amend。完全一致gate・zero-write・
  fail-closed不変。test migration「SCOPE_MISMATCH単一oracle→原因別oracle、rebindの新oracle」）、
  §3.13（spec 228の復元初期値Amendは#375）、§8（#374→#375。rebindの所有）、
  §11（選択復元初期値の実装位置は本planが所有）。

## Design

### Modules and interfaces

AGENTS.md設計規約（小さなinterface・既存seamの再利用・platform型を計画moduleのinterfaceへ
漏らさない）に従う。新設の純粋seamは3つまでに留め、gate自体の判定規則は1行も変更しない。

1. **早期gateのcause導出（`ScopeBindingGate` への純粋追加）**
   - `deriveConfirmMismatch(sessionScope, detected, selected): ScopeMismatchCause?`
     （命名は実装PRで確定。`null` = 集合一致）。依頼scope候補のうち検出cutに不在または
     非`AVAILABLE`のものがあれば `CANDIDATE_UNRESOLVED`、そうでなければ `SET_MISMATCH`。
     `evaluate()` と同じ「解決不能の判定が集合差に先行する」順序を共有する。
   - `ManualOrganizationRun.confirmSelection()` のinlineソート比較をこの呼出に置換する。
     **gateのpass/fail結果は1件も変わらない**（集合一致ならpass、不一致ならfailは現行同値。
     変わるのはfail時のcause labelのみ）。既存unit test
     （早期gate `SET_MISMATCH` + `intentScopeCount` 維持）は回帰として残す。
2. **選択面差分と復元の純粋導出（`ScopeBindingGate` または隣接純粋object）**
   - diff: 入力 `(依頼scope identity集合, 検出cut, 現行選択)`、出力
     `missing（依頼scopeかつ未選択。すべて選択可能row）` / `extra（選択だが依頼scope外）` /
     `unresolvable（依頼scopeだが選択可能rowに存在しない）`。UI表示はこの結果のみを受け、
     判定をUIへ置かない。
   - 復元初期値: 入力 `(依頼scope identity集合, 検出cut)`、出力 `scope ∩ detected`
     （解決可能な依頼scope候補の集合）。決定的・副作用なし。
3. **rebind入力再構築seam（personalization配下の新規純粋object。仮称
   `RebindIntentRebuilder`）**
   - 入力: #374 durable record（canonical decisions＋`minimizeMovement`＋`exportId`＋
     `entryKind`）、現行active session、現行構造digest、now（注入clock）。
   - 出力: `ValidatedPersonalizedIntent` 相当のplanning入力、またはtyped失敗
     （session不在/TTL/`exportId`不一致 → #374 reconcileと同判定。
     **構造digest不一致 → `CONTEXT_STALE`意味論のtyped失敗（spec Contract notes 1）**）。
   - 手順: (a) session有効性（#374 reconcile条件の再確認。二重化は同期check 1回）,
     (b) `session.sourceContextDigest == 現行構造digest`（不一致は継続不可）,
     (c) `SessionExportReconstructor.rebuild(session, currentStructural)` でexport view再構築
     （既存seam再利用）, (d) recordのcanonical decisionsからplanner入力を構築
     （`rationale`/`confidence` は存在しないため構成要素に含めない）。
   - **確定しない点（実装時に検証・確定）**: recordのcanonical decisionsから
     `ValidatedPersonalizedIntent` を満たす最小modelを組む方法は2案ある —
     (i) `IntentCompletion.complete()` がrecord decisionsを再現する最小 `PersonalizedIntentV1`
     を構築する（identityを既存calculatorで再導出できる）,
     (ii) `completed` を直接持つ薄いplanning入力型を、`IntentPlannerAdapter.project()` の
     入力契約を保った形で定義する。(i)を第一候補とする（既存型・既存identity導出の再利用。
     往復property test「record decisions → 再構築 → completed == record decisions」で固定）。
     (ii)を採る場合はplanner seamの拡張が要るためdiff reviewで根拠を記録する。
     またrebind由来入力の `identity` はrecord内容に対して決定的だが、import時identityと
     （`rationale` が存在した場合）一致しない可能性がある。identityのjournal/diagnosticsでの
     用途を実装時に確認し、観測上の非整合があれば記録する（未検証の項目）。
4. **coordinator / state の拡張（`ManualOrganizationRun`）**
   - `State.Selecting` へ `intentScopeCandidates: Set<CandidateTarget.AppKey> = emptySet()` と
     `restoredSelection: Set<CandidateTarget.AppKey> = emptySet()` を追加
     （既存 `intentScopeCount` は後方互換のため残す。既存testの期待値を壊さない）。
     emptyが「無し」を表す。内部identity型のみで、durable型・platform型は漏らさない。
   - `start(trigger, intent, selectionRestore)` — 第3引数は復元モードの指定
     （例: sealed interface `SelectionRestore { None, PreviousExplicit }` 相当。
     命名は実装PRで確定）。`PreviousExplicit` のとき検出後に復元導出を評価し
     `restoredSelection` へ設定する。呼出側（holder）が #374 recordの `entryKind` から
     モードを写像する（RUN_IN由来のrebindのみ `PreviousExplicit`。idle由来・同一process内
     既存CTAは `None`）。durable record型をrun seamへ渡さない。
   - `confirmSelection()` はcause導出を (1) に置換する以外は現行構造（intent維持・
     Selecting復帰・zero-write）を維持する。
5. **UI（`MissingAppSelectionScreen.kt` / `ManualOrganizationPreferences.kt`）**
   - 選択stateのseed: `remember(selectingState?.runId)` の初期値を
     `MissingAppSelectionState(candidates, selectingState?.restoredSelection.orEmpty())` へ。
     復元がある場合もD-1の規約上は「初期値」であり、既存のbulk操作・検索・編集契約は無変更。
   - 差分強調: `missingAppSelectionItems()` へdiff結果を渡し、依頼scopeに含まれる候補rowと
     依頼外の選択rowを強調する（非色依存のaffordance＋semantics state description）。
     `unresolvable` はrowとして存在しないため、案内text側で説明する（選択修正では救済不能な
     旨。spec Failure behavior表）。
   - cause別案内: `exchangeContractFailureText` 系のmappingをcause別へ拡張する
     （`SET_MISMATCH` = 修正案内（続行可を明示）/ `CANDIDATE_UNRESOLVED`・
     `PROJECTION_MISMATCH` = 続行不能・依頼作り直し案内）。#373/#369の統合面との接続は
     spec Open questions 3のとおり表示面のみ。
6. **再開面CTA（#374のImportReview再開面上。実装位置は#374の着地構造に従う）**
   - 「この提案で続ける」CTA: reconcile通過済みのproposalにのみ表示（#374契約の再利用）。
     押下 → single-flight開始（既存 `continueImport` と同一規律。処理中の破棄/Back不受理）→
     (3) の再構築seamでtyped失敗ならCTA面上のtyped案内（run admissionは発生しない）→
     有効なら `run.start(intent = rebuilt, selectionRestore = entryKindから写像)` →
     `Busy` ならtyped拒否、`Started` ならrun面（T-09/T-08）へ遷移して成功settle。
   - **成功settleはrecordを削除しない**（spec Contract notes 2。#374の消失系契約維持）。
   - #374の実装が未着地のため、本項のseam名・hostingは#374 spec/plan draft
     （`4b28b45543`）の定義を基準にし、着地後の実名へ追従する（未検証）。

### Data flow（rebind継続の全体像）

```text
status card行（#374 reconcile通過）
  → Hub → ImportReview再開面（内容・残時間・破棄。#374）
  → 「この提案で続ける」（single-flight）
      → RebindIntentRebuilder: session有効性 → 構造digest等価（CONTEXT_STALE意味論）
        → export view再構築 → planner入力再構築
      → 失敗: typed案内（依頼を作り直す）。run admissionなし。record残存
      → 成功: run.start(intent, selectionRestore)   ← ここで初めてRUN admission
          → 検出 → State.Selecting(intentScopeCandidates, restoredSelection)
              （RUN_IN由来: 復元初期値。IDLE由来: unchecked＋件数案内）
          → 選択編集（自由） → 「続行」confirm（明示確認1回）
              → 早期gate（cause導出: SET_MISMATCH=修正で続行可 /
                 CANDIDATE_UNRESOLVED=続行不可） → composed
              → evaluateScopeBinding（既存・無変更。PROJECTION_MISMATCH）
          → planning（既存IntentPlannerAdapter経路） → 確認面（既存）
```

同一process内の既存経路（import成功 → `continueImport` → `connectRun` → attach/start）は
#374が維持する契約どおり不変であり、本計画が触れるのは (1) のcause label導出、
(2) の差分表示、(4) のstate field追加のみである。

### Alternatives rejected

- **確認なしの完全自動復元**（復元＋自動confirm）: TO-BE D-17が明示却下（spec 228明示選択
  契約を弱める）。採らない。
- **依頼scope・ref対応表・labelの#374 recordへの複製**: session側正本の複製であり、
  #374のprivacy境界（label/ref/`rationale`/`confidence`非永続）にも抵触する。reconcileの
  lifetime規約（提案が有効な間はsessionが有効）により参照先消失も契約上発生しない。
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
| `ui/ManualOrganizationRun.kt` | `confirmSelection` のcause導出置換・`State.Selecting` field追加・`start` の復元モード引数 | coordinatorが選択確定点と復元の所有者 |
| `organizer/personalization` 配下 | rebind入力再構築の純粋seam（新規）＋typed失敗 | disposition §11「実装位置は#375のplanが所有」。既存pipeline/reconstructorの再利用 |
| `ui/MissingAppSelectionScreen.kt` | 差分強調の表示・semantics | T-08面の表示契約（spec SR-AC-01） |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | 選択stateの復元seed・cause別案内描画・再開面CTA接続 | hosting層。既存rejection描画の拡張 |
| `ui/exchange/ExchangeFlowUi.kt`（または#374着地後の対応構造） | 再開CTAのsingle-flight制御・`exchangeContractFailureText` のcause別拡張 | 既存CTA規律（spec 328 AC-3）の様式継承 |
| `res/values/strings.xml` / `res/values-ja/strings.xml` | cause別案内・差分強調a11y・CTA label（T-18語彙） | spec 123契約（ja正本＋en） |
| `tests/unit/.../ManualOrganizationRunTest.kt` ほか | cause導出・復元・継続の新oracle＋既存回帰の無編集green確認 | spec Test oracle表 |
| `tests/organizer-instrumentation/.../ui/` | 差分強調・rebind統合（冷起動）・CTA規律のinstrumentation | spec SR-AC-01/03/07/08 |

source変更は上記のみ。`favorites` / layout DB / recovery DB / export session store /
#374 store schema への接触なし。

## Migration and recovery

- **schema/rule migration**: なし。新規persistent state・既存store format変更なし。
  #374 recordは既存field（`entryKind`含む）のみを消費する。
- **failure中のrollback**: gate失敗・再構築typed失敗はすべてzero-write（既存不変条件の継続）。
  復元初期値はprocess-localな選択stateであり、失敗時に掃除すべき永続物がない。
- **release rollback/downgrade**: PR revertで現行挙動へ戻る。#374 storeが存在しない環境では
  再開面CTAは対象が存在せず自然に無効（表示経路ごと存在しない）。#374が着地した後のrevertで
  も、本機能の追加は表示・検証のみであり残留物は生じない。
- **backup/restore compatibility**: 影響なし（本機能は永続化しない。#374 store自体が
  backup対象外）。
- **ホームレイアウト安全規約**: `favorites` 無接触のため適用対象外。zero-write gateの
  回帰（SR-AC-04）が最終防衛。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| SR-AC-01 | unit: `confirmSelection`の`SET_MISMATCH`（intent維持・修正後通過・zero-write）+ 差分導出純粋関数のtable test。instrumentation: 差分強調表示・非色依存semantics | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` + organizer instrumentation lane |
| SR-AC-02 | unit: 解決不能候補のcause優先（uninstall/disable/配置済みfixture）+ `PROJECTION_MISMATCH`案内区分 + 既存 `ExchangeTargetScopeCouplingTest` 無編集green | 同上 |
| SR-AC-03 | instrumentation: 冷起動 → status card → 再開面 → CTA → 検出 → 復元選択面 → confirm → 確認面。idle由来の非復元。冷起動emulator evidence（manual記録） | organizer instrumentation lane + API 36 emulator |
| SR-AC-04 | 既存scope gate系test群（`ManualOrganizationRunTest` 1107-1180行相当、`ExchangeTargetScopeCouplingTest`）の無編集green + diff review | unit gate |
| SR-AC-05 | instrumentation: 通常run・idle継続のunchecked回帰 + rebind復元の「編集可・confirm必須」（confirmなしでplanに進まない否定的観測） | organizer instrumentation lane |
| SR-AC-06 | 既存attach/freeze oracle（`attachIntent`回帰、`ExchangeImportSuccessInstrumentationTest`）無編集green | instrumentation lane |
| SR-AC-07 | holder/instrumentation: reconcile不通でCTA非表示、single-flight、Busy拒否、成功後record残存・再継続可 | unit + instrumentation |
| SR-AC-08 | unit: 再構築seamのtable test（digest不一致/session不在/TTL → typed失敗、record残存）+ instrumentation（CTA押下でrun不在の否定的観測） | unit + instrumentation |
| SR-AC-09 | spec 331/228 diff review（Scope節と一致・gate規則不変）+ owner受入記録（Issue #375コメント） | PR diff review |
| SR-AC-10 | strings走査（ja/en name集合・placeholder一致、spec 123 AC-5方式）+ hardcoded literal grep + a11y assertion + light/dark × ja/default screenshot | unit + manual evidence |

含めるべき観点: unit/contract（純粋導出3種の決定性・境界）、failure injection
（session不在・digest不一致・write不要経路のzero-write証明）、UI/accessibility
（semantics・traversal・200%・announce）、integration（冷起動rebind・単一active run競合）、
property（record decisions→再構築→completedの往復）。performance観点は新規ではなく
既存検出/composition経路の再利用のみのため対象外（NFR-006の既存budget外の新経路なし）。

## Documentation updates

- [x] spec 331改訂（本Issueの実装成果。受入後に実装。SR-AC-09）
- [x] spec 228注記（同上）
- [ ] `CONTEXT.md`（原因別remedy / rebind / 選択復元初期値の用語追加 — 正本改訂は#365との
      調整のうえ、本Issueの受入時に案を反映）
- [ ] `DESIGN.md` gate 13行（scope binding gateの参照先は変えない。rebind契約の参照追加が
      必要かはspec 331改訂diff review時に判断。既存行は「scope binding gateはspec 331所有」
      で本変更後も正しい）
- [ ] ADR: 不要（本計画の判断はすべて既存accepted契約（TO-BE D-17・disposition・spec 331 D-5）
      の範囲内。3条件「変更が高コスト/理由がコードから分からない/実際の選択肢があった」に
      該当する新規判断は無い）

## Execution checklist

- [ ] spec 331/228改訂docsを先行作成しowner受入を得る（AGENTS.md「正本を先に」。#374の
      spec 328 rev.2前倒しと同一様式）
- [ ] Current behavior reproduced: 既存scope gate系testの現行期待値（`SET_MISMATCH`単一
      案内・intent維持/破棄の区別）をgreenで確認
- [ ] 純粋seam（cause導出・diff・復元）を先に実装し、失敗するtestを先に追加（TDD）
- [ ] coordinator/state拡張（`confirmSelection`・`State.Selecting`・`start`復元モード）
- [ ] rebind再構築seam＋構造digest再検証（typed失敗含む）＋往復property test
- [ ] UI（差分強調・cause別案内・復元seed）＋strings（ja/en）
- [ ] 再開面CTA（single-flight・Busy・record残存）＋navigation
- [ ] instrumentation（rebind冷起動統合・CTA規律）＋冷起動emulator evidence
- [ ] 既存回帰の無編集green確認（scope gate・attach・CTA契約）
- [ ] PR evidence（実行した検証コマンドと結果・残リスク・Contract notes 1のowner確認結果）
      を記録

## Explicitly unverified areas

- **#374/#369の最終契約**: 両specはdraft（`4b28b45543` / `3c39ceb2f8`）でありowner review
  前。再開面の構造・seam名・record型名は着地後の実名へ追従する必要がある。
- **rebind入力再構築の具体shape**: Design 3の(i)/(ii)選択、record decisions→intent modelの
  往復可能性、rebind由来identityのjournal/diagnostics上の意味（import時identityとの差の
  観測性）は実装時検証。
- **同一process内CTAへの構造digest再検証の適用要否**: spec Contract notes 1のowner確認待ち。
  適用しない場合の現行挙動（短時間窓・import時検証のみ）は既存契約のまま。
- **選択面が開く前の継続失敗の表示面**: #369 T-13統合が未着地のため、rebindの検出
  `Unavailable`等の既存経路文言は現行面に表示される（#369着地後は統合面）。表示面の移設は
  #369の差分に従う。
- **同一process run-in中のplaced item消失（import〜confirm間の構造変化）**: 既存から存在する
  隣接の未typed経路であり、本Issueのscope外（spec Non-goals）。rebind pathのみ再検証で
  閉じる。将来の別追跡が必要かどうかは実装PRの調査結果で判断する。
