# Issue #150 device verification evidence (redacted)

> Status: recorded on branch `codex/issue-150-a7-verification-diagnosis`
> Tested head: `b9f7e969cd` (fix commit; merged base `0623b4d0aa` on `main@fd3dad799d` including Issue #155/#156)
> Date: 2026-08-27
> Environment: AVD `nunu_qpr2_api36_1` (Pixel 6 definition, Google APIs arm64-v8a), Android 16 / API 36, 1080x2400 @ 420 dpi, 4x5 grid — the Issue #150 environment.
> Builds: `Lawnchair.15.Dev.(b9f7e96).github.debug.apk` (`app.lawnchair.debug`) and `Lawnchair.15.Dev.(b9f7e96).github.release.apk` (`app.lawnchair`), both installed fresh (`pm clear`) and set as HOME before each run.

## Causal chain (final)

1. **Completion ordering** (fixed in `66a3e8d02f`): the organizer reload completion fired inside `bindWorkspace`, before the exact LoaderTask committed/closed its transaction. Proven by the deterministic oracle `OrganizerReloadCompletionOrderingTest`: red pre-fix ("Organizer completion fired before the loader transaction boundary"), green post-fix.
2. **QSB-reservation overlap deletion** (fixed by Issue #155 / PR #158): the planned folder at first-screen (0,0) collided with the QSB reservation in `LoaderCursor.checkItemPlacement` and was deleted during the correlated reload. On-device DB poller captured the row vanishing ~9 ms after APPLY_COMMITTED.
3. **MODEL_EXECUTOR starvation** (fixed by Issue #156 / PR #157): `HotseatRestoreHelper.restoreBackup` blocked the single model thread on `acquireBlockingQuietly(MODEL_WRITER)` while the organizer lease was held; the recovery LoaderTask queued behind it until the 10 s adapter timeout. Thread dump at commit+~7 s showed the loader blocked in `HotseatRestoreHelper → ModelDbController.newTransaction`; terminal event always landed at APPLY_COMMITTED + ~10.1 s.
4. **Canonical item order divergence** (fixed in `b9f7e969cd`): after 2–3 were fixed, a manual apply still ended `APPLY_RECOVERY_FAILED (VERIFICATION_FAILED)` although the DB recapture matched the intended manifest byte-exactly (`manifestEqual=true resourcesEqual=true`, DB states captured via on-device file-hash watcher showed no mutation). Temporary diagnostics showed the mismatch was `LayoutState` item order only: the planner emits intended items in ItemId byte order (`placements.sortedBy { it.item }`), the capture emitted favorites row-enumeration order (numeric `_ID ASC`). Intended order was `1,10,11,…,16,2,…,9`; recapture order was `1,2,3,…,16`. `LayoutState` equality is order-sensitive; the orders diverge only with ten or more rows, so all single-digit-id fixtures and unit tests passed while every real workspace failed. Fix: `RowManifestCodec.capture` now emits canonical items in ItemId byte order; `manifest.rows` keeps row-enumeration order. Regression: `captureOrdersCanonicalItemsByItemIdByteOrderNotRowEnumeration` (`RealAdapterRowMatrixInstrumentationTest`).

## AC-150-04 — default workspace reaches A8

Manual flow (fresh install as HOME → Settings → Home screen → Organize home layout → Review organization → Apply reviewed organization), tested commit `b9f7e969cd`:

- **Debug** (`app.lawnchair.debug`): UI reached "Organization was applied and verified." Redacted journal sequence:
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
  Favorites after apply: 15 rows, folder moved to first-screen (0,1) — outside the QSB reservation row.
- **Release/minified** (`app.lawnchair`): UI reached "Organization was applied and verified." No `OrganizerDiag` line in logcat (failure-only contract intact). Favorites identical to the debug post-apply state (15 rows; folder at screen 0 cell (0,1); screen-1 rows at (2,0),(1,0),(0,0)).

## AC-150-05 — explicit recovery correlation

After the verified apply (same run, debug `b9f7e969cd`): "Restore the previous layout" → preview → "Restore saved layout" → "The saved layout was restored." Redacted journal:

```text
 9 RECOVERY_REQUESTED   recovery.pointId=48e52f39…  recovery.pointOriginRunId=d194ed1f…
10 RECOVERY_RESTORED    recovery.pointId=48e52f39…  recovery.pointOriginRunId=d194ed1f…
```

- `pointOriginRunId` equals the run ID of the verified apply (`d194ed1f…`) — non-null and matching.
- `pointId` equals the checkpoint's `48e52f39…`.
- Post-restore `favorites`: 15 rows, exact pre-apply state (folder back at screen 0 cell (0,4)); recovery record lifecycle `RESTORED`.

The same correlation (`RECOVERY_REQUESTED`/`RECOVERY_RESTORED` with matching `pointOriginRunId`) was also observed on the earlier diagnostic build (content-identical fix) before the final labeled rerun.

## Test surfaces executed at the audited head

- `./gradlew spotlessCheck` — pass
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — pass
- `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest` focused classes — `OrganizerReloadCompletionOrderingTest`, `OrganizerReloadSupersessionTest`, `HotseatRestoreAdmissionTest`, `RealAdapterRowMatrixInstrumentationTest` (incl. new ordering regression) — pass; `ManualOrganizationProductionE2EInstrumentationTest` passed in the dedicated clean-AVD configuration (see note below)
- `./gradlew assembleLawnWithQuickstepGithubDebug` / `...Release` — pass
- `python3 tools/repo-contract/validate_repo_contract.py`, `test_validate_repo_contract.py`, `test_validate_high_risk_evidence.py` — pass

Note: a combined focused run on two simultaneously attached AVDs showed the E2E suite failing at its preference-toggle setUp ("Must be called from main thread") on one device; the suite passed when run in the CI-equivalent single clean-emulator configuration. Not a production path.

## Outstanding for merge gate

- PR `CI / final-status` on the exact head SHA (`risk: layout-data` requirement).
- Independent audit record (`docs/assessment/pr-<PR>-…md`) authored by a separate session.
- Issue #153 (ZIP `NotReady` diagnostic code observability) remains the separately owned follow-up (AC-150-06).
