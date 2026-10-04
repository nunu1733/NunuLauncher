# High-risk audit: PR #143 resolve grid preset inventory from authoritative device type

> Status: accepted
> Audit date: 2026-08-25

- Auditor: independent audit session (ZCode subagent), no implementation role in #134. Every check below was re-executed by this session from a clean checkout; implementer-reported results were not trusted without reproduction.
- PR: https://github.com/nunu1733/NunuLauncher/pull/143 (carries `risk: layout-data`, `Closes #134`)
- Head SHA: e5effed905e8336f97232a57d67ade8273c409f1
- Evidence code head: 95f2ab6c9b15b3194bce606597f9946917101de8 — verified ancestor of the head above; the only commit in between (`e5effed905`) touches `docs/` paths only (`docs/assessment/issue-108-organizer-mvp-compatibility.md`, `specs/134-grid-preset-snapshot/plan.md`). The host-evidence APKs installed on both emulators report `versionName=15.Dev.(95f2ab6)`.
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32815865390 —
  verified via the GitHub API: `.github/workflows/ci.yml`, event `pull_request`,
  associated with PR #143, head_sha equal to this record's Head SHA, completed
  with conclusion `success` (attempt 2), `final-status` success, source jobs
  `organizer-unit-tests`, `check-style`, `build-debug-apk` all executed and
  successful. Attempt-1 history is recorded in Findings.
- Criteria: specs/134-grid-preset-snapshot/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6

## Scope

Branch `issue-134-grid-preset-snapshot`, working tree clean at HEAD
`e5effed905e8336f97232a57d67ade8273c409f1`. This session read issue #134 with
all comments, the accepted Stage A `specs/134-grid-preset-snapshot/spec.md`
and `plan.md`, the full `git diff main...HEAD`, and re-executed the gates and
host evidence below.

Diff inventory vs `main` (8 files, +730/-24): production change confined to
`lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt`; new JVM pure-seam test
`tests/unit/app/lawnchair/DeviceProfileOverridesPresetResolutionTest.kt`; new
instrumentation harness
`tests/organizer-instrumentation/app/lawnchair/organizer/integration/Issue134GridPresetInstrumentationTest.kt`;
KDoc-only update to `Issue108GridEvidenceInstrumentationTest.kt`; CONTEXT.md
domain term; dated correction addendum in
`docs/assessment/issue-108-organizer-mvp-compatibility.md`; spec/plan documents.
`git diff main...HEAD -- src/ quickstep/` is empty: zero Launcher3/AOSP bridge,
as the plan requires. The change reuses unchanged upstream public API only:
`InvariantDeviceProfile.parseAllDefinedGridOptions` (`src/.../InvariantDeviceProfile.java:692`),
`GridOption.isEnabled(deviceType)` (`:1100`), and
`DisplayController.Info.getDeviceType()` — the same authority `initGrid` uses.

Independently confirmed properties of the production diff:

- **Construction-time snapshot removed; query-time resolution.**
  `enabledPresets()` parses the declared catalog per query via
  `parseAllDefinedGridOptions(appContext)` and filters with
  `GridOption.isEnabled` over `DEVICE_TYPES = {TYPE_PHONE, TYPE_TABLET,
  TYPE_MULTI_DISPLAY}`, using `DisplayController.INSTANCE.get(appContext).getInfo().getDeviceType()`
  as authority. Nothing caches by constructor time, so posture/device-type
  changes re-resolve (spec scenario "foldable" holds by construction).
- **Deterministic ceiling match.** `ceilingMatchPreset(presets, target)`:
  declaration-order first preset with `numRows >= target.numRows &&
  numColumns >= target.numColumns`, else the last enabled preset; documented
  approximation per spec Scenario "current grid name". Pure companion function,
  no platform types, shared by production and JVM tests (same seam).
- **Fail-closed diagnostics.** `getGridInfo(gridName)` throws
  `NoSuchElementException` naming the requested preset, the resolved
  `deviceType`, and the enabled preset set; it runs before any preference
  write, so unknown/disabled names cannot partially apply.
  `setCurrentGrid` still writes exactly the existing three keys through the
  pre-existing path (rows/columns/hotseat preferences), unchanged.
- **Public surface unchanged:** `getGridInfo()`, `getGridInfo(gridName)`,
  `getGridName(gridInfo)`, `getCurrentGridName()`, `setCurrentGrid(gridName)`,
  `getOverrides(...)`, `getTextFactors(...)` keep their signatures; the only
  behavioral deltas are the intended inventory correctness and typed
  diagnostics.
- **Review follow-up verified:** the instrumentation negative paths and the
  named transition go through
  `InvariantDeviceProfile.INSTANCE.get(context).setCurrentGrid(context, name)`
  (production delegate + `MAIN_EXECUTOR` re-init), addressing issue #134 review
  P1 item 1; the evidence SHA misrecorded as `6b89df5` was corrected to
  `95f2ab6c9b` with an explicit correction note in the #108 matrix addendum,
  addressing P1 item 2.

Lineage/provenance checks all passed: `git merge-base --is-ancestor
95f2ab6c... e5effed905` true; post-code-head delta docs-only; clean tree.

## Criteria check

Spec status is `accepted`; each criterion below maps to what THIS session
independently observed.

- **AC-1 (tablet named switch through the production seam applies 6x5/hotseat 6
  and survives process restart; current grid name matches persisted name) —
  PASS.** Re-executed on emulator-5554 (`issue108_api36_pixel_tablet`,
  TYPE_TABLET, API 36): full class OK (3/3); phased `apply` → `am force-stop`
  → relaunch → `verify` → `restore` all OK. `verify` ran in a genuinely fresh
  process (pidof empty after force-stop before the relaunch) and asserts live
  6x5/hotseat 6, `getCurrentGridName() == "6_by_5"`, and persisted
  `idp_grid_name == "6_by_5"`. One deviation during the restart step is
  recorded in Findings (systemui ANR dialog; explicit launcher start used).
- **AC-2 (current grid name always resolves to an enabled preset; exact match
  first, deterministic ceiling approximation otherwise) — PASS at reachable
  levels.** JVM pure seam re-executed green: type-filtered inventories for all
  three device types plus unknown-type empty inventory; ceiling match exact /
  approximate-first-fit / last-fallback cases; exhaustive determinism sweep
  (all types x targets 1..8 x 1..8, result always an inventory member and
  repeatable). Host level: `currentGridNameIsAlwaysAnEnabledPreset` green on
  tablet and phone. Residual limitation: `TYPE_MULTI_DISPLAY` exists on no
  available emulator host, so it is covered at pure level only — exactly what
  the spec's non-goals permit; no real-host support claim is made.
- **AC-3 (existing phone lane passes unmodified) — PASS.** Re-executed on
  emulator-5556 (`nunu_qpr2_api36_1`, phone, API 36):
  `Issue108GridEvidenceInstrumentationTest` OK (2/2); its diff is KDoc-only.
  Full JVM unit gate green.
- **AC-4 (unknown/disabled names fail closed: preferences unchanged, typed
  diagnostic, no partial application) — PASS.** Re-executed on BOTH hosts:
  `disabledOrUnknownPresetIsRejectedWithoutPreferenceChange` green (tablet
  within the 3-test class run; phone in the two-method selection). It asserts
  the three preference keys byte-equal before/after, rejects an unknown name
  and the host-disabled name through the IDP-level seam, and checks the
  diagnostic contains the requested name and the enabled-name list.
- **AC-5 (tablet regression harness exists in-repo; #108 matrix #134 cell
  updated with dated fix/evidence reference) — PASS.** Harness reviewed and
  executed (above). The addendum diff adds a dated (2026-08-25) section that
  records the fix, the corrected evidence revision `95f2ab6c9b` with an
  explicit note that the earlier `6b89df5` entry predates the implementation,
  and the re-executed host results.
- **AC-6 (`risk: layout-data` label; high-risk independent-evidence gate: CI
  merge gate green on the audited head + separate-session assessment record) —
  PASS.** Label observed via the GitHub API; CI run verified as stated in the
  header (attempt 2 fully green on this exact head); this record is written by
  a session with no implementation role. The `high-risk-gate` workflow run on
  this head is red solely because this file was not yet committed when it ran;
  landing this record (docs-only) satisfies that dependency.

No criterion failed. No requirement IDs were cited against documents that do
not define them; no ADR exists for this change and none is needed per the plan's
documented decision.

## Executed test surface

All commands below were executed by this session on 2026-08-25.

Block A — local repository checks (clean tree at `e5effed905e8336f97232a57d67ade8273c409f1`):

```text
git status && git rev-parse HEAD && git log --oneline -4
  PASS — HEAD e5effed905..., branch issue-134-grid-preset-snapshot, nothing to commit

git diff main...HEAD --stat && git diff main...HEAD --name-only -- src/ quickstep/
  PASS — 8 files (+730/-24); src//quickstep diff EMPTY (zero Launcher3 bridge)

git diff main...HEAD -- lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt (+ test diffs)
  PASS — full diff read; properties recorded in Scope

git merge-base --is-ancestor 95f2ab6c9b15b3194bce606597f9946917101de8 e5effed905e8336f97232a57d67ade8273c409f1
  PASS — exit 0; git diff --name-only 95f2ab6...e5effed9 = docs/ paths only

./gradlew spotlessCheck --no-configuration-cache
  PASS — BUILD SUCCESSFUL in 1s, exit 0 (tasks UP-TO-DATE: Gradle input-hash
         verification against the clean tree)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --no-configuration-cache
  PASS — BUILD SUCCESSFUL in 24s, 386 tasks, EXIT=0

/Users/nunu/Library/Android/sdk/platform-tools/adb devices -l
  PASS — emulator-5554 (2560x1600@320dpi, tablet form factor), emulator-5556
         (1080x2400@420dpi, phone) both online

adb -s emulator-5554 shell dumpsys package app.lawnchair.debug | grep versionName
adb -s emulator-5556 shell dumpsys package app.lawnchair.debug | grep versionName
  PASS — both "versionName=15.Dev.(95f2ab6)" (APK provenance = evidence code head);
         app.lawnchair.debug.test present on both
```

Block B — tablet host re-execution (emulator-5554, TYPE_TABLET):

```text
adb -s emulator-5554 shell am instrument -w -r -e class \
  app.lawnchair.organizer.integration.Issue134GridPresetInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  PASS — "OK (3 tests)" (disabledOrUnknownPresetIsRejectedWithoutPreferenceChange,
         durableNamedPresetSwitch single-process mode, currentGridNameIsAlwaysAnEnabledPreset)

adb -s emulator-5554 shell am instrument -w -r -e class \
  ...Issue134GridPresetInstrumentationTest#durableNamedPresetSwitch -e issue134.phase apply \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  RAN — verdict line not captured (output filter too narrow, see Findings);
        durability of the applied state independently proven by the verify phase below

adb -s emulator-5554 shell am force-stop app.lawnchair.debug   # FORCE_STOP_OK, sleep 3
adb -s emulator-5554 shell monkey -p app.lawnchair.debug -c android.intent.category.HOME 1
  DEVIATION — relaunch did not leave Lawnchair running: pidof empty, a
              "System UI isn't responding" dialog held focus, and the top
              resumed activity was com.google.android.apps.nexuslauncher
              RecentsActivity. Recovered (see Findings).
adb -s emulator-5554 shell am start -n app.lawnchair.debug/app.lawnchair.LawnchairLauncher
  PASS — fresh Lawnchair process started (pid 6198; process verifiably dead
         immediately before), became focused home window after the ANR dialog
         ("Wait") was dismissed via uiautomator-located tap; sleep 12

adb -s emulator-5554 shell am instrument -w -r -e class \
  ...Issue134GridPresetInstrumentationTest#durableNamedPresetSwitch -e issue134.phase verify ...
  PASS — "OK (1 test)": durable 6x5/hotseat 6, getCurrentGridName()=="6_by_5",
         persisted idp_grid_name=="6_by_5" in the fresh process

adb -s emulator-5554 shell am instrument -w -r -e class \
  ...Issue134GridPresetInstrumentationTest#durableNamedPresetSwitch -e issue134.phase restore ...
  PASS — "OK (1 test)"
```

Block C — phone host re-execution (emulator-5556):

```text
adb -s emulator-5556 shell am instrument -w -r -e class \
  app.lawnchair.organizer.integration.Issue108GridEvidenceInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  PASS — "OK (2 tests)" (existing phone lanes, unmodified harness)

adb -s emulator-5556 shell am instrument -w -r -e class \
  ...Issue134GridPresetInstrumentationTest#disabledOrUnknownPresetIsRejectedWithoutPreferenceChange,\
...Issue134GridPresetInstrumentationTest#currentGridNameIsAlwaysAnEnabledPreset \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  PASS — "OK (2 tests)"
```

Block D — GitHub Actions verification:

```text
gh api repos/nunu1733/NunuLauncher/actions/runs/32815865390
  ATTEMPT 1 — completed, conclusion FAILURE; metadata correct throughout:
             path=.github/workflows/ci.yml, event=pull_request,
             head_sha=e5effed905e8336f97232a57d67ade8273c409f1, pull_requests=[143]

gh api .../runs/32815865390/jobs?per_page=100
  ATTEMPT 1 — final-status: failure, organizer-instrumentation-issue53-tests: failure
             (OnboardingOrganizationProposalInstrumentationTest
             .productionOwnerDefersBindWhilePausedThenShowsAndRoutesReviewAfterResume,
             bare java.lang.AssertionError); organizer-unit-tests/check-style/
             build-debug-apk and all other lanes: success

gh api repos/nunu1733/NunuLauncher/actions/runs?branch=issue-134-grid-preset-snapshot
  PASS — no other run existed for either head SHA at check time; also confirmed
         main's latest run (ad4bab00, this PR's merge-base) is itself red on a
         DIFFERENT emulator lane (organizer-instrumentation-api35-tests),
         evidencing pre-existing emulator-lane instability independent of this PR

gh api repos/nunu1733/NunuLauncher/actions/jobs/97703961618/logs
  PASS — failure isolated to the single issue53 UI test; unrelated to any file
         or class changed by this PR

gh run rerun 32815865390 --repo nunu1733/NunuLauncher --failed
  EXECUTED BY THIS SESSION — exit 0; no code or workflow change; transparent
  rerun following the precedent recorded in docs/assessment/pr-140-issue136-
  default-layout-rejection.md for the same flaky test; poll loop until completed

gh api .../runs/32815865390 (after polling) 
  ATTEMPT 2 — status=completed, conclusion=success
gh api .../runs/32815865390/jobs?per_page=100
  ATTEMPT 2 — ALL 12 jobs success: final-status, changes, validate-repo-contract,
              organizer-unit-tests, check-style, build-debug-apk, and all six
              organizer-instrumentation lanes (api35, db-migration, issue52,
              issue53, issue99, shared-writer)
```

## Findings

- **CI merge-gate attempt 1 failed; resolved by a transparent failed-jobs
  rerun executed by this audit session.** Run 32815865390 attempt 1 failed
  ONLY in lane `organizer-instrumentation-issue53-tests`:
  `OnboardingOrganizationProposalInstrumentationTest.productionOwnerDefersBindWhilePausedThenShowsAndRoutesReviewAfterResume`
  threw a bare `java.lang.AssertionError`, taking `final-status` down with it.
  That test exercises Issue #53 onboarding UI choreography, shares no code
  path with anything in this PR's diff (grid preset inventory in
  `DeviceProfileOverrides` + tests only), is the same test previously recorded
  as an emulator-lane flake in the PR #140 audit, and main's own latest run
  (ad4bab00) is red on a different emulator lane. This session reran only the
  failed jobs (`gh run rerun --failed`); attempt 2 went fully green including
  `final-status`. Both attempts are recorded here so the merge-gate history is
  complete; the header's CI run link evidences attempt 2 on the audited head.
- **Phased-durability restart deviation (recovered, durability property
  preserved).** After `am force-stop`, the prescribed
  `monkey -p app.lawnchair.debug -c android.intent.category.HOME 1` relaunch
  coincided with a "System UI isn't responding" ANR dialog; Lawnchair was not
  left running (pidof empty) and the stock launcher's RecentsActivity was
  top-resumed. This session dismissed the dialog (uiautomator dump → "Wait"
  tap) and started the launcher explicitly via `am start -n
  app.lawnchair.debug/app.lawnchair.LawnchairLauncher`. The property under
  test — durable grid state across a REAL cold process start — was unaffected:
  the process was verifiably dead between force-stop and start, and the verify
  phase then asserted the full durable state (dimensions, current grid name,
  persisted `idp_grid_name`) in that fresh process.
- **Apply-phase verdict line not captured.** This session's output filter hid
  the `OK`/failure line of the phased `apply` invocation. The subsequent
  `verify` phase passing in a fresh process proves the apply wrote the target
  state; recorded here because the audit log should show every verdict
  directly. All other invocations have their verdict lines captured verbatim.
- **Residual limitation (spec-sanctioned): `TYPE_MULTI_DISPLAY` has no real
  host.** No available emulator exposes it, so its enabled-inventory behavior
  is covered only by the pure seam test. Per the spec's non-goals, no
  real-host support claim is made.
- **Residual risk (documented non-goal): other static `deviceType` readers
  remain.** `getGridOptionFromFileName` / `getGridNameFromSize` /
  `getGridOptionFromName` still read the static field; they are only invoked
  after first grid initialization today, which the spec explicitly leaves
  untouched. Notably, the instrumentation itself had to avoid the
  static-field-filtered `parseAllGridOptions` before initialization (it would
  reproduce defect #134 inside the test); the harness uses authoritative
  resolution instead — recorded in the plan's execution notes.
- **`High-risk gate / high-risk-evidence` is red on this head until this
  record lands** (run 32815873217: "no docs/assessment/pr-143-<slug>.md audit
  record for this PR"; an earlier duplicate run was cancelled as superseded).
  This is the designed dependency of the gate on the audit file. This session
  did NOT commit or push; committing this docs-only file is the maintainer's
  remaining step, after which the gate can pass on a re-run.
- **Minor observation: `spotlessCheck` reported UP-TO-DATE** rather than
  executing rules afresh. On the verified-clean tree this is Gradle's
  input-hash verification and is accepted as a pass; noted for completeness.
- No blocking finding was identified. Verdict: **accepted** — the audited head
  `e5effed905e8336f97232a57d67ade8273c409f1` carries a lawnchair-only,
  zero-bridge implementation whose observable contract matches the accepted
  spec; every acceptance criterion was independently re-verified (local gates,
  both hosts, pure seam, CI merge gate) by this session.
