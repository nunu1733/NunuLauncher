# Assessment: Issue #299 — Nova restore後のOrganizer capture `CAPTURE_INVALID`

Status: `investigation I-1/I-2 complete`（再現経路確立、issue-representative failure捕捉、定点matrix拡張。fix architectureはI-5 decision gate待ち）

Date: 2026-09-13（I-2完了revision）
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/299
Spec: [specs/299-nova-restore-capture-invalid/spec.md](../../specs/299-nova-restore-capture-invalid/spec.md)（accepted）
Plan: [specs/299-nova-restore-capture-invalid/plan.md](../../specs/299-nova-restore-capture-invalid/plan.md)

Evidence harness (committed): `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` + `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureInterruptedReloadTest` / `NovaRestoreCaptureUnknownProviderTest`。raw logcatはcommitしない（本書の引用はharness matrix行とshipped diagnostics行のみで、いずれも件数・分類・例外class identityのみを含む）。

## I-2 additions（2026-09-13）: issue-representative failure捕捉とmatrix拡張

### #298相当failure pathでの同一invariant category観測（CI-AC-01 close条件a の履行）

一時的な調査計測（debug build限定・未commit・実施後にrevert済み）で、loaderの
widget修復点（`WorkspaceItemProcessor.processWidget` 冒頭）がcrashした場合の
挙動を観測した。crashはcache-dir marker fileでtoggleし、
`IllegalStateException("I299_SIMULATED_WRONG_THREAD at processWidget")` を投げる。
logcatには **`Desktop items loading interrupted`**（WorkspaceItemProcessorの
既存catch行）として記録された — これは #298/#287 セッションで観測された
loader中断signatureと同一の行である。

観測結果（SimulatedWrongThread scenario、1 process、run @ 2026-09-13T21:48）:

| 時点 | widget行 appWidgetId | restored flag | capture |
|---|---|---|---|
| 定点1（performRestore commit直後、I299Probe） | **-1** | 7 | —（capture不可時点） |
| 定点2（reloadAfterRestore直前、I299Probe） | -1（`modelLoaded=false` を同時記録） | 7 | — |
| 修復点crashのreload generation群の後（cycle1） | **-1（恒常残存）** | 7 | **Invalid（IllegalArgumentException）** |
| 中断を除去し完了generationを走らせた後（cycle2） | **有効値** | 4 | **Ready** |

ここから次が確定する:

- **同一invariant categoryの観測（CI-AC-01 close条件a）**: #298相当の
  reload中断（修復点でのloader crash → `Desktop items loading interrupted`）が
  unbound widget行（`appWidgetId=-1`）を残し、captureは #299 の観測と同一の
  `IllegalArgumentException`（codec widget不変条件、candidate pathの
  `RowManifestCodec` require）でfail-closedする。**capture側から見た
  issue-representative failureを、#298相当failure pathで捕捉した。**
- **持続（恒常化）の再現**: 修復generationがcrashし続ける限り、
  unbound rowは持続し、captureは繰り返し `CAPTURE_INVALID` になる。
  中断が止まれば次の完了generationが修復する（回復条件の確定）。
  元実機セッションの「process再起動・再restoreでも不回復」に対応する状態は、
  「修復を含むreload generationが何度試行しても完了しない」ことで説明できる。
- **定点1/2の取得**: `performRestore` commit直後（定点1）に既に
  `widgetIdNegative=1 / restored=7` であり、正規化は行われていないことが確定。
  `reloadAfterRestore` 直前（定点2）でも `modelLoaded=false`（reload未完了状態からの
  forceReload dispatch）を記録。定点4相当（中断generation後）はcycle1の観測が該当する。
- この計測はplan I-2の「出荷しない調査計測」契約どおり、PRから取り除いた
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

### 複数profile / grid変換窓

未実施。provider両経路の修復挙動が確定したため、複数profileとgrid変換窓の
優先度は相対的に下がった（candidate pathは既に両経路で同一と確認済み）。
必要になった場合（I-3で成功/失敗差分が説明できない場合）にI-2追補として実施する。

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

## この調査で確定しないこと（I-2完了後 / I-3以降または未確認）

- #298の **actual** wrong-thread障害（`BaseIconCache.assertWorkerThread` 経由の
  `Cache accessed on wrong thread`、`Can't create handler inside Thread[NovaBackupRestore]`）
  そのものの再現。本調査は修復点crashを #298相当の **中断** として使用し、
  中断signature（`Desktop items loading interrupted`）と後続状態の因果は捕捉したが、
  wrong-thread呼び出し連鎖そのものの特定と修正は #298 のscopeである。
- 複数profile、grid変換（fixture grid ≠ 元grid）窓でのcapture読み取り
  （I-2追補候補。上記「複数profile / grid変換窓」節参照）。
- 影響を受けた実機セッションの実backup内容（privacy上取得不能。本調査はsynthetic等価で再現）。
  元セッションが本調査の持続メカニズム（修復generationの持続的完了不能）と
  同一だったかの最終確認は、#298側のactual path特定後に可能になる。

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
