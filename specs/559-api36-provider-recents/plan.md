---
issue: "#559"
status: accepted
updated: 2026-10-09
---

# API36 provider exact-wire repair plan

Risk tier: H (provider wire bridge). Spec: [spec.md](./spec.md). No layout-data audit.

## Revision / base

- Spec 559 accepted; main spec 554 owner revision `57503d88fc` preserved (API37 decoders on 554 impl kept).
- Impl worktree: `/Users/nunu/Documents/work2/NunuLauncher-559-impl`, source branch `issue-559-api36-recents-compat` based PR #557 head `f131e8e09441dc2d10801d4619b671b0da15a6ee`.
- Docs branch: `issue-559-api36-provider-recovery` based main `a3aaf3925a`; docs PR base main, `Refs #559`; no stack code bulk merge.

## Owned paths / seam

- `quickstep/src/com/android/quickstep/SystemUiProxy.kt` only: reuse initial writer, add exact-BE2A/API36 selector (distinct from Nothing) routing five-arg payload to txn 6.
- `lawnchair/src/app/lawnchair/util/Compatibility.kt` only if needed; no AIDL revendor/new module.
- Wire proof: `docs/assessment/559-api36-provider-evidence/wire-contract/` README + DEX excerpts (do not edit original evidence).

## Order / migration / rollback

1. Derive probe invocation from existing `tools/diagnostics/RecentsParcelProbe.java` (+ `verify_recents_provider.py`); save probe RED on candidate2 control APK `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`.
2. Apply minimal selector + txn-6 routing; keep Nothing/API37 paths intact.
3. Probe GREEN + real surface AC-1..4; record fixed APK hash + source SHA separately.
4. If writer probe GREEN but APP_SWITCH/gesture fails (callback decode/5000ms timeout): STOP, capture, amend + re-review; no further production under this approval.
5. Source commits + pushed review packet separate from docs branch.
6. Rollback: revert selector commit. No migration/DB change.

## Verification / constraints

- AC-1/2 real surface; AC-3 unchanged matrix incl. API37 control; AC-4 same-APK control; AC-5 #554 evidence link.
- Independent final source-SHA audit required for this work. Mechanical high-risk gate (paths/labels only) is not sufficient evidence.
- Known commands only: `./gradlew spotlessCheck`, `./gradlew assembleLawnWithQuickstepGithubDebug`; probe invocation fixed at design from existing tools above. Build/style/exact probe commands + CI evidence recorded in packet. No invented commands.
- ADR-0018 Dec 3/8: #559 remains open until cutover; close-possible stage is verified source + independent review handoff ready, not closed. Docs PR uses `Refs #559`.
- Stack hold: implementation stays on stack branch. Independent start-gate review and acceptance are recorded in [Issue #559](https://github.com/nunu1733/NunuLauncher/issues/559#issuecomment-6065882809); implementation completion requires separate review.

## Execution checklist

- [ ] RED probe before production.
- [ ] Minimal change, no scope creep.
- [ ] GREEN + real surface recorded.
- [ ] Review packet pushed; docs PR `Refs #559`.
