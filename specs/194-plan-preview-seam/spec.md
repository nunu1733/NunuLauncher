---
issue: "#194"
status: draft
requirements: []
risk:
  - layout-data
updated: 2026-09-02
---

# Read-only plan preview and PreviewChange projection contract

## Problem

Organizer の確認画面は件数サマリーのみを表示し、ユーザーは「どの項目が、どこからどこへ、どう変わるか」を適用前に確認できない。調査 ([Issue #192 assessment](../../docs/assessment/issue-192-organizer-concrete-preview-investigation.md)) により、不足は一致保証ではなく **UI が消費できる typed preview model と表示名 data path** であることが確定した。

確認時点で確定しているのは semantic placement plan (`PendingPlan` の `OrganizationInput` + `PlanningResult`) であり、実行対象の executable action plan (`ValidatedLayoutPlan`) は confirm 後の fresh capture を経て materialize されて初めて確定する。preview と apply の一致を構成的に保証する最短経路は、materialize 済み `ValidatedLayoutPlan` を canonical preview source として再利用することである (spec 84 `inspectRecovery` の read-only seam 先例)。

## Outcome

`LayoutApplicationModule` 配下に **read-only plan preview operation** (`inspectPlan`) と、その内部で動く **純粋な `PreviewChange` projection module** が追加される。`inspectPlan` は readiness gate と non-blocking writer lease の下で current state を一度だけ capture し、planning snapshot revision と照合した上で `OrganizationPlanMaterializer.materialize` を実行し、materialize 済み `ValidatedLayoutPlan` と、それと同一オブジェクト由来の per-item `PreviewChange` 一覧および header counts を返す。

coordinator (`ManualOrganizationRun`) は preview 時に `inspectPlan` を呼び、preview 済み plan を保持する。confirm は preview 済み plan をそのまま apply へ渡す (confirm 時の materialize は preview が得られた場合は不要になる)。`State.Preview` は既存の件数サマリー (`Summary`) を引き続き供給し、本 Issue 単体 merge でも現行の件数 UI が regression なく動作する (#195 との independently mergeable の成立条件)。production confirmation UI の更新は #195 の scope である。

本 Issue で organizer run の適用安全性契約 (apply / recovery / diagnostics) は変更しない。

## Scope

- `LayoutApplicationModule` 配下に read-only plan preview operation `inspectPlan(input, result) -> PlanPreviewResult` を追加する。readiness gate + non-blocking organizer writer lease の下で `captureCurrent` を1回行い、planning snapshot revision と照合する。revision 一致なら `OrganizationPlanMaterializer.materialize` を実行し、`ValidatedLayoutPlan` と純粋 projection の結果を返す。
- 純粋 projection module (`ValidatedLayoutPlan` + `Planned` → `List<PreviewChange>` + header counts) を追加する。`inspectPlan` の API 境界は `PlanningResult` を受け、`result.outcome` が `Planned` である場合のみ preview に進む。
- coordinator (`ManualOrganizationRun`) の preview 表示経路を `inspectPlan` 経由へ更新し、confirm は preview 済み plan をそのまま apply へ渡す。preview が得られなかった場合 (busy / concurrent / unavailable / not-plannable) は現行どおり count-only preview + confirm 時 materialize へ劣化する (graceful degradation、§Coordinator integration)。
- `specs/52-manual-full-organization-vertical-slice/spec.md` の §"Preview and details" / §"Confirmation, staleness, and apply" を拡張し、scenario matrix に preview の stale / busy ケースを追加する。
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) へ title / `PreviewChange` の diagnostics 流出禁止を明文化する (契約の維持であり、変更ではない)。
- `CONTEXT.md` と `DESIGN.md` への影響確認・更新を同じ PR で行う。

## Non-goals

- confirmation UI の文言・レイアウト更新 (#195)。
- visual before/after preview。
- layout strategy の決定 (#182)。
- planner 配置アルゴリズムの変更。
- apply / recovery 契約、diagnostics 契約の変更。新しい `PhaseCode` / error code / `InputReadinessReason` は追加しない。
- `PreviewChange` の serialize、journal 化、export。
- profile role (個人/仕事用) の表示名投影 (#182 も将来要求する input のため、別 decision で行う)。
- `ValidatedLayoutPlan` への説明用 metadata (rationale / warning) 追加。

## Domain language

`CONTEXT.md` に次の用語を追加する (承認時に反映):

**plan preview (プランプレビュー)**:
確認より前に、planning snapshot と同じ revision を read-only に再 capture し、executable action plan を materialize して semantic plan の rationale と対応付けた、process-local な変更一覧。書込み、checkpoint、recovery 操作を行わない。
_Avoid_: visual preview (#195 以外の scope)、Backup、Undo

## Public preview contract

### Operation and result surface

```text
inspectPlan(input: OrganizationInput, result: PlanningResult) -> PlanPreviewResult

PlanPreviewResult =
  | Previewed { preview: PlanPreview }
  | Stale
  | NotPlannable { reason: PlanPreviewRejection }
  | Unavailable { reason: PlanPreviewUnavailable }
  | WriterBusy
  | Concurrent

PlanPreview {
  plan: ValidatedLayoutPlan     // canonical preview source; confirm はこのオブジェクトをそのまま apply へ渡す
  changes: List<PreviewChange>  // 決定的な順序 (§Projection contract)
  counts: PreviewCounts         // §Projection contract
}

PlanPreviewRejection =
  | OUTCOME_NOT_PLANNED        // result.outcome が Rejected
  | CAPTURE_FAILED             // captureCurrent が失敗 (typed non-write; 現行 materialize の Invalid 扱いに相当)
  | MATERIALIZATION_INVALID    // materializer が Invalid、または projection join 不整合

PlanPreviewUnavailable =
  | RECONCILIATION_PENDING
  | RECONCILIATION_FAILED
```

- `Previewed` は preview 済み `ValidatedLayoutPlan` を保持する。**opaque confirmation capability は導入しない**: plan 自身が application-owned な不変 artifact であり、apply の既存 A2 exact precondition gate (revision + 完全状態比較) が preview→confirm 間の TOCTOU を typed zero-write で閉じる。spec 84 の confirmation token は recovery の pointId 束縛が必要だったための仕組みであり、本契約では plan オブジェクト自体がその役割を果たす。
- `PlanPreview` / `PreviewChange` は process-local であり、**serialize しない**。coordinator が所有し、opaque confirmation と同じ現行規約に従う。
- `Stale` は capture revision が planning snapshot revision と不一致のときの typed non-write 結果である。preview 操作自身が capture するため、呼び出し側が stale な context を渡す経路は存在しない (spec 84 と同様、stale は preview→confirm の TOCTOU 側で A2 gate が扱う)。
- 生の `RevisionId`、digest、DB row、recovery record を result surface へ露出しない。`PlanPreview.plan` は既存の public `ValidatedLayoutPlan` 型であり、新たな accessor を追加しない。

### Read-only protocol and ordering

`inspectPlan` は readiness gate と module RunMutex を共有するが、mutation protocol には決して入らない。

| Step | Required behavior | Result on condition | Persistent effect |
|---|---|---|---|
| P0 | Require completed successful startup reconciliation (readiness gate)。 | `Unavailable(RECONCILIATION_PENDING)` before readiness; `Unavailable(RECONCILIATION_FAILED)` after a failed gate。 | None. |
| P1 | module RunMutex を non-blocking で取得。 | `Concurrent` on contention。 | None. |
| P2 | 既存 organizer writer lease (`WriterKind.ORGANIZER`) を non-blocking で取得。lease は短命で、1回の authoritative capture のみを許す。 | `WriterBusy` on lease contention。 | None. |
| P3 | lease の下で `captureCurrent` を1回実行し、lease と mutex を `finally` で解放する。capture failure (RuntimeException) は `NotPlannable(CAPTURE_FAILED)` へ落とす。 | — | None. |
| P4 | `capture.revision != input.snapshot.revision` を検査。 | `Stale`。 | None. |
| P5 | `result.outcome` が `Planned` であることを検査し、`OrganizationPlanMaterializer.materialize(input, result, capture.layoutState)` を実行、`Ready` なら純粋 projection を実行して `Previewed` を返す。 | `NotPlannable(OUTCOME_NOT_PLANNED)` / `NotPlannable(MATERIALIZATION_INVALID)`。 | None. |

lease 保持中に `checkpoint`、`markApplying`、`markRestoring`、`advance`、`pruneUnused`、`runRetention`、`applyWriteSet`、`requestCorrelatedReload`、diagnostic projection を呼んではならない。lease を返却後に保持、転送、queue してもならない。**すべての result path で checkpoint、recovery store 書込み、layout 書込み、model reload、lifecycle 遷移、diagnostic emit を行わない** (spec 84 の禁止事項を踏襲し、test で証明する)。lock state の個別検査は不要である — revision は canonical state 全体の digest であり、lock state を含む全 canonical field が planning snapshot と一致していることを `Stale` 検査が包含する。

## Projection contract

純粋関数 module (`PlanPreviewProjector`) が `ValidatedLayoutPlan` + `Planned` を `List<PreviewChange>` + header counts へ投影する。UI 文言を持たず、Android 型を持たず、planning / application の既存型だけを消費する。

### 責務分担 (authoritative source)

- **action / before-after / executable change の authoritative source は `ValidatedLayoutPlan`** (何が実際に適用されるか): source 位置は action の `expected.placement`、destination は `intended.placement`、新規フォルダの配置は `intendedState` から得る。
- **rationale / preserve reason / warning の authoritative source は `Planned`** (`PlanningResult.outcome`) (なぜそうなったか): `Disposition.Moved.rationale` (`PlacementCode`)、`Disposition.Preserved.reason` (`PreserveReason`)、`Planned.warnings`。
- 両者の join は item identity で行う: persistent item は `ItemId` (`ApplyAction.ref: ApplicationItemRef.PersistentItem`)、planned folder は `NewFolderOrdinal` (`ApplicationItemRef.PlannedFolder`)。materializer が同一 `(input, result)` から決定的に actions を導出するため、join は決定的である。join に失敗する (action に対応する `Planned` entry が存在しない) ことは契約違反であり、typed に `NotPlannable(MATERIALIZATION_INVALID)` として扱う。
- `ValidatedLayoutPlan` 自体へ説明用 metadata は追加しない。将来 #182 の preserved-by-strategy rationale も `Planned` 側に付加され、projection は無変更で消費する。

### Change kinds and row rules

`PreviewChange` は次の closed 種別のみを含む。行の対応規則:

| Action | Row rule |
|---|---|
| `ApplyAction.Preserve` | `PreservedChange` 1行 (reason は joined `Disposition.Preserved` より)。 |
| `ApplyAction.Update` (placement が変化) | `MoveChange` 1行 (source = `expected.placement`、destination = `intended.placement`、rationale は joined `Disposition.Moved` より)。 |
| `ApplyAction.Update` (placement 不変 — container 構成のみ変化) | 専用行を立てない。変化は member の `MoveChange` 行が記述する (member 行と二重表示しない)。 |
| `ApplyAction.Insert` (planned folder) | `NewFolderChange` 1行。 |

```text
PreviewChange =
  | MoveChange { item: ItemId, label: PreviewLabel, source: PreviewPosition,
                 destination: PreviewPosition, rationale: PlacementCode? }
  | PreservedChange { item: ItemId, label: PreviewLabel, reason: PreserveReason }
  | NewFolderChange { ordinal: NewFolderOrdinal, placement: PreviewPosition,
                      memberLabels: List<PreviewLabel> }   // planned rank 順
  | NewPageChange { ordinal: NewPageOrdinal, displayPosition: Int }  // 1-based
  | ItemWarningChange { item: ItemId, label: PreviewLabel, code: WarningCode }

PreviewLabel =
  | Named(value: String)                       // canonical capture title
  | KindFallback(kind: CanonicalItemKind)      // UI が kind 由来の総称文言へ解決する

PreviewPosition =
  | Workspace(pageDisplayOrdinal: Int, isNewPage: Boolean, rowBand: RowBand,
              columnBand: ColumnBand, rowOrdinal: Int)
  | DockRank(rank: Int)
  | InFolder(folder: PreviewFolderRef)
  | InAppPair(pair: PreviewLabel)

PreviewFolderRef =
  | Existing(label: PreviewLabel)
  | Planned(ordinal: NewFolderOrdinal)

RowBand = TOP | CENTER | BOTTOM
ColumnBand = LEFT | CENTER | RIGHT

PreviewCounts {
  movedCount: Int                       // MoveChange 行数
  preservedCount: Int                   // PreservedChange 行数
  newFolderCount: Int                   // NewFolderChange 行数 (= plan.newFolders.size)
  newPageCount: Int                     // NewPageChange 行数 (= plan.newPages.size)
  warningCounts: Map<WarningCode, Int>  // Planned.warnings 全体 (item 非依存)
}
```

- `MoveChange.rationale` は joined disposition が `Moved` のとき `PlacementCode` を運ぶ。`Update` かつ disposition が `Preserved` の組合せ (placement 変化を伴う構成変更) は v1 planner が生産しない (fixture corpus の property test で検証)。その場合も projection は total であり、`rationale = null` として決定的に投影する (#182 の strategy 拡張で説明 metadata が付加されても行構造は変わらない)。
- `ItemWarningChange` は `Planned.warnings` のうち params がちょうど1つの `ItemParam` を持つ warning に対して1行生成する。それ以外の warning は header counts のみに現れる。

### Position normalization (page 序数・領域表現)

`ItemId` / `PageId` / 生 cell / digest を `PreviewChange` の表示値へ露出しない。位置は次の正規化規約に従う typed 値のみを運ぶ。正規化は projection 内で完結し、決定的である。

- **page 序数**: `plan.sourceState.pages` と `plan.newPages` を合わせた page 集合を `PageOrder` 昇順にソートした位置 (1-based) を `pageDisplayOrdinal` とする。persistent page の `isNewPage` は `false`、planned page は `true` とする。
- **行帯 × 列帯の領域表現**: device grid を3行帯 ×3列帯へ正規化する。band index は item の start cell (`GridCell.x` / `GridCell.y`、span の左上) に対して `floor(coordinate * 3 / dimension)` を計算し、`0..2` へ clamp する (`RowBand` / `ColumnBand`)。grid が3未満の次元を持つ場合、対応する帯は単に現れない。
- **start cell 基準**: span > 1 の item も start cell のみで帯を判定する (span は帯判定に使わない)。帯境界付近の移動が同じ語に潰れる誇張・過小は許容する (assessment §6.1 の text-only の限界)。
- **行序数**: start cell の 1-based 行位置 (`cell.y + 1`) を `rowOrdinal` として運ぶ (帯表現の副詞的補助)。
- **帯内微調整**: `MoveChange` の source と destination が双方 `Workspace` で、`pageDisplayOrdinal`・`rowBand`・`columnBand` が等しい場合、その行は帯内微調整 (`sameBandAdjustment`) である。この判定は `MoveChange` に派生 property (`get()`) として定義し、位置値と矛盾し得ない。文言は UI 側 (#195) が strings で管理する。
- **dock**: `DockRank(rank)` は 1-based 表示序数 (`rank + 1`) ではなく、既存 `rank` 契約 (0-based) をそのまま運ぶ。文言側で +1 する。
- **folder**: 移動先が既存 folder なら capture title 由来の `PreviewLabel`、新規 folder なら `Planned(ordinal)` で運び、`NewFolderChange` 行と対応付ける。
- **app pair**: v1 の full organization で app pair は保持対象であり、`InAppPair` は `Update` の destination としては生産されない。projection は total であるため型のみ定義する。

### Labels and privacy

- 表示名は canonical capture (`plan.sourceState`) の `title` を主とし、`OptionalText.Present` なら `PreviewLabel.Named(value)` を運ぶ。
- `Absent` の場合は `PreviewLabel.KindFallback(kind)` を運ぶ。**kind 由来の総称文言は projection に置かない** (UI 文言を持たない契約)。#195 が strings (`values/` + `values-ja/`) で解決する。この点は assessment §2-3 の「kind 由来の総称表現」を、文言の所在地だけ UI 側へ寄せて具体化したものである。
- raw package / component / shortcut id / `ItemId` / `PageId` / 生 cell / span / digest / profile serial を `PreviewLabel` および表示値へ露出しない。`ItemId` は opaque 相関キーとしてのみ保持する (spec 84 の `RecoveryPreviewSummary.pointId` と同じ姿勢)。
- 新規 folder の名前は planner が決めないため、`NewFolderChange` 自身の label は持たず、配置 + member labels で識別する。

### Row ordering and determinism

同一 `(input, result)` + 同一 revision からは常に同一の `List<PreviewChange>` が得られる。

- `MoveChange` / `PreservedChange` は `plan.actions` の順序 (source state の canonical 順、その後 planned folder の ordinal 順) に従う。
- `NewFolderChange` は ordinal 昇順、`NewPageChange` は ordinal 昇順、`ItemWarningChange` は `Planned.warnings` の宣言順に従う。
- projection は I/O を行わず、`Clock` や乱数に依存しない。

### Header counts と既存 Summary の関係

`PreviewCounts` は materialize 済み plan (actions) から導出した truth である。既存 `Summary` (planning disposition から導出) は `State.Preview` の UI 供給契約として**現行どおり別途維持され、本契約によって置き換えも正規化もしない**。両者の数値が一致しない edge case (planner が現位置と同一の target に `Moved` を付ける等) が将来生じても、UI の件数契約は壊れない。fixture corpus に対する v1 planner の property test で `movedCount == Summary.movedCount` 等の対応観測を行い、乖離が検出されたら #182 側へ課題として届ける。

## Coordinator integration

`ManualOrganizationRun` の変更:

- **start**: `Planned` かつ非空のとき、現行の `PendingPlan` 生成と `State.Preview(summary)` 遷移の前に `inspectPlan(input, result)` を呼ぶ。
  - `Previewed` → `PendingPlan` が `PlanPreview` を保持する。`State.Preview(summary)` の形状は**変更しない** (既存 `Summary` 供給を維持)。`PREVIEWED` run event は現行どおり発行する。
  - `Stale` → `State.Stale` へ遷移し、現行の confirm 時 materialize 失敗と同じ `APPLY_REJECTED` (A2, `STALE_REVISION`) run event を coordinator が発行する。書込みなし。MFO-06 の契約 (旧 plan 破棄、recapture 要求) は変わらない。
  - `WriterBusy` / `Concurrent` / `Unavailable` / `NotPlannable` → **graceful degradation**: preview なしで現行どおり `State.Preview(summary)` へ遷移し、confirm が従来どおり materialize する。run を失敗させず、UI の件数サマリーも壊さない。新 state / 新 diagnostics は導入しない。
- **confirm**: `PendingPlan` が `PlanPreview` を保持する場合、**preview 済み plan をそのまま** `application.apply(plan, runId)` へ渡し、confirm 時 materialize を省略する。保持しない場合 (degradation) は現行の materialize 経路をそのまま使う。apply の A2 exact precondition gate は変更しない — preview→confirm 間に layout が変われば、apply は現行どおり typed zero-write で fail (`STALE_REVISION` / `EXACT_PRECONDITION_FAILED`) し、`State.Stale` へ遷移して recapture を要求する。
- cancel / dismissal / process recreation の契約は現行どおり。`PlanPreview` は process-local であり再現されない (opaque confirmation と同じ規約)。
- coordinator は `inspectPlan` の結果を diagnostics へ投影しない。`PlanningProjection` は件数のみのまま。

## spec 52 への拡張 (同じ PR で適用)

- §"Preview and details" に追記: plannable で非空の full run の preview は、application-owned の read-only plan preview (spec 194) に裏付けられる。coordinator は preview 時に materialize 済み `ValidatedLayoutPlan` と per-item `PreviewChange` projection を取得し、confirm はその plan を適用する。preview が得られない場合は count-only preview へ劣化し、confirm 時 materialize が既存どおり動く。preview 時の stale は既存の typed stale 結果と A2 stale rejection event で扱う。preview の per-item 表示 (文言・a11y・展開) の正本は #195 であり、本節は data path と安全性のみを固定する。
- §"Confirmation, staleness, and apply" に追記: confirm は取得済み preview 済み plan を適用対象とし、A2 exact precondition が最終 gate であることを明記。
- scenario matrix に1行追加: preview capture が stale / busy の場合 → typed stale (書込みなし、既存 event) または count-only への graceful degradation; 確認画面は機能し続ける。

## Behavior scenarios

### Scenario: Plannable non-empty plan reaches a change-level preview

Given capture と planning が成功し、非空の `Planned` result が得られている,

When coordinator が `inspectPlan(input, result)` を呼ぶ,

Then readiness gate / RunMutex / writer lease の下で1回の capture が行われ、revision 一致なら materialize と projection が実行され、`Previewed` (plan + `PreviewChange` 一覧 + counts) が返る,

And `State.Preview` は既存 `Summary` を引き続き供給し、`PREVIEWED` event は現行どおり発行され、layout 書込み / checkpoint / recovery 書込み / model reload / lifecycle 遷移 / diagnostics emit は発生しない。

### Scenario: Layout changes between planning capture and preview capture

Given planning 直接後に layout が変化した,

When `inspectPlan` が fresh capture を行い revision 不一致を検出した,

Then `Stale` が返り、coordinator は `State.Stale` へ遷移して既存 `APPLY_REJECTED` (A2, `STALE_REVISION`) event を発行する,

And いかなる書込みも checkpoint も行われず、UI は既存の stale 経路 (recapture 要求) を提示する。

### Scenario: Preview is unavailable (writer busy / concurrent / readiness / not-plannable)

Given writer lease が他 writer に保持されている、または mutex / readiness / plan 整合性の条件が満たされない,

When `inspectPlan` が `WriterBusy` / `Concurrent` / `Unavailable` / `NotPlannable` を返した,

Then coordinator は preview なしで `State.Preview(summary)` へ遷移し (graceful degradation)、confirm が従来どおり materialize する,

And 件数サマリー UI は regression なく動作し、新 state も新 diagnostics も導入されない。

### Scenario: Confirm applies the previewed plan unchanged

Given `Previewed` な preview が表示されている,

When user が confirm する,

Then coordinator は preview 済み `ValidatedLayoutPlan` (同一オブジェクト) をそのまま apply へ渡し、confirm 時 materialize は実行されない,

And apply の A2 revision / exact precondition gate が最終 gate として機能し、layout 変更後の confirm は typed zero-write で fail して `State.Stale` となる。

### Scenario: Projection is deterministic and responsibility-split

Given 同一 `(input, result)` と同一 revision の capture が2回与えられた,

When projection が2回実行される,

Then 両者の `List<PreviewChange>` と counts は完全に一致する,

And すべての action / before-after は `ValidatedLayoutPlan` に、すべての rationale / warning は `Planned` に由来し、join は決定的である (どちらか一方だけでは行を構成できない)。

## Data and state

- `PlanPreview` / `PreviewChange` は process-local な in-memory value であり、serialize / journal / export / 永続化されない。recovery point、checkpoint、recovery record、Launcher DB、schema、rule format への影響はない。
- 既存 `PendingPlan` の process-local 保持規約に `PlanPreview` を追加するのみで、coordinator の並行性構造 (operation lease、`RunMutex` 的 coordinator lock、recovery preview lease) は変更しない。
- migration / rollback / backup-restore への影響なし。

## Permissions, privacy, and security

新たな permission、network 通信、telemetry、export は追加しない。preview が扱う title はユーザー自身の端末上の表示名であり、on-device 表示のみを意図する。`PreviewChange`・title を diagnostics journal / logcat / export へ投影することを禁止する (organizer-diagnostics.md 契約の明文化)。test fixture に実 title を含めない (synthetic identity のみ)。

## Accessibility and localization

本 Issue は UI を追加しない。`PreviewChange` は #195 が必要とする情報 (解決済み label または kind fallback、page 序数、領域コード、行序数、帯内微調整判定、rationale / warning code) を過不足なく運び、TalkBack 行の単一 label 構成、font scaling、focus 復帰の契約は spec 52 のまま #195 が所有する。領域語彙・kind fallback の文言は strings (`values/` + `values-ja/`) で管理する。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| PP-AC-01 | `spec.md` と `plan.md` が、唯一の application-owned read-only preview operation (`inspectPlan`)、closed な `PlanPreviewResult` surface、既存 `apply` / `recover` 契約の無変更を定義している。 |
| PP-AC-02 | すべての result path (`Previewed` / `Stale` / `NotPlannable` / `Unavailable` / `WriterBusy` / `Concurrent`) で、checkpoint / recovery store 書込み / layout 書込み / model reload / lifecycle 遷移 / diagnostics emit が zero であることを、interface 経由の fake counter test で証明する (spec 84 の証明パターンを継承)。 |
| PP-AC-03 | 同一 `(input, result)` + 同一 revision に対する `PreviewChange` 一覧と counts が決定的である (2回実行一致)。 |
| PP-AC-04 | preview の内容が apply 対象 actions と構成的に一致する: preview 済み plan オブジェクトがそのまま apply へ渡されること (同一インスタンス)、および `MoveChange` / `PreservedChange` / `NewFolderChange` 行が同一 plan の actions と 1:1 対応することを contract test で検証する。 |
| PP-AC-05 | action / before-after が `ValidatedLayoutPlan` から、rationale / preserve reason / warning が `Planned` から供給される責務分担を contract test で検証する (join の決定性を含む)。 |
| PP-AC-06 | stale capture (revision 不一致) で typed non-write の `Stale` を返す。 |
| PP-AC-07 | coordinator が `State.Preview` で既存 `Summary` を引き続き供給し、`WriterBusy` / `Concurrent` / `Unavailable` / `NotPlannable` 時に count-only preview + confirm 時 materialize へ graceful degradation することで、#195 未実装のまま本 Issue 単体 merge しても現行件数 UI が regression なく動作する。 |
| PP-AC-08 | page 序数 / 行帯 × 列帯 / start cell 基準 / 帯内微調整判定 / dock / folder / label fallback の正規化規約が、境界値を含む unit test で検証される。 |
| PP-AC-09 | spec 52 の拡張、`CONTEXT.md` / `DESIGN.md` の影響確認・更新、organizer-diagnostics.md の流出禁止明文化が同じ PR で完了する。 |
| PP-AC-10 | test fixture が実 title を含まない (synthetic identity のみ)。 |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| PP-AC-01 | public shape の review + 既存 `ApplyResult` / `RecoveryResult` shape 無変更の contract test。 |
| PP-AC-02 | `PlanPreviewProtocolTest`: fake writer / store / diagnostics counter による全 result path の zero-mutation 主張、`faults.serializationContention()` と `refuseLease` による busy 経路、mutex 保持による `Concurrent`、capture-only counter による lease scope / release 証明。 |
| PP-AC-03 | 同一 fixture での2回 `inspectPlan` 実行一致 + 純粋 projector の2回実行一致。 |
| PP-AC-04 | coordinator test: `apply` へ渡された plan インスタンスが preview 済み plan と同一であること (`===`)、materialize が呼ばれないこと。projector contract test: action ↔ row 対応表。 |
| PP-AC-05 | projector contract test: rationale / reason / warning を変えた fixture で行の該当 field のみが変化し、placement を変えた fixture で source / destination のみが変化すること。 |
| PP-AC-06 | `PlanPreviewProtocolTest`: capture revision を planning snapshot と差し替えた fixture で `Stale`、書込み counter 0。 |
| PP-AC-07 | `ManualOrganizationRunTest`: preview 成功時 / 非成功時の `State.Preview(summary)` 供給、`apply` 経路の分岐、既存 summary 系 test の無変更通過。 |
| PP-AC-08 | `PlanPreviewProjectorTest`: band 境界値 (`floor(coord*3/dim)`、clamp)、start cell 基準 (span>1)、同帯判定、page 序数 (新規 page 含む)、`PreviewLabel` fallback、dock / folder / app pair 位置。 |
| PP-AC-09 | PR diff review (spec 52 / CONTEXT.md / DESIGN.md / organizer-diagnostics.md)。 |
| PP-AC-10 | fixture review + synthetic identity のみの fixture corpus による property test。 |

## Open questions

None。Stage A で決定済み:

1. opaque confirmation capability は導入しない — preview 済み `ValidatedLayoutPlan` 自身が confirm の適用対象であり、A2 exact precondition が TOCTOU の最終 gate である。
2. preview が得られない場合は run を失敗させず count-only へ graceful degradation する (既存 confirm 時 materialize 経路を維持)。
3. `risk: layout-data` label を付ける (coordinator の confirm / apply 前段フローと spec 52 を触るため)。high-risk independent-evidence gate (CI `final-status` + `docs/assessment/pr-194-*.md`) を満たす。
4. profile role 表示名投影は #182 / #195 側の将来 decision とし、本契約は露出しない。
5. kind fallback の文言所在地は UI (#195) とし、projection は `KindFallback(kind)` を運ぶ。

実装が raw revision の露出、書込み経路の追加、diagnostics 契約の変更なしには表現できないと判明した場合は、この境界を弱めず owner application-contract の follow-up issue を起票して停止する。

## Change history

- 2026-09-02: Drafted for Issue #194。production 実装は spec / plan 承認まで停止。

## References

- [Issue #194: plan preview seam + PreviewChange projection contract](https://github.com/nunu1733/NunuLauncher/issues/194)
- [Issue #192 assessment: Organizer concrete preview investigation](../../docs/assessment/issue-192-organizer-concrete-preview-investigation.md)
- [Issue #195: confirmation UI 更新](https://github.com/nunu1733/NunuLauncher/issues/195)
- [Issue #182: layout strategies Epic](https://github.com/nunu1733/NunuLauncher/issues/182)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
- [CONTEXT.md: organizer domain language](../../CONTEXT.md)
- [DESIGN.md: Layout Application module and invariants](../../DESIGN.md)
- [Spec 52: manual full-organization vertical slice](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 84: read-only revision-bound recovery preview (architectural precedent)](../84-recovery-preview-seam/spec.md)
- [Spec 13: safe layout application and recovery](../13-safe-layout-application/spec.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
