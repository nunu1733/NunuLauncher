# Implementation Plan: hub status cardから復元flowへの接続（D-15）

> Spec: [spec.md](./spec.md)（draft。実装着手にはowner受入が必要）。
> 本planは main `a2b6aba3189922a6e1cb314eb52cff1d1f973e02`（2026-09-19）時点の実装調査に
> 基づく。実装開始時に最新 `origin/main`・Issueコメント・依存Issue（#366のmerge状態）を
> 再確認し、差分を反映してから着手する。

## 1. Current implementation（調査済みの事実）

### 復元経路（既存。本planでは変更しない部分）

- **application seam**: `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt`
  - `inspectRecovery(pointId)`（~289行）: spec 84のread-only検査。readiness gateでfail-closedし、
    `RecoveryPreviewProtocol.inspect(pointId)`（I0–I5。run mutex非block → 検査projection読取 →
    checksum/format/lifecycle/retention preflight → writer lease非block取得 → 現state capture →
    `Restorable` + one-shot `RecoveryPreviewConfirmation` 発行）へ委譲する。
  - `confirmRecoveryPreview(pointId, confirmation)`（~354行, internal）:
    `consumePreviewConfirmation` でone-shot registry（`pendingPreviewConfirmations`、
    process-localな `IdentityHashMap`。~84行で宣言）を1回消費して内部で `RecoveryRequest` を
    組み立て、
    `recoverWithApplicationBehavior`（既存のreadiness gate + `RECOVERY_REQUESTED` /
    terminal recovery diagnostics + `RecoveryProtocol.recover`）へ委譲する。公開契約は
    `RecoveryResult` のみ。
  - `durableOrganizerStatus()`（~315行）: readiness gate + `ordinaryMutex.tryAcquire` 非block +
    `store.readInspectionSnapshot()` → 純粋な `OrganizerDurableStatusDeriver.derive(...)`。
    fail-closedは `UNAVAILABLE`。
- **検査snapshot**: `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot.kt`
  — `Record(pointId, lifecycle, createdAtMs, updatedAtMs, checksumValid, formatVersion)` /
  `Tombstone(pointId, reason, expiresAtMs)`。SQLite非open・書込みなし（spec 89）。
- **純粋派生**: `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt`
  — `VERIFIED` + retention内 → `ORGANIZED_RESTORABLE`。retention定数は同packageの
  `RetentionPolicy.RETENTION_MILLIS`（24h、spec 13）。
- **coordinator**: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`
  - `ManualOrganizationApplication` façade（~71–116行）: `inspectRecovery` / `confirmRecovery` /
    `readDurableOrganizerStatus()` / `readinessState` をmoduleへ委譲。
  - `ManualOrganizationModule.get()`（~161–185行）: cold-process初期化
    （`LauncherAppState.getInstance` + `app.ensureOrganizerStartupReconciliation()`。spec 271
    DS-AC-10のno-callback loader橋を含む）。[LawnchairApp.kt][la] ~128行の
    `ensureOrganizerStartupReconciliation()` が正体。
  - `beginRecoveryPreview()`（~924行）: **旧entry**。`operationGate.tryAcquire(RECOVERY)` →
    `lastVerifiedApply` / `appliedPoint` / `activeOperation` / `recoveryLease` を検査 →
    `State.InspectingRecovery` → `application.inspectRecovery(point)` →
    `State.RecoveryPreview(preview, correlated)`。相関gateはspec 230 D2実装
    （`lastVerifiedApply.result` が `ApplyResult.Applied` でpointId等価のときだけsummaryを渡す）。
  - `cancelRecoveryPreview()`（~973行）: `lastVerifiedApply ?: State.Idle` へ復帰、zero-write。
  - `confirmRecovery()`（~982行）: `State.Recovering` → `application.confirmRecovery(...)` →
    `State.RecoveryResultState(result)`。
  - apply成功時の `appliedPoint` / `lastVerifiedApply` 更新（~911–914行）。process-local・非永続。
  - `dismiss()`（~1013行）: recovery leaseのcancel path含む既存の中断規約。
- **admission**: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOperationLease.kt` —
  `Kind.RUN / RECOVERY / AUTHORING` の単一flight process-local gate。
- **writer lease**: `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  ~79行 — `LayoutWriteCoordinator.getInstance().tryAcquire(OwnerKind.ORGANIZER)` へのbridge。
  spec 84 I4 / spec 13 recovery protocolが取得する（無変更）。
- **UI**: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - `showDurableStatus`（~179行）: run stateが `Idle`/`Cancelled` のときdurable status読取。
    `LaunchedEffect(showDurableStatus, readinessState)` で再読込（DS-AC-09）。
  - `durableStatusItems(...)`（~854行）: `ORGANIZED_RESTORABLE` → `SummaryText` のみ（表示のみ）。
    `RESTORED_OR_EXPIRED` / `UNRESOLVED`（+safe-support）/ それ以外は行なし。
  - 旧entryの復元action（`State.Applied` 面の `manual_organization_recovery`、~683行）、
    `State.InspectingRecovery`（~716行）、`State.RecoveryPreview`（~724行。confirmは
    `Restorable` のときだけdecision pairに現れる）、`State.RecoveryResultState`（~771行）、
    `recoveryHistoryLine`（~1706行）、`recoveryPreviewMessage`（~1719行）。
- **strings**: `lawnchair/res/values/strings.xml`（~1090–1114行）と `values-ja/strings.xml`
  （~158–179行）。`manual_organization_durable_status_restorable` /
  `manual_organization_recovery*` 系が既存。複数形は `plurals`
  （`manual_organization_recovery_history_moved` 等）。

### テストの現状

- unit: `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt`
  （restart後に旧entryへ到達しないcase ~957–963行、`cancellingRecoveryPreview...` ~840行を含む84 test）、
  `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` /
  `contract/RecoveryPreviewContractTest.kt`、
  `tests/unit/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriverTest.kt`。
- instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/`
  `ManualOrganizationPreferencesInstrumentationTest.kt` /
  `ManualOrganizationProductionE2EInstrumentationTest.kt`
  （`manualRunUsesProductionCaptureApplyVerificationAndRecovery` ~169行）、
  `tests/organizer-instrumentation/app/lawnchair/organizer/application/store/`
  `OrganizerDurableStatusInstrumentationTest.kt`。

### Gap（本Issueで埋める）

1. `ORGANIZED_RESTORABLE` 行に操作・残時間がない（表示のみ）。
2. coordinatorに `appliedPoint` / `lastVerifiedApply` 非依存の復元開始entryがない。
3. application moduleに「最新の検証済み1点」の選択readがない（snapshotには必要なmetadataが
   既にある）。
4. spec 271がcold-process restoreをNon-goalsとしており、DS-AC-07の表示面が旧配置のままである。

## 2. Ownership / module boundaries

- **application module**（`organizer/application/**`）: 選択readと閉じたentry型を所有する
  （DESIGN.md §4.2のmodule所有規約。snapshot・retention・revisionは漏らさない）。
  変更は `LayoutApplicationModule` へのadditive操作と、`application/public/` への閉じた型追加、
  純粋selectorの新規配置に限定する。
- **coordinator**（`organizer/ui/ManualOrganizationRun.kt`）: cold entryとstate遷移を所有。
  既存state列・lease規約を再利用し、新しいstate種別・新しいapplication検査操作を追加しない。
- **UI**（`ui/preferences/destinations/**`、#366 merge後はhub destination）: CTA・残時間の描画と
  navigationのみ。選択・検査の判断をUIへ置かない。
- **recovery protocol / store**: 無変更。spec 13/84/89の契約面にdiffを出さない。

## 3. Interfaces / seams

新設する公開面は **1つの読み取り操作 + 2つの閉じた型** のみ（仮称。最終名は実装PRで確定）:

```kotlin
// application/public/RestorableRecoveryEntry.kt（新規）
sealed interface RemainingWindow {
    data class HoursRemaining(val value: Int) : RemainingWindow   // 1..24、切捨て
    data object LessThanOneHour : RemainingWindow
}
data class RestorableRecoveryEntry(
    val pointId: RecoveryPointId,        // opaque handle。描画・log・永続化しない
    val remainingWindow: RemainingWindow,
)

// LayoutApplicationModule（additive）
fun readRestorableRecoveryEntry(): RestorableRecoveryEntry?
```

- `null` の意味: retention内の有効な検証済みpointが存在しない、またはfail-closed
  （gate未ready / snapshot unreadable / mutex競合 / 読取失敗）。UIはstatus行の表示にのみ使う
  値を持ち、`null` ではCTAを出さない（spec D6）。
- 実装は `durableOrganizerStatus()` と同一の構造を踏襲する:
  `readinessGate.runWhenReady(unavailable = { null })` → `ordinaryMutex.tryAcquire`（非block、
  失敗でnull）→ `store.readInspectionSnapshot()` → 純粋selectorへ委譲 → `finally` でrelease。
  書込み・lifecycle遷移・cleanup・journal eventは発生しない。
- coordinator façade（`ManualOrganizationApplication`）には `readRestorableRecoveryEntry()` の
  委譲を追加する。`inspectRecovery` / `confirmRecovery` は既存のまま流用する。
- **platform型・DB行はinterfaceへ出ない**（AGENTS.md設計規約）。`RecoveryPointId` のみ、
  spec 84が既に許すopaque相関キーとしてcoordinatorまで到達する。

## 4. Data model / identity / control & data flow

- 新規persisted data・schema変更・migration: **なし**（spec「Data and state」節）。
- identity: `RecoveryPointId` を再利用。新識別子なし。tokenはprocess-local non-persistent
  （既存registry）。
- control flow（cold process）:

```text
hub起動（cold） → ManualOrganizationModule.get()（LauncherAppState + reconciliation）
→ readiness READY → status cardが durableOrganizerStatus()==ORGANIZED_RESTORABLE を表示
→ readRestorableRecoveryEntry() が {pointId(最新VERIFIED), remainingWindow} を返す
→ CTA表示（状態→残期限→操作）
→ tap → coordinator cold entry（RECOVERY lease取得。競合は静かに不受理）
→ State.InspectingRecovery → inspectRecovery(pointId)（既存spec 84検査。fresh token発行）
→ State.RecoveryPreview(result, correlated=null)（coldでは履歴行なし。spec 230 D2）
→ confirm → State.Recovering → RecoveryProtocol.recover（既存。writer lease・transaction・検証）
→ State.RecoveryResultState → Back/完了 → Idle → status card再読込（RESTORED_OR_EXPIRED等）
```

- TOCTOU: status表示時点とtap時点の間でpointが失効した場合は既存のtyped結果
  （`NotRestorable(EXPIRED)` 等）が確認面に出る。confirm時のlayout変化は
  `NotRestorable(STALE_REVISION)`（既存）。いずれもzero-write。

## 5. Expected files / modules to change

| File | Change |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/RestorableRecoveryEntry.kt` | 新規。閉じたentry型 + `RemainingWindow` |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/RestorableRecoveryPointSelector.kt` | 新規。純粋selector（選択 + window計算。`OrganizerDurableStatusDeriver` と同配置・同様式） |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | `readRestorableRecoveryEntry()` 追加（additive） |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | façade委譲追加 + cold entry method（`beginRecoveryPreviewFromDurableEntry()` 仮称）+ state遷移 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `ORGANIZED_RESTORABLE` 行への残時間 + CTA描画、CTA tap → cold entry + 確認面への遷移。durable status読取とentry readの並列読込 |
| #366 merge後: `OrganizerHubPreferences.kt`（新destinationのstatus card） | 同一seam・resource・条件式でCTA付き行を実装（hubが既存行描画を共有/抽出する形は実装PRで確定） |
| `lawnchair/res/values/strings.xml`, `values-ja/strings.xml` | 残時間表示（format resource。`LessThanOneHour` 区分含む）+ CTA label（既存 `manual_organization_recovery` の再利用可）。EN/ja同期 |
| `specs/271-organizer-durable-status-projection/spec.md` | companion revision（spec「spec 271 companion revision」節の1–5）。**本specの実装PRで実施** |
| `DESIGN.md` | §4.2のread-only seam列挙へ選択readを追記 |
| `specs/123...` evidence: `docs/assessment/evidence/issue-123-ui-mapping.md` | 新規row/CTAのmapping追記（#366 HUB-AC-09と同一扱い） |
| tests | §8の一覧 |

依存Issueの実装（#366のhub、#369のrun面統合）との衝突を避けるため、
`ManualOrganizationPreferences.kt` のdurable row周辺の変更は最小diffに留める。

## 6. Compatibility constraints

- 既存の旧entry（`State.Applied` → `beginRecoveryPreview()`）、確認面、strings、
  diagnostics契約、recovery protocolは無変更。既存testは無編集でgreenにする
  （`ManualOrganizationRunTest` のrestart oracle、`RecoveryPreviewContractTest`、
  `OrganizerDurableStatusInstrumentationTest` を含む）。
- #366が未mergeの間に本Issueを実装する場合: CTAは既存organizer面の行に付き、hub側は
  同一seamで引き継ぐ（spec「Compatibility and migration」節）。#366のdraft spec
  （`issue-366-spec-plan` @ `bf00f96175`）はstatus card描画を「同一seam・同一resource・
  同一条件式」と規定しており、resource再利用によりcopy変更は両面へ自動伝播する。
- #374/#375のstatus card行（AI依頼・取り込み済み提案）とは行種別が異なるため直接競合しないが、
  status cardの行構造・読み順（状態→残期限→操作）は#366/#374と同一規約を揃える。
- downgrade: 追加はUI/読み取りのみであり、旧版では表示のみへ戻る（disposition §7.3）。

## 7. Migration / rollback / recovery

- migration: なし（schema・persisted data・backup許可list不変）。
- rollback: PR revertで現行挙動へ戻る。additiveな操作・型・stringであり、revert時に
  残骸（persisted state、journal、schema）を残さない。
- recovery: 本機能自体がrecoveryの入口であり、復元実行の安全性（transaction・検証・
  失敗時のtyped結果）は既存spec 13 protocolが所有する。新経路がrecovery storeに
  書き込む経路は存在しないため、失敗注入・rollback testの対象は「新経路が書込みを
  行わないこと」の証明（counter assert）と、既存recovery E2Eの継続greenである。
- AGENTS.mdホームレイアウト安全規約への適合: 復元適用は既存protocolが所有し、
  revision一致（STALE_REVISION拒否）・保持/移動/削除の説明可能性（recovery write-set）・
  transaction・recovery point（復元対象そのもの）・再検証（DB/model convergence）は
  既存契約のまま。本planは適用moduleの安全契約を変更しない。

## 8. Testing strategy

### Unit（JVM。既存fixture方式を踏襲）

- `RestorableRecoveryPointSelectorTest`（新規）:
  - 単一点 / 複数点で最新選択 / retention境界（`createdAt + 24h` の ±1 ms） /
    checksum無効・非VERIFIED・tombstoneの除外 / 空でnull / 同 `createdAtMs` の決定的tie-break /
    `RemainingWindow` の切捨て・1..24 clamp・1時間未満区分。
- `LayoutApplicationModule`（既存test群へ追加）: `readRestorableRecoveryEntry` の
  gate未ready / mutex競合 / snapshot unavailable → null、no-write/no-event counter assert。
- `ManualOrganizationRunTest`（既存へ追加）:
  - cold entry → `InspectingRecovery` → `RecoveryPreview(result, correlated=null)`（履歴summaryを
    運ばないこと。coldでは `lastVerifiedApply` が常にnull）→ confirm → `RecoveryResultState`。
  - run active中・recovery lease競合中の不受理（状態不変）。
  - cancel → `Idle`、zero-write。
  - 旧entryのrestart oracle（既存 ~957–963行）が無編集でgreenであることの回帰確認。
  - warm processで `lastVerifiedApply` のpointId一致時に履歴行が表示される既存挙動の継続。
- `RecoveryPreviewContractTest` / `RecoveryPreviewProtocolTest`: 無編集green
  （spec 84契約の無変更の証拠）。

### Instrumentation

- status card行のstatus別render: `ORGANIZED_RESTORABLE` 行に残時間 + CTAがあること、
  他status・fail-closedではCTAなし（`OrganizerDurableStatusInstrumentationTest` /
  `ManualOrganizationPreferencesInstrumentationTest` へ追加）。
- CTA → 検査 → 確認面（decision pair）→ cancel / confirm の両経路。
- run進行中はdurable行・CTAが消える否定的観測。
- **cold-process emulator flow**（RS-AC-01）: force-stop → hubを最初の面として起動 →
  Launcherを開かずにCTA→検査→確認→復元を完了 → 復元後の行が「restored or expired」へ
  変わる。evidenceをPR/auditへ記録（spec 271 DS-AC-10と同型）。
- `ManualOrganizationProductionE2EInstrumentationTest.manualRunUsesProductionCaptureApplyVerificationAndRecovery`
  の継続green（既存recovery経路の無変更証拠）。

### a11y / localization evidence

- Compose semantics test: 読み順「状態→残期限→操作」、CTAのrole/state、行statusとの区別。
- 200% font scaleでCTA到達可能。non-color-only。focus restoration。
- 新規stringの `values/` / `values-ja/` name集合・placeholder一致の機械確認 +
  hardcoded literal grep（#366 HUB-AC-08と同一方式）。

### 検証command（記録対象）

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
# organizer instrumentation lane（既存のlane構成に従う）
# cold-process emulator evidence（RS-AC-01）
```

## 9. Accessibility evidence

spec「Accessibility and localization」節の要求を、§8のsemantics test +
emulator screenshot（light/dark × ja/default）+ TalkBack操作記録でPRへ添付する。
status card読み順規約（TO-BE §13-5）は#366が導入するため、#366 merge後の実装では
hub側のsemantics testと結果を突き合わせる。

## 10. Incremental implementation order

1. **純粋selector + entry型 + module読み取り**（unit test込み。application内で完結）。
2. **coordinator cold entry**（unit test込み。既存state列の再利用確認）。
3. **UI描画**（残時間 + CTA、strings EN/ja、semantics test）。
4. **instrumentation / cold-process evidence**。
5. **spec 271 companion revision + DESIGN.md + inventory evidence**（同一PR。spec受入はowner review）。
6. **高リスクPR evidence**: `final-status` CI成功 + `docs/assessment/pr-<n>-<slug>.md`。

2と3は依存するが、1は独立してreview可能。単独のaudit可能な小PRとして出す
（disposition §8: 高リスクpathのため単独小PR推奨。#374/#375とbundleしない）。

## 11. Dependency / blocker（高リスクPR要件を含む）

- **hard blocker（実装着手）**:
  - #365（正本改訂、docs-only）のmerge（正本を先に。disposition §5 順1）。
  - #366（hub）のmerge（CTAの契約上のentry面。disposition §8 graph）。#366未mergeの場合は
    既存面上の実装→hub側引き継ぎの順で差し支えないが、ownerと調整する。
  - 本specのowner受入（`status: draft` → accepted。`status: needs-spec` → readyはownerが付与）。
- **高リスクPR要件**（`risk: layout-data` label。AGENTS.md / github-workflow.md §高リスク契約）:
  - PRに `risk: layout-data` labelが付く。
  - 検証対象head commit上で `CI / final-status`（source job `organizer-unit-tests` /
    `check-style` / `build-debug-apk` をskipなしで含む `pull_request` eventのrun）が成功。
  - `docs/assessment/pr-<PR番号>-<slug>.md` に独立audit記録（Auditor / Audit date /
    Head SHA / CI run link / Criteria=本spec受入条件+spec 84/13/271の受入条件を要件ID付きで参照）。
    auditは実装sessionとは別のsession/agentが実施する。
  - `High-risk gate / high-risk-evidence` checkの成功（`.github/workflows/high-risk-gate.yml`
    による機械検証）。
- **非block**: #369（確認面のhost移動。契約は不変）、#370/#371/#372/#373（別面）。
  #374/#375はstatus cardの別行であり、本Issueの行・seamと衝突しない。

## 12. Risk

| Risk | Mitigation |
|---|---|
| 選択readがsnapshotの内容をUI判断へ露出する（spec 84/271境界の侵食） | 閉じた2型のみ公開。pointIdはcoordinatorまで。型shape test + 契約test（RP-AC-05系）の継続で担保 |
| status表示とentry readの不整合（表示中の失効・復元済み化） | fail-closed側へ倒す（CTAなし）+ 検査を権威gateに（typed結果表示）。live countdownを作らず再読込時更新 |
| cold entryが旧entryの不変条件（`lastVerifiedApply` 相関）を壊す | cold entryは `appliedPoint` / `lastVerifiedApply` を読み書きしない。旧path oracleの無編集greenをAC化 |
| recovery storeへの意図しない書込み | 読み取り経路のno-write/no-event counter assert。既存protocol以外の書込み経路を新設しない設計 |
| #366/#369との同一file衝突（`ManualOrganizationPreferences.kt`） | 行構造の最小diff。#366 merge後のhub側実装と条件式を共有。plan revisionで差分を反映 |
| 確認tokenの誤用（死後process横断・二重実行） | 既存one-shot registryの構造的性質に依存（永続化しない）。unit testで固定（RS-AC-03） |

## 13. Explicitly unverified areas

- #366のhub実装の最終形（draft `bf00f96175` 時点の計画に基づく。merge後に
  status card描画の共有方法・route名 `HomeScreenOrganizer` / destination
  `OrganizerHubPreferences` を再確認する）。
- `RemainingWindow` の表示copyと複数形の扱い（ja「約N時間」等。実装PRのstring diffで確定）。
- #369 merge後の確認面host・Back経路の実装詳細（本specの契約は不変だが、
  navigation testの対象destinationは変わる）。
- `OrganizationOperationLease` のKind構成が#367以降のauthoring移動で変化する場合の
  cold entry側の追従（契約は「RECOVERYの単一flight」であり、変化しても本specの
  要求は変わらない）。

[la]: ../../lawnchair/src/app/lawnchair/LawnchairApp.kt
