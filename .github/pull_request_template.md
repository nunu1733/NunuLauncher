## Issue relationship

<!--
For each linked Issue, replace the single line below with exactly one relationship:
- final PR that satisfies the Issue's exit criteria: `Closes #N`
- intermediate spec/plan/research/investigation/evidence PR: `Refs #N`
Never leave both relationships for the same Issue. Do not use a parenthetical such as
`Closes #N (after implementation)`; GitHub still closes the Issue when this PR merges.
-->
Issue relationship:

## Outcome

Describe the observable outcome and the smallest implementation shape that delivers it.

Spec: `specs/<issue>-<slug>/spec.md`

Requirements: FR-___, NFR-___

## Acceptance evidence

| Acceptance criterion | Evidence |
|---|---|
| AC-1 | |

## Review / handoff packet

- Issue and all comments:
- Scope type (`feature` / `bug` / `research/decision` / `maintenance/docs-only`):
- Accepted spec + exact commit, or bug oracle as either repository-tracked path + exact commit **or** Issue/comment permalink + retrieved-at UTC timestamp + owner acceptance link, or `N/A` with reason:
- Plan + exact revision, or `N/A` with reason:
- Base SHA / head SHA:
- Diff compare URL and `git diff --stat <base>..<head>` result:
- Diff boundary: full diff inspected, or verified path/range list for a partial/pasted diff; all other scope is unverified and partial review is not whole-scope final approval:
- Exact commands/results and CI/check URLs:
- Unverified scope or runtime constraints:
- Review recommendation / conditions / re-review head:
- Owner decision / conditional approval closure:
- Merge operator check / next step:

## Risk review

- Risk labels applied to this PR (`risk: layout-data`, `risk: migration`), or why none:
- Layout/recovery impact:
- Migration/backup/restore impact:
- Privacy/permission/network impact:
- Lawnchair/Launcher3 upstream files changed and why:
- Accessibility/localization impact:

## Verification

List exact commands/environments and results. Do not write only “tests pass.”

```text
<command> -> <result>
```

Not run and why:

## Closing keyword review

- [ ] I searched this PR body for `close`/`fix`/`resolve` keywords and checked every linked Issue against this PR's actual exit criteria.
- [ ] Intermediate Issues use `Refs #<issue>` and remain open for the next PR; only completed Issues use a closing keyword.

## Documentation

- [ ] Spec status and history updated
- [ ] CONTEXT/DESIGN/ADR updated when applicable
- [ ] AGENTS updated if verified commands or workflow changed
- [ ] Remaining work moved to linked Issues
