# Issue #247 closing-keyword lifecycle assessment

> Status: proposed
> Audit date: 2026-09-08
> Repository: `nunu1733/NunuLauncher`
> Target: `main`

## Scope

This assessment records the historical closing-keyword inventory and the lifecycle rule for distinguishing intermediate spec/plan/research PRs from a final Issue-closing PR. It covers GitHub Issue/PR state and repository workflow documents; it does not change application behavior.

The inventory was performed against the explicit fork `nunu1733/NunuLauncher` on 2026-09-08. All PRs returned by the repository PR listing (`state=all`, limit 1000) were searched for GitHub closing-keyword forms (`close`, `fix`, `resolve`, including inflections) followed by an Issue number. Docs/spec/plan/research/investigation/assessment/evidence-looking matches were then classified against the linked Issue's exit criteria and later PR history. A title or branch name was not treated as proof of finality.

## Confirmed early-close incident: Issue #230

Issue [#230](https://github.com/nunu1733/NunuLauncher/issues/230) required a spec, plan, implementation, and AC-1–AC-7 evidence. Its comments explicitly recorded that the spec/plan PR was followed by an implementation PR.

| Event | Evidence | Meaning |
|---|---|---|
| Intermediate spec/plan PR | [PR #244](https://github.com/nunu1733/NunuLauncher/pull/244), merged `2026-09-08T02:20:03Z` | Docs-only spec + plan; body said `Closes #230 (実装 PR 完了後)`. |
| Automatic close | [Issue #230](https://github.com/nunu1733/NunuLauncher/issues/230), closed `2026-09-08T02:20:04Z` | The parenthetical did not defer GitHub's close action. |
| Intermediate plan correction | [PR #245](https://github.com/nunu1733/NunuLauncher/pull/245), merged `2026-09-08T03:03:03Z` | Docs-only plan review correction; its body again used `Closes #230` for only a plan step. |
| Final implementation | [PR #246](https://github.com/nunu1733/NunuLauncher/pull/246), merged `2026-09-08T05:24:29Z` | Implemented the accepted behavior and recorded AC-1–AC-7, including emulator evidence. |

The Issue comments provide the causal proof: before the implementation, the Issue described the required next steps as plan review and implementation; after PR #246, the comment recorded that implementation and all AC evidence were complete. Therefore #244 is a confirmed early-close violation, and #245 is the same incorrect relationship on a later intermediate PR.

## Inventory findings and controls

No additional confirmed case was found in the inspected historical matches where an intermediate PR closed an Issue whose required implementation or exit evidence was still pending. The following controls were checked because their wording can look similar:

| Case | Classification | Evidence |
|---|---|---|
| Spec/plan stage followed by implementation | Correct lifecycle control | [PR #35](https://github.com/nunu1733/NunuLauncher/pull/35) explicitly says the Stage A PR does not close #12; [PR #36](https://github.com/nunu1733/NunuLauncher/pull/36) is the implementation PR that closes #12. |
| Final investigation record | Valid final close | [PR #196](https://github.com/nunu1733/NunuLauncher/pull/196) closes #192, whose deliverable is the investigation/design record itself; no later implementation is required by that Issue. |
| Final assessment/evidence record | Valid final close | [PR #173](https://github.com/nunu1733/NunuLauncher/pull/173) closes the investigation Issue #171; [PR #151](https://github.com/nunu1733/NunuLauncher/pull/151) closes evidence follow-ups #105/#106 while explicitly using `Refs #104` for the related Issue. |
| Investigation related to a future feature | Correct non-close relationship | [PR #236](https://github.com/nunu1733/NunuLauncher/pull/236) closes the investigation Issue #212 and says it follows up to #234; the later feature implementation is [PR #243](https://github.com/nunu1733/NunuLauncher/pull/243). |
| Duplicate/verification PR | Not an early-close implementation claim | [PR #50](https://github.com/nunu1733/NunuLauncher/pull/50) was closed without merge; the merged evidence is in [PR #51](https://github.com/nunu1733/NunuLauncher/pull/51). |

The inventory therefore distinguishes the Issue's own final research/evidence deliverable from a spec/plan that explicitly hands work to a later implementation. It does not infer that every closed Issue was incorrectly handled.

## Worker choice examples

| PR scope | Required Issue relationship |
|---|---|
| Accepted spec or implementation plan only, with implementation still listed as a next step | `Refs #230` |
| Final implementation with all Issue acceptance criteria and evidence | `Closes #230` |
| Research/assessment Issue whose requested output is the investigation record | `Closes #192` or `Closes #171` |
| One PR completes one linked Issue but only contributes evidence to another | `Closes #105`, `Closes #106`, and `Refs #104` |
| One PR mentions several Issues but leaves one exit condition pending | Close only the completed Issue; use `Refs` for the pending Issue |

## Adopted rule

- Intermediate spec, plan, research, investigation, evidence, and review-correction PRs use `Refs #<issue>` and keep the Issue open.
- A final implementation or final Issue-scoped research/evidence PR uses `Closes #<issue>` only after its Issue exit criteria are satisfied.
- A parenthetical qualifier cannot make a closing keyword conditional.
- Each linked Issue is judged independently; title, branch name, and a general “follow-up” phrase are not substitutes for exit-criteria evidence.
- The PR template requires a closing-keyword search and a final/intermediate choice so the review catches accidental auto-close before merge.

## Verification evidence

- Repository contract: `python3 tools/repo-contract/validate_repo_contract.py` — PASS (`repository contract OK`).
- Repository contract self-test: `python3 tools/repo-contract/test_validate_repo_contract.py` — PASS (13 tests OK).
- Diff whitespace: `git diff --check` — PASS (no output).
- Historical lifecycle evidence: PR #244 closed #230 before PR #246; PR #35 left #12 open until PR #36.
