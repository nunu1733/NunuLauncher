# Layout Strategy v1

> Status: Proposed (research output of Issue #5)
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
- Dock の slot 管理
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

## 2. Device profile からの region 導出

### 2.1 Canonical input

planner は次の入力を snapshot から受け取る。Android class や SQLite row を公開しない。

```text
DeviceProfile {
    columns: int          // numColumns（例: 4）
    rows: int             // numRows（例: 5）
    hotseatSlots: int     // numShownHotseatIcons（例: 4）
    folderMaxColumns: int // numFolderColumns（例: 3）
    folderMaxRows: int    // numFolderRows（例: 3）
    orientation: enum     // PORTRAIT, LANDSCAPE, TWO_PANEL_PORTRAIT, TWO_PANEL_LANDSCAPE
}
```

### 2.2 Region 導出規則

固定 row の仮定を置かない。各 container の有効領域を device profile から導出する。

| Container | Region 導出 |
|---|---|
| WORKSPACE_PAGE | 全 grid cell: `(0, 0)` から `(columns - 1, rows - 1)`。D-003 の対象外 item の占有領域を除く。 |
| DOCK | 1次元 slot: `[0, hotseatSlots - 1]`。rank が slot 位置を表す。 |
| FOLDER | 動的 grid: `folderMaxColumns × folderMaxRows` を上限とし、item 数から `FolderGridOrganizer` と同等の規則で列数・行数を決定する。 |

### 2.3 占有領域の表現

各 placement は次の矩形で表現される。`cell` は左上座標、`span` は占有セル数。

```text
OccupiedRegion {
    container: ContainerRef
    cell: (x, y)
    span: (width, height)
}
```

locked placement は full region を占有制約として保持する。preserved item も同様に占有領域として扱うが、planner は移動を試みない。

### 2.4 Page 管理

snapshot は現在の ordered page set を保持する。planner は次を決定する。

- 既存 page の順序を維持する。
- 空になった page は保持する（Issue #24 の決定まで削除しない）。
- 全 item が入り切らない場合、既存 page の末尾に新しい page を追加する。
- 新しい page の device profile は snapshot 時点のものを使用する。

## 3. 配置戦略と優先順位

### 3.1 全体整理の流れ

1. **占有領域の収集**: locked placement、preserved item、widget の占有領域を固定する。
2. **移動対象の分類**: move 対象 item を category（未実装の場合は category なし）で grouping する。
3. **Dock 配置**: Dock の move 対象 item を既存 slot の空きまたは新規 slot へ配置する。
4. **Folder 配置**: category が 2 件以上の item を folder 化して配置する。
5. **単体配置**: 残りの move 対象 item を workspace page へ配置する。
6. **Overflow 処理**: 全 item が入り切らない場合の処理（§6）。

### 3.2 配置優先順位

次に従って配置を試行する。各段階で占用領域を更新する。

1. Locked placement（移動不可、占有領域を確保）
2. Preserved item（移動不可、占有領域を確保）
3. Widget（移動不可、占有領域を確保）
4. Dock item（Dock slot 内でのみ移動可能）
5. Folder（workspace 上の block として移動）
6. 単体 app/deep shortcut（空き cell へ配置）

### 3.3 Folder 配置

- 同一 category の 2 件以上の item を 1 つの folder にまとめる。
- Folder 自体の span は `FolderGridOrganizer` の計算結果に基づく（folder 内 grid の列数 × 行数から最小の workspace span を導出）。
- Folder の中身は folder 内 grid に従って配置する。子 item の workspace 上の座標は folder の cell からの相対位置とする。
- 単体 item は folder 化しない。

### 3.4 Dock 配置

- 既存の Dock item は rank 順に slot を占有する。
- 新規に Dock へ配置する item は、空き slot または末尾に追加する。
- Dock の capacity 超過時は、Dock に収まらない item を workspace へ移動する。

## 4. 決定性の保証

### 4.1 Tie-break 規則

同じ条件で複数の配置候補がある場合、次に従って一意に決定する。

1. **Page 優先順位**: 小さい screen ID を持つ page を優先する。
2. **Cell 探索順序**: 左上から右下へ走査する（`y` 優先: `(0,0) → (1,0) → ... → (columns-1, 0) → (0,1) → ...`）。これは baseline `GridOccupancy.findVacantCell` の走査順序と一致する。
3. **同一 category 内の順序**: package name の辞書順（locale 非依存の ASCII 比較）で整列する。
4. **Folder 内の順序**: 上記と同様、package name の辞書順。
5. **Dock slot 順序**: 小さい rank から順に割り当てる。
6. **新規 page 追加時**: 新しい page は既存の最大 screen ID + 1 を割り当てる。

### 4.2 同一 page 内の fill 戦略

- 左上から右下へ走査する fill 戦略を default とする。
- 各 cell の占有状態を `GridOccupancy` と同等の方法で追跡する。
- Widget の large span が fill の妨げになる場合、その widget の占有領域を避けて配置する。

### 4.3 入力の canonicalization

- 入力 snapshot の page 順序は screen ID の昇順とする。
- 入力 item の順序は `_id` の昇順とする。
- 以上により、同じ snapshot から常に同じ plan が生成される。

## 5. Locked placement の取扱い

### 5.1 占有領域の固定

- Locked placement は、その cell と span で示される全領域を占有制約として固定する。
- Planner は locked placement の cell/span を変更しない。
- 他の item は locked placement の占有領域を避けて配置する。
- Locked placement の占有領域が device profile に収まらない場合、plan を reject する（Issue #3 の §4 に従う）。

### 5.2 Lock の種類別占有領域

| Lock 対象 | 占有領域 |
|---|---|
| 単体 app/shortcut | `cell (x, y)` の `span (1, 1)` |
| Folder | Folder の cell と span、および子 item の全占有領域 |
| Widget | Widget の cell と span（`spanX × spanY`） |
| App pair | App pair の cell と span |
| Dock item | Dock の該当 slot（rank 位置） |

## 6. Overflow と未配置 item

### 6.1 Overflow の定義

全 move 対象 item を既存 page + 新規 page に配置しても収まらない場合を overflow とする。

### 6.2 Overflow 時の振る舞い

| 状況 | 振る舞い |
|---|---|
| 新規 page 追加で収まる | 新規 page を追加して配置する。追加 page 数に上限は設けない（NFR-006 の budget 内で処理する）。 |
| 新規 page 追加でも収まらない | Reject し、plan を作成しない。Diagnostic に「capacity 不足」と未配置 item 数を記録する。 |
| Overflow が発生する fixture | F-11a（full grid without overflow）は reject。F-11b（full grid with candidate overflow）は Issue #5 の決定を待つ。 |

### 6.3 未配置 item の明示

- Reject 時は、どの item が配置できなかったかを diagnostic に記録する。
- 未配置 item の個数と理由をユーザーに表示可能にする（FR-015）。

## 7. Phone/Tablet/Grid/Orientation の例

### 7.1 Phone portrait (4×5 grid, 4 hotseat)

```text
Input:
  DeviceProfile: columns=4, rows=5, hotseatSlots=4
  Move items: 12 apps (同一 category なし)
  Preserved: 2 widgets (span 2×2, 4×1)
  Locked: 1 app at cell (0, 4)

Process:
  1. Locked app at (0, 4) → 占有領域固定
  2. Widget 1 at (0, 0) span 2×2 → 占有領域固定
  3. Widget 2 at (2, 0) span 4×1 → 占有領域固定
  4. 12 apps を空き cell へ配置
     - 空き領域: (0,2)-(3,4) の 3行×4列 = 12 cell → 全 item 配置可能
     - 1 page で完了

Output:
  Page 1: 12 apps + 2 widgets + 1 locked app
  Dock: 未変更（Dock item なし）
```

### 7.2 Phone landscape (4×3 grid, 4 hotseat)

```text
Input:
  DeviceProfile: columns=4, rows=3, hotseatSlots=4
  Move items: 12 apps
  Preserved: 2 widgets (span 2×2, 4×1)
  Locked: 1 app at cell (0, 2)

Process:
  1. Locked app at (0, 2) → 占有領域固定
  2. Widget 1 at (0, 0) span 2×2 → 占有領域固定
  3. Widget 2 at (2, 0) span 4×1 → 占有領域固定
  4. 12 apps を空き cell へ配置
     - 空き領域: Widget 2 の下 (2,1)-(3,2) の 2行×2列 = 4 cell
     - 不足: 8 cell → 新規 page 追加
     - Page 2: 全12 column × 3 row = 12 cell → 全 item 配置可能

Output:
  Page 1: 4 apps + 2 widgets + 1 locked app
  Page 2: 8 apps
  Dock: 未変更
```

### 7.3 Tablet (6×5 grid, 6 hotseat)

```text
Input:
  DeviceProfile: columns=6, rows=5, hotseatSlots=6
  Move items: 20 apps
  Preserved: なし
  Locked: なし

Process:
  1. 全 20 apps を 1 page の 6×5 = 30 cell へ配置
  2. 左上から fill: 20 cell 使用、10 cell 空き

Output:
  Page 1: 20 apps
  Dock: 未変更
```

### 7.4 Folder 化を含む例

```text
Input:
  DeviceProfile: columns=4, rows=5, hotseatSlots=4
  Move items: 15 apps (category A: 3, category B: 5, その他: 7)
  Preserved: なし
  Locked: なし

Process:
  1. Category A (3 apps) → folder 化: folder (span 2×2 推奨)
  2. Category B (5 apps) → folder 化: folder (span 3×2 推奨)
  3. その他 7 apps → 単体配置
  4. 合計: 2 folder + 7 apps = 9 placement → 1 page の 4×5 = 20 cell に収まる

Output:
  Page 1: Folder A (3 apps), Folder B (5 apps), 7 apps
  Dock: 未変更
```

### 7.5 Dock 超過

```text
Input:
  DeviceProfile: columns=4, rows=5, hotseatSlots=4
  Move items: 6 apps (全て Dock 対象)
  Preserved Dock items: 2 apps (rank 0, 1)
  Locked: なし

Process:
  1. Dock 既存: 2 items (rank 0, 1) → 占有 slot 0, 1
  2. Dock 空き: slot 2, 3
  3. 新規 Dock 配置: 2 items → slot 2, 3
  4. 残り 4 items → workspace へ配置（Dock 超過分）

Output:
  Page 1: 4 apps (Dock 超過分)
  Dock: 2 preserved + 2 new = 4 items
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
- Tie-break は package name 辞書順（ASCII 比較）で決定する。
- Overflow 時は新規 page 追加を試行し、それでも収まらない場合は reject する。

## 10. 代表的な fixture

| # | Fixture | 期待結果 |
|---|---|---|
| L-01 | 空の workspace | 空の plan（変更なし） |
| L-02 | 単一 page の app のみ | 左上から fill、全 item 配置 |
| L-03 | 複数 page の app | 各 page を左上から fill、page 間で item が移動しない |
| L-04 | Widget 混在 | Widget の占有領域を避けて app を配置 |
| L-05 | Locked placement 混在 | Locked 領域を避けて配置、locked 領域内に配置しない |
| L-06 | Folder 混在 | Folder を block として移動、子 item は folder 内 grid に従う |
| L-07 | Dock item 混在 | Dock の slot を保持、超過分は workspace へ |
| L-08 | Full grid（capacity 不足） | Reject、diagnostic に未配置 item 数を記録 |
| L-09 | 複数 category の folder 化 | 2 件以上の category を folder 化、単体は folder 化しない |
| L-10 | Portrait → Landscape 変更 | Device profile 変更後は stale、再 capture が必要 |
| L-11 | 冪等性: 既に整列済み | 空の差分 |
| L-12 | 決定性: 同一入力を 2 回 | 同一の plan |
| L-13 | Phone portrait 4×5 | §7.1 の例 |
| L-14 | Phone landscape 4×3 | §7.2 の例 |
| L-15 | Tablet 6×5 | §7.3 の例 |
| L-16 | Folder 化を含む | §7.4 の例 |
| L-17 | Dock 超過 | §7.5 の例 |

## 11. 未決定事項と後続 Issue への制約

| 項目 | 制約 / open point |
|---|---|
| Category 分類 | category が未実装の場合、全部の move 対象を同一 category として扱う（Issue #6 で解決） |
| Folder 内 grid の決定 | `FolderGridOrganizer` の規則と同等とする。詳細は planner 実装（Issue #12）で具体化する |
| 新規 page の上限 | 明示的な上限を設けない。NFR-006 の budget 内で処理する（Issue #15） |
| Empty folder の削除 | Issue #24 の決定を待つ。本戦略では空 folder を保持する |
| Lock の永続化 | Issue #23 の決定を待つ。本戦略では lock の振る舞いのみ定義する |
| Rule との統合 | 整理ルールの file format が決定後（Issue #10 の一部）、本戦略の規則を rule として表現可能にする |

## 12. 根拠

全ての source 参照は baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b` に固定する。

- `DeviceProfile.java` — cell 計算、workspace/hotseat/folder の grid 定義
- `InvariantDeviceProfile.java` — `numRows`, `numColumns`, `numFolderRows`, `numFolderColumns`, `numShownHotseatIcons`
- `GridOccupancy.java` — `findVacantCell` の走査順序
- `CellLayout.java` — `findNearestVacantArea`、`mCountX`/`mCountY`
- `FolderGridOrganizer.java` — `calculateGridSize`、`getPosForRank`
- `WorkspaceItemSpaceFinder.java` — `findSpaceForItem` の page 管理
- `LauncherSettings.java` — `Favorites` table の container/screen/cell/span 定義
- `WorkspaceLayoutManager.java` — screen ID 管理
- `device_profiles.xml` — grid option の定義