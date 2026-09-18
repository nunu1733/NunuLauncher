# Independent audit: PR #353 (Issue #328 import success state)

> Status: accepted
> Audit date: 2026-09-18

- Auditor: general-purpose independent audit session (dedicated sub-agent session; it did not implement, review, or fix PR #353, and re-verified the head with its own commands).
- PR: https://github.com/nunu1733/NunuLauncher/pull/353 (`Refs #328`)
- Base SHA: `a9ec3c2cf9ce6f666e5f9f12eecf82f541f12916`
- Audited head SHA: `468e097d9c1c905a1645fb8fe990f333b3d5514d`
- Audited-head CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35307059756 (workflow `ci.yml`; at audit time 14 checks passed with the issue52 manual-organization lane still running; the lane set is unchanged from merged main)
- Criteria: `specs/328-exchange-import-success-state/spec.md` AC-1..AC-10 (feature PR; no ADR criteria — the PR changes no persisted layout, recovery, schema, or backup state)

## Scope

The audit fixed the implementation baseline to head `468e097d9c`. It re-ran the organizer unit suite itself and inspected the complete base..head diff.

```text
git diff --stat a9ec3c2cf9ce6f666e5f9f12eecf82f541f12916..468e097d9c1c905a1645fb8fe990f333b3d5514d
-> 16 files, +3189 / -62 (spec/plan, organizer UI + personalization, tests, docs)
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
-> BUILD SUCCESSFUL; 139 suites / 1501 tests / 0 failures / 0 errors / 0 skipped
```

The audit confirmed the diff contains no layout-DB write path, migration/schema, backup, deck, or upstream Launcher3 file change, matching the plan's "変更しないseam" declaration, and that PR labels carry no `risk: layout-data` / `risk: migration` (high-risk gate not applicable).

## Criteria check

- **AC-1 (success state, run untouched, attempt anchor): accepted.** `import()` numbers a monotonic attempt token with entry kind and owning run id; `settleImport` drops any settle whose token is not current, with oracles for cancel, input edit/clear, out-of-order imports, and owning-run loss; the idle path starts no run before the CTA (`detectionCalls == 0`); the immediate attach/start and the retired status string are gone.
- **AC-2 (not-applied statement): accepted.** Both locale resources declare the string and the rendered surface asserts it.
- **AC-3 (CTA single-flight and seam contract): accepted.** Synchronous `continuing` flip with a one-call oracle, attempt-token-anchored settle (same-identity ABA oracle), `ensureActive()`-style live-context exception conversion with non-CE/live-CE/scope-cancel oracles, pre-attach owning-runId re-check, and the single `StrategyWriteArbiter` state machine (write-vs-write single flight, entry-specific write-start gate, commit-time restart suppression, `finally` release on restart success / Committed-no-restart / non-commit / throw / cancel with the RESERVED and RESTARTING windows observed).
- **AC-4 (canonical summary, privacy boundary): accepted.** The pure function reads only the canonical `completed` plus the planner-effective global preference; the model-shape reflection contract and the cross-contract test against `IntentPlannerAdapter.project(...).globalMinimizeMovement` (true/false/absent) both hold.
- **AC-5 (discard, Back, conflict freeze): accepted.** Discard keeps the session and supports the re-import round trip; the hosting-level `ExchangeImportSuccessBackHandler` composes after the screen-level handler and its viewport-out oracle drives the success item out of composition before pressing Back; the entry-specific gates are one shared truth table used by the hosting wiring and the tests.
- **AC-6 (success/warning/reject identification): partially verified.** Automated semantics (Polite live region, distinct warning heading, enabled/disabled actions, frozen-picker reason live region) are asserted; the manual TalkBack read-out remains deferred to the device evidence pass (see below).
- **AC-7 (off-viewport Back): accepted at the implementation+oracle level.** The instrumentation oracle proves the host fallback is not reached with the success item outside the viewport; the audit did not execute instrumentation itself (see "Not verified here").
- **AC-8 (large font): offered as structural evidence.** A platform-font-scale render test runs in the CI lane; the 200% (`font_scale=2.0`) run was recorded on the API 36 AVD by the implementation session.
- **AC-9 (Import → CTA → next step → preview E2E): not satisfied.** Physical-device E2E is deferred; the Issue stays open (`Refs #328`), matching the #327 precedent.
- **AC-10 (existing failure/export regression): accepted.** The audit's own unit run covered the existing controller/normalizer/pipeline/run suites with 0 failures.

## Discrepancies found and their resolution

The audit reported D-1..D-8; all were addressed in the PR before merge:

- **D-1** picker-freeze UI oracles missing -> `strategyPickerItems` made test-accessible and a new `StrategyPickerFreezeInstrumentationTest` pins disabled rows, the Polite reason live region and both reason copies.
- **D-2** Busy/NotAttachable settle oracle missing -> `ctaGateRefusalsKeepTheSuccessStateAndAllowRetry`.
- **D-3** `Started.runId` mismatch oracle missing -> `aStartedRunIdMismatchSettlesAsFailureNotSuccess`.
- **D-4** continuing-discard refusal oracle missing -> `discardIsRefusedWhileTheCtaIsContinuing`.
- **D-5** new instrumentation classes not wired into CI -> added to the `organizer-instrumentation-issue52-tests` class filter.
- **D-6** heading focus not implemented -> focus request on arrival plus a focused-semantics regression oracle (`assertIsFocused` with a bounded settle wait), re-reviewed and approved.
- **D-7** both-locale string/plural existence test missing -> `importSuccessStateStringsExistInBothLocales`.
- **D-8** transient CTA guidance lingering after a successful settle -> `status = null` on the success settle, with the oracle asserting it.

## Follow-up commits after the audited head

The audit fixes above are production/test/CI changes after `468e097d9c` (fix commit `33c11c865a`, focus oracle commit `41d3cd6236`, audit-record commit following). Each was re-reviewed by the implementation review pipeline on its own head and approved before merge; the merge head's CI `final-status` is the CI evidence for the merged state.

## Not verified here

- Physical-device E2E (AC-9) and the manual TalkBack read-out (AC-6) were not exercised; neither is available in this environment (emulator only). They remain tracked on Issue #328 for a device evidence pass, and the Issue is intentionally left open.
- The auditor did not run the instrumentation suites itself; those results come from the PR's CI run and the implementation session's local AVD runs, as recorded above.
- Compose `OnBackPressedDispatcher` "last enabled callback wins" behavior is a documented framework dependency; the AC-7 oracle is the regression guard.
