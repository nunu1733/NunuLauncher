# Assessment: Issue #299 — Nova restore後のOrganizer capture `CAPTURE_INVALID`

Status: `investigation I-1 complete`（再現経路の確立とcompletion barrier評価。元障害のroot cause確定（CI-AC-01）はI-2、fix architectureはI-5 decision gate待ち）

Date: 2026-09-13（I-1 review反映revision）
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/299
Spec: [specs/299-nova-restore-capture-invalid/spec.md](../../specs/299-nova-restore-capture-invalid/spec.md)（accepted）
Plan: [specs/299-nova-restore-capture-invalid/plan.md](../../specs/299-nova-restore-capture-invalid/plan.md)

Evidence harness (committed): `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` + `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureInterruptedReloadTest`。raw logcatはcommitしない（本書の引用はharness matrix行とshipped diagnostics行のみで、いずれも件数・分類・例外class identityのみを含む）。

## Verdict（I-1で証明できた範囲）

capture側 `IllegalArgumentException` について、**元の#299実機障害と整合する再現可能なcandidate path**をemulator上で確立した。元障害のthrow点同定（CI-AC-01 close）はこの時点では確定していない（下記「CI-AC-01のclose条件」）。

1. **確定した再現経路**: Nova restoreはwidget行を `appWidgetId=-1` で保存する
   （`NovaBackupConverter.insertNovaItems`（:463）の固定値。`RestoreDbTask.sanitizeDB`（:359-365）
   はrestore flag（観測値7 = ID_NOT_VALID|PROVIDER_NOT_READY|UI_NOT_READY）を付与するだけで
   idを再bindせず、`restoreAppWidgetIdsIfExists`（:516-532）はplatform restore用の
   `APP_WIDGET_IDS` prefsが無いNova pathでは実行されない —
   logcat: "Did not receive new app widget id map during Launcher restore"）。
   `toPersistentRow`（RowManifestCodec.kt:240）が負値をnull化し、
   captureは `requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }`
   （:296-297）で `IllegalArgumentException` となる。例外は
   `LayoutWriterCanonicalCaptureSource`（OrganizationInputComposer.kt:94-110）がcatchして
   `CanonicalCaptureReadResult.Invalid`、composerはterminal `CAPTURE_INVALID` を返す。
   shipped diagnostics（debug build）は
   `OrganizerDiag: phase=CAPTURE exceptionClass=IllegalArgumentException` を出す。
   この経路はproduction codeと一致し、emulatorで再現・固定済みである。
2. **元障害とのidentityの限界**: 元の実機セッションに残るshipped diagnosticsは
   例外class identityのみであり、本harnessはsynthetic fixtureを使用している。
   したがって「元のセッションも上記requireで落ちた」というthrow-site identityまでは
   証明できない。例外class identityのみが一致する、productionと一致する再現可能経路、
   というのが現時点の証拠範囲である。
3. **CI-AC-01のclose条件（I-2で実施）**: 次のいずれかでissue-representative failureを
   捕捉してからcloseする。(a) #298相当のfailure path（reload中断を含む実障害経路）で
   同一のinvariant categoryを観測する、または (b) throw-siteを区別できるdebug-only
   instrumentation（出荷しない調査計測）で元障害相当のfailureを捕捉する。
4. **修復機構（I-4経路cの実在確認）**: reload generationの内側で
   `WorkspaceItemProcessor.processWidget`（WorkspaceItemProcessor.kt:533-538）が
   `WidgetInflater` によるbind成功後に `APPWIDGET_ID` / `APPWIDGET_PROVIDER` / `RESTORED`
   をDBへ書き戻す（観測: flag 7→4、`appWidgetId` -1→有効値）。providerが
   未インストールでrestore未開始の場合は `markDeleted`（:497-505）で行を削除する。
   つまり**修復（bindまたは削除）はreload generation依存**であることまでは確認できた。
5. **持続性の証拠範囲（重要な限界）**: 本harnessが直接証明したのは、
   (a) `quiesceForRestore()` によるin-flight loadの停止がunbound widget行を残すこと、
   (b) その後に**正常に完了したreload generation**が修復を実行すること、の2点である。
   したがって、今回再現したwidget invariantについては
   「正常completion generationが走れば修復される」。元障害のpersistence
   （process再起動・再restoreを跨ぐ恒常化）を説明する**有力仮説**は
   「reload修復が正常完了しない」ことだが、**persistent variantは本調査では未再現**であり、
   #298のactual wrong-thread pathも未再現である。この仮説の検証はI-2/#298側の再現に委ねる。
6. **#185非関与（この範囲での観測）**: 全runでreservation検証・`CAPTURE_RESERVED_OVERLAP`
   は発生せず、QSB予約不変条件への回帰・変種の兆候は観測されなかった
   （正式な非回帰確認はI-5で既存coverageのgreenをもって実施）。

## Environment

| | |
|---|---|
| Build | branch `issue-299-spec-plan` @ `cdc217d032` + review反映revision（app code `c502b22189`）、`assembleLawnWithQuickstepGithubDebug` + androidTest |
| Device | AVD `nunu_qpr2_api36_1`（Pixel 6 class, Google APIs, Android 16 / API 36.1, arm64） |
| Fixture | synthetic等価Nova backup（`nova.xml` + `nova.db`。test app自身のcomponentと合成identityのみ。grid=対象AVDの元grid 4列×5行、hotseat 4、widget/provider/deep-shortcut/folder/apps/hotseat要素） |
| Execution | 1 scenario class per `am instrument`（fresh process）。3 scenario classesすべてPASS |

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

## この調査で確定しないこと（I-2以降 / 未確認範囲）

- #298のwrong-thread障害そのものの再現（本調査は `quiesceForRestore` を中断のproxyとして使用）。
  中断が修復世代を横断的に壊し続ける経路（持続条件の本体）は #298 側の再現が必要。
- persistent variant（process再起動・再restoreを跨ぐ恒常的 `CAPTURE_INVALID`）の再現 — 未達。
- 元障害セッションのthrow-site identity（CI-AC-01 close条件は上記4参照）。
- 複数profile、provider未インストールwidgetの削除修復が中断された場合の挙動、
  grid変換（fixture grid ≠ 元grid）窓でのcapture読み取り。いずれもI-2のmatrix拡張対象。
- 影響を受けた実機セッションの実backup内容（privacy上取得不能。本調査はsynthetic等価で再現）。

## Findings

- #172契約のshipped diagnostics行（`phase=CAPTURE exceptionClass=IllegalArgumentException`）が
  harness上で再現され、candidate pathの例外class identityが本障害の観測signatureと
  一致することを確認した。CI-AC-08のbounded category（widget不変条件の区別）への
  拡張はI-5後の実装で行う。
- spec CI-AC-02のoracle（completion barrier後の最初の権威的captureが成功）の
  検証surfaceは、正式oracle（production/testのgeneration-correlated barrier）が
  整備されるまで、本harnessの窓検証付き調査barrierで近似する。正式oracleの
  整備場所・形はI-5 decision gateの対象である。
- widget行 `appWidgetId=-1` を含むrestoreは「restore直後〜barrier前」のOrganizer要求に
  対して必ず `CAPTURE_INVALID` を返す。これはfail-closed契約どおりであり、UI側で
  restore直後のOrganizer要求にNotReady系の見せ方をするかどうかは本Issueのscope外
  （必要なら別Issue）。
