---
issue: "#208"
status: draft
requirements: []
risk: []
updated: 2026-09-06
---

# Organizer proposal の各行が同一名称の複数 placement 間で対象を一意に識別する

## Problem

[Exploratory UX review](https://github.com/nunu1733/NunuLauncher/blob/main/.ux-review/ux-exploratory-review-2026-09-05.md) (2026-09-05, F-01 / major・high confidence) で、Organizer proposal の確認画面において同名の配置アイテムが Move と Preserve の両方に無区別で現れ、提案が自己矛盾に見える問題が観測された (Issue #208)。

実装の構造的な原因は 2 点である。

1. **`PreservedChange` は位置情報を一切 projection に載せていない** (`PlanPreview.kt` の `PreservedChange(item, label, reason)`)。保持行は `“ラベル”: 理由` の format (`manual_organization_preview_item_row`) だけで描画され、ホーム上の icon か folder 内 item か folder unit か widget かを画面から判別できない。
2. **種別 (kind) は表示名が存在する限り行に現れない**。`PreviewLabel` は `Named(value)` か `KindFallback(kind)` であり、title を持つ item は常に名前だけで描画される。同じ “Google” が app icon / folder unit / widget として存在しても行文言は同一になる。

さらに起草 v1 の review で、表示語彙の追加だけでは不十分であることが確定した。**表示位置 (coarse `PreviewPosition`) と placement の同一視は、次の 2 反例で成立しない**:

- 同名・同 kind・同 page・同 3×3 band 内の別 cell に home icon が 2 つある場合、`page 2 top left` 表現は両行で同一になる (4×6 grid では `top left` band が 4 cell を含む。#212 assessment の counterexample)。
- 同名 folder が複数ある場合、「フォルダ “X” の 1番目」は衝突する (page 1 の “Google” folder と page 2 の “Google” folder の rank 1 が同名 child を持つ)。

よって本 spec は、行が指す placement をデータ上で一意に表す **canonical placement identity** を projection に導入し、表示をそこから導出する構成へ改訂した (v1 review 指摘 1–5 の反映)。

なお、destination (移動先) 表示の cell 具体性は [Investigation #212](https://github.com/nunu1733/NunuLauncher/issues/212) が Case A と判定し、[Issue #234](https://github.com/nunu1733/NunuLauncher/issues/234) へ handoff 済みである。本 spec と #234 の責務境界は §Relationship to #234 に定める。

## Outcome

確認画面の全変更行 (移動・保持・警告・新規フォルダ) が、同一 app 名の複数 placement (ホーム上の icon、folder 内 item、folder unit、widget、app pair、dock) の間で曖昧にならない。各行は canonical placement identity を根拠に描画され、**1つの proposal 内で、行から指される placement が一意に判別できる**。ユーザーは proposal 画面の文言だけで、任意の app について「その運命 (移動元・移動先 / 保持理由)」を正しく、各行に対応付けて述べられる。同一 placement が Move と Preserve の両 bucket に現れることはなく、同名 item が異なる運命を持つ場合も (例: ホームの icon は移動、folder 内 item は保持) 両行が区別できる。

## Scope

### 1. Canonical placement identity の導入 (contract の中核)

projection (`PlanPreview.kt`) へ、行が指す placement を表す sealed interface `PreviewPlacementIdentity` を追加する。これは **identity data model** であり、localized string を含まない。variant は capture model の container 種別と対応する:

```kotlin
sealed interface PreviewPlacementIdentity {
    /** ホーム画面上の配置アイテム。page は proposal 内で一意な表示序数
     *  (persistent + planned を統合した PageOrder ソート由来、既存
     *  PositionContext と同一規約) で同定し、raw page id は保持しない。
     *  cell は anchor 座標 (grid 座標系、span 不含)。cell 座標は identity
     *  の構成要素としてのみ保持し、表示は formatter が導出する。 */
    data class Workspace(
        val pageDisplayOrdinal: Int,
        val isNewPage: Boolean,
        val cellX: Int,
        val cellY: Int,
    ) : PreviewPlacementIdentity

    /** Dock の slot。rank は source capture の dock 序数。 */
    data class Dock(val rank: Int) : PreviewPlacementIdentity

    /** folder 内 item。親 folder を placement identity で表す (表示名ではない)。
     *  親が planned folder の場合はその intended workspace placement から
     *  Workspace identity を構成する。 */
    data class FolderChild(val parent: PreviewPlacementIdentity, val rank: Int) : PreviewPlacementIdentity

    /** app pair 内 item。 */
    data class AppPairChild(val parent: PreviewPlacementIdentity, val stage: SplitStage) : PreviewPlacementIdentity

    /** container 種別が capture 上未対応の場合 (UnsupportedContainer)。 */
    data class Unidentified(val code: ContainerCode) : PreviewPlacementIdentity
}
```

`SplitStage` / `ContainerCode` は capture model (`CanonicalItemState.placement`) が既に public projection で露出している型である。identity 一意性に対する page 表示序数の十分性: `combinedPagePositions` は決定的であり、1 proposal 内で 2 つの異なる page が同じ表示序数を持たないため、page 同定は proposal 内で一意である (有効期間契約と整合)。

要件:

- **identity 一意性 invariant**: 同一 proposal (`PlanPreviewDetails`) 内で、2 つの行が同じ container 種別の同一 variant かつ等しい identity を持つのは、両行が **同一 placement (同一 source item)** を指すとき、かつそのときに限る。すなわち `distinct source placements ⇒ distinct identities`。fixture test でこの invariant を property として検証する。
- **同値の定義**: identity の同値は capture の placement 構成要素 (container 種別 + page + cell / dock rank / parent identity + rank / pair + stage) の構造的同値であり、表示名・kind・localized copy を含まない。
- **有効期間 (review 指摘 4)**: placement identity は **1 回の projected proposal の内部で一意かつ安定**である。proposal 再計画 (再 capture / 再 materialize) 後の identity 値の安定性、lock / strategy 変更後の追跡は要求しない。永続 launcher item ID ではない。process-local であり serialize / persist されない (#194 契約の継続)。

### 2. 既存 change 型への適用

- `MoveChange` に `identity: PreviewPlacementIdentity` (source 側) と `kind: CanonicalItemKind` を追加。
- `PreservedChange` に `identity: PreviewPlacementIdentity` と `kind: CanonicalItemKind` を追加。
- `ItemWarningChange` に `identity: PreviewPlacementIdentity` と `kind: CanonicalItemKind` を追加。
- `PreviewChange` の variant 集合は変更しない。全追加は data class の field 追加であり、既存消費者 (行構築、counts 導出、既存 test) に対して非破壊である。

### 3. 表示 (presentation) は identity から導出する

行構築 (`OrganizationPreviewContent`) は identity → presentation → localized copy の一方向で行 text を組み立てる (review 指摘 5)。表示語彙の要件:

- **保持行・警告行**: 「名前 — kind — 現在位置 — 理由」構成へ拡張。現在位置は identity から formatter が導出し、生 cell / 生 ordinal を表示しない。
- **移動行**: source 側の記述は source identity から導出する。
- **kind 併記は全変更行で常時行う**。`KindFallback` の行では label が既に kind 語であるため二重化しない。
- **identity が表示で区別できない場合の下限**: identity が別でも coarse 表示語 (page + 3×3 band 等) が同一になる同 band 内別 cell ケースは、本 spec では「identity は一意だが表示語は衝突しうる」状態を許容しない — **同名・同 kind・同 page・同 band の別 cell が同一 proposal に共存する fixture で、行の区別に必要な表示情報が存在すること**を受入条件とする (AC-2)。区別に使える表示要素は既存 vocabulary 内の page 序数 / 領域語 / 行序数に加え、必要なら identity 由来の補助語 (同 band 内の何番目等) であり、その選択は実装 PR で en/ja copy とともに owner review にかける。cell 座標そのものの常時表示は要求しない (#234 と競合するため、この表示強化は identity が一意であることの見え方の下限保証に留める)。
- **folder child**: 「フォルダ “名前” の N番目」に加え、同名 folder が共存する場合に親 folder を区別できる表示要素 (親 folder の現在位置語) を identity から導出して付与する (AC-5)。親 folder の位置語が同一になるケース (同名 folder × 同位置語) も fixture 対象であり、この場合の補助語も前項と同じく実装 PR で確定する。

### 4. Projector による identity 生成

- identity は `plan.sourceState` の `CanonicalItemState.placement` (`Workspace(page, cell)` / `Dock(rank)` / `FolderChild(parent, rank)` / `AppPairChild(parent, stage)` / `UnsupportedContainer(code)`) から決定的に生成する。parent は `ApplicationItemRef` を source item lookup で解決し、parent の placement identity を再帰的に構成する。join miss は現行どおり `Result.Invalid` (fail-closed)。
- position 表示 (`PreviewPosition`) との関係: 既存 `PreviewPosition` は表示導出の入力として維持する。identity は「どの placement か」の同定、`PreviewPosition` は「どこにあるか」の語彙であり、両者を同一視しない。identity から position 表示を導出する単一経路を projector 内に置き、formatter が capture 構造へ直接触れない。

## Relationship to #234 (責務境界 — review 指摘 3)

review 指摘 3 の **Option A** を採用する。

- **本 spec (#208)**: current / source placement の **identity data model と一意性 invariant** を導入する。identity の型、projector での生成、行への適用、identity 由来の区別表示の下限が本 spec の成果である。
- **#234**: destination (移動先) 表示の cell 具体性 (`visibleCandidates(rendered destination, grid) == {resolved anchor}`) を destination copy / presentation の変更で達成する。#234 は本 spec が導入する identity と page / cell ref の仕組みを destination 側で利用してよいが、本 spec は #234 の完了に依存しない。
- 依存方向は #234 → (任意) #208 のみであり、#208 が #234 を待たずに着地できる。両 Issue が `positionText` / `manual_organization_preview_position_*` strings を共有する競合は plan の coordination notes に従い、#208 を先に着地させる。

## Non-goals

- destination 表示の cell 具体性そのもの (`visibleCandidates == {resolved anchor}` の達成) — #234 の scope。本 spec が要求するのは source/current 側の identity 一意性と、同名 placement を区別する表示の下限までである。
- `NewFolderChange` の member label 一覧の identity 化。新規フォルダの member は全員が同一運命 (同一 new folder への移動) を持つため、member 行の運命説明に曖昧さは生じない。member 一覧内の同名 label は F4 fixture (同名 folder × 同 rank × 同名 child) の対象外であり、行レベルの identity 契約 (Move/Preserve/Warning 行) のみが対象である。
- planner / application 契約・materializer・`inspectPlan` coordinator 契約の変更。identity は projector が既に持つ `plan.sourceState` から導出する。
- identity の永続化、proposal 間での安定化、lock / strategy 変更追跡 (§1 の有効期間契約)。
- proposal UI の visual before/after preview。
- `Placement locks` 画面自体の変更 (語彙の揃いは LQA 確認項目のみ)。
- 新規 state、新 `PhaseCode`、新 diagnostics の追加。

## Domain language

`CONTEXT.md` への追加は不要とする。「placement 識別」は既存の「配置 (Placement)」の観測可能表現の問題であり、新しい domain 概念を導入しない。`PreviewPlacementIdentity` は projection の型名であり、UI 表示層の実装語である。

## Behavior scenarios

### Scenario: Same-named home icon and folder child are both preserved

Given capture に “Gmail” という名称の 2 つの配置アイテム (ホーム page 2 の icon と folder “Utilities” 内 item) が含まれ、planner が両方を保持 (`NON_TARGET` と `LOCKED`) と判断した,

When 確認画面が描画される,

Then 保持 group の 2 行はそれぞれ kind 語と現在位置を持ち、「“Gmail” (アプリ) — ホーム画面 page 2 の «領域» — 整理対象外のため保持」「“Gmail” (アプリ) — フォルダ “Utilities” の N番目 — ロックされているため保持」のように区別できる,

And 2 行は source placement の identity が別である (同一 placement の複数行ではない)。

### Scenario: Same-named, same-kind icons inside one 3x3 band on one page

Given 同一 page の同一 3×3 band 内の別 cell に、同名・同 kind の home icon が 2 つ存在し、両方が保持行に含まれる,

When 確認画面が描画される,

Then 2 行の identity は別である (cell を含む identity が別 anchor を指す),

And 行文言も区別に必要な表示要素を持つ (identity 由来の補助語、または既存 vocabulary 内の区別要素)。名称・kind・coarse 位置語のみで同一文言の重複は発生しない。

### Scenario: Same-named folders with same-rank same-named children

Given 同名 folder “Google” が 2 つ (page 1 と page 2) 存在し、双方の rank 1 に同名 child “Gmail” がある,

When 両 child が保持行に含まれる,

Then 2 行の identity は親 folder の placement identity (page / cell を含む) が別であるため別である,

And 行文言には親 folder を区別する表示要素 (親 folder の現在位置語) が現れる。

### Scenario: Same-named home icon moves while folder copy is preserved

Given “Photos” の home icon が Move に、同名の folder 内 item が Preserve に含まれる,

When 確認画面が描画される,

Then Move の行と Preserve の行は identity が別であり、ユーザーは「home の icon は移動、folder 内 item は維持」と画面内で説明できる,

And 同一 placement (同一 source item) が両 bucket に現れることはない (conservation 不変条件の表示への投影)。

### Scenario: Same-named widget and app icon are distinguished by kind and identity

Given “Google” という名称の app icon、widget、folder unit が保持行に含まれる,

When 確認画面が描画される,

Then 3 行は kind 語 (アプリ / ウィジェット / フォルダ) と位置語で区別され、名称のみの同一文言の重複は発生しない。

### Scenario: Full placement-type fixture stays unique and readable

Given fixture に icon / folder unit / folder child / widget / app pair / dock の全 placement 種別を含む proposal が与えられ、うち同一名称の item が複数の placement を持つ,

When 確認画面が描画される,

Then 全行が「名前 — kind — 位置 — 運命」の組合せとして人間が区別でき、identity の一意性 invariant (distinct placements ⇒ distinct identities) が property test として成立する,

And truncation / 展開 (#195 D6) と group 見出し件数 (`PreviewCounts` truth) の既存契約は退化しない。

### Scenario: Kind-fallback rows inherit the same identity wording

Given title を持たない item (KindFallback) が保持行に含まれる,

When 確認画面が描画される,

Then 行は kind 語と位置語を持ち、`Named` の行と同一の identity 構成を満たす (fallback 行だけ identity・位置・kind が欠落しない)。

### Scenario: Projection invariants are preserved

Given 任意の plan / planned の組合せ,

When projector が details を生成する,

Then identity・位置・kind は `plan.sourceState` から決定的に導出され、出力は byte-equivalent に再現される,

And identity の同値に表示名・localized copy は関与せず、projection は raw package / component / digest / profile identity を露出しない (spec 194 privacy 契約の継続。page / cell / rank は identity 構成要素としてのみ保持され、表示は formatter が導出する)。

## Data and state

- 読む data: `ValidatedLayoutPlan` (sourceState の placement / kind) と planner `Planned` (disposition / reason / warnings)。既存の projector 入力のみであり、新たな capture / I/O を追加しない。
- 永続化、migration、backup/restore、Launcher DB への影響は **なし** (zero-write の表示層拡張)。identity は process-local な projection の field であり serialize されない。
- `PlacementState` と `CanonicalItemState.kind` は capture model の既存 field であり、plan へ新規 field を追加しない。

## Permissions, privacy, and security

None。新たな permission、network、telemetry、export は追加しない。identity は page / cell / rank / stage 等の placement 構成要素のみを保持し、package / component / digest / profile serial 等の raw identifier は spec 194 契約どおり非露出とする。identity が cell 座標を保持することは「表示への露出」ではなく、表示文言は formatter の導出に従う。

## Accessibility and localization

- 変更行は単一の意味ある node として読め、kind + 位置を含む行 label が自然な語順で読み上げられる (#195 a11y 契約の継承)。`liveRegion` の配置は現行 (status 行のみ) を維持する。
- 行文言の長化に伴い、200% font scale で wrap し切抜き・横 scroll 依存がない。
- 追加・変更 strings はすべて `values/` + `values-ja/` へ置く (#123: 日本語実行時の英語 fallback 禁止)。`manual_organization_preview_item_row` を拡張する場合、既存 ja 文言の語順 (「«名前»»: «理由»») を崩さない。
- Placement locks 画面との語彙一致 (folder 内位置表現) を ja / en 両 locale で LQA 確認する。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | projection に `PreviewPlacementIdentity` が導入され、`MoveChange` / `PreservedChange` / `ItemWarningChange` が identity と kind を持つ。identity は localized string を含まない。`PreviewChange` の variant 集合と `PlanPreviewDetails` / `PreviewCounts` の shape は不変である。 |
| AC-2 | identity 一意性 invariant「同一 proposal 内で distinct source placements ⇒ distinct identities」が、同名・同 kind・同 page・同 3×3 band 内の別 cell、同名 folder × 同 rank × 同名 child を含む fixture で property test として検証される。 |
| AC-3 | 表示の下限: 同名・同 kind・同 band 内別 cell fixture と同名 folder × 同位置語 fixture で、行文言が区別に必要な表示要素を持つ (名称・kind・coarse 位置語のみの同一文言の重複が発生しない)。表示要素の採用 (identity 由来補助語等) は en/ja copy とともに実装 PR で owner review にかける。 |
| AC-4 | 保持行は「名前 — kind — 現在位置 — 保持理由」の全要素で描画され、位置語は identity から formatter が導出する単一経路であり、生 cell / 生 ordinal は表示へ現れない。 |
| AC-5 | folder child 行は親 folder を区別できる表示要素 (親 folder の現在位置語) を持ち、Placement locks 画面の folder 位置語彙と語彙系統が一致する (en/ja)。 |
| AC-6 | kind 併記が全変更行で行われ、`KindFallback` の行で二重化しない。 |
| AC-7 | identity は 1 回の projected proposal 内で一意かつ安定であり、永続 launcher item ID ではない。process-local であり serialize / persist されない。 |
| AC-8 | projection の決定性と byte-equivalent 再現が既存 property/contract test 表面で維持される。projection は spec 194 の privacy 契約を満たし続ける。 |
| AC-9 | 全 placement 種別 + 同名 placement fixture の instrumentation test (API 36 / Platform 36.1) が CI で成功し、行ごとの区別可能性が UI レベルで検証される。 |
| AC-10 | TalkBack / Switch Access / 200% font scale で変更後の行が読める (既存 a11y instrumentation の無変更通過と、拡張行の読み上げ確認)。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-7 | `PlanPreview.kt` 契約 review + `PlanPreviewProjectorTest` (identity 生成、serialize 不可能性は型で保証) |
| AC-2 | 新規 property test: 全 placement 種別 × 同名 × 同 band 別 cell / 同名 folder × 同 rank fixture で distinct placements ⇒ distinct identities を主張 |
| AC-3 | `OrganizationPreviewContentTest`: 同 band 別 cell fixture・同名 folder fixture で行文言の区別要素を主張 (copy 確定は実装 PR review) |
| AC-4, AC-6 | `OrganizationPreviewContentTest` 更新 (保持行 format、kind 併記、KindFallback 非二重化、単一導出経路) |
| AC-5 | folder child 表示の unit test + locks 画面語彙一致の LQA 記録 (manual) |
| AC-8 | 既存 `PlanPreviewProjectorTest` / `PlanPreviewCountsCorpusContractTest` / `DestinationRegionMappingTest` の無変更 (または最小更新) 通過 |
| AC-9 | `ManualOrganizationPreferencesInstrumentationTest` 新規 test + CI run URL を PR へ記録 |
| AC-10 | 既存 a11y instrumentation test 群の通過 + 拡張行の semantics 確認 |

## Open questions

None。spec 時点で確定した判断は §Scope 1–4 と §Relationship to #234 のとおり。同 band 別 cell の表示補助語と copy は、identity 一意性 invariant を満たす範囲で実装 PR にて en/ja copy とともに owner review にかける (非 blocking: invariant 自体は本 spec で確定済み)。

## Change history

- 2026-09-06: Drafted for Issue #208。UX review F-01 の行アイデンティティ問題を、#234 (destination 具体性) と棲み分けた上で、projection additive 拡張 (PreservedChange 位置/kind、folder 内 rank、kind 併記) として起案。
- 2026-09-06: Owner review revision (Request changes 反映): 表示位置と placement の同一視を撤回し、canonical `PreviewPlacementIdentity` の導入と一意性 invariant (distinct placements ⇒ distinct identities) を契約の中核へ変更 (High 1)。folder child identity を親 folder の placement identity + rank で定義 (High 2)。#234 との責務境界を Option A (identity data model は #208、destination presentation は #234、依存は #234→#208 のみ) として明記 (High 3)。identity の有効期間 (proposal 内一意・非永続) を定義 (Medium 4)。identity → presentation → localized copy の一方向を明記 (Medium 5)。AC を identity invariant ベースへ再構成し、反例 fixture (同 band 別 cell、同名 folder × 同 rank) を test oracle へ追加。

## References

- [Issue #208: Organizer proposalの同名placementがMoveとPreserveの両方に無区別で現れ、提案が自己矛盾に見える](https://github.com/nunu1733/NunuLauncher/issues/208)
- [Issue #208 review comment (Spec/Plan review — Request changes, 2026-09-06)](https://github.com/nunu1733/NunuLauncher/issues/208#issuecomment-IC_kwDOTy-DFc8AAAABS0STow)
- [Spec 194: read-only plan preview and PreviewChange projection contract](../194-plan-preview-seam/spec.md)
- [Spec 195: Organizer confirmation change list (行構築・grouping・truncation 契約)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 212 / assessment: destination 表記 investigation (Case A 判定と R1 handoff)](../../docs/assessment/issue-212-organizer-destination-verification.md)
- [Issue #234: destination 表示を resolved anchor 一意の具体性へ更新 (併走 Issue)](https://github.com/nunu1733/NunuLauncher/issues/234)
- [Spec 123: organizer UI convergence (日本語 fallback 禁止契約)](../123-organizer-ui-convergence/spec.md)
- [Spec 52: manual full-organization vertical slice (a11y 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
