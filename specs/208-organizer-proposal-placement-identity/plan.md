# Implementation Plan: Organizer proposal の各行が同一名称の複数 placement 間で対象を一意に識別する

> Issue: #208
> Spec: [spec.md](./spec.md)
> Status: draft (spec owner review 待ち。spec 承認後に実装を開始する)
> Revision: 2 — v1 review (Request changes) を受け、canonical placement identity 導入方式へ改訂

## Current evidence

- **F-01 の再現構造** (確認済み):
  - `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt:80` — `PreservedChange(item, label, reason)` は位置 field を持たない。
  - `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt:158-167` — Preserve 経路は `action.expected` (`CanonicalItemState`: kind と placement を持つ) を手にしているが、label だけを使って捨てている。
  - `PlanPreviewProjector.kt:209-212` — `itemLabel` は title が `Present` なら kind を落とす。
  - `PlanPreviewProjector.kt:257-286` — `PositionContext.position(state)` は Workspace / DockRank / InFolder / InAppPair を導出済み。InFolder 分岐は folder 名のみで `FolderChild.rank` を使っていない。
  - `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt:185-189` — `preservedRowText` は `itemRow` format に label + reason を流し込むだけ。
  - `PlanPreviewProjector.kt:231-244` — `combinedPagePositions` は persistent + planned page を統合した決定的ソートであり、proposal 内で page 表示序数が一意である (identity の page 同定に再利用可能)。
  - 対照として Placement locks 画面は `Inside a folder, position N` / `フォルダ内の位置 N` を既に表示する (`PlacementLockPreferences.kt` `placementDescription`)。
- **v1 review で確定した反例** (本 plan の方式で解決すべきもの):
  - 同名・同 kind・同 page・同 3×3 band 内の別 cell home icon × 2 → coarse 表示語が同一行文言になる。identity は cell を含むため一意だが、表示の区別要素を spec AC-3 が要求する。
  - 同名 folder × 同 rank × 同名 child → folder 表示名 + rank の表示は衝突する。identity は親 folder の placement identity (page/cell 含む) で区別され、表示は親 folder の現在位置語で区別する。
- **#212 assessment** (`docs/assessment/issue-212-organizer-destination-verification.md`): destination == apply == persisted は cell-exact で一致 (R2 PASS)。粗い領域語は意図的設計だが R1 未達 → destination 具体性は Issue #234 へ handoff 済み。
- 推測と確認済みの区別: 上記は head `bf1886d578` での source 確認済み [SRC]。同名 placement の runtime 挙動は実装時の fixture test で固定する。

## Design

### Modules and interfaces (reviewer 推奨順序に対応)

変更は 3 つの既存 seam に局所する。planner / application / materializer / coordinator は触れない。

1. **placement identity の型と uniqueness invariant を確定 (推奨順序 1)** — `application/public/PlanPreview.kt`:
   - `PreviewPlacementIdentity` (sealed interface) を新設。variant: `Workspace(pageDisplayOrdinal, isNewPage, cellX, cellY)` / `Dock(rank)` / `FolderChild(parent, rank)` / `AppPairChild(parent, stage)` / `Unidentified(code)`。spec §Scope 1 の契約どおり。
   - `MoveChange` に `identity` (source 側) と `kind`、`PreservedChange` / `ItemWarningChange` に `identity` と `kind` を追加。
   - KDoc に一意性 invariant と有効期間契約 (proposal 内一意・非永続・serialize されない) を記録する。
2. **duplicate fixture を先に追加 (推奨順序 2)** — 失敗 test 先行:
   - §Change set の fixture 表どおり、unit property test (identity invariant) と行構築 test (表示区別要素) を、実装に先立って red で追加する。
3. **projector が identity を生成 (推奨順序 3)** — `application/preview/PlanPreviewProjector.kt`:
   - `PositionContext` 内に `identity(state: CanonicalItemState): PreviewPlacementIdentity` を新設。`PlacementState` → identity の決定的変換。
   - `FolderChild` / `AppPairChild` の parent は `ApplicationItemRef` を `sourceItemByItemId` で解決し、parent の identity を再帰構成。planned folder parent は planned folder の intended workspace placement から構成。join miss は現行どおり `Result.Invalid`。
   - Preserve / warning 経路で `identity` / `kind` を `PreservedChange` / `ItemWarningChange` へ流す。`UnsupportedContainer` は `Unidentified(code)` とする (行の欠落ではなく、理由語が既に状態を語る)。
   - `combinedPagePositions` を page 表示序数の source として再利用 (既存 `workspacePosition` と同一規約)。
4. **change 型への適用 (推奨順序 4)**: 3 と同一変更 (型追加と projector 生成は 1 つの PR ステップで通す。test は 2 が red を先に固定する)。
5. **UI presentation と copy を更新 (推奨順序 5)** — `organizer/ui/OrganizationPreviewContent.kt`:
   - `preservedRowText` / `warningRowText` を「名前 (kind) — 現在位置: 理由」構成へ拡張。位置は既存 `positionText` 経路 (identity 由来ではない点に注意 — 現行 `PreviewPosition` から導出)。
   - **同 band 別 cell / 同名 folder の表示区別要素**: identity と既存 `PreviewPosition` の差分 (cell、親の cell) を formatter が受け、既存 vocabulary での区別が不可能な場合に補助語を付与する。補助語 format と en/ja copy は spec AC-3 のとおり実装 PR で owner review にかける。
   - `moveRowText` / `sameBandAdjustment` 行も名前に kind を併記。`KindFallback` の行では kind 語を二重化しない。
   - grouping / counts / truncation / 展開の制御 flow は無変更 (行 text だけが変わる)。
6. **instrumentation (推奨順序 6)**: §Verification の AC-9 行を参照。
7. **#234 との shared projection / formatter の回帰確認 (推奨順序 7)**: coordination notes を参照。

### Data flow

入力は既存の `(ValidatedLayoutPlan, Planned)` のまま。`plan.sourceState` の `CanonicalItemState` (kind, placement) → `PositionContext` (identity 生成 + 位置語への決定的変換) → `PreviewChange` の新 field (identity / kind) → 純粋行構築 (identity → presentation → localized copy の一方向) → 文言。エラーは既存の `Result.Invalid` (join miss) のみで、`Unidentified` は行内 fallback 表示へ落ちる。

### Alternatives rejected

- **v1 方式 (表示位置 `PreviewPosition` の追加だけ)**: 同 band 別 cell / 同名 folder × 同 rank の 2 反例で一意性が成立しないため撤回 (v1 review High 1・2)。identity (データ上の一意同定) と表示 (語彙) を分離する現方式へ改訂。
- **表示名 / rank による folder child 同定**: 同名 folder で衝突するため不採用。parent identity + rank のみが構造的に一意。
- **条件付き kind 併記**: 行が change list 全体の cross-row 解析を要求し、行単位の検証を壊すため不採用。
- **ItemId 由来の接尾辞 / 連番**: `ItemId` は不透明 correlation key であり (#194)、placement を同定する構造情報を持たないため不採用。
- **cell 座標の常時表示**: #234 (destination 具体性) と競合する。本 spec は区別に必要な場合の補助語の導入に留め、常時 cell 表示は要求しない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` | `PreviewPlacementIdentity` 新設、`MoveChange.identity/.kind`、`PreservedChange.identity/.kind`、`ItemWarningChange.identity/.kind` 追加 (KDoc に invariant と有効期間) | spec 194 projection 契約の唯一の定義点。identity data model はここでしか導入できない |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` | `PositionContext.identity()` 新設、Preserve / warning 経路での identity・kind 設定 | placement → identity の決定的変換は projector の責務 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | 保持・警告・移動行の format 拡張、同 band 別 cell / 同名 folder の区別補助語、`OrganizationPreviewWording` 追加分 | 純粋行構築 seam (#195)。UI composable 側は変更不要 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 移動行 / 保持行 / 警告行 format の拡張、区別補助語 format | #123 契約 (en/ja 両方) |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` (+ 新規 identity property test) | identity 生成・一意性 invariant (全 fixture)、`Unidentified` 経路、既存 Invalid 経路の維持 | projection 契約の unit 正本 |
| `tests/unit/app/lawnchair/organizer/ui/OrganizationPreviewContentTest.kt` | 保持行・警告行・移動行の新 format、区別補助語、KindFallback 非二重化 | 行構築契約の正本 test |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | 同名 placement が Move/Preserve に跨る fixture で、行ごとの区別可能性を rendered text で主張する新規 test。既存行 text 主張の期待更新 | UI レベル検証 (Issue #208 終了条件 2) |
| `specs/194-plan-preview-seam/spec.md` | Change history へ「#208 による additive 拡張 (identity / kind)」を追記 | 契約変更の記録規約 |
| `specs/195-organizer-confirmation-change-list/spec.md` | 行表現が identity 由来の区別要素を含む旨を §Scope / change history へ追記 | 行表現の正本は 195 の scope 内にあるため |

planner / application / materializer / coordinator / diagnostics / DB には変更なし。risk label は付与しない (zero-write 表示層・`risk: layout-data` / `risk: migration` 対象外)。

### 必須 fixture (review 指摘のリストを網羅)

| # | Fixture | 検証する invariant / 要件 | Test surface |
|---|---|---|---|
| F1 | 同名 app、別 page | identity 一意 + 行文言区別 | unit property + row test |
| F2 | **同名・同 kind・同 page・同 3×3 band 内の別 cell** | identity 一意 (cell 含む) + AC-3 の表示区別要素 | 同上 |
| F3 | 同名 folder が複数 (別 page / 別 cell) | FolderChild identity が parent identity で区別される | unit property |
| F4 | **同名 folder × 同 rank × 同名 child** | F3 の最難ケース。行文言に親 folder 区別要素 (現在位置語) が現れる | unit property + row test |
| F5 | home icon と folder child が同名 (Move / Preserve に跨る) | bucket を跨いでも identity が別、関係が行から説明できる | unit + row test |
| F6 | widget / folder unit / app pair / dock の同名混在 | kind 併記による区別、identity が各 container 種別で成立 | unit + row test |
| F7 | title 無し (KindFallback) item の混在 | fallback 行でも identity・位置・kind 構成が欠落しない | unit row test |
| F8 | 全種別 + 同名の統合 proposal | instrumentation で rendered text の行区別を主張 (終了条件 2) | instrumentation (API 36) |

## Migration and recovery

- schema / rule migration: なし。`PreviewChange` / identity は process-local な projection であり、serialize / persist されない (#194 契約)。field 追加は release rollback 時にも互換性問題を生まない。
- failure 中の rollback: 適用系に触れないため対象外。
- backup/restore compatibility: 対象外 (永続化データなし)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (identity 導入・shape 不変) | `PlanPreviewProjectorTest` 更新 + corpus / counts contract test 通過 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` |
| AC-2 (identity invariant) | F1–F4 を含む identity property test | 同上 |
| AC-3 (表示区別の下限) | F2 / F4 の row test (copy 確定は実装 PR review) | 同上 |
| AC-4 (保持行 format・単一導出経路) | `OrganizationPreviewContentTest` 更新 | 同上 |
| AC-5 (folder child 親区別・locks 語彙) | F4 row test + locks 画面語彙一致の LQA 記録 | unit + manual |
| AC-6 (kind 併記・非二重化) | F6 / F7 の row test | 同上 |
| AC-7 (有効期間・非永続) | 契約 review + identity 型が Parcelable/Serializable でないことの確認 | code review |
| AC-8 (決定性・privacy) | 既存 projector / corpus / `DestinationRegionMappingTest` 通過 | 同 unit test |
| AC-9 (UI レベル区別可能性) | F8 instrumentation test + CI `organizer-instrumentation` job の run URL を PR 記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest` / CI (API 36) |
| AC-10 (a11y) | 既存 a11y instrumentation test 群の通過 + 拡張行の semantics 確認 | 同 instrumentation |

必須 verification command (現行 building guide の検証済み set): `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、上記 unit / instrumentation test。

## Documentation updates

- [x] spec status/history (本 spec: v2 に改訂済み、承認時に status 更新、実装 PR で change history 追記)
- [ ] `specs/194-plan-preview-seam/spec.md` — additive 拡張 (identity / kind) の記録。**契約文言の限定的更新**を含む: 現行 KDoc は "No raw ... cell coordinate ... is carried" と宣言するが、`PreviewPlacementIdentity` は cell 座標を「proposal 内の placement 同定のための構成要素」として保持する (表示には決して現れない)。更新では identity が保持してよい要素 (page 表示序数 / cell 座標 / dock rank / folder rank / pair stage) と引き続き禁止する要素 (package / component / page id 生値 / digest / profile identity) を明記する
- [ ] `specs/195-organizer-confirmation-change-list/spec.md` — 行表現拡張の追記
- [ ] CONTEXT.md — 不要 (domain 言語の追加なし。`PreviewPlacementIdentity` は表示層実装語)
- [ ] DESIGN.md — 不要 (module 構造・不変条件の変更なし)
- [ ] ADR — 不要 (代替案の比較は本 plan に記録。「変更困難」「理由がコードから分からない」に該当する判断なし)

## Execution checklist

- [ ] Spec 承認 (owner review) を得る (v2)。
- [ ] F1–F4 fixture の identity invariant test を red で追加 (推奨順序 2)。
- [ ] `PreviewPlacementIdentity` 型と projector 生成を実装 (推奨順序 1・3・4)。
- [ ] 行構築と strings (en/ja)、区別補助語 copy を実装 (推奨順序 5、copy は owner review)。
- [ ] 既存 test surface (projector / corpus / DestinationRegionMapping / 既存 instrumentation) を更新して通過。
- [ ] F8 instrumentation で行区別可能性を検証し、CI run を記録 (推奨順序 6)。
- [ ] #234 との shared formatter 回帰確認 (推奨順序 7)。
- [ ] 関連 spec (194 / 195) の文書更新を同一 PR で行い、PR 本文に検証結果と spec 受入条件の対応を記録 (`Closes #208`)。

## Coordination notes

- **Issue #234 (destination 具体性) との競合回避 (Option A)**: 本 plan が identity data model と source/current 側の区別表示を導入し、#234 は destination presentation でその仕組みを利用してよい。依存は #234 → (任意) #208 のみで、#208 は #234 を待たずに着地できる。両 Issue とも `positionText` と `manual_organization_preview_position_*` strings を触るため、#208 を先に着地させる。逆順で #234 が先に着地した場合は本 plan の strings 変更を rebase 時に整合させる (execution 前に `git log` で #234 の着地を確認する)。
- 既存 instrumentation test (`previewDetailsRenderConcreteChangeListMatchingPreviewCounts` 等) は行 text を直接主張するため、本変更で期待値の更新が必要になる。更新は本 PR で行い、主張内容の弱体化 (区別可能性主張の削除) はしない。
