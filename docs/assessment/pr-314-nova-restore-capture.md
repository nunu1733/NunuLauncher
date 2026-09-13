# High-risk audit: PR #314 Nova restore後のreload settle待ちとbounded capture不変条件diagnostics

> Status: accepted
> Audit date: 2026-09-13

- Auditor: 独立session（PR #314の実装を行っていないsessionによるaudit。solo保守のため、同一保守の別sessionとして実装経路に依存しない再実行・再確認を実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/314
- Head SHA: b669194b5552d7ac2ab2d8c92592125e98c46412
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34771951461
- Criteria: specs/299-nova-restore-capture-invalid/spec.md（status: implemented）CI-AC-01, CI-AC-02, CI-AC-03, CI-AC-04, CI-AC-05, CI-AC-06, CI-AC-07, CI-AC-08
- Re-audit (1): 初回audit（head `0eb3355e0e`、CI run 34768482481）後、docs commit `483038d94f`（audit記録追加とspec statusの `implemented` への更新）が加わったため再監査。差分 `git diff 0eb3355e0e..483038d94fd2d378692e54dd5bf2d80adf11d635` は `docs/assessment/pr-314-nova-restore-capture.md`（追加）と `specs/299-nova-restore-capture-invalid/spec.md`（status行とChange history 1行のみ）の2ファイルで、production code・test・CI workflowへの変更は無い。受入条件（CI-AC-01..08）の定義内容は不変であり、初回auditの判定をそのまま承継する。
- Re-audit (2): commit `b669194b55`（「fix(299): harden capture-failure diagnostics and settle-wait lifecycle per review」、5ファイル +56/−19。監査対象code stateは `483038d94f..b669194b55` の差分6ファイル＝本audit記録の更新commit `f0c535ba00` を含む）が加わったため再監査。audit本人がdiffを直接reviewした結果:
  - **capture-failure diagnosticsのenum型化（CI-AC-08の強化、自由text穴の閉塞）**: `DiagnosticsLogger.logCaptureFailure` / `formatCaptureFailure` のinvariant引数が `String?` から `CaptureInvariantCategory?` へ変更され、定数名のrendering（`invariant.name`）はlogger内部に移動した。wiring（`LayoutApplicationModule`）とharness（`NovaRestoreCaptureTestBase`）、unit test（`DiagnosticsLoggerTest`）もenum直接渡しへ更新。これでinvariant fieldに入りうる値は閉じたenum定数名のみと型で強制され、初回auditで「自由文字列Parameterは不導入」と記述した境界が文字列型経由の迂回路を含めて真になる。出力形式（`phase=CAPTURE exceptionClass=IllegalArgumentException invariant=INVALID_WIDGET_ROW`）・redaction境界・journal語彙は不変。
  - **settle待ちlifecycleの強化（CI-AC-02の実装品質修正、意味論不変）**: `RestoreReloadSettleWait` にidempotentな `cleanup()`（`cleanedUp` / `stopped` flag、main handler上で `removeCallbacks(pollRunnable)` とobserverの `removeCallbacks` を1回だけ実行）を導入し、`convertAndRestore` 側は `awaitSettled()` をtry/finallyで覆い、`awaitSettled` 自体も `InterruptedException` を捕捉（interrupt flag復元・WARN log・次の完了reloadへ委譲）してfinallyでcleanupする。pollは匿名lambdaから名前付き `pollRunnable` へ変更され、cancelが実際に効く。これでsettle待ちの中断時・restore失敗時にobserver登録とpoll再scheduleが漏出するlifecycle leakが閉じた。timeout時fail-open・次の完了reloadへ委譲する意味論は不変であり、DB書込み・migration経路の追加は無い（handler callbackとmodel observerの登録解除のみ）。
  - 差分にlayout-write / migration pathの変更は無い（`NovaBackupConverter.kt` の変更は上記lifecycle管理のみでrestore系DB書込みは不変、`DiagnosticsLogger` / `LayoutApplicationModule` はdiagnostics read pathのみ、残り2ファイルはtest）。受入条件（CI-AC-01..08）の定義内容は不変であり、初回auditの判定を承継した上で、上記2点を検証済みの強化として記録する。

## Scope

対象はPR #314（base `main`、head branch `issue-299-spec-plan`、audited head `b669194b5552d7ac2ab2d8c92592125e98c46412`）。code本体は `0eb3355e0e` で確定し、以後の差分は監査記録・spec statusの更新（`0eb3355e..483038d94f`、Re-audit (1)参照）と、review対応の2点の強化（`483038d94f..b669194b55`、Re-audit (2)参照）のみである。後者の差分6ファイルのうちcode/testは `NovaBackupConverter.kt`（settle待ちlifecycleのみ・DB書込み不変）、`DiagnosticsLogger.kt` / `LayoutApplicationModule.kt`（diagnostics引数のenum型化のみ・read path）、`NovaRestoreCaptureTestBase.kt` / `DiagnosticsLoggerTest.kt`（test側の追従）であり、layout-write / migration pathへの変更は無い。`git merge-base origin/main` は `c5274b5d0d`（#313 merge後の現行main）であり、GitHub APIの取得したPR diff（同merge-base基準）は19ファイル、+2597/−25である（初回audit時の18ファイル +2454/−25 に、本audit記録ファイルの追加とreview対応による既存5ファイルの修正が加わった値）。code確定commit `0eb3355e0e` 自体は16ファイル、+428/−224で、残りはbranch上で先行commitされたspec（`specs/299-nova-restore-capture-invalid/spec.md`、`plan.md`）と調査assessment（`docs/assessment/issue-299-nova-restore-capture-invalid.md`）の追加である。task packetに記載のbase `37e3dd8feb` は現行merge-baseではなく（mainが#313で進行）、記録値「16ファイル +428/−224」はcode確定commit単体のdiffと一致する。本auditはGitHub APIとlocal gitで確認した値を正本とする。

確認したdiff領域:

- `.github/workflows/ci.yml` — 新CI lane `organizer-instrumentation-issue299-tests`（scenario classごとの `connectedLawnWithQuickstepGithubDebugAndroidTest` 3実行 + manual `adb install -r` と `am instrument` によるcross-process pair（Stage A → force-stop → Stage B））。`final-status` のneedsへ追加。
- `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` — `convertAndRestore` のsettle barrier（`RestoreReloadSettleWait`）。`reloadAfterRestore` のdispatch前に `BgDataModel.Callbacks` をregisterし、restore-family leaseの解放後（`use` blockの外）で `finishBindingItems` 発火 + `LauncherModel.isModelLoaded` を上限15秒（`DEFAULT_SETTLE_TIMEOUT_MS = 15_000L`）で待ってからreturnする。timeout時はfail-open（WARN logを出しreturn）。
- `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt` — widget不変条件の2箇所の `requireNotNull`（provider欠落 / appWidgetId欠落）を、`CaptureInvariantViolationException(CaptureInvariantCategory.INVALID_WIDGET_ROW, ...)` のthrowへ変更。captureのread pathのみ。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/CaptureInvariant.kt` — 新規。閉じた `CaptureInvariantCategory` enum（初出 `INVALID_WIDGET_ROW`）と、`IllegalArgumentException` を継承する `CaptureInvariantViolationException`。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` — `CaptureFailureObserver` 経由でcategoryを `DiagnosticsLogger` へ渡すwiring。
- `lawnchair/src/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLogger.kt` — `logCaptureFailure` / `formatCaptureFailure` に閉じた定数名の `invariant=` fieldを追加（自由文字列Parameterは不導入）。
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` — `CaptureFailureObserver` のsignature変更と、`LayoutWriterCanonicalCaptureSource` におけるtyped違反のclass identityを `IllegalArgumentException` へ正規化して返す処理。
- instrumentation harness — `tests/organizer-instrumentation/app/lawnchair/backup/` に `NovaRestoreCaptureTestBase` / `ControlTest` / `WidgetWindowTest` / `UnknownProviderTest` / `CrossProcessTest` を追加。head commitで `NovaRestoreCaptureInterruptedReloadTest.kt` を削除（中断注入scenarioはplan I-2契約どおり出荷しない）。JVM unit test `DiagnosticsLoggerTest` / `OrganizationInputComposerTest` を拡張。
- docs — `docs/engineering/organizer-diagnostics.md`（§7のredaction表へ `invariant=` fieldの行追加、§10の記述更新、change history）。上記のspec/plan/assessment追加。

runtime書き込み経路・migration対象の確認: PR labelは `risk: layout-data` であり、diffはhigh-risk path（`lawnchair/src/app/lawnchair/backup/`、`lawnchair/src/app/lawnchair/organizer/application/`）を含む。ただし本diffによる新しいlayout write・migrationは存在しない。settle待ちはcallbackのregister / `isModelLoaded` のpoll / callbackのremoveのみでDB writeを追加しない。restore自体の書込み（`performRestore`、`applyConvertedGrid` 等）は既存のBACKUP_RESTORE leaseとtransactionの内側から不変である。codec・`CaptureInvariant`・observer経路の変更はcapture（読み取り専用契約の維持）とdebug diagnostics出力のみに作用し、journal語彙（`CAPTURE_INVALID` 含む）・schema・`favorites` 書式・recovery stateは不変である。

## Criteria check

`specs/299-nova-restore-capture-invalid/spec.md`（accepted）の受入条件ごとの確認結果。調査証跡の正本は `docs/assessment/issue-299-nova-restore-capture-invalid.md`（以下「assessment」と記す）。

- **CI-AC-01（throw点の特定と記録）: 満たす（記録された限界付き）**。assessment I-2 additionsが、一時debug計測（commitせずrevert済み）によるthrow点の直接捕捉 `top=app.lawnchair.organizer.application.adapter.RowManifestCodec.toCanonical:297`（`requireNotNull(row.appWidgetId)`）と、同時観測（widget行 `appWidgetId=-1`、capture fail-closed）を記録。boundaryとして「synthetic issue-representative failure上での確定であり、元実機セッションとのthrow-site identityは未証明」が明記されている（下限Findings参照）。
- **CI-AC-02（barrier後の最初の権威的capture成功・恒常化しない）: 満たす（実装 + 記録された検証）**。実装は `convertAndRestore` がdispatchしたreload generationのsettle（`finishBindingItems` + `isModelLoaded`）をlease解放後・上限15秒で待ってからreturnするもの。検証は `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureUnknownProviderTest`（barrier-settled return後のcapture Readyをassert）が実施者local（AVD `nunu_qpr2_api36_1`）とCI lane `organizer-instrumentation-issue299-tests` の両方でPASS。assessment記録のre-restore実験（窓の再open → settle後の再修復）も安定性を補強する。barrier signalはgeneration identityを持たないsettle heuristicであることがassessment「Completion barrier」節に記録済みであり、restore API return / 固定待ち時間をcompletion扱いしないspecの定義と整合する。Re-audit (2)のlifecycle強化（idempotent `cleanup()`・`InterruptedException` 処理）はfail-openの意味論とrestore系DB書込み経路を不変にしたままobserver登録・poll再scheduleの漏出を閉じたものであり、本判定に影響しない。
- **CI-AC-03（fail-closedの維持）: 満たす**。readiness checkの弱化・`CAPTURE_INVALID` の握り潰しは無い。codecのthrow点は同一不変条件のtyped化であり、`LayoutWriterCanonicalCaptureSource` は引き続き `CanonicalCaptureReadResult.Invalid` を返す。`NovaRestoreCaptureUnknownProviderTest` はsettle点前のcapture Invalid（fail-closed）とsettle後のReady（削除修復）を両方assert。JVM unit testは私の再実行で99 suite / 1094 test、failure 0（下記）、CI `organizer-unit-tests` もsuccess。
- **CI-AC-04（#185 reserved-QSB保護の非回帰）: 満たす**。既存coverage（`OrganizationInputComposerTest` の#185 case、`organizer-instrumentation-shared-writer-tests` の `LoaderCursorOverlapAcceptanceContractTest` 等）がCI merge gate run 34771951461ですべてsuccess。assessmentも全runでreservation違反・`CAPTURE_RESERVED_OVERLAP` 未発生を記録。
- **CI-AC-05（自動regressionの追加）: 満たす**。committed instrumentation family + 新CI lane `organizer-instrumentation-issue299-tests` が `final-status` のmerge gateへ接続され、run 34771951461（audited head上）で実際に実行・成功している。deterministicなtest seamが作れたケースに該当し、代替device evidenceへの依存は不要。codec/loggerのtyped化はJVM unit test（`DiagnosticsLoggerTest` / `OrganizationInputComposerTest` 拡張、review対応でenum型化に追従）でも覆盖される。
- **CI-AC-06（emulatorでの繰り返しrestore → capture検証）: 満たす（記録された範囲で）**。assessmentのmatrixが同一環境での復帰・安定性（settle後Ready、中断注入→解除→回復、cross-processのprocess death跨ぎ持続 → 新processのsettleで修復、re-restoreで窓が再openしsettle後に再修復）を記録し、CI laneが同harnessを毎回実行する。ただし元障害セッションの「同一手順で揺れる」intermittent trigger自体の再現は未達で、assessmentがI-5 gate入力として明示している（後述）。
- **CI-AC-07（復旧挙動の確定とdecision gate記録）: 満たす**。assessment I-5 decision gate（2026-09-14記録）が「正規化/拒絶を採用しない」ことを記録し、根拠（settle到達reloadの後はwidget行がbind（有効id）またはdeleteで修復されることがI-2/I-4で決定的観測済み＝barrier後もinvalidが残る状態は不在）と、採用却下の理由（restore threadからのwidget bindという#298 hazard、未install providerでの行喪失）を示す。specの「不採用は別fixでCI-AC-02を満たせる場合のみ可」の条件を、その別fix（settle barrier）の観測証跡とともに満たす。採用した振る舞い（barrier）はCI-AC-02の検証で被っている。
- **CI-AC-08（bounded category diagnostics・選択非依存の必須成果）: 満たす**。`CaptureInvariantCategory.INVALID_WIDGET_ROW`（閉じたenum）→ `phase=CAPTURE exceptionClass=IllegalArgumentException invariant=INVALID_WIDGET_ROW`（debug build・capture失敗・typed違反時のみ）。class identityはtyped違反でも `IllegalArgumentException` に正規化され、#172契約のidentity互換を維持。message / stack trace / layout由来textは型として渡らない（自由文字列Parameter不導入）。journal語彙は不変。`docs/engineering/organizer-diagnostics.md` の§7 redaction表・§10・change historyが同期されている。unit testでline formatとnon-containmentを検証（CI `organizer-unit-tests` success、私の再実行でもgreen）。Re-audit (2)でinvariant引数が `String?` から `CaptureInvariantCategory?` 型へ変更され、定数名以外が型として進入不可能になった（文字列経由の迂回路の閉塞。出力形式・redaction境界は不変、実施者によるemulator再検証ではinvariant category assertion付きでPASS）。

記録されたboundary（assessment I-5 gate入力として明示、本auditもそのまま承継する）: (1) 元実機セッションのthrow-site identityは未証明（shipped diagnosticsが例外classのみのため。確定はsynthetic issue-representative failure上）); (2) 元のintermittent triggerは未確定（controlled interruption matrixでI-3を履行しdeviation記録済み）; (3) #298のactual wrong-thread pathは未再現（#298 scope、本PRは同seamに触れない）。

## Executed test surface

audit本人による独立再実行（対象は既追跡分のみ・dirty fileなし）:

- 初回audit時（code state `0eb3355e0e`）とRe-audit (2)時（現audited head `b669194b55`、enum型化されたloggerとlifecycle強化を含むcode state）の2回を実施。後者が現headの `DiagnosticsLoggerTest`（enum引数）を含むorganizer unit test表面を再実行する。

```text
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
→ [0eb3355e0e上] BUILD SUCCESSFUL in 27s（exit 0）。
→ [b669194b55上] BUILD SUCCESSFUL in 31s（exit 0）。
  両runとも build/test-results/testLawnWithQuickstepGithubDebugUnitTest のXML集計:
  app.lawnchair.organizer.* 99 suite / 1094 tests / failures 0 / errors 0 / skipped 0
```

```text
python3 tools/repo-contract/test_validate_high_risk_evidence.py
→ Ran 51 tests in 0.016s / OK（exit 0。Re-audit (1)時の0.015s、初回時も同一51 testでOK）
```

```text
python3 tools/repo-contract/validate_high_risk_evidence.py \
  --repo nunu1733/NunuLauncher --pr-number 314 \
  --head-sha b669194b5552d7ac2ab2d8c92592125e98c46412
→ PASS: audit docs/assessment/pr-314-nova-restore-capture.md covers
  b669194b5552d7ac2ab2d8c92592125e98c46412 with independent CI evidence
  (specs/299-nova-restore-capture-invalid/spec.md)（exit 0）
```

実施者が記録した実行（PR本文・assessment。CI側で機械検証済みの項目を含む。実施者環境: AVD `nunu_qpr2_api36_1` / API 36.1）:

```text
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' → BUILD SUCCESSFUL
./gradlew spotlessCheck → PASS（CI job check-style と同一surface）
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureControlTest      → PASS
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureWidgetWindowTest → PASS
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureUnknownProviderTest → PASS
adb shell am instrument -w -e class app.lawnchair.backup.NovaRestoreCaptureCrossProcessStageATest ... → PASS
adb shell am force-stop app.lawnchair.debug
adb shell am instrument -w -e class app.lawnchair.backup.NovaRestoreCaptureCrossProcessStageBTest ... → PASS
python3 tools/repo-contract/validate_repo_contract.py → PASS
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...ControlTest / ...WidgetWindowTest
  （Re-audit (2)のreview対応後の再検証。invariant category assertionと "Restore reload settled" log確認を含む） → PASS
```

CI merge gate（GitHub APIで直接確認。audit本人が `gh api` / `gh run view` で検証）:

- audited head `b669194b5552d7ac2ab2d8c92592125e98c46412` のmerge gateはrun 34771951461: `event=pull_request`、`pull_requests=[314]`、`head_branch=issue-299-spec-plan`、`head_sha=b669194b5552d7ac2ab2d8c92592125e98c46412`、`path=.github/workflows/ci.yml`、`status=completed`、`conclusion=success`、`run_attempt=2`。
- 同runのjob別conclusion（per_page=100で取得）: `final-status: success` を含む全14 jobがsuccess。source job `organizer-unit-tests` / `check-style` / `build-debug-apk` は実行済みsuccess、新lane `organizer-instrumentation-issue299-tests` も実行済みsuccess。第1attemptの `organizer-instrumentation-issue52-tests` のみ環境起因の一度の失敗（system launcherのANRがwindow focusを遮蔽）がありrerunで成功した — issue52 laneは本diffが触れない表面であり、最終conclusionはsuccessである。
- 参照run（prose記録、`CI run:` 行には新runのみ記載）: code commit `0eb3355e0e` 上のmerge gate run 34768482481と、docsのみのcommit `483038d94f` 上のrun 34770025968も、同一条件（pull_request / PR #314関連付け / completed / success / 全job green）をaudit本人が確認済み。`0eb3355e..b669194b55` のcode差分はRe-audit (2)の2点の強化のみであり、各head上のlane実行がその都度緑であることを上記run列が示す。

## Findings

- **settle待ちtimeout pathは設計どおりfail-openである**: 15秒以内にreloadがsettleしない場合、restoreは警告logとともにreturnし、その窓のOrganizer要求は従来どおりfail-closed（`INPUT_NOT_READY` / `CAPTURE_INVALID`）を続ける。specが許容する一時的な非Readyであり欠陥ではないが、restore return後も恒常化が完全に排除されるわけではなく「次に完了するreload」までの残存窓はユーザーから観測しうる。settle signalはgeneration identityを持たないheuristicである点（assessment「Completion barrier」節の記録どおり）も、正式generation-correlated oracleを将来整備する場合の残課題である。
- **CI-AC-01/CI-AC-06の証拠boundaryは記録済みのまま**: 元実機セッションのthrow-site identityは未証明、元のintermittent triggerは未確定、#298 actual wrong-thread pathは未再現。いずれもassessmentがI-5 gate入力として明示しており、本auditはspec要件がその記述と整合して満たされていると判定するが、元障害の完全な因果確定は这两点の将来追跡に依存する。後続追跡は#298（scope分離維持）とassessment記録の再開条件に従う。
- **#298のactual pathは本PRでは意図的に扱わない**: specのNon-goalどおり、#298と同一seam（restore/reload窓）への追加介入は避けられている。settle barrierが#298の症状を部分的に緩和しうるかに見えても、その判断は#298側の証拠で行うべきであり、本auditは緩和主張を認めない。
- **cross-process検証surfaceの手動性**: Gradle connected testはtest APK再install時にapp dataを消去するため、cross-process pairはCI lane内でもmanual `adb install -r` + `am instrument` + `force-stop` の手順に依存する（lane commentとassessmentに文書化済み）。動作はCIで毎回検証されるが、emulator runner側の環境変化に対する脆弱性は残る。
- **task packetのdiff記述と実測の差異**: packet記載のbase `37e3dd8feb`（16ファイル +428/−224）は現行merge-baseではなくcode head commit単体のdiffに一致し、実際のPR diff（vs `c5274b5d0d`）は18ファイル +2454/−25である。本auditの判定は実測値に基づく。
- **再監査の成立条件（Re-audit (1)・(2)）**: 初回audit（head `0eb3355e0e`）以降、docs commit `483038d94f`（audit記録追加・spec status更新。差分はdocs/specのみ）とreview対応commit `b669194b55`（本audit「Re-audit (2)」の2点の強化。差分はdiagnostics / restore lifecycleとtest追従のみでlayout-write / migration pathの変更は無い）が加わった。各自の差分をaudit本人が直接reviewし、audited headを `b669194b55` へ更新し、当該head上のmerge gate run 34771951461をCI証拠とする。本audit記録自体の更新はdocs-only commitとしてheadに積まれる（gateが許容する経路）。head以降にさらにcode変更が入った場合は本auditを無効とし再auditを要する（docs-only commitのみ監査有効性を維持）。
