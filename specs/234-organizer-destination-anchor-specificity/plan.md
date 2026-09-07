# Implementation Plan: Organizer destination 表示の anchor 一意化

> Issue: #234
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

[Spec 212 assessment](../../docs/assessment/issue-212-organizer-destination-verification.md) の Case A 判定 (すべて source-verified @ `ceb1e287d5`) と、現行 `main` での再確認に基づく。

### 現在の表示経路 (destination が曖昧になる箇所)

1. `PlanPreviewProjector.PositionContext.workspacePosition` (`lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt:443-452`) が identity (`PreviewPlacementIdentity.Workspace(pageDisplayOrdinal, isNewPage, cellX, cellY)`) を `PreviewPosition.Workspace(pageDisplayOrdinal, isNewPage, rowBand, columnBand, rowOrdinal)` へ変換する。band は `floor(coord * 3 / dimension)` (clamped)、`rowOrdinal = cellY + 1`。**column 方向の ordinal はこの変換で落ちる**。
2. `OrganizationPreviewContent.positionText` → `workspacePosition` format (`organizer/ui/OrganizationPreviewContent.kt:291-313`) が `«page 語»` + `«領域語»` を組み立てる (`manual_organization_preview_position_workspace` = `%2$s, %1$s` / `%1$s・%2$s`)。
3. move 行: `moveRowText` (`OrganizationPreviewContent.kt:276-289`) — 通常 move は `«descriptor» → «destination»(«row ordinal note») («理由»)`。same-band adjustment は `«descriptor» → position adjusted within «領域»(«row ordinal note»)`。`rowOrdinalNote` (`:355-362`) は同 page・同 row band・row ordinal 変化時のみ出るため、**同 band・同行序数の列方向調整は destination 側の差分表示がゼロ**になる。
4. destination 側は descriptor 側と異なり collision 補助語の対象外 (`descriptorSupplements` は source descriptor のみに適用)。

### 確定済みの事実 (確認済み)

- `PreviewPosition.Workspace` を消費するのは production 2 file (`OrganizationPreviewContent.kt`、`ManualOrganizationPreferences.kt` の wording 実装) と test 5 file のみ (`grep PreviewPosition` で全列挙済み)。field 追加の波及範囲は閉じている。
- instrumentation test `distinctAnchorsInsideOneBandRenderIdenticalDestinationTextOnTheCard` (`tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt:519`) が現行曖昧さの user-visible evidence として存在する。fix 後は期待表示を更新して R1 PASS の反転 evidence にする (spec AC-7)。
- `DestinationRegionMappingTest` が band rule・F-03 shape・`visibleCandidates == 4` を characterization として固定している。fix 後は主張を反転させる (spec AC-4)。
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

2. **formatter の destination 経路** (`organizer/ui/OrganizationPreviewContent.kt`)

   - `positionText` の `Workspace` 分岐が column 表示序数を含む destination 語を組み立てる。領域語 (`regionText`) は維持し、anchor を一意にする表示序数を付加する。
   - `moveRowText`: 通常 move の destination は新語へ。same-band adjustment 行は「調整先の列表示序数」を destination 部分に含める (行序数 note の既存分岐は維持)。
   - `rowOrdinalNote` の分岐条件は無変更 (R5)。ただし same-band 行で note が出ないケース (同 row ordinal) でも列方向の区別は destination 語自体が担うため、曖昧さは残らない。
   - `descriptorSupplements` / `identitySupplement` / `workspaceLocator` (#208 collision 補助語) は無変更。語彙の重複 (`supplementCell` と新 destination 語) が気になる場合は copy review で語を分ける (例: destination は「行列」を明示する別 string)。

3. **strings** (`lawnchair/res/values/strings.xml` + `values-ja/strings.xml`)

   - destination 用の表示序数 string を追加 (案: `manual_organization_preview_anchor_cell` = `row %1$d, column %2$d` / `%1$d行目%2$d列目` — copy は実装 PR で owner review)。
   - 既存 `manual_organization_preview_position_workspace` / `manual_organization_preview_same_band_move_row` の template 変更有無は copy 決定に従う。placeholder は positional `%n$` を維持 (#123 契約)。

4. **wording interface** (`OrganizationPreviewWording`)

   - destination 具体性に必要な property 追加のみ。既存 property の意味変更はしない。`ResourceOrganizationPreviewWording` (`ManualOrganizationPreferences.kt:936`) と test 用 literal 実装 (`OrganizationPreviewContentTest`) の両方へ機械的追従。

### Data flow

```text
intended placement (不変)
  → identity (cellX, cellY 保持, 不変)
  → PreviewPosition.Workspace (+ columnOrdinal 追加)
  → positionText / moveRowText (destination 語に表示序数を織り込む)
  → rendered card (anchor 一意)
```

出力: 変更は `PreviewChange` の表示語のみ。plan / action / write-set / persisted state は 1 byte も変わらない。error: 新たな error path なし (projection の fail-closed 条件は無変更)。

### Alternatives rejected

- **destination 側へ #208 型の collision-local 補助語を適用**: 衝突検出が proposal 内の行集合に依存するため、単独 move の表示でも anchor 候補が 1 個に絞られず `visibleCandidates == {resolved anchor}` を満たさない。destination は常時 anchor 一意であることを契約とする (spec AC-1)。
- **生 cell 座標 (`cell 0,1`) の常時表示**: Issue 受入条件が exact `(cellX, cellY)` を必須とせず、生座標は spec 194/208 の「raw 値を表示しない」privacy 調性と衝突する。1-based 表示序数で十分に一意である (row ordinal が既に同方式)。
- **band label を廃止して row/column のみにする**: 領域語は「画面のどのあたりか」の第一読解を提供し、既存 copy と test の互換性が高い。anchor 一意性は付加語で達成できるため、置換は不要。
- **formatter が `deviceCapabilities` から座標を再計算**: spec 208 §4 の単一導出経路契約違反。projection が供給する値のみを使う (spec AC-3)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` | `PreviewPosition.Workspace` へ `columnOrdinal` を追加 (KDoc 更新) | 表示 data の正本は projection。formatter 再計算を禁じる単一経路契約 (AC-3) |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` | `workspacePosition` が `cellX + 1` を詰める | identity → position の単一変換点 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | destination 語の構築 (positionText / moveRowText) を anchor 一意へ。wording interface 追加 | presentation 層の formatter。R5 deterministic は純粋関数で維持 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `ResourceOrganizationPreviewWording` へ追従 | production wording 実装 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | destination 表示序数 string 追加 / template 更新 (en + ja 同時) | #123 契約 |
| `tests/unit/.../application/preview/DestinationRegionMappingTest.kt` | F-03 主張の反転 (同一 destination → 区別される destination + 候補 1 個)、grid 境界 parameterized 主張 | R1 未達 characterization の正本。fix と同じ PR で期待を反転 |
| `tests/unit/.../application/preview/PlanPreviewProjectorTest.kt` | `columnOrdinal` 導出と total function の追従 | projection seam test |
| `tests/unit/.../ui/OrganizationPreviewContentTest.kt` | 同帯・同行序数の列方向調整 fixture、branch 分岐 fixture、copy 主張 | 純粋行構築の主張対象 |
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
| AC-1 anchor 一意性 | `DestinationRegionMappingTest` (反転後): F-03 fixture で 2 行の destination 表示が異なり、各表示の解釈候補が 1 個。2/4/5 column の parameterized 主張 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.preview.*'` |
| AC-2 同帯列方向調整 | `OrganizationPreviewContentTest`: 同帯・同行序数の列方向調整行の destination が調整先 cell を一意に特定 | 同上 `--tests 'app.lawnchair.organizer.ui.*'` |
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
- **regression boundary の扱い**: `PreviewApplyPlacementEqualityTest` / `PreviewApplyPersistedPlacementEqualityTest` の diff は constructor 追従のみであることを PR review で明示する。`DestinationRegionMappingTest` は「R1 未達 evidence」から「fix 後契約」への役割変化を KDoc に記録する。

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
- [ ] Tests fail for the missing behavior: `DestinationRegionMappingTest` の反転主張を先に書き、現行表示で fail することを確認
- [ ] Minimal implementation completed: projection field → projector → wording interface → formatter → strings (en/ja) → wording 実装の順
- [ ] placement equality / branch stability の既存 test が無変更で pass
- [ ] Full relevant verification completed: JVM gate + instrumentation job の成功を PR へ記録
- [ ] PR evidence and remaining risks recorded: `Closes #234`、copy 決定の owner review 記録、残余 risk (copy 語順の LQA feedback 等) を明記
