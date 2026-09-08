# Issue #251 Worker/Review handoff assessment

> Status: implemented
> Audit date: 2026-09-08
> Repository: `nunu1733/NunuLauncher`
> Target: `main`

## Scope

This assessment records the shared execution contract for a Worker, an external Review session, the owner, and the merge operator. It is a maintenance/documentation change; it does not assert that any model runtime has been benchmarked or that the Codex session can invoke Zcode or ChatGPT.

## Findings and decisions

- The repository had a UX-only `.codex/agents/ux-observer.toml` / `ux-critic.toml` pair but no general Worker/Review packet. The UX runtime files remain unchanged and are not treated as a general approval mechanism.
- The workflow now records the stated operating premise: GLM-5.3-flash (Zcode) or GPT-5.6-Luna (Codex/xhigh) for Worker, and GPT-5.6-Sol (ChatGPT/High) for Review. Astra is not a required model.
- Worker output, Review recommendation, owner product/final decision, and merge execution are separate responsibilities. A different model name alone is not independent evidence.
- A `status: accepted` spec is tied to an exact commit. A repository-tracked bug oracle is tied to its path and exact commit; an Issue/comment bug oracle is tied to its permalink, retrieved-at UTC timestamp, and owner acceptance link. A plan is tied to an exact revision. Maintenance/docs-only work records why spec/plan are `N/A`.
- Conditional approval is not final approval. A new substantive head, changed exit criteria, changed risk, or unresolved review condition invalidates the prior approval and requires re-review.
- A review of a partial or pasted diff records every verified path/range and treats all other scope as unverified; it cannot be a whole-scope final approval.
- Existing high-risk independent audit requirements remain additive and are not replaced by this general packet.

## Review / handoff packet example

The worked example is the historical #230 delivery:

- Issue and all comments: [Issue #230](https://github.com/nunu1733/NunuLauncher/issues/230); retrieved at `2026-09-08T09:58:36Z`; state=`closed`; labels=`type: bug`, `status: needs-spec`.
- Scope type: `bug`.
- Accepted spec revision: `specs/230-restore-confirmation-target/spec.md` at `436f2a7a54d2ae1346806772ce0fbd3e7827ef76`.
- Plan revision: PR #245 plan-review fix at `f69251ad55493c1acc60ffd687e4c18268ae3eaa`; the earlier `0f3d1d3a8f2562be5f21ea23f3b4d2ccc53cd463` revision is superseded.
- Implementation base/head: `d36b109e989d49fd218bc13f3eb7c0e053a16709` → `73173d2e829438447e3a0230b4af0959c18e9661`.
- Exact diff: [base/head comparison](https://github.com/nunu1733/NunuLauncher/compare/d36b109e989d49fd218bc13f3eb7c0e053a16709...73173d2e829438447e3a0230b4af0959c18e9661).
- Diff stat: `17 files changed, 297 insertions(+), 14 deletions(-)`; nine of the changed files are emulator evidence images.
- CI evidence: [PR #246 CI run](https://github.com/nunu1733/NunuLauncher/actions/runs/34189752934), with all source and final jobs successful.
- Review/owner links: [implementation review](https://github.com/nunu1733/NunuLauncher/pull/246#pullrequestreview-5137097642), [owner completion record](https://github.com/nunu1733/NunuLauncher/issues/230#issuecomment-5579746821).
- Unverified scope: the current Codex session did not invoke Zcode or ChatGPT; the packet must not describe those runtimes as having executed the commands.

## Runtime verification boundary

No cross-runtime handoff was performed in this repository session. This is recorded as an explicit unverified constraint, satisfying the Issue's requirement not to treat the audit as multi-model runtime evidence. A future handoff must copy the packet, preserve exact revisions, and report any command or evidence unavailable to the receiving session as unverified.

## Verification

- `python3 tools/repo-contract/validate_repo_contract.py` — PASS.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — PASS (13 tests on this change).
- `git diff --check` — PASS on the final head.
- Android build and Gradle tests are not run for this docs-only change; the PR records that limitation.
