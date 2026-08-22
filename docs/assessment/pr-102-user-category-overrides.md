# High-risk audit: PR #102 User-authored category overrides

> Status: accepted
> Audit date: 2026-08-22

- Auditor: Archimedes (independent Luna audit worker; read-only, non-implementing, and instructed not to create child agents or threads)
- PR: https://github.com/nunu1733/NunuLauncher/pull/102
- Head SHA: acf2df869d74d7807b88f7f6305796143bb40d72
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32574746879
- Criteria: specs/99-user-authored-category-overrides/spec.md — AC-2, AC-3, AC-4, AC-5, AC-6, AC-6a, AC-7, AC-8, AC-9, AC-10, AC-11.

## Scope

This audit covers the final Stage B implementation for Issue #99 at the exact head above. It reviewed the profile-scoped category-override preference entry, validated policy-category presentation, canonical `UserCache` profile mapping, authoring revalidation, complete-snapshot `AtomicFile` persistence and migration barrier, filtered composer-visible identity, the shared organization-operation lease, and the production organization-input composition seam.

The high-risk persistence review included AndroidX `AtomicFile` interrupted-publication recovery, a writer-to-force-stop-to-fresh-reader instrumentation sequence, legacy `SharedPreferences` behavior after migration, read/write serialization, failure injection, and fresh composition after authoring. The audit also confirmed that authoring cannot invoke the planner, layout database writer, recovery-point creation, or apply path.

## Criteria check

| Accepted criterion | Independent check | Result |
|---|---|---|
| AC-2 | One Home Screen preference destination exposes the validated v1 policy categories, with an exhaustive presentation mapping. | Pass |
| AC-3 | Authoring supports set, change, and remove; explicit `OTHER` remains distinct from absent/automatic classification. | Pass |
| AC-4 | Capture and authoring share the canonical `UserCache` serial-to-`ProfileId` mapping, preserve profile isolation, and re-resolve inventory immediately before mutation. | Pass |
| AC-5 | Invalid or stale policy categories fail closed while valid `OTHER` remains authorable. | Pass |
| AC-6 | Complete generations publish through AndroidX `AtomicFile`; unit evidence covers start/write/sync/finish and post-finish verification failures, shared-mutex concurrency, and interrupted recovery. API 35 evidence runs the writer and reader in separate instrumentation processes and reads only the recovered committed generation. | Pass |
| AC-6a | Migration durably closes the legacy authority barrier at schema 2; a fresh legacy `SharedPreferences` reader returns `UnsupportedSchema` after process restart. | Pass |
| AC-7 | The #83 filtered composer-visible identity is preserved, and corrupt or newer-schema states fail closed. | Pass |
| AC-8 | RUN, RECOVERY, and AUTHORING share one operation lease; admission conflicts and exception paths release the lease without overlapping work. | Pass |
| AC-9 | Authoring ends after S1 persistence and refresh and has no route to planning, layout writes, recovery-point creation, or apply. | Pass |
| AC-10 | The dedicated API 36 UI suite covers semantics, keyboard interaction, touch targets, focus restoration, and 200% font scale. | Pass |
| AC-11 | Unit, API 35 production/restart, and API 36 UI suites cover the accepted success, failure, migration, concurrency, compatibility, accessibility, and fresh-composition surfaces. The linked source CI run and `final-status` succeeded on the audited SHA. | Pass |

## Executed test surface

The independent audit verified that the linked `pull_request` CI run executed and passed the following commands on `acf2df869d74d7807b88f7f6305796143bb40d72`:

```bash
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.rules.CategoryOverrideAtomicFileInstrumentationTest
./gradlew installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.rules.CategoryOverrideAtomicFileRestartWriterInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb shell am force-stop app.lawnchair.debug.test
adb shell am force-stop app.lawnchair.debug
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.rules.CategoryOverrideAtomicFileRestartReaderInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.CategoryOverridePreferencesInstrumentationTest

python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 tools/repo-contract/test_validate_high_risk_evidence.py
```

The run completed successfully, including `organizer-unit-tests`, `check-style`, `build-debug-apk`, `organizer-instrumentation-api35-tests`, `organizer-instrumentation-issue99-tests`, the Issue #52/#53 regression jobs, `validate-repo-contract`, and `final-status`.

## Findings

1. No implementation, specification, or repository-standards blocker was found at the audited SHA.

2. The earlier audit findings are resolved. The final evidence uses the AndroidX implementation rather than a fake atomic adapter, exercises the complete failure matrix and production composer concurrency, proves migration incompatibility to a fresh legacy reader, and runs the restart reader in a distinct instrumentation process after force-stop.

3. The preceding high-risk-gate failures were caused by the old audit record referencing `b1f282f1c6d70244ce42b9397120dd14fc9a0072` and an obsolete CI run. This record supersedes that evidence with the exact final source SHA and successful source CI run above.

4. Any later non-document change requires a new independent audit and a new successful `pull_request` CI run.
