# Issue #249 main merge gate assessment

> Status: implemented
> Audit date: 2026-09-08
> Repository: `nunu1733/NunuLauncher`
> Target: `main`

## Scope

This assessment records the GitHub-side branch protection change for Issue #249. It does not change application code, CI workflow files, or the high-risk audit policy.

## Before configuration

Read-only API checks against the explicit fork target returned:

- `GET /repos/nunu1733/NunuLauncher/branches/main/protection`: HTTP 404, `Branch not protected`.
- `GET /repos/nunu1733/NunuLauncher/rulesets`: `[]`.

The repository was public and the authenticated actor had repository administration access. No repository-wide `gh` default was changed.

## Applied configuration

The branch protection API was updated with the following contract:

| Setting | Value | Reason |
|---|---|---|
| Required pull request | enabled | Direct pushes to `main` are not the delivery path. |
| Required approving reviews | `0` | Does not require an unavailable second GitHub user in solo maintenance. |
| Required checks | `final-status`, `high-risk-evidence` | Aggregate CI and independent high-risk evidence gate. |
| Check app identity | GitHub Actions, app id `15368` | Verified through the Checks API on PR #254. |
| Strict checks | enabled | Merge only from an up-to-date PR branch. |
| Enforce admins | enabled | No administrator bypass is assumed. |
| Conversation resolution | enabled | Review threads must be resolved before merge. |
| Force-push/delete | disabled | Protect the main history and branch. |
| Linear history | disabled | Preserve the repository's merge-commit workflow. |
| Push restrictions/bypass actors | none | No unreviewed bypass path is configured. |

## Read-back evidence

The applied state was read back from:

- [Branch protection API](https://api.github.com/repos/nunu1733/NunuLauncher/branches/main/protection)
- [Repository rulesets API](https://api.github.com/repos/nunu1733/NunuLauncher/rulesets)
- [PR #254](https://github.com/nunu1733/NunuLauncher/pull/254), a docs-only low-risk PR whose `final-status` and `high-risk-evidence` checks passed before merge.
- [CI run for PR #254](https://github.com/nunu1733/NunuLauncher/actions/runs/34192872985)
- [High-risk gate run for PR #254](https://github.com/nunu1733/NunuLauncher/actions/runs/34192872975)

The existing high-risk gate demonstration is [PR #64](https://github.com/nunu1733/NunuLauncher/pull/64): missing audit evidence failed the gate and adding the evidence made it pass, as recorded in [github-workflow.md](../project/github-workflow.md).

## Recovery

The pre-change state can be restored by an administrator with the explicit fork target:

```bash
gh api --method DELETE repos/nunu1733/NunuLauncher/branches/main/protection
gh api repos/nunu1733/NunuLauncher/branches/main/protection
gh api repos/nunu1733/NunuLauncher/rulesets
```

Expected recovery read-back is HTTP 404 for branch protection and an empty ruleset array. This procedure is for configuration failure recovery only; it must not be used to bypass a required check for a normal merge.

## Verification

- `gh api repos/nunu1733/NunuLauncher/branches/main/protection` returned the configured required checks, app ids, admin enforcement, and force-push/deletion settings.
- `gh api repos/nunu1733/NunuLauncher/rulesets` returned no rulesets before and after the change; branch protection is the selected mechanism.
- The API response identified `final-status` and `high-risk-evidence` as required contexts with app id `15368`.
