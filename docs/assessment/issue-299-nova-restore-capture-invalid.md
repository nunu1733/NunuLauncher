# Assessment: Issue #299 — Nova restore後のOrganizer capture `CAPTURE_INVALID`

Status: `investigation I-1/I-2 complete`（throw-site直接捕捉済み、repair中断による持続再現、matrix拡張+de-scope判定。cross-process追跡はI-4残務、fix architectureはI-5 decision gate待ち）

Date: 2026-09-13（I-2完了revision）
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/299
Spec: [specs/299-nova-restore-capture-invalid/spec.md](../../specs/299-nova-restore-capture-invalid/spec.md)（accepted）
Plan: [specs/299-nova-restore-capture-invalid/plan.md](../../specs/299-nova-restore-capture-invalid/plan.md)

Evidence harness (committed): `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` + `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureInterruptedReloadTest` / `NovaRestoreCaptureUnknownProviderTest`。raw logcatはcommitしない（本書の引用はharness matrix行とshipped diagnostics行のみで、いずれも件数・分類・例外class identityのみを含む）。

## I-2 additions（2026-09-13, rev 2 — I-2 review反映）: throw-site直接捕捉とmatrix拡張

### capture throw-siteの直接捕捉（CI-AC-01の本体）

一時的なcapture path instrumentation（debug build限定・未commit・実施後にrevert済み。
`LayoutWriterCanonicalCaptureSource` のcatch節でclass simple nameとstack最上位frameを
debug logcatへ出力）により、repair-point中断が残したinvalid state上でのcapture失敗の
throw点を **直接捕捉**した（run @ 2026-09-13T22:04）:

```text
I299Probe: captureThrow class=IllegalArgumentException
           top=app.lawnchair.organizer.application.adapter.RowManifestCodec.toCanonical:297
```

`RowManifestCodec.kt:297` は
`requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }` である。
計測時の同時観測: widget行 `appWidgetId=-1` 1行（`widgetIdNegative=1 widgetIdValid=0`）、
capture fail-closed、observerへの通知は `IllegalArgumentException.class`。
これで「`appWidgetId=-1` が存在 + IAEが出た → 当該requireがthrow-site」が推論ではなく
直接観測になった。**synthetic issue-representative failureに対するCI-AC-01の
throw-site/invariant特定は完了**（元実機セッションとの同一性の限界は後述）。

### #298相当failure path（repair-point中断）での持続観測

同じく一時的な調査計測（`WorkspaceItemProcessor.processWidget` 冒頭のcrashを
cache-dir marker fileでtoggle、`IllegalStateException` を注入。実施後にrevert済み）で、
修復点がcrashした場合の挙動を観測した。logcatには
**`Desktop items loading interrupted`**（WorkspaceItemProcessorの既存catch行）として
記録された — #298/#287 セッションで観測されたloader中断signatureと同一の行
（両者が最終的にloader interruptionへ到達したことのevidence。同じ原因・同じ
interruption pointだったことのidentityではない）。

観測結果（SimulatedWrongThread scenario、1 process、run @ 2026-09-13T21:48）:

| 時点 | widget行 appWidgetId | restored flag | capture |
|---|---|---|---|
| 定点1（performRestore commit直後、I299Probe） | **-1** | 7 | —（capture不可時点） |
| 定点2（reloadAfterRestore直前、I299Probe） | -1（`modelLoaded=false` を同時記録） | 7 | — |
| 修復点crashのreload generation群の後（cycle1） | **-1（残存）** | 7 | **Invalid（IllegalArgumentException、throw-site上記）** |
| 中断を除去し完了generationを走らせた後（cycle2） | **有効値** | 4 | **Ready** |

I-2から言える範囲（evidence boundary）:

- **repair generationを同じ位置で継続的に中断すれば**、invalid rowと
  `CAPTURE_INVALID` は持続し、**中断を止めれば修復する**（直接証明）。
- 元実機セッションの「process再起動・再restoreでも不回復」をこの機構で
  説明できるというのは、現段階では **有力なmechanism hypothesis**である
  （process restart / re-restoreを跨ぐ追跡は未実施。#298のactual
  wrong-thread呼び出し連鎖も未捕捉。cross-process追跡とactual path特定は
  I-4残務 / #298 scope）。
- **定点1/2の取得**: `performRestore` commit直後（定点1）に既に
  `widgetIdNegative=1 / restored=7` であり、正規化は行われていないことが確定。
  `reloadAfterRestore` 直前（定点2）でも `modelLoaded=false`（reload未完了状態からの
  forceReload dispatch）を記録。定点4相当（中断generation後）はcycle1の観測が該当する。
- これらの計測はplan I-2の「出荷しない調査計測」契約どおり、PRから取り除いた
  （`git checkout`でrevert、一時scenario fileも削除済み。本assessmentの
  引用ログのみが残る）。

### matrix拡張: provider未インストールwidget（削除修復経路）

`NovaRestoreCaptureUnknownProviderTest`（committed harness、存在しないproviderの
widget行）の結果:

| 時点 | widget行 | capture |
|---|---|---|
| settle点前 | `appWidgetId=-1` 1行 | **Invalid（IllegalArgumentException）** |
| settle点後（削除修復完了） | **0行**（`markDeleted` で削除） | **Ready** |

installed provider（bind修復）とunknown provider（削除修復）のどちらの経路でも、
settle点までのcaptureは同一のcodec widget不変条件でfail-closedする。修復の
成否・形態にかかわらず「settle点までの窓が `CAPTURE_INVALID` になる」構造は共通である。

### 複数profile / grid変換窓 — de-scope判定

throw-siteの直接捕捉（上記）により、**synthetic issue-representative failureに
対して**、widget不変条件が実際のthrow点であることが確定した。providerの
bind/delete分岐が同じ不変条件に到達することに加え、この確定により
「profile inventory欠落（:90）」「page inventory不整合（:73）」
「NULL screen/cell/span（:232, :259-261）」「malformed intent（:370-401）」といった
**他のIAE candidateは、synthetic issue-representative failureのcapture失敗点から
除外された**（元実機セッションが同じrequireだったこと自体は未証明 —
「この調査で確定しないこと」節参照）。複数profileとgrid変換窓が新たな
IAE candidateを追加する可能性は、
candidate path（Nova restore → unbound widget行）が既に直接捕捉済みの以上に
優先される根拠がない。よって両項目を本Issueの調査matrixからde-scopeし、
将来I-3で成功/失敗差分が本mechanismで説明できない場合に限りI-2追補として
再開する（plan.mdへ同期済み）。

## Verdict（I-1/I-2で証明できた範囲）

capture側 `IllegalArgumentException` のthrow点
（`RowManifestCodec.kt:297` のwidget不変条件require）を、synthetic
issue-representative failure（repair-point中断が残したinvalid state）上で
**直接捕捉した**（I-2 additions参照）。元の#299実機セッションとの
identityの限界は下記2のとおり残る。

1. **確定した再現経路**: Nova restoreはwidget行を `appWidgetId=-1` で保存する
   （`NovaBackupConverter.insertNovaItems`（:463）の固定値。`RestoreDbTask.sanitizeDB`（:359-365）
   はrestore flag（観測値7 = ID_NOT_VALID|PROVIDER_NOT_READY|UI_NOT_READY）を付与するだけで
   idを再bindせず、`restoreAppWidgetIdsIfExists`（:516-532）はplatform restore用の
   `APP_WIDGET_IDS` prefsが無いNova pathでは実行されない —
   logcat: "Did not receive new app widget id map during Launcher restore"）。
   `toPersistentRow`（RowManifestCodec.kt:240）が負値をnull化し、
   captureは `requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }`
   （:296-297）で `IllegalArgumentException` となる — **このthrow点は一時
   instrumentationで直接捕捉済み**（`top=RowManifestCodec.toCanonical:297`）。
   例外は `LayoutWriterCanonicalCaptureSource`（OrganizationInputComposer.kt:94-110）がcatchして
   `CanonicalCaptureReadResult.Invalid`、composerはterminal `CAPTURE_INVALID` を返す。
   shipped diagnostics（debug build）は
   `OrganizerDiag: phase=CAPTURE exceptionClass=IllegalArgumentException` を出す。
2. **元障害とのidentityの限界**: throw点の直接捕捉はsynthetic
   issue-representative failure上のものである。元の実機セッションに残る
   shipped diagnosticsは例外class identityのみであり、「元のセッションも
   同一のrequireで落ちた」こと自体は推論の域を出ない。ただしthrow-site確定により、
   他のcodec IAE candidate（profile inventory欠落、page inventory不整合、
   NULL座標、malformed intent）は **synthetic issue-representative failureの
   capture失敗点から** 除外された（元実機セッションについての除外ではない）。
3. **修復機構（I-4経路cの実在確認）**: reload generationの内側で
   `WorkspaceItemProcessor.processWidget`（WorkspaceItemProcessor.kt:533-538）が
   `WidgetInflater` によるbind成功後に `APPWIDGET_ID` / `APPWIDGET_PROVIDER` / `RESTORED`
   をDBへ書き戻す（観測: flag 7→4、`appWidgetId` -1→有効値）。providerが
   未インストールでrestore未開始の場合は `markDeleted`（:497-505）で行を削除する。
   つまり**修復（bindまたは削除）はreload generation依存**であることまでは確認できた。
4. **持続性の証拠範囲（重要な限界）**: 直接証明できたのは次までである —
   **repair generationを同じ位置で継続的に中断すればinvalid rowと
   `CAPTURE_INVALID` は持続し、中断を止めれば修復する**。元障害のpersistence
   （process再起動・再restoreを跨ぐ恒常化）をこの機構で説明できるのは
   **有力なmechanism hypothesis**であり、process restart / re-restoreを跨ぐ
   cross-process追跡（I-4残務）と #298 actual wrong-thread pathの特定（#298 scope）
   が残る。
5. **#185非関与（この範囲での観測）**: 全runでreservation検証・`CAPTURE_RESERVED_OVERLAP`
   は発生せず、QSB予約不変条件への回帰・変種の兆候は観測されなかった
   （正式な非回帰確認はI-5で既存coverageのgreenをもって実施）。

## Environment

| | |
|---|---|
| Build | branch `issue-299-spec-plan` @ `cdc217d032` + review反映revision（app code `c502b22189`）、`assembleLawnWithQuickstepGithubDebug` + androidTest |
| Device | AVD `nunu_qpr2_api36_1`（Pixel 6 class, Google APIs, Android 16 / API 36.1, arm64） |
| Fixture | synthetic等価Nova backup（`nova.xml` + `nova.db`。test app自身のcomponentと合成identityのみ。grid=対象AVDの元grid 4列×5行、hotseat 4、widget/provider/deep-shortcut/folder/apps/hotseat要素） |
| Execution | 1 scenario class per `am instrument`（fresh process）。4 scenario classesすべてPASS |

## State matrix（bounded分類 — plan I-1の5定点のうち取得できた定点のみ）

表の「barrier後」はharnessのsettle heuristic到達後を意味する（§Completion barrier参照）。

| 定点 / variant | widget行 | appWidgetId | provider | restored flag | capture |
|---|---|---|---|---|---|
| restore直後（pre-barrier）/ widget有 | 1 | **-1** | あり | 7 | **Invalid（IllegalArgumentException）** |
| completion barrier後 / widget有 | 1 | **有効値** | あり | 4 | **Ready** |
| 中断generation直後 / widget有 | 1 | **-1** | あり | 7 | **Invalid（IllegalArgumentException）** |
| 中断後の完了generation / widget有 | 1 | **有効値** | あり | 4 | **Ready** |
| barrier後 / widget無（control） | 0 | — | — | — | Ready（items=9）※ |

※ fixtureはdeep shortcut 1行を含むが、loaderのrestore sanitize
（WorkspaceItemProcessor: "removing app that is not restored and not installing"）が
未インストールpackageの行をbarrier generation内でDBから削除するため、
barrier後のcaptureは9行。これも「修復・削除はreload generationの内側で起きる」
ことの観測である（plan I-4 経路c）。profiles=1、desktopPages=2 は全variantで一定。

### 未取得の定点・matrix項目（plan I-1からの未達とI-2移送先）

- 定点1（`performRestore` 直後）/ 定点2（`reloadAfterRestore` 開始時）/ 定点4
  （中断されたreload後・#298実経路相当）は、production flowの内部時点として未取得。
  `convertAndRestore` は atomic に実行されるため、これらはI-2のdebug-only調査計測
  （plan I-2: 出荷しない）での取得対象とする。
- 複数profile要素（matrix要件）、provider未インストールwidgetの削除修復が
  中断されたvariant、grid変換（fixture grid ≠ 元grid）窓： I-2のmatrix拡張対象。
- 1 process内で重なったreload generationの全挙動： generation-agnostic観測では
  判定不能（下記barrier節）。generation identity付き観測の整備後に再計測する。

## Completion barrier（plan I-1必須成果 — 正式性の区別を含む）

- **production signalは現存しない（構造的gapの確認 — 確定した成果）**。
  `RestoreDbTask.reloadAfterRestore` → `LauncherModel.forceReload()`
  （RestoreDbTask.java:283-288, LauncherModel.java:314-327）はcallbackを持たず、
  `convertAndRestore` return時に `isModelLoaded=false` を実測した
  （restore API returnはcompletionではない — plan/specの定義どおり）。
- **bind完了はterminal commitではない（production control flowで確認）**。
  `LauncherModel.LoaderTransaction` は `mLastLoadId++` をconstructor（load開始時）で行い
  （LauncherModel.java:675）、`commit()` は `mModelLoaded = true` のみ
  （:682-686）。`BgDataModel.lastLoadId` は `BaseLauncherBinder.bindWorkspace` 内で
  copyされる（BaseLauncherBinder.java:151,172）ため、terminal commitより前の
  bind時点で更新されうる。したがってbind完了発火も `lastLoadId` も
  「commit済みgeneration数」の信号にならず、本調査で両者から
  「何generationがcommitしたか」を導くことは撤回した。
- **現在のtest barrierは契約gradeのoracleではなく、generation identityを持たない
  調査用settle heuristicである**。harnessのbarrierは「`finishBindingItems` 発火後に
  `LauncherModel.isModelLoaded()` が成立」を条件とするheuristicであり、
  `isModelLoaded()`（`mModelLoaded && mLoaderTask == null && !mModelDestroyed`）は
  観測瞬間にmodelがloadedかつactive loaderが無いという **settled stateの確認**には
  使えるが、generation identityを運ばず、generation数も数えられない。
  **restore自身のgenerationのidentityは主張しない**。I-3定点3/5の判定はこの
  settle heuristicで行い、**generation-level causal attributionには使用しない**。
  CI-AC-02の正式oracleはgeneration identityを運ぶsignalを要求し、それは
  I-5 seam decisionの対象として残る。
- **既存の `forceReloadForOrganizer` はそのままbarrierに流用できないことを確認**
  （「test-onlyでgeneration相関signalが作れない」までは主張しない）。
  lease tokenが非ゼロだとloaderが修復sanitizeをスキップし（LoaderTask.java:291、
  organizer reloadはDB不変を保持する設計）、ゼロだと完了通知が発火しない
  （LoaderTask.java:429）。修復観測とgeneration相関完了を既存機構のままで
  両立させることはできない — この確認が、I-3をsettle heuristicで進め
  production変更をI-5まで持ち越す決定の根拠である。
- **I-5 seam候補（この時点での評価）**: restore pathのreloadを、completion観測付きかつ
  修復sanitizeを保持するgeneration identity付きbarrierへ置くこと
  （`forceReloadForOrganizer` + `OrganizerReloadRequest` tokenのidentity check /
  completed-cancelled区別 / #150境界snapshot deliveryの機構は再利用候補）。
  seam選択はI-5 decision gateで行う。

## Reproduction sequence（harness）

1. fixture zipをcache dirに生成（synthetic等価、privacy-equivalent）。
2. `NovaBackupConverter.parseInfo()` → `convertAndRestore(info)` をproduction pathのまま実行
   （BACKUP_RESTORE lease、staging DB、`applyConvertedGrid`、restored.db copy、
   `performRestore`、`reloadAfterRestore` をすべて通る）。
3. 直後にproduction capture sourceでcapture（pre-barrier観測）。
4. `finishBindingItems` latch + `isModelLoaded` のsettle heuristicで
   settled stateを観測（generation identityは主張しない）。
5. bounded state matrix（件数・分類のみ）をlogcatへ記録。
6. interrupted variantは `quiesceForRestore()` でgenerationを中断してから同様に観測。

## この調査で確定しないこと（I-2完了後 / I-3以降または未確認）

- 元の#299実機セッションが同一のthrow点（`RowManifestCodec:297` widget不変条件）で
  落ちたことの直接証明（元セッションのdiagnosticsは例外class identityのみ。
  throw-site直接捕捉はsynthetic issue-representative failure上のものである）。
- process restart / re-restoreを跨ぐcross-process persistence追跡（I-4残務）。
  現在の持続観測は1 process内のrepair-point継続中断である。
- #298の **actual** wrong-thread障害（`BaseIconCache.assertWorkerThread` 経由の
  `Cache accessed on wrong thread`、`Can't create handler inside Thread[NovaBackupRestore]`）
  そのものの再現。本調査は修復点crashを #298相当の **中断** として使用し、
  中断signature（`Desktop items loading interrupted`）と後続状態の因果は捕捉したが、
  両者の原因・interruption pointの同一性までは証明されておらず、
  wrong-thread呼び出し連鎖そのものの特定と修正は #298 のscopeである。
- 複数profile、grid変換（fixture grid ≠ 元grid）窓でのcapture読み取り
  （de-scope済み。再開条件は上記「複数profile / grid変換窓」節参照）。
- 影響を受けた実機セッションの実backup内容（privacy上取得不能。本調査はsynthetic等価で再現）。

## Findings

- #172契約のshipped diagnostics行（`phase=CAPTURE exceptionClass=IllegalArgumentException`）が
  harness上で再現され、throw-site（`RowManifestCodec:297` widget不変条件）が
  一時instrumentationで直接捕捉された。CI-AC-08のbounded category（widget不変条件の
  区別）への拡張はI-5後の実装で行う。
- spec CI-AC-02のoracle（completion barrier後の最初の権威的captureが成功）の
  検証surfaceは、正式oracle（production/testのgeneration-correlated barrier）が
  整備されるまで、本harnessの窓検証付き調査barrierで近似する。正式oracleの
  整備場所・形はI-5 decision gateの対象である。
- widget行 `appWidgetId=-1` を含むrestoreは「restore直後〜settle点前」のOrganizer要求に
  対して必ず `CAPTURE_INVALID` を返す。これはfail-closed契約どおりであり、UI側で
  restore直後のOrganizer要求にNotReady系の見せ方をするかどうかは本Issueのscope外
  （必要なら別Issue）。
