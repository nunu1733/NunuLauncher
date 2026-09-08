# Implementation Plan: Crash レポートの filesystem 安全な保存と pre-handler 失敗隔離

> Issue: #242
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

#237 AC-10 実機 triage (2026-09-07、Pixel 9a / ja locale) の観測記録 ([Issue #237 コメント](https://github.com/nunu1733/NunuLauncher/issues/237#issuecomment-5569381592)) と、現行 `main` (`4da41ef1bb`) の静的分析に基づく。

### 現在の失敗連鎖 (source-verified)

1. `lawnchair/src/app/lawnchair/bugreport/LawnchairBugReporter.kt:71` — `private val fileName = "$appName bug report ${SimpleDateFormat.getDateTimeInstance().format(Date())}"`。`getDateTimeInstance()` は default locale 依存で、ja では `2026/09/07 19:09:17` となり **`/` を含む**。
2. `save()` (`:86-94`) — `File(dest, "$fileName.txt").createNewFile()` が中間 directory 不存在で `IOException: No such file or directory` を **毎回** throw。catch がない。
3. `init` の uncaught exception handler (`:39-43`) — `sendNotification(throwable)` が throw すると、後続の `defaultHandler?.uncaughtException(thread, throwable)` が実行されない。reporter 自身の失敗が元例外の platform crash 処理を肩代わりに潰す。
4. 実機観測: `logcat` に `Uncaught exception pre-handler error: No such file or directory` + `am_wtf`、`cache/logs/` に空 directory のみ。report 本文・通知は不生成。

### 確認済みの事実 (確認済み)

- `save()` は失敗時に null を返す契約を既に持ち、null 時は `BugReport.file == null` として通知側が text copy / share へ fallback する (`BugReportReceiver.kt:126` の `fileUri == null` 分岐)。**throw するために** designed fallback が到達不能になっているのみである。
- `id = contents.hashCode()` は header (ファイル名) 結合 **前** の `contents` から計算される (`generateBugReport()` `:73-80`)。ファイル名変更は id・notification id に影響しない。
- `<hex id>` directory 命名 (`String.format("%x", ...)`) と `removeDismissedLogs()` の retention 契約は locale 非依存で、本修正と無関係に成立する。
- `Report` は `LawnchairBugReporter` の inner class で `Context` / `NotificationManager` 依存を持つため、JVM unit test には純粋関数への抽出が必要。
- JVM test source set は `build.gradle:364-366` で `tests/unit` を `test` srcDir として登録済み (`app.lawnchair` root package の実績あり)。Android framework class (`android.util.Log` 等) は JVM test で mock されないため、抽出関数は android import を含めてはならない。
- CI の JVM gate (`organizer-unit-tests`) は `--tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*'` の filter 形式であり、第 2 package tree を追加した前例が存在する (`.github/workflows/ci.yml:184`)。
- 本変更は favorites / DB / lock に触れないため `risk: layout-data` / `risk: migration` の high-risk 独立エビデンス gate は対象外。

### 推測と区別済みの事実 (推測)

- 上流 `LawnchairLauncher/lawnchair` に同種の locale 依存 bug が存在する可能性 (本 plan では未確認。報告は別 track)。
- save 失敗時の `Log` 記録の要否 (現行は log なし。本 plan では contract 変更を最小化するため新規 log 追加は optional とする)。

## Design

### Modules and interfaces

変更は `LawnchairBugReporter.kt` 単 file への純粋関数抽出と、既存 3 箇所の修正に限定する。新規 interface・module・adapter は作らない (設計規約: 必要になるまで仮想 interface を増やさない)。抽出関数は android import を含まない top-level `internal` 関数として **同一 file** に置き、JVM test から same-module で参照する。

1. **ファイル名構築の抽出と修正**

   ```kotlin
   internal fun buildReportFileName(appName: String, date: Date): String =
       "$appName bug report ${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)}"
   ```

   - 固定 pattern + 明示 `Locale.US` により、default locale に依存しない。使用文字は英数字・`-`・`_`・空白のみで `/` を含まない。
   - **timezone**: 出力は default timezone に依存する (同一 `Date` でも UTC と Asia/Tokyo で異なる文字列になる — 委託レビューで JDK 実測)。crash 時刻は triage のため device-local 時刻を維持する価値が高いため UTC への固定はせず、determinism 契約を「同一 `(appName, timestamp, default timezone)` 下で deterministic」に限定する。quality-strategy の locale・timezone 非依存規約は Organization Planning interface の項目であり、本修正への無条件適用ではない。
   - `Report.fileName` の initializer を `buildReportFileName(appName, Date())` へ置換。header 行 (`contentsWithHeader` の先頭行) も `2026-09-07_19-09-17` 型の固定表現になり人間可読性を維持する。
   - 秒解像度のため同一秒・同一 contents の 2 report は id 衝突で既存どおり null を返す (非回帰、挙動変化なし)。異なる contents なら id が異なり directory も分離するため、timestamp 保持で一意性の退行はない。

2. **report file 書き込みの抽出と save 失敗の縮退**

   ```kotlin
   internal fun writeReportFile(dest: File, fileName: String, contents: String): File? = try {
       dest.mkdirs()
       val file = File(dest, "$fileName.txt")
       if (file.createNewFile()) { file.writeText(contents); file } else null
   } catch (e: IOException) {
       null
   }
   ```

   - `save()` は `writeReportFile(File(logsFolder, String.format("%x", id)), fileName, contents)` へ委譲する薄い shell になる。**`save()` は新たな `Date()` を生成せず、Report 構築時に保持した同一 `fileName` を header と保存先で共有する** (現行 `:71` / `:90` の挙動維持)。実装時に新たに `buildReportFileName(appName, Date())` を呼ぶと、header と実ファイル名が秒境界を跨いで不一致になったり、同一 `Report` の再実行で現行より広い衝突回避 (新名前での保存) が発生するため禁止する。この接続は抽出関数単体の test では検出できないため、diff review の確認項目とする。
   - `createNewFile() == false` (id 衝突) と IOException の両方が既存の null 契約へ収束し、`BugReport.file == null` → 通知側 text fallback が designed path として到達可能になる。
   - `dest.mkdirs()` は既存どおり (`logsFolder` 自体は init 時に作成済み、`<hex id>` directory はここで作成)。
   - **既知 edge (現行と同等、非回帰)**: `createNewFile()` 成功後の `writeText()` 失敗 (disk full 等) では空 file が `<hex id>/` 配下に残留したまま null 縮退する。現行実装も同様の残留を起こすため回帰ではない。残留 file は `removeDismissedLogs()` (通知非 active 時の再起動時清掃) または active 通知の保持期間で自然に扱われる。失敗時の file 削除は null 契約を複雑化するため追加しない。

3. **pre-handler work の失敗隔離**

   ```kotlin
   internal fun dispatchUncaughtException(
       defaultHandler: Thread.UncaughtExceptionHandler?,
       thread: Thread,
       throwable: Throwable,
       preHandler: () -> Unit,
       onPreHandlerFailure: (Throwable) -> Unit = {},
   ) {
       try {
           try {
               preHandler()
           } catch (t: Throwable) {
               onPreHandlerFailure(t)
           }
       } catch (ignored: Throwable) {
           // 失敗記録経路自体の失敗は再記録できないため、委譲を優先して吞む (記録は best effort)
       } finally {
           defaultHandler?.uncaughtException(thread, throwable)
       }
   }
   ```

   - `init` の handler は `dispatchUncaughtException(defaultHandler, thread, throwable, { sendNotification(throwable) }) { Log.w(TAG, "Uncaught exception pre-handler error", it) }` へ委譲する。
   - **委譲は `finally` で保証・ちょうど 1 回**: pre-handler work がどの `Throwable` を throw しても、また失敗の記録 (`onPreHandlerFailure`) 自体が throw しても、platform default handler が元の `(thread, throwable)` で必ず呼ばれる (Issue 期待動作 2、owner review Blocker 1)。defaultHandler 自体の throw は platform の責務であり、ここでは扱わない。
   - 失敗記録は二段の隔離: 内側 catch が preHandler の throw を `onPreHandlerFailure` へ渡し、外側 catch が `onPreHandlerFailure` 自体の throw を吞む。記録は **best effort** であり、記録経路自体の失敗をさらに記録する安全な経路は存在しないため、再記録・再委譲はせず委譲の保証を優先する (委託レビュー P2-1)。
   - `onPreHandlerFailure` を parameter 化することで android `Log` への依存を抽出関数の外に置き、JVM test 可能性を保つ。

### Data flow

```text
uncaught exception (thread, throwable)
  → dispatchUncaughtException (抽出, 純粋)
      → preHandler: sendNotification → Report.generateBugReport
            → buildReportFileName (locale 非依存)
            → writeReportFile (IOException → null → file == null BugReport)
      → 失敗時: onPreHandlerFailure (Log.w, shell 側)
      → defaultHandler.uncaughtException(thread, throwable)  // 常時・1 回
```

入力・出力の shape 変更はなし。`BugReport` / 通知 / upload の data flow は現行のまま (file のみ従来 null になり得る契約が機能するようになる)。DB・preference・journal への書込みはゼロ。

### Alternatives rejected

- **Robolectric を導入して `Report` を直接 test**: dependency 追加は AGENTS 規約上 spec と risk 評価を要求し、`java.io` + 文字列組立ての検証には過剰。純粋関数抽出で回避する。
- **`java.time` (`DateTimeFormatter`) への置換**: 挙動は等価だが、minSdk / desugaring の確認が伴い diff が増える。固定 pattern + 明示 locale の `SimpleDateFormat` で locale 独立性・安全性は達成できる (thread-safety 不要: 毎 call 新規構築は現行と同じ)。実装 PR で `java.time` 利用が既に file 内で確立されていることが分かれば置換してよい (micro、挙動契約は同一)。
- **IOException の catch を `Throwable` に広げる**: null 契約の意味論が曖昧になる。`createNewFile` / `writeText` の failure (IOException) と既存 false 返却のみを縮退対象とする。
- **AC-4 の IOException 注入に「target path へ directory 先置き」を使う**: `File.createNewFile()` は対象 path が既に存在する場合、通常は `false` を返すのみで IOException にならない (owner review Blocker 2)。既存の id 衝突 branch と同じ挙動になり、Issue の直接原因である IOException 分岐が未検証のまま残る。確実な注入は **`dest` を通常 file として先に作成**し、その配下 (`dest/<name>.txt`) への作成で `IOException` を発生させる方法を採用する (具体型は環境依存 — JDK 21 / macOS APFS 実測では `IOException: Not a directory`。assertion は `IOException` に限定する)。
- **ファイル名から timestamp を除き id のみにする**: header の人間可読性が失われ、report 開閉時の識別が id hash にのみ依存する。timestamp は安全な形式で保持する。
- **pre-handler 委譲の retry・吞み込みを許す**: defaultHandler 委譲は platform crash 処理への単一委譲であり、retry・吞み込みは証跡を再び消す。委譲は `finally` で 1 回だけ保証し、preHandler と失敗記録 (`onPreHandlerFailure`) のみを隔離対象とする (spec AC-3)。
- **CI filter を追加せず targeted command のみで検証**: gate が新 test を自動で拾わず退行検出が手動依存になる。第 2 filter の前例が既にあり追加コストは最小。quality-strategy どおり第二の seam は作らない (同じ `tests/unit` source set)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/bugreport/LawnchairBugReporter.kt` | (1) `buildReportFileName` の抽出と固定 pattern + `Locale.US` 化、`fileName` initializer 置換。(2) `writeReportFile` の抽出と `save()` の委譲 + IOException 縮退 (**Report 保持の同一 `fileName` を header と保存先で共有**、新たな `Date()` 生成はしない)。(3) `dispatchUncaughtException` の抽出と handler shell 化 (Log.w は shell 側)。companion object へ `private const val TAG = "LawnchairBugReporter"` を追加 | 3 振る舞いの正本が 1 file に集約されており、新規 module を作る根拠がない。抽出は android import を含まないため JVM test 可能。TAG は shell 側の失敗記録 (`Log.w`) 用 (owner review Minor) |
| `tests/unit/app/lawnchair/bugreport/BugReportFileNameTest.kt` (新規) | ja 再現 test (red → green 反転)、locale 行列 (ja / en_US / de / fi / ar) の `/` 非含有・移植文字集合・determinism、header 表現の主張 | AC-1 の test surface。`tests/unit` は既存 JVM source set (`build.gradle:364-366`) |
| `tests/unit/app/lawnchair/bugreport/ReportFileSaveTest.kt` (新規) | save 成功 (temp directory への file 作成・内容一致・`<hex id>` 配下配置)、failure injection (**`dest` を通常 file として先に作成** → child 作成時の `IOException` (具体型への限定 assertion はしない) → null・例外非漏出)、id 衝突 (createNewFile false → null) | AC-2 / AC-4 / AC-5 (path 構造) の test surface。純粋 `java.io` のみで構成 |
| `tests/unit/app/lawnchair/bugreport/CrashPreHandlerIsolationTest.kt` (新規) | throw する preHandler + 記録用 fake default handler: 元 `(thread, throwable)` でちょうど 1 回委譲、pre-handler 由来例外が漏れず `onPreHandlerFailure` へ渡る、**`onPreHandlerFailure` 自体が throw しても委譲が保証される (外側 catch)**、preHandler 成功時も委譲される | AC-3 の test surface。純粋関数を直接呼ぶ |
| `.github/workflows/ci.yml` | `organizer-unit-tests` job の test command へ `--tests 'app.lawnchair.bugreport.*'` を追加 | 新 test を merge gate に接続する。workflow 変更 PR は同一 gates を実行する (quality-strategy) |

## Migration and recovery

- schema/rule migration: なし。DB / preference / journal への書込みはゼロ。
- failure 中の rollback: 対象外 (report 保存は fire-and-forget であり、失敗時は null 縮退 → text 通知 fallback)。layout 適用系の安全規約 (recovery point 等) は本変更に適用されない。
- release rollback/downgrade: 旧 build は常に save 失敗していたため、`/` 含みファイル名の legacy file は存在しない。新形式ファイル名は旧 `removeDismissedLogs` (`<hex id>` directory 走査) と互換であり、downgrade 時の清掃も壊れない。
- backup/restore compatibility: cache directory は backup 対象外。影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 ファイル名の安全性・locale 独立性 | `BugReportFileNameTest`: `Locale.setDefault(Locale.JAPAN)` での `/` 非含有 (現行 logic 抽出 commit 上で red、fix 後 green)、locale 行列・同一 `(appName, timestamp, default timezone)` 下の determinism・移植文字集合 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.bugreport.*'` |
| AC-2 save 成功 | `ReportFileSaveTest`: 非null `File`、内容一致、path 構造 | 同上 |
| AC-3 pre-handler 失敗隔離 | `CrashPreHandlerIsolationTest`: throw する preHandler でも default handler が元 throwable で 1 回呼ばれる、例外非漏出、failure が `onPreHandlerFailure` に渡る、**`onPreHandlerFailure` 自体が throw しても委譲が 1 回保証される** | 同上 |
| AC-4 save 失敗時の縮退 | `ReportFileSaveTest` failure injection case: `dest` 通常 file 先置き → child 作成時 IOException → null 返却・例外非漏出。id 衝突 case (target path の file 先置き → `createNewFile` false → null) | 同上 |
| AC-5 retention・id 契約の無変更 | `ReportFileSaveTest` path 主張 + `removeDismissedLogs` / id 計算の zero-diff review | diff review (PR) |
| AC-6 organizer 非回帰 | 既存 organizer JVM gate 無変更で pass | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` / CI `organizer-unit-tests` job |
| AC-7 文書・証跡 | spec status/history 更新、PR へ AC ごと evidence 記録 | PR |
| 補助 (任意) 実機 evidence | ja locale 実機で debug build に `adb shell am crash <debug applicationId>` → `cache/logs/<hex id>/` へ file 作成・crash 通知表示、logcat に `Uncaught exception pre-handler error` が現れないこと (#237 triage 環境の再現確認) | Pixel 9a / API 36 実機または emulator |

含めるべき観点:

- **unit/contract**: 3 抽出関数の全分岐 (成功・false 返却・IOException・preHandler throw・onPreHandlerFailure throw・preHandler 成功)。locale 行列は parameterized。
- **failure injection**: save 先の書き込み不能状態 (`dest` を通常 file として先に作成) と preHandler / onPreHandlerFailure の throw を注入し、いずれも例外が caller へ漏れず委譲が保証されることを主張。
- **determinism**: 同一 `(appName, timestamp, default timezone)` で繰り返し生成したファイル名が一致。timezone 非依存は契約外 (device-local 時刻の維持を優先)。quality-strategy の「locale、timezone に依存しない」規約は Organization Planning interface の項目であり、本修正への無条件適用ではない。
- **performance / property / DB / UI**: 対象外 (報告 path の I/O 1 回、DB 書込みなし、通知 UI は無変更)。
- **format**: `./gradlew spotlessCheck` (ktfmt) を通す。

## Documentation updates

- [x] spec status/history: owner review 通過時に `accepted`、実装 PR で `implemented` へ更新
- [ ] CONTEXT.md: domain 語の追加判定 (「crash pre-handler work」が生きた語になるか実装 PR で判断。実装語の域を出なければ追加しない)
- [ ] DESIGN.md: 変更なし (module 構造・seam 構造は不変。`:bugReport` secondary process の記述に影響しない)
- [ ] ADR: 不要 (選択肢比較は spec / 本 plan の Alternatives rejected に留まり、変更困難な架橋判断を生まない)
- [ ] AGENTS.md: 変更なし (新 command なし。CI filter 追加は既存 gate への package tree 追加であり検証済み command の追加を伴わない)

## Execution checklist

- [ ] Current behavior reproduced: 現行 `buildReportFileName` 相当 logic (抽出 commit 直後) 上で ja locale test が red になることを確認 (`/` 含みファイル名の再現)
- [ ] Tests fail for the missing behavior: `BugReportFileNameTest` (ja 再現 case) が抽出後・修正前の code で fail することを確認してから修正する (extract-then-fix)
- [ ] Minimal implementation completed: ファイル名修正 → save 縮退 → handler 隔離の順。`removeDismissedLogs` / id 計算 / 通知・upload 経路を変更しない
- [ ] Shell 接続の diff review: `save()` が Report 保持の同一 `fileName` を header と保存先の双方に使用していること (新たな `Date()` 生成の混入なし)。抽出関数単体 test では検出されない接続箇所
- [ ] CI filter 追加 (`app.lawnchair.bugreport.*`) と workflow gate の成功確認
- [ ] Full relevant verification completed: `spotlessCheck` + targeted unit test + organizer gate + `assembleLawnWithQuickstepGithubDebug` の成功を PR へ記録
- [ ] PR evidence and remaining risks recorded: `Closes #242`、AC ごと evidence、残余 risk (上流への同種 bug 報告は別 track である旨) を明記

## Open questions (owner approval 時の判断事項)

- CI filter 追加の採否 (plan の推奨: 追加。spec Open questions と同じ論点)
- save 失敗時の `Log.w` 追加の要否 (現行契約は log なし。追加する場合は AC-4 の evidence に記載)
