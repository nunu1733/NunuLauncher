---
issue: "#234"
status: draft
requirements: [R1-ANCHOR-UNIQUENESS, R2-EQUALITY-NONREGRESSION, R3-BRANCH-STABILITY, R4-SINGLE-DERIVATION, R5-LOCALE-COPY, R6-A11Y-AND-PRIVACY]
risk: []
updated: 2026-09-07
---

# Organizer proposal card の destination 表示を resolved anchor を一意に識別できる具体性へ更新

## Problem

[Issue #234](https://github.com/nunu1733/NunuLauncher/issues/234)。Investigation #212 の Case A 結論 ([assessment](../../docs/assessment/issue-212-organizer-destination-verification.md)) により、Organizer proposal card の preview destination / apply destination / persisted placement は 1 つの resolved placement を共有しており (R2 PASS)、機能的 mismatch は存在しない。しかし card が表示する destination は 3×3 row/column band label (`top left` / `上段左` 等) + page 序数 + (条件付きの) 行序数 note のみである。

band rule `floor(coord * 3 / dimension)` (start cell 基準) は、4-column grid の `top left` band に anchor cell 4 個 {(0,0), (1,0), (0,1), (1,1)} を含む。異なる resolved anchor を持つ 2 item が同一 proposal 上で同じ destination text (例: `top left, page 2`) を受け取り、行序数 note は同 band 内の row 変化時しか出ないため、column 差は表示から完全に落ちる。つまり:

```text
visibleCandidates(rendered destination, grid) != { resolved anchor }
```

spec #212 R1 未達であり、ユーザーは accept 前に移動先 cell を特定できない。#195 D5 (帯内微調整の text-only 表現は十分) の residual-risk 判断は、この evidence によって更新を要求される。

## Outcome

確認画面の各移動行の destination 部分が、現行 grid 上で resolved anchor をちょうど 1 個に絞り込める具体性を持つ。ユーザーは proposal を accept する前に、各 item が実際に着地する page と cell を表示だけから一意に判別できる。表示は生 cell 座標 (`cellX`/`cellY` の raw 値) を露出せず、1-based の行/列表示序数と既存の領域語の組合せで表現する。#212 で確定した placement equality (preview == apply == persisted) と R5 branch stability は無変更で維持される。

## Scope

- **移動行の destination 表示**を、band label 単独から anchor 一意の表現へ更新する。対象は `MoveChange.destination` を描画する分岐 (通常 move 行、same-band adjustment 行) である。
  - **実現方法は destination 専用の formatter 経路に限る**。`positionText` は source descriptor (`descriptorText`)、保持行・警告行の `current` 位置、`descriptorSupplements` の collision 判定、`NewFolderChange.placement` と共有されており、ここへ anchor 具体性を織り込むと「coarse position が衝突した行だけ identity 補助語を付与する」という #208 の source descriptor 契約が意味論ごと変わる (owner review 指摘 1)。したがって destination 専用の組立て経路を追加し、移動行の destination 描画のみで使用する。
  - **表示要素の契約 (en/ja 共通、owner review 指摘 4 により accepted 前に確定、再レビュー指摘で統一)**: workspace destination (通常 move 行 / same-band adjustment 行のいずれも) は **page 語 + 領域語 + 1-based row ordinal + 1-based column ordinal** を常時表示する。same-band 行でも page を省略しない — same-band は page 内の band 調整を指すが、R1 の正本は「final resolved **page** + anchor cell」の一意識別であり、same-band の省略を許すと異なる page の same-band 調整が同一 destination 語になる (re-review counterexample)。grid や衝突の有無に依存する条件付き省略は設けない。
  - exact `(cellX, cellY)` 文字列は必須としない (Issue 受入条件)。上記の要素集合は `(page, row, column)` が grid 上で anchor をちょうど 1 個に絞るため、anchor 一意性を構造的に満たす。**同一 proposal 内で異なる resolved placement を持つ 2 item が destination 部分だけで区別できない表示を残してはならない**。
  - same-band adjustment 行 (`«領域» 内で位置を調整 (from row X to row Y)`) も、行序数 note が効かない純粋な列方向調整 (同 band・同行序数) で destination 候補が複数残らない形へ更新する。これは #195 D5 の残余リスク「同帯・同行序数の列方向調整は before/after が同一文言」を解消する。
- **projection の additive 拡張**: `PreviewPosition.Workspace` が保持する page ordinal / rowBand / columnBand / rowOrdinal のデータ範囲では column specific 性を表現できないため、1-based **column ordinal** (`cellX + 1`) を projection に追加する ([spec 194](../194-plan-preview-seam/spec.md) projection 契約の additive 変更、[spec 208](../208-organizer-proposal-placement-identity/spec.md) §Relationship to #234 が許諾する範囲)。formatter は `PreviewPosition` からのみ表示を組み立て、独自の座標再計算を行わない (Issue 受入条件)。
  - identity (`PreviewPlacementIdentity.Workspace`) は既に `cellX` / `cellY` を保持しており、identity 契約の変更は不要である。`position(identity)` の total function は維持する。
- **翻訳**: 追加・変更する strings は `values/` と `values-ja/` の両方へ置く (#123 契約)。表示する要素集合 (§Scope の契約) は en/ja で同一である。
- **既存 branch stability (R5) と rendering instrumentation** の維持 (Issue 受入条件)。

## Non-goals

- planner algorithm・strategy heuristic の変更。resolved placement の生成に手を入れない (R2 の一致は既に保証済み)。
- destination mismatch の fix (mismatch は存在しないことが #212 で確定済み)。
- placement equality の再検証自体 (既存 `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` が無変更で通り続けることを regression boundary として要求するのみ)。
- destination 以外の行部分 (source descriptor、理由語) の再設計。#208 の descriptor 契約は無変更。
- UI レイアウト全体の再設計、visual before/after preview (#195 D5 の Optional な将来拡張として残る)。
- 保持行・警告行の `current` 位置語の変更 (本 Issue は destination の具体性が主題。保持行は「移動しない」ことの表明であり anchor 一意性契約の対象ではない)。
- 新規フォルダ行 (`NewFolderChange.placement`) の placement 語の anchor 具体化。行は解決済みフォルダ名で識別され (#201 契約)、spec 208 が `NewFolderChange` を identity 契約の対象外としていることに揃え、destination 専用経路の適用対象は移動行に限定する。同一 proposal に同 band 内の別 cell へ配置された複数の新規フォルダが現れた場合は placement 語が同一文言になる residual として許容し、観測された時点で別 Issue として起票する。

## Domain language

- **anchor 具体性 (anchor specificity)**: 表示が current grid 上の resolved anchor を候補集合として一意に絞り込める度合い。本 spec の受入基準は spec #212 が定義した `visibleCandidates(display, grid) == { resolved anchor }` である。
- **表示序数 (display ordinal)**: 生座標でなく 1-based の行/列位置を表す表示値。既存 `rowOrdinal` (= `cellY + 1`) と同型の概念を column 方向へ拡張したもの。`CONTEXT.md` への追加は実装 PR で判定する (既存の「表示序数」語は spec 208 が補助語の文脈で使用済みであり、domain 語としての新規性は薄い)。

## Behavior scenarios

### Scenario: 同一 band 内の別 column への移動が destination だけで区別できる

Given 4-column × 6-row grid で、item a が page 2 の (0,0) へ、item b が page 2 の (1,0) へ resolved された proposal が表示されている (両者とも `TOP` × `LEFT` band、同一 row ordinal),

When 確認画面の移動行を描画する,

Then item a と item b の destination 部分の表示は異なり、各表示から解釈できる destination 候補集合は `{ (0,0) }` / `{ (1,0) }` として 1 個になる,

And いずれの行にも生 cell 座標値 (`cellX=0` 等の raw 値) や `ItemId` は現れない。

### Scenario: 純粋な列方向の帯内調整が before/after を区別できる

Given 同一 page・同 row band・同 column band・同一 row ordinal 内で列方向のみ移動する move が proposal に含まれる (`sameBandAdjustment == true` かつ row ordinal 不変),

When 確認画面の same-band adjustment 行を描画する,

Then 行は調整の発生を告知し (現行契約どおり)、かつ destination 部分の表示 (page + 領域 + 行表示序数 + 列表示序数) から解釈できる anchor 候補が 1 個になる,

And #195 D5 の残余リスク「同帯・同行序数の列方向調整は before/after が同一文言」は当該行の destination 部分については残らない。

### Scenario: 異なる page の same-band 調整が destination だけで区別できる

Given 同一 proposal 内に、同一 row ordinal・同一 column ordinal・異なる page への same-band adjustment が 2 つ含まれる (例: A は page 1 の (0,0) → (1,0)、B は page 2 の (0,0) → (1,0)),

When 確認画面の 2 行を描画する,

Then 2 行の destination 部分の表示は異なり (page 語の差)、各表示から解釈できる anchor 候補集合は `{ (page 1, (1,0)) }` / `{ (page 2, (1,0)) }` として 1 個になる,

And 同一 proposal 内で異なる resolved placement が destination 部分だけで区別不能になる表示を残さない (R1)。

### Scenario: 既存の行序数 note 分岐は意味を保つ

Given 同一 page 内で row band が不変・row ordinal が変化する move が proposal に含まれる,

When 確認画面の移動行を描画する,

Then 既存の `(from row X to row Y)` note 分岐は現行どおり機能し、表示は anchor 一意の具体性を満たす,

And 同じ resolved move data・grid・locale 条件に対する出力は deterministic である (R5)。

### Scenario: grid 幅が変わっても anchor 一意性が保たれる

Given 2-column / 5-column 等の異なる column 数の grid で proposal が表示されている,

When 確認画面の移動行を描画する,

Then いずれの grid でも `visibleCandidates(rendered destination, grid) == { resolved anchor }` が成立する (band 幅の grid 依存性は表示の具体性を損なわない),

And 表示語彙は生座標を現さない。

### Scenario: destination 専用経路であり source descriptor は不変である

Given 同一 proposal 内に、coarse source position (page + band) が衝突する行と衝突しない行が混在する,

When 確認画面の移動行を描画する,

Then destination の具体性は destination 専用の formatter 経路のみで付与され、source descriptor (名前 + kind + 現在位置) の語彙と #208 collision 補助語の付与条件 (collision-local) は現行契約どおりである,

And destination 具体性の追加によって #208 の descriptor 契約上の衝突判定が変化しない。

### Scenario: placement equality は無変更で維持される

Given 既存の placement equality characterization test 群が実行される,

When destination copy の変更を含む本 Issue の実装が適用される,

Then `PreviewApplyPlacementEqualityTest` と `PreviewApplyPersistedPlacementEqualityTest` は無変更 (または projection 型変更への機械的追従のみ) で pass し、preview / apply / persisted の cell-exact 一致が維持される,

And destination copy の変更が resolved placement を一切動かしていないことが evidence から確認できる。

### Scenario: ja locale で同等の具体性を持つ

Given device locale が ja の状態で確認画面が表示されている,

When 移動行の destination 部分を確認する,

Then 日本語 copy が en と同等の anchor 一意性を提供し (日本語実行時の英語 fallback は発生しない、#123 契約)、placeholder の数と順序が en/ja で一致する。

## Data and state

- 読む data: `PreviewPosition` (destination 側は既存の identity → position 単一導出経路の産物) と、本 spec が追加する column 表示序数。UI が capture / planner / DB に直接触れることはない (zero-write の表示層、spec 195 と同様)。
- 永続化、migration、backup/restore、Launcher DB への影響: **なし**。表示層のみの変更である。
- `PlanPreview` / `PlanPreviewResult` の shape 変更はなし。`PreviewPosition.Workspace` への field 追加は process-local な projection の additive 変更であり、serialize / journal / export 対象外である (spec 194 契約どおり)。

## Permissions, privacy, and security

None。新たな permission、network、telemetry は追加しない。表示は 1-based 表示序数であり、生 cell 座標・`ItemId`・package・component・digest・profile identity は引き続き表示へ現れない (spec 194 / spec 208 の privacy 契約の継続)。

## Accessibility and localization

- 変更行は引き続き単一の意味ある読み上げ node であり、destination 具体性の追加語は node を分割しない (spec 195 / spec 52 の a11y 契約継続)。
- 200% font scale で追加語を含む行が wrap し、切抜き・横 scroll 依存がない。
- 追加・変更 strings は `values/` + `values-ja/` の両方へ置き、placeholder を保持する (#123: 日本語実行時の英語 fallback 禁止)。en/ja の文言長差で layout が崩れない。
- TalkBack による行の読み上げには、移動先の行/列の区別が含まれる (destination の具体性が視覚だけでなく読み上げにも届く)。

## Acceptance criteria

- [ ] AC-1: **anchor 一意性 (本 Issue の中核、spec #212 R1 の反転)**。同一 proposal 内の全 move 行について、destination 表示から解釈できる current grid 上の anchor 候補集合が `{ resolved anchor }` に一致する。特に 4×6 grid で (0,0) と (1,0) へ resolved された 2 item が destination 部分だけで区別できる。exact `(cellX, cellY)` 文字列は要求しない。検証は **projection 層 → formatter 層 → rendered card 層** の 3 層で連鎖的に行い、application/preview の test が UI copy の解釈ロジックを複製しない (owner review 指摘 3)。
- [ ] AC-2: **same-band 列方向調整の非曖昧化 (page を含む)**。同 page・同 band・同行序数の列方向調整行が、destination 側の表示 (page + 領域 + 行/列表示序数) で調整先 cell を一意に特定でき、#195 D5 の残余リスクの当該部分を解消する。**異なる page の same-band 調整が同一 destination 語にならないこと** (同一 row/column ordinal・異なる page の 2 same-band move fixture で固定、再レビュー指摘) を含む。
- [ ] AC-3: **単一導出経路と destination 専用経路の分離**。destination の表示は `PreviewPlacementIdentity` → `PreviewPosition` → copy の単一経路で構築され、formatter が座標を再計算しない。column 表示序数は projection が供給する ([spec 208](../208-organizer-proposal-placement-identity/spec.md) §4 の経路契約の継続)。anchor 具体性は **destination 専用の formatter 経路**でのみ付与され、`positionText` (source descriptor、保持行・警告行の current、collision 判定、新規フォルダ placement に共有) の語彙と #208 の collision-local 補助語契約は無変更である (owner review 指摘 1)。
- [ ] AC-4: **placement equality の非回帰と test 責務の分離**。`PreviewApplyPlacementEqualityTest` と `PreviewApplyPersistedPlacementEqualityTest` が cell-exact 一致を維持して pass する (projection 型変更への機械的更新は許容、意味論的緩和は不許可)。`DestinationRegionMappingTest` の characterization は **application/preview 層の主張** (projection が `(rowOrdinal, columnOrdinal)` を失わず保持し、distinct anchors が distinct projected destinations になる) へ反転し、UI formatter に依存しない。rendered copy の主張は `OrganizationPreviewContentTest` が担う (owner review 指摘 3)。
- [ ] AC-5: **branch stability (R5)**。同じ resolved move data・grid・locale 条件で、formatter 分岐と destination 語が deterministic である。既存 `sectionsAreDeterministicForIdenticalDetails` 系の主張が維持される。
- [ ] AC-6: **en/ja copy (#123 契約)**。追加・変更 strings が両 locale で解決し、ja 実行時に en fallback が発生しない。placeholder 数・順序が en/ja で一致する。
- [ ] AC-7: **rendered card の UI-level evidence (spec #212 R3 継続)**。実 `ManualOrganizationPreferences` card 上で、同 band 別 column の 2 move 行が異なる destination text で描画されることを instrumentation test が主張する。既存の `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` (現行曖昧さの evidence) は fix 後の期待表示へ更新し、R1 PASS の反転 evidence とする。
- [ ] AC-8: **a11y と privacy**。変更行は単一読み上げ node を維持し、200% font scale で wrap する。生 cell 座標・`ItemId` 等は表示へ現れない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 (projection 層) | `tests/unit/.../application/preview/DestinationRegionMappingTest.kt` の更新: F-03 fixture の主張を「同一 coarse destination」から「distinct anchors ⇒ distinct projected destinations (row/column 表示序数の無欠損)」へ反転。同 band 別 column (x=0 vs x=1)、別 row、grid 境界 (2/4/5 column) を parameterized に主張。**UI formatter に依存しない** |
| AC-1 (formatter 層) | `tests/unit/.../ui/OrganizationPreviewContentTest.kt`: projection で得た destination から組み立てた copy が anchor 一意であること (同 band 別 column の 2 destination が異なる copy、copy 中の表示序数が projection 値と一致)。formatter が `PreviewPosition` のみを入力にすることもこの純粋行構築 test で担保 |
| AC-1 (rendered card 層) | AC-7 の instrumentation evidence |
| AC-2 | `tests/unit/.../ui/OrganizationPreviewContentTest.kt` へ同帯・同行序数の列方向調整 fixture を追加し、destination 部分の具体性を主張。**同一 row/column ordinal・異なる page の 2 same-band move が異なる destination 語になる fixture** (再レビュー指摘) もここで固定 |
| AC-3 | `PlanPreviewProjectorTest` の更新: `workspacePosition` が column 表示序数を identity から導出すること、`position()` が total であること。destination 専用経路のみが具体語を付けることは `OrganizationPreviewContentTest` の構造と `positionText` 系主張の無変更で担保 |
| AC-4 | 既存 `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` を無変更 (または機械的追従のみ) で実行し pass。command: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-5 | `OrganizationPreviewContentTest` の determinism 主張の維持 + branch 分岐 (通常 move / same-band / row ordinal note) の fixture 追加 |
| AC-6 | instrumentation test: ja configuration context で追加・変更 string を解決し、en 値と一致しないこと (fallback 検出) |
| AC-7 | `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt`: `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` を fix 後期待へ更新 (2 行が異なる destination text で表示、両行とも anchor 一意)。`organizer-instrumentation-issue52-tests` job (API 36 / Platform 36.1) の成功 run URL を PR へ記録 |
| AC-8 | instrumentation test: 行 node の単一性、200% font scale。生座標の非露出は unit test の copy 主張で担保 |

## Open questions

- **copy の確定状況 (owner review 指摘 4 により accepted 前に確定)**: destination が常時表示する要素集合は本 spec で確定した — **workspace destination は page + 領域 + 1-based row ordinal + 1-based column ordinal で統一** (通常 move 行 / same-band adjustment 行とも、再レビュー指摘により same-band の page 省略を撤回)。残る実装 PR での調整は句読点・接続語・string 名などの micro-copy に限られ、表示要素集合を変える場合は spec 更新を伴う。
- destination 全行が anchor 一意形式になった結果、source descriptor 側の #208 collision 補助語 (`supplementCell`) と語彙が重複して冗長になる可能性: copy review で調整する。補助語の collision-local 付与契約自体は無変更。
- #195 D5 の判断は、本 spec の受入条件が pass した時点で「destination 部分については解消」として Issue 側へ記録する。

## Change history

- 2026-09-07: Drafted for Issue #234。#212 assessment (Case A 判定、region⇆cell mapping、R1 handoff) を evidence とし、destination の anchor 一意性 contract、projection への column 表示序数追加、regression boundary の取り扱いを確定して作成。
- 2026-09-07: Review revision (owner review @ [Issue #234 コメント](https://github.com/nunu1733/NunuLauncher/issues/234)「Spec / Plan review — Changes requested」): (1) **High** — destination 専用 formatter 経路へ設計変更し、`positionText` 共有による #208 source descriptor 契約への波及を排除。新規フォルダ placement の具体化を non-goal へ明記。(2) **Medium** — 「2-column grid の `top left` は band 一意」の誤例 (TOP row band が y=0,1 を含むため候補 2 件) を削除し、row/column 表示序数の **常時表示** を契約化。(3) **Medium** — AC-1 の検証を projection → formatter → rendered card の 3 層に分離し、`DestinationRegionMappingTest` を UI 依存のない projection 層主張へ反転。(4) **Medium** — en/ja とも表示要素集合 (page/region/row/column ordinal) を spec accepted 前に確定し、micro-copy のみを実装 PR review に移動。
- 2026-09-07: Re-review revision (owner re-review @ Issue #234「Spec / Plan re-review — Changes requested」): **High** — same-band 行の destination から page を省略する契約を撤回し、workspace destination を一律 **page + 領域 + row ordinal + column ordinal** に統一。異なる page の same-band 調整が同一 destination 語になる counterexample (page 1 / page 2 の同座標調整) を R1 違反として解消。Behavior scenario「異なる page の same-band 調整が destination だけで区別できる」と AC-2 へ境界 fixture を追加。

## References

- [Issue #234: destination 表示を resolved anchor 一意の具体性へ更新](https://github.com/nunu1733/NunuLauncher/issues/234)
- [Issue #212 assessment: organizer destination verification (Case A verdict)](../../docs/assessment/issue-212-organizer-destination-verification.md)
- [Spec 212: destination 表記と適用 cell の functional contract (R1 定義)](../212-organizer-proposal-destination-verification/spec.md)
- [Spec 195: confirmation UI 変更一覧 (D5 判定の更新対象、grouping/counts/truncation 契約)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 208: placement identity (identity → position → copy の単一経路、#234 との責務境界)](../208-organizer-proposal-placement-identity/spec.md)
- [Spec 194: plan preview seam (projection 契約)](../194-plan-preview-seam/spec.md)
- [Spec 123: organizer UI convergence (日本語 fallback 禁止契約)](../123-organizer-ui-convergence/spec.md)
- [Spec 52: manual full-organization vertical slice (a11y 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
