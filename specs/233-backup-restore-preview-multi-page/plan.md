# Implementation Plan: Home backup復元previewでの複数page保存内容の確認

> Issue: #233
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

Baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` (origin/main, 2026-09-10) 時点の確認。推測は含まない。

### 復元側 (表示箇所)

- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt`
  - portrait の `RestoreBackupOptions` が `DummyLauncherBox` 内に `backup.screenshot` を単一 `Image` (`contentDescription = null`, `ContentScale.FillHeight`) で表示する (L146-174)。pager・page 切替・caption は存在しない。`DummyLauncherBox` は portrait のみで、landscape には preview がない。
- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt`
  - `init()` で `LawnchairBackup(context, uri).readInfoAndPreview()` を呼ぶのみ。page 情報は持たない。
- `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt`
  - `readInfoAndPreview()` は zip から `info` (proto)・`screenshot.png`・`wallpaper.png` のみを読む。`BackupInfo` に page 情報はない (`previewWidth` / `previewHeight` / `previewDarkText` / `gridState` / `contents` / `createdAt` / `lawnchairVersion` / `backupVersion`)。
  - `BACKUP_VERSION` は現在に至るまで `1` のみ (L133)。
  - zip は `getFiles(context, forRestore)` のとおり `launcher.db` (全 favorites を含む)、prefs 関連 file を格納する。restore (`restore()`) は critical section (#58, ADR-0011) 内で全 file を書き戻す。**restore scope は全 page**。
  - 既存の `readZip` (L114) は entry 名完全一致の handler dispatch であり、entry 名から file path を構成しない。本 plan の reader も同じ性質を維持する。

### 作成側 (制約の発生箇所)

- `lawnchair/src/app/lawnchair/backup/ui/CreateBackupViewModel.kt` — `captureScreenshot()` が `LauncherPreviewView` で bitmap 1枚を生成。
- `lawnchair/src/app/lawnchair/views/LauncherPreviewView.kt` — `loadModelData()` → `LauncherPreviewRenderer.getRenderedView()`。
- `src/com/android/launcher3/graphics/LauncherPreviewRenderer.java` (AOSP/upstream 由来)
  - `launcher_preview_layout` を inflate し workspace は `FIRST_SCREEN_ID` 1枚のみ (two-panel のみ `SECOND_SCREEN_ID` 追加、L253-268)。
  - `populate()` は `filterCurrentWorkspaceItems(...)` で先頭画面以外を `otherWorkspaceItems` へ分離し (L504-517)、それを描画に使用しない。

### test / CI 環境の実態 (2026-09-11 確認)

- local unit test は root project の `test` source set (`java.srcDirs = ['tests/unit']`, `build.gradle` L364-366)、依存は JUnit4 のみ (`testImplementation libs.junit4`)。**Robolectric は未導入**。backup UI/logic は root project に属する (`:lawnchair` のような独立 module は存在しない。`settings.gradle` には他 subproject が含まれるが本件の対象は root)。
- 既存 `tests/unit/app/lawnchair/backup/LawnchairBackupRestoreCriticalSectionTest.kt` は pure JVM (java.io / JUnit4) で DB に依存しない orchestration test。
- CI (`.github/workflows/ci.yml` "Run organizer unit tests") は `--tests 'app.lawnchair.organizer.*'` 等の filter 指定であり、**`app.lawnchair.backup.*` は含まれていない**。本件で filter に追加する。
- 検証 command: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` (既存 task。CI filter への追加も同じ PR で行う)。

### 結論 (確認済み事実)

先頭 page のみの preview は regression ではなく、upstream Lawnchair の backup 仕様 (screenshot 1枚方式) と AOSP preview renderer の構造に由来する。backup serialization は全 page を保持しており、preview のみが限定されている。Issue の「調査事項」のうち1つ目・3つ目はこれで解決済み。2つ目 (upstream 同等 Issue の有無) は Open questions として残る (実装 blocking なし)。

## Design

### Modules and interfaces

変更は `app.lawnchair` 配下 (fork 固有領域) と CI workflow の test filter のみ。`com.android.launcher3` / AOSP path への変更・patch なし。

1. **page 要約の解析module (新規)**: `app.lawnchair.backup` 配下に新設 (例: `BackupPageSummaryReader`)。
   - 入力: backup uri (既に画面が保持するもの)。
   - 責務 (純粋 JVM 部分と Android 依存部分に分離):
     a. **zip 抽出 (pure JVM, unit test 対象)**: `ZipInputStream` で entry 名が完全一致 `launcher.db` のもののみを扱う。同名 entry の2回目の遭遇は unavailable。entry の uncompressed size が上限 (既定 64 MB) を超えたら読まずに unavailable。出力先は内部生成の一意な一時 file (entry 名を使わない)。展開は byte 上限つきで行う (打ち切り = unavailable)。**zip 全体の走査にも上限を設ける**: 関連しない entry も含め全 entry を chunk (例: 64 KB) 読み取りで drain し、**展開後 (uncompressed) の累積 byte** の上限 (既定 512 MB)・圧縮 stream の累積読み取り byte 上限・entry 数上限を設け、chunk ごとに `ensureActive()` で coroutine cancel を確認し、上限到達時は unavailable とする (`ZipInputStream` は未読 entry を内部で drain するため、drain を明示的に chunk 化して cost と取消可能性を bound する)。
     b. **集計 (pure JVM, unit test 対象)**: `(screen, itemType, count)` の grouped 列 (下記 (c) の GROUP BY query 出力) から、非空 page の昇順 list と page 別の item 総数 / folder 数 (`itemType = 2`) / widget 数 (`itemType = 4, 5`) を計算する純粋関数。folder child は二重計上しない (favorites 行ベースの集計のため自明)。hotseat・空 screen は除外。screen 飛び番は連番化。先頭 screen の特定は `workspaceScreens` 表の `screenRank` 最小値で行い、表がない場合は renderer の実際の描画対象である `FIRST_SCREEN_ID = 0` を使う (非空 screen 最小値は使わない)。caption 表示判定 (先頭 screen 以外に非空 page が存在するか) もこの層で計算する。
     c. **SQLite 読み取り (Android 依存、unit test 対象外)**: 展開した db を `SQLiteDatabase.OPEN_READONLY` で開き、`favorites` 表の必要 column (`container` / `screen` / `itemType`) の存在を検証したうえで `SELECT screen, itemType, COUNT(*) FROM favorites WHERE container = -100 GROUP BY screen, itemType` を実行し、grouped count 行を (b) へ渡す。`workspaceScreens` 表は存在する場合のみ `SELECT _ID FROM workspaceScreens ORDER BY screenRank LIMIT 1` で先頭 screen を読む (表なしは fallback)。失敗 (open 不能・table/column 欠損) は unavailable。
   - 出力: typed result (`PageSummary(pages: List<PageSummaryEntry>)` / `PageSummaryUnavailable`)。platform 型 (Cursor 等) を interface に漏らさない。`BackupInfo` proto は拡張しない。
   - 作業 file: `context.cacheDir` 配下に解析1回ごとに一意な名前で作成し、close 後 `finally` で削除。削除失敗は log のみ。
   - 既存の `LawnchairBackup.readZip` の handler 機構は private であるため、独立した読み取りとして実装する。
2. **ViewModel 拡張**: `RestoreBackupViewModel` — `readInfoAndPreview()` 成功後、contents が layout を含む場合のみ解析を起動し、その state を `RestoreBackupUiState.Success` とは別の flow (例: `pageSummary: StateFlow<PageSummaryUiState>`) として公開する。`Success` 表示を解析完了待ちさせない (pending 中は UI に何も表示しない)。解析失敗は restore 可否へ影響させない。
3. **UI**: `RestoreBackupScreen.kt` — caption と page 要約を portrait `DummyLauncherBox` の外 (options 領域近傍) に追加し、landscape でも表示する。表示条件は「backup が layout を含む」かつ「先頭 screen (workspaceScreens の screenRank 最小、表なしの場合は screen 0) 以外に非空 page が存在 (上記 (b) の判定)」で、screenshot の有無・contents checkbox 状態に依存しない。caption 文言は「保存された page の全部がこの画像に表示されない場合があります」系の非断定表現とする。
4. **strings**: `values/` / `values-ja/` へ追加。caption は「保存された page の全部がこの画像に表示されない場合があります」等の two-panel でも不正確にならない非断定表現。
5. **CI**: `.github/workflows/ci.yml` の unit test filter へ `'app.lawnchair.backup.*'` を追加。

### Data flow

```
RestoreBackup route (uri)
  -> RestoreBackupViewModel.init
       -> LawnchairBackup.readInfoAndPreview()      (既存, Success 判定)
       -> [新規, 並行] contents has INCLUDE_LAYOUT_AND_SETTINGS ?
            -> BackupPageSummaryReader.read(uri)  (Dispatchers.IO)
                 -> zip extract (exact "launcher.db", size-capped)
                 -> SQLite readonly open -> group-by query
                 -> pure aggregation -> PageSummary | PageSummaryUnavailable
  -> RestoreBackupScreen
       -> screenshot preview (既存)
       -> caption + page summary (新規, pageSummary state で分岐)
```

- 解析は `Dispatchers.IO` で実行し、restore button の有効条件 (`contents != 0 && !restoringBackup`) は変更しない。
- 解析の cost は (a) の zip 全体走査上限 (chunk ごとの展開後累積 byte 上限・圧縮 byte 上限・entry 数上限) と (c) の単発 GROUP BY query で bound される。`ViewModel.onCleared` で coroutine はキャンセルされ (chunk 読み取りごとに cancel を確認)、一時 file は `finally` で削除される。
- 並行性: 解析は restore 実行前の表示期間のみ。restore 実行 (`backup.restore`) との間に data 競合はない (解析は zip のみ読み、DB へ触れない)。`restoringBackup` 中の再解析を行わない。

### Alternatives rejected

- **backup format へ page 別 screenshot を追加**: 旧 backup に遡行せず、format 互換・移行の議論が発生する。Issue が format 変更を非対象としているため不採用 (graphical preview が必要になった時点で再検討)。
- **restore 時に `launcher.db` から `LauncherPreviewRenderer` で live render して pager 表示**: renderer は `com.android.launcher3` の AOSP 由来 file であり、複数 page 描画には同 file への拡張 (bridge patch) が必要。upstream patch surface policy と整合しないため最小案では不採用。
- **`BackupInfo` proto へ page 数 field を追加**: 新 backup しか対象にできず、旧 backup でも動く「restore 時解析」に対する利点がない。
- **Robolectric / JDBC sqlite 依存の追加による SQLite 経路の unit test 化**: 依存追加は AGENTS 上 spec とリスク評価を要する。本件では集計・zip 抽出を pure JVM に分離して unit test で担保し、SQLite open 経路は emulator での手動 evidence (AC-8) で検証する。十分性に疑義が出たら別 Issue で再評価する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/backup/` (新規 file) | page 要約 reader module (zip 抽出 / 集計 / SQLite 読み取り) | zip 読み取り専用解析。fork 固有領域に置く |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt` | page summary state 追加 (Success と分離した flow) | 表示 state の所有者は ViewModel |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | caption + page summary 表示 (portrait/landscape 両対応) | preview の誤認を排除する表示箇所 |
| `lawnchair/res/values/strings.xml`, `lawnchair/res/values-ja/strings.xml` | 追加 string | #123 契約 (両言語必須) |
| `tests/unit/app/lawnchair/backup/` (新規 test file) | 集計純粋関数と zip 抽出の JVM unit test | 既存 backup unit test と同じ surface |
| `.github/workflows/ci.yml` | unit test filter へ `app.lawnchair.backup.*` を追加 | CI で backup test を実行するため |

## Migration and recovery

- schema / format 変更なし。migration 不要。
- 本機能は `favorites` へ書き込まないため、ホームレイアウト安全規約の対象となる書き込みを行わない。restore 実行経路 (`LawnchairBackup.restore` の critical section) は変更しない。
- 解析の作業 file は `context.cacheDir` 配下に解析1回ごと一意の名前で作成し、`finally` で削除する。process death で残った場合も cache であり永続領域への影響はない。
- release rollback: 機能は UI/読み取りのみのため revert で data 影響なし。

## High-risk gate (AC-9)

`docs/project/github-workflow.md` は `lawnchair/src/app/lawnchair/backup/**` を high-risk path に分類する。実装 PR は:

- 該当 risk label を付与する。
- merge 前に対象 head SHA で CI `final-status` が成功していること。
- `docs/assessment/pr-<PR番号>-<slug>.md` に独立 audit 記録 (対象 head SHA、参照 spec 受入条件、実行 test 表面、成功 CI run link) を作成する。audit は実装 session とは別の独立 session (general-purpose subagent) で行う。
- `high-risk-gate` workflow の機械検証を通過する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | unit test: multi-page fixture → 集計結果。手動: emulator 2 page layout | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` |
| AC-2 | unit test: single-page / hotseat-only / 空 favorites fixture | 同上 |
| AC-3 | unit test: zip level 失敗注入 (entry 不在・entry 破損・重複 entry・size 超過) → unavailable。SQLite level 失敗 (open 不能・schema 欠損) は AC-8 emulator | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` |
| AC-4 | unit test: 解析後の作業 file 削除・内部生成 path・書き込みなし | 同上 |
| AC-5 | unit test: 飛び番 screenId・folder/widget 混在・folder 二重計上なし・先頭 screen 空の境界 fixture | 同上 |
| AC-6 | unit test (ViewModel state 分離) + 手動 (回転・checkbox) | 同上 + emulator |
| AC-7 | string 両言語の存在確認 | PR evidence (`git grep`) |
| AC-8 | emulator (2 page layout) で backup 作成 → restore 画面の caption・要約・TalkBack 読み上げ・実 launcher.db 解析経路、さらに restore 実行後に復元された workspace の page 構成が backup 保存内容と一致すること (round-trip) を確認し evidence を PR へ記録 | device/emulator |
| AC-9 | 本 plan の Upstream 記載 + high-risk gate (上記) | PR 本文 / `docs/assessment/` / CI |

property test は本件 (集計ロジック) の規模に対して過剰であり、境界 fixture の列举で代替する。failure injection は AC-3/AC-4 で実施する。instrumentation test は追加しない (表示確認は AC-8 の手動 evidence で担保し、loop cost に見合わない)。

## Upstream 方針 (AC-9)

- 先頭 page 限定 preview を生んでいる作成側 screenshot 生成 (`CreateBackupViewModel` / `LauncherPreviewView`) と renderer (`LauncherPreviewRenderer.java`) は upstream Lawnchair / AOSP 由来である。
- 本変更は `app.lawnchair.backup` 配下の fork 固有実装であり、AOSP patch surface を増やさない。upstream への改善報告 (page 毎 preview の要望) は実装 PR で判断し、実施する場合は upstream Issue chooser から行う。

## Documentation updates

- [ ] spec status/history
- [ ] CONTEXT.md — domain language 追加は承認時に判断 (実装語のみなら不要)
- [ ] DESIGN.md — 変更なし (system structure 不変)
- [ ] ADR — 不要 (format・構造の変更困難な判断を含まない。graphical preview 採用時は再評価)
- [ ] AGENTS.md — 変更なし

## Execution checklist

- [ ] Current behavior reproduced (本 plan の Current evidence で code 上確定済み。端末での再現は AC-8 で実施)。
- [ ] Tests fail for the missing behavior (page summary reader の pure JVM 部分に対する test を先に追加)。
- [ ] Minimal implementation completed。
- [ ] Migration/recovery verified (対象なしを確認のうえ記録)。
- [ ] Full relevant verification completed (`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'`, `spotlessCheck`, `assembleLawnWithQuickstepGithubDebug`)。
- [ ] PR evidence and remaining risks recorded。
- [ ] High-risk gate: 独立 audit (docs/assessment) + final-status CI 成功を確認して merge。

## Unverified areas

- upstream Lawnchair repository に同等の制約・Issue・実装 (graphical multi-page preview) が存在するかは未確認 (network 調査を実装 PR 前に実施する)。
- SQLite open 経路 (c) は JVM unit test の対象外であり、実 db での検証は AC-8 の emulator evidence に依存する。
- `favorites` 表の fork 固有 column (organizer lock tri-state 等) が read-only open や集計に影響しないことは schema 定義 (`src/com/android/launcher3/provider/LauncherProvider.java` 系列) の確認で担保する。実装時に確認する。
- 旧 BACKUP_VERSION の db との互換は「`BACKUP_VERSION` は commit `2038c6722c` での backup 実装導入時から一貫して `1` のみ」であること (git history で `BACKUP_VERSION` の変更 record なし) と、必要 column の存在検証で担保する。
