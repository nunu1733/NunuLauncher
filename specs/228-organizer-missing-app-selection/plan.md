# Implementation Plan: Organizer がホーム未配置アプリを選択して追加・整理できる

> Issue: #228
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

2026-09-06 時点で `main` (`5ec5e592ca`) 上の source を確認済み。推測は「未確認」と明記する。

### 既存契約で成立していること (新設不要)

- **planner 入力型に candidate model が既にある**: `CandidateItem` / `CandidateKind` (APPLICATION | DEEP_SHORTCUT) / `CandidateTarget` / `TargetSet.additions` (`lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt`)。V-15 (重複 target)、V-21 (oversized)、V-22 (unavailable) の candidate validation も既存 ([spec 10](../10-pure-organization-planning/spec.md))。
- **apply 層に typed create が既にある**: `ApplyAction.Insert` と `ApplicationItemRef.PlannedCandidate` (`application/public/LayoutState.kt:272`, `:94`)。favorites 行の insert は production writer が既に実装済み (`application/adapter/LauncherLayoutAdapter.kt:356-371` の update-or-`insertOrThrow`、`:423` の expected-absence precondition)。
- **recovery は insert を反転できる**: `RecoveryAction.DeleteRow` (`application/actions/RecoveryWriteSet.kt`)。
- **incremental candidate allocation の意味論が規定済み**: [spec 12](../12-deterministic-full-layout-planner-v1/spec.md) P-04/P-06/P-08/P-11 (新規 page は uncapped、fit しない候補は構造的に発生しない)。
- **installed-app inventory の前例が organizer 内にある**: `AndroidCategoryOverrideAppInventory.availableApps()` (`organizer/ui/CategoryOverrideAuthoring.kt:166`) が `LauncherApps.getActivityList(null, user)` を locked/quiet profile 除外付きで使用。

### 欠けている link (本 Issue の対象)

- **V-18 が full organization の additions を無条件禁止**: `PlanningValidation.kt:621` の `ADDITIONS_UNDER_FULL_ORGANIZATION`。
- **target policy が additions 空を強制**: `FullTargetSetMaterializer.kt:63` が常に `additions = emptyList()`、`:74` が `FULL_ORGANIZATION_TARGET_POLICY_VERSION = "full-target-v1"`。
- **materializer が candidate insert を構造的に拒否**: `OrganizationPlanMaterializer.materialize` は planned placement の item が captured snapshot ID と完全一致しないと `Invalid`。candidate を発行する経路がない。
- **preview projector が candidate で fail-closed**: `PlanPreviewProjector.kt:75` (`PlannedCandidate -> Result.Invalid`)、`:275` (label 解決 `null`)。
- **preview 変更種別に Add がない**: `PlanPreview.kt:58` の `PreviewChange` closed union は `MoveChange | PreservedChange | NewFolderChange | NewPageChange | ItemWarningChange`。`PreviewCounts` に `addedCount` がない。
- **disposition に Added がない**: `Disposition` は `Moved | Preserved` のみ ([spec 10](../10-pure-organization-planning/spec.md))。
- **coordinator に選択 phase がない**: `ManualOrganizationRun.kt:139` の `State` は `Capturing → Planning` 直行。`start(trigger)` (`:210`) は `RunMode.FULL_ORGANIZATION` で即 planning へ進む。
- **選択 UI が存在しない**。

### 関連 Issue の状態

- [#208](https://github.com/nunu1733/NunuLauncher/issues/208) (同名 placement の識別) は OPEN / needs-spec。本機能の Add 行は #194/#195 の配置語表現を継承し、#208 の識別設計が確定すれば追従する。
- [#203](https://github.com/nunu1733/NunuLauncher/issues/203) (usage signals) は OPEN。本機能は #203 なしで決定的に動作する (AC-12)。

## Design

### Modules and interfaces

変更は既存 package 構成 (`DESIGN.md` §9) に収め、新 package は作らない。

- **`organizer/planning/` (純粋)**
  - `MissingAppDetector`: snapshot (`LayoutSnapshot`) と inventory identity 項目 (opaque な package / profile / component を持つ typed 値) を受け、未配置候補を決定的に計算する純粋 module。spec D1 の (package, profile) 一致規則を実装する。Android 型は interface に露出しない。
  - planner 契約変更: V-18 の条件化 (`full-target-v2` provenance 付き選択 additions のみ許可)、`Disposition.Added{rationale: PlacementCode}` の新設。captured item は `Added` にならず、追加候補は `Moved` にならない validation を追加。
- **`organizer/integration/` (port + adapter)**
  - `InstalledAppInventorySource` (port): 未配置検出に使う launchable installed apps の identity 項目 (component / package / profile / availability) を返す。ADR-0007 の source ownership model に従い、`InputProvenance` に source identity を参加させる。production adapter は `CategoryOverrideAuthoring.kt` 前例 (`LauncherApps.getActivityList`、locked/quiet 除外) に従う。
  - `OrganizationInputComposer` 拡張: 選択 additions を受け取る合成経路 (`full-target-v2`) と、inventory readiness failure の typed `InputReadinessReason` (新値) を提供。classic 経路 (`composeFullOrganization`、additions 空) は現行どおり。
- **`organizer/application/`**
  - `OrganizationPlanMaterializer` 拡張: `PlannedCandidate` 参照 → 完全な intended state (title / intent / icon / placement / structure) を持つ `ApplyAction.Insert` の発行。content 解決は単一 resolver port (`CandidateContentResolver`、`FolderTitleResolver` 前例) に分離し、label 未解決は fail-closed `Invalid`。
  - `PlanPreviewProjector` 拡張: `PlannedCandidate` 参照を `AddChange` へ投影。fail-closed 経路の削除ではなく、action 種別との 1:1 対応を保つ。
  - `PlanPreview.kt` 契約変更: `PreviewChange` union に `AddChange(label: PreviewLabel, placement: PreviewPosition)`、`PreviewCounts.addedCount` を追加。
  - writer / recovery は変更しない (既存 insert / `DeleteRow` 経路を消費)。
- **`organizer/ui/`**
  - `ManualOrganizationRun` 拡張: 選択 variant trigger、`State.SelectingApps(candidates)`、選択確定 / cancel / 「選択せずに整理する」action、Stale 再 capture 時の選択保持規則 (spec D4)。既存 `MANUAL_FULL` の状態遷移は不変。
  - `ManualOrganizationPreferences` に選択画面 (icon / label / checkbox / 件数 / 検索 / Select all / Clear all) を追加。UI 状態は process-local。
  - confirmation UI: 新規配置 (Add) group の追加 (spec 195 の group 順序へ)。

### Seam と production/test adapter

- 呼び出し側と test は同じ seam を使う: planner は `OrganizationPlanner.plan`、適用は `LayoutApplicationModule`、検出は `MissingAppDetector`、inventory は `InstalledAppInventorySource` (test では fixture adapter)。内部実装を直接検証しない。
- `CandidateContentResolver` は application protocol の port 集合 (`Ports.kt`) に加え、production adapter は run 内 inventory snapshot から解決する。
- planner の interface は不変のまま (`plan(OrganizationInput)`): label / icon は planner へ入らず、選択 additions は `TargetSet.additions` (既存型) のみで伝わる。

### Data flow

```text
選択 variant start
  → capture (既存 captureCurrent、revision 固定)
  → inventory source 読出 (readiness 失敗時は typed InputUnavailable)
  → MissingAppDetector (snapshot × inventory → 候補 + 選択保持の再計算)
  → State.SelectingApps (書込みなし)
  → confirmSelection(選択済み id 集合)
  → composer (full-target-v2: additions = 選択済み CandidateItem、provenance 記録)
  → OrganizationPlanner.plan (V-18 条件化後、追加候補は Disposition.Added)
  → materialize (candidate → Insert(PlannedCandidate, 完全 intended state)、resolver で content 解決)
  → inspectPlan → Previewed(changes = Move/Add/NewFolder/NewPage/Preserve/Warning, counts.addedCount)
  → confirm → applyWithRunId (transaction / stale 再確認 / recovery point / 相関リロード検証)
  → 失敗時 rollback (部分作成なし) / 成功後 recovery は DeleteRow で反転
```

error: inventory 読出失敗 → `InputUnavailable(新 reason)`。planner `Invalid` / `Impossible` → 既存 `PlanningRejected`。stale → 既存 `Stale` → 再 capture → 検出やり直し (D4 の保持規則)。

### Alternatives rejected

- **`RunMode.IncrementalPlacement` の流用**: P-08 により captured item は全件 Preserve になり Move が生成されない。Issue #228 が要求する「既存 placement の Move + 追加候補の Add」を 1 proposal で表現できないため不採用。
- **planner 層で `Moved{SINGLE_PLACEMENT}` を流用し preview のみ Add と表示**: issue が「Move を作成に流用して review / transactional semantics を曖昧にするな」と明示。planner 自己記述と summary counts でも区別できる `Disposition.Added` を採用した。
- **preview 変更種別を追加せず NewFolderChange で近似**: 行と action の 1:1 契約 (spec 194) を崩すため不採用。
- **package event / install 時の自動追加**: ADR-0005 と #85 Option B により MVP 外。本機能は per-run の stateless 検出であり、presence store を持たない。
- **label による一致判定**: label は翻訳・rename で不安定。identity は (package, profile) (D1)。
- **選択状態の永続化**: first delivery の安全性を優先し process-local。永続化は別 Issue。
- **選択画面の推薦 (使用頻度順等)**: #203 依存を first delivery から排除 (AC-12)。決定的順序のみ。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/planning/` | `MissingAppDetector` (純粋)、V-18 条件化、`Disposition.Added`、追加候補の legal target 継承 | 検出と disposition は planner 契約の領域。platform 型を漏らさない |
| `organizer/integration/` | `InstalledAppInventorySource` port + production adapter、composer の full-target-v2 経路、provenance 拡張、`InputReadinessReason` 新値 | 入力構成と authoritative source の所有は integration (spec 83 / ADR-0007 の model) |
| `organizer/application/` | materializer の candidate `Insert` 発行、`CandidateContentResolver` port、projector の `AddChange`、`PreviewCounts.addedCount` | transactional 意味論と review 表現の正本は application module |
| `organizer/ui/` | coordinator の選択 phase、選択画面、confirmation UI の Add group、strings (ja/en) | run flow と表示の所有は ui |
| `tests/` | planner contract/property test、detector fixture test、materializer/projector test、application contract test (test DB)、UI instrumentation | quality strategy の表面区分に従う |
| docs | spec 10 / 83 / 194 / 195 の revision 記録、`CONTEXT.md`、`DESIGN.md`、必要なら ADR | 正本を先に直す (AGENTS.md) |

## Migration and recovery

- schema / rule migration: **なし**。新規永続化データなし。
- 適用中の failure: 既存 transaction 契約 (全成功または変更なし) をそのまま使用。candidate insert は中途半端に残らない。
- 適用後の recovery: 既存 recovery point ([ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md))。`DeleteRow` により追加行を反転し、既存 placement は `UpdateRow` / `PreserveRow` で復元。
- release rollback / downgrade: 適用済みの追加行は通常の favorites 行であり、旧 build でも通常の layout item として扱われる。特別な compatibility 処理は不要 (未確認 → 実装時に grid migration 経路で追確認)。
- backup / restore: 追加行は通常 layout item として backup 対象。特別扱いなし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1, AC-2 | detector fixture test (直接/folder 内/重複/work variant/widget のみ/unavailable/非起動可能) + 決定性 property test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-3, AC-4 | 選択 UI unit test + instrumentation (icon/label/件数/検索/Select all/Clear all) | organizer instrumentation job (API 36 / Platform 36.1) |
| AC-5 | planner 契約 test (未選択候補の `Insert` 不在) + coordinator test (検出/選択/preview で writer 呼出 0) | unit test |
| AC-6, AC-16 | 空 snapshot fixture、additions 含む corpus の determinism / idempotence property test | unit test (`SyntheticFixtureGenerator` corpus 拡張) |
| AC-7 | planner 契約 test (`Disposition.Added`、captured は Added にならない) + materializer test (完全 intended state、label 未解決 fail-closed) | unit test |
| AC-8 | projector test (`AddChange` / `addedCount` / 配置語) + confirmation UI instrumentation | unit + instrumentation |
| AC-9 | 既存 zero-write 契約 test の拡張 + coordinator test | unit test |
| AC-10, AC-11 | application contract test (test DB): candidate insert transaction、stale で無書込み、Nth-write failure で全 rollback、recovery `DeleteRow` 反転と再復元 | unit test (test DB) |
| AC-12 | inventory source 契約 test (signals 非依存、決定的) | unit test |
| AC-13 | 空 workspace / 混在 / folder 内 / 重複 / select-all・clear-all / 部分選択 / cancel / stale / apply-recovery の instrumentation・device evidence を PR へ記録 | organizer instrumentation job + 実機/emulator evidence |
| AC-14 | a11y instrumentation (checked semantics、status 通知、traversal、200% font scale) + ja strings 解決 test | instrumentation |
| AC-15 | spec 10/83/194/195 revision、`CONTEXT.md`、`DESIGN.md` の diff | PR review |
| 高リスク gate | `risk: layout-data` → `docs/assessment/pr-<番号>-organizer-missing-app-selection.md` 独立 audit + `final-status` 成功 | `high-risk-gate` workflow |

含めるべき観点: unit/contract (planner・detector・materializer・projector)、property (determinism / idempotence / 決定的順序)、DB/integration (test DB の transaction / rollback / recovery)、UI/accessibility (multi-select semantics / TalkBack / font scale / ja strings)、failure injection (Nth-write failure、stale、inventory 読出失敗)、performance (大規模在庫 (数百候補) での検出・描画の顕著な劣化がないことの計測記録)。

## Documentation updates

- [ ] spec status/history (本 spec + spec 10 / 83 / 194 / 195 の revision 記録)
- [ ] CONTEXT.md (未配置アプリ / スコープ選択 の用語追加)
- [ ] DESIGN.md (§5 invariants の Conservation に対する additions の位置づけ明記、§6 run 状態図の選択 phase 追記、必要なら §9 配置)
- [ ] ADR (V-18 緩和・新 authoritative source が「変更困難・理由がコードから分からない・選択肢があった」の3条件を満たすと owner review が判断した場合。spec D5/D6 の判断記録で足りるかも含めて実装 PR で判断)
- [ ] AGENTS.md (検証済み command の追加が必要になった場合のみ)

## Execution checklist

- [ ] Current behavior reproduced (V-18 / full-target-v1 / projector fail-closed の現状 test で固定)。
- [ ] Tests fail for the missing behavior (detector / Added disposition / AddChange / coordinator 選択 phase の赤 test)。
- [ ] Minimal implementation completed (縦切り: 選択 variant → 検出 → planning → preview Add 行 → apply → recovery)。
- [ ] Migration/recovery verified (test DB での rollback / recovery 反転)。
- [ ] Full relevant verification completed (unit + instrumentation + 高リスク gate)。
- [ ] PR evidence and remaining risks recorded (`Closes #228`、AC 対応表、`docs/assessment/` audit)。
