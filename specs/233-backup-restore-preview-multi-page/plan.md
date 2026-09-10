# Implementation Plan: Home backup復元previewでの複数page保存内容の確認

> Issue: #233
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

Baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` (origin/main, 2026-09-10) 時点の確認。推測は含まない。

### 復元側 (表示箇所)

- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt`
  - portrait の `RestoreBackupOptions` が `DummyLauncherBox` 内に `backup.screenshot` を単一 `Image` (`contentDescription = null`, `ContentScale.FillHeight`) で表示する (L146-174)。pager・page 切替・caption は存在しない。
- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt`
  - `init()` で `LawnchairBackup(context, uri).readInfoAndPreview()` を呼ぶのみ。page 情報は持たない。
- `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt`
  - `readInfoAndPreview()` は zip から `info` (proto)・`screenshot.png`・`wallpaper.png` のみを読む。`BackupInfo` に page 情報はない (`previewWidth` / `previewHeight` / `previewDarkText` / `gridState` / `contents` / `createdAt` / `lawnchairVersion` / `backupVersion`)。
  - zip は `getFiles(context, forRestore)` のとおり `launcher.db` (全 favorites を含む)、prefs 関連 file を格納する。restore (`restore()`) は critical section (#58, ADR-0011) 内で全 file を書き戻す。**restore scope は全 page**。

### 作成側 (制約の発生箇所)

- `lawnchair/src/app/lawnchair/backup/ui/CreateBackupViewModel.kt` — `captureScreenshot()` が `LauncherPreviewView` で bitmap 1枚を生成。
- `lawnchair/src/app/lawnchair/views/LauncherPreviewView.kt` — `loadModelData()` → `LauncherPreviewRenderer.getRenderedView()`。
- `src/com/android/launcher3/graphics/LauncherPreviewRenderer.java` (AOSP/upstream 由来)
  - `launcher_preview_layout` を inflate し workspace は `FIRST_SCREEN_ID` 1枚のみ (two-panel のみ `SECOND_SCREEN_ID` 追加、L253-268)。
  - `populate()` は `filterCurrentWorkspaceItems(...)` で先頭画面以外を `otherWorkspaceItems` へ分離し (L504-517)、それを描画に使用しない。

### 結論 (確認済み事実)

先頭 page のみの preview は regression ではなく、upstream Lawnchair の backup 仕様 (screenshot 1枚方式) と AOSP preview renderer の構造に由来する。backup serialization は全 page を保持しており、preview のみが限定されている。Issue の「調査事項」のうち1つ目・3つ目はこれで解決済み。2つ目 (upstream 同等 Issue の有無) は Open questions として残る (実装 blocking なし)。

## Design

### Modules and interfaces

変更は `app.lawnchair` 配下 (fork 固有領域) のみ。`com.android.launcher3` / AOSP path への変更・patch なし。

1. **page 要約の解析module (新規)**: `app.lawnchair.backup` 配下に新設 (例: `BackupPageSummaryReader`)。
   - 入力: backup uri (既に画面が保持するもの)。
   - 責務: zip を読み取り専用で開き、`launcher.db` entry を cache 作業 file へ展開 → SQLite で read-only open → `favorites` の `container = -100` (workspace) 行を `screen` で group 化し、page 数と page 別の item/folder/widget 件数を返す → 作業 file を削除。
   - 出力: typed result (`PageSummary(pages: List<PageSummaryEntry>)` / `PageSummaryUnavailable`)。platform 型 (Cursor 等) を interface に漏らさない。`BackupInfo` proto は拡張しない。
   - 既存の `LawnchairBackup.readZip` の handler 機構は private であるため、独立した ZipInputStream 読み取りとして実装する (重複する開閉処理は許容。`LawnchairBackup` への公開 API 追加は実装時に必要性で判断)。
2. **ViewModel 拡張**: `RestoreBackupViewModel` — `readInfoAndPreview()` 成功後、contents が layout を含む場合のみ解析を起動し、その state を `RestoreBackupUiState.Success` へ追加する。解析失敗は restore 可否へ影響させない。
3. **UI**: `RestoreBackupScreen.kt` — caption (先頭 page のみである旨) と page 要約 (page 数 + 各 page の件数) を `DummyLauncherBox` 直下に追加。single-page や解析失敗は spec の分岐どおり。
4. **strings**: `values/` / `values-ja/` へ追加。

### Data flow

```
RestoreBackup route (uri)
  -> RestoreBackupViewModel.init
       -> LawnchairBackup.readInfoAndPreview()      (既存)
       -> [新規] contents has INCLUDE_LAYOUT_AND_SETTINGS ?
            -> BackupPageSummaryReader.read(uri)
                 -> PageSummary | PageSummaryUnavailable
  -> RestoreBackupScreen
       -> screenshot preview (既存)
       -> caption + page summary (新規, state で分岐)
```

- 解析は `Dispatchers.IO` で実行し、restore button の有効条件 (`contents != 0 && !restoringBackup`) は変更しない。
- 並行性: 解析は restore 実行前の表示期間のみ。restore 実行 (`backup.restore`) との間に data 競合はない (解析は zip のみ読み、DB へ触れない)。`restoringBackup` 中の再解析を行わない。

### Alternatives rejected

- **backup format へ page 別 screenshot を追加**: 旧 backup に遡行せず、format 互換・移行の議論が発生する。Issue が format 変更を非対象としているため不採用 (graphical preview が必要になった時点で再検討)。
- **restore 時に `launcher.db` から `LauncherPreviewRenderer` で live render して pager 表示**: renderer は `com.android.launcher3` の AOSP 由来 file であり、複数 page 描画には同 file への拡張 (bridge patch) が必要。upstream patch surface policy と整合しないため最小案では不採用。
- **`BackupInfo` proto へ page 数 field を追加**: 新 backup しか対象にできず、旧 backup でも動く「restore 時解析」に対する利点がない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/backup/` (新規 file) | page 要約 reader module | zip 読み取り専用解析。fork 固有領域に置く |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt` | page summary state 追加 | 表示 state の所有者は ViewModel |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | caption + page summary 表示 | preview の誤認を排除する表示箇所 |
| `lawnchair/res/values/strings.xml`, `values-ja/strings.xml` | 追加 string | #123 契約 (両言語必須) |
| `tests/unit/app/lawnchair/backup/` (新規 test file) | reader と ViewModel state の unit test | 既存 backup unit test と同じ surface |

## Migration and recovery

- schema / format 変更なし。migration 不要。
- 本機能は `favorites` へ書き込まないため、ホームレイアウト安全規約の対象となる書き込みを行わない。restore 実行経路 (`LawnchairBackup.restore` の critical section) は変更しない。
- 解析の作業 file は `context.cacheDir` 配下に作成し、`finally` で削除する。process death で残った場合も cache であり、system により回収され得る (path を固定し再実行時に上書きする)。
- release rollback: 機能は UI/読み取りのみのため revert で data 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit test: multi-page fixture zip → `PageSummary` 値と ViewModel state。手動: emulator 2 page layout | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` 相当 (実行 command は building guide の検証済み command に従う) |
| AC-2 | unit test: single-page fixture / wallpaper-only contents | 同上 |
| AC-3 | unit test: 破損 `launcher.db` entry fixture → `PageSummaryUnavailable`、UI state は screenshot 保持 | 同上 |
| AC-4 | unit test: 解析後に作業 file が存在しないこと、DB を read-only flag で開いていること | 同上 |
| AC-5 | unit test: 飛び番 screenId・folder/widget 混在 fixture | 同上 |
| AC-6 | emulator (2 page layout) で backup 作成 → restore 画面の caption・要約・TalkBack 読み上げを確認し evidence を PR へ記録 | device/emulator |
| AC-7 | string 両言語の存在確認 | PR evidence (`git grep`) |
| AC-8 | 本 plan の Upstream 由み記載 (下記) | PR 本文 |

含める観点のうち property test は本件 (集計ロジック) の規模に対して過剰であり、境界 fixture の列举で代替する。failure injection は AC-3/AC-4 で実施する。instrumentation test は追加しない (表示確認は AC-6 の手動 evidence で担保し、loop cost に見合わない)。

## Upstream 方針 (AC-8)

- 先頭 page 限定 preview を生んでいる作成側 screenshot 生成 (`CreateBackupViewModel` / `LauncherPreviewView`) と renderer (`LauncherPreviewRenderer.java`) は upstream Lawnchair / AOSP 由来である。
- 本変更は `app.lawnchair.backup` 配下の fork 固有実装であり、AOSP patch surface を増やさない。upstream への改善報告 (page 毎 preview の要望) は実装 PR で判断し、実施する場合は upstream Issue chooser から行う。

## Documentation updates

- [ ] spec status/history
- [ ] CONTEXT.md — domain language 追加は承認時に判断 (実装語のみなら不要)
- [ ] DESIGN.md — 変更なし (system structure 不変)
- [ ] ADR — 不要 (format・構造の変更困難な判断を含まない。graphical preview 採用時は再評価)
- [ ] AGENTS.md — 変更なし

## Execution checklist

- [ ] Current behavior reproduced (本 plan の Current evidence で code 上確定済み。端末での再現は AC-6 で実施)。
- [ ] Tests fail for the missing behavior (page summary reader / ViewModel state test を先に追加)。
- [ ] Minimal implementation completed。
- [ ] Migration/recovery verified (対象なしを確認のうえ記録)。
- [ ] Full relevant verification completed。
- [ ] PR evidence and remaining risks recorded。

## Unverified areas

- upstream Lawnchair repository に同等の制約・Issue・実装 (graphical multi-page preview) が存在するかは未確認 (network 調査を実装 PR 前に実施する)。
- `favorites` 表の fork 固有 column (organizer lock tri-state 等) が read-only open や集計に影響しないことは schema 定義 (`src/com/android/launcher3/provider/LauncherProvider.java` 系列) の確認で担保する。実装時に確認する。
- cache 展開した `launcher.db` を開く際の SQLite version 互換 (fork 固有 migration が db に適用済みか) は fixture test で担保する。実装時に、旧 BACKUP_VERSION の db にも read-only で開けることを確認する。
