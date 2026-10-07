# Issue #528 — test-audit note (new unit tests + CI routing)

Applied per `.agents/skills/test-audit/SKILL.md` (authoring gate). Head: `issue-528-lan-network`.

1. **Protected contract** — the spec #528 canonicalization contract (single `%s`
   substitution before parsing; 4-valued static classification), the outcome
   lifecycle (template change clears to `not-run`; publish only while the
   fetch-start template is current), and the log redaction contract (category +
   permission state + SDK_INT only; exception class name instead of message).
2. **Credible regression** — plausible breaks: someone "fixes" the classifier to
   treat ULA/hostname as public (would silently break the LAN guard contract),
   replaces `replaceFirst` with `replace`, or publishes stale outcomes after a
   template change; logging `e.message` again would leak URLs into logcat.
   Each test fails on those concrete changes.
3. **Canonical owner** — `SuggestionUrlClassifier` /
   `SuggestionFetchOutcomeState` / `SuggestionFetchLog` are pure JVM seams that
   production (`CustomWebSearchProvider`, settings UI) calls directly; the unit
   test is the primary owner at the lowest deterministic boundary. No
   test-only production code was added.
4. **Distinct higher-layer risk** — the emulator matrix (spec Verification)
   owns LNP enforcement, dialog behavior and settings rendering; the unit lane
   deliberately does not replay those scenarios (no distinct JVM-observable
   failure mode left uncovered).
5. **Overlap** — no existing coverage for this package (previous `--tests`
   allowlist had no `app.lawnchair.search.*`).
6. **Impact surface / lane** — `surface_jvm` already covers `tests/unit/**`;
   the new tests join the existing permanent `organizer-unit-tests` gate by
   adding `--tests 'app.lawnchair.search.*'` (no new lane, no new job).
   `tools/repo-contract/ci_portfolio_map.yml` enumerates surfaces, not
   packages, so no map change is required.
7. **CI classification** — Permanent: the classifier guard ships as the merge
   gate's regression oracle for a targetSdk-37 platform contract.
8. **Cost vs confidence** — pure JVM tests (~ms), zero emulator/clean-state
   cost, high independent confidence on the exact contract seams.

## Addendum — cleartext classification fix (regression test)

Applied per the same authoring gate. `mapFailureToOutcome` moved from a private
method of `CustomWebSearchProvider` to an internal pure top-level function in
`SuggestionFetchOutcome.kt` (same pattern as `SuggestionFetchLog`: production
caller, no test-only seam) so the classification is directly testable.

1. **Protected contract** — the spec #528 typed-outcome contract: a cleartext
   policy rejection must surface as `CLEARTEXT_BLOCKED`, never as
   `GENERIC_NETWORK_FAILURE`; `SSLException` stays `TLS_CT_FAILURE`; anything
   else stays generic.
2. **Credible regression** — the verified bug this fix addresses: merged OkHttp
   5.5.0 throws `java.net.UnknownServiceException("CLEARTEXT communication to
   <host> not permitted by network security policy")`, which the old
   `"Cleartext HTTP traffic"` message match missed, so every cleartext
   rejection was mis-typed as generic. Demonstrated: the new tests fail on the
   pre-fix logic (red run: 3 failed / 33) and pass on the fix (green).
3. **Canonical owner** — the pure `mapFailureToOutcome` JVM seam, tested in the
   existing `SuggestionFetchOutcomeLifecycleTest` at the lowest deterministic
   boundary; no existing test owned exception→outcome classification.
4. **Distinct higher-layer risk** — the emulator re-capture (evidence item 7a)
   owns the end-to-end OkHttp/NSC integration; the CI lane does not replay it.
5. **Seam cost** — none: internal pure function with a production caller; no
   wrapper, flag, global, or test-only export.
6. **Impact surface / lane** — joins the existing permanent merge-gate job via
   the already-present `--tests 'app.lawnchair.search.*'` filter (no new lane,
   no `ci_portfolio_map.yml` change; the map enumerates surfaces, not
   packages).
7. **CI classification** — Permanent (regression oracle for the fix in the
   merge gate).
8. **Cost vs confidence** — pure JVM tests (~ms), no emulator or clean-state
   cost.

## Addendum — review round 1 fixes (F2 cancellation gate, F3 invalid-template short-circuit, F4 blank-template hook)

Applied per the same authoring gate for the review-round-1 tests added to
`SuggestionFetchOutcomeLifecycleTest`.

1. **Protected contract** — three accepted-contract seams: (a) a publish is
   refused when the fetching coroutine is no longer active, so a blocking call
   completing after the outer `.timeout()` cancellation can never flip the
   user-visible timeout state (`generic-network-failure`) to `SUCCESS`; the
   gate lives in `SuggestionFetchOutcomeState.publishIfActive`, so the full
   publish decision (template currency + coroutine state) is testable at the
   state-holder seam; (b) the timeout hook's publish-if-template-current
   semantics: `GENERIC_NETWORK_FAILURE` publishes only for the template that
   was actually being fetched, and a stale hook after a template change is
   refused; (c) the template-change lifecycle also holds across
   `A → blank → A` (the hook runs for blank in the UI layer; the state seam
   asserts the clearing), and the new `guardInvalidTemplate` log builder keeps
   the redaction contract (reason category + SDK_INT only, no URL/placeholder/
   query material).
2. **Credible regression** — plausible breaks: `publishIfActive` ignoring the
   coroutine flag (reintroduces the review's finding 2 race where the late
   completing call overwrites the timeout outcome); the timeout hook
   publishing against the current template instead of the fetch-start template
   (would mark a newly saved configuration as failed); moving the UI hook back
   into the non-blank-only composable (would resurface stale failures for
   `A → blank → A` — the production wiring risk is accepted and covered by the
   emulator oracle; the state seam pins the lifecycle it must produce); logging
   URL/query material in the invalid-template guard.
3. **Canonical owner** — `SuggestionFetchOutcomeState` / `SuggestionFetchLog`
   remain the canonical pure JVM seams; `publishIfActive` has a production
   caller (the suspend `publishOutcome` in `CustomWebSearchProvider`), so no
   test-only seam was added. The `A → blank → A` hook placement itself is a
   Compose-structure property not representable on the JVM; it is covered by
   evidence oracle item 4 instead of a duplicated, weaker unit test.
4. **Distinct higher-layer risk** — the emulator oracles own the
   production-binding behaviors this lane cannot see: no OkHttp call for an
   INVALID template, no LAN prompt for it, the timeout →
   generic-network-failure → no-late-SUCCESS flip end-to-end, and the
   request-button state after system-initiated process death. The unit lane
   does not replay those.
5. **Overlap** — extends the existing `SuggestionFetchOutcomeLifecycleTest`;
   no new file, no overlap with the classifier tests (the double-`%s` →
   INVALID verdict itself was already pinned there; the new tests cover the
   fetch-path consequences, not the verdict).
6. **Impact surface / lane** — same `surface_jvm` coverage via the existing
   `--tests 'app.lawnchair.search.*'` filter in the permanent merge-gate job;
   no new lane, no `ci_portfolio_map.yml` change.
7. **CI classification** — Permanent (merge-gate regression oracles for the
   review-round-1 contracts).
8. **Cost vs confidence** — pure JVM tests (~ms), no emulator or clean-state
   cost.
