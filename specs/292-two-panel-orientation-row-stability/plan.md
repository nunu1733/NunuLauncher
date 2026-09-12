# Implementation Plan: orientation stale-rejection test survives the one-time default-workspace load

> Issue: #292
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

### 確認済み（code path・CI・ローカル再現で裏取り済み）

- **CI 失敗 2 回、同一シグネチャ・同一行**: `_id=9`（Maps、`container=6`、`rank=2`、
  `modified=0`→`<wallclock>`、`cellX/cellY=null`→`2/0`、`screen=null`→`0`）。
  - [run 34625444030](https://github.com/nunu1733/NunuLauncher/actions/runs/34625444030)
    attempt 1、job `organizer-instrumentation-api35-tests`（id 103349402467）
  - [run 34665957437](https://github.com/nunu1733/NunuLauncher/actions/runs/34665957437)
    attempt 1、同 job（id 103477809426）
- **失敗行の shape は default layout 由来 folder 子そのもの**:
  `AutoInstallsLayout` の folder 解析は子行に `container=folderId` と `rank` のみを設
  定し、`screen`/`cellX`/`cellY` を置かない
  （`src/com/android/launcher3/AutoInstallsLayout.java` の folder `parseAndAdd`）。
  CI の default layout（`lawnchair/res/xml/default_workspace_4x5.xml`）は hotseat
  resolve 5 行の直後に Google folder、続いて 9 子。XML 順に採番されると folder=`6`、
  Maps=3 番目の子=`9`（rank=2）。ローカル api35 emulator でも実測値は同一
  （`folder=6, Maps=9, rank=2`）。
- **一回きりの default load が favorites 全行を削除・再挿入する**:
  `ModelDbController.loadDefaultFavoritesIfNecessary()`
  （`src/com/android/launcher3/model/ModelDbController.java:1114`）は
  `EMPTY_DATABASE_CREATED` フラグが立っている場合、`createEmptyDB`（全行削除）の後
  default layout を再挿入する。呼び出し元は `LoaderTask.java:483` であり、各 model
  load（LoaderTask）で実行される。model が既に load 済みの rebind では再実行されない
  （`isModelLoaded` 待機の根拠はこの区別である）。フラグは db ファイル作成時に
  `createDatabaseHelper` の `onEmptyDbCreateCallback`（`ModelDbController.java:143-145`）
  が立て、初回 load で消化される。
- **テストの挿入 id はキャッシュ由来**:
  `DatabaseHelper.generateNewItemId()`
  （`src/com/android/launcher3/model/DatabaseHelper.java:484`）は `mMaxItemId`
  キャッシュ +1 を返す。テストの raw `db.delete`（`restoreFavorites`）はキャッシュを
  再初期化しない。
- **回転時の folder 子書込み経路**:
  `Folder.updateItemLocationsInDatabaseBatch` →
  `FolderGridOrganizer.updateRankAndPos` → `moveItemsInDatabase` が bind/回転 rebind
  時に `screen`/`cellX`/`cellY`/`modified` を書き込む
  （`src/com/android/launcher3/folder/Folder.java`）。既存の desktop/hotseat フィル
  タ（commit `e38c92aed4`）はこのクラスの行を除外する前提で正しい。
- **model bind は activity start まで走らない**（lane 実測・ローカル）: ローカル lane
  実行の test 結果 XML で、TwoPanel test 1/2 の `tearDown` が各 5.0 秒（`isModelLoaded`
  待ちがタイムアウト＝ model 未 bind）を消費し、test 3 のみ 3.1 秒だった。つまり初回
  bind は test 3 の `bringLauncherToForeground()` で発生する。
- **ローカル再現（メカニズム実証）**: emulator API 35 google_apis arm64
  （`nunu_smoke_api35`）で、app data を新規にした上で
  1. TwoPanel test 1 を単独実行（schema 生成＋ db 作成＝フラグ設定、行は tearDown で
     消える）
  2. `run-as app.lawnchair.debug sqlite3` で `container=6` のダミー行 5 行（id 2-6）
     を挿入（次の挿入 id を default layout の folder 子 id へ合わせる）
  3. TwoPanel クラス全体を `am instrument` で実行
     → `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` が
     `_id=8`（Gmail 子、`container=6`、`rank=1`、`modified=0`→`<wallclock>`、
     `cellX/cellY=null`→`1/0`）で失敗。CI と同種シグネチャ。
  - logcat タイムライン（`/tmp` 記録、PR に要約を貼る）:
    `13:19:12.388` test 開始 → `13:19:12.830` `LauncherProvider: loading default
    workspace`（= `createEmptyDB` + 再挿入）→ `13:19:14.906` 失敗
    （`TwoPanelOrientationCaptureInstrumentationTest.kt:196`）。
    テスト開始から 442ms 後に default load が走り、pick→rowsBefore 窓と重なった。

### 推測（メカニズムの成立に非依存）

- CI 失敗時にテストの挿入 id が 9 になった経路は「lane の先行クラス・先行テストでの
  `generateNewItemId()` 呼び出し合計が 8 回」である可能性が高いが、CI ログからは呼び
  出し回数を直接検証できない。メカニズムは挿入 id の具体的な値に依存しない（任意の挿
  入 id が、再採番後の default layout の同 id 行と衝突するだけである）。CI の実測値
  （9 → Maps 子）がこの衝突を直接示す。
- 「CI でも初回 bind が test 3 の foreground まで走らない」は間接証拠（attempt 1 の
  みの失敗・失敗シグネチャが test 3 の窓と整合・ローカル実測）による推測である。修正
  はこの推測に依存しない: bind 待ちにより、フラグが未消化でも消化済みでも競合は閉じ
  る。

## Design

### Modules and interfaces

- 変更は 1 テストファイルのみ:
  `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
- 計算と書込みの分離、interface、seam には触れない。検査対象の seam
  （`LayoutApplicationModule.apply` の stale rejection + no-write）は不変。
- `ensureLauncherRow` の desktop/hotseat フィルタ（commit `e38c92aed4`）は維持する。
  変更するのは呼び出しタイミングのみ。

### Data flow（修正後の test 3）

```text
bringLauncherToForeground()
  → enableLauncherTestRotation()
  → lockRotationTo(PORTRAIT)
  → awaitLauncherModelLoaded()   // NEW: 初回 bind 完了 = default load 消化
  → plannedRowId = ensureLauncherRow(...)   // 移動: bind 後なので再採番競合なし
  → captureCurrent → plan 生成 → rowsBefore
  → lockRotationTo(LANDSCAPE) → reconcileAtStart → apply（stale reject）
  → rowsAfter → marker title 無し + plan 行 before/after 一致 assert
```

待機は `launcher.model.isModelLoaded` を 100ms 間隔でポーリングし、deadline は 20 秒
とする（テスト既存の `awaitOrientation` と同じ上限。`enableLauncherTestRotation` の
15 秒に揃えると CI の遅い runner で近いので余裕を持たせる）。`isModelLoaded` が true
になるのは LoaderTask 完了後であり、`loadDefaultFavoritesIfNecessary`（LoaderTask
内、loadWorkspace 段階）はその前に完了しているため、フラグ消化を確実に含む。deadline
までに完了しない場合はメッセージ付きで `assertTrue` 失敗させる。organizer
coordinator の lease で loader が defer された場合（`LoaderTask.run` → `runOrDefer`、
Issue #14）もこのタイムアウトとして顕在化する。これは「環境が bind を完了できない」
ことの明示的な失敗であり、意図した挙動である。

行選択の移動が検証を壊さない根拠: `LauncherLayoutAdapter.capture()` は in-memory
model ではなく `controller.db` を直接読む（`RowManifestCodec.capture` に
`db = controller.db` を渡す。`lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt:97-131`）。
したがって bind 後に選択・挿入した行は、追加の model reload 無しに `captureCurrent`
から見える。

### Alternatives rejected

- **marker title だけの assert に弱める**: seam 契約（plan 行自体に書込みが無い）の
  検査が失われる。
- **bind 待ち無しで常時自前挿入にする**: 挿入行は default load の全行削除で消えるた
  め、競合は除去できない（ローカル再現がまさにこの経路を実証した）。
- **TestProtocol `REQUEST_CLEAR_DATA` / `REQUEST_REINITIALIZE_DATA` でフラグを先に消
  化する**: テスト protocol への新規結合であり、lane の他クラスの workspace 状態
  （空 workspace を後続に残す）に影響する。
- **テストの skip / rerun 運用継続**: メカニズムを隠すだけ。CI 2/4 の実績が rerun 運
  用の限界を示す。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/.../TwoPanelOrientationCaptureInstrumentationTest.kt` | test 3 の `ensureLauncherRow` 呼び出しを foreground+bind 待ちの後に移動、`awaitLauncherModelLoaded` ヘルパー追加、順序根拠のコメント（#292） | 競合の原因がテスト内の順序のみであり、production・他テストに影響しない |

## Migration and recovery

- schema/rule migration、rollback、backup/restore への影響なし（テストのみの変更）。

## Verification

Issue #292 の終了条件「連続複数runでのgreen確認」に対応するため、単発 run では完了と
扱わない。AC-3 と AC-5 が繰り返し条件を持つ。

| Acceptance criterion | Requirement | Automated/manual evidence | Command or environment |
|---|---|---|---|
| AC-1 現行テストが再現条件で失敗する（メカニズム実証） | TS-AC-04 | 済み。CI 2 回（#292 本文）＋ローカル再現（上記 Current evidence、`_id=8` Gmail 子、logcat タイムライン付き） | api35 emulator（google_apis arm64）+ `am instrument` |
| AC-2 修正後、同一の衝突準備（ダミー行 5 行）＋同一手順で green | TS-AC-01, TS-AC-02 | 修正 branch 上で再実行し PR へ記録 | 同上 |
| AC-3 修正後、新規インストール状態（フラグ未消化）からの 4 クラス lane が**連続 3 回** green（各 run 間に emulator の app data を消去してフラグを復元する） | TS-AC-01, TS-AC-02, TS-AC-04 | 修正 branch 上で 3 run 実行し、各 run の結果を PR へ記録 | `ANDROID_SERIAL=<api35> ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.rules.CategoryOverrideAtomicFileInstrumentationTest,com.android.launcher3.organizer.NestedTransactionTest,app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest` |
| AC-4 lint/formatter green | — | `./gradlew spotlessCheck` | JDK 21 / Android SDK 36.1 |
| AC-5 PR CI の api35 lane が、同一 fix head で**連続 3 回** green（job の rerun を含む。attempt 1 が green なら rerun 2 回） | TS-AC-01, TS-AC-02 | PR の CI run 記録を PR へ link | GitHub Actions `organizer-instrumentation-api35-tests` |

含めるべき観点の内、unit/property/DB-integration/UI は本変更の対象外（テストタイミ
ング修正のみのため）。failure injection 相当は AC-1/AC-2 の再現・消滅ペアで代替する
（フレイクは実行順序の競合であり、決定的な失敗注入が不可能なため。理由を PR に記載
する）。TS-AC-03（bind 不完了時の明示的失敗）は、コード上の assert メッセージと
deadline 定数で検証する（実行環境を人工的に作れないため、代替証拠を PR に記載する）。

## Documentation updates

- [ ] spec status/history（承認後に `accepted`、PR 後に `implemented`）
- [ ] CONTEXT.md — 不要（domain language 変更なし）
- [ ] DESIGN.md — 不要（system structure 変更なし）
- [ ] ADR — 不要（3 条件を満たす判断なし。テスト内コメントに根拠を残す）
- [ ] AGENTS.md — 不要（verified command 変更なし）

## Execution checklist

- [x] Current behavior reproduced.（CI 2 回 + ローカル再現）
- [ ] Tests fail for the missing behavior.（AC-1 がそれ自身。修正対象はテストであり、
  新規テストは不要）
- [ ] Minimal implementation completed.
- [ ] Migration/recovery verified.（対象外、テストのみ）
- [ ] Full relevant verification completed.（AC-2〜AC-5、AC-5のCI run記録はPR evidenceに含める）
- [ ] PR evidence and remaining risks recorded.
