# High-risk audit: PR #314 Nova restore後のreload settle待ちとbounded capture不変条件diagnostics

> Status: accepted
> Audit date: 2026-09-13

- Auditor: 独立session（PR #314の実装を行っていないsessionによるaudit。solo保守のため、同一保守の別sessionとして実装経路に依存しない再実行・再確認を実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/314
- Head SHA: 0eb3355e0ef9937b9a4b99889303741aa86a4ce2
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34768482481
- Criteria: specs/299-nova-restore-capture-invalid/spec.md（status: accepted）CI-AC-01, CI-AC-02, CI-AC-03, CI-AC-04, CI-AC-05, CI-AC-06, CI-AC-07, CI-AC-08

## Scope

対象はPR #314（base `main`、head branch `issue-299-spec-plan`、head `0eb3355e0ef9937b9a4b99889303741aa86a4ce2`）。`git merge-base origin/main` は `c5274b5d0d`（#313 merge後の現行main）であり、GitHub APIの取得したPR diffは18ファイル、+2454/−25。そのうちaudited head commit `0eb3355e0e` 自体は16ファイル、+428/−224で、残りはbranch上で先行commitされたspec（`specs/299-nova-restore-capture-invalid/spec.md`、`plan.md`）と調査assessment（`docs/assessment/issue-299-nova-restore-capture-invalid.md`）の追加である。task packetに記載のbase `37e3dd8feb` は現行merge-baseではなく（mainが#313で進行）、記録値「16ファイル +428/−224」はhead commit単体のdiffと一致する。本auditはGitHub APIとlocal gitで確認した値を正本とする。

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
- **CI-AC-02（barrier後の最初の権威的capture成功・恒常化しない）: 満たす（実装 + 記録された検証）**。実装は `convertAndRestore` がdispatchしたreload generationのsettle（`finishBindingItems` + `isModelLoaded`）をlease解放後・上限15秒で待ってからreturnするもの。検証は `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureUnknownProviderTest`（barrier-settled return後のcapture Readyをassert）が実施者local（AVD `nunu_qpr2_api36_1`）とCI lane `organizer-instrumentation-issue299-tests` の両方でPASS。assessment記録のre-restore実験（窓の再open → settle後の再修復）も安定性を補強する。barrier signalはgeneration identityを持たないsettle heuristicであることがassessment「Completion barrier」節に記録済みであり、restore API return / 固定待ち時間をcompletion扱いしないspecの定義と整合する。
- **CI-AC-03（fail-closedの維持）: 満たす**。readiness checkの弱化・`CAPTURE_INVALID` の握り潰しは無い。codecのthrow点は同一不変条件のtyped化であり、`LayoutWriterCanonicalCaptureSource` は引き続き `CanonicalCaptureReadResult.Invalid` を返す。`NovaRestoreCaptureUnknownProviderTest` はsettle点前のcapture Invalid（fail-closed）とsettle後のReady（削除修復）を両方assert。JVM unit testは私の再実行で99 suite / 1094 test、failure 0（下記）、CI `organizer-unit-tests` もsuccess。
- **CI-AC-04（#185 reserved-QSB保護の非回帰）: 満たす**。既存coverage（`OrganizationInputComposerTest` の#185 case、`organizer-instrumentation-shared-writer-tests` の `LoaderCursorOverlapAcceptanceContractTest` 等）がCI run 34768482481ですべてsuccess。assessmentも全runでreservation違反・`CAPTURE_RESERVED_OVERLAP` 未発生を記録。
- **CI-AC-05（自動regressionの追加）: 満たす**。committed instrumentation family + 新CI lane `organizer-instrumentation-issue299-tests` が `final-status` のmerge gateへ接続され、run 34768482481で実際に実行・成功している。deterministicなtest seamが作れたケースに該当し、代替device evidenceへの依存は不要。codec/loggerのtyped化はJVM unit test（`DiagnosticsLoggerTest` / `OrganizationInputComposerTest` 拡張）でも覆盖される。
- **CI-AC-06（emulatorでの繰り返しrestore → capture検証）: 満たす（記録された範囲で）**。assessmentのmatrixが同一環境での復帰・安定性（settle後Ready、中断注入→解除→回復、cross-processのprocess death跨ぎ持続 → 新processのsettleで修復、re-restoreで窓が再openしsettle後に再修復）を記録し、CI laneが同harnessを毎回実行する。ただし元障害セッションの「同一手順で揺れる」intermittent trigger自体の再現は未達で、assessmentがI-5 gate入力として明示している（後述）。
- **CI-AC-07（復旧挙動の確定とdecision gate記録）: 満たす**。assessment I-5 decision gate（2026-09-14記録）が「正規化/拒絶を採用しない」ことを記録し、根拠（settle到達reloadの後はwidget行がbind（有効id）またはdeleteで修復されることがI-2/I-4で決定的観測済み＝barrier後もinvalidが残る状態は不在）と、採用却下の理由（restore threadからのwidget bindという#298 hazard、未install providerでの行喪失）を示す。specの「不採用は別fixでCI-AC-02を満たせる場合のみ可」の条件を、その別fix（settle barrier）の観測証跡とともに満たす。採用した振る舞い（barrier）はCI-AC-02の検証で被っている。
- **CI-AC-08（bounded category diagnostics・選択非依存の必須成果）: 満たす**。`CaptureInvariantCategory.INVALID_WIDGET_ROW`（閉じたenum）→ `phase=CAPTURE exceptionClass=IllegalArgumentException invariant=INVALID_WIDGET_ROW`（debug build・capture失敗・typed違反時のみ）。class identityはtyped違反でも `IllegalArgumentException` に正規化され、#172契約のidentity互換を維持。message / stack trace / layout由来textは型として渡らない（自由文字列Parameter不導入）。journal語彙は不変。`docs/engineering/organizer-diagnostics.md` の§7 redaction表・§10・change historyが同期されている。unit testでline formatとnon-containmentを検証（CI `organizer-unit-tests` success、私の再実行でもgreen）。

記録されたboundary（assessment I-5 gate入力として明示、本auditもそのまま承継する）: (1) 元実機セッションのthrow-site identityは未証明（shipped diagnosticsが例外classのみのため。確定はsynthetic issue-representative failure上）); (2) 元のintermittent triggerは未確定（controlled interruption matrixでI-3を履行しdeviation記録済み）; (3) #298のactual wrong-thread pathは未再現（#298 scope、本PRは同seamに触れない）。

## Executed test surface

audit本人による独立再実行（本checkout = head `0eb3355e0ef9937b9a4b99889303741aa86a4ce2`、dirty fileなし・対象は既追跡分のみ）:

```text
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
→ BUILD SUCCESSFUL in 27s（exit 0）。
  build/test-results/testLawnWithQuickstepGithubDebugUnitTest のXML集計:
  app.lawnchair.organizer.* 99 suite / 1094 tests / failures 0 / errors 0 / skipped 0
```

```text
python3 tools/repo-contract/test_validate_high_risk_evidence.py
→ Ran 51 tests in 0.015s / OK（exit 0）
```

```text
python3 tools/repo-contract/validate_high_risk_evidence.py \
  --repo nunu1733/NunuLauncher --pr-number 314 \
  --head-sha 0eb3355e0ef9937b9a4b99889303741aa86a4ce2
→ PASS: audit docs/assessment/pr-314-nova-restore-capture.md covers
  0eb3355e0ef9937b9a4b99889303741aa86a4ce2 with independent CI evidence
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
```

CI merge gate（GitHub APIで直接確認。audit本人が `gh api` / `gh run view` で検証）:

- run 34768482481は `event=pull_request`、`pull_requests=[314]`、`head_branch=issue-299-spec-plan`、`head_sha=0eb3355e0ef9937b9a4b99889303741aa86a4ce2`、`path=.github/workflows/ci.yml`、`status=completed`、`conclusion=success`。
- job別conclusion（per_page=100で取得）: `final-status: success` を含む全jobがsuccess。source job `organizer-unit-tests` / `check-style` / `build-debug-apk` は実行済みsuccess、新lane `organizer-instrumentation-issue299-tests` も実行済みsuccess。

## Findings

- **settle待ちtimeout pathは設計どおりfail-openである**: 15秒以内にreloadがsettleしない場合、restoreは警告logとともにreturnし、その窓のOrganizer要求は従来どおりfail-closed（`INPUT_NOT_READY` / `CAPTURE_INVALID`）を続ける。specが許容する一時的な非Readyであり欠陥ではないが、restore return後も恒常化が完全に排除されるわけではなく「次に完了するreload」までの残存窓はユーザーから観測しうる。settle signalはgeneration identityを持たないheuristicである点（assessment「Completion barrier」節の記録どおり）も、正式generation-correlated oracleを将来整備する場合の残課題である。
- **CI-AC-01/CI-AC-06の証拠boundaryは記録済みのまま**: 元実機セッションのthrow-site identityは未証明、元のintermittent triggerは未確定、#298 actual wrong-thread pathは未再現。いずれもassessmentがI-5 gate入力として明示しており、本auditはspec要件がその記述と整合して満たされていると判定するが、元障害の完全な因果確定は这两点の将来追跡に依存する。後続追跡は#298（scope分離維持）とassessment記録の再開条件に従う。
- **#298のactual pathは本PRでは意図的に扱わない**: specのNon-goalどおり、#298と同一seam（restore/reload窓）への追加介入は避けられている。settle barrierが#298の症状を部分的に緩和しうるかに見えても、その判断は#298側の証拠で行うべきであり、本auditは緩和主張を認めない。
- **cross-process検証surfaceの手動性**: Gradle connected testはtest APK再install時にapp dataを消去するため、cross-process pairはCI lane内でもmanual `adb install -r` + `am instrument` + `force-stop` の手順に依存する（lane commentとassessmentに文書化済み）。動作はCIで毎回検証されるが、emulator runner側の環境変化に対する脆弱性は残る。
- **task packetのdiff記述と実測の差異**: packet記載のbase `37e3dd8feb`（16ファイル +428/−224）は現行merge-baseではなくhead commit単体のdiffに一致し、実際のPR diff（vs `c5274b5d0d`）は18ファイル +2454/−25である。本auditの判定は実測値に基づく。head以降にcode変更が入った場合は本auditを無効とし再auditを要する（docs-only commitのみ監査有効性を維持）。
