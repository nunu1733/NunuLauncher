# Benchmark fixture lock encoding correction

Status: proposed fix; exact-head review/CI pending. Date: 2026-10-12. Issue: [#577](https://github.com/nunu1733/NunuLauncher/issues/577), discovered during [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase3.

## Contract and scope

Tier L / test-only. The primary owner is the existing `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest` under accepted [spec441](../../specs/441-editing-burden-benchmark/spec.md). No new product behavior or write seam is introduced, so no new feature spec/plan is required.

The fixture must be usable for benchmark editing, preserve dock/reservations, and restore its pre-state in default mode. All four fixture constructors wrote literal0; the public lock enum is UNKNOWN0/UNLOCKED1/LOCKED2, and production RowManifestCodec decodes its ordinal. The fixture's own geometric/identity test did not inspect that semantic state.

Phase3 observed38 fixture rows at0 and4 preserved rows at1 after a successful seed. Production composition rejected UNKNOWN_LOCK; an isolated valid two-profile input passed. The production fail-closed gate is correct. This is a deterministic fixture defect, not evidence that user layout data was corrupted.

## Test-audit

- Protected contract: fixture-owned rows are known unlocked, visible through the same production capture seam used by editing; preserved rows are unchanged.
- Credible regression: a raw numeric lock value that maps to UNKNOWN makes benchmark editing/apply unavailable while a geometric seed check still passes.
- Primary boundary: existing fixture seeding test. Its existing reservation assertion now also captures the real typed rows, checks complete membership (no vacuous all-match), and requires UNLOCKED. This tests decoding/admission meaning rather than repeating a raw literal comparison.
- Overlap: planner/apply UNKNOWN rejection already has its own tests; those are not duplicated. No new test method/class/lane or production hook is added.
- Impact/CI: surface_organizer_ui, existing manual-organization-ui Conditional lane. The portfolio description and map ownership note are updated; lane edges/class filters are unchanged.
- Validation: local repository/portfolio validators and whitespace check pass. Exact-head CI and runtime RED/GREEN will be recorded in the PR packet; this document is not a claim they have already completed.

## Cutover applicability

The same constructors exist on the16 rebase stack. Phase3 uses valid diagnostic fixture state and separately records the mainline fix's applicability. This PR does not perform the baseline cutover or close Epic516.
