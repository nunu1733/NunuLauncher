# Implementation Plan: Organizerの対象scope確定を方法選択より先に行い、AI/非AIを同じ凍結scope上の兄弟分岐へ一本化する

> Issue: #417
> Spec: [spec.md](./spec.md)
> Status: draft
> Revision 6（2026-09-24）: PR #423 5回目review（Changes requested）対応。二段階commitのsave→bind窓を排除する原子commit（同一正順critical sectionでsession保存＋epoch再検証＋束縛更新）へ置換、`generationEpoch`のfirst-class化（claim/advance/失効）、破棄時のepoch先失効、oracle (m)(n)追加。
> Revision 5（2026-09-24）: PR #423 4回目review（Changes requested）対応。lock順序を正順（run lock → `ExchangeMutationGate`）に固定、ownerless RUN_IN遷移の一意化、Non-goals整合。
> Revision 4（2026-09-24）: PR #423 3回目review（Changes requested）対応。`boundExportId`束縛の線形化点・lifetime契約化、ownerless RUN_IN保存後のImportReview遷移固定、spec 375 entryKind-flip scenario置換。
> Revision 3（2026-09-24）: PR #423 2回目review（Changes requested）対応。RUN_INの「durable provenance」と「生存runへのdirect attach authority」の2軸分離、session storeへのfailure-aware invalidation追加、downgrade契約の実態化、durable active依頼とRUN leaseの区別。
> Revision 2（2026-09-24）: PR #423 1回目review（Changes requested）対応。session origin追加、scope-bound依頼破棄の契約化、manual trigger限定、D-06/spec 369本文Amend、reopen guard、oracle分割。

## Current evidence

観測は main `17d883a012d66833244d10c00d551215064c35d9` 時点。変更対象はUI/flow層とsession metadataのadditive拡張であり、DB書込み経路・validator/schemaは触れない。

- **方法選択がscope形成より先**（問題の構造）: `ManualOrganizationPreferences.kt` のIdle/Cancelled branch（527-586行）がT-07前置き面であり、「そのまま整理」（`manual_organization_start`、549-570行）と「AIに相談」（`exchange_method_consult`、579-585行）を並べて提示する。AI行の`onClick = exchangeHolder::openFlow`はrun admissionなしでidle exchange flowを開く。idle生成は `ExchangeInputAdapter.composeForExport(now)` → `composeFullOrganization()` + 空candidate labels（`ExchangeInputAdapter.kt:44`）で、CANDIDATE subjectsを含まない。
- **exchange hostingは2箇所**: `exchangeFlowItems` は `State.Selecting` branch（688行、scoped付き）と、`Idle/Cancelled` 面の末尾（1127-1145行、scopedなし。spec 205 V1の「run外ではidle/cancelledのときのみhost」規則）から呼ばれる。
- **run-in入口は選択面に同居**: `State.Selecting` branchが選択面とexchange flowを同一surfaceにhostする。`exchangeBusy = exchangeHolder.screen !is ExchangeScreen.Closed`（634行）→ `editsEnabled = !exchangeBusy`（686行）が、AI依頼の存在ではなくExchange画面stateだけで選択を凍結する。run-in entry（`ExchangeScopedEntryRow`、`ExchangeFlowUi.kt:2287`）は選択0件でもrenderする（`ExchangeFlowUi.kt:2002`のrender gateは`scoped != null`のみ）。
- **run-in生成のscope**: `generateScoped(tier, selection, labels)`（`ExchangeFlowUi.kt:611-628`）→ `controller.generateForSelection` → `adapter.composeForExport(now, selection, labels)` → `composeScopeComposedOrganization(selection)`（`ExchangeInputAdapter.kt:58`）。CANDIDATE subjectsは`ContextExportBuilder.kt:94-133`がadditionsから組む。
- **確認とcomposeの結合**: `ManualOrganizationRun.confirmSelection`（`ManualOrganizationRun.kt:807-867`）は早期scope gateの後、直接`runComposedPhase(operation, selection)`を呼ぶ。intentがboundされたrun（rebind/取り込み継続）のみ確認時に早期一致検証が効く。0候補はdisplay層pass-through（face mapping、`ManualOrganizationPreferences.kt:616`）で内部stateは`Selecting(空)`に入る。
- **attach契約**: `attachIntent`（`ManualOrganizationRun.kt:917-929`）は`State.Selecting`のみで束縛し、single-shot。`connectRun`（`ExchangeFlowUi.kt:1412-1452`）はRUN_INで生存runに`attachIntent`、IDLEで`run.start(intent)`。
- **entryKindは実行時推定でprovenanceが壊れ得る**: `beginImportAttempt`（`ExchangeFlowUi.kt:1099-1110`）が `entryKind = if (selecting != null) RUN_IN else IDLE`（1104行）とimport時点のrun stateから決める。このため (a) legacy IDLE依頼をrun内surfaceから取り込むとRUN_INに誤記され、(b) 逆にrun内で作成した依頼をprocess死後にhub経由で取り込むとIDLEに落ちる。#375は `entryKind` により選択復元初期値の有無を決めるため、これはrebind意味論の誤分類であり、正本をsession保持のoriginに置く必要がある。
- **RUN_INのattach authorityは3箇所のfenceで守られている**: (a) `beginImportAttempt`が`State.Selecting`から`owningRunId`を記録、(b) validation settle後のdurable save時にowning-run fence（ownerを欠くRUN_INはpending保存しない側へ倒す）、(c) `connectRun`（`ExchangeFlowUi.kt:1412-1452`）で同一runIdの生存runを再確認して`attachIntent`。import面を`ScopeConfirmed`へ移す場合、この3箇所の契約をscope-first側に合わせて更新しないと、正規の新規依頼のattachが（`State.Selecting`要求により）不能になり、逆に死後hub取り込みのRUN_IN pendingがfenceで落ちてrebindへ届かなくなる。
- **session store primitiveはfailure非観測**: `ExportSessionStore.invalidate(exportId): Unit` は成功/NoMatch/WriteFailedを区別できず、`AndroidExportSessionStore`の`AtomicFile.delete()`後もdurable成否を返さない（#375のpending側 `discardIf -> Committed / NoMatch / WriteFailed` と非対称）。scope-bound破棄の「永続化失敗を観測して凍結維持・再試行」には、failure-aware invalidationの追加が必要である。
- **session record codecは未知keyでfail-closed**: `AndroidExportSessionStore`は`Json`既定設定で`SessionRecord`をdecodeするため、未知fieldを含むrecordは旧buildでdecode失敗→no-sessionとなる（`categoryRefs` fieldの既存コメントが同一挙動を明記）。downgrade時の継続性を「旧buildが新しいfieldを無視する」ものとして主張してはならない。
- **session無効化・mutation gateの既存primitive**: 置換確認つき生成（`confirmReplacementAndGenerate`/`startGeneration`、`ExchangeFlowUi.kt:524/592`）、未送信sessionの破棄（`closeDisclosure`、722行）、置換時のin-process提案無効化（`invalidateImportedIntentForReplacement`、696行）、process-wide `ExchangeMutationGate`（340行）が存在する。scope-bound依頼破棄はこれらの再利用＋failure-aware invalidation追加で契約化する。
- **検出とfreshness**: 検出はadmission時1回のfresh capture（`LayoutApplicationModule.detectMissingAppCandidates`、`LayoutApplicationModule.kt:182`）。composition時に`OrganizationInputComposer.kt:246-264`がfresh captureと再照合し、`CANDIDATE_SELECTION_STALE`でfail-closed。preview staleは`StaleOrigin.DETECTED_BEFORE_REVIEW`（`ManualOrganizationRun.kt:1232-1236`）。
- **onboarding固定経路**: `Trigger.ONBOARDING_PROPOSAL`（D-16、spec 53/370）はT-07を省略してadmissionし、方法は「そのまま整理」固定でplanning直行する。本変更でこの経路に方法選択面を出現させてはならない。
- **既存test資産**: `ManualOrganizationRunTest.kt`（attach/scope-binding）、`ExchangeFlowStateHolderTest.kt`、`MissingAppSelectionInstrumentationTest.kt`（0候補pass-through含む）、`ManualOrganizationPreferencesInstrumentationTest.kt`（T-07 AI行の存在assert `t07AiConsultationOpensTheRequestFaceWithoutRunAdmission`、418行 — 本specで置換される旧oracle）、`ExchangeImportSurfaceInstrumentationTest.kt`（scoped entry）。

## Design

### Modules and interfaces

変更はorganizer ui層・exchange flow層・session metadata（additive）に局所する。planner・scope binding gate・validator・durable pending storeの読み取り契約は変更しない。

- **`ManualOrganizationRun`（ui state machine）**
  - `confirmSelection(selection)`: **manual triggerのみ**挙動を変える。早期scope gate後、intentがbound済みなら現行どおり`runComposedPhase`へ直行（rebind/取り込み継続経路）。intent未boundなら**新state `State.ScopeConfirmed`**（runId、候補cut、確定selection、exchange生成に必要なcandidate labels、intentScope表示fields）をpublishして止まる。`Trigger.ONBOARDING_PROPOSAL`は現行どおり（intent有無にかかわらず）確認→`runComposedPhase`直行とし、方法選択面へ到達させない（D-16継続）。
  - **0候補pass-throughのstate層移行（manualのみ）**: 検出完了時に候補0件なら`State.Selecting`をpublishせず直接`State.ScopeConfirmed(runId, selection=空)`へ進む。onboardingの0候補は現行のdisplay層pass-through→planning直行を維持する。
  - 新規`fun planWithConfirmedScope()`: `State.ScopeConfirmed`から`runComposedPhase(operation, selection)`へ進む（「このまま整理」の本体）。
  - `attachIntent`: 受理条件を`State.Selecting`に加えて`State.ScopeConfirmed`へ拡張。`ScopeConfirmed`での受理時は、確認時早期gateと同一のpure derivation（`ScopeBindingCauseDerivation.deriveConfirmMismatch`を確定selectionに対して実行）で一致検証し、不一致はtyped拒否（layout zero-write、方法選択面にrejection表示）。一致時はintentを束縛し`runComposedPhase`へ進む（取り込み成功CTA「この提案で続ける」が明示consent点であり、attach後の追加確認は挟まない）。
  - 新規`fun reopenSelection(): Boolean`: `State.ScopeConfirmed`で候補cutが**非空**のときだけ`State.Selecting`へ戻る（layout書込みなし、選択内容はUI stateが保持）。候補0件の`ScopeConfirmed`では常に拒否する（UIのBackは中断へ倒す）。依頼がactiveな場合の受理可否はstate machine自身は判定せず、UI層はscope-bound依頼破棄の成功後にのみ呼ぶ（下記の順序契約）。
  - `State.Selecting`の契約は不変（未確定の選択面。exchange flowのhostからは外れる）。
- **export sessionのdurable entry originと、生存runへのattach authorityの2軸**
  - (軸1: durable provenance) session保存時に、その依頼のentry origin（`IDLE` / `RUN_IN`）を1 fieldとして追加する（書込みはsession生成時の1回のみ、immutable）。既存recordは「origin保持なし＝IDLE読み替え」で読む。追加はadditiveであり、schema/validator/framing/privacy tierは不変（spec 204 Amend）。`beginImportAttempt`の`entryKind`（`ExchangeFlowUi.kt:1104`）を実行時run state推定から「durable originの読み出し」へ変更する。origin保持なし→IDLE。
  - (軸2: direct attach authority) 生存runへのdirect attachは、process-localな「このrunの`ScopeConfirmed`で`generateScoped`したexact exportId」の束縛でのみ成立する。scope一致（origin＋scopeCandidatesの等価）だけでは同一runと判定しない（同一scopeの別run由来sessionのattachを防ぐ）。
  - **束縛の正本と線形化点（lock順序を含む）**: 束縛の正本はactive `Operation`（`ScopeConfirmed`に公開される）が保持するprocess-localな`boundExportId: String?`である。全経路のlock順序は既存#375（`beginAdmission`がrun lock保持のままanchor内でgate取得）と同じ正順 **run lock → `ExchangeMutationGate`** に固定し、gate保持中にrun lockを取得する経路は作らない（ABBA deadlock防止）。
  - **generation transaction（`generationEpoch`）**: active `Operation`が`generationEpoch`（runId・operationId・凍結scope identity・epoch）を所有するfirst-class transactionとして生成を管理する。生成開始・置換生成のたびにrun lock下でclaim/advanceし、選択面への復帰・中断・run終了・scope-bound依頼破棄で失効させる。staleなepochの生成完了はzero-writeで破棄される（session保存も束縛も行わない）。
  - **原子commit（save→bind窓の排除）**: RUN_IN生成では、export組成（pure処理）をlock外で行い、**durableなsession保存・`generationEpoch`再検証・`boundExportId`更新を同一の正順critical section（run lock → gate）でcommitする**。session保存と束縛更新の間に窓は構造的に存在せず、保存されたsessionは必ず束縛を持つ。これにより「active session=E2 / binding=E1」の中間状態が存在しなくなる。
  - **fence 3箇所のscope-first側への更新**: (a) `beginImportAttempt`は「live owner」と「durable provenance」を別々に保持する — live ownerは方法選択面の現在runのrunId＋`boundExportId`一致（旧`State.Selecting`要求は更新）、(b) durable save時のowning-run fenceは「live ownerが生存するRUN_IN import」にのみ適用し、ownerを欠くRUN_IN origin importは **RUN_IN pendingとしてdurable保存する**（fenceで落とさない。continuationはdirect attachせずrebindのみ）、(c) `connectRun`のRUN_IN生存確認は`State.ScopeConfirmed`＋同一runId＋`boundExportId`一致へ更新する。
  - **scope-bound依頼破棄の原子境界**: run lockを外側、gate内側とし、(1) gate内で先にcurrent `generationEpoch`を失効させる（進行中の生成完了を以後zero-writeで棄却）、(2) `invalidateIf(boundExportId)`を実行、(3) `Committed` / `NoMatch`の場合のみ従属する取り込み済み提案の処理（既存in-process無効化＋read-time reconcile委譲）と束縛clearを行う。session保存と束縛が原子であるため、束縛と異なる当該runのsessionが生存している状態は存在せず、`NoMatch`は「当該runのsessionは既に残っていない」ことの証明として成功扱いできる。`WriteFailed`ではsession・pending・`ScopeConfirmed`を保持して再試行する。
  - **ownerless RUN_IN保存成功後の表示遷移**: direct-attach用の取り込み成功状態（`ImportSuccess`→`connectRun`）を採用せず、保存済みrecordを既存reconcile経由で読み直して**ImportReviewへ遷移する**（この一経路のみ。hub/status cardへの戻しは採用しない）。「この提案で続ける」は既存ImportReviewだけから実行され、#375のadmission anchorを再利用する。新しいrebind/start seamは作らない。same-processの取り込み成功CTAはlive-owner direct attach時にのみ現れる（spec 374 Amendに明記）。
  - **方法選択面からの取り込み制限**: 取り込み対象は「この面の確定scopeから作成された依頼」— in-processならこのrunの`ScopeConfirmed`で生成（束縛exportId一致）、それ以外は不可（同一scopeでも別run由来・legacy IDLE由来は不可）。「このscope用に依頼を作り直す」（既存置換確認経由の新規生成）へ案内する。
- **scope-bound依頼破棄（failure-aware invalidationの追加と順序付け）**
  - `ExportSessionStore` / `AndroidExportSessionStore`へ、gate内で線形化できるfailure-aware invalidationを追加する: `invalidateIf(expectedExportId) -> Committed / NoMatch / WriteFailed`（AtomicFile上でのatomic record更新またはtombstoneにより成否を観測可能にする。#375のpending側`discardIf`と対称な契約）。
  - `ExchangeFlowStateHolder`に、active依頼（session）と従属物を破棄する明示operationを1つ追加する。`ExchangeMutationGate`配下で次の順で実行する: (1) `invalidateIf(expectedExportId)`、(2) 結果が`Committed` / `NoMatch`の場合のみ、従属する取り込み済み提案の処理 — 既存`invalidateImportedIntentForReplacement`と同一のin-process無効化＋durable側は既存read-time reconcile契約に委譲、(3) 完了後にUIへ成功を返す。
  - (1)が`WriteFailed`の場合はsession・pending・`ScopeConfirmed`のすべてを保持し、方法選択面にtypedで再試行可能な失敗を表示する。成功後でのみUIは`reopenSelection()`を呼んでよい。layout/workspace DBへの書込みは生じないが、このoperation自体はdurable mutationである。AC-8(c)/AC-11のfailure注入oracle（process死を挟むものを含む）で固定する。
  - spec 204（invalidation primitive追加）・spec 374（消失原因の追加）・spec 375（mutation gate適用範囲・2軸分離の明記）へのAmendをAC-10の対象に含める。
- **`ExchangeFlowStateHolder` / `ExchangeFlowUi`（hosting再配置）**
  - 生成入力の供給源を「UI選択state」から「確定scope（`State.ScopeConfirmed`のselection + labels）」へ変更する。`generateScoped`のinterface（selection+labelsを受け取る）は不変で、呼び出し側の供給が変わる。
  - `ExchangeScopedEntryRow`（選択面上のentry行）と`Closed` branchのscoped entry rowを撤去する。`exchangeFlowItems`のhostは (a) 方法選択面branch（scoped、作成+取り込み）と (b) `Idle/Cancelled`面（**import-only**）の2箇所になる。
  - entry面hostingはimport-onlyとする: 既存active依頼の状況表示・回答取り込み・既存破棄操作のみで、新規依頼作成（T-15の生成・T-16送出）の導線を持たない。#372のT-15情報表示（active依頼の存在・残時間・置換要否）は維持し、作り直しは「整理の中で」（方法選択面）へ案内する。hub request row（`ExchangeOpen.REQUEST`）の遷移先はこのimport-only hostingへ解決される。
  - `importAttemptActive`によるidle開始行の凍結は、開始行が「方法選択を含まない入口」になることで意味を失うため、入口面の整理とともに撤去する。**Busyは生存runがRUN leaseを保持している場合のみ**であり、durableなactive依頼（legacy IDLE依頼・owning runを失ったRUN_IN session）はRUN leaseを持たないため、単独ではmanual run admissionを妨げない。依頼がscope形成を隠れて凍結する構造を再導入しない（AC-5/AC-8(d)）。
- **UI面の再配置（`ManualOrganizationPreferences.kt` / `MissingAppSelectionScreen.kt` / hub）**
  - 入口面（Idle/Cancelled + T-07前置き面の後継）: scope summary（既存`manual_organization_preamble_scope`を維持）+ 開始CTA 1行のみ。CTA labelは方法を示さない「整理を開始」系の新文字列へ変更し、「そのまま整理」labelをscope選択入口から撤去する。AI行は置かない。
  - `State.Selecting`面: 選択項目 + 全選択/解除 + 「続行」。exchange flow items・scoped entry rowをhostしない。`editsEnabled`は常にtrue（凍結はこの面の外へ出る）。0件のまま「続行」した場合の明示helper文言（「未配置アプリを追加せず整理します」系）を`onConfirm`直前に表示する（新文字列）。
  - 新規`State.ScopeConfirmed`面（方法選択面）: 見出し「整理案の作り方」系 + 「このまま整理」行（`planWithConfirmedScope`）+ 「AIに相談」行（scoped `openFlow`）。`exchangeFlowItems`をこのbranchでhostし、依頼作成〜取り込みの各state（T-15〜T-18、ImportSuccess）をここにrenderする。依頼がactiveな間は面内に依頼状態の行（作成中/送信前確認/取り込み中）が可視であることを凍結理由の表示とする。system Back: 依頼なし（かつ候補cut非空）→`reopenSelection()`、依頼あり→scope-bound依頼破棄の確認dialog（D-13「破棄」）→承認でoperationを実行し成功後に`reopenSelection()`、候補0件→中断（zero-writeで入口/hubへ）。
  - rebind/取り込み継続でintentがadmission時にboundされたrunは、確認（早期gate通過）後`runComposedPhase`へ直行するため、方法選択面は現れない（scenario「process死後の再開」どおり）。
  - `intentScopeCount` / scope diff / `scopeRejection`表示は`State.Selecting`の既存行を維持（rebind経路で使用）。`ScopeConfirmed`でのattach拒否時は方法選択面にrejection表示（`exchangeContractFailureText`を再利用）。
- **seam**: 変更の検証は既存seam（`ManualOrganizationRun`のstate observable + `ExchangeFlowStateHolder`のscreen observable）経由のみ。新しいproduction/test分岐seamは作らない。

### Data flow

入口 → `start(trigger)`（admission、RUN lease）→ `CandidateDetection`（fresh capture 1回）→ manual: 候補あり `Selecting` / 候補0 `ScopeConfirmed(空)` → 選択面「続行」で`confirmSelection`（早期gate、intent未boundなら`ScopeConfirmed`）→ 方法選択面 →
- 「このまま整理」: `planWithConfirmedScope()` → `runComposedPhase(selection)` → preview
- 「AIに相談」: scoped `openFlow` → 依頼作成（確定scopeから`generateScoped`、sessionにorigin記録）→ 送信前確認 → 外部AI → 取り込み（originから`entryKind`導出）→ 検証 → 「この提案で続ける」→ `attachIntent`（一致検証）→ `runComposedPhase(selection, intent)` → preview

error: 早期/composition scope gate不一致はtyped `SCOPE_MISMATCH`（zero-write、rejection表示・remedy案内）。stale候補はcomposition時`CANDIDATE_SELECTION_STALE`（zero-write、typed再試行案内）。scope-bound依頼破棄の永続化失敗は方法選択面残留＋再試行。依頼生成・取り込み失敗は既存typed失敗面のまま。中断は両面でlayout zero-write（選択ありの選択面からの離脱は既存どおり確認1回）。

### Alternatives rejected

- **代替案B（idle AIを「追加アプリを含めない別機能」として残存）**: Issue #417 Outcome「AIだけ別のscope形成タイミングを持たせない」に正面衝突するため却下。
- **代替案Cのrun外実装（admission前に検出・選択を行うpre-run scope formation面）**: 同じ順序を実現するが、検出・選択の面とstateをrun外に二重化する必要があり、RUN lease・中断・process死の意味が二重のstate machine交差を続けるため不採用。既存run内で同一順序を実現する。
- **`entryKind`を実行時surface/run state推定のまま維持（format不変で回避）**: import面と依頼由来の組合せで誤分類が残る（legacy依頼のrun内取り込み→誤RUN_IN、run内作成依頼の死後hub取り込み→誤IDLE）。#375の復元意味論に直結するためprovenanceはdurable originへ置くのが正しく、推定では修復できないため却下。
- **「durable origin＋scope一致」でsame-run判定を行う方式**: 同一scopeの別run由来sessionをattachでき、same-run invariant（spec 331 §5/#375）を破る。direct attach authorityはprocess-localなrunId↔exportId束縛に限定する（2軸分離）。
- **gate保持中にrun lockを取ってbind/clearする実装（`ExchangeMutationGate` → run lockの新設）**: 既存rebind admission（run lock → gate）とABBA deadlockを起こすため禁止する。正順（run lock → gate）に固定する。
- **gateを解放したうえで単独のrun lockだけでbindする二段階commit実装**: saveとbindの間に「active session=新依頼 / binding=旧依頼」の中間状態が生じ、scope-bound破棄の`invalidateIf(旧exportId)`が`NoMatch`成功扱いで通ると、置換後sessionを残したまま選択再編集が可能になる（round 5 reviewで指摘された穴）。session保存・epoch再検証・束縛更新を同一正順critical sectionで原子commitする方式を採用し、窓自体を排除する。
- **downgrade継続性のためにsidecar/versioningへ設計変更する方式**: 旧codecが未知keyでfail-closedする実態を迂回するための追加format機構であり、no-session fail-closed＋pending reconcile失効という安全側の挙動で十分（既存`categoryRefs`と同様の既知の許容）であるため不採用。
- **方法選択面からのBack禁止（中断のみ）**: 状態機械は単純になるが、依頼なしのscope確定ミス時に選択をやり直すためにrun全体の中断・再検出・再選択を強いる。zero-writeで安全に戻れる経路を残す方を採用（0候補時はBack＝中断）。
- **`State.Selecting`にconfirmed flagを追加する代案**: 新state `ScopeConfirmed`とし、凍結の状態を型で区別する（flag合流だとattach/editable条件が再びboolean合成になり、#417の問題を構造内に残す）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/product/organizer-to-be-ux.md` | Revision 6追記: D-04/D-05/D-06/D-17/§5.1/§5.2/§5.3をscope-first順序へ改訂（D-16不変を明記） | UX正本。production変更より先（AC-10） |
| `docs/product/organizer-disposition-migration.md` | §5 supersession mapへ#417行（spec 369/372/331/367/204/374/375・idle入口Retire・D-16 Continue）追記 | 処分の正本 |
| `specs/417-.../spec.md` 本spec | status遷移（draft→accepted→implemented）とChange history | 同一PRで更新（specs/README規則） |
| `specs/369` | RD-3・D-06節・20状態→8状態対応表・0候補scenarioを本文レベルでAmend（manual/onboardingの区別を明記） | 旧normative本文を標記だけ残さない（review指摘） |
| `specs/372`, `specs/331`, `specs/367` | Amend/Supersede標記＋該当規定の改訂（372: EX-AC-01/T-07二択、331: §5 idle entry・run-in一般化、367: secondary entry記述） | 既存specの正確な状態表示 |
| `specs/204` | export sessionへのdurable entry origin追加と、session storeへのfailure-aware invalidation（`invalidateIf` 相当）追加をAmend | session契約・storeの所有者 |
| `lawnchair/.../organizer/personalization/` の `ExportSessionStore` / `AndroidExportSessionStore` | `invalidateIf(expectedExportId) -> Committed / NoMatch / WriteFailed` の追加（atomic record更新またはtombstoneで成否を観測可能に） | scope-bound破棄のfailure観測点 |
| `specs/374`, `specs/375` | 消失原因へのscope-bound破棄追加・mutation gate適用範囲明確化・origin読み替えをAmend（374: same-process取り込み成功CTAはlive-owner direct attach時のみ・ownerless RUN_INはImportReview rebindを明記。375: attach authority 2軸分離を明記し、「同一session再取り込みでentryKindだけflip」のscenario/SR-AC-07該当oracleを「再取り込みはentryKindを保持する」回帰へ置換。record replacement anchorのfull-equality oracleは別settleでの他field変化・別exportId/session置換のcaseで維持） | durable契約の所有者 |
| `CONTEXT.md` | 「対象scope凍結」「方法選択面」「scope-bound依頼破棄」の用語追加 | ドメイン語の正本 |
| `lawnchair/.../organizer/ui/ManualOrganizationRun.kt` | `State.ScopeConfirmed`新設（`boundExportId`・`generationEpoch`を含むoperation保持fields）、`confirmSelection`分岐（manual限定・onboarding直行）、0候補state pass-through（manual限定）、`planWithConfirmedScope`、`attachIntent`拡張、`reopenSelection`（非空候補guard）、epoch claim/advanceとrun lock下commitの線形化 | run state machineの唯一の正 |
| `lawnchair/.../organizer/ui/exchange/ExchangeFlowUi.kt` | durable originの記録/読み出し、`entryKind`導出変更（再取り込みで不変）、live owner（runId＋`boundExportId`一致）とdurable provenanceの分離保持、`generationEpoch`のclaim/advance/失効と原子commit（正順critical sectionでsession保存＋束縛更新）、durable save時owning-run fenceの更新（owner欠落RUN_IN pending保存）と保存成功後のImportReview遷移、`connectRun`の生存確認更新（`ScopeConfirmed`＋同runId＋束縛一致）、方法選択面の他由来取り込み遮断、scope-bound破棄operation（正順lock・mutation gate配下・epoch先失効・束縛clearを含む）、scoped entry行撤去、entry面import-only hosting | exchange flow正本 |
| `lawnchair/.../organizer/integration/exchange/ExchangeInputAdapter.kt` 関連 | session保存時にoriginを書く（生成seamに追加する1 field） | provenanceの書込み点 |
| `lawnchair/.../ui/preferences/destinations/ManualOrganizationPreferences.kt` | 入口面の方法選択撤去・CTA改名、方法選択面branch新設、`exchangeBusy`凍結の撤去、hosting再配置（方法選択面/entry面import-only）、Back経路 | 画面正本 |
| `lawnchair/.../organizer/ui/MissingAppSelectionScreen.kt` | 0件続行helper文言、exchange hostの分離 | 選択面の契約（編集常時可） |
| `lawnchair/res/values{,-ja}/strings.xml` | 入口CTA・方法選択面・0件helper・凍結理由・作り直し案内の新文字列、撤去文字列の削除 | EN/ja同時 |
| `tests/unit/.../organizer/ui/*`, `tests/unit/.../personalization/*` | `ManualOrganizationRunTest`（新state・attach・reopen guard・onboarding直行回帰）、`ExchangeFlowStateHolderTest`（origin/entryKind導出・取り込み遮断・破棄失敗注入・entry面creation不在）更新 | 失敗を先に再現するtest |
| `tests/organizer-instrumentation/...` | journey (a)〜(n)（AC-8定義どおり）、legacy import、a11y（自動oracle＋実機evidence） | AC-8/9のevidence |

## Migration and recovery

- schema/rule migration: なし（export sessionへの1 field追加のみ。既存recordはorigin保持なし＝IDLE読み替え）。
- failure中のrollback: layout/workspace書込みは全新経路でzero。scope-bound依頼破棄のみdurable mutation（failure-aware invalidation＋既存reconcile、mutation gate配下）を含み、`WriteFailed`時はsession・pending・凍結scopeを保持して再試行できる。
- release rollback/downgrade: **現行codecの実態どおり、旧buildは未知fieldを含むsession recordをdecodeできず、scope-firstで作成したsessionはno-sessionとしてfail-closedになる**（従属するpendingもread-time reconcileで失効し得る）。この安全側への倒れ込みを明示的に許容する（既存`categoryRefs` fieldと同一の既知挙動。`SET_MISMATCH`等のfail-closed gateは維持される）。旧record（origin保持なし）は従来どおり読める。継続性を要求する場合のsidecar設計は本specでは採用しない（代替案rejected参照）。
- backup/restore compatibility: durable store（export session・pending intent）は既存formatのadditive拡張。restore後のreconcile契約（spec 374/376）は不変。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit: `ManualOrganizationRunTest`（確認→`ScopeConfirmed`→各肢、intent bound時・onboarding時は確認→compose直行） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-2 | unit: export組成（`ContextExportBuilderTest`拡張）とplanner入力の同一scope contract test | 同上 |
| AC-3 | unit: 0候補→`ScopeConfirmed(空)`直接遷移。instrumentation: 方法選択面assert | 両seam |
| AC-4 | unit + instrumentation: 0件続行の表示と空additions | 両seam |
| AC-5 | unit: 凍結条件・reopen guard（依頼あり拒否/0候補拒否）・`WriteFailed`時の凍結維持・durable依頼単独ではadmission可能。instrumentation: (c)(e) journey | 両seam |
| AC-6 | unit: origin書込み・`entryKind`導出（origin保持なし→IDLE・再取り込みで不変）・`boundExportId`のbind/clear/置換lifetime・他由来取り込み遮断・owner欠落RUN_IN pending保存とImportReview遷移・entry面creation不在。instrumentation: hub経由のlegacy import、(g)〜(k) journey | 両seam |
| AC-7 | unit: `CANDIDATE_SELECTION_STALE`既存契約回帰 + instrumentation: Home変化後typed再試行 | 両seam |
| AC-8 | instrumentation: (a) empty Home→全選択→AI→import→preview、(b) empty Home→全選択→このまま整理→preview、(c) 破棄`Committed`/`WriteFailed`のBack、(d) 中断・process recreation＋legacy依頼active下でのadmissionと取り込み遮断、(e) 0候補Back、(f) onboardingに方法選択面が現れない、(g) 同一run生成→import→attach成功、(h) owning run喪失後のhub取り込み→RUN_IN pending→ImportReview→rebind、(i) 同一scope別run由来のattach不発、(j) 生成保存完了をbarrierで止めた中断/再開後の遅延settleで束縛不付与、(k) E1→E2置換後のE1回答attach不発、(l) lock順序交差でdeadlockせずrun不変、(m) 置換生成epoch claim後の破棄でE2完了がzero-write破棄・E1無効化後に復帰・E2はprocess recreation後も再出現しない、(n) E1/E2完了の順序反転でcurrent epochのみcommit | connected test lane |
| AC-9 | instrumentation: semantics/live region/focus/200%の自動assert + 実機TalkBack・キーボード・Switch Accessの操作evidence | 両seam + 実機 |
| AC-10 | PR diffのcommit順序確認（docs→production） | review |
| AC-11 | 既存回帰（scope binding gate・durable import・rebind・selection既定値・onboarding D-16）＋新規failure注入oracle（破棄`WriteFailed`、owning run喪失後のRUN_IN取り込み、stale epoch完了、lock順序交差、process死を挟む同等シナリオ）＋「同一session再取り込みで`entryKind`不変」回帰（spec 375 SR-AC-07置換後） | 両seam |

含めるべき観点: unit/contract（state machine・provenance・scope対応）、UI/accessibility（新面・凍結理由・破棄確認）、failure injection（scope mismatch・stale・取り込み失敗・破棄永続化失敗）。performanceは対象外（計算量変更なし）。

## Documentation updates

- [ ] spec status/history（本spec）
- [ ] CONTEXT.md（用語追加）
- [ ] DESIGN.md（該当なし — module構造・interface不変を確認のうえ触れない）
- [ ] ADR（該当なし — 変更困難な新判断は本specとTO-BE revision 6が所有）
- [ ] AGENTS.md（該当なし）

## Execution checklist

- [ ] Current behavior reproduced（旧oracle: T-07 AI行assert・`exchangeBusy`凍結・scoped entry・entryKind実行時推定の現状testを特定）。
- [ ] Tests fail for the missing behavior（`ScopeConfirmed`遷移・方法選択面・origin導出・`boundExportId` lifetime・取り込み遮断・ImportReview遷移・onboarding回帰）。
- [ ] Minimal implementation completed（docs commit（AC-10）→ session origin → state machine → exchange flow → UI → strings → test）。
- [ ] Migration/recovery verified（session追加fieldの読み替え・rollback degrade・破棄失敗注入を含む）。
- [ ] Full relevant verification completed（JVM gate + focused connected lane + a11y実機evidence）。
- [ ] PR evidence and remaining risks recorded。
