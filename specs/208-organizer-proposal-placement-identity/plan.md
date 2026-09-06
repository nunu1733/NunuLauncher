# Implementation Plan: Organizer proposal の各行が同一名称の複数 placement 間で対象を一意に識別する

> Issue: #208
> Spec: [spec.md](./spec.md)
> Status: in progress (spec は 2026-09-06 に owner 承認済み)
> Revision: 4 — v3 review の High 1–3 (Preserve/Warning への `current: PreviewPosition` 追加・`PreviewPosition.Unidentified` variant による total 変換・discriminator の source item 固定) と Medium 4 (descriptor 行列の table-driven 化) を反映

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
   - `PreviewPlacementIdentity` (sealed interface) を新設。variant: `Workspace(pageDisplayOrdinal, isNewPage, cellX, cellY)` / `Dock(rank)` / `FolderChild(parent, rank)` / `AppPairChild(parent, stage)` / `Unidentified(code, proposalLocalDiscriminator)`。discriminator は **source item ごとに 1 回**、決定的に採番する (v4 review High 3)。spec §Scope 1 の契約どおり。
   - `MoveChange` に `identity` (source 側) と `kind`、`PreservedChange` / `ItemWarningChange` に `identity` / `kind` / **`current: PreviewPosition`** を追加。UI は `current` を消費して位置語を描画し、identity から再計算しない (v4 review High 1)。
   - **`PreviewPosition` に `Unidentified` variant を追加**し、`PreviewPlacementIdentity -> PreviewPosition` を全 identity variant に対する total 関数にする (v4 review High 2)。`PreviewPosition.Unidentified` は proposal-local 序数 (identity discriminator と同値) を保持し、descriptor は raw ID・生 code 値でない一般序数語として描画する。
   - KDoc に一意性 invariant と有効期間契約 (proposal 内一意・非永続・serialize されない) を記録する。
2. **duplicate fixture を先に追加 (推奨順序 2)** — 失敗 test 先行:
   - §Change set の fixture 表どおり、unit property test (identity invariant) と行構築 test (表示区別要素) を、実装に先立って red で追加する。
3. **projector が identity を生成し、位置表示を identity から導出 (推奨順序 3)** — `application/preview/PlanPreviewProjector.kt`:
   - **discriminator の採番は proposal 生成時に 1 回 (v4 review High 3)**: `discriminatorBySourceItem: Map<ItemId, Int>` (または `ApplicationItemRef` key 相当) を projection 開始時に構築し、`identity(state)` は map を参照するのみ。同一 source item が action 行 (Move / Preserve) と warning 行の双方に現れても同一 identity になる。呼び出し順 counter は使わない。
   - `PositionContext` 内に `identity(state: CanonicalItemState): PreviewPlacementIdentity` を新設。`PlacementState` → identity の決定的変換。
   - **単一導出経路への統合 (v3 review High 1)**: 現行の `PositionContext.position(state)` は identity を入力とする total 関数へ置き換え (`position(of: PreviewPlacementIdentity)`)、`CanonicalItemState.placement` から位置表示を直接再生成する経路は残さない。移動行の source / destination、保持行・警告行の `current` はすべてこの関数経由とする。capture 構造を consult するのは identity 生成と、親 folder の表示 title 解決のみ。
   - `FolderChild` / `AppPairChild` の parent は `ApplicationItemRef` を `sourceItemByItemId` で解決し、parent の identity を再帰構成。planned folder parent は planned folder の intended workspace placement から構成。join miss は現行どおり `Result.Invalid`。
   - Preserve / warning 経路で `identity` / `kind` / `current = position(identity)` を `PreservedChange` / `ItemWarningChange` へ流す。`UnsupportedContainer` は `Unidentified(code, discriminator)` + `PreviewPosition.Unidentified` とする (行の欠落ではなく、理由語が既に状態を語る)。
   - `combinedPagePositions` を page 表示序数の source として再利用 (既存 `workspacePosition` と同一規約)。
4. **change 型への適用 (推奨順序 4)**: 3 と同一変更 (型追加と projector 生成は 1 つの PR ステップで通す。test は 2 が red を先に固定する)。
5. **UI presentation と copy を更新 (推奨順序 5)** — `organizer/ui/OrganizationPreviewContent.kt`:
   - **descriptor の分離 (v3 review High 3)**: source descriptor (名前 + kind + 現在位置) を構築する純粋関数 (`descriptorText(identity, kind, label, current, wording)` 等) を運命部分 (移動先・理由・警告語) の構築から分離し、unit test / instrumentation が descriptor を直接主張できる構成にする。保持行・警告行は「descriptor: 理由」、移動行は「descriptor — 移動先 (理由)」構成。
   - **位置語は change 行が運ぶ `current` / `source` / `destination` (`PreviewPosition`) を消費する**。`OrganizationPreviewContent` は capture 構造へ触れず、identity からの再計算もしない (UI に grid dimensions も親表示名も渡らないため再計算は構造的に成立しない)。
   - **同 band 別 cell / 同名 folder の表示区別要素**: identity と `PreviewPosition` の差分 (cell、親の cell) を descriptor 構築が受け、既存 vocabulary での区別が不可能な場合に補助語を付与する。`PreviewPosition.Unidentified` は raw code・discriminator 生値を表示せず、proposal-local な一般序数語で描画する。補助語 format と en/ja copy は spec AC-3 のとおり実装 PR で owner review にかける。
   - `moveRowText` / `sameBandAdjustment` 行も descriptor に kind を併記。`KindFallback` の行では kind 語を二重化しない。
   - grouping / counts / truncation / 展開の制御 flow は無変更 (行 text だけが変わる)。
6. **instrumentation (推奨順序 6)**: §Verification の AC-9 行を参照。
7. **#234 との shared projection / formatter の回帰確認 (推奨順序 7)**: coordination notes を参照。

### Data flow

入力は既存の `(ValidatedLayoutPlan, Planned)` のまま。source placement の表示は単一経路である:

```text
CanonicalItemState (placement, kind, title)
  -> PreviewPlacementIdentity          (PositionContext.identity、決定的。
                                        Unidentified の discriminator は
                                        proposal 開始時に source item ごとに採番)
  -> PreviewPosition                   (identity を入力とする total 関数。
                                        capture への再照会なし。全 variant を網羅)
  -> PlacementDescriptor (純粋行構築)  (名前 + kind + 位置。unit/instrumentation の主張対象)
  -> row text                          (descriptor + 運命部分: 移動先・理由・警告語)
```

`PreservedChange` / `ItemWarningChange` は `current: PreviewPosition` を projection に運び、`current == position(identity)` が projector invariant である。移動行の destination も同一経路 (intended placement → identity → `PreviewPosition`) であり、#234 はこの一本化された経路を消費する。エラーは既存の `Result.Invalid` (join miss) のみで、`Unidentified` は行内 fallback 表示へ落ちる。

### Alternatives rejected

- **v1 方式 (表示位置 `PreviewPosition` の追加だけ)**: 同 band 別 cell / 同名 folder × 同 rank の 2 反例で一意性が成立しないため撤回 (v1 review High 1・2)。identity (データ上の一意同定) と表示 (語彙) を分離する現方式へ改訂。
- **identity と `PreviewPosition` を capture から別々に生成する二重 source**: 同一 placement について「identity は capture placement から、位置表示は capture placement から」の 2 経路が並ぶと両者の整合が契約にならず (v2 review High 1)、単一経路 (`identity -> PreviewPosition`) へ統合。
- **行全文の不一致を主張する一意性 test**: 元の UX バグ (F-01) の状態でも移動先・理由語の差で行全文は既に異なり、反例を捕捉できない (v2 review High 3)。descriptor (source 部分) を主張対象とする。
- **表示名 / rank による folder child 同定**: 同名 folder で衝突するため不採用。parent identity + rank のみが構造的に一意。
- **条件付き kind 併記**: 行が change list 全体の cross-row 解析を要求し、行単位の検証を壊すため不採用。
- **ItemId 由来の接尾辞 / 連番**: `ItemId` は不透明 correlation key であり (#194)、placement を同定する構造情報を持たないため不採用。
- **cell 座標の常時表示**: #234 (destination 具体性) と競合する。本 spec は区別に必要な場合の補助語の導入に留め、常時 cell 表示は要求しない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` | `PreviewPlacementIdentity` 新設、`PreviewPosition.Unidentified` variant 新設、`MoveChange.identity/.kind`、`PreservedChange.identity/.kind/.current`、`ItemWarningChange.identity/.kind/.current` 追加 (KDoc に invariant と有効期間) | spec 194 projection 契約の唯一の定義点。identity data model とその派生 presentation の運搬先はここでしか導入できない |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` | `PositionContext.identity()` 新設、`position()` を identity からの導出へ置き換え (単一経路)、Preserve / warning 経路での identity・kind 設定 | placement → identity の決定的変換と、位置表示の唯一の source of truth は projector の責務 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | descriptor 分離 pure 関数の新設、保持・警告・移動行の format 拡張、同 band 別 cell / 同名 folder の区別補助語、`OrganizationPreviewWording` 追加分 | 純粋行構築 seam (#195)。UI composable 側は変更不要 |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 移動行 / 保持行 / 警告行 format の拡張、区別補助語 format | #123 契約 (en/ja 両方) |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` (+ 新規 identity property test) | identity 生成・一意性 invariant (全 fixture)、`current == position(identity)` invariant、`Unidentified` / `PreviewPosition.Unidentified` 経路、F9 (同一 unsupported item の行跨ぎ identity 同一性)、既存 Invalid 経路の維持 | projection 契約の unit 正本 |
| `tests/unit/app/lawnchair/organizer/ui/OrganizationPreviewContentTest.kt` | descriptor 分離関数の直接主張 (同名同 kind の distinct identity で descriptor 不一致)、保持行・警告行・移動行の新 format、区別補助語、KindFallback 非二重化 | 行構築契約の正本 test |
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
| F5 | home icon と folder child が同名 (Move / Preserve に跨る) | bucket を跨いでも identity が別、関係が行から説明できる。**`MoveChange` と `PreservedChange` の identity 集合が排他**であることを明示主張 (v3 review Medium 5) | unit + row test |
| F6 | widget / folder unit / app pair / dock の同名混在 | kind 併記による区別、identity が各 container 種別で成立 | unit + row test |
| F7 | title 無し (KindFallback) item の混在 | fallback 行でも identity・位置・kind 構成が欠落しない | unit row test |
| F8 | 全種別 + 同名の統合 proposal | instrumentation で各行が source placement を区別する表示要素 (kind 語・位置語) を実際に持つことを descriptor ベースで主張する — **行全文の不一致主張では元の UX バグを捕捉できないため行わない** (v3 review High 3)。Move / Preserve identity 排他も主張 (終了条件 2) | instrumentation (API 36) |
| F9 | **同一 unsupported source item が Preserve 行 + Warning 行の双方に現れる** (v4 review High 3) | 両行の `PreviewPlacementIdentity` が同一であること (discriminator が source item 固定であることの直接固定)。counter 方式だと破れる境界 | unit property test |
| F10 | **同名・同 kind・同 unsupported code の別 item 複数** (v4 review High 2) | identity は別 (discriminator)、descriptor は proposal-local 一般序数語で区別される。raw code / 生 ID は表示に現れない | unit property + row test |

## Migration and recovery

- schema / rule migration: なし。`PreviewChange` / identity は process-local な projection であり、serialize / persist されない (#194 契約)。field 追加は release rollback 時にも互換性問題を生まない。
- failure 中の rollback: 適用系に触れないため対象外。
- backup/restore compatibility: 対象外 (永続化データなし)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (identity 導入・shape 不変) | `PlanPreviewProjectorTest` 更新 + corpus / counts contract test 通過 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` |
| AC-2 (identity invariant + bucket exclusivity) | F1–F4 を含む identity property test、F5 / F8 の `MoveChange`・`PreservedChange` identity 排他主張、F9 の行跨ぎ identity 同一性主張 | 同上 |
| AC-3 (descriptor 一意性, table-driven) | AC-3 行列 (同 band 別 cell、同名 folder、dock 別 rank、同一 folder 内別 rank、app pair 別 stage、F10 unsupported duplicate) の row test を descriptor 分離関数への直接主張で実装 (copy 確定は実装 PR review) | 同上 |
| AC-4 (保持行 format・単一導出経路) | `OrganizationPreviewContentTest` 更新 + `current == position(identity)` invariant の unit 主張 + `position()` が identity のみを入力とすることの unit 主張 (capture 再照会経路の不在) | 同上 |
| AC-5 (folder child 親区別・locks 語彙) | F4 row test + locks 画面語彙一致の LQA 記録 | unit + manual |
| AC-6 (kind 併記・非二重化) | F6 / F7 の row test | 同上 |
| AC-7 (有効期間・非永続) | 契約 review + identity 型が Parcelable/Serializable でないことの確認 | code review |
| AC-8 (決定性・privacy) | 既存 projector / corpus / `DestinationRegionMappingTest` 通過 | 同 unit test |
| AC-9 (UI レベル区別可能性) | F8 instrumentation test (descriptor ベースの主張) + CI `organizer-instrumentation` job の run URL を PR 記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest` / CI (API 36) |
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

## Change history

- 2026-09-06: v2 — v1 review (Request changes) を受け、canonical placement identity 導入方式へ改訂。
- 2026-09-06: v3 — v2 review の反映: source/current 位置表示を `identity -> PreviewPosition` の単一経路へ統合し、Plan 側の「現行 `PreviewPosition` から導出」との食い違いを解消 (High 1)。`Unidentified` に proposal-local discriminator を追加し invariant を universal 化 (High 2)。受入 test を descriptor (source 部分) 主張へ寄せ、行全文不一致主張の誤 PASS を排除 (High 3)。F5 / F8 に Move / Preserve identity 排他の明示主張を追加 (Medium 5)。
- 2026-09-06: v4 — v3 review の反映: `PreservedChange` / `ItemWarningChange` に identity 導出の `current: PreviewPosition` を追加し `current == position(identity)` を invariant 化 (High 1)。`PreviewPosition.Unidentified` variant を追加し identity → `PreviewPosition` を total 関数化、unsupported duplicate の descriptor 区別語を契約に含めた (High 2)。discriminator を source item 固定の proposal-local ordinal に変更し、F9 で同一 unsupported item の行跨ぎ identity 同一性を固定 (High 3)。AC-3 を identity variant 全体の table-driven 行列へ一般化し F10 を追加 (Medium 4)。
