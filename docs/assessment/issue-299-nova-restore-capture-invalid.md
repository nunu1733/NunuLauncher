# Assessment: Issue #299 — Nova restore後のOrganizer capture `CAPTURE_INVALID`

Status: `investigation I-1 complete`（completion barrier確定とthrow点特定。fix architectureはI-5 decision gate待ち）

Date: 2026-09-13
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/299
Spec: [specs/299-nova-restore-capture-invalid/spec.md](../../specs/299-nova-restore-capture-invalid/spec.md)（accepted）
Plan: [specs/299-nova-restore-capture-invalid/plan.md](../../specs/299-nova-restore-capture-invalid/plan.md)

Evidence harness (committed): `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` + `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureInterruptedReloadTest`。raw logcatはcommitしない（本書の引用はharness matrix行とshipped diagnostics行のみで、いずれも件数・分類・例外class identityのみを含む）。

## Verdict（I-1/I-2成果）

capture側 `IllegalArgumentException` の出所と、それが持続する条件を特定した。

1. **Throw点（CI-AC-01）**: capture pathは `RowManifestCodec` のwidget不変条件で失敗する。
   `RowManifestCodec.kt:296-297` — `requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }`
   （provider欠落の `requireNotNull` も同箇所）。Nova restore由来のwidget行は
   `appWidgetId=-1` で保存され、`toPersistentRow`（:240）が負値をnull化するため
   このrequireが落ちる。例外は `LayoutWriterCanonicalCaptureSource`（OrganizationInputComposer.kt:94-110）
   がcatchして `CanonicalCaptureReadResult.Invalid`、composerはterminal
   `CAPTURE_INVALID` を返す。shipped diagnostics（debug build）は
   `OrganizerDiag: phase=CAPTURE exceptionClass=IllegalArgumentException` を出す。
2. **なぜwidget行が-1のまま残るか**: `NovaBackupConverter.insertNovaItems`（:463）は
   `appWidgetId=-1`、`restored=0` を固定で書く。`RestoreDbTask.sanitizeDB`（:359-365）は
   widget行にrestore flag（観測値7 = ID_NOT_VALID|PROVIDER_NOT_READY|UI_NOT_READY）を
   付けるだけでidを再bindしない。`restoreAppWidgetIdsIfExists`（:516-532）は
   `APP_WIDGET_IDS` prefsが存在する場合のみ動くplatform restore用経路で、
   Nova pathでは実行されない（logcat: "Did not receive new app widget id map during Launcher restore"）。
3. **修復機構（I-4経路cの実在確認）**: reload generationの内側で
   `WorkspaceItemProcessor.processWidget`（WorkspaceItemProcessor.kt:533-538）が
   `WidgetInflater` によるbind成功後に `APPWIDGET_ID` / `APPWIDGET_PROVIDER` / `RESTORED`
   をDBへ書き戻す（観測: flag 7→4、`appWidgetId` -1→有効値）。providerが
   未インストールでrestore未開始の場合は `markDeleted`（:497-505）で行を削除する。
   つまり**修復（bindまたは削除）はすべてreload generation依存**である。
4. **`CAPTURE_INVALID` 窓**: restore完了（`convertAndRestore` return）から
   修復を含むreload generationの完了までの間、権威的captureは必ずfail-closedする。
   これは正しいfail-closedであり、この窓自体が障害ではない。
5. **持続化の条件（観測された不回復の説明）**: 後続の完了したreload generationが
   修復を実行することを実験で確認した（下表）。したがって観測セッションのような
   持続的 `CAPTURE_INVALID` は、復元dataの内在的無効性ではなく、
   **修復を含むreload generationが持続的に完了しない（中断され続ける）状態**でのみ成立する。
   これは #298（reload中断・wrong-thread障害）と機構レベルで整合する。
   両Issueのmergeは引き続き証拠待ちだが、#299の調査matrix上の因果候補が
   「capture読み取り窓のレース」から「reload修復の中断」へ具体化した。
6. **#185非関与**: 全runでreservation検証・`CAPTURE_RESERVED_OVERLAP` は発生せず、
   QSB予約不変条件への回帰・変種の兆候はない（CI-AC-04の正式確認はI-5で実施）。

## Environment

| | |
|---|---|
| Build | branch `issue-299-spec-plan` @ `c502b22189`（app code）、`assembleLawnWithQuickstepGithubDebug` + androidTest |
| Device | AVD `nunu_qpr2_api36_1`（Pixel 6 class, Google APIs, Android 16 / API 36.1, arm64） |
| Fixture | synthetic等価Nova backup（`nova.xml` + `nova.db`。test app自身のcomponentと合成identityのみ。grid=対象AVDの元grid 4列×5行、hotseat 4、widget/provider/deep-shortcut/folder/apps/hotseat要素） |
| Execution | 1 scenario class per `am instrument`（fresh process）。3 scenario classesすべてPASS |

## State matrix（bounded分類 — plan I-1の5定点のうち取得できた定点）

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

## Completion barrier（plan I-1必須成果）

- **production signalは現存しない（構造的gapの確認）**。`RestoreDbTask.reloadAfterRestore`
  → `LauncherModel.forceReload()`（RestoreDbTask.java:283-288, LauncherModel.java:314-327）
  はcallbackを持たず、`convertAndRestore` return時に `isModelLoaded=false` を実測した
  （restore API returnはcompletionではない — plan/specの定義どおり）。
- **test側barrier**: restore呼び出し前に登録した `BgDataModel.Callbacks.finishBindingItems`
  latch + `isModelLoaded` poll（既存 `ManualOrganizationProductionE2EInstrumentationTest`
  の `reloadAndWait` pattern）が1 restore/process構成で安定して機能する。
  latchは **generation-agnostic** であり（中断されたgenerationが先にlatchを数えた例を観測）、
  「どのgenerationが完了したか」のidentityは運ばない。cancellation/interruptionは
  latchを発火させない（quiesce後もlatch=0を実測）— successful completionとの
  区別要件を満たす。
- **1 process内の複数restoreはgenerationを重ねさせる**: restore自身のforceReload、
  `applyConvertedGrid`（Main hop）由来のlistener reload、quiesceによるcancelが重なり、
  generation-agnosticなbarrierでは判定できない。harnessは1 scenario/processで実行する。
- **I-5 seam候補（この時点での評価）**: restore pathのreloadを、
  organizer用に存在するcompletion観測付きreload（`forceReloadForOrganizer` +
  `OrganizerReloadRequest` token — completed/cancelledを区別し #150 境界でsnapshotを
  取る、LauncherModel.java:499-562）と同型のgeneration identity付きbarrierへ
  置くことが、production signal不在の解消候補である。seam選択はI-5 decision gateで行う。

## Reproduction sequence（harness）

1. fixture zipをcache dirに生成（synthetic等価、privacy-equivalent）。
2. `NovaBackupConverter.parseInfo()` → `convertAndRestore(info)` をproduction pathのまま実行
   （BACKUP_RESTORE lease、staging DB、`applyConvertedGrid`、restored.db copy、
   `performRestore`、`reloadAfterRestore` をすべて通る）。
3. 直後にproduction capture sourceでcapture（pre-barrier観測）。
4. `finishBindingItems` latch + `isModelLoaded` でcompletion barrierを観測。
5. bounded state matrix（件数・分類のみ）をlogcatへ記録。
6. interrupted variantは `quiesceForRestore()` でgenerationを中断してから同様に観測。

## この調査で確定しないこと（I-2以降 / 未確認範囲）

- #298のwrong-thread障害そのものの再現（本調査は `quiesceForRestore` を中断のproxyとして使用）。
  中断が修復世代を横断的に壊し続ける経路（持続条件の本体）は #298 側の再現が必要。
- 複数profile、provider未インストールwidgetの削除修復が中断された場合の挙動、
  grid変換（fixture grid ≠ 元grid）窓でのcapture読み取り。いずれもI-2のmatrix拡張対象。
- 1 process内で重なったreload generationの全挙動（generation-agnostic barrierで
  判定不能な範囲）。generation identity付きbarrier（I-5 seam候補）で再計測する。
- 影響を受けた実機セッションの実backup内容（privacy上取得不能。本調査はsynthetic等価で再現）。

## Findings

- #172契約のshipped diagnostics行（`phase=CAPTURE exceptionClass=IllegalArgumentException`）が
  harness上で再現され、例外class identityが本障害のthrow点と一致することを確認した。
  CI-AC-08のbounded category（widget不変条件の区別）への拡張はI-5後の実装で行う。
- spec CI-AC-02のoracle（completion barrier後の最初の権威的captureが成功）は、
  本harnessのcontrol/widget両scenarioで「barrier後Ready」として検証可能な形になった。
  修正実装PRでは、このharnessをCI-AC-05のautomated regression（繰り返しrestore cycle +
  中断窓）へ発展させる。
- 中間障害は無いが、`appWidgetId=-1` のwidget行を含むrestoreは
  「restore直後〜barrier前」のOrganizer要求に対して必ず `CAPTURE_INVALID` を返す。
  これはfail-closed契約どおりであり、UI側でrestore直後のOrganizer要求に
  NotReady系の見せ方をするかどうかは本Issueのscope外（必要なら別Issue）。
