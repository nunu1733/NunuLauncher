# High-risk audit: PR #580 folder-child unset-cell sentinel

> Status: accepted — bounded representation repair
> Audit date: 2026-10-12

- Auditor: Codex independent audit session `/root/audit_580`; did not implement this change. Separate worktree at the audited source commit; no production/test source edits or device operations.
- PR: https://github.com/nunu1733/NunuLauncher/pull/580
- Head SHA: 1f3a4b94dbec59055448ed46d2b19b3becbf863f
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/38227448412
- Criteria: specs/13-safe-layout-application/spec.md AC-10; specs/269-folder-workspace-representability/spec.md AC-269-04

## Scope

Independently inspected the complete nine-file diff from base
`7985a45a6e0d14977bd0f0c155882de65756254c` to the Head SHA above:
`PersistenceManifest.kt`, both changed test files, Spec 13 spec/plan, Spec 269
spec, the CI portfolio/map documentation, and the Issue #579 handoff packet.
The production change is confined to row/manifest admission. It adds no DB
writer, schema or recovery migration, codec wire-format change, public
placement field, permission, dependency, or CI lane/filter.

The accepted bug authorization was read independently at these fixed
revisions: Spec 13 at `805c36d41c6835afd55052e5557cfd3c7d4f391a`, plan 13 at
`ba299cf06c7dfffda198ff440c0800c5a9ffb63e`, Spec 269 at
`e42d2f73cb2340c1f4d4737ccc8afab83a75273d`, and plan 269 at
`2b7a2702c464538a52731bc52e4141b6f3802784`. Issue #579 body and all comments
(none), PR #580, AGENTS, CONTEXT, DESIGN, ADR-0003, quality strategy, building
guide, CI routing, and the high-risk evidence contract/validator were read.
Issue #579's observed source is `f2592577f9675a5153ffa5819651e1001b5c518e`;
the accepted-spec route supplies authorization, rather than treating the
Issue body as a separately accepted Issue-only oracle.

The audit followed the production `RowManifestCodec.capture/values` boundary,
`RecoveryRecordCodec` signed-int encode/decode, and
`RecoveryWriteSetMaterializer` exact row diff. SQLite fixture writes below
exercise the production column conversion; they do not establish a new
transaction/reload/writer contract.

## Criteria check

| Criterion / applicable portion | Independent result |
|---|---|
| Spec 13 AC-10: exact folder/profile persistence round trip | The row admits paired `-1/-1` only with a non-negative candidate parent container. The complete manifest then requires a distinct existing item-type-2 folder parent with equal profile identity. The raw columns and `rawCell` remain signed values; no normalization is introduced. The new codec test uses handwritten expectations for sentinel, SQL NULL, and non-negative cells. The existing SQLite row matrix exercises capture → exact column materialization → recapture and compares complete manifests/states, retaining its widget/profile/lock controls. |
| Spec 269 AC-269-04: exact unchanged control and strict desktop capture | The extended row-matrix method retains its NULL-cell child and adds the sentinel child. The unmodified `desktopApplicationWithNullSpanIsRejectedByCanonicalCapture` remains in the explicitly executed class. Unit negative controls reject desktop/Hotseat sentinel use, mixed/null/other-negative cells, orphan/non-folder/AppPair/self parents and profile mismatch. Public `FolderChild` is still parent/rank only; canonical desktop span strictness and the desktop-entry writer are unchanged. |
| Spec 13/269 exact recovery compatibility | Encode/decode and SQL column materialization already preserve signed ints; neither codec framing nor version/schema/migration changes. Pre-fix readers reject sentinel-containing records at row construction. The plan explicitly requires a rollback reader containing this fix, and does not claim release 45 compatibility or permit fallback row/record rewrites. ADR-0003 private storage/backup exclusion is unchanged. |

These checks cover the applicable representation repair. They do not claim a
new independent execution of every Spec 13 acceptance criterion, every #269
writer scenario, process death, backup exclusion, or migration/rollback APK
matrix.

Test-audit ownership: the credible regression is ordinary folder-child
capture/recovery rejecting or changing paired negative cells. The existing
`RecoveryRecordCodecTest` is the lowest deterministic admission/serialization
owner; the existing `RealAdapterRowMatrixInstrumentationTest` adds the distinct
real Android SQLite read/write/nullability risk. Existing round-trip and
strict-desktop controls are retained. The unit owner remains in the Permanent
`organizer-unit-tests` gate; the SQLite class remains in the Conditional
reservation/recovery lane for `surface_layout_write`. The portfolio and map
were updated together; no new production seam, class, lane, or routing edge
was added. The worker's reported baseline RED is supplementary history, not
an independent baseline rerun by this auditor.

## Executed test surface

Independent local execution used JDK 21 and the configured Android SDK,
at the fixed Head SHA in the separate audit worktree:

```bash
./gradlew --no-watch-fs --no-configuration-cache --console=plain testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryRecordCodecTest'
python3 tools/repo-contract/validate_ci_portfolio.py
python3 tools/repo-contract/test_validate_ci_portfolio.py
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 580 --head-sha 1f3a4b94dbec59055448ed46d2b19b3becbf863f
git diff --check 7985a45a6e0d14977bd0f0c155882de65756254c...1f3a4b94dbec59055448ed46d2b19b3becbf863f
```

The focused unit task actually executed and finished `BUILD SUCCESSFUL` in
6m 44s. Its JUnit XML at `2026-10-12T09:24:29.796Z` reports 11 tests,
zero failures, zero errors, and zero skips, including both added sentinel
tests. The portfolio validator passed, its 21 self-tests passed, the
repository validator passed, its 13 self-tests passed, and diff whitespace
checks passed. The portfolio self-tests emitted
an existing unclosed-file ResourceWarning; they exited successfully. The
repository self-test's deliberately invalid fixture diagnostics are expected.
The high-risk evidence validator passed with this record and the linked
successful CI run; the repository validator passed again after the record was
added. Publishing the record still requires the actual GitHub check to pass.

Independent GitHub API and job-log checks verified that the linked `ci.yml`
run completed successfully, is a `pull_request` run associated with PR #580,
and has exactly the audited Head SHA, branch
`issue-579-folder-child-sentinel`, base SHA above, and base `main`.
`final-status` (job `114743616585`) and the non-skipped source jobs
`organizer-unit-tests` (`114738437956`), `check-style` (`114738438179`), and
`build-debug-apk` (`114738438158`) all succeeded. The standard PR checkout log
identifies the synthetic merge of this head into the pinned base; the run's
API head identity is exactly the audited commit. Their logs show the actual
Gradle tasks running and `BUILD SUCCESSFUL`, rather than a docs-only skip.

The CI unit command was:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest -Pnunu.excludeAiExchangeUnitTests=true --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.homeedit.*' --tests 'app.lawnchair.ui.popup.*' --tests 'app.lawnchair.ui.preferences.navigation.*' --tests 'app.lawnchair.bugreport.*' --tests 'app.lawnchair.backup.*' --tests 'app.lawnchair.migration.*' --tests 'app.lawnchair.DeviceProfileOverridesPresetResolutionTest'
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
```

The reservation/recovery job `114738438115` succeeded on API 36,
`google_apis` x86_64. Its log explicitly includes the existing row-matrix
class in this actual invocation:

```bash
bash tools/ci/run-emulator-command-with-failure-capture.sh emulator-5554 build/reservation-recovery-failure-time-evidence -- ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.ProductionPublicSeamInstrumentationTest,app.lawnchair.organizer.application.RealAdapterRowMatrixInstrumentationTest,app.lawnchair.organizer.application.SanitizerInstrumentationTest,com.android.launcher3.organizer.RecoveryStoreLifecycleTest,app.lawnchair.organizer.application.OverlapAcceptanceGateSeamInstrumentationTest,app.lawnchair.organizer.planning.LoaderCursorOverlapAcceptanceContractTest,app.lawnchair.organizer.application.Issue265ManualEditRecoveryInstrumentationTest,app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest,app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotPublicationInstrumentationTest,app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest,com.android.launcher3.organizer.RecoveryStoreChunkedManifestInstrumentationTest,app.lawnchair.organizer.application.Issue265GateFailedRouteInstrumentationTest,app.lawnchair.organizer.application.PageCaptureInstrumentationTest,app.lawnchair.organizer.locks.LockAuthoringInstrumentationTest
```

The log reports 88 tests started and finished, zero skipped, zero failed,
and `BUILD SUCCESSFUL`. Successful per-method names are not printed, so this
is explicit class routing plus aggregate lane evidence. It is not a claim
that this auditor ran instrumentation locally or observed physical UI.
The independently inspected shared-writer and production-input job logs also
show successful execution; the production-input aggregate contains one skip.
No claim that all instrumentation everywhere ran without skips is made.

## Findings

No blocking implementation or test-ownership finding was identified in the
bounded representation change. The independent unit rerun and exact-head
source CI merge gate passed. Adding this record through a
`docs/`-only commit may retain the pinned source Head SHA under the existing
high-risk validator; the published audit must still pass `high-risk-evidence`
before merge. Any subsequent non-docs change requires re-audit.

This record concerns current main/Lawnchair 15 only. It does not validate the
Lawnchair 16 port, physical API 37 capture, TalkBack, the Phase 3 UI matrix,
or Phase 4 baseline cutover; those remain under Issue #516. No APK install,
device DB modification, Apply, or external publication was performed by this
audit session.
