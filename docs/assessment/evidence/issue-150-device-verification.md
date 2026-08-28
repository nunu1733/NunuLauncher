# Issue #150 device verification evidence (redacted)

> Status: recorded on branch `codex/issue-150-a7-verification-diagnosis`
> Tested head/build: `Lawnchair.15.Dev.(44b4bad).github.{debug,release}.apk`, built at commit `44b4bad0c2` (the reviewed implementation head; later commits in this PR touch only `tests/` and `docs/`, not production sources)
> Prior evidence run at `b9f7e969cd` (identical production behavior) is preserved in git history of this file
> Date: 2026-08-28
> Environment: AVD `nunu_qpr2_api36_1` (Pixel 6 definition, Google APIs arm64-v8a), Android 16 / API 36, 1080x2400 @ 420 dpi, 4x5 grid — the Issue #150 environment
> Install state for each run: fresh install (`pm clear`) of the respective APK, set as HOME

## Causal chain (final)

1. **Completion ordering** (fixed in `66a3e8d02f`): the organizer reload completion fired inside `bindWorkspace`, before the exact LoaderTask committed/closed its transaction. Proven by the deterministic oracle `OrganizerReloadCompletionOrderingTest`: red pre-fix ("Organizer completion fired before the loader transaction boundary"), green post-fix. The oracle additionally asserts a **causal hold probe**: while the boundary barrier is held, the completion must not fire even given 3 seconds of scheduling time, so the oracle fails whenever the hold is broken and never passes merely because the scheduler was slow.
2. **QSB-reservation overlap deletion** (fixed by Issue #155 / PR #158): the planned folder at first-screen (0,0) collided with the QSB reservation in `LoaderCursor.checkItemPlacement` and was deleted during the correlated reload. On-device DB poller captured the row vanishing ~9 ms after APPLY_COMMITTED.
3. **MODEL_EXECUTOR starvation** (fixed by Issue #156 / PR #157): `HotseatRestoreHelper.restoreBackup` blocked the single model thread on `acquireBlockingQuietly(MODEL_WRITER)` while the organizer lease was held; the recovery LoaderTask queued behind it until the 10 s adapter timeout. Thread dump at commit+~7 s showed the loader blocked in `HotseatRestoreHelper → ModelDbController.newTransaction`; terminal event always landed at APPLY_COMMITTED + ~10.1 s.
4. **Canonical item order divergence** (fixed in `b9f7e969cd`): the planner emits intended items in ItemId byte order (`placements.sortedBy { it.item }`) while the DB capture emitted favorites row-enumeration order (numeric `_ID ASC`); `LayoutState` equality is order-sensitive and the orders diverge at ≥10 rows, which every real workspace exceeds. Fix: `RowManifestCodec.capture` emits canonical items in ItemId byte order; `manifest.rows` keeps row-enumeration order. Regression: `captureOrdersCanonicalItemsByItemIdByteOrderNotRowEnumeration` (`RealAdapterRowMatrixInstrumentationTest`).
5. **Post-close supersession delivery** (fixed in `44b4bad0c2`, re-review P1): a request whose loader had closed its transaction but whose queued notification had not run lost its terminal signal when a newer request replaced the token. `forceReloadForOrganizer` now terminalizes the leftover token (`SUPERSEDED`) exactly once; the completion is explicitly queued via `MODEL_EXECUTOR.post` (re-review P2). Regression: `OrganizerReloadSupersessionTest#terminalSignalSurvivesReplacementDuringPostCloseDeliveryGap` (red pre-fix, green post-fix).

## AC-150-04 — default workspace reaches A8 at `44b4bad0c2`

Manual flow (fresh install as HOME → Settings → Home screen → Organize home layout → Review organization → Apply reviewed organization):

- **Debug** (`app.lawnchair.debug`, journal read from `files/organizer_diagnostics/organizer_diagnostics.journal`):
  ```text
  1 RUN_STARTED
  2 CAPTURED
  3 PLANNED
  4 PREVIEWED
  5 USER_CONFIRMED
  6 CHECKPOINTED        stage=A4
  7 APPLY_COMMITTED     stage=A6
  8 APPLY_VERIFIED      stage=A8
  ```
  UI: "Organization was applied and verified." Favorites after apply: 15 rows, folder moved to first-screen (0,1) — outside the QSB reservation row.
- **Release/minified** (`app.lawnchair`): UI: "Organization was applied and verified." Logcat: zero `OrganizerDiag` warnings (failure-only contract intact). Favorites identical to the debug post-apply state (15 rows; folder at screen 0 cell (0,1); screen-1 rows at (2,0),(1,0),(0,0)).
- **Release journal provenance**: exported through the supported Settings → Home screen → Organizer diagnostics → "Export organizer diagnostics" surface (privacy-safe export), pulled from Downloads, and verified to contain the same terminal phases:
  ```text
  6 CHECKPOINTED        stage=A4
  7 APPLY_COMMITTED     stage=A6
  8 APPLY_VERIFIED      stage=A8
  ```

## AC-150-05 — explicit recovery correlation at `44b4bad0c2`

After the verified apply of the same run ("Restore the previous layout" → preview → "Restore saved layout" → "The saved layout was restored."):

- **Debug** journal:
  ```text
  9 RECOVERY_REQUESTED   recovery.pointId=6a38b80f…  recovery.pointOriginRunId=f8cdfb7c…
  10 RECOVERY_RESTORED   recovery.pointId=6a38b80f…  recovery.pointOriginRunId=f8cdfb7c…
  ```
  `pointOriginRunId` equals the verified apply's run ID (`f8cdfb7c…`); `pointId` equals the checkpoint (`6a38b80f…`). Post-restore `favorites`: 15 rows, exact pre-apply placement.
- **Release** (exported journal, same approved surface):
  ```text
  9 RECOVERY_REQUESTED   recovery.pointOriginRunId=22c76597…
  10 RECOVERY_RESTORED   recovery.pointId=e0829324…  recovery.pointOriginRunId=22c76597…
  ```
  Same correlation: `pointOriginRunId` equals the verified apply's run ID; `pointId` matches the checkpoint.

## Test surfaces executed

- `./gradlew spotlessCheck` — pass
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — pass
- Focused connected run on `nunu_qpr2_api36_1`: `OrganizerReloadCompletionOrderingTest` (with the strengthened causal-hold probe; red pre-fix verified by checking out the pre-fix `src/` sources), `OrganizerReloadSupersessionTest` (7/7 incl. the post-close supersession regression; red pre-fix verified via stash), `HotseatRestoreAdmissionTest`, `RealAdapterRowMatrixInstrumentationTest`, `ManualOrganizationProductionE2EInstrumentationTest` — 31/31 pass
- `./gradlew assembleLawnWithQuickstepGithubDebug` / `...Release` — pass
- `python3 tools/repo-contract/validate_repo_contract.py`, `test_validate_repo_contract.py`, `test_validate_high_risk_evidence.py` — pass

## Outstanding for merge gate

- `CI / final-status` on the exact final head (rerun after the audit record and oracle strengthening are pushed).
- Independent re-audit against the final head (the first audit record at `44b4bad0c2` is preserved at `docs/assessment/pr-160-manual-organization-a7-verification.md`).
- Issue #153 (ZIP `NotReady` diagnostic code observability) remains the separately owned follow-up (AC-150-06).
