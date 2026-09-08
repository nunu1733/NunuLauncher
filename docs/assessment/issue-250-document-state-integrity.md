# Issue #250 document-state integrity assessment

> Status: accepted
> Audit date: 2026-09-08
> Repository: `nunu1733/NunuLauncher`
> Audited main: `72cfebc401780c06fc189739e2b73488928e9913`

## Scope and method

This assessment audits the document and Issue lifecycle, not application
behavior. The Issue and all comments were retrieved from the fork on
2026-09-08. Each closed Issue with a local spec was classified from its own
acceptance criteria, the merged PR that claims completion, and any independent
assessment. A title, branch name, or closed state alone was not treated as
implementation evidence.

The classification rule is:

- `implemented`: the Issue's implementation or maintenance acceptance criteria
  are satisfied by a merged mainline PR and the relevant test/audit evidence.
- `accepted`: the Issue deliberately delivers a research result, decision, or
  reusable contract whose downstream implementation is owned elsewhere, or
  whose contract explicitly remains advisory. Closed does not imply
  `implemented` for these documents.
- `superseded`: only when a replacement document is authoritative and linked;
  no audited spec met this condition.

## State classification

| Issue / spec | Previous | Result | Evidence and reason |
|---|---|---|---|
| #10 pure planning interface | accepted | implemented | [PR #29](https://github.com/nunu1733/NunuLauncher/pull/29) merged the public seam and contract tests; concrete planning remains owned by #12. |
| #11 planner harness | accepted | implemented | [PR #30](https://github.com/nunu1733/NunuLauncher/pull/30) merged the black-box fixture/property harness and its 70-test evidence. |
| #12 deterministic planner | accepted | implemented | [PR #36](https://github.com/nunu1733/NunuLauncher/pull/36) merged the planner, public-seam tests, and corrected L-14 evidence. |
| #13 safe application/recovery | accepted | accepted | [PR #37](https://github.com/nunu1733/NunuLauncher/pull/37) completed the research/contract deliverable; the production implementation is the separate #14 / [PR #47](https://github.com/nunu1733/NunuLauncher/pull/47). |
| #24 empty-folder policy | accepted | accepted | [PR #69](https://github.com/nunu1733/NunuLauncher/pull/69) completed the decision record. Deletion remains an explicitly gated future capability, so this policy is not falsely marked as a product implementation. |
| #52 manual full-organization | accepted | implemented | [PR #94](https://github.com/nunu1733/NunuLauncher/pull/94) merged the final vertical slice; [independent audit](./pr-94-manual-organization.md) records GO and exact-head AC evidence. |
| #53 onboarding proposal | accepted | implemented | [PR #95](https://github.com/nunu1733/NunuLauncher/pull/95) merged the implementation; [independent audit](./pr-95-onboarding-organization.md) records GO for AC-001–AC-008. |
| #57 Deck retirement | accepted | implemented | [PR #79](https://github.com/nunu1733/NunuLauncher/pull/79) merged the runtime retirement and migration; [independent audit](./pr-79-deck-runtime-retirement.md) records the green merge gate and resolved blockers. |
| #58 serialized restores | accepted | implemented | [PR #77](https://github.com/nunu1733/NunuLauncher/pull/77) merged the quiesced restore lifecycle and AC-1–AC-7 evidence. |
| #67 diagnostics | accepted | implemented | [PR #82](https://github.com/nunu1733/NunuLauncher/pull/82) merged the journal/export/logcat implementation; residual device evidence was closed by #81 and the supported Settings route by [PR #145](https://github.com/nunu1733/NunuLauncher/pull/145). |
| #99 category overrides | accepted | implemented | [PR #101](https://github.com/nunu1733/NunuLauncher/pull/101) merged the completed Stage B implementation with the accepted Stage A contract and independent audit. |
| #110 upstream patch baseline | accepted | implemented | [PR #112](https://github.com/nunu1733/NunuLauncher/pull/112) merged the reproducible inventory, verification tool, and NFR-010 evidence. |
| #134 grid preset snapshot | accepted | implemented | [PR #143](https://github.com/nunu1733/NunuLauncher/pull/143) merged the production fix; [independent audit](./pr-143-issue134-grid-preset-snapshot.md) records all criteria passed. |
| #153 ZIP restore root cause | accepted | implemented | [PR #190](https://github.com/nunu1733/NunuLauncher/pull/190) merged the diagnosis contract and [PR #188](https://github.com/nunu1733/NunuLauncher/pull/188) merged the focused fix-side verification; the Issue close note records AC-1–AC-6 complete. |
| #155 QSB reservation reload | accepted | implemented | [PR #158](https://github.com/nunu1733/NunuLauncher/pull/158) merged the reservation/recovery implementation; [independent audit](./pr-158-qsb-reservation.md) maps QSB-AC-01–09 to exact-head evidence. |
| #166 recovery tombstone lockout | accepted | implemented | [PR #183](https://github.com/nunu1733/NunuLauncher/pull/183) merged the admission/tombstone behavior; [independent audit](./pr-183-recovery-tombstone-admission.md) records AC-166-01–07 passed. |
| #182 strategy catalog Epic | accepted | implemented | The Issue close note says all criteria and child PRs are complete; the catalog and child implementations are in [PR #207](https://github.com/nunu1733/NunuLauncher/pull/207), [PR #221](https://github.com/nunu1733/NunuLauncher/pull/221), [PR #222](https://github.com/nunu1733/NunuLauncher/pull/222), [PR #223](https://github.com/nunu1733/NunuLauncher/pull/223), [PR #224](https://github.com/nunu1733/NunuLauncher/pull/224), [PR #225](https://github.com/nunu1733/NunuLauncher/pull/225), [PR #226](https://github.com/nunu1733/NunuLauncher/pull/226), [PR #227](https://github.com/nunu1733/NunuLauncher/pull/227), and [PR #241](https://github.com/nunu1733/NunuLauncher/pull/241). |
| #187 ZIP restore recovery artifacts | accepted | implemented | [PR #188](https://github.com/nunu1733/NunuLauncher/pull/188) merged the hard-stop/serialization fix and its independent audit; it also supplies #153 AC-6 evidence. |
| #194 plan preview seam | accepted | implemented | [PR #197](https://github.com/nunu1733/NunuLauncher/pull/197) merged the zero-write preview seam and contract tests. |
| #195 confirmation change list | accepted | implemented | [PR #198](https://github.com/nunu1733/NunuLauncher/pull/198) merged the concrete `PreviewChange` UI and acceptance evidence. |
| #199 UX visual review contract | accepted | accepted | [PR #200](https://github.com/nunu1733/NunuLauncher/pull/200) completed the research/quality contract. Its AC-09 explicitly keeps adoption advisory without a merge gate, so `accepted` distinguishes the delivered contract from product behavior implementation. |
| #201 generated folder naming | accepted | implemented | [PR #202](https://github.com/nunu1733/NunuLauncher/pull/202) merged planner/application/UI naming and instrumentation evidence. |
| #208 placement identity | accepted | implemented | [PR #238](https://github.com/nunu1733/NunuLauncher/pull/238) merged the descriptor identity implementation and related proposal tests. |
| #209 decision action affordance | accepted | implemented | [PR #239](https://github.com/nunu1733/NunuLauncher/pull/239) merged the button/action layout and accessibility changes. |
| #234 destination anchor specificity | accepted | implemented | [PR #243](https://github.com/nunu1733/NunuLauncher/pull/243) merged the resolved-anchor display and its regression evidence. |
| #237 global compact v2 | accepted | implemented | [PR #241](https://github.com/nunu1733/NunuLauncher/pull/241) merged the strategy implementation, recapture/idempotence evidence, and high-risk assessment. |

No audited spec is `superseded`; replacing a contract requires an explicit
replacement link rather than inferring replacement from a later implementation.

Issue #46 is intentionally not a missing feature spec: it is a closed
maintenance/workflow item with no observable product contract. Its acceptance
criteria are represented by the stress workflow and the quality-strategy
entry, so `spec: N/A` is valid with this recorded reason.

## Document and close-procedure changes

- `docs/project/github-workflow.md` is now `accepted`. Its close section makes
  the Worker/Review/Owner/Merge-operator evidence packet authoritative for
  status transitions and distinguishes implementation, research/decision, and
  supersession.
- `docs/engineering/quality-strategy.md` is now `accepted`; the stale
  post-source-import command placeholder was replaced with the checked-in
  Gradle/CI command boundary and a clean-checkout rule for new mandatory gates.
- `CONTEXT.md` now contains domain vocabulary only. Strategy reason identifiers,
  model projections, and correlated reload/token mechanics are defined under
  the Layout Application module in `DESIGN.md`.
- Closed Issues' stale `status:*` labels were removed after the explicit
  repository-wide inventory below (32 closed Issues affected, including the
  13 audited spec/maintenance cases). The close procedure now requires a final
  Issue/spec reconciliation before closure. This audit does not infer
  implementation from labels.

### Closed status-label reconciliation inventory

The removed label was an active-work marker left on an already closed Issue;
the canonical closed state is sufficient, and the active-work label would
misrepresent current work. The list records the pre-change label so the
reconciliation is reconstructable:

| Issue | Removed status label | Reason |
|---:|---|---|
| #251 | `status: review` | Closed state is canonical; active-work label was stale. |
| #247 | `status: review` | Closed state is canonical; active-work label was stale. |
| #230 | `status: needs-spec` | Closed state is canonical; active-work label was stale. |
| #212 | `status: review` | Closed state is canonical; active-work label was stale. |
| #210 | `status: needs-spec` | Closed state is canonical; active-work label was stale. |
| #130 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #129 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #106 | `status: blocked` | Closed state is canonical; active-work label was stale. |
| #105 | `status: blocked` | Closed state is canonical; active-work label was stale. |
| #104 | `status: blocked` | Closed state is canonical; active-work label was stale. |
| #100 | `status: in-progress` | Closed state is canonical; active-work label was stale. |
| #96 | `status: in-progress` | Closed state is canonical; active-work label was stale. |
| #89 | `status: needs-spec` | Closed state is canonical; active-work label was stale. |
| #85 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #84 | `status: in-progress` | Closed state is canonical; active-work label was stale. |
| #83 | `status: review` | Closed state is canonical; active-work label was stale. |
| #81 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #56 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #55 | `status: blocked` | Closed state is canonical; active-work label was stale. |
| #54 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #45 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #44 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #43 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #42 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #41 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #38 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #16 | `status: ready` | Closed state is canonical; active-work label was stale. |
| #15 | `status: blocked` | Closed state is canonical; active-work label was stale. |
| #6 | `status: in-progress` | Closed state is canonical; active-work label was stale. |
| #5 | `status: in-progress` | Closed state is canonical; active-work label was stale. |
| #4 | `status: review` | Closed state is canonical; active-work label was stale. |
| #3 | `status: review` | Closed state is canonical; active-work label was stale. |

The workflow and quality-strategy `Proposed` → `Accepted` transitions were
also explicitly owner-approved for the reviewed PR head in the owner decision
recorded on PR #260; the PR packet links that decision and exact head.

## Verification

```text
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
git diff --check
```

The Android build is intentionally not run: this change audits documents,
spec metadata, and repository-contract wording only; it does not change
production, test, workflow, dependency, permission, migration, or persisted
layout behavior.
