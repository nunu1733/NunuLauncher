# Investigation Plan: Organizer proposal destination functional verification

> Issue: #212
> Spec: [spec.md](./spec.md)
> Status: ready

## Goal

Organizer proposal の destination 表記が、planner / proposal model / apply 後 workspace cell のどの意味論を表すかを確定し、2026-09-05 の exploratory review F-03 で観測された「複数 item が同じ `top left, page 2` と表示されるが、適用後は別 cell」という挙動を deterministic に分類する。

本 plan は investigation を完了させるためのものであり、原因が確定する前に production planner や copy を変更しない。

## Phase 1 — Trace the current data flow

### 1.1 Proposal generation

- Organizer strategy / planner が move destination を生成する箇所を特定する。
- generated proposal が保持する page / cell / region / delta 等の field を列挙する。
- requested position、normalized position、effective position に相当する複数表現があるか確認する。

### 1.2 Presentation formatting

- `top left` / `top center` / `bottom right` 等を生成する formatter / resource / helper を特定する。
- `source → destination` と `position adjusted within ...` の分岐条件を追う。
- `(from row X to row Y)` の row 値がどの coordinate から算出されるか確認する。

### 1.3 Apply path

- proposal accept から workspace write までを追跡する。
- apply 前後で clamp / normalization / collision resolution / page remap が実行されるか確認する。
- proposal model の exact destination と persisted workspace cell を比較できる seam を特定する。

### Deliverable

Issue または assessment evidence に、次の表を残す。

| Stage | Data | Coordinate meaning | Can mutate destination? |
|---|---|---|---|
| Planner decision | TBD | TBD | TBD |
| Proposal model | TBD | TBD | TBD |
| Formatter | TBD | display only | TBD |
| Apply request | TBD | TBD | TBD |
| Persisted workspace | TBD | final cell | n/a |

## Phase 2 — Define region mapping

### 2.1 4-column baseline

F-03 の観測条件に合わせて、4-column grid で各 region が含む cell 集合を実装から導出する。

最低限:

- top left
- top center
- top right（存在する場合）
- bottom left
- bottom center（存在する場合）
- bottom right

region 名が row と column の両方を coarse に bucketize している場合、その境界式を記録する。

### 2.2 Grid-size boundary

実装が可変 column count をサポートする場合、少なくとも 2-column / 5-column 相当、または既存 test fixture で利用可能な最小・奇数列の代表値を確認する。

確認事項:

- odd/even column count で center の意味が変わるか。
- 1 region が何 cell を含み得るか。
- 同一 region label を複数 item が共有することが仕様上可能か。

## Phase 3 — Reproduce F-03 deterministically

### 3.1 Minimal fixture

スクリーンショットを oracle にせず、次の状態を作る最小 fixture を構成する。

- 同一 proposal に複数 move がある。
- 2 item の displayed destination region が同じになる。
- exact destination cell は異なる、または不一致が発生する条件を観測できる。

元 episode の Photos / Settings という app identity 自体が本質でなければ synthetic item へ縮小する。

### 3.2 Assertions

fixture から以下を同時に取得する。

1. proposal exact destination（存在する場合）
2. displayed destination region / text
3. accept 時に application layer へ渡る destination
4. apply 後 workspace の page / cellX / cellY

判定:

```text
if displayed label is an exact-cell claim:
    proposal destination == applied cell
else if displayed label is a region claim:
    applied cell ∈ displayed region
```

2 item が同一 exact cell に解決されないことも既存 contract と照合する。

### 3.3 Presentation branch fixture

`source → destination` と `position adjusted within ...` をそれぞれ発生させる fixture を用意し、分岐条件と出力の deterministic 性を固定する。

## Phase 4 — Audit existing property coverage

`PlannerGeneratedPropertyTest` と関連 planner / application tests を確認し、次の matrix を埋める。

| Property | Existing coverage | New coverage needed |
|---|---|---|
| Exact destination collision-free | TBD | TBD |
| Same region / different cell allowed | TBD | TBD |
| Proposal exact destination == apply input | TBD | TBD |
| Apply input == persisted workspace cell | TBD | TBD |
| Displayed region contains applied cell | TBD | TBD |
| Region mapping across grid sizes | TBD | TBD |
| Formatter branch deterministic | TBD | TBD |

既存 property test に presentation concern を無理に混ぜない。formatter や integration seam が適切な owner なら、再現 test はそちらへ置く。

## Phase 5 — Classify the result

### A. Designed coarse region

- exact destination と apply result は正しい。
- 同じ `top left` は複数 cell を含む region のため重複表示される。

Action:

- functional investigation は pass。
- UX 側へ「region 粒度では複数 destination が同一に見える」ことを evidence 付きで引き継ぐ。

### B. Presentation-only defect

- model / apply は正しい。
- formatter が applied cell を含まない region を表示する、必要 field を落とす、または同一 semantic input で不安定に分岐する。

Action:

- formatter / presentation test を scope とする focused fix Issue を起票する。

### C. Planner / apply defect

- proposal exact destination と accept 後 workspace cell が一致しない。

Action:

- mutation point を特定する。
- `risk: layout-data` 相当を考慮した focused fix Issue を起票する。
- fix Issue に exact regression fixture と non-regression boundary を渡す。

### D. Ambiguous ownership

- requested / normalized / effective destination が複数層で混在し、正本がない。

Action:

- authoritative destination ownership を follow-up contract として定義し、二重補正や presentation 独自計算を解消する Issue に分ける。

## Change set for this investigation

想定される変更は次に限定する。

| Area | Intended change |
|---|---|
| `specs/212-organizer-proposal-destination-verification/spec.md` | functional contract / decision criteria |
| `specs/212-organizer-proposal-destination-verification/plan.md` | investigation procedure |
| planner / formatter / application tests | 必要な characterization fixture のみ |
| `docs/assessment/**` または Issue comment | trace と結論の evidence（必要時） |

production behavior は Phase 5 で bug が確定しても本 Issue の investigation commit には混ぜず、follow-up fix Issue で扱う。

## Verification commands / environment

実装探索後に repository の実際の module 名へ置換する。最低限次を実行する。

1. 対象 formatter / planner の unit or property test。
2. proposal → application seam を通る deterministic integration / instrumentation test。
3. grid-size parameterized test（実装が可変 grid を扱う場合）。
4. 既存 `PlannerGeneratedPropertyTest` corpus の回帰。

on-device screenshot は元 episode の再確認に使ってよいが、AC の判定根拠は coordinate assertion とする。

## Stop conditions

次の場合は推測で production fix に進まず、調査結果として明示して停止する。

- proposal model に exact destination が存在せず、UI region と apply cell を結ぶ authoritative seam がない。
- apply 後 cell を test から deterministic に取得できず、新しい test seam の設計が必要。
- variable-grid の region 意味論が既存 spec と実装で矛盾している。
- Issue #212 の観測と無関係な planner algorithm defect が見つかった。

これらはそれぞれ focused follow-up Issue の候補とする。

## Completion checklist

- [ ] proposal → formatter → apply → workspace の data flow を記録した。
- [ ] 4-column grid の region mapping を確定した。
- [ ] 必要な grid-size boundary を確認した。
- [ ] F-03 の同一 destination label 条件を deterministic fixture で再現した。
- [ ] displayed region と applied cell の包含関係を assert した。
- [ ] proposal exact destination がある場合、apply result との一致を assert した。
- [ ] `position adjusted within ...` の分岐条件を固定した。
- [ ] `PlannerGeneratedPropertyTest` の coverage gap を記録した。
- [ ] Case A–D のいずれかに結論を分類した。
- [ ] bug の場合は focused follow-up Issue、設計の場合は UX follow-up への引き継ぎを記録した。
