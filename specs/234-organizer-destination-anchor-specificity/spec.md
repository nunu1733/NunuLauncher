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

- **移動行の destination 表示**を、band label 単独から anchor 一意の表現へ更新する。対象は `MoveChange.destination` を描画する全分岐 (通常 move 行、same-band adjustment 行) である。
  - 表示形式は「既存の領域語 + page 語を保持しつつ、1-based column 表示序数 (および行序数) を補う」方針を baseline とする。exact `(cellX, cellY)` 文字列は必須としない (Issue 受入条件)。row/column、anchor、一意な region+supplement の組合せのいずれでもよいが、**同一 proposal 内で異なる resolved placement を持つ 2 item が destination 部分だけで区別できない表示を残してはならない**。
  - same-band adjustment 行 (`«領域» 内で位置を調整 (from row X to row Y)`) も、行序数 note が効かない純粋な列方向調整 (同 band・同行序数) で destination 候補が複数残らない形へ更新する。これは #195 D5 の残余リスク「同帯・同行序数の列方向調整は before/after が同一文言」を解消する。
- **projection の additive 拡張**: `PreviewPosition.Workspace` が保持する page ordinal / rowBand / columnBand / rowOrdinal のデータ範囲では column specific 性を表現できないため、1-based **column ordinal** (`cellX + 1`) を projection に追加する ([spec 194](../194-plan-preview-seam/spec.md) projection 契約の additive 変更、[spec 208](../208-organizer-proposal-placement-identity/spec.md) §Relationship to #234 が許諾する範囲)。formatter は `PreviewPosition` からのみ表示を組み立て、独自の座標再計算を行わない (Issue 受入条件)。
  - identity (`PreviewPlacementIdentity.Workspace`) は既に `cellX` / `cellY` を保持しており、identity 契約の変更は不要である。`position(identity)` の total function は維持する。
- **翻訳**: 追加・変更する strings は `values/` と `values-ja/` の両方へ置く (#123 契約)。
- **既存 branch stability (R5) と rendering instrumentation** の維持 (Issue 受入条件)。既存表示のうち band 一意な destination (例: 2-column grid の `top left` = x=0 のみ) では、anchor 一意性を満たす範囲で現行形式からの逸脱を最小化する。

## Non-goals

- planner algorithm・strategy heuristic の変更。resolved placement の生成に手を入れない (R2 の一致は既に保証済み)。
- destination mismatch の fix (mismatch は存在しないことが #212 で確定済み)。
- placement equality の再検証自体 (既存 `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` が無変更で通り続けることを regression boundary として要求するのみ)。
- destination 以外の行部分 (source descriptor、理由語) の再設計。#208 の descriptor 契約は無変更。
- UI レイアウト全体の再設計、visual before/after preview (#195 D5 の Optional な将来拡張として残る)。
- 保持行・警告行の `current` 位置語の変更 (本 Issue は destination の具体性が主題。保持行は「移動しない」ことの表明であり anchor 一意性契約の対象ではない)。

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

Then 行は調整の発生を告知し (現行契約どおり)、かつ destination 側の表示から解釈できる anchor 候補が 1 個になる (「位置を調整」の対象 region 内で列表示序数が復元される),

And #195 D5 の残余リスク「同帯・同行序数の列方向調整は before/after が同一文言」は当該行の destination 部分については残らない。

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

- [ ] AC-1: **anchor 一意性 (本 Issue の中核、spec #212 R1 の反転)**。同一 proposal 内の全 move 行について、destination 表示から解釈できる current grid 上の anchor 候補集合が `{ resolved anchor }` に一致する。特に 4×6 grid で (0,0) と (1,0) へ resolved された 2 item が destination 部分だけで区別できる。exact `(cellX, cellY)` 文字列は要求しない。
- [ ] AC-2: **same-band 列方向調整の非曖昧化**。同 page・同 band・同行序数の列方向調整行が、destination 側の表示で調整先 cell を一意に特定でき、#195 D5 の残余リスクの当該部分を解消する。
- [ ] AC-3: **単一導出経路の維持**。destination の表示は `PreviewPlacementIdentity` → `PreviewPosition` → copy の単一経路で構築され、formatter が座標を再計算しない。column 表示序数は projection が供給する ([spec 208](../208-organizer-proposal-placement-identity/spec.md) §4 の経路契約の継続)。
- [ ] AC-4: **placement equality の非回帰**。`PreviewApplyPlacementEqualityTest` と `PreviewApplyPersistedPlacementEqualityTest` が cell-exact 一致を維持して pass する (projection 型変更への機械的更新は許容、意味論的緩和は不許可)。`DestinationRegionMappingTest` の characterization は「R1 未達の evidence」としての役割を終え、fix 後の期待表示を固定する形へ更新する (R1 PASS へ反転)。
- [ ] AC-5: **branch stability (R5)**。同じ resolved move data・grid・locale 条件で、formatter 分岐と destination 語が deterministic である。既存 `sectionsAreDeterministicForIdenticalDetails` 系の主張が維持される。
- [ ] AC-6: **en/ja copy (#123 契約)**。追加・変更 strings が両 locale で解決し、ja 実行時に en fallback が発生しない。placeholder 数・順序が en/ja で一致する。
- [ ] AC-7: **rendered card の UI-level evidence (spec #212 R3 継続)**。実 `ManualOrganizationPreferences` card 上で、同 band 別 column の 2 move 行が異なる destination text で描画されることを instrumentation test が主張する。既存の `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` (現行曖昧さの evidence) は fix 後の期待表示へ更新し、R1 PASS の反転 evidence とする。
- [ ] AC-8: **a11y と privacy**。変更行は単一読み上げ node を維持し、200% font scale で wrap する。生 cell 座標・`ItemId` 等は表示へ現れない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `tests/unit/.../application/preview/DestinationRegionMappingTest.kt` の更新: F-03 fixture の主張を「同一 coarse destination」から「destination 表示の区別 + 候補 1 個」へ反転。同 band 別 column (x=0 vs x=1)、別 row、grid 境界 (2/4/5 column) を parameterized に主張 |
| AC-2 | `tests/unit/.../ui/OrganizationPreviewContentTest.kt` へ同帯・同行序数の列方向調整 fixture を追加し、destination 部分の具体性を主張 |
| AC-3 | `PlanPreviewProjectorTest` の更新: `workspacePosition` が column 表示序数を identity から導出すること、`position()` が total であること。formatter の copy が `PreviewPosition` のみを入力にすることは `OrganizationPreviewContentTest` の純粋行構築 test で担保 |
| AC-4 | 既存 `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` を無変更 (または機械的追従のみ) で実行し pass。command: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-5 | `OrganizationPreviewContentTest` の determinism 主張の維持 + branch 分岐 (通常 move / same-band / row ordinal note) の fixture 追加 |
| AC-6 | instrumentation test: ja configuration context で追加・変更 string を解決し、en 値と一致しないこと (fallback 検出) |
| AC-7 | `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt`: `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` を fix 後期待へ更新 (2 行が異なる destination text で表示、両行とも anchor 一意)。`organizer-instrumentation-issue52-tests` job (API 36 / Platform 36.1) の成功 run URL を PR へ記録 |
| AC-8 | instrumentation test: 行 node の単一性、200% font scale。生座標の非露出は unit test の copy 主張で担保 |

## Open questions

- **destination 表示の最終 copy** (「`上段左, 2ページ目, 1行目1列目`」の語順・接続詞等): spec 時点では anchor 一意性 contract のみを確定し、具体的語順・copy は実装 PR で en/ja copy とともに owner review にかける (非 blocking。spec 208 の補助語 copy と同様の進め方)。#195 D5 の判断は、本 spec の受入条件が pass した時点で「destination 部分については解消」として Issue 側へ記録する。
- 同一 proposal 内の destination 表示が全部 anchor 一意形式になった結果、source descriptor 側の #208 collision 補助語 (`supplementCell`) と語彙が重複して冗長になる可能性: copy review で調整する。補助語の collision-local 付与契約自体は無変更。

## Change history

- 2026-09-07: Drafted for Issue #234。#212 assessment (Case A 判定、region⇆cell mapping、R1 handoff) を evidence とし、destination の anchor 一意性 contract、projection への column 表示序数追加、regression boundary の取り扱いを確定して作成。

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
