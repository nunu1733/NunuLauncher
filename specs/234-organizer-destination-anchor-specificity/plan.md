# Implementation Plan: Organizer destination 表示の anchor 一意化

> Issue: #234
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

[Spec 212 assessment](../../docs/assessment/issue-212-organizer-destination-verification.md) の Case A 判定 (すべて source-verified @ `ceb1e287d5`) と、現行 `main` での再確認に基づく。

### 現在の表示経路 (destination が曖昧になる箇所)

1. `PlanPreviewProjector.PositionContext.workspacePosition` (`lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt:443-452`) が identity (`PreviewPlacementIdentity.Workspace(pageDisplayOrdinal, isNewPage, cellX, cellY)`) を `PreviewPosition.Workspace(pageDisplayOrdinal, isNewPage, rowBand, columnBand, rowOrdinal)` へ変換する。band は `floor(coord * 3 / dimension)` (clamped)、`rowOrdinal = cellY + 1`。**column 方向の ordinal はこの変換で落ちる**。
2. `OrganizationPreviewContent.positionText` → `workspacePosition` format (`organizer/ui/OrganizationPreviewContent.kt:291-313`) が `«page 語»` + `«領域語»` を組み立てる (`manual_organization_preview_position_workspace` = `%2$s, %1$s` / `%1$s・%2$s`)。**`positionText` は destination 専用ではない**: `descriptorText` 経由の source position、保持行・警告行の `current`、`descriptorSupplements` の collision 判定 key、`NewFolderChange.placement` が同じ formatter を共有する (owner review 指摘 1 の根拠。destination 具体性はここへ織り込めない)。
3. move 行: `moveRowText` (`OrganizationPreviewContent.kt:276-289`) — 通常 move は `«descriptor» → «destination»(«row ordinal note») («理由»)`。same-band adjustment は `«descriptor» → position adjusted within «領域»(«row ordinal note»)`。`rowOrdinalNote` (`:355-362`) は同 page・同 row band・row ordinal 変化時のみ出るため、**同 band・同行序数の列方向調整は destination 側の差分表示がゼロ**になる。
4. destination 側は descriptor 側と異なり collision 補助語の対象外 (`descriptorSupplements` は source descriptor のみに適用)。

### 確定済みの事実 (確認済み)

- `PreviewPosition.Workspace` を消費するのは production 2 file (`OrganizationPreviewContent.kt`、`ManualOrganizationPreferences.kt` の wording 実装) と test 5 file のみ (`grep PreviewPosition` で全列挙済み)。field 追加の波及範囲は閉じている。
- instrumentation test `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` (`tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt:519`) が現行曖昧さの user-visible evidence として存在する。fix 後は期待表示を更新して R1 PASS の反転 evidence にする (spec AC-7)。
- `DestinationRegionMappingTest` が band rule・F-03 shape・`visibleCandidates == 4` を characterization として固定している。fix 後は **projection 層の主張** (distinct anchors ⇒ distinct projected destinations、UI formatter に依存しない) へ反転させ、rendered copy の主張は `OrganizationPreviewContentTest` が担う (spec AC-4、owner review 指摘 3 の層分離)。
- `PreviewApplyPlacementEqualityTest` (:108-110) と `PreviewApplyPersistedPlacementEqualityTest` (:104, :136) が `PreviewPosition.Workspace` を position 断言に使用している。field 追加は data class の copy 等価に影響しないが、constructor 呼び出しの機械的追従が必要になる。
- placement の正本は preview == apply == persisted で 1 つ (R2 PASS 済み)。本 plan は placement を一切動かさない。

### 推測と区別済みの事実 (推測)

- destination copy の最終語順 (「1行目1列目」を領域語の前後に置くか等) は確定していない。spec Open questions のとおり実装 PR で owner review にかける。

## Design

### Modules and interfaces

変更は presentation 層 (projection + formatter + resources) に限定する。planner / materializer / apply / persist には触れない。

1. **`PreviewPosition.Workspace` への additive field** (`application/public/PlanPreview.kt`)

   ```kotlin
   data class Workspace(
       val pageDisplayOrdinal: Int,
       val isNewPage: Boolean,
       val rowBand: RowBand,
       val columnBand: ColumnBand,
       val rowOrdinal: Int,
       val columnOrdinal: Int,  // 1-based 表示序数 = cellX + 1
   )
   ```

   - identity 契約 (`PreviewPlacementIdentity`) は無変更 (`cellX` を既に保持)。`PositionContext.workspacePosition` が `position.cellX + 1` を詰めるだけの変換であり、`position(identity)` の total function は維持される。
   - non-additive な契約変更 (既存 field の意味変更、variant 削除) は行わない。

2. **formatter の destination 専用経路** (`organizer/ui/OrganizationPreviewContent.kt`)

   - **`positionText` は無変更** (owner review 指摘 1 / blocker)。source descriptor、保持行・警告行の `current` 位置、`descriptorSupplements` の collision 判定 key、`NewFolderChange.placement` が共有しており、ここへ row/column の常時表示を織り込むと「coarse position が衝突した行だけ #208 identity 補助語を付与する」契約が意味論ごと変わる (collision が構造的に消え、補助語が付かなくなる)。
   - **destination 専用の組立て関数** (案: `destinationText(position, wording)` / `workspaceDestinationText(...)`) を追加し、`MoveChange.destination` を描画する 2 分岐のみで使用する:
     - **workspace destination は一律 page 語 + 領域語 + 1-based row ordinal + 1-based column ordinal を常時表示** — 通常 move 行 / same-band adjustment 行とも同一の要素集合である (再レビュー指摘により same-band の page 省略を撤回。省略すると異なる page の same-band 調整が同一 destination 語になり R1 違反)。grid・衝突の有無に依存する条件付き省略はしない。
     - **非-workspace destination の扱い** (再レビュー指摘): `destinationText` は `PreviewPosition.Workspace` のみ新表示へ振り分け、`DockRank` / `InFolder` / `InAppPair` は既存 `positionText` へそのまま委譲して表示を変えない (dock slot 番号・folder 内 rank・app pair 語は既に container + rank で一意であり、`visibleCandidates(..., grid)` は workspace anchor に対する契約)。`Unidentified` destination は projector が fail-closed (move 行不成立) のため有効な proposal には現れない。
   - `moveRowText` の 2 分岐だけが destination 専用関数を呼ぶ。`rowOrdinalNote` の分岐条件は無変更 (R5)。same-band 行で note が出ないケース (同 row ordinal) でも列方向の区別は destination 語自体が担うため曖昧さは残らない。
   - **無変更を保つ箇所**: `positionText`、`descriptorText`、`descriptorSupplements` / `identitySupplement` / `workspaceLocator` (#208 collision 補助語)、`newFolderRowText` (`NewFolderChange.placement` は現行どおり coarse)。語彙の重複 (`supplementCell` と新 destination 語) が気になる場合は copy review で語を分ける。

3. **strings** (`lawnchair/res/values/strings.xml` + `values-ja/strings.xml`)

   - **表示要素集合は spec で確定済み** (owner review 指摘 4 + 再レビュー指摘): **workspace destination は page + 領域 + 1-based row ordinal + 1-based column ordinal で統一** (通常 move / same-band とも同一 template でよい)。en/ja でこれを表現する template を追加する。baseline 案 (micro-copy は実装 PR owner review で調整可、表示要素集合の変更は spec 更新を伴う):
     - en destination: `«領域», «page», row R, column C` (例: `top left, page 2, row 1, column 2`) — 通常 move / same-band 共通
     - ja destination: `«page»・«領域»・R行目・C列目` (例: `2ページ目・上段左・1行目・2列目`) — 通常 move / same-band 共通
   - 行/列の語は #208 `supplementCell` と同じ 1-based 表示序数語彙 (`row/column` / `行目/列目`) を使い、en/ja で語彙と placeholder 数・順序を一致させる。要素集合が統一されたため、same-band 行は既存 `manual_organization_preview_same_band_move_row` の `%2$s` (領域語 slot) に代えて destination 全体語を入れる形になる可能性が高い (template 調整は micro-copy review に含める)。既存 `manual_organization_preview_position_workspace` / `manual_organization_preview_row_ordinal_note` は destination 専用経路の外で現行どおり。placeholder は positional `%n$` を維持 (#123 契約)。

4. **wording interface** (`OrganizationPreviewWording`)

   - destination 専用経路から参照する property の追加のみ。既存 property の意味変更はしない。`ResourceOrganizationPreviewWording` (`ManualOrganizationPreferences.kt:936`) と test 用 literal 実装 (`OrganizationPreviewContentTest`) の両方へ機械的追従。

### Data flow

```text
intended placement (不変)
  → identity (cellX, cellY 保持, 不変)
  → PreviewPosition.Workspace (+ columnOrdinal 追加)
  → destination 専用 formatter (move 2 分岐のみ; positionText は source/current 用に現行維持)
  → rendered card (anchor 一意)
```

出力: 変更は `PreviewChange` の表示語のみ。plan / action / write-set / persisted state は 1 byte も変わらない。error: 新たな error path なし (projection の fail-closed 条件は無変更)。

### Alternatives rejected

- **`positionText` を destination 専用化・具体化する**: 実装は最小で済むが、source descriptor・保持/警告行・collision 判定・新規フォルダ placement が共有するため、#208 の「coarse 衝突時のみ補助語」契約が意味論ごと変わる (owner review 指摘 1 で blocker 判定)。destination 専用関数を追加する設計を採用。
- **same-band 行のみ destination から page を省略する (短縮)**: same-band の定義上 source descriptor と同一 page のため一見冗長だが、R1 の正本は resolved **page** + anchor cell の一意識別であり、page 1 と page 2 の同座標 same-band 調整 (例: `page 1, (0,0) → (1,0)` と `page 2, (0,0) → (1,0)`) が同一 destination 語になる counterexample が成立する (再レビュー指摘 High)。workspace destination を `page + 領域 + row + column` で統一する方が、一意性契約・formatter・test oracle のいずれも単純になる。
- **destination 側へ #208 型の collision-local 補助語を適用**: 衝突検出が proposal 内の行集合に依存するため、単独 move の表示でも anchor 候補が 1 個に絞られず `visibleCandidates == {resolved anchor}` を満たさない。destination は常時 anchor 一意であることを契約とする (spec AC-1)。
- **band 一意な grid でのみ表示序数を省略する (条件付き省略)**: 省略条件は row band と column band の両方向の候補集合で判定する必要があり、かつ「2-column grid の `top left` は x=0 に絞られるが TOP row band が y=0,1 を含むため候補 2 件」のように当初想定した例は誤りだった (owner review 指摘 2)。全 grid で row/column ordinal を常時表示する方が契約が単純で、条件分岐の R5 deterministic 検証も不要になる。
- **生 cell 座標 (`cell 0,1`) の常時表示**: Issue 受入条件が exact `(cellX, cellY)` を必須とせず、生座標は spec 194/208 の「raw 値を表示しない」privacy 調性と衝突する。1-based 表示序数で十分に一意である (row ordinal が既に同方式)。
- **band label を廃止して row/column のみにする**: 領域語は「画面のどのあたりか」の第一読解を提供し、既存 copy と test の互換性が高い。anchor 一意性は付加語で達成できるため、置換は不要。
- **formatter が `deviceCapabilities` から座標を再計算**: spec 208 §4 の単一導出経路契約違反。projection が供給する値のみを使う (spec AC-3)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` | `PreviewPosition.Workspace` へ `columnOrdinal` を追加 (KDoc 更新) | 表示 data の正本は projection。formatter 再計算を禁じる単一経路契約 (AC-3) |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` | `workspacePosition` が `cellX + 1` を詰める | identity → position の単一変換点 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | destination 専用 formatter 経路の追加と move 2 分岐 (通常 / same-band) での使用。`positionText` / `descriptorText` / `descriptorSupplements` 系 / `newFolderRowText` は **無変更**。wording interface 追加 | presentation 層の formatter。共有経路への波及を遮断し (review 指摘 1)、R5 deterministic は純粋関数で維持 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `ResourceOrganizationPreviewWording` へ追従 | production wording 実装 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | destination 表示序数 string 追加 / template 更新 (en + ja 同時) | #123 契約 |
| `tests/unit/.../application/preview/DestinationRegionMappingTest.kt` | **projection 層の主張へ反転**: F-03 fixture を「同一 coarse destination」から「distinct anchors ⇒ distinct projected destinations (row/column 表示序数の無欠損)」へ。grid 境界 (2/4/5 column) を parameterized に主張。**UI formatter に依存しない** | R1 未達 characterization の正本 (application/preview 層)。fix と同じ PR で期待を反転 (review 指摘 3) |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` | `columnOrdinal` 導出と total function の追従 | projection seam test |
| `tests/unit/.../ui/OrganizationPreviewContentTest.kt` | **formatter 層の主張**: projection から組み立てた destination copy が anchor 一意 (同 band 別 column の 2 destination が異なる copy、copy 中の表示序数が projection 値と一致)。**同一 row/column ordinal・異なる page の 2 same-band move が異なる destination 語になる境界 fixture** (再レビュー指摘)。**非-workspace destination (`DockRank` / `InFolder` / `InAppPair`) の 1〜2 case で既存 copy が #234 で変わらないことを固定** (再レビュー 2 指摘)。同帯・同行序数の列方向調整 fixture、branch 分岐 fixture、`positionText` 系主張の無変更確認 | 純粋行構築の主張対象。UI copy の責務をこの層に集約 (review 指摘 3) |
| `tests/unit/.../application/actions/PreviewApplyPlacementEqualityTest.kt` / `.../protocol/PreviewApplyPersistedPlacementEqualityTest.kt` | constructor 呼び出しの機械的追従のみ (断言は無変更) | regression boundary: 意味論的変更をしないための証拠 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` | `distinctAnchorsInsideOneBand...` を fix 後期待へ更新、ja fallback / a11y 主張 | R1 PASS の user-visible 反転 evidence (AC-7) |

## Migration and recovery

- schema/rule migration: なし。DB / rule / preference への書込みはゼロ。
- failure 中の rollback: 表示層のみのため適用対象なし。preview projection の fail-closed 条件 (join miss → `Result.Invalid`) は無変更。
- release rollback/downgrade: `PlanPreview` は process-local かつ非永続 (spec 194) のため、downgrade で永続 data が壊れる経路はない。
- backup/restore compatibility: 対象外 (表示語は永続化しない)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 anchor 一意性 (3 層で連鎖) | **projection 層**: `DestinationRegionMappingTest` (反転後) — (page, rowOrdinal, columnOrdinal) の無欠損と anchor 単射性、2/4/5 column 境界。**formatter 層**: `OrganizationPreviewContentTest` — projection からの destination copy が anchor 一意 (同 band 別 column ⇒ 異なる copy)、非-workspace destination の既存 copy 維持 case。**rendered card 層**: AC-7 の instrumentation | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-2 同帯列方向調整 (page 含む) | `OrganizationPreviewContentTest`: 同帯・同行序数の列方向調整行の destination が調整先 cell を一意に特定 + 異なる page の same-band 2 move が異なる destination 語 (境界 fixture) | 同上 `--tests 'app.lawnchair.organizer.ui.*'` |
| AC-3 単一導出経路 | `PlanPreviewProjectorTest`: `columnOrdinal` が identity から導出。formatter が `PreviewPosition` のみを入力にすることは純粋行構築 test の構造で担保 | 同上 |
| AC-4 placement equality 非回帰 | 既存 2 test が機械的追従のみで pass。diff review で断言の緩和が無いことを確認 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-5 branch stability | `OrganizationPreviewContentTest` の determinism 主張 + 通常 move / same-band / row ordinal note の分岐 fixture | 同上 |
| AC-6 en/ja copy | instrumentation test: ja configuration で追加 string を解決し en 値と一致しない (fallback 検出) | `connectedLawnWithQuickstepGithubDebugAndroidTest` (API 36 / Platform 36.1) |
| AC-7 rendered card evidence | 更新後 `distinctAnchorsInsideOneBand...`: 2 行が異なる destination text で描画、両行 anchor 一意。CI job の成功 run URL を PR へ記録 | `.github/workflows/ci.yml` `organizer-instrumentation-issue52-tests` job |
| AC-8 a11y / privacy | instrumentation: 行 node 単一性、200% font scale。生座標非露出は unit copy 主張 | 同上 |

含めるべき観点:

- **unit/contract**: projection と formatter の全分岐。band 幅が grid により変わる境界 (2/4/5 column) を parameterized に主張し、表示語が grid 非依存の語彙で anchor 一意になることを確認。
- **failure injection**: 相当する failure path なし (表示層のみ)。`Result.Invalid` 系の既存 test が無変更で通ることで担保。
- **UI/accessibility**: 同帯列方向調整行が TalkBack 読み上げで行/列の区別を含むこと (AC-8)。
- **property**: 対象外 (placement を動かさないため planner property は既存 gate がそのまま効く)。
- **regression boundary の扱い**: `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` の diff は constructor 追従のみであることを PR review で明示する。`DestinationRegionMappingTest` は「R1 未達 evidence」から「projection 層の fix 後契約」への役割変化を KDoc に記録し、UI copy の主張 (`OrganizationPreviewContentTest`) との層分離を維持する (review 指摘 3)。`positionText` / `descriptorText` / `descriptorSupplements` / `newFolderRowText` の diff がゼロであることを PR review で確認する (review 指摘 1)。

## Documentation updates

- [ ] spec status/history: owner review 通過時に `accepted`、実装 PR で `implemented` へ更新
- [ ] spec 195: D5 residual risk の更新を Change history へ記録 (「destination 部分については #234 が解消」)
- [ ] spec 212: R1 handoff 先 (#234) の完了状態を assessment に追記 (実装後)
- [ ] CONTEXT.md: domain 語の追加が不要か実装 PR で判定 (「表示序数」は spec 208 が既に使用、新規語が生きたら追加)
- [ ] DESIGN.md: 変更なし (module 構造・interface 構造は不変)
- [ ] ADR: 不要 (選択肢比較は spec の Alternatives rejected に留まり、変更困難な架橋判断を生まない)
- [ ] AGENTS.md: 変更なし (新 command なし)

## Execution checklist

- [ ] Current behavior reproduced: 現行 `main` で `DestinationRegionMappingTest` (R1 未達 characterization) と instrumentation `distinctAnchorsInsideOneBand...` が pass することを確認
- [ ] Tests fail for the missing behavior: `DestinationRegionMappingTest` の反転主張 (projection 層) と `OrganizationPreviewContentTest` の anchor 一意 copy 主張 (formatter 層) を先に書き、現行実装で fail することを確認
- [ ] Minimal implementation completed: projection field → projector → wording interface → destination 専用 formatter (move 2 分岐のみ) → strings (en/ja) → wording 実装の順。`positionText` / `descriptorText` / `descriptorSupplements` / `newFolderRowText` を変更しない
- [ ] placement equality / branch stability の既存 test が無変更で pass
- [ ] Full relevant verification completed: JVM gate + instrumentation job の成功を PR へ記録
- [ ] PR evidence and remaining risks recorded: `Closes #234`、micro-copy (句読点・接続語) の owner review 記録、残余 risk (同 band 別 cell の複数新規フォルダ placement 語の同一文言 — spec non-goal に記録済み) を明記

## Review coordination notes

- owner review (2026-09-07、Issue #234 コメント) の 4 指摘の反映位置: 指摘 1 (High) → plan §Design 2 / §Alternatives rejected / spec §Scope・AC-3・Behavior scenario「destination 専用経路」、指摘 2 (Medium) → plan §Alternatives rejected「条件付き省略」+ spec §Scope の常時表示契約、指摘 3 (Medium) → spec AC-1/AC-4・Test oracle の 3 層分離 + plan §Change set・§Verification、指摘 4 (Medium) → spec §Scope の表示要素集合契約 + plan §Design 3 の baseline copy。
- re-review (2026-09-07、Issue #234 コメント) の 1 指摘 (High / same-band destination からの page 省略が R1 違反) の反映位置: spec §Scope 表示要素契約の統一 (page + 領域 + row + column) / Behavior scenario「異なる page の same-band 調整が destination だけで区別できる」/ AC-2 / Test oracle AC-2 行 / plan §Design 2・3 / §Alternatives rejected「page 省略」/ §Change set (OrganizationPreviewContentTest 境界 fixture) / §Verification AC-2 行。
- re-review 2 (2026-09-07、Issue #234 コメント、minor) の 1 指摘 (Medium / AC-1 の「全 move 行」が非-workspace destination にも grid anchor 一意性を要求して読める) の反映位置: spec AC-1 の対象限定 (Workspace destination)・§Scope・Behavior scenario「destination 専用経路」の Then 追記 / Test oracle AC-1 formatter 層行 / plan §Design 2「非-workspace destination の扱い」(既存 `positionText` への委譲) / §Change set (OrganizationPreviewContentTest 非-workspace case) / §Verification AC-1 行。
