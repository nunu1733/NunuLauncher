# High-risk audit: PR #184 Organizer INPUT_NOT_READY terminal record with readiness code (Issue #172)

> Status: accepted
> Audit date: 2026-08-31

- Auditor: independent ZCode agent session (read-only re-verification + independent re-execution); implementation was performed by a different session
- PR: https://github.com/nunu1733/NunuLauncher/pull/184
- Head SHA: c0d8f7d52995da7648f046c64dc7281d8e660559
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33373505732
- Criteria: specs/172-input-unavailable-diagnostics/spec.md AC-1, AC-2, AC-4, AC-5, AC-6, AC-7 (NFR-011)
- Criteria: specs/83-production-organization-input-sources/spec.md AC-5, AC-7 (NFR-011)
- Criteria: docs/engineering/organizer-diagnostics.md §3, §4.1, §5, §7, §10, §13

## Scope

監査対象diffは `git diff origin/main...c0d8f7d529`（21 files, +886/−36）である。監査時点のcheckout HEADは `c0d8f7d52995da7648f046c64dc7281d8e660559` でPR #184のheadと一致し、working treeはcleanであった。監査は実装sessionとは別の独立sessionとして実行し、検証commandを監査session自身で再実行した。

変更内容: `PhaseCode.INPUT_NOT_READY`（terminal）と `ErrorFamily.INPUT_READINESS` の追加、closed enum `InputCompositionCode`（16定数）への `CompositionDiagnostic.code` の型変更、`ManualOrganizationRun.start()` の `NotReady` 分岐でのterminal record emit、`DiagnosticsLogger.logCaptureFailure(Class<out Throwable>)`（debug build限定・exception class単純名のみ）とtypedな `CaptureFailureObserver` の注入（`LayoutApplicationModule.production()` wiring）、`InputUnavailable` のcopy区分（en/ja）、`docs/engineering/organizer-diagnostics.md` の§3/§4.1/§5/§7/§10/§13更新、unit test 6件追加とinstrumentation test 2件追加。

runtime書き込み経路とmigration対象: diff中にLauncher DB/favoritesへの書込み、recovery store変更、schema/migration変更は存在しない（`git diff --name-only | grep -iE 'favorites|launcher3/provider|database|migration|schema|recovery'` → 不一致）。`LayoutApplicationModule.kt` の変更はobserver構築とcomposer DI wiringのみであり、write pathには触れない。diagnostics journalへの変更はappend-onlyのserialized enum定数追加のみで、schemaVersionは1のままである。

## Criteria check

**172 AC-1（理由コード付きterminal record）— 適合。** `ManualOrganizationRun.start()` は `NotReady` 受信時に `INPUT_NOT_READY` + `ErrorEntry(INPUT_READINESS, InputCompositionCode)` をemitしてから `State.InputUnavailable` へfinishする（`ManualOrganizationRun.kt:212-213, 635-649`）。code集合はcomposerとjournalで同一のclosed enumであり、`ErrorEntry.validCodesForFamily(INPUT_READINESS)` が検証する。test: `inputUnavailableRunEmitsTerminalInputNotReadyWithReadinessCode`、`reconciliationPendingRunEmitsTerminalInputNotReadyWithPendingCode`、`diagnosticsEmitFailureDoesNotBlockInputUnavailableTerminalState`（fail-open）、`errorEntryAllowsEveryInputCompositionCode`（全16値）、`errorEntryRejectsArbitraryStringForInputReadiness`。

**172 AC-2（capture例外の観測、message出力なし）— 適合。** 観測API（`CaptureFailureObserver.onCaptureFailure(Class<out Throwable>)`、`DiagnosticsLogger.logCaptureFailure(Class<out Throwable>)`）はString型パラメータを持たず、class名はlogger内部で `simpleName` 化される。message・layout由来textが型として渡る経路は存在しない。test: `captureFailureFormatContainsOnlyExceptionClassName`、`captureFailureFormatNormalizesToSimpleNameWithoutMessage`（"Row too big"/"CursorWindow"/"message" のnon-containment assert）、`releaseBuildSuppressesCaptureFailureLine`、`captureFailureObservesExceptionClassOnlyAndStaysFailClosed`。

**172 AC-4（copy区分）— 適合（connected laneはCIで実行）。** `ReconciliationPending` → 再試行系copy、その他 → 報告系copy（`ManualOrganizationPreferences.kt:111, 522-524`）。en/ja string確認済み。instrumentation test `reconciliationPendingShowsTryAgainLaterCopy` / `sourceUnavailableShowsBugReportCopyWithRetry` は1 test 1 `setContent` に分割済みでcompileが通り、CI `organizer-instrumentation-*` checksがこのSHAでSUCCESSである。

**172 AC-5（privacy不変）— 適合。** journal側はclosed enum定数名のみ（`ErrorEntry.kt:87`）。logcat側は `inputNotReadyCarriesOnlyReadinessFamilyAndCode`（package名/座標/revision/message/digest/kebab-codeのnon-containment）とcapture行のclass名のみ制約で検証される。既存negative fixture群も通過。

**172 AC-6（readiness意味論の不変）— 適合。** composerの制御フロー差分は `String` → enum定数の同一箇所置換のみで、fail-closed判定は不変（capture失敗は引き続き `CanonicalCaptureReadResult.Invalid`、`OrganizationInputComposer.kt:88`）。既存composer/organizer suiteが無変更で通過（監査sessionでの再実行で801 tests / 0 failures）。既存fixtureのenum型合わせとterminal event 1件分のevent数assert更新はspec意図どおりの意図的変更であり、semantics変更ではない。

**172 AC-7（journal versioning）— 適合。** `unknownSerializedEnumValueResetsJournalAndKeepsSequence`: 未知enum値を含むjournalは既存corruption isolationで全体reset、sequenceは保持され、次のappendは継続番号から行われる。upgrade方向は既存journal decode test群の通過で確認。契約§3にupgrade/downgrade規定を追加済み。

**83 AC-5（fail closed）— 適合。** capture失敗 → `Invalid` → `NotReady(CAPTURE_INVALID)` の経路は不変。observerがthrowしても結果は変わらない（`throwingObserverDoesNotChangeFailClosedResult`）。既存source failure matrix testは無変更で通過。

**83 AC-7（deterministic composition）— 適合。** composer差分はcode typingのみで、`unknownLockFailsClosedAndValueEquivalentInputsComposeEqually`、`dynamicCutMismatchRetriesOnceThenSucceedsOrReturnsBothTypedCuts` が無変更で通過。

diagnostics契約（docs/engineering/organizer-diagnostics.md）との整合: §4.1にterminal行、§5にfamily/code来源と16値一覧、§7にcapture側例外identityの限定例外行（class名のみ、journal/export不使用、message/stack traceはNeverのまま）、§10に「RunEvent射影である」一般則の明示的限定例外（pre-journal、debug build限定）、§13にfixture D-11が追加されている。実装はこれらの記述と一致する。

## Executed test surface

監査sessionで実際に実行したcommandと結果（JDK 21.0.12 / Gradle 9.3.0 / head `c0d8f7d529`）:

- `git rev-parse HEAD` → `c0d8f7d52995da7648f046c64dc7281d8e660559`（PR headと一致）。`git status --porcelain` → 空。`git branch --show-current` → `issue-172-spec-plan`。
- `./gradlew spotlessCheck --rerun-tasks` → BUILD SUCCESSFUL（強制全再実行）。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL。XML集計（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/*.xml`、68 suites）: **801 tests / 0 failures / 0 errors / 0 skipped**。
- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL。
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → BUILD SUCCESSFUL。

CI（https://github.com/nunu1733/NunuLauncher/actions/runs/33373505732 、このPRの `pull_request` 実行）: `final-status` を含む全job SUCCESS（build-debug-apk、check-style、organizer-unit-tests、organizer-instrumentation-{issue52,issue53,issue99,issue155,db-migration,api35,shared-writer}-tests、validate-repo-contract、changes）。

## Findings

- 監査時点で `high-risk-evidence` checkは本assessment文書の不在によりFAILUREであった。本記録はaudited head `c0d8f7d529` を対象とし、CI merge gateは同一SHAでSUCCESSであるため、docs-only commitでの追加がgate logに許可された手順どおりである。本commit以後にnon-docs変更を行った場合はre-auditが必要である。
- AC-3（post-restore episodeの再現/bound、`docs/assessment/issue-172-input-unavailable-diagnostics.md`）は本PRの対象外であり、issue #172をopenのまま後続で完了する扱いがPR本文とissueコメントで明示されている。本記録はAC-1/2/4/5/6/7のみを対象とする。
- 非blockerの観察: fail-open guardは `RuntimeException` のみを捕捉する（既存 `capture()` 契約と同一の範囲であり、regressionではない）。
