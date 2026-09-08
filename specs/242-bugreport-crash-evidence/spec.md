---
issue: "#242"
status: draft
requirements: [R1-SAFE-FILENAME, R2-SAVE-SUCCESS, R3-PRE-HANDLER-ISOLATION, R4-GRACEFUL-DEGRADE, R5-RETENTION-CONTRACT, R6-NO-ORGANIZER-REGRESSION]
risk: []
updated: 2026-09-08
---

# Crash レポートの証跡が全 locale で永続化される（filesystem 安全なファイル名と crash pre-handler の失敗隔離）

## Problem

[Issue #242](https://github.com/nunu1733/NunuLauncher/issues/242)。#237 AC-10 の実機 triage (2026-09-07、Pixel 9a / ja locale) で、未処理例外の調査中に crash レポートが一切保存されない pre-existing bug が発見された ([triage 記録](https://github.com/nunu1733/NunuLauncher/issues/237#issuecomment-5569381592))。

静的分析 (source-verified @ `4da41ef1bb`) による失敗連鎖:

1. `LawnchairBugReporter.kt:71` が report ファイル名を `"$appName bug report ${SimpleDateFormat.getDateTimeInstance().format(Date())}"` で生成する。`getDateTimeInstance()` は **default locale 依存**であり、ja では `2026/09/07 19:09:17` のように **`/` を含む**文字列になる。
2. `save()` (`:86-94`) は `File(dest, "$fileName.txt").createNewFile()` を行う。`fileName` 中の `/` は存在しない中間 directory (`.../logs/<id>/Lawnchair bug report 2026/09/07` 等) を指すため、**毎回** `IOException: No such file or directory` で失敗する。
3. `save()` には catch がなく、IOException は `generateBugReport()` → `sendNotification()` → uncaught exception handler (`:39-43`) へ伝播する。handler は `sendNotification(throwable)` の後に `defaultHandler?.uncaughtException(thread, throwable)` を呼ぶ構造だが、**前者が throw するため後者は実行されない**。reporter 自身の失敗が元例外の platform 標準 crash 処理 (crash buffer への記録等) を肩代わりに潰し、実機では `Uncaught exception pre-handler error: No such file or directory` + `am_wtf` のみが記録された。
4. 結果: `cache/logs/<id>/` に空 directory だけが残り、report 本文・通知は生成されない。実際に例外が起きた run は organizer diagnostics journal (`RUN_STARTED`/`CAPTURED` のみ) から間接的にしか追えず、triage を阻害した。

なお `save()` は null 返却時に `BugReport.file == null` として通知側が text 共有へ fallback する設計 (`BugReportReceiver.kt:126` の `fileUri == null` 分岐) が既に存在するが、`createNewFile` が **throw** するためこの designed fallback は到達不能である。

## Outcome

どの device locale でも uncaught exception の report ファイルが `cache/logs/<hex id>/` 配下に作成され、crash 通知に添付可能な file が載る。reporter 自身の失敗 (report 生成・通知のいかなる throw) が発生しても、元例外は platform 標準の crash 処理へ必ず委譲され、crash 証跡が reporter の失敗によって消えない。save が失敗した場合も既存の designed fallback (file なしの text 通知) へ縮退し、例外が handler 外へ漏れない。

## Scope

- **report ファイル名の生成** (`LawnchairBugReporter.kt`): 固定 pattern + 明示 locale により、locale に依存せず filesystem 移植安全な文字 (`/` なし、ASCII 系の移植可能な文字集合) で構成する。`(appName, timestamp)` に対し deterministic。`contentsWithHeader` の先頭行 (header) は人間が読める timestamp 表現を維持する。
- **save 失敗時の挙動** (`save()`): IOException を catch して既存の null 契約 (→ `file == null` 通知) へ縮退する。`createNewFile()` が false を返す既存 case (id 衝突) の扱いは無変更。
- **uncaught exception handler の失敗隔離** (`init` 内 handler): pre-handler work (report 生成 + 通知) を隔離し、あらゆる `Throwable` を catch して記録した上で、platform の default handler へ元の `(thread, throwable)` を **必ず 1 回** 委譲する。
- **JVM test 可能化のための最小抽出**: 上記 3 振る舞い (ファイル名構築、report file 書き込み、pre-handler dispatch) を同一 file 内の純粋関数として抽出する。新規 interface・module・adapter は作らない (設計規約: 必要になるまで仮想 interface を増やさない)。
- **JVM test の追加** (`tests/unit/app/lawnchair/bugreport/`) と、**CI organizer unit-test job の `--tests` filter への追加** (`app.lawnchair.bugreport.*`。同 job は既に `app.lawnchair.ui.preferences.navigation.*` を含む前例がある。第二の test seam は作らない)。
- spec の status/history 更新を同じ PR で行う。

## Non-goals

- `UploaderService` / `KatbinService` / `FileProvider` / 通知 UI・channel・action の変更 (report upload 経路は `:bugReport` process であり本修正対象外)。
- notification id (`contents.hashCode()`) と `<hex id>` directory 命名 (`String.format("%x", ...)`) の意味変更。
- `removeDismissedLogs()` の init 時 main-thread file I/O (StrictMode 観点、pre-existing)。課題が顕在化した場合は別 Issue で追跡する。
- organizer 実装・#237 の受入への影響 (Issue scope に明記された分離対象。triage を阻害した pre-existing bug として本 Issue で独立に修正)。
- 上流 `LawnchairLauncher/lawnchair` への報告: 同種の locale 依存 bug が上流にも存在する可能性が高いが、AGENTS.md の上流報告 route (上流 Issue chooser) を用いる別 track であり、本 Issue の終了条件ではない。
- Robolectric 等の test framework 追加 (dependency 追加は spec と risk 評価を要求する設計規約により、純粋関数抽出で回避する)。

## Domain language

- **crash pre-handler work**: アプリが設定した default uncaught exception handler が、platform の default handler へ委譲する前に行う report 生成・通知の一連の処理。CONTEXT.md への追加は実装 PR で判定する (実装語の域を出ない可能性が高い)。

## Behavior scenarios

### Scenario: ja locale で crash 証跡が保存される

Given device default locale が ja であり (JVM 上では `Locale.setDefault(Locale.JAPAN)` で現在の `2026/09/07 19:09:17` 型出力を再現できる)、report 保存先 directory が書き込み可能である,

When uncaught exception が発生し `Report.generateBugReport()` が実行される,

Then report file が `cache/logs/<hex id>/` 配下に作成され、`save()` は非 null の `File` を返し、IOException は発生しない,

And ファイル名は path separator (`/`) を含まず移植可能な文字集合のみで構成され、crash 通知が生成される (file 添付・share action が利用できる)。

### Scenario: ファイル名は locale 行列に対して安全で deterministic である

Given 日付形式に `/`・`:`・非 ASCII 数字等を含みうる locale 行列 (ja, en_US, de, fi, ar 等) のそれぞれを default locale として設定する,

When 同一の `(appName, timestamp)` から report ファイル名を生成する,

Then 全ての出力が `/` を含まず移植可能な文字集合のみで構成され、同一入力に対する出力は deterministic である,

And header 行 (`contentsWithHeader` の先頭行) は人間が読める timestamp 表現を維持する。

### Scenario: save 失敗時は例外を漏らさず text 通知へ縮退する

Given save 先が書き込み不能である (`dest` 自体が通常 file として存在し、その配下への child file 作成が `FileNotFoundException` (IOException) を throw する状態を注入)、または `createNewFile()` が false を返す (id 衝突),

When `generateBugReport()` が実行される,

Then `save()` は null を返し、`generateBugReport()` は `file == null` の `BugReport` を返し、例外は handler 外へ漏れない,

And 通知側は既存の `file == null` fallback (text copy / share action) で動作する。

### Scenario: reporter 自身の失敗が元例外の crash 処理を潰さない

Given アプリの default uncaught exception handler が登録されており、pre-handler work (report 生成または通知) が `Throwable` を throw する状態である (注入または実障害)、さらに失敗の記録経路自体も `Throwable` を throw しうる,

When uncaught exception handler が実行される,

Then platform の default handler が元の `(thread, throwable)` で **ちょうど 1 回** 呼ばれ (pre-handler work の throw と失敗記録経路の throw のいずれが発生しても)、pre-handler work の失敗は記録される (caller 側の log),

And 元例外の stack trace が platform の crash 処理 (crash buffer 等の証跡) に到達し、reporter の失敗によって消えない (Issue の期待動作 2)。

### Scenario: id 衝突の既存挙動は維持される

Given 同一内容の report が既に保存済みで `createNewFile()` が false を返す,

When `generateBugReport()` が実行される,

Then `save()` は null を返し、`file == null` の `BugReport` が返る既存 fallback のみで動作する,

And id 生成 (`contents.hashCode()`) は header (ファイル名) 変更の影響を受けない (`id` は header 結合前の `contents` から計算されるため)。

### Scenario: retention 契約は無変更である

Given report file の保存成功・失敗が混在する状態で process が再起動する,

When `removeDismissedLogs()` が init で実行される,

Then `<hex id>` directory 命名と「active notification に対応しない entry を deleteRecursively する」retention 契約は無変更であり、save 失敗が残した空 directory も従来どおり清掃される。

## Data and state

- 読み書き: `cacheDir/logs/<hex id>/<report file名>.txt` のみ。DB への書込み・migration・backup/restore への影響は **なし** (cache directory は backup 対象外)。
- `id = contents.hashCode()` と notification id の意味は無変更。ファイル名変更は id に影響しない (id は header 結合前の `contents` から計算)。
- report text の変化は先頭 header 行の timestamp 表現のみ (locale 依存表現から固定 format へ)。本文・metadata 行は無変更。
- retention (`removeDismissedLogs`) の契約は無変更。

## Permissions, privacy, and security

None。新たな permission・network・telemetry は追加しない。report の保存先・内容範囲は現行と同一 (app cache 内) であり、ファイル名の安全化は file を外部共有する際の portability をむしろ改善する。

## Accessibility and localization

- 通知の string・action 変更なし。a11y への影響なし。
- 本修正は locale 依存の排除が主題であり、実装で default locale に依存する日付 format を新たに持ち込まない。翻訳の追加・変更は不要。

## Acceptance criteria

- [ ] AC-1 (R1): **ファイル名の安全性・locale 独立性**。ja を default locale とした状態で生成される report ファイル名に `/` が含まれない (現行実装で再現する red test が fix 後 green へ反転)。locale 行列 (ja, en_US, de, fi, ar) のいずれでも `/` を含まず移植可能な文字集合のみで、同一入力に対し deterministic。header 行は人間が読める timestamp を維持する。
- [ ] AC-2 (R2): **save 成功**。任意の locale で `generateBugReport()` が非 null の `File` を持つ `BugReport` を返し、file が `cache/logs/<hex id>/` 配下に存在し内容が `contentsWithHeader` と一致する。
- [ ] AC-3 (R3): **pre-handler 失敗隔離**。pre-handler work が `Throwable` を throw しても、また失敗の記録経路自体が `Throwable` を throw しても、platform default handler が元の `(thread, throwable)` でちょうど 1 回呼ばれ (`finally` による保証)、pre-handler 由来の例外は handler 外へ漏れず、失敗が log に記録される。
- [ ] AC-4 (R4): **save 失敗時の縮退**。注入した save 失敗に対し `file == null` の `BugReport` が返り、例外が漏れず、通知側の既存 fallback 分岐が使われる。
- [ ] AC-5 (R5): **retention・id 契約の無変更**。file は `<hex id>` directory 配下に置かれ、`removeDismissedLogs`・notification id の logic に機能的な diff がない (diff review で確認)。
- [ ] AC-6 (R6): **organizer 非回帰**。既存 organizer JVM gate (`app.lawnchair.organizer.*`) が無変更で pass する。
- [ ] AC-7: **文書・証跡**。spec status/history が更新され、PR へ AC ごとの evidence (test 結果、CI run URL、任意の実機 evidence) が記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `tests/unit/app/lawnchair/bugreport/` (例: `BugReportFileNameTest`): `Locale.setDefault(Locale.JAPAN)` 再現 test (現行 `main` で red)、locale 行列 (ja / en_US / de / fi / ar) の `/` 非含有・移植文字集合・determinism 主張。command: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.bugreport.*'` |
| AC-2 | `ReportFileSaveTest` (同 directory) の save 成功 case: temp directory への file 作成・内容一致・`<hex id>` 配下配置の主張 |
| AC-3 | `CrashPreHandlerIsolationTest` (同 directory): throw する pre-handler work lambda + 記録用 fake default handler — 元 throwable でちょうど 1 回呼ばれる / 例外が漏れない / 失敗が `onPreHandlerFailure` へ渡る / **`onPreHandlerFailure` 自体が throw しても委譲される**。本 JVM surface の CI 実行は filter 追加後の `organizer-unit-tests` job |
| AC-4 | `ReportFileSaveTest` failure injection (`dest` を通常 file として先に作成 → child 作成時の `FileNotFoundException` (IOException) → null 返却・例外非漏出。target path が既に存在する場合は `createNewFile` が false を返すため IOException 注入には使えない)、id 衝突 case (target path の file 先置き → `createNewFile` false → null) |
| AC-5 | save test の path 構造主張 + `removeDismissedLogs` / notification id logic の zero-diff review |
| AC-6 | CI `organizer-unit-tests` job (無変更分) の成功 run URL |
| AC-7 | PR 本文の AC ごと evidence 記録 |
| 補助実機 evidence (任意) | ja locale 実機で debug build に `adb shell am crash <debug applicationId>` → `cache/logs/<hex id>/` への file 作成・通知表示を確認し、logcat に `Uncaught exception pre-handler error` が現れないこと (#237 triage 環境の再現確認) |

## Open questions

- **CI filter 追加の要否** (plan の Alternatives rejected で比較): 追加を推奨する。既に `ui.preferences.navigation.*` の前例があり第二の seam を作らない。owner approval 時の判断事項だが実装開始を blocking しない (filter 追加の有無にかかわらず、targeted command による検証は可能)。
- **上流への報告**: 別 track (上流 Issue chooser) として起票するかを owner が判断する。本 Issue の終了条件ではない。

## Change history

- 2026-09-08: Draft created for #242。#237 AC-10 実機 triage の観測記録と現行 `main` (`4da41ef1bb`) における静的分析を evidence として作成。
- 2026-09-09: Review revision (owner review @ [Issue #242 コメント](https://github.com/nunu1733/NunuLauncher/issues/242#issuecomment-5587856141)、Changes requested — 2 blockers): (1) **P1** — pre-handler 失敗隔離を `finally` による委譲保証 + 二段の隔離 (preHandler の throw を `onPreHandlerFailure` へ、`onPreHandlerFailure` 自体の throw を吞む) へ変更し、AC-3 と該当 scenario / test oracle に記録経路の throw case を追加。(2) **P1** — AC-4 の IOException 注入方法を「target path への directory 先置き」(実際には `createNewFile` が false を返すため不成立) から「`dest` を通常 file として先に作成」へ修正し、spec / plan の test oracle と Alternatives rejected に不成立の理由を記録。(3) **P3** — `Log.w` 用 `TAG` 定数の追加を plan Change set へ明記。

## References

- [Issue #242: LawnchairBugReporter の crash レポート保存が常に IOException で失敗する](https://github.com/nunu1733/NunuLauncher/issues/242)
- [#237 AC-10 実機 evidence 取得時の調査記録 (triage 記録)](https://github.com/nunu1733/NunuLauncher/issues/237#issuecomment-5569381592)
- PR #241 (triage を行った実装 PR)
- [docs/engineering/quality-strategy.md](../../docs/engineering/quality-strategy.md) (organizer unit-test CI gate / test seam 規約)
