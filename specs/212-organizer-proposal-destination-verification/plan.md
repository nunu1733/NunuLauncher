# Investigation Plan: Organizer proposal destination functional verification

> Issue: #212
> Spec: [spec.md](./spec.md)
> Status: ready

## Goal

Organizer proposal の destination 表記が、requested destination / final resolved placement / rendered preview / apply destination / persisted workspace placement のどの意味論を表すかを確定し、2026-09-05 の exploratory review F-03 で観測された「複数 item が同じ `top left, page 2` と表示されるが、適用後は別 cell」という挙動を deterministic に分類する。

本 plan は investigation を完了させるためのものであり、原因が確定する前に production planner や copy を変更しない。ただし current `top left` / `左上` のような coarse label 単独を「設計どおりなので acceptance 済み」と扱わない。Spec R1/R2/R3 を満たすかを明示的に判定する。

## Phase 1 — Trace authoritative destination ownership

### 1.1 Requested → resolved placement

- Organizer strategy / planner が requested destination を生成する箇所を特定する。
- clamp / collision resolution / fallback / page remap 等、preview 前に placement を変更し得る処理を列挙する。
- preview 時点で authoritative な final resolved page + anchor cell を保持する field / object を特定する。
- requested / normalized / effective / resolved と呼ばれる複数表現がある場合、それぞれの意味と owner を記録する。

### 1.2 Proposal and presentation

- generated proposal が保持する page / cell / region / delta 等の field を列挙する。
- `top left` / `top center` / `bottom right` 等を生成する formatter / resource / helper を特定する。
- `source → destination` と `position adjusted within ...` の分岐条件を追う。
- `(from row X to row Y)` の row 値がどの coordinate から算出されるか確認する。
- formatter output が最終的にどの Organizer card/row composable/view に rendered されるか追跡する。

### 1.3 Accept / apply path

- proposal accept から workspace write までを追跡する。
- Apply が preview 時の resolved placement をそのまま使用するか、accept 後に clamp / normalization / collision fallback / page remap を再実行するか確認する。
- proposal/resolved placement、application input、persisted workspace placement を同じ fixture で比較できる seam を特定する。
- state drift 等で preview 済み placement を使用できない場合、silent relocation / reject / replan のどの挙動になるか確認する。

### Deliverable

Issue comment または assessment evidence に、少なくとも次の表を残す。

| Stage | Concrete field/type | Coordinate meaning | Can mutate destination? | Owner |
|---|---|---|---|---|
| Requested planner decision | TBD | requested | TBD | TBD |
| Final resolution | TBD | resolved page + anchor | TBD | TBD |
| Proposal model | TBD | TBD | TBD | TBD |
| Formatter | TBD | display only | no placement mutation expected | TBD |
| Rendered Organizer card | TBD | preview destination | display only | TBD |
| Apply request | TBD | apply destination | TBD | TBD |
| Persisted workspace | TBD | final placement | n/a | TBD |

Phase 1 の終了時に、Spec R2 の equality を比較できる authoritative value が特定できない場合は Case D として記録し、推測で downstream field を正本扱いしない。

## Phase 2 — Define region mapping and specificity

### 2.1 4-column baseline

F-03 の観測条件に合わせて、4-column grid で各 region が含む anchor cell 集合を実装から導出する。

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
- 1 region が何 anchor cell を含み得るか。
- 同一 region label を複数 item が共有することが仕様上可能か。

### 2.3 R1 specificity test

visible destination から current grid/page 上の候補 anchor を導出する test helper / assertion を investigation 用に定義する。production API の追加は前提にしない。

```text
visibleCandidates(rendered destination, grid) == { resolved anchor }
```

次を明示的な negative characterization とする。

- `top left` / `左上` だけでは 2 個以上の anchor が候補に残る grid。
- 2 item が異なる resolved anchor を持つのに、両方が同じ coarse destination text になる proposal。

この場合は planner/apply が正しくても R1 FAIL と記録する。exact `(cellX, cellY)` 表記そのものは要求せず、row/column、anchor、または一意な region + supplemental information でもよい。

## Phase 3 — Reproduce F-03 end to end

### 3.1 Minimal deterministic fixture

スクリーンショットを coordinate oracle にせず、次の状態を作る最小 fixture を構成する。

- 同一 proposal に複数 move がある。
- 2 item の current displayed destination が同じ coarse region になる。
- resolved anchor cell は異なる、または preview/apply mismatch が発生する条件を観測できる。

元 episode の Photos / Settings という app identity 自体が本質でなければ synthetic item へ縮小する。

### 3.2 Capture all destination stages

fixture から以下を同一 run で取得する。

1. requested destination
2. final resolved page / anchor cell
3. proposal model が保持する destination data
4. rendered Organizer card/row の destination text / semantics
5. accept 時に application layer へ渡る destination
6. apply 後 workspace の page / cellX / cellY（必要なら span）

### 3.3 R2 equality assertions

coordinate representation の変換がある場合は変換を固定したうえで、少なくとも次を assert する。

```text
preview's source resolved placement == apply destination
apply destination == persisted workspace placement
```

preview 後に fallback / clamp / collision resolution が再実行され、別 cell に変わるケースを characterization する。同じ coarse region 内に収まっていても exact resolved placement が異なれば R2 FAIL とする。

state drift が必要な再 resolution を引き起こす設計なら、silent relocation ではなく以下のどちらかを要求する contract と照合する。

- proposal/preview を更新し、再度ユーザーが確認できる。
- apply を中止し、replan/retry surface に戻す。

### 3.4 Actual rendered Organizer card evidence

R1/R3 は formatter/model の unit assertion だけでは完了扱いにしない。最低限、実際の Organizer proposal card/row を通る UI-level evidence を残す。

優先順:

1. 既存の Compose/UI instrumentation seam があれば、card の rendered text / semantics を deterministic に assert する。
2. 既存 seam だけでは不足する場合、最小 characterization UI test を owning surface に追加する。
3. 元 F-03 flow は emulator/device でも再確認し、card が accept 前に何を表示するか screenshot/evidence として残す。

UI screenshot は coordinate equality の oracle にはしない。R2 は stage data / workspace coordinate assertion、R3 は actual rendered UI evidence と役割を分離する。

### 3.5 Presentation branch fixture

`source → destination` と `position adjusted within ...` をそれぞれ発生させる fixture を用意し、分岐条件と output の deterministic 性を固定する。同じ resolved semantics なのに branch が変わる場合は presentation defect 候補とする。

## Phase 4 — Audit verified existing test coverage

現行 `main` で以下の実在を確認済みである。

- `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt`
- `tests/unit/app/lawnchair/organizer/planning/harness/PlannerContractHarness.kt`

`PlannerGeneratedPropertyTest` は `DeterministicOrganizationPlanner` を `PlannerContractHarness` と generated/example fixtures に接続する concrete planner adapter である。`PlannerContractHarness` は少なくとも `EXPECTATION`、`CONSERVATION`、`BOUNDS`、`NO_OVERLAP`、`CONTAINER_INTEGRITY`、`LOCK_PRESERVATION`、`PROFILE_ISOLATION`、`IDEMPOTENCE`、`DETERMINISM`、`INPUT_PERMUTATION` 等を planner outcome に対して検証する。

この既存 property surface を次の matrix で監査する。

| Property | Verified existing coverage | New coverage needed |
|---|---|---|
| Resolved destination collision-free | TBD (`NO_OVERLAP` の exact semantics を確認) | TBD |
| Same coarse region / different resolved anchor | TBD | TBD |
| Resolved placement exposed to preview | planner-only surface外の想定 | TBD |
| Preview resolved placement == apply input | planner-only surface外の想定 | TBD |
| Apply input == persisted placement | planner-only surface外の想定 | TBD |
| Actual Organizer card identifies resolved anchor | planner-only surface外 | UI-level evidence required |
| Region mapping across grid sizes | TBD | TBD |
| Formatter branch deterministic | planner-only surface外の想定 | TBD |

既存 property test に presentation/application concern を無理に混ぜない。実装探索で owner が別 module/seam と確定した場合、再現 test はそちらへ置く。

## Phase 5 — Classify the result against R1/R2/R3

### A. Planner/apply correct; intentional coarse presentation

Evidence:

- final resolved placement == apply destination == persisted placement。
- `top left` 等は複数 anchor を含む region として設計どおり。
- actual Organizer card は coarse label 単独で、resolved anchor を一意に識別できない。

Verdict / action:

- planner/apply mismatch はない。
- **R1/R3 は未達。`already satisfied` として close しない。**
- #195 系または focused presentation follow-up へ、current region mapping、F-03 fixture、必要な destination specificity contract を引き継ぐ。

### B. Presentation inaccurate or unstable

Evidence:

- final resolved placement == apply/persisted placement。
- formatter/rendered card が resolved placement と異なる destination を示す、必要情報を落とす、または同一 input で不安定に分岐する。

Verdict / action:

- presentation defect。
- formatter/rendering test を scope とする focused fix Issue を起票する。

### C. Preview-resolved / apply / persisted mismatch

Evidence:

- preview の正本とした resolved placement と apply destination または persisted placement が一致しない。

Verdict / action:

- mutation point を特定する。
- `risk: layout-data` 相当を考慮した focused fix Issue を起票する。
- fix Issue に exact regression fixture と non-regression boundary を渡す。

### D. Ambiguous authoritative destination

Evidence:

- requested / normalized / effective / apply destination が複数層で混在し、preview と apply が共有する正本を特定できない。

Verdict / action:

- authoritative resolved placement ownership を follow-up contract として定義する。
- 二重補正や presentation 独自計算を解消する Issue に分ける。

## Change set for this investigation

想定される変更は次に限定する。

| Area | Intended change |
|---|---|
| `specs/212-organizer-proposal-destination-verification/spec.md` | R1/R2/R3 と decision criteria |
| `specs/212-organizer-proposal-destination-verification/plan.md` | investigation procedure / verified test surfaces |
| owning planner / formatter / UI / application tests | 必要な characterization fixture のみ |
| `docs/assessment/**` または Issue comment | trace、rendered evidence、結論（必要時） |

production behavior は Phase 5 で gap/bug が確定しても本 Issue の investigation commit には混ぜず、focused follow-up Issue で扱う。

## Verification

実装探索後、実際の owning module/test target を使用する。最低限次を完了する。

1. `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` と `PlannerContractHarness.kt` の関連 property を実行し、planner-level no-overlap / determinism coverage を確認する。
2. destination formatter / presentation branch の focused unit test を実行する。
3. actual Organizer card/row を通る UI-level test または instrumentation evidence で、rendered destination が resolved anchor を一意に識別可能か確認する。
4. proposal final resolved placement → application input → persisted workspace placement を同一 fixture で exact compare する integration/instrumentation test を実行する。
5. 実装が可変 grid を扱う場合、4-column baseline と 2/5-column 相当の boundary を parameterized に確認する。
6. emulator/device で元 F-03 の flow を再確認し、actual card と apply 後 Home の evidence を残す。visual evidence は R3 の delivery evidence であり、R2 の coordinate oracle には使用しない。

## Stop conditions

次の場合は推測で production fix に進まず、調査結果として明示して停止する。

- preview 時点の final resolved placement に相当する authoritative value が存在せず、どの layer を owner にするか contract decision が必要 → Case D。
- apply 後 placement を deterministic に取得できず、新しい test seam の設計が必要。
- actual Organizer card の rendered output を既存 UI test seam から観測できず、characterization seam の追加が必要。
- variable-grid の region 意味論が既存 spec と実装で矛盾している。
- Issue #212 の観測と無関係な planner algorithm defect が見つかった。

これらは focused follow-up の候補とする。ただし「UI test seam がない」ことを理由に formatter string だけで R3 を PASS させない。

## Completion checklist

- [ ] requested → resolved → proposal/preview → apply → persisted workspace の data flow と owner を記録した。
- [ ] 4-column grid の region mapping を確定した。
- [ ] 必要な grid-size boundary を確認した。
- [ ] F-03 の同一 coarse destination label 条件を deterministic fixture で再現または反証した。
- [ ] current `top left` / `左上` のような coarse label 単独を R1 PASS としていない。
- [ ] actual Organizer card/row の rendered destination を UI-level evidence で確認した。
- [ ] rendered destination から final resolved anchor を一意に識別できるか判定した。
- [ ] preview の final resolved placement == apply destination == persisted placement を exact compare した。
- [ ] preview 後の fallback/clamp/re-resolution がある場合、silent relocation の有無を確認した。
- [ ] `position adjusted within ...` の分岐条件と deterministic 性を固定した。
- [ ] 実在する `PlannerGeneratedPropertyTest.kt` / `PlannerContractHarness.kt` の coverage boundary を記録した。
- [ ] Case A–D のいずれかに結論を分類し、R1/R2/R3 の各 verdict を明記した。
- [ ] gap/bug の場合は focused follow-up Issue と regression surface、設計の場合も必要な UX/presentation handoff を記録した。
