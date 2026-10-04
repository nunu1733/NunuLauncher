# Layout Strategy v1

> Status: Proposed (research output of Issue #5; the accepted planner contract is [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md))
> Reviewed: 2026-08-09
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-001, FR-003, FR-015, NFR-002, NFR-003, NFR-004, NFR-006, NFR-007
> Decision gate: D-007 (layout strategy v1)
> Depends on: Issue #3 item preservation policy, Issue #2 Deck audit
> Primary scope/dependency record: Issue #5

## 1. Question and scope

この文書は、device profile から region を導出し、固定 row 前提を置かない layout strategy v1 を定義する。対象は page、region、cell、folder、Dock、locked placement、tie-break、overflow である。planner の内部実装ではなく、planning interface の契約と canonical example を提供する。

### Scope

- workspace page の決定方法と占有領域の計算
- grid 列数・行数と device profile からの導出
- folder 内 grid の決定方法
- Dock の保存規則（既定で保持）
- locked placement の占有領域保持
- 決定性を保証する tie-break 規則
- overflow と未配置 item の扱い
- phone/tablet/grid/orientation の例

### Non-goals

- 実装 code（planner の内部実装は Issue #12）
- category taxonomy（Issue #6）
- 整理ルールの file format（Issue #10 の一部）
- Lock の永続化（Issue #23）
- 空 folder の削除（Issue #24）
- Dock item の移動・追加・退避（明示的 Dock action は別 mode とし、本戦略では定義しない）

## 2. Device profile からの region 導出

### 2.1 Canonical input

snapshot adapter は platform の device profile を次の domain input へ変換する。planning interface は Android class や SQLite row を公開しない。

```text
DeviceCapabilities {
    columns: int          // 例: 4
    rows: int             // 例: 5
    hotseatSlots: int     // 例: 4
    folderMaxColumns: int // 例: 3
    folderMaxRows: int    // 例: 3
    orientation: enum     // PORTRAIT, LANDSCAPE, TWO_PANEL_PORTRAIT, TWO_PANEL_LANDSCAPE
}
```

### 2.2 Region 導出規則

固定 row の仮定を置かない。各 container の有効領域を device profile から導出する。

| Container | Region 導出 |
|---|---|
| WORKSPACE_PAGE | 全 grid cell: `(0, 0)` から `(columns - 1, rows - 1)`。D-003 の対象外 item の占有領域を除く。 |
| DOCK | 1次元 slot: `[0, hotseatSlots - 1]`。rank が slot 位置を表す。既定で保持し、本戦略では移動しない。 |
| FOLDER | 動的 grid: `folderMaxColumns × folderMaxRows` を上限とし、item 数から `FolderGridOrganizer` と同等の規則で列数・行数を決定する。 |

### 2.3 占有領域の表現

各 placement は次の矩形で表現される。`cell` は左上座標、`span` は占有セル数。座標 `(x, y)` は `x` が列、`y` が行を表す。

```text
OccupiedRegion {
    container: ContainerRef
    cell: (x, y)         // 左上セル座標
    span: (width, height) // 占有セル数
}
```

配置の有効性条件: `x >= 0 && y >= 0 && x + width <= columns && y + height <= rows`。これを満たさない placement は in-bounds invariant（NFR-002）に反し、plan を reject する。

locked placement は full region を占有制約として保持する。preserved item も同様に占有領域として扱うが、planner は移動を試みない。

### 2.4 Page 管理

snapshot は、opaque な `PageId` と canonical な `pageOrder` を持つ ordered page set を保持する。platform の screen ID と新しい page の永続ID割当は snapshot/apply adapter の責務である。planner は次を決定する。

- 既存 page の `pageOrder` を維持する。
- 空になった page は保持する（Issue #24 の決定まで削除しない）。
- 全 item が入り切らない場合、既存 page の末尾に `NewPageOrdinal` 順で新しい page を追加する。apply adapter がそのordinalを新しいopaque `PageId`とplatformの永続IDへ対応付ける。
- 新しい page の device capabilities は snapshot 時点のものを使用する。

## 3. 配置戦略と優先順位

### 3.1 全体整理の流れ

1. **占有領域の収集**: locked placement、preserved item、widget、Dock item の占有領域を固定する。
2. **移動対象の分類**: move 対象 item を category と profile で grouping する。category 信号がない場合は folder 化せず全て単体配置とする（§3.3）。
3. **Folder 配置**: 同一 profile 内で同一 category の 2 件以上の item を folder 化して配置する。
4. **単体配置**: 残りの move 対象 item を workspace page へ配置する。
5. **Overflow 処理**: 全 item が入り切らない場合の処理（§6）。

Dock は既定で保持する。Issue #3 §3.2 に従い、Dock item の rank は明示的 Dock action（将来の mode）でのみ変更する。本 layout strategy v1 は Dock action を定義せず、Dock の全 slot を固定の占有制約として扱う。

### 3.2 配置優先順位

次に従って配置を試行する。各段階で占用領域を更新する。

1. Locked placement（移動不可、占有領域を確保）
2. Preserved item — widget、app pair、legacy shortcut（移動不可、占有領域を確保）
3. Dock item（既定で保持、占有領域を確保）
4. Folder（workspace 上の 1 block として移動）
5. 単体 app/deep shortcut（空き cell へ配置）

### 3.3 Folder 配置

Folder の配置は Issue #3 §3.2 の規則に従う。

- 既存 top-level folder は workspace 上の1つのplacement unitとして移動する。既存folderの子item配置は**保持**し、plannerはfolder内座標を変更しない。
- 既存 folder の workspace 上のcell/spanは現在の表示spanを維持する。
- 新規 folder を作る場合だけ、寄与するtop-level move itemを新しい `FolderRef` へ移し、§4.1の順序でfolder内のrank/cellを導出する。container参照はplan内で有効な新しい`FolderRef`を指さなければならず、新規folderのworkspace spanは`1×1`とする。
- 同一 profile 内で同一 category の 2 件以上の move 対象 item がある場合、新規 folder を作成できる。
- **profile をまたぐ folder 化は行わない。** profile identity は Issue #3 §3.5 に従い、異なる profile の item を同一 folder へ混入しない。
- **category 信号がない場合は folder 化を行わない。** 全ての move 対象 item を単体配置する。category 信号の有無は snapshot の分類結果（Issue #6）で決まる。
- 単体 item（category に 1 件のみ、または category なし）は folder 化しない。

### 3.4 Dock の取扱い

Dock item は既定で **保持** する。Issue #3 §3.2 に従い、Dock app の rank は明示的 Dock action のみで変更する。本 layout strategy v1 では Dock action を入力として定義しないため、全 Dock slot は固定の占有制約となる。planner は Dock slot へ新規に item を追加せず、workspace から Dock へ退避させない。

## 4. 決定性の保証

### 4.1 Tie-break 規則

同じ条件で複数の配置候補がある場合、次に従って一意に決定する。

1. **Page 優先順位**: 小さい `pageOrder` を持つ page を優先する。
2. **Cell 探索順序**: 左上から右下へ走査する（`y` 優先: `(0,0) → (1,0) → ... → (columns-1, 0) → (0,1) → ...`）。これは baseline `GridOccupancy.findVacantCell` の走査順序と一致する。
3. **Folder candidate group の順序**: group key `(profile identity, category ID)` を、まずprofile identity、次にcategory IDのlocale非依存canonical byte順で整列する。
4. **同一 category 内の順序**: package name の辞書順（locale 非依存の ASCII 比較）で整列し、同名の場合はopaqueなorganizer instance IDのcanonical byte順で決定する。
5. **Folder 内の順序**: 上記と同様。ただし既存folder内配置は保持が既定であるため、新規folder作成時のみ適用する。
6. **新規 page 追加時**: 新しい page は末尾に `NewPageOrdinal` の昇順で追加する。platformの永続IDはapply adapterが割り当てる。

### 4.2 同一 page 内の fill 戦略

- 左上から右下へ走査する fill 戦略を default とする。
- 各 cell の占有状態を `GridOccupancy` と同等の方法で追跡する。
- Widget の large span が fill の妨げになる場合、その widget の占有領域を避けて配置する。

### 4.3 入力の canonicalization

- 入力 snapshot の page 順序は `pageOrder` の昇順とする。
- 入力 item の順序は opaque な organizer instance ID の昇順とする。organizer instance ID は snapshot capture 時に各配置アイテムへ割り当てられるopaqueな識別子であり、DB row IDやplatform型を公開しない（DESIGN.md §4.1）。
- 以上により、同じ snapshot から常に同じ plan が生成される。

## 5. Locked placement の取扱い

### 5.1 占有領域の固定

- Locked placement は、その cell と span で示される全領域を占有制約として固定する。
- Planner は locked placement の cell/span を変更しない。
- 他の item は locked placement の占有領域を避けて配置する。
- Locked placement の占有領域が device capabilities に収まらない場合、plan を reject する（Issue #3 の §4 に従う）。

### 5.2 Lock の種類別占有領域

| Lock 対象 | 占有領域 |
|---|---|
| 単体 app/shortcut | `cell (x, y)` の `span (1, 1)` |
| Folder | Folder の cell と span。子 item の配置は保持する（Issue #3 §3.2） |
| Widget | Widget の cell と span（`spanX × spanY`） |
| App pair | App pair の cell と span |
| Dock item | Dock の該当 slot（rank 位置） |

## 6. Overflow と未配置 item

### 6.1 Overflow の定義

既存 page に全move対象itemを配置できない場合をoverflowとする。Dock slotは固定であり、overflow解消に利用しない。各move対象itemが空のpage内に収まる場合、overflowは新規page追加で解消する。

### 6.2 Overflow 時の振る舞い

| 状況 | 挙動 |
|---|---|
| 空の新規 page に収まる | 必要な`NewPageOrdinal`を追加して配置する。page数のperformance budgetはIssue #15で定めるが、v1では数値上限を設けない。 |
| 空の新規 page にも収まらない | 例: spanがdevice capabilitiesのcolumns/rowsを超える。Rejectし、planを作成しない。Diagnosticに「capacity不足」、未配置item ID、必要spanを記録する。 |
| Full grid | 新規pageを追加して配置する。item自体が空のpageに収まらない場合だけ上記のrejectとなる。 |

### 6.3 未配置 item の明示

- Reject 時は、どの item が配置できなかったかを diagnostic に記録する。
- 未配置 item の個数と理由をユーザーに表示可能にする（FR-015）。

## 7. Phone/Tablet/Grid/Orientation の例

各例で `(x, y)` は `x` = 列、`y` = 行を表す。grid は `columns × rows` で、有効 cell は `(0, 0)` から `(columns-1, rows-1)` まで。

### 7.1 Phone portrait (4×5 grid, 4 hotseat)

```text
Input:
  DeviceCapabilities: columns=4, rows=5, hotseatSlots=4
  Move items: 10 apps (同一 category なし → 全て単体配置)
  Preserved: 2 widgets (span 2×2 at (0,0), span 4×1 at (0,2))
  Dock: 2 apps (rank 0, 1) → 保持、占有領域として固定
  Locked: 1 app at cell (3, 4)

Grid layout (4 columns × 5 rows):
  Row 0: [W1] [W1] [..] [..]
  Row 1: [W1] [W1] [..] [..]
  Row 2: [W2] [W2] [W2] [W2]
  Row 3: [..] [..] [..] [..]
  Row 4: [..] [..] [..] [L ]

  W1 = widget 1 (span 2×2 at (0,0))
  W2 = widget 2 (span 4×1 at (0,2))
  L  = locked app (at (3,4))

Process:
  1. Dock apps (rank 0, 1) → 保持、固定
  2. Widget 1 at (0,0) span 2×2 → 占有領域固定
  3. Widget 2 at (0,2) span 4×1 → 占有領域固定
  4. Locked app at (3,4) → 占有領域固定
  5. 10 apps を空き cell へ配置
     - 空き cell: (2,0),(3,0),(2,1),(3,1),(0,3),(1,3),(2,3),(3,3),(0,4),(1,4),(2,4) = 11 cell
     - 10 apps < 11 cell → 全 item 配置可能
     - 左上から fill: (2,0),(3,0),(2,1),(3,1),(0,3),(1,3),(2,3),(3,3),(0,4),(1,4)

Output:
  Page 1: 10 apps + 2 widgets + 1 locked app (19 cell 使用)
  Dock: 2 apps（変更なし）
```

### 7.2 Phone landscape (4×3 grid, 4 hotseat)

```text
Input:
  DeviceCapabilities: columns=4, rows=3, hotseatSlots=4
  Move items: 10 apps (同一 category なし → 全て単体配置)
  Preserved: 1 widget (span 2×2 at (0,0))
  Dock: 2 apps (rank 0, 1) → 保持
  Locked: 1 app at cell (3, 2)

Grid layout (4 columns × 3 rows):
  Row 0: [W ] [W ] [..] [..]
  Row 1: [W ] [W ] [..] [..]
  Row 2: [..] [..] [..] [L ]

  W = widget (span 2×2 at (0,0))
  L = locked app (at (3,2))

Process:
  1. Dock apps (rank 0, 1) → 保持、固定
  2. Widget at (0,0) span 2×2 → 占有領域固定
  3. Locked app at (3,2) → 占有領域固定
  4. 10 apps を空き cell へ配置
     - Page 1 空き cell: (2,0),(3,0),(2,1),(3,1),(0,2),(1,2) = 6 cell
     - 6 apps を Page 1 へ配置、4 apps 不足 → 新規 page 追加
     - Page 2 (4×3 = 12 cell): 4 apps を配置

Output:
  Page 1: 6 apps + 1 widget + 1 locked app (11 cell 使用)
  Page 2: 4 apps
  Dock: 2 apps（変更なし）
```

### 7.3 Tablet (6×5 grid, 6 hotseat)

```text
Input:
  DeviceCapabilities: columns=6, rows=5, hotseatSlots=6
  Move items: 20 apps (同一 category なし → 全て単体配置)
  Preserved: なし
  Dock: 4 apps (rank 0-3) → 保持
  Locked: なし

Process:
  1. Dock apps (rank 0-3) → 保持、固定
  2. 全 20 apps を 1 page の 6×5 = 30 cell へ配置
  3. 左上から fill: 20 cell 使用、10 cell 空き

Output:
  Page 1: 20 apps
  Dock: 4 apps（変更なし）
```

### 7.4 Folder 化を含む例

```text
Input:
  DeviceCapabilities: columns=4, rows=5, hotseatSlots=4
  Move items: 15 apps
    Category A: 3 apps (同一 profile)
    Category B: 5 apps (同一 profile)
    Category なし: 7 apps
  Preserved: なし
  Dock: なし
  Locked: なし

Process:
  1. Category A (3 apps, 同一 profile) → 新規 folder A (span 1×1)へ移動
  2. Category B (5 apps, 同一 profile) → 新規 folder B (span 1×1)へ移動
     ※ 新規folderの子itemはpackage name、同名時はorganizer instance ID順でrank/cellを導出する。既存folderの子item配置だけは保持する。
  3. Category なし 7 apps → 単体配置
  4. 合計: 2 folder + 7 apps = 9 placement → 1 page の 4×5 = 20 cell に収まる
  5. 左上から fill:
     (0,0)=folder A, (1,0)=folder B,
     (2,0)=app1, (3,0)=app2,
     (0,1)=app3, (1,1)=app4, (2,1)=app5, (3,1)=app6, (0,2)=app7

Output:
  Page 1: 2 folders + 7 apps (9 cell 使用)
  Dock: 変更なし
```

### 7.5 Widget と locked placement の混在例

```text
Input:
  DeviceCapabilities: columns=5, rows=5, hotseatSlots=5
  Move items: 8 apps (同一 category なし → 全て単体配置)
  Preserved: 1 widget (span 3×2 at (0,0))
  Dock: 3 apps (rank 0-2) → 保持
  Locked: 1 app at cell (4, 4)

Grid layout (5 columns × 5 rows):
  Row 0: [W ] [W ] [W ] [..] [..]
  Row 1: [W ] [W ] [W ] [..] [..]
  Row 2: [..] [..] [..] [..] [..]
  Row 3: [..] [..] [..] [..] [..]
  Row 4: [..] [..] [..] [..] [L ]

  W = widget (span 3×2 at (0,0))
  L = locked app (at (4,4))

Process:
  1. Dock apps (rank 0-2) → 保持、固定
  2. Widget at (0,0) span 3×2 → 占有領域固定
  3. Locked app at (4,4) → 占有領域固定
  4. 8 apps を空き cell へ配置
     - 空き cell: (3,0),(4,0),(3,1),(4,1) + Row 2-3 全 10 cell + (0,4),(1,4),(2,4),(3,4) = 18 cell
     - 8 apps < 18 cell → 全 item 配置可能
     - 左上から fill: (3,0),(4,0),(3,1),(4,1),(0,2),(1,2),(2,2),(3,2)

Output:
  Page 1: 8 apps + 1 widget + 1 locked app (15 cell 使用)
  Dock: 3 apps（変更なし）
```

## 8. 冪等性の例

### 8.1 冪等性の定義

plan を適用した後の状態に対して、同じ rule で同じ全体整理を行った場合、空の差分（変更なし）を返す。

### 8.2 例

```text
Input (1回目):
  Page 1: app A at (0,0), app B at (1,0), app C at (2,0)
  Rule: alphabetical order, fill from top-left

Plan (1回目):
  A → (0,0), B → (1,0), C → (2,0)  // 既に整列済み、変更なし

Input (2回目、plan 適用後):
  Page 1: app A at (0,0), app B at (1,0), app C at (2,0)

Plan (2回目):
  空の差分（変更なし）
```

```text
Input (1回目):
  Page 1: app C at (0,0), app B at (1,0), app A at (2,0)
  Rule: alphabetical order, fill from top-left

Plan (1回目):
  A → (0,0), B → (1,0), C → (2,0)

Input (2回目、plan 適用後):
  Page 1: app A at (0,0), app B at (1,0), app C at (2,0)

Plan (2回目):
  空の差分（変更なし）
```

## 9. D-007 の提案

D-007 の提案: **device profile から region を導出し、固定 row 前提を置かない layout strategy を採用する。**

- 各 container の region は device profile の `columns`, `rows`, `hotseatSlots`, `folderMaxColumns`, `folderMaxRows` から導出する。
- Fill 戦略は左上から右下への走査を default とする。
- Tie-break は folder group、package name、organizer instance ID のcanonical順で決定する。
- Overflow 時は空の新規pageに収まるitemを追加pageへ配置し、空pageにも収まらないitemだけをrejectする。
- Dock は既定で保持し、明示的 Dock action のみが変更を許される。

## 10. 代表的な fixture

| # | Fixture | 期待結果 |
|---|---|---|
| L-01 | 空の workspace | 空の plan（変更なし） |
| L-02 | 単一 page の app のみ | 左上から fill、全 item 配置 |
| L-03 | 複数 page の app | 各 page を左上から fill、page 間で item が移動しない |
| L-04 | Widget 混在 | Widget の占有領域を避けて app を配置 |
| L-05 | Locked placement 混在 | Locked 領域を避けて配置、locked 領域内に配置しない |
| L-06 | Folder 混在 | Folder を 1 block として移動、子 item は保持 |
| L-07 | Dock item 混在 | Dock の slot を全て保持、workspace item は Dock へ退避しない |
| L-08 | Full grid（全move対象itemが空pageに収まる） | 新規pageを追加し、全itemを配置 |
| L-09 | 複数 category の folder 化 | 同一 profile 内の 2 件以上の同一 category を folder 化、単体は folder 化しない |
| L-10 | Portrait → Landscape 変更 | Device profile 変更後は stale、再 capture が必要 |
| L-11 | 冪等性: 既に整列済み | 空の差分 |
| L-12 | 決定性: 同一入力を 2 回 | 同一の plan |
| L-13 | Phone portrait 4×5 | §7.1 の例 |
| L-14 | Phone landscape 4×3 | §7.2 の例 |
| L-15 | Tablet 6×5 | §7.3 の例 |
| L-16 | Folder 化を含む | §7.4 の例 |
| L-17 | Widget と locked placement の混在 | §7.5 の例 |
| L-18 | Category 信号なし | 全 move 対象を単体配置、folder 化なし |
| L-19 | Cross-profile 同一 category | profile ごとに独立して folder 化、cross-profile folder なし |
| L-20 | 空pageにも収まらないspan | Reject、diagnosticに未配置item IDと必要spanを記録 |

## 11. 未決定事項と後続 Issue への制約

| 項目 | 制約 / open point |
|---|---|
| Category 信号の有無 | category 信号がない場合は folder 化を行わず全て単体配置する。category 信号の供給は Issue #6 で解決する。 |
| Folder 内 grid の決定 | `FolderGridOrganizer` の規則と同等とする。詳細は planner 実装（Issue #12）で具体化する。ただし既存 folder の子 item 配置は保持する。 |
| 新規 page の上限 | v1では空pageに収まる全move対象itemに必要なpageを追加する。page数のperformance budgetはIssue #15で定める。 |
| Empty folder の削除 | Issue #24 の決定を待つ。本戦略では空 folder を保持する。 |
| Lock の永続化 | [ADR-0004](../adr/0004-organizer-lock-persistence.md)がownershipとmigrationを定義する。本戦略はlockの振る舞いのみを定義する。 |
| Dock action | 明示的 Dock action（rank 変更、workspace 退避等）は別 mode として本戦略では定義しない。将来の Issue で入力 mode を定義する。 |
| Rule との統合 | 整理ルールの file format が決定後（Issue #10 の一部）、本戦略の規則を rule として表現可能にする。 |

## 12. 根拠

全ての source 参照は baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b` に固定する。確認日: 2026-08-09。

- [DeviceProfile.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/DeviceProfile.java) — cell 計算、workspace/hotseat/folder の grid 定義
- [InvariantDeviceProfile.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/InvariantDeviceProfile.java) — `numRows`, `numColumns`, `numFolderRows`, `numFolderColumns`, `numShownHotseatIcons`
- [GridOccupancy.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/util/GridOccupancy.java) — `findVacantCell` の走査順序
- [CellLayout.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/CellLayout.java) — `findNearestVacantArea`、`mCountX`/`mCountY`
- [FolderGridOrganizer.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/folder/FolderGridOrganizer.java) — `calculateGridSize`、`getPosForRank`
- [WorkspaceItemSpaceFinder.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/WorkspaceItemSpaceFinder.java) — `findSpaceForItem` の page 管理
- [LauncherSettings.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/LauncherSettings.java) — `Favorites` table の container/screen/cell/span 定義
- [WorkspaceLayoutManager.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/WorkspaceLayoutManager.java) — screen ID 管理
- [device_profiles.xml](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/res/xml/device_profiles.xml) — grid option の定義
