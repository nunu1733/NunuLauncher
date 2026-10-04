# Independent audit: PR #261 bugreport crash report persistence and pre-handler isolation

> Status: accepted (verdict below; not a `risk: layout-data`/`risk: migration` gate record — the owner requested this independent audit)
> Audit date: 2026-09-09

- Auditor: Independent audit session (general-purpose subagent), not the implementing session
- PR: https://github.com/nunu1733/NunuLauncher/pull/261 (state OPEN, **draft**, base `main`, head branch `issue-242-bugreport-crash-evidence`)
- Head SHA: `2f16ab50e66d7a74a01360f779728994a124fbad` (verified locally via `git rev-parse HEAD` on a clean tree, and remotely via `gh pr view 261 --json headRefOid` — both return the same SHA)
- CI status at audit time: **no runs exist on this head** (see Findings 1)
- Criteria: specs/242-bugreport-crash-evidence/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 (spec status `accepted`, owner-approved 2026-09-09)
- Repository check before any `gh` use: `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher`, default branch `main`

## Scope

Full diff `git diff main...HEAD` is 8 files (+704/−11): `LawnchairBugReporter.kt` (the only production change), 3 new test files under `tests/unit/app/lawnchair/bugreport/`, `.github/workflows/ci.yml` (1 line), `docs/engineering/quality-strategy.md` (2 hunks), spec and plan docs.

### Production diff review (independent reading, not trusting the PR body)

1. **Filename (AC-1)**: `Report.fileName` initializer is now `buildReportFileName(appName, Date())`; the extracted top-level function is `"$appName bug report ${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)}"`. Fixed pattern + explicit `Locale.US` → no default-locale dependence, no `/`, portable charset, header stays human-readable. The old `SimpleDateFormat.getDateTimeInstance()` call is fully removed from the file.
2. **save degradation (AC-4)**: `writeReportFile` wraps `mkdirs`/`createNewFile`/`writeText` in a try-expression returning `file` on success, `null` on `createNewFile() == false`, and `null` on `catch (IOException)`. No other exception type is caught; nothing rethrows. `save()` is a thin shell: `writeReportFile(File(logsFolder, String.format("%x", id)), fileName, contents)`.
3. **fileName wiring (review-flagged, unit-test-undetectable)**: verified in the diff that `Date()` appears exactly once in the class — the `fileName` property initializer. `generateBugReport()` builds `contentsWithHeader` from that same property (line 84 at head) and `save()` passes the same property to `writeReportFile` (line 97). No fresh `buildReportFileName(appName, Date())` regeneration exists anywhere; header and on-disk filename cannot diverge.
4. **pre-handler isolation (AC-3)**: `dispatchUncaughtException` is `try { try { preHandler() } catch (t) { onPreHandlerFailure(t) } } catch (ignored: Throwable) { } finally { defaultHandler?.uncaughtException(thread, throwable) }`. Delegation is in `finally`, so it executes exactly once with the original `(thread, throwable)` on all three paths (preHandler ok / preHandler throws / logger throws); a throwing `onPreHandlerFailure` is swallowed (best-effort recording). The `init` shell passes `preHandler = { sendNotification(throwable) }` and `onPreHandlerFailure = { Log.w(TAG, ...) }` — android `Log` stays outside the extracted function (JVM-testable).
5. **Retention/id non-regression (AC-5)**: hunks touch only imports, the handler lambda, the `fileName` initializer, `save()`'s body, and the companion/TAG + appended functions. `removeDismissedLogs()`, `String.format("%x", id)` dir naming, `id = contents.hashCode()` (computed from pre-header `contents`), `sendNotification`, and `BugReportReceiver` are zero-diff (`git diff main...HEAD -- lawnchair/src/app/lawnchair/bugreport/` contains no other file).

### Test claim verification

The 13 new JVM tests (4 + 4 + 5) map to real, non-vacuous assertions: exact-string header assertion (`"Lawnchair bug report 2026-09-07_19-09-17"`), per-character portable-charset assertion across a 5-locale matrix, determinism under two explicit timezones, path-structure + content-equality save assertions, exception-leak assertions via catch-and-assert-null, and exactly-once delegation asserted by list equality (`listOf(thread to original)`) plus referential identity (`===`) on the original throwable. `save()` failure injection follows the spec's corrected method (`dest` pre-created as a regular file), and the id-collision case separately covers `createNewFile() == false`.

## Criteria check

| Accepted criterion | Independent check | Result |
|---|---|---|
| AC-1 (filename safety, locale independence) | `BugReportFileNameTest`: ja no-separator test (reproduces the #242 red), 5-locale matrix (ja/en_US/de/fi/ar) with `/`-free + portable-charset + determinism assertions, exact header string. Red at the extraction commit (3 of 4 fail — see red evidence) → all green at head. | Pass |
| AC-2 (save success) | `ReportFileSaveTest.successfulSaveCreatesTheFileUnderTheHexIdDirectory`: asserts `saved?.parentFile == dest`, filename `<fileName>.txt`, and `readText() == contents`. | Pass |
| AC-3 (pre-handler failure isolation) | `CrashPreHandlerIsolationTest` 5 tests: delegation exactly once with original `(thread, throwable)` on preHandler-ok / preHandler-throw / logger-throw paths, failure handed to `onPreHandlerFailure`, no leak with null default handler, identity (`===`) preserved. Diff review confirms the `finally` guarantee. | Pass |
| AC-4 (save failure degradation) | Unwritable dest (dest pre-created as regular file → child creation throws IOException) → `assertNull`, no leak; id collision (pre-existing target path → `createNewFile()` false) → `assertNull` with the pre-existing file left empty; plus the createNewFile-false-not-throw fixture (owner-review Blocker 2). | Pass |
| AC-5 (retention/id contract unchanged) | Diff review: only `LawnchairBugReporter.kt` changed in the bugreport package; `removeDismissedLogs`, `%x` dir naming, `id = contents.hashCode()`, notification/upload paths zero functional diff. Path-structure claim covered by the AC-2 save test. | Pass |
| AC-6 (organizer non-regression) | CI gate has not run (Finding 1). Local equivalent executed by this audit: `--tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*'` → 89 classes, 964 tests, 0 failures/errors/skipped (JUnit XML summed independently). | Pass (local only; CI pending) |
| AC-7 (docs and evidence) | Spec status/history updated (`accepted`, full review history); plan checklist records red/green and verification; PR body has per-AC evidence table. **Incomplete**: CI run URLs absent because no CI run exists (Finding 1); plan checklist final item unchecked. | Partial |

## Executed test surface (all run by this audit session)

At audited head `2f16ab50e66d7a74a01360f779728994a124fbad` (JDK 21, Android SDK via `/opt/homebrew/share/android-commandline-tools`):

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.bugreport.*'
# BUILD SUCCESSFUL in 23s (386 actionable tasks: 17 executed, 369 up-to-date)
# JUnit XML (build/test-results/testLawnWithQuickstepGithubDebugUnitTest/), summed independently:
#   BugReportFileNameTest: 4 tests, 0 failures   CrashPreHandlerIsolationTest: 5 tests, 0 failures
#   ReportFileSaveTest: 4 tests, 0 failures      → 13 tests, 0 failures, 0 errors, 0 skipped

./gradlew spotlessCheck --rerun-tasks
# BUILD SUCCESSFUL in 9s (5 actionable tasks: 5 executed) — forced rerun, see Finding 3

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.ui.preferences.navigation.*'
# BUILD SUCCESSFUL; 89 classes, 964 tests, 0 failures, 0 errors, 0 skipped (AC-6 local equivalent)
```

## Red-evidence verification (commit `9501bf4d4d`)

Executed in a throwaway worktree (`git worktree add /tmp/audit-242 9501bf4d4d`, submodule initialized, `local.properties` copied; removed afterwards):

- The extraction commit **does not compile as committed**: `BugReportFileNameTest.kt:30:30 Unresolved reference 'Date'` — the file lacks `import java.util.Date` (added in the fix commit; see Finding 2). After applying a compile-only import repair in the worktree (no behavioral change), the run produced:
- `13 tests completed, 8 failed` — exactly the claimed 8/13 split. Failed: 3 filename tests (ja separator, locale matrix, header ComparisonFailure), 4 isolation tests (null-handler leak, logger-throw IOException escape, pre-handler-failure IOException escape, identity RuntimeException escape), 1 save test (unwritable-dest IOException escape). Passed (5): filename determinism, save success, id collision, createNewFile-false fixture, successful pre-handler delegation. This matches the spec's red expectations test-by-test: the failures are precisely the behaviors fixed in `cafc999b46` (locale-safe filename, IOException→null degradation, two-tier dispatch isolation), and the passing 5 are behavior-preserving under the old code.

## CI status at audit time (2026-09-09)

```bash
gh pr checks 261 -R nunu1733/NunuLauncher
# "no checks reported on the 'issue-242-bugreport-crash-evidence' branch"
gh api repos/nunu1733/NunuLauncher/commits/2f16ab50e66d7a74a01360f779728994a124fbad/check-runs
# empty
gh api "repos/nunu1733/NunuLauncher/actions/runs?head_sha=2f16ab50e6..." → 0 runs
gh api "repos/nunu1733/NunuLauncher/actions/runs?branch=issue-242-bugreport-crash-evidence" → 0 runs
```

The repository's Actions itself is healthy (latest `main` CI run 34238910803, success, 2026-09-08), so this is a branch/PR-state issue, not an infra outage. The PR is in draft.

## Findings

1. **Medium (evidence/process, no code impact)** — No CI run exists anywhere on the audited head: no check runs, no workflow runs for the SHA or the branch. AC-6's CI evidence and AC-7's PR-recorded run URL are therefore absent (PR body says "CI run URL は push 後に追記する"; plan checklist final item unchecked). The AGENTS.md merge requirement that CI actually succeed on the verification commit is not yet satisfiable. Remedy: mark the PR ready for review / push to trigger CI, then record the run URL(s) in the PR body. Local equivalents of every gate filter were run green by this audit, but per AGENTS.md they do not substitute for the CI record.
2. **Low (commit hygiene, fixed at head)** — Extraction commit `9501bf4d4d` does not compile as committed (missing `import java.util.Date` in `BugReportFileNameTest.kt`; introduced red test references `Date` only via the fix commit's import). This breaks bisectability of that intermediate commit and required a compile-only repair to verify the red evidence. Head is unaffected; no action required beyond noting the intermediate-commit red evidence was verified with that one-line repair.
3. **Info** — The first `spotlessCheck` invocation in this session reported all tasks up-to-date from the configuration cache; a genuine rerun was forced with `--rerun-tasks` (5 tasks executed) and passed.
4. **Info** — PR body uses `Fixes #242` (valid GitHub closing keyword) with a trailing `Refs #242`; AGENTS.md names the literal token `Closes #<issue>` for the final PR. Semantically equivalent for auto-close; the owner may want the literal token for contract consistency.

## Verdict

All shipped-code claims verified independently: implementation matches the spec contract, the fileName wiring is correct in the diff, retention/id semantics are zero-diff, 13/13 bugreport tests green at head, spotlessCheck green, red evidence 8/13 reproduced at the extraction commit (with the documented compile-only repair). Findings 1–2 are evidence-state/process items that must be closed before merge (CI run on head + PR evidence), not code defects.

Audit verdict: PASS with findings
