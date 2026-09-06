---
issue: "#212"
status: implemented
requirements: []
updated: 2026-09-06
---

# Organizer proposal の destination 表記と適用 cell の functional contract を確定する

## Problem

[Issue #212](https://github.com/nunu1733/NunuLauncher/issues/212) は、2026-09-05 の exploratory UX review F-03 で観測された Organizer proposal の destination 表記について、表示が plan の実体および accept 後の配置と一致しているかを deterministic に確定する調査である。

観測では、異なる item がともに `top left, page 2` と表示された一方、適用後の Home では別 cell に配置された。また後続 proposal では `position adjusted within top center (from row 2 to row 1)` のような別形式が現れ、同種の移動に対する表記 style も一定ではなかった。

この観測だけでは、次のどれかを判別できない。

1. `top left` 等が複数 cell を含む coarse な region 名であり、同一表記は現実装の設計どおり。
2. proposal formatter が resolved destination の一部を失っており、表示だけが不十分または不正確。
3. proposal preview が表す destination と apply が使用する resolved destination が異なる。
4. planner / normalization / apply のいずれかで destination が preview 後に変更されている。
5. 同一 semantic move に複数の表示分岐があり、style の差に意味がある、または偶発的に不安定である。

Vision や screenshot 上の見た目だけでは cell 座標の正否を確定できないため、placement 一致の pass/fail は plan data と適用後 workspace data の deterministic な比較を正本とする。一方、destination 情報がユーザーに実際に届いているかは model/formatter の存在だけでは証明できないため、最終的な Organizer card の rendered presentation も acceptance evidence に含める。

## Outcome

本 Issue の完了時に、以下がコード・spec・test evidence で一意に説明できる。

- `top left` / `top center` / `bottom right` 等の region 表記が、plan のどの field / coordinate から導出されるか。
- planner の requested destination、preview に使う final resolved placement、apply に渡す placement、persisted workspace placement の関係。
- 同じ coarse region 表記を複数 item が共有できるか。共有できる場合、その region が含む cell の定義。
- `position adjusted within ...` と通常の `source → destination` 表記が選択される条件。
- 4-column grid を含む各 grid size で region と cell の対応が deterministic か。
- Organizer card が、accept 前に final resolved destination を現状の coarse label より具体的に識別できる情報として表示しているか。
- 観測された不一致が「coarse presentation requirement gap」「presentation bug」「planner/apply bug」「destination ownership ambiguity」のどれか。

production fix が必要な場合、本 Issue では原因・owning seam・regression surface を確定し、focused follow-up Issue へ分割する。planner algorithm や copy の改善を調査に混ぜない。ただし、現状の `top left` / `左上` のような coarse label 単独は、たとえ意図された region 表記であっても本 spec の destination-specificity contract を満たしたとは判定しない。

## Domain language

本調査では destination の段階を次のように区別する。実装に同名の型が存在することは要求しないが、調査 evidence では対応する値を明示する。

- **requested destination**: strategy / planner が placement resolution 前に要求する位置・領域・優先先。
- **resolved placement**: clamp、fallback、collision resolution、page remap 等、本来 apply 前に必要な決定をすべて反映した、preview 時点で authoritative な最終 page + anchor cell（必要なら span を含む）。
- **preview destination**: Organizer card がユーザーに示す destination。resolved placement から導出されなければならない。
- **apply destination**: accept 後に application layer が実際に使用する placement。
- **persisted placement**: apply 完了後に workspace/model から取得できる最終 placement。

## Scope

### 1. Destination semantics の trace

Organizer の proposal 生成から適用までを、少なくとも次の境界で追跡する。

```text
strategy / planner requested destination
  -> normalization / collision resolution / fallback
  -> final resolved placement
  -> generated proposal / move data
  -> destination presentation
  -> rendered Organizer card
  -> accept / application request
  -> persisted workspace placement
```

各境界で以下を記録する。

- page / screen ID
- anchor cellX / cellY と span、または同等の exact placement coordinate
- region または bucket 名
- requested / resolved / apply / persisted のどの意味か
- formatter / UI が参照する field
- preview 後に再計算または補正が発生するか

### 2. Region ⇆ cell mapping

少なくとも 4-column grid で `top left` / `top center` 等の region が含む cell 集合を確定する。

region が 1 cell を表すとは仮定しない。実装が coarse region を意図している場合は、複数 item が同じ region label を共有し得ることを現状挙動として記録する。ただし **複数の resolved anchor cell を同じ `top left` 等だけで表す coarse label 単独は R1 を満たさない**。

grid-independent な mapping であることを意図している場合は、2-column / 5-column 相当、または実装がサポートする異なる column count の代表値で境界を確認する。固定の grid size にしか意味を持たない実装であれば、その制約を明記する。

### 3. Preview ⇆ resolved ⇆ applied placement consistency

preview に表示する destination は、accept 前に確定した final resolved placement から導出する。apply は同じ resolved placement を使用しなければならない。

```text
preview source placement == resolved placement
resolved placement == apply destination
apply destination == persisted placement
```

coordinate representation の変換がある場合は deterministic な等価変換を明示して比較する。

apply 時の state drift 等で既存 resolved placement を使用できなくなった場合、同じ region 内の別 cell へ silent fallback してはならない。再 resolution が必要なら、新しい resolved placement を proposal/preview に反映してユーザーが確認できる状態へ戻すか、apply を失敗・再提案扱いにする。preview 済みの destination と異なる placement をそのまま commit する挙動は R2 を満たさない。

### 4. Destination presentation specificity

Organizer card は、ユーザーが accept 前に各 moved item の resolved placement を現在の coarse label より具体的に識別できる情報を表示する。

exact `(cellX, cellY)` 文字列そのものは要求しない。次のいずれでもよい。

- resolved anchor cell を直接表す表示。
- row / column 等、現在の grid 上で anchor を一意に特定できる表示。
- current grid 上で一意の resolved anchor に対応する destination region。
- coarse region に追加情報を組み合わせ、候補 anchor を一意に絞れる表示。

表示から解釈できる destination 候補集合を `visibleCandidates(display, grid)` としたとき、moved item について次を満たすことを目標 contract とする。

```text
visibleCandidates(preview destination, grid) == { resolved anchor }
```

したがって `top left` / `左上` のように同一 grid/page 内の複数 anchor を候補に残す coarse label 単独は不十分である。同一 proposal 内で異なる resolved placement を持つ 2 item が、destination 部分だけでは区別不能になる表示も同じ理由で不十分である。

### 5. Presentation branch stability

同じ resolved move data、grid、locale 条件に対して formatter / rendered output の形式が deterministic であることを確認する。

対象例:

- `source region, page N → destination region, page N`
- `position adjusted within <region>`
- `(from row X to row Y)` を伴う形式

異なる形式が意味の違いを表す場合は、その分岐条件を明文化する。意味が同じなのに偶発的に形式が変わる場合は presentation bug 候補とする。

### 6. Existing planner property-test surface

現行 `main` には実在する以下の test surface がある。

- `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt`
- `tests/unit/app/lawnchair/organizer/planning/harness/PlannerContractHarness.kt`

`PlannerGeneratedPropertyTest` は `DeterministicOrganizationPlanner` を Issue #11 の `PlannerContractHarness` / generated fixtures に接続する concrete planner test である。少なくとも harness は planner outcome の `NO_OVERLAP`、bounds、determinism、idempotence 等を検証するが、Organizer card rendering や application/persistence path を通るものではない。

本 Issue では、この既存 surface が exact destination collision をどこまで保証するかを確認しつつ、**preview rendering と apply/persisted placement の一致をこの planner-only property test が保証しているとは仮定しない**。presentation / application concern は owning UI/integration seam に characterization を置く。

## Requirements

### R1 — Preview destination is specific enough to identify the resolved placement

Organizer card の destination 表示は、accept 前に final resolved page + anchor cell を current grid 上で一意に識別できる情報を含む。

- exact 座標表記は必須ではない。
- ただし、現状の `top left` / `左上` のように複数 cell を含む coarse direction/region label **単独では R1 を満たさない**。
- distinct resolved placements が同一の destination 表示として区別不能になる場合も R1 を満たさない。

### R2 — Preview and Apply use the same final resolved placement

Preview destination は final resolved placement から導出し、Apply は同じ resolved placement を使用する。

```text
preview-resolved placement == apply destination == persisted placement
```

fallback / clamp / collision resolution / page remap が必要なら preview より前に完了させる。preview 後に再 resolution が必要になった場合は silent relocation をせず、新しい preview を提示するか apply を中止する。

### R3 — User-visible rendering is part of acceptance evidence

model field や formatter string が存在するだけでは R1 の evidence としない。実際の Organizer proposal card/row に最終 destination 情報が rendered され、accept 前にユーザーが参照できることを UI-level test または emulator/device evidence で確認する。

### R4 — Resolved placements remain collision-free

同一 proposal 内の moves は同じ occupied exact placement に解決されない。既存 planner contract の `NO_OVERLAP` を維持する。

### R5 — Presentation branch is deterministic

同じ resolved move data、grid、locale 条件では同じ formatter branch と同じ destination description を返す。

### R6 — Coordinate verdict is deterministic; visual evidence verifies delivery

placement 一致の oracle は model / application / persisted coordinate の deterministic assertion とする。screenshot/Vision は coordinate 正否の代替にはしない。一方、R3 の「ユーザーに表示される」ことの evidence として rendered Organizer card の UI-level evidence を要求する。

## Investigation decision matrix

### Case A — Planner/apply are correct, but coarse presentation is intentional

条件:

- resolved placement と apply/persisted placement は一致する。
- `top left` 等は複数 cell を含む region として一貫して定義されている。
- actual Organizer card は coarse label 単独であり、distinct resolved placements を一意に識別できない。

結論:

planner/apply の functional mismatch ではないが、**R1 は未達**である。「already satisfied」としては扱わない。coarse presentation requirement gap として #195 系または focused presentation follow-up へ evidence と具体性 contract を引き継ぐ。

### Case B — Presentation is inaccurate or unstable

条件:

- resolved placement と apply/persisted placement は一致する。
- formatter/rendering が resolved placement と異なる destination を示す、必要 field を落とす、または同一 semantic input で不安定に分岐する。

結論:

presentation defect。formatter / rendering regression test を scope とする focused fix Issue を起票する。

### Case C — Preview-resolved placement differs from apply/persisted placement

条件:

- preview に対応する resolved placement と apply destination または persisted placement が一致しない。

結論:

planner / normalization / application seam の functional bug。mutation point を特定し、layout-data risk を伴う focused fix Issue を起票する。

### Case D — Authoritative resolved placement is ambiguous

条件:

- requested / normalized / effective / apply destination の意味が複数レイヤーで混在し、preview と apply が共有する正本を特定できない。

結論:

follow-up fix の前に authoritative resolved placement ownership を定義する。二重 normalization や表示層での独自再計算は避ける。

## Non-goals

- destination の具体的な copy wording や UI レイアウトを本調査内で決定すること。
- planner の整理品質・strategy heuristic の再設計。
- destination mismatch が確定する前の planner algorithm 変更。
- screenshot / Vision の主観判定を placement coordinate の functional oracle にすること。
- 本 Issue と無関係な workspace coordinate system 全体のリファクタリング。

## Acceptance criteria

- [ ] requested destination → final resolved placement → preview → apply destination → persisted placement の data flow と ownership が文書化されている。
- [ ] `top left` / `top center` 等の region ⇆ cell mapping が、少なくとも観測対象 4-column grid で確定している。
- [ ] 現状の `top left` / `左上` のような coarse label 単独は R1 を満たさないことを判定基準に含め、実際の Organizer card が resolved destination を一意に識別できる表示か評価されている。
- [ ] 最終 rendered Organizer proposal card/row が acceptance evidence に含まれ、destination 情報が accept 前に実際にユーザーへ表示されることが確認されている。
- [ ] preview が表す final resolved placement、apply destination、persisted placement の exact equality が deterministic に検証されている。preview 後の silent fallback は許容されない。
- [ ] F-03 の「同じ destination label / 異なる applied cell」条件が deterministic fixture で再現または反証され、Case A–D のいずれかに分類されている。
- [ ] `position adjusted within ...` と通常の move 表記の分岐条件が特定され、同一 input に対する deterministic 性が確認されている。
- [ ] 実在する `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` と `PlannerContractHarness.kt` の coverage boundary が記録され、UI/application coverage と混同されていない。
- [ ] functional bug が確定した場合は原因・owning seam・risk・regression test を含む focused fix Issue が起票されている。
- [ ] planner/apply が正しく coarse presentation が意図された設計だった場合も `already satisfied` とはせず、R1 を満たすための presentation/UX follow-up へ引き継ぎが記録されている。
