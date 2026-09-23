# Implementation Plan: Organizerの対象scope確定を方法選択より先に行い、AI/非AIを同じ凍結scope上の兄弟分岐へ一本化する

> Issue: #417
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

観測は main `17d883a012d66833244d10c00d551215064c35d9` 時点。経路はすべてzero-write系（UI/flow）であり、DB書込み・persistent formatには触れない。

- **方法選択がscope形成より先**（問題の構造）: `ManualOrganizationPreferences.kt` のIdle/Cancelled branch（527-586行）がT-07前置き面であり、「そのまま整理」（`manual_organization_start`、549-570行）と「AIに相談」（`exchange_method_consult`、579-585行）を並べて提示する。AI行の`onClick = exchangeHolder::openFlow`はrun admissionなしでidle exchange flowを開く。idle生成は `ExchangeInputAdapter.composeForExport(now)` → `composeFullOrganization()` + 空candidate labels（`ExchangeInputAdapter.kt:44`）で、CANDIDATE subjectsを含まない。
- **run-in入口は選択面に同居**: `ManualOrganizationPreferences.kt:615-706` の`State.Selecting` branchが選択面とexchange flowを同一surfaceにhostする。`exchangeBusy = exchangeHolder.screen !is ExchangeScreen.Closed`（634行）→ `editsEnabled = !exchangeBusy`（686行）が、AI依頼の存在ではなくExchange画面stateだけで選択を凍結する。run-in entry（`ExchangeScopedEntryRow`、`ExchangeFlowUi.kt:2287-2317`）は選択0件でもrenderする（`scopedSelection`は空listでもnon-null、`ExchangeFlowUi.kt:1995-2008`）。
- **run-in生成のscope**: `generateScoped(tier, selection, labels)`（`ExchangeFlowUi.kt:611-628`）→ `controller.generateForSelection` → `adapter.composeForExport(now, selection, labels)` → `composeScopeComposedOrganization(selection)`（`ExchangeInputAdapter.kt:54-58`）。CANDIDATE subjectsは`ContextExportBuilder.kt:94-133`がadditionsから組む。
- **確認とcomposeの結合**: `ManualOrganizationRun.confirmSelection`（`ManualOrganizationRun.kt:807-867`）は早期scope gateの後、直接`runComposedPhase(operation, selection)`を呼ぶ。intentがboundされたrun（rebind/取り込み継続）のみ確認時に早期一致検証が効く。
- **attach契約**: `attachIntent`（`ManualOrganizationRun.kt:917-929`）は`State.Selecting`のみで束縛し、single-shot。`connectRun`（`ExchangeFlowUi.kt:1412-1435`）はRUN_INで生存runに`attachIntent`、IDLEで`run.start(intent)`。
- **検出とfreshness**: 検出はadmission時1回のfresh capture（`LayoutApplicationModule.detectMissingAppCandidates`、`LayoutApplicationModule.kt:182-200`）。cutは`State.Selecting`と`operation.detectedCandidates`に保持。composition時に`OrganizationInputComposer.kt:246-264`がfresh captureと再照合し、`CANDIDATE_SELECTION_STALE`でfail-closed。preview staleは`StaleOrigin.DETECTED_BEFORE_REVIEW`（`ManualOrganizationRun.kt:1232-1236`）。Home変化の監視・selection面での再検出は存在しない。
- **既存test資産**: `ManualOrganizationRunTest.kt`（attach/scope-binding）、`ExchangeFlowStateHolderTest.kt`、`MissingAppSelectionInstrumentationTest.kt`（0候補pass-through含む）、`ManualOrganizationPreferencesInstrumentationTest.kt`（T-07 AI行の存在assert `t07AiConsultationOpensTheRequestFaceWithoutRunAdmission`、418行 — 本specで置換される旧oracle）、`ExchangeImportSurfaceInstrumentationTest.kt`（scoped entry）。

## Design

### Modules and interfaces

変更はorganizer ui層とexchange flow層に局所する。planner・scope binding gate・durable store・export組成のinterfaceは変更しない。

- **`ManualOrganizationRun`（ui state machine）**
  - `confirmSelection(selection)`: 早期scope gate後、intentがbound済みなら現行どおり`runComposedPhase`へ直行（rebind/取り込み継続経路 = 確認→capture/plan）。intentが未boundなら**新state `State.ScopeConfirmed`**（runId、候補cut、確定selection、exchange生成に必要なcandidate labels、intentScope表示fields）をpublishして止まる。composeは開始しない。
  - **候補0件のpass-throughをstate層へ上げる**: 検出完了時に候補0件であれば`State.Selecting`をpublishせず直接`State.ScopeConfirmed(runId, selection=空)`へ進む（現行はdisplay層のpass-throughで内部stateは`Selecting(空)`に入る。#417では方法選択面が到達点になるため、state-levelで明示的な空scopeとして固定する）。
  - 新規`fun planWithConfirmedScope()`: `State.ScopeConfirmed`から`runComposedPhase(operation, selection)`へ進む（「このまま整理」の本体）。
  - `attachIntent`: 受理条件を`State.Selecting`に加えて`State.ScopeConfirmed`へ拡張。`ScopeConfirmed`での受理時は、確認時早期gateと同一のpure derivation（`ScopeBindingCauseDerivation.deriveConfirmMismatch`を確定selectionに対して実行）で一致検証し、不一致はtyped拒否（zero-write、方法選択面に rejection表示）。一致時はintentを束縛し`runComposedPhase`へ進む（取り込み成功CTA「この提案で続ける」が明示consent点であり、attach後の追加確認は挟まない）。
  - 新規`fun reopenSelection(): Boolean`: `State.ScopeConfirmed`から`State.Selecting`へ戻る（zero-write、選択内容はUI stateが保持）。**AI依頼がactiveな場合は受理しない**（UI側が先に破棄確認を経る。state machine側も`exchangeHolder`参照を持たないため、guardはUI層の呼び出し規律 + holder側のactive request照会で実装する）。
  - `State.Selecting`の契約は不変（未確定の選択面。exchange flowのhostからは外れる）。
- **`ExchangeFlowStateHolder` / `ExchangeFlowUi`**
  - 生成入力の供給源を「UI選択state」から「確定scope（`State.ScopeConfirmed`のselection + labels）」へ変更する。`generateScoped`のinterface（selection+labelsを受け取る）は不変で、呼び出し側の供給が変わる。
  - `ExchangeScopedEntryRow`（選択面上のentry行）と`Closed` branchのscoped entry rowを撤去する。`exchangeFlowItems`は方法選択面branchからのみhostする。
  - `beginImportAttempt`の`entryKind`: 生存runの`State.ScopeConfirmed`で作成された依頼は`RUN_IN`（`owningRunId`記録）。`IDLE`を新規に生成する経路はなくなる（既存recordの読み取り互換は維持）。`connectRun`のRUN_IN一致条件を`run.state is State.ScopeConfirmed && runId一致`へ更新（生存run attach）。IDLE由来recordの`run.start(intent)`経路はlegacy互換として維持。
  - `importAttemptActive`によるidle開始行の凍結は、開始行が「方法選択を含まない入口」になることで意味を失うため、入口面の整理とともに撤去する。依頼がactiveでもrun外からの新規run開始はRUN lease競合でBusyになるだけであり、凍結ではなくBusy表示で理由を説明できる（AC-5）。
- **UI面の再配置（`ManualOrganizationPreferences.kt` / `MissingAppSelectionScreen.kt` / hub）**
  - 入口面（Idle/Cancelled + T-07前置き面の後継）: scope summary（既存`manual_organization_preamble_scope`を維持）+ 開始CTA 1行のみ。CTA labelは方法を示さない「整理を開始」系の新文字列へ変更し、「そのまま整理」labelをscope選択入口から撤去する。AI行は置かない。
  - `State.Selecting`面: 選択項目 + 全選択/解除 + 「続行」。exchange flow items・scoped entry rowをhostしない。`editsEnabled`は常にtrue（凍結はこの面の外へ出る）。0件のまま「続行」した場合の明示helper文言（「未配置アプリを追加せず整理します」系）を`onConfirm`直前に表示する（新文字列）。
  - 新規`State.ScopeConfirmed`面（方法選択面）: 見出し「整理案の作り方」系 + 「このまま整理」行（`planWithConfirmedScope`）+ 「AIに相談」行（`exchangeHolder.openFlow`のscoped起動）。`exchangeFlowItems`をこのbranchでhostし、依頼作成〜取り込みの各state（T-15〜T-18、ImportSuccess）をここにrenderする。依頼がactiveな間、面内に依頼状態の行（作成中/送信前確認/取り込み中）が可視であることを凍結理由の表示とする。system Back: 依頼なし→`reopenSelection()`、依頼あり→破棄確認dialog（D-13「破棄」）→承認で依頼を破棄し`reopenSelection()`。
  - rebind/取り込み継続でintentがadmission時にboundされたrunは、確認（早期gate通過）後`runComposedPhase`へ直行するため、方法選択面は現れない（scenario「process死後の再開」どおり）。
  - `intentScopeCount` / scope diff / `scopeRejection`表示は`State.Selecting`の既存行を維持（rebind経路で使用）。`ScopeConfirmed`でのattach拒否時は方法選択面にrejection表示（`exchangeContractFailureText`を再利用）。
- **seam**: 変更の検証は既存seam（`ManualOrganizationRun`のstate observable + `ExchangeFlowStateHolder`のscreen observable）経由のみ。新しいproduction/test分岐seamは作らない。

### Data flow

入口 → `start(trigger)`（admission、RUN lease）→ `CandidateDetection`（fresh capture 1回）→ 候補あり: `Selecting`（未選択デフォルト）/ 候補0: `ScopeConfirmed`へ直接publish（selection=空）→ 選択面「続行」で`confirmSelection`（早期gate、intent未boundなら`ScopeConfirmed`）→ 方法選択面 →
- 「このまま整理」: `planWithConfirmedScope()` → `runComposedPhase(selection)` → preview
- 「AIに相談」: `openFlow` → 依頼作成（確定scopeから`generateScoped`）→ 送信前確認 → 外部AI → 取り込み → 検証 → 「この提案で続ける」→ `attachIntent`（一致検証）→ `runComposedPhase(selection, intent)` → preview

error: 早期/composition scope gate不一致はtyped `SCOPE_MISMATCH`（zero-write、rejection表示・remedy案内）。stale候補はcomposition時`CANDIDATE_SELECTION_STALE`（zero-write、typed再試行案内）。依頼生成・取り込み失敗は既存typed失敗面のまま。中断は両面でzero-write（選択ありの選択面からの離脱は既存どおり確認1回）。

### Alternatives rejected

- **代替案B（idle AIを「追加アプリを含めない別機能」として残存）**: Issue #417 Outcome「AIだけ別のscope形成タイミングを持たせない」に正面衝突するため却下。
- **代替案Cのrun外実装（admission前に検出・選択を行うpre-run scope formation面）**: 同じ順序を実現するが、検出・選択の面とstateをrun外に二重化する必要があり、RUN lease・中断・process死の意味が二重のstate machine交差を続けるため不採用。既存run内で同一順序を実現する。
- **方法選択面からのBack禁止（中断のみ）**: 状態機械は単純になるが、依頼なしのscope確定ミス時に選択をやり直すためにrun全体の中断・再検出・再選択を強いる。zero-writeで安全に戻れる経路を残す方を採用。
- **`State.Selecting`にconfirmed flagを追加する代案**: 新state `ScopeConfirmed`とし、凍結の状態を型で区別する（flag合流だとattach/editable条件が再びboolean合成になり、#417の問題を構造内に残す）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/product/organizer-to-be-ux.md` | Revision 6追記: D-04/D-05/D-17/§5.1/§5.2/§5.3をscope-first順序へ改訂 | UX正本。production変更より先（AC-10） |
| `docs/product/organizer-disposition-migration.md` | §5 supersession mapへ#417行（spec 369/372/331/367・idle入口Retire）追記 | 処分の正本 |
| `specs/417-.../spec.md` 本spec | status遷移（draft→accepted→implemented）とChange history | 同一PRで更新（specs/README規則） |
| `specs/369`, `specs/372`, `specs/331`, `specs/367` | 該当規定へのAmend/Supersede標記（契約本文は原則触らない） | 既存specの正確な状態表示 |
| `CONTEXT.md` | 「対象scope凍結」「方法選択面」の用語追加 | ドメイン語の正本 |
| `lawnchair/.../organizer/ui/ManualOrganizationRun.kt` | `State.ScopeConfirmed`新設、`confirmSelection`分岐、`planWithConfirmedScope`、`attachIntent`拡張、`reopenSelection` | run state machineの唯一の正 |
| `lawnchair/.../organizer/ui/MissingAppSelectionScreen.kt` | 0件続行helper文言、exchange hostの分離 | 選択面の契約（編集常時可） |
| `lawnchair/.../ui/preferences/destinations/ManualOrganizationPreferences.kt` | 入口面の方法選択撤去・CTA改名、方法選択面branch新設、`exchangeBusy`凍結の撤去、exchange hostの移設 | 画面正本 |
| `lawnchair/.../organizer/ui/exchange/ExchangeFlowUi.kt` | scoped entry行撤去、生成入力の確定scope化、`entryKind`生成、`connectRun`条件、依頼状態可視化 | exchange flow正本 |
| `lawnchair/res/values{,-ja}/strings.xml` | 入口CTA・方法選択面・0件helper・凍結理由の新文字列、撤去文字列の削除 | EN/ja同時 |
| `tests/unit/.../organizer/ui/*` | `ManualOrganizationRunTest`（新state・attach・reopen）、`ExchangeFlowStateHolderTest`（entryKind・生成入力）更新 | 失敗を先に再現するtest |
| `tests/organizer-instrumentation/...` | journey (a)〜(d)、0候補、legacy import、a11y | AC-8/9のevidence |

## Migration and recovery

- schema/rule migration: なし。
- failure中のrollback: 全新経路はzero-write。run中断・Back・破棄は既存D-13語彙とzero-write契約を維持する。
- release rollback/downgrade: persistent format変更がないため、新版で作成した依頼・提案（すべて`RUN_IN`）は旧版の読み取り契約（IDLE/RUN_IN双方を読む）でも参照可能。process-localの新stateは死ぬと消えるのみ。
- backup/restore compatibility: durable store（export session・pending intent）は既存formatのまま。restore後のreconcile契約（spec 374/376）は不変。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit: `ManualOrganizationRunTest`（確認→`ScopeConfirmed`→各肢、intent bound時は確認→compose直行） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-2 | unit: export組成（`ContextExportBuilderTest`拡張）とplanner入力の同一scope contract test | 同上 |
| AC-3 | instrumentation: 0候補→方法選択面（既存pass-through testの改訂） | connected test lane |
| AC-4 | unit + instrumentation: 0件続行の表示と空additions | 両seam |
| AC-5 | unit: 凍結条件=依頼存在。instrumentation: (c) journey（`exchangeBusy`由来のdisabled撤去を含む旧oracle置換） | 両seam |
| AC-6 | unit: `entryKind`生成（RUN_INのみ）+ idle入口行不在assert。instrumentation: hub status card経由のlegacy import | 両seam |
| AC-7 | unit: `CANDIDATE_SELECTION_STALE`既存契約回帰 + instrumentation: Home変化後typed再試行 | 両seam |
| AC-8 | instrumentation: (a) empty Home→全選択→AI→import→preview、(b) empty Home→全選択→このまま整理→preview、(c) 依頼存在下のBack/破棄/編集可否、(d) 中断・process recreation後のscope ownership | connected test lane |
| AC-9 | instrumentation: TalkBack label/focus、200% font scale | connected test lane |
| AC-10 | PR diffのcommit順序確認（docs→production） | review |
| AC-11 | 既存回帰: scope binding gate・durable import・rebind・selection既定値の全test | 両seam |

含めるべき観点: unit/contract（state machine・scope対応）、UI/accessibility（新面・凍結理由・破棄確認）、failure injection（scope mismatch・stale・取り込み失敗時のzero-write）。performanceは対象外（計算量変更なし）。

## Documentation updates

- [ ] spec status/history（本spec）
- [ ] CONTEXT.md（用語追加）
- [ ] DESIGN.md（該当なし — module構造・interface不変を確認のうえ触れない）
- [ ] ADR（該当なし — 変更困難な新判断は本specとTO-BE revision 6が所有）
- [ ] AGENTS.md（該当なし）

## Execution checklist

- [ ] Current behavior reproduced（旧oracle: T-07 AI行assert・`exchangeBusy`凍結・scoped entryの現状testを特定）。
- [ ] Tests fail for the missing behavior（`ScopeConfirmed`遷移・方法選択面・entryKind生成）。
- [ ] Minimal implementation completed（docs commit（AC-10）→ state machine → UI → strings → test）。
- [ ] Migration/recovery verified（該当なしの確認を含む）。
- [ ] Full relevant verification completed（JVM gate + focused connected lane）。
- [ ] PR evidence and remaining risks recorded。
