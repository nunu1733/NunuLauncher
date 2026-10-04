# Assessment: Issue #185 — QSB-row item interop (AC-3 on-device reproduction resolved)

> Status: implemented (PR: issue-185-implementation branch)
> Verification date: 2026-09-01
> Build: `issue-185-implementation` debug (LawnWithQuickstepGithubDebug)
> Environment: emulator `nunu_qpr2_api36_1` (API 36.1, `sdk_gphone64_arm64`), serial `emulator-5554`

## Outcome in one line

The #172 reproduction shape — a workspace with a deep-shortcut item inside the
QSB reservation row (`screen=0 cell(2,0)`), kept by the loader under a tolerant
overlap policy — now composes input and reaches a plan
(`RUN_STARTED → CAPTURED → PLANNED captured=1 moved=0 preserved=1`), where the
same state previously ended permanently in
`INPUT_NOT_READY / INPUT_READINESS.CAPTURE_INVALID` (issue #172 assessment).

## Reproduction setup (deterministic, on emulator)

1. `pm clear app.lawnchair.debug` (fresh 4x5 default workspace, QSB enabled,
   `allowWidgetOverlap` at its default `false`).
2. Home screen → **Widgets → Allow overlap** ON (mirrors the #172 environment
   where the loader tolerated the row across multiple model loads).
3. Inserted the reproduction row directly into the grid DB via `run-as`:
   `_id=115, itemType=6 (DEEP_SHORTCUT), screen=0, cell(2,0)` — the same shape
   as the #172 assessment's favorites row 115.
4. `am force-stop` + relaunch: the model load **kept** row 115 (tolerant
   policy), reproducing the persistent interop state.
5. Settings → Home screen → Organize home layout → **Review organization**.

## Result: composition succeeds, item preserved

```
OrganizerDiag: run=b9e47e13973af6ff3ec038f92b7e9cfd phase=RUN_STARTED
OrganizerDiag: run=b9e47e13973af6ff3ec038f92b7e9cfd phase=CAPTURED
OrganizerDiag: run=b9e47e13973af6ff3ec038f92b7e9cfd phase=PLANNED captured=1 moved=0 preserved=1
```

Journal record (run-as export):

```json
{"schemaVersion":1,"journalSequence":3,"runId":"b9e47e13973af6ff3ec038f92b7e9cfd","phase":"PLANNED",
 "planSummary":{"capturedItemCount":1,"preservedCount":1,
 "preservedByReason":{"RESERVED_REGION":1},"confidenceCounts":{"FALLBACK":1}}}
```

With a second movable item added (row 116 at `cell(3,4)`), the same workspace
planned `captured=2 moved=1 preserved=1 → PREVIEWED → USER_CANCELLED` (run
cancelled without apply): the reservation-overlapping item is kept exactly in
place (`Preserved(RESERVED_REGION)`), the movable item is planned outside the
reserved row, and no reservation cell is targeted.

## Platform-tolerance branch (intolerant policy)

With `allowWidgetOverlap` at its default (`false`), a QSB-row item inserted
into the DB is **deleted by the platform loader at the next model load**
(observed: row vanished after restart) — the workspace self-heals, which is
why the compose gate (`NotReady(CAPTURE_RESERVED_OVERLAP)`) is required only
for the window where capture can see the row while the policy would not
accept it. That branch is covered deterministically by
`qsbRowItemCapturesLosslesslyAndComposesPerPlatformTolerance`
(ProductionOrganizationInputInstrumentationTest): same capture shape with
tolerance `false` yields `NotReady(InvalidCanonicalCapture(RESERVED_OVERLAP))`
+ code `CAPTURE_RESERVED_OVERLAP`, with planner/write/recovery invocation
count 0. The on-device UI flow cannot reach that window (the loader deletes
the row before the organizer can be opened), matching the spec's analysis.

## Evidence summary (acceptance criteria)

- AC-1 decision: `docs/adr/0010-qsb-row-item-overlap-interop.md` (accepted),
  ADR-0008 annotated with the partial supersession.
- AC-2 regression shape: instrumentation test
  `qsbRowItemCapturesLosslesslyAndComposesPerPlatformTolerance` (16/16 class
  green) + unit fixtures (`OverlapAcceptanceGateTest`,
  `OrganizationPlanMaterializerReservationGuardTest`, planner/composer
  fixtures). This on-device record is the manual AC-3 evidence.
- AC-3: this document — the #172 sequence composes input on-device.
- AC-4: reservation guard negative tests remain green (target-into-reservation
  invalid, reservation↔reservation rejected, unknown page rejected); recovery
  contract untouched (`RealAdapterRowMatrixInstrumentationTest` round-trips).
- AC-5: composer tolerance matrix unit tests (tolerated → Ready + Preserved
  role; intolerant → typed NotReady; no overlap → policy ignored).
- AC-6: full `testLawnWithQuickstepGithubDebugUnitTest --tests
  'app.lawnchair.organizer.*'` green; organizer instrumentation
  (ProductionOrganizationInputInstrumentationTest 16/16;
  RealAdapterRowMatrix + ProductionPublicSeam + Sanitizer 12/12);
  `spotlessCheck`; `assembleLawnWithQuickstepGithubDebug`.
- AC-7: `PreserveReason.RESERVED_REGION` summary copy added (en + values-ja).
- AC-8: `OverlapAcceptanceGateTest` covers the A5 predicate matrix (overlap +
  intolerant → reject; no overlap / tolerant → accept; hotseat and folder rows
  unaffected). On-device, a policy flip triggers `reloadGrid`, which mutates
  the workspace (the loader deletes the intolerated row) and fails the run
  typed at the existing stale/precondition path — no silent apply.
- AC-9: ADR-0010 records the contract-test choice (no `LoaderCursor` bridge);
  the organizer predicate is the single internal acceptance point shared by
  the composer gate and the A5 gate.

## Commands executed (this assessment)

- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL.
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests
  'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL (797 tests).
- `am instrument` targeted classes (results above).
- `run-as` journal export + sqlite inspection of `launcher_5_4_4.db`.
