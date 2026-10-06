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
