# High-risk audit: PR #274 fix: preserve folder-child representability across workspace moves

> Status: corrective action required
> Audit date: 2026-09-10

- Auditor: independent audit session, separate from the implementation session
- PR: https://github.com/nunu1733/NunuLauncher/pull/274
- Head SHA: 2b7a2702c464538a52731bc52e4141b6f3802784
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34459682621
- Criteria: `specs/269-folder-workspace-representability/spec.md` — AC-269-01, AC-269-02, AC-269-03, AC-269-04

## Scope

This audit covers the full PR diff from base `a96da395056210ad882d262750fbd51b8198f080` through the audited head. The changed paths are:

- `src/com/android/launcher3/model/ModelWriter.java`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt`
- `specs/13-safe-layout-application/spec.md`
- `specs/269-folder-workspace-representability/spec.md`
- `specs/269-folder-workspace-representability/plan.md`

The production review followed the real `ModelWriter.moveItemInDatabase` path through `updateItemInfoProps`, the destination-container branch, pending `Favorites.SPANX`/`SPANY` writes, notification, and enqueueing. The organizer adapter and canonical capture boundary were also checked: `FolderChild` retains nullable raw geometry, while desktop canonical capture remains strict. The recovery/apply path and the new instrumentation oracle were reviewed for exact manifest restoration, revision-bound application, correlated reload, and repeat determinism. No Launcher DB schema or migration path is changed by this PR.

## Criteria check

- **AC-269-01 — substantially met.** `ModelWriter` normalizes an existing `WorkspaceItemInfo` to `1×1` when the destination is `Favorites.CONTAINER_DESKTOP`, before the in-memory notification and pending database write. The new instrumentation covers direct desktop entry, NULL and positive non-`1×1` legacy spans through a Hotseat intermediate, and an AppPair-like positive source container. The production rule is destination-based and therefore does not depend on the source container.
- **AC-269-02 — met by the implementation oracle and CI evidence.** `pathA_manualEditBeforeRestore` performs the first organize, captures the recovery point, makes the real writer move, requires `Restorable → Restored` for that exact point, and compares the post-restore manifest with the pre-organize manifest. The local audit session did not have a connected emulator (`adb` is unavailable), so the connected execution is attributed to the verified CI run rather than claimed as a local run.
- **AC-269-03 — met by the implementation oracle and CI evidence.** `pathC_manualEditThenSecondOrganize` independently performs the second organize, requires a verified `Applied` result, reloads and recaptures, then repeats organize and requires exact raw-manifest equality after the repeat. The local audit session did not have a connected emulator; CI is the connected execution evidence.
- **AC-269-04 — incomplete evidence.** The control path preserves exact restore behavior, and the strict desktop capture contract remains unchanged. However, the required focused fixture proving that a malformed desktop row with a NULL span is rejected by capture was not found in the PR or the existing `RealAdapterRowMatrixInstrumentationTest`; the new test only asserts that the successful post-organize/reload state has no desktop NULL span. The acceptance criterion is therefore not fully evidenced by this head.

## Executed test surface

- `gh api repos/nunu1733/NunuLauncher/actions/runs/34459682621 --jq '{event,head_branch,head_sha,status,conclusion,path,pull_requests:[.pull_requests[].number],html_url}'` — verified `pull_request`, PR `274`, branch `issue-269-folder-workspace-representability`, exact audited HEAD, `.github/workflows/ci.yml`, `completed`, `success`.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/34459682621/jobs --paginate --jq '.jobs[] | select(.name=="final-status" or .name=="organizer-unit-tests" or .name=="check-style" or .name=="build-debug-apk") | {name,status,conclusion,html_url}'` — verified `final-status`, `organizer-unit-tests`, `check-style`, and `build-debug-apk` each completed successfully.
- `./gradlew spotlessCheck` — PASS.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — PASS; 386 actionable tasks.
- `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` — PASS; 389 actionable tasks.
- `git diff --check a96da395056210ad882d262750fbd51b8198f080 2b7a2702c464538a52731bc52e4141b6f3802784` — PASS; no whitespace errors.
- `adb devices` — could not run because `adb` is not installed in this audit environment; no local connected instrumentation is claimed.

## Findings

1. **Acceptance-evidence blocker (AC-269-04): missing malformed desktop NULL-span capture fixture.** The strict behavior is present in `LauncherLayoutAdapter`/`RowManifestCodec`, but this PR does not add the focused regression test promised by the accepted spec and plan, and the existing adapter matrix does not supply that fixture. Add the fixture through the existing adapter/application seam, run it on the supported API 36.1 instrumentation surface, update the PR head, and repeat this independent audit.
2. No production defect was found in the reviewed destination-based writer normalization or in the exact recovery/second-organize assertions. The successful CI run is valid independent merge-gate evidence for the audited head, but it does not close the missing AC-269-04 test evidence.
3. **Evidence-tooling mismatch:** `tools/repo-contract/validate_high_risk_evidence.py` does not currently parse the accepted spec's composite IDs `AC-269-01` through `AC-269-04` (its requirement regex handles `AC-<number>` and prefixed forms such as `CW-AC-01`). Running the gate against this audit therefore reports that the requested Criteria reference has no requirement IDs. The validator's ID grammar needs a separate tooling fix; this audit does not alter the requested criteria citation to conceal that mismatch.

**Recommendation: do not merge PR #274 yet.** Add and execute the AC-269-04 malformed-row regression fixture, resolve the validator's composite-ID handling, push the resulting head, obtain a successful `CI / final-status` and high-risk evidence gate for that new head, and re-run the independent high-risk audit. The audit record in this commit intentionally reports corrective action required.
