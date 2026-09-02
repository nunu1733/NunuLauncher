# Assessment: Issue #192 — Organizer実行前確認を具体的な変更内容プレビューへ見直すための調査

> Status: investigation complete (implementation は後続 Feature Issue へ分離、本調査での production 変更なし)
> Audit date: 2026-09-02
> Evidence target: `main` commit `c47c9d9464562c97ecc92d42f22ceb89c6fa1226` (2026-09-02 時点)
> Related: #182 (strategy Epic)、#52 / spec 52 (manual full organization)、#84 / spec 84 (recovery preview seam)、#123 / spec 123 (UI convergence)、#152 (post-apply verification)

## Outcome in one line

確認画面表示時点で確定しているのは **semantic placement plan**(各項目の移動先・保持理由を記述する `PendingPlan` の `OrganizationInput` + `PlanningResult`)であり、これはメモリ内に保持されている。実際の `ApplyAction.Preserve/Update/Insert` を持つ **executable action plan**(`ValidatedLayoutPlan`)は、confirm 後の fresh capture を経て materialize されて初めて確定する。apply は semantic plan を再導出するのではなく、同一ペアから決定的な materializer で per-item action へ変換し、revision 照合 + 完全状態の exact precondition で新鮮さを最終 gate している。不足しているのは一致性ではなく、**UI が消費できる typed preview model と表示名(title)の data path** である。推奨は spec 84 の `inspectRecovery` 先例に倣い、**application-owned の read-only preview seam で materialize 済み `ValidatedLayoutPlan` を「何が適用されるか」の canonical source とし、rationale / warning は同一 semantic plan の `PlanningResult.Planned` を authoritative として併用、UI 向け `PreviewChange` projection を経由して表示する**構成である(§5-3)。

## 1. 現行 plan → preview → confirm → apply の source trace

### 1.1 Run coordinator の状態遷移

`lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (Issue #52 の coordinator):

1. **start** (`ManualOrganizationRun.kt:196`): `OrganizationOperationLease` で RUN 排他を取得し `RUN_STARTED` を発行。
2. **capture** (`:211`): `application.composeFullOrganization()` → `LayoutApplicationModule.composeManualFullOrganizationInput` (`application/protocol/LayoutApplicationModule.kt:134`) → readiness gate → `ProductionOrganizationInputComposer` → `writer.captureCurrent(CaptureId("organization-input"))` (`integration/OrganizationInputComposer.kt:81`) で canonical `LayoutState` を取得し、`mapLayout`/`mapItem` (`OrganizationInputComposer.kt:310, :330`) で planning 用 `LayoutSnapshot` へ投影する。**この投影は `title` を読み捨てる**(後述 §2.3)。
3. **plan** (`:232`): `planner.plan(input)` → `DeterministicOrganizationPlanner` (`planning/DeterministicOrganizationPlanner.kt:7`) → validation → classification → `PlanningPlacement.place` → canonical化された `PlanningResult`。
4. **preview** (`:245-265`): `Planned` の場合、`outcome.summary(input)` (`:576` → `:551`) で件数のみの `Summary` を生成する。全件数 0 なら `State.NoChanges` (`:248`)。そうでなければ `PendingPlan(operation, input, result, summary)` を保持し (`:253`)、`State.Preview(summary)` へ遷移して `PREVIEWED` を発行する。
5. **confirm** (`:310`): `State.Preview` のときのみ受理。`State.Applying` へ遷移し `USER_CONFIRMED` を発行。
6. **materialize** (`:328`): `application.materialize(pendingPlan.input, pendingPlan.result)` → `LayoutApplicationModule.materializeManualFullOrganizationPlan` (`LayoutApplicationModule.kt:153`)。readiness gate の下で `writer.captureCurrent(CaptureId("manual-full-materialize"))` を再取得し、**`capture.revision != input.snapshot.revision` なら `Result.Invalid`** (`:164`)。有効なら `OrganizationPlanMaterializer.materialize(input, result, capture.layoutState)` を呼ぶ。
7. **Invalid → Stale** (`:330-343`): `State.Stale` + `APPLY_REJECTED`(A2, `STALE_REVISION`) 診断。書込みなし。
8. **apply** (`:355`): `application.apply(plan, runId)` → `LayoutApplicationModule.applyWithRunId` (`LayoutApplicationModule.kt:114`) → `ApplyProtocol.apply` (`application/protocol/ApplyProtocol.kt:43`)。詳細は §3。
9. **terminal** (`:356-375`): `State.Applied(result, summary)` / `NoChanges` / `Stale` 等へ遷移。成功時は `appliedPoint` と `lastVerifiedApply` を保存し、recovery preview (`inspectRecovery` / `confirmRecoveryPreview`) へつながる (`:383-449`)。

### 1.2 件数サマリーの生成元と UI

- 生成元: `ManualOrganizationRun.Summary` (`ManualOrganizationRun.kt:147-181`)。`PlanningResult.summary(input)` (`:551-574`) が `Planned.placements` を `Disposition.Moved` / `Disposition.Preserved` で数え、`newFolders.size` / `newPages.size`、理由別 (`PlacementCode` / `PreserveReason` / `RejectionCode` / `UnplacedReason` / `WarningCode`)、constraints (locked / unavailable / widget / app pair / legacy shortcut / empty folder / availability) を集計する。**入力は `Planned` そのものであり、サマリーは plan の二次投影に過ぎない。**
- UI: `ui/preferences/destinations/ManualOrganizationPreferences.kt`。`State.Preview` の描画は `:153-173` で `summaryItems(currentState.summary)` (`:379-445`) を呼び、`manual_organization_moved_count` = "%1$d placements will move" 等の文字列 (`lawnchair/res/values/strings.xml:1007-1012`、ja は `values-ja/strings.xml`) で件数行を並べる。**UI layer が受け取るのは `Summary` のみであり、`PlanningResult` の具体的な per-item 内容は UI へ一切渡っていない。**
- 文言 "Review the proposed organization" (`manual_organization_preview`) + 件数列挙が、Issue #192 が指摘する「件数しか分からない確認画面」の正体である。
- Onboarding (#53) は同一 coordinator / 同一画面を再利用する (`OrganizationOnboardingProposal.kt` が `HomeScreenManualOrganization` を起動)。プレビュー変更は onboarding 経路へも波及する。

### 1.3 PlanningResult と writer mutation の対応

`PlanningResult.Planned` (`planning/PlanningResult.kt:12-18`) の構造:

- `placements: List<PlannedPlacement>` — **対象全itemに対して1つ**(`item: ItemId`, `disposition: Moved(PlacementCode) | Preserved(PreserveReason)`, `target: PlacementTarget`)。`PlacementTarget` は `WorkspaceTarget(page, cell, span)` / `Dock(rank)` / `FolderMember(folder, rank)` / `AppPairMember(pair)`。
- `newPages: List<NewPage>` (`ordinal`, `order`)、`newFolders: List<NewFolder>` (`ordinal`, `profile`, `workspacePlacement`, `members: List<ItemId>`)、`categories`、`warnings`。

「どこから」は `OrganizationInput.snapshot` 側が持つ: `CapturedItem.placement` (`CapturedPlacement.Workspace(PageRef, cell, span)` 等) と `snapshot.pages` (`PageId` + `PageOrder`)。「どこへ」は `PlannedPlacement.target`。materializer が両者を突き合わせて action を組むため、planning 時点で source と destination の両方が確定している。

Materializer (`application/actions/OrganizationPlanMaterializer.kt:46-158`) は純粋関数として:

1. `result.revision/ruleVersion/taxonomyVersion` と `input` の一致検証 (`:51-56`)。
2. ordinal 検証、reservation-overlap guard (ADR-0010, `:66-74`)。
3. **conservation 検証**: `planned.placements` の item 集合 == snapshot item 集合 == materialize 時 capture の item 集合 (`:76-81`)。重複禁止。
4. per-item の `ApplyAction.Preserve / Update / Insert` を構築 (`:130-143`)。`Update` は `intended != source`(配置・構造・その他全フィールド比較)のときのみ発火し、**`Delete` action は存在しない**(spec 52)。
5. `ValidatedLayoutPlan{sourceRevision, sourceState, intendedState, actions, newPages, newFolders, ...}` を返す (`:146-157`)。

`ValidatedLayoutPlan` (`application/public/ValidatedLayoutPlan.kt:16-43`) は「適用される具体的変更」の正本表現である。writer は `prepareApplyWriteSet` → `RowManifestCodec` 経由で favorites 行 (`Favorites.TITLE` 含む、`adapter/RowManifestCodec.kt:185`) を書き、`LauncherLayoutAdapter` が transaction・recovery point・model reload・検証 (#152) を実行する。

## 2. confirmation 時点で利用可能な concrete change data の一覧

### 2.1 `State.Preview` 時点(coordinator メモリ内、確認前)

| データ | 場所 | ユーザー表示への直結度 |
|---|---|---|
| 移動 item の source (page/cell/span、dock rank、所属 folder) | `PendingPlan.input.snapshot.items[].placement` | page は `snapshot.pages` の `PageOrder` から序数へ変換可能。cell は生座標(粗い表現へ要変換) |
| 移動 item の destination (page/cell/span、dock、folder、rank) | `PendingPlan.result.outcome.placements[].target` | 同上。`NewPageRef` / `NewFolderRef` は ordinal で表示可能 |
| 移動/保持の rationale | `disposition.rationale` / `disposition.reason` | 理由別文言は既存(`PlacementCode`/`PreserveReason` → strings)。「変更なし」は `Preserved(ALREADY_CANONICAL)` として表現済み |
| item identity | `CapturedItem.target: TargetKey` (`AppKey(component, profile)` / `ShortcutKey(packageName, shortcutId, profile)` / `WidgetKey` / `FolderKey` / `AppPairKey`) | **component/package/shortcut id は内部識別子**。表示名への投影が必要(§2.3) |
| 新規フォルダの構成メンバー・profile・配置 | `Planned.newFolders[].members/profile/workspacePlacement` | メンバーの ItemId → 表示名投影が必要。**フォルダ名は planner が決めない**(materialize 時の placeholder は "Folder", `OrganizationPlanMaterializer.kt:257`)。カテゴリは `categories: CategoryDecision` から推せるが folder title 契約は未定義 |
| 新規ページ | `Planned.newPages[]` | ordinal + `PageOrder` → 「末尾にページ追加」として表示可能 |
| 未配置/警告 | `Rejected.Impossible.unplaced` / `Planned.warnings` | 既存文言あり |
| page 情報 | `snapshot.pages` (`PageId`, `PageOrder`) | `PageId` は不透明。表示は `PageOrder` ソート順の序数(「2ページ目」)を使う |
| profile | `CapturedItem.profile: ProfileId` (不opaque serial 由来) | **"仕事用/個人" という role 名への正本的投影は存在しない**(#182 も同じ指摘)。category override UI の `CategoryOverrideProfile{PERSONAL, WORK}` (`ui/CategoryOverrideAuthoring.kt:25`) が唯一の先例(UserCache serial → PERSONAL/WORK 判別) |

### 2.2 confirm 後(materialize 時)に初めて手に入るデータ

- materialize 時 capture の `LayoutState`: per-item の `title: OptionalText`(favorites `TITLE` 列由来、Absent の場合あり)、`intent`、`icon`、widget 状態、lock state、folder/app-pair 構造 (`application/public/LayoutState.kt:117-132`)。
- `ValidatedLayoutPlan.actions`: per-item の完全な before (`expected`) / after (`intended`) canonical state (`LayoutState.kt:260-276`)。これが A2 gate と writer の実行対象であり、**「何が適用されるか (before/after)」の canonical source** である。ただし actions には rationale が付かない(`ApplyAction.Preserve` は `ref + expected` のみ)ため、**表示用の理由 (`PlacementCode` / `PreserveReason` / `warnings`) は `PlanningResult.Planned` 側が authoritative であり、projection は両者を併用する**(§5-3)。

### 2.3 表示名の source として何があり、何が欠けているか

- **planning 入力に title は存在しない。** `OrganizationInputComposer.mapItem` は canonical item の `title` を読まず `CapturedItem` に投影しない(該当フィールドなし)。#182 の feasibility 分析も "Current input: No display label" と確認済み。
- **canonical capture には title がある。** `CanonicalItemState.title` は favorites `TITLE` 列から復号され(`RowManifestCodec.kt:358`)、workspace が実際に表示している文字列と同じ authority である。ただし Absent の場合があるため、fallback 表現(kind ベースの総称名)が必要。
- **PackageManager / LauncherApps からの label 解決も可能。** 先例は category override authoring の `AndroidCategoryOverrideAppInventory` (`CategoryOverrideAuthoring.kt:152-178`) で、capture と同じ `UserCache` serial mapping で profile を判別し `activity.label` と icon を得ている。ただしこれは capture とは独立した source であり、label 変化・局所化差・遅延 uninstall で capture と不一致になり得る。**表示のみに限定するなら許容**だが、planner 入力に混入してはならない(#182 が "label alphabetical には authoritative sort-key source が先" と要求しているのと同様の境界)。

結論: 移動先・移動元・フォルダ構成・新規ページ・理由・警告は**追加の authoritative input なしでユーザー向けに説明可能**。表示名は (a) canonical capture の title を主、(b) Absent 時は kind 由来の総称表現に fallback するのが最も安全で、PackageManager label は表示補助として任意。raw package / component / ItemId / PageId / 生座標 / digest をそのまま見せることは Non-goals(#192)および既存 privacy 姿勢に反する。

## 3. preview と実 apply の一致保証の現状

**現状の保証は強い。** 欠落は UI 側の投影だけである。

1. **同一 semantic plan の再利用**: `PendingPlan` が `input + result`(semantic placement plan)を保持し、confirm 時の materialize が同じペアを消費する。apply が別途変更内容を導出することはない(確認画面と apply は同じ `Planned` から分岐)。
2. **revision = 全状態 content hash**: `RevisionId` は canonical `LayoutState` 全体の digest である (`application/revision/RevisionCalculator.kt:32`、"Two byte-identical canonical layouts produce equal revisions")。ハッシュ一致は byte-identical を強く示唆するが、論理的な `⟺ byte-identical` と断定するものではない。**完全一致の最終保証は、revision 照合に加えて apply 時の exact precondition (`capture.layoutState != plan.sourceState` → `EXACT_PRECONDITION_FAILED`) が担う。**
3. **materialize 時の新鮮さ gate**: `LayoutApplicationModule.kt:164` が materialize 用 capture の revision を planning snapshot revision と照合する。不一致なら `Result.Invalid` → `State.Stale`(書込みなし)。この gate は revision (digest) のみを照合する。
4. **apply 時の二重 gate (A2)**: `ApplyProtocol.kt:109-117` が `capture.revision != plan.sourceRevision` → `STALE_REVISION`、**`capture.layoutState != plan.sourceState` → `EXACT_PRECONDITION_FAILED`** を返す。writer adapter 側でも同様の exact 再検証がある (`LauncherLayoutAdapter.kt:184`)。preview→confirm 間の TOCTOU はこの typed zero-write rejection で閉じられる。
5. **決定性**: planner は決定的(spec 12、property/contract test)、materializer は純関数。同一 `(input, result)` から同一 actions が得られる。
6. **UI の現状との関係**: UI が見ている `Summary` は `Planned` の集計であり、件数が偽る余地はない。ただし「どの項目がどう変わるか」は表示していないため、**ユーザーの承認対象が具体的内容ではなく件数になっている** — これが Issue #192 の問題の正確な位置づけである。

## 4. 具体的変更を表示するために不足している data / contract

1. **UI 向け typed preview model** が存在しない。UI は `Summary`(件数)しか受け取らない。`PreviewChange` 等の per-item projection 型と、それを組み立てる単一の seam が必要。
2. **preview 時点での表示名 data path**。title は materialize 時 capture にしかない。preview を具体化するには (i) preview 前に materialize 相当の read-only capture を行う、または (ii) composer が title を planning 入力へ追加する、のいずれかが必要。(ii) は planner 入力契約(spec 10/12)の変更と #182 の「label は新 input」との整合判断を伴うため、(i) が影響範囲最小である。
3. **profile role の表示名投影**(個人/仕事用)。`ProfileId` は不opaque。表示だけなら role なしの区別(セクション分け)でも成立するが、同名アプリ判別には PERSONAL/WORK 別の表示が事実上必要。category override UI の UserCache 判別の再利用または正式な profile-role projection の decision が必要(#182 も同 input を将来要求)。
4. **page 序数と同一ページ内の領域表現のユーザー規約**。`PageId` は不opaqueのため、`PageOrder` ソート順に基づく序数表示の規約(1始まり、新規ページの位置表現)を契約として固定する必要がある。さらに同一ページ内移動は序数だけでは before/after を判断できないため、§6.1 の領域表現(帯の境界、start cell 基準、帯内微調整の扱い)を projection 側の正規化規約と strings 語彙として固定する必要がある。
5. **spec 52 §"Preview and details" の拡張**。現行 spec は counts + reasons + warnings を要求する(accepted 契約)。具体的変更一覧は spec の拡張であり、spec 更新を同じ Feature Issue で行う必要がある。spec 13 (apply 契約) の変更は不要。
6. **診断 privacy 境界の明示**。preview UI が title を表示することは診断契約と無関係だが、`PreviewChange` を `RunEvent` / journal へ流さない規約を明文化する必要がある(現行の `PlanningProjection` は件数のみ、`organizer-diagnostics.md` 契約どおり)。

## 5. 候補アーキテクチャ比較と推奨案

### Option 1: UI が `PlanningResult` を直接解釈する

- 長所: 新しい seam が不要。現行 `Summary` と同型の依存。
- 短所: UI が materializer の意味論(どの `Preserved` が「無変化」か、`Update` の発火条件、folder 構造の再構築)を再実装することになり、表示と実適用の drift リスクを常時抱える。依存方向(UI ← planning 型の直接依存)も悪化し、#182 の strategy 変更が UI に波及する。テストは UI 経由しかなく契約検証が難しい。**非推奨。**

### Option 2: planning/application contract から専用 `PreviewChange` projection を作る

- 長所: 依存方向が正しく、projection を interface 経由で単体テスト可能。localization / a11y / truncation policy を一箇所に集約できる。
- 短所: projection の入力が `PlanningResult` + `input` のままだと title が無く(§2.3)、表示名を別途解決する必要がある。また actions を再導出すると Option 1 と同型の drift を内包する。

### Option 3: materialize 済み `ValidatedLayoutPlan` を canonical preview source として再利用する

- 長所: **preview と apply が構成上一致する**(表示対象の action が同一オブジェクト)。title を含む canonical state が手に入る。materializer は既に reservation guard・conservation・整合検証を通過済みの artifact を返す。spec 84 の `RecoveryPreviewProtocol` が「application-owned、writer lease 下の read-only capture、typed result、opaque confirmation、TOCTOU は confirm 時再検証」という同型パターンを実装済みで、安全姿勢の先例として検証されている。#182 の将来 strategy も同じ materializer を通るため**自動的に preview 対応**になる。
- 短所: coordinator フローが変わり(spec 52 更新が必要)、preview 毎に capture+digest が 1 回増える(`RecoveryPreviewProtocol.inspect` と同コスト、DB read + hash 程度で軽微)。preview 操作が mutation を行わないことを test で証明する必要がある(spec 84 が同証明を既に持つ)。加えて `ValidatedLayoutPlan` 単独では rationale / warning を保持しないため、**projection は semantic plan (`PlanningResult.Planned`) を説明 metadata の source として併用する必要がある**(§5-3)。

### 推奨: Option 3 を骨格に、Option 2 の typed projection を提示層として重ねる

1. application 側に **read-only plan preview operation**(仮称 `inspectPlan(input, result) -> PlanPreviewResult`)を追加する。readiness gate + 非blocking writer lease の下で `captureCurrent` → revision 照合 → `OrganizationPlanMaterializer.materialize` → **materialize 後に actions と `PlanningResult.Planned` を item identity で対応付け、両者を `PreviewChange` 一覧へ純粋投影**(§5-3 の責務分担)し、capture revision に紐付いた opaque `PlanPreviewConfirmation`(および preview 済み `ValidatedLayoutPlan`)を返す。checkpoint・診断・lifecycle 遷移は行わない(spec 84 の禁止事項リストを踏襲)。
2. coordinator は preview 表示時に `inspectPlan` を呼び、`State.Preview(PlanPreviewResult)` を UI へ渡す。confirm は preview 済み plan をそのまま apply に渡す(現行の confirm 時 materialize は preview 側へ移動)。apply の A2 exact precondition は最終 gate として**変更しない** — stale は現行どおり typed zero-write で fail し recapture を要求する。
   - 用語の整理: preview 前に確定しているのは semantic placement plan(`input + result`)であり、executable action plan(`ValidatedLayoutPlan`)は `inspectPlan` 内の materialize で確定する。つまりこの構成では **confirm 前に executable plan へ引き上げる**変更であり、「semantic plan は preview 前に確定 / executable action plan は capture + materialize 後に確定」という区別は現行と同様に保たれる。`State.Preview` が旧 `Summary` と同等の情報を引き続き供給する互換性要件は #194 の acceptance criteria として明記済み。
   - 代替(confirm 時 materialize を維持し preview は投影のみ)も revision 照合 + 決定性により契約上は成立するが、preview と apply の一致を「決定性 + revision 一致」で論じるより同一オブジェクトの方が検証・説明とも単純である。最終選択は後続 spec で行う。
3. **`PreviewChange` projection は純粋関数 module** (`ValidatedLayoutPlan` + `PlanningResult.Planned` → `List<PreviewChange>` + header counts)。**入力は2つであり、単独の `ValidatedLayoutPlan` では rationale / warning を投影できない**: `ValidatedLayoutPlan` が保持するのは `sourceState / intendedState / actions / newPages / newFolders / ruleVersion / taxonomyVersion` で(`ValidatedLayoutPlan.kt:16-43`)、`ApplyAction` は `ref + expected/intended` のみ(`LayoutState.kt:260-276`)であり、planner の `PlacementCode` / `PreserveReason` / `warnings` は保持しない。したがって責務を次のように分ける:
   - **action / before-after / executable change の authoritative source は `ValidatedLayoutPlan`**(何が実際に適用されるか)。
   - **rationale / preserve reason / placement reason / warning の authoritative source は `PlanningResult.Planned`**(なぜそうなったか; `disposition.rationale`、`Planned.warnings`)。将来 #182 の preserved-by-strategy rationale もこちらに付加され、`ValidatedLayoutPlan` 自体へ説明用 metadata を追加する必要はない。
   - 両者は `inspectPlan(input, result)` 内で materialize 後に item identity(`ItemId` / planned folder ordinal)で対応付ける。materializer が同一 `(input, result)` から決定的に actions を導出するため(§3-5)、対応付けは決定的である。
   projection 自体は UI 文言を持たず、Android 型を持たず、planning/application の既存型だけを消費する。ItemId は `RecoveryPreviewSummary` の `pointId` と同じ「opaque 相関キー」としてのみ保持し、表示は名前解決済みの文字列を運ぶ。位置は生 cell を運ばず、**page 序数 + 正規化済みの領域コード + 行序数**(§6.1)を typed 値として運ぶ。領域語彙の文言は strings(`values/` + `values-ja/`)で管理する。
4. title 解決は §2.3 のとおり canonical capture title を主とし、Absent は kind 由来の総称表現へ fallback。PackageManager label は表示補助として独立 decision。
5. diagnostics への流出禁止を projection の KDoc と diagnostics 契約文書に明記する。
6. **既存 count-only UI との互換性**: `inspectPlan` 導入後も `PlanPreviewResult` は旧 `Summary` と同値の header counts(移動/保持/新規フォルダ/新規ページ/警告の件数)を供給し、`State.Preview` が既存 `Summary` 契約を壊さないことを #194 の acceptance criteria とする。これにより #194 単体 merge 時も現行の件数 UI が regression なしで動作し、#195 (UI 更新) と独立して merge 可能である。

## 6. 初回 UX の具体例(text/list ベース、visual は対象外)

件数ヘッダ(現行サマリーを維持)+ 変更種別ごとの grouping + 先頭 N 件表示 + 「すべて表示」展開。座標は生 cell ではなく「ページ序数 + 粗い領域」で表現する(§6.1)。

### 6.1 同一ページ内移動のユーザー可読な位置表現

同一ページ内移動では page 序数だけが変わらないため、「1ページ内で移動」では before/after を判断できない。Organizer の変更が同一ページ内の詰め直し中心になるケースでは、元の件数サマリーと情報価値が大きく変わらないリスクがある。ユーザー向けの粗い位置表現として、planner の既存出力から追加 input なしで計算可能な候補を比較する(cell + span と `DeviceCapabilities.columns/rows` は §2.1 のとおり capture に既存):

| 候補 | 例 | 長所 | 短所 |
|---|---|---|---|
| A: 行帯 × 列帯の領域表現(grid を縦3×横3の帯へ正規化) | 「上段中央 → 上段左」「下段右 → 中段右」 | device grid 規格に非依存で移植性が高い。cell 1個分の座標より認知的に近い。localization は語彙9個のみ | 隣接帯境界付近の移動が同じ語に潰れる。span>1 の item は代表 cell(start cell)での判定になり誇張・過小が起き得る |
| B: 行序数のみ(横方向は語らない) | 「2段目 → 1段目」 | 語彙が最小で TalkBack の読み上げが短い | 縦方向の詰め直しが支配的な実 grid で、横方向の移動を説明できない |
| C: before/after の行列表現(ページ内の表) | 「2ページ目: 3×5 表で before/after を並べる」 | 変更全体の相対関係が分かる | 行列表現は text list の流れに埋め込むと視認性が悪く、a11y(表の線形 traversal)と font scaling で契約違反になりやすい。visual preview の領域であり text/list の前提を超える |

推奨は **A を主表現とし、行の変化が主たる move では B を副詞的に併記する**(例: 「上段中央 → 上段左(2段目から1段目)」)。正規化規約(帯の境界、start cell 基準、dock / folder / page 跨ぎの扱い)は projection の純粋関数側に置き、`PreviewChange` は正規化済みの領域コード + 行序数を運ぶ(§5-3)。語彙の文言は strings(`values/` + `values-ja/`)で管理する。

**残余リスク(text-only の説明力の限界)を明記する**: A は領域を3×3帯へ解像度圧縮するため、帯内の微細な詰め直し(例: 同じ「上段中央」帯内での隣接スワップ)は before/after が同じ語になり、**同一ページ移動では text-only に限界がある**。#195 の spec では (a) 帯内移動が検出された場合の行の表現(「同帯内で微調整」等の明示)、(b) 該当 move の全件が帯内に潰れる場合の警告、を契約に入れ、この residual risk が text-only で説明力不足と判定される場合は visual preview を「Optional な将来拡張」ではなく起票必須の後続 Issue として扱う判断を spec 時点で行う(本 assessment の「visual は初回実装の前提にしない」は変わらない)。

**例1: 移動のみ(9件、§6.1 の領域表現を適用)**

```text
整理の内容
移動 9件:
・Chrome — 2ページ目 → 1ページ目 上段中央
・カレンダー — 1ページ目 上段中央 → 上段左(3段目から2段目)
・(残り7件) すべて表示
変更なし 12件 / ロックされた配置 2件 / ウィジェット 2件
[適用する]  [キャンセル]
```

**例2: フォルダ作成を含む**

```text
移動 11件 / 新規フォルダ 2件
新規フォルダ (1ページ目 上段中央): 写真・カメラ・ファイル をまとめる
新規フォルダ (2ページ目): 音楽・ポッドキャスト をまとめる
・音楽 — 2ページ目 → 新規フォルダへ
...
```

**例3: 新規ページの追加**

```text
移動 14件 / 新規ページ 1件
新しいページを末尾に追加し、7件をそこへ配置します
・マップ — 3ページ目 → 4ページ目(新規)
...
```

**例4: 変更0件** — 現行どおり "No changes"(確認 action を出さない、`ManualOrganizationRun.kt:248`)。

**例5: stale** — preview 表示後に layout 変更 → 確認で typed `Stale` → 現行文言("レイアウトが変更されました" 相当)+ 再取得。preview を自動再計画しない。

表示しないもの: raw package/component 名、ItemId、PageId、cell の生座標、digest、DB 行、profile serial。

## 7. accessibility / localization / privacy / stale-plan の考慮事項

- **TalkBack**: 現行の status 行は `liveRegion = Polite`(`ManualOrganizationPreferences.kt:356-360`)。変更一覧の各行は「名前 + 移動元 → 移動先」を1つの意味ある label にまとめ、行ごとに focus させる。§6.1 の領域表現(「上段中央」等)は座標より短く読み上げ可能である。大量行を liveRegion にしない(読み上げ洪水防止)。「すべて表示」は状態(展開/折畳)を semantics に持つ。focus 復帰は spec 52 の契約(表示後・展開後・stale 後)を継承。
- **Switch Access / keyboard**: grouping 見出し + 行 + confirm/cancel/展開の線形 traversal。expandable が focus 順を壊さないこと。
- **font scaling (200%)**: 行は `PreferenceLazyColumn` 規約(spec 123 収束先)で wrap し、横 scroll 依存を禁止(spec 52 a11y 契約どおり)。
- **localization**: 全 copy を `values/` + `values-ja/` へ追加(spec 123 の「日本語 fallback 禁止」契約)。序数・結合プレースホルダは ICU 由来の Android resource 規約に従う。名前挿入による文の組み立ては `%1$s` + 語順差を許す構造にする。
- **privacy**: preview UI はユーザー自身の端末上の title を表示してよい。ただし `PreviewChange`・title を diagnostics/journal/export へ投影しない(現行 `PlanningProjection` は件数のみ、`organizer-diagnostics.md` 契約維持)。PR・test fixture にも実 title を残さない(synthetic identity)。
- **stale-plan**: preview は capture revision に束縛し、自動再計画しない。confirm/apply の A2 exact precondition が最終 gate(現行契約のまま)。preview 中に layout が変わった場合の UX は「適用 → Stale 表示 → 再取得」でよく、preview を監視し続ける channel は不要(複雑性と lease 占有を避ける)。
- **configuration change / activity recreation**: coordinator は process-local singleton で `stateFlow` 経由の復元が現行契約。preview model も `PendingPlan` と同様に coordinator が所有し、**serialize しない**(opaque confirmation の現行規約どおり)。
- **readiness 失敗**: preview 前段の readiness gate / WRITER_BUSY は既存の typed 結果をそのまま UI へ(`inspectRecovery` と同型)。

## 8. #182 との ownership 整理

- #182 の planned child Issue 5 "selection and preview-explanation UI" と本件は重複する。
- **推奨整理: 共通 preview infrastructure を本 Issue (#192) 由来の独立 Feature Issue とし、#182 側から利用する。**
  - 推奨構成の projection は `PlanningResult` / `ValidatedLayoutPlan` 由来であり、strategy 非依存である。#182 が strategy を追加しても materializer と projection は無変更で動作し、strategy 固有の追加(strategy identity 表示、cross-page move 件数、preserved-by-strategy rationale)は `PreviewChange` への付加フィールドと #182 側 UI で吸収できる。
  - #182 の child Issue 5 は「strategy 選択 UI + strategy 固有 consequence の付加」に縮小され、preview 一覧の基盤実装を複製しない。
  - 本調査の結論は #182 へコメントで通知し、#182 の spec 作成時に参照させる(重複実装の防止は #192 の acceptance criteria)。

## 9. 実装分割(後続 Issue、起票済み)

1. **#194 — Feature: plan preview seam + `PreviewChange` projection contract**(application-owned read-only preview、spec 更新込み)。read-only 操作だが coordinator の confirm フローと spec 52 を触るため、PR では `risk: layout-data` 判定と evidence 要件を実装 Issue 側で確認する。**`PlanPreviewResult` が旧 `Summary` と同値の header counts を供給し、#194 単体 merge でも現行件数 UI が regression なく動作することを acceptance criteria とする**(§5-6)。これが #195 との independently mergeable の成立条件である。
2. **#195 — Feature: Organizer confirmation UI を件数中心から変更一覧中心へ**(1 に依存。UI、strings + ja、a11y、instrumentation evidence)。
3. **Optional Feature: visual before/after layout preview** — 本調査では起票しない。text/list ベースの出荷後の評価で必要性が確定した場合に起票。初回実装の前提にしない(#192 の指示どおり)。ただし §6.1 のとおり、同一ページ内移動は text-only で説明力の限界(帯内微調整が同じ語に潰れる)が残るため、#195 の spec 時点で residual risk の大きさを再判定し、説明不足と結論した場合は Optional ではなく必須後続 Issue として起票する。
4. **#182 側 integration** — #182 の spec / child Issue として扱い、本 Issue では起票しない(ownership は §8 のとおり #182 へ通知済み)。

1 と 2 は independently mergeable(2 は 1 の契約にのみ依存)。単一巨大 PR は想定しない。

## Traceability(参照した正本)

- spec 52 §Preview and details / Confirmation / Result(件数中心 preview の現行契約、exact precondition 契約、a11y・privacy 契約)
- spec 84(read-only revision-bound preview + opaque confirmation のアーキテクチャ先例、summary の privacy 姿勢)
- spec 13(apply/recovery 契約、変更不要)
- spec 123(UI 収束・localization 契約)
- #182(feasibility 分析: label 非存在、strategy preview 要件、child Issue 5)
- `DESIGN.md` §4-5、§6(run state machine)、`CONTEXT.md`(plan/run/recovery point 用語)
- source: §1-3 に記載の file:line(すべて commit `c47c9d9464` 時点)
