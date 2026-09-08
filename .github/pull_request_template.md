## Issue relationship

Choose one relationship for each Issue and replace the placeholders below:

- Final PR that satisfies the Issue's exit criteria: `Closes #<issue>`
- Intermediate spec/plan/research/investigation/evidence PR: `Refs #<issue>`

Do not use `Closes #<issue> (after implementation)` or a similar parenthetical reservation. GitHub still closes the Issue when this PR merges.

## Outcome

Describe the observable outcome and the smallest implementation shape that delivers it.

Spec: `specs/<issue>-<slug>/spec.md`

Requirements: FR-___, NFR-___

## Acceptance evidence

| Acceptance criterion | Evidence |
|---|---|
| AC-1 | |

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
