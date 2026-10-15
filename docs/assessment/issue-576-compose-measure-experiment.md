# Issue #576 compose-measure experiment record

> Status: evidence only (Diagnostic). The spec
> [`576-compose-measure-diagnosis`](../../specs/576-compose-measure-diagnosis/spec.md)
> remains `accepted` research; this record does not implement or resolve it.
> Original evidence owner: [discovery](./issue-576-compose-measure-discovery.md).
> Executed 2026-10-15 at head `2a6bcb3188c2ceecd6e5f606d66aa1bd98edfa04`.
> Evidence detail: [`576-compose-measure-evidence/`](./576-compose-measure-evidence/).
> Final PR references this work with `Refs #576`, not `Closes #576`.

## Question

Does the original `IndexOutOfBoundsException: Index 2, size 2` LazyList
measurement failure (PR #575 run 38141049003, `staleApplyAttemptExplainsOutcomeAndNextStep`)
reproduce on current source, and does switching the target runner fixture from
Direct to `HandlerRunPublicationThread` publication change the outcome?

## Matrix (spec AC-2, complete 10/10)

- Baseline arm: exact current source, 5 runs, overlay empty. Per-run receipts in
  [`baseline-results.md`](./576-compose-measure-evidence/baseline-results.md).
- Candidate arm: current source plus the 1-line LOCAL overlay in
  [`candidate-overlay.diff`](./576-compose-measure-evidence/candidate-overlay.diff),
  5 runs. Per-run receipts in
  [`candidate-results.md`](./576-compose-measure-evidence/candidate-results.md).
- Same dedicated AVD (`nunu_issue576_api36_20261015`, `emulator-5558`), same
  boot flags, same Gradle invocation, existing assertions unchanged, no
  stop-and-triage event (no individual failure occurred).
- Result: **5/5 PASS baseline, 5/5 PASS candidate.** The original signature did
  not appear in any run, under either publication mode.

## AC assessment

- AC-1 (original evidence owner): met. Discovery identifiers and stack lines
  match the original artifacts; the existing connected test owns the contract.
- AC-2 (controlled comparison): met in full. 10 runs executed, each with
  source/head/result/log receipts; no blocked or incomplete cell.
- AC-3 (classification): the original NONREPRO stands. No causal evidence for
  any supported class was produced, so classification remains **class 6
  unknown**, the Issue stays open, the four stale-wording assertions and the
  existing Conditional CI lane are unchanged, and no permanent test fix, new
  lane, timeout change, or source change follows. The Issue's
  supported-classification exit condition is NOT met.

## Honest limits

- Non-reproduction on current source is bounded evidence, not root-cause
  resolution and not proof the original CI failure was environmental.
- Local arch is arm64 vs original CI x86_64 (image build ID matches); the test
  and destination files also changed since the original run, so
  historical-versus-current success is not a single-variable comparison.
- Sequential order (5 baseline, then 5 candidate), no randomization or
  counterbalancing: timing differences between runs and arms support no
  performance or causality claim.
- Publication difference alone explains nothing: both arms pass, so the
  Handler hypothesis stays a hypothesis. `ManualOrganizationRunStateBus`/publication source
  is unchanged.

## Teardown (verified by operator)

- Dedicated `emulator-5558` shut down after candidate-5 (08:44Z); owned AVD
  deleted via `avdmanager`; no owned Gradle/Kotlin processes remain; ports
  5558/5559 empty.
- Pre-existing `emulator-5554`/`5556` and netsim `66282` were not ours and were
  not modified. No claim is made that all host devices terminated.
