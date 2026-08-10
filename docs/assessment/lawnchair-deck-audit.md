# Lawnchair 15 Deck Layout Audit

> Status: Completed assessment
> Reviewed: 2026-08-09
> Author: Agent `deck-audit` (Issue #2)
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`（audit開始時のNunuLauncher `main` は `40e437943c5de7f4d35f9baf4823efc294cb0530`。product sourceはbaselineと同一）
> Requirements: FR-001, FR-004, FR-005, NFR-001, NFR-002, NFR-010
> Decisions: D-001（基準revision）, D-002（Deck layoutの扱い）
> Resolution: D-002 was subsequently accepted as **replace** by
> [ADR-0002](../adr/0002-replace-deck-layout.md); statements below that call it
> pending describe the audit-time state.

## 1. Question and scope

Lawnchair 15 Beta 3 の Deck layout は、NunuLauncher の「安全・決定的な整理」要件（FR-001/004/005、NFR-001/002/010）にどこまで再利用でき、どこを refactor または replace すべきか。

調査は次を追跡した: preference、`LawndeckManager`、`AddFoldersWithItemsTask`、package event hook、model/DB 接点、backup/restore 接点、入出力、副作用、transaction boundary、error handling、coverage（phone/tablet、profile、Dock、folder、widget）。production source は読み取り専用とし、変更は行っていない。

## 2. Conclusion

既存Deck layoutは**計算と適用が未分離の破壊的レイアウト操作**であり、NunuLauncherの安全性要件を満たさない。そのまま再利用しない（**replace** を基本方針）。ただし、event hook点（`ModelLauncherCallbacks.onPackageAdded` → `PackageUpdatedTask(OP_ADD)`）、分類源としてのFlowerpot、Folder配置で使う`WorkspaceItemSpaceFinder` の呼び出し方、`ModelWriter` / `ModelDbController` の利用事例は後続Issueの参考情報として使える。

推奨: **replace**（Deck layoutは整理対象から外し、NunuLauncherの新規organizer moduleで要件を満たす安全な適用pathを構築する）。AOSP由来の`PackageUpdatedTask.java`へ追記されたdeck分岐だけはupstream patch surfaceを減らすため除去し、新規hookから同じeventを消費する形へ移行する。この判断は **D-002 を解決する提案** である。最終決定は D-002 gate で承認すること。

理由の要約は §8。後続 Issue #3–#6 への制約は §9。

## 3. Investigated symbols and paths

確認した baseline 上の file と主な symbol。特に断らない限り行番号は baseline commit 時点。

| 役割 | Path | 代表 symbol / 行 |
|---|---|---|
| Deck Manager | `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | `class LawndeckManager` L28、`enableLawndeck` L34、`disableLawndeck` L54、`createBackup`/`restoreBackup` L61/L68、`getDatabaseFiles` L76、`postRestoreActions` L89、`addAllAppsToWorkspace` L94、`addNewlyInstalledApp` L177、`findFolderByCategory` L241、`createFolderInfo` L253 |
| Folder投入task | `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` | `class AddFoldersWithItemsTask` L25、`execute` L32、`shortcutExists` L133 |
| Package event hook（AOSP file） | `src/com/android/launcher3/model/PackageUpdatedTask.java` | `OP_ADD` 分岐 L456–473（import `LawndeckManager` L71） |
| Callback → task dispatcher（Lawnchair追加Kotlin） | `src/com/android/launcher3/model/ModelLauncherCallbacks.kt` | `onPackageAdded` L40 → `PackageUpdatedTask(OP_ADD, ...)` L41 |
| 分類utility | `lawnchair/src/app/lawnchair/util/AppCategorizationUtils.kt` | `categorizeAppsWithSystemAndGoogle` L15 |
| 分類engine | `lawnchair/src/app/lawnchair/flowerpot/Flowerpot.kt` | `Flowerpot.Manager.categorizeApps` L153、`Manager.categorizeApps` 1 pot 単位 L61 |
| preference定義 | `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | `deckLayout` L682（`enable_lawn_deck`）、`showDeckLayout` L688（`show_deck_layout`） |
| preference UI | `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` | `HomeLayoutSettings` L48、enable/disable切替 L112–159 |
| preference UI gate | `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` L71–89、`ExperimentalFeaturesPreferences.kt` L73–75、`PreferencesDashboard.kt` L145–146、`GestureHandlerPreference.kt` L72–78 |
| Backup（本物） | `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | `create` L144、`restore` L60、ZIP内訳 `getFiles` L134（`launcher.db` + shared_prefs + preferences DB + DataStore protobuf） |
| Restore DB処理 | `src/com/android/launcher3/provider/RestoreDbTask.java` | `performRestore`（`ModelDbController` 経由で呼ばれる） |
| DB書込 | `src/com/android/launcher3/model/ModelWriter.java` | `addItemToDatabase` L271、`addOrMoveItemInDatabase` L110、`deleteItemFromDatabase` L300 |
| Schema | `src/com/android/launcher3/LauncherSettings.java` | `Favorites` item types L96–157、`CONTAINER_DESKTOP` L191、`CONTAINER_HOTSEAT` L192 |
| 再起動 | `lawnchair/src/app/lawnchair/util/LawnchairUtils.kt` | `restartLauncher` L85/L98、`killLauncher` L112（`exitProcess(0)`） |

主要なsource根拠（すべてbaseline commit固定）:

- [LawndeckManager.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt)
- [AddFoldersWithItemsTask.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt)
- [PackageUpdatedTask.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/PackageUpdatedTask.java)
- [ModelLauncherCallbacks.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/ModelLauncherCallbacks.kt)
- [LawnchairBackup.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)

再現command（読み取り専用）:

```bash
git -C /Users/nunu/Documents/work/NunuLauncher show 505dbc40e6154c05158b5d0271c45f6a885a411b:lawnchair/src/app/lawnchair/deck/LawndeckManager.kt
```

## 4. What Deck layout does (observed behavior)

### 4.1 Enable（手動全体操作）

`HomeLayoutSettings` が `deckLayout` preference を toggle し、`enableLawndeck` を呼ぶ（`HomeLayoutPreferences.kt` L112–133）。同時に `swipeUpGesture = NoOp`、`addIconToHome = true` へ強制設定する。

`enableLawndeck`（`LawndeckManager.kt` L34–52）:

1. `backupExists("bk")` でなければ `createBackup("bk")` を作る。
2. `backupExists("lawndeck")` なら前回の Deck layout を `restoreBackup("lawndeck")` で復元。
3. 無ければ `addAllAppsToWorkspace` で全アプリを分類して配置。

`addAllAppsToWorkspace`（L94–166）:

- `launcher.mAppsView.appsStore.apps` を読み、`categorizeAppsWithSystemAndGoogle` で「System Apps / Google Apps / Flowerpot各カテゴリ」へ分ける。
- カテゴリ1件 → `ItemInstallQueue.queueItem` で単体配置。
- カテゴリ2件以上 → `createFolderInfo` で `FolderInfo` を組み立て、`AddFoldersWithItemsTask` へ渡す。
- 単体アプリの完了待ちに固定遅延 `postDelayed(..., 800)` を使う（L147, L159）。

`AddFoldersWithItemsTask.execute`（L32–127）:

- `WorkspaceItemSpaceFinder.findSpaceForItem` で空きセルを探し、`ModelWriter.addItemToDatabase` で folder を、`addOrMoveItemInDatabase` で folder 内 item を順に書く。folder 内の rank/座標は `index % 4` / `index / 4` の固定4列計算（L87–88）。

### 4.2 Disable（手動で戻す）

`disableLawndeck`（L54–59）: `createBackup("lawndeck")` で現在を保存し、`restoreBackup("bk")` で enable 前へ戻す。

### 4.3 Backup / restore 機構（Deck独自）

`createBackup(suffix)`（L61–66）: `getDatabasePath(idp.dbFile)` のDB file と journal を `suffix_` 付き file へ直接 copy する。SQLiteへのAPI経由ではなくfile copy。`runCatching` で失敗を `Log.e` のみ記録し、呼び出し側へ失敗を伝播しない。

`restoreBackup(suffix)`（L68–74）: 逆方向へ copy したあと `postRestoreActions`（L89–92）で `RestoreDbTask.performRestore` を呼び、`restartLauncher`（= `exitProcess(0)` によるprocess再起動）を実行する。

### 4.4 Install増分（package event hook）

`PackageUpdatedTask` の `OP_ADD` block（L456–473）で `PreferenceManager2.getDeckLayout()` が真のときだけ `LawndeckManager.addNewlyInstalledApp` を各新規 package について呼ぶ。

`addNewlyInstalledApp`（L177–239）:

- `LauncherApps.getActivityList` で app を取り、先頭 activity を代表とする（L189）。
- カテゴリ決定: `com.google.` 始まり → Google Apps、system app → System Apps、それ以外 → Flowerpot。Flowerpotが空なら単体で `queueItem`。
- 既存 folder（`findFolderByCategory`、title一致）があればそこへ追加。無ければ単体配置。
- folder追加時の rank は `getContents().size % 4` / `/ 4` の固定計算（L231–232）。

event 元は `ModelLauncherCallbacks.onPackageAdded`（L40–42）。Lawnchairが追加したKotlin file で、AOSPの`LauncherApps.Callback`を継承し、`onPackageAdded`/`onPackageChanged`/`onPackageRemoved`/`onPackagesAvailable`等を `PackageUpdatedTask(OP_*)` へ変換する。deck依存のコードは持たない。

## 5. Capability / gap matrix

FR/NFR ごとの評価。記号: ✅ 満たす / ⚠️ 部分 / ❌ 満たさない / ➖ 該当せず。

| 要件 | 期待 | Deck layout の現状 | 判定 | 根拠 |
|---|---|---|---|---|
| FR-001 | snapshotから副作用なしにplanとdiagnosticを生成 | 計算とDB書込が同一task内で混在。plan/diagnostic objectが存在しない | ❌ | §4.1, §6.1 |
| FR-004 | 適用前にrecovery pointを作り復旧可能 | enable前に`bk`、disableに`lawndeck`を作るが、外部backup相当のfile copy。作成失敗が無視され、保持期間・検証・復旧UIが未定義 | ⚠️ | §4.2, §6.3 |
| FR-005 | staleでない検証済みplanだけを原子的に適用し、適用後に再検証 | revision確認・事前検証・事後検証がいずれも無い。複数writeは別model taskで逐次実行され、途中失敗で部分的な中間状態が残る | ❌ | §6.1, §6.2 |
| NFR-001 | crash/cancel/process death/書込失敗で適用前layoutを失わない | 遅延待ち・file copy・`exitProcess`再起動に依存。途中失敗やprocess killで中間状態のまま再起動する | ❌ | §6.2, §6.4 |
| NFR-002 | conservation/bounds/overlap/container/lock/profileを適用前後に検証 | いずれの検証も存在しない。lock概念自体が無い | ❌ | §6.1 |
| NFR-010 | project固有logicを少数の深いmoduleへ置き、patch surfaceを記録・計測 | deck logicはAOSP file (`PackageUpdatedTask.java`) へ直接追記されている。patch surfaceの記録なし | ⚠️ | §6.6 |

参考（要件外だが coverage 評価に有用）:

| 項目 | 現状 | 判定 |
|---|---|---|
| 全アプリ分類配置 | Flowerpot + System/Google 分類で実装済み | ✅ 機能としては存在 |
| 新規アプリ増分 | `OP_ADD` hook から既存folderへ追加可能 | ⚠️ profile/複数activity/restore扱いが弱い（§6.5） |
| folder配置 | `WorkspaceItemSpaceFinder` で空き探索 | ✅ 機能としては存在 |
| phone / tablet | top-level folder配置は現在のworkspace/gridを使うが、Deck固有のphone/tablet分岐・tablet/foldable fixture・orientation検証は無い | ⚠️ source上の部分対応のみ |
| lock / 対象外保持 | 存在しない | ❌ |
| 確認 / preview / undo | loading dialog のみ。preview/undo 無し | ❌ |
| 決定性 | category map は `toSortedMap` だが、tie-break と folder 内固定4列計算がgrid非依存でない | ⚠️ |

## 6. Findings（root cause level）

### 6.1 計算と適用が未分離（FR-001/NFR-002 の根本）

`addAllAppsToWorkspace` と `AddFoldersWithItemsTask` は、分類結果を即 `ModelWriter.addItemToDatabase` へ書き込む。plan object、validator、diagnostic が無く、適用前に「何をどこへ置くか」を検査できない。この構造では NunuLauncher の invariants（conservation / no overlap / bounds / referential integrity / lock preservation / profile isolation）を適用前後で検証できない。

### 6.2 atomicity 無し（FR-005/NFR-001）

folder とその中身は複数回の `addItemToDatabase` / `addOrMoveItemInDatabase` で別々に書かれる（`AddFoldersWithItemsTask` L64–90）。各 write は別 model task として逐次実行され、SQLite transaction で一括ではない。N番目で失敗しても前方の write は残る。`ModelWriter.addItemToDatabase` 自体（`ModelWriter.java` L271–295）も単件 insert であり、複数件を束ねる transaction API を使っていない。

### 6.3 recovery が file copy + process restart（FR-004/NFR-001）

`createBackup`/`restoreBackup` はDB file と journal の raw copy。作成失敗は `runCatching` で握り潰され（L66, L74）、呼び出し側は成功を前提に進む。保持期間・上限・rotation が無く、`bk`/`lawndeck` 2 slot だけ。復旧は必ず `exitProcess(0)` を伴う process 再起動で、アプリ内での即時復旧や「適用後に戻す」操作を提供しない。これは `initial-design-review.md` が指摘した「手動backupを即時Undoとみなす」問題そのものである。

### 6.4 時間依存の完了判定（NFR-001）

`addAllAppsToWorkspace` は `ItemInstallQueue` の非同期処理を待つために `postDelayed(..., 800)` の固定遅延を使う（L147, L159）。遅延中の process kill や、低速端末での処理遅れで、UI が「完了」と判断した時点でDBが未到達になり得る。

### 6.5 identity / coverage が弱い

`addNewlyInstalledApp` は `getActivityList` の先頭 activity だけを代表とし（L189）、複数 launcher activity を区別しない。folder 内 rank を `size % 4` / `/ 4` の固定計算で扱い（L231–232, `AddFoldersWithItemsTask` L87–88）、folder span や grid 列数を考慮しない。widget、deep shortcut、app pair、custom widget、Dock hotseat 配置に対する扱いが存在しない。work/private profile への `OP_ADD` も同じ `addNewlyInstalledApp` を通るが、profile identity を保存した plan 検証は無い。

phone/tablet差について、top-level folderの空き探索は `WorkspaceItemSpaceFinder` を通るため現在のworkspace/grid情報を利用する。一方、Deck固有codeにはphone/tablet/foldable/orientation別のpolicyやfixtureがなく、folder child座標は端末classに関係なく固定4列である。したがって「実装上まったく動かない」とは断定できないが、NFR-007を満たすdevice-profile対応としては未検証である。

### 6.6 AOSP file への直接追記（NFR-010）

`PackageUpdatedTask.java`（AOSP/Launcher3 由来）へ、L71 の `import app.lawnchair.deck.LawndeckManager` 等と L456–473 の deck 分岐が直接書かれている。これは NFR-010 が回避を求める「広い patch surface」であり、Lawnchair 16 追従時のmerge conflict源になる。`ModelLauncherCallbacks.kt` は Lawnchair 追加 file であり deck 非依存なので、新規 organizer はこの dispatcher を使い続け、`PackageUpdatedTask` の deck 分岐は除去できる。

### 6.7 backup 形式の事実再確認

`initial-design-review.md` の指摘通り、Lawnchair backup は XML ではなく ZIP（`LawnchairBackup.kt`）。`getFiles`（L134）は `launcher.db`、shared_prefs XML、preferences SQLite DB、DataStore protobuf を格納する。Deck 独自の `bk`/`lawndeck` file copy はこの backup 体系と独立しており、通常の backup/restore 操作と整合しない。

## 7. Reuse / refactor / replace comparison

| 側面 | reuse（そのまま） | refactor（修正） | replace（新設） |
|---|---|---|---|
| 安全性要件（FR-001/004/005, NFR-001/002） | 満たさない。計算適用分離・atomicity・検証が構造上欠落 | atomic transaction と検証を後付けするには、実質全書き替えに等しい。遅延/file copy/`exitProcess` を含むため抜本的修正が必要 | 新規 organizer module で要件を直接満たせる。AGENTS.md の設計規約（副作用なし計画module、transaction適用module、少数seam）に合致 |
| upstream patch surface（NFR-010） | AOSP file への追記が残り続ける | 追記を整理しても AOSP file 由点は残る | `PackageUpdatedTask` の deck 分岐を除去し、新規 module から event を消費すれば surface を最小化できる |
| 機能資産の活用 | 全アプリ配置・folder化は動く | 分類と空き探索は再利用可能 | **分類源（Flowerpot）と空き探索（`WorkspaceItemSpaceFinder`）の知見は新設側へ持ち込める**（参考実装として）。deck 実体は整理対象から外す |
| Lawnchair 16 追従 | deck 分岐が毎回 conflict | 分岐が残る限り継続 conflict | deck 分岐を除去すれば追従が軽くなる |
| 工数 | 最小だが要件違反 | 中〜大。要件を満たすには replace に近い | 大だが、設計意図と一致し後続 Issue を正しく unblock できる |

**推奨: replace。** 機能資産（分類源・空き探索の使い方）は参考実装として参照し、deck 実体を整理対象から外す。ただし整理結果として「既存Deck layout output」を fixture corpus（`quality-strategy.md` の「existing Deck layout output」行）に含め、回帰を検知できるようにする。この推奨が **D-002 の解決案** である。

## 8. Recommendation summary（D-002）

1. 既存Deck layout（`LawndeckManager`、`AddFoldersWithItemsTask`、`PackageUpdatedTask` の deck 分岐、`deckLayout`/`showDeckLayout` preference とそれらを表示する UI gate）を整理機能の再利用対象から外す。NunuLauncher は新規 organizer module（`DESIGN.md` §4/§9）で安全な計算・適用を構築する。
2. `PackageUpdatedTask.java` の L71 import 群と L456–473 の deck 分岐を、organizer の新規 integration module へ移す。これにより AOSP file への patch surface を減らす（NFR-010）。この除去自体は別 Issue とし、本 audit の範囲外。
3. Flowerpot の分類能力と `WorkspaceItemSpaceFinder` の利用事例は、分類/配置を設計する後続 Issue（#5 layout strategy、#6 category taxonomy）の**入力資料**として参照する。Flowerpot をそのまま採用するかは別 Issue で決める。
4. 整理結果検証のため、fixture corpus に「Deck layout で生成された layout」を含め、organizer がそれを安全に取り込める/明示的に保持できることを検証する（`quality-strategy.md` fixture 表）。

## 9. Constraints and open points for Issue #3–#6

| Issue | 影響を受ける判断 | 制約 / open point |
|---|---|---|
| #3 対象集合とitem保持policy | 全アプリ配置の既存挙動 | Deck は全アプリを home へ追加する。NunuLauncher は対象集合を D-003 で決める。Deck 由来の「全アプリ追加」を default としない方針（requirements D-003 推奨）と整合させる。既存Deck layout上の item は「保持」または「明示的削除」で説明可能にする必要がある |
| #3 / 全item coverage | item type policy | Deck は app/folder のみ扱い、widget/deep shortcut/app pair/custom widget/Dock を扱わない。後続 Issue は `LauncherSettings.Favorites` の全 item type（§3 表）について move/preserve/transform/reject を定義すること。Deck は coverage の反面教師 |
| #4 trigger/確認/recovery UX | recovery の仕様 | Deck の file copy + `exitProcess` は recovery として不適（§6.3）。FR-004/NFR-001 の recovery point は、アプリ内で原子復旧でき保持期間を持つ形式（`DESIGN.md` §7）にする。trigger は D-004 で手動/onboarding/増分を別 policy にする |
| #5 layout strategy v1 | grid非依存配置 | Deck の folder 内 `index % 4` 計算は grid 非依存でない。D-007 に従い device profile から region/列数を導出する。`WorkspaceItemSpaceFinder` の利用事例は参考になるが、そのままの固定計算は持ち込まない |
| #6 category taxonomy | 分類源 | Flowerpot は assets 由来のrule file で app を分類する。project taxonomy を独立定義する方針（D-008）と、Flowerpot rule file の採否は別 Issue で比較する。本 audit は Flowerpot 採用を推奨しない（決定は別 Issue） |
| 共通 | upstream patch surface | `PackageUpdatedTask.java` の deck 分岐除去を追跡 Issue にする。新規 organizer は `ModelLauncherCallbacks`（deck 非依存の dispatcher）を event 源として使うことで、AOSP file への追加を行わずに済む |

## 10. Open questions to resolve elsewhere

本 audit では決定しない（別 Issue / decision gate）:

- D-002 の最終承認（replace を採るか）。本文書は replace を推奨するが、gate での承認が必要。
- Flowerpot rule file 形式を採用するか、別の typed rule model を作るか（D-009 関連、#6）。
- Deck layout を無効化・除去するタイミングと、その間の共存方針（別 maintenance Issue）。
- Lawnchair 16 への追従時期と、deck 除去が追従に与える影響（Epic/ADR 要件、AGENTS.md）。

## 11. Verification notes

- production source の変更は一切行っていない。確認は読み取りと `git show <baseline>:<path>` のみ。
- baseline SHA `505dbc40e6154c05158b5d0271c45f6a885a411b` と tag `v15.0.0-beta3.0` は `DESIGN.md` §2 および `initial-design-review.md` の記載と一致した。
- 行番号根拠は baseline commit 時点。audit開始時のNunuLauncher `main` (`40e437943c5de7f4d35f9baf4823efc294cb0530`) が同じproduct sourceを含むことを確認済み。
- 本文書は Issue #2 の Primary write path（`docs/assessment/lawnchair-deck-audit.md`）のみを変更する。CI/build/emulator を使用していない。
