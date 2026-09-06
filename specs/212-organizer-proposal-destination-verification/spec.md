---
issue: "#212"
status: accepted
requirements: []
updated: 2026-09-06
---

# Organizer proposal の destination 表記と適用 cell の functional contract を確定する

## Problem

[Issue #212](https://github.com/nunu1733/NunuLauncher/issues/212) は、2026-09-05 の exploratory UX review F-03 で観測された Organizer proposal の destination 表記について、表示が plan の実体と一致しているかを deterministic に確定する調査である。

観測では、異なる item がともに `top left, page 2` と表示された一方、適用後の Home では別 cell に配置された。また後続 proposal では `position adjusted within top center (from row 2 to row 1)` のような別形式が現れ、同種の移動に対する表記 style も一定ではなかった。

この観測だけでは、次のどれかを判別できない。

1. `top left` 等が複数 cell を含む coarse な region 名であり、同一表記は設計どおり。
2. proposal formatter が destination の一部を失っており、表示だけが不正確。
3. proposal が保持する destination と apply 時に使用される destination が異なる。
4. planner / normalization / apply のいずれかで destination が後段に変更されている。
5. 同一 semantic move に複数の表示分岐があり、style の差に意味がある、または偶発的に不安定である。

Vision や screenshot 上の見た目だけでは cell 座標の正否を確定できないため、本 Issue の pass/fail は plan data と適用後 workspace data の deterministic な比較を正本とする。

## Outcome

本 Issue の完了時に、以下がコード・spec・test evidence で一意に説明できる。

- `top left` / `top center` / `bottom right` 等の region 表記が、plan のどの field / coordinate から導出されるか。
- 同じ region 表記を複数 item が共有できるか。共有できる場合、その region が含む cell の定義。
- `position adjusted within ...` と通常の `source → destination` 表記が選択される条件。
- proposal の semantic destination と accept 後に適用される workspace cell の関係。
- 4-column grid を含む各 grid size で region と cell の対応が deterministic か。
- 観測された不一致が「設計どおりの coarse presentation」「presentation bug」「planner/apply bug」のどれか。

production fix が必要な場合、本 Issue では原因・owning seam・regression surface を確定し、focused follow-up Issue へ分割する。planner algorithm や copy の改善を調査に混ぜない。

## Scope

### 1. Destination semantics の trace

Organizer の proposal 生成から適用までを、少なくとも次の境界で追跡する。

```text
strategy / planner decision
  -> generated proposal / move data
  -> destination region / position formatting
  -> proposal UI
  -> accept / application request
  -> persisted workspace cell
```

各境界で以下を記録する。

- page / screen ID
- cellX / cellY または同等の cell coordinate
- region または bucket 名
- requested / normalized / effective position の区別が存在する場合はその意味
- formatter が参照する field
- apply 前後で再計算または補正が発生するか

### 2. Region ⇆ cell mapping

少なくとも 4-column grid で `top left` / `top center` 等の region が含む cell 集合を確定する。

region が 1 cell を表すとは仮定しない。実装が coarse region を意図している場合は、複数 item が同じ destination label を共有し得ることを contract として明示する。

grid-independent な mapping であることを意図している場合は、2-column / 5-column 相当、または実装がサポートする異なる column count の代表値で境界を確認する。固定の grid size にしか意味を持たない実装であれば、その制約を明記する。

### 3. Proposal ⇆ applied cell consistency

proposal が exact cell を意味する field を保持している場合、その field と apply 後 workspace cell が一致することを deterministic test で検証する。

```text
proposal semantic destination cell == applied workspace cell
```

region 表記が coarse presentation である場合、UI label 自体に cell-level 1:1 を要求しない。ただし、label が示す region に実際の applied cell が含まれることは検証対象とする。

```text
applied workspace cell ∈ displayed destination region
```

### 4. Presentation branch stability

同じ semantic input に対して formatter の出力形式が deterministic であることを確認する。

対象例:

- `source region, page N → destination region, page N`
- `position adjusted within <region>`
- `(from row X to row Y)` を伴う形式

異なる形式が意味の違いを表す場合は、その分岐条件を明文化する。意味が同じなのに偶発的に形式が変わる場合は presentation bug 候補とする。

### 5. Existing property-test surface

`PlannerGeneratedPropertyTest` を含む既存 planner/property corpus が、以下を既に保証しているか確認する。

- exact destination の collision / overlap 不変条件
- region label の重複を許容するか
- proposal destination と apply destination の一致
- grid size を跨ぐ region mapping

既存 test が semantic destination の正しさのみを保証し presentation を通らない場合、本 Issue の再現 fixture を owning formatter / integration seam に追加する方針とする。

## Behavioral contracts

### C1: Coarse label is not silently treated as an exact cell

region label が複数 cell を表す設計なら、test と documentation は `top left` 等を exact cell identifier として扱わない。

### C2: Displayed region contains the applied destination

proposal が `R` を destination region として表示する場合、accept 後の item の cell は `R` の定義域に含まれる。

### C3: Exact semantic destination survives proposal → apply

proposal model が exact destination cell を保持する場合、通常の apply path で別 cell へ無説明に変化しない。後段 normalization が contract の一部なら、その effective cell を proposal semantic destination として区別して記録する。

### C4: Two items cannot resolve to the same occupied exact cell

同一 proposal 内の move が collision-free であるという既存 planner contract がある場合、それを維持する。同一 region label は許容され得るが、同一 exact cell への同時配置とは区別する。

### C5: Formatter output is deterministic

同じ move semantic data、grid、locale 条件では同じ formatter branch と同じ destination description を返す。

### C6: Functional verdict is non-visual

screenshot は再現補助 evidence として使用できるが、一致判定は model / workspace coordinate を assert する test または同等の deterministic instrumentation evidence による。

## Investigation decision matrix

### Case A — Coarse region presentation is by design

条件:

- proposal の exact destination と apply result は一致する。
- `top left` 等は複数 cell を含む region として一貫して定義されている。
- 観測された 2 item は同一 region 内の異なる cell へ配置される。

結論:

functional bug ではない。region の粒度により「同じ destination に見える」UX 問題として #195 系または適切な UX follow-up へ引き継ぐ。

### Case B — Presentation loses semantic destination information

条件:

- proposal model / apply result は正しい。
- formatter が field を欠落・誤変換し、表示 region が applied cell を含まない、または branch が不安定。

結論:

presentation-only fix Issue を起票し、formatter と presentation regression test を owning scope とする。

### Case C — Proposal semantic destination differs from apply result

条件:

- proposal model の exact destination と適用後 workspace cell が一致しない。

結論:

planner / normalization / application seam の functional bug。原因箇所を特定し、layout-data risk を伴う focused fix Issue を起票する。

### Case D — Contract itself is ambiguous

条件:

- requested / normalized / effective destination の意味が複数レイヤーで混在し、どの値が proposal の正本か定義されていない。

結論:

follow-up fix の前に authoritative destination ownership を定義する。二重 normalization や表示層での独自再計算は避ける。

## Non-goals

- region 名の copy 改善や UI レイアウト刷新。
- planner の整理品質・strategy heuristic の再設計。
- destination bug が確定する前の planner algorithm 変更。
- screenshot / Vision の主観判定を functional oracle にすること。
- 本 Issue と無関係な workspace coordinate system 全体のリファクタリング。

## Acceptance criteria

- [ ] destination 表記から plan field / formatter への対応が文書化されている。
- [ ] `top left` / `top center` 等の region ⇆ cell mapping が、少なくとも観測対象 4-column grid で確定している。
- [ ] 異なる item が同一 destination label を共有する再現条件と、その原因が設計 / presentation bug / planner-apply bug のいずれかに分類されている。
- [ ] proposal semantic destination と適用後 cell の関係を deterministic に検証する test surface が確定し、必要な characterization test が追加されている。
- [ ] `position adjusted within ...` と通常の move 表記の分岐条件が特定されている。
- [ ] 同一 semantic input に対する formatter output の deterministic 性が確認されている。
- [ ] `PlannerGeneratedPropertyTest` 等の既存 property corpus が何を保証し、何を保証しないか記録されている。
- [ ] functional bug が確定した場合は原因・owning seam・risk・regression test を含む focused fix Issue が起票されている。
- [ ] 設計どおりの coarse presentation と確定した場合は、UX 表記改善側への引き継ぎ内容が記録されている。
